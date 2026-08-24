// What this screen now decides, beyond showing a catalogue: the weight given
// to each rule for this edition, and the confirmation standing between an
// administrator and a plan contrary to the Code du travail.
//
// The component is created but never rendered — the project favours logic
// tests — so what is pinned here is the sequence of calls: no PUT when the
// confirmation is refused, one PUT with the weight when the dial moves, and
// the optimistic value rolled back when the server refuses.

import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { ConstraintView, ConstraintsView } from '../../core/models';
import { ProblemesStore } from '../../core/problemes.store';
import { SolverJobService } from '../../core/solver-job.service';
import { SolverSettingsService } from '../../core/solver-settings.service';
import { ConstraintsPage } from './constraints-page';
import { LegalDisableConfirmService } from './legal-disable-dialog';

function contrainte(overrides: Partial<ConstraintView> = {}): ConstraintView {
  return {
    name: 'equilibrerCharge',
    niveau: 'MEDIUM',
    categorie: "Qualité d'organisation",
    description: 'La charge de travail doit être répartie équitablement.',
    actif: true,
    protegee: false,
    dosable: true,
    poids: 1,
    score: null,
    matchCount: null,
    violations: [],
    ...overrides
  };
}

const REGLE_LEGALE = contrainte({
  name: 'travailDeNuitInterditPourMineur',
  niveau: 'HARD',
  categorie: 'Légal (mineurs)',
  description: 'Pas de travail de nuit pour un mineur (art. L3163-1).',
  protegee: true,
  dosable: false
});

function view(contraintes: ConstraintView[]): ConstraintsView {
  return {
    analysedAt: null,
    scoreGlobal: null,
    postesNonPourvus: null,
    faisabilite: null,
    hardScore: null,
    contraintes
  };
}

/** Reaches the protected members the template binds to. */
type PageInternals = {
  view: { (): ConstraintsView | null; set: (value: ConstraintsView) => void };
  error: () => string;
  toggleConstraint: (constraint: ConstraintView, actif: boolean) => Promise<void>;
  setPoids: (constraint: ConstraintView, poids: number) => Promise<void>;
  dosageMax: (constraint: ConstraintView) => number;
};

