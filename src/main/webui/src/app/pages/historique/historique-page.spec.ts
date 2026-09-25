// The history screen's conversation with the server: « Exports » is a
// parameter of the request — so the whole retention is searched — and the
// classification of a line comes from the server's catalogue. The component is
// created but never rendered, as in the other page specs.

import { Location } from '@angular/common';
import { Signal, provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AnalysesApi } from '../../core/api/analyses-api';
import { ActionHistorique, EntreeHistorique } from '../../core/models';
import { FiltreNature } from './historique';
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

/** Reaches the protected members the template binds to. */
type PageInternals = {
  entrees: Signal<EntreeHistorique[]>;
  chargement: Signal<boolean>;
  nature: Signal<FiltreNature>;
  changeNature: (nature: FiltreNature) => void;
  reinitialiser: () => void;
  isExport: (entree: EntreeHistorique) => boolean;
};

function createPage(): PageInternals {
  const fixture = TestBed.createComponent(HistoriquePage);
  fixture.detectChanges();
  return fixture.componentInstance as unknown as PageInternals;
}

describe('HistoriquePage', () => {
  const analysesApi = { actionHistory: vi.fn(), actionInventory: vi.fn() };

  function setUp(queryParams: Record<string, string> = {}): void {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: AnalysesApi, useValue: analysesApi },
        { provide: Router, useValue: { navigate: vi.fn() } },
        { provide: Location, useValue: { path: () => '/historique', replaceState: vi.fn() } },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap(queryParams) } },
        },
      ],
    });
  }

  beforeEach(() => {
    analysesApi.actionHistory.mockReset();
    analysesApi.actionInventory.mockReset();
    analysesApi.actionHistory.mockResolvedValue([line()]);
    analysesApi.actionInventory.mockResolvedValue(INVENTORY);
  });

  it('reads every kind of action by default', async () => {
    setUp();
    const page = createPage();
    await vi.waitFor(() => expect(page.chargement()).toBe(false));

    expect(analysesApi.actionHistory).toHaveBeenCalledExactlyOnceWith(null);
  });

  it('asks the server for the exports when the address says so', async () => {
    setUp({ nature: 'EXPORTS' });
    const page = createPage();
    await vi.waitFor(() => expect(page.chargement()).toBe(false));

    expect(analysesApi.actionHistory).toHaveBeenCalledExactlyOnceWith('exports');
  });

  it('reloads from the server when « Exports » is chosen, and back', async () => {
    setUp();
    const page = createPage();
    await vi.waitFor(() => expect(page.chargement()).toBe(false));

    page.changeNature('EXPORTS');
    await vi.waitFor(() => expect(page.chargement()).toBe(false));
    expect(analysesApi.actionHistory).toHaveBeenLastCalledWith('exports');

    page.reinitialiser();
    await vi.waitFor(() => expect(page.chargement()).toBe(false));
    expect(analysesApi.actionHistory).toHaveBeenLastCalledWith(null);
    expect(analysesApi.actionHistory).toHaveBeenCalledTimes(3);
  });

  it('never lets an older answer overwrite the one asked last', async () => {
    let answerFirst!: (lines: EntreeHistorique[]) => void;
    analysesApi.actionHistory.mockReturnValueOnce(
      new Promise<EntreeHistorique[]>((resolve) => (answerFirst = resolve)),
    );
    analysesApi.actionHistory.mockResolvedValueOnce([line({ id: 2 })]);
    setUp();
    const page = createPage();

    page.changeNature('EXPORTS');
    await vi.waitFor(() => expect(page.entrees().map((e) => e.id)).toEqual([2]));
    answerFirst([line({ id: 1, action: 'ANIMATEUR_MODIFIE' })]);
    await Promise.resolve();

    expect(page.entrees().map((e) => e.id)).toEqual([2]);
  });

  it('classifies a line by the server’s catalogue', async () => {
    setUp();
    const page = createPage();
    await vi.waitFor(() => expect(page.isExport(line())).toBe(true));

    expect(page.isExport(line({ action: 'ANIMATEUR_MODIFIE' }))).toBe(false);
  });

  it('stays readable when the inventory cannot be read', async () => {
    analysesApi.actionInventory.mockRejectedValue(new Error('indisponible'));
    setUp();
    const page = createPage();
    await vi.waitFor(() => expect(page.chargement()).toBe(false));

    expect(page.entrees()).toHaveLength(1);
    expect(page.isExport(line())).toBe(false);
  });
});
