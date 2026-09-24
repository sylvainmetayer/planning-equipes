import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../api.service';
import { NouvelleHabilitation } from '../models';
import { ComptesApi } from './comptes-api';
import { StandsApi } from './stands-api';

describe('ComptesApi', () => {
  const api = {
    get: vi.fn(),
    post: vi.fn(),
    delete: vi.fn(),
    getDansEdition: vi.fn(),
  };
  let comptes: ComptesApi;

  beforeEach(() => {
    for (const stub of Object.values(api)) {
      stub.mockReset();
    }
    TestBed.configureTestingModule({
      providers: [ComptesApi, StandsApi, { provide: ApiService, useValue: api }],
    });
    comptes = TestBed.inject(ComptesApi);
  });

  it('lists and creates accounts on the collection', async () => {
    await comptes.list();
    await comptes.create({ email: 'rh@example.org', nom: 'Camille' });

    expect(api.get).toHaveBeenCalledWith('/api/comptes');
    expect(api.post).toHaveBeenCalledWith('/api/comptes', {
      email: 'rh@example.org',
      nom: 'Camille',
    });
  });

  it('deactivates and reactivates without a body, the id escaped', async () => {
    await comptes.deactivate('a/b');
    await comptes.reactivate('a/b');

    expect(api.post.mock.calls).toEqual([
      ['/api/comptes/a%2Fb/desactivation', null],
      ['/api/comptes/a%2Fb/reactivation', null],
    ]);
  });

  it('grants on the account and withdraws by DELETE, reading the account back', async () => {
    const habilitation: NouvelleHabilitation = {
      role: 'RESPONSABLE_STAND',
      editionId: '2026',
      expireLe: null,
      standIds: ['s1'],
    };
    api.delete.mockResolvedValue({ id: 'c1' });

    await comptes.grant('c1', habilitation);
    const updated = await comptes.withdraw('c1', 'h 1');

    expect(api.post).toHaveBeenCalledWith('/api/comptes/c1/habilitations', habilitation);
    expect(api.delete).toHaveBeenCalledWith('/api/comptes/c1/habilitations/h%201');
    expect(updated).toEqual({ id: 'c1' });
  });

  /** A right in another edition names that edition's stands, never the ones on screen. */
  it('reads the stands of a named edition with an explicit edition', async () => {
    await TestBed.inject(StandsApi).listInEdition('2025');

    expect(api.getDansEdition).toHaveBeenCalledWith('/api/stands', '2025');
  });
});
