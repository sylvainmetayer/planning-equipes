// The two pieces of `SolverPage` the report names as the missing half of its
// safety net: `applySolveResult` — reached through the real result handler the
// page registers on `SolverJobService`, not called directly — and
// `raisonVerrou`, the sentence that says why the buttons are dead.
//
// `solver-duration.spec.ts` already covers the duration arithmetic. What was
// missing here is everything that needs the component built, which is why this
// spec carries the eleven mocks the page injects. The component is created but
// never rendered, so this stays a logic test.

import { provideZonelessChangeDetection, Signal, WritableSignal, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { NotificationService } from '../../core/notification.service';
import { PlanningResolutionStore } from '../../core/planning-resolution.store';
import { PlanningStateService } from '../../core/planning-state.service';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { SolverJobService, TrackedJob } from '../../core/solver-job.service';
import { SolverSettingsService } from '../../core/solver-settings.service';
import { ConfirmService } from '../../shared/confirm-dialog';
import {
  ChangementAffectation,
  ConstraintDiagnostic,
  FeasibilityReport,
  JobView,
  PlanningDiagnostic,
  ResultatSolveIncremental,
  StatistiquesIncremental
} from '../../core/models';
import { HardIssue } from '../../shared/feasibility-banner';
import { SolverPage } from './solver-page';

function contrainte(name: string, score: string, matchCount = 1): ConstraintDiagnostic {
  return { name, score, matchCount, violations: [] };
}

function report(): FeasibilityReport {
  return { feasible: false, manqueAnimateurs: 2, causes: [], totalCauses: 0, message: 'Non réalisable.' };
}

function diagnostic(overrides: Partial<PlanningDiagnostic> = {}): PlanningDiagnostic {
  return {
    score: '-3hard/0medium/-120soft',
    postesNonPourvus: 4,
    contraintes: [],
    faisabilite: null,
    hardScore: -3,
    contraintesAdHocEnCause: [],
    ...overrides
  };
}

function statistiques(): StatistiquesIncremental {
  return { postesTotal: 200, postesFiges: 180, postesLiberes: 20, postesLiberesManuellement: 2, postesNouveaux: 0 };
}

function changement(standId: string): ChangementAffectation {
  return {
    standId,
    standNom: standId,
    creneauId: 1,
    date: '2026-08-01',
    heureDebut: '10:00',
    heureFin: '12:00',
    avant: ['Alice'],
    apres: ['Bob']
  };
}

/** What `SolverJobService.activeJob` holds: a job rebased on local time. */
function tracked(overrides: Partial<TrackedJob> = {}): TrackedJob {
  return {
    id: 'j1',
    type: 'SOLVE',
    label: 'Résolution',
    startedAtMs: Date.now(),
    mine: true,
    editionId: 'festival-2026',
    editionNom: 'Festival 2026',
    secondsLimit: 600,
    ...overrides
  };
}

/** What `listJobs()` returns: the server view, with its completion timestamp. */
function jobView(overrides: Partial<JobView> = {}): JobView {
  return {
    id: 'j1',
    type: 'SOLVE',
    status: 'COMPLETED',
    editionId: 'festival-2026',
    editionNom: 'Festival 2026',
    secondsLimit: 600,
    submittedAt: '2026-08-01T11:00:00Z',
    startedAt: '2026-08-01T11:00:00Z',
    finishedAt: '2026-08-01T12:00:00Z',
    elapsedSeconds: 3600,
    error: null,
    result: null,
    ...overrides
  };
}

/** Reaches the protected/private members the template and the job service reach. */
type PageInternals = {
  output: Signal<string>;
  feasibility: Signal<FeasibilityReport | null>;
  hardScore: Signal<number | null>;
  hardIssues: Signal<HardIssue[]>;
  exportBusy: WritableSignal<boolean>;
  incrementalStats: Signal<StatistiquesIncremental | null>;
  incrementalChangements: Signal<ChangementAffectation[]>;
  raisonVerrou: Signal<string>;
  lastRunAt: Signal<string | null>;
};

describe('SolverPage', () => {
  const activeJob = signal<TrackedJob | null>(null);
  const solverBusy = signal(false);
  const editingLocked = signal(false);
  /** Handlers the page registers per job type, so the tests can push a result. */
  const handlers = new Map<string, ((result: unknown) => void)[]>();

  const jobs = {
    activeJob,
    solverBusy: () => solverBusy(),
    editingLocked: () => editingLocked(),
    file: () => [],
    activeJobDescription: vi.fn(() => 'Une résolution est en cours (autre navigateur).'),
    estimatedEndMs: () => null,
    remainingSeconds: () => null,
    listJobs: vi.fn(),
    onResult: vi.fn((type: string, handler: (result: unknown) => void) => {
      handlers.set(type, [...(handlers.get(type) ?? []), handler]);
      return () => handlers.set(type, (handlers.get(type) ?? []).filter((entry) => entry !== handler));
    })
  };
  const api = { get: vi.fn(), post: vi.fn(), downloadPost: vi.fn() };
  const planningState = { set: vi.fn(), require: vi.fn() };
  const solverSettings = { refresh: vi.fn(), secondsLimit: () => 600 };
  const notifications = { notify: vi.fn() };
  const confirm = { ask: vi.fn() };
  const dialog = { open: vi.fn(() => ({ afterClosed: () => ({ subscribe: vi.fn() }) })) };
  const crud = { reload: vi.fn(), reportError: vi.fn() };
  // `resolution()` and `problemes()` are bound by the template, which does
  // render here: `TestBed` auto-detects changes, so an incomplete mock would
  // throw during change detection instead of failing an assertion.
  const resolution = { dataStale: () => false, resolution: () => null, reload: vi.fn() };
  const problemes = {
    reload: vi.fn(),
    alerteReglesLegales: () => '',
    comptage: () => ({ total: 0 }),
    problemes: () => []
  };

  beforeEach(() => {
    handlers.clear();
    activeJob.set(null);
    solverBusy.set(false);
    editingLocked.set(false);
    for (const stub of [
      jobs.listJobs,
      jobs.onResult,
      jobs.activeJobDescription,
      api.get,
      api.post,
      api.downloadPost,
      planningState.set,
      planningState.require,
      solverSettings.refresh,
      notifications.notify,
      confirm.ask,
      crud.reload,
      crud.reportError,
      resolution.reload,
      problemes.reload
    ]) {
      stub.mockClear();
    }
    jobs.listJobs.mockResolvedValue([]);
    api.get.mockResolvedValue({});
    solverSettings.refresh.mockResolvedValue(undefined);
    crud.reload.mockResolvedValue(undefined);
    resolution.reload.mockResolvedValue(undefined);
    problemes.reload.mockResolvedValue(undefined);
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: ApiService, useValue: api },
        { provide: SolverJobService, useValue: jobs },
        { provide: PlanningStateService, useValue: planningState },
        { provide: SolverSettingsService, useValue: solverSettings },
        { provide: NotificationService, useValue: notifications },
        { provide: ConfirmService, useValue: confirm },
        { provide: MatDialog, useValue: dialog },
        { provide: ReferenceCrudService, useValue: crud },
        { provide: PlanningResolutionStore, useValue: resolution },
        { provide: ProblemesStore, useValue: problemes }
      ]
    });
  });

  let fixture: ComponentFixture<SolverPage>;

  function createPage(): PageInternals {
    fixture = TestBed.createComponent(SolverPage);
    return fixture.componentInstance as unknown as PageInternals;
  }

  /** Pushes a job result the way `SolverJobService` does, whoever started the job. */
  function pushResult(type: 'SOLVE' | 'SOLVE_INCREMENTAL', result: unknown): void {
    for (const handler of handlers.get(type) ?? []) {
      handler(result);
    }
  }

  describe('applying a solve result', () => {
    // The page subscribes to both job types: a solve launched from another
    // browser lands here too, already analysed.
    it('listens to both a full solve and an incremental one', () => {
      createPage();

      expect(jobs.onResult).toHaveBeenCalledTimes(2);
      expect(handlers.get('SOLVE')).toHaveLength(1);
      expect(handlers.get('SOLVE_INCREMENTAL')).toHaveLength(1);
    });

    // The solved planning is not in the job result (it can weigh dozens of MB):
    // dropping the cached one is what makes the calendars re-read the fresh
    // plan instead of a stale in-memory copy.
    it('drops the cached planning so the display screens re-read the fresh one', () => {
      const page = createPage();

      pushResult('SOLVE', diagnostic());

      expect(planningState.set).toHaveBeenCalledExactlyOnceWith(null);
      expect(page.hardScore()).toBe(-3);
    });

    it('keeps the feasibility report the diagnostic carries', () => {
      const page = createPage();

      pushResult('SOLVE', diagnostic({ faisabilite: report() }));

      expect(page.feasibility()?.manqueAnimateurs).toBe(2);
    });

    // `ConstraintDiagnostic` carries no `niveau`, unlike the Contraintes page:
    // the hard part of the raw score string is the only discriminator.
    it('keeps only the constraints with a negative hard part', () => {
      const page = createPage();

      pushResult(
        'SOLVE',
        diagnostic({
          contraintes: [
            contrainte('reposObligatoire', '-14hard/0medium/0soft', 14),
            contrainte('equiteHeures', '0hard/0medium/-120soft', 30),
            contrainte('couverture', '0hard/-2medium/0soft', 2)
          ]
        })
      );

      expect(page.hardIssues()).toEqual([{ name: 'reposObligatoire', matchCount: 14 }]);
    });

    it('reports no hard issue on a plan that satisfies every hard rule', () => {
      const page = createPage();

      pushResult('SOLVE', diagnostic({ contraintes: [contrainte('equiteHeures', '0hard/0medium/-120soft')] }));

      expect(page.hardIssues()).toEqual([]);
    });

    it('dumps the whole diagnostic in the output panel', () => {
      const page = createPage();

      pushResult('SOLVE', diagnostic({ score: '-3hard/0medium/-120soft' }));

      expect(page.output()).toContain('-3hard/0medium/-120soft');
      expect(page.output()).toContain('postesNonPourvus');
    });

    it('re-reads the problem sources the solve rewrote server-side', () => {
      createPage();
      problemes.reload.mockClear();

      pushResult('SOLVE', diagnostic());

      expect(problemes.reload).toHaveBeenCalledOnce();
    });

    // This page is lazy-loaded and rebuilt on every navigation: a handler left
    // behind would stack one more copy per visit, and every stale copy would
    // keep writing into a component nobody looks at.
    it('unregisters both handlers when the page is destroyed', () => {
      createPage();
      expect(handlers.get('SOLVE')).toHaveLength(1);

      fixture.destroy();

      expect(handlers.get('SOLVE')).toEqual([]);
      expect(handlers.get('SOLVE_INCREMENTAL')).toEqual([]);
    });

    it('leaves nothing behind that would still react after the page is gone', () => {
      createPage();
      fixture.destroy();
      planningState.set.mockClear();

      pushResult('SOLVE', diagnostic());

      expect(planningState.set).not.toHaveBeenCalled();
    });

    it('ignores a result carrying no diagnostic at all', () => {
      const page = createPage();

      pushResult('SOLVE', null);

      expect(planningState.set).not.toHaveBeenCalled();
      expect(page.hardScore()).toBeNull();
    });
  });

  describe('applying an incremental result', () => {
    function resultatIncremental(): ResultatSolveIncremental {
      return {
        diagnostic: diagnostic({ hardScore: 0 }),
        statistiques: statistiques(),
        changements: [changement('tir'), changement('quilles')]
      };
    }

    // An incremental result wraps the diagnostic (issue #86) instead of being
    // one: both shapes have to land in the same state.
    it('unwraps the diagnostic an incremental result carries', () => {
      const page = createPage();

      pushResult('SOLVE_INCREMENTAL', resultatIncremental());

      expect(page.hardScore()).toBe(0);
      expect(planningState.set).toHaveBeenCalledExactlyOnceWith(null);
    });

    it('keeps the crews that moved, with the statistics that frame them', () => {
      const page = createPage();

      pushResult('SOLVE_INCREMENTAL', resultatIncremental());

      expect(page.incrementalStats()?.postesLiberes).toBe(20);
      expect(page.incrementalChangements().map((row) => row.standId)).toEqual(['tir', 'quilles']);
    });

    // The diff would otherwise describe a planning that no longer exists.
    it('clears the incremental diff as soon as a full solve replaces the plan', () => {
      const page = createPage();
      pushResult('SOLVE_INCREMENTAL', resultatIncremental());
      expect(page.incrementalStats()).not.toBeNull();

      pushResult('SOLVE', diagnostic());

      expect(page.incrementalStats()).toBeNull();
      expect(page.incrementalChangements()).toEqual([]);
    });
  });

  describe('why the solver actions are locked', () => {
    // A disabled button with no explanation is the classic dead end: the user
    // clicks, nothing happens, and nothing says another browser holds the lock.
    it('says nothing while nothing holds the lock', () => {
      const page = createPage();

      expect(page.raisonVerrou()).toBe('');
    });

    it('names a running job, whoever started it', () => {
      solverBusy.set(true);
      activeJob.set(tracked());
      const page = createPage();

      expect(page.raisonVerrou()).toBe('Une résolution est en cours (autre navigateur).');
    });

    // Pessimistic before the first server answer: the service reports busy
    // while it does not know yet, and the page has to say so rather than name
    // a job it cannot describe.
    it('explains the unknown state before the first server answer', () => {
      solverBusy.set(true);
      activeJob.set(null);
      const page = createPage();

      expect(page.raisonVerrou()).toContain("n'est pas encore connu");
    });

    // An export in flight reads the persisted planning: it wins over the solver
    // state, because it is the reason the user is actually blocked right now.
    it('puts a running export ahead of the solver state', () => {
      solverBusy.set(true);
      activeJob.set(tracked());
      const page = createPage();

      page.exportBusy.set(true);

      expect(page.raisonVerrou()).toContain('export');
    });

    it('names the export even when the solver is idle', () => {
      const page = createPage();

      page.exportBusy.set(true);

      expect(page.raisonVerrou()).toContain('export');
    });

    it('goes back to silence once the job ends', () => {
      solverBusy.set(true);
      activeJob.set(tracked());
      const page = createPage();
      expect(page.raisonVerrou()).not.toBe('');

      solverBusy.set(false);
      activeJob.set(null);

      expect(page.raisonVerrou()).toBe('');
    });
  });

  describe('the last completed run', () => {
    // `listJobs()` is submitted-desc and jobs never overlap, so the first
    // entry with a finishedAt is the most recent run.
    it('takes the most recent finished job', async () => {
      jobs.listJobs.mockResolvedValue([
        jobView({ id: 'running', status: 'RUNNING', finishedAt: null }),
        jobView({ id: 'done', finishedAt: '2026-08-01T12:00:00Z' }),
        jobView({ id: 'older', finishedAt: '2026-07-01T12:00:00Z' })
      ]);

      const page = createPage();
      await vi.waitFor(() => expect(page.lastRunAt()).not.toBeNull());

      expect(page.lastRunAt()).toBe('2026-08-01T12:00:00Z');
    });

    it('reports no run when none has ever finished', async () => {
      jobs.listJobs.mockResolvedValue([jobView({ status: 'RUNNING', finishedAt: null })]);

      const page = createPage();
      await vi.waitFor(() => expect(jobs.listJobs).toHaveBeenCalled());

      expect(page.lastRunAt()).toBeNull();
    });

    // Best-effort: the page still works without the history.
    it('survives a history the server refuses to serve', async () => {
      jobs.listJobs.mockRejectedValue(new Error('Historique indisponible.'));

      const page = createPage();
      await vi.waitFor(() => expect(jobs.listJobs).toHaveBeenCalled());

      expect(page.lastRunAt()).toBeNull();
      expect(notifications.notify).not.toHaveBeenCalled();
    });

    it('re-reads the history after a solve lands', async () => {
      createPage();
      await vi.waitFor(() => expect(jobs.listJobs).toHaveBeenCalledOnce());

      pushResult('SOLVE', diagnostic());

      expect(jobs.listJobs).toHaveBeenCalledTimes(2);
    });
  });

  describe('a job started somewhere else', () => {
    // `TestBed.tick()` and not an awaited microtask: the explanation is written
    // by an `effect`, which only runs on a change-detection pass. Awaiting a
    // promise lets the assertion run before the effect ever fires, which makes
    // the negative test below vacuous — it passed against a mutant that wrote
    // the message for my own jobs too.
    it('explains the lock in the output panel when the job is not mine', () => {
      const page = createPage();

      activeJob.set(tracked({ mine: false }));
      TestBed.tick();

      expect(page.output()).toContain('verrouillées');
      expect(page.output()).toContain('autre navigateur');
    });

    it('says nothing when the running job is mine', () => {
      const page = createPage();

      activeJob.set(tracked({ mine: true }));
      TestBed.tick();

      expect(page.output()).toBe('');
    });
  });
});
