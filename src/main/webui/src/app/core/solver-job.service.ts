// Background solver jobs: submit, follow, and report without blocking the UI.
//
// A solve can run for minutes. Instead of awaiting a long HTTP request, the
// browser posts to the /async endpoints and follows the job through the server.
//
// The "a solver run is in progress" state belongs to the server, not to the
// browser: this service polls /api/jobs/active, so a solve started in one tab
// locks the solver buttons and shows the same elapsed time in another browser,
// in a private window, or after clearing the local storage.

import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { ApiService, toError } from './api.service';
import { EditionStore } from './edition.store';
import { NotificationService } from './notification.service';
import {
  JobType,
  JobView,
  MutationsWhatIf,
  PerimetreReplanification,
  PlanningDiagnostic,
  PlanningFestival,
  ResultatSolveIncremental
} from './models';

/**
 * How often the server-side solver lock is re-read. Two paces rather than one:
 * a running job needs a responsive elapsed time and a prompt hand-over of its
 * result, while an idle solver only needs to be noticed reasonably soon. Idling
 * at the fast pace cost one request every two seconds per open tab, forever,
 * for a state that had not changed in hours.
 */
const POLL_ACTIVE_MS = 2000;
const POLL_IDLE_MS = 30000;

/**
 * Called lazily (from methods, never at module scope): $localize only sees
 * translations registered by `loadTranslations()` in `main.ts`, and that
 * runs after this module is imported but before any of these calls execute.
 */
function jobLabel(type: string): string {
  if (type === 'SOLVE') {
    return $localize`:@@job.type.solve:Résolution Timefold`;
  }
  if (type === 'SOLVE_INCREMENTAL') {
    return $localize`:@@job.type.solveIncremental:Replanification incrémentale`;
  }
  if (type === 'ANALYZE') {
    return $localize`:@@job.type.analyze:Analyse de la solution`;
  }
  return type;
}

function statusLabel(status: string): string {
  if (status === 'FAILED') {
    return $localize`:@@job.status.failed:échouée`;
  }
  if (status === 'CANCELLED') {
    return $localize`:@@job.status.cancelled:annulée`;
  }
  if (status === 'INTERROMPU') {
    return $localize`:@@job.status.interrompu:interrompue par un redémarrage`;
  }
  return status.toLowerCase();
}

/** Job held by the server, as followed by this client. */
export interface TrackedJob {
  id: string;
  type: JobType;
  label: string;
  /** Local timestamp rebased on the server elapsed time. */
  startedAtMs: number;
  /** True when this browser submitted the job. */
  mine: boolean;
  /** Edition the job writes to; null when the server did not say (defensive). */
  editionId: string | null;
  /** Display name of that edition, for every message naming the job. */
  editionNom: string | null;
  /**
   * Wall-clock budget the server gave this job, in seconds — `null` when it
   * runs on the server default and the client was never told. Drives the
   * estimated finishing time; see {@link estimatedEndMs}.
   */
  secondsLimit: number | null;
}

/**
 * What each kind of job hands back when it completes. `JobType` *is* the
 * discriminant — the payload's shape is a function of it — but nothing said so
 * to the compiler: every result crossed the application as `unknown` and was
 * re-asserted by a cast at each consumer. Adding a job type, or changing a
 * payload server-side, then broke nothing at compile time and everything at
 * runtime, on the user's screen, on an `undefined`.
 *
 * <p>SOLVE is genuinely a union: a full solve answers a bare diagnostic, an
 * incremental one wraps it (issue #86) — see {@link extraireDiagnostic}.</p>
 */
export interface JobResults {
  SOLVE: PlanningDiagnostic | ResultatSolveIncremental;
  SOLVE_INCREMENTAL: ResultatSolveIncremental;
  ANALYZE: PlanningDiagnostic;
}

/** What a finished job of type `T` is handed to. */
export type ResultHandler<T extends JobType> = (result: JobResults[T] | null) => void;

@Injectable({ providedIn: 'root' })
export class SolverJobService {
  /** null when the solver is idle. */
  readonly activeJob = signal<TrackedJob | null>(null);
  /**
   * Solves planned behind the running one, in the order they will start.
   * Server-side state shared by every client: a run planned from another
   * browser shows up (and can be removed) here too.
   */
  readonly file = signal<JobView[]>([]);
  /** Ticks every second so the monitor can display a live duration. */
  readonly now = signal(Date.now());

