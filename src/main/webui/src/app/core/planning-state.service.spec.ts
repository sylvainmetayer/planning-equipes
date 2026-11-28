import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from './api.service';
import { PlanningStateService } from './planning-state.service';
import type {
  Animateur,
  CompactPlanning,
  CompactSeat,
  Creneau,
  PlanningEvenement,
  Stand,
} from './models';

function stand(id: string, effectifMax: number): Stand {
  return {
    id,
    nom: id,
    typologiesProposees: ['STRATEGIE'],
    effectifMin: 1,
    effectifMax,
    reserveMajeurs: false,
    premium: false,
    niveauEffort: 'NORMAL',
    emplacement: null,
    indisponibilites: [],
    ouvertures: [],
    horaires: [],
  };
}

function creneau(id: number): Creneau {
  return { id, jour: 1, date: '2026-07-08', heureDebut: '09:00', heureFin: '13:00' };
}

function animateur(id: string): Animateur {
  return {
    id,
    prenom: id,
    nom: id,
    dateNaissance: '2000-01-01',
    manager: false,
    competences: { STRATEGIE: 'REFERENT' },
    souhaits: [],
    joursIndisponibles: [],
  };
}

function seatFields(id: string) {
  return {
    id,
    verrouille: false,
    dureeEffectiveMinutes: 240,
    heureDebutEffective: null,
    heureFinEffective: null,
    suiteDe: null,
  };
}

function seat(id: string, animateurId: string | null): CompactSeat {
  return { ...seatFields(id), standId: 'S1', creneauId: 1, animateurId };
}

/** Fake ApiService routing GETs by URL. */
class FakeApi {
  responses: Record<string, unknown> = {};
  get = vi.fn((url: string) => {
    if (!(url in this.responses)) {
      return Promise.reject(new Error(`Unexpected GET ${url}`));
    }
    return Promise.resolve(this.responses[url]);
  });
}

describe('PlanningStateService', () => {
  let service: PlanningStateService;
  let api: FakeApi;

  beforeEach(() => {
    api = new FakeApi();
    TestBed.configureTestingModule({
      providers: [PlanningStateService, { provide: ApiService, useValue: api }],
    });
    service = TestBed.inject(PlanningStateService);
  });

  describe('loadForDisplay / require', () => {
    const solved: PlanningEvenement = {
      animateurs: [animateur('A1')],
      postes: [
        { id: 'p1', stand: stand('S1', 1), creneau: creneau(1), animateur: animateur('A1') },
      ],
      score: { hardScore: 0, mediumScore: 0, softScore: 0 },
    };

    it('returns the in-session planning without calling the API', async () => {
      service.set(solved);
      const result = await service.loadForDisplay();

      expect(result).toBe(solved);
      expect(api.get).not.toHaveBeenCalled();
    });

    it('loads the compact persisted planning and rebuilds every seat', async () => {
      api.responses = {
        '/api/planning/persisted': {
          animateurs: [animateur('A1')],
          stands: [stand('S1', 2)],
          creneaux: [creneau(1)],
          postes: [seat('p1', 'A1'), seat('p2', null)],
          score: null,
        } satisfies CompactPlanning,
      };
      const result = await service.loadForDisplay();

      expect(api.get).toHaveBeenCalledWith('/api/planning/persisted');
      expect(result.postes).toEqual([
        {
          ...seatFields('p1'),
          stand: stand('S1', 2),
          creneau: creneau(1),
          animateur: animateur('A1'),
        },
        { ...seatFields('p2'), stand: stand('S1', 2), creneau: creneau(1), animateur: null },
      ]);
      expect(result.postes[0].stand).toBe(result.postes[1].stand);
      expect(result.animateurs).toEqual([animateur('A1')]);
      expect('stands' in result).toBe(false);
    });

    it('require() throws when the loaded planning has no postes', async () => {
      api.responses = {
        '/api/planning/persisted': {
          animateurs: [],
          stands: [],
          creneaux: [],
          postes: [],
          score: null,
        } satisfies CompactPlanning,
      };

      await expect(service.require()).rejects.toThrow(/Aucun planning disponible/);
    });

    it('require() returns the planning when it has postes', async () => {
      service.set(solved);
      await expect(service.require()).resolves.toBe(solved);
    });
  });
});
