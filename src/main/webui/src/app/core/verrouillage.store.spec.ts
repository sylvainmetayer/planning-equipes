import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService, SessionExpireeError } from './api.service';
import { VerrouillageStore } from './verrouillage.store';
import type { TypeVerrouillage, VerrouillagePlanning } from './models';

function verrouillage(
  id: string,
  type: TypeVerrouillage,
  target: Partial<VerrouillagePlanning>,
): VerrouillagePlanning {
  return {
    id,
    type,
    animateurId: null,
    standId: null,
    creneauId: null,
    jour: null,
    raison: null,
    ...target,
  };
}

/** Fake ApiService routing GETs by URL. */
class FakeApi {
  responses: Record<string, unknown> = {};
  get = vi.fn((url: string) => {
    if (!(url in this.responses)) {
      return Promise.reject(new Error(`Unexpected GET ${url}`));
    }
    return Promise.resolve(this.responses[url]);
  });
  post = vi.fn(() => Promise.resolve({}));
  delete = vi.fn((_url: string) => Promise.resolve(undefined));
}

describe('VerrouillageStore', () => {
  let store: VerrouillageStore;
  let api: FakeApi;

  beforeEach(() => {
    api = new FakeApi();
    TestBed.configureTestingModule({
      providers: [VerrouillageStore, { provide: ApiService, useValue: api }],
    });
    store = TestBed.inject(VerrouillageStore);
  });

  it("recharge et applique tous les verrouillages de l'édition", async () => {
    api.responses['/api/verrouillages'] = [
      verrouillage('V1', 'JOUR', { jour: '2026-07-08' }),
      verrouillage('V2', 'STAND', { standId: 'STAND-A' }),
    ];

    await store.reload();

    expect(store.verrouillages()).toHaveLength(2);
    expect(store.estJourVerrouille('2026-07-08')).toBe(true);
    expect(store.estStandVerrouille('STAND-A')).toBe(true);
  });

  it('reconnaît chaque type de cible', async () => {
    api.responses['/api/verrouillages'] = [
      verrouillage('V1', 'ANIMATEUR', { animateurId: 'A1' }),
      verrouillage('V2', 'STAND', { standId: 'STAND-A' }),
      verrouillage('V3', 'CRENEAU', { creneauId: 42 }),
    ];

    await store.reload();

    expect(store.estAnimateurVerrouille('A1')).toBe(true);
    expect(store.estAnimateurVerrouille('A2')).toBe(false);
    expect(store.estStandVerrouille('STAND-A')).toBe(true);
    expect(store.estCreneauVerrouille(42)).toBe(true);
    expect(store.estCreneauVerrouille(43)).toBe(false);
    expect(store.estJourVerrouille(null)).toBe(false);
  });

  it('recharge après une création et après une suppression', async () => {
    api.responses['/api/verrouillages'] = [];

    await store.create({ type: 'JOUR', jour: '2026-07-08' });
    expect(api.post).toHaveBeenCalledWith('/api/verrouillages', {
      type: 'JOUR',
      jour: '2026-07-08',
    });
    expect(api.get).toHaveBeenCalledWith('/api/verrouillages');

    await store.remove('V1');
    expect(api.delete).toHaveBeenCalledWith('/api/verrouillages/V1');
  });

  it('answers the warnings of a written lock even when the re-read fails', async () => {
    api.post.mockResolvedValueOnce({
      avertissements: [{ type: 'VERROU_SUR_VIOLATION', message: 'Un siège gelé casse une règle.' }],
    });
    api.get.mockRejectedValueOnce(new Error('réseau'));

    await expect(store.create({ type: 'JOUR', jour: '2026-07-08' })).resolves.toHaveLength(1);
  });

  it('lifts every lock of a batch, keeps going past a refusal, and reads the list once', async () => {
    api.responses['/api/verrouillages'] = [];
    api.delete
      .mockResolvedValueOnce(undefined)
      .mockRejectedValueOnce(new Error('Une résolution est en cours.'))
      .mockResolvedValueOnce(undefined);

    const result = await store.removeMany(['V1', 'V2', 'V3']);

    expect(api.delete.mock.calls.map(([url]) => url)).toEqual([
      '/api/verrouillages/V1',
      '/api/verrouillages/V2',
      '/api/verrouillages/V3',
    ]);
    expect(result.succes).toEqual(['V1', 'V3']);
    expect(result.echecs).toEqual([
      { id: 'V2', message: 'Une résolution est en cours.', concurrente: false },
    ]);
    expect(api.get).toHaveBeenCalledTimes(1);
  });

  it('stops a batch at an expired session instead of sending the rest', async () => {
    api.responses['/api/verrouillages'] = [];
    api.delete
      .mockResolvedValueOnce(undefined)
      .mockRejectedValueOnce(new SessionExpireeError())
      .mockResolvedValueOnce(undefined);

    await expect(store.removeMany(['V1', 'V2', 'V3'])).rejects.toBeInstanceOf(SessionExpireeError);

    expect(api.delete.mock.calls.map(([url]) => url)).toEqual([
      '/api/verrouillages/V1',
      '/api/verrouillages/V2',
    ]);
  });

  it('answers what a batch did even when the re-read fails', async () => {
    api.get.mockRejectedValueOnce(new Error('réseau'));

    await expect(store.removeMany(['V1'])).resolves.toMatchObject({ succes: ['V1'] });
  });
});
