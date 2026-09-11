import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../api.service';
import { DerivationRequest, ParametresDecoupage, RegleRecurrence } from '../models';
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

  it('reads the diagnostic, the control, the slicing parameters and the slicing preview', async () => {
    await creneaux.diagnostic();
    await creneaux.control();
    await creneaux.slicingParameters();
    await creneaux.previewSlicing();

    expect(api.get.mock.calls.map(([url]) => url)).toEqual([
      '/api/creneaux/diagnostic',
      '/api/creneaux/controle',
      '/api/parametres-decoupage',
      '/api/decoupage/preview',
    ]);
  });

  // The mode travels as a query parameter: the same request derives amplitudes or vacations.
  it('carries the grid mode on the derivation and recurrence calls, preview and write alike', async () => {
    const request = { dateDebut: '2026-07-06', dateFin: '2026-07-07' } as DerivationRequest;
    const regle = { jours: 'TOUS' } as unknown as RegleRecurrence;

    await creneaux.previewDerivation('AMPLITUDES', request);
    await creneaux.derive('AMPLITUDES', request);
    await creneaux.previewRecurrence('VACATIONS', regle);
    await creneaux.createRecurrence('VACATIONS', regle);

    expect(api.post.mock.calls).toEqual([
      ['/api/creneaux/derivation/apercu?mode=AMPLITUDES', request],
      ['/api/creneaux/derivation?mode=AMPLITUDES', request],
      ['/api/creneaux/recurrence/apercu?mode=VACATIONS', regle],
      ['/api/creneaux/recurrence?mode=VACATIONS', regle],
    ]);
  });

  it('writes the whole slicing settings on one path and the grid mode alone on another', async () => {
    const parametres = { dureeChevauchementMinutes: 20 } as ParametresDecoupage;

    await creneaux.saveSlicingParameters(parametres);
    await creneaux.setGridMode('VACATIONS');

    expect(api.put).toHaveBeenNthCalledWith(1, '/api/parametres-decoupage', parametres);
    expect(api.put).toHaveBeenNthCalledWith(2, '/api/parametres-decoupage/mode-grille', {
      modeGrille: 'VACATIONS',
    });
  });

  it('generates the slicing with an empty body', async () => {
    await creneaux.generateSlicing();

    expect(api.post).toHaveBeenCalledWith('/api/decoupage/generer', {});
  });
});
