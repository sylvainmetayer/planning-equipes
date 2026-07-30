package dev.sylvain.planning.service;

import java.time.Duration;
import java.time.Instant;
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
 * elapsed time through {@code GET /api/jobs/active}.</p>
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
        return submit(JobType.SOLVE, secondsLimit, () -> {
            PlanningFestival solved = planningService.resoudre(problem, secondsLimit);
            persistenceService.persist(solved);
            PlanningService.PlanningDiagnostic diagnostic = planningService.diagnostiquer(solved);
            analysisStore.record(diagnostic);
            return diagnostic;
        });
    }

    public SolverJob submitAnalyze(PlanningFestival problem, Long secondsLimit) {
        return submit(JobType.ANALYZE, secondsLimit, () -> {
            PlanningService.PlanningDiagnostic diagnostic = planningService.analyser(problem, secondsLimit);
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
        SolverJob job = new SolverJob(UUID.randomUUID().toString(), type, secondsLimit);
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
            job.markCompleted(task.execute());
        } catch (Exception e) {
            job.markFailed(e);
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
     * solver keeps working.
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
        Object execute();
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
        private final Instant submittedAt = Instant.now();
        private volatile JobStatus status = JobStatus.PENDING;
        private volatile Instant startedAt;
        private volatile Instant finishedAt;
        private volatile Object result;
        private volatile String error;

        private SolverJob(String id, JobType type, Long secondsLimit) {
            this.id = id;
            this.type = type;
            this.secondsLimit = secondsLimit;
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
