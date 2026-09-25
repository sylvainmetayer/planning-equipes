// Query-param sync of the animateurs table: the quick filter and the sort
// survive a refresh. Kept apart from animateurs-page.spec.ts, which pins the
// alert badges and the rendered table.

import { Signal, WritableSignal, provideZonelessChangeDetection } from '@angular/core';
import { Location } from '@angular/common';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { Sort } from '@angular/material/sort';
import { provideLocationMocks } from '@angular/common/testing';
import { ActivatedRoute, convertToParamMap, provideRouter, Router } from '@angular/router';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { AnimateursApi } from '../../core/api/animateurs-api';
import { Animateur } from '../../core/models';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { AnimateursPage } from './animateurs-page';
import { seedStore } from '../../core/testing/seed-store';

function animateur(
  id: string,
  prenom: string,
  nom: string,
  competences: Record<string, string> = {},
): Animateur {
  return {
    id,
    prenom,
    nom,
    competences,
    souhaits: [],
    joursIndisponibles: [],
  } as unknown as Animateur;
}

type PageInternals = {
  filtre: WritableSignal<string>;
  sort: WritableSignal<Sort>;
  accuses: WritableSignal<'tous' | 'jamais' | 'silence'>;
  silenceJours: WritableSignal<number>;
  neverReminded: WritableSignal<boolean>;
  viewChanged: Signal<boolean>;
  animateursFiltres: Signal<Animateur[]>;
  typologiesFiltrees: Signal<string[]>;
  typologieLabel: Signal<string>;
  souhaitLabel: Signal<string>;
  clearTypologie(): void;
  clearSouhait(): void;
  resetView(): void;
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
    dernierEnvoi: null,
  },
  {
    animateurId: 'bob',
    nomAffiche: 'Bob Durand',
    statut: 'CONFIRME',
    affecte: true,
    confirmeLe: '2026-07-02T10:00:00Z',
    relanceLe: null,
    dernierEnvoi: null,
  },
];

/**
 * The same page under a real router: the address is navigated to, so the route
 * emits its params as it does in the application — which is what a deep link
 * landing on the current screen depends on.
 */
async function setUpRoute(url: string) {
  const dialog = { open: vi.fn(() => ({ afterClosed: () => of(undefined) })) };
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      provideRouter([{ path: 'animateurs', children: [] }]),
      provideLocationMocks(),
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
            echecsEnvoi: 0,
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
      { provide: MatDialog, useValue: dialog },
    ],
  });
  const store = TestBed.inject(ReferenceDataStore);
  seedStore(store, 'animateurs', [
    animateur('alice', 'Alice', 'Martin', { ESCAPE: 'CONFIRME' }),
    animateur('bob', 'Bob', 'Durand'),
  ]);
  seedStore(store, 'typologies', [{ id: 'ESCAPE', label: 'Escape game' }] as never);
  const router = TestBed.inject(Router);
  await router.navigateByUrl(url);
  const fixture = TestBed.createComponent(AnimateursPage);
  return { fixture, dialog, router, location: TestBed.inject(Location) };
}

/** Carole holds a seat, never answered, and was reminded a long time ago. */
const CAROLE_RELANCEE = {
  animateurId: 'carole',
  nomAffiche: 'Carole Petit',
  statut: 'RELANCE',
  affecte: true,
  confirmeLe: null,
  relanceLe: '2020-01-02T10:00:00Z',
  dernierEnvoi: null,
};