  /**
   * Pessimistic until the first server answer: callers must not offer an
   * action that the server would refuse.
   */
  readonly solverBusy = computed(() => this.activeJob() !== null || !this.stateKnown());

  /**
   * True while writes to the CURRENT edition must wait. Unlike
   * {@link solverBusy} (the global "no second job can start" lock), a job
   * solving another edition does not freeze this one: after switching
   * editions during a long solve, data entry and scenario imports stay
   * available here — the job keeps reading and writing the edition it was
   * submitted for (see EditionContext.executeDans on the backend). Stays
   * pessimistic whenever either side's edition is not known yet.
   */
  readonly editingLocked = computed(() => {
    const job = this.activeJob();
    if (!job) {
      return !this.stateKnown();
    }
    const courante = this.editions.courant()?.id;
    return courante == null || job.editionId == null || job.editionId === courante;
  });

  /**
   * When the running job is due to end at the latest: its start plus the
   * budget it was submitted with. Null when no job runs or when the server
   * did not report a limit.
   *
   * <p>An upper bound, not a promise: the solver stops early when it exhausts
   * its unimproved-seconds budget, and a job can be cancelled by hand.</p>
   */
  readonly estimatedEndMs = computed(() => {
    const job = this.activeJob();
    if (!job || job.secondsLimit == null || job.secondsLimit <= 0) {
      return null;
    }
    return job.startedAtMs + job.secondsLimit * 1000;
  });

  /** Seconds left before {@link estimatedEndMs}, floored at 0; null when unknown. */
  readonly remainingSeconds = computed(() => {
    const end = this.estimatedEndMs();
    return end === null ? null : Math.max(0, Math.round((end - this.now()) / 1000));
  });

  readonly activeJobDescription = computed(() => {
    const job = this.activeJob();
    if (!job) {
      return this.stateKnown() ? '' : $localize`:@@job.stateUnknown:L'état du solveur n'est pas encore connu.`;
    }
    const duration = formatDuration(elapsedSeconds(job, this.now()));
    const edition = job.editionNom ?? job.editionId ?? '?';
    return job.mine
      ? $localize`:@@job.runningMine:${job.label}:jobLabel: est en cours sur l'édition « ${edition}:edition: » depuis ${duration}:duration:.`
      : $localize`:@@job.runningOther:${job.label}:jobLabel: est en cours sur l'édition « ${edition}:edition: » depuis ${duration}:duration: (démarré depuis une autre session).`;
  });

  private readonly api = inject(ApiService);
  private readonly editions = inject(EditionStore);
  private readonly notifications = inject(NotificationService);
  private readonly stateKnown = signal(false);
  /** Ids submitted from this browser, including the ones still queued. */
  private readonly mesJobs = new Set<string>();
  /**
   * Stored loosely and handed out typed: a Map cannot express "the handler
   * list of key T takes JobResults[T]". The one cast lives at the single
   * dispatch point below, where the key and the payload come from the same
   * JobView.
   */
  private readonly resultHandlers = new Map<JobType, ResultHandler<JobType>[]>();
  private started = false;
  /** Non-null only while a job runs: see {@link updateTicker}. */
  private tickHandle: ReturnType<typeof setInterval> | null = null;
  /** Non-null while the polling loop runs: see {@link start} and {@link stop}. */
  private pollHandle: ReturnType<typeof setInterval> | null = null;
  /** Pace {@link pollHandle} currently runs at, so it is only rescheduled on a real change. */
  private pollIntervalMs = 0;
  /** Id of the job the last poll saw, to reload the queue only on a real hand-over. */
  private dernierJobVu: string | null = null;

  /** Starts the shared polling loop. Called once by the app shell. */
  start(): void {
    if (this.started) {
      return;
    }
    this.started = true;
    void this.sync();
    this.schedulePolling();
  }

  /**
   * Stops the shared polling loop. This service is `providedIn: 'root'`, so it
   * outlives the shell that started it: without this, a session expiring would
   * leave the loop running on the login page, every tick producing a fresh 401
   * and a fresh redirect. Called from the shell's `DestroyRef`; `start()` works
   * again afterwards, as a new shell after a re-login does.
   */
  stop(): void {
    this.started = false;
    if (this.pollHandle !== null) {
      clearInterval(this.pollHandle);
      this.pollHandle = null;
      this.pollIntervalMs = 0;
    }
  }

