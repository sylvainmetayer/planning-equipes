import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from './api.service';
import { EditionStore } from './edition.store';
import { NotificationService } from './notification.service';
import { SolverJobService } from './solver-job.service';
import type { JobView } from './models';

const POLL_INTERVAL_MS = 2000;

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
 * Fake ApiService driving the poll loop: `activeResponses` is consumed one
 * entry per `/api/jobs/active` call, so a test can script "running, then idle".
 */
class FakeApi {
  activeResponses: { status: number; body: JobView | null }[] = [];
  jobsById: Record<string, JobView> = {};
  /** What `/api/jobs/file` answers: the solves planned behind the running one. */
  file: JobView[] = [];
  /** Next answer of a submit: a JobView to return, or an error to throw. */
  postResult: JobView | HttpErrorResponse | null = null;

  getResponse = vi.fn(async () => this.activeResponses.shift() ?? { status: 204, body: null });
  get = vi.fn(async (url: string) => {
    if (url === '/api/jobs/file') {
      return this.file;
    }
    const id = url.replace('/api/jobs/', '');
    if (!(id in this.jobsById)) {
      throw new Error(`Unexpected GET ${url}`);
    }
    return this.jobsById[id];
  });
  postPreservingHttpError = vi.fn(async (_url: string, _payload?: unknown) => {
    if (this.postResult instanceof HttpErrorResponse) {
      throw this.postResult;
    }
    return this.postResult;
  });
  delete = vi.fn(async () => undefined);
}

