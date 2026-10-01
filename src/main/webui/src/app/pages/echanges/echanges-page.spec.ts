// The swap requests screen: the « à arbitrer » view the home screen's
// « À traiter aujourd'hui » counts and links to (`?statut=a-arbitrer`), and
// the narrowing a figure of the Statistiques tab opens the list with — every
// criterion carried by the URL, shown as a chip, and removable.

import { Location } from '@angular/common';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { ActivatedRoute, ParamMap, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { EchangesApi } from '../../core/api/echanges-api';
import {
  DemandeEchangeView,
  EchangeDelay,
  EchangeStatistics,
  StatutDemandeEchange,
} from '../../core/models';
import { EchangesPage } from './echanges-page';
import {
  NO_LIST_FILTER,
  isListFiltered,
  listFilterParams,
  matchesListFilter,
  oldestWaitingFirst,
  readListFilter,
  readMeasure,
  readToArbitrate,
  waitingSince,
} from './echanges-filter';

function demande(
  id: string,
  statut: StatutDemandeEchange,
  creeLe: string,
  cibleDecideLe: string | null = null,
  overrides: Partial<DemandeEchangeView> = {},
): DemandeEchangeView {
  return {
    id,
    creneauId: 1,
    date: '2026-07-10',
    heureDebut: '10:00',
    heureFin: '12:00',
    standId: 'S1',
    standNom: 'Stand',
    demandeurId: `a-${id}`,
    demandeurNom: `Demandeur ${id}`,
    cibleId: `c-${id}`,
    cibleNom: `Cible ${id}`,
    creneauCibleId: null,
    dateCible: null,
    heureDebutCible: null,
    heureFinCible: null,
    standCibleId: null,
    standCibleNom: null,
    motif: null,
    statut,
    prevalidationOk: true,
    contraintesViolees: [],
    commentaireAdmin: null,
    creeLe,
    cibleDecideLe,
    decideLe: null,
    communiqueeLe: null,
    ...overrides,
  };
}

// Agreed by the colleague on the 3rd, although created before the other one.
const RECENTE = demande('recente', 'PROPOSEE', '2026-07-01T08:00:00Z', '2026-07-03T08:00:00Z');
const ANCIENNE = demande('ancienne', 'PROPOSEE', '2026-07-02T08:00:00Z');
const WITH_COLLEAGUE = demande('cible', 'EN_ATTENTE_CIBLE', '2026-06-01T08:00:00Z');
const DECIDEE = demande('decidee', 'ACCEPTEE', '2026-06-01T08:00:00Z', null, {
  creneauCibleId: 2,
  standId: 'S2',
  standNom: 'Stand deux',
  prevalidationOk: false,
  contraintesViolees: ['Repos quotidien'],
  date: null,
});

const NOTHING_MEASURED: EchangeDelay = {
  mesurees: 0,
  sansHorodatage: 0,
  medianeSecondes: null,
  centile90Secondes: null,
  moyenneSecondes: null,
};

/** The statistics of a foire nobody used: what the Statistiques tab renders in these tests. */
const NO_STATISTICS: EchangeStatistics = {
  du: null,
  au: null,
  fenetreFoire: false,
  fuseau: 'Europe/Paris',
  creees: 0,
  dirigees: 0,
  enAttenteCible: 0,
  enAttenteOrganisation: 0,
  acceptees: 0,
  refusees: 0,
  refuseesCible: 0,
  annulees: 0,
  accordCollegues: { numerateur: 0, denominateur: 0 },
  acceptationOrganisation: { numerateur: 0, denominateur: 0 },
  aboutissement: { numerateur: 0, denominateur: 0 },
  prevalidees: { numerateur: 0, denominateur: 0 },
  acceptationNonPrevalidees: { numerateur: 0, denominateur: 0 },
  delaiReponseCollegue: NOTHING_MEASURED,
  delaiArbitrage: NOTHING_MEASURED,
  delaiCommunication: NOTHING_MEASURED,
  delaiAnnulation: NOTHING_MEASURED,
  parJourCreation: [],
  parJourEvenement: [],
  creneauRetire: 0,
  parStand: [],
  contraintesViolees: [],
};

describe('echanges-filter', () => {
  it('reads `statut=a-arbitrer` and nothing else', () => {
    expect(readToArbitrate('a-arbitrer')).toBe(true);
    expect(readToArbitrate(null)).toBe(false);
    expect(readToArbitrate('toutes')).toBe(false);
  });

  it('reads a measure the server knows, and nothing else', () => {
    expect(readMeasure('delai-arbitrage')).toBe('delai-arbitrage');
    expect(readMeasure('accordees')).toBe('accordees');
    expect(readMeasure('toutes')).toBeNull();
    expect(readMeasure(null)).toBeNull();
  });

  it("dates a request from the colleague's agreement, its creation without one", () => {
    expect(waitingSince(RECENTE)).toBe('2026-07-03T08:00:00Z');
    expect(waitingSince(ANCIENNE)).toBe('2026-07-02T08:00:00Z');
  });

  it('puts the request waiting the longest first', () => {
    expect(oldestWaitingFirst([RECENTE, ANCIENNE]).map((each) => each.id)).toEqual([
      'ancienne',
      'recente',
    ]);
  });

  it('reads the list filter tolerantly and writes it back param for param', () => {
    const filter = readListFilter(
      convertToParamMap({
        statuts: 'ACCEPTEE,INCONNU,REFUSEE',
        dirigees: '1',
        prevalidee: 'non',
        jour: 'retire',
        stand: 'S2',
        contrainte: 'Repos quotidien',
      }),
    );

    expect(filter).toEqual({
      statuts: ['ACCEPTEE', 'REFUSEE'],
      directed: true,
      prevalidated: 'non',
      day: 'retire',
      stand: 'S2',
      constraint: 'Repos quotidien',
    });
    expect(listFilterParams(filter)).toEqual({
      statuts: 'ACCEPTEE,REFUSEE',
      dirigees: '1',
      prevalidee: 'non',
      jour: 'retire',
      stand: 'S2',
      contrainte: 'Repos quotidien',
    });
    expect(readListFilter(convertToParamMap({ jour: 'demain', prevalidee: 'peut-etre' }))).toEqual(
      NO_LIST_FILTER,
    );
    expect(isListFiltered(NO_LIST_FILTER)).toBe(false);
  });

  it('keeps a request only when it passes every criterion', () => {
    const filter = readListFilter(
      convertToParamMap({ statuts: 'ACCEPTEE', dirigees: '1', jour: 'retire', stand: 'S2' }),
    );

    expect(matchesListFilter(DECIDEE, filter)).toBe(true);
    expect(matchesListFilter(RECENTE, filter)).toBe(false);
    expect(matchesListFilter(DECIDEE, { ...NO_LIST_FILTER, constraint: 'Repos quotidien' })).toBe(
      true,
    );
    expect(matchesListFilter(RECENTE, { ...NO_LIST_FILTER, prevalidated: 'non' })).toBe(false);
    expect(matchesListFilter(RECENTE, { ...NO_LIST_FILTER, day: '2026-07-10' })).toBe(true);
  });
});

async function setUp(queryParams: Record<string, string>) {
  const replaceState = vi.fn();
  const params = new BehaviorSubject<ParamMap>(convertToParamMap(queryParams));
  // The server narrows by creation period; these four stand for whatever it answered.
  const list = vi.fn<EchangesApi['list']>(async () => [RECENTE, ANCIENNE, WITH_COLLEAGUE, DECIDEE]);
  const statistics = vi.fn<EchangesApi['statistics']>(async () => NO_STATISTICS);
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      provideRouter([]),
      {
        provide: EchangesApi,
        useValue: {
          list,
          statistics,
          configuration: vi.fn(async () => ({
            foireOuverte: true,
            ouverteAujourdhui: true,
            debut: null,
            fin: null,
          })),
        },
      },
      { provide: MatDialog, useValue: { open: vi.fn() } },
      { provide: Location, useValue: { path: () => '/echanges', replaceState } },
      {
        provide: ActivatedRoute,
        useValue: { snapshot: { queryParamMap: params.value }, queryParamMap: params },
      },
    ],
  });
  const fixture = TestBed.createComponent(EchangesPage);
  // The first render runs ngOnInit.
  fixture.detectChanges();
  await fixture.whenStable();
  return { fixture, replaceState, params, list, statistics };
}

