// Background solver jobs: submit, follow, and report without blocking the UI.
//
// A solve can run for minutes. Instead of awaiting a long HTTP request, the
// browser posts to the /async endpoints and follows the job through the server.
//
// The "a solver run is in progress" state belongs to the server, not to the
// browser: this service follows /api/jobs, so a solve started in one tab locks
// the solver buttons and shows the same elapsed time in another browser, in a
// private window, or after clearing the local storage.
//
// It follows it twice, on purpose. A server-sent events stream
// (/api/jobs/stream, owned by solver-stream.ts) carries every transition in the
// second it happens, and a slow poll of /api/jobs/active keeps running
// underneath as the safety net. Dropping the poll would be a regression, not a
// simplification: SSE fails SILENTLY. A proxy that buffers, a connection cut
// that never reconnects, and the screen stays frozen on a stale state without
// a word — strictly worse than polling, which repairs itself at the next tick.
// So the poll keeps the lead until the stream has proved it is alive, and
// takes it back the moment the stream reports it lost it.

import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { ApiService, toError } from './api.service';
import { EditionStore } from './edition.store';
import { NotificationService } from './notification.service';
import {
  JobType,
  JobView,
  PerimetreReplanification,
  PlanningDiagnostic,
  PlanningEvenement,
  ImpactPublication,
  ImpactValidations,
  PreviousPlan,
  Reamorcage,
  ReamorcageEffectue,
  ResultatSolve,
  ResultatSolveIncremental,
  ScoreTrace,
} from './models';
import { applyScoreDelta } from './score-trace';
import { JobsStreamState, SolverStream } from './solver-stream';

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
    return $localize`:@@job.type.solve:Calcul du planning`;
  }
  if (type === 'SOLVE_INCREMENTAL') {
    return $localize`:@@job.type.solveIncremental:Replanification incrémentale`;
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
    return $localize`:@@job.status.interrompu:interrompue par l'arrêt du serveur`;
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
 * <p>SOLVE is genuinely a union: both a full solve (issue #274) and an
 * incremental one (issue #86) wrap the diagnostic, and a job finished before
 * either shipped still answers a bare one — see {@link extraireDiagnostic}.</p>
 */
export interface JobResults {
  SOLVE: PlanningDiagnostic | ResultatSolve | ResultatSolveIncremental;
  SOLVE_INCREMENTAL: ResultatSolveIncremental;
}

/** What a finished job of type `T` is handed to. */
export type ResultHandler<T extends JobType> = (result: JobResults[T] | null) => void;

@Injectable({ providedIn: 'root' })
export class SolverJobService {
  /** null when the solver is idle. */
  private readonly _activeJob = signal<TrackedJob | null>(null);
  readonly activeJob = this._activeJob.asReadonly();
  /**
   * Solves planned behind the running one, in the order they will start.
   * Server-side state shared by every client: a run planned from another
   * browser shows up (and can be removed) here too.
   */
  private readonly _file = signal<JobView[]>([]);
  readonly file = this._file.asReadonly();
  /**
   * Score curve of the solve currently running — or of the last one, until the
   * next replaces it (issue #304). Null when no solve has run since the server
   * started, or when the one that did belongs to another edition.
   *
   * <p>Server-side state like everything else here: a solve started from
   * another browser draws its curve on this one too. It carries the edition it
   * describes rather than being filtered here, exactly as {@link TrackedJob}
   * does — see `ScoreStreamDelta` in score-trace.ts.</p>
   */
  private readonly _scoreTrace = signal<ScoreTrace | null>(null);
  readonly scoreTrace = this._scoreTrace.asReadonly();
  /** Ticks every second so the monitor can display a live duration. */
  private readonly _now = signal(Date.now());
  readonly now = this._now.asReadonly();

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
   * {@link scoreTrace}, but only when it describes a run on the edition this
   * browser is on — null otherwise, and null while either side's edition is
   * still unknown.
   *
   * <p>The filter that makes the curve honest, and the reason it is here and
   * not in the page: a solve running on another edition is followed by this
   * client (its lock is global) but says nothing about the planning on screen,
   * and drawing its progress on the Solveur page of another edition would be a
   * plain lie. The request-scoped read of the curve applies the same rule
   * server-side, where the edition header exists; a stream has none, so this is
   * where it is applied for the pushed half — exactly as {@link editingLocked}
   * already does for the job itself.</p>
   */
  readonly scoreTraceEdition = computed(() => {
    const trace = this.scoreTrace();
    if (!trace) {
      return null;
    }
    const courante = this.editions.courant()?.id;
    if (courante == null || trace.editionId == null) {
      return null;
    }
    return trace.editionId === courante ? trace : null;
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
    if (job?.secondsLimit == null || job.secondsLimit <= 0) {
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
      return this.stateKnown()
        ? ''
        : $localize`:@@job.stateUnknown:L'état du solveur n'est pas encore connu.`;
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
  /**
   * The pushed source. It owns the connection, its watchdog and its backoff;
   * what it delivers lands here, in the same body a poll tick goes through —
   * see {@link applyServerState}. Only when it has proved alive does the poll
   * drop to its idle pace: until the stream has said something, and again as
   * soon as it goes quiet, polling is what keeps the screen truthful.
   */
  private readonly stream = new SolverStream({
    onState: (state: JobsStreamState) =>
      void this.applyServerState(state.active ?? null, state.file ?? []),
    onScore: (delta) => this._scoreTrace.set(applyScoreDelta(this.scoreTrace(), delta)),
    onAlive: () => this.schedulePolling(),
    onLost: () => this.onStreamLost(),
  });

  /**
   * Starts following the server: the stream, and the polling loop underneath
   * it. Called once by the app shell.
   */
  start(): void {
    if (this.started) {
      return;
    }
    this.started = true;
    void this.sync();
    this.schedulePolling();
    this.stream.open();
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
    // The stream outlives a closed shell as stubbornly as the loop did, and
    // worse: `EventSource` reconnects by itself, so an expired session would
    // keep reopening a stream the server answers with a 401, forever.
    this.stream.close();
    // And the one-second clock, which a running job had started: on a
    // root-provided service it would tick on the login page for as long as
    // the tab lives. Reading `started` inside keeps a sync() still in flight
    // from starting it again after this point.
    this.updateTicker();
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
  submitSolve(planning: PlanningEvenement, seconds?: number): Promise<JobView> {
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
  submitSolveFromReferenceData(
    seconds?: number,
    enFile = false,
    reamorcage: Reamorcage = 'AUTO',
  ): Promise<JobView> {
    // AUTO is the server's default too: only a deliberate choice travels.
    const endpoint =
      reamorcage === 'AUTO'
        ? '/api/solve/async/reference-data'
        : `/api/solve/async/reference-data?reamorcage=${reamorcage}`;
    return this.submit(endpoint, {}, 'SOLVE', seconds, enFile);
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
    scope: PerimetreReplanification,
    seconds?: number,
    enFile = false,
  ): Promise<JobView> {
    return this.submit('/api/solve/incremental/async', scope, 'SOLVE_INCREMENTAL', seconds, enFile);
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
    enFile = false,
  ): Promise<JobView> {
    this.notifications.requestDesktopPermission();
    const params = new URLSearchParams();
    if (seconds) {
      params.set('seconds', String(seconds));
    }
    if (enFile) {
      params.set('enFile', 'true');
    }
    const separator = endpoint.includes('?') ? '&' : '?';
    const url = params.size > 0 ? `${endpoint}${separator}${params}` : endpoint;
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
        timeout: 6000,
      });
      await this.rafraichirFile();
      return job;
    }
    this.adopt(job, true);
    this.notifications.notify({
      title: $localize`:@@job.started:${jobLabel(type)}:jobLabel: démarrée`,
      message: $localize`:@@job.startedMessage:Cela s'exécute sur le serveur — vous pouvez continuer à utiliser l'application, depuis ce navigateur ou un autre.`,
      timeout: 5000,
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
      variant: 'error',
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
          $localize`:@@job.alreadyQueued:${jobLabel(conflit.type)}:jobLabel: est déjà planifiée sur l'édition « ${edition}:edition: ». Retirez-la de la file si vous voulez la replanifier.`,
        );
      }
      this.adopt(conflit, false);
      const duration = formatDuration(conflit.elapsedSeconds);
      const edition = conflit.editionNom ?? conflit.editionId ?? '?';
      return new Error(
        $localize`:@@job.alreadyRunningEdition:${jobLabel(conflit.type)}:jobLabel: est déjà en cours sur l'édition « ${edition}:edition: » (${duration}:duration:). Planifiez-la pour qu'elle démarre à la suite, ou attendez la fin.`,
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
    // A live stream reports a hand-over in the second it happens, so the fast
    // pace buys nothing while it lasts. The loop does not stop for all that:
    // it is the net under a stream that dies without saying so.
    const idle = this.activeJob() === null && this.file().length === 0;
    const wanted = idle || this.stream.alive ? POLL_IDLE_MS : POLL_ACTIVE_MS;
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
    await this.applyServerState(server, null);
  }

  /**
   * Reads the score curve whole, once, from the only route that carries an
   * edition — so the server, not this client, decides whether the running solve
   * concerns the edition on screen (204 when it does not).
   *
   * <p>Called when the Solveur page opens, and only there: the curve is not
   * polled. It does not need to be. A new connection is sent the entire series
   * in its first `score` event, and the stream is already torn down and
   * reopened both on error and after 45 s of silence (solver-stream.ts) — so
   * a stream that dies, buffers, or was never opened at all repairs the curve
   * through machinery that exists anyway. What this one read buys is the
   * window before that: an operator opening the page mid-solve sees the run so
   * far immediately, and sees it even in a browser with no `EventSource`.</p>
   *
   * <p>It only ever <b>fills a hole</b>, never overwrites: the stream is the
   * writer, and the two are not ordered. A snapshot taken before a delta but
   * applied after it would leave the series shorter than what the server
   * believes this connection holds, and every following delta would splice one
   * gap too far — a desynchronisation lasting the whole run, for a curve that
   * would look plausible throughout. So it does nothing at all as soon as
   * anything is held, before <em>and</em> after the await.</p>
   */
  async chargerCourbeScore(): Promise<void> {
    if (this.scoreTrace() !== null) {
      return;
    }
    try {
      // A 204 — no curve, or one belonging to another edition — arrives here as
      // a null body, which is exactly the "nothing to fill the hole with" case.
      const trace = await this.api.get<ScoreTrace | null>('/api/jobs/score');
      // Re-checked after the await, not only before it: a delta may have landed
      // while this request was in flight, and overwriting it with an older
      // snapshot would leave the series SHORTER than what the stream believes
      // it has sent. The next delta's `depuis` would then overshoot, `slice`
      // would silently return fewer points than asked for, and the two sides
      // would stay one gap apart for the rest of the run.
      if (trace && this.scoreTrace() === null) {
        this._scoreTrace.set(trace);
      }
    } catch {
      // Transient error: keep whatever is on screen rather than blanking it.
    }
  }

  /**
   * The one place the server's answer becomes this client's state, whichever
   * of the two sources brought it. Keeping a single body is what makes the
   * fallback honest: a poll tick and a stream event produce exactly the same
   * transitions, the same notifications and the same result dispatch, so
   * losing the stream costs latency and nothing else.
   *
   * @param file the queue when the caller already holds it — the stream
   *             carries it in the same event, which is what removes the second
   *             request — or null to re-read it only on a real hand-over
   */
  private async applyServerState(server: JobView | null, file: JobView[] | null): Promise<void> {
    const vu = server?.id ?? null;
    if (file !== null) {
      this._file.set(file);
      this.dernierJobVu = vu;
    } else if (vu !== this.dernierJobVu) {
      // The queue only ever changes when the solver hands over: re-reading it
      // on every tick doubled the request count to re-publish an identical list.
      this.dernierJobVu = vu;
      await this.rafraichirFile();
    }
    const tracked = this.activeJob();
    if (tracked && (!server || server.id !== tracked.id)) {
      this._activeJob.set(null);
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

  /**
   * A live stream went silent or failed: the poll takes the lead back, and
   * asks now rather than at its next idle tick — the state on screen has
   * simply stopped being true, and nothing else would repair it in time.
   */
  private onStreamLost(): void {
    this.schedulePolling();
    void this.sync();
  }

  /** Server-side queue, re-read on every poll: another client may add to it. */
  private async rafraichirFile(): Promise<void> {
    const file = await this.api.get<JobView[]>('/api/jobs/file').catch(() => null);
    if (file) {
      this._file.set(file);
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
    const running = this.started && this.activeJob() !== null;
    if (running && this.tickHandle === null) {
      this._now.set(Date.now());
      this.tickHandle = setInterval(() => this._now.set(Date.now()), 1000);
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
        this._activeJob.set({ ...tracked, mine: true });
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
      secondsLimit: job.secondsLimit == null ? null : Number(job.secondsLimit),
    };
    this._activeJob.set(entry);
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
        timeout: 6000,
      });
      return;
    }
    if (!entry.mine) {
      const duration = formatDuration(job.elapsedSeconds);
      const edition = entry.editionNom ?? entry.editionId ?? '?';
      this.notifications.notify({
        title: $localize`:@@job.alreadyRunningTitle:${entry.label}:jobLabel: déjà en cours`,
        message: $localize`:@@job.alreadyRunningMessage:Démarrée depuis une autre session il y a ${duration}:duration:, sur l'édition « ${edition}:edition: » ; les actions du solveur sont verrouillées jusqu'à la fin.`,
      });
    }
  }

  private async reportFinishedJob(entry: TrackedJob): Promise<void> {
    this.mesJobs.delete(entry.id);
    const job = await this.api.get<JobView>(`/api/jobs/${entry.id}`).catch(() => null);
    if (!job) {
      this.notifications.notify({
        title: $localize`:@@job.finishedUnknownTitle:${entry.label}:jobLabel: terminée`,
        message: $localize`:@@job.finishedUnknownMessage:Le serveur ne connaît plus cette tâche (redémarrage ou purge de rétention).`,
      });
      return;
    }
    if (job.status !== 'COMPLETED') {
      this.notifications.notify({
        title: $localize`:@@job.notCompletedTitle:${entry.label}:jobLabel: ${statusLabel(job.status)}:status:`,
        message: job.error ?? '',
        variant: 'error',
        desktop: true,
        timeout: 0,
      });
      // A cancelled job is not an empty job: the server keeps its partial
      // result (a stopped solve is still persisted and analyzed, a stopped
      // queue keeps its per-group summary — see SolverJobService.cancel on
      // the backend). The same goes for a solve the server stopped under,
      // whose partial plan may have been kept. The pages must still consume
      // it, or their caches silently diverge from what the database now holds.
      if ((job.status === 'CANCELLED' || job.status === 'INTERROMPU') && job.result != null) {
        this.dispatchResult(job);
      }
      return;
    }
    const duration = formatDuration(job.elapsedSeconds);
    this.notifications.notify({
      title: $localize`:@@job.completedTitle:${entry.label}:jobLabel: terminée en ${duration}:duration:`,
      message: describeResult(job.result),
      variant: 'success',
      desktop: true,
    });
    // Raised here rather than by the solving page's onResult handler:
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
 * The diagnostic inside a SOLVE payload: the `diagnostic` field when the
 * payload wraps one (issues #86 and #274), the payload itself otherwise — a
 * job finished before those shipped answers a bare diagnostic and must keep
 * displaying. Null when the job carries no result at all — a cancelled job, or
 * one still running.
 */
export function extraireDiagnostic(result: unknown): PlanningDiagnostic | null {
  if (!result || typeof result !== 'object') {
    return null;
  }
  const enveloppe = result as Partial<ResultatSolveIncremental>;
  return (enveloppe.diagnostic ?? (result as PlanningDiagnostic)) || null;
}

/**
 * The plan a finished solve replaced (issue #274), or null when there is
 * nothing to compare against: the first solve of an edition, a payload from
 * before this shipped, or a capture that failed.
 */
export function extrairePlanPrecedent(result: unknown): PreviousPlan | null {
  if (!result || typeof result !== 'object') {
    return null;
  }
  return (result as Partial<ResultatSolve>).previousPlan ?? null;
}

/** The people a finished solve would disturb if published; `null` when nothing was published or the payload predates it. */
export function extraireImpactPublication(result: unknown): ImpactPublication | null {
  if (!result || typeof result !== 'object') {
    return null;
  }
  return (result as Partial<ResultatSolve>).impactPublication ?? null;
}

/**
 * The readings a finished solve withdrew — days somebody had accepted and on
 * which a seat has just moved. `null` when it withdrew none, or on a payload
 * from before the review state shipped.
 */
export function extraireImpactValidations(result: unknown): ImpactValidations | null {
  if (!result || typeof result !== 'object') {
    return null;
  }
  return (result as Partial<ResultatSolve>).impactValidations ?? null;
}

/**
 * Where a finished full solve started from (issue #174), or `null` when the
 * payload does not say: an incremental result, or one from before this shipped.
 */
export function extraireReamorcage(result: unknown): ReamorcageEffectue | null {
  if (!result || typeof result !== 'object') {
    return null;
  }
  return (result as Partial<ResultatSolve>).reamorcage ?? null;
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
