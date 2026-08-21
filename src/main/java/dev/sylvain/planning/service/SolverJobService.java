package dev.sylvain.planning.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
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
import dev.sylvain.planning.domain.Edition;
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
 * <p>A job can also be <b>queued</b> instead of refused when the solver is
 * busy (the "Planifier" buttons): it starts by itself as soon as the running
 * one finishes, so preparing another edition no longer means waiting in front
 * of the screen for a solve to end. The queue is FIFO, in memory like the jobs
 * themselves, and a queued job builds its problem only when it actually
 * starts — the referential it reads is the one in place at that moment, not
 * the one of the click.</p>
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

    /**
     * Default budget of an incremental re-solve (issue #86). An order of
     * magnitude under a full solve's (180 s by default, 600 s on the reference
     * scenario) because the effective problem is a fraction of the full one:
     * most seats are pinned, and the whole point is a fast answer to a
     * last-minute change.
     */
    static final long DUREE_INCREMENTALE_DEFAUT_SECONDES = 60L;

    public enum JobType {
        SOLVE,
        /** Incremental re-solve (issue #86) — its own type so the UI can name it. */
        SOLVE_INCREMENTAL,
        ANALYZE
    }

    public enum JobStatus {
        /** Handed to the worker pool, about to run: already holds the solver. */
        PENDING,
        /** Waiting for the running job to finish; does not hold the solver yet. */
        QUEUED,
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
    KpiHistoriqueService kpiHistoriqueService;

    @Inject
    EditionContext editionContext;

    @Inject
    EditionRepository editionRepository;

    private final Map<String, SolverJob> jobs = new ConcurrentHashMap<>();
    /** FIFO of jobs waiting for the solver. Guarded by this service's monitor. */
    private final Deque<TacheEnFile> file = new ArrayDeque<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2, new SolverThreadFactory());

    /** A queued job and the work it will run once the solver frees up. */
    private record TacheEnFile(SolverJob job, JobTask task) {
    }

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
        return submit(JobType.SOLVE, secondsLimit, false, job -> {
            // The safety net of issue #138: the plan about to be overwritten
            // is captured first, so a solve no longer destroys the previous
            // result.
            snapshotService.capturerAvantSolve();
            Instant debutSolve = Instant.now();
            PlanningFestival solved = planningService.resoudre(problem, secondsLimit, job::attachSolver);
            long dureeSolveSecondes = Duration.between(debutSolve, Instant.now()).getSeconds();
            persistenceService.persist(solved);
            PlanningService.PlanningDiagnostic diagnostic = planningService.diagnostiquer(solved);
            analysisStore.record(diagnostic);
            // KPI history (issue #89): one row per completed solve, carrying the
            // real duration. Deliberately after the analysis — the KPI read the
            // score it just recorded — and never able to fail the job.
            kpiHistoriqueService.enregistrerApresSolve(dureeSolveSecondes);
            return diagnostic;
        });
    }

    /**
     * Full solve whose problem is built <b>inside the job</b>, from the
     * reference data of the job's edition. Deferring the build is what makes
     * queueing meaningful: a job planned while another one runs must solve the
     * edition as it stands when its turn comes, not as it stood at the click —
     * the whole point being to keep preparing that edition meanwhile.
     *
     * @param enFile when the solver is busy, wait for it instead of being refused
     */
    public SolverJob submitSolveDepuisReferenceData(Long secondsLimit, boolean enFile) {
        return submit(JobType.SOLVE, secondsLimit, enFile, job -> {
            snapshotService.capturerAvantSolve();
            PlanningFestival problem = planningService.construireDepuisReferenceData();
            Instant debutSolve = Instant.now();
            PlanningFestival solved = planningService.resoudre(problem, secondsLimit, job::attachSolver);
            long dureeSolveSecondes = Duration.between(debutSolve, Instant.now()).getSeconds();
            persistenceService.persist(solved);
            PlanningService.PlanningDiagnostic diagnostic = planningService.diagnostiquer(solved);
            analysisStore.record(diagnostic);
            kpiHistoriqueService.enregistrerApresSolve(dureeSolveSecondes);
            return diagnostic;
        });
    }

    /**
     * Result of an incremental re-solve (issue #86): the usual diagnostic, plus
     * how much of the problem was frozen and exactly which stand × créneau
     * crews changed — replanning partially is only worth it if one can say who
     * is impacted.
     */
    public record ResultatSolveIncremental(
            PlanningService.PlanningDiagnostic diagnostic,
            PlanningService.StatistiquesIncremental statistiques,
            List<ReplanificationDiff.ChangementAffectation> changements) {
    }

    /**
     * Incremental re-solve (issue #86): starts from the persisted plan, pins
     * everything a late change did not invalidate and {@code perimetre} does
     * not re-open (see
     * {@link PlanningService#construireIncrementalDepuisReferenceData}), and
     * re-fills only the rest — which is why a far shorter budget than a full
     * solve is enough.
     *
     * <p>The problem is built <b>inside</b> the job, on the job's edition,
     * because it reads the persisted plan: building it on the request thread
     * would race with the previous job's persistence.</p>
     */
    public SolverJob submitSolveIncremental(Long secondsLimitDemande, PerimetreReplanification perimetre,
            boolean enFile) {
        Long secondsLimit = secondsLimitDemande != null ? secondsLimitDemande : DUREE_INCREMENTALE_DEFAUT_SECONDES;
        return submit(JobType.SOLVE_INCREMENTAL, secondsLimit, enFile, job -> {
            snapshotService.capturerAvantSolve();
            PlanningService.ProblemeIncremental probleme =
                    planningService.construireIncrementalDepuisReferenceData(perimetre);
            Instant debutSolve = Instant.now();
            PlanningFestival solved =
                    planningService.resoudre(probleme.planning(), secondsLimit, job::attachSolver);
            long dureeSolveSecondes = Duration.between(debutSolve, Instant.now()).getSeconds();
            persistenceService.persist(solved);
            PlanningService.PlanningDiagnostic diagnostic = planningService.diagnostiquer(solved);
            analysisStore.record(diagnostic);
            kpiHistoriqueService.enregistrerApresSolve(dureeSolveSecondes);
            return new ResultatSolveIncremental(diagnostic, probleme.statistiques(),
                    ReplanificationDiff.calculer(probleme.affectationsPrecedentes(), solved));
        });
    }

    public SolverJob submitAnalyze(PlanningFestival problem, Long secondsLimit) {
        return submit(JobType.ANALYZE, secondsLimit, false, job -> {
            PlanningService.PlanningDiagnostic diagnostic =
                    planningService.analyser(problem, secondsLimit, job::attachSolver);
            analysisStore.record(diagnostic);
            return diagnostic;
        });
    }

    /**
     * Registers the job and either hands it to the worker pool or queues it.
     * Synchronized so two simultaneous requests cannot both pass the "no
     * active job" check — the same monitor {@link #terminerEtEnchainer} holds
     * while it promotes the next queued job, so there is no window in which
     * the solver looks free while a hand-over is under way.
     */
    private synchronized SolverJob submit(JobType type, Long secondsLimit, boolean enFile, JobTask task) {
        purgeExpiredJobs();
        Optional<SolverJob> actif = findActive();
        if (actif.isPresent() && !enFile) {
            throw new SolverBusyException(actif.get());
        }
        // Captured here, on the request thread: the worker has no request of
        // its own to read the X-Edition-Id header from, and the job must keep
        // writing to the edition it was launched for even if the browser has
        // switched to another one in the meantime.
        String editionId = editionContext.editionIdCourant();
        if (actif.isPresent()) {
            refuserDoublon(type, editionId);
        }
        SolverJob job = new SolverJob(UUID.randomUUID().toString(), type, secondsLimit,
                editionId, nomEdition(editionId));
        jobs.put(job.getId(), job);
        if (actif.isPresent()) {
            job.markQueued();
            file.addLast(new TacheEnFile(job, task));
        } else {
            executor.submit(() -> run(job, task));
        }
        return job;
    }

    /**
     * Refuses a second job of the same kind on the same edition, whether the
     * first one is running or merely queued. This is the double-click guard:
     * queueing what is already under way would silently solve the same edition
     * twice in a row, and the second run would discard the first one's result.
     * Two <b>different</b> kinds stay allowed — chaining a full solve and an
     * incremental replanning on one edition is a legitimate sequence.
     */
    private void refuserDoublon(JobType type, String editionId) {
        jobs.values().stream()
                .filter(job -> !job.isFinished())
                .filter(job -> job.getType() == type && job.getEditionId().equals(editionId))
                .findFirst()
                .ifPresent(dejaPrevu -> {
                    throw new SolverBusyException(dejaPrevu);
                });
    }

    /** The jobs waiting for the solver, in the order they will run. */
    public synchronized List<SolverJob> fileAttente() {
        return file.stream().map(TacheEnFile::job).toList();
    }

    /**
     * Human-readable name of the edition a job is submitted for, resolved once
     * at submit time — the UI shows it wherever the job is reported, so an
     * operator who switched editions still knows which one the solver writes
     * to. Falls back to the id if the edition vanishes mid-lookup.
     */
    private String nomEdition(String editionId) {
        return editionRepository.listEditions().stream()
                .filter(edition -> edition.getId().equals(editionId))
                .map(Edition::getNom)
                .findFirst()
                .orElse(editionId);
    }

    private void run(SolverJob job, JobTask task) {
        if (job.getStatus() == JobStatus.CANCELLED) {
            // Cancelled between promotion and start: the solver is free, so the
            // queue must still move on rather than stall behind a dead job.
            enchainer();
            return;
        }
        job.markRunning();
        Object result = null;
        Exception echec = null;
        try {
            result = editionContext.executeDans(job.getEditionId(), () -> task.execute(job));
        } catch (Exception e) {
            echec = e;
        }
        terminerEtEnchainer(job, result, echec);
    }

    /**
     * Records the job's outcome and starts the next queued one, both under the
     * service monitor. Atomic on purpose: between "this job is finished" and
     * "the next one holds the solver" there must be no instant where a
     * concurrent {@link #submit} sees an idle solver — it would start a second
     * run alongside the one being promoted.
     */
    private synchronized void terminerEtEnchainer(SolverJob job, Object result, Exception echec) {
        if (echec != null) {
            if (job.isCancelRequested()) {
                job.markCancelled(null);
            } else {
                job.markFailed(echec);
            }
        } else if (job.isCancelRequested()) {
            job.markCancelled(result);
        } else {
            job.markCompleted(result);
        }
        demarrerSuivant();
    }

    private synchronized void enchainer() {
        demarrerSuivant();
    }

    /**
     * Promotes the first queued job that is still wanted. Marking it PENDING
     * before releasing the monitor is what makes it visible to
     * {@link #findActive()} — and therefore what keeps the solver lock held
     * across the hand-over. Must be called while holding the monitor.
     */
    private void demarrerSuivant() {
        TacheEnFile suivante;
        while ((suivante = file.pollFirst()) != null) {
            if (suivante.job().getStatus() == JobStatus.CANCELLED) {
                continue;
            }
            suivante.job().markPending();
            TacheEnFile aLancer = suivante;
            executor.submit(() -> run(aLancer.job(), aLancer.task()));
            return;
        }
    }

    public Optional<SolverJob> find(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    /**
     * The job currently holding the solver, if any. Shared by every client so
     * the "solver busy" state does not depend on browser-local storage.
     *
     * <p>A {@link JobStatus#QUEUED} job is deliberately <b>not</b> active: it
     * holds nothing yet, and reporting it as active would freeze data entry on
     * its edition — precisely the edition the operator queued it to keep
     * preparing.</p>
     */
    public Optional<SolverJob> findActive() {
        return jobs.values().stream()
                .filter(job -> job.getStatus() == JobStatus.PENDING || job.getStatus() == JobStatus.RUNNING)
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
        if (job.getStatus() == JobStatus.QUEUED) {
            // Taking a job out of the queue: nothing ran, nothing to stop.
            file.removeIf(tache -> tache.job().getId().equals(jobId));
        } else if (job.getStatus() == JobStatus.PENDING) {
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
        if (job.getStatus() == JobStatus.QUEUED) {
            job.markCancelledIfPending();
            file.removeIf(tache -> tache.job().getId().equals(jobId));
        } else if (job.getStatus() == JobStatus.PENDING) {
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
        /** Edition this job was submitted for, and the one its result is written to. */
        private final String editionId;
        /** Display name of that edition, resolved at submit time. */
        private final String editionNom;
        private final Instant submittedAt = Instant.now();
        private volatile JobStatus status = JobStatus.PENDING;
        private volatile Instant startedAt;
        private volatile Instant finishedAt;
        private volatile Object result;
        private volatile String error;
        private volatile boolean cancelRequested;
        private volatile Solver<PlanningFestival> solver;

        private SolverJob(String id, JobType type, Long secondsLimit, String editionId, String editionNom) {
            this.id = id;
            this.type = type;
            this.secondsLimit = secondsLimit;
            this.editionId = editionId;
            this.editionNom = editionNom;
        }

        private void markQueued() {
            status = JobStatus.QUEUED;
        }

        /** Queued job promoted to "about to run": from here it holds the solver. */
        private void markPending() {
            status = JobStatus.PENDING;
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
            if (status == JobStatus.PENDING || status == JobStatus.QUEUED) {
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
            if (status == JobStatus.QUEUED) {
                // Time spent waiting is not time spent solving: a queued job
                // must not display a duration that suggests it is under way.
                return 0;
            }
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

        public String getEditionNom() {
            return editionNom;
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