  /**
   * Registers what to do with the payload of a finished job, and returns the
   * function unregistering it — a lazy-loaded page is instantiated again on
   * every navigation, so a handler that is never removed would pile up (and
   * keep the destroyed component alive). Results are always dispatched,
   * whoever started the job: a solve launched from another browser also
   * updates this one when it completes. Multiple independent callers can
   * subscribe to the same job type (e.g. the app shell and the page showing it).
   */
  onResult<T extends JobType>(type: T, handler: ResultHandler<T>): () => void {
    const registered = handler as ResultHandler<JobType>;
    const handlers = this.resultHandlers.get(type);
    if (handlers) {
      handlers.push(registered);
    } else {
      this.resultHandlers.set(type, [registered]);
    }
    return () => {
      const registered = this.resultHandlers.get(type);
      const index = registered?.indexOf(handler as ResultHandler<JobType>) ?? -1;
      if (registered && index >= 0) {
        registered.splice(index, 1);
      }
    };
  }

  /**
   * Submits a solve. The server always analyzes the result as part of the same
   * job (see {@code SolverJobService.submitSolve} on the backend), so the
   * SOLVE result handler receives a {@link PlanningDiagnostic} directly — no
   * separate follow-up action or client-side state is needed. The solved
   * planning itself is not part of the payload; it is persisted server-side
   * and fetched from `/api/planning/persisted` by the dedicated screens.
   */
  submitSolve(planning: PlanningFestival, seconds?: number): Promise<JobView> {
    return this.submit('/api/solve/async', planning, 'SOLVE', seconds);
  }

  /**
   * Submits a solve whose problem is built entirely server-side from the
   * persisted reference data: no planning is uploaded, so even a very large
   * scenario (whose planning JSON would exceed the HTTP body limit and fail
   * with a network error) can be solved.
   */
  /**
   * @param enFile queue the solve behind the running one instead of being
   *               refused; it starts by itself, and reads the referential as
   *               it stands at that moment
   */
  submitSolveFromReferenceData(seconds?: number, enFile = false): Promise<JobView> {
    return this.submit('/api/solve/async/reference-data', {}, 'SOLVE', seconds, enFile);
  }

  /**
   * Incremental re-solve (issue #86): the server starts from the persisted
   * plan, pins whatever a late change did not invalidate and `perimetre` does
   * not re-open, then re-fills only the rest. Its SOLVE payload is a
   * {@link ResultatSolveIncremental}, not a bare diagnostic — see
   * {@link extraireDiagnostic}. Without `seconds`, the server applies its own
   * short budget rather than the full-solve one.
   */
  submitSolveIncremental(
    perimetre: PerimetreReplanification,
    seconds?: number,
    enFile = false
  ): Promise<JobView> {
    return this.submit('/api/solve/incremental/async', perimetre, 'SOLVE_INCREMENTAL', seconds, enFile);
  }

  /**
   * Takes a planned solve out of the queue before it starts. Nothing ran, so
   * there is nothing to stop and no partial result to keep.
   */
  async retirerDeLaFile(jobId: string): Promise<void> {
    await this.api.delete(`/api/jobs/${encodeURIComponent(jobId)}`);
    this.mesJobs.delete(jobId);
    await this.rafraichirFile();
  }

  submitAnalyze(planning: PlanningFestival, seconds?: number): Promise<JobView> {
    return this.submit('/api/solve/analyze/async', planning, 'ANALYZE', seconds);
  }

  /** Server-side-built counterpart of {@link submitAnalyze}. */
  submitAnalyzeFromReferenceData(seconds?: number): Promise<JobView> {
    return this.submit('/api/solve/analyze/async/reference-data', {}, 'ANALYZE', seconds);
  }

  /**
   * Analyzes a what-if variant (issue #73): the server applies the mutations to
   * in-memory copies of the reference data and analyzes the result. Nothing is
   * written — an ANALYZE job never persists a plan, and the variant lives in
   * the request.
   */
  submitWhatIfAnalyze(mutations: MutationsWhatIf, seconds?: number): Promise<JobView> {
    return this.submit('/api/what-if/analyze', mutations, 'ANALYZE', seconds);
  }

  /**
   * Stops a job started by mistake. The server terminates the underlying
   * solver early (it still persists/analyzes whatever it found so far) rather
   * than killing it outright, so the job ends up CANCELLED through the normal
   * polling flow.
   */
  async cancel(jobId: string): Promise<void> {
    await this.api.post(`/api/jobs/${encodeURIComponent(jobId)}/cancel`, {});
  }

  /** Every job, newest-submitted first — used for history (e.g. last run date), not polled. */
  listJobs(): Promise<JobView[]> {
    return this.api.get<JobView[]>('/api/jobs');
  }

