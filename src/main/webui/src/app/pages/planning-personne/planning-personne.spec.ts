import { describe, expect, it } from 'vitest';
import {
  Animateur,
  Creneau,
  HeuresRapport,
  LigneEquite,
  PlanningEvenement,
  PosteAffectation,
  RapportEquite,
  Stand,
} from '../../core/models';
import { NO_SORT, SortState } from '../../core/view-query-params';
import { planningDays } from '../journee/journee';
import {
  buildTableauPersonnes,
  summaryKeys,
  FiltresPersonne,
  readDensitePersonne,
  readPersonView,
} from './planning-personne';

function stand(id: string): Stand {
  return {
    id,
    nom: id,
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
  };
}

function personne(id: string, prenom: string, joursIndisponibles: string[] = []): Animateur {
  return {
    id,
    prenom,
    nom: 'X',
    dateNaissance: '1990-01-01',
    manager: false,
    competences: {},
    souhaits: [],
    joursIndisponibles,
  };
}

const MATIN: Creneau = {
  id: 1,
  jour: 1,
  date: '2026-09-05',
  heureDebut: '09:00',
  heureFin: '12:00',
};
const SOIR: Creneau = {
  id: 2,
  jour: 1,
  date: '2026-09-05',
  heureDebut: '18:00',
  heureFin: '22:00',
};
const LENDEMAIN: Creneau = {
  id: 3,
  jour: 2,
  date: '2026-09-06',
  heureDebut: '09:00',
  heureFin: '12:00',
};
const TIR = stand('Tir');
const DIXIT = stand('Dixit');
const ALICE = personne('a', 'Alice');
const BRUNO = personne('b', 'Bruno', ['2026-09-06']);
const CHLOE = personne('c', 'Chloé');

function siege(id: string, sur: Stand, creneau: Creneau, qui: Animateur | null): PosteAffectation {
  return { id, stand: sur, creneau, animateur: qui };
}

const PLANNING: PlanningEvenement = {
  animateurs: [ALICE, BRUNO, CHLOE],
  postes: [
    siege('p1', TIR, MATIN, ALICE),
    siege('p2', DIXIT, SOIR, ALICE),
    siege('p3', TIR, LENDEMAIN, ALICE),
    siege('p4', TIR, MATIN, BRUNO),
  ],
  score: null,
};

function ligneEquite(id: string, nom: string, heuresTotal: number): LigneEquite {
  return {
    animateurId: id,
    nom,
    heuresTotal,
    heuresParSemaine: { '2026-W36': heuresTotal },
    heuresSoiree: id === 'a' ? 2 : 0,
    heuresWeekEnd: heuresTotal,
    heuresJourFerie: 0,
    postes: 1,
    postesPenibles: 0,
    standsDistincts: 1,
    typologiesDistinctes: 1,
    emplacementsDistinctsParJourMax: 1,
    tauxSouhaits: 0,
    tauxAppreciation: 0,
    joursTravailles: 1,
    joursRepos: 1,
    plusLongueSerie: 1,
  };
}

const EQUITE: RapportEquite = {
  heureDebutSoiree: '20:00:00',
  semaines: ['2026-W36'],
  lignes: [ligneEquite('a', 'Alice X', 10), ligneEquite('b', 'Bruno X', 3)],
  syntheses: { heuresTotal: { mediane: 6.5, min: 3, max: 10, ecartType: 3.5 } },
  colonnesSolveur: [],
};

const HEURES: HeuresRapport = {
  heureDebutSoiree: '20:00:00',
  semaines: ['2026-W36'],
  animateurs: [
    {
      animateurId: 'a',
      nom: 'Alice X',
      heuresParSemaine: {},
      total: 10,
      heuresDimanche: 3,
      heuresJourFerie: 0,
      heuresDimancheFerie: 0,
      heuresNuit: 0,
      heuresSoiree: 2,
    },
  ],
};

function tableau(filtres: Partial<FiltresPersonne> = {}) {
  return buildTableauPersonnes(PLANNING, planningDays(PLANNING.postes), EQUITE, HEURES, {
    animateur: '',
    standsRetenus: null,
    recherche: '',
    sansReposSeulement: false,
    toutesColonnes: false,
    tri: NO_SORT,
    ...filtres,
  });
}

