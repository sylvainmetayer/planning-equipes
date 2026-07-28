// Background solver jobs: submit, follow, and report without blocking the UI.
//
// A solve can run for minutes. Instead of awaiting a long HTTP request, the
// browser posts to the /async endpoints and follows the job through the server.
//
// The "a solver run is in progress" state belongs to the server, not to the
// browser: this service polls /api/jobs/active, so a solve started in one tab
// locks the solver buttons and shows the same elapsed time in another browser,
// in a private window, or after clearing the local storage.

import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ApiService, toError } from './api.service';
import { NotificationService } from './notification.service';
import { JobType, JobView, PlanningDiagnostic, PlanningFestival } from './models';

const POLL_INTERVAL_MS = 2000;
const LABELS: Record<JobType, string> = {
  SOLVE: 'Timefold solve',
  ANALYZE: 'Solution analysis'
};

/** Job held by the server, as followed by this client. */
export interface TrackedJob {
  id: string;
  type: JobType;
  label: string;
  /** Local timestamp rebased on the server elapsed time. */
  startedAtMs: number;
  /** True when this browser submitted the job. */
  mine: boolean;
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

  readonly activeJobDescription = computed(() => {
    const job = this.activeJob();
    if (!job) {
      return this.stateKnown() ? '' : 'The solver state is not known yet.';
    }
    const origin = job.mine ? '' : ' (started from another session)';
    return `${job.label} has been running for ${formatDuration(elapsedSeconds(job, this.now()))}${origin}.`;
  });

  private readonly api = inject(ApiService);
  private readonly http = inject(HttpClient);
  private readonly notifications = inject(NotificationService);
  private readonly stateKnown = signal(false);
  private readonly resultHandlers = new Map<JobType, ResultHandler>();
  private started = false;

  /** Starts the shared polling loop. Called once by the app shell. */
  start(): void {
    if (this.started) {
      return;
    }
    this.started = true;
    void this.sync();
    setInterval(() => void this.sync(), POLL_INTERVAL_MS);
    setInterval(() => this.now.set(Date.now()), 1000);
  }

  /**
   * Registers what to do with the payload of a finished job. Results are always
   * dispatched, whoever started the job: a solve launched from another browser
   * also updates this one when it completes.
   */
  onResult(type: JobType, handler: ResultHandler): void {
    this.resultHandlers.set(type, handler);
  }

  submitSolve(planning: PlanningFestival, seconds?: number): Promise<JobView> {
    return this.submit('/api/solve/async', planning, 'SOLVE', seconds);
  }

  submitAnalyze(planning: PlanningFestival, seconds?: number): Promise<JobView> {
    return this.submit('/api/solve/analyze/async', planning, 'ANALYZE', seconds);
  }

  private async submit(endpoint: string, payload: PlanningFestival, type: JobType, seconds?: number): Promise<JobView> {
    this.notifications.requestDesktopPermission();
    const url = seconds ? `${endpoint}?seconds=${encodeURIComponent(seconds)}` : endpoint;
    let job: JobView;
    try {
      job = await firstValueFrom(this.http.post<JobView>(url, payload));
    } catch (error) {
      throw this.explainSubmitFailure(error);
    }
    this.adopt(job, true);
    this.notifications.notify({
      title: `${LABELS[type]} started`,
      message: 'This runs on the server — you can keep using the app, from this browser or another one.',
      timeout: 5000
    });
    return job;
  }

  // 409: the server already runs a solve, possibly submitted by another client.
  private explainSubmitFailure(error: unknown): Error {
    if (error instanceof HttpErrorResponse && error.status === 409 && error.error) {
      const running = error.error as JobView;
      this.adopt(running, false);
      return new Error(
        `${LABELS[running.type] ?? running.type} is already running (${formatDuration(running.elapsedSeconds)}). `
          + 'Wait for it to finish before starting another one.'
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
  }

  /** 204 means idle, undefined means "could not ask". */
  private async fetchActiveJob(): Promise<JobView | null | undefined> {
    try {
      const response = await firstValueFrom(
        this.http.get<JobView>('/api/jobs/active', { observe: 'response' })
      );
      return response.status === 204 ? null : response.body;
    } catch {
      return undefined;
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
    const entry: TrackedJob = {
      id: job.id,
      type: job.type,
      label: LABELS[job.type] ?? job.type,
      startedAtMs: Date.now() - (Number(job.elapsedSeconds) || 0) * 1000,
      mine
    };
    this.activeJob.set(entry);
    if (!entry.mine) {
      this.notifications.notify({
        title: `${entry.label} already running`,
        message: `Started from another session ${formatDuration(job.elapsedSeconds)} ago. `
          + 'Solver actions are locked until it finishes.'
      });
    }
  }

  private async reportFinishedJob(entry: TrackedJob): Promise<void> {
    const job = await this.api.get<JobView>(`/api/jobs/${entry.id}`).catch(() => null);
    if (!job) {
      this.notifications.notify({
        title: `${entry.label} is over`,
        message: 'The server no longer knows this job (restart or retention purge).'
      });
      return;
    }
    if (job.status !== 'COMPLETED') {
      this.notifications.notify({
        title: `${entry.label} ${job.status.toLowerCase()}`,
        message: job.error ?? '',
        variant: 'error',
        desktop: true,
        timeout: 0
      });
      return;
    }
    this.notifications.notify({
      title: `${entry.label} finished in ${formatDuration(job.elapsedSeconds)}`,
      message: describeResult(job.type, job.result),
      variant: 'success',
      desktop: true
    });
    this.resultHandlers.get(job.type)?.(job.result);
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

function describeResult(type: JobType, result: unknown): string {
  if (!result) {
    return '';
  }
  if (type === 'ANALYZE') {
    const analysis = result as PlanningDiagnostic;
    return `Score ${analysis.score} — ${analysis.postesNonPourvus} unfilled seats.`;
  }
  const solved = result as PlanningFestival;
  const unfilled = Array.isArray(solved.postes)
    ? solved.postes.filter((poste) => !poste.animateur).length
    : null;
  const score = formatScore(solved.score);
  return unfilled === null ? `Score ${score}.` : `Score ${score} — ${unfilled} unfilled seats.`;
}

export function formatScore(score: PlanningFestival['score']): string {
  if (!score) {
    return 'n/a';
  }
  return `${score.hardScore}hard/${score.mediumScore}medium/${score.softScore}soft`;
}
