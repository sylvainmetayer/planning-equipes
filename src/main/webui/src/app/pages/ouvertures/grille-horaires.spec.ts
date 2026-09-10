import { describe, expect, it } from 'vitest';
import { RapportOuvertures } from '../../core/models';
import {
  Cellules,
  cellulesDepuis,
  cellulesInertes,
  cellulesPartielles,
  countCopied,
  collerBloc,
  colonnes,
  deplacement,
  ecrireCellule,
  jourDeReference,
  libelleColonne,
  readCell,
  recopierJour,
  saisie,
  standsModifies,
  valeursLigne,
} from './grille-horaires';

/**
 * Two days: 10-12, 14-20 and a nocturne 20-00 on the first, 10-12 and 14-20
 * on the second — the nocturne is what a day copy must not invent.
 */
function rapport(): RapportOuvertures {
  const jour = (date: string, jour: number, ids: number[], nocturne: boolean) => ({
    date,
    jour,
    heureDebut: '10:00',
    heureFin: nocturne ? '00:00' : '20:00',
    minutes: nocturne ? 720 : 480,
    nombreCreneaux: ids.length,
    creneaux: ids.map((id, rang) => ({
      id,
      heureDebut: ['10:00', '14:00', '20:00'][rang],
      heureFin: ['12:00', '20:00', '00:00'][rang],
      famille: 0,
      couverturePause: false,
    })),
  });
  const cellule = (
    creneauId: number,
    effectif: number | null,
    partiel = false,
    horsFamille = false,
  ) => ({
    creneauId,
    effectif,
    partiel,
    horsFamille,
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
    jours: [jour('2026-07-08', 1, [1, 2, 3], true), jour('2026-07-09', 2, [4, 5], false)],
    stands: [
      {
        standId: 'A',
        nom: 'Stand A',
        effectifMin: 2,
        jours: [
          jourStand('2026-07-08', [cellule(1, 2), cellule(2, 4), cellule(3, 4)]),
          jourStand('2026-07-09', [cellule(4, 2), cellule(5, 4)]),
        ],
        minutesOuvertes: 0,
        postes: 0,
        modifieLe: '2026-09-06T10:00:00Z',
      },
      {
        standId: 'B',
        nom: 'Stand B',
        effectifMin: 1,
        jours: [
          jourStand('2026-07-08', [cellule(1, 1, true), cellule(2, null), cellule(3, null)]),
          jourStand('2026-07-09', [cellule(4, null), cellule(5, null)]),
        ],
        minutesOuvertes: 0,
        postes: 0,
        modifieLe: '2026-09-06T10:00:00Z',
      },
    ],
    standsJamaisOuverts: 0,
    postesTotal: 0,
    anomalies: [],
  };
}

const STANDS = ['A', 'B'];

describe('colonnes et cellules', () => {
  it('déroule une colonne par créneau, jour après jour, avec son rang dans le jour', () => {
    const cols = colonnes(rapport());
    expect(cols.map((colonne) => colonne.creneauId)).toEqual([1, 2, 3, 4, 5]);
    expect(cols.map((colonne) => colonne.rang)).toEqual([0, 1, 2, 0, 1]);
    expect(cols.map(libelleColonne)).toEqual(['10-12', '14-20', '20-00', '10-12', '14-20']);
  });

  it('lit les cases du rapport, fermé compris', () => {
    const cellules = cellulesDepuis(rapport());
    expect(valeursLigne(cellules, 'A', colonnes(rapport()))).toEqual([2, 4, 4, 2, 4]);
    expect(valeursLigne(cellules, 'B', colonnes(rapport()))).toEqual([1, null, null, null, null]);
    expect(cellulesPartielles(rapport())).toEqual(new Set(['B#1']));
  });

  // L'API envoie « HH:mm:ss » : tester la forme brute laissait toutes les heures
  // finir par « :00 », et 13:30-14:30 s'affichait « 13-14 ».
  it('libelle une demi-heure avec ses minutes, telle que l’API l’envoie', () => {
    expect(
      libelleColonne({
        date: '',
        creneauId: 1,
        heureDebut: '13:30:00',
        heureFin: '14:30:00',
        rang: 0,
      }),
    ).toBe('13:30-14:30');
    expect(
      libelleColonne({
        date: '',
        creneauId: 1,
        heureDebut: '10:00:00',
        heureFin: '12:00:00',
        rang: 0,
      }),
    ).toBe('10-12');
  });

  it('relève les cases d’une autre famille de relais', () => {
    const rapportAvecFamille = rapport();
    rapportAvecFamille.stands[1].jours[0].creneaux[0].horsFamille = true;

    expect(cellulesInertes(rapportAvecFamille)).toEqual(new Set(['B#1']));
  });
});

describe('lireCellule', () => {
  it('lit un effectif, et trois façons d’écrire une case fermée', () => {
    expect(readCell('3')).toBe(3);
    expect(readCell(' 12 ')).toBe(12);
    expect(readCell('')).toBeNull();
    expect(readCell('0')).toBeNull();
    expect(readCell('-')).toBeNull();
  });

  it('ne lit pas ce qui n’est pas une valeur', () => {
    expect(readCell('a')).toBeUndefined();
    expect(readCell('2.5')).toBeUndefined();
    expect(readCell('-1')).toBeUndefined();
  });
});

describe('standsModifies et saisie', () => {
  it('ne nomme que les stands dont une case a changé, et envoie toutes leurs cases', () => {
    const reference = cellulesDepuis(rapport());
    const modifie = ecrireCellule(reference, { standId: 'B', creneauId: 2 }, 3);

    expect(standsModifies(reference, reference)).toEqual([]);
    expect(standsModifies(modifie, reference)).toEqual(['B']);
    expect(saisie(modifie, ['B'])).toEqual([
      {
        standId: 'B',
        modifieLe: null,
        cellules: [
          { creneauId: 1, effectif: 1 },
          { creneauId: 2, effectif: 3 },
          { creneauId: 3, effectif: null },
          { creneauId: 4, effectif: null },
          { creneauId: 5, effectif: null },
        ],
      },
    ]);
  });

  it('n’écrit ni ne renvoie une case inerte', () => {
    const reference = cellulesDepuis(rapport());
    const cols = colonnes(rapport());
    const inertes = new Set(['A#2']);

    const colle = collerBloc(
      reference,
      '9\t9',
      { standId: 'A', creneauId: 1 },
      STANDS,
      cols,
      inertes,
    );
    expect(valeursLigne(colle, 'A', cols)).toEqual([9, 4, 4, 2, 4]);

    const envoye = saisie(colle, ['A'], inertes);
    expect(envoye[0].cellules.map((cellule) => cellule.creneauId)).toEqual([1, 3, 4, 5]);
  });

  it('compte les cases qu’une recopie a changées', () => {
    const cols = colonnes(rapport());
    const depart = cellulesDepuis(rapport());
    const after = recopierJour(
      ecrireCellule(depart, { standId: 'A', creneauId: 4 }, 7),
      '2026-07-09',
      ['A'],
      cols,
    );

    expect(countCopied(depart, after, cols)).toBeGreaterThan(0);
    expect(countCopied(depart, depart, cols)).toBe(0);
  });

  it('ne modifie jamais la carte reçue', () => {
    const reference = cellulesDepuis(rapport());
    ecrireCellule(reference, { standId: 'A', creneauId: 1 }, 9);
    expect(reference.get('A')!.get(1)).toBe(2);
  });

  it('revenir à la valeur d’origine efface la modification', () => {
    const reference = cellulesDepuis(rapport());
    const allerRetour = ecrireCellule(
      ecrireCellule(reference, { standId: 'A', creneauId: 1 }, 9),
      { standId: 'A', creneauId: 1 },
      2,
    );
    expect(standsModifies(allerRetour, reference)).toEqual([]);
  });
});

describe('deplacement', () => {
  const cols = colonnes(rapport());

  it('descend sur Entrée et flèche bas, et s’arrête à la dernière ligne', () => {
    expect(deplacement('Enter', { standId: 'A', creneauId: 2 }, STANDS, cols)).toEqual({
      standId: 'B',
      creneauId: 2,
    });
    expect(deplacement('ArrowDown', { standId: 'B', creneauId: 2 }, STANDS, cols)).toEqual({
      standId: 'B',
      creneauId: 2,
    });
    expect(deplacement('ArrowUp', { standId: 'B', creneauId: 2 }, STANDS, cols)).toEqual({
      standId: 'A',
      creneauId: 2,
    });
  });

  it('passe d’un jour à l’autre sur les flèches latérales, Home et End', () => {
    expect(deplacement('ArrowRight', { standId: 'A', creneauId: 3 }, STANDS, cols)).toEqual({
      standId: 'A',
      creneauId: 4,
    });
    expect(deplacement('ArrowLeft', { standId: 'A', creneauId: 1 }, STANDS, cols)).toEqual({
      standId: 'A',
      creneauId: 1,
    });
    expect(deplacement('Home', { standId: 'A', creneauId: 5 }, STANDS, cols)).toEqual({
      standId: 'A',
      creneauId: 1,
    });
    expect(deplacement('End', { standId: 'A', creneauId: 1 }, STANDS, cols)).toEqual({
      standId: 'A',
      creneauId: 5,
    });
  });

  it('laisse passer les autres touches et une case inconnue', () => {
    expect(deplacement('a', { standId: 'A', creneauId: 1 }, STANDS, cols)).toBeNull();
    expect(deplacement('Enter', { standId: 'Z', creneauId: 1 }, STANDS, cols)).toBeNull();
    expect(deplacement('Enter', { standId: 'A', creneauId: 99 }, STANDS, cols)).toBeNull();
  });
});

describe('collerBloc', () => {
  const cols = colonnes(rapport());

  it('pose un bloc tabulé depuis la case active, ligne par ligne', () => {
    const cellules = collerBloc(
      cellulesDepuis(rapport()),
      '5\t6\n7\t\n',
      { standId: 'A', creneauId: 2 },
      STANDS,
      cols,
    );

    expect(valeursLigne(cellules, 'A', cols)).toEqual([2, 5, 6, 2, 4]);
    expect(valeursLigne(cellules, 'B', cols)).toEqual([1, 7, null, null, null]);
  });

  it('ignore ce qui déborde de la grille et ce qui n’est pas une valeur', () => {
    const cellules = collerBloc(
      cellulesDepuis(rapport()),
      'x\t9\t9\t9\n1\n1\n1',
      { standId: 'B', creneauId: 4 },
      STANDS,
      cols,
    );

    expect(valeursLigne(cellules, 'B', cols)).toEqual([1, null, null, null, 9]);
    expect(valeursLigne(cellules, 'A', cols)).toEqual([2, 4, 4, 2, 4]);
  });

  it('lit un zéro ou un tiret collé comme une case fermée', () => {
    const cellules = collerBloc(
      cellulesDepuis(rapport()),
      '0\t-',
      { standId: 'A', creneauId: 1 },
      STANDS,
      cols,
    );
    expect(valeursLigne(cellules, 'A', cols)).toEqual([null, null, 4, 2, 4]);
  });
});

describe('recopierJour', () => {
  const cols = colonnes(rapport());

  it('recopie un jour sur les autres, créneau à créneau par ses heures, sans inventer la nocturne', () => {
    const depart: Cellules = ecrireCellule(
      cellulesDepuis(rapport()),
      { standId: 'A', creneauId: 4 },
      7,
    );
    const cellules = recopierJour(depart, '2026-07-09', ['A'], cols);

    expect(valeursLigne(cellules, 'A', cols)).toEqual([7, 4, 4, 7, 4]);
  });

  it('ne touche que les stands nommés', () => {
    const cellules = recopierJour(cellulesDepuis(rapport()), '2026-07-08', ['B'], cols);

    expect(valeursLigne(cellules, 'B', cols)).toEqual([1, null, null, 1, null]);
    expect(valeursLigne(cellules, 'A', cols)).toEqual([2, 4, 4, 2, 4]);
  });

  it('rend la même carte quand rien ne change ou que le jour est inconnu', () => {
    const depart = cellulesDepuis(rapport());
    expect(recopierJour(depart, '2026-07-08', ['A'], cols)).toBe(depart);
    expect(recopierJour(depart, '2026-08-01', STANDS, cols)).toBe(depart);
  });

  it('prend pour référence le premier jour renseigné, ou le premier jour', () => {
    const cellules = cellulesDepuis(rapport());
    expect(jourDeReference(cellules, 'B', cols)).toBe('2026-07-08');
    const vide = ecrireCellule(cellules, { standId: 'B', creneauId: 1 }, null);
    expect(jourDeReference(vide, 'B', cols)).toBe('2026-07-08');
    expect(jourDeReference(ecrireCellule(vide, { standId: 'B', creneauId: 5 }, 2), 'B', cols)).toBe(
      '2026-07-09',
    );
    expect(jourDeReference(cellules, 'B', [])).toBeNull();
  });
});
