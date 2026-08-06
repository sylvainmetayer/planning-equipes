import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from './api.service';
import { PlanningResolutionStore } from './planning-resolution.store';
import type { GroupeCreneau, PlanningResolution } from './models';

function groupe(id: string, actif: boolean): GroupeCreneau {
  return { id, nom: id.toLowerCase(), actif };
}

function resolution(overrides: Partial<PlanningResolution> = {}): PlanningResolution {
  return {
    solved: true,
    groupeCreneauId: 'DEFAUT',
    groupeCreneauNom: 'defaut',
    resoluLe: '2026-07-01T10:00:00Z',
    derniereModificationDonnees: null,
    ...overrides
  };
}

/** Fake ApiService routing GETs by URL. */
class FakeApi {
  responses: Record<string, unknown> = {};
  get = vi.fn(async (url: string) => {
    if (!(url in this.responses)) {
      throw new Error(`Unexpected GET ${url}`);
    }
    return this.responses[url];
  });
}

describe('PlanningResolutionStore', () => {
  let store: PlanningResolutionStore;
  let api: FakeApi;

  beforeEach(() => {
    api = new FakeApi();
    TestBed.configureTestingModule({
      providers: [PlanningResolutionStore, { provide: ApiService, useValue: api }]
    });
    store = TestBed.inject(PlanningResolutionStore);
  });

  async function load(current: PlanningResolution, groupes: GroupeCreneau[]): Promise<void> {
    api.responses = {
      '/api/planning/persisted/resolution': current,
      '/api/groupes-creneaux': groupes
    };
    await store.reload();
  }

  describe('activeGroupe', () => {
    it('is null while nothing is loaded', () => {
      expect(store.activeGroupe()).toBeNull();
    });

    it('picks the single group flagged active', async () => {
      await load(resolution(), [groupe('DEFAUT', false), groupe('SECOURS', true)]);
      expect(store.activeGroupe()?.id).toBe('SECOURS');
    });
  });

  describe('stale', () => {
    it('is false when the solved group is still the active one', async () => {
      await load(resolution({ groupeCreneauId: 'DEFAUT' }), [groupe('DEFAUT', true)]);
      expect(store.stale()).toBe(false);
    });

    it('is true once the active group differs from the solved one', async () => {
      await load(resolution({ groupeCreneauId: 'DEFAUT' }), [groupe('DEFAUT', false), groupe('SECOURS', true)]);
      expect(store.stale()).toBe(true);
    });

    it('stays false while nothing has ever been solved', async () => {
      await load(resolution({ solved: false, groupeCreneauId: null }), [groupe('SECOURS', true)]);
      expect(store.stale()).toBe(false);
    });

    it('stays false when no group is active at all', async () => {
      await load(resolution({ groupeCreneauId: 'DEFAUT' }), [groupe('DEFAUT', false)]);
      expect(store.stale()).toBe(false);
    });
  });

  describe('dataStale', () => {
    it('is true when reference data was edited after the solve', async () => {
      await load(
        resolution({ resoluLe: '2026-07-01T10:00:00Z', derniereModificationDonnees: '2026-07-01T11:00:00Z' }),
        [groupe('DEFAUT', true)]
      );
      expect(store.dataStale()).toBe(true);
    });

    it('is false when the last edit predates the solve', async () => {
      await load(
        resolution({ resoluLe: '2026-07-01T10:00:00Z', derniereModificationDonnees: '2026-07-01T09:00:00Z' }),
        [groupe('DEFAUT', true)]
      );
      expect(store.dataStale()).toBe(false);
    });

    it('is false when no edit has been recorded', async () => {
      await load(resolution({ derniereModificationDonnees: null }), [groupe('DEFAUT', true)]);
      expect(store.dataStale()).toBe(false);
    });

    it('is false while nothing has ever been solved', async () => {
      await load(
        resolution({ solved: false, resoluLe: null, derniereModificationDonnees: '2026-07-01T11:00:00Z' }),
        [groupe('DEFAUT', true)]
      );
      expect(store.dataStale()).toBe(false);
    });
  });
});
