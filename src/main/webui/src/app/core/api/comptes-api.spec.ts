import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../api.service';
import { NouveauCompte, NouvelleHabilitation } from '../models';
import { ComptesApi } from './comptes-api';

describe('ComptesApi', () => {
  const get = vi.fn();
  const post = vi.fn();
  const deleteReturning = vi.fn();
  let api: ComptesApi;

  beforeEach(() => {
    for (const fn of [get, post, deleteReturning]) {
      fn.mockReset();
      fn.mockResolvedValue({});
    }
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: ApiService, useValue: { get, post, deleteReturning } },
      ],
    });
    api = TestBed.inject(ComptesApi);
  });

  it('lists and creates accounts', async () => {
    await api.list();
    expect(get).toHaveBeenCalledWith('/api/comptes');
    const nouveau: NouveauCompte = { email: 'a@b.fr', nom: 'A' };
    await api.create(nouveau);
    expect(post).toHaveBeenCalledWith('/api/comptes', nouveau);
  });

  it('deactivates and reactivates an account, its id escaped in the path', async () => {
    await api.deactivate('a/b');
    expect(post).toHaveBeenCalledWith('/api/comptes/a%2Fb/desactivation', null);
    await api.reactivate('c1');
    expect(post).toHaveBeenCalledWith('/api/comptes/c1/reactivation', null);
  });

  it('grants and withdraws a right', async () => {
    const droit: NouvelleHabilitation = { role: 'ORGANISATEUR', editionId: 'E1', expireLe: null };
    await api.grant('c1', droit);
    expect(post).toHaveBeenCalledWith('/api/comptes/c1/habilitations', droit);
    await api.withdraw('c1', 'h1');
    expect(deleteReturning).toHaveBeenCalledWith('/api/comptes/c1/habilitations/h1');
  });
});
