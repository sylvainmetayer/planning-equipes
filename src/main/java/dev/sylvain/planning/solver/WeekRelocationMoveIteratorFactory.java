package dev.sylvain.planning.solver;

import ai.timefold.solver.core.impl.heuristic.selector.move.factory.MoveIteratorFactory;
import ai.timefold.solver.core.impl.score.director.ScoreDirector;
import ai.timefold.solver.core.preview.api.domain.metamodel.PlanningSolutionMetaModel;
import ai.timefold.solver.core.preview.api.domain.metamodel.PlanningVariableMetaModel;
import ai.timefold.solver.core.preview.api.move.Move;
import ai.timefold.solver.core.preview.api.move.builtin.Moves;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlafondsLegauxMajeurs;
import dev.sylvain.planning.domain.PlafondsLegauxMineurs;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * <p><b>The run of days, when the edition holds it hard.</b> With
 * {@code maxJoursConsecutifsTravaillesDur} on, the day that has to go is not
 * only one of the same ISO week: a Monday hole taken by someone who worked
 * Wednesday to Sunday makes a seventh day in a row, and only a day of that run
 * breaks it — a day the ISO week does not reach. The chain then releases a day
 * that brings both the week and the run back under their caps. And a run
 * already too long, with no hole to fill, gets the second half of the chain on
 * its own: one of its days handed to colleagues. At a tight cap the plan is
 * short of person-days rather than hours, so a day is only ever handed to
 * people already working it, and a day held in pieces can be regrouped onto
 * them — the person-day it frees is what a later chain spends on a hole
 * (ADR 0049). With the rule off — the default — and no pinned seat, the factory
 * yields the moves it always did, draw for draw. Pinned seats (the frozen past,
 * the locks) are never handed over, but they occupy their hour and count in a
 * run exactly as the rules count them.</p>
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
    /** The same bound under the run rule, where a chain whose day only goes to people already there is rarer. */
    private static final int CANDIDATE_TRIES_UNDER_RUN_CAP = 3 * CANDIDATE_TRIES;
    /** Days drawn before giving up on a regrouping: most full days have nobody free to absorb them. */
    private static final int CONSOLIDATION_TRIES = 50;
    /** The hard form of the run rule, the only one the move reads (ADR 0045). */
    private static final String HARD_RUN_RULE = "maxJoursConsecutifsTravaillesDur";
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

    /**
     * The run cap the move works towards: the edition's
     * {@code joursConsecutifsMax} when {@code maxJoursConsecutifsTravaillesDur}
     * is active — read as the score reads it, toggle first, catalogue default
     * otherwise — and {@code 0} when it is not: the rule ships off (ADR 0045), and
     * an edition that leaves it off keeps the move it had.
     */
    public static int hardRunCap(PlanningEvenement solution) {
        boolean on = ConstraintCatalog.isActive(solution.getConstraintsDesactivees(), HARD_RUN_RULE);
        List<ParametresQualite> qualite = solution.getParametresQualite();
        if (!on || qualite == null || qualite.isEmpty() || qualite.get(0) == null) {
            return 0;
        }
        return qualite.get(0).joursConsecutifsMax();
    }

    /** The plan read once: who holds what, per day, and where the holes are. */
    static final class Index {
        private final PlanningEvenement solution;
        private final List<PosteAffectation> holes = new ArrayList<>();
        private final List<PosteAffectation> movable = new ArrayList<>();
        /** The seats the move may reassign, per animateur and day. */
        private final Map<Animateur, Map<LocalDate, List<PosteAffectation>>> seats = new HashMap<>();
        /**
         * Every seat held, pinned ones included — the frozen past, the locks.
         * The move never reassigns those, but they occupy their hour and count
         * in a run exactly as the rules count them.
         */
        private final Map<Animateur, Map<LocalDate, List<PosteAffectation>>> held = new HashMap<>();
        /** Days on which an animateur holds a pinned seat: such a day cannot be freed. */
        private final Map<Animateur, Set<LocalDate>> pinnedDays = new HashMap<>();
        /** Who works each day, pinned seats included. */
        private final Map<LocalDate, List<Animateur>> workingOn = new HashMap<>();

        private final ParametresLegaux parametresLegaux;
        /** The run cap when the hard form of the rule is on, else {@code 0}: runs are then not the move's business. */
        private final int runCap;
        /** Animateurs whose longest run exceeds {@link #runCap}. */
        private final List<Animateur> overlongRuns = new ArrayList<>();

        Index(PlanningEvenement solution) {
            this.solution = solution;
            this.parametresLegaux = solution.parametresLegaux();
            this.runCap = hardRunCap(solution);
            for (PosteAffectation poste : solution.getPostes()) {
                if (poste.getCreneau() == null) {
                    continue;
                }
                Animateur animateur = poste.getAnimateur();
                LocalDate date = poste.getCreneau().getDate();
                if (animateur != null) {
                    List<PosteAffectation> day = held.computeIfAbsent(animateur, a -> new HashMap<>())
                            .computeIfAbsent(date, d -> {
                                workingOn
                                        .computeIfAbsent(d, x -> new ArrayList<>())
                                        .add(animateur);
                                return new ArrayList<>();
                            });
                    day.add(poste);
                }
                if (poste.isVerrouille()) {
                    if (animateur != null) {
                        pinnedDays
                                .computeIfAbsent(animateur, a -> new HashSet<>())
                                .add(date);
                    }
                    continue;
                }
                movable.add(poste);
                if (animateur == null) {
                    holes.add(poste);
                } else {
                    seats.computeIfAbsent(animateur, a -> new HashMap<>())
                            .computeIfAbsent(date, d -> new ArrayList<>())
                            .add(poste);
                }
            }
            if (runCap > 0) {
                held.forEach((animateur, days) -> {
                    if (seats.containsKey(animateur) && longestRun(sorted(days.keySet()), null, null) > runCap) {
                        overlongRuns.add(animateur);
                    }
                });
            }
        }

        Move<PlanningEvenement> nextMove(RandomGenerator random) {
            if (runCap > 0) {
                int draw = random.nextInt(3);
                Move<PlanningEvenement> day = null;
                if (draw == 0 && !overlongRuns.isEmpty()) {
                    day = releaseFromRun(random);
                } else if (draw == 1 || holes.isEmpty()) {
                    day = consolidate(random);
                }
                if (day != null) {
                    return day;
                }
            }
            if (holes.isEmpty()) {
                return plainChange(random);
            }
            PosteAffectation hole = holes.get(random.nextInt(holes.size()));
            List<Animateur> candidates = new ArrayList<>();
            for (Animateur animateur : solution.getAnimateurs()) {
                if (EligibleAnimateurMoveFilter.isEligible(hole, animateur, parametresLegaux)
                        && free(animateur, hole)) {
                    candidates.add(animateur);
                }
            }
            if (candidates.isEmpty()) {
                return plainChange(random);
            }
            Collections.shuffle(candidates, new java.util.Random(random.nextLong()));
            // Under the run rule a chain that hands its day only to people already
            // there is rarer: the index looks a little further than a dozen
            // candidates. Bounded all the same — each try scans the colleagues of
            // every day it could release, on the solver thread.
            int tries = Math.min(runCap > 0 ? CANDIDATE_TRIES_UNDER_RUN_CAP : CANDIDATE_TRIES, candidates.size());
            for (Animateur candidate : candidates.subList(0, tries)) {
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
         * the same week — or, under the run rule, of the run the seat would
         * lengthen — to colleagues; {@code null} when no day can be handed over.
         */
        private Move<PlanningEvenement> chain(PosteAffectation hole, Animateur candidate, RandomGenerator random) {
            LocalDate date = hole.getCreneau().getDate();
            Map<LocalDate, List<PosteAffectation>> days = seats.getOrDefault(candidate, Map.of());
            List<LocalDate> released;
            if (runCap > 0) {
                released = releasedUnderRunCap(candidate, date);
                if (released == null) {
                    // Neither the week nor the run goes over: the plain fill is the whole chain.
                    return Moves.change(ANIMATEUR, hole, candidate);
                }
            } else {
                released = new ArrayList<>();
                for (LocalDate other : days.keySet()) {
                    if (!other.equals(date) && sameWeek(other, date)) {
                        released.add(other);
                    }
                }
                if (released.isEmpty()) {
                    // Not at the cap for this week: the plain fill is the whole chain.
                    return Moves.change(ANIMATEUR, hole, candidate);
                }
            }
            Collections.shuffle(released, new java.util.Random(random.nextLong()));
            for (LocalDate day : released) {
                List<Move<PlanningEvenement>> links = new ArrayList<>();
                links.add(Moves.change(ANIMATEUR, hole, candidate));
                if (handOver(days.get(day), candidate, links, random)) {
                    return Moves.compose(links);
                }
            }
            return null;
        }

        /**
         * Under the run rule, the days the candidate could give up so that
         * taking {@code date} keeps both the week and the run under their caps;
         * {@code null} when taking it breaks neither, an empty list when no single
         * day repairs what it breaks — the chain is then not proposed at all,
         * rather than a release that leaves the run as long as it was.
         */
        private List<LocalDate> releasedUnderRunCap(Animateur candidate, LocalDate date) {
            List<LocalDate> worked =
                    sorted(held.getOrDefault(candidate, Map.of()).keySet());
            if (worked.contains(date)) {
                return null;
            }
            long week = worked.stream().filter(day -> sameWeek(day, date)).count() + 1;
            boolean weekOver = week > weekCap(candidate, date);
            boolean runOver = longestRun(worked, null, date) > runCap;
            if (!weekOver && !runOver) {
                return null;
            }
            return releasable(candidate, worked, date, freeable(candidate));
        }

        /**
         * Among {@code choices}, the days whose release leaves the run under the
         * cap and, when a day {@code added} is taken on, its week under the
         * weekly cap; {@code worked} is every day worked, pinned ones included.
         */
        private List<LocalDate> releasable(
                Animateur animateur, List<LocalDate> worked, LocalDate added, List<LocalDate> choices) {
            List<LocalDate> fits = new ArrayList<>();
            for (LocalDate day : choices) {
                if (day.equals(added) || longestRun(worked, day, added) > runCap) {
                    continue;
                }
                if (added != null) {
                    long week = worked.stream()
                                    .filter(d -> !d.equals(day) && sameWeek(d, added))
                                    .count()
                            + 1;
                    if (week > weekCap(animateur, added)) {
                        continue;
                    }
                }
                fits.add(day);
            }
            return fits;
        }

        /** The days an animateur holds only movable seats on: the ones that can be freed entirely. */
        private List<LocalDate> freeable(Animateur animateur) {
            Set<LocalDate> pinned = pinnedDays.getOrDefault(animateur, Set.of());
            List<LocalDate> days = new ArrayList<>();
            for (LocalDate day : seats.getOrDefault(animateur, Map.of()).keySet()) {
                if (!pinned.contains(day)) {
                    days.add(day);
                }
            }
            Collections.sort(days);
            return days;
        }

        /**
         * Six days a week (L3132-1); five for a minor, whose two rest days in the
         * week (L3164-2) leave no more.
         */
        private static int weekCap(Animateur animateur, LocalDate date) {
            return animateur.isMineurOn(date)
                    ? 7 - PlafondsLegauxMineurs.JOURS_REPOS_CONSECUTIFS_PAR_SEMAINE
                    : PlafondsLegauxMajeurs.JOURS_TRAVAILLES_MAX_PAR_SEMAINE;
        }

        /**
         * A run already over the cap, no hole involved: one of its days handed
         * to colleagues free at those hours. Only a day of the over-long run is
         * released — a day elsewhere would leave it as long as it was. {@code
         * null} when none can be.
         */
        private Move<PlanningEvenement> releaseFromRun(RandomGenerator random) {
            Animateur animateur = overlongRuns.get(random.nextInt(overlongRuns.size()));
            List<LocalDate> worked = sorted(held.get(animateur).keySet());
            Set<LocalDate> inOverlongRuns = new HashSet<>(daysOfRunsLongerThan(worked, runCap));
            List<LocalDate> choices = new ArrayList<>();
            for (LocalDate day : freeable(animateur)) {
                if (inOverlongRuns.contains(day)) {
                    choices.add(day);
                }
            }
            List<LocalDate> released = releasable(animateur, worked, null, choices);
            if (released.isEmpty()) {
                // No single day brings the run under the cap: any day of it shortens it.
                released = choices;
            }
            Collections.shuffle(released, new java.util.Random(random.nextLong()));
            Map<LocalDate, List<PosteAffectation>> days = seats.get(animateur);
            for (LocalDate day : released) {
                List<Move<PlanningEvenement>> links = new ArrayList<>();
                if (handOver(days.get(day), animateur, links, random)) {
                    return Moves.compose(links);
                }
            }
            return null;
        }

        /**
         * One person's day handed to colleagues already working it and free at
         * those hours: the day is covered by the same seats, one person-day
         * fewer. Under a run cap the event is short of person-days rather than
         * of hours — a montage day held by two people in half-days costs two of
         * them — and a day freed here is what a later chain spends on a hole.
         * A day with a pinned seat is not drawn: it stays worked whatever moves.
         * {@code null} when no day drawn can be regrouped.
         */
        private Move<PlanningEvenement> consolidate(RandomGenerator random) {
            List<Animateur> animateurs = solution.getAnimateurs();
            for (int attempt = 0; attempt < CONSOLIDATION_TRIES; attempt++) {
                Animateur animateur = animateurs.get(random.nextInt(animateurs.size()));
                List<LocalDate> dates = freeable(animateur);
                if (dates.isEmpty()) {
                    continue;
                }
                LocalDate day = dates.get(random.nextInt(dates.size()));
                List<Move<PlanningEvenement>> links = new ArrayList<>();
                if (handOver(seats.get(animateur).get(day), animateur, links, random)) {
                    return Moves.compose(links);
                }
            }
            return null;
        }

        /**
         * Every seat of a released day to a colleague; {@code false} when one of
         * them finds nobody. Under the run rule one colleague may take several
         * of the day's seats, as long as they do not overlap — that is how a day
         * held in pieces is regrouped onto one person.
         */
        private boolean handOver(
                List<PosteAffectation> daySeats,
                Animateur leaving,
                List<Move<PlanningEvenement>> links,
                RandomGenerator random) {
            Map<Animateur, List<PosteAffectation>> given = new HashMap<>();
            for (PosteAffectation seat : daySeats) {
                Animateur taker = taker(seat, leaving, given, random);
                if (taker == null) {
                    return false;
                }
                given.computeIfAbsent(taker, a -> new ArrayList<>()).add(seat);
                links.add(Moves.change(ANIMATEUR, seat, taker));
            }
            return true;
        }

        /**
         * A colleague for a released seat: eligible, free at its hour, and by
         * preference already working that day — a new day for them would only
         * move the cap problem, though the score is the judge of that too. Under
         * the hard run rule the preference is a requirement, so only the people
         * working that day are scanned.
         */
        private Animateur taker(
                PosteAffectation seat,
                Animateur leaving,
                Map<Animateur, List<PosteAffectation>> given,
                RandomGenerator random) {
            LocalDate date = seat.getCreneau().getDate();
            List<Animateur> animateurs =
                    runCap > 0 ? workingOn.getOrDefault(date, List.of()) : solution.getAnimateurs();
            if (animateurs.isEmpty()) {
                return null;
            }
            int start = random.nextInt(animateurs.size());
            Animateur fallback = null;
            for (int i = 0; i < animateurs.size(); i++) {
                Animateur animateur = animateurs.get((start + i) % animateurs.size());
                if (animateur.equals(leaving)) {
                    continue;
                }
                List<PosteAffectation> already = given.get(animateur);
                if (already != null && (runCap == 0 || overlapsAny(seat, already))) {
                    continue;
                }
                if (!EligibleAnimateurMoveFilter.isEligible(seat, animateur, parametresLegaux)
                        || !free(animateur, seat)) {
                    continue;
                }
                if (held.getOrDefault(animateur, Map.of()).containsKey(date)) {
                    return animateur;
                }
                if (fallback == null && runCap == 0) {
                    fallback = animateur;
                }
            }
            return fallback;
        }

        /** Not holding a seat — pinned or not — that overlaps this one in time, on its day. */
        private boolean free(Animateur animateur, PosteAffectation seat) {
            List<PosteAffectation> day = held.getOrDefault(animateur, Map.of())
                    .getOrDefault(seat.getCreneau().getDate(), List.of());
            return !overlapsAny(seat, day);
        }

        private static boolean overlapsAny(PosteAffectation seat, List<PosteAffectation> others) {
            int debut = minutes(seat.heureDebutEffectif());
            int fin = debut + seat.getDureeEffectiveMinutes();
            for (PosteAffectation other : others) {
                int otherDebut = minutes(other.heureDebutEffectif());
                int otherFin = otherDebut + other.getDureeEffectiveMinutes();
                if (debut < otherFin && otherDebut < fin) {
                    return true;
                }
            }
            return false;
        }

        private Move<PlanningEvenement> plainChange(RandomGenerator random) {
            PosteAffectation poste = movable.get(random.nextInt(movable.size()));
            List<Animateur> animateurs = solution.getAnimateurs();
            return Moves.change(ANIMATEUR, poste, animateurs.get(random.nextInt(animateurs.size())));
        }

        /**
         * Longest run of consecutive dates in {@code worked} (sorted), with
         * {@code removed} taken out and {@code added} put in; either may be
         * {@code null}.
         */
        static int longestRun(List<LocalDate> worked, LocalDate removed, LocalDate added) {
            int longest = 0;
            for (List<LocalDate> run : runs(worked, removed, added)) {
                longest = Math.max(longest, run.size());
            }
            return longest;
        }

        /** The days of the runs longer than {@code cap} in {@code worked} (sorted). */
        static List<LocalDate> daysOfRunsLongerThan(List<LocalDate> worked, int cap) {
            List<LocalDate> days = new ArrayList<>();
            for (List<LocalDate> run : runs(worked, null, null)) {
                if (run.size() > cap) {
                    days.addAll(run);
                }
            }
            return days;
        }

        private static List<List<LocalDate>> runs(List<LocalDate> worked, LocalDate removed, LocalDate added) {
            List<LocalDate> days = new ArrayList<>(worked.size() + 1);
            boolean addedPlaced = added == null || worked.contains(added);
            for (LocalDate day : worked) {
                if (!addedPlaced && added.isBefore(day)) {
                    days.add(added);
                    addedPlaced = true;
                }
                if (!day.equals(removed)) {
                    days.add(day);
                }
            }
            if (!addedPlaced) {
                days.add(added);
            }
            List<List<LocalDate>> runs = new ArrayList<>();
            List<LocalDate> current = new ArrayList<>();
            for (LocalDate day : days) {
                if (!current.isEmpty() && ChronoUnit.DAYS.between(current.getLast(), day) != 1) {
                    runs.add(current);
                    current = new ArrayList<>();
                }
                current.add(day);
            }
            if (!current.isEmpty()) {
                runs.add(current);
            }
            return runs;
        }

        private static List<LocalDate> sorted(Set<LocalDate> days) {
            List<LocalDate> list = new ArrayList<>(days);
            Collections.sort(list);
            return list;
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
