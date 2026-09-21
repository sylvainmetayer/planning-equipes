import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../api.service';
import { ParametresLegaux } from '../models';
import { ConstraintsApi } from './constraints-api';

describe('ConstraintsApi', () => {
  const api = { get: vi.fn(), post: vi.fn(), put: vi.fn() };
  let constraints: ConstraintsApi;

  beforeEach(() => {
    for (const stub of Object.values(api)) {
      stub.mockReset();
    }
    TestBed.configureTestingModule({
      providers: [ConstraintsApi, { provide: ApiService, useValue: api }],
    });
    constraints = TestBed.inject(ConstraintsApi);
  });

  it('reads the catalogue and the legal parameters, and runs the diagnostic as a POST', async () => {
    await constraints.catalogue();
    await constraints.legalParameters();
    await constraints.diagnose();

    expect(api.get).toHaveBeenNthCalledWith(1, '/api/constraints');
    expect(api.get).toHaveBeenNthCalledWith(2, '/api/parametres-legaux');
    expect(api.post).toHaveBeenCalledWith('/api/constraints/diagnostic', {});
  });

  // A constraint name is an identifier from the catalogue, encoded all the same: the route must not change on a stray character.
  it('writes a rule state and its weight on the rule path', async () => {
    await constraints.setActive('equilibrerCharge', false);
    await constraints.setWeight('equilibrerCharge', 7);

    expect(api.put).toHaveBeenNthCalledWith(1, '/api/constraints/equilibrerCharge', {
      actif: false,
    });
    expect(api.put).toHaveBeenNthCalledWith(2, '/api/constraints/equilibrerCharge/poids', {
      poids: 7,
    });
  });

  it('sends the legal parameters whole', async () => {
    const parametres = {
      dureeHebdomadaireMaxMinutes: 2880,
      dureeHebdomadaireMaxMineurMinutes: 2100,
      reposQuotidienMinimalMinutes: 660,
    } as ParametresLegaux;

    await constraints.saveLegalParameters(parametres);

    expect(api.put).toHaveBeenCalledWith('/api/parametres-legaux', parametres);
  });
});
