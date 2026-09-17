// Loading / error / empty / data states of the hours screen, plus the table
// logic the template binds to. The component is created but never rendered, so
// this stays a logic test (the project favours those over full DOM rendering).
//
// These four states are exactly what a migration to `httpResource()` would
// re-implement, which is why they are pinned here first.

import { provideZonelessChangeDetection, Signal, WritableSignal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Sort } from '@angular/material/sort';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { PlanningApi } from '../../core/api/planning-api';
import { PlanningStateService } from '../../core/planning-state.service';
import { HeuresAnimateur, HeuresRapport, PlanningEvenement } from '../../core/models';
import { HoursPage } from './hours-page';

/** A promise whose settlement the test drives, to observe the in-flight state. */
function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

function row(
  nom: string,
  heuresParSemaine: Record<string, number>,
  paie: Partial<
    Pick<
      HeuresAnimateur,
      'heuresDimanche' | 'heuresJourFerie' | 'heuresDimancheFerie' | 'heuresNuit'
    >
  > = {},
): HeuresAnimateur {
  return {
    animateurId: nom.toLowerCase(),
    nom,
    heuresParSemaine,
    total: Object.values(heuresParSemaine).reduce((sum, heures) => sum + heures, 0),
    heuresDimanche: 0,
    heuresJourFerie: 0,
    heuresDimancheFerie: 0,
    heuresNuit: 0,
    ...paie,
  };
}

const PLANNING = { postes: [] } as unknown as PlanningEvenement;

/** Reaches the protected members the template binds to. */
type PageInternals = {
  output: Signal<string>;
  busy: Signal<boolean>;
  exportBusy: Signal<boolean>;
  rapport: WritableSignal<HeuresRapport | null>;
  columns: Signal<string[]>;
  sort: WritableSignal<Sort>;
  sortedAnimateurs: Signal<HeuresAnimateur[]>;
  totaux: Signal<{
    animateurCount: number;
    parSemaine: Record<string, number>;
    total: number;
    moyenneParAnimateur: number;
  }>;
  niveauHeures: (heures: number) => string;
  libelleHeures: (heures: number) => string;
  load: () => Promise<void>;
  onExportCsv: () => Promise<void>;
};