describe('ConstraintsPage', () => {
  const api = { get: vi.fn(), put: vi.fn() };
  const legalDisable = { allowsDisabling: vi.fn() };

  beforeEach(() => {
    api.get.mockReset();
    api.put.mockReset();
    legalDisable.allowsDisabling.mockReset();
    api.get.mockImplementation(async (url: string) =>
      url === '/api/constraints'
        ? view([])
        : { dureeHebdomadaireMaxMinutes: 48 * 60, dureeHebdomadaireMaxMineurMinutes: 35 * 60 }
    );
    api.put.mockImplementation(async (_url: string, body: unknown) => body);
    legalDisable.allowsDisabling.mockResolvedValue(true);
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ApiService, useValue: api },
        { provide: MatDialog, useValue: { open: vi.fn() } },
        { provide: LegalDisableConfirmService, useValue: legalDisable },
        {
          provide: SolverJobService,
          useValue: { solverBusy: () => false, editingLocked: () => false, onResult: () => () => undefined }
        },
        { provide: SolverSettingsService, useValue: { secondsLimit: () => 60 } },
        { provide: ProblemesStore, useValue: { constraints: { set: vi.fn() }, alerteReglesLegales: () => '' } }
      ]
    });
  });

  /**
   * The page loads the catalogue in its constructor, so the fixture has to be
   * the answer to that very call — setting the signal afterwards would be
   * overwritten by the load landing a microtask later.
   */
  async function createPage(contraintes: ConstraintView[]): Promise<PageInternals> {
    api.get.mockImplementation(async (url: string) =>
      url === '/api/constraints'
        ? view(contraintes)
        : { dureeHebdomadaireMaxMinutes: 48 * 60, dureeHebdomadaireMaxMineurMinutes: 35 * 60 }
    );
    const page = TestBed.createComponent(ConstraintsPage).componentInstance as unknown as PageInternals;
    await vi.waitFor(() => expect(page.view()?.contraintes).toHaveLength(contraintes.length));
    return page;
  }

  describe('switching a rule off', () => {
    it('asks nothing for an ordinary rule and saves it', async () => {
      const page = await createPage([contrainte()]);

      await page.toggleConstraint(contrainte(), false);

      expect(api.put).toHaveBeenCalledWith('/api/constraints/equilibrerCharge', { actif: false });
      expect(page.view()?.contraintes[0].actif).toBe(false);
    });

    // The dialog is what stands between an administrator and a plan scoring
    // zero hard that still breaks the Code du travail. Refusing it must leave
    // the rule exactly as it was — including on screen, where the Material
    // switch has already flipped itself.
    it('saves nothing and keeps the rule active when the confirmation is refused', async () => {
      legalDisable.allowsDisabling.mockResolvedValue(false);
      const page = await createPage([REGLE_LEGALE]);

      await page.toggleConstraint(REGLE_LEGALE, false);

      expect(legalDisable.allowsDisabling).toHaveBeenCalledOnce();
      expect(api.put).not.toHaveBeenCalled();
      expect(page.view()?.contraintes[0].actif).toBe(true);
    });

    it('saves the disabling once the confirmation is given', async () => {
      const page = await createPage([REGLE_LEGALE]);

      await page.toggleConstraint(REGLE_LEGALE, false);

      expect(api.put).toHaveBeenCalledWith('/api/constraints/travailDeNuitInterditPourMineur', { actif: false });
      expect(page.view()?.contraintes[0].actif).toBe(false);
    });

    /** Putting a legal rule back needs no ceremony. */
    it('never asks when a rule is switched back on', async () => {
      const page = await createPage([{ ...REGLE_LEGALE, actif: false }]);

      await page.toggleConstraint(REGLE_LEGALE, true);

      expect(legalDisable.allowsDisabling).not.toHaveBeenCalled();
      expect(page.view()?.contraintes[0].actif).toBe(true);
    });
  });

  describe('the weight of a rule', () => {
    it('saves the new weight and keeps what the server answered', async () => {
      api.put.mockResolvedValue({ poids: 7 });
      const page = await createPage([contrainte()]);

      await page.setPoids(contrainte(), 7);

      expect(api.put).toHaveBeenCalledWith('/api/constraints/equilibrerCharge/poids', { poids: 7 });
      expect(page.view()?.contraintes[0].poids).toBe(7);
    });

    it('saves nothing when the value did not move', async () => {
      const page = await createPage([contrainte({ poids: 3 })]);

      await page.setPoids(contrainte({ poids: 3 }), 3);

      expect(api.put).not.toHaveBeenCalled();
    });

    // Zero is refused server-side on purpose (switching a rule off goes
    // through the toggle, and for a legal rule through the confirmation), so
    // the dial must never send it.
    it('never sends a weight below one or above the server ceiling', async () => {
      const page = await createPage([contrainte({ poids: 4 })]);

      await page.setPoids(contrainte({ poids: 4 }), 0);
      expect(api.put).toHaveBeenCalledWith('/api/constraints/equilibrerCharge/poids', { poids: 1 });

      api.put.mockClear();
      await page.setPoids(contrainte({ poids: 4 }), 250);
      expect(api.put).toHaveBeenCalledWith('/api/constraints/equilibrerCharge/poids', { poids: 100 });
    });

    it('rolls the optimistic value back and reports the error when the save fails', async () => {
      api.put.mockRejectedValue(new Error('refusé'));
      const page = await createPage([contrainte({ poids: 2 })]);

      await page.setPoids(contrainte({ poids: 2 }), 9);

      expect(page.view()?.contraintes[0].poids).toBe(2);
      expect(page.error()).toContain('refusé');
    });

    /** The dial stops at ten, unless a scenario pinned something higher. */
    it('opens the dial up to the current value when it exceeds the usual range', async () => {
      const page = await createPage([contrainte()]);

      expect(page.dosageMax(contrainte({ poids: 3 }))).toBe(10);
      expect(page.dosageMax(contrainte({ poids: 42 }))).toBe(42);
    });
  });
});
