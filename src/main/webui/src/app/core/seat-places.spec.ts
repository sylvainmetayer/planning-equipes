import { describe, expect, it } from 'vitest';
import { planningDays, syntheseJournee } from '../pages/journee/journee';
import { joursGrille } from '../pages/planning-grille/jours-grille';
import { buildTableauStands } from '../pages/planning-stand/planning-stand';
import { aggregateHours } from '../pages/repartition-heures/treemap';
import { standCoverage } from '../pages/stands/stand-order';
import { Animateur, Creneau, PlanningEvenement, PosteAffectation, Stand } from './models';
import { continuedSeatIds, placesOf, seatMinutes } from './seat-places';

// One timeslot repaired on the day (ADR 0066), the server's `SplitSeatFixture`:
// Cirque, two seats 09:00-12:00 — Ada held the first until 09:20 (`p1`), Bob
// holds the rest (`p1~0920`), Cyd the second (`p2`); Kiosque, one seat
// narrowed at 09:20 and empty (`p3~0920`). Three places, two staffed; six
// seat-hours on Cirque, two hours forty on Kiosque.

function stand(id: string, nom: string, typologie: string): Stand {
  return {
    id,
    nom,
    typologiesProposees: [typologie],
    effectifMin: 1,
    effectifMax: 2,
    reserveMajeurs: false,
    premium: false,
    niveauEffort: 'NORMAL',
    emplacement: null,
    indisponibilites: [],
    ouvertures: [],
    horaires: [],
  };
}

function person(id: string, prenom: string, nom: string): Animateur {
  return {
    id,
    prenom,
    nom,
    dateNaissance: '1990-01-01',
    manager: false,
    competences: {},
    souhaits: [],
    joursIndisponibles: [],
  };
}

const MORNING: Creneau = {
  id: 1,
  jour: 1,
  date: '2026-07-11',
  heureDebut: '09:00',
  heureFin: '12:00',
};
const CIRCUS = stand('CIRQUE', 'Cirque', 'STRATEGIE');
const KIOSK = stand('KIOSQUE', 'Kiosque', 'AMBIANCE');
const ADA = person('A-ADA', 'Ada', 'Lovelace');
const BOB = person('A-BOB', 'Bob', 'Kahn');
const CYD = person('A-CYD', 'Cyd', 'Charisse');

const SEATS: PosteAffectation[] = [
  {
    id: 'p1',
    stand: CIRCUS,
    creneau: MORNING,
    animateur: ADA,
    heureFinEffective: '09:20',
    dureeEffectiveMinutes: 20,
  },
  {
    id: 'p1~0920',
    stand: CIRCUS,
    creneau: MORNING,
    animateur: BOB,
    heureDebutEffective: '09:20',
    suiteDe: 'p1',
    dureeEffectiveMinutes: 160,
  },
  { id: 'p2', stand: CIRCUS, creneau: MORNING, animateur: CYD, dureeEffectiveMinutes: 180 },
  {
    id: 'p3~0920',
    stand: KIOSK,
    creneau: MORNING,
    animateur: null,
    heureDebutEffective: '09:20',
    dureeEffectiveMinutes: 160,
  },
];

describe('seat places (ADR 0066)', () => {
  it('counts a split seat on its continuation, and a narrowed seat as itself', () => {
    expect([...continuedSeatIds(SEATS)]).toEqual(['p1']);
    expect(placesOf(SEATS).map((poste) => poste.id)).toEqual(['p1~0920', 'p2', 'p3~0920']);
  });

  it('reads the minutes of each part on its own window', () => {
    expect(SEATS.map(seatMinutes)).toEqual([20, 160, 180, 160]);
    // Without the server's figure, the window says the same.
    expect(
      SEATS.map((poste) => seatMinutes({ ...poste, dureeEffectiveMinutes: undefined })),
    ).toEqual([20, 160, 180, 160]);
  });

  it('draws the Par stand grid with one place per split seat and every hour', () => {
    const jours = planningDays(SEATS);
    const { lignes, pied } = buildTableauStands(SEATS, jours, joursGrille(jours), {
      standsRetenus: null,
      animateur: '',
      recherche: '',
    });

    const [cirque, kiosque] = lignes;
    expect(cirque.cases[0].sieges).toBe(2);
    expect(cirque.cases[0].pourvus).toBe(2);
    expect(cirque.cases[0].statut).toBe('pourvu');
    expect(cirque.cases[0].noms).toEqual(['Ada L.', 'Cyd C.', 'Bob K.']);
    expect(cirque.synthese['heures']?.texte).toBe('6');
    expect(cirque.synthese['taux']?.texte).toBe('100 %');
    expect(kiosque.cases[0].statut).toBe('vide');
    expect(kiosque.synthese['heures']?.texte).toMatch(/^2[,.]7$/);
    expect(pied.cases).toEqual(['2/3']);
  });

  it('sums up the day with three seats, two held', () => {
    const synthese = syntheseJournee(SEATS, 1, null);

    expect(synthese.sieges).toBe(3);
    expect(synthese.pourvus).toBe(2);
    expect(synthese.vides).toBe(1);
    expect(synthese.animateurs).toBe(3);
  });

  it('gives the Stands table the coverage of places', () => {
    const planning = { animateurs: [ADA, BOB, CYD], postes: SEATS } as PlanningEvenement;

    expect(standCoverage(planning).get('CIRQUE')).toEqual({ postes: 2, pourvus: 2 });
    expect(standCoverage(planning).get('KIOSQUE')).toEqual({ postes: 1, pourvus: 0 });
  });

  it('sizes the treemap on the seat-minutes of both parts and the narrowed window', () => {
    const tree = aggregateHours({
      postes: SEATS,
      stands: [CIRCUS, KIOSK],
      grouping: 'stand',
      period: { kind: 'all' },
      emplacementId: null,
      typologieLabels: new Map(),
      labels: { root: 'Tout', noEmplacement: 'Sans lieu', noTypologie: 'Sans typologie' },
    });

    expect(tree.requiredMinutes).toBe(360 + 160);
    expect(tree.filledMinutes).toBe(360);
  });
});
