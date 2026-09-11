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
import { AnimateursApi } from '../../core/api/animateurs-api';
import { Animateur } from '../../core/models';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { AnimateursPage } from './animateurs-page';
import { seedStore } from '../../core/testing/seed-store';

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
  accuses: WritableSignal<'tous' | 'jamais' | 'silence'>;
  silenceJours: WritableSignal<number>;
  viewChanged: Signal<boolean>;
  animateursFiltres: Signal<Animateur[]>;
  reinitialiserVue(): void;
};

/** Alice holds a seat and never answered; Bob holds one and confirmed. */
const CONFIRMATIONS = [
  {
    animateurId: 'alice',
    nomAffiche: 'Alice Martin',
    statut: 'NON_VU',
    affecte: true,
    confirmeLe: null,
    relanceLe: null,
  },
  {
    animateurId: 'bob',
    nomAffiche: 'Bob Durand',
    statut: 'CONFIRME',
    affecte: true,
    confirmeLe: '2026-07-02T10:00:00Z',
    relanceLe: null,
  },
];

function setUp(queryParams: Record<string, string>) {
  const replaceState = vi.fn();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      {
        provide: ApiService,
        useValue: { get: vi.fn(async () => ({ causes: [], totalCauses: 0, message: '' })) },
      },
      {
        provide: AnimateursApi,
        useValue: {
          confirmations: vi.fn(async () => CONFIRMATIONS),
          syntheseConfirmations: vi.fn(async () => ({
            confirmes: 1,
            relances: 0,
            silencieux: 1,
            dernierePublicationLe: '2020-01-01T10:00:00Z',
            jamaisPublie: false,
          })),
        },
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
  seedStore(store, 'animateurs', [
    animateur('alice', 'Alice', 'Martin'),
    animateur('bob', 'Bob', 'Durand'),
  ]);
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
    const { fixture, replaceState, page } = setUp({
      q: 'durand',
      sort: 'majorite',
      dir: 'asc',
      silence: '3',
    });
    await fixture.whenStable();
    expect(page.viewChanged()).toBe(true);
    replaceState.mockClear();

    page.reinitialiserVue();
    await fixture.whenStable();

    expect(page.viewChanged()).toBe(false);
    expect(page.accuses()).toBe('tous');
    expect(replaceState).toHaveBeenLastCalledWith('/animateurs');
  });

  // The acknowledgement filters (issue #504) are view state like the rest:
  // a link carrying them opens on the same rows, and the reset clears them.

  it('seeds « jamais confirmés » from the URL and keeps only the unanswered', async () => {
    const { fixture, page } = setUp({ confirmation: 'jamais' });
    await fixture.whenStable();

    expect(page.accuses()).toBe('jamais');
    expect(page.viewChanged()).toBe(true);
    expect(page.animateursFiltres().map((each) => each.id)).toEqual(['alice']);
  });

  it('seeds « silencieux depuis N jours » from the URL, N included', async () => {
    const { fixture, page } = setUp({ silence: '7' });
    await fixture.whenStable();

    expect(page.accuses()).toBe('silence');
    expect(page.silenceJours()).toBe(7);
    // Published years ago in the fixture: Alice has been silent for far longer than 7 days.
    expect(page.animateursFiltres().map((each) => each.id)).toEqual(['alice']);
  });

  it('writes the chosen mode back to the URL, and only the param that mode uses', async () => {
    const { fixture, replaceState, page } = setUp({});
    await fixture.whenStable();

    page.accuses.set('jamais');
    await fixture.whenStable();
    expect(replaceState).toHaveBeenLastCalledWith('/animateurs?confirmation=jamais');

    page.accuses.set('silence');
    page.silenceJours.set(5);
    await fixture.whenStable();
    expect(replaceState).toHaveBeenLastCalledWith('/animateurs?silence=5');
  });

  it('ignores a silence that is not a number of days', async () => {
    const { fixture, page } = setUp({ silence: 'beaucoup' });
    await fixture.whenStable();

    expect(page.accuses()).toBe('tous');
    expect(page.animateursFiltres()).toHaveLength(2);
  });
});
