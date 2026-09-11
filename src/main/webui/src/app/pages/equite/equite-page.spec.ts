// Loading / error / empty / data states of the Équité screen, and the CSV
// export. `equite.spec.ts` covers the pure sorting and filtering; what is
// pinned here is that the report is a resource — a failed refresh keeps the
// last table on screen behind the failure sentence — and that the template
// binds the table logic the way the report shapes it.

import { Location } from '@angular/common';
import { provideZonelessChangeDetection, Signal, WritableSignal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Sort } from '@angular/material/sort';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { PlanningApi } from '../../core/api/planning-api';
import { LigneEquite, RapportEquite } from '../../core/models';
import { EquitePage } from './equite-page';

function ligne(partial: Partial<LigneEquite> & { animateurId: string; nom: string }): LigneEquite {
  return {
    heuresTotal: 0,
    heuresParSemaine: {},
    heuresSoiree: 0,
    heuresWeekEnd: 0,
    heuresJourFerie: 0,
    postes: 0,
    postesPenibles: 0,
    standsDistincts: 0,
    typologiesDistinctes: 0,
    emplacementsDistinctsParJourMax: 0,
    tauxSouhaits: 0,
    tauxAppreciation: 0,
    joursTravailles: 0,
    joursRepos: 0,
    plusLongueSerie: 0,
    ...partial,
  };
}

function rapport(partial: Partial<RapportEquite> = {}): RapportEquite {
  return {
    heureDebutSoiree: '20:00:00',
    semaines: ['2026-W28'],
    lignes: [
      ligne({
        animateurId: 'bob',
        nom: 'Bob',
        heuresTotal: 30,
        heuresParSemaine: { '2026-W28': 30 },
      }),
      ligne({
        animateurId: 'alice',
        nom: 'Alice',
        heuresTotal: 12,
        heuresParSemaine: { '2026-W28': 12 },
      }),
    ],
    syntheses: { heuresTotal: { mediane: 21, min: 12, max: 30, ecartType: 9 } },
    colonnesSolveur: [{ colonne: 'postes', contrainte: 'equilibrerCharge', active: true }],
    ...partial,
  };
}