function setUp(
  queryParams: Record<string, string>,
  path = '/animateurs',
  confirmations: object[] = CONFIRMATIONS,
) {
  const replaceState = vi.fn();
  const dialog = { open: vi.fn(() => ({ afterClosed: () => of(undefined) })) };
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
          confirmations: vi.fn(async () => confirmations),
          syntheseConfirmations: vi.fn(async () => ({
            confirmes: 1,
            relances: 0,
            silencieux: 1,
            echecsEnvoi: 0,
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
      { provide: MatDialog, useValue: dialog },
      { provide: Location, useValue: { path: () => path, replaceState } },
      {
        provide: ActivatedRoute,
        useValue: { snapshot: { queryParamMap: convertToParamMap(queryParams) } },
      },
    ],
  });
  const store = TestBed.inject(ReferenceDataStore);
  seedStore(store, 'animateurs', [
    animateur('alice', 'Alice', 'Martin', { ESCAPE: 'CONFIRME' }),
    animateur('bob', 'Bob', 'Durand'),
    ...(confirmations.includes(CAROLE_RELANCEE) ? [animateur('carole', 'Carole', 'Petit')] : []),
  ]);
  seedStore(store, 'typologies', [{ id: 'ESCAPE', label: 'Escape game' }] as never);
  const fixture = TestBed.createComponent(AnimateursPage);
  return {
    fixture,
    replaceState,
    dialog,
    page: fixture.componentInstance as unknown as PageInternals,
  };
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

  /**
   * The links from a symptom (issue #489): a staffing bottleneck or a scarce
   * competence names a game category, and lands here on the people holding it.
   */
  describe('the typologie filter', () => {
    it('keeps only the animateurs holding an appreciation on one of the typologies named', () => {
      const { page } = setUp({ typologie: 'ESCAPE,QUIZ' });

      expect(page.typologiesFiltrees()).toEqual(['ESCAPE', 'QUIZ']);
      expect(page.animateursFiltres().map((each) => each.id)).toEqual(['alice']);
      expect(page.viewChanged()).toBe(true);
    });

    it('names the typologies by their label, the id when the référentiel has none', () => {
      const { page } = setUp({ typologie: 'ESCAPE,QUIZ' });

      expect(page.typologieLabel()).toBe('Escape game, QUIZ');
    });

    it('writes the filter to the URL, and drops it with the chip', async () => {
      const { fixture, page, replaceState } = setUp({ typologie: 'ESCAPE' });
      await fixture.whenStable();
      expect(replaceState).toHaveBeenLastCalledWith('/animateurs?typologie=ESCAPE');

      page.clearTypologie();
      await fixture.whenStable();

      expect(page.animateursFiltres().map((each) => each.id)).toEqual(['alice', 'bob']);
      expect(replaceState).toHaveBeenLastCalledWith('/animateurs');
    });
  });

  /**
   * The link from the Typologies screen on a game category nobody masters:
   * who wished for it is who to train first.
   */
  describe('the souhait filter', () => {
    it('keeps only the animateurs who wished for the typologie, and names it', async () => {
      const { fixture, page, replaceState } = setUp({ souhait: 'ESCAPE' });
      seedStore(TestBed.inject(ReferenceDataStore), 'animateurs', [
        animateur('alice', 'Alice', 'Martin'),
        { ...animateur('bob', 'Bob', 'Durand'), souhaits: ['ESCAPE'] },
      ]);
      await fixture.whenStable();

      expect(page.animateursFiltres().map((each) => each.id)).toEqual(['bob']);
      expect(page.souhaitLabel()).toBe('Escape game');
      expect(page.viewChanged()).toBe(true);
      expect(replaceState).toHaveBeenLastCalledWith('/animateurs?souhait=ESCAPE');

      page.clearSouhait();
      await fixture.whenStable();

      expect(page.animateursFiltres().map((each) => each.id)).toEqual(['alice', 'bob']);
      expect(replaceState).toHaveBeenLastCalledWith('/animateurs');
    });
  });

  /**
   * `?edit=<id>`: a link from a problem or a warning opens the fiche it names,
   * once the référentiel is in. Obeyed once — the param leaves the URL, so a
   * refresh shows the list, not the dialog again.
   *
   * Driven by a real router here, and that is the point: the link that carries
   * this param very often points at the screen already displayed, where nothing
   * is constructed and a param read in a constructor is never seen.
   */
  describe('the edit deep link', () => {
    it('opens the fiche named in the URL once the référentiel is loaded, and forgets the param', async () => {
      const { fixture, dialog, location } = await setUpRoute('/animateurs?edit=bob');
      await fixture.whenStable();

      expect(dialog.open).toHaveBeenCalledOnce();
      const [, config] = dialog.open.mock.calls[0] as unknown as [unknown, { data: unknown }];
      expect(config.data).toEqual({
        animateur: expect.objectContaining({ id: 'bob' }),
        dernierEnvoiEchec: null,
      });
      expect(location.path()).not.toContain('edit=');
    });

    it('opens nothing for an id the référentiel does not hold', async () => {
      const { fixture, dialog } = await setUpRoute('/animateurs?edit=nobody');
      await fixture.whenStable();

      expect(dialog.open).not.toHaveBeenCalled();
    });

    // The case the feature exists for, and the one a constructor cannot serve:
    // « Voir la fiche » pressed from the animateurs screen itself, twice in a
    // row on the same person.
    it('obeys a link landing on the screen already displayed, again and again', async () => {
      const { fixture, dialog, router } = await setUpRoute('/animateurs');
      await fixture.whenStable();
      expect(dialog.open).not.toHaveBeenCalled();

      await router.navigateByUrl('/animateurs?edit=bob');
      await fixture.whenStable();
      expect(dialog.open).toHaveBeenCalledOnce();

      await router.navigateByUrl('/animateurs?edit=bob');
      await fixture.whenStable();
      expect(dialog.open).toHaveBeenCalledTimes(2);
    });
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

    page.resetView();
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

  // « Jamais relancés »: the list « À traiter aujourd'hui » counts as silent to
  // remind, and links to — somebody already reminded is not on it.

  it('seeds « jamais relancés » from the URL and leaves out whoever was reminded', async () => {
    const { fixture, page } = setUp({ silence: '3', relance: 'jamais' }, '/animateurs', [
      ...CONFIRMATIONS,
      CAROLE_RELANCEE,
    ]);
    await fixture.whenStable();

    expect(page.neverReminded()).toBe(true);
    expect(page.animateursFiltres().map((each) => each.id)).toEqual(['alice']);
  });

  it('keeps the reminded among the silent without the criterion', async () => {
    const { fixture, page } = setUp({ silence: '3' }, '/animateurs', [
      ...CONFIRMATIONS,
      CAROLE_RELANCEE,
    ]);
    await fixture.whenStable();

    expect(page.neverReminded()).toBe(false);
    expect(page.animateursFiltres().map((each) => each.id)).toEqual(['alice', 'carole']);
  });

  it('writes the criterion to the URL only while a mode is on, and the reset drops it', async () => {
    const { fixture, replaceState, page } = setUp({ silence: '3', relance: 'jamais' });
    await fixture.whenStable();
    expect(replaceState).toHaveBeenLastCalledWith('/animateurs?silence=3&relance=jamais');

    page.accuses.set('tous');
    await fixture.whenStable();
    expect(replaceState).toHaveBeenLastCalledWith('/animateurs');

    page.accuses.set('jamais');
    await fixture.whenStable();
    expect(replaceState).toHaveBeenLastCalledWith('/animateurs?confirmation=jamais&relance=jamais');

    page.resetView();
    await fixture.whenStable();
    expect(page.neverReminded()).toBe(false);
    expect(replaceState).toHaveBeenLastCalledWith('/animateurs');
  });
});
