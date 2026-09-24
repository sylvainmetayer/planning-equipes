import { describe, expect, it } from 'vitest';
import { Animateur, Creneau, PosteAffectation, RapportPauses, Stand } from '../../core/models';
import {
  alignerAnimateurs,
  alignerStands,
  filterComparedAnimateurs,
  filterComparedStands,
  formatDelta,
  isComparable,
  defaultComparisonDay,
  JourEvenement,
  jourSemaineVoisine,
  lignesSynthese,
  planningDays,
  resolveComparison,
  syntheseJournee,
} from './journee';

const SAMEDI_12 = { id: 1, jour: 1, date: '2026-07-12', heureDebut: '10:00', heureFin: '12:00' };
const SAMEDI_19 = { id: 2, jour: 8, date: '2026-07-19', heureDebut: '10:00', heureFin: '12:00' };

function stand(id: string, nom = id): Stand {
  return { id, nom } as Stand;
}

function animateur(id: string, prenom: string, nom: string): Animateur {
  return { id, prenom, nom } as Animateur;
}

let compteur = 0;
function poste(
  creneau: Creneau,
  standPoste: Stand,
  qui: Animateur | null = null,
): PosteAffectation {
  compteur += 1;
  return { id: `p${compteur}`, creneau, stand: standPoste, animateur: qui };
}

const alice = animateur('a1', 'Alice', 'Martin');
const bob = animateur('a2', 'Bob', 'Durand');
const jeux = stand('S1', 'Jeux');
const buvette = stand('S2', 'Buvette');

function jour(n: number, date: string | null): JourEvenement {
  return { jour: n, date, key: date ?? `J${n}`, title: `J${n}` };
}

describe('resolveComparison', () => {
  const jours = [jour(1, '2026-07-12'), jour(8, '2026-07-19')];

  it('resolves the second day by its key', () => {
    expect(resolveComparison(jours, jours[0], '2026-07-19')).toEqual({
      jour: jours[1],
      refus: null,
    });
  });

  it('ignores the day already on screen, and says why', () => {
    expect(resolveComparison(jours, jours[0], '2026-07-12').refus).toBe('identique');
  });

  it('ignores a day the plan does not hold, and says why', () => {
    expect(resolveComparison(jours, jours[0], '2030-01-01').refus).toBe('inconnu');
  });

  it('compares undated days like the others', () => {
    const undated = [jour(3, null), jour(4, null)];
    expect(resolveComparison(undated, undated[0], 'J4').jour).toBe(undated[1]);
  });

  it('is off without a param', () => {
    expect(resolveComparison(jours, jours[0], null)).toEqual({ jour: null, refus: null });
  });
});

describe('isComparable', () => {
  it('compares on the calendar and the rail only', () => {
    expect(isComparable('calendrier')).toBe(true);
    expect(isComparable('rail')).toBe(true);
    expect(isComparable('carte')).toBe(false);
    expect(isComparable('pauses')).toBe(false);
    expect(isComparable('changements')).toBe(false);
  });
});

describe('jourSemaineVoisine', () => {
  const jours = [jour(1, '2026-07-12'), jour(2, '2026-07-13'), jour(8, '2026-07-19')];

  it('finds the same weekday a week later when the event holds it', () => {
    expect(jourSemaineVoisine(jours, jours[0], 1)).toBe(jours[2]);
    expect(jourSemaineVoisine(jours, jours[2], -1)).toBe(jours[0]);
  });

  it('answers nothing when that day is not in the event', () => {
    expect(jourSemaineVoisine(jours, jours[1], 1)).toBeNull();
    expect(jourSemaineVoisine(jours, jours[0], -1)).toBeNull();
  });

  it('moves an undated day by seven day numbers', () => {
    const undated = [jour(1, null), jour(8, null)];
    expect(jourSemaineVoisine(undated, undated[0], 1)).toBe(undated[1]);
  });
});

describe('defaultComparisonDay', () => {
  it('prefers the same weekday a week later, then the next day', () => {
    const jours = [jour(1, '2026-07-12'), jour(2, '2026-07-13'), jour(8, '2026-07-19')];
    expect(defaultComparisonDay(jours, jours[0])).toBe(jours[2]);
    expect(defaultComparisonDay(jours, jours[1])).toBe(jours[2]);
    expect(defaultComparisonDay(jours, jours[2])).toBe(jours[0]);
  });

  it('falls back on the previous day at the end of a short event', () => {
    const jours = [jour(1, '2026-07-12'), jour(2, '2026-07-13')];
    expect(defaultComparisonDay(jours, jours[1])).toBe(jours[0]);
    expect(defaultComparisonDay([jours[0]], jours[0])).toBeNull();
  });
});

