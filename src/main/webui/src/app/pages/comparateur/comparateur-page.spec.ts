// Loading / error / empty / data states of the A/B comparator, plus the picker
// defaults, the comparison guard and the cell labels, without rendering. The
// rendering half at the end covers the three caveats — different editions,
// different volumetries, recomputed KPI — that qualify every figure of the
// table: read without them, the comparison says something it does not mean.
// The metric rows themselves are the business of `comparateur-metrics.spec.ts`.
//
// These four states are exactly what a migration to `httpResource()` would
// re-implement, which is why they are pinned here first.

import { provideZonelessChangeDetection, Signal, WritableSignal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { PlanningApi } from '../../core/api/planning-api';
import {
  ComparaisonSnapshots,
  CoteComparaison,
  PlanningKpi,
  PlanSnapshot,
} from '../../core/models';
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
    scoreMediumHorsPlancher: null,
    plancherMedium: null,
    journeesSousConsigne: null,
    heuresFermeesParConsigne: null,
    ...overrides,
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
    referenceModifieLe: null,
    perime: false,
    publieLe: null,
    ...overrides,
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
    consignes: null,
    ...overrides,
  };
}

function comparaison(overrides: Partial<ComparaisonSnapshots> = {}): ComparaisonSnapshots {
  return {
    base: cote(),
    variante: cote({ snapshotId: 8, libelle: 'Après canicule' }),
    editionsDifferentes: false,
    volumetriesDifferentes: false,
    consignesDifferentes: false,
    diffViolations: [],
    ...overrides,
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
  cotesPerimes: Signal<string[]>;
  rafraichir: () => Promise<void>;
  comparer: () => Promise<void>;
  valeurSelection: (snapshot: PlanSnapshot) => string;
  libelle: (snapshot: PlanSnapshot) => string;
  libelleCote: (cote: CoteComparaison) => string;
  deltaLabel: (ligne: LigneMetrique) => string;
  violationsLabel: (valeur: number | null) => string;
};

function ligne(overrides: Partial<LigneMetrique> = {}): LigneMetrique {
  return {
    cle: 'scoreSoft',
    label: 'Score soft',
    base: '-12',
    variante: '-8',
    delta: 4,
    tendance: null,
    ...overrides,
  };
}

function createPage(): PageInternals {
  const fixture = TestBed.createComponent(ComparateurPage);
  fixture.detectChanges();
  return fixture.componentInstance as unknown as PageInternals;
}

describe('ComparateurPage', () => {
  const planningApi = { comparableSnapshots: vi.fn(), compareSnapshots: vi.fn() };

  beforeEach(() => {
    planningApi.comparableSnapshots.mockReset();
    planningApi.compareSnapshots.mockReset();
    planningApi.comparableSnapshots.mockResolvedValue([]);
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: PlanningApi, useValue: planningApi },
      ],
    });
  });

  describe('loading state', () => {
    it('is loading while the pickers are in flight and idle once they land', async () => {
      const pending = deferred<PlanSnapshot[]>();
      planningApi.comparableSnapshots.mockReturnValue(pending.promise);

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

      expect(planningApi.comparableSnapshots).toHaveBeenCalledOnce();
    });

    it('reloads the pickers on demand, since a solve elsewhere adds snapshots', async () => {
      const page = createPage();
      await vi.waitFor(() => expect(page.chargement()).toBe(false));
      planningApi.comparableSnapshots.mockResolvedValue([snapshot({ id: 9 })]);

      await page.rafraichir();

      expect(page.instantanes().map((instantane) => instantane.id)).toEqual([9]);
    });
  });

  describe('error state', () => {
    it('shows a failed picker load and stops loading', async () => {
      planningApi.comparableSnapshots.mockRejectedValue(new Error('Instantanés illisibles.'));

      const page = createPage();
      await vi.waitFor(() => expect(page.chargement()).toBe(false));

      expect(page.error()).toContain('Instantanés illisibles.');
      expect(page.instantanes()).toEqual([]);
    });

    it('shows a failed comparison and leaves no half-built table behind', async () => {
      planningApi.comparableSnapshots.mockResolvedValue([snapshot({ id: 8 })]);
      const page = createPage();
      await vi.waitFor(() => expect(page.varianteId()).toBe('8'));
      planningApi.compareSnapshots.mockRejectedValue(new Error('Comparaison impossible.'));

      await page.comparer();

      expect(page.error()).toContain('Comparaison impossible.');
      expect(page.comparaison()).toBeNull();
      expect(page.lignes()).toEqual([]);
      expect(page.chargement()).toBe(false);
    });

    it('drops the previous comparison before running a new one', async () => {
      planningApi.comparableSnapshots.mockResolvedValue([snapshot({ id: 8 })]);
      const page = createPage();
      await vi.waitFor(() => expect(page.varianteId()).toBe('8'));

      planningApi.compareSnapshots.mockResolvedValue(comparaison());
      await page.comparer();
      expect(page.comparaison()).not.toBeNull();

      planningApi.compareSnapshots.mockRejectedValue(new Error('Comparaison impossible.'));
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
      planningApi.comparableSnapshots.mockResolvedValue([snapshot({ id: 8 }), snapshot({ id: 9 })]);

      const page = createPage();
      await vi.waitFor(() => expect(page.chargement()).toBe(false));

      expect(page.varianteId()).toBe('8');
    });

    it('does not overwrite a variant the user already picked when the list is reloaded', async () => {
      planningApi.comparableSnapshots.mockResolvedValue([snapshot({ id: 8 }), snapshot({ id: 9 })]);
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

      planningApi.compareSnapshots.mockClear();
      await page.comparer();
      expect(planningApi.compareSnapshots).not.toHaveBeenCalled();
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
      planningApi.comparableSnapshots.mockResolvedValue([snapshot({ id: 8 })]);
      const page = createPage();
      await vi.waitFor(() => expect(page.varianteId()).toBe('8'));
      planningApi.compareSnapshots.mockResolvedValue(comparaison());

      await page.comparer();

      expect(planningApi.compareSnapshots).toHaveBeenLastCalledWith('courant', '8');
      expect(page.comparaison()).not.toBeNull();
      expect(page.lignes().length).toBeGreaterThan(0);
    });

    it('flags the degraded mode when either side had its KPI recomputed', async () => {
      planningApi.comparableSnapshots.mockResolvedValue([snapshot({ id: 8 })]);
      const page = createPage();
      await vi.waitFor(() => expect(page.varianteId()).toBe('8'));

      planningApi.compareSnapshots.mockResolvedValue(
        comparaison({ variante: cote({ snapshotId: 8, kpiRecalcule: true }) }),
      );
      await page.comparer();

      expect(page.kpiRecalcule()).toBe(true);
    });

    it('leaves the degraded mode off when both sides carry stored KPI', async () => {
      planningApi.comparableSnapshots.mockResolvedValue([snapshot({ id: 8 })]);
      const page = createPage();
      await vi.waitFor(() => expect(page.varianteId()).toBe('8'));

      planningApi.compareSnapshots.mockResolvedValue(comparaison());
      await page.comparer();

      expect(page.kpiRecalcule()).toBe(false);
    });
  });

  describe('stale sides', () => {
    // Read from the comparison displayed, not from the pickers: the warning
    // must describe the table on screen, which an untouched selection change
    // would otherwise contradict.
    it('names the compared sides whose referential has moved since the capture', async () => {
      planningApi.comparableSnapshots.mockResolvedValue([
        snapshot({ id: 7, libelle: 'Avant canicule', perime: true }),
        snapshot({ id: 8, libelle: 'Après canicule', perime: false }),
      ]);
      const page = createPage();
      await vi.waitFor(() => expect(page.instantanes()).toHaveLength(2));
      planningApi.compareSnapshots.mockResolvedValue(comparaison());
      page.baseId.set('7');
      page.varianteId.set('8');

      await page.comparer();

      expect(page.cotesPerimes()).toEqual(['Avant canicule']);
    });

    it('says nothing when both sides are current, nor before a comparison', async () => {
      planningApi.comparableSnapshots.mockResolvedValue([snapshot({ id: 7, perime: false })]);
      const page = createPage();
      await vi.waitFor(() => expect(page.instantanes()).toHaveLength(1));

      expect(page.cotesPerimes()).toEqual([]);

      planningApi.compareSnapshots.mockResolvedValue(comparaison());
      page.baseId.set('7');
      page.varianteId.set(page.courant);
      await page.comparer();

      expect(page.cotesPerimes()).toEqual([]);
    });
  });

  describe('labels', () => {
    it('names a snapshot by its label, its edition and its date', () => {
      const page = createPage();

      const label = page.libelle(
        snapshot({ libelle: 'Avant canicule', editionNom: 'Festival 2026' }),
      );

      expect(label).toContain('Avant canicule');
      expect(label).toContain('Festival 2026');
      expect(label.split(' — ')).toHaveLength(3);
    });

    it('falls back to the edition id, and drops the date part when there is none', () => {
      const page = createPage();

      const label = page.libelle(
        snapshot({ editionNom: null, editionId: 'edition-1708', creeLe: null }),
      );

      expect(label).toBe('Avant canicule — edition-1708');
    });

    // Issue #170: a comparison is a decision aid, so a side computed before its
    // referential moved has to say so — it changes nothing about what may be
    // compared, only about what the numbers mean.
    it('marks a stale snapshot in the picker and leaves a fresh one alone', () => {
      const page = createPage();

      expect(page.libelle(snapshot({ perime: true }))).toContain('périmé');
      expect(page.libelle(snapshot({ perime: false }))).not.toContain('périmé');
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

      expect(page.deltaLabel(ligne({ delta: 4, tendance: 'amelioration' }))).toContain(
        'amélioration',
      );
      expect(page.deltaLabel(ligne({ delta: -4, tendance: 'degradation' }))).toContain(
        'dégradation',
      );
    });

    it('distinguishes an unmeasured violation count from zero', () => {
      const page = createPage();

      expect(page.violationsLabel(null)).toContain('non mesuré');
      expect(page.violationsLabel(0)).toBe('0');
    });
  });
});

