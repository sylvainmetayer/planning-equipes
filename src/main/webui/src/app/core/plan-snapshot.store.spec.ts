import { HttpErrorResponse } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from './api.service';
import { PlanSnapshot } from './models';
import {
  InstantanePerimeError,
  PlanSnapshotStore,
  ReferencesManquantesError,
} from './plan-snapshot.store';

function snapshot(id: number, libelle = 'S' + id): PlanSnapshot {
  return {
    id,
    libelle,
    automatique: false,
    score: '0hard/0medium/0soft',
    nombreAffectations: 12,
    creeLe: '2026-08-18T10:00:00Z',
    editionId: 'DEFAUT',
    editionNom: 'Édition par défaut',
    kpi: null,
    referenceModifieLe: null,
    perime: false,
    publieLe: null,
  };
}

describe('PlanSnapshotStore', () => {
  let store: PlanSnapshotStore;
  let api: {
    get: ReturnType<typeof vi.fn>;
    postPreservingHttpError: ReturnType<typeof vi.fn>;
    post: ReturnType<typeof vi.fn>;
    delete: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    api = {
      get: vi.fn().mockResolvedValue([]),
      post: vi.fn().mockResolvedValue({}),
      postPreservingHttpError: vi.fn(),
      delete: vi.fn().mockResolvedValue(undefined),
    };
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), { provide: ApiService, useValue: api }],
    });
    store = TestBed.inject(PlanSnapshotStore);
  });

  it('surfaces the ids a refused restore names', async () => {
    api.postPreservingHttpError.mockRejectedValue(
      new HttpErrorResponse({
        status: 409,
        error: {
          message: 'Références disparues',
          referencesManquantes: ['stand:S1', 'creneau:42'],
        },
      }),
    );

    await expect(store.restaurer(7)).rejects.toBeInstanceOf(ReferencesManquantesError);
    await store.restaurer(7).catch((error: unknown) => {
      expect((error as ReferencesManquantesError).references).toEqual(['stand:S1', 'creneau:42']);
      expect((error as Error).message).toBe('Références disparues');
    });
  });

  it('reports any other failure as a plain error', async () => {
    api.postPreservingHttpError.mockRejectedValue(new HttpErrorResponse({ status: 500 }));

    await expect(store.restaurer(7)).rejects.not.toBeInstanceOf(ReferencesManquantesError);
  });

  it('turns the staleness refusal into a question the caller can answer', async () => {
    api.postPreservingHttpError.mockRejectedValue(
      new HttpErrorResponse({
        status: 409,
        error: {
          message: 'Le référentiel a été modifié depuis cette capture',
          referencesManquantes: [],
          perime: true,
          referenceModifieLe: '2026-08-19T08:30:00Z',
        },
      }),
    );

    await expect(store.restaurer(7)).rejects.toBeInstanceOf(InstantanePerimeError);
    await store.restaurer(7).catch((error: unknown) => {
      expect((error as InstantanePerimeError).referenceModifieLe).toBe('2026-08-19T08:30:00Z');
    });
    // The plain restore never carries the override: forcing has to be asked for.
    expect(api.postPreservingHttpError).toHaveBeenCalledWith(
      '/api/planning/snapshots/7/restore',
      {},
    );
  });

  it('asks again with forcer only when told to', async () => {
    api.postPreservingHttpError.mockResolvedValue({ restaure: true, affectations: 12 });

    await store.restaurer(7, true);

    expect(api.postPreservingHttpError).toHaveBeenCalledWith(
      '/api/planning/snapshots/7/restore?forcer=true',
      {},
    );
  });

  it('reloads the list after a capture and after a delete', async () => {
    api.get.mockResolvedValue([snapshot(1)]);

    await store.capturer('Essai');
    expect(api.post).toHaveBeenCalledWith('/api/planning/snapshots', { libelle: 'Essai' });
    expect(store.snapshots()).toHaveLength(1);

    api.get.mockResolvedValue([]);
    await store.supprimer(1);
    expect(api.delete).toHaveBeenCalledWith('/api/planning/snapshots/1');
    expect(store.snapshots()).toHaveLength(0);
  });
});