describe('syntheseJournee', () => {
  it('counts seats, filled, empty, staff and stands of one day', () => {
    const postes = [
      poste(SAMEDI_12, jeux, alice),
      poste(SAMEDI_12, jeux, null),
      poste(SAMEDI_12, buvette, alice),
      poste(SAMEDI_19, jeux, bob),
    ];

    expect(syntheseJournee(postes, 1, null)).toEqual({
      sieges: 3,
      pourvus: 2,
      vides: 1,
      unrelievedBreaks: null,
      animateurs: 1,
      stands: 2,
    });
  });

  it('counts the breaks of that day with nobody to relieve', () => {
    const rapport = {
      journees: [
        {
          jour: 1,
          sequences: [{ pausesDues: [{ relaisDisponible: false }, { relaisDisponible: true }] }],
        },
        { jour: 8, sequences: [{ pausesDues: [{ relaisDisponible: false }] }] },
      ],
    } as unknown as RapportPauses;

    expect(syntheseJournee([], 1, rapport).unrelievedBreaks).toBe(1);
  });

  it('narrows to the stands of the lines a filter keeps, breaks included', () => {
    const postes = [
      poste(SAMEDI_12, jeux, alice),
      poste(SAMEDI_12, jeux, null),
      poste(SAMEDI_12, buvette, bob),
    ];
    const rapport = {
      journees: [
        {
          jour: 1,
          animateurId: 'a1',
          sequences: [{ pausesDues: [{ relaisDisponible: false, standId: 'S1' }] }],
        },
        {
          jour: 1,
          animateurId: 'a2',
          sequences: [{ pausesDues: [{ relaisDisponible: false, standId: 'S2' }] }],
        },
      ],
    } as unknown as RapportPauses;

    expect(syntheseJournee(postes, 1, rapport, { standIds: new Set(['S1']) })).toEqual({
      sieges: 2,
      pourvus: 1,
      vides: 1,
      unrelievedBreaks: 1,
      animateurs: 1,
      stands: 1,
    });
  });

  it('narrows to the seats held by the animateurs of the lines a filter keeps, leaving the seats to fill unknown', () => {
    const postes = [
      poste(SAMEDI_12, jeux, alice),
      poste(SAMEDI_12, jeux, null),
      poste(SAMEDI_12, buvette, bob),
    ];

    expect(syntheseJournee(postes, 1, null, { animateurIds: new Set(['a2']) })).toMatchObject({
      sieges: null,
      pourvus: 1,
      vides: null,
      animateurs: 1,
      stands: 1,
    });
  });

  it('shows the seats to fill without any assignment when nothing was solved', () => {
    const synthese = syntheseJournee([poste(SAMEDI_12, jeux), poste(SAMEDI_12, jeux)], 1, null);
    expect(synthese).toMatchObject({ sieges: 2, pourvus: 0, vides: 2, animateurs: 0 });
  });
});

describe('lignesSynthese', () => {
  it('gives the écart B − A, and reads it by the direction of each figure', () => {
    const a = { sieges: 10, pourvus: 8, vides: 2, unrelievedBreaks: 1, animateurs: 6, stands: 3 };
    const b = { sieges: 12, pourvus: 8, vides: 4, unrelievedBreaks: 0, animateurs: 6, stands: 4 };

    const lignes = Object.fromEntries(lignesSynthese(a, b).map((ligne) => [ligne.key, ligne]));

    expect(lignes['sieges']).toMatchObject({ delta: 2, tonalite: 'neutre' });
    expect(lignes['pourvus']).toMatchObject({ delta: 0, tonalite: 'nul' });
    expect(lignes['vides']).toMatchObject({ delta: 2, tonalite: 'pire' });
    expect(lignes['unrelievedBreaks']).toMatchObject({ delta: -1, tonalite: 'mieux' });
    expect(lignes['stands']).toMatchObject({ delta: 1, tonalite: 'neutre' });
  });

  it('leaves the écart unknown when a side could not be read', () => {
    const a = { sieges: 1, pourvus: 1, vides: 0, unrelievedBreaks: null, animateurs: 1, stands: 1 };
    const ligne = lignesSynthese(a, a).find((candidate) => candidate.key === 'unrelievedBreaks');
    expect(ligne).toMatchObject({ delta: null, tonalite: 'inconnu' });
  });

  it('leaves the seats to fill and the empty seats unknown under an animateur scope', () => {
    const a = {
      sieges: null,
      pourvus: 2,
      vides: null,
      unrelievedBreaks: 0,
      animateurs: 2,
      stands: 1,
    };
    const lignes = Object.fromEntries(lignesSynthese(a, a).map((ligne) => [ligne.key, ligne]));
    expect(lignes['sieges']).toMatchObject({ a: null, b: null, delta: null, tonalite: 'inconnu' });
    expect(lignes['vides']).toMatchObject({ a: null, b: null, delta: null, tonalite: 'inconnu' });
  });
});

describe('formatDelta', () => {
  it('signs the écart', () => {
    expect(formatDelta(3)).toBe('+3');
    expect(formatDelta(-2)).toBe('−2');
    expect(formatDelta(0)).toBe('=');
    expect(formatDelta(null)).toBe('—');
  });
});

