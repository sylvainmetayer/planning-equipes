import { HttpErrorResponse } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from './api.service';
import { PlanSnapshot } from './models';
import { GroupeDifferentError, PlanSnapshotStore, ReferencesManquantesError } from './plan-snapshot.store';

function snapshot(id: number, groupeCreneauId: string | null, libelle = 'S' + id): PlanSnapshot {
  return {
    id,
    libelle,
    automatique: false,
    groupeCreneauId,
    groupeNom: groupeCreneauId,
    score: '0hard/0medium/0soft',
    nombreAffectations: 12,
    creeLe: '2026-08-18T10:00:00Z'
  };
}

describe('PlanSnapshotStore', () => {
  let store: PlanSnapshotStore;
  let api: { get: ReturnType<typeof vi.fn>; postPreservingHttpError: ReturnType<typeof vi.fn>; post: ReturnType<typeof vi.fn>; delete: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    api = {
      get: vi.fn().mockResolvedValue([]),
      post: vi.fn().mockResolvedValue({}),
      postPreservingHttpError: vi.fn(),
      delete: vi.fn().mockResolvedValue(undefined)
    };
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), { provide: ApiService, useValue: api }]
    });
    store = TestBed.inject(PlanSnapshotStore);
  });

  it('keeps the most recent snapshot of each group', () => {
    // The API answers newest first, so the first one seen for a group wins.
    store.snapshots.set([
      snapshot(3, 'CONTINU', 'récent'),
      snapshot(2, 'CONTINU', 'ancien'),
      snapshot(1, 'DEFAUT'),
      snapshot(0, null)
    ]);

    const parGroupe = store.parGroupe();
    expect(parGroupe.get('CONTINU')?.libelle).toBe('récent');
    expect(parGroupe.get('DEFAUT')?.id).toBe(1);
    // A snapshot whose group is unknown is not indexed under any group.
    expect(parGroupe.size).toBe(2);
  });

  it('surfaces the ids a refused restore names', async () => {
    api.postPreservingHttpError.mockRejectedValue(
      new HttpErrorResponse({
        status: 409,
        error: { message: 'Références disparues', referencesManquantes: ['stand:S1', 'creneau:42'] }
      })
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

  it("distingue le refus « autre groupe de créneaux », rejouable avec l'option forcer", async () => {
    api.postPreservingHttpError.mockRejectedValue(
      new HttpErrorResponse({
        status: 409,
        error: { message: 'Autre groupe', referencesManquantes: [], groupeDifferent: true }
      })
    );

    await expect(store.restaurer(7)).rejects.toBeInstanceOf(GroupeDifferentError);
    expect(api.postPreservingHttpError).toHaveBeenLastCalledWith('/api/planning/snapshots/7/restore', {});

    api.postPreservingHttpError.mockResolvedValue({ restaure: true, affectations: 12 });
    await store.restaurer(7, true);
    expect(api.postPreservingHttpError).toHaveBeenLastCalledWith(
      '/api/planning/snapshots/7/restore?forcer=true',
      {}
    );
  });

  it('reloads the list after a capture and after a delete', async () => {
    api.get.mockResolvedValue([snapshot(1, 'DEFAUT')]);

    await store.capturer('Essai');
    expect(api.post).toHaveBeenCalledWith('/api/planning/snapshots', { libelle: 'Essai' });
    expect(store.snapshots()).toHaveLength(1);

    api.get.mockResolvedValue([]);
    await store.supprimer(1);
    expect(api.delete).toHaveBeenCalledWith('/api/planning/snapshots/1');
    expect(store.snapshots()).toHaveLength(0);
  });
});
