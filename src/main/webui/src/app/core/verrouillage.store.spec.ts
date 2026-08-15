import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from './api.service';
import { VerrouillageStore } from './verrouillage.store';
import type { GroupeCreneau, TypeVerrouillage, VerrouillagePlanning } from './models';

function groupe(id: string, actif: boolean): GroupeCreneau {
  return { id, nom: id.toLowerCase(), actif };
}

function verrouillage(
  id: string,
  type: TypeVerrouillage,
  cible: Partial<VerrouillagePlanning>,
  groupeCreneauId = 'DEFAUT'
): VerrouillagePlanning {
  return {
    id,
    type,
    groupeCreneauId,
    animateurId: null,
    standId: null,
    creneauId: null,
    jour: null,
    raison: null,
    ...cible
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
  post = vi.fn(async () => ({}));
  delete = vi.fn(async () => undefined);
}

describe('VerrouillageStore', () => {
  let store: VerrouillageStore;
  let api: FakeApi;

  beforeEach(() => {
    api = new FakeApi();
    TestBed.configureTestingModule({
      providers: [VerrouillageStore, { provide: ApiService, useValue: api }]
    });
    store = TestBed.inject(VerrouillageStore);
  });

  it('ne retient comme actifs que les verrouillages du groupe de créneaux actif', async () => {
    api.responses['/api/verrouillages'] = [
      verrouillage('V1', 'JOUR', { jour: '2026-07-08' }),
      verrouillage('V2', 'STAND', { standId: 'STAND-A' }, 'SECOURS')
    ];
    api.responses['/api/groupes-creneaux'] = [groupe('DEFAUT', true), groupe('SECOURS', false)];

    await store.reload();

    expect(store.verrouillages()).toHaveLength(2);
    expect(store.actifs().map((v) => v.id)).toEqual(['V1']);
    expect(store.estJourVerrouille('2026-07-08')).toBe(true);
    // Recorded on the dormant group: it must not show up as frozen.
    expect(store.estStandVerrouille('STAND-A')).toBe(false);
  });

  it('reconnaît chaque type de cible du groupe actif', async () => {
    api.responses['/api/verrouillages'] = [
      verrouillage('V1', 'ANIMATEUR', { animateurId: 'A1' }),
      verrouillage('V2', 'STAND', { standId: 'STAND-A' }),
      verrouillage('V3', 'CRENEAU', { creneauId: 42 })
    ];
    api.responses['/api/groupes-creneaux'] = [groupe('DEFAUT', true)];

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
    api.responses['/api/groupes-creneaux'] = [groupe('DEFAUT', true)];

    await store.create({ type: 'JOUR', jour: '2026-07-08' });
    expect(api.post).toHaveBeenCalledWith('/api/verrouillages', { type: 'JOUR', jour: '2026-07-08' });
    expect(api.get).toHaveBeenCalledWith('/api/verrouillages');

    await store.remove('V1');
    expect(api.delete).toHaveBeenCalledWith('/api/verrouillages/V1');
  });
});
