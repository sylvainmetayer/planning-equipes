// What this screen now decides, beyond showing a catalogue: the weight given
// to each rule for this edition, and the confirmation standing between an
// administrator and a plan contrary to the Code du travail.
//
// The component is created but never rendered — the project favours logic
// tests — so what is pinned here is the sequence of calls: no PUT when the
// confirmation is refused, one PUT with the weight when the field changes, and
// the optimistic value rolled back when the server refuses.

import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSlideToggle, MatSlideToggleChange } from '@angular/material/slide-toggle';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ConstraintsApi } from '../../core/api/constraints-api';
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
    ...overrides,
  };
}

const REGLE_LEGALE = contrainte({
  name: 'travailDeNuitInterditPourMineur',
  niveau: 'HARD',
  categorie: 'Légal (mineurs)',
  description: 'Pas de travail de nuit pour un mineur (art. L3163-1).',
  protegee: true,
  dosable: false,
});

/**
 * The event a `mat-slide-toggle` emits, with the switch that emitted it: it has
 * already flipped itself to `checked` when the page gets the call, so its own
 * state is what a refusal has to restore.
 */
function bascule(checked: boolean): MatSlideToggleChange {
  return new MatSlideToggleChange({ checked } as unknown as MatSlideToggle, checked);
}

function view(contraintes: ConstraintView[]): ConstraintsView {
  return {
    analysedAt: null,
    scoreGlobal: null,
    postesNonPourvus: null,
    faisabilite: null,
    hardScore: null,
    contraintesAdHocEnCause: [],
    contraintes,
  };
}

/** Reaches the protected members the template binds to. */
type PageInternals = {
  view: { (): ConstraintsView | null; set: (value: ConstraintsView) => void };
  error: () => string;
  toggleConstraint: (constraint: ConstraintView, event: MatSlideToggleChange) => Promise<void>;
  setPoids: (constraint: ConstraintView, poids: number) => Promise<void>;
  onPoidsChange: (constraint: ConstraintView, field: HTMLInputElement) => Promise<void>;
  pauseSurPoste: { (): boolean; set: (value: boolean) => void };
  pauseEntreVacationsMinutes: { (): number | null; set: (value: number | null) => void };
  reposQuotidienHeures: { (): number | null; set: (value: number | null) => void };
  saveParametresLegaux: () => Promise<void>;
};