function cards(root: HTMLElement): string[] {
  return Array.from(root.querySelectorAll('.echanges-demande strong:first-of-type')).map(
    (each) => each.textContent?.trim() ?? '',
  );
}

describe('EchangesPage « à arbitrer »', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('opens on the requests to arbitrate only, the longest waiting first', async () => {
    const { fixture } = await setUp({ statut: 'a-arbitrer' });
    await fixture.whenStable();

    const root = fixture.nativeElement as HTMLElement;
    expect(cards(root)).toEqual(['Demandeur ancienne', 'Demandeur recente']);
    expect(root.textContent).not.toContain("En attente de l'accord du collègue");
    expect(root.textContent).not.toContain('Décidées');
  });

  it('shows every section without the param, and writes the filter back to the URL when ticked', async () => {
    const { fixture, replaceState } = await setUp({});
    await fixture.whenStable();

    const root = fixture.nativeElement as HTMLElement;
    expect(cards(root)).toHaveLength(4);
    expect(replaceState).toHaveBeenLastCalledWith('/echanges');

    root.querySelector<HTMLInputElement>('mat-checkbox input')!.click();
    await fixture.whenStable();
    expect(replaceState).toHaveBeenLastCalledWith('/echanges?statut=a-arbitrer');
    expect(cards(root)).toHaveLength(2);
  });
});

