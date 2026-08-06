import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from './api.service';
import { NotificationService } from './notification.service';
import { SolverJobService } from './solver-job.service';
import type { JobView } from './models';

const POLL_INTERVAL_MS = 2000;

function job(overrides: Partial<JobView> = {}): JobView {
  return {
    id: 'job-1',
    type: 'SOLVE',
    status: 'RUNNING',
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
 * Fake ApiService driving the poll loop: `activeResponses` is consumed one
 * entry per `/api/jobs/active` call, so a test can script "running, then idle".
 */
class FakeApi {
  activeResponses: { status: number; body: JobView | null }[] = [];
  jobsById: Record<string, JobView> = {};

  getResponse = vi.fn(async () => this.activeResponses.shift() ?? { status: 204, body: null });
  get = vi.fn(async (url: string) => {
    const id = url.replace('/api/jobs/', '');
    if (!(id in this.jobsById)) {
      throw new Error(`Unexpected GET ${url}`);
    }
    return this.jobsById[id];
  });
}

describe('SolverJobService', () => {
  let service: SolverJobService;
  let api: FakeApi;

  beforeEach(() => {
    vi.useFakeTimers();
    api = new FakeApi();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        SolverJobService,
        { provide: ApiService, useValue: api },
        { provide: NotificationService, useValue: { notify: vi.fn(), requestDesktopPermission: vi.fn() } }
      ]
    });
    service = TestBed.inject(SolverJobService);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  /** Plays "a job is running", then "the solver is idle", so a job completes. */
  async function runJobToCompletion(result: unknown = { score: '0hard/0medium/0soft' }): Promise<void> {
    api.activeResponses = [
      { status: 200, body: job() },
      { status: 204, body: null }
    ];
    api.jobsById['job-1'] = job({ status: 'COMPLETED', finishedAt: '2026-07-01T10:00:30Z', result });
    service.start();
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    await vi.advanceTimersByTimeAsync(0);
  }

  describe('onResult', () => {
    it('dispatches the payload of a finished job to every registered handler', async () => {
      const first = vi.fn();
      const second = vi.fn();
      service.onResult('SOLVE', first);
      service.onResult('SOLVE', second);

      await runJobToCompletion({ score: '0hard/0medium/0soft', postesNonPourvus: 0 });

      expect(first).toHaveBeenCalledTimes(1);
      expect(second).toHaveBeenCalledTimes(1);
      expect(first.mock.calls[0][0]).toEqual({ score: '0hard/0medium/0soft', postesNonPourvus: 0 });
    });

    it('stops dispatching to a handler once its returned disposer is called', async () => {
      const kept = vi.fn();
      const disposed = vi.fn();
      service.onResult('SOLVE', kept);
      const stop = service.onResult('SOLVE', disposed);

      stop();
      await runJobToCompletion();

      expect(kept).toHaveBeenCalledTimes(1);
      expect(disposed).not.toHaveBeenCalled();
    });

    it('never dispatches to a handler registered for another job type', async () => {
      const analyze = vi.fn();
      service.onResult('ANALYZE', analyze);

      await runJobToCompletion();

      expect(analyze).not.toHaveBeenCalled();
    });
  });

  describe('solverBusy', () => {
    it('is pessimistic until the server has answered once', () => {
      expect(service.solverBusy()).toBe(true);
    });

    it('is false once the server reports an idle solver', async () => {
      api.activeResponses = [{ status: 204, body: null }];
      service.start();
      await vi.advanceTimersByTimeAsync(0);

      expect(service.solverBusy()).toBe(false);
    });

    it('is true while the server reports a running job', async () => {
      api.activeResponses = [{ status: 200, body: job() }];
      service.start();
      await vi.advanceTimersByTimeAsync(0);

      expect(service.solverBusy()).toBe(true);
      expect(service.activeJob()?.id).toBe('job-1');
    });
  });

  describe('duration ticker', () => {
    it('does not tick while the solver is idle', async () => {
      api.activeResponses = [{ status: 204, body: null }];
      service.start();
      await vi.advanceTimersByTimeAsync(0);

      const before = service.now();
      await vi.advanceTimersByTimeAsync(5000);

      expect(service.now()).toBe(before);
    });

    it('ticks every second while a job is running', async () => {
      api.activeResponses = [{ status: 200, body: job() }];
      service.start();
      await vi.advanceTimersByTimeAsync(0);

      const before = service.now();
      await vi.advanceTimersByTimeAsync(3000);

      expect(service.now()).toBeGreaterThan(before);
    });
  });
});
