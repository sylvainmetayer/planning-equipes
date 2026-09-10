import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from './api.service';
import { PlanningResolutionStore } from './planning-resolution.store';
import type { PlanningResolution } from './models';

function resolution(overrides: Partial<PlanningResolution> = {}): PlanningResolution {
  return {
    solved: true,
    resoluLe: '2026-07-01T10:00:00Z',
    derniereModificationDonnees: null,
    ...overrides,
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
      providers: [PlanningResolutionStore, { provide: ApiService, useValue: api }],
    });
    store = TestBed.inject(PlanningResolutionStore);
  });

  async function load(current: PlanningResolution): Promise<void> {
    api.responses = { '/api/planning/persisted/resolution': current };
    await store.reload();
  }

  describe('dataStale', () => {
    it('is true when reference data was edited after the solve', async () => {
      await load(
        resolution({
          resoluLe: '2026-07-01T10:00:00Z',
          derniereModificationDonnees: '2026-07-01T11:00:00Z',
        }),
      );
      expect(store.dataStale()).toBe(true);
    });

    it('is false when the last edit predates the solve', async () => {
      await load(
        resolution({
          resoluLe: '2026-07-01T10:00:00Z',
          derniereModificationDonnees: '2026-07-01T09:00:00Z',
        }),
      );
      expect(store.dataStale()).toBe(false);
    });

    it('is false when no edit has been recorded', async () => {
      await load(resolution({ derniereModificationDonnees: null }));
      expect(store.dataStale()).toBe(false);
    });

    it('is false while nothing has ever been solved', async () => {
      await load(
        resolution({
          solved: false,
          resoluLe: null,
          derniereModificationDonnees: '2026-07-01T11:00:00Z',
        }),
      );
      expect(store.dataStale()).toBe(false);
    });
  });
});
