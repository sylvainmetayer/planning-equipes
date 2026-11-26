import { describe, expect, it } from 'vitest';
import { EtatGenerateur, RapportOuvertures } from '../../core/models';
import { cellulesDepuis, colonnes } from './grille-horaires';
import {
  accesGrilleGabarits,
  colonnesGabarits,
  ecrireColonneGabarit,
  libelleColonneGabarit,
  resumeGabarits,
  valeurGabarit,
} from './grille-gabarits';

/**
 * Three days: two « Journée » (10-12 then 14-20) and one « Nocturne » that
 * adds a 21-00 slot. Stand A works both, stand B only the afternoons — and
 * on the second Journée it takes one more person, which is the drift the
 * per-template grid has to admit rather than flatten.
 */
function rapport(): RapportOuvertures {
  const creneau = (id: number, heureDebut: string, heureFin: string) => ({
    id,
    tranche: 0,
    heureDebut,
    heureFin,
    couverturePause: false,
  });
  const jour = (date: string, numero: number, creneaux: ReturnType<typeof creneau>[]) => ({
    date,
    jour: numero,
    heureDebut: '10:00',
    heureFin: creneaux.at(-1)!.heureFin,
    minutes: 600,
    nombreCreneaux: creneaux.length,
    ferie: null,
    creneaux,
  });
  const cellule = (creneauId: number, effectif: number | null) => ({
    creneauId,
    tranche: 0,
    effectif,
    partiel: false,
    segments: [],
  });
  const jourStand = (date: string, creneaux: ReturnType<typeof cellule>[]) => ({
    date,
    etat: 'OUVERT_TOTAL' as const,
    source: 'REGLE' as const,
    fenetres: [],
    minutesOuvertes: 0,
    minutesAmplitude: 0,
    postes: 0,
    creneaux,
  });
  return {
    jours: [
      jour('2026-07-08', 1, [creneau(1, '10:00', '12:00'), creneau(2, '14:00', '20:00')]),
      jour('2026-07-09', 2, [creneau(3, '10:00', '12:00'), creneau(4, '14:00', '20:00')]),
      jour('2026-07-10', 3, [
        creneau(5, '10:00', '12:00'),
        creneau(6, '14:00', '20:00'),
        creneau(7, '21:00', '00:00'),
      ]),
    ],
    stands: [
      {
        standId: 'A',
        nom: 'Stand A',
        effectifMin: 2,
        jours: [
          jourStand('2026-07-08', [cellule(1, 2), cellule(2, 2)]),
          jourStand('2026-07-09', [cellule(3, 2), cellule(4, 3)]),
          jourStand('2026-07-10', [cellule(5, 2), cellule(6, 2), cellule(7, 1)]),
        ],
        minutesOuvertes: 0,
        postes: 0,
        modifieLe: '2026-09-12T10:00:00Z',
      },
      {
        standId: 'B',
        nom: 'Stand B',
        effectifMin: 1,
        jours: [
          jourStand('2026-07-08', [cellule(1, null), cellule(2, 1)]),
          jourStand('2026-07-09', [cellule(3, null), cellule(4, 1)]),
          jourStand('2026-07-10', [cellule(5, null), cellule(6, 1), cellule(7, null)]),
        ],
        minutesOuvertes: 0,
        postes: 0,
        modifieLe: '2026-09-12T10:00:00Z',
      },
    ],
    standsJamaisOuverts: 0,
    postesTotal: 0,
    anomalies: [],
  };
}

function etat(): EtatGenerateur {
  return {
    gabarits: [
      {
        id: 4,
        nom: 'Journée',
        vacations: [
          { heureDebut: '10:00:00', heureFin: '12:00:00', couverturePause: false },
          { heureDebut: '14:00:00', heureFin: '20:00:00', couverturePause: false },
        ],
      },
      {
        id: 6,
        nom: 'Nocturne',
        vacations: [
          { heureDebut: '10:00:00', heureFin: '12:00:00', couverturePause: false },
          { heureDebut: '14:00:00', heureFin: '20:00:00', couverturePause: false },
          { heureDebut: '21:00:00', heureFin: '00:00:00', couverturePause: false },
        ],
      },
      { id: 9, nom: 'Montage', vacations: [] },
    ],
    calendrier: [
      { date: '2026-07-08', gabaritId: 4 },
      { date: '2026-07-09', gabaritId: 4 },
      { date: '2026-07-10', gabaritId: 6 },
    ],
    datesEnEcart: [],
    datesSousConsigne: [],
  };
}

describe('les colonnes par gabarit', () => {
  it('donne une colonne par vacation, et aucune pour un gabarit que rien ne gouverne', () => {
    const colonnesJT = colonnesGabarits(rapport(), etat());

    expect(colonnesJT.map((colonne) => colonne.colonneId)).toEqual([
      'jt:4@10:00-12:00',
      'jt:4@14:00-20:00',
      'jt:6@10:00-12:00',
      'jt:6@14:00-20:00',
      'jt:6@21:00-00:00',
    ]);
    expect(colonnesJT[0].nomGabarit).toBe('Journée');
    expect(libelleColonneGabarit(colonnesJT[4])).toBe('21:00-00:00');
  });

  it('couvre chaque date que sa gabarit gouverne', () => {
    const colonnesJT = colonnesGabarits(rapport(), etat());
    const grille = colonnes(rapport());
    const parId = new Map(grille.map((colonne) => [colonne.colonneId, colonne.date]));

    expect(colonnesJT[1].colonnes.map((id) => parId.get(id))).toEqual(['2026-07-08', '2026-07-09']);
    expect(colonnesJT[4].colonnes.map((id) => parId.get(id))).toEqual(['2026-07-10']);
    expect(colonnesJT.every((colonne) => colonne.datesSansColonne.length === 0)).toBe(true);
  });

  it('ne propose rien sans gabarits', () => {
    expect(colonnesGabarits(rapport(), null)).toEqual([]);
  });

  it('laisse hors grille une date dont le créneau est coupé en deux', () => {
    const sansColonne = rapport();
    sansColonne.jours[1].creneaux[1] = {
      id: 4,
      tranche: 0,
      heureDebut: '14:00',
      heureFin: '17:00',
      couverturePause: false,
    };

    const colonnesJT = colonnesGabarits(sansColonne, etat());

    expect(colonnesJT[1].colonnes).toHaveLength(1);
    expect(colonnesJT[1].datesSansColonne).toEqual(['2026-07-09']);
  });
});

