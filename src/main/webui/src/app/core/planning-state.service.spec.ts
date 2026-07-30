import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from './api.service';
import { PlanningStateService } from './planning-state.service';
import type { Animateur, Creneau, PlanningFestival, Stand } from './models';

function stand(id: string, effectifMax: number): Stand {
  return {
    id,
    nom: id,
    typologiesProposees: ['STRATEGIE'],
    effectifMin: 1,
    effectifMax,
    reserveMajeurs: false
  };
}

function creneau(id: string): Creneau {
  return { id, jour: 1, date: '2026-07-08', heureDebut: '09:00', heureFin: '13:00' };
}

function animateur(id: string): Animateur {
  return {
    id,
    prenom: id,
    nom: id,
    dateNaissance: '2000-01-01',
    statut: 'BENEVOLE',
    competences: { STRATEGIE: 'REFERENT' },
    joursIndisponibles: []
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
}

describe('PlanningStateService', () => {
  let service: PlanningStateService;
  let api: FakeApi;

  beforeEach(() => {
    api = new FakeApi();
    TestBed.configureTestingModule({
      providers: [PlanningStateService, { provide: ApiService, useValue: api }]
    });
    service = TestBed.inject(PlanningStateService);
  });

  describe('buildFromReferenceData', () => {
    it('generates effectifMax seats per stand and per timeslot', async () => {
      api.responses = {
        '/api/animateurs': [animateur('A1')],
        '/api/stands': [stand('S1', 2), stand('S2', 3)],
        '/api/creneaux': [creneau('C1'), creneau('C2')]
      };

      const planning = await service.buildFromReferenceData();

      // (2 + 3) seats × 2 timeslots = 10 postes.
      expect(planning.postes).toHaveLength(10);
      expect(planning.postes.every((p) => p.animateur === null)).toBe(true);
      expect(planning.animateurs).toEqual(api.responses['/api/animateurs']);
      expect(planning.score).toBeNull();
    });

    it('assigns a unique id to every poste', async () => {
      api.responses = {
        '/api/animateurs': [animateur('A1')],
        '/api/stands': [stand('S1', 2)],
        '/api/creneaux': [creneau('C1'), creneau('C2')]
      };

      const planning = await service.buildFromReferenceData();
      const ids = planning.postes.map((p) => p.id);

      expect(new Set(ids).size).toBe(ids.length);
    });

    it('falls back to at least one seat when effectifMax is zero', async () => {
      api.responses = {
        '/api/animateurs': [animateur('A1')],
        '/api/stands': [stand('S1', 0)],
        '/api/creneaux': [creneau('C1')]
      };

      const planning = await service.buildFromReferenceData();
      expect(planning.postes).toHaveLength(1);
    });

    it('throws when any reference collection is empty', async () => {
      api.responses = {
        '/api/animateurs': [],
        '/api/stands': [stand('S1', 1)],
        '/api/creneaux': [creneau('C1')]
      };

      await expect(service.buildFromReferenceData()).rejects.toThrow(/No reference data/);
    });
  });

  describe('loadForDisplay / require', () => {
    const solved: PlanningFestival = {
      animateurs: [animateur('A1')],
      postes: [{ id: 'p1', stand: stand('S1', 1), creneau: creneau('C1'), animateur: animateur('A1') }],
      score: { hardScore: 0, mediumScore: 0, softScore: 0 }
    };

    it('returns the in-session planning without calling the API', async () => {
      service.set(solved);
      const result = await service.loadForDisplay();

      expect(result).toBe(solved);
      expect(api.get).not.toHaveBeenCalled();
    });

    it('loads the persisted planning when nothing was solved this session', async () => {
      api.responses = { '/api/planning/persisted': solved };
      const result = await service.loadForDisplay();

      expect(api.get).toHaveBeenCalledWith('/api/planning/persisted');
      expect(result).toBe(solved);
    });

    it('require() throws when the loaded planning has no postes', async () => {
      api.responses = {
        '/api/planning/persisted': { animateurs: [], postes: [], score: null } satisfies PlanningFestival
      };

      await expect(service.require()).rejects.toThrow(/No planning available/);
    });

    it('require() returns the planning when it has postes', async () => {
      service.set(solved);
      await expect(service.require()).resolves.toBe(solved);
    });
  });
});
