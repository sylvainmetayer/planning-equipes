// Query-param sync of the animateurs table: the quick filter and the sort
// survive a refresh. Kept apart from animateurs-page.spec.ts, which pins the
// alert badges and the rendered table.

import { Signal, WritableSignal, provideZonelessChangeDetection } from '@angular/core';
import { Location } from '@angular/common';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { Sort } from '@angular/material/sort';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { Animateur } from '../../core/models';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { AnimateursPage } from './animateurs-page';

function animateur(id: string, prenom: string, nom: string): Animateur {
  return {
    id,
    prenom,
    nom,
    competences: {},
    souhaits: [],
    joursIndisponibles: [],
  } as unknown as Animateur;
}

type PageInternals = {
  filtre: WritableSignal<string>;
  sort: WritableSignal<Sort>;
  viewChanged: Signal<boolean>;
  animateursFiltres: Signal<Animateur[]>;
  reinitialiserVue(): void;
};

function setUp(queryParams: Record<string, string>) {
  const replaceState = vi.fn();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      {
        provide: ApiService,
        useValue: { get: vi.fn(async () => ({ causes: [], totalCauses: 0, message: '' })) },
      },
      { provide: ReferenceCrudService, useValue: { reload: vi.fn(async () => undefined) } },
      {
        provide: SolverJobService,
        useValue: { solverBusy: () => false, editingLocked: () => false },
      },
      { provide: MatDialog, useValue: { open: vi.fn() } },
      { provide: Location, useValue: { path: () => '/animateurs', replaceState } },
      {
        provide: ActivatedRoute,
        useValue: { snapshot: { queryParamMap: convertToParamMap(queryParams) } },
      },
    ],
  });
  const store = TestBed.inject(ReferenceDataStore);
  store.animateurs.set([animateur('alice', 'Alice', 'Martin'), animateur('bob', 'Bob', 'Durand')]);
  const fixture = TestBed.createComponent(AnimateursPage);
  return { fixture, replaceState, page: fixture.componentInstance as unknown as PageInternals };
}

describe('AnimateursPage query-param sync', () => {
  beforeEach(() => {
    TestBed.resetTestingModule();
  });

  it('seeds the filter and the sort from the URL, and filters the rows accordingly', () => {
    const { page } = setUp({ q: 'durand', sort: 'majorite', dir: 'asc' });

    expect(page.filtre()).toBe('durand');
    expect(page.sort()).toEqual({ active: 'majorite', direction: 'asc' });
    expect(page.animateursFiltres().map((each) => each.id)).toEqual(['bob']);
  });

  it('shows the whole referential, unsorted, when the URL carries nothing', () => {
    const { page } = setUp({});

    expect(page.filtre()).toBe('');
    expect(page.sort()).toEqual({ active: '', direction: '' });
    expect(page.viewChanged()).toBe(false);
  });

  it('ignores a sort whose direction is not a direction', () => {
    const { page } = setUp({ sort: 'majorite', dir: '' });

    expect(page.sort()).toEqual({ active: '', direction: '' });
  });

  it('writes filter and sort back to the URL (replacing, not pushing history)', async () => {
    const { fixture, replaceState } = setUp({ q: 'durand', sort: 'majorite', dir: 'asc' });
    await fixture.whenStable();

    expect(replaceState).toHaveBeenCalledWith('/animateurs?sort=majorite&dir=asc&q=durand');
  });

  it('clears every param once the view is reset', async () => {
    const { fixture, replaceState, page } = setUp({ q: 'durand', sort: 'majorite', dir: 'asc' });
    await fixture.whenStable();
    expect(page.viewChanged()).toBe(true);
    replaceState.mockClear();

    page.reinitialiserVue();
    await fixture.whenStable();

    expect(page.viewChanged()).toBe(false);
    expect(replaceState).toHaveBeenLastCalledWith('/animateurs');
  });
});
