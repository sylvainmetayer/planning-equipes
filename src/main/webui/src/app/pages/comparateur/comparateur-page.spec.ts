// Loading / error / empty / data states of the A/B comparator, plus the picker
// defaults, the comparison guard and the cell labels. The component is created
// but never rendered, so this stays a logic test (the project favours those
// over full DOM rendering). The metric rows themselves are the business of
// `comparateur-metrics.spec.ts`.
//
// These four states are exactly what a migration to `httpResource()` would
// re-implement, which is why they are pinned here first.

import { provideZonelessChangeDetection, Signal, WritableSignal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { ComparaisonSnapshots, CoteComparaison, PlanningKpi, PlanSnapshot } from '../../core/models';
import { LigneMetrique } from './comparateur-metrics';
import { ComparateurPage } from './comparateur-page';

/** A promise whose settlement the test drives, to observe the in-flight state. */
function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

function kpi(overrides: Partial<PlanningKpi> = {}): PlanningKpi {
  return {
    score: '0hard/-12soft',
    scoreHard: 0,
    scoreMedium: 0,
    scoreSoft: -12,
    postesTotal: 200,
    postesPourvus: 190,
    animateursAffectes: 120,
    standsDistincts: 40,
    creneauxDistincts: 60,
    heuresTotal: 1200,
    heuresMoyenne: 10,
    heuresEcartType: 2.25,
    heuresMin: 4,
    heuresMax: 18,
    heuresIncompletes: false,
    modificationsManuelles: 7,
    tauxModificationsManuelles: 0.035,
    dureeSolveSecondes: 600,
    violationsParContrainte: {},
    ...overrides
  };
}

function snapshot(overrides: Partial<PlanSnapshot> = {}): PlanSnapshot {
  return {
    id: 7,
    libelle: 'Avant canicule',
    automatique: false,
    score: '0hard',
    nombreAffectations: 190,
    creeLe: '2026-08-01T10:00:00Z',
    editionId: 'festival-2026',
    editionNom: 'Festival 2026',
    kpi: kpi(),
    ...overrides
  };
}

function cote(overrides: Partial<CoteComparaison> = {}): CoteComparaison {
  return {
    snapshotId: 7,
    libelle: 'Avant canicule',
    editionId: 'festival-2026',
    editionNom: 'Festival 2026',
    creeLe: '2026-08-01T10:00:00Z',
    kpi: kpi(),
    kpiRecalcule: false,
    ...overrides
  };
}

function comparaison(overrides: Partial<ComparaisonSnapshots> = {}): ComparaisonSnapshots {
  return {
    base: cote(),
    variante: cote({ snapshotId: 8, libelle: 'Après canicule' }),
    editionsDifferentes: false,
    volumetriesDifferentes: false,
    diffViolations: [],
    ...overrides
  };
}

/** Reaches the protected members the template binds to. */
type PageInternals = {
  instantanes: Signal<PlanSnapshot[]>;
  baseId: WritableSignal<string>;
  varianteId: WritableSignal<string>;
  comparaison: Signal<ComparaisonSnapshots | null>;
  chargement: Signal<boolean>;
  error: Signal<string>;
  courant: string;
  lignes: Signal<LigneMetrique[]>;
  pretAComparer: Signal<boolean>;
  kpiRecalcule: Signal<boolean>;
  rafraichir: () => Promise<void>;
  comparer: () => Promise<void>;
  valeurSelection: (snapshot: PlanSnapshot) => string;
  libelle: (snapshot: PlanSnapshot) => string;
  libelleCote: (cote: CoteComparaison) => string;
  deltaLabel: (ligne: LigneMetrique) => string;
  violationsLabel: (valeur: number | null) => string;
};

function ligne(overrides: Partial<LigneMetrique> = {}): LigneMetrique {
  return { cle: 'scoreSoft', label: 'Score soft', base: '-12', variante: '-8', delta: 4, tendance: null, ...overrides };
}

describe('ComparateurPage', () => {
  const api = { get: vi.fn() };

  beforeEach(() => {
    api.get.mockReset();
    api.get.mockResolvedValue([]);
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), { provide: ApiService, useValue: api }]
    });
  });

  function createPage(): PageInternals {
    return TestBed.createComponent(ComparateurPage).componentInstance as unknown as PageInternals;
  }

  describe('loading state', () => {
    it('is loading while the pickers are in flight and idle once they land', async () => {
      const pending = deferred<PlanSnapshot[]>();
      api.get.mockReturnValue(pending.promise);

      const page = createPage();
      expect(page.chargement()).toBe(true);

      pending.resolve([snapshot()]);
      await vi.waitFor(() => expect(page.chargement()).toBe(false));
      expect(page.instantanes()).toHaveLength(1);
    });

    // Since #172 a variant of an edition is another edition, so the pair worth
    // comparing usually straddles two of them.
    it('reads the snapshots of every edition, not just the current one', async () => {
      const page = createPage();
      await vi.waitFor(() => expect(page.chargement()).toBe(false));

      expect(api.get).toHaveBeenCalledExactlyOnceWith('/api/planning/snapshots/comparables');
    });

    it('reloads the pickers on demand, since a solve elsewhere adds snapshots', async () => {
      const page = createPage();
      await vi.waitFor(() => expect(page.chargement()).toBe(false));
      api.get.mockResolvedValue([snapshot({ id: 9 })]);

      await page.rafraichir();

      expect(page.instantanes().map((instantane) => instantane.id)).toEqual([9]);
    });
  });

  describe('error state', () => {
    it('shows a failed picker load and stops loading', async () => {
      api.get.mockRejectedValue(new Error('Instantanés illisibles.'));

      const page = createPage();
      await vi.waitFor(() => expect(page.chargement()).toBe(false));

      expect(page.error()).toContain('Instantanés illisibles.');
      expect(page.instantanes()).toEqual([]);
    });

    it('shows a failed comparison and leaves no half-built table behind', async () => {
      api.get.mockResolvedValue([snapshot({ id: 8 })]);
      const page = createPage();
      await vi.waitFor(() => expect(page.varianteId()).toBe('8'));
      api.get.mockRejectedValue(new Error('Comparaison impossible.'));

      await page.comparer();

      expect(page.error()).toContain('Comparaison impossible.');
      expect(page.comparaison()).toBeNull();
      expect(page.lignes()).toEqual([]);
      expect(page.chargement()).toBe(false);
    });

    it('drops the previous comparison before running a new one', async () => {
      api.get.mockResolvedValue([snapshot({ id: 8 })]);
      const page = createPage();
      await vi.waitFor(() => expect(page.varianteId()).toBe('8'));

      api.get.mockResolvedValue(comparaison());
      await page.comparer();
      expect(page.comparaison()).not.toBeNull();

      api.get.mockRejectedValue(new Error('Comparaison impossible.'));
      await page.comparer();

      expect(page.comparaison()).toBeNull();
    });
  });

  describe('empty state', () => {
    it('leaves the variant picker unset when no snapshot exists', async () => {
      const page = createPage();
      await vi.waitFor(() => expect(page.chargement()).toBe(false));

      expect(page.varianteId()).toBe('');
      expect(page.pretAComparer()).toBe(false);
    });

    it('holds no metric row while nothing has been compared', () => {
      const page = createPage();

      expect(page.lignes()).toEqual([]);
      expect(page.kpiRecalcule()).toBe(false);
    });
  });

  describe('picker defaults', () => {
    it('starts on the currently persisted plan as the baseline', () => {
      const page = createPage();

      expect(page.baseId()).toBe('courant');
      expect(page.courant).toBe('courant');
    });

    it('preselects the first snapshot as the variant', async () => {
      api.get.mockResolvedValue([snapshot({ id: 8 }), snapshot({ id: 9 })]);

      const page = createPage();
      await vi.waitFor(() => expect(page.chargement()).toBe(false));

      expect(page.varianteId()).toBe('8');
    });

    it('does not overwrite a variant the user already picked when the list is reloaded', async () => {
      api.get.mockResolvedValue([snapshot({ id: 8 }), snapshot({ id: 9 })]);
      const page = createPage();
      await vi.waitFor(() => expect(page.varianteId()).toBe('8'));

      page.varianteId.set('9');
      await page.rafraichir();

      expect(page.varianteId()).toBe('9');
    });

    it('designates a snapshot by its id as text, like the picker value', () => {
      const page = createPage();

      expect(page.valeurSelection(snapshot({ id: 12 }))).toBe('12');
    });
  });

  describe('the comparison guard', () => {
    it('refuses to compare a side against itself', async () => {
      const page = createPage();
      await vi.waitFor(() => expect(page.chargement()).toBe(false));
      page.baseId.set('8');
      page.varianteId.set('8');

      expect(page.pretAComparer()).toBe(false);

      api.get.mockClear();
      await page.comparer();
      expect(api.get).not.toHaveBeenCalled();
    });

    it('refuses to compare while a side is unset', async () => {
      const page = createPage();
      await vi.waitFor(() => expect(page.chargement()).toBe(false));
      page.baseId.set('');
      page.varianteId.set('8');

      expect(page.pretAComparer()).toBe(false);
    });

    it('accepts two distinct sides', async () => {
      const page = createPage();
      await vi.waitFor(() => expect(page.chargement()).toBe(false));
      page.baseId.set('courant');
      page.varianteId.set('8');

      expect(page.pretAComparer()).toBe(true);
    });
  });

  describe('displayed data', () => {
    it('sends both sides as query parameters and keeps what comes back', async () => {
      api.get.mockResolvedValue([snapshot({ id: 8 })]);
      const page = createPage();
      await vi.waitFor(() => expect(page.varianteId()).toBe('8'));
      api.get.mockResolvedValue(comparaison());

      await page.comparer();

      expect(api.get).toHaveBeenLastCalledWith('/api/planning/snapshots/compare?base=courant&variante=8');
      expect(page.comparaison()).not.toBeNull();
      expect(page.lignes().length).toBeGreaterThan(0);
    });

    it('flags the degraded mode when either side had its KPI recomputed', async () => {
      api.get.mockResolvedValue([snapshot({ id: 8 })]);
      const page = createPage();
      await vi.waitFor(() => expect(page.varianteId()).toBe('8'));

      api.get.mockResolvedValue(comparaison({ variante: cote({ snapshotId: 8, kpiRecalcule: true }) }));
      await page.comparer();

      expect(page.kpiRecalcule()).toBe(true);
    });

    it('leaves the degraded mode off when both sides carry stored KPI', async () => {
      api.get.mockResolvedValue([snapshot({ id: 8 })]);
      const page = createPage();
      await vi.waitFor(() => expect(page.varianteId()).toBe('8'));

      api.get.mockResolvedValue(comparaison());
      await page.comparer();

      expect(page.kpiRecalcule()).toBe(false);
    });
  });

  describe('labels', () => {
    it('names a snapshot by its label, its edition and its date', () => {
      const page = createPage();

      const label = page.libelle(snapshot({ libelle: 'Avant canicule', editionNom: 'Festival 2026' }));

      expect(label).toContain('Avant canicule');
      expect(label).toContain('Festival 2026');
      expect(label.split(' — ')).toHaveLength(3);
    });

    it('falls back to the edition id, and drops the date part when there is none', () => {
      const page = createPage();

      const label = page.libelle(snapshot({ editionNom: null, editionId: 'edition-1708', creeLe: null }));

      expect(label).toBe('Avant canicule — edition-1708');
    });

    it('names the unlabelled side the currently persisted plan', () => {
      const page = createPage();

      expect(page.libelleCote(cote({ libelle: null }))).toContain('persisté');
      expect(page.libelleCote(cote({ libelle: 'Avant canicule' }))).toBe('Avant canicule');
    });

    it('renders an unknown delta as a dash', () => {
      const page = createPage();

      expect(page.deltaLabel(ligne({ delta: null }))).toBe('—');
    });

    it('signs a positive delta and leaves a negative one as is', () => {
      const page = createPage();

      expect(page.deltaLabel(ligne({ delta: 4 }))).toBe('+4');
      expect(page.deltaLabel(ligne({ delta: -4 }))).toBe('-4');
    });

    it('rounds a fractional delta to one decimal and keeps an integer whole', () => {
      const page = createPage();

      expect(page.deltaLabel(ligne({ delta: 1.25 }))).toBe('+1.3');
      expect(page.deltaLabel(ligne({ delta: 2 }))).toBe('+2');
    });

    it('says in words whether the variation is an improvement or a regression', () => {
      const page = createPage();

      expect(page.deltaLabel(ligne({ delta: 4, tendance: 'amelioration' }))).toContain('amélioration');
      expect(page.deltaLabel(ligne({ delta: -4, tendance: 'degradation' }))).toContain('dégradation');
    });

    it('distinguishes an unmeasured violation count from zero', () => {
      const page = createPage();

      expect(page.violationsLabel(null)).toContain('non mesuré');
      expect(page.violationsLabel(0)).toBe('0');
    });
  });
});
