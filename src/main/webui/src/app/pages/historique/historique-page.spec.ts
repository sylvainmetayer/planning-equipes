// The history screen's conversation with the server: the nature and the
// period are parameters of the request — so the whole retention is searched —,
// they live in the address, a next page is asked by the cursor of the last line
// shown, and the classification of a line comes from the server's catalogue.
// The component is created but never rendered, as in the other page specs.

import { Location } from '@angular/common';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AnalysesApi, HistoryQuery } from '../../core/api/analyses-api';
import { ActionHistorique, EntreeHistorique } from '../../core/models';
import { fakeOf, provideFake } from '../../core/testing/fake';
import { PAGE_HISTORIQUE } from './historique';
import { HistoriquePage } from './historique-page';

function line(partial: Partial<EntreeHistorique> = {}): EntreeHistorique {
  return {
    id: 1,
    survenuLe: '2026-09-07T14:32:00Z',
    acteur: 'ADMIN',
    acteurId: 'admin',
    acteurNom: null,
    action: 'EXPORT_REFERENTIELS',
    libelle: 'Référentiels exportés en CSV',
    entite: 'PLANNING',
    entiteId: null,
    entiteNom: null,
    champs: ['stands'],
    resultat: 'SUCCES',
    statut: 200,
    ...partial,
  };
}

const INVENTORY: ActionHistorique[] = [
  {
    code: 'EXPORT_REFERENTIELS',
    libelle: 'Référentiels exportés en CSV',
    entite: 'PLANNING',
    export: true,
  },
  {
    code: 'ANIMATEUR_MODIFIE',
    libelle: 'Fiche animateur modifiée',
    entite: 'ANIMATEUR',
    export: false,
  },
];

function createPage(): HistoriquePage {
  const fixture = TestBed.createComponent(HistoriquePage);
  fixture.detectChanges();
  return fixture.componentInstance;
}

/** What the page asks for a first page under no filter. */
const FIRST_PAGE: HistoryQuery = {
  nature: null,
  depuis: null,
  jusqua: null,
  avant: null,
  limite: PAGE_HISTORIQUE + 1,
};

