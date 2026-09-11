// The bench is keyed by two selectors, and the requests they fire do not come
// back in order. What is pinned here is the property the former request
// counter existed for — a stale answer never overwrites the screen — plus the
// cold open: the server picks the créneau, and the page adopts it without
// asking a second time. `banc-de-touche.spec.ts` covers the pure rows.

import { Location } from '@angular/common';
import { provideZonelessChangeDetection, Signal, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AnalysesApi } from '../../core/api/analyses-api';
import { BancDeTouche, CreneauSiege } from '../../core/models';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { BancDeTouchePage } from './banc-de-touche-page';

function creneau(id: number): CreneauSiege {
  return { id, jour: 1, date: '2026-07-08', heureDebut: '10:00', heureFin: '12:00', famille: 0 };
}

function banc(creneauId: number, partial: Partial<BancDeTouche> = {}): BancDeTouche {
  return {
    creneauId,
    statut: 'EVALUATED',
    posteCibleId: `p${creneauId}`,
    standCibleId: 'tir',
    animateurCibleId: null,
    total: 3,
    disponibles: 2,
    creneauxAvecSieges: [creneau(5), creneau(7)],
    animateurs: [],
    ...partial,
  };
}

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve: (value: T) => void = () => undefined;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

type PageInternals = {
  banc: Signal<BancDeTouche | null>;
  chargement: Signal<boolean>;
  creneaux: Signal<CreneauSiege[]>;
  erreur: Signal<string>;
  creneauAffiche: Signal<number | null>;
  changerCreneau: (creneauId: number) => void;
  changerStand: (standId: string) => void;
  reinitialiser: () => void;
};

