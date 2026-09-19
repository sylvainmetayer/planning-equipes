package dev.sylvain.planning.solver;

import ai.timefold.solver.core.impl.heuristic.selector.move.factory.MoveIteratorFactory;
import ai.timefold.solver.core.impl.score.director.ScoreDirector;
import ai.timefold.solver.core.preview.api.domain.metamodel.PlanningSolutionMetaModel;
import ai.timefold.solver.core.preview.api.domain.metamodel.PlanningVariableMetaModel;
import ai.timefold.solver.core.preview.api.move.Move;
import ai.timefold.solver.core.preview.api.move.builtin.Moves;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Fills an empty seat through the week: the animateur who could hold it is
 * free at that hour but already works six days, so the move gives them the
 * seat <em>and</em> hands the seats they held on another day of the same week
 * to colleagues free at those hours — all in one move.
 *
 * <p>This is the chain the local search could not find. Tuesday's montage
 * seats stayed empty while 61 people were free that day, every one of them at
 * six days: taking a Tuesday seat costs a day elsewhere, and the seats of that
 * day have to go to somebody else. A change move sees only the first link
 * (hard-neutral: one seat filled, one week over the cap), and the second link
 * is one specific change among half a million. The ruin-and-recreate move
 * of {@link HoleNeighbourPosteFilter} rebuilds the seats at the <em>hole's
 * hour</em>, which is the right neighbourhood for a chain that runs through
 * the same day and the wrong one for a chain that runs through Monday. On
 * the 2026 edition, thirteen such chains filled all 26 empty seats without
 * breaking a rule, by hand; this move lets the solver do it.</p>
 *
 * <p>The move only guarantees what the score cannot evaluate cheaply — the
 * colleague is eligible for the seat (skills, availability, the minors'
 * rules of {@link EligibleAnimateurMoveFilter}) and not already busy at that
 * hour. Everything else (hours, rests, meal breaks, the week's caps) is left
 * to the score: late acceptance decides, the move only makes the chain
 * reachable. When the plan has no empty seat, or no chain can be built for the
 * one drawn, it degrades to a plain change on the seat, so the union selector
 * always gets a move.</p>
 *
 * <p>{@code MoveIteratorFactory} lives in {@code core.impl}; the moves it
 * yields are the public preview {@link Move}s, composed from built-in change
 * moves, so no move logic depends on Timefold internals. Inventoried in
 * {@code TimefoldInternalApiStructuralTest}.</p>
 */
public final class WeekRelocationMoveIteratorFactory
        implements MoveIteratorFactory<PlanningEvenement, Move<PlanningEvenement>> {

    /** Candidates tried for the hole before giving up on a chain and falling back to a plain change. */
    private static final int CANDIDATE_TRIES = 12;
    /** How many moves the original-order iterator yields: bounded, since the neighbourhood is combinatorial. */
    private static final int ORIGINAL_MOVES = 1_000;

    private static final PlanningVariableMetaModel<PlanningEvenement, PosteAffectation, Animateur> ANIMATEUR =
            PlanningSolutionMetaModel.of(PlanningEvenement.class, PosteAffectation.class)
                    .genuineEntity(PosteAffectation.class)
                    .basicVariable("animateur", Animateur.class);

    @Override
    public long getSize(ScoreDirector<PlanningEvenement> scoreDirector) {
        PlanningEvenement solution = scoreDirector.getWorkingSolution();
        return (long) solution.getPostes().size()
                * Math.max(1, solution.getAnimateurs().size());
    }

    @Override
    public Iterator<Move<PlanningEvenement>> createOriginalMoveIterator(
            ScoreDirector<PlanningEvenement> scoreDirector) {
        Iterator<Move<PlanningEvenement>> random = createRandomMoveIterator(scoreDirector, new java.util.Random(0));
        return new Iterator<>() {
            private int yielded;

            @Override
            public boolean hasNext() {
                return yielded < ORIGINAL_MOVES;
            }

            @Override
            public Move<PlanningEvenement> next() {
                yielded++;
                return random.next();
            }
        };
    }

    @Override
    public Iterator<Move<PlanningEvenement>> createRandomMoveIterator(
            ScoreDirector<PlanningEvenement> scoreDirector, RandomGenerator random) {
        // One index per step: the candidates of a step are all evaluated on the
        // same working solution, so the index built here stays valid for them.
        Index index = new Index(scoreDirector.getWorkingSolution());
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return true;
            }

            @Override
            public Move<PlanningEvenement> next() {
                return index.nextMove(random);
            }
        };
    }

    /** The plan read once: who holds what, per day, and where the holes are. */
    static final class Index {
        private final PlanningEvenement solution;
        private final List<PosteAffectation> holes = new ArrayList<>();
        private final List<PosteAffectation> movable = new ArrayList<>();
        private final Map<Animateur, Map<LocalDate, List<PosteAffectation>>> seats = new HashMap<>();
        private final boolean pauseSurPoste;

        Index(PlanningEvenement solution) {
            this.solution = solution;
            this.pauseSurPoste = solution.pauseSurPosteActive();
            for (PosteAffectation poste : solution.getPostes()) {
                // A renfort is out of this factory altogether (issue #505).
                // It plays a whole week-long chain to fill one hole, and this
                // phase only chases feasibility: an empty renfort is not a
                // hole, and moving somebody onto one costs a chain to gain a
                // soft point the next phase can gain for free.
                if (poste.isVerrouille() || poste.getCreneau() == null || poste.isOptionnel()) {
                    continue;
                }
                movable.add(poste);
                if (poste.getAnimateur() == null) {
                    holes.add(poste);
                } else {
                    seats.computeIfAbsent(poste.getAnimateur(), a -> new HashMap<>())
                            .computeIfAbsent(poste.getCreneau().getDate(), d -> new ArrayList<>())
                            .add(poste);
                }
            }
        }

        Move<PlanningEvenement> nextMove(RandomGenerator random) {
            if (holes.isEmpty()) {
                return plainChange(random);
            }
            PosteAffectation hole = holes.get(random.nextInt(holes.size()));
            List<Animateur> candidates = new ArrayList<>();
            for (Animateur animateur : solution.getAnimateurs()) {
                if (EligibleAnimateurMoveFilter.isEligible(hole, animateur, pauseSurPoste) && free(animateur, hole)) {
                    candidates.add(animateur);
                }
            }
            if (candidates.isEmpty()) {
                return plainChange(random);
            }
            Collections.shuffle(candidates, new java.util.Random(random.nextLong()));
            for (Animateur candidate : candidates.subList(0, Math.min(CANDIDATE_TRIES, candidates.size()))) {
                Move<PlanningEvenement> chain = chain(hole, candidate, random);
                if (chain != null) {
                    return chain;
                }
            }
            // No day could be handed over: the plain fill, which the score will
            // refuse if the week is at its cap — no worse than the change selector.
            return Moves.change(ANIMATEUR, hole, candidates.get(0));
        }

        /**
         * The seat to the candidate, and one of the candidate's other days of
         * the same week to colleagues; {@code null} when no day can be handed over.
         */
        private Move<PlanningEvenement> chain(PosteAffectation hole, Animateur candidate, RandomGenerator random) {
            LocalDate date = hole.getCreneau().getDate();
            Map<LocalDate, List<PosteAffectation>> days = seats.getOrDefault(candidate, Map.of());
            List<LocalDate> sameWeek = new ArrayList<>();
            for (LocalDate other : days.keySet()) {
                if (!other.equals(date) && sameWeek(other, date)) {
                    sameWeek.add(other);
                }
            }
            if (sameWeek.isEmpty()) {
                // Not at the cap for this week: the plain fill is the whole chain.
                return Moves.change(ANIMATEUR, hole, candidate);
            }
            Collections.shuffle(sameWeek, new java.util.Random(random.nextLong()));
            for (LocalDate released : sameWeek) {
                List<Move<PlanningEvenement>> links = new ArrayList<>();
                links.add(Moves.change(ANIMATEUR, hole, candidate));
                List<Animateur> taken = new ArrayList<>();
                boolean complete = true;
                for (PosteAffectation seat : days.get(released)) {
                    Animateur taker = taker(seat, candidate, taken, random);
                    if (taker == null) {
                        complete = false;
                        break;
                    }
                    taken.add(taker);
                    links.add(Moves.change(ANIMATEUR, seat, taker));
                }
                if (complete) {
                    return Moves.compose(links);
                }
            }
            return null;
        }

        /**
         * A colleague for a released seat: eligible, free at its hour, and by
         * preference already working that day — a new day for them would only
         * move the cap problem, though the score is the judge of that too.
         */
        private Animateur taker(
                PosteAffectation seat, Animateur leaving, List<Animateur> taken, RandomGenerator random) {
            List<Animateur> animateurs = solution.getAnimateurs();
            int start = random.nextInt(animateurs.size());
            Animateur fallback = null;
            for (int i = 0; i < animateurs.size(); i++) {
                Animateur animateur = animateurs.get((start + i) % animateurs.size());
                if (animateur.equals(leaving) || taken.contains(animateur)) {
                    continue;
                }
                if (!EligibleAnimateurMoveFilter.isEligible(seat, animateur, pauseSurPoste) || !free(animateur, seat)) {
                    continue;
                }
                if (seats.getOrDefault(animateur, Map.of())
                        .containsKey(seat.getCreneau().getDate())) {
                    return animateur;
                }
                if (fallback == null) {
                    fallback = animateur;
                }
            }
            return fallback;
        }

        /** Not holding a seat that overlaps this one in time, on its day. */
        private boolean free(Animateur animateur, PosteAffectation seat) {
            List<PosteAffectation> held = seats.getOrDefault(animateur, Map.of())
                    .getOrDefault(seat.getCreneau().getDate(), List.of());
            int debut = minutes(seat.heureDebutEffectif());
            int fin = debut + seat.getDureeEffectiveMinutes();
            for (PosteAffectation other : held) {
                int otherDebut = minutes(other.heureDebutEffectif());
                int otherFin = otherDebut + other.getDureeEffectiveMinutes();
                if (debut < otherFin && otherDebut < fin) {
                    return false;
                }
            }
            return true;
        }

        private Move<PlanningEvenement> plainChange(RandomGenerator random) {
            PosteAffectation poste = movable.get(random.nextInt(movable.size()));
            List<Animateur> animateurs = solution.getAnimateurs();
            return Moves.change(ANIMATEUR, poste, animateurs.get(random.nextInt(animateurs.size())));
        }

        private static boolean sameWeek(LocalDate a, LocalDate b) {
            return a.get(IsoFields.WEEK_BASED_YEAR) == b.get(IsoFields.WEEK_BASED_YEAR)
                    && a.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) == b.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        }

        private static int minutes(java.time.LocalTime time) {
            return time == null ? 0 : time.toSecondOfDay() / 60;
        }
    }
}