  private async submit(
    endpoint: string,
    payload: unknown,
    type: JobType,
    seconds?: number,
    enFile = false
  ): Promise<JobView> {
    this.notifications.requestDesktopPermission();
    const params = new URLSearchParams();
    if (seconds) {
      params.set('seconds', String(seconds));
    }
    if (enFile) {
      params.set('enFile', 'true');
    }
    const url = params.size > 0 ? `${endpoint}?${params}` : endpoint;
    let job: JobView;
    try {
      // Raw errors: the 409 branch below needs the status and the body.
      job = await this.api.postPreservingHttpError<JobView>(url, payload);
    } catch (error) {
      throw this.explainSubmitFailure(error);
    }
    // Remembered whichever way it goes: a job planned here must still count as
    // "mine" when it starts on its own, minutes later.
    this.mesJobs.add(job.id);
    if (job.status === 'QUEUED') {
      const edition = job.editionNom ?? job.editionId ?? '?';
      this.notifications.notify({
        title: $localize`:@@job.queued:${jobLabel(type)}:jobLabel: planifiée`,
        message: $localize`:@@job.queuedMessage:Elle démarrera d'elle-même sur l'édition « ${edition}:edition: » dès que la tâche en cours sera terminée. Vous pouvez fermer cet écran.`,
        timeout: 6000
      });
      await this.rafraichirFile();
      return job;
    }
    this.adopt(job, true);
    this.notifications.notify({
      title: $localize`:@@job.started:${jobLabel(type)}:jobLabel: démarrée`,
      message: $localize`:@@job.startedMessage:Cela s'exécute sur le serveur — vous pouvez continuer à utiliser l'application, depuis ce navigateur ou un autre.`,
      timeout: 5000
    });
    return job;
  }

  /**
   * Reports a refused launch and hands the caller the error to display.
   *
   * <p>The snack bar is not decoration: every other outcome of a solver job —
   * started, planned, finished, failed — pops one and lands in the
   * notifications log. A refusal that only wrote into the page's result panel
   * looked, from the button, exactly like nothing happening at all.</p>
   */
  private explainSubmitFailure(error: unknown): Error {
    const refus = this.decrireRefus(error);
    this.notifications.notify({
      title: $localize`:@@job.refusedTitle:Lancement refusé`,
      message: refus.message,
      variant: 'error'
    });
    return refus;
  }

  /**
   * 409: something already covers this. Either the solver is busy and the
   * caller did not ask to queue, or the very same run is already planned on
   * that edition — two different messages, told apart by the conflicting job's
   * own status rather than by a second error shape.
   */
  private decrireRefus(error: unknown): Error {
    if (error instanceof HttpErrorResponse && error.status === 409 && error.error) {
      const conflit = error.error as JobView;
      if (conflit.status === 'QUEUED') {
        const edition = conflit.editionNom ?? conflit.editionId ?? '?';
        return new Error(
          $localize`:@@job.alreadyQueued:${jobLabel(conflit.type)}:jobLabel: est déjà planifiée sur l'édition « ${edition}:edition: ». Retirez-la de la file si vous voulez la replanifier.`
        );
      }
      this.adopt(conflit, false);
      const duration = formatDuration(conflit.elapsedSeconds);
      const edition = conflit.editionNom ?? conflit.editionId ?? '?';
      return new Error(
        $localize`:@@job.alreadyRunningEdition:${jobLabel(conflit.type)}:jobLabel: est déjà en cours sur l'édition « ${edition}:edition: » (${duration}:duration:). Planifiez-la pour qu'elle démarre à la suite, ou attendez la fin.`
      );
    }
    return toError(error);
  }

  /**
   * (Re)schedules the loop at the pace the current state calls for. Called
   * after every poll and after every local adoption, so submitting a job from
   * this tab switches to the fast pace immediately instead of waiting out the
   * idle interval.
   */
  private schedulePolling(): void {
    if (!this.started) {
      return;
    }
    // A non-empty queue counts as "busy" even with no job adopted yet: a run
    // planned from here is about to take the solver, and waiting out a whole
    // idle interval to notice would make it look like nothing happened.
    const idle = this.activeJob() === null && this.file().length === 0;
    const wanted = idle ? POLL_IDLE_MS : POLL_ACTIVE_MS;
    if (this.pollHandle !== null && this.pollIntervalMs === wanted) {
      return;
    }
    if (this.pollHandle !== null) {
      clearInterval(this.pollHandle);
    }
    this.pollIntervalMs = wanted;
    this.pollHandle = setInterval(() => void this.sync(), wanted);
  }

