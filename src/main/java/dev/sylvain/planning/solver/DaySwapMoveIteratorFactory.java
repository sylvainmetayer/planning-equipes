package dev.sylvain.planning.solver;

import static dev.sylvain.planning.solver.MoveFactorySupport.ANIMATEUR;

import ai.timefold.solver.core.impl.heuristic.selector.move.factory.MoveIteratorFactory;
import ai.timefold.solver.core.impl.score.director.ScoreDirector;
import ai.timefold.solver.core.preview.api.move.Move;
import ai.timefold.solver.core.preview.api.move.builtin.Moves;
import ai.timefold.solver.core.preview.api.neighborhood.stream.dataset.sample.Sample;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.PlafondsLegauxMineurs;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.solver.constraints.LegalConstraints;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.random.RandomGenerator;

/**
 * Swaps two animateurs' days: A's movable seats of a date go to B and B's to
 * A, both working that date, in one move.
 *
 * <p>The rules that count stands, locations or game categories per person and
 * day reward a whole day changing hands, and a one-seat move has to make the
 * plan worse before the whole day has moved. Exchanging the two days keeps
 * the dates each of them works, so the day count, the week and the run of
 * days stay as they were. What each of them does that day changes — and with
 * it the hours of the day, the week's total and the rest around it, which
 * the score judges like any other move.</p>
 *
 * <p>The pair is chosen here rather than drawn and then filtered, since two
 * random blocks of the same date are rarely both eligible — which is what
 * made Timefold's own pillar swap selector unusable (see
 * {@code solverConfig.xml}). Only pairs the score would not refuse on sight
 * are built:</p>
 * <ul>
 * <li>every seat goes to an animateur eligible for it
 * ({@link EligibleAnimateurMoveFilter#isEligible});</li>
 * <li>no seat overlaps a pinned seat its receiver keeps that day — the frozen
 * past and the locks are never handed over, but they occupy their hour;</li>
 * <li>a minor's day, with the seats received, stays under the daily cap
 * {@code dureeQuotidienneMaxMineur} applies, measured as it measures it;</li>
 * <li>the two days differ: two blocks of the same seats (stand, timeslot,
 * hours) would swap nothing.</li>
 * </ul>
 *
 * <p>The block is drawn uniformly among the (animateur, date) blocks of
 * movable seats, and its partner uniformly among the colleagues of that date
 * the four rules above let through. The move itself is Timefold's pillar swap,
 * one value per side.</p>
 *
 * <p>{@code MoveIteratorFactory} lives in {@code core.impl}; the moves it
 * yields are the public preview {@link Move}s. Inventoried in
 * {@code TimefoldInternalApiStructuralTest}.</p>
 */
