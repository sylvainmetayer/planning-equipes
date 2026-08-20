package dev.sylvain.planning.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import ai.timefold.solver.core.api.solver.Solver;
import dev.sylvain.planning.domain.PlanningFestival;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Runs long solver calls (solve / analyze) outside of the HTTP request thread.
 *
 * <p>A solve can take several minutes; blocking the browser for that long is
 * not acceptable. Callers submit a job, get an id back immediately, and poll
 * {@code /api/jobs/{id}} until the job is finished. Jobs are kept in memory
 * only: a restart loses them, which is fine because every completed solve is
 * also persisted by {@link PlanningPersistenceService}.</p>
 *
 * <p>The "a solver run is in progress" state lives here, not in the browser:
 * only one solve or analyze may run at a time for the whole server, so any
 * client (other browser, private window) sees the same lock and the same
 * elapsed time through {@code GET /api/jobs/active}. That lock stays
 * <b>global</b> now that the referential is partitioned into groups: a lock per
 * group would put two Timefold solvers on the same JVM at once, which the
 * current memory sizing does not anticipate. Each job does record the group it
 * was submitted for, and writes its result there.</p>
 */
@ApplicationScoped
public class SolverJobService {

    /** Completed jobs are dropped from the registry after this delay. */
    private static final Duration COMPLETED_JOB_RETENTION = Duration.ofHours(1);

    public enum JobType {
        SOLVE,
        ANALYZE
    }

    public enum JobStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    @Inject
    PlanningService planningService;

    @Inject
    PlanningPersistenceService persistenceService;

    @Inject
    ConstraintAnalysisStore analysisStore;

    @Inject
    PlanSnapshotService snapshotService;

    @Inject
    EditionContext editionContext;