  // Single source of truth: what the server reports as its active job.
  private async sync(): Promise<void> {
    const server = await this.fetchActiveJob();
    if (server === undefined) {
      return; // transient network error: keep the last known state
    }
    // The queue only ever changes when the solver hands over: re-reading it on
    // every tick doubled the request count to re-publish an identical list.
    const vu = server?.id ?? null;
    if (vu !== this.dernierJobVu) {
      this.dernierJobVu = vu;
      await this.rafraichirFile();
    }
    const tracked = this.activeJob();
    if (tracked && (!server || server.id !== tracked.id)) {
      this.activeJob.set(null);
      await this.reportFinishedJob(tracked);
    }
    if (server) {
      // adopt() keeps the "mine" flag for a job this client already follows.
      this.adopt(server, false);
    }
    if (!this.stateKnown()) {
      this.stateKnown.set(true);
    }
    this.updateTicker();
    this.schedulePolling();
  }

  /** Server-side queue, re-read on every poll: another client may add to it. */
  private async rafraichirFile(): Promise<void> {
    const file = await this.api.get<JobView[]>('/api/jobs/file').catch(() => null);
    if (file) {
      this.file.set(file);
      this.schedulePolling();
    }
  }

  /** 204 means idle, undefined means "could not ask". */
  private async fetchActiveJob(): Promise<JobView | null | undefined> {
    try {
      const response = await this.api.getResponse<JobView>('/api/jobs/active');
      return response.status === 204 ? null : response.body;
    } catch {
      return undefined;
    }
  }

  /**
   * Runs the one-second clock only while a job is running. It exists solely to
   * refresh the displayed duration, and every write schedules a change
   * detection pass in this zoneless app — ticking on an idle solver would keep
   * re-rendering whatever page is open, forever, for nothing.
   */
  private updateTicker(): void {
    const running = this.activeJob() !== null;
    if (running && this.tickHandle === null) {
      this.now.set(Date.now());
      this.tickHandle = setInterval(() => this.now.set(Date.now()), 1000);
    } else if (!running && this.tickHandle !== null) {
      clearInterval(this.tickHandle);
      this.tickHandle = null;
    }
  }

  // Starts the local view of the server-side job. Elapsed time is rebased on
  // the server value so every client shows the same duration, whatever its
  // clock or when it connected. A job already followed is left untouched, so
  // consumers only see real transitions and not every poll tick.
  private adopt(job: JobView, mine: boolean): void {
    const tracked = this.activeJob();
    if (tracked?.id === job.id) {
      if (mine && !tracked.mine) {
        this.activeJob.set({ ...tracked, mine: true });
      }
      return;
    }
    // A job planned from this browser stays "mine" when it starts on its own,
    // long after the submit() that queued it.
    const mienne = mine || this.mesJobs.has(job.id);
    const entry: TrackedJob = {
      id: job.id,
      type: job.type,
      label: jobLabel(job.type),
      startedAtMs: Date.now() - (Number(job.elapsedSeconds) || 0) * 1000,
      mine: mienne,
      editionId: job.editionId ?? null,
      editionNom: job.editionNom ?? null,
      secondsLimit: job.secondsLimit == null ? null : Number(job.secondsLimit)
    };
    this.activeJob.set(entry);
    // A submit() adopts its job without waiting for the next poll: start the
    // duration clock — and the fast polling pace — right away rather than up
    // to a whole idle interval later.
    this.updateTicker();
    this.schedulePolling();
    if (mienne && !mine) {
      // Discovered by polling, yet ours: the queue just handed it the solver.
      const edition = entry.editionNom ?? entry.editionId ?? '?';
      this.notifications.notify({
        title: $localize`:@@job.queuedStartedTitle:${entry.label}:jobLabel: planifiée : c'est parti`,
        message: $localize`:@@job.queuedStartedMessage:La tâche en attente vient de prendre le solveur, sur l'édition « ${edition}:edition: ».`,
        timeout: 6000
      });
      return;
    }
    if (!entry.mine) {
      const duration = formatDuration(job.elapsedSeconds);
      const edition = entry.editionNom ?? entry.editionId ?? '?';
      this.notifications.notify({
        title: $localize`:@@job.alreadyRunningTitle:${entry.label}:jobLabel: déjà en cours`,
        message: $localize`:@@job.alreadyRunningMessage:Démarrage depuis une autre session il y a ${duration}:duration:, sur l'édition « ${edition}:edition: ». Les actions du solveur sont verrouillées jusqu'à la fin ; la saisie n'est bloquée que sur cette édition-là.`
      });
    }
  }

