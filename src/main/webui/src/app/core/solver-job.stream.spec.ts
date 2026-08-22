import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from './api.service';
import { EditionStore } from './edition.store';
import { NotificationService } from './notification.service';
import { SolverJobService } from './solver-job.service';
import type { JobView } from './models';

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
    ...overrides
  };
}

/**
 * jsdom has no `EventSource`, which is a feature here rather than an obstacle:
 * the service reads it off the global and treats its absence as "keep
 * polling", so installing this fake is the whole test seam — no provider, no
 * injected factory, and the production code path is the one exercised.
 */
class FakeEventSource {
  static instances: FakeEventSource[] = [];

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

  fail(): void {
    this.onerror?.(new Event('error'));
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

  getResponse = vi.fn(async () => this.activeResponses.shift() ?? { status: 204, body: null });
  get = vi.fn(async (url: string) => {
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
    FakeEventSource.instances = [];
    (globalThis as { EventSource?: unknown }).EventSource = FakeEventSource;
    api = new FakeApi();
    notifications = { notify: vi.fn(), notifyFeasibility: vi.fn(), requestDesktopPermission: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        SolverJobService,
        { provide: ApiService, useValue: api },
        { provide: EditionStore, useValue: { courant: () => ({ id: 'ed-1' }) } },
        { provide: NotificationService, useValue: notifications }
      ]
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
      ([notification]) => notification.variant === 'success'
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
});
