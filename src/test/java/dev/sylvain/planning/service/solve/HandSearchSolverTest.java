package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.solver.Solver;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The direct engine behind the face of a solver (ADR 0087): it solves, it is
 * followed and stopped like any other, and a plan Timefold scores otherwise
 * fails the solve instead of being believed.
 */
class HandSearchSolverTest {

    private static final String SCENARIO = "gamme-12-7j-10stands-24animateurs-semaine-complete-ferie.yaml";

    private static SolverConfiguration.SearchSettings hand(long seconds) {
        return new SolverConfiguration.SearchSettings(seconds, 0, 0, SolverEngine.HAND);
    }

    @Test
    void reachesAFeasiblePlanTheRefereeScoresAlike() {
        HandSearchFixtures.Loaded loaded = HandSearchFixtures.load(SCENARIO);
        HandSearchSolver solver =
                new HandSearchSolver(hand(3), 0, loaded.configuration().solutionManager());
        List<HardMediumSoftScore> bests = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        solver.addEventListener(event -> {
            bests.add((HardMediumSoftScore) event.getNewBestScore());
            times.add(event.getTimeMillisSpent());
        });

        PlanningEvenement solved = solver.solve(loaded.problem());

        assertThat(solved).isSameAs(loaded.problem());
        assertThat(solved.getScore().isFeasible()).isTrue();
        assertThat(solved.getScore())
                .isEqualTo(loaded.configuration().solutionManager().update(solved));
        assertThat(solved.getPostes()).allMatch(poste -> poste.getAnimateur() != null);
        assertThat(loaded.disagreements()).isEmpty();
        assertThat(bests).isNotEmpty().last().isEqualTo(solved.getScore());
        assertThat(times).isSorted();
        assertThat(solver.isSolving()).isFalse();
    }

    @Test
    void stoppedEarlyItHandsBackThePlanItHas() {
        HandSearchFixtures.Loaded loaded = HandSearchFixtures.load(SCENARIO);
        HandSearchSolver solver =
                new HandSearchSolver(hand(120), 0, loaded.configuration().solutionManager());
        // Stopped from its own first announcement, as a job stops the solver it was handed.
        solver.addEventListener(event -> solver.terminateEarly());

        long start = System.nanoTime();
        PlanningEvenement solved = solver.solve(loaded.problem());

        assertThat((System.nanoTime() - start) / 1_000_000_000L).isLessThan(30);
        assertThat(solver.isTerminateEarly()).isTrue();
        assertThat(solved.getScore())
                .isEqualTo(loaded.configuration().solutionManager().update(solved));
    }

    @Test
    void aPlanTheRefereeScoresOtherwiseFailsTheSolveAndLeavesTheProblemAsItCame() {
        HandSearchFixtures.Loaded loaded = HandSearchFixtures.load(SCENARIO);
        // A search that lays a plan and claims a score it does not have: what a rule
        // written wrong in the engine looks like from here.
        HandSearchSolver solver = new HandSearchSolver(loaded.configuration().solutionManager(), problem -> {
            HandSearchFixtures.randomPlan(problem, new SplittableRandom(1));
            return HardMediumSoftScore.of(0, -12, -3);
        });

        assertThatThrownBy(() -> solver.solve(loaded.problem()))
                .isInstanceOf(HandSearchSolver.Failed.class)
                .hasMessageContaining("0hard/-12medium/-3soft")
                .hasMessageContaining("plan refusé");
        assertThat(loaded.problem().getPostes()).allMatch(poste -> poste.getAnimateur() == null);
    }

    @Test
    void aProblemTheEngineDoesNotScoreIsRefusedInWords() {
        HandSearchFixtures.Loaded loaded = HandSearchFixtures.load(SCENARIO);
        loaded.problem().parametresLegaux().setDureePauseMinutes(90);
        HandSearchSolver solver =
                new HandSearchSolver(hand(3), 0, loaded.configuration().solutionManager());

        assertThatThrownBy(() -> solver.solve(loaded.problem()))
                .isInstanceOf(HandSearchSolver.Failed.class)
                .hasMessageContaining("Le moteur direct ne peut pas résoudre ce problème")
                .hasMessageContaining("la pause dépasse 60 minutes");
    }

    @Test
    void aSeatHandedInWithItsHolderIsWhereTheSearchStartsAndAPinnedOneDoesNotMove() {
        HandSearchFixtures.Loaded loaded = HandSearchFixtures.load(SCENARIO);
        HandSearchFixtures.search(loaded.problem(), 1, 7);
        PosteAffectation pinned = loaded.problem().getPostes().get(0);
        pinned.setVerrouille(true);
        var holder = pinned.getAnimateur();

        new HandSearchSolver(hand(1), 0, loaded.configuration().solutionManager()).solve(loaded.problem());

        assertThat(pinned.getAnimateur()).isEqualTo(holder);
        assertThat(loaded.problem().getScore().isFeasible()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"extreme-10-sans-animateur.yaml", "extreme-15-sans-creneau.yaml"})
    void aProblemWithNobodyOrNothingToSeatIsScoredAsItStands(String scenario) {
        HandSearchFixtures.Loaded loaded = HandSearchFixtures.load(scenario);

        PlanningEvenement solved =
                new HandSearchSolver(hand(1), 0, loaded.configuration().solutionManager()).solve(loaded.problem());

        assertThat(solved.getScore())
                .isEqualTo(loaded.configuration().solutionManager().update(solved));
        assertThat(loaded.disagreements()).isEmpty();
    }

    /** The path a worker takes: settings alone, no reference data, the engine named by the settings. */
    @Test
    void theEngineNamedByTheSettingsIsTheOneAPreparedProblemIsSolvedWith() {
        HandSearchFixtures.Loaded loaded = HandSearchFixtures.load(SCENARIO);
        SolverConfiguration ofWorker = SolverConfiguration.ofSettings(hand(2));
        AtomicReference<Solver<PlanningEvenement>> handed = new AtomicReference<>();

        SolveRunner.Solved solved = new SolveRunner(ofWorker, null, null)
                .solvePrepared(loaded.problem(), new SolveBudget(2L, 0L, null), handed::set);

        assertThat(handed.get()).isInstanceOf(HandSearchSolver.class);
        assertThat(solved.feasibilityFirst()).isNull();
        assertThat(solved.planning().getScore().isFeasible()).isTrue();
        assertThat(ofWorker.searchSettings(null).engine()).isEqualTo(SolverEngine.HAND);
    }

    @Test
    void theEngineIsReadFromItsNameAndAnUnknownOneIsRefused() {
        assertThat(SolverEngine.of("timefold")).isEqualTo(SolverEngine.TIMEFOLD);
        assertThat(SolverEngine.of(" Hand ")).isEqualTo(SolverEngine.HAND);
        assertThat(SolverEngine.HAND.configValue()).isEqualTo("hand");
        assertThatThrownBy(() -> SolverEngine.of("cp-sat"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("planning.solver.engine");
    }
}
