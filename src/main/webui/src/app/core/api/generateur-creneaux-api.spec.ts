import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../api.service';
import { DemandeGeneration, DerivationRequest, RegleRecurrence } from '../models';
import { GenerateurCreneauxApi } from './generateur-creneaux-api';

describe('GenerateurCreneauxApi', () => {
  const api = { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() };
  let generateur: GenerateurCreneauxApi;

  beforeEach(() => {
    for (const stub of Object.values(api)) {
      stub.mockReset();
    }
    TestBed.configureTestingModule({
      providers: [GenerateurCreneauxApi, { provide: ApiService, useValue: api }],
    });
    generateur = TestBed.inject(GenerateurCreneauxApi);
  });

  it('reads, writes and deletes the templates and their calendar on the generator routes', async () => {
    const gabarit = { nom: 'Jour normal', vacations: [] };
    await generateur.etat();
    await generateur.create(gabarit);
    await generateur.update(3, gabarit);
    await generateur.delete(3);
    await generateur.setCalendrier([{ date: '2026-07-06', gabaritId: 3 }]);

    expect(api.get.mock.calls).toEqual([['/api/generateur-creneaux']]);
    expect(api.post.mock.calls).toEqual([['/api/generateur-creneaux/gabarits', gabarit]]);
    expect(api.put.mock.calls).toEqual([
      ['/api/generateur-creneaux/gabarits/3', gabarit],
      ['/api/generateur-creneaux/calendrier', [{ date: '2026-07-06', gabaritId: 3 }]],
    ]);
    expect(api.delete.mock.calls).toEqual([['/api/generateur-creneaux/gabarits/3']]);
  });

  it('previews, applies and withdraws with one request shape whatever the source', async () => {
    const derivation: DerivationRequest = {
      dateDebut: '2026-07-06',
      dateFin: '2026-07-07',
      heureFermeture: '00:00',
      dureeMinimaleMinutes: 60,
      remplacer: false,
    };
    const recurrence: RegleRecurrence = {
      jours: 'TOUS',
      dateDebut: null,
      dateFin: null,
      joursSemaine: [],
      dates: [],
      exclusions: [],
      fenetres: [{ heureDebut: '10:00', heureFin: '12:00' }],
    };
    const demandes: DemandeGeneration[] = [
      { source: 'GABARITS' },
      { source: 'STANDS', derivation },
      { source: 'RECURRENCE', recurrence },
    ];

    for (const demande of demandes) {
      await generateur.preview(demande);
      await generateur.apply(demande);
    }
    await generateur.withdraw(['2026-07-06']);

    expect(api.post.mock.calls).toEqual([
      ...demandes.flatMap((demande) => [
        ['/api/generateur-creneaux/apercu', demande],
        ['/api/generateur-creneaux/application', demande],
      ]),
      ['/api/generateur-creneaux/retrait', { dates: ['2026-07-06'] }],
    ]);
  });

  it('recognises the templates a grid implies, preview first', async () => {
    await generateur.previewReconnaissance();
    await generateur.reconnaitre();

    expect(api.post.mock.calls.map(([url]) => url)).toEqual([
      '/api/generateur-creneaux/reconnaissance/apercu',
      '/api/generateur-creneaux/reconnaissance',
    ]);
  });
});
