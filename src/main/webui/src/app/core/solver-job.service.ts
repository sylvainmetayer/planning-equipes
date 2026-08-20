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
import { NotificationService } from './notification.service';
import { GroupeFileResultat, JobType, JobView, MutationsWhatIf, PlanningDiagnostic, PlanningFestival } from './models';

const POLL_INTERVAL_MS = 2000;

/**
 * Called lazily (from methods, never at module scope): $localize only sees
 * translations registered by `loadTranslations()` in `main.ts`, and that
 * runs after this module is imported but before any of these calls execute.
 */
function jobLabel(type: string): string {
  if (type === 'SOLVE') {
    return $localize`:@@job.type.solve:Résolution Timefold`;
  }
  if (type === 'ANALYZE') {
    return $localize`:@@job.type.analyze:Analyse de la solution`;
  }
  if (type === 'SOLVE_FILE') {
    return $localize`:@@job.type.solveFile:Résolution de tous les groupes`;
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
  /**
   * Wall-clock budget the server gave this job, in seconds — `null` when it
   * runs on the server default and the client was never told. Drives the
   * estimated finishing time; see {@link estimatedEndMs}.
   */
  secondsLimit: number | null;
  /** SOLVE_FILE progress, refreshed on every poll — null on other job types. */
  groupeCourantNom: string | null;
  groupeCourant: number | null;
  totalGroupes: number | null;
}

type ResultHandler = (result: unknown) => void;

@Injectable({ providedIn: 'root' })
export class SolverJobService {
  /** null when the solver is idle. */
  readonly activeJob = signal<TrackedJob | null>(null);
  /** Ticks every second so the monitor can display a live duration. */
  readonly now = signal(Date.now());

  /**
   * Pessimistic until the first server answer: callers must not offer an
   * action that the server would refuse.
   */
  readonly solverBusy = computed(() => this.activeJob() !== null || !this.stateKnown());

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
    // A SOLVE_FILE budget applies to EACH queued group: the upper bound is
    // the whole queue's worth of it (each warm-started group may bail out
    // far earlier — see the plateau termination server-side).
    const facteur = job.type === 'SOLVE_FILE' && job.totalGroupes ? job.totalGroupes : 1;
    return job.startedAtMs + job.secondsLimit * facteur * 1000;
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
    const base = job.mine
      ? $localize`:@@job.runningMine:${job.label}:jobLabel: est en cours depuis ${duration}:duration:.`
      : $localize`:@@job.runningOther:${job.label}:jobLabel: est en cours depuis ${duration}:duration: (démarré depuis une autre session).`;
    if (job.type === 'SOLVE_FILE' && job.groupeCourant && job.totalGroupes) {
      const progression = $localize`:@@job.fileProgress:Groupe ${job.groupeCourant}:current:/${job.totalGroupes}:total: — ${job.groupeCourantNom ?? ''}:groupe:.`;
      return `${base} ${progression}`;
    }
    return base;
  });

  private readonly api = inject(ApiService);
  private readonly notifications = inject(NotificationService);
  private readonly stateKnown = signal(false);
  private readonly resultHandlers = new Map<JobType, ResultHandler[]>();
  private started = false;
  /** Non-null only while a job runs: see {@link updateTicker}. */
  private tickHandle: ReturnType<typeof setInterval> | null = null;

  /** Starts the shared polling loop. Called once by the app shell. */
  start(): void {
    if (this.started) {
      return;
    }
    this.started = true;
    void this.sync();
    setInterval(() => void this.sync(), POLL_INTERVAL_MS);
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
  onResult(type: JobType, handler: ResultHandler): () => void {
    const handlers = this.resultHandlers.get(type);
    if (handlers) {
      handlers.push(handler);
    } else {
      this.resultHandlers.set(type, [handler]);
    }
    return () => {
      const registered = this.resultHandlers.get(type);
      const index = registered?.indexOf(handler) ?? -1;
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
  submitSolveFromReferenceData(seconds?: number): Promise<JobView> {
    return this.submit('/api/solve/async/reference-data', {}, 'SOLVE', seconds);
  }

  submitAnalyze(planning: PlanningFestival, seconds?: number): Promise<JobView> {
    return this.submit('/api/solve/analyze/async', planning, 'ANALYZE', seconds);
  }

  /** Server-side-built counterpart of {@link submitAnalyze}. */
  submitAnalyzeFromReferenceData(seconds?: number): Promise<JobView> {
    return this.submit('/api/solve/analyze/async/reference-data', {}, 'ANALYZE', seconds);
  }

  /**
   * The "résoudre tous les groupes" queue (issue #167): one sequential solve
   * per flagged groupe de créneaux, warm-started from each group's last
   * snapshot, the active group last. Progress is reported through the normal
   * job polling (`groupeCourant`/`totalGroupes`).
   */
  submitSolveFile(seconds?: number): Promise<JobView> {
    return this.submit('/api/solve/file/async', {}, 'SOLVE_FILE', seconds);
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

  private async submit(endpoint: string, payload: unknown, type: JobType, seconds?: number): Promise<JobView> {
    this.notifications.requestDesktopPermission();
    const url = seconds ? `${endpoint}?seconds=${encodeURIComponent(seconds)}` : endpoint;
    let job: JobView;
    try {
      // Raw errors: the 409 branch below needs the status and the body.
      job = await this.api.postPreservingHttpError<JobView>(url, payload);
    } catch (error) {
      throw this.explainSubmitFailure(error);
    }
    this.adopt(job, true);
    this.notifications.notify({
      title: $localize`:@@job.started:${jobLabel(type)}:jobLabel: démarrée`,
      message: $localize`:@@job.startedMessage:Cela s'exécute sur le serveur — vous pouvez continuer à utiliser l'application, depuis ce navigateur ou un autre.`,
      timeout: 5000
    });
    return job;
  }

  // 409: the server already runs a solve, possibly submitted by another client.
  private explainSubmitFailure(error: unknown): Error {
    if (error instanceof HttpErrorResponse && error.status === 409 && error.error) {
      const running = error.error as JobView;
      this.adopt(running, false);
      const duration = formatDuration(running.elapsedSeconds);
      return new Error(
        $localize`:@@job.alreadyRunning:${jobLabel(running.type)}:jobLabel: est déjà en cours (${duration}:duration:). Veuillez attendre la fin avant d'en démarrer une autre.`
      );
    }
    return toError(error);
  }

  // Single source of truth: what the server reports as its active job.
  private async sync(): Promise<void> {
    const server = await this.fetchActiveJob();
    if (server === undefined) {
      return; // transient network error: keep the last known state
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
      const mineChanged = mine && !tracked.mine;
      // The queue's progress is the one thing that moves between two polls of
      // the same job: refresh it, or « groupe 1/N » would stay on screen for
      // the whole file.
      const progressChanged =
        tracked.groupeCourant !== (job.groupeCourant ?? null) ||
        tracked.groupeCourantNom !== (job.groupeCourantNom ?? null);
      if (mineChanged || progressChanged) {
        this.activeJob.set({
          ...tracked,
          mine: tracked.mine || mine,
          groupeCourantNom: job.groupeCourantNom ?? null,
          groupeCourant: job.groupeCourant ?? null,
          totalGroupes: job.totalGroupes ?? null
        });
      }
      return;
    }
    const entry: TrackedJob = {
      id: job.id,
      type: job.type,
      label: jobLabel(job.type),
      startedAtMs: Date.now() - (Number(job.elapsedSeconds) || 0) * 1000,
      mine,
      secondsLimit: job.secondsLimit == null ? null : Number(job.secondsLimit),
      groupeCourantNom: job.groupeCourantNom ?? null,
      groupeCourant: job.groupeCourant ?? null,
      totalGroupes: job.totalGroupes ?? null
    };
    this.activeJob.set(entry);
    // A submit() adopts its job without waiting for the next poll: start the
    // duration clock right away rather than up to two seconds later.
    this.updateTicker();
    if (!entry.mine) {
      const duration = formatDuration(job.elapsedSeconds);
      this.notifications.notify({
        title: $localize`:@@job.alreadyRunningTitle:${entry.label}:jobLabel: déjà en cours`,
        message: $localize`:@@job.alreadyRunningMessage:Démarrage depuis une autre session il y a ${duration}:duration:. Les actions du solveur sont verrouillées jusqu'à la fin.`
      });
    }
  }

  private async reportFinishedJob(entry: TrackedJob): Promise<void> {
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
      return;
    }
    const duration = formatDuration(job.elapsedSeconds);
    this.notifications.notify({
      title: $localize`:@@job.completedTitle:${entry.label}:jobLabel: terminée en ${duration}:duration:`,
      message: job.type === 'SOLVE_FILE' ? describeFileResult(job.result) : describeResult(job.result),
      variant: 'success',
      desktop: true
    });
    // Raised here rather than by the SOLVE/ANALYZE page's onResult handler:
    // that handler only exists while its page is mounted, so a solve finishing
    // after the user navigated away would otherwise never surface this. This
    // runs unconditionally, whichever page (if any) is open when the job ends.
    // A SOLVE_FILE result is a per-group summary, not a diagnostic.
    if (job.type !== 'SOLVE_FILE') {
      const diagnostic = job.result as PlanningDiagnostic | null;
      if (diagnostic) {
        this.notifications.notifyFeasibility(diagnostic.faisabilite, diagnostic.hardScore);
      }
    }
    // Iterate a copy: a handler may unregister itself (or its page) while running.
    [...(this.resultHandlers.get(job.type) ?? [])].forEach((handler) => handler(job.result));
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

function describeResult(result: unknown): string {
  if (!result) {
    return '';
  }
  const diagnostic = result as PlanningDiagnostic;
  const score = diagnostic.score;
  const postesNonPourvus = diagnostic.postesNonPourvus;
  return $localize`:@@job.result:Score ${score}:score: — ${postesNonPourvus}:count: poste(s) non pourvu(s).`;
}

/**
 * A SOLVE_FILE payload is one line per group. The notification only counts:
 * every group's snapshot is one click away on the Snapshots page, and the
 * detail lives in the solver page's summary.
 */
function describeFileResult(result: unknown): string {
  if (!Array.isArray(result)) {
    return '';
  }
  const resultats = result as GroupeFileResultat[];
  const resolus = resultats.filter((r) => r.statut === 'RESOLU').length;
  const echecs = resultats.filter((r) => r.statut === 'ECHEC').length;
  const compte = $localize`:@@job.fileResult:${resolus}:count:/${resultats.length}:total: groupe(s) résolu(s).`;
  return echecs > 0 ? `${compte} ${$localize`:@@job.fileResultEchecs:${echecs}:count: en échec.`}` : compte;
}

