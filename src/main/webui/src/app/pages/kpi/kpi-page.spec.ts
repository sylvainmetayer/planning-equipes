// Loading / error / empty / data states of the KPI history screen, plus the
// cell labels the template binds to and the guarded deletion. The component is
// created but never rendered, so this stays a logic test (the project favours
// those over full DOM rendering).
//
// These four states are exactly what a migration to `httpResource()` would
// re-implement, which is why they are pinned here first.

import { provideZonelessChangeDetection, Signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { KpiHistoriqueEntry, PlanningKpi } from '../../core/models';
import { ConfirmService } from '../../shared/confirm-dialog';
import { KpiPage } from './kpi-page';

/** A promise whose settlement the test drives, to observe the in-flight state. */
function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

function kpi(overrides: Partial<PlanningKpi> = {}): PlanningKpi {
  return {
    score: '0hard/-12soft',
    scoreHard: 0,
    scoreMedium: 0,
    scoreSoft: -12,
    postesTotal: 200,
    postesPourvus: 190,
    animateursAffectes: 120,
    standsDistincts: 40,
    creneauxDistincts: 60,
    heuresTotal: 1200,
    heuresMoyenne: 10,
    heuresEcartType: 2.25,
    heuresMin: 4,
    heuresMax: 18,
    heuresIncompletes: false,
    modificationsManuelles: 7,
    tauxModificationsManuelles: 0.035,
    dureeSolveSecondes: 600,
    violationsParContrainte: {},
    ...overrides
  };
}

function entry(overrides: Partial<KpiHistoriqueEntry> = {}): KpiHistoriqueEntry {
  return {
    id: 1,
    editionId: 'festival-2026',
    editionNom: 'Festival 2026',
    kpi: kpi(),
    creeLe: '2026-08-01T10:00:00Z',
    ...overrides
  };
}

/** Reaches the protected members the template binds to. */
type PageInternals = {
  entries: Signal<KpiHistoriqueEntry[]>;
  chargement: Signal<boolean>;
  error: Signal<string>;
  columns: string[];
  recharger: () => Promise<void>;
  supprimer: (entry: KpiHistoriqueEntry) => Promise<void>;
  dateLabel: (entry: KpiHistoriqueEntry) => string;
  editionLabel: (entry: KpiHistoriqueEntry) => string;
  scoreLabel: (entry: KpiHistoriqueEntry) => string;
  couvertureLabel: (entry: KpiHistoriqueEntry) => string;
  fairnessLabel: (entry: KpiHistoriqueEntry) => string;
  modificationsLabel: (entry: KpiHistoriqueEntry) => string;
  dureeLabel: (entry: KpiHistoriqueEntry) => string;
};

describe('KpiPage', () => {
  const api = { get: vi.fn(), delete: vi.fn() };
  const confirm = { ask: vi.fn() };

  beforeEach(() => {
    api.get.mockReset();
    api.delete.mockReset();
    confirm.ask.mockReset();
    api.get.mockResolvedValue([]);
    api.delete.mockResolvedValue(undefined);
    confirm.ask.mockResolvedValue(true);
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: ApiService, useValue: api },
        { provide: ConfirmService, useValue: confirm }
      ]
    });
  });

  function createPage(): PageInternals {
    return TestBed.createComponent(KpiPage).componentInstance as unknown as PageInternals;
  }

  describe('loading state', () => {
    it('is loading while the history is in flight and idle once it lands', async () => {
      const pending = deferred<KpiHistoriqueEntry[]>();
      api.get.mockReturnValue(pending.promise);

      const page = createPage();
      expect(page.chargement()).toBe(true);

      pending.resolve([entry()]);
      await vi.waitFor(() => expect(page.chargement()).toBe(false));
      expect(page.entries()).toHaveLength(1);
    });

    it('reads the history on creation, without waiting for a manual refresh', async () => {
      const page = createPage();
      await vi.waitFor(() => expect(page.chargement()).toBe(false));

      expect(api.get).toHaveBeenCalledExactlyOnceWith('/api/kpi/historique');
    });
  });

  describe('error state', () => {
    it('shows the failure message and stops loading', async () => {
      api.get.mockRejectedValue(new Error('Historique indisponible.'));

      const page = createPage();
      await vi.waitFor(() => expect(page.chargement()).toBe(false));

      expect(page.error()).toContain('Historique indisponible.');
    });

    it('clears the previous message when a new load starts', async () => {
      api.get.mockRejectedValueOnce(new Error('Historique indisponible.'));
      const page = createPage();
      await vi.waitFor(() => expect(page.error()).not.toBe(''));

      api.get.mockResolvedValue([entry()]);
      await page.recharger();

      expect(page.error()).toBe('');
    });

    // The rows already fetched stay on screen: a failed refresh must not look
    // like an emptied history.
    it('keeps the rows already loaded when a refresh fails', async () => {
      api.get.mockResolvedValue([entry()]);
      const page = createPage();
      await vi.waitFor(() => expect(page.entries()).toHaveLength(1));

      api.get.mockRejectedValue(new Error('Historique indisponible.'));
      await page.recharger();

      expect(page.entries()).toHaveLength(1);
      expect(page.error()).toContain('Historique indisponible.');
    });
  });

  describe('empty state', () => {
    it('holds no row and no message when no solve has been recorded yet', async () => {
      const page = createPage();
      await vi.waitFor(() => expect(page.chargement()).toBe(false));

      expect(page.entries()).toEqual([]);
      expect(page.error()).toBe('');
    });
  });

  describe('displayed data', () => {
    it('keeps the server order of the history rows', async () => {
      api.get.mockResolvedValue([entry({ id: 3 }), entry({ id: 1 }), entry({ id: 2 })]);

      const page = createPage();
      await vi.waitFor(() => expect(page.entries()).toHaveLength(3));

      expect(page.entries().map((row) => row.id)).toEqual([3, 1, 2]);
    });

    it('names the edition by its label, and falls back to its id once it is gone', () => {
      const page = createPage();

      expect(page.editionLabel(entry({ editionNom: 'Festival 2026' }))).toBe('Festival 2026');
      expect(page.editionLabel(entry({ editionNom: null, editionId: 'edition-1708' }))).toBe('edition-1708');
    });

    it('renders the coverage as a ratio and its percentage', () => {
      const page = createPage();

      expect(page.couvertureLabel(entry({ kpi: kpi({ postesPourvus: 190, postesTotal: 200 }) }))).toBe(
        '190 / 200 (95.0 %)'
      );
    });

    it('renders a dash rather than dividing by zero when the plan has no seat', () => {
      const page = createPage();

      expect(page.couvertureLabel(entry({ kpi: kpi({ postesPourvus: 0, postesTotal: 0 }) }))).toBe('—');
    });

    it('renders fairness as a standard deviation of hours, and a dash when unmeasured', () => {
      const page = createPage();

      expect(page.fairnessLabel(entry({ kpi: kpi({ heuresEcartType: 2.25 }) }))).toBe('σ 2.3 h');
      expect(page.fairnessLabel(entry({ kpi: kpi({ heuresEcartType: null }) }))).toBe('—');
    });

    it('renders the manual edits with their rate, without it, and as a dash when unmeasured', () => {
      const page = createPage();

      expect(
        page.modificationsLabel(entry({ kpi: kpi({ modificationsManuelles: 7, tauxModificationsManuelles: 0.035 }) }))
      ).toBe('7 (3.5 %)');
      expect(
        page.modificationsLabel(entry({ kpi: kpi({ modificationsManuelles: 7, tauxModificationsManuelles: null }) }))
      ).toBe('7');
      expect(page.modificationsLabel(entry({ kpi: kpi({ modificationsManuelles: null }) }))).toBe('—');
    });

    it('renders the solve duration in seconds, and a dash when unmeasured', () => {
      const page = createPage();

      expect(page.dureeLabel(entry({ kpi: kpi({ dureeSolveSecondes: 600 }) }))).toBe('600 s');
      expect(page.dureeLabel(entry({ kpi: kpi({ dureeSolveSecondes: null }) }))).toBe('—');
    });

    it('renders the score as captured, and a dash when the solve recorded none', () => {
      const page = createPage();

      expect(page.scoreLabel(entry({ kpi: kpi({ score: '0hard/-12soft' }) }))).toBe('0hard/-12soft');
      expect(page.scoreLabel(entry({ kpi: kpi({ score: null }) }))).toBe('—');
    });

    it('renders no date at all for a row without a capture timestamp', () => {
      const page = createPage();

      expect(page.dateLabel(entry({ creeLe: null }))).toBe('');
      expect(page.dateLabel(entry({ creeLe: '2026-08-01T10:00:00Z' }))).not.toBe('');
    });
  });

  describe('deleting a row', () => {
    it('asks for confirmation, and deletes nothing when it is refused', async () => {
      const page = createPage();
      await vi.waitFor(() => expect(page.chargement()).toBe(false));
      confirm.ask.mockResolvedValue(false);

      await page.supprimer(entry({ id: 42 }));

      expect(confirm.ask).toHaveBeenCalledOnce();
      expect(api.delete).not.toHaveBeenCalled();
    });

    it('deletes the confirmed row and reloads the history', async () => {
      const page = createPage();
      await vi.waitFor(() => expect(page.chargement()).toBe(false));
      api.get.mockClear();

      await page.supprimer(entry({ id: 42 }));

      expect(api.delete).toHaveBeenCalledExactlyOnceWith('/api/kpi/historique/42');
      expect(api.get).toHaveBeenCalledExactlyOnceWith('/api/kpi/historique');
    });

    it('warns that the deletion is permanent', async () => {
      const page = createPage();
      await vi.waitFor(() => expect(page.chargement()).toBe(false));

      await page.supprimer(entry({ id: 42 }));

      expect(confirm.ask).toHaveBeenCalledWith(expect.objectContaining({ danger: true }));
    });

    it('shows a failed deletion instead of silently reloading', async () => {
      const page = createPage();
      await vi.waitFor(() => expect(page.chargement()).toBe(false));
      api.delete.mockRejectedValue(new Error('Ligne déjà supprimée.'));
      api.get.mockClear();

      await page.supprimer(entry({ id: 42 }));

      expect(page.error()).toContain('Ligne déjà supprimée.');
      expect(api.get).not.toHaveBeenCalled();
    });
  });
});
