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
    reserveMajeurs: false,
    premium: false
  };
}

function creneau(id: string): Creneau {
  return { id, jour: 1, date: '2026-07-08', heureDebut: '09:00', heureFin: '13:00', standsOuvertsIds: [] };
}

function animateur(id: string): Animateur {
  return {
    id,
    prenom: id,
    nom: id,
    dateNaissance: '2000-01-01',
    manager: false,
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
