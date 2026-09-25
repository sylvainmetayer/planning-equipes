package dev.sylvain.planning.service.solve;

import dev.sylvain.planning.service.solve.SolverJobService.JobStatus;
import dev.sylvain.planning.service.solve.SolverJobService.JobType;
import dev.sylvain.planning.service.solve.SolverJobService.SolverJob;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * The solver's operating metrics, in one place so their names and labels are
 * written once: how long runs take and how they end, how many wait, whether one
 * holds the solver, and how many died on a bug.
 *
 * <p>The labels are closed sets — the two job types and the terminal states —
 * and never an edition, a job id or anything a caller typed: an operator
 * watches the solver of the instance, not of an edition (see
 * {@code docs/observabilite.md} § Métriques).</p>
 */
@ApplicationScoped
public class SolverMetrics {

    static final String DURATION = "planning.solver.duration";
    static final String FAILURES = "planning.solver.failures";
    static final String QUEUE_SIZE = "planning.solver.queue.size";
    static final String ACTIVE = "planning.solver.active";

    @Inject
    MeterRegistry registry;

    /**
     * Registers the two gauges and the failure counters, so a scrape shows
     * them at zero from the start rather than only after the first failure —
     * an absent series and a zero one do not read the same on an alert.
     */
    void bind(Supplier<Number> queueSize, Supplier<Boolean> active) {
        Gauge.builder(QUEUE_SIZE, queueSize)
                .description("Solver jobs waiting for the solver")
                .strongReference(true)
                .register(registry);
        Gauge.builder(ACTIVE, () -> active.get() ? 1 : 0)
                .description("1 while a solver job holds the solver, 0 otherwise")
                .strongReference(true)
                .register(registry);
        for (JobType type : JobType.values()) {
            failures(type);
        }
    }

    /**
     * Records a job that reached a terminal state. A job cancelled before it
     * ever ran has no duration to give and is not timed.
     *
     * @param refused the job failed on a business refusal — nothing left to
     *                plan — rather than on a bug: it is the job's answer, not
     *                a failure to page anybody for, so it is labelled apart
     */
    void finished(SolverJob job, boolean refused) {
        if (job.getStartedAt() == null || job.getFinishedAt() == null) {
            return;
        }
        Timer.builder(DURATION)
                .description("Duration of solver runs, from start to terminal state")
                .tag("type", type(job.getType()))
                .tag("outcome", refused ? "refused" : outcome(job.getStatus()))
                .register(registry)
                .record(Duration.between(job.getStartedAt(), job.getFinishedAt()));
    }

    /** A run that died on an unexpected exception — the one reported to the error tracker. */
    void failed(SolverJob job) {
        failures(job.getType()).increment();
    }

    private Counter failures(JobType type) {
        return Counter.builder(FAILURES)
                .description("Solver runs that failed on an unexpected error")
                .tag("type", type(type))
                .register(registry);
    }

    static String type(JobType type) {
        return switch (type) {
            case SOLVE -> "full";
            case SOLVE_INCREMENTAL -> "incremental";
        };
    }

    /** The terminal states, in the label's own words. */
    static String outcome(JobStatus status) {
        return switch (status) {
            case COMPLETED -> "completed";
            case FAILED -> "failed";
            case CANCELLED -> "cancelled";
            case INTERROMPU -> "interrupted";
            case PENDING, QUEUED, RUNNING -> "unfinished";
        };
    }
}
