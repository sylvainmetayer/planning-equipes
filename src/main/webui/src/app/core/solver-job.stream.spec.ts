import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from './api.service';
import { EditionStore } from './edition.store';
import { NotificationService } from './notification.service';
import { SolverJobService } from './solver-job.service';
import type { JobView, ScorePoint, ScoreTrace } from './models';

/** Wire shape of a `score` event, mirrored here so the fake stays honest. */
type ScoreDeltaWire = Omit<ScoreTrace, 'points' | 'jobId'> & {
  jobId: string | null;
  depuis: number;
  points: ScorePoint[];
};

/**
 * The stream half of `SolverJobService`, and above all its fallback.
 *
 * <p>The reason these tests exist at all is that server-sent events fail
 * <b>silently</b>. A proxy that buffers, a connection cut that never
 * reconnects: nothing throws, nothing logs, and the screen simply stops being
 * true. That is strictly worse than the polling it replaces, which repairs
 * itself at the next tick. So the property under test is never "the stream
 * works" — it is "when the stream stops working, polling takes the lead
 * back".</p>
 *
 * <p>They live beside `solver-job.service.spec.ts` rather than inside it on
 * purpose: the public surface of the service did not move, and the 30 tests of
 * that file passing <em>untouched</em> is the evidence. `git diff` on it is
 * part of this lot's verification.</p>
 */

/** Server heartbeat is 20 s; the client gives up after 45 s of silence. */
const STREAM_SILENCE_MS = 45000;
const POLL_ACTIVE_MS = 2000;
const POLL_IDLE_MS = 30000;

function job(overrides: Partial<JobView> = {}): JobView {
  return {
    id: 'job-1',
    type: 'SOLVE',
    status: 'RUNNING',
    editionId: 'ed-1',
    editionNom: 'Année 2026',
    secondsLimit: 30,
    submittedAt: '2026-07-01T10:00:00Z',
    startedAt: '2026-07-01T10:00:00Z',
    finishedAt: null,
    elapsedSeconds: 3,
    error: null,
    result: null,
    ...overrides,
  };
}

/**
 * jsdom has no `EventSource`, which is a feature here rather than an obstacle:
 * the service reads it off the global and treats its absence as "keep
 * polling", so installing this fake is the whole test seam — no provider, no
 * injected factory, and the production code path is the one exercised.
 */
class FakeEventSource {
  static readonly instances: FakeEventSource[] = [];

  static get last(): FakeEventSource {
    const instance = FakeEventSource.instances.at(-1);
    if (!instance) {
      throw new Error('No EventSource was opened');
    }
    return instance;
  }

  static get open(): FakeEventSource[] {
    return FakeEventSource.instances.filter((instance) => !instance.closed);
  }

  closed = false;
  onerror: ((event: Event) => void) | null = null;
  private readonly listeners = new Map<string, ((event: MessageEvent<string>) => void)[]>();

  constructor(readonly url: string) {
    FakeEventSource.instances.push(this);
  }

  addEventListener(type: string, handler: (event: MessageEvent<string>) => void): void {
    const registered = this.listeners.get(type);
    if (registered) {
      registered.push(handler);
    } else {
      this.listeners.set(type, [handler]);
    }
  }

  close(): void {
    this.closed = true;
  }

  /** How many handlers are attached: the reconnection must never grow this. */
  handlerCount(type: string): number {
    return (this.listeners.get(type) ?? []).length;
  }

  emitState(active: JobView | null, file: JobView[] = []): void {
    this.emit('state', JSON.stringify({ active, file }));
  }

  emitHeartbeat(): void {
    this.emit('heartbeat', JSON.stringify({ at: '2026-07-01T10:00:00Z' }));
  }

  /** One `score` delta of the running solve's curve (issue #304). */
  emitScore(delta: Partial<ScoreDeltaWire> & { depuis: number; points: ScorePoint[] }): void {
    this.emit(
      'score',
      JSON.stringify({
        jobId: 'job-1',
        editionId: 'ed-1',
        generation: 1,
        intervalleMs: 1000,
        dureeMs: 30000,
        termine: false,
        ...delta,
      }),
    );
  }

