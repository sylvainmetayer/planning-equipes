// Query-param sync of the Équité table: the column sorted on, its direction
// and the quick filter survive a refresh. Kept apart from equite-page.spec.ts,
// which pins the loading/error/empty states of the report itself.

import { Location } from '@angular/common';
import { provideZonelessChangeDetection, Signal, WritableSignal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Sort } from '@angular/material/sort';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { LigneEquite, RapportEquite } from '../../core/models';
import { EquitePage } from './equite-page';

function ligne(animateurId: string, nom: string, heuresTotal: number): LigneEquite {
  return {
    animateurId,
    nom,
    heuresTotal,
    heuresParSemaine: { '2026-W28': heuresTotal },
    heuresSoiree: 0,
    heuresWeekEnd: 0,
    heuresJourFerie: 0,
    postes: 1,
    postesPenibles: 0,
    standsDistincts: 1,
    typologiesDistinctes: 0,
    emplacementsDistinctsParJourMax: 0,
    tauxSouhaits: 0,
    tauxAppreciation: 0,
    joursTravailles: 1,
    joursRepos: 0,
    plusLongueSerie: 1,
  };
}

const RAPPORT: RapportEquite = {
  heureDebutSoiree: '20:00:00',
  semaines: ['2026-W28'],
  lignes: [ligne('bob', 'Bob', 30), ligne('alice', 'Alice', 12)],
  syntheses: {},
  colonnesSolveur: [],
};

/** The edition's animateurs as the referential lists them: Carol holds no seat in the plan. */
const ANIMATEURS = [{ id: 'bob' }, { id: 'alice' }, { id: 'carol' }];

/** The API as the page and the referential store read it. */
async function get(url: string): Promise<unknown> {
  if (url === '/api/animateurs') {
    return ANIMATEURS;
  }
  if (url === '/api/planning/volumetrie') {
    return {
      animateurCount: 3,
      posteCount: 0,
      contrainteAdHocCount: 0,
      hoursToFill: 0,
      hoursAvailable: 0,
    };
  }
  return RAPPORT;
}

type PageInternals = {
  sort: WritableSignal<Sort>;
  filtre: WritableSignal<string>;
  viewChanged: Signal<boolean>;
  lignesAffichees: Signal<LigneEquite[]>;
  resetView(): void;
  comparedId: Signal<string>;
  comparedSearch: Signal<string>;
  onComparedSearch(text: string): void;
  choisir(animateurId: string): void;
};

function setUp(queryParams: Record<string, string>) {
  const replaceState = vi.fn();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      provideRouter([]),
      { provide: ApiService, useValue: { get: vi.fn(get), downloadGet: vi.fn() } },
      { provide: Location, useValue: { path: () => '/equite', replaceState } },
      {
        provide: ActivatedRoute,
        useValue: { snapshot: { queryParamMap: convertToParamMap(queryParams) } },
      },
    ],
  });
  const fixture = TestBed.createComponent(EquitePage);
  return { fixture, replaceState, page: fixture.componentInstance as unknown as PageInternals };
}

