// The most destructive operation of the application: importing a scenario
// replaces stands and animateurs, wipes the solved planning and its locks.
// Before this spec it was covered by exactly one Playwright test, kept out of
// CI — and its ten-step choreography existed three times, in three copies, in
// the page.

import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection, signal } from '@angular/core';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from './api.service';
import { EditionStore } from './edition.store';
import { NotificationService } from './notification.service';
import { PlanSnapshotStore } from './plan-snapshot.store';
import { PlanningResolutionStore } from './planning-resolution.store';
import { PlanningStateService } from './planning-state.service';
import { ProblemesStore } from './problemes.store';
import { ReferenceDataStore } from './reference-data.store';
import { ScenarioImportService } from './scenario-import.service';
import { SolverSettingsService } from './solver-settings.service';
import { ConfirmService } from '../shared/confirm-dialog';
import type { CibleImport, Edition, ImpactImport, ImportScenarioResult } from './models';

const EDITION_COURANTE = { id: 'ed-2026', nom: 'Année 2026' } as Edition;

function target(overrides: Partial<CibleImport> = {}): CibleImport {
  return {
    editionId: null,
    existe: false,
    editionNomFichier: null,
    editionNomExistant: null,
    ...overrides,
  };
}

function impact(overrides: Partial<ImpactImport> = {}): ImpactImport {
  return { planningResolu: false, ...overrides } as ImpactImport;
}

