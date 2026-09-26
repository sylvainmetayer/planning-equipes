// The comparator panel of « Versions du plan »: what it asks the server for
// the two sides it is given, its loading / error / data states, and the
// caveats that qualify every figure of the table — different editions,
// different volumetries, recomputed KPI, different dosages: read without them,
// the comparison says something it does not mean. The metric rows themselves
// are the business of `comparateur-metrics.spec.ts`.

import { provideZonelessChangeDetection, Signal } from '@angular/core';
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
import { ComparaisonPanel } from './comparaison-panel';

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
    dosagesDifferents: false,
    diffViolations: [],
    ...overrides,
  };
}

/** Reaches the protected members the template binds to. */
type PanelInternals = {
  comparaison: Signal<ComparaisonSnapshots | null>;
  chargement: Signal<boolean>;
  error: Signal<string>;
  lignes: Signal<LigneMetrique[]>;
  kpiRecalcule: Signal<boolean>;
  cotesPerimes: Signal<string[]>;
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

describe('ComparaisonPanel', () => {
  const planningApi = { compareSnapshots: vi.fn() };
  let fixture: ComponentFixture<ComparaisonPanel>;

  beforeEach(() => {
    planningApi.compareSnapshots.mockReset();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: PlanningApi, useValue: planningApi },
      ],
    });
  });

  /** Opens the panel on two sides, the table's snapshots beside it. */
  async function open(
    base: string,
    variante: string,
    snapshots: PlanSnapshot[] = [],
  ): Promise<PanelInternals> {
    fixture = TestBed.createComponent(ComparaisonPanel);
    fixture.componentRef.setInput('base', base);
    fixture.componentRef.setInput('variante', variante);
    fixture.componentRef.setInput('snapshots', snapshots);
    await fixture.whenStable();
    return fixture.componentInstance as unknown as PanelInternals;
  }

  it('asks for the two sides it is given, and keeps what comes back', async () => {
    planningApi.compareSnapshots.mockResolvedValue(comparaison());

    const panel = await open('7', 'courant');

    expect(planningApi.compareSnapshots).toHaveBeenCalledExactlyOnceWith('7', 'courant');
    expect(panel.comparaison()).not.toBeNull();
    expect(panel.lignes().length).toBeGreaterThan(0);
    expect(panel.chargement()).toBe(false);
  });

  it('is loading while the comparison is in flight', async () => {
    const pending = deferred<ComparaisonSnapshots>();
    planningApi.compareSnapshots.mockReturnValue(pending.promise);

    const panel = await openWithoutWaiting('7', '8');
    expect(panel.chargement()).toBe(true);

    pending.resolve(comparaison());
    await fixture.whenStable();
    expect(panel.chargement()).toBe(false);
  });

  /** Like `open`, without waiting for the comparison to land. */
  async function openWithoutWaiting(base: string, variante: string): Promise<PanelInternals> {
    fixture = TestBed.createComponent(ComparaisonPanel);
    fixture.componentRef.setInput('base', base);
    fixture.componentRef.setInput('variante', variante);
    fixture.detectChanges();
    return fixture.componentInstance as unknown as PanelInternals;
  }

  it('compares again when another pair is ticked, and drops the previous table first', async () => {
    planningApi.compareSnapshots.mockResolvedValue(comparaison());
    const panel = await open('7', '8');
    expect(panel.comparaison()).not.toBeNull();

    planningApi.compareSnapshots.mockRejectedValue(new Error('Comparaison impossible.'));
    fixture.componentRef.setInput('variante', 'courant');
    await fixture.whenStable();

    expect(planningApi.compareSnapshots).toHaveBeenLastCalledWith('7', 'courant');
    expect(panel.error()).toContain('Comparaison impossible.');
    expect(panel.comparaison()).toBeNull();
    expect(panel.lignes()).toEqual([]);
  });

  it('flags the degraded mode when either side had its KPI recomputed, and only then', async () => {
    planningApi.compareSnapshots.mockResolvedValue(
      comparaison({ variante: cote({ snapshotId: 8, kpiRecalcule: true }) }),
    );
    expect((await open('7', '8')).kpiRecalcule()).toBe(true);

    planningApi.compareSnapshots.mockResolvedValue(comparaison());
    expect((await open('7', '8')).kpiRecalcule()).toBe(false);
  });

  // Read from the comparison displayed: the warning describes the table on screen.
  it('names the compared sides whose referential has moved since the capture', async () => {
    planningApi.compareSnapshots.mockResolvedValue(comparaison());

    const panel = await open('7', '8', [
      snapshot({ id: 7, libelle: 'Avant canicule', perime: true }),
      snapshot({ id: 8, libelle: 'Après canicule', perime: false }),
    ]);

    expect(panel.cotesPerimes()).toEqual(['Avant canicule']);
  });

  it('names the unlabelled side the currently persisted plan', async () => {
    planningApi.compareSnapshots.mockResolvedValue(comparaison());
    const panel = await open('7', '8');

    expect(panel.libelleCote(cote({ libelle: null }))).toContain('persisté');
    expect(panel.libelleCote(cote({ libelle: 'Avant canicule' }))).toBe('Avant canicule');
  });

  it('words a delta: a dash when unknown, signed, rounded, and says its direction', async () => {
    planningApi.compareSnapshots.mockResolvedValue(comparaison());
    const panel = await open('7', '8');

    expect(panel.deltaLabel(ligne({ delta: null }))).toBe('—');
    expect(panel.deltaLabel(ligne({ delta: 4 }))).toBe('+4');
    expect(panel.deltaLabel(ligne({ delta: -4 }))).toBe('-4');
    expect(panel.deltaLabel(ligne({ delta: 1.25 }))).toBe('+1.3');
    expect(panel.deltaLabel(ligne({ delta: 4, tendance: 'amelioration' }))).toContain(
      'amélioration',
    );
    expect(panel.deltaLabel(ligne({ delta: -4, tendance: 'degradation' }))).toContain(
      'dégradation',
    );
  });

  it('distinguishes an unmeasured violation count from zero', async () => {
    planningApi.compareSnapshots.mockResolvedValue(comparaison());
    const panel = await open('7', '8');

    expect(panel.violationsLabel(null)).toContain('non mesuré');
    expect(panel.violationsLabel(0)).toBe('0');
  });
});

