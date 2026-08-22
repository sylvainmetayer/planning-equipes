// Loading / error / empty / data states of the staffing-need screen, plus the
// derived values the template binds to. The component is created but never
// rendered, so this stays a logic test (the project favours those over full
// DOM rendering).
//
// These four states are exactly what a migration to `httpResource()` would
// re-implement, which is why they are pinned here first.

import { provideZonelessChangeDetection, Signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { BorneStaffing, JourStaffing, StaffingSummary } from '../../core/models';
import { StaffingPage } from './staffing-page';

/** A promise whose settlement the test drives, to observe the in-flight state. */
function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

function jour(overrides: Partial<JourStaffing> = {}): JourStaffing {
  return {
    date: '2026-08-01',
    jour: 1,
    standsOuverts: 12,
    sieges: 40,
    heures: 200,
    picSimultane: 18,
    picAvecPause: 22,
    ...overrides
  };
}

function summary(overrides: Partial<StaffingSummary> = {}): StaffingSummary {
  return {
    parJour: [jour()],
    picSimultane: 18,
    picAvecPause: 22,
    jourCritique: jour(),
    totalDemandeHeures: 1200,
    nombreSemaines: 3,
    capaciteHeuresParAnimateur: 90,
    chargeTotal: 14,
    minimumTotal: 22,
    borneRetenue: 'PIC_AVEC_PAUSE',
    minimumMajeurs: 15,
    minimumMineurs: 7,
    pauseMinimaleMinutes: 30,
    dureeHebdomadaireMaxMinutes: 2880,
    ...overrides
  };
}

/** Reaches the protected members the template binds to. */
type PageInternals = {
  summary: Signal<StaffingSummary | null>;
  loading: Signal<boolean>;
  error: Signal<string>;
  columns: string[];
  heuresParSemaine: Signal<number>;
  jourCritiqueLabel: (jour: JourStaffing) => string;
  estBorneRetenue: (borne: BorneStaffing) => boolean;
  pauseMinutes: () => number;
  /** Private to the component; reachable here because `private` is compile-time only. */
  load: () => Promise<void>;
};

describe('StaffingPage', () => {
  const api = { get: vi.fn() };

  beforeEach(() => {
    api.get.mockReset();
    api.get.mockResolvedValue(summary());
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), { provide: ApiService, useValue: api }]
    });
  });

  function createPage(): PageInternals {
    return TestBed.createComponent(StaffingPage).componentInstance as unknown as PageInternals;
  }

  describe('loading state', () => {
    // `load()` raises the flag itself, synchronously, before its first await:
    // the initial value of the signal is never observed. The point of the test
    // is that the first paint shows a progress bar and not an empty table.
    it('is loading while the first request is in flight, with nothing to show yet', () => {
      api.get.mockReturnValue(deferred<StaffingSummary>().promise);

      const page = createPage();

      expect(page.loading()).toBe(true);
      expect(page.summary()).toBeNull();
    });

    it('stops loading once the summary lands', async () => {
      const pending = deferred<StaffingSummary>();
      api.get.mockReturnValue(pending.promise);
      const page = createPage();

      pending.resolve(summary({ minimumTotal: 33 }));
      await vi.waitFor(() => expect(page.loading()).toBe(false));

      expect(page.summary()?.minimumTotal).toBe(33);
    });

    it('reads the summary from the server on creation instead of computing it in the browser', async () => {
      const page = createPage();
      await vi.waitFor(() => expect(page.loading()).toBe(false));

      expect(api.get).toHaveBeenCalledExactlyOnceWith('/api/staffing');
    });
  });

  describe('error state', () => {
    it('shows the failure message and stops loading', async () => {
      api.get.mockRejectedValue(new Error('Référentiel incomplet.'));

      const page = createPage();
      await vi.waitFor(() => expect(page.loading()).toBe(false));

      expect(page.error()).toContain('Référentiel incomplet.');
      expect(page.summary()).toBeNull();
    });

    it('clears the error once a later load succeeds', async () => {
      api.get.mockRejectedValueOnce(new Error('Référentiel incomplet.'));
      const page = createPage();
      await vi.waitFor(() => expect(page.error()).not.toBe(''));

      api.get.mockResolvedValue(summary());
      await page.load();

      expect(page.error()).toBe('');
      expect(page.summary()).not.toBeNull();
    });

    // Deliberate, and different from the hours screen: the error is cleared on
    // success, never when a load starts. A failing refresh therefore keeps the
    // previous summary on screen behind its message rather than blanking it.
    it('keeps the previous summary visible when a refresh fails', async () => {
      const page = createPage();
      await vi.waitFor(() => expect(page.summary()).not.toBeNull());

      api.get.mockRejectedValue(new Error('Référentiel incomplet.'));
      await page.load();

      expect(page.summary()).not.toBeNull();
      expect(page.error()).toContain('Référentiel incomplet.');
    });
  });

  describe('empty state', () => {
    it('reports no hours per week rather than a division by zero when no week is covered', async () => {
      api.get.mockResolvedValue(summary({ nombreSemaines: 0, capaciteHeuresParAnimateur: 0, parJour: [] }));

      const page = createPage();
      await vi.waitFor(() => expect(page.summary()).not.toBeNull());

      expect(page.heuresParSemaine()).toBe(0);
    });

    it('reports no hours per week and no break while nothing is loaded', () => {
      api.get.mockReturnValue(deferred<StaffingSummary>().promise);

      const page = createPage();

      expect(page.heuresParSemaine()).toBe(0);
      expect(page.pauseMinutes()).toBe(0);
      expect(page.estBorneRetenue('PIC_AVEC_PAUSE')).toBe(false);
    });
  });

  describe('displayed data', () => {
    it('spreads the per-animateur capacity over the covered weeks', async () => {
      api.get.mockResolvedValue(summary({ capaciteHeuresParAnimateur: 90, nombreSemaines: 3 }));

      const page = createPage();
      await vi.waitFor(() => expect(page.summary()).not.toBeNull());

      expect(page.heuresParSemaine()).toBe(30);
    });

    it('highlights the bound the server actually retained, and only that one', async () => {
      api.get.mockResolvedValue(summary({ borneRetenue: 'CHARGE_HORAIRE' }));

      const page = createPage();
      await vi.waitFor(() => expect(page.summary()).not.toBeNull());

      expect(page.estBorneRetenue('CHARGE_HORAIRE')).toBe(true);
      expect(page.estBorneRetenue('PIC_AVEC_PAUSE')).toBe(false);
      expect(page.estBorneRetenue('PIC_SIMULTANE')).toBe(false);
    });

    it('reports the legal break the peak-with-break bound is built on', async () => {
      api.get.mockResolvedValue(summary({ pauseMinimaleMinutes: 45 }));

      const page = createPage();
      await vi.waitFor(() => expect(page.summary()).not.toBeNull());

      expect(page.pauseMinutes()).toBe(45);
    });

    it('names the busiest day by its number, date and open stands', () => {
      const page = createPage();

      const label = page.jourCritiqueLabel(jour({ jour: 4, date: '2026-08-04', standsOuverts: 27 }));

      expect(label).toContain('4');
      expect(label).toContain('2026-08-04');
      expect(label).toContain('27');
    });

    it('lists the peak with break as a column of its own, distinct from the simultaneous peak', () => {
      const page = createPage();

      expect(page.columns).toContain('picSimultane');
      expect(page.columns).toContain('picAvecPause');
    });
  });
});