describe('ScenarioImportService', () => {
  let service: ScenarioImportService;
  let api: {
    get: ReturnType<typeof vi.fn>;
    getDansEdition: ReturnType<typeof vi.fn>;
    post: ReturnType<typeof vi.fn>;
    postRaw: ReturnType<typeof vi.fn>;
  };
  let confirm: { ask: ReturnType<typeof vi.fn> };
  let snapshots: { capturer: ReturnType<typeof vi.fn> };
  let notifications: { notify: ReturnType<typeof vi.fn> };
  let editions: {
    courant: ReturnType<typeof signal<Edition | null>>;
    basculer: ReturnType<typeof vi.fn>;
    reload: ReturnType<typeof vi.fn>;
  };
  let refreshed: string[];

  /** What the import endpoint answers; `null` means "no edition routing". */
  let importResult: ImportScenarioResult | null;
  /** What the impact endpoint answers, or an error to throw. */
  let impactResponse: ImpactImport | Error;

  beforeEach(() => {
    refreshed = [];
    importResult = null;
    impactResponse = impact();
    api = {
      get: vi.fn(async (url: string) => {
        if (url.includes('impact-import')) {
          if (impactResponse instanceof Error) {
            throw impactResponse;
          }
          return impactResponse;
        }
        if (url.includes('cible-scenario')) {
          return target();
        }
        throw new Error(`Unexpected GET ${url}`);
      }),
      getDansEdition: vi.fn(async () => {
        if (impactResponse instanceof Error) {
          throw impactResponse;
        }
        return impactResponse;
      }),
      post: vi.fn(async () => importResult),
      postRaw: vi.fn(async (url: string) => (url.includes('cible') ? target() : importResult)),
    };
    confirm = { ask: vi.fn(async () => true) };
    snapshots = { capturer: vi.fn(async () => undefined) };
    notifications = { notify: vi.fn() };
    editions = {
      courant: signal<Edition | null>(EDITION_COURANTE),
      basculer: vi.fn(),
      reload: vi.fn(async () => refreshed.push('editions')),
    };

    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        ScenarioImportService,
        { provide: ApiService, useValue: api },
        { provide: ConfirmService, useValue: confirm },
        { provide: PlanSnapshotStore, useValue: snapshots },
        { provide: NotificationService, useValue: notifications },
        { provide: EditionStore, useValue: editions },
        {
          provide: PlanningStateService,
          useValue: { set: vi.fn(() => refreshed.push('planningState')) },
        },
        {
          provide: ReferenceDataStore,
          useValue: { reload: vi.fn(async () => refreshed.push('referenceData')) },
        },
        {
          provide: PlanningResolutionStore,
          useValue: { reload: vi.fn(async () => refreshed.push('resolution')) },
        },
        {
          provide: SolverSettingsService,
          useValue: { refresh: vi.fn(async () => refreshed.push('solverSettings')) },
        },
        {
          provide: ProblemesStore,
          useValue: { reloadFeasibility: vi.fn(async () => refreshed.push('problemes')) },
        },
      ],
    });
    service = TestBed.inject(ScenarioImportService);
  });

  describe('named scenario', () => {
    it('imports the named scenario server-side, without the file ever reaching the browser', async () => {
      const outcome = await service.importer({ kind: 'name', name: 'edition-1708' });

      expect(outcome.status).toBe('imported');
      expect(api.post).toHaveBeenCalledTimes(1);
      expect(api.post.mock.calls[0][0]).toBe(
        '/api/reference-data/import-scenario?name=edition-1708',
      );
    });

    it('falls back to the default sample when no scenario is named', async () => {
      await service.importer({ kind: 'name', name: null });

      expect(api.post.mock.calls[0][0]).toBe('/api/reference-data/import-scenario');
    });

    it('percent-encodes a scenario name rather than pasting it into the URL', async () => {
      await service.importer({ kind: 'name', name: 'edition 1708/canicule' });

      expect(api.post.mock.calls[0][0]).toContain('edition%201708%2Fcanicule');
    });
  });

  describe('scenario file', () => {
    it('posts the YAML as-is and asks the server where it is routed', async () => {
      await service.importer({
        kind: 'file',
        fileName: 'festival.yaml',
        content: 'edition:\n  id: ed-2027\n',
      });

      const urls = api.postRaw.mock.calls.map(([url]) => url);
      expect(urls[0]).toBe('/api/reference-data/cible-scenario-fichier');
      expect(urls[1]).toBe('/api/reference-data/import-scenario-fichier');
      expect(api.postRaw.mock.calls[1][1]).toBe('edition:\n  id: ed-2027\n');
      expect(api.postRaw.mock.calls[1][2]).toBe('application/x-yaml');
    });

    it('reports an unreadable file as such, and never imports it', async () => {
      api.postRaw = vi.fn(async () => {
        throw new Error('mapping values are not allowed here');
      });

      await expect(
        service.importer({ kind: 'file', fileName: 'festival.yaml', content: ': broken' }),
      ).rejects.toThrow('mapping values are not allowed here');
      expect(notifications.notify).toHaveBeenCalledWith(
        expect.objectContaining({ variant: 'error' }),
      );
    });
  });

  describe('confirmation', () => {
    it('imports nothing at all when the confirmation is declined', async () => {
      confirm.ask = vi.fn(async () => false);

      const outcome = await service.importer({ kind: 'name', name: 'edition-1708' });

      expect(outcome.status).toBe('cancelled');
      expect(api.post).not.toHaveBeenCalled();
      expect(snapshots.capturer).not.toHaveBeenCalled();
      expect(refreshed).toEqual([]);
    });

    it('names the current edition in the confirmation when the file routes nowhere else', async () => {
      await service.importer({ kind: 'name', name: 'edition-1708' });

      expect(confirm.ask.mock.calls[0][0].message).toContain('Année 2026');
      expect(confirm.ask.mock.calls[0][0].danger).toBe(true);
    });

    it('announces an edition the file names but that has no id yet as one to create', async () => {
      // The section gives only a name: the application draws the id on import.
      const namedOnly = target({ editionNomFichier: 'Édition importée' });
      api.get = vi.fn(async (url: string) =>
        url.includes('cible-scenario') ? namedOnly : impact(),
      );

      await service.importer({ kind: 'name', name: 'edition-1708' });

      const message = confirm.ask.mock.calls[0][0].message as string;
      expect(message).toContain('« Édition importée » : elle sera CRÉÉE');
      expect(snapshots.capturer).not.toHaveBeenCalled();
    });

    it('still asks for confirmation when the impact count fails — counting is comfort, not safety', async () => {
      impactResponse = new Error('500');

      const outcome = await service.importer({ kind: 'name', name: 'edition-1708' });

      expect(confirm.ask).toHaveBeenCalledTimes(1);
      expect(outcome.status).toBe('imported');
    });
  });

  describe('automatic snapshot before a destructive import', () => {
    it('captures the plan when a solved planning of the current edition is about to be replaced', async () => {
      impactResponse = impact({ planningResolu: true });

      await service.importer({ kind: 'name', name: 'edition-1708' });

      expect(snapshots.capturer).toHaveBeenCalledTimes(1);
      expect(api.post).toHaveBeenCalledTimes(1);
    });

    it('does not capture anything when there is no solved planning to lose', async () => {
      impactResponse = impact({ planningResolu: false });

      await service.importer({ kind: 'name', name: 'edition-1708' });

      expect(snapshots.capturer).not.toHaveBeenCalled();
    });

    it('does not capture the current plan when the import writes into another edition', async () => {
      impactResponse = impact({ planningResolu: true });
      api.postRaw = vi.fn(async (url: string) =>
        url.includes('cible')
          ? target({ editionId: 'ed-2027', existe: true, editionNomExistant: 'Année 2027' })
          : importResult,
      );

      await service.importer({ kind: 'file', fileName: 'f.yaml', content: 'x' });

      expect(snapshots.capturer).not.toHaveBeenCalled();
    });

    it('imports anyway when the snapshot fails, but says so instead of staying silent', async () => {
      impactResponse = impact({ planningResolu: true });
      snapshots.capturer = vi.fn(async () => {
        throw new Error('disque plein');
      });

      const outcome = await service.importer({ kind: 'name', name: 'edition-1708' });

      expect(outcome.status).toBe('imported');
      expect(notifications.notify).toHaveBeenCalledWith(
        expect.objectContaining({ variant: 'error', message: 'disque plein' }),
      );
    });
  });

  describe('after the import', () => {
    it('reloads every store the import invalidated', async () => {
      await service.importer({ kind: 'name', name: 'edition-1708' });

      expect(refreshed).toEqual(
        expect.arrayContaining([
          'planningState',
          'referenceData',
          'resolution',
          'solverSettings',
          'problemes',
          'editions',
        ]),
      );
    });

    it('offers to switch when the file routed the import to another edition, and switches on yes', async () => {
      importResult = {
        editionId: 'ed-2027',
        editionNom: 'Année 2027',
        editionCreee: true,
      } as ImportScenarioResult;

      await service.importer({ kind: 'name', name: 'edition-1708' });

      // Two dialogs: the destructive confirmation, then the switch offer.
      expect(confirm.ask).toHaveBeenCalledTimes(2);
      expect(editions.basculer).toHaveBeenCalledWith(expect.objectContaining({ id: 'ed-2027' }));
    });

    it('stays on the current edition when the switch is declined', async () => {
      importResult = {
        editionId: 'ed-2027',
        editionNom: 'Année 2027',
        editionCreee: true,
      } as ImportScenarioResult;
      confirm.ask = vi.fn().mockResolvedValueOnce(true).mockResolvedValueOnce(false);

      await service.importer({ kind: 'name', name: 'edition-1708' });

      expect(editions.basculer).not.toHaveBeenCalled();
    });

    it('never offers to switch to the edition already being shown', async () => {
      importResult = {
        editionId: 'ed-2026',
        editionNom: 'Année 2026',
        editionCreee: false,
      } as ImportScenarioResult;

      await service.importer({ kind: 'name', name: 'edition-1708' });

      expect(confirm.ask).toHaveBeenCalledTimes(1);
      expect(editions.basculer).not.toHaveBeenCalled();
    });

    it('hands the raw server result back, so the page can build its own recap', async () => {
      importResult = { editionId: 'ed-2026', editionNom: 'Année 2026' } as ImportScenarioResult;

      const outcome = await service.importer({ kind: 'name', name: 'edition-1708' });

      expect(outcome.result).toEqual(importResult);
    });
  });
});
