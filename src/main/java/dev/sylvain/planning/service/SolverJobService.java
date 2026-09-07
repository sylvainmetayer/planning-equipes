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
import java.util.function.Consumer;
import java.util.function.Function;

import ai.timefold.solver.core.api.solver.Solver;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.service.notification.Notification;
import dev.sylvain.planning.service.SolverJobRepository.LigneJob;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import io.sentry.Sentry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Runs long solver calls (solve / analyze) outside of the HTTP request thread.
 *
 * <p>A solve can take several minutes; blocking the browser for that long is
 * not acceptable. Callers submit a job, get an id back immediately, and poll
 * {@code /api/jobs/{id}} until the job is finished. Every completed solve is
 * persisted by {@link PlanningPersistenceService}.</p>
 *
 * <p>A job can also be <b>queued</b> instead of refused when the solver is
 * busy (the "Planifier" buttons): it starts by itself as soon as the running
 * one finishes, so preparing another edition no longer means waiting in front
 * of the screen for a solve to end. The queue is FIFO, and a queued job builds
 * its problem only when it actually starts — the referential it reads is the
 * one in place at that moment, not the one of the click.</p>
 *
 * <p><b>A restart no longer loses the queue.</b> Every status transition is
 * mirrored into {@code solver_job} through {@link SolverJobRepository}, and
 * {@link #restaurer()} replays it at startup: queued jobs go back in the queue,
 * in order, and the first one takes the solver by itself. Only the
 * <em>intention</em> is stored, never a Timefold state — which is exactly what
 * a queued job needs, since it builds its problem when it starts anyway. A job
 * that <b>was</b> holding the solver cannot be resumed that way: its run died
 * with the JVM, so it comes back as {@link JobStatus#INTERROMPU} rather than as
 * a ghost {@code RUNNING} that would hold the lock forever. Resuming the
 * computation itself (warm start from a checkpoint) is issue #174.</p>
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

    private static final Logger LOG = Logger.getLogger(SolverJobService.class);

    /** Completed jobs are dropped from the registry after this delay. */
    private static final Duration COMPLETED_JOB_RETENTION = Duration.ofHours(1);

    /**
     * Default budget of an incremental re-solve (issue #86). An order of
     * magnitude under a full solve's (900 s by default, 600 s on the reference
     * scenario) because the effective problem is a fraction of the full one:
     * most seats are pinned, and the whole point is a fast answer to a
     * last-minute change.
     */
    static final long DUREE_INCREMENTALE_DEFAUT_SECONDES = 60L;

    public enum JobType {
        SOLVE,
        /** Incremental re-solve (issue #86) — its own type so the UI can name it. */
        SOLVE_INCREMENTAL
    }

    public enum JobStatus {
        /** Handed to the worker pool, about to run: already holds the solver. */
        PENDING,
        /**
         * Was holding the solver when the server stopped. A terminal state, on
         * purpose: nothing will ever finish it, so it must not keep the solver
         * lock. Two ways in. After a full restart, what it had computed is
         * gone with the JVM. When the container stopped under it — a graceful
         * shutdown, a live reload — the run was cut short and its best plan so
         * far kept only if it beat the persisted one; the {@code error}
         * message says which.
         */
        INTERROMPU,
        /** Waiting for the running job to finish; does not hold the solver yet. */
        QUEUED,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    @Inject
    SolvePipeline pipeline;

    @Inject
    PlanningService planningService;

    @Inject
    EditionContext editionContext;

    @Inject
    EditionRepository editionRepository;

    @Inject
    SolverJobRepository jobRepository;

    /**
     * Announces every transition below to the open {@code /api/jobs/stream}
     * connections. Called at the <em>end</em> of a state change, never inside
     * one: a subscriber reads this service back to build its snapshot, and a
     * hand-over must look atomic from outside — see {@link #finishAndChain}.
     */
    @Inject
    JobStreamBroadcaster jobStream;

    /**
     * Records the score curve of the running solve (issue #304). Fed by the
     * listener {@link #onSolverReady} registers, read back by
     * {@code GET /api/jobs/score} and by the {@code score} events of
     * {@code /api/jobs/stream}.
     */
    @Inject
    SolverScoreTrace scoreTrace;

    /**
     * Whether the queue is replayed at startup. On by default — that is the
     * whole point — but switched off under {@code %test}, where a job left
     * queued by a previous run would start a real solve as the next test boots.
     */
    @ConfigProperty(name = "planning.jobs.reprise-au-demarrage", defaultValue = "true")
    boolean replayAtStartup;

    private final Map<String, SolverJob> jobs = new ConcurrentHashMap<>();
    /** FIFO of jobs waiting for the solver. Guarded by this service's monitor. */
    private final Deque<QueuedTask> file = new ArrayDeque<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2, new SolverThreadFactory());
    /**
     * Raised by {@link #shutdown()} and never lowered: from then on a run that
     * returns was cut short by the server, not by its budget. Read by the
     * pipeline after the solver hands back, and by {@link #finishAndChain}.
     */
    private volatile boolean shutdownRequested;

    /** A queued job and the work it will run once the solver frees up. */
    private record QueuedTask(SolverJob job, JobTask task) {
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
    public SolverJob submitSolve(PlanningEvenement problem, Long secondsLimit) {
        // Not replayable: the problem came in the request body, which is not
        // stored. Never queued either, so a restart can only ever find it in a
        // terminal state or interrupted.
        return submit(JobType.SOLVE, secondsLimit, false, null, false,
                job -> resultatSolve(
                        pipeline.execute(job.getEditionNom(), problem, secondsLimit, onSolverReady(job),
                                this::isShutdownRequested)));
    }

    /**
     * Result of a full solve: the usual diagnostic, plus the plan it replaced
     * (issue #274) — a solve that announces only its own score lets an
     * operator walk away with a worse planning than the one they had, without
     * ever being told.
     *
     * @param interruption set when the server stopped under the run, and says
     *                     whether its partial plan was kept; {@code null} for
     *                     a solve that finished
     */
    public record ResultatSolve(
            PlanningService.PlanningDiagnostic diagnostic,
            PreviousPlan previousPlan,
            ReamorcageEffectue reamorcage,
            SolvePipeline.ImpactPublication impactPublication,
            SolvePipeline.Interruption interruption) {
    }

    /**
     * Where a full solve actually started from (issue #174), for the
     * recap: « réamorcé (N postes) » or « de zéro ». {@code mode} is never
     * {@link Reamorcage#AUTO} — the request was resolved into what was done.
     *
     * @param postesLiberes seats the persisted plan staffed but that had to
     *                      start empty (animateur gone, or since unavailable)
     */
    public record ReamorcageEffectue(Reamorcage mode, int postes, int postesLiberes) {
    }

    /**
     * The result of a solve whose problem came in the request body: its
     * starting point is whatever the caller sent, which the server cannot
     * name — hence {@code null} rather than « de zéro », which would be a
     * claim about a plan we never built.
     */
    private static ResultatSolve resultatSolve(SolvePipeline.Resolution<?> resolution) {
        return new ResultatSolve(resolution.diagnostic(), resolution.previousPlan(), null,
                resolution.impactPublication(), resolution.interruption());
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
    public SolverJob submitSolveFromReferenceData(Long secondsLimit, boolean enFile) {
        return submitSolveFromReferenceData(secondsLimit, enFile, Reamorcage.AUTO);
    }

    /**
     * Same, saying where to start from (issue #174). {@link Reamorcage#AUTO}
     * re-seeds from the persisted plan when there is one — the default of
     * every entry point, screen, API and MCP alike, because starting cold is
     * what silently loses the plan already reached. The choice is persisted
     * with the job: a queued solve replayed after a restart starts the way it
     * was asked to.
     */
    public SolverJob submitSolveFromReferenceData(Long secondsLimit, boolean enFile, Reamorcage reamorcage) {
        return submitReplayable(JobType.SOLVE, secondsLimit, null,
                reamorcage == null ? Reamorcage.AUTO : reamorcage, enFile);
    }

    /** The work of {@link #submitSolveFromReferenceData}, see {@link #replayableTask}. */
    private JobTask solveTaskFromReferenceData(Long secondsLimit, Reamorcage reamorcage) {
        return job -> {
            SolvePipeline.Resolution<PlanningService.ProblemeReamorce> resolution =
                    pipeline.execute(job.getEditionNom(),
                            () -> planningService.buildFromReferenceData(reamorcage),
                            PlanningService.ProblemeReamorce::planning,
                            secondsLimit, onSolverReady(job), this::isShutdownRequested);
            PlanningService.ProblemeReamorce probleme = resolution.probleme();
            return new ResultatSolve(resolution.diagnostic(), resolution.previousPlan(),
                    new ReamorcageEffectue(probleme.reamorcage(), probleme.postesReamorces(),
                            probleme.postesLiberes()),
                    resolution.impactPublication(), resolution.interruption());
        };
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
            List<ReplanificationDiff.ChangementAffectation> changements,
            PreviousPlan previousPlan,
            SolvePipeline.ImpactPublication impactPublication,
            SolvePipeline.Interruption interruption) {
    }

    /**
     * Incremental re-solve (issue #86): starts from the persisted plan, pins
     * everything a late change did not invalidate and {@code scope} does
     * not re-open (see
     * {@link PlanningService#buildIncrementalFromReferenceData}), and
     * re-fills only the rest — which is why a far shorter budget than a full
     * solve is enough.
     *
     * <p>The problem is built <b>inside</b> the job, on the job's edition,
     * because it reads the persisted plan: building it on the request thread
     * would race with the previous job's persistence.</p>
     */
    public SolverJob submitSolveIncremental(Long secondsLimitDemande, ReplanificationScope scope,
            boolean enFile) {
        Long secondsLimit = secondsLimitDemande != null ? secondsLimitDemande : DUREE_INCREMENTALE_DEFAUT_SECONDES;
        return submitReplayable(JobType.SOLVE_INCREMENTAL, secondsLimit, scope, null, enFile);
    }

    /** The work of {@link #submitSolveIncremental}, see {@link #replayableTask}. */
    private JobTask incrementalSolveTask(Long secondsLimit, ReplanificationScope scope) {
        return job -> {
            SolvePipeline.Resolution<PlanningService.ProblemeIncremental> resolution =
                    pipeline.execute(job.getEditionNom(),
                            () -> planningService.buildIncrementalFromReferenceData(scope),
                            PlanningService.ProblemeIncremental::planning,
                            secondsLimit, onSolverReady(job), this::isShutdownRequested);
            PlanningService.ProblemeIncremental probleme = resolution.probleme();
            return new ResultatSolveIncremental(resolution.diagnostic(), probleme.statistiques(),
                    ReplanificationDiff.compute(probleme.affectationsPrecedentes(), resolution.planning()),
                    resolution.previousPlan(), resolution.impactPublication(), resolution.interruption());
        };
    }

    /**
     * Submits a job that knows how to rebuild its own problem. These are the
     * only ones that may be queued — and therefore the only ones a restart can
     * replay, which is the same property seen from the other end: what makes a
     * job queueable (it builds its problem when it starts, from the referential
     * of that moment) is exactly what makes it replayable.
     *
     * <p>Routing both cases through {@link #replayableTask} is deliberate: a
     * job restored at startup then runs the very same code as the click that
     * originally queued it, instead of a second implementation free to
     * drift.</p>
     */
    private SolverJob submitReplayable(JobType type, Long secondsLimit, ReplanificationScope scope,
            Reamorcage reamorcage, boolean enFile) {
        return submit(type, secondsLimit, enFile, scope, reamorcage, true,
                replayableTask(type, secondsLimit, scope, reamorcage));
    }

    /** Rebuilds the work of a replayable job from its persisted intention. */
    private JobTask replayableTask(JobType type, Long secondsLimit, ReplanificationScope scope,
            Reamorcage reamorcage) {
        return switch (type) {
            case SOLVE -> solveTaskFromReferenceData(secondsLimit, reamorcage == null ? Reamorcage.AUTO : reamorcage);
            case SOLVE_INCREMENTAL -> incrementalSolveTask(secondsLimit, scope);
        };
    }

    /**
     * Registers the job and either hands it to the worker pool or queues it.
     * Synchronized so two simultaneous requests cannot both pass the "no
     * active job" check — the same monitor {@link #finishAndChain} holds
     * while it promotes the next queued job, so there is no window in which
     * the solver looks free while a hand-over is under way.
     */
    private synchronized SolverJob submit(JobType type, Long secondsLimit, boolean enFile,
            ReplanificationScope scope, boolean rejouable, JobTask task) {
        return submit(type, secondsLimit, enFile, scope, null, rejouable, task);
    }

    private synchronized SolverJob submit(JobType type, Long secondsLimit, boolean enFile,
            ReplanificationScope scope, Reamorcage reamorcage, boolean rejouable, JobTask task) {
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
            refuseDuplicate(type, editionId);
        }
        SolverJob job = new SolverJob(UUID.randomUUID().toString(), type, secondsLimit,
                editionId, nomEdition(editionId), scope, reamorcage, rejouable);
        jobs.put(job.getId(), job);
        if (actif.isPresent()) {
            job.markQueued();
            file.addLast(new QueuedTask(job, task));
            store(job);
        } else {
            // Written before the hand-over, never after: the worker may have
            // reached RUNNING (and written that) by the time this method
            // resumes, and a late PENDING row would overwrite it.
            store(job);
            executor.submit(() -> run(job, task));
        }
        jobStream.publish();
        return job;
    }

    /**
     * Refuses a second job of the same kind on the same edition <b>already in
     * the queue</b>. This is the double-click guard: two identical planned runs
     * would solve the same edition twice in a row, the second discarding the
     * first one's result.
     *
     * <p>Deliberately blind to the <em>running</em> job: planning a fresh solve
     * of the edition currently being solved is a legitimate — and expected —
     * move. The run under way started before the last corrections and cannot
     * account for them; queueing another is precisely how one says "redo it
     * with what I just fixed". Two different kinds are likewise allowed:
     * chaining a full solve and an incremental replanning is a real sequence.</p>
     */
    private void refuseDuplicate(JobType type, String editionId) {
        file.stream()
                .map(QueuedTask::job)
                .filter(job -> job.getType() == type && job.getEditionId().equals(editionId))
                .findFirst()
                .ifPresent(dejaPlanifie -> {
                    throw new SolverBusyException(dejaPlanifie);
                });
    }

    /** The jobs waiting for the solver, in the order they will run. */
    public synchronized List<SolverJob> fileAttente() {
        return file.stream().map(QueuedTask::job).toList();
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

    /**
     * What a job does with the {@link Solver} it has just been handed: hold it,
     * so a cancel can stop it, and follow the scores it announces, so the
     * browser can draw the run as it happens (issue #304).
     *
     * <p>One consumer rather than two hooks: both need the solver at the exact
     * same instant — after it is built, before it blocks on {@code solve()} —
     * and a second seam through {@code SolvePipeline} would only be a way for
     * the two to drift apart.</p>
     */
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

    private void run(SolverJob job, JobTask task) {
        if (job.getStatus() == JobStatus.CANCELLED) {
            // Cancelled between promotion and start: the solver is free, so the
            // queue must still move on rather than stall behind a dead job.
            chain();
            return;
        }
        job.markRunning();
        store(job);
        jobStream.publish();
        Object result = null;
        Exception failure = null;
        try {
            result = editionContext.executeIn(job.getEditionId(), () -> task.execute(job));
        } catch (Exception e) {
            failure = e;
        }
        finishAndChain(job, result, failure);
    }

    /**
     * Reports a solve that died to the error tracker, and to the log.
     *
     * <p>Nothing else does. The request that started the solve was accepted
     * long before — {@code 202}, then the screen polls the job — so the
     * failure never passes through {@code GlobalExceptionMapper} and never
     * reaches Sentry: the one class of failure the operator sees and we never
     * do. Here the exception is still in hand, with its stack, and every entry
     * point goes through this method — the screen, the API, MCP and the queue
     * replayed after a restart.</p>
     *
     * <p>Best-effort, and last: a tracker that is down, or absent (no
     * {@code SENTRY_DSN}, where every call is a no-op), must not turn a
     * reported failure into a lost one.</p>
     */
    private static void reportFailure(SolverJob job, Exception failure) {
        LOG.errorf(failure, "Solver job %s (%s, edition %s) failed", job.getId(), job.getType(), job.getEditionId());
        try {
            Sentry.captureException(failure, scope -> {
                scope.setTag("job.type", job.getType().name());
                scope.setTag("job.id", job.getId());
                scope.setTag("edition", job.getEditionId());
                scope.setTag("solve.perimetre", job.getPerimetre() == null ? "complet" : "incremental");
            });
        } catch (RuntimeException e) {
            LOG.warn("The solver failure could not be reported to the error tracker", e);
        }
    }

    /**
     * Records the job's outcome and starts the next queued one, both under the
     * service monitor. Atomic on purpose: between "this job is finished" and
     * "the next one holds the solver" there must be no instant where a
     * concurrent {@link #submit} sees an idle solver — it would start a second
     * run alongside the one being promoted.
     */
    private synchronized void finishAndChain(SolverJob job, Object result, Exception failure) {
        // Closes the score curve before anything else, so the screen stops it
        // on the run's real final score — whether it completed, failed, or was
        // stopped by hand.
        scoreTrace.finish(job.getId());
        SolvePipeline.Interruption interruption = interruptionOf(result);
        if (failure != null) {
            if (job.isCancelRequested()) {
                job.markCancelled(null);
            } else if (shutdownRequested) {
                // Died while the server was going down — a pool already
                // closed, a bean already gone. Not a bug to report: the run
                // is lost, the persisted plan stands.
                LOG.warnf(failure, "Solver job %s was stopped by the server shutdown and its run is lost",
                        job.getId());
                job.markInterrompu(SHUTDOWN_RUN_LOST, null);
            } else {
                job.markFailed(failure);
                reportFailure(job, failure);
            }
        } else if (job.isCancelRequested()) {
            job.markCancelled(result);
        } else if (interruption != null) {
            // Never COMPLETED: a run the server cut short would otherwise be
            // indistinguishable from one that spent its budget.
            job.markInterrompu(describe(interruption), result);
        } else {
            job.markCompleted(result);
        }
        store(job);
        startNext();
        // After startNext, not before: between "finished" and "the next one
        // holds the solver" there is an instant where the solver looks idle,
        // and publishing it would make every screen blink through a state that
        // never really existed.
        jobStream.publish();
    }

    private static final String SHUTDOWN_RUN_LOST =
            "Interrompue par l'arrêt du serveur : le calcul en cours a été perdu.";

    private static SolvePipeline.Interruption interruptionOf(Object result) {
        return switch (result) {
            case ResultatSolve solve -> solve.interruption();
            case ResultatSolveIncremental solve -> solve.interruption();
            case null, default -> null;
        };
    }

    /** What the operator reads on a job the server stopped under: what became of its plan. */
    private static String describe(SolvePipeline.Interruption interruption) {
        if (interruption.partialPlanKept()) {
            return interruption.persistedScore() == null
                    ? "Interrompue par l'arrêt du serveur : le meilleur plan trouvé (" + interruption.partialScore()
                            + ") a été enregistré, aucun plan ne l'était avant."
                    : "Interrompue par l'arrêt du serveur : le meilleur plan trouvé (" + interruption.partialScore()
                            + ") remplace le plan enregistré (" + interruption.persistedScore() + "), qu'il améliore.";
        }
        return "Interrompue par l'arrêt du serveur : le plan enregistré (" + interruption.persistedScore()
                + ") est conservé, le calcul partiel (" + interruption.partialScore() + ") ne l'améliorait pas.";
    }

    private boolean isShutdownRequested() {
        return shutdownRequested;
    }

    private synchronized void chain() {
        startNext();
        jobStream.publish();
    }

    /**
     * Promotes the first queued job that is still wanted. Marking it PENDING
     * before releasing the monitor is what makes it visible to
     * {@link #findActive()} — and therefore what keeps the solver lock held
     * across the hand-over. Must be called while holding the monitor.
     */
    private void startNext() {
        if (shutdownRequested) {
            // The server is going down: promoting a queued job would start a
            // fresh solve the shutdown then has to interrupt. It stays QUEUED
            // in `solver_job` and the next start replays it.
            return;
        }
        QueuedTask suivante;
        while ((suivante = file.pollFirst()) != null) {
            if (suivante.job().getStatus() == JobStatus.CANCELLED) {
                continue;
            }
            suivante.job().markPending();
            store(suivante.job());
            QueuedTask aLancer = suivante;
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

    /**
     * Refuses the caller when a solve holds the solver <b>for the current
     * edition</b> — used by the referential deletes, which that solve would
     * otherwise undo.
     *
     * <p>{@link SolvePipeline#execute} builds its problem from the referential,
     * then persists the result; {@code PlanningPersistenceService.persist}
     * re-upserts the stands and animateurs that result names. So deleting one
     * <em>while</em> a solve is in flight lets the solve put it back, seats and
     * all, minutes after the operator watched it disappear. For an animateur
     * that is personal data returning on its own, on a base that holds
     * minors — so the delete is refused rather than silently reverted.</p>
     *
     * <p><b>Scoped to the job's own edition</b>, not to the solver as a whole.
     * A job runs inside {@code editionContext.executeIn(job.getEditionId(), …)},
     * so its landing persist writes to that edition and no other: a solve on the
     * fallback variant cannot resurrect anything in the edition being prepared
     * next door. Refusing there anyway would break the promise the queue exists
     * to keep — preparing the next edition without waiting in front of the
     * screen. Compared on the <em>job's</em> edition rather than on whoever
     * submitted it, so a run launched on A stays blocking for A even once the
     * operator's tab has moved to B. Same comparison {@link #refuseDuplicate}
     * already makes.</p>
     *
     * <p>Scoped to {@link #findActive}, hence to PENDING and RUNNING only: a
     * QUEUED job has not built its problem yet and will read the referential as
     * it stands when its turn comes, deletion included. Blocking on it would
     * freeze data entry on the very edition it was queued to keep preparing,
     * which is the trade-off {@link #findActive} already documents. Filtering
     * its single result is enough because the solver lock is global and
     * sequential (decision 0008): at most one job is ever PENDING or RUNNING.</p>
     *
     * <p>{@code synchronized} on the same monitor as {@link #submit}, so a job
     * cannot take the solver while this check is being made.</p>
     */
    public synchronized void refuseIfSolving() {
        // No purge here, deliberately: this only looks at PENDING and RUNNING,
        // which are never purgeable, so purging would be a DELETE and a monitor
        // hold per call — 200 of them behind a 200-row bulk delete, serialising
        // the referential screen against job submission for nothing.
        String editionId = editionContext.editionIdCourant();
        findActive()
                .filter(job -> job.getEditionId().equals(editionId))
                .ifPresent(job -> {
                    throw new SolverBusyException(job);
                });
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
            file.removeIf(task -> task.job().getId().equals(jobId));
        } else if (job.getStatus() == JobStatus.PENDING) {
            job.markCancelledIfPending();
        } else if (!job.isFinished()) {
            throw new SolverBusyException(job);
        }
        jobs.remove(jobId);
        oublier(jobId);
        jobStream.publish();
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
            file.removeIf(task -> task.job().getId().equals(jobId));
        } else if (job.getStatus() == JobStatus.PENDING) {
            job.markCancelledIfPending();
        } else if (job.getStatus() == JobStatus.RUNNING) {
            job.requestCancel();
        }
        // A running job is only flagged here; its terminal state is written by
        // finishAndChain once the solver actually stops.
        store(job);
        jobStream.publish();
        return Optional.of(job);
    }

    private void purgeExpiredJobs() {
        Instant cutoff = Instant.now().minus(COMPLETED_JOB_RETENTION);
        jobs.values().removeIf(job -> job.isFinished()
                && job.getFinishedAt() != null
                && job.getFinishedAt().isBefore(cutoff));
        try {
            jobRepository.purgeFinishedBefore(cutoff);
        } catch (RuntimeException e) {
            LOG.warn("Expired solver jobs could not be purged from the database", e);
        }
    }

    /**
     * Mirrors a job's current state into {@code solver_job}. <b>Never fails the
     * job</b>: the queue surviving a restart is a convenience, losing a run
     * because its bookkeeping row could not be written would not be — same
     * contract as the automatic snapshot and the KPI history row.
     */
    private void store(SolverJob job) {
        try {
            jobRepository.record(new LigneJob(
                    job.getId(),
                    job.getEditionId(),
                    job.getEditionNom(),
                    job.getType(),
                    job.getStatus(),
                    job.getSecondsLimit(),
                    job.getPerimetre(),
                    job.getReamorcage(),
                    job.isRejouable(),
                    job.getError(),
                    job.getSubmittedAt(),
                    job.getStartedAt(),
                    job.getFinishedAt()));
        } catch (RuntimeException e) {
            LOG.warnf(e, "Solver job %s could not be persisted; the run itself is unaffected", job.getId());
        }
    }

    private void oublier(String jobId) {
        try {
            jobRepository.delete(jobId);
        } catch (RuntimeException e) {
            LOG.warnf(e, "Solver job %s could not be deleted from the database", jobId);
        }
    }

    /**
     * Replays the persisted queue when the server starts. Failing here never
     * prevents the application from booting: an empty queue is a degraded
     * start, a refused start is an outage.
     */
    void reprendreAuDemarrage(@Observes StartupEvent demarrage) {
        if (!replayAtStartup) {
            return;
        }
        try {
            restaurer();
        } catch (RuntimeException e) {
            LOG.error("Solver queue could not be restored; starting with an empty queue", e);
        }
    }

    /**
     * Rebuilds the in-memory registry from {@code solver_job}: queued jobs go
     * back in the queue in submission order and the first one takes the solver,
     * everything that was <em>holding</em> the solver becomes
     * {@link JobStatus#INTERROMPU}, and finished jobs come back as history
     * (without their result payload, which is not stored — see
     * {@link SolverJobRepository}).
     *
     * <p>Package-private rather than private so a test can replay a queue
     * without restarting a JVM.</p>
     *
     * @return how many jobs went back into the queue
     */
    synchronized int restaurer() {
        purgeExpiredJobs();
        int rejoues = 0;
        for (LigneJob ligne : jobRepository.list()) {
            if (jobs.containsKey(ligne.id())) {
                continue;
            }
            SolverJob job = new SolverJob(ligne);
            jobs.put(job.getId(), job);
            JobTask task = ligne.statut() == JobStatus.QUEUED && ligne.rejouable() ? taskOrNothing(ligne) : null;
            if (task != null) {
                file.addLast(new QueuedTask(job, task));
                rejoues++;
            } else if (!job.isFinished()) {
                // Was holding the solver (or queued without being replayable):
                // nothing can finish it now, and leaving it RUNNING would hold
                // the lock forever.
                job.markInterrompu();
                store(job);
            }
        }
        if (rejoues > 0) {
            LOG.infof("Solver queue restored from the database: %d job(s) waiting again", rejoues);
            startNext();
        }
        return rejoues;
    }

    /**
     * The work of a row to replay, or {@code null} when it cannot be rebuilt —
     * a row hand-edited in the database, or a job type added later without an
     * entry in {@link #replayableTask}. Losing one job to a corrupt row is
     * acceptable; letting it sink the whole queue is not.
     */
    private JobTask taskOrNothing(LigneJob ligne) {
        try {
            return replayableTask(ligne.type(), ligne.secondsLimit(), ligne.scope(), ligne.reamorcage());
        } catch (RuntimeException e) {
            LOG.warnf(e, "Solver job %s cannot be replayed; it comes back interrupted", ligne.id());
            return null;
        }
    }

    /**
     * The container going down — a graceful stop, or a live reload in dev.
     *
     * <p>The running solver is asked to stop and given a moment to hand back
     * its best solution and go through {@link #finishAndChain}, which reads
     * {@link #shutdownRequested} and ends the job {@code INTERROMPU} rather
     * than {@code COMPLETED}. The pool is not interrupted first: an
     * interrupted thread is refused by the connection pool, and the partial
     * plan may deserve to be written. Only what is still running once the
     * grace period is over gets interrupted, as a last resort; a job that
     * still did not end is found {@code RUNNING} at the next start and
     * marked by {@link #restaurer}.</p>
     */
    @PreDestroy
    void shutdown() {
        shutdownRequested = true;
        jobs.values().stream()
                .filter(job -> job.getStatus() == JobStatus.RUNNING)
                .forEach(SolverJob::stopSolver);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_GRACE, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * How long a running solve gets to stop and write its outcome. Below the
     * ten seconds Docker allows a container before killing it, so a graceful
     * stop stays graceful.
     */
    private static final long SHUTDOWN_GRACE = 8;

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
        /** Perimeter of an incremental re-solve; null for the other types. */
        private final ReplanificationScope scope;
        /** Where a full solve was asked to start from (issue #174); null for the other types. */
        private final Reamorcage reamorcage;
        /** Whether this job can rebuild its own problem — see {@link #replayableTask}. */
        private final boolean rejouable;
        private final Instant submittedAt;
        private volatile JobStatus status = JobStatus.PENDING;
        private volatile Instant startedAt;
        private volatile Instant finishedAt;
        private volatile Object result;
        private volatile String error;
        private volatile boolean cancelRequested;
        /** A cancel or a shutdown asked the solver to stop; honoured by {@link #attachSolver} if it is not built yet. */
        private volatile boolean stopRequested;
        private volatile Solver<PlanningEvenement> solver;

        private SolverJob(String id, JobType type, Long secondsLimit, String editionId, String editionNom,
                ReplanificationScope scope, Reamorcage reamorcage, boolean rejouable) {
            this.id = id;
            this.type = type;
            this.secondsLimit = secondsLimit;
            this.editionId = editionId;
            this.editionNom = editionNom;
            this.scope = scope;
            this.reamorcage = reamorcage;
            this.rejouable = rejouable;
            this.submittedAt = Instant.now();
        }

        /**
         * Rebuilds a job from its persisted row (see {@link #restaurer()}).
         * {@code result} stays null: the payload is deliberately not stored,
         * and what it described is already in the database.
         */
        private SolverJob(LigneJob ligne) {
            this.id = ligne.id();
            this.type = ligne.type();
            this.secondsLimit = ligne.secondsLimit();
            this.editionId = ligne.editionId();
            this.editionNom = ligne.editionNom();
            this.scope = ligne.scope();
            this.reamorcage = ligne.reamorcage();
            this.rejouable = ligne.rejouable();
            this.submittedAt = ligne.soumisLe();
            this.status = ligne.statut();
            this.startedAt = ligne.demarreLe();
            this.finishedAt = ligne.termineLe();
            this.error = ligne.erreur();
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

        /**
         * Terminal state of a job the server stopped under. {@code finishedAt}
         * is the moment the restart noticed, not the moment the run died — so
         * the elapsed time of an interrupted job spans the downtime too, which
         * is the honest reading of "it never finished".
         */
        private void markInterrompu() {
            markInterrompu("Interrompue par un redémarrage du serveur : le calcul en cours a été perdu.", null);
        }

        /**
         * Same state, reached while the server is still up: the container
         * stopped under the run, and {@code message} says what became of its
         * partial plan. {@code value} is the result the run still produced,
         * so a screen can read its diagnostic like a cancelled job's.
         */
        private void markInterrompu(String message, Object value) {
            error = message;
            result = value;
            finishedAt = Instant.now();
            status = JobStatus.INTERROMPU;
        }

        private void markCancelled(Object value) {
            result = value;
            finishedAt = Instant.now();
            status = JobStatus.CANCELLED;
        }

        /**
         * Called once by {@link PlanningService#solve} right after building
         * the {@link Solver}, before it blocks on {@code solve()}. Terminates it
         * immediately if a cancel was already requested (the narrow race window
         * between {@link #requestCancel()} and this call).
         */
        private void attachSolver(Solver<PlanningEvenement> solver) {
            this.solver = solver;
            if (stopRequested) {
                solver.terminateEarly();
            }
        }

        /** Stops the solver as soon as it exists, and flags the job as cancelled. */
        private void requestCancel() {
            cancelRequested = true;
            stopSolver();
        }

        /**
         * Stops the solver as soon as it exists, without deciding what the job
         * becomes: a cancel and a server shutdown both end here, and
         * {@link #finishAndChain} tells them apart.
         */
        private void stopSolver() {
            stopRequested = true;
            Solver<PlanningEvenement> currentSolver = solver;
            if (currentSolver != null) {
                currentSolver.terminateEarly();
            }
        }

        private boolean isCancelRequested() {
            return cancelRequested;
        }

        public boolean isFinished() {
            return status == JobStatus.COMPLETED || status == JobStatus.FAILED
                    || status == JobStatus.CANCELLED || status == JobStatus.INTERROMPU;
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

        /** Where a full solve was asked to start from (issue #174); null for an incremental job. */
        public Reamorcage getReamorcage() {
            return reamorcage;
        }

        /** Bookkeeping for {@link #store}, not part of the public job view. */
        ReplanificationScope getPerimetre() {
            return scope;
        }

        /** Bookkeeping for {@link #store}, not part of the public job view. */
        boolean isRejouable() {
            return rejouable;
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
