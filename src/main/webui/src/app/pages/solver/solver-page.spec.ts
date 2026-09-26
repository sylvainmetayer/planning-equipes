// What the page still owns once its cards became components: the result
// handler it registers on `SolverJobService` (`applySolveResult`, reached
// through the real handler and not called directly), the three ways of
// launching a solve, and `raisonVerrou`, the sentence that says why the
// buttons are dead. The cards test themselves next door — `score-curve-card`,
// `publication-panel`, `solve-recap`, `solver-queue`,
// `solver-volumetry`, `incremental-result` — and render here for real, on the
// same mocks.

import { provideZonelessChangeDetection, Signal, signal } from '@angular/core';
import { provideRouter } from '@angular/router';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AnalysesApi } from '../../core/api/analyses-api';
import { PlanningApi } from '../../core/api/planning-api';
import { PlanSnapshotStore } from '../../core/plan-snapshot.store';
import { NotificationService } from '../../core/notification.service';
import { PlanningResolutionStore } from '../../core/planning-resolution.store';
import { PlanningStateService } from '../../core/planning-state.service';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { SolverJobService, TrackedJob } from '../../core/solver-job.service';
import { SolverSettingsService } from '../../core/solver-settings.service';
import { ConfirmService } from '../../shared/confirm-dialog';
import { ModifiedFormsRegistry, UnsavedEntry } from '../../core/formulaires-modifies';
import {
  CauseInfaisabilite,
  ChangementAffectation,
  ConstraintsView,
  ConstraintDiagnostic,
  FeasibilityReport,
  JobView,
  PlanningDiagnostic,
  ImpactPublication,
  PreviousPlan,
  ReamorcageEffectue,
  ResultatSolveIncremental,
  ScoreSentence,
  ScoreTrace,
  StatistiquesIncremental,
} from '../../core/models';
import { HardIssue } from '../../shared/feasibility-banner';
import { SolverPage } from './solver-page';

function contrainte(name: string, score: string, matchCount = 1): ConstraintDiagnostic {
  return { name, score, matchCount, violations: [] };
}

function report(): FeasibilityReport {
  return {
    feasible: false,
    manqueAnimateurs: 2,
    causes: [],
    totalCauses: 0,
    causesCritiques: 0,
    causesElevees: 0,
    message: 'Non réalisable.',
  };
}

function diagnostic(overrides: Partial<PlanningDiagnostic> = {}): PlanningDiagnostic {
  return {
    score: '-3hard/0medium/-120soft',
    postesNonPourvus: 4,
    contraintes: [],
    faisabilite: null,
    hardScore: -3,
    contraintesAdHocEnCause: [],
    scoreHorsPlancher: '-3hard/0medium/-120soft',
    plancherMedium: 0,
    plancherSoft: 0,
    pivotEcarts: [],
    lecture: [],
    ...overrides,
  };
}

function statistiques(): StatistiquesIncremental {
  return {
    postesTotal: 200,
    postesFiges: 180,
    postesLiberes: 20,
    postesLiberesManuellement: 2,
    postesNouveaux: 0,
    postesPasses: 0,
  };
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
    apres: ['Bob'],
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
    ...overrides,
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
    ...overrides,
  };
}

/** Reaches the protected/private members the template and the job service reach. */
type PageInternals = {
  output: Signal<string>;
  feasibility: Signal<FeasibilityReport | null>;
  hardScore: Signal<number | null>;
  hardIssues: Signal<HardIssue[]>;
  incrementalStats: Signal<StatistiquesIncremental | null>;
  incrementalChangements: Signal<ChangementAffectation[]>;
  raisonVerrou: Signal<string>;
  lastRunAt: Signal<string | null>;
  planPrecedent: Signal<PreviousPlan | null>;
  score: Signal<string | null>;
  reamorcageEffectue: Signal<ReamorcageEffectue | null>;
  impactPublication: Signal<ImpactPublication | null>;
  pointDeDepart: Signal<string>;
  planEnregistre: Signal<boolean>;
  reading: Signal<ScoreSentence[] | null>;
  onPlanPrecedentRestaure: (message: string) => void;
  onRecommencerDeZero: () => Promise<void>;
  onTimefoldSolve: (reamorcage?: string) => Promise<void>;
};