describe('HoursPage', () => {
  const planningApi = { hoursReport: vi.fn(), exportHours: vi.fn() };
  const planningState = { require: vi.fn() };

  beforeEach(() => {
    planningApi.hoursReport.mockReset();
    planningApi.exportHours.mockReset();
    planningState.require.mockReset();
    planningState.require.mockResolvedValue(PLANNING);
    planningApi.hoursReport.mockResolvedValue({ semaines: [], animateurs: [] });
    planningApi.exportHours.mockResolvedValue('Export téléchargé.');
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: Router, useValue: { navigate: vi.fn(async () => true) } },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap({}) } },
        },
        { provide: PlanningApi, useValue: planningApi },
        { provide: PlanningStateService, useValue: planningState },
      ],
    });
  });

  function createPage(): PageInternals {
    return TestBed.createComponent(HoursPage).componentInstance as unknown as PageInternals;
  }

  describe('loading state', () => {
    it('is busy while the report is in flight and idle once it lands', async () => {
      const pending = deferred<HeuresRapport>();
      planningApi.hoursReport.mockReturnValue(pending.promise);

      const page = createPage();
      await Promise.resolve();
      expect(page.busy()).toBe(true);

      pending.resolve({ semaines: ['2026-W31'], animateurs: [] });
      await vi.waitFor(() => expect(page.busy()).toBe(false));
    });

    it('clears a previous message when a new load starts', async () => {
      planningApi.hoursReport.mockRejectedValueOnce(new Error('Serveur indisponible.'));
      const page = createPage();
      await vi.waitFor(() => expect(page.output()).not.toBe(''));

      planningApi.hoursReport.mockResolvedValue({ semaines: [], animateurs: [] });
      await page.load();

      expect(page.output()).toBe('');
    });

    it('loads the report on creation, without waiting for a manual refresh', async () => {
      const page = createPage();
      await vi.waitFor(() => expect(page.busy()).toBe(false));

      expect(planningState.require).toHaveBeenCalledTimes(1);
      expect(planningApi.hoursReport).toHaveBeenCalledWith(PLANNING);
      expect(page.rapport()).not.toBeNull();
    });
  });

  describe('error state', () => {
    it('drops the stale report and shows the failure message', async () => {
      const page = createPage();
      await vi.waitFor(() => expect(page.rapport()).not.toBeNull());

      planningApi.hoursReport.mockRejectedValue(new Error('Serveur indisponible.'));
      await page.load();

      expect(page.rapport()).toBeNull();
      expect(page.output()).toContain('Serveur indisponible.');
      expect(page.busy()).toBe(false);
    });

    it('reports a missing planning raised upstream rather than calling the endpoint', async () => {
      planningState.require.mockRejectedValue(
        new Error('Aucun planning disponible pour le moment.'),
      );

      const page = createPage();
      await vi.waitFor(() => expect(page.busy()).toBe(false));

      expect(planningApi.hoursReport).not.toHaveBeenCalled();
      expect(page.output()).toContain('Aucun planning disponible');
    });
  });

  describe('empty state', () => {
    it('keeps only the fixed columns when no report is loaded yet', () => {
      const page = createPage();

      expect(page.columns()).toEqual(['animateur', 'total', 'dimanche', 'jourFerie', 'nuit']);
      expect(page.sortedAnimateurs()).toEqual([]);
    });

    it('reports zero totals and no division by zero on an empty roster', async () => {
      const page = createPage();
      await vi.waitFor(() => expect(page.rapport()).not.toBeNull());

      expect(page.totaux()).toEqual({
        animateurCount: 0,
        parSemaine: {},
        total: 0,
        moyenneParAnimateur: 0,
        dimanche: 0,
        jourFerie: 0,
        dimancheFerie: 0,
        nuit: 0,
      });
    });
  });

  describe('displayed data', () => {
    beforeEach(() => {
      planningApi.hoursReport.mockResolvedValue({
        semaines: ['2026-W31', '2026-W32'],
        animateurs: [
          row('Zoé', { '2026-W31': 10, '2026-W32': 20 }),
          row('Alice', { '2026-W31': 40 }),
        ],
      });
    });

    it('frames the weeks of the report between the animateur and total columns', async () => {
      const page = createPage();
      await vi.waitFor(() => expect(page.rapport()).not.toBeNull());

      expect(page.columns()).toEqual([
        'animateur',
        '2026-W31',
        '2026-W32',
        'total',
        'dimanche',
        'jourFerie',
        'nuit',
      ]);
    });

    it('keeps the server order while no sort is applied', async () => {
      const page = createPage();
      await vi.waitFor(() => expect(page.rapport()).not.toBeNull());

      expect(page.sortedAnimateurs().map((animateur) => animateur.nom)).toEqual(['Zoé', 'Alice']);
    });

    it('sorts by name, and reverses on descending', async () => {
      const page = createPage();
      await vi.waitFor(() => expect(page.rapport()).not.toBeNull());

      page.sort.set({ active: 'animateur', direction: 'asc' });
      expect(page.sortedAnimateurs().map((animateur) => animateur.nom)).toEqual(['Alice', 'Zoé']);

      page.sort.set({ active: 'animateur', direction: 'desc' });
      expect(page.sortedAnimateurs().map((animateur) => animateur.nom)).toEqual(['Zoé', 'Alice']);
    });

    it('sorts by total hours numerically, not as text', async () => {
      planningApi.hoursReport.mockResolvedValue({
        semaines: ['2026-W31'],
        animateurs: [row('Neuf', { '2026-W31': 9 }), row('Dix', { '2026-W31': 10 })],
      });
      const page = createPage();
      await vi.waitFor(() => expect(page.rapport()).not.toBeNull());

      page.sort.set({ active: 'total', direction: 'asc' });
      expect(page.sortedAnimateurs().map((animateur) => animateur.nom)).toEqual(['Neuf', 'Dix']);
    });

    it('treats a week the animateur did not work as zero when sorting on it', async () => {
      const page = createPage();
      await vi.waitFor(() => expect(page.rapport()).not.toBeNull());

      page.sort.set({ active: '2026-W32', direction: 'asc' });
      expect(page.sortedAnimateurs().map((animateur) => animateur.nom)).toEqual(['Alice', 'Zoé']);
    });

    it('leaves the loaded report untouched while sorting a copy of it', async () => {
      const page = createPage();
      await vi.waitFor(() => expect(page.rapport()).not.toBeNull());

      page.sort.set({ active: 'animateur', direction: 'asc' });
      page.sortedAnimateurs();

      expect(page.rapport()!.animateurs.map((animateur) => animateur.nom)).toEqual([
        'Zoé',
        'Alice',
      ]);
    });

    it('sums the event-wide totals week by week and averages them per animateur', async () => {
      const page = createPage();
      await vi.waitFor(() => expect(page.rapport()).not.toBeNull());

      expect(page.totaux()).toEqual({
        animateurCount: 2,
        parSemaine: { '2026-W31': 50, '2026-W32': 20 },
        total: 70,
        moyenneParAnimateur: 35,
        dimanche: 0,
        jourFerie: 0,
        dimancheFerie: 0,
        nuit: 0,
      });
    });

    /**
     * Issue #597: the payroll columns sum like the weeks do, and sort on their
     * own field rather than on a week that does not exist.
     */
    it('sums and sorts the payroll columns of the roster', async () => {
      planningApi.hoursReport.mockResolvedValue({
        semaines: ['2026-W31'],
        animateurs: [
          row(
            'Zoé',
            { '2026-W31': 10 },
            { heuresDimanche: 4, heuresJourFerie: 4, heuresDimancheFerie: 4 },
          ),
          row('Alice', { '2026-W31': 20 }, { heuresNuit: 3 }),
        ],
      });
      const page = createPage();
      await vi.waitFor(() => expect(page.rapport()).not.toBeNull());

      expect(page.totaux()).toMatchObject({
        dimanche: 4,
        jourFerie: 4,
        dimancheFerie: 4,
        nuit: 3,
      });

      page.sort.set({ active: 'nuit', direction: 'desc' });
      expect(page.sortedAnimateurs().map((animateur) => animateur.nom)).toEqual(['Alice', 'Zoé']);
    });
  });

  describe('weekly ceilings', () => {
    it('flags nothing at or below the minor ceiling', () => {
      const page = createPage();

      expect(page.niveauHeures(35)).toBe('normal');
      expect(page.libelleHeures(35)).toBe('');
    });

    it('asks the reader to check a week above the minor ceiling', () => {
      const page = createPage();

      expect(page.niveauHeures(35.5)).toBe('verifier');
      expect(page.libelleHeures(36)).toContain('35');
    });

    it('calls a week above the adult ceiling a breach, and the ceiling itself only a check', () => {
      const page = createPage();

      expect(page.niveauHeures(48)).toBe('verifier');
      expect(page.niveauHeures(48.5)).toBe('depassement');
      expect(page.libelleHeures(52)).toContain('48');
    });
  });

  describe('CSV export', () => {
    it('announces the export, then shows the download outcome', async () => {
      const page = createPage();
      await vi.waitFor(() => expect(page.busy()).toBe(false));
      const pending = deferred<string>();
      planningApi.exportHours.mockReturnValue(pending.promise);

      const running = page.onExportCsv();
      await vi.waitFor(() => expect(planningApi.exportHours).toHaveBeenCalled());
      expect(page.exportBusy()).toBe(true);
      expect(page.output()).toContain('export CSV');

      pending.resolve('Fichier téléchargé.');
      await running;
      expect(page.exportBusy()).toBe(false);
      expect(page.output()).toBe('Fichier téléchargé.');
      expect(planningApi.exportHours).toHaveBeenCalledWith(PLANNING);
    });

    it('surfaces an export failure without clearing the loaded report', async () => {
      const page = createPage();
      await vi.waitFor(() => expect(page.rapport()).not.toBeNull());
      planningApi.exportHours.mockRejectedValue(new Error('Export refusé.'));

      await page.onExportCsv();

      expect(page.output()).toContain('Export refusé.');
      expect(page.exportBusy()).toBe(false);
      expect(page.rapport()).not.toBeNull();
    });
  });
});
