import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../api.service';
import { CreneauxApi } from './creneaux-api';

describe('CreneauxApi', () => {
  const api = { get: vi.fn(), post: vi.fn(), put: vi.fn() };
  let creneaux: CreneauxApi;

  beforeEach(() => {
    for (const stub of Object.values(api)) {
      stub.mockReset();
    }
    TestBed.configureTestingModule({
      providers: [CreneauxApi, { provide: ApiService, useValue: api }],
    });
    creneaux = TestBed.inject(CreneauxApi);
  });

  it('reads the diagnostic and the control', async () => {
    await creneaux.diagnostic();
    await creneaux.control();

    expect(api.get.mock.calls.map(([url]) => url)).toEqual([
      '/api/creneaux/diagnostic',
      '/api/creneaux/controle',
    ]);
  });
});