/** A sentence of a reading, told apart by its words. */
function phrase(texte: string): ScoreSentence {
  return { sujet: 'VERDICT', niveau: 'INFO', texte, liens: [] };
}

function incrementalResult(): ResultatSolveIncremental {
  return {
    diagnostic: diagnostic({ hardScore: 0 }),
    statistiques: statistiques(),
    changements: [changement('tir'), changement('quilles')],
    previousPlan: null,
  };
}

describe('SolverPage', () => {
  const activeJob = signal<TrackedJob | null>(null);
  const solverBusy = signal(false);
  const editingLocked = signal(false);
  /** The live score curve of issue #304, already narrowed to this edition. */
  const scoreTraceEdition = signal<ScoreTrace | null>(null);
  /** Handlers the page registers per job type, so the tests can push a result. */
  const handlers = new Map<string, ((result: unknown) => void)[]>();

  const jobs = {
    activeJob,
    solverBusy: () => solverBusy(),
    editingLocked: () => editingLocked(),
    file: () => [],
    scoreTraceEdition,
    chargerCourbeScore: vi.fn(async () => undefined),
    activeJobDescription: vi.fn(() => 'Une résolution est en cours (autre navigateur).'),
    estimatedEndMs: () => null,
    remainingSeconds: () => null,
    listJobs: vi.fn(),
    submitSolveFromReferenceData: vi.fn(async () => ({})),
    onResult: vi.fn((type: string, handler: (result: unknown) => void) => {
      handlers.set(type, [...(handlers.get(type) ?? []), handler]);
      return () =>
        handlers.set(
          type,
          (handlers.get(type) ?? []).filter((entry) => entry !== handler),
        );
    }),
  };
  const planningApi = {
    solveInputs: vi.fn(async () => ({
      locks: 2,
      adjustments: 0,
      consignes: [{ date: '2026-08-02', motif: 'Canicule' }],
      pendingDeclarations: 1,
      disabledRules: 0,
      changesSinceSolve: 3,
      solvedAt: null,
    })),
    persistedCount: vi.fn(),
    publicationPreview: vi.fn(),
    publish: vi.fn(),
    exportGlobalPdf: vi.fn(),
    exportBundle: vi.fn(),
  };
  const planningState = { set: vi.fn(), require: vi.fn() };
  const solverSettings = {
    refresh: vi.fn(),
    secondsLimit: () => 600,
    effectivePlateauSeconds: () => 0,
    bounds: () => null,
    dureeResolutionSecondes: () => null,
    plateauSecondes: () => null,
  };
  const notifications = { notify: vi.fn() };
  const confirm = { ask: vi.fn() };
  const modifiedForms = { unsavedEntries: vi.fn((): UnsavedEntry[] => []) };
  const dialog = { open: vi.fn(() => ({ afterClosed: () => ({ subscribe: vi.fn() }) })) };
  const crud = { reload: vi.fn(), reportError: vi.fn() };
  // `resolution()` and `problemes()` are bound by the template, which does
  // render here: `TestBed` auto-detects changes, so an incomplete mock would
  // throw during change detection instead of failing an assertion.
  const resolution = { dataStale: () => false, resolution: () => null, reload: vi.fn() };
  const causesBloquantes = signal<CauseInfaisabilite[]>([]);
  /** The stored analysis, which a solve's own reading gives way to once superseded. */
  const constraints = signal<ConstraintsView | null>(null);
  const problemes = {
    reload: vi.fn(),
    alerteReglesLegales: () => '',
    constraints,
    alertePausesSansRelais: () => '',
    comptage: () => ({ total: 0 }),
    problemes: () => [],
    causesBloquantes,
  };

  beforeEach(() => {
    handlers.clear();
    activeJob.set(null);
    solverBusy.set(false);
    editingLocked.set(false);
    scoreTraceEdition.set(null);
    causesBloquantes.set([]);
    constraints.set(null);
    for (const stub of [
      jobs.listJobs,
      jobs.submitSolveFromReferenceData,
      jobs.onResult,
      jobs.chargerCourbeScore,
      jobs.activeJobDescription,
      planningApi.persistedCount,
      planningApi.publicationPreview,
      planningApi.publish,
      planningApi.exportGlobalPdf,
      planningApi.exportBundle,
      planningState.set,
      planningState.require,
      solverSettings.refresh,
      notifications.notify,
      confirm.ask,
      crud.reload,
      crud.reportError,
      resolution.reload,
      problemes.reload,
    ]) {
      stub.mockClear();
    }
    jobs.listJobs.mockResolvedValue([]);
    planningApi.persistedCount.mockResolvedValue({});
    planningApi.publicationPreview.mockResolvedValue({});
    solverSettings.refresh.mockResolvedValue(undefined);
    crud.reload.mockResolvedValue(undefined);
    resolution.reload.mockResolvedValue(undefined);
    problemes.reload.mockResolvedValue(undefined);
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        // The page links to « Export & publication » since issue #320.
        provideRouter([]),
        { provide: PlanningApi, useValue: planningApi },
        { provide: SolverJobService, useValue: jobs },
        { provide: PlanningStateService, useValue: planningState },
        { provide: SolverSettingsService, useValue: solverSettings },
        { provide: NotificationService, useValue: notifications },
        { provide: ConfirmService, useValue: confirm },
        { provide: ModifiedFormsRegistry, useValue: modifiedForms },
        { provide: MatDialog, useValue: dialog },
        { provide: ReferenceCrudService, useValue: crud },
        { provide: PlanningResolutionStore, useValue: resolution },
        { provide: ProblemesStore, useValue: problemes },
        // « Dernières versions du plan » reads the history and the snapshots.
        { provide: AnalysesApi, useValue: { kpiHistory: vi.fn(async () => []) } },
        {
          provide: PlanSnapshotStore,
          useValue: { snapshots: signal([]), reload: vi.fn(async () => undefined) },
        },
      ],
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

  /** A stored analysis carrying one sentence, analysed at `analysedAt`. */
  function stored(analysedAt: string, texte: string): ConstraintsView {
    return { analysedAt, lecture: [phrase(texte)] } as unknown as ConstraintsView;
  }

  /** The order of the page, and the words on it (issue #719). */
  describe('the page as it reads', () => {
    function racine(): HTMLElement {
      return fixture.nativeElement as HTMLElement;
    }

    it('puts the feasibility first, then what the solve receives, then the buttons', async () => {
      createPage();
      await fixture.whenStable();

      const order = Array.from(
        racine().querySelectorAll('app-feasibility-banner, app-solve-inputs, .solver-modes'),
      ).map((element) => element.tagName.toLowerCase());
      expect(order).toEqual(['app-feasibility-banner', 'app-solve-inputs', 'ul']);
    });

    it('says what each of the three buttons does on the page, not in a tooltip', async () => {
      createPage();
      await fixture.whenStable();

      const modes = Array.from(racine().querySelectorAll('.solver-modes li'));
      expect(modes).toHaveLength(3);
      for (const mode of modes) {
        const button = mode.querySelector('button')!;
        const phrase = racine().querySelector(`#${button.getAttribute('aria-describedby')}`);
        expect(phrase?.textContent?.trim()).not.toBe('');
        expect(button.hasAttribute('mattooltip')).toBe(false);
      }
      expect(modes[1].textContent).toContain('Ne rouvre que ce que les changements ont invalidé');
    });

    it('writes « Solveur » once, and no search space', async () => {
      createPage();
      await fixture.whenStable();

      expect(racine().textContent!.match(/Solveur/g) ?? []).toHaveLength(1);
      expect(racine().textContent).not.toContain('espace de recherche');
    });

    it('puts the plan, its reading and its publication one click away after a run', async () => {
      jobs.listJobs.mockResolvedValue([jobView()]);
      createPage();
      await vi.waitFor(async () => {
        await fixture.whenStable();
        expect(racine().querySelector('a[href="/publication"]')).not.toBeNull();
      });

      const labels = Array.from(racine().querySelectorAll('mat-card-actions a')).map((each) =>
        each.textContent!.trim(),
      );
      expect(labels).toEqual(
        expect.arrayContaining([
          'view_dayVoir le planning',
          'fact_checkRelire',
          'outgoing_mailPublier',
        ]),
      );
    });

    it('shows « Corriger » what changed since the plan before asking for a perimeter', async () => {
      const page = createPage() as unknown as { onSolveIncremental: () => Promise<void> };
      dialog.open.mockClear();

      // The dialog never closes here: only what it was opened with matters.
      void page.onSolveIncremental();
      await vi.waitFor(() => expect(dialog.open).toHaveBeenCalledOnce());

      const [, config] = dialog.open.mock.calls[0] as unknown as [unknown, { data: unknown }];
      expect(config.data).toEqual({ depuis: '' });
    });
  });

  describe("the solve's reading", () => {
    it('stands over its own stored analysis, and gives way to the next one', async () => {
      constraints.set(stored('2026-07-01T10:00:00Z', 'avant'));
      const page = createPage();
      await fixture.whenStable();
      expect(page.reading()?.[0].texte).toBe('avant');

      pushResult('SOLVE', diagnostic({ lecture: [phrase('comparée')] }));
      await fixture.whenStable();
      expect(page.reading()?.[0].texte).toBe('comparée');

      // The re-read after the solve brings the solve's own analysis: the
      // reading with its comparison still stands.
      constraints.set(stored('2026-07-01T11:00:00Z', 'la même sans comparaison'));
      await fixture.whenStable();
      expect(page.reading()?.[0].texte).toBe('comparée');

      // A later analysis — the plan re-analysed or rewritten since.
      constraints.set(stored('2026-07-01T12:00:00Z', 'plus récente'));
      await fixture.whenStable();
      expect(page.reading()?.[0].texte).toBe('plus récente');
    });

    it('gives way to the stored analysis once the previous plan is restored', async () => {
      constraints.set(stored('2026-07-01T10:00:00Z', 'stockée'));
      const page = createPage();
      pushResult('SOLVE', diagnostic({ lecture: [phrase('comparée')] }));
      await fixture.whenStable();
      expect(page.reading()?.[0].texte).toBe('comparée');
      problemes.reload.mockClear();

      page.onPlanPrecedentRestaure('Plan restauré.');
      await fixture.whenStable();

      expect(page.reading()?.[0].texte).toBe('stockée');
      expect(problemes.reload).toHaveBeenCalledOnce();
    });
  });

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
            contrainte('couverture', '0hard/-2medium/0soft', 2),
          ],
        }),
      );

      expect(page.hardIssues()).toEqual([{ name: 'reposObligatoire', matchCount: 14 }]);
    });

    it('reports no hard issue on a plan that satisfies every hard rule', () => {
      const page = createPage();

      pushResult(
        'SOLVE',
        diagnostic({ contraintes: [contrainte('equiteHeures', '0hard/0medium/-120soft')] }),
      );

      expect(page.hardIssues()).toEqual([]);
    });

    /**
     * Issue #589: the output panel is a state line, never the serialised
     * diagnostic. Everything an organiser looks for in it is read by the cards
     * above, and the dump chased away the last state or error message, which
     * shares that one panel.
     */
    it('says the solve is over instead of dumping the diagnostic in the output panel', () => {
      const page = createPage();

      pushResult('SOLVE', diagnostic({ score: '-3hard/0medium/-120soft' }));

      expect(page.output()).not.toContain('postesNonPourvus');
      expect(page.output()).not.toContain('-3hard/0medium/-120soft');
      expect(page.output()).not.toContain('{');
      expect(page.output()).toContain('Résolution terminée');
    });

    /** The sentence says which of the two it was, since the panel no longer shows the numbers. */
    it('distinguishes a feasible plan from one that is not yet', () => {
      const page = createPage();

      pushResult('SOLVE', diagnostic({ faisabilite: { ...report(), feasible: false } }));
      expect(page.output()).toContain("n'est pas encore faisable");

      pushResult('SOLVE', diagnostic({ faisabilite: { ...report(), feasible: true } }));
      expect(page.output()).toContain('est faisable');
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
    // An incremental result wraps the diagnostic (issue #86) instead of being
    // one: both shapes have to land in the same state.
    it('unwraps the diagnostic an incremental result carries', () => {
      const page = createPage();

      pushResult('SOLVE_INCREMENTAL', incrementalResult());

      expect(page.hardScore()).toBe(0);
      expect(planningState.set).toHaveBeenCalledExactlyOnceWith(null);
    });

    it('keeps the crews that moved, with the statistics that frame them', () => {
      const page = createPage();

      pushResult('SOLVE_INCREMENTAL', incrementalResult());

      expect(page.incrementalStats()?.postesLiberes).toBe(20);
      expect(page.incrementalChangements().map((row) => row.standId)).toEqual(['tir', 'quilles']);
    });

    // The diff would otherwise describe a planning that no longer exists.
    it('clears the incremental diff as soon as a full solve replaces the plan', () => {
      const page = createPage();
      pushResult('SOLVE_INCREMENTAL', incrementalResult());
      expect(page.incrementalStats()).not.toBeNull();

      pushResult('SOLVE', diagnostic());

      expect(page.incrementalStats()).toBeNull();
      expect(page.incrementalChangements()).toEqual([]);
    });
  });

  // Issue #274: a solve announced its own score and never the one it
  // replaced, so re-solving a good plan read as a success — "0 hard" — while
  // costing medium points nobody was shown.
  describe('the plan a solve replaced', () => {
    const resultat = (previousPlan: PreviousPlan | null): unknown => ({
      diagnostic: diagnostic({ score: '0hard/-7434medium/-564soft', hardScore: 0 }),
      previousPlan,
    });

    it('compares the score before and after the solve', () => {
      const page = createPage();

      pushResult(
        'SOLVE',
        resultat({ snapshotId: 12, score: '0hard/-6232medium/-920soft', degraded: true }),
      );

      expect(page.planPrecedent()?.score).toBe('0hard/-6232medium/-920soft');
      expect(page.score()).toBe('0hard/-7434medium/-564soft');
      expect(page.planPrecedent()?.degraded).toBe(true);
    });

    // The whole point is to be believed: flagging a solve that improved things
    // would train the user to ignore the flag.
    it('flags nothing when the solve improved the plan', () => {
      const page = createPage();

      pushResult(
        'SOLVE',
        resultat({ snapshotId: 12, score: '0hard/-9000medium/-999soft', degraded: false }),
      );

      expect(page.planPrecedent()?.degraded).toBe(false);
    });

    it('keeps the snapshot to go back to even when its score is unknown', () => {
      const page = createPage();

      pushResult('SOLVE', resultat({ snapshotId: 12, score: null, degraded: false }));

      expect(page.planPrecedent()?.snapshotId).toBe(12);
      expect(page.planPrecedent()?.score).toBeNull();
    });

    it('keeps no previous plan on the first solve of an edition', () => {
      const page = createPage();

      pushResult('SOLVE', resultat(null));

      expect(page.planPrecedent()).toBeNull();
    });

    // A job that finished before this shipped answers a bare diagnostic: it
    // must still display, minus the comparison.
    it('still displays a result carrying no previous plan at all', () => {
      const page = createPage();

      pushResult('SOLVE', diagnostic({ hardScore: 0 }));

      expect(page.hardScore()).toBe(0);
      expect(page.planPrecedent()).toBeNull();
    });
  });

  /** Persisted-plan count the page reads at load and after every result. */
  function persistedCount(assignments: number | null): void {
    planningApi.persistedCount.mockResolvedValue(assignments === null ? {} : { assignments });
  }

  /**
   * Where a full solve starts from (issue #174): said before the click, said
   * again in the recap, and the only cold start there is asks first.
   */
  describe('where the solve starts from', () => {
    it('announces a cold start and disables « Recommencer de zéro » without a plan', async () => {
      persistedCount(0);
      const page = createPage();
      await fixture.whenStable();

      expect(page.pointDeDepart()).toContain('Aucun plan enregistré');
      expect(page.planEnregistre()).toBe(false);
    });

    it('announces the saved plan it will restart from', async () => {
      persistedCount(12);
      const page = createPage();
      await fixture.whenStable();

      expect(page.pointDeDepart()).toContain('12 affectations');
      expect(page.pointDeDepart()).toContain('repart du plan enregistré');
      expect(page.planEnregistre()).toBe(true);
    });

    it('says nothing rather than something wrong when the count cannot be read', async () => {
      persistedCount(null);
      const page = createPage();
      await fixture.whenStable();

      expect(page.pointDeDepart()).toBe('');
      expect(page.planEnregistre()).toBe(false);
    });

    // The recap puts these in words (`solve-recap.spec.ts`); the page's part
    // is to pick them out of the result, and to hold nothing on a payload from
    // before the feature.
    it('keeps where the solve started from, and whom it would disturb', () => {
      const page = createPage();

      pushResult('SOLVE', {
        diagnostic: diagnostic({ hardScore: 0 }),
        previousPlan: null,
        reamorcage: { mode: 'PLAN_COURANT', postes: 10, postesLiberes: 2, postesPasses: 0 },
        impactPublication: { personnes: 12, publieLe: '2026-09-01T10:00:00Z' },
      });
      expect(page.reamorcageEffectue()).toEqual({
        mode: 'PLAN_COURANT',
        postes: 10,
        postesLiberes: 2,
        postesPasses: 0,
      });
      expect(page.impactPublication()).toEqual({ personnes: 12, publieLe: '2026-09-01T10:00:00Z' });

      pushResult('SOLVE', diagnostic({ hardScore: 0 }));
      expect(page.reamorcageEffectue()).toBeNull();
      expect(page.impactPublication()).toBeNull();
    });

    // The diffusion moved to « Export & publication » (issue #320): this page
    // solves, and nothing here reads who would be informed. The panel reads it
    // when that screen opens, which is after the solve by construction.
    it('reads the last publication once, and never again after a solve', async () => {
      createPage();
      await vi.waitFor(() => expect(planningApi.publicationPreview).toHaveBeenCalledOnce());

      pushResult('SOLVE', diagnostic());

      // The date only feeds the « Recommencer de zéro » warning, and a solve
      // does not change when the plan was last published.
      expect(planningApi.publicationPreview).toHaveBeenCalledOnce();
    });

    it('sends the default start with the everyday button, and nothing else', async () => {
      const page = createPage();

      await page.onTimefoldSolve();

      expect(jobs.submitSolveFromReferenceData).toHaveBeenCalledWith(undefined, false, 'AUTO');
    });

    it('asks before starting over from scratch, and does nothing when refused', async () => {
      persistedCount(12);
      const page = createPage();
      await fixture.whenStable();
      confirm.ask.mockResolvedValueOnce(false);

      await page.onRecommencerDeZero();

      expect(confirm.ask).toHaveBeenCalledWith(
        expect.objectContaining({ title: 'Recommencer de zéro ?', danger: true }),
      );
      expect(jobs.submitSolveFromReferenceData).not.toHaveBeenCalled();
    });

    it('starts cold, by name, once the user confirmed', async () => {
      persistedCount(12);
      const page = createPage();
      await fixture.whenStable();
      confirm.ask.mockResolvedValueOnce(true);

      await page.onRecommencerDeZero();

      expect(jobs.submitSolveFromReferenceData).toHaveBeenCalledWith(undefined, false, 'AUCUN');
    });
  });

  // Issue #30: a run that cannot come out at zero hard is machine time the
  // organiser gets nothing out of, and the cause only shows once it is over.
  describe('before spending solver time on an impossible problem', () => {
    const bloquante: CauseInfaisabilite = {
      type: 'AFFECTATION_FORCEE_SIEGE_VERROUILLE',
      severite: 'CRITIQUE',
      message: "L'affectation forcée C01 ne peut pas être tenue.",
      creneauId: null,
      date: null,
      heureDebut: null,
      heureFin: null,
      standIds: [],
      contrainteIds: ['C01'],
      demande: 0,
      capacite: 0,
      manque: 0,
    };

    it('does not ask when nothing blocks', async () => {
      const page = createPage();
      await fixture.whenStable();

      await page.onTimefoldSolve();

      expect(confirm.ask).not.toHaveBeenCalled();
      expect(jobs.submitSolveFromReferenceData).toHaveBeenCalled();
    });

    it('names the blocking cause and does nothing when refused', async () => {
      causesBloquantes.set([bloquante]);
      const page = createPage();
      await fixture.whenStable();
      confirm.ask.mockResolvedValueOnce(false);

      await page.onTimefoldSolve();

      expect(confirm.ask).toHaveBeenCalledWith(
        expect.objectContaining({ title: 'Lancer malgré un problème bloquant ?', danger: true }),
      );
      await expect(confirm.ask.mock.calls.at(-1)![0].detail).resolves.toContain('C01');
      expect(jobs.submitSolveFromReferenceData).not.toHaveBeenCalled();
    });

    // A dialog is not the Problèmes screen: three sentences, then a count.
    it('spells out three causes and counts the rest', async () => {
      causesBloquantes.set(
        ['C01', 'C02', 'C03', 'C04', 'C05'].map((id) => ({
          ...bloquante,
          message: `L'affectation forcée ${id} ne peut pas être tenue.`,
          contrainteIds: [id],
        })),
      );
      const page = createPage();
      await fixture.whenStable();
      confirm.ask.mockResolvedValueOnce(false);

      await page.onTimefoldSolve();

      const detail = await confirm.ask.mock.calls.at(-1)![0].detail;
      expect(detail).toContain('C01');
      expect(detail).toContain('C03');
      expect(detail).not.toContain('C04');
      expect(detail).toContain('2');
    });

    it('launches anyway once the user said so', async () => {
      causesBloquantes.set([bloquante]);
      const page = createPage();
      await fixture.whenStable();
      confirm.ask.mockResolvedValueOnce(true);

      await page.onTimefoldSolve();

      expect(jobs.submitSolveFromReferenceData).toHaveBeenCalledWith(undefined, false, 'AUTO');
    });

    // Two dialogs on one click would be one too many: the blocking question
    // comes first, and « Recommencer de zéro » asks its own only after it.
    it('asks it before the cold-start question, and stops there when refused', async () => {
      causesBloquantes.set([bloquante]);
      planningApi.persistedCount.mockResolvedValue({ assignments: 12 });
      const page = createPage();
      await fixture.whenStable();
      confirm.ask.mockResolvedValueOnce(false);

      await page.onRecommencerDeZero();

      expect(confirm.ask).toHaveBeenCalledTimes(1);
      expect(jobs.submitSolveFromReferenceData).not.toHaveBeenCalled();
    });
  });

  describe('an entry typed and not saved', () => {
    afterEach(() => modifiedForms.unsavedEntries.mockReturnValue([]));

    it('asks before solving without it, names it, and does nothing when refused', async () => {
      modifiedForms.unsavedEntries.mockReturnValue([{ key: 'k', label: 'la fiche du stand S1' }]);
      const page = createPage();
      await fixture.whenStable();
      confirm.ask.mockResolvedValueOnce(false);

      await page.onTimefoldSolve();

      expect(confirm.ask).toHaveBeenCalledWith(
        expect.objectContaining({ title: 'Calculer sans la saisie en cours ?' }),
      );
      await expect(confirm.ask.mock.calls.at(-1)![0].detail).resolves.toContain(
        'la fiche du stand S1',
      );
      expect(jobs.submitSolveFromReferenceData).not.toHaveBeenCalled();
    });

    it('solves once the user said so', async () => {
      modifiedForms.unsavedEntries.mockReturnValue([{ key: 'k', label: 'un nouveau stand' }]);
      const page = createPage();
      await fixture.whenStable();
      confirm.ask.mockResolvedValueOnce(true);

      await page.onTimefoldSolve();

      expect(jobs.submitSolveFromReferenceData).toHaveBeenCalledWith(undefined, false, 'AUTO');
    });

    it('changes nothing when nothing is being typed', async () => {
      const page = createPage();
      await fixture.whenStable();

      await page.onTimefoldSolve();

      expect(confirm.ask).not.toHaveBeenCalled();
      expect(jobs.submitSolveFromReferenceData).toHaveBeenCalled();
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

    // The export moved to « Export & publication » (issue #320), and with it
    // the only reason this page could be blocked by something other than the
    // solver: the lock reason is now the solver's, and only the solver's.
    it('names nothing but the solver, now that the exports left the page', () => {
      const page = createPage();

      expect(page.raisonVerrou()).toBe('');
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
        jobView({ id: 'older', finishedAt: '2026-07-01T12:00:00Z' }),
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