describe('alignerStands', () => {
  it('gives a stand open on one day only a line on both sides, closed on the other', () => {
    const postes = [
      poste(SAMEDI_12, jeux, alice),
      poste(SAMEDI_12, buvette, bob),
      poste(SAMEDI_19, jeux, bob),
    ];

    const lignes = alignerStands(postes, 1, 8);

    expect(lignes.map((ligne) => ligne.standNom)).toEqual(['Buvette', 'Jeux']);
    expect(lignes[0].a?.sieges).toBe(1);
    expect(lignes[0].b).toBeNull();
    expect(lignes[0].different).toBe(true);
  });

  it('holds two days alike when windows, seats and coverage match, whoever holds them', () => {
    const lignes = alignerStands(
      [poste(SAMEDI_12, jeux, alice), poste(SAMEDI_19, jeux, bob)],
      1,
      8,
    );

    expect(lignes).toHaveLength(1);
    expect(lignes[0].different).toBe(false);
    expect(lignes[0].a?.plages[0]).toEqual({
      heureDebut: '10:00',
      heureFin: '12:00',
      sieges: 1,
      noms: ['Alice Martin'],
    });
  });

  it('marks a stand whose coverage differs', () => {
    const lignes = alignerStands([poste(SAMEDI_12, jeux, alice), poste(SAMEDI_19, jeux)], 1, 8);
    expect(lignes[0].different).toBe(true);
    expect(lignes[0].b).toMatchObject({ sieges: 1, pourvus: 0 });
  });

  it('marks a stand whose hours differ', () => {
    const soir = { ...SAMEDI_19, id: 3, heureDebut: '18:00', heureFin: '20:00' };
    const lignes = alignerStands([poste(SAMEDI_12, jeux, alice), poste(soir, jeux, bob)], 1, 8);
    expect(lignes[0].different).toBe(true);
  });
});

describe('alignerAnimateurs', () => {
  it('lines up everyone holding a seat on either day, absent on the other side', () => {
    const postes = [poste(SAMEDI_12, jeux, alice), poste(SAMEDI_19, jeux, bob)];

    const lignes = alignerAnimateurs(postes, 1, 8);

    expect(lignes.map((ligne) => [ligne.nom, ligne.a !== null, ligne.b !== null])).toEqual([
      ['Alice Martin', true, false],
      ['Bob Durand', false, true],
    ]);
    expect(lignes.every((ligne) => ligne.different)).toBe(true);
    expect(lignes[0].a?.minutes).toBe(120);
  });

  it('holds an animateur alike when the same shifts come back', () => {
    const lignes = alignerAnimateurs(
      [poste(SAMEDI_12, jeux, alice), poste(SAMEDI_19, jeux, alice)],
      1,
      8,
    );
    expect(lignes[0].different).toBe(false);
  });
});

describe('filters of the comparison', () => {
  const postes = [
    poste(SAMEDI_12, jeux, alice),
    poste(SAMEDI_19, jeux, bob),
    poste(SAMEDI_12, buvette, bob),
    poste(SAMEDI_19, buvette, alice),
  ];
  const aucun = { filtre: '', stand: '', animateur: '', seulementEcarts: false };

  it('« seulement les différences » hides the stands alike on both days', () => {
    const lignes = alignerStands([...postes, poste(SAMEDI_19, jeux)], 1, 8);
    const visibles = filterComparedStands(lignes, { ...aucun, seulementEcarts: true });
    expect(visibles.map((ligne) => ligne.standNom)).toEqual(['Jeux']);
  });

  it('applies the stand and animateur filters to both days at once', () => {
    const lignes = alignerStands(postes, 1, 8);
    expect(filterComparedStands(lignes, { ...aucun, stand: 'S2' })).toHaveLength(1);
    expect(filterComparedStands(lignes, { ...aucun, animateur: 'a2' })).toHaveLength(2);
    expect(filterComparedStands(lignes, { ...aucun, filtre: 'buvette' })).toHaveLength(1);

    const rail = alignerAnimateurs(postes, 1, 8);
    expect(filterComparedAnimateurs(rail, { ...aucun, animateur: 'a1' })).toHaveLength(1);
    expect(filterComparedAnimateurs(rail, { ...aucun, stand: 'S1' })).toHaveLength(2);
    expect(filterComparedAnimateurs(rail, { ...aucun, filtre: 'bob' })).toHaveLength(1);
  });
});

describe('comparison on days read from the plan', () => {
  it('compares the days planningDays lists', () => {
    const postes = [poste(SAMEDI_12, jeux, alice), poste(SAMEDI_19, jeux, bob)];
    const jours = planningDays(postes);
    expect(resolveComparison(jours, jours[0], '2026-07-19').jour?.jour).toBe(8);
  });
});