describe('la valeur d’une colonne par gabarit', () => {
  it('rend ce que toutes ses dates disent', () => {
    const cellules = cellulesDepuis(rapport());
    const colonnesJT = colonnesGabarits(rapport(), etat());

    expect(valeurGabarit(cellules, 'A', colonnesJT[0])).toBe(2);
    expect(valeurGabarit(cellules, 'B', colonnesJT[0])).toBeNull();
    expect(valeurGabarit(cellules, 'B', colonnesJT[1])).toBe(1);
  });

  // The whole point of not flattening: two Journées that differ read as a
  // drift, and the dated grid stays the place to settle it.
  it('annonce un écart quand deux dates de la même gabarit divergent', () => {
    const cellules = cellulesDepuis(rapport());
    const colonnesJT = colonnesGabarits(rapport(), etat());

    expect(valeurGabarit(cellules, 'A', colonnesJT[1])).toBe('ecart');
  });
});

describe('l’écriture par gabarit', () => {
  it('écrit la même valeur sur chaque date du gabarit', () => {
    const cellules = cellulesDepuis(rapport());
    const colonnesJT = colonnesGabarits(rapport(), etat());

    const apres = ecrireColonneGabarit(cellules, 'A', colonnesJT[1], 5);

    expect(valeurGabarit(apres, 'A', colonnesJT[1])).toBe(5);
    // The Nocturne shares those hours and is a different template: untouched.
    expect(valeurGabarit(apres, 'A', colonnesJT[3])).toBe(2);
    // And the other stand never moves.
    expect(valeurGabarit(apres, 'B', colonnesJT[1])).toBe(1);
  });

  it('ferme les dates d’un gabarit d’un seul geste', () => {
    const cellules = cellulesDepuis(rapport());
    const colonnesJT = colonnesGabarits(rapport(), etat());

    const apres = ecrireColonneGabarit(cellules, 'A', colonnesJT[0], null);

    expect(valeurGabarit(apres, 'A', colonnesJT[0])).toBeNull();
  });

  it('ne touche rien quand la colonne ne couvre aucune date', () => {
    const cellules = cellulesDepuis(rapport());
    const colonnesJT = colonnesGabarits(rapport(), etat());
    const orpheline = { ...colonnesJT[0], colonnes: [], datesSansColonne: ['2026-07-08'] };

    expect(ecrireColonneGabarit(cellules, 'A', orpheline, 9)).toBe(cellules);
  });
});

describe('le résumé de la grille par gabarit', () => {
  it('dit combien de cases datées les colonnes remplacent, et ce qu’elles ne savent pas dire', () => {
    const cellules = cellulesDepuis(rapport());
    const colonnesJT = colonnesGabarits(rapport(), etat());

    const resume = resumeGabarits(cellules, ['A', 'B'], colonnesJT);

    // 5 colonnes × 2 stands, contre 7 créneaux × 2 stands par date.
    expect(resume.colonnes).toBe(10);
    expect(resume.cellulesDatees).toBe(14);
    expect(resume.ecarts).toBe(1);
    expect(resume.datesSansColonne).toBe(0);
  });
});

describe("l'accès à la grille par gabarit", () => {
  const acces = () =>
    accesGrilleGabarits(new Map(colonnesGabarits(rapport(), etat()).map((c) => [c.colonneId, c])));

  it('lit la valeur commune aux dates de la colonne', () => {
    const cellules = cellulesDepuis(rapport());
    expect(acces().read(cellules, 'A', 'jt:4@10:00-12:00')).toBe(2);
    expect(acces().read(cellules, 'B', 'jt:4@10:00-12:00')).toBeNull();
  });

  it("ne dit rien à recopier d'une colonne dont les dates divergent", () => {
    // « A » takes 2 on 8 July and 3 on the 9th, under the same template.
    const cellules = cellulesDepuis(rapport());
    expect(acces().read(cellules, 'A', 'jt:4@14:00-20:00')).toBeUndefined();
  });

  it("ne dit rien à recopier d'une colonne inconnue", () => {
    const cellules = cellulesDepuis(rapport());
    expect(acces().read(cellules, 'A', 'jt:99@10:00-12:00')).toBeUndefined();
  });

  it('écrit sur toutes les dates de la colonne, et nulle part ailleurs', () => {
    const cellules = acces().write(cellulesDepuis(rapport()), 'B', 'jt:4@10:00-12:00', 4);
    expect(acces().read(cellules, 'B', 'jt:4@10:00-12:00')).toBe(4);
    expect(cellules.get('B')?.get('5@10:00-12:00')).toBeNull();
  });
});
