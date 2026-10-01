// The query strings of the swap requests endpoints: an empty bound is left
// out, and « Choisir les dates » with neither bound asks for the whole edition
// rather than letting the server fall back to the foire window.

import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../api.service';
import { EchangesApi } from './echanges-api';

describe('EchangesApi', () => {
  const api = { get: vi.fn(), post: vi.fn(), put: vi.fn() };
  let echanges: EchangesApi;

  beforeEach(() => {
    for (const stub of Object.values(api)) {
      stub.mockReset();
    }
    TestBed.configureTestingModule({
      providers: [EchangesApi, { provide: ApiService, useValue: api }],
    });
    echanges = TestBed.inject(EchangesApi);
  });

  it('asks for the whole edition when no date is chosen, the foire window by default', async () => {
    await echanges.statistics({ kind: 'dates', du: null, au: null });
    await echanges.statistics({ kind: 'foire' });
    await echanges.statistics({ kind: 'edition' });
    await echanges.statistics({ kind: 'dates', du: '2026-06-01', au: null });

    expect(api.get).toHaveBeenNthCalledWith(1, '/api/echanges/statistiques?periode=edition');
    expect(api.get).toHaveBeenNthCalledWith(2, '/api/echanges/statistiques?');
    expect(api.get).toHaveBeenNthCalledWith(3, '/api/echanges/statistiques?periode=edition');
    expect(api.get).toHaveBeenNthCalledWith(4, '/api/echanges/statistiques?du=2026-06-01');
  });

  it('narrows the list by period and measure, leaving out what is not given', async () => {
    await echanges.list();
    await echanges.list('2026-06-01', null, 'delai-communication');

    expect(api.get).toHaveBeenNthCalledWith(1, '/api/echanges?');
    expect(api.get).toHaveBeenNthCalledWith(
      2,
      '/api/echanges?du=2026-06-01&mesure=delai-communication',
    );
  });
});