public final class DaySwapMoveIteratorFactory
        implements MoveIteratorFactory<PlanningEvenement, Move<PlanningEvenement>> {

    /**
     * Seat draws before giving up for this step. A draw spends one whether it
     * lands on an empty seat, on a block turned down to keep the blocks
     * equally likely, or on a block nobody can trade with.
     */
    private static final int DRAWS = 40;
    /** The rule whose daily measure the move checks a minor's day against. */
    private static final String MINOR_DAILY_CAP_RULE = "dureeQuotidienneMaxMineur";

    /** What does not change during a phase; rebuilt at its start. */
    private Layout layout;

    @Override
    public void phaseStarted(ScoreDirector<PlanningEvenement> scoreDirector) {
        layout = null;
    }

    @Override
    public void phaseEnded(ScoreDirector<PlanningEvenement> scoreDirector) {
        layout = null;
    }

    @Override
    public long getSize(ScoreDirector<PlanningEvenement> scoreDirector) {
        return MoveFactorySupport.sizeBound(scoreDirector.getWorkingSolution());
    }

    @Override
    public Iterator<Move<PlanningEvenement>> createOriginalMoveIterator(
            ScoreDirector<PlanningEvenement> scoreDirector) {
        return MoveFactorySupport.bounded(createRandomMoveIterator(scoreDirector, new java.util.Random(0)));
    }

    /**
     * One iterator per step. It ends — {@code hasNext()} false — when
     * {@link #DRAWS} draws found nobody to swap with, which the union reads as
     * "draw from the other selectors this step". {@code hasNext()} therefore
     * has to look ahead: the union asks it of every child when the step
     * starts, and a child that answers true must yield a move.
     */
    @Override
    public Iterator<Move<PlanningEvenement>> createRandomMoveIterator(
            ScoreDirector<PlanningEvenement> scoreDirector, RandomGenerator random) {
        Step step = new Step(layout(scoreDirector.getWorkingSolution()), random);
        return new Iterator<>() {
            private Move<PlanningEvenement> upcoming;
            private boolean exhausted;

            @Override
            public boolean hasNext() {
                if (upcoming == null && !exhausted) {
                    upcoming = step.nextMove();
                    exhausted = upcoming == null;
                }
                return upcoming != null;
            }

            @Override
            public Move<PlanningEvenement> next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                Move<PlanningEvenement> move = upcoming;
                upcoming = null;
                return move;
            }
        };
    }

    private Layout layout(PlanningEvenement solution) {
        if (layout == null || layout.solution != solution) {
            layout = new Layout(solution);
        }
        return layout;
    }

    /**
     * The plan as far as a phase cannot change it: which seats are movable,
     * per date, and the pinned seats each animateur keeps on each date.
     */
    static final class Layout {
        private final PlanningEvenement solution;
        private final List<PosteAffectation> movable = new ArrayList<>();
        private final Map<LocalDate, List<PosteAffectation>> movableOn = new HashMap<>();
        private final Map<Animateur, Map<LocalDate, List<PosteAffectation>>> pinned = new HashMap<>();
        private final ParametresLegaux parametresLegaux;
        private final boolean minorDailyCapOn;

        Layout(PlanningEvenement solution) {
            this.solution = solution;
            this.parametresLegaux = solution.parametresLegaux();
            this.minorDailyCapOn =
                    ConstraintCatalog.isActive(solution.getConstraintsDesactivees(), MINOR_DAILY_CAP_RULE);
            for (PosteAffectation poste : solution.getPostes()) {
                Creneau creneau = poste.getCreneau();
                if (creneau == null) {
                    continue;
                }
                if (!poste.isVerrouille()) {
                    movable.add(poste);
                    movableOn
                            .computeIfAbsent(creneau.getDate(), d -> new ArrayList<>())
                            .add(poste);
                } else if (poste.getAnimateur() != null) {
                    pinned.computeIfAbsent(poste.getAnimateur(), a -> new HashMap<>())
                            .computeIfAbsent(creneau.getDate(), d -> new ArrayList<>())
                            .add(poste);
                }
            }
        }

        private List<PosteAffectation> pinnedOn(Animateur animateur, LocalDate date) {
            return pinned.getOrDefault(animateur, Map.of()).getOrDefault(date, List.of());
        }
    }

    /**
     * One step's draws. Who holds what is read per date, the first time a
     * draw lands on it: every move of a step is evaluated on the same working
     * solution, so what is read stays valid until the step ends.
     */
    static final class Step {
        private final Layout layout;
        private final RandomGenerator random;
        private final Map<LocalDate, Map<Animateur, List<PosteAffectation>>> blocksOn = new HashMap<>();

        Step(Layout layout, RandomGenerator random) {
            this.layout = layout;
            this.random = random;
        }

        /** A swap for a block drawn at random; {@code null} when {@link #DRAWS} draws found none. */
        Move<PlanningEvenement> nextMove() {
            List<PosteAffectation> movable = layout.movable;
            if (movable.isEmpty()) {
                return null;
            }
            for (int i = 0; i < DRAWS; i++) {
                PosteAffectation seat = movable.get(random.nextInt(movable.size()));
                Animateur animateur = seat.getAnimateur();
                if (animateur == null) {
                    continue;
                }
                LocalDate date = seat.getCreneau().getDate();
                Map<Animateur, List<PosteAffectation>> day = blocksOn(date);
                List<PosteAffectation> block = day.get(animateur);
                // A seat drawn lands on a block in proportion to its seats:
                // keeping it one time in its size makes every block equally likely.
                if (random.nextInt(block.size()) != 0) {
                    continue;
                }
                Move<PlanningEvenement> move = swapWithColleague(animateur, date, block, day);
                if (move != null) {
                    return move;
                }
            }
            return null;
        }

        /** The movable seats of a date, per holder, in plan order. */
        private Map<Animateur, List<PosteAffectation>> blocksOn(LocalDate date) {
            return blocksOn.computeIfAbsent(date, d -> {
                Map<Animateur, List<PosteAffectation>> day = new LinkedHashMap<>();
                for (PosteAffectation poste : layout.movableOn.get(d)) {
                    if (poste.getAnimateur() != null) {
                        day.computeIfAbsent(poste.getAnimateur(), a -> new ArrayList<>())
                                .add(poste);
                    }
                }
                return day;
            });
        }

        /**
         * The colleagues of the date visited in a random order — the first one
         * who can trade is then a uniform pick among all who can.
         */
        private Move<PlanningEvenement> swapWithColleague(
                Animateur animateur,
                LocalDate date,
                List<PosteAffectation> block,
                Map<Animateur, List<PosteAffectation>> day) {
            List<Animateur> colleagues = new ArrayList<>(day.keySet());
            int size = colleagues.size();
            for (int i = 0; i < size; i++) {
                Collections.swap(colleagues, i, i + random.nextInt(size - i));
                Animateur colleague = colleagues.get(i);
                if (colleague == animateur) {
                    continue;
                }
                List<PosteAffectation> theirs = day.get(colleague);
                if (!sameSeats(block, theirs) && canTake(colleague, date, block) && canTake(animateur, date, theirs)) {
                    return Moves.pillarSwap(ANIMATEUR, Sample.wrap(block), Sample.wrap(theirs));
                }
            }
            return null;
        }

        /** Whether {@code animateur}, keeping only their pinned seats of {@code date}, can take {@code seats}. */
        private boolean canTake(Animateur animateur, LocalDate date, List<PosteAffectation> seats) {
            ParametresLegaux parametres = layout.parametresLegaux;
            for (PosteAffectation seat : seats) {
                if (!EligibleAnimateurMoveFilter.isEligible(seat, animateur, parametres)) {
                    return false;
                }
            }
            List<PosteAffectation> kept = layout.pinnedOn(animateur, date);
            for (PosteAffectation seat : seats) {
                if (MoveFactorySupport.overlapsAny(seat, kept)) {
                    return false;
                }
            }
            return !layout.minorDailyCapOn
                    || !animateur.isMineurOn(date)
                    || withinMinorDailyCap(animateur, date, seats, kept);
        }

        private boolean withinMinorDailyCap(
                Animateur animateur, LocalDate date, List<PosteAffectation> seats, List<PosteAffectation> kept) {
            List<PosteAffectation> dayAfter = new ArrayList<>(seats.size() + kept.size());
            dayAfter.addAll(seats);
            dayAfter.addAll(kept);
            int cap = PlafondsLegauxMineurs.dureeQuotidienneMaxMinutes(animateur.isUnder16On(date));
            return LegalConstraints.effectiveWorkMineurMinutes(dayAfter, layout.parametresLegaux) <= cap;
        }

        /** The same seats on both sides — stand, timeslot and hours — so the swap would change nothing. */
        private static boolean sameSeats(List<PosteAffectation> left, List<PosteAffectation> right) {
            if (left.size() != right.size()) {
                return false;
            }
            Map<SeatContent, Integer> count = new HashMap<>();
            for (PosteAffectation seat : left) {
                count.merge(SeatContent.of(seat), 1, Integer::sum);
            }
            for (PosteAffectation seat : right) {
                if (count.merge(SeatContent.of(seat), -1, Integer::sum) < 0) {
                    return false;
                }
            }
            return true;
        }
    }

    /** What a seat is, as far as the rules can tell two seats apart. */
    private record SeatContent(Stand stand, Creneau creneau, LocalTime start, LocalTime end) {
        static SeatContent of(PosteAffectation seat) {
            return new SeatContent(
                    seat.getStand(), seat.getCreneau(), seat.heureDebutEffectif(), seat.heureFinEffectif());
        }
    }
}