describe('HistoriquePage', () => {
  const analysesApi = fakeOf<AnalysesApi>({
    actionHistory: () => Promise.resolve([line()]),
    actionInventory: () => Promise.resolve(INVENTORY),
  });
  const location = { path: vi.fn(() => '/historique'), replaceState: vi.fn() };

  function setUp(queryParams: Record<string, string> = {}): void {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideFake(AnalysesApi, analysesApi),
        { provide: Router, useValue: { navigate: vi.fn() } },
        { provide: Location, useValue: location },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap(queryParams) } },
        },
      ],
    });
  }

  /** The address the page wrote last. */
  function lastAddress(): string {
    return location.replaceState.mock.calls.at(-1)?.[0] ?? '';
  }

  beforeEach(() => {
    analysesApi.actionHistory.mockReset();
    analysesApi.actionInventory.mockReset();
    location.replaceState.mockReset();
    analysesApi.actionHistory.mockResolvedValue([line()]);
    analysesApi.actionInventory.mockResolvedValue(INVENTORY);
  });

  it('reads every kind of action by default', async () => {
    setUp();
    const page = createPage();
    await vi.waitFor(() => expect(page['chargement']()).toBe(false));

    expect(analysesApi.actionHistory).toHaveBeenCalledExactlyOnceWith(FIRST_PAGE);
    expect(page['suivant']()).toBeNull();
  });

  it('asks the server for the exports when the address says so, in either case', async () => {
    setUp({ nature: 'EXPORTS' });
    const page = createPage();
    await vi.waitFor(() => expect(page['chargement']()).toBe(false));

    expect(analysesApi.actionHistory).toHaveBeenCalledExactlyOnceWith({
      ...FIRST_PAGE,
      nature: 'exports',
    });
    expect(lastAddress()).toBe('/historique?nature=exports');
  });

  /**
   * The Solveur's « N modifications depuis la dernière résolution »: the end
   * of the solve reaches the server to the microsecond, as the server counted
   * from it — a `Date` would have rounded it to the millisecond.
   */
  it('opens the changes since a solve from the address, the instant kept verbatim', async () => {
    setUp({ depuis: '2026-09-12T10:00:00.123456Z', nature: 'donnees' });
    const page = createPage();
    await vi.waitFor(() => expect(page['chargement']()).toBe(false));

    expect(analysesApi.actionHistory).toHaveBeenCalledExactlyOnceWith({
      ...FIRST_PAGE,
      nature: 'donnees',
      depuis: '2026-09-12T10:00:00.123456Z',
    });
    expect(page['nature']()).toBe('DONNEES');
    expect(lastAddress()).toBe('/historique?nature=donnees&depuis=2026-09-12T10%3A00%3A00.123456Z');
  });

  /**
   * The field shows the Solveur's bound to the minute: until the reader
   * actually edits it, the microseconds stay — editing the other bound
   * included.
   */
  it('keeps the exact bound while its field still shows it', async () => {
    const depuis = '2026-09-12T10:00:00.123456Z';
    setUp({ depuis, nature: 'donnees' });
    const page = createPage();
    await vi.waitFor(() => expect(page['chargement']()).toBe(false));

    page['changeDepuis'](page['depuisLocal']());
    page['commitPeriod']();
    expect(page['depuis']()).toBe(depuis);
    expect(analysesApi.actionHistory).toHaveBeenCalledTimes(1);

    page['changeJusqua']('2026-09-12T14:32');
    page['commitPeriod']();
    await vi.waitFor(() => expect(page['chargement']()).toBe(false));
    expect(analysesApi.actionHistory).toHaveBeenLastCalledWith({
      ...FIRST_PAGE,
      nature: 'donnees',
      depuis,
      jusqua: new Date(new Date('2026-09-12T14:32').getTime() + 59_999)
        .toISOString()
        .replace('Z', '999Z'),
    });
  });

  /** The field reports every segment typed: « 2026 » typed digit by digit is four valid years. */
  it('asks for a period once the typing rests, not at every keystroke', async () => {
    setUp();
    const page = createPage();
    await vi.waitFor(() => expect(page['chargement']()).toBe(false));

    vi.useFakeTimers();
    try {
      page['changeDepuis']('0002-09-12T08:30');
      page['changeDepuis']('0020-09-12T08:30');
      page['changeDepuis']('2026-09-12T08:30');
      vi.advanceTimersByTime(300);
      expect(analysesApi.actionHistory).toHaveBeenCalledTimes(1);
      vi.advanceTimersByTime(1000);
    } finally {
      vi.useRealTimers();
    }
    await vi.waitFor(() => expect(page['chargement']()).toBe(false));

    expect(analysesApi.actionHistory).toHaveBeenCalledTimes(2);
    expect(analysesApi.actionHistory).toHaveBeenLastCalledWith({
      ...FIRST_PAGE,
      depuis: new Date('2026-09-12T08:30').toISOString(),
    });
  });

  it('drops a bound it cannot read rather than sending it to be refused', async () => {
    setUp({ depuis: 'hier', jusqua: '2026-09-13T00:00:00Z' });
    const page = createPage();
    await vi.waitFor(() => expect(page['chargement']()).toBe(false));

    expect(analysesApi.actionHistory).toHaveBeenCalledExactlyOnceWith({
      ...FIRST_PAGE,
      jusqua: '2026-09-13T00:00:00Z',
    });
  });

  it('reloads when a bound is typed, and writes the period in the address', async () => {
    setUp();
    const page = createPage();
    await vi.waitFor(() => expect(page['chargement']()).toBe(false));

    page['changeDepuis']('2026-09-12T08:30');
    page['commitPeriod']();
    await vi.waitFor(() => expect(page['chargement']()).toBe(false));

    const depuis = new Date('2026-09-12T08:30').toISOString();
    expect(analysesApi.actionHistory).toHaveBeenLastCalledWith({ ...FIRST_PAGE, depuis });
    expect(page['depuisLocal']()).toBe('2026-09-12T08:30');
    TestBed.tick();
    expect(lastAddress()).toBe(`/historique?depuis=${encodeURIComponent(depuis)}`);

    page['reinitialiser']();
    await vi.waitFor(() => expect(page['chargement']()).toBe(false));
    expect(analysesApi.actionHistory).toHaveBeenLastCalledWith(FIRST_PAGE);
    TestBed.tick();
    expect(lastAddress()).toBe('/historique');
  });

  it('reloads from the server when « Exports » is chosen, and back', async () => {
    setUp();
    const page = createPage();
    await vi.waitFor(() => expect(page['chargement']()).toBe(false));

    page['changeNature']('EXPORTS');
    await vi.waitFor(() => expect(page['chargement']()).toBe(false));
    expect(analysesApi.actionHistory).toHaveBeenLastCalledWith({
      ...FIRST_PAGE,
      nature: 'exports',
    });

    page['reinitialiser']();
    await vi.waitFor(() => expect(page['chargement']()).toBe(false));
    expect(analysesApi.actionHistory).toHaveBeenLastCalledWith(FIRST_PAGE);
    expect(analysesApi.actionHistory).toHaveBeenCalledTimes(3);
  });

  /**
   * One line more than a page is asked: when it comes back there is a next
   * page, asked after the last line shown, under the same filters.
   */
  it('loads the next page after the last line shown', async () => {
    const full = Array.from({ length: PAGE_HISTORIQUE + 1 }, (_, i) => line({ id: 1000 - i }));
    analysesApi.actionHistory.mockResolvedValueOnce(full);
    analysesApi.actionHistory.mockResolvedValueOnce([line({ id: 3 }), line({ id: 2 })]);
    setUp({ nature: 'donnees' });
    const page = createPage();
    await vi.waitFor(() => expect(page['chargement']()).toBe(false));

    expect(page['entrees']()).toHaveLength(PAGE_HISTORIQUE);
    const lastShown = 1000 - (PAGE_HISTORIQUE - 1);
    expect(page['suivant']()).toBe(lastShown);

    await page['loadMore']();

    expect(analysesApi.actionHistory).toHaveBeenLastCalledWith({
      ...FIRST_PAGE,
      nature: 'donnees',
      avant: lastShown,
    });
    expect(page['entrees']()).toHaveLength(PAGE_HISTORIQUE + 2);
    expect(page['entrees']().at(-1)?.id).toBe(2);
    expect(page['suivant']()).toBeNull();
  });

  it('never lets an older answer overwrite the one asked last', async () => {
    let answerFirst!: (lines: EntreeHistorique[]) => void;
    analysesApi.actionHistory.mockReturnValueOnce(
      new Promise<EntreeHistorique[]>((resolve) => (answerFirst = resolve)),
    );
    analysesApi.actionHistory.mockResolvedValueOnce([line({ id: 2 })]);
    setUp();
    const page = createPage();

    page['changeNature']('EXPORTS');
    await vi.waitFor(() => expect(page['entrees']().map((e) => e.id)).toEqual([2]));
    answerFirst([line({ id: 1, action: 'ANIMATEUR_MODIFIE' })]);
    await Promise.resolve();

    expect(page['entrees']().map((e) => e.id)).toEqual([2]);
  });

  it('shows nothing of the previous filters when a reload fails', async () => {
    const full = Array.from({ length: PAGE_HISTORIQUE + 1 }, (_, i) => line({ id: 1000 - i }));
    analysesApi.actionHistory.mockResolvedValueOnce(full);
    analysesApi.actionHistory.mockRejectedValueOnce(new Error('indisponible'));
    setUp();
    const page = createPage();
    await vi.waitFor(() => expect(page['chargement']()).toBe(false));
    expect(page['suivant']()).not.toBeNull();

    page['changeNature']('EXPORTS');
    await vi.waitFor(() => expect(page['chargement']()).toBe(false));

    expect(page['erreur']()).not.toBe('');
    expect(page['entrees']()).toEqual([]);
    expect(page['suivant']()).toBeNull();
  });

  it('classifies a line by the server’s catalogue', async () => {
    setUp();
    const page = createPage();
    await vi.waitFor(() => expect(page['isExport'](line())).toBe(true));

    expect(page['isExport'](line({ action: 'ANIMATEUR_MODIFIE' }))).toBe(false);
  });

  it('stays readable when the inventory cannot be read', async () => {
    analysesApi.actionInventory.mockRejectedValue(new Error('indisponible'));
    setUp();
    const page = createPage();
    await vi.waitFor(() => expect(page['chargement']()).toBe(false));

    expect(page['entrees']()).toHaveLength(1);
    expect(page['isExport'](line())).toBe(false);
  });
});
