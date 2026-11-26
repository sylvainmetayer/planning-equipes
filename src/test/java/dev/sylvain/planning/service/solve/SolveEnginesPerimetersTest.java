package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import dev.sylvain.planning.domain.AffectationPubliee;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PastHorizon;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.TypeVerrouillage;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * What a solve is handed besides an empty grid — a perimeter to re-open, a
 * plan to start from, locks, a past already lived, a published plan — reaches
 * both engines through the same prepared problem, and both must honour it the
 * same way: <b>a pinned seat never moves</b>, the search starts from the plan
 * in place, and the plan that comes out is the one the constraint provider
 * scores.
 *
 * <p>The problems are built with the very pieces {@link ProblemBuilder} runs
 * ({@code figerPostesIncremental}, {@code reamorcerDepuisAffectations},
 * {@code applyVerrouillages}, {@link FrozenPast}) from a plan solved once,
 * then solved through {@link SolveRunner#solvePrepared} — the seam where the
 * engine is chosen — once per engine. No database.</p>
 */
class SolveEnginesPerimetersTest {

    private static final String SCENARIO = "gamme-12-7j-10stands-24animateurs-semaine-complete-ferie.yaml";

    /** The plan every case starts from, as the persistence would hand it back: holders by stand × timeslot. */
    private static Map<String, List<String>> persisted;

    private static List<LocalDate> days;

    @BeforeAll
    static void solveThePlanInPlace() {
        HandSearchFixtures.Loaded base = HandSearchFixtures.load(SCENARIO);
        assertThat(HandSearchFixtures.search(base.problem(), 2, 0).isFeasible()).isTrue();
        persisted = holdersByCell(base.problem());
        days = base.problem().getPostes().stream()
                .map(poste -> poste.getCreneau().getDate())
                .distinct()
                .sorted()
                .toList();
    }

    @ParameterizedTest
    @EnumSource(SolverEngine.class)
    void anIncrementalSolveLeavesEverySeatOutsideItsPerimeterWhereItWas(SolverEngine engine) {
        HandSearchFixtures.Loaded loaded = HandSearchFixtures.load(SCENARIO);
        PlanningEvenement problem = loaded.problem();
        ReplanificationScope oneDay = new ReplanificationScope(Set.of(), Set.of(days.get(3)), Set.of());
        ProblemBuilder.StatistiquesIncremental built = ProblemBuilder.figerPostesIncremental(
                problem.getPostes(), problem.getAnimateurs(), persisted, oneDay, problem.getContraintesAdHoc());
        assertThat(built.postesFiges()).isPositive();
        assertThat(built.postesLiberesManuellement()).isPositive();
        Map<String, String> pinned = pinnedHolders(problem);
        // Read off the dates, not off what the builder says it pinned: the perimeter is the re-opened day.
        assertThat(pinned.keySet())
                .isEqualTo(problem.getPostes().stream()
                        .filter(poste -> !poste.getCreneau().getDate().equals(days.get(3)))
                        .map(PosteAffectation::getId)
                        .collect(Collectors.toSet()));

        PlanningEvenement solved = solve(engine, loaded, 2);

        assertThat(pinnedHolders(solved)).isEqualTo(pinned);
        assertSolvedAndRefereed(loaded, solved);
    }

    @ParameterizedTest
    @EnumSource(SolverEngine.class)
    void anIncrementalSolveRefillsWhatAFreshUnavailabilityFreedAndNothingElse(SolverEngine engine) {
        HandSearchFixtures.Loaded loaded = HandSearchFixtures.load(SCENARIO);
        PlanningEvenement problem = loaded.problem();
        LocalDate day = days.get(2);
        String absentId = persisted.entrySet().stream()
                .filter(cell -> onDay(problem, cell.getKey(), day))
                .map(cell -> cell.getValue().get(0))
                .findFirst()
                .orElseThrow();
        Animateur absent = problem.getAnimateurs().stream()
                .filter(animateur -> animateur.getId().equals(absentId))
                .findFirst()
                .orElseThrow();
        absent.getJoursIndisponibles().add(day);
        ProblemBuilder.StatistiquesIncremental built = ProblemBuilder.figerPostesIncremental(
                problem.getPostes(),
                problem.getAnimateurs(),
                persisted,
                ReplanificationScope.automatic(),
                problem.getContraintesAdHoc());
        assertThat(built.postesLiberes()).isPositive();
        Map<String, String> pinned = pinnedHolders(problem);

        PlanningEvenement solved = solve(engine, loaded, 2);

        assertThat(pinnedHolders(solved)).isEqualTo(pinned);
        assertThat(solved.getPostes())
                .noneMatch(poste -> absent.equals(poste.getAnimateur())
                        && poste.getCreneau().getDate().equals(day));
        assertSolvedAndRefereed(loaded, solved);
    }

    @ParameterizedTest
    @EnumSource(SolverEngine.class)
    void aReseededSolveStartsFromThePlanInPlace(SolverEngine engine) {
        HandSearchFixtures.Loaded loaded = HandSearchFixtures.load(SCENARIO);
        PlanningEvenement problem = loaded.problem();
        int[] seededAndFreed = ProblemBuilder.reamorcerDepuisAffectations(
                problem.getPostes(), problem.getAnimateurs(), persisted, problem.getContraintesAdHoc());
        assertThat(seededAndFreed[0]).isEqualTo(problem.getPostes().size());
        HardMediumSoftScore start = loaded.configuration().solutionManager().update(problem);
        assertThat(start.isFeasible()).isTrue();

        PlanningEvenement solved = solve(engine, loaded, 2);

        // Never worse than where it started: the plan in place is the first best plan.
        assertThat(solved.getScore()).isGreaterThanOrEqualTo(start);
        assertSolvedAndRefereed(loaded, solved);
    }

    @ParameterizedTest
    @EnumSource(SolverEngine.class)
    void lockedSeatsKeepTheirHolderWhileTheRestIsSolvedAroundThem(SolverEngine engine) {
        HandSearchFixtures.Loaded loaded = HandSearchFixtures.load(SCENARIO);
        PlanningEvenement problem = loaded.problem();
        VerrouillagePlanning day = new VerrouillagePlanning("lock-day", TypeVerrouillage.JOUR);
        day.setJour(days.get(1));
        VerrouillagePlanning stand = new VerrouillagePlanning("lock-stand", TypeVerrouillage.STAND_JOUR);
        stand.setStandId(problem.getPostes().get(0).getStand().getId());
        stand.setJour(problem.getPostes().get(0).getCreneau().getDate());
        List<VerrouillagePlanning> locks = List.of(day, stand);
        ProblemBuilder.applyVerrouillages(problem.getPostes(), problem.getAnimateurs(), locks, persisted);
        problem.setVerrouillages(locks);
        Map<String, String> pinned = pinnedHolders(problem);
        assertThat(pinned).isNotEmpty().doesNotContainValue(null);
        assertThat(problem.getPostes()).anyMatch(poste -> poste.getAnimateur() == null);

        PlanningEvenement solved = solve(engine, loaded, 2);

        assertThat(pinnedHolders(solved)).isEqualTo(pinned);
        assertSolvedAndRefereed(loaded, solved);
    }

    @ParameterizedTest
    @EnumSource(SolverEngine.class)
    void theDaysAlreadyLivedStayAsTheyWere(SolverEngine engine) {
        HandSearchFixtures.Loaded loaded = HandSearchFixtures.load(SCENARIO);
        PlanningEvenement problem = loaded.problem();
        ProblemBuilder.reamorcerDepuisAffectations(
                problem.getPostes(), problem.getAnimateurs(), persisted, problem.getContraintesAdHoc());
        PastHorizon noonOnTheThirdDay = new PastHorizon(days.get(2), LocalTime.NOON);
        assertThat(FrozenPast.mark(problem.getPostes(), noonOnTheThirdDay)).isPositive();
        FrozenPast.pin(problem.getPostes());
        problem.setPastHorizon(noonOnTheThirdDay);
        Map<String, String> pinned = pinnedHolders(problem);

        PlanningEvenement solved = solve(engine, loaded, 2);

        assertThat(pinnedHolders(solved)).isEqualTo(pinned);
        assertThat(solved.getPostes()).filteredOn(PosteAffectation::isPasse).isNotEmpty();
        assertSolvedAndRefereed(loaded, solved);
    }

    @ParameterizedTest
    @EnumSource(SolverEngine.class)
    void aPublishedPlanWeighsOnTheSolveThatStartsFromIt(SolverEngine engine) {
        HandSearchFixtures.Loaded loaded = HandSearchFixtures.load(SCENARIO);
        PlanningEvenement problem = loaded.problem();
        ProblemBuilder.reamorcerDepuisAffectations(
                problem.getPostes(), problem.getAnimateurs(), persisted, problem.getContraintesAdHoc());
        problem.setAffectationsPubliees(publishedFrom(problem));
        HardMediumSoftScore start = loaded.configuration().solutionManager().update(problem);

        PlanningEvenement solved = solve(engine, loaded, 2);

        assertThat(solved.getScore()).isGreaterThanOrEqualTo(start);
        // The plan in place is the published one: leaving it must have paid for itself.
        // Neither engine moves a seat here today; without the published plan the direct one moves most of
        // them (the reseeded line of the full-size comparison). One in twenty leaves room for a move that pays.
        assertThat(FeasibilityFirstSolve.publishedSeatsChanged(solved))
                .isLessThanOrEqualTo(solved.getPostes().size() / 20);
        assertSolvedAndRefereed(loaded, solved);
    }

    /**
     * The same perimeters on a full-size grid, both engines side by side: the
     * lines it prints are the comparison {@code site/src/docs/comprendre/tests-de-scenarios.md} points
     * at. Thirty seconds a solve.
     */
    @Test
    @Tag("scenario-lent")
    void bothEnginesHonourAPerimeterOnAFullSizeGrid() {
        String scenario = "festival-hivernal.yaml";
        HandSearchFixtures.Loaded base = HandSearchFixtures.load(scenario);
        assertThat(HandSearchFixtures.search(base.problem(), 30, 0).isFeasible())
                .isTrue();
        Map<String, List<String>> plan = holdersByCell(base.problem());
        LocalDate day = base.problem().getPostes().stream()
                .map(poste -> poste.getCreneau().getDate())
                .distinct()
                .sorted()
                .skip(5)
                .findFirst()
                .orElseThrow();
        for (String perimeter : List.of("one day re-opened", "reseeded")) {
            for (SolverEngine engine : SolverEngine.values()) {
                HandSearchFixtures.Loaded loaded = HandSearchFixtures.load(scenario);
                PlanningEvenement problem = loaded.problem();
                if (perimeter.startsWith("one day")) {
                    ProblemBuilder.figerPostesIncremental(
                            problem.getPostes(),
                            problem.getAnimateurs(),
                            plan,
                            new ReplanificationScope(Set.of(), Set.of(day), Set.of()),
                            problem.getContraintesAdHoc());
                } else {
                    ProblemBuilder.reamorcerDepuisAffectations(
                            problem.getPostes(), problem.getAnimateurs(), plan, problem.getContraintesAdHoc());
                }
                Map<String, String> pinned = pinnedHolders(problem);
                Map<String, String> before = holders(problem);
                AtomicLong feasibleAt = new AtomicLong(-1);
                long start = System.nanoTime();

                PlanningEvenement solved = solve(engine, loaded, 30, feasibleAt);

                long changedOutside = pinned.entrySet().stream()
                        .filter(seat -> !java.util.Objects.equals(
                                seat.getValue(), holders(solved).get(seat.getKey())))
                        .count();
                Map<String, String> after = holders(solved);
                long changed = before.entrySet().stream()
                        .filter(seat -> !java.util.Objects.equals(seat.getValue(), after.get(seat.getKey())))
                        .count();
                System.out.printf(
                        "PERIMETER | %s | %s | seats %d | pinned %d | changed outside %d | changed %d | feasible"
                                + " at %d ms | %s | %d s%n",
                        perimeter,
                        engine.configValue(),
                        problem.getPostes().size(),
                        pinned.size(),
                        changedOutside,
                        changed,
                        feasibleAt.get(),
                        solved.getScore(),
                        (System.nanoTime() - start) / 1_000_000_000L);
                assertThat(changedOutside).isZero();
                assertSolvedAndRefereed(loaded, solved);
            }
        }
    }

    /* ------------------------------ helpers ------------------------------ */

    private static PlanningEvenement solve(SolverEngine engine, HandSearchFixtures.Loaded loaded, long seconds) {
        return solve(engine, loaded, seconds, new AtomicLong());
    }

    /** Through the seam a worker goes through: settings alone decide the engine. */
    private static PlanningEvenement solve(
            SolverEngine engine, HandSearchFixtures.Loaded loaded, long seconds, AtomicLong feasibleAt) {
        SolverConfiguration ofSettings =
                SolverConfiguration.ofSettings(new SolverConfiguration.SearchSettings(seconds, 0, 0, engine));
        return new SolveRunner(ofSettings, null, null)
                .solvePrepared(loaded.problem(), new SolveBudget(seconds, 0L, null), solver -> {
                    // Without this every assertion here would hold with the setting ignored.
                    assertThat(solver instanceof HandSearchSolver).isEqualTo(engine == SolverEngine.HAND);
                    solver.addEventListener(event -> {
                        if (feasibleAt.get() < 0
                                && event.isNewBestSolutionInitialized()
                                && event.getNewBestScore() instanceof HardMediumSoftScore score
                                && score.isFeasible()) {
                            feasibleAt.set(event.getTimeMillisSpent());
                        }
                    });
                })
                .planning();
    }

    private static void assertSolvedAndRefereed(HandSearchFixtures.Loaded loaded, PlanningEvenement solved) {
        assertThat(solved.getScore().isFeasible())
                .as("score %s", solved.getScore())
                .isTrue();
        assertThat(loaded.configuration().solutionManager().update(solved)).isEqualTo(solved.getScore());
    }

    private static Map<String, String> holders(PlanningEvenement planning) {
        Map<String, String> holders = new HashMap<>();
        for (PosteAffectation poste : planning.getPostes()) {
            holders.put(
                    poste.getId(),
                    poste.getAnimateur() == null ? null : poste.getAnimateur().getId());
        }
        return holders;
    }

    /** Every pinned seat and who holds it — {@code null} for a pinned hole. */
    private static Map<String, String> pinnedHolders(PlanningEvenement planning) {
        Set<String> pinned = planning.getPostes().stream()
                .filter(PosteAffectation::isVerrouille)
                .map(PosteAffectation::getId)
                .collect(Collectors.toSet());
        Map<String, String> holders = holders(planning);
        holders.keySet().retainAll(pinned);
        return holders;
    }

    private static Map<String, List<String>> holdersByCell(PlanningEvenement planning) {
        Map<String, List<String>> byCell = new HashMap<>();
        for (PosteAffectation poste : planning.getPostes()) {
            byCell.computeIfAbsent(
                            PlanningPersistenceService.standCreneauKey(
                                    poste.getStand().getId(), poste.getCreneau().getId()),
                            cell -> new ArrayList<>())
                    .add(poste.getAnimateur().getId());
        }
        return byCell;
    }

    private static boolean onDay(PlanningEvenement problem, String cell, LocalDate day) {
        return problem.getPostes().stream()
                .anyMatch(poste -> poste.getCreneau().getDate().equals(day)
                        && PlanningPersistenceService.standCreneauKey(
                                        poste.getStand().getId(),
                                        poste.getCreneau().getId())
                                .equals(cell));
    }

    private static List<AffectationPubliee> publishedFrom(PlanningEvenement plan) {
        Set<AffectationPubliee> facts = new LinkedHashSet<>();
        for (PosteAffectation poste : plan.getPostes()) {
            Creneau creneau = poste.getCreneau();
            facts.add(new AffectationPubliee(
                    poste.getStand().getId(),
                    creneau.getDate(),
                    creneau.getHeureDebut(),
                    creneau.getHeureFin(),
                    poste.getAnimateur().getId()));
        }
        return new ArrayList<>(new HashSet<>(facts));
    }
}