describe('ConstraintsPage', () => {
  const constraintsApi = {
    catalogue: vi.fn(),
    diagnose: vi.fn(),
    legalParameters: vi.fn(),
    saveLegalParameters: vi.fn(),
    setActive: vi.fn(),
    setWeight: vi.fn(),
  };
  const legalDisable = { allowsDisabling: vi.fn() };

  beforeEach(() => {
    for (const stub of Object.values(constraintsApi)) {
      stub.mockReset();
    }
    legalDisable.allowsDisabling.mockReset();
    constraintsApi.catalogue.mockResolvedValue(view([]));
    constraintsApi.legalParameters.mockResolvedValue({
      dureeHebdomadaireMaxMinutes: 48 * 60,
      dureeHebdomadaireMaxMineurMinutes: 35 * 60,
      pauseMinimaleEntreVacationsMinutes: 30,
      reposQuotidienMinimalMinutes: 660,
      pauseSurPoste: false,
    });
    constraintsApi.saveLegalParameters.mockImplementation(async (body: unknown) => body);
    constraintsApi.setActive.mockImplementation(async (_name: string, actif: boolean) => ({
      actif,
    }));
    constraintsApi.setWeight.mockImplementation(async (_name: string, poids: number) => ({
      poids,
    }));
    legalDisable.allowsDisabling.mockResolvedValue(true);
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ConstraintsApi, useValue: constraintsApi },
        { provide: MatDialog, useValue: { open: vi.fn() } },
        { provide: LegalDisableConfirmService, useValue: legalDisable },
        {
          provide: SolverJobService,
          useValue: {
            solverBusy: () => false,
            editingLocked: () => false,
            onResult: () => () => undefined,
          },
        },
        { provide: SolverSettingsService, useValue: { secondsLimit: () => 60 } },
        {
          provide: ProblemesStore,
          useValue: { shareConstraints: vi.fn(), alerteReglesLegales: () => '' },
        },
      ],
    });
  });

  /**
   * The page loads the catalogue in its constructor, so the fixture has to be
   * the answer to that very call — setting the signal afterwards would be
   * overwritten by the load landing a microtask later.
   */
  async function createPage(contraintes: ConstraintView[]): Promise<PageInternals> {
    constraintsApi.catalogue.mockResolvedValue(view(contraintes));
    const page = TestBed.createComponent(ConstraintsPage)
      .componentInstance as unknown as PageInternals;
    await vi.waitFor(() => expect(page.view()?.contraintes).toHaveLength(contraintes.length));
    return page;
  }

  describe('legal parameters form', () => {
    it('loads every field and sends them all back, so a save never resets one it did not show', async () => {
      constraintsApi.legalParameters.mockResolvedValue({
        dureeHebdomadaireMaxMinutes: 48 * 60,
        dureeHebdomadaireMaxMineurMinutes: 35 * 60,
        pauseMinimaleEntreVacationsMinutes: 0,
        reposQuotidienMinimalMinutes: 9 * 60,
        pauseSurPoste: true,
      });
      const page = TestBed.createComponent(ConstraintsPage)
        .componentInstance as unknown as PageInternals;
      await vi.waitFor(() => expect(page.pauseSurPoste()).toBe(true));
      expect(page.pauseEntreVacationsMinutes()).toBe(0);
      expect(page.reposQuotidienHeures()).toBe(9);

      page.pauseSurPoste.set(false);
      await page.saveParametresLegaux();

      expect(constraintsApi.saveLegalParameters).toHaveBeenCalledWith({
        dureeHebdomadaireMaxMinutes: 48 * 60,
        dureeHebdomadaireMaxMineurMinutes: 35 * 60,
        pauseMinimaleEntreVacationsMinutes: 0,
        reposQuotidienMinimalMinutes: 9 * 60,
        pauseSurPoste: false,
      });
    });

    it('refuses to save while a field it holds is still unknown', async () => {
      constraintsApi.legalParameters.mockReturnValue(new Promise(() => undefined));
      const page = TestBed.createComponent(ConstraintsPage)
        .componentInstance as unknown as PageInternals;
      await vi.waitFor(() => expect(page.view()).not.toBeNull());

      await page.saveParametresLegaux();

      expect(constraintsApi.saveLegalParameters).not.toHaveBeenCalled();
    });
  });

  describe('switching a rule off', () => {
    it('asks nothing for an ordinary rule and saves it', async () => {
      const page = await createPage([contrainte()]);

      await page.toggleConstraint(contrainte(), bascule(false));

      expect(constraintsApi.setActive).toHaveBeenCalledWith('equilibrerCharge', false);
      expect(page.view()?.contraintes[0].actif).toBe(false);
    });

    // The dialog is what stands between an administrator and a plan scoring
    // zero hard that still breaks the Code du travail. Refusing it must leave
    // the rule exactly as it was — including on screen, where the Material
    // switch has already flipped itself.
    it('saves nothing and keeps the rule active when the confirmation is refused', async () => {
      legalDisable.allowsDisabling.mockResolvedValue(false);
      const page = await createPage([REGLE_LEGALE]);

      const evenement = bascule(false);
      await page.toggleConstraint(REGLE_LEGALE, evenement);

      expect(legalDisable.allowsDisabling).toHaveBeenCalledOnce();
      expect(constraintsApi.setActive).not.toHaveBeenCalled();
      expect(page.view()?.contraintes[0].actif).toBe(true);
      // The switch itself, not only the model: `[checked]` never changed value,
      // so nothing puts it back except the page.
      expect(evenement.source.checked).toBe(true);
    });

    it('saves the disabling once the confirmation is given', async () => {
      const page = await createPage([REGLE_LEGALE]);

      const evenement = bascule(false);
      await page.toggleConstraint(REGLE_LEGALE, evenement);

      expect(constraintsApi.setActive).toHaveBeenCalledWith(
        'travailDeNuitInterditPourMineur',
        false,
      );
      expect(page.view()?.contraintes[0].actif).toBe(false);
      expect(evenement.source.checked).toBe(false);
    });

    // Same lie, other cause: a refused save leaves the rule active, so the
    // switch has to come back too.
    it('puts the switch back when the save fails', async () => {
      constraintsApi.setActive.mockRejectedValue(new Error('refusé'));
      const page = await createPage([contrainte()]);
      const evenement = bascule(false);

      await page.toggleConstraint(contrainte(), evenement);

      expect(page.view()?.contraintes[0].actif).toBe(true);
      expect(evenement.source.checked).toBe(true);
      expect(page.error()).toContain('refusé');
    });

    // The « legal rules disabled » banner of this very screen reads the store,
    // not the page: a toggle that stayed in the page left the banner empty
    // after disabling a protected rule, and full after putting it back.
    it('hands every toggle to the shared store, so the banner follows the switch', async () => {
      const problemes = TestBed.inject(ProblemesStore);
      const page = await createPage([REGLE_LEGALE]);

      await page.toggleConstraint(REGLE_LEGALE, bascule(false));

      expect(problemes.shareConstraints).toHaveBeenLastCalledWith(
        expect.objectContaining({
          contraintes: [expect.objectContaining({ name: REGLE_LEGALE.name, actif: false })],
        }),
      );
    });

    /** Putting a legal rule back needs no ceremony. */
    it('never asks when a rule is switched back on', async () => {
      const page = await createPage([{ ...REGLE_LEGALE, actif: false }]);

      await page.toggleConstraint(REGLE_LEGALE, bascule(true));

      expect(legalDisable.allowsDisabling).not.toHaveBeenCalled();
      expect(page.view()?.contraintes[0].actif).toBe(true);
    });
  });

  describe('the weight of a rule', () => {
    it('saves the new weight and keeps what the server answered', async () => {
      constraintsApi.setWeight.mockResolvedValue({ poids: 7 });
      const page = await createPage([contrainte()]);

      await page.setPoids(contrainte(), 7);

      expect(constraintsApi.setWeight).toHaveBeenCalledWith('equilibrerCharge', 7);
      expect(page.view()?.contraintes[0].poids).toBe(7);
    });

    it('saves nothing when the value did not move', async () => {
      const page = await createPage([contrainte({ poids: 3 })]);

      await page.setPoids(contrainte({ poids: 3 }), 3);

      expect(constraintsApi.setWeight).not.toHaveBeenCalled();
    });

    // Zero is refused server-side on purpose (switching a rule off goes
    // through the toggle, and for a legal rule through the confirmation), so
    // the field must never send it.
    it('never sends a weight below one or above the server ceiling', async () => {
      const page = await createPage([contrainte({ poids: 4 })]);

      await page.setPoids(contrainte({ poids: 4 }), 0);
      expect(constraintsApi.setWeight).toHaveBeenCalledWith('equilibrerCharge', 1);

      constraintsApi.setWeight.mockClear();
      await page.setPoids(contrainte({ poids: 4 }), 250);
      expect(constraintsApi.setWeight).toHaveBeenCalledWith('equilibrerCharge', 100);
    });

    it('rolls the optimistic value back and reports the error when the save fails', async () => {
      constraintsApi.setWeight.mockRejectedValue(new Error('refusé'));
      const page = await createPage([contrainte({ poids: 2 })]);

      await page.setPoids(contrainte({ poids: 2 }), 9);

      expect(page.view()?.contraintes[0].poids).toBe(2);
      expect(page.error()).toContain('refusé');
    });
  });

  // One control for the thirty-nine rules, protected ones included: a number
  // field. What it must never do is leave the user looking at a figure that
  // was not saved, so what is typed is normalised back into the element.
  describe('the weight field', () => {
    function champ(value: string): HTMLInputElement {
      const field = document.createElement('input');
      field.type = 'number';
      field.value = value;
      return field;
    }

    it('saves what was typed once brought back into the accepted range', async () => {
      const page = await createPage([contrainte({ poids: 4 })]);
      const field = champ('12');

      await page.onPoidsChange(contrainte({ poids: 4 }), field);

      expect(constraintsApi.setWeight).toHaveBeenCalledWith('equilibrerCharge', 12);
      expect(field.value).toBe('12');
    });

    it('clamps an out-of-range figure and shows the value actually sent', async () => {
      const page = await createPage([contrainte({ poids: 4 })]);
      const field = champ('0');

      await page.onPoidsChange(contrainte({ poids: 4 }), field);

      expect(constraintsApi.setWeight).toHaveBeenCalledWith('equilibrerCharge', 1);
      expect(field.value).toBe('1');
    });

    // Emptying the field is not asking for a weight of zero, which the server
    // refuses: it is asking for nothing, so nothing is saved.
    it('saves nothing and restores the stored weight when the field is emptied', async () => {
      const page = await createPage([contrainte({ poids: 4 })]);
      const field = champ('');

      await page.onPoidsChange(contrainte({ poids: 4 }), field);

      expect(constraintsApi.setWeight).not.toHaveBeenCalled();
      expect(field.value).toBe('4');
    });
  });
});