    private final Map<String, SolverJob> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2, new SolverThreadFactory());

    /**
     * Solves, then always analyzes the result in the same job — the two are
     * never split across a client-visible gap, so the score analysis reaches
     * the caller even if the browser reloads, closes, or never asks again. The
     * solved planning itself is persisted but not returned as part of the job
     * result: it is fetched from {@code /api/planning/persisted} by whichever
     * dedicated screen needs it, so the (possibly huge) job-polling payload
     * stays limited to the diagnostic.
     */
    public SolverJob submitSolve(PlanningFestival problem, Long secondsLimit) {
        return submit(JobType.SOLVE, secondsLimit, job -> {
            // The safety net of issue #138: the plan about to be overwritten
            // is captured first, so a solve no longer destroys the previous
            // result.
            snapshotService.capturerAvantSolve();
            PlanningFestival solved = planningService.resoudre(problem, secondsLimit, job::attachSolver);
            persistenceService.persist(solved);
            PlanningService.PlanningDiagnostic diagnostic = planningService.diagnostiquer(solved);
            analysisStore.record(diagnostic);
            return diagnostic;
        });
    }

    public SolverJob submitAnalyze(PlanningFestival problem, Long secondsLimit) {
        return submit(JobType.ANALYZE, secondsLimit, job -> {
            PlanningService.PlanningDiagnostic diagnostic =
                    planningService.analyser(problem, secondsLimit, job::attachSolver);
            analysisStore.record(diagnostic);
            return diagnostic;
        });
    }

    /**
     * Registers the job and hands it to the worker pool. Synchronized so two
     * simultaneous requests cannot both pass the "no active job" check.
     */
    private synchronized SolverJob submit(JobType type, Long secondsLimit, JobTask task) {
        purgeExpiredJobs();
        findActive().ifPresent(active -> {
            throw new SolverBusyException(active);
        });
        // Captured here, on the request thread: the worker has no request of
        // its own to read the X-Edition-Id header from, and the job must keep
        // writing to the group it was launched for even if the browser has
        // switched to another one in the meantime.
        SolverJob job = new SolverJob(UUID.randomUUID().toString(), type, secondsLimit,
                editionContext.editionIdCourant());
        jobs.put(job.getId(), job);
        executor.submit(() -> run(job, task));
        return job;
    }

    private void run(SolverJob job, JobTask task) {
        if (job.getStatus() == JobStatus.CANCELLED) {
            return;
        }
        job.markRunning();
        try {
            Object result = editionContext.executeDans(job.getEditionId(), () -> task.execute(job));
            if (job.isCancelRequested()) {
                job.markCancelled(result);
            } else {
                job.markCompleted(result);
            }
        } catch (Exception e) {
            if (job.isCancelRequested()) {
                job.markCancelled(null);
            } else {
                job.markFailed(e);
            }
        }
    }

    public Optional<SolverJob> find(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    /**
     * The job currently holding the solver, if any. Shared by every client so
     * the "solver busy" state does not depend on browser-local storage.
     */
    public Optional<SolverJob> findActive() {
        return jobs.values().stream()
                .filter(job -> !job.isFinished())
                .min(Comparator.comparing(SolverJob::getSubmittedAt));
    }

    /** Newest job first, so the UI can show a readable history. */
    public List<SolverJob> list() {
        return jobs.values().stream()
                .sorted(Comparator.comparing(SolverJob::getSubmittedAt).reversed())
                .toList();
    }

    /**
     * Forgets a finished job (or cancels one still queued). A running job is
     * refused: dropping it would release the server-side solver lock while the
     * solver keeps working. Use {@link #cancel} to stop a running job instead.
     */
    public synchronized boolean forget(String jobId) {
        SolverJob job = jobs.get(jobId);
        if (job == null) {
            return false;
        }
        if (job.getStatus() == JobStatus.PENDING) {
            job.markCancelledIfPending();
        } else if (!job.isFinished()) {
            throw new SolverBusyException(job);
        }
        jobs.remove(jobId);
        return true;
    }

    /**
     * Stops a solver job started by mistake: a queued job is cancelled outright,
     * a running one has its underlying Timefold {@link Solver} terminated early
     * (it returns the best solution found so far, which is then still persisted
     * and analyzed, same as a normal run reaching its time limit) so the job
     * transitions to {@link JobStatus#CANCELLED} rather than being killed. A
     * job that already finished is left untouched. Returns the job, or empty if
     * {@code jobId} is unknown.
     */
    public synchronized Optional<SolverJob> cancel(String jobId) {
        SolverJob job = jobs.get(jobId);
        if (job == null) {
            return Optional.empty();
        }
        if (job.getStatus() == JobStatus.PENDING) {
            job.markCancelledIfPending();
        } else if (job.getStatus() == JobStatus.RUNNING) {
            job.requestCancel();
        }
        return Optional.of(job);
    }

    private void purgeExpiredJobs() {
        Instant cutoff = Instant.now().minus(COMPLETED_JOB_RETENTION);
        jobs.values().removeIf(job -> job.isFinished()
                && job.getFinishedAt() != null
                && job.getFinishedAt().isBefore(cutoff));
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface JobTask {
        Object execute(SolverJob job);
    }

    /** Raised when a solve or analyze is requested while another one runs. */
    public static final class SolverBusyException extends RuntimeException {

        private final transient SolverJob activeJob;

        SolverBusyException(SolverJob activeJob) {
            super("A solver job is already running (" + activeJob.getType() + ", id " + activeJob.getId() + ")");
            this.activeJob = activeJob;
        }

        public SolverJob getActiveJob() {
            return activeJob;
        }
    }

    private static final class SolverThreadFactory implements ThreadFactory {

        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "solver-job-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

    /** Mutable job handle shared between the HTTP threads and the worker. */
    public static final class SolverJob {

        private final String id;
        private final JobType type;
        private final Long secondsLimit;
        /** Group this job was submitted for, and the one its result is written to. */
        private final String editionId;
        private final Instant submittedAt = Instant.now();
        private volatile JobStatus status = JobStatus.PENDING;
        private volatile Instant startedAt;
        private volatile Instant finishedAt;
        private volatile Object result;
        private volatile String error;
        private volatile boolean cancelRequested;
        private volatile Solver<PlanningFestival> solver;

        private SolverJob(String id, JobType type, Long secondsLimit, String editionId) {
            this.id = id;
            this.type = type;
            this.secondsLimit = secondsLimit;
            this.editionId = editionId;
        }

        private void markRunning() {
            status = JobStatus.RUNNING;
            startedAt = Instant.now();
        }

        private void markCompleted(Object value) {
            result = value;
            finishedAt = Instant.now();
            status = JobStatus.COMPLETED;
        }

        private void markFailed(Exception e) {
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            finishedAt = Instant.now();
            status = JobStatus.FAILED;
        }

        private void markCancelledIfPending() {
            if (status == JobStatus.PENDING) {
                status = JobStatus.CANCELLED;
                finishedAt = Instant.now();
            }
        }

        private void markCancelled(Object value) {
            result = value;
            finishedAt = Instant.now();
            status = JobStatus.CANCELLED;
        }

        /**
         * Called once by {@link PlanningService#resoudre} right after building
         * the {@link Solver}, before it blocks on {@code solve()}. Terminates it
         * immediately if a cancel was already requested (the narrow race window
         * between {@link #requestCancel()} and this call).
         */
        private void attachSolver(Solver<PlanningFestival> solver) {
            this.solver = solver;
            if (cancelRequested) {
                solver.terminateEarly();
            }
        }

        /** Stops the solver as soon as it exists, and flags the job as cancelled. */
        private void requestCancel() {
            cancelRequested = true;
            Solver<PlanningFestival> currentSolver = solver;
            if (currentSolver != null) {
                currentSolver.terminateEarly();
            }
        }

        private boolean isCancelRequested() {
            return cancelRequested;
        }

        public boolean isFinished() {
            return status == JobStatus.COMPLETED || status == JobStatus.FAILED || status == JobStatus.CANCELLED;
        }

        /**
         * Seconds since the job entered the queue, frozen once it is finished.
         * Computed server-side so every client displays the same duration,
         * whatever its own clock or when it connected.
         */
        public long getElapsedSeconds() {
            Instant end = finishedAt == null ? Instant.now() : finishedAt;
            return Math.max(0, Duration.between(submittedAt, end).getSeconds());
        }

        public String getId() {
            return id;
        }

        public JobType getType() {
            return type;
        }

        public Long getSecondsLimit() {
            return secondsLimit;
        }

        public String getEditionId() {
            return editionId;
        }

        public Instant getSubmittedAt() {
            return submittedAt;
        }

        public JobStatus getStatus() {
            return status;
        }

        public Instant getStartedAt() {
            return startedAt;
        }

        public Instant getFinishedAt() {
            return finishedAt;
        }

        public Object getResult() {
            return result;
        }

        public String getError() {
            return error;
        }
    }
}
