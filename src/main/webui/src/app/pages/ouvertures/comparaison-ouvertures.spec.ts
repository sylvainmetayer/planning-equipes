import { describe, expect, it } from 'vitest';
import {
  CelluleCreneauOuverture,
  HoraireStand,
  LigneStandOuverture,
  RapportOuvertures,
  SourceHoraire,
  Stand,
} from '../../core/models';
import {
  MAX_STANDS_COMPARES,
  addStands,
  ruleKey,
  comparer,
  readStandsParam,
  reglesComparees,
  writeStandsParam,
} from './comparaison-ouvertures';

/** Two days of two columns: 10-12 and 14-18. */
function jours(): RapportOuvertures['jours'] {
  return ['2026-07-08', '2026-07-09'].map((date, index) => ({
    date,
    jour: index + 1,
    heureDebut: '10:00',
    heureFin: '18:00',
    minutes: 480,
    nombreCreneaux: 2,
    creneaux: [
      {
        id: index * 10 + 1,
        tranche: 0,
        heureDebut: '10:00',
        heureFin: '12:00',
        couverturePause: false,
      },
      {
        id: index * 10 + 2,
        tranche: 0,
        heureDebut: '14:00',
        heureFin: '18:00',
        couverturePause: false,
      },
    ],
  }));
}

type Case = number | null | { effectif: number; segments: [string, string, number][] };

/** A cell as the report carries it: a headcount, closed, or partial stretches. */
function cellule(creneauId: number, valeur: Case): CelluleCreneauOuverture {
  if (valeur === null || typeof valeur === 'number') {
    return { creneauId, tranche: 0, effectif: valeur, partiel: false, segments: [] };
  }
  return {
    creneauId,
    tranche: 0,
    effectif: valeur.effectif,
    partiel: true,
    segments: valeur.segments.map(([heureDebut, heureFin, effectif]) => ({
      heureDebut,
      heureFin,
      effectif,
    })),
  };
}

/** A stand from its four cells, day by day; `source` is what decided it, which must not matter. */
function ligne(
  standId: string,
  cases: [Case, Case, Case, Case],
  source: SourceHoraire = 'REGLE',
): LigneStandOuverture {
  const jour = (date: string, ids: number[], valeurs: Case[]) => ({
    date,
    etat: 'OUVERT_TOTAL' as const,
    source,
    fenetres: [],
    minutesOuvertes: 0,
    minutesAmplitude: 480,
    postes: 0,
    creneaux: ids.map((id, index) => cellule(id, valeurs[index])),
  });
  return {
    standId,
    nom: `Buvette ${standId}`,
    effectifMin: 1,
    jours: [
      jour('2026-07-08', [1, 2], [cases[0], cases[1]]),
      jour('2026-07-09', [11, 12], [cases[2], cases[3]]),
    ],
    minutesOuvertes: 0,
    postes: 0,
    modifieLe: null,
  };
}

function rapport(...stands: LigneStandOuverture[]): RapportOuvertures {
  return { jours: jours(), stands, standsJamaisOuverts: 0, postesTotal: 0, anomalies: [] };
}

describe('comparer', () => {
  it('finds no difference between two stands typed differently but opening the same way', () => {
    // One decided by a rule, the other by dated exceptions: the solver reads the same thing.
    const comparaison = comparer(
      rapport(ligne('1', [2, 3, 2, 3], 'REGLE'), ligne('2', [2, 3, 2, 3], 'EXCEPTION')),
      ['1', '2'],
      '1',
    );

    expect(comparaison.jours.every((jour) => !jour.ecart)).toBe(true);
    expect(comparaison.synthese).toEqual([
      { standId: '2', nom: 'Buvette 2', daysWithGap: 0, firstGap: null },
    ]);
  });

  it('types each difference: open against closed, hours, headcount', () => {
    const reference = ligne('1', [2, 3, 2, 3]);
    const autre = ligne('2', [null, { effectif: 3, segments: [['14:00', '16:00', 3]] }, 2, 2]);
    const comparaison = comparer(rapport(reference, autre), ['1', '2'], '1');
    const cases = comparaison.jours.flatMap((jour) =>
      jour.colonnes.map((colonne) => colonne.cases[1]),
    );

    expect(cases.map((each) => each.ecarts)).toEqual([['OUVERTURE'], ['HEURES'], [], ['EFFECTIF']]);
    expect(cases[0].description).toBe('fermé là où la référence ouvre 10:00–12:00');
    expect(cases[1].description).toBe('ouvert 14:00–16:00 au lieu de 14:00–18:00');
    expect(cases[1].text).toBe('14:00–16:00 ×3');
    expect(cases[3].description).toBe('effectif 2 au lieu de 3');
    // The reference itself never differs from itself.
    expect(comparaison.jours[0].colonnes[0].cases[0]).toMatchObject({
      reference: true,
      ecarts: [],
      text: '2',
    });
  });

  it('counts the days a stand differs on and words its first difference', () => {
    const comparaison = comparer(
      rapport(ligne('1', [2, 3, 2, 3]), ligne('2', [2, 3, 2, 2]), ligne('3', [2, 2, 2, 2])),
      ['1', '2', '3'],
      null,
    );

    expect(comparaison.referenceId).toBe('1');
    expect(comparaison.synthese).toEqual([
      {
        standId: '2',
        nom: 'Buvette 2',
        daysWithGap: 1,
        firstGap: 'le 2026-07-09 de 14:00 – 18:00, effectif 2 au lieu de 3',
      },
      {
        standId: '3',
        nom: 'Buvette 3',
        daysWithGap: 2,
        firstGap: 'le 2026-07-08 de 14:00 – 18:00, effectif 2 au lieu de 3',
      },
    ]);
    expect(comparaison.jours.map((jour) => jour.ecart)).toEqual([true, true]);
  });

  it('shows a day no stand opens on, without a difference', () => {
    const comparaison = comparer(
      rapport(ligne('1', [null, null, 2, 3]), ligne('2', [null, null, 2, 3])),
      ['1', '2'],
      '1',
    );
    expect(comparaison.jours).toHaveLength(2);
    expect(comparaison.jours[0].ecart).toBe(false);
    expect(comparaison.jours[0].colonnes[0].cases[1].text).toBe('—');
  });

  it('sets aside a stand the report no longer knows, and hands the reference on', () => {
    const comparaison = comparer(
      rapport(ligne('1', [2, 3, 2, 3]), ligne('2', [2, 3, 2, 3])),
      ['disparu', '2', '1', '2'],
      'disparu',
    );

    expect(comparaison.inconnus).toEqual(['disparu']);
    expect(comparaison.stands.map((stand) => stand.standId)).toEqual(['2', '1']);
    expect(comparaison.referenceId).toBe('2');
  });

  it('has nothing to compare without a report', () => {
    expect(comparer(null, ['1', '2'], null)).toEqual({
      referenceId: null,
      stands: [],
      jours: [],
      synthese: [],
      inconnus: [],
    });
  });
});