function deferred<T>(): {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (error: Error) => void;
} {
  let resolve: (value: T) => void = () => undefined;
  let reject: (error: Error) => void = () => undefined;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

/** Reaches the protected members the template binds to. */
type PageInternals = {
  rapport: Signal<RapportEquite | null>;
  chargement: Signal<boolean>;
  erreur: Signal<string>;
  output: Signal<string>;
  exportBusy: Signal<boolean>;
  sort: WritableSignal<Sort>;
  filtre: WritableSignal<string>;
  colonnes: Signal<string[]>;
  lignesAffichees: Signal<LigneEquite[]>;
  heureSoiree: Signal<string>;
  viewChanged: Signal<boolean>;
  gap: (ligne: LigneEquite, colonne: string) => number | null;
  tooltipColonne: (colonne: string) => string;
  measuredBySolver: (colonne: string) => boolean;
  recharger: () => void;
  resetView: () => void;
  onExportCsv: () => Promise<void>;
};

describe('EquitePage', () => {
  const planningApi = { equityReport: vi.fn(), exportEquity: vi.fn() };
  let fixture: ComponentFixture<EquitePage>;

  beforeEach(() => {
    planningApi.equityReport.mockReset();
    planningApi.exportEquity.mockReset();
    planningApi.equityReport.mockResolvedValue(rapport());
    planningApi.exportEquity.mockResolvedValue('Fichier téléchargé.');
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: PlanningApi, useValue: planningApi },
        { provide: Location, useValue: { path: () => '/equite', replaceState: vi.fn() } },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap({}) } },
        },
      ],
    });
  });

  function createPage(): PageInternals {
    fixture = TestBed.createComponent(EquitePage);
    return fixture.componentInstance as unknown as PageInternals;
  }

  function text(): string {
    fixture.detectChanges();
    return (fixture.nativeElement as HTMLElement).textContent!.replace(/\s+/g, ' ');
  }

  describe('loading state', () => {
    it('is loading while the report is in flight and shows it once it lands', async () => {
      const pending = deferred<RapportEquite>();
      planningApi.equityReport.mockReturnValue(pending.promise);
      const page = createPage();

      expect(page.chargement()).toBe(true);
      expect(page.rapport()).toBeNull();

      pending.resolve(rapport());
      await vi.waitFor(() => expect(page.chargement()).toBe(false));
      expect(page.rapport()).not.toBeNull();
      expect(planningApi.equityReport).toHaveBeenCalledOnce();
      expect(text()).toContain('Bob');
      expect(text()).toContain('20:00');
    });

    it('keeps the table on screen while a refresh is in flight', async () => {
      planningApi.equityReport.mockResolvedValueOnce(rapport());
      const page = createPage();
      await vi.waitFor(() => expect(page.rapport()).not.toBeNull());
      const pending = deferred<RapportEquite>();
      planningApi.equityReport.mockReturnValue(pending.promise);

      page.recharger();
      await vi.waitFor(() => expect(page.chargement()).toBe(true));

      expect(page.rapport()).not.toBeNull();
      expect(text()).toContain('Bob');

      pending.resolve(rapport({ heureDebutSoiree: '22:00:00' }));
      await vi.waitFor(() => expect(page.chargement()).toBe(false));
      expect(page.heureSoiree()).toBe('22:00');
    });
  });

  describe('error state', () => {
    it('shows the failure as a sentence, not a blank card', async () => {
      planningApi.equityReport.mockRejectedValue(new Error('Serveur indisponible.'));
      const page = createPage();

      await vi.waitFor(() => expect(page.erreur()).toContain('Serveur indisponible.'));
      expect(page.rapport()).toBeNull();
      expect(text()).toContain('Serveur indisponible.');
    });

    it('shows the failure of a refresh in place of the report, keeping the last table', async () => {
      planningApi.equityReport.mockResolvedValueOnce(rapport());
      const page = createPage();
      await vi.waitFor(() => expect(page.rapport()).not.toBeNull());
      planningApi.equityReport.mockRejectedValue(new Error('Serveur indisponible.'));

      page.recharger();

      await vi.waitFor(() => expect(page.erreur()).toContain('Serveur indisponible.'));
      expect(page.rapport()).not.toBeNull();
      expect(page.lignesAffichees()).toHaveLength(2);
    });
  });

  describe('empty state', () => {
    it('says the saved plan is empty rather than showing an empty table', async () => {
      planningApi.equityReport.mockResolvedValue(
        rapport({ lignes: [], semaines: [], syntheses: {} }),
      );
      const page = createPage();
      await vi.waitFor(() => expect(page.rapport()).not.toBeNull());

      expect(page.lignesAffichees()).toEqual([]);
      expect(text()).toContain('Aucun animateur affecté');
      expect((fixture.nativeElement as HTMLElement).querySelector('table')).toBeNull();
    });
  });

  describe('displayed data', () => {
    it('frames the weeks of the report between the person, the total and the other columns', async () => {
      const page = createPage();
      await vi.waitFor(() => expect(page.rapport()).not.toBeNull());

      expect(page.colonnes().slice(0, 3)).toEqual(['animateur', 'heuresTotal', '2026-W28']);
    });

    it('keeps the server order while no sort is applied, then sorts a copy', async () => {
      const page = createPage();
      await vi.waitFor(() => expect(page.rapport()).not.toBeNull());
      expect(page.lignesAffichees().map((row) => row.nom)).toEqual(['Bob', 'Alice']);

      page.sort.set({ active: 'heuresTotal', direction: 'asc' });
      expect(page.lignesAffichees().map((row) => row.nom)).toEqual(['Alice', 'Bob']);
      expect(page.rapport()!.lignes.map((row) => row.nom)).toEqual(['Bob', 'Alice']);
      expect(page.viewChanged()).toBe(true);
    });

    it('narrows the rows with the quick filter, and one action resets both filter and sort', async () => {
      const page = createPage();
      await vi.waitFor(() => expect(page.rapport()).not.toBeNull());

      page.filtre.set('ali');
      page.sort.set({ active: 'animateur', direction: 'desc' });
      expect(page.lignesAffichees().map((row) => row.nom)).toEqual(['Alice']);

      page.resetView();
      expect(page.filtre()).toBe('');
      expect(page.sort()).toEqual({ active: '', direction: '' });
      expect(page.viewChanged()).toBe(false);
      expect(page.lignesAffichees()).toHaveLength(2);
    });

    it('computes the distance to the median from the synthesis, and none without one', async () => {
      const page = createPage();
      await vi.waitFor(() => expect(page.rapport()).not.toBeNull());
      const [bob, alice] = page.lignesAffichees();

      expect(page.gap(bob, 'heuresTotal')).toBe(9);
      expect(page.gap(alice, 'heuresTotal')).toBe(-9);
      expect(page.gap(bob, 'heuresSoiree')).toBeNull();
    });

    it('says which columns the solver measures', async () => {
      const page = createPage();
      await vi.waitFor(() => expect(page.rapport()).not.toBeNull());

      expect(page.measuredBySolver('postes')).toBe(true);
      expect(page.measuredBySolver('heuresSoiree')).toBe(false);
      expect(page.tooltipColonne('postes')).toContain('equilibrerCharge');
      expect(page.tooltipColonne('heuresSoiree')).toContain('non prise en compte');
    });

    it('links every person to their timeline', async () => {
      const page = createPage();
      await vi.waitFor(() => expect(page.rapport()).not.toBeNull());
      fixture.detectChanges();

      const liens = Array.from(
        (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLAnchorElement>(
          'a.equite-lien-timeline',
        ),
      );
      expect(liens.map((lien) => lien.getAttribute('href'))).toEqual([
        '/timeline?animateur=bob',
        '/timeline?animateur=alice',
      ]);
    });
  });

  describe('CSV export', () => {
    it('announces the export, then shows the download outcome', async () => {
      const page = createPage();
      await vi.waitFor(() => expect(page.rapport()).not.toBeNull());
      const pending = deferred<string>();
      planningApi.exportEquity.mockReturnValue(pending.promise);

      const running = page.onExportCsv();
      await vi.waitFor(() => expect(planningApi.exportEquity).toHaveBeenCalled());
      expect(page.exportBusy()).toBe(true);
      expect(page.output()).toContain('export CSV');

      pending.resolve('Fichier téléchargé.');
      await running;
      expect(page.exportBusy()).toBe(false);
      expect(page.output()).toBe('Fichier téléchargé.');
    });

    it('surfaces an export failure without touching the loaded report', async () => {
      const page = createPage();
      await vi.waitFor(() => expect(page.rapport()).not.toBeNull());
      planningApi.exportEquity.mockRejectedValue(new Error('Export refusé.'));

      await page.onExportCsv();

      expect(page.output()).toContain('Export refusé.');
      expect(page.exportBusy()).toBe(false);
      expect(page.rapport()).not.toBeNull();
    });
  });
});