describe('ComparateurPage rendering', () => {
  let fixture: ComponentFixture<ComparateurPage>;

  async function rendre(
    instantanes: PlanSnapshot[],
    resultat: ComparaisonSnapshots | Error | null = null,
  ): Promise<void> {
    const planningApi = {
      comparableSnapshots: vi.fn(async () => instantanes),
      compareSnapshots: vi.fn(async () => {
        if (resultat instanceof Error) {
          throw resultat;
        }
        return resultat;
      }),
    };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: PlanningApi, useValue: planningApi },
      ],
    });
    fixture = TestBed.createComponent(ComparateurPage);
    await fixture.whenStable();
  }

  function racine(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function text(): string {
    return racine().textContent!.replace(/\s+/g, ' ');
  }

  function bouton(libelle: string): HTMLButtonElement {
    const trouve = Array.from(racine().querySelectorAll('button')).find((each) =>
      each.textContent!.includes(libelle),
    );
    expect(trouve, `bouton « ${libelle} » absent`).toBeDefined();
    return trouve as HTMLButtonElement;
  }

  async function comparer(): Promise<void> {
    bouton('Comparer').click();
    await fixture.whenStable();
  }

  it('tells the user how to get a first snapshot instead of showing empty pickers', async () => {
    await rendre([]);

    expect(text()).toContain('Aucun instantané enregistré');
    expect(racine().querySelector('.comparateur-selection')).toBeNull();
  });

  it('offers the persisted plan and every snapshot on both sides', async () => {
    await rendre([snapshot(), snapshot({ id: 8, libelle: 'Après canicule' })]);

    const selects = racine().querySelectorAll('mat-select');
    expect(selects).toHaveLength(2);
    // Ready out of the box on the nominal pair — the persisted plan against
    // the latest snapshot — so the screen answers something without a setup step.
    expect(bouton('Comparer').disabled).toBe(false);
  });

  it('renders one row per metric once the two plans are compared', async () => {
    await rendre([snapshot(), snapshot({ id: 8 })], comparaison());
    const page = fixture.componentInstance as unknown as PageInternals;
    page.baseId.set('7');
    page.varianteId.set('8');
    fixture.detectChanges();

    await comparer();

    const lignes = Array.from(racine().querySelectorAll('tbody tr')).map((row) =>
      Array.from(row.querySelectorAll('td')).map((cell) => cell.textContent!.trim()),
    );
    expect(lignes.length).toBeGreaterThan(0);
    expect(lignes[0][0]).not.toBe('');
    // Both column headers name their side, so a reader knows which is which.
    const entetes = Array.from(racine().querySelectorAll('thead th')).map((each) =>
      each.textContent!.trim(),
    );
    expect(entetes.some((entete) => entete.includes('Avant canicule'))).toBe(true);
    expect(entetes.some((entete) => entete.includes('Après canicule'))).toBe(true);
  });

  async function comparerAvec(overrides: Partial<ComparaisonSnapshots>): Promise<void> {
    await rendre([snapshot(), snapshot({ id: 8 })], comparaison(overrides));
    const page = fixture.componentInstance as unknown as PageInternals;
    page.baseId.set('7');
    page.varianteId.set('8');
    fixture.detectChanges();
    await comparer();
  }

  it('warns that two plans of different editions are not comparable all things equal', async () => {
    await comparerAvec({ editionsDifferentes: true });

    expect(text()).toContain('éditions différentes');
  });

  it('warns that a volumetry gap is not a quality gap', async () => {
    await comparerAvec({ volumetriesDifferentes: true });

    expect(text()).toContain("n'ont pas le même nombre de postes");
  });

  it('warns when a snapshot predates the KPI and had to be recomputed', async () => {
    await comparerAvec({ base: cote({ kpiRecalcule: true }) });

    // Its violations are simply not measured: saying so is what keeps the
    // "0 violation" of that column from being read as good news.
    expect(text()).toContain('mode dégradé');
  });

  it('stays silent about all three caveats when none applies', async () => {
    await comparerAvec({});

    expect(racine().querySelectorAll('.locked-hint')).toHaveLength(0);
  });

  it('folds the reading of each side under its own name, and says when a side has none', async () => {
    await comparerAvec({
      base: cote({
        kpi: kpi({
          lecture: [
            {
              sujet: 'VERDICT',
              niveau: 'OK',
              texte: 'Le planning respecte toutes les règles impératives.',
              liens: [],
            },
          ],
        }),
      }),
    });

    const sides = racine().querySelectorAll('details.comparateur-lecture');
    expect(sides).toHaveLength(2);
    expect(sides[0].querySelector('summary')!.textContent).toContain('Avant canicule');
    expect(sides[0].textContent).toContain('Le planning respecte toutes les règles impératives.');
    // Named once, by its summary: no second heading repeating it.
    expect(sides[0].querySelector('h2')).toBeNull();
    expect(sides[1].querySelector('summary')!.textContent).toContain('Après canicule');
    expect(sides[1].textContent).toContain('Aucune lecture enregistrée pour ce plan');
    expect(sides[1].textContent).not.toContain('règles impératives');
  });

  it('says a side has no reading when its list is empty, not only when it is absent', async () => {
    await comparerAvec({ base: cote({ kpi: kpi({ lecture: [] }) }) });

    const sides = racine().querySelectorAll('details.comparateur-lecture');
    expect(sides[0].textContent).toContain('Aucune lecture enregistrée pour ce plan');
  });

  it('lists the violations per constraint, and only when there are some', async () => {
    await comparerAvec({});
    expect(text()).not.toContain('Écarts par contrainte');

    await comparerAvec({
      diffViolations: [{ contrainte: 'Repos quotidien', base: 3, variante: 0 }],
    } as Partial<ComparaisonSnapshots>);

    expect(text()).toContain('Écarts par contrainte');
    expect(text()).toContain('Repos quotidien');
  });

  it('shows the comparison error instead of a stale table', async () => {
    await rendre([snapshot(), snapshot({ id: 8 })], new Error('comparaison impossible'));
    const page = fixture.componentInstance as unknown as PageInternals;
    page.baseId.set('7');
    page.varianteId.set('8');
    fixture.detectChanges();

    await comparer();

    expect(text()).toContain('comparaison impossible');
    expect(racine().querySelector('tbody tr')).toBeNull();
  });
});