function horaire(overrides: Partial<HoraireStand> = {}): HoraireStand {
  return {
    id: 1,
    mode: 'OUVERTURE',
    jours: 'TOUS',
    joursSemaine: [],
    dateDebut: null,
    dateFin: null,
    dates: [],
    fenetres: [{ heureDebut: '14:00', heureFin: null, effectif: 3 }],
    motif: null,
    ...overrides,
  };
}

function stand(id: string, overrides: Partial<Stand> = {}): Stand {
  return {
    id,
    nom: id,
    typologiesProposees: [],
    effectifMin: 1,
    effectifMax: 3,
    reserveMajeurs: false,
    premium: false,
    niveauEffort: 'NORMAL',
    emplacement: null,
    indisponibilites: [],
    ouvertures: [],
    horaires: [],
    ...overrides,
  };
}

describe('reglesComparees', () => {
  it('matches a rule by what it does, not by its id, its reason or the order of its days', () => {
    expect(
      ruleKey(
        horaire({ id: 1, motif: 'a', jours: 'JOURS_SEMAINE', joursSemaine: ['MONDAY', 'FRIDAY'] }),
      ),
    ).toBe(
      ruleKey(
        horaire({ id: 9, motif: 'b', jours: 'JOURS_SEMAINE', joursSemaine: ['FRIDAY', 'MONDAY'] }),
      ),
    );
    expect(ruleKey(horaire())).not.toBe(
      ruleKey(horaire({ fenetres: [{ heureDebut: '14:00', heureFin: null, effectif: 2 }] })),
    );
  });

  it('marks the rules the reference lacks, lists the ones a stand lacks, and the exceptions by date', () => {
    const soir = horaire({ id: 2, fenetres: [{ heureDebut: '18:00', heureFin: '22:00' }] });
    const reference = stand('R', { horaires: [horaire(), soir] });
    const autre = stand('A', {
      horaires: [horaire({ id: 5 }), horaire({ id: 6, mode: 'FERMETURE' })],
      indisponibilites: [
        { id: 1, date: '2026-07-10', heureDebut: '14:00', heureFin: null, motif: null },
      ],
      ouvertures: [
        {
          id: 2,
          date: '2026-07-09',
          heureDebut: '10:00',
          heureFin: '12:00',
          motif: null,
          effectif: null,
        },
      ],
    });

    const [colonneReference, colonneAutre] = reglesComparees([autre, reference], ['R', 'A'], 'R');

    expect(colonneReference.reference).toBe(true);
    expect(colonneReference.reglesManquantes).toEqual([]);
    expect(colonneAutre.regles.map((regle) => regle.chezReference)).toEqual([true, false]);
    expect(colonneAutre.reglesManquantes).toEqual([soir]);
    expect(colonneAutre.exceptions.map((entree) => [entree.date, entree.ouverture])).toEqual([
      ['2026-07-09', true],
      ['2026-07-10', false],
    ]);
  });
});

describe('selection', () => {
  it('reads the address tolerantly and writes it back', () => {
    expect(readStandsParam(' a, b ,,a,c ')).toEqual(['a', 'b', 'c']);
    expect(readStandsParam(null)).toEqual([]);
    expect(readStandsParam('1,2,3,4,5,6,7,8,9,10')).toHaveLength(MAX_STANDS_COMPARES);
    expect(writeStandsParam(['a', 'b'])).toBe('a,b');
    expect(writeStandsParam([])).toBeNull();
  });

  it('refuses past eight stands, and says how many were left out', () => {
    const ajout = addStands(['1', '2', '3', '4', '5', '6'], ['2', '7', '8', '9', '10']);
    expect(ajout.selection).toEqual(['1', '2', '3', '4', '5', '6', '7', '8']);
    expect(ajout.refuses).toBe(2);
  });
});
