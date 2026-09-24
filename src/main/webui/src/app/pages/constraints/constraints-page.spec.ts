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
import { RouterTestingHarness } from '@angular/router/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ConstraintsApi } from '../../core/api/constraints-api';
import { ConstraintView, ConstraintsView, ParametreContrainte } from '../../core/models';
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
    legale: false,
    dosable: true,
    poids: 1,
    score: null,
    matchCount: null,
    violations: [],
    postesEvalues: null,
    plancher: null,
    references: [],
    ...overrides,
  };
}

const REGLE_LEGALE = contrainte({
  name: 'travailDeNuitInterditPourMineur',
  niveau: 'HARD',
  categorie: 'Légal (mineurs)',
  description: 'Pas de travail de nuit pour un mineur (art. L3163-1).',
  protegee: true,
  legale: true,
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
    scoreHorsPlancher: null,
    plancherMedium: null,
    plancherSoft: null,
    pivotEcarts: [],
    lecture: [],
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
  horsPlancherLabel: () => string;
  plancherRatioLabel: (constraint: ConstraintView) => string;
  triParScore: () => boolean;
  sortByFloor: () => boolean;
  basculerTri: () => void;
  basculerTriPlancher: () => void;
  groups: () => { categorie: string; items: ConstraintView[]; ancre: string }[];
  scrollToAnchor: (id: string) => void;
  ancreLabel: (constraint: ConstraintView) => string;
  parametreLabel: (parametre: ParametreContrainte) => string;
};

/** A rule that penalised every seat for lack of data (issue #495). */
const AT_FLOOR = contrainte({
  name: 'souhaitsIncompatibles',
  score: '0hard/-12medium/0soft',
  matchCount: 12,
  postesEvalues: 12,
  plancher: {
    ratio: 1,
    motif: 'SOUHAITS',
    libelle: 'Aucun souhait déclaré sur les fiches animateur.',
    lien: '/animateurs',
  },
});

describe('ConstraintsPage', () => {
  const constraintsApi = {
    catalogue: vi.fn(),
    diagnose: vi.fn(),
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
        // A real route, not `provideRouter([])`: the deep-link test below
        // renders the page at `/constraints#uneRegle` through the router.
        provideRouter([{ path: 'constraints', component: ConstraintsPage }]),
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

  // The floor (issue #495) is a reading, not a decision: the page says what
  // it costs and puts the floored rules first, and switches nothing off.
  describe('a rule that measures missing data', () => {
    it("says what share of its items the rule matched, at the rule's own grain", async () => {
      const page = await createPage([AT_FLOOR]);

      expect(page.plancherRatioLabel(AT_FLOOR)).toBe(
        '100 % des 12 éléments évalués sont en écart.',
      );
      expect(page.plancherRatioLabel(contrainte())).toBe('');
    });

    it('shows the score net of the floor only when it differs from the raw one', async () => {
      const page = await createPage([AT_FLOOR]);
      page.view.set({
        ...page.view()!,
        analysedAt: '2026-07-01T10:00:00Z',
        scoreGlobal: '0hard/-6675medium/-120soft',
        scoreHorsPlancher: '0hard/-1675medium/-120soft',
      });
      expect(page.horsPlancherLabel()).toBe(
        'Hors plancher : 0hard/-1675medium/-120soft — 1 règle(s) mesurent une donnée absente.',
      );

      page.view.set({
        ...page.view()!,
        scoreHorsPlancher: '0hard/-6675medium/-120soft',
      });
      expect(page.horsPlancherLabel()).toBe('');
    });

    it('puts the floored rules first when sorting by floor, and the two sorts exclude each other', async () => {
      const page = await createPage([
        contrainte({ name: 'equilibrerCharge', matchCount: 40 }),
        AT_FLOOR,
      ]);
      expect(page.groups()[0].items.map((c) => c.name)).toEqual([
        'equilibrerCharge',
        'souhaitsIncompatibles',
      ]);

      page.basculerTriPlancher();
      expect(page.groups()[0].items.map((c) => c.name)).toEqual([
        'souhaitsIncompatibles',
        'equilibrerCharge',
      ]);

      page.basculerTri();
      expect(page.triParScore()).toBe(true);
      expect(page.sortByFloor()).toBe(false);
      expect(page.groups()[0].items.map((c) => c.name)).toEqual([
        'equilibrerCharge',
        'souhaitsIncompatibles',
      ]);
    });

    // Reported, never decided: loading a floored rule changes nothing about it,
    // and the controls stay the ordinary ones — a badge is not a lock. The
    // « nothing was called » half alone would pass on a page that renders no
    // control at all, so the toggle is exercised too.
    it('leaves the rule active and still operable: a floor is reported, never decided', async () => {
      const page = await createPage([AT_FLOOR]);

      expect(page.view()?.contraintes[0].plancher).not.toBeNull();
      expect(page.view()?.contraintes[0].actif).toBe(true);
      expect(constraintsApi.setActive).not.toHaveBeenCalled();
      expect(constraintsApi.setWeight).not.toHaveBeenCalled();

      await page.toggleConstraint(AT_FLOOR, bascule(false));
      expect(constraintsApi.setActive).toHaveBeenCalledWith(AT_FLOOR.name, false);
      expect(page.view()?.contraintes[0].actif).toBe(false);

      const poids = document.createElement('input');
      poids.type = 'number';
      poids.value = '3';
      await page.onPoidsChange(AT_FLOOR, poids);
      expect(constraintsApi.setWeight).toHaveBeenCalledWith(AT_FLOOR.name, 3);
    });
  });

  // A catalogue of fifty rules read as one column per category is navigable
  // only if one can jump into it and link to a single rule — the reason the
  // cards carry an id at all.
  describe('the anchors of the catalogue', () => {
    it("gives every category an anchor that cannot collide with a rule's name", async () => {
      const page = await createPage([contrainte(), REGLE_LEGALE]);

      expect(page.groups().map((group) => group.ancre)).toEqual([
        'categorie-qualite-d-organisation',
        'categorie-legal-mineurs',
      ]);
    });

    it('names the rule in the label of its own anchor link', async () => {
      const page = await createPage([contrainte()]);

      expect(page.ancreLabel(contrainte())).toBe('Lien direct vers la règle equilibrerCharge');
    });

    // The card of a rule really is the element the anchor names, and going to
    // it moves the caret as well as the eye. jsdom implements neither
    // `scrollIntoView` nor smooth scrolling, so the call is what is pinned.
    it('goes to a rule and leaves the caret on it', async () => {
      const scrollIntoView = vi.fn();
      Element.prototype.scrollIntoView = scrollIntoView;
      const page = await createPage([contrainte()]);
      await vi.waitFor(() => expect(document.getElementById('equilibrerCharge')).not.toBeNull());
      const carte = document.getElementById('equilibrerCharge');

      page.scrollToAnchor('equilibrerCharge');

      expect(scrollIntoView).toHaveBeenCalled();
      expect(scrollIntoView.mock.instances[0]).toBe(carte);
      expect(document.activeElement).toBe(carte);
    });

    /** A stale link to a renamed rule must not throw, just do nothing. */
    it('ignores a fragment naming no rule', async () => {
      const scrollIntoView = vi.fn();
      Element.prototype.scrollIntoView = scrollIntoView;
      const page = await createPage([contrainte()]);

      page.scrollToAnchor('regleQuiNexistePlus');

      expect(scrollIntoView).not.toHaveBeenCalled();
    });

    /**
     * The deep link, end to end: nothing exists on this page before the answer
     * to `GET /api/constraints` lands, so reading the fragment at construction
     * time and scrolling there and then would look for an id the DOM does not
     * hold yet — which is exactly what this asserts against.
     */
    it('scrolls to the rule named by the URL fragment on a direct load', async () => {
      const scrollIntoView = vi.fn();
      Element.prototype.scrollIntoView = scrollIntoView;
      constraintsApi.catalogue.mockResolvedValue(view([contrainte(), REGLE_LEGALE]));

      const harness = await RouterTestingHarness.create(
        '/constraints#travailDeNuitInterditPourMineur',
      );
      await vi.waitFor(() => {
        harness.detectChanges();
        expect(document.getElementById('travailDeNuitInterditPourMineur')).not.toBeNull();
      });

      await vi.waitFor(() => expect(scrollIntoView).toHaveBeenCalled());
      expect(scrollIntoView.mock.instances[0]).toBe(
        document.getElementById('travailDeNuitInterditPourMineur'),
      );
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
  // A rule's threshold lives on a form the screen never showed, so the reader
  // had to guess which field the description meant (issue #57).
  describe('réglages lus par une règle', () => {
    const CEILING: ParametreContrainte = {
      libelle: "Jours travaillés d'affilée",
      valeur: '8 jours',
      lien: '/parametres',
      onglet: 'edition',
    };

    it('names the field and its value so the link is readable out of context', async () => {
      const page = await createPage([contrainte({ parametres: [CEILING] })]);

      expect(page.parametreLabel(CEILING)).toBe(
        "Régler « Jours travaillés d'affilée », actuellement 8 jours",
      );
    });

    it('carries the parameters through to the view', async () => {
      const page = await createPage([contrainte({ parametres: [CEILING] })]);

      expect(page.view()?.contraintes[0].parametres).toEqual([CEILING]);
    });

    // Most rules read none, and an older payload carries no field at all:
    // neither may break the screen.
    it('accepts a rule with no parameter and an absent field alike', async () => {
      const page = await createPage([
        contrainte({ parametres: [] }),
        contrainte({ name: 'posteDoitEtrePourvu' }),
      ]);

      expect(page.view()?.contraintes[0].parametres).toEqual([]);
      expect(page.view()?.contraintes[1].parametres).toBeUndefined();
    });
  });
  // RGAA 7.1 / 7.3: the pivot's cells open the detail this table exists to
  // give, so the keyboard reaches them — one tab stop, arrows inside, Enter.
  describe('the pivot as a keyboard grid', () => {
    async function renderPivot(): Promise<HTMLElement> {
      constraintsApi.catalogue.mockResolvedValue({
        ...view([contrainte({ name: 'repos' }), contrainte({ name: 'pause' })]),
        pivotEcarts: [
          { contrainte: 'repos', axe: 'JOUR', cle: '2026-07-01', ecarts: 3 },
          { contrainte: 'repos', axe: 'JOUR', cle: '2026-07-02', ecarts: 1 },
          { contrainte: 'pause', axe: 'JOUR', cle: '2026-07-02', ecarts: 2 },
        ],
      });
      const fixture = TestBed.createComponent(ConstraintsPage);
      const root = fixture.nativeElement as HTMLElement;
      document.body.appendChild(root);
      await vi.waitFor(() => {
        fixture.detectChanges();
        expect(root.querySelectorAll('td[data-ligne]').length).toBe(4);
      });
      return root;
    }

    it('holds exactly one tab stop, on a cell named by its rule and its column', async () => {
      const root = await renderPivot();

      const stops = root.querySelectorAll('td[data-ligne][tabindex="0"]');
      expect(stops).toHaveLength(1);
      expect(stops[0].getAttribute('aria-label')).toMatch(/écart/);
      expect(stops[0].getAttribute('aria-label')).toContain('2026-07-01');
      root.remove();
    });

    it('moves with the arrows and opens the cell on Enter', async () => {
      const root = await renderPivot();
      const first = root.querySelector<HTMLElement>('td[data-ligne="0"][data-colonne="0"]')!;
      first.focus();

      first.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
      const second = root.querySelector<HTMLElement>('td[data-ligne="0"][data-colonne="1"]')!;
      expect(document.activeElement).toBe(second);

      second.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
      await vi.waitFor(() => expect(root.querySelector('.constraint-pivot-detail')).not.toBeNull());
      root.remove();
    });
  });
});
