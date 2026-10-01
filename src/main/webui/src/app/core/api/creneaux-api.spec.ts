import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../api.service';
import { DerivationRequest, RegleRecurrence } from '../models';
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

  /**
   * The mode used to travel as a query parameter on all four: the same request
   * judged amplitudes or vacations. There is one reading of a grid left, so the
   * paths carry nothing but the rule itself.
   */
  it('posts the derivation and recurrence calls, preview and write alike, with no mode', async () => {
    const request: DerivationRequest = {
      dateDebut: '2026-07-06',
      dateFin: '2026-07-07',
      heureFermeture: '00:00',
      dureeMinimaleMinutes: 60,
      remplacer: false,
    };
    const rule: RegleRecurrence = {
      jours: 'TOUS',
      dateDebut: null,
      dateFin: null,
      joursSemaine: [],
      dates: [],
      exclusions: [],
      fenetres: [{ heureDebut: '10:00', heureFin: '12:00' }],
    };

    await creneaux.previewDerivation(request);
    await creneaux.derive(request);
    await creneaux.previewRecurrence(rule);
    await creneaux.createRecurrence(rule);

    expect(api.post.mock.calls).toEqual([
      ['/api/creneaux/derivation/apercu', request],
      ['/api/creneaux/derivation', request],
      ['/api/creneaux/recurrence/apercu', rule],
      ['/api/creneaux/recurrence', rule],
    ]);
  });
});