describe('EchangesPage narrowed by a figure of the statistics', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('asks the server for the creation period and filters the rest, each criterion a chip', async () => {
    const { fixture, list } = await setUp({
      du: '2026-06-01',
      au: '2026-06-30',
      statuts: 'ACCEPTEE',
      dirigees: '1',
    });
    await fixture.whenStable();

    const root = fixture.nativeElement as HTMLElement;
    expect(list).toHaveBeenLastCalledWith('2026-06-01', '2026-06-30', null);
    expect(cards(root)).toEqual(['Demandeur decidee']);
    const chips = Array.from(root.querySelectorAll('mat-chip')).map((chip) =>
      chip.getAttribute('data-filtre'),
    );
    expect(chips).toEqual(['periode', 'statuts', 'dirigees']);
  });

  it('widens back when a chip is removed, and reads the list again when the period goes', async () => {
    const { fixture, list, replaceState } = await setUp({ du: '2026-06-01', statuts: 'ACCEPTEE' });
    await fixture.whenStable();
    const root = fixture.nativeElement as HTMLElement;

    root.querySelector<HTMLButtonElement>('mat-chip[data-filtre="statuts"] button')!.click();
    await fixture.whenStable();
    expect(cards(root)).toHaveLength(4);
    expect(replaceState).toHaveBeenLastCalledWith('/echanges?du=2026-06-01');

    root.querySelector<HTMLButtonElement>('mat-chip[data-filtre="periode"] button')!.click();
    await fixture.whenStable();
    expect(list).toHaveBeenLastCalledWith(null, null, null);
    expect(replaceState).toHaveBeenLastCalledWith('/echanges');
  });

  it('follows a link from the statistics to the list on the same route', async () => {
    const { fixture, params, replaceState, list } = await setUp({
      onglet: 'stats',
      periode: 'edition',
    });
    const root = fixture.nativeElement as HTMLElement;
    // Opened on the statistics, the screen reads no name.
    expect(list).not.toHaveBeenCalled();
    expect(cards(root)).toHaveLength(0);
    expect(root.textContent).toContain('Pas encore de demande sur cette période.');
    expect(replaceState).toHaveBeenLastCalledWith('/echanges?onglet=stats&periode=edition');

    params.next(convertToParamMap({ prevalidee: 'non' }));
    await fixture.whenStable();
    fixture.detectChanges();

    expect(cards(root)).toEqual(['Demandeur decidee']);
    expect(replaceState).toHaveBeenLastCalledWith('/echanges?prevalidee=non');
    expect(list).toHaveBeenCalledTimes(1);
  });

  it('asks the server for the measure of a figure, shown as a removable chip', async () => {
    const { fixture, list, replaceState } = await setUp({
      du: '2026-06-01',
      mesure: 'delai-communication',
    });
    await fixture.whenStable();
    const root = fixture.nativeElement as HTMLElement;

    expect(list).toHaveBeenLastCalledWith('2026-06-01', null, 'delai-communication');
    expect(replaceState).toHaveBeenLastCalledWith(
      '/echanges?du=2026-06-01&mesure=delai-communication',
    );

    root.querySelector<HTMLButtonElement>('mat-chip[data-filtre="mesure"] button')!.click();
    await fixture.whenStable();
    expect(list).toHaveBeenLastCalledWith('2026-06-01', null, null);
    expect(replaceState).toHaveBeenLastCalledWith('/echanges?du=2026-06-01');
  });

  it('reloads the statistics, not the hidden list, from the Statistiques tab', async () => {
    const { fixture, list, statistics } = await setUp({ onglet: 'stats' });
    fixture.detectChanges();
    await fixture.whenStable();
    const root = fixture.nativeElement as HTMLElement;
    const calls = statistics.mock.calls.length;

    root.querySelector<HTMLButtonElement>('.page-header button')!.click();
    await fixture.whenStable();

    expect(statistics).toHaveBeenCalledTimes(calls + 1);
    expect(list).not.toHaveBeenCalled();
  });
});