describe('EquitePage query-param sync', () => {
  beforeEach(() => {
    TestBed.resetTestingModule();
  });

  it('seeds the sort and the filter from the URL and applies them to the rows', async () => {
    const { fixture, page } = setUp({ sort: 'heuresTotal', dir: 'desc', q: 'b' });
    await fixture.whenStable();
    await vi.waitFor(() => expect(page.lignesAffichees()).toHaveLength(1));

    expect(page.sort()).toEqual({ active: 'heuresTotal', direction: 'desc' });
    expect(page.filtre()).toBe('b');
    expect(page.lignesAffichees().map((row) => row.nom)).toEqual(['Bob']);
  });

  it('leaves the report in its own order when the URL carries nothing', async () => {
    const { fixture, page } = setUp({});
    await fixture.whenStable();
    await vi.waitFor(() => expect(page.lignesAffichees()).toHaveLength(2));

    expect(page.sort()).toEqual({ active: '', direction: '' });
    expect(page.viewChanged()).toBe(false);
    expect(page.lignesAffichees().map((row) => row.nom)).toEqual(['Bob', 'Alice']);
  });

  it('ignores a malformed direction instead of failing the page', async () => {
    const { fixture, page } = setUp({ sort: 'heuresTotal', dir: 'sideways' });
    await fixture.whenStable();

    expect(page.sort()).toEqual({ active: '', direction: '' });
  });

  it('ignores a week column that no longer exists, and shows the report unsorted', async () => {
    const { fixture, page } = setUp({ sort: '2019-W01', dir: 'asc' });
    await fixture.whenStable();
    await vi.waitFor(() => expect(page.lignesAffichees()).toHaveLength(2));

    expect(page.lignesAffichees().map((row) => row.nom)).toEqual(['Bob', 'Alice']);
  });

  it('writes the sort and the filter back to the URL (replacing, not pushing history)', async () => {
    const { fixture, replaceState } = setUp({ sort: 'heuresTotal', dir: 'desc', q: 'bob' });
    await fixture.whenStable();

    expect(replaceState).toHaveBeenCalledWith('/equite?sort=heuresTotal&dir=desc&q=bob');
  });

  it('keeps the radar axes and the compared person in the URL, axes in canonical order', async () => {
    const { fixture, replaceState, page } = setUp({
      vue: 'fiche',
      animateur: 'bob',
      axes: 'plusLongueSerie,inconnu,heuresJourFerie',
      comparer: 'alice',
    });
    await fixture.whenStable();

    expect(replaceState).toHaveBeenLastCalledWith(
      '/equite?vue=fiche&animateur=bob&axes=heuresJourFerie%2CplusLongueSerie&comparer=alice',
    );
    expect(page.viewChanged()).toBe(true);
  });

  it('names the compared person in the field after a reload, so emptying it ends the comparison', async () => {
    const { fixture, replaceState, page } = setUp({
      vue: 'fiche',
      animateur: 'bob',
      comparer: 'alice',
    });
    await fixture.whenStable();
    await vi.waitFor(() => expect(page.comparedSearch()).toBe('Alice'));

    page.onComparedSearch('');
    await fixture.whenStable();
    expect(page.comparedId()).toBe('');
    expect(replaceState).toHaveBeenLastCalledWith('/equite?vue=fiche&animateur=bob');
  });

  it('ends the comparison when the fiche moves to the compared person', async () => {
    const { fixture, replaceState, page } = setUp({
      vue: 'fiche',
      animateur: 'bob',
      comparer: 'alice',
    });
    await fixture.whenStable();
    await vi.waitFor(() => expect(page.comparedSearch()).toBe('Alice'));

    page.choisir('alice');
    await fixture.whenStable();
    expect(page.comparedId()).toBe('');
    expect(page.comparedSearch()).toBe('');
    expect(replaceState).toHaveBeenLastCalledWith('/equite?vue=fiche&animateur=alice');
  });

  it('says a known animateur holds no seat, and treats an unknown id as no choice', async () => {
    const known = setUp({ vue: 'fiche', animateur: 'carol' });
    await known.fixture.whenStable();
    known.fixture.detectChanges();
    await vi.waitFor(() => {
      known.fixture.detectChanges();
      expect(known.fixture.nativeElement.textContent).toContain("n'a aucun poste");
    });

    TestBed.resetTestingModule();
    const unknown = setUp({ vue: 'fiche', animateur: 'ghost' });
    await unknown.fixture.whenStable();
    unknown.fixture.detectChanges();
    const text = unknown.fixture.nativeElement.textContent as string;
    expect(text).toContain('Tapez un nom');
    expect(text).not.toContain("n'a aucun poste");
  });

  it('clears every param once the view is reset', async () => {
    const { fixture, replaceState, page } = setUp({ sort: 'heuresTotal', dir: 'desc', q: 'bob' });
    await fixture.whenStable();
    expect(page.viewChanged()).toBe(true);
    replaceState.mockClear();

    page.resetView();
    await fixture.whenStable();

    expect(replaceState).toHaveBeenLastCalledWith('/equite');
  });
});
