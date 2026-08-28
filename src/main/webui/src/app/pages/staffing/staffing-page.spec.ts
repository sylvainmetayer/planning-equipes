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
import { BorneStaffing, CompetenceStaffing, JourStaffing, StaffingSummary, TypologieStaffing } from '../../core/models';
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

function typologie(overrides: Partial<TypologieStaffing> = {}): TypologieStaffing {
  return {
    typologie: 'ESCAPE',
    label: 'Escape game',
    ninja: false,
    sieges: 12,
    heures: 60,
    nombreSemaines: 1,
    picSimultane: 4,
    picAvecPause: 6,
    chargeTotal: 3,
    minimumTotal: 6,
    borneRetenue: 'PIC_AVEC_PAUSE',
    specialistes: 6,
    manque: 0,
    ...overrides
  };
}

function competence(overrides: Partial<CompetenceStaffing> = {}): CompetenceStaffing {
  return {
    parTypologie: [typologie()],
    polyvalents: 3,
    siegesNonAttribues: 0,
    manqueTotal: 0,
    animateursTotal: 40,
    typologieNinjaDefinie: true,
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
    parCompetence: competence(),
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
  competenceColumns: string[];
  competence: Signal<CompetenceStaffing | null>;
  estGoulot: (ligne: TypologieStaffing) => boolean;
  reserveLabel: Signal<string>;
  siegesNonAttribuesLabel: Signal<string>;
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

  describe('bottleneck per game category', () => {
    /** Loads a page whose summary carries the given breakdown, and waits for it. */
    async function pageWith(overrides: Partial<CompetenceStaffing>): Promise<PageInternals> {
      api.get.mockResolvedValue(summary({ parCompetence: competence(overrides) }));
      const page = createPage();
      await vi.waitFor(() => expect(page.competence()).not.toBeNull());
      return page;
    }

    // The decision this pins: one payload, one round trip. The breakdown is
    // the same computation on the same seats, so it travels with them rather
    // than through an endpoint that would rebuild the whole problem.
    it('reads the breakdown from the same payload, without a second request', async () => {
      const page = await pageWith({ polyvalents: 7 });

      expect(api.get).toHaveBeenCalledExactlyOnceWith('/api/staffing');
      expect(page.competence()?.polyvalents).toBe(7);
    });

    it('has no breakdown to show while nothing is loaded', () => {
      api.get.mockReturnValue(deferred<StaffingSummary>().promise);

      const page = createPage();

      expect(page.competence()).toBeNull();
      expect(page.reserveLabel()).toBe('');
    });

    it('highlights the rows the server reported a shortfall on, and only those', () => {
      const page = createPage();

      expect(page.estGoulot(typologie({ manque: 4 }))).toBe(true);
      expect(page.estGoulot(typologie({ manque: 0 }))).toBe(false);
    });

    it('says nothing can be detected yet while no animateur is known', async () => {
      const page = await pageWith({ animateursTotal: 0, polyvalents: 0, manqueTotal: 0 });

      expect(page.reserveLabel()).toContain('Aucun animateur');
    });

    it('announces the absence of a bottleneck without claiming a reserve there is none of', async () => {
      const withReserve = await pageWith({ manqueTotal: 0, polyvalents: 4 });
      expect(withReserve.reserveLabel()).toContain('Aucun goulot');
      expect(withReserve.reserveLabel()).toContain('4');

      const withoutReserve = await pageWith({ manqueTotal: 0, polyvalents: 0 });
      expect(withoutReserve.reserveLabel()).toContain('Aucun goulot');
      expect(withoutReserve.reserveLabel()).not.toContain('0 polyvalents');
    });

    it('says a reserve of zero is no notion here when no typologie is marked polyvalente', async () => {
      const page = await pageWith({ manqueTotal: 3, polyvalents: 0, typologieNinjaDefinie: false });

      expect(page.reserveLabel()).toContain('Aucune typologie');
      expect(page.reserveLabel()).not.toContain('plus mince');
    });

    it('reads the shortfall against the polyvalent reserve, which absorbs it or does not', async () => {
      const absorbable = await pageWith({ manqueTotal: 2, polyvalents: 5 });
      expect(absorbable.reserveLabel()).toContain('peuvent y répondre');
      expect(absorbable.reserveLabel()).not.toContain('plus mince');

      const shortfall = await pageWith({ manqueTotal: 8, polyvalents: 5 });
      expect(shortfall.reserveLabel()).toContain('8');
      expect(shortfall.reserveLabel()).toContain('plus mince');
    });

    it('reports the seats no single typologie can claim', async () => {
      const page = await pageWith({ siegesNonAttribues: 14 });

      expect(page.siegesNonAttribuesLabel()).toContain('14');
    });
  });
});
