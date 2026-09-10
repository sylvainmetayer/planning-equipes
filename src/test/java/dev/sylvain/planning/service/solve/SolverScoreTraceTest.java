package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.Score;
import ai.timefold.solver.core.api.solver.event.BestSolutionChangedEvent;
import ai.timefold.solver.core.api.solver.event.EventProducerId;

import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.service.solve.SolverScoreTrace.Point;
import dev.sylvain.planning.service.solve.SolverScoreTrace.Trace;
import org.junit.jupiter.api.Test;

/**
 * The score curve of the running solve (issue #304), on its own: no Quarkus, no
 * solver, only the two bounds that make the curve sendable at all.
 *
 * <p>They are the reason this class exists rather than a plain list. Timefold
 * announces a new best solution far faster than once per second at the start of
 * a run, and a full budget is fifteen minutes: without sampling the stream
 * carries thousands of points, and without a cap the series keeps growing with
 * the budget.</p>
 */
class SolverScoreTraceTest {

    private final SolverScoreTrace trace = new SolverScoreTrace();

    @Test
    void noRunSinceStartupMeansNoCurveAtAll() {
        assertThat(trace.snapshot()).isNull();
    }

    @Test
    void improvementsInsideTheSameSecondCollapseIntoTheLatestOne() {
        trace.start("job-1", "edition-1");

        trace.record("job-1", event(0, -40, -10, -1000));
        // Same window: the solver announced twice in the same second, which is
        // the normal rate at the start of a run.
        trace.record("job-1", event(300, -30, -8, -900));
        trace.record("job-1", event(1000, -20, -6, -800));

        // Two points, and the middle one is gone — but the value kept for the
        // first second is the LATEST of the window, never the first: the score
        // only improves, so the newest one is the truthful one.
        assertThat(points()).containsExactly(
                new Point(0, -40, -10, -1000),
                new Point(1000, -20, -6, -800));
    }

    @Test
    void aSolutionNotInitializedYetIsNotPlotted() {
        trace.start("job-1", "edition-1");

        // Half the seats are still empty, so almost nothing is violated: a
        // flattering score that would draw a cliff at the moment the plan
        // becomes complete, reading as a regression when it is the opposite.
        trace.record("job-1", uninitialized(0, -1, 0, 0));
        trace.record("job-1", event(1000, -40, -10, -1000));

        assertThat(points()).containsExactly(new Point(1000, -40, -10, -1000));
    }

    @Test
    void theSeriesStaysBoundedByHalvingItselfAndDoublingItsInterval() {
        trace.start("job-1", "edition-1");

        // One point per second for well past the cap: a long solve must cost no
        // more memory, and no more bytes on the wire, than a short one.
        for (int seconde = 0; seconde < 3 * SolverScoreTrace.MAX_POINTS; seconde++) {
            trace.record("job-1", event(seconde * 1000L, -seconde, 0, 0));
        }

        Trace snapshot = trace.snapshot();
        assertThat(snapshot.points()).hasSizeLessThanOrEqualTo(SolverScoreTrace.MAX_POINTS);
        // Sampling widened with the run rather than points being dropped at
        // random: the curve still spans it from the very first improvement.
        assertThat(snapshot.intervalleMs()).isGreaterThan(SolverScoreTrace.INTERVALLE_INITIAL_MS);
        assertThat(snapshot.points().get(0).tempsMs()).isZero();
        // The last announced score is a few windows ahead of the last recorded
        // one — which is exactly what the flush at the end of the run repairs.
        trace.finish("job-1");
        assertThat(trace.snapshot().points().getLast().hard())
                .isEqualTo(-(3L * SolverScoreTrace.MAX_POINTS - 1));
    }

    @Test
    void decimatingMovesTheGenerationSoAReaderKnowsToStartOver() {
        trace.start("job-1", "edition-1");
        int avant = trace.generation();

        for (int seconde = 0; seconde <= SolverScoreTrace.MAX_POINTS; seconde++) {
            trace.record("job-1", event(seconde * 1000L, -seconde, 0, 0));
        }

        // Everything else only ever appends at the end; this is the one
        // transformation an incremental reader cannot follow blindly.
        assertThat(trace.generation()).isGreaterThan(avant);
    }

    /**
     * The property the whole screen rests on. Timefold fires
     * {@code bestSolutionChanged} <b>only on a strict improvement</b>, so a
     * solve that plateaus records nothing at all — and a reader with the points
     * alone would see a curve whose last point is its right edge, i.e. a run
     * that looks like it is still climbing however long it has been stuck.
     */
    @Test
    void aRunThatStopsImprovingKeepsReportingTimePassing() throws InterruptedException {
        trace.start("job-1", "edition-1");
        trace.record("job-1", event(0, -40, -10, -1000));

        long avant = trace.snapshot().dureeMs();
        // No further announcement: this is exactly what a plateau produces.
        Thread.sleep(30);

        Trace apres = trace.snapshot();
        assertThat(apres.points()).hasSize(1);
        assertThat(apres.dureeMs()).isGreaterThan(avant);
    }

