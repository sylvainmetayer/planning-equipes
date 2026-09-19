import { describe, expect, it } from 'vitest';
import {
  AnomalieOuverture,
  CelluleJourOuverture,
  LigneStandOuverture,
  RapportOuvertures,
} from '../../core/models';
import {
  anomaliesParStand,
  classeCellule,
  dureeCourte,
  filtrerStands,
  iconeAnomalie,
  largeurPourcent,
  standsEnAnomalie,
  synthese,
} from './ouvertures';

const LIBELLES = { heures: 'h', minutes: 'min' };

function cellule(patch: Partial<CelluleJourOuverture> = {}): CelluleJourOuverture {
  return {
    date: '2026-07-08',
    etat: 'OUVERT_TOTAL',
    source: 'DEFAUT',
    fenetres: [{ heureDebut: '10:00', heureFin: '20:00' }],
    minutesOuvertes: 600,
    minutesAmplitude: 600,
    postes: 2,
    creneaux: [{ creneauId: 1, tranche: 0, effectif: 2, partiel: false, segments: [] }],
    ...patch,
  };
}

function ligne(patch: Partial<LigneStandOuverture> = {}): LigneStandOuverture {
  return {
    standId: 'BOURSE',
    nom: 'Autres - Bourse',
    effectifMin: 2,
    effectifMax: 2,
    jours: [cellule()],
    minutesOuvertes: 600,
    postes: 2,
    modifieLe: '2026-09-06T10:00:00Z',
    ...patch,
  };
}

function rapport(patch: Partial<RapportOuvertures> = {}): RapportOuvertures {
  return {
    jours: [
      {
        date: '2026-07-08',
        jour: 1,
        heureDebut: '10:00',
        heureFin: '20:00',
        minutes: 600,
        nombreCreneaux: 1,
        creneaux: [
          {
            id: 1,
            tranche: 0,
            heureDebut: '10:00',
            heureFin: '20:00',
            couverturePause: false,
          },
        ],
      },
    ],
    stands: [ligne()],
    standsJamaisOuverts: 0,
    postesTotal: 2,
    anomalies: [],
    ...patch,
  };
}

describe('largeurPourcent', () => {
  it('rend la part d’amplitude couverte', () => {
    expect(largeurPourcent(cellule({ minutesOuvertes: 300, minutesAmplitude: 600 }))).toBe(50);
    expect(largeurPourcent(cellule())).toBe(100);
  });

  it('rend zéro pour un jour fermé', () => {
    expect(largeurPourcent(cellule({ etat: 'FERME', minutesOuvertes: 0, fenetres: [] }))).toBe(0);
  });

  /**
   * Le cas qui compte le plus visuellement : une ouverture d'une minute sur dix
   * heures doit rester visible, sinon la case ressemble à un jour fermé — alors
   * que c'est précisément l'anomalie à repérer.
   */
  it('garde un sliver visible pour une ouverture minuscule', () => {
    expect(largeurPourcent(cellule({ minutesOuvertes: 1, minutesAmplitude: 840 }))).toBe(4);
  });

  it('ne dépasse jamais cent pour cent', () => {
    expect(largeurPourcent(cellule({ minutesOuvertes: 900, minutesAmplitude: 600 }))).toBe(100);
  });
});

describe('dureeCourte', () => {
  it('formate minutes et heures', () => {
    expect(dureeCourte(0, LIBELLES)).toBe('—');
    expect(dureeCourte(45, LIBELLES)).toBe('45 min');
    expect(dureeCourte(120, LIBELLES)).toBe('2 h');
    expect(dureeCourte(135, LIBELLES)).toBe('2 h 15');
  });
});

describe('classeCellule', () => {
  it('porte l’état, et la provenance seulement quand elle est explicite', () => {
    expect(classeCellule(cellule())).toBe('ouverture-cellule etat-total');
    expect(classeCellule(cellule({ etat: 'OUVERT_PARTIEL', source: 'REGLE' }))).toBe(
      'ouverture-cellule etat-partiel source-regle',
    );
    expect(classeCellule(cellule({ etat: 'FERME', source: 'EXCEPTION' }))).toBe(
      'ouverture-cellule etat-ferme source-exception',
    );
  });
});