  fail(): void {
    this.onerror?.(new Event('error'));
  }

  /** Raw payload, for the events a truncated connection cuts in half. */
  emitRaw(type: string, data: string): void {
    this.emit(type, data);
  }

  private emit(type: string, data: string): void {
    const event = { data, type } as MessageEvent<string>;
    [...(this.listeners.get(type) ?? [])].forEach((handler) => handler(event));
  }
}

/** Same shape as the fake in `solver-job.service.spec.ts`, minus what is unused here. */
class FakeApi {
  activeResponses: { status: number; body: JobView | null }[] = [];
  jobsById: Record<string, JobView> = {};
  file: JobView[] = [];
  /**
   * What `/api/jobs/score` answers. Null is what a 204 looks like through
   * `ApiService.get` — no curve, or one belonging to another edition. A promise
   * so a test can hold the answer back and land a stream delta underneath it.
   */
  scoreTrace: Promise<ScoreTrace | null> = Promise.resolve(null);

  getResponse = vi.fn(async () => this.activeResponses.shift() ?? { status: 204, body: null });
  get = vi.fn(async (url: string): Promise<JobView | JobView[] | ScoreTrace | null> => {
    if (url === '/api/jobs/score') {
      return this.scoreTrace;
    }
    if (url === '/api/jobs/file') {
      return this.file;
    }
    const id = url.replace('/api/jobs/', '');
    return this.jobsById[id] ?? job({ id, status: 'COMPLETED' });
  });
}

