import { describe, expect, it } from 'vitest';
import { AnimateurFragilite, CompetenceRare, RapportFragilite } from '../../core/models';
import {
  classeSeverite,
  filtrerAnimateurs,
  filtrerCompetences,
  heure,
  iconeSeverite,
  libelleJour,
  lireFiltre,
  readView,
  synthese,
} from './fragilite';

function animateur(partial: Partial<AnimateurFragilite>): AnimateurFragilite {
  return {
    animateurId: 'a1',
    nom: 'Alice Martin',
    ninja: false,
    affectations: 3,
    postesEffondres: 3,
    postesIrremplacables: 0,
    competencesRares: 0,
    severite: 'MODEREE',
    postes: [],
    postesNonDetailles: 0,
    ...partial,
  };
}

function competence(partial: Partial<CompetenceRare>): CompetenceRare {
  return {
    standId: 'S1',
    standNom: 'Escape game',
    creneauId: 1,
    date: '2026-07-08',
    jour: 1,
    heureDebut: '10:00:00',
    heureFin: '12:00:00',
    typologies: ['ESCAPE'],
    specialistes: 1,
    animateurId: 'a1',
    nom: 'Alice Martin',
    renforts: 0,
    pourvu: true,
    severite: 'ELEVEE',
    ...partial,
  };
}

function rapport(partial: Partial<RapportFragilite>): RapportFragilite {
  return {
    animateurs: [],
    competencesRares: [],
    totalCompetencesRares: 0,
    groupesSansSpecialiste: 0,
    groupesAnalyses: 0,
    groupesDejaSousEffectif: 0,
    animateursIrremplacables: 0,
    ninjaConfigure: false,
    aucunAnimateur: false,
    message: '',
    ...partial,
  };
}

describe('readView / lireFiltre', () => {
  it('tombe sur la vue par défaut pour toute valeur inconnue', () => {
    expect(readView(null)).toBe('ANIMATEURS');
    expect(readView('n_importe_quoi')).toBe('ANIMATEURS');
    expect(readView('COMPETENCES')).toBe('COMPETENCES');
  });

  it('tombe sur le filtre par défaut pour toute valeur inconnue', () => {
    expect(lireFiltre(null)).toBe('TOUS');
    expect(lireFiltre('CRITIQUES')).toBe('CRITIQUES');
    expect(lireFiltre('critiques')).toBe('TOUS');
  });
});

describe('filtrerAnimateurs', () => {
  const source = rapport({
    animateurs: [
      animateur({ animateurId: 'a1', nom: 'Alice Martin', postesIrremplacables: 2 }),
      animateur({ animateurId: 'a2', nom: 'Bruno Léger', postesIrremplacables: 0 }),
    ],
  });

  it('conserve le classement rendu par le serveur', () => {
    expect(filtrerAnimateurs(source, 'TOUS', '').map((ligne) => ligne.animateurId)).toEqual([
      'a1',
      'a2',
    ]);
  });

  it('ne garde que les irremplaçables sur le filtre critique', () => {
    expect(filtrerAnimateurs(source, 'CRITIQUES', '').map((ligne) => ligne.animateurId)).toEqual([
      'a1',
    ]);
  });

  it('cherche sans accent ni casse, sur le nom comme sur l’identifiant', () => {
    expect(filtrerAnimateurs(source, 'TOUS', 'LEGER')).toHaveLength(1);
    expect(filtrerAnimateurs(source, 'TOUS', 'a1')).toHaveLength(1);
  });

  it('ne casse pas sur un rapport absent', () => {
    expect(filtrerAnimateurs(null, 'TOUS', '')).toEqual([]);
  });
});

describe('filtrerCompetences', () => {
  const source = rapport({
    competencesRares: [
      competence({
        standId: 'S1',
        severite: 'CRITIQUE',
        specialistes: 0,
        animateurId: null,
        nom: null,
      }),
      competence({ standId: 'S2', standNom: 'Jeux d’ambiance', severite: 'MODEREE', renforts: 2 }),
    ],
  });

  it('ne garde que les critiques sur le filtre critique', () => {
    expect(filtrerCompetences(source, 'CRITIQUES', '').map((ligne) => ligne.standId)).toEqual([
      'S1',
    ]);
  });

  it('cherche aussi sur la typologie et sur le nom du seul spécialiste', () => {
    expect(filtrerCompetences(source, 'TOUS', 'escape')).toHaveLength(2);
    expect(filtrerCompetences(source, 'TOUS', 'alice')).toHaveLength(1);
  });
});

describe('synthese', () => {
  it('reprend les compteurs du serveur sans les recalculer', () => {
    const bilan = synthese(
      rapport({
        animateurs: [animateur({})],
        animateursIrremplacables: 4,
        totalCompetencesRares: 7,
        groupesSansSpecialiste: 2,
        groupesAnalyses: 120,
        groupesDejaSousEffectif: 3,
        ninjaConfigure: true,
      }),
    );

    expect(bilan).toEqual({
      animateurs: 1,
      irremplacables: 4,
      competencesRares: 7,
      sansSpecialiste: 2,
      groupes: 120,
      dejaSousEffectif: 3,
      ninjaConfigure: true,
    });
  });
});

describe('rendu d’une sévérité', () => {
  it('donne une classe et une icône par niveau', () => {
    expect(classeSeverite('CRITIQUE')).toBe('fragilite-critique');
    expect(classeSeverite('ELEVEE')).toBe('fragilite-elevee');
    expect(classeSeverite('MODEREE')).toBe('fragilite-moderee');
    expect(
      new Set([iconeSeverite('CRITIQUE'), iconeSeverite('ELEVEE'), iconeSeverite('MODEREE')]).size,
    ).toBe(3);
  });
});

describe('libellés courts', () => {
  it('raccourcit la date et l’heure', () => {
    expect(libelleJour('2026-07-08')).toBe('08/07');
    expect(heure('10:00:00')).toBe('10:00');
  });
});