describe('Par personne', () => {
  it('reads its view and its density tolerantly, the Jours de repos keys included', () => {
    expect(readPersonView('frise')).toBe('frise');
    expect(readPersonView('treemap')).toBe('grille');
    expect(readDensitePersonne('compact')).toBe('compact');
    expect(readDensitePersonne('confort')).toBe('detail');
  });

  it('puts the stand and the hours in the cell, coloured by the day', () => {
    const alice = tableau().lignes.find((ligne) => ligne.animateurId === 'a')!;

    expect(alice.cases[0].stands).toBe('Tir, Dixit');
    expect(alice.cases[0].heures).toBe('09:00–12:00, 18:00–22:00');
    expect(alice.cases[0].classe).toContain('planning-personne-travaille');
    // Past 20:00, the one evening of the application.
    expect(alice.cases[0].classe).toContain('planning-personne-soiree');
    expect(alice.cases[0].posteId).toBe('p1');
  });

  it('tells rest from unavailable, and a rest day opens nothing', () => {
    const lignes = tableau().lignes;
    const bruno = lignes.find((ligne) => ligne.animateurId === 'b')!;
    const chloe = lignes.find((ligne) => ligne.animateurId === 'c')!;

    expect(bruno.cases[1].statut).toBe('indisponible');
    expect(chloe.cases[0].statut).toBe('repos');
    expect(chloe.cases[0].active).toBe(false);
  });

  it("joins the Équité columns and the payroll's Sunday and night, person by person", () => {
    const { lignes, colonnes } = tableau({ toutesColonnes: true });
    const alice = lignes.find((ligne) => ligne.animateurId === 'a')!;

    expect(alice.synthese['heuresTotal']?.texte).toBe('10');
    expect(alice.synthese['ecartMediane']?.texte).toBe('+3,5');
    expect(alice.synthese['heuresDimanche']?.texte).toBe('3');
    expect(colonnes.map((colonne) => colonne.label)).toContain('Nuit (paie)');
    // Chloé holds no seat: the reports know nothing of her.
    expect(lignes.find((ligne) => ligne.animateurId === 'c')!.synthese['heuresTotal']?.texte).toBe(
      '—',
    );
  });

  it('hides the columns every line leaves at zero, until asked for', () => {
    const cachees = tableau();
    expect(cachees.vides).toEqual(
      expect.arrayContaining(['heuresJourFerie', 'heuresNuit', 'tauxSouhaits']),
    );
    expect(cachees.colonnes.map((colonne) => colonne.key)).not.toContain('heuresNuit');

    const all = tableau({ toutesColonnes: true });
    expect(all.colonnes.map((colonne) => colonne.key)).toEqual(summaryKeys(EQUITE));
  });

  it('sorts on any summary column, both ways, and on the name', () => {
    const ordre = (tri: SortState): string[] =>
      tableau({ tri }).lignes.map((ligne) => ligne.animateurId);

    expect(ordre({ active: 'heuresTotal', direction: 'desc' })).toEqual(['a', 'b', 'c']);
    expect(ordre({ active: 'heuresTotal', direction: 'asc' })).toEqual(['c', 'b', 'a']);
    expect(ordre({ active: 'nom', direction: 'asc' })).toEqual(['a', 'b', 'c']);
  });

  it('keeps the person, the stands and the text the filters name', () => {
    expect(tableau({ animateur: 'b' }).lignes.map((ligne) => ligne.animateurId)).toEqual(['b']);
    expect(
      tableau({ standsRetenus: new Set(['Dixit']) }).lignes.map((ligne) => ligne.animateurId),
    ).toEqual(['a']);
    expect(tableau({ recherche: 'chlo' }).lignes.map((ligne) => ligne.animateurId)).toEqual(['c']);
  });

  it('counts who rests each day, at the foot', () => {
    expect(tableau().pied.cases).toEqual(['1', '1']);
  });
});