describe('SolverJobService — server-sent events', () => {
  let service: SolverJobService;
  let api: FakeApi;
  let notifications: {
    notify: ReturnType<typeof vi.fn>;
    notifyFeasibility: ReturnType<typeof vi.fn>;
    requestDesktopPermission: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    vi.useFakeTimers();
    FakeEventSource.instances.length = 0;
    (globalThis as { EventSource?: unknown }).EventSource = FakeEventSource;
    api = new FakeApi();
    notifications = {
      notify: vi.fn(),
      notifyFeasibility: vi.fn(),
      requestDesktopPermission: vi.fn(),
    };
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        SolverJobService,
        { provide: ApiService, useValue: api },
        { provide: EditionStore, useValue: { courant: () => ({ id: 'ed-1' }) } },
        { provide: NotificationService, useValue: notifications },
      ],
    });
    service = TestBed.inject(SolverJobService);
  });

  afterEach(() => {
    service.stop();
    delete (globalThis as { EventSource?: unknown }).EventSource;
    vi.useRealTimers();
  });

  it('opens the stream when the shell starts it', async () => {
    service.start();
    await vi.advanceTimersByTimeAsync(0);

    expect(FakeEventSource.open).toHaveLength(1);
    expect(FakeEventSource.last.url).toBe('/api/jobs/stream');
  });

  it('takes the state the stream delivers on connect, without waiting for a transition', async () => {
    service.start();
    await vi.advanceTimersByTimeAsync(0);
    // The very first thing the server sends on an open connection: the state as
    // it stands. An idle solver produces no transition for hours, so a client
    // that only listened for transitions would show nothing at all.
    FakeEventSource.last.emitState(job({ id: 'job-7' }), [job({ id: 'job-8', status: 'QUEUED' })]);
    await vi.advanceTimersByTimeAsync(0);

    expect(service.activeJob()?.id).toBe('job-7');
    // The queue rode along in the same event: no second request was needed.
    expect(service.file().map((view) => view.id)).toEqual(['job-8']);
    expect(api.get.mock.calls.filter(([url]) => url === '/api/jobs/file')).toHaveLength(0);
  });

  it('lets the stream deliver a hand-over instantly, where polling would take thirty seconds', async () => {
    // Nothing is running, so the loop sits at its slow pace: a solve started
    // from another browser would otherwise take up to 30 s to show up here.
    service.start();
    await vi.advanceTimersByTimeAsync(0);
    FakeEventSource.last.emitHeartbeat();
    await vi.advanceTimersByTimeAsync(0);
    expect(service.activeJob()).toBeNull();

    FakeEventSource.last.emitState(job({ id: 'job-9' }));
    await vi.advanceTimersByTimeAsync(0);

    // Zero elapsed milliseconds, against the 30 000 the fallback would cost.
    expect(service.activeJob()?.id).toBe('job-9');
  });

  it('keeps polling slowly under a live stream instead of trusting it alone', async () => {
    api.getResponse = vi.fn(async () => ({ status: 200, body: job() }));
    service.start();
    await vi.advanceTimersByTimeAsync(0);
    FakeEventSource.last.emitState(job());
    await vi.advanceTimersByTimeAsync(0);
    const afterStart = api.getResponse.mock.calls.length;

    // A running job would poll every 2 s without the stream; with it, the loop
    // stays at the idle pace — but it does not stop, because a stream that dies
    // silently is exactly what it is there to catch.
    await vi.advanceTimersByTimeAsync(POLL_ACTIVE_MS * 4);
    expect(api.getResponse.mock.calls.length - afterStart).toBe(0);

    await vi.advanceTimersByTimeAsync(POLL_IDLE_MS);
    expect(api.getResponse.mock.calls.length - afterStart).toBe(1);
  });

  it('falls back to polling when the stream goes silent without ever reporting an error', async () => {
    api.getResponse = vi.fn(async () => ({ status: 200, body: job() }));
    service.start();
    await vi.advanceTimersByTimeAsync(0);
    FakeEventSource.last.emitState(job());
    await vi.advanceTimersByTimeAsync(0);
    const silent = FakeEventSource.last;
    const beforeSilence = api.getResponse.mock.calls.length;

    // No error, no close: the connection may well still be open. This is the
    // failure mode the whole design exists for — a buffering proxy.
    await vi.advanceTimersByTimeAsync(STREAM_SILENCE_MS);

    // The poll asked at once rather than at its next idle tick...
    expect(api.getResponse.mock.calls.length).toBeGreaterThan(beforeSilence);
    const afterTakeover = api.getResponse.mock.calls.length;
    // ... and is back to its fast pace, since a job is running.
    await vi.advanceTimersByTimeAsync(POLL_ACTIVE_MS * 2);
    expect(api.getResponse.mock.calls.length - afterTakeover).toBe(2);
    // The stream it gave up on was closed, not left dangling.
    expect(silent.closed).toBe(true);
  });

  it('falls back to polling when the stream errors out', async () => {
    api.getResponse = vi.fn(async () => ({ status: 200, body: job() }));
    service.start();
    await vi.advanceTimersByTimeAsync(0);
    FakeEventSource.last.emitState(job());
    await vi.advanceTimersByTimeAsync(0);
    const afterState = api.getResponse.mock.calls.length;

    FakeEventSource.last.fail();
    await vi.advanceTimersByTimeAsync(0);

    expect(api.getResponse.mock.calls.length).toBeGreaterThan(afterState);
    const afterFailure = api.getResponse.mock.calls.length;
    await vi.advanceTimersByTimeAsync(POLL_ACTIVE_MS * 2);
    expect(api.getResponse.mock.calls.length - afterFailure).toBe(2);
  });

  it('reconnects with an exponential backoff instead of a fixed delay', async () => {
    service.start();
    await vi.advanceTimersByTimeAsync(0);

    // First failure: one second.
    FakeEventSource.last.fail();
    await vi.advanceTimersByTimeAsync(999);
    expect(FakeEventSource.instances).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(FakeEventSource.instances).toHaveLength(2);

    // Second: two seconds, not one again.
    FakeEventSource.last.fail();
    await vi.advanceTimersByTimeAsync(1999);
    expect(FakeEventSource.instances).toHaveLength(2);
    await vi.advanceTimersByTimeAsync(1);
    expect(FakeEventSource.instances).toHaveLength(3);

    // And it is capped: an outage that lasts must still be retried.
    for (let i = 0; i < 12; i++) {
      FakeEventSource.last.fail();
      await vi.advanceTimersByTimeAsync(30_000);
    }
    const before = FakeEventSource.instances.length;
    FakeEventSource.last.fail();
    await vi.advanceTimersByTimeAsync(30_000);
    expect(FakeEventSource.instances.length).toBe(before + 1);
  });

  it('does not duplicate handlers across reconnections', async () => {
    service.start();
    await vi.advanceTimersByTimeAsync(0);

    for (let i = 0; i < 4; i++) {
      FakeEventSource.last.fail();
      await vi.advanceTimersByTimeAsync(60_000);
    }

    // Exactly one live stream, and one handler per event name on it: a reopen
    // that added listeners to a surviving stream would apply every state twice
    // — two notifications, two result dispatches, for one job.
    expect(FakeEventSource.open).toHaveLength(1);
    expect(FakeEventSource.last.handlerCount('state')).toBe(1);
    expect(FakeEventSource.last.handlerCount('heartbeat')).toBe(1);

    // Measured on the outcome too, not only on the wiring: one finished job,
    // one "completed" notification — and one read of its payload. Duplicated
    // handlers would report the same job twice.
    api.jobsById['job-1'] = job({ status: 'COMPLETED', finishedAt: '2026-07-01T10:00:30Z' });
    FakeEventSource.last.emitState(job());
    await vi.advanceTimersByTimeAsync(0);
    FakeEventSource.last.emitState(null);
    await vi.advanceTimersByTimeAsync(0);
    const completions = notifications.notify.mock.calls.filter(
      ([notification]) => notification.variant === 'success',
    );
    expect(completions).toHaveLength(1);
    expect(api.get.mock.calls.filter(([url]) => url === '/api/jobs/job-1')).toHaveLength(1);
  });

  it('closes the stream on stop(), and does not reopen one behind the shell', async () => {
    service.start();
    await vi.advanceTimersByTimeAsync(0);
    const opened = FakeEventSource.last;

    service.stop();

    expect(opened.closed).toBe(true);
    // Not even a pending reconnection: `stop()` is called from the shell's
    // DestroyRef, and a session that just expired must not have a stream left
    // reopening itself into a 401 on the login page.
    await vi.advanceTimersByTimeAsync(10 * 60 * 1000);
    expect(FakeEventSource.open).toHaveLength(0);
  });

  // The pendant of the two stop() tests above: a session that expires stops
  // the service, and the shell of the re-login starts it again. The reopening
  // hinges on one flag, `wanted`, reset by `close()` alone — if that reset
  // went missing, `open()` would return at once and the application would
  // fall back on the poll alone, transitions up to thirty seconds late,
  // without a single red test.
  it('opens a fresh, live stream when a new shell starts the service after a stop', async () => {
    service.start();
    await vi.advanceTimersByTimeAsync(0);
    service.stop();
    expect(FakeEventSource.open).toHaveLength(0);

    service.start();
    await vi.advanceTimersByTimeAsync(0);

    expect(FakeEventSource.open).toHaveLength(1);
    FakeEventSource.last.emitState(job({ id: 'job-2' }));
    expect(service.activeJob()?.id).toBe('job-2');
  });

  it('cancels a reconnection that stop() interrupts mid-backoff', async () => {
    service.start();
    await vi.advanceTimersByTimeAsync(0);
    FakeEventSource.last.fail();

    service.stop();
    await vi.advanceTimersByTimeAsync(10 * 60 * 1000);

    expect(FakeEventSource.open).toHaveLength(0);
  });

  it('polls exactly as before in a browser with no EventSource at all', async () => {
    delete (globalThis as { EventSource?: unknown }).EventSource;
    api.getResponse = vi.fn(async () => ({ status: 200, body: job() }));

    service.start();
    await vi.advanceTimersByTimeAsync(0);
    const afterStart = api.getResponse.mock.calls.length;
    await vi.advanceTimersByTimeAsync(POLL_ACTIVE_MS * 3);

    // The fast pace, untouched: no stream means the poll is the only source.
    expect(api.getResponse.mock.calls.length - afterStart).toBe(3);
    expect(FakeEventSource.instances).toHaveLength(0);
  });

  /**
   * The live score curve (issue #304). What is worth testing is not that points
   * arrive — it is the splice: the events are <b>deltas</b>, because the series
   * grows for as long as the solve runs and re-sending it whole every second
   * would make the traffic grow with the budget. A delta protocol has exactly
   * one failure mode, and it is the one that costs the user a wrong curve
   * rather than an error: appending what should have replaced.
   */
  describe('score curve', () => {
    const point = (tempsMs: number, hard: number): ScorePoint => ({
      tempsMs,
      hard,
      medium: 0,
      soft: 0,
    });

    it('has no curve at all until the server sends one', async () => {
      service.start();
      await vi.advanceTimersByTimeAsync(0);

      expect(service.scoreTrace()).toBeNull();
    });

    it('appends the points of each delta to the series it already holds', async () => {
      service.start();
      await vi.advanceTimersByTimeAsync(0);

      FakeEventSource.last.emitScore({ depuis: 0, points: [point(0, -40), point(1000, -30)] });
      FakeEventSource.last.emitScore({ depuis: 2, points: [point(2000, -10)] });
      await vi.advanceTimersByTimeAsync(0);

      expect(service.scoreTrace()?.points.map((p) => p.hard)).toEqual([-40, -30, -10]);
    });

    it('replaces the series when the server restarts at zero', async () => {
      service.start();
      await vi.advanceTimersByTimeAsync(0);
      FakeEventSource.last.emitScore({ depuis: 0, points: [point(0, -40), point(1000, -30)] });
      await vi.advanceTimersByTimeAsync(0);

      // A reconnection, or a series the server has just decimated: it starts
      // over at zero, and appending here would draw every point twice.
      FakeEventSource.last.emitScore({
        generation: 2,
        depuis: 0,
        points: [point(0, -40), point(2000, -10)],
      });
      await vi.advanceTimersByTimeAsync(0);

      expect(service.scoreTrace()?.points.map((p) => p.hard)).toEqual([-40, -10]);
    });

    it('starts a new run from scratch rather than after the previous curve', async () => {
      service.start();
      await vi.advanceTimersByTimeAsync(0);
      FakeEventSource.last.emitScore({ depuis: 0, points: [point(0, -40)], termine: true });
      await vi.advanceTimersByTimeAsync(0);

      FakeEventSource.last.emitScore({
        jobId: 'job-2',
        generation: 2,
        depuis: 0,
        points: [point(0, -900)],
      });
      await vi.advanceTimersByTimeAsync(0);

      const trace = service.scoreTrace();
      expect(trace?.jobId).toBe('job-2');
      expect(trace?.termine).toBe(false);
      expect(trace?.points.map((p) => p.hard)).toEqual([-900]);
    });

    it('survives a truncated event instead of losing the curve', async () => {
      service.start();
      await vi.advanceTimersByTimeAsync(0);
      FakeEventSource.last.emitScore({ depuis: 0, points: [point(0, -40)] });
      await vi.advanceTimersByTimeAsync(0);

      FakeEventSource.last.emitRaw('score', '{"jobId":"job-1","poi');
      await vi.advanceTimersByTimeAsync(0);

      expect(service.scoreTrace()?.points).toHaveLength(1);
    });

    it('drops the curve when the server says it no longer has one', async () => {
      service.start();
      await vi.advanceTimersByTimeAsync(0);
      FakeEventSource.last.emitScore({ depuis: 0, points: [point(0, -40)] });
      await vi.advanceTimersByTimeAsync(0);
      expect(service.scoreTrace()).not.toBeNull();

      // A restart. Without this event the last curve received would stay on
      // screen with its last `termine: false`, reading as a live solve for a
      // run the server already reports as interrupted.
      FakeEventSource.last.emitScore({ jobId: null, generation: -1, depuis: 0, points: [] });
      await vi.advanceTimersByTimeAsync(0);

      expect(service.scoreTrace()).toBeNull();
    });

    it('refuses to expose a curve belonging to another edition', async () => {
      service.start();
      await vi.advanceTimersByTimeAsync(0);

      // The stream carries every run, whatever edition it writes to — the lock
      // is global. Drawing this one on the Solveur page of edition ed-1 would
      // show an operator the progress of a solve that is not rewriting the
      // planning in front of them.
      FakeEventSource.last.emitScore({ editionId: 'ed-2', depuis: 0, points: [point(0, -40)] });
      await vi.advanceTimersByTimeAsync(0);

      expect(service.scoreTrace()?.points).toHaveLength(1);
      expect(service.scoreTraceEdition()).toBeNull();
    });

    it('exposes the curve of the edition this browser is on', async () => {
      service.start();
      await vi.advanceTimersByTimeAsync(0);

      FakeEventSource.last.emitScore({ editionId: 'ed-1', depuis: 0, points: [point(0, -40)] });
      await vi.advanceTimersByTimeAsync(0);

      expect(service.scoreTraceEdition()?.points).toHaveLength(1);
    });

    /**
     * The full read is a second writer next to the stream, and the two are not
     * ordered. Letting it overwrite would leave the series shorter than what
     * the server believes this connection holds — and every following delta
     * would then splice one gap too far, for the rest of the run, on a curve
     * that would look perfectly plausible throughout.
     */
    describe('the one-shot full read', () => {
      const courbe = (points: ScorePoint[]): ScoreTrace => ({
        jobId: 'job-1',
        editionId: 'ed-1',
        generation: 1,
        intervalleMs: 1000,
        dureeMs: 5000,
        termine: false,
        points,
      });

      it('fills the curve when nothing is held yet', async () => {
        api.scoreTrace = Promise.resolve(courbe([point(0, -40)]));
        service.start();
        await vi.advanceTimersByTimeAsync(0);

        await service.chargerCourbeScore();

        expect(service.scoreTrace()?.points).toHaveLength(1);
      });

      it('never overwrites a series the stream is already feeding', async () => {
        api.scoreTrace = Promise.resolve(courbe([point(0, -40)]));
        service.start();
        await vi.advanceTimersByTimeAsync(0);
        FakeEventSource.last.emitScore({ depuis: 0, points: [point(0, -40), point(1000, -30)] });
        await vi.advanceTimersByTimeAsync(0);

        await service.chargerCourbeScore();

        expect(service.scoreTrace()?.points).toHaveLength(2);
        // Not even asked for: there is nothing this read could add.
        expect(api.get.mock.calls.filter(([url]) => url === '/api/jobs/score')).toHaveLength(0);
      });

      it('discards its answer when a delta lands while it is in flight', async () => {
        let repondre: ((trace: ScoreTrace) => void) | null = null;
        api.scoreTrace = new Promise<ScoreTrace>((resolve) => {
          repondre = resolve;
        });
        service.start();
        await vi.advanceTimersByTimeAsync(0);
        const lecture = service.chargerCourbeScore();

        // The stream got there first, with more than the snapshot carries.
        FakeEventSource.last.emitScore({ depuis: 0, points: [point(0, -40), point(1000, -30)] });
        await vi.advanceTimersByTimeAsync(0);
        repondre!(courbe([point(0, -40)]));
        await lecture;

        expect(service.scoreTrace()?.points).toHaveLength(2);
      });
    });
  });
});
