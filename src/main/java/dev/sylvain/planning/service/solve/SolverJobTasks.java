package dev.sylvain.planning.service.solve;

import ai.timefold.solver.core.api.solver.Solver;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.service.solve.SolverJobService.JobType;
import dev.sylvain.planning.service.solve.SolverJobService.ReamorcageEffectue;
import dev.sylvain.planning.service.solve.SolverJobService.ResultatSolve;
import dev.sylvain.planning.service.solve.SolverJobService.ResultatSolveIncremental;
import dev.sylvain.planning.service.solve.SolverJobService.SolverJob;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * What a solver job <em>does</em> once the queue hands it the solver: the
 * three kinds of work, built as tasks the queue runs without knowing which.
 *
 * <p>Split out of {@code SolverJobService} (issue #392, A11) along the one
 * line that separates cleanly: the queue, the registry and the replay share a
 * monitor and stay together; building a task touches no shared state at all.
 * A task built here for a replayable job is rebuilt identically from the
 * persisted intention at startup, which is the whole point of
 * {@link #replayable}.</p>
 */
@ApplicationScoped
public class SolverJobTasks {

    /** The work of one job, run on the solver thread. */
    @FunctionalInterface
    interface JobTask {
        Object execute(SolverJob job);
    }

    @Inject
    SolvePipeline pipeline;

    @Inject
    PlanningService planningService;

    @Inject
    SolverScoreTrace scoreTrace;

    /** A one-off solve of a problem that came in the request body — never replayable, the body is not stored. */
    JobTask solve(PlanningEvenement problem, Long secondsLimit, BooleanSupplier shutdownRequested) {
        return job -> resultatSolve(
                pipeline.execute(job.getEditionNom(), problem, secondsLimit, onSolverReady(job), shutdownRequested));
    }

    /** Rebuilds the work of a replayable job from its persisted intention. */
    JobTask replayable(
            JobType type,
            Long secondsLimit,
            ReplanificationScope scope,
            Reamorcage reamorcage,
            BooleanSupplier shutdownRequested) {
        return switch (type) {
            case SOLVE ->
                solveFromReferenceData(
                        secondsLimit, reamorcage == null ? Reamorcage.AUTO : reamorcage, shutdownRequested);
            case SOLVE_INCREMENTAL -> incremental(secondsLimit, scope, shutdownRequested);
        };
    }

    private JobTask solveFromReferenceData(
            Long secondsLimit, Reamorcage reamorcage, BooleanSupplier shutdownRequested) {
        return job -> {
            SolvePipeline.Resolution<ProblemBuilder.ProblemeReamorce> resolution = pipeline.execute(
                    job.getEditionNom(),
                    () -> planningService.buildFromReferenceData(reamorcage),
                    ProblemBuilder.ProblemeReamorce::planning,
                    secondsLimit,
                    onSolverReady(job),
                    shutdownRequested);
            ProblemBuilder.ProblemeReamorce probleme = resolution.probleme();
            return new ResultatSolve(
                    resolution.diagnostic(),
                    resolution.previousPlan(),
                    new ReamorcageEffectue(probleme.reamorcage(), probleme.postesReamorces(), probleme.postesLiberes()),
                    resolution.impactPublication(),
                    resolution.impactValidations(),
                    resolution.interruption());
        };
    }

    private JobTask incremental(Long secondsLimit, ReplanificationScope scope, BooleanSupplier shutdownRequested) {
        return job -> {
            SolvePipeline.Resolution<ProblemBuilder.ProblemeIncremental> resolution = pipeline.execute(
                    job.getEditionNom(),
                    () -> planningService.buildIncrementalFromReferenceData(scope),
                    ProblemBuilder.ProblemeIncremental::planning,
                    secondsLimit,
                    onSolverReady(job),
                    shutdownRequested);
            ProblemBuilder.ProblemeIncremental probleme = resolution.probleme();
            return new ResultatSolveIncremental(
                    resolution.diagnostic(),
                    probleme.statistiques(),
                    ReplanificationDiff.compute(probleme.affectationsPrecedentes(), resolution.planning()),
                    resolution.previousPlan(),
                    resolution.impactPublication(),
                    resolution.impactValidations(),
                    resolution.interruption());
        };
    }

    private static ResultatSolve resultatSolve(SolvePipeline.Resolution<?> resolution) {
        return new ResultatSolve(
                resolution.diagnostic(),
                resolution.previousPlan(),
                null,
                resolution.impactPublication(),
                resolution.impactValidations(),
                resolution.interruption());
    }

    private Consumer<Solver<PlanningEvenement>> onSolverReady(SolverJob job) {
        return solver -> {
            // Holding it first: attachSolver honours a cancel that arrived
            // while the problem was being built, by terminating the solver
            // before it ever starts.
            job.attachSolver(solver);
            if (job.isCancelRequested()) {
                // And then there is nothing to follow. Starting a trace anyway
                // would clear the previous run's curve and replace it with an
                // empty one — the screen would announce "no solution yet" for a
                // solve that finished minutes ago.
                return;
            }
            scoreTrace.follow(job.getId(), job.getEditionId(), solver);
        };
    }
}