    @Test
    void aFinishedRunStopsStretchingInsteadOfGrowingForever() throws InterruptedException {
        trace.start("job-1", "edition-1");
        trace.record("job-1", event(0, -40, -10, -1000));
        trace.finish("job-1");

        long fige = trace.snapshot().dureeMs();
        Thread.sleep(30);

        // Frozen: a curve left on screen after a solve must not keep widening,
        // which would make its plateau grow for a run that is over.
        assertThat(trace.snapshot().dureeMs()).isEqualTo(fige);
    }

    @Test
    void theCurveNeverEndsBeforeItsOwnLastPoint() {
        trace.start("job-1", "edition-1");
        // A point Timefold timed well past the wall clock this trace started
        // on: the axis must still contain it rather than cut it off.
        trace.record("job-1", event(600_000, -40, -10, -1000));

        assertThat(trace.snapshot().dureeMs()).isGreaterThanOrEqualTo(600_000);
    }

    @Test
    void theEndOfTheRunFlushesTheFinalScoreEvenMidWindow() {
        trace.start("job-1", "edition-1");
        trace.record("job-1", event(0, -40, -10, -1000));
        // Two hundred milliseconds later the solver stops: the sampling window
        // has not elapsed, but the final score is the one number of the whole
        // curve that must not be an approximation.
        trace.record("job-1", event(200, 0, -2, -900));

        trace.finish("job-1");

        Trace snapshot = trace.snapshot();
        assertThat(snapshot.termine()).isTrue();
        assertThat(snapshot.points()).endsWith(new Point(200, 0, -2, -900));
    }

    @Test
    void aFinishedCurveIgnoresLateAnnouncementsFromItsOwnSolver() {
        trace.start("job-1", "edition-1");
        trace.record("job-1", event(0, -40, -10, -1000));
        trace.finish("job-1");

        trace.record("job-1", event(5000, 0, 0, 0));

        assertThat(points()).containsExactly(new Point(0, -40, -10, -1000));
    }

    @Test
    void anotherJobNeitherWritesIntoNorClosesTheCurrentCurve() {
        trace.start("job-1", "edition-1");
        trace.record("job-1", event(0, -40, -10, -1000));

        // A listener of a previous run outliving its solver must not be able to
        // pollute the curve of the one on screen.
        trace.record("job-0", event(1000, -1, -1, -1));
        trace.finish("job-0");

        Trace snapshot = trace.snapshot();
        assertThat(snapshot.termine()).isFalse();
        assertThat(snapshot.points()).containsExactly(new Point(0, -40, -10, -1000));
    }

    @Test
    void aNewRunReplacesTheCurveAndItsEdition() {
        trace.start("job-1", "edition-1");
        trace.record("job-1", event(0, -40, -10, -1000));
        trace.finish("job-1");

        trace.start("job-2", "edition-2");

        Trace snapshot = trace.snapshot();
        assertThat(snapshot.jobId()).isEqualTo("job-2");
        // Carried all the way to the wire: it is what lets a screen refuse to
        // draw the curve of a solve running on another edition.
        assertThat(snapshot.editionId()).isEqualTo("edition-2");
        assertThat(snapshot.termine()).isFalse();
        assertThat(snapshot.points()).isEmpty();
    }

    private List<Point> points() {
        return trace.snapshot().points();
    }

    private static BestSolutionChangedEvent<PlanningEvenement> event(long tempsMs, long hard, long medium,
            long soft) {
        return new FakeEvent(tempsMs, HardMediumSoftScore.of(hard, medium, soft), true);
    }

    private static BestSolutionChangedEvent<PlanningEvenement> uninitialized(long tempsMs, long hard, long medium,
            long soft) {
        return new FakeEvent(tempsMs, HardMediumSoftScore.of(hard, medium, soft), false);
    }

    /** What Timefold hands the listener, minus the solution it would clone. */
    private record FakeEvent(long tempsMs, Score<?> score, boolean initialise)
            implements BestSolutionChangedEvent<PlanningEvenement> {

        @Override
        public long getTimeMillisSpent() {
            return tempsMs;
        }

        @Override
        public EventProducerId getProducerId() {
            return null;
        }

        @Override
        public PlanningEvenement getNewBestSolution() {
            return null;
        }

        @Override
        @SuppressWarnings("rawtypes")
        public Score getNewBestScore() {
            return score;
        }

        @Override
        public boolean isNewBestSolutionInitialized() {
            return initialise;
        }

        @Override
        public boolean isEveryProblemChangeProcessed() {
            return true;
        }
    }
}