describe('BancDeTouchePage', () => {
  const analysesApi = { bench: vi.fn() };
  const store = { stands: signal([]), animateurs: signal([]), reload: vi.fn() };
  let fixture: ComponentFixture<BancDeTouchePage>;

  beforeEach(() => {
    analysesApi.bench.mockReset();
    store.reload.mockReset();
    store.reload.mockResolvedValue(undefined);
  });

  function createPage(queryParams: Record<string, string> = {}): PageInternals {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: AnalysesApi, useValue: analysesApi },
        { provide: ReferenceDataStore, useValue: store },
        { provide: Location, useValue: { path: () => '/banc-de-touche', replaceState: vi.fn() } },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap(queryParams) } },
        },
      ],
    });
    fixture = TestBed.createComponent(BancDeTouchePage);
    return fixture.componentInstance as unknown as PageInternals;
  }

  it('lets the server pick the créneau on a cold open, and adopts it without asking again', async () => {
    analysesApi.bench.mockResolvedValue(banc(5));
    const page = createPage();

    await vi.waitFor(() => expect(page.banc()).not.toBeNull());

    expect(analysesApi.bench).toHaveBeenCalledExactlyOnceWith(null, '');
    expect(page.creneauAffiche()).toBe(5);
    await fixture.whenStable();
    // Adopting the answer must not re-key the bench: still one request.
    expect(analysesApi.bench).toHaveBeenCalledOnce();
  });

  it('asks for the créneau and stand a bookmark names', async () => {
    analysesApi.bench.mockResolvedValue(banc(7));
    const page = createPage({ creneau: '7', stand: 'tir' });

    await vi.waitFor(() => expect(page.banc()).not.toBeNull());

    expect(analysesApi.bench).toHaveBeenCalledExactlyOnceWith(7, 'tir');
  });

  it('re-keys the bench when a selector changes', async () => {
    analysesApi.bench.mockResolvedValue(banc(5));
    const page = createPage();
    await vi.waitFor(() => expect(page.banc()).not.toBeNull());

    analysesApi.bench.mockResolvedValue(banc(7));
    page.changerCreneau(7);
    await vi.waitFor(() => expect(analysesApi.bench).toHaveBeenLastCalledWith(7, ''));

    page.changerStand('quilles');
    await vi.waitFor(() => expect(analysesApi.bench).toHaveBeenLastCalledWith(7, 'quilles'));
  });

  it('asks for the créneau on screen, not the default, when only the stand changes', async () => {
    analysesApi.bench.mockResolvedValue(banc(5));
    const page = createPage();
    await vi.waitFor(() => expect(page.creneauAffiche()).toBe(5));

    page.changerStand('quilles');
    await vi.waitFor(() => expect(analysesApi.bench).toHaveBeenCalledTimes(2));

    expect(analysesApi.bench).toHaveBeenLastCalledWith(5, 'quilles');
  });

  it('keeps the selectors and the table on screen while another créneau loads', async () => {
    analysesApi.bench.mockResolvedValueOnce(banc(5));
    const page = createPage();
    await vi.waitFor(() => expect(page.banc()?.creneauId).toBe(5));

    const next = deferred<BancDeTouche>();
    analysesApi.bench.mockReturnValueOnce(next.promise);
    page.changerCreneau(7);
    await vi.waitFor(() => expect(page.chargement()).toBe(true));

    // A re-key resets the resource's value; the page must not follow it, or
    // the créneau selector — built from the answer — is unmounted mid-click.
    expect(page.banc()?.creneauId).toBe(5);
    expect(page.creneaux()).toHaveLength(2);

    next.resolve(banc(7));
    await vi.waitFor(() => expect(page.banc()?.creneauId).toBe(7));
  });

  it('never lets an older answer overwrite a newer request', async () => {
    const first = deferred<BancDeTouche>();
    const second = deferred<BancDeTouche>();
    analysesApi.bench.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
    const page = createPage({ creneau: '5' });
    await vi.waitFor(() => expect(analysesApi.bench).toHaveBeenCalledOnce());

    page.changerCreneau(7);
    await vi.waitFor(() => expect(analysesApi.bench).toHaveBeenCalledTimes(2));
    expect(page.chargement()).toBe(true);

    // The newer answer lands first, then the stale one: the screen must keep
    // the newer, and the progress bar must not drop before the current
    // request is done.
    second.resolve(banc(7));
    await vi.waitFor(() => expect(page.banc()?.creneauId).toBe(7));
    first.resolve(banc(5));
    await fixture.whenStable();

    expect(page.banc()?.creneauId).toBe(7);
    expect(page.chargement()).toBe(false);
  });

  it('shows the failure and clears the table when the server refuses', async () => {
    analysesApi.bench.mockResolvedValueOnce(banc(5));
    const page = createPage();
    await vi.waitFor(() => expect(page.banc()).not.toBeNull());

    analysesApi.bench.mockRejectedValueOnce(new Error('Créneau inconnu.'));
    page.changerCreneau(99);
    await vi.waitFor(() => expect(page.erreur()).toContain('Créneau inconnu.'));

    expect(page.banc()).toBeNull();
    expect(page.chargement()).toBe(false);
  });

  it('shows a référentiel that could not be read, rather than a blank card', async () => {
    analysesApi.bench.mockResolvedValue(banc(5));
    store.reload.mockRejectedValue(new Error('Référentiel indisponible.'));
    const page = createPage();

    await vi.waitFor(() => expect(page.erreur()).toContain('Référentiel indisponible.'));
    expect(store.reload).toHaveBeenCalledExactlyOnceWith(['stands', 'animateurs']);
  });

  it('asks the server again on reset, even when nothing was chosen', async () => {
    analysesApi.bench.mockResolvedValue(banc(5));
    const page = createPage();
    await vi.waitFor(() => expect(page.banc()).not.toBeNull());

    page.reinitialiser();
    await vi.waitFor(() => expect(analysesApi.bench).toHaveBeenCalledTimes(2));

    expect(analysesApi.bench).toHaveBeenLastCalledWith(null, '');
  });
});
