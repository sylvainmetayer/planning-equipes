import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from './api.service';
import { ReferenceDataStore } from './reference-data.store';

/** Answers every collection with an empty list, so `reload()` succeeds. */
class FakeApi {
  get = vi.fn(async (url: string) => (url.endsWith('/volumetrie') ? {} : []));
  post = vi.fn(async () => undefined);
  put = vi.fn(async () => undefined);
  delete = vi.fn(async () => undefined);
}

describe('ReferenceDataStore bulk operations', () => {
  let store: ReferenceDataStore;
  let api: FakeApi;

  beforeEach(() => {
    api = new FakeApi();
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), ReferenceDataStore, { provide: ApiService, useValue: api }]
    });
    store = TestBed.inject(ReferenceDataStore);
  });

  /** Number of `reload()` calls, each of which fetches every collection once. */
  function reloadCount(): number {
    return api.get.mock.calls.filter(([url]) => url === '/api/stands').length;
  }

  it('supprime chaque ligne puis ne recharge qu’une fois', async () => {
    const result = await store.removeMany('stands', ['S1', 'S2']);

    expect(api.delete).toHaveBeenCalledTimes(2);
    expect(api.delete).toHaveBeenCalledWith('/api/stands/S1');
    expect(result.succes).toEqual(['S1', 'S2']);
    expect(reloadCount()).toBe(1);
  });

  it('encode les identifiants dans l’URL', async () => {
    await store.removeMany('emplacements', ['salle des fêtes']);

    expect(api.delete).toHaveBeenCalledWith('/api/emplacements/salle%20des%20f%C3%AAtes');
  });

  // Un refus du serveur sur une ligne ne doit pas interrompre le lot.
  it('poursuit le lot après un échec et le rapporte', async () => {
    api.delete.mockRejectedValueOnce(new Error('encore référencé'));

    const result = await store.removeMany('typologies', ['t1', 't2']);

    expect(api.delete).toHaveBeenCalledTimes(2);
    expect(result.succes).toEqual(['t2']);
    expect(result.echecs).toEqual([{ id: 't1', message: 'encore référencé' }]);
    expect(reloadCount()).toBe(1);
  });

  it('enregistre chaque ligne par PUT sur son identifiant', async () => {
    const result = await store.saveMany('animateurs', [
      { id: 'alice', manager: true },
      { id: 'bob', manager: true }
    ]);

    expect(api.put).toHaveBeenCalledWith('/api/animateurs/alice', { id: 'alice', manager: true });
    expect(result.succes).toEqual(['alice', 'bob']);
    expect(reloadCount()).toBe(1);
  });
});
