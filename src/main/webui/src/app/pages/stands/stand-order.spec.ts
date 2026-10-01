import { describe, expect, it } from 'vitest';
import {
  Animateur,
  EtatOuverture,
  PlanningEvenement,
  PosteAffectation,
  RapportOuvertures,
  Stand,
} from '../../core/models';
import { coverageRate, sortStands, standCoverage, standOpenings } from './stand-order';

function stand(id: string, nom: string, partial: Partial<Stand> = {}): Stand {
  return {
    id,
    nom,
    typologiesProposees: [],
    effectifMin: 1,
    effectifMax: 2,
    reserveMajeurs: false,
    premium: false,
    niveauEffort: 'NORMAL',
    emplacement: null,
    indisponibilites: [],
    ouvertures: [],
    horaires: [],
    ...partial,
  };
}

/** The openings report of one stand, one day per state given. */
function openingsReport(
  standId: string,
  postes: number,
  states: EtatOuverture[],
): RapportOuvertures {
  return {
    jours: [],
    stands: [
      {
        standId,
        nom: standId,
        effectifMin: 1,
        jours: states.map((etat, index) => ({
          date: `2026-07-${String(10 + index)}`,
          etat,
          source: 'REGLE',
          fenetres: [],
          minutesOuvertes: 0,
          minutesAmplitude: 0,
          postes: 0,
          creneaux: [],
        })),
        minutesOuvertes: 0,
        postes,
        modifieLe: null,
      },
    ],
    standsJamaisOuverts: 0,
    postesTotal: postes,
    anomalies: [],
  };
}

function animateur(id: string): Animateur {
  return {
    id,
    prenom: id,
    nom: id,
    dateNaissance: '1990-01-01',
    manager: false,
    competences: {},
    souhaits: [],
    joursIndisponibles: [],
  };
}

function seat(id: string, standId: string, animateurId: string | null): PosteAffectation {
  return {
    id,
    stand: stand(standId, standId),
    creneau: null,
    animateur: animateurId ? animateur(animateurId) : null,
  };
}

function planning(postes: PosteAffectation[]): PlanningEvenement {
  return { animateurs: [], postes, score: null };
}

const context = {
  typologies: new Map([['T1', 'Échecs']]),
  openings: new Map([
    ['S1', { joursOuverts: 3, postes: 12 }],
    ['S2', { joursOuverts: 5, postes: 8 }],
  ]),
  coverage: new Map([['S1', { pourvus: 6, postes: 12 }]]),
};

describe('stand order', () => {
  it('sorts on every column, names numerically and a missing figure last', () => {
    const stands = [stand('S2', 'Stand 10'), stand('S1', 'Stand 2'), stand('S3', 'Stand 1')];

    expect(
      sortStands(stands, { active: 'nom', direction: 'asc' }, context).map((s) => s.nom),
    ).toEqual(['Stand 1', 'Stand 2', 'Stand 10']);
    expect(
      sortStands(stands, { active: 'ouvert', direction: 'desc' }, context).map((s) => s.id),
    ).toEqual(['S2', 'S1', 'S3']);
    // A stand without coverage is last whichever the direction, its ties in id order.
    expect(
      sortStands(stands, { active: 'couverture', direction: 'asc' }, context).map((s) => s.id),
    ).toEqual(['S1', 'S2', 'S3']);
    // No sort, or an unknown column: the natural order of the ids.
    const byId = ['S1', 'S2', 'S3'];
    expect(sortStands(stands, { active: '', direction: '' }, context).map((s) => s.id)).toEqual(
      byId,
    );
    expect(
      sortStands(stands, { active: 'horaires', direction: 'asc' }, context).map((s) => s.id),
    ).toEqual(byId);
  });

  it('counts the open days and the seats of each stand from the openings report', () => {
    const report = openingsReport('S1', 7, ['OUVERT_TOTAL', 'FERME', 'OUVERT_PARTIEL']);

    expect(standOpenings(report).get('S1')).toEqual({ joursOuverts: 2, postes: 7 });
    expect(standOpenings(null).size).toBe(0);
  });

  it('covers nothing before a plan holds anybody, then counts the held seats per stand', () => {
    const empty = planning([seat('p1', 'S1', null)]);
    expect(standCoverage(empty).size).toBe(0);

    const plan = planning([seat('p1', 'S1', 'a1'), seat('p2', 'S1', null)]);
    expect(standCoverage(plan).get('S1')).toEqual({ pourvus: 1, postes: 2 });
    expect(coverageRate(standCoverage(plan).get('S1'))).toBe(0.5);
    expect(coverageRate(undefined)).toBeNull();
  });
});