describe('SolverJobService', () => {
  let service: SolverJobService;
  let api: FakeApi;
  let notifications: {
    notify: ReturnType<typeof vi.fn>;
    notifyFeasibility: ReturnType<typeof vi.fn>;
    requestDesktopPermission: ReturnType<typeof vi.fn>;
  };
  /** What EditionStore reports as the edition this browser works on. */
  let editionCourante: { id: string } | null;

  beforeEach(() => {
    vi.useFakeTimers();
    api = new FakeApi();
    notifications = {
      notify: vi.fn(),
      notifyFeasibility: vi.fn(),
      requestDesktopPermission: vi.fn(),
    };
    editionCourante = { id: 'ed-1' };
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        SolverJobService,
        { provide: ApiService, useValue: api },
        { provide: EditionStore, useValue: { courant: () => editionCourante } },
        { provide: NotificationService, useValue: notifications },
      ],
    });
    service = TestBed.inject(SolverJobService);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  /** Plays "a job is running", then "the solver is idle", so a job completes. */
  async function runJobToCompletion(
    result: unknown = { score: '0hard/0medium/0soft' },
  ): Promise<void> {
    api.activeResponses = [
      { status: 200, body: job() },
      { status: 204, body: null },
    ];
    api.jobsById['job-1'] = job({
      status: 'COMPLETED',
      finishedAt: '2026-07-01T10:00:30Z',
      result,
    });
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
      const incremental = vi.fn();
      service.onResult('SOLVE_INCREMENTAL', incremental);

      await runJobToCompletion();

      expect(incremental).not.toHaveBeenCalled();
    });
  });

  describe('file d’attente', () => {
    /** 409 the server answers when something already covers this run. */
    function conflit(view: JobView): HttpErrorResponse {
      return new HttpErrorResponse({ status: 409, error: view });
    }

    it('publie la file telle que le serveur la rapporte', async () => {
      api.activeResponses = [{ status: 200, body: job() }];
      api.file = [
        job({ id: 'job-2', status: 'QUEUED', editionId: 'ed-2', editionNom: 'Canicule' }),
      ];
      service.start();
      await vi.advanceTimersByTimeAsync(0);

      expect(service.file().map((planifie) => planifie.id)).toEqual(['job-2']);
    });

    it('une tâche planifiée ne prend pas le solveur et ne verrouille pas sa saisie', async () => {
      // Le job en cours porte sur une AUTRE édition que celle du navigateur :
      // c'est tout l'intérêt de la file, préparer ed-1 pendant que ed-2 tourne.
      api.activeResponses = [{ status: 200, body: job({ id: 'job-2', editionId: 'ed-2' }) }];
      api.file = [job({ id: 'job-3', status: 'QUEUED', editionId: 'ed-1' })];
      service.start();
      await vi.advanceTimersByTimeAsync(0);

      expect(service.activeJob()?.id).toBe('job-2');
      // La tâche planifiée sur ed-1 ne gèle pas la saisie de ed-1.
      expect(service.editingLocked()).toBe(false);
    });

    it('demande explicitement la mise en file et n’adopte pas la tâche planifiée', async () => {
      api.activeResponses = [{ status: 204, body: null }];
      service.start();
      await vi.advanceTimersByTimeAsync(0);
      api.postResult = job({ id: 'job-9', status: 'QUEUED' });

      const planifie = await service.submitSolveFromReferenceData(120, true);

      expect(api.postPreservingHttpError.mock.calls[0][0]).toContain('enFile=true');
      expect(planifie.status).toBe('QUEUED');
      // Planifiée n'est pas démarrée : rien ne doit prendre la place du job actif.
      expect(service.activeJob()).toBeNull();
    });

    it('reconnaît comme sienne la tâche qu’elle a planifiée quand elle démarre', async () => {
      api.activeResponses = [{ status: 204, body: null }];
      service.start();
      await vi.advanceTimersByTimeAsync(0);
      api.postResult = job({ id: 'job-9', status: 'QUEUED' });
      // A server that just answered QUEUED also lists that job in its queue:
      // without this the fake contradicts itself, and the poll pace derived
      // from the queue would read "nothing pending".
      api.file = [job({ id: 'job-9', status: 'QUEUED' })];
      await service.submitSolveFromReferenceData(120, true);

      // Le serveur la promeut : elle arrive par le poll, pas par un submit.
      api.activeResponses = [{ status: 200, body: job({ id: 'job-9', status: 'RUNNING' }) }];
      await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);

      expect(service.activeJob()?.id).toBe('job-9');
      // Sans quoi l'IHM annoncerait un démarrage « depuis une autre session ».
      expect(service.activeJob()?.mine).toBe(true);
    });

    it('distingue « déjà en cours » de « déjà planifiée » sur un 409', async () => {
      api.activeResponses = [{ status: 204, body: null }];
      service.start();
      await vi.advanceTimersByTimeAsync(0);

      api.postResult = conflit(job({ id: 'job-2', status: 'RUNNING' }));
      await expect(service.submitSolveFromReferenceData(120)).rejects.toThrow(/déjà en cours/);

      // Le 409 « en cours » a fait découvrir job-2 : c'est voulu, il tient
      // vraiment le solveur.
      expect(service.activeJob()?.id).toBe('job-2');

      api.postResult = conflit(job({ id: 'job-3', status: 'QUEUED' }));
      await expect(service.submitSolveFromReferenceData(120, true)).rejects.toThrow(
        /déjà planifiée/,
      );
      // Une tâche seulement planifiée, elle, ne prend la place de personne.
      expect(service.activeJob()?.id).toBe('job-2');
    });

    it('signale tout refus par une notification, pas seulement par l’erreur rendue', async () => {
      api.activeResponses = [{ status: 204, body: null }];
      service.start();
      await vi.advanceTimersByTimeAsync(0);
      notifications.notify.mockClear();
      api.postResult = conflit(job({ id: 'job-3', status: 'QUEUED', editionNom: 'Canicule' }));

      await expect(service.submitSolveFromReferenceData(120, true)).rejects.toThrow();

      // Sans elle, le bouton refusé est indiscernable d'un bouton sans effet :
      // le message n'apparaîtrait qu'au bas de la page de résolution.
      expect(notifications.notify).toHaveBeenCalledTimes(1);
      const notifiee = notifications.notify.mock.calls[0][0];
      expect(notifiee.variant).toBe('error');
      expect(notifiee.message).toMatch(/déjà planifiée/);
      expect(notifiee.message).toContain('Canicule');
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

  describe('editingLocked', () => {
    it('locks while the running job works on the edition this browser is on', async () => {
      api.activeResponses = [{ status: 200, body: job({ editionId: 'ed-1' }) }];
      service.start();
      await vi.advanceTimersByTimeAsync(0);

      expect(service.editingLocked()).toBe(true);
    });

    it('does not lock when the job works on another edition', async () => {
      api.activeResponses = [{ status: 200, body: job({ editionId: 'ed-2' }) }];
      service.start();
      await vi.advanceTimersByTimeAsync(0);

      expect(service.editingLocked()).toBe(false);
      expect(service.solverBusy()).toBe(true);
    });

    it('stays pessimistic when the job does not say which edition it works on', async () => {
      api.activeResponses = [{ status: 200, body: job({ editionId: null, editionNom: null }) }];
      service.start();
      await vi.advanceTimersByTimeAsync(0);

      expect(service.editingLocked()).toBe(true);
    });

    it('stays pessimistic while the current edition is not known yet', async () => {
      editionCourante = null;
      api.activeResponses = [{ status: 200, body: job({ editionId: 'ed-2' }) }];
      service.start();
      await vi.advanceTimersByTimeAsync(0);

      expect(service.editingLocked()).toBe(true);
    });

    it('is idle-unlocked once the server has answered, like solverBusy', async () => {
      api.activeResponses = [{ status: 204, body: null }];
      service.start();
      await vi.advanceTimersByTimeAsync(0);

      expect(service.editingLocked()).toBe(false);
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

  describe('polling loop', () => {
    /** Answers "a job is running" for as many polls as a test needs. */
    function alwaysRunning(view: JobView = job()): void {
      api.getResponse = vi.fn(async () => ({ status: 200, body: view }));
    }

    it('stops querying the server once the loop is stopped', async () => {
      api.getResponse = vi.fn(async () => ({ status: 204, body: null }));
      service.start();
      await vi.advanceTimersByTimeAsync(0);
      const afterStart = api.getResponse.mock.calls.length;

      service.stop();
      await vi.advanceTimersByTimeAsync(10 * 60 * 1000);

      expect(api.getResponse.mock.calls.length).toBe(afterStart);
    });

    it('can be restarted after a stop, as a new shell would', async () => {
      api.getResponse = vi.fn(async () => ({ status: 204, body: null }));
      service.start();
      await vi.advanceTimersByTimeAsync(0);
      service.stop();
      const afterStop = api.getResponse.mock.calls.length;

      service.start();
      await vi.advanceTimersByTimeAsync(0);

      expect(api.getResponse.mock.calls.length).toBeGreaterThan(afterStop);
    });

    it('polls every two seconds while a job runs', async () => {
      alwaysRunning();
      service.start();
      await vi.advanceTimersByTimeAsync(0);
      const afterStart = api.getResponse.mock.calls.length;

      await vi.advanceTimersByTimeAsync(6000);

      expect(api.getResponse.mock.calls.length - afterStart).toBe(3);
    });

    it('backs off to one poll every thirty seconds while the solver is idle', async () => {
      api.getResponse = vi.fn(async () => ({ status: 204, body: null }));
      service.start();
      await vi.advanceTimersByTimeAsync(0);
      const afterStart = api.getResponse.mock.calls.length;

      await vi.advanceTimersByTimeAsync(6000);
      expect(api.getResponse.mock.calls.length - afterStart).toBe(0);

      await vi.advanceTimersByTimeAsync(30_000);
      expect(api.getResponse.mock.calls.length - afterStart).toBe(1);
    });

    it('speeds back up as soon as a job is picked up', async () => {
      api.activeResponses = [
        { status: 204, body: null },
        { status: 200, body: job() },
      ];
      const responses = api.activeResponses;
      api.getResponse = vi.fn(async () => responses.shift() ?? { status: 200, body: job() });
      service.start();
      await vi.advanceTimersByTimeAsync(0);

      // Idle: the next poll only happens after the slow interval.
      await vi.advanceTimersByTimeAsync(30_000);
      const afterPickup = api.getResponse.mock.calls.length;

      // A job is running now, so the loop must be back to the fast interval.
      await vi.advanceTimersByTimeAsync(4000);

      expect(api.getResponse.mock.calls.length - afterPickup).toBe(2);
    });

    it('reloads the queue only when the identity of the active job changes', async () => {
      alwaysRunning();
      service.start();
      await vi.advanceTimersByTimeAsync(0);
      const afterFirstPoll = api.get.mock.calls.filter(([url]) => url === '/api/jobs/file').length;

      await vi.advanceTimersByTimeAsync(6000);

      const later = api.get.mock.calls.filter(([url]) => url === '/api/jobs/file').length;
      expect(afterFirstPoll).toBe(1);
      expect(later).toBe(afterFirstPoll);
    });

    it('reloads the queue when another job takes the solver', async () => {
      const responses = [
        { status: 200, body: job({ id: 'job-1' }) },
        { status: 200, body: job({ id: 'job-2' }) },
      ];
      api.getResponse = vi.fn(
        async () => responses.shift() ?? { status: 200, body: job({ id: 'job-2' }) },
      );
      api.jobsById['job-1'] = job({ id: 'job-1', status: 'COMPLETED' });
      service.start();
      await vi.advanceTimersByTimeAsync(0);
      const afterFirstPoll = api.get.mock.calls.filter(([url]) => url === '/api/jobs/file').length;

      await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
      await vi.advanceTimersByTimeAsync(0);

      const later = api.get.mock.calls.filter(([url]) => url === '/api/jobs/file').length;
      expect(later).toBe(afterFirstPoll + 1);
    });
  });
});