  private async reportFinishedJob(entry: TrackedJob): Promise<void> {
    this.mesJobs.delete(entry.id);
    const job = await this.api.get<JobView>(`/api/jobs/${entry.id}`).catch(() => null);
    if (!job) {
      this.notifications.notify({
        title: $localize`:@@job.finishedUnknownTitle:${entry.label}:jobLabel: terminée`,
        message: $localize`:@@job.finishedUnknownMessage:Le serveur ne connaît plus cette tâche (redémarrage ou purge de rétention).`
      });
      return;
    }
    if (job.status !== 'COMPLETED') {
      this.notifications.notify({
        title: $localize`:@@job.notCompletedTitle:${entry.label}:jobLabel: ${statusLabel(job.status)}:status:`,
        message: job.error ?? '',
        variant: 'error',
        desktop: true,
        timeout: 0
      });
      // A cancelled job is not an empty job: the server keeps its partial
      // result (a stopped solve is still persisted and analyzed, a stopped
      // queue keeps its per-group summary — see SolverJobService.cancel on
      // the backend). The pages must still consume it, or their caches
      // silently diverge from what the database now holds.
      if (job.status === 'CANCELLED' && job.result != null) {
        this.dispatchResult(job);
      }
      return;
    }
    const duration = formatDuration(job.elapsedSeconds);
    this.notifications.notify({
      title: $localize`:@@job.completedTitle:${entry.label}:jobLabel: terminée en ${duration}:duration:`,
      message: describeResult(job.result),
      variant: 'success',
      desktop: true
    });
    // Raised here rather than by the SOLVE/ANALYZE page's onResult handler:
    // that handler only exists while its page is mounted, so a solve finishing
    // after the user navigated away would otherwise never surface this. This
    // runs unconditionally, whichever page (if any) is open when the job ends.
    // extraireDiagnostic, not a bare cast: an incremental solve wraps its
    // diagnostic (issue #86), and the feasibility notification must fire for it
    // exactly like for a full solve.
    const diagnostic = extraireDiagnostic(job.result);
    if (diagnostic) {
      this.notifications.notifyFeasibility(diagnostic.faisabilite, diagnostic.hardScore);
    }
    // Iterate a copy: a handler may unregister itself (or its page) while running.
    this.dispatchResult(job);
  }
  /**
   * The one place a job payload is narrowed. `JobView.result` is `unknown`
   * because it comes straight off the wire; the key and the payload here come
   * from the same `JobView`, so the pairing the cast asserts is exactly the
   * one the server produced. Iterates a copy: a handler may unregister itself
   * (or its page) while running.
   */
  private dispatchResult(job: JobView): void {
    const result = job.result as JobResults[JobType] | null;
    [...(this.resultHandlers.get(job.type) ?? [])].forEach((handler) => handler(result));
  }
}

export function elapsedSeconds(job: TrackedJob, nowMs: number): number {
  return Math.max(0, Math.round((nowMs - job.startedAtMs) / 1000));
}

export function formatDuration(totalSeconds: number): string {
  const seconds = Math.max(0, Number(totalSeconds) || 0);
  const minutes = Math.floor(seconds / 60);
  return minutes > 0 ? `${minutes}m ${String(seconds % 60).padStart(2, '0')}s` : `${seconds}s`;
}

/**
 * The diagnostic inside a SOLVE payload: the payload itself for a full solve,
 * the `diagnostic` field for an incremental one (issue #86). Null when the job
 * carries no result at all — a cancelled job, or one still running.
 */
export function extraireDiagnostic(result: unknown): PlanningDiagnostic | null {
  if (!result || typeof result !== 'object') {
    return null;
  }
  const incremental = result as Partial<ResultatSolveIncremental>;
  return (incremental.diagnostic ?? (result as PlanningDiagnostic)) || null;
}

function describeResult(result: unknown): string {
  const diagnostic = extraireDiagnostic(result);
  if (!diagnostic) {
    return '';
  }
  const score = diagnostic.score;
  const postesNonPourvus = diagnostic.postesNonPourvus;
  return $localize`:@@job.result:Score ${score}:score: — ${postesNonPourvus}:count: poste(s) non pourvu(s).`;
}