describe('filtrerStands', () => {
  const anomaly: AnomalieOuverture = {
    type: 'STAND_JAMAIS_OUVERT',
    standId: 'FERME-PARTOUT',
    standNom: 'Fermé partout',
    date: null,
    message: 'jamais ouvert',
  };
  const complet = rapport({
    stands: [
      ligne({ standId: 'COMPLET', nom: 'Complet' }),
      ligne({ standId: 'PARTIEL', nom: 'Partiel', jours: [cellule({ etat: 'OUVERT_PARTIEL' })] }),
      ligne({
        standId: 'FERME-PARTOUT',
        nom: 'Fermé partout',
        jours: [cellule({ etat: 'FERME' })],
        postes: 0,
      }),
    ],
    anomalies: [anomaly],
  });

  it('rend tout par défaut', () => {
    expect(filtrerStands(complet, 'TOUS', '').map((l) => l.standId)).toEqual([
      'COMPLET',
      'PARTIEL',
      'FERME-PARTOUT',
    ]);
  });

  // Le raccourci de validation : sur soixante stands, dérouler toute la grille
  // pour trouver les trois qui clochent annule l'intérêt de l'écran.
  it('ne garde que les stands en anomalie', () => {
    expect(filtrerStands(complet, 'ANOMALIES', '').map((l) => l.standId)).toEqual([
      'FERME-PARTOUT',
    ]);
  });

  it('isole les jours partiels et les jours fermés', () => {
    expect(filtrerStands(complet, 'PARTIELS', '').map((l) => l.standId)).toEqual(['PARTIEL']);
    expect(filtrerStands(complet, 'FERMES', '').map((l) => l.standId)).toEqual(['FERME-PARTOUT']);
  });

  it('cherche dans l’id comme dans le nom, sans casse', () => {
    expect(filtrerStands(complet, 'TOUS', 'partiel').map((l) => l.standId)).toEqual(['PARTIEL']);
    expect(filtrerStands(complet, 'TOUS', 'FERMÉ PARTOUT').map((l) => l.standId)).toEqual([
      'FERME-PARTOUT',
    ]);
    expect(filtrerStands(complet, 'TOUS', 'inconnu')).toEqual([]);
  });

  it('combine le filtre et la recherche', () => {
    expect(filtrerStands(complet, 'ANOMALIES', 'complet')).toEqual([]);
  });
});

describe('synthese', () => {
  it('compte les stands par état rencontré au moins une fois', () => {
    const bilan = synthese(
      rapport({
        stands: [
          ligne({ standId: 'A', jours: [cellule(), cellule({ etat: 'FERME' })] }),
          ligne({ standId: 'B', jours: [cellule({ etat: 'OUVERT_PARTIEL' })] }),
        ],
        standsJamaisOuverts: 1,
        postesTotal: 7,
        anomalies: [
          {
            type: 'SEGMENT_TROP_COURT',
            standId: 'A',
            standNom: 'A',
            date: '2026-07-08',
            message: 'court',
          },
        ],
      }),
    );

    expect(bilan).toEqual({
      stands: 2,
      jamaisOuverts: 1,
      avecJourFerme: 1,
      avecJourPartiel: 1,
      postesTotal: 7,
      anomalies: 1,
    });
  });
});

describe('anomaliesParStand / standsEnAnomalie', () => {
  it('regroupe par stand', () => {
    const anomalies: AnomalieOuverture[] = [
      {
        type: 'SEGMENT_TROP_COURT',
        standId: 'A',
        standNom: 'A',
        date: '2026-07-08',
        message: 'un',
      },
      {
        type: 'FENETRE_SANS_EFFET',
        standId: 'A',
        standNom: 'A',
        date: '2026-07-09',
        message: 'deux',
      },
      { type: 'STAND_JAMAIS_OUVERT', standId: 'B', standNom: 'B', date: null, message: 'trois' },
    ];

    expect(anomaliesParStand(anomalies).get('A')).toHaveLength(2);
    expect(standsEnAnomalie(anomalies)).toEqual(new Set(['A', 'B']));
  });
});

describe('iconeAnomalie', () => {
  it('donne une icône distincte par type, pour ne pas dépendre de la couleur seule', () => {
    const icones = (
      ['STAND_JAMAIS_OUVERT', 'FENETRE_SANS_EFFET', 'SEGMENT_TROP_COURT'] as const
    ).map(iconeAnomalie);
    expect(new Set(icones).size).toBe(3);
  });
});