describe('ComparaisonPanel rendering', () => {
  let fixture: ComponentFixture<ComparaisonPanel>;

  async function rendre(resultat: ComparaisonSnapshots | Error): Promise<void> {
    const planningApi = {
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
    fixture = TestBed.createComponent(ComparaisonPanel);
    fixture.componentRef.setInput('base', '7');
    fixture.componentRef.setInput('variante', '8');
    await fixture.whenStable();
  }

  function racine(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function text(): string {
    return racine().textContent!.replace(/\s+/g, ' ');
  }

  it('renders one row per metric once the two plans are compared', async () => {
    await rendre(comparaison());

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

  async function compareWith(overrides: Partial<ComparaisonSnapshots>): Promise<void> {
    await rendre(comparaison(overrides));
  }

  it('warns that two plans of different editions are not comparable all things equal', async () => {
    await compareWith({ editionsDifferentes: true });

    expect(text()).toContain('éditions différentes');
  });

  it('warns that a volumetry gap is not a quality gap', async () => {
    await compareWith({ volumetriesDifferentes: true });

    expect(text()).toContain("n'ont pas le même nombre de postes");
  });

  it('warns when a snapshot predates the KPI and had to be recomputed', async () => {
    await compareWith({ base: cote({ kpiRecalcule: true }) });

    // Its violations are simply not measured: saying so is what keeps the
    // "0 violation" of that column from being read as good news.
    expect(text()).toContain('mode dégradé');
  });

  it('warns that two plans solved under different dosages do not compare at equal weights, and lists the rules', async () => {
    const neutre = { weights: {}, instanceWeights: {}, disabled: [], enabled: [] };
    await compareWith({
      dosagesDifferents: true,
      base: cote({ kpi: kpi({ dosage: neutre }) }),
      variante: cote({
        snapshotId: 8,
        kpi: kpi({ dosage: { ...neutre, weights: { souhaitsIncompatibles: 5 } } }),
      }),
    });

    expect(text()).toContain('dosages différents');
    expect(text()).toContain('souhaitsIncompatibles');
    expect(text()).toContain('1 → 5');
  });

  it('stays silent about all three caveats when none applies', async () => {
    await compareWith({});

    expect(racine().querySelectorAll('.locked-hint')).toHaveLength(0);
  });

  it('folds the reading of each side under its own name, and says when a side has none', async () => {
    await compareWith({
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
    expect(sides[0].querySelector('h2, h3')).toBeNull();
    expect(sides[1].querySelector('summary')!.textContent).toContain('Après canicule');
    expect(sides[1].textContent).toContain('Aucune lecture enregistrée pour ce plan');
    expect(sides[1].textContent).not.toContain('règles impératives');
  });

  it('says a side has no reading when its list is empty, not only when it is absent', async () => {
    await compareWith({ base: cote({ kpi: kpi({ lecture: [] }) }) });

    const sides = racine().querySelectorAll('details.comparateur-lecture');
    expect(sides[0].textContent).toContain('Aucune lecture enregistrée pour ce plan');
  });

  it('lists the violations per constraint, and only when there are some', async () => {
    await compareWith({});
    expect(text()).not.toContain('Écarts par contrainte');

    await compareWith({
      diffViolations: [{ contrainte: 'Repos quotidien', base: 3, variante: 0 }],
    } as Partial<ComparaisonSnapshots>);

    expect(text()).toContain('Écarts par contrainte');
    expect(text()).toContain('Repos quotidien');
  });

  it('shows the comparison error instead of a stale table', async () => {
    await rendre(new Error('comparaison impossible'));

    expect(text()).toContain('comparaison impossible');
    expect(racine().querySelector('tbody tr')).toBeNull();
  });

  it('closes on its button, and names itself for assistive technology', async () => {
    await rendre(comparaison());
    const closed = vi.fn();
    fixture.componentInstance.closed.subscribe(closed);

    const aside = racine().querySelector('aside')!;
    expect(aside.getAttribute('aria-labelledby')).toBe('versions-panneau-titre');
    (racine().querySelector('button[aria-label="Fermer"]') as HTMLButtonElement).click();

    expect(closed).toHaveBeenCalledOnce();
  });
});
