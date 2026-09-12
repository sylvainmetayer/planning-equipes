import { describe, expect, it } from 'vitest';
import { RapportOuvertures } from '../../core/models';
import {
  Cellules,
  ColonneGrille,
  aplatissement,
  cellulesDepuis,
  cellulesPartielles,
  collerBloc,
  colonnes,
  countCopied,
  deplacement,
  ecrireCellule,
  jourDeReference,
  libelleColonne,
  propagerClefs,
  propagerScission,
  readCell,
  recopierJour,
  saisie,
  scinder,
  segmentsPartiels,
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
      tranche: 0,
      heureDebut: ['10:00', '14:00', '20:00'][rang],
      heureFin: ['12:00', '20:00', '00:00'][rang],
      couverturePause: false,
    })),
  });
  const cellule = (creneauId: number, effectif: number | null, partiel = false) => ({
    creneauId,
    tranche: 0,
    effectif,
    partiel,
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

/** The column id of a créneau of the fixture, each in one piece. */
function id(creneauId: number): string {
  return colonnes(rapport()).find((colonne) => colonne.creneauId === creneauId)!.colonneId;
}

describe('colonnes et cellules', () => {
  it('déroule une colonne par créneau, jour après jour, avec son rang dans le jour', () => {
    const cols = colonnes(rapport());
    expect(cols.map((colonne) => colonne.creneauId)).toEqual([1, 2, 3, 4, 5]);
    expect(cols.map((colonne) => colonne.colonneId)).toEqual([
      '1@10:00-12:00',
      '2@14:00-20:00',
      '3@20:00-00:00',
      '4@10:00-12:00',
      '5@14:00-20:00',
    ]);
    expect(cols.map((colonne) => colonne.rang)).toEqual([0, 1, 2, 0, 1]);
    expect(cols.map(libelleColonne)).toEqual(['10-12', '14-20', '20-00', '10-12', '14-20']);
  });

  it('lit les cases du rapport, fermé compris', () => {
    const cellules = cellulesDepuis(rapport());
    expect(valeursLigne(cellules, 'A', colonnes(rapport()))).toEqual([2, 4, 4, 2, 4]);
    expect(valeursLigne(cellules, 'B', colonnes(rapport()))).toEqual([1, null, null, null, null]);
    expect(cellulesPartielles(rapport())).toEqual(new Set(['B#1@10:00-12:00']));
  });

  // L'API envoie « HH:mm:ss » : tester la forme brute laissait toutes les heures
  // finir par « :00 », et 13:30-14:30 s'affichait « 13-14 ».
  it('libelle une demi-heure avec ses minutes, telle que l’API l’envoie', () => {
    expect(
      libelleColonne({
        date: '',
        creneauId: 1,
        colonneId: '1@13:30-14:30',
        heureDebut: '13:30:00',
        heureFin: '14:30:00',
        rang: 0,
      }),
    ).toBe('13:30-14:30');
    expect(
      libelleColonne({
        date: '',
        creneauId: 1,
        colonneId: '1@10:00-12:00',
        heureDebut: '10:00:00',
        heureFin: '12:00:00',
        rang: 0,
      }),
    ).toBe('10-12');
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
    const modifie = ecrireCellule(reference, { standId: 'B', colonneId: id(2) }, 3);

    expect(standsModifies(reference, reference)).toEqual([]);
    expect(standsModifies(modifie, reference)).toEqual(['B']);
    expect(saisie(modifie, ['B'], colonnes(rapport()))).toEqual([
      {
        standId: 'B',
        modifieLe: null,
        cellules: [
          { creneauId: 1, heureDebut: '10:00', heureFin: '12:00', effectif: 1 },
          { creneauId: 2, heureDebut: '14:00', heureFin: '20:00', effectif: 3 },
          { creneauId: 3, heureDebut: '20:00', heureFin: '00:00', effectif: null },
          { creneauId: 4, heureDebut: '10:00', heureFin: '12:00', effectif: null },
          { creneauId: 5, heureDebut: '14:00', heureFin: '20:00', effectif: null },
        ],
        aplatir: false,
      },
    ]);
  });

  it('compte les cases qu’une recopie a changées', () => {
    const cols = colonnes(rapport());
    const depart = cellulesDepuis(rapport());
    const after = recopierJour(
      ecrireCellule(depart, { standId: 'A', colonneId: id(4) }, 7),
      '2026-07-09',
      ['A'],
      cols,
    );

    expect(countCopied(depart, after, cols)).toBeGreaterThan(0);
    expect(countCopied(depart, depart, cols)).toBe(0);
  });

  it('ne modifie jamais la carte reçue', () => {
    const reference = cellulesDepuis(rapport());
    ecrireCellule(reference, { standId: 'A', colonneId: id(1) }, 9);
    expect(reference.get('A')!.get(id(1))).toBe(2);
  });

  it('revenir à la valeur d’origine efface la modification', () => {
    const reference = cellulesDepuis(rapport());
    const allerRetour = ecrireCellule(
      ecrireCellule(reference, { standId: 'A', colonneId: id(1) }, 9),
      { standId: 'A', colonneId: id(1) },
      2,
    );
    expect(standsModifies(allerRetour, reference)).toEqual([]);
  });
});

describe('deplacement', () => {
  const cols = colonnes(rapport());

  it('descend sur Entrée et flèche bas, et s’arrête à la dernière ligne', () => {
    expect(deplacement('Enter', { standId: 'A', colonneId: id(2) }, STANDS, cols)).toEqual({
      standId: 'B',
      colonneId: id(2),
    });
    expect(deplacement('ArrowDown', { standId: 'B', colonneId: id(2) }, STANDS, cols)).toEqual({
      standId: 'B',
      colonneId: id(2),
    });
    expect(deplacement('ArrowUp', { standId: 'B', colonneId: id(2) }, STANDS, cols)).toEqual({
      standId: 'A',
      colonneId: id(2),
    });
  });

  it('passe d’un jour à l’autre sur les flèches latérales, Home et End', () => {
    expect(deplacement('ArrowRight', { standId: 'A', colonneId: id(3) }, STANDS, cols)).toEqual({
      standId: 'A',
      colonneId: id(4),
    });
    expect(deplacement('ArrowLeft', { standId: 'A', colonneId: id(1) }, STANDS, cols)).toEqual({
      standId: 'A',
      colonneId: id(1),
    });
    expect(deplacement('Home', { standId: 'A', colonneId: id(5) }, STANDS, cols)).toEqual({
      standId: 'A',
      colonneId: id(1),
    });
    expect(deplacement('End', { standId: 'A', colonneId: id(1) }, STANDS, cols)).toEqual({
      standId: 'A',
      colonneId: id(5),
    });
  });

  it('laisse passer les autres touches et une case inconnue', () => {
    expect(deplacement('a', { standId: 'A', colonneId: id(1) }, STANDS, cols)).toBeNull();
    expect(deplacement('Enter', { standId: 'Z', colonneId: id(1) }, STANDS, cols)).toBeNull();
    expect(
      deplacement('Enter', { standId: 'A', colonneId: '99@00:00-01:00' }, STANDS, cols),
    ).toBeNull();
  });
});

describe('collerBloc', () => {
  const cols = colonnes(rapport());

  it('pose un bloc tabulé depuis la case active, ligne par ligne', () => {
    const cellules = collerBloc(
      cellulesDepuis(rapport()),
      '5\t6\n7\t\n',
      { standId: 'A', colonneId: id(2) },
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
      { standId: 'B', colonneId: id(4) },
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
      { standId: 'A', colonneId: id(1) },
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
      { standId: 'A', colonneId: id(4) },
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
    const vide = ecrireCellule(cellules, { standId: 'B', colonneId: id(1) }, null);
    expect(jourDeReference(vide, 'B', cols)).toBe('2026-07-08');
    expect(
      jourDeReference(ecrireCellule(vide, { standId: 'B', colonneId: id(5) }, 2), 'B', cols),
    ).toBe('2026-07-09');
    expect(jourDeReference(cellules, 'B', [])).toBeNull();
  });
});

describe('segmentsPartiels et aplatissement', () => {
  const grille: ColonneGrille[] = [
    {
      date: '2026-07-08',
      creneauId: 1,
      colonneId: '1@14:00-20:00',
      heureDebut: '14:00',
      heureFin: '20:00',
      rang: 0,
    },
    {
      date: '2026-07-08',
      creneauId: 2,
      colonneId: '2@20:00-00:00',
      heureDebut: '20:00',
      heureFin: '00:00',
      rang: 1,
    },
  ];

  it('prices flattening as the créneau at the highest headcount minus what the stretches cover', () => {
    const segments = new Map([
      // 4 from 14:00 to 19:00, then 2: flattening puts 4 on the last hour, +2 h.
      [
        'DIV#1@14:00-20:00',
        [
          { heureDebut: '14:00', heureFin: '19:00', effectif: 4 },
          { heureDebut: '19:00', heureFin: '20:00', effectif: 2 },
        ],
      ],
      // 3 until 21:00 then 5 till midnight: +2 on one hour, +2 h — and midnight counts as 24:00.
      [
        'DIV#2@20:00-00:00',
        [
          { heureDebut: '20:00', heureFin: '21:00', effectif: 3 },
          { heureDebut: '21:00', heureFin: '00:00', effectif: 5 },
        ],
      ],
      // Open one hour out of six at 1: +5 h.
      ['FLIP7#1@14:00-20:00', [{ heureDebut: '14:00', heureFin: '15:00', effectif: 1 }]],
    ]);

    expect(aplatissement(segments, grille)).toEqual({
      stands: ['DIV', 'FLIP7'],
      cases: 3,
      minutes: 9 * 60,
    });
  });

  it('ignores a cell whose créneau is not a column, and prices nothing when nothing is partial', () => {
    expect(
      aplatissement(
        new Map([['X#99@10:00-12:00', [{ heureDebut: '10:00', heureFin: '11:00', effectif: 1 }]]]),
        grille,
      ),
    ).toEqual({ stands: [], cases: 0, minutes: 0 });
    expect(aplatissement(new Map(), grille)).toEqual({ stands: [], cases: 0, minutes: 0 });
  });

  it('collects the stretches of the partial cells only, and sends aplatir with the body', () => {
    const rapport = {
      jours: [
        {
          date: '2026-07-08',
          jour: 1,
          heureDebut: '14:00',
          heureFin: '00:00',
          minutes: 600,
          nombreCreneaux: 2,
          creneaux: [
            {
              id: 1,
              tranche: 0,
              heureDebut: '14:00',
              heureFin: '20:00',
              couverturePause: false,
            },
            {
              id: 2,
              tranche: 0,
              heureDebut: '20:00',
              heureFin: '00:00',
              couverturePause: false,
            },
          ],
        },
      ],
      stands: [
        {
          standId: 'A',
          nom: 'A',
          effectifMin: 1,
          minutesOuvertes: 0,
          postes: 0,
          modifieLe: null,
          jours: [
            {
              date: '2026-07-08',
              etat: 'OUVERT_PARTIEL',
              source: 'REGLE',
              fenetres: [],
              minutesOuvertes: 0,
              minutesAmplitude: 0,
              postes: 0,
              creneaux: [
                {
                  creneauId: 1,
                  tranche: 0,
                  effectif: 4,
                  partiel: true,
                  segments: [{ heureDebut: '14:00', heureFin: '19:00', effectif: 4 }],
                },
                {
                  creneauId: 2,
                  tranche: 0,
                  effectif: 2,
                  partiel: false,
                  segments: [{ heureDebut: '20:00', heureFin: '00:00', effectif: 2 }],
                },
              ],
            },
          ],
        },
      ],
      standsJamaisOuverts: 0,
      postesTotal: 0,
      anomalies: [],
    } as unknown as RapportOuvertures;

    expect(Array.from(segmentsPartiels(rapport).keys())).toEqual(['A#1@14:00-20:00']);
    const cols = colonnes(rapport);
    const corps = saisie(cellulesDepuis(rapport), ['A'], cols, { aplatir: true });
    expect(corps[0].aplatir).toBe(true);
    expect(saisie(cellulesDepuis(rapport), ['A'], cols)[0].aplatir).toBe(false);
  });
});

describe('scinder', () => {
  const cols = colonnes(rapport());

  it('cuts a column in two at an hour strictly inside it, and renumbers the day', () => {
    const coupees = scinder(cols, '2@14:00-20:00', '19:00')!;

    expect(coupees.map((colonne) => colonne.colonneId)).toEqual([
      '1@10:00-12:00',
      '2@14:00-19:00',
      '2@19:00-20:00',
      '3@20:00-00:00',
      '4@10:00-12:00',
      '5@14:00-20:00',
    ]);
    expect(coupees.map((colonne) => colonne.rang)).toEqual([0, 1, 2, 3, 0, 1]);
    expect(coupees[1].creneauId).toBe(2);
    expect(coupees[2].heureDebut).toBe('19:00');
  });

  it('cuts a midnight-crossing column past midnight, and refuses an edge or an outside hour', () => {
    expect(scinder(cols, '3@20:00-00:00', '23:00')!.map((colonne) => colonne.colonneId)).toContain(
      '3@23:00-00:00',
    );
    expect(scinder(cols, '2@14:00-20:00', '14:00')).toBeNull();
    expect(scinder(cols, '2@14:00-20:00', '20:00')).toBeNull();
    expect(scinder(cols, '2@14:00-20:00', '09:00')).toBeNull();
    expect(scinder(cols, 'inconnue', '15:00')).toBeNull();
  });

  it('carries every value under the old column onto both new ones, and so with the flags', () => {
    const cellules = propagerScission(cellulesDepuis(rapport()), '2@14:00-20:00', [
      '2@14:00-19:00',
      '2@19:00-20:00',
    ]);
    const coupees = scinder(cols, '2@14:00-20:00', '19:00')!;
    expect(valeursLigne(cellules, 'A', coupees)).toEqual([2, 4, 4, 4, 2, 4]);
    expect(cellules.get('A')!.has('2@14:00-20:00')).toBe(false);
    // Nothing is modified by the cut itself.
    expect(standsModifies(cellules, cellules)).toEqual([]);

    const clefs = propagerClefs(new Map([['B#2@14:00-20:00', 'x']]), '2@14:00-20:00', [
      '2@14:00-19:00',
      '2@19:00-20:00',
    ]);
    expect(Array.from(clefs.keys())).toEqual(['B#2@14:00-19:00', 'B#2@19:00-20:00']);
  });

  it('sends a cut column as a cell with its own bounds', () => {
    const coupees = scinder(cols, '2@14:00-20:00', '19:00')!;
    const cellules = ecrireCellule(
      propagerScission(cellulesDepuis(rapport()), '2@14:00-20:00', [
        '2@14:00-19:00',
        '2@19:00-20:00',
      ]),
      { standId: 'A', colonneId: '2@19:00-20:00' },
      2,
    );
    const corps = saisie(cellules, ['A'], coupees);
    expect(corps[0].cellules.slice(1, 3)).toEqual([
      { creneauId: 2, heureDebut: '14:00', heureFin: '19:00', effectif: 4 },
      { creneauId: 2, heureDebut: '19:00', heureFin: '20:00', effectif: 2 },
    ]);
  });
});
