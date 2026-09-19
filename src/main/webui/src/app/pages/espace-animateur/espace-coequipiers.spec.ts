import { describe, expect, it } from 'vitest';
import { PosteAnimateurView } from '../../core/models';
import { JourPlanning } from './espace-maintenant';
import { EQUIPE_NOMBREUSE, filtrerCoequipiers, vueCoequipiers } from './espace-coequipiers';

function poste(overrides: Partial<PosteAnimateurView> = {}): PosteAnimateurView {
  return {
    date: '2026-02-16',
    standId: 'stand-1',
    standNom: 'Construction',
    creneauId: 1,
    heureDebut: '10:00:00',
    heureFin: '12:00:00',
    coequipiers: [],
    emplacementNom: null,
    emplacementLatitude: null,
    emplacementLongitude: null,
    typologieId: 'CONSTRUCTION',
    typologieLibelle: 'Jeux de construction',
    ...overrides,
  };
}

function jour(date: string, postes: PosteAnimateurView[]): JourPlanning {
  return { date, postes, repos: false, pauses: [] };
}

describe('vueCoequipiers', () => {
  const jours = [
    jour('2026-02-15', [
      poste({ date: '2026-02-15', coequipiers: ['Zoé Durand', 'Alice Martin'] }),
    ]),
    jour('2026-02-16', [
      poste({ date: '2026-02-16', coequipiers: ['Alice Martin'] }),
      poste({
        date: '2026-02-16',
        standNom: 'ENF',
        heureDebut: '14:00',
        coequipiers: ['Alice Martin'],
      }),
    ]),
  ];

  it('ranks the most frequent first, then by name', () => {
    const { coequipiers } = vueCoequipiers(jours);

    expect(coequipiers.map((each) => [each.nom, each.occurrences.length])).toEqual([
      ['Alice Martin', 3],
      ['Zoé Durand', 1],
    ]);
  });

  it('says where each shift was shared', () => {
    const [alice] = vueCoequipiers(jours).coequipiers;

    expect(alice.occurrences[0]).toEqual({
      date: '2026-02-15',
      heureDebut: '10:00',
      heureFin: '12:00',
      standNom: 'Construction',
    });
  });

  /**
   * The montage and the démontage put most of the roster on one stand: a
   * hundred names answer nothing, and they would bury the four people somebody
   * really spends their fortnight with.
   */
  it('counts a crowd instead of naming it', () => {
    const foule = Array.from({ length: EQUIPE_NOMBREUSE + 4 }, (_, index) => `Bénévole ${index}`);
    const { coequipiers, affluences } = vueCoequipiers([
      jour('2026-02-14', [poste({ date: '2026-02-14', standNom: 'Montage', coequipiers: foule })]),
      ...jours,
    ]);

    expect(affluences).toEqual([
      {
        date: '2026-02-14',
        standNom: 'Montage',
        heureDebut: '10:00',
        heureFin: '12:00',
        effectif: foule.length + 1,
      },
    ]);
    expect(coequipiers.map((each) => each.nom)).toEqual(['Alice Martin', 'Zoé Durand']);
  });

  it('answers an empty planning with two empty lists', () => {
    expect(vueCoequipiers([])).toEqual({ coequipiers: [], affluences: [] });
  });
});

describe('filtrerCoequipiers', () => {
  const liste = vueCoequipiers([
    jour('2026-02-15', [poste({ coequipiers: ['Zoé Durand', 'Alice Martin'] })]),
  ]).coequipiers;

  it('ignores accents and case, as every other name lookup does', () => {
    expect(filtrerCoequipiers(liste, 'zoe').map((each) => each.nom)).toEqual(['Zoé Durand']);
    expect(filtrerCoequipiers(liste, 'MARTIN').map((each) => each.nom)).toEqual(['Alice Martin']);
  });

  it('matches everybody on an empty search', () => {
    expect(filtrerCoequipiers(liste, '   ')).toHaveLength(2);
  });

  it('answers nothing when nobody carries that name', () => {
    expect(filtrerCoequipiers(liste, 'Bernard')).toEqual([]);
  });
});
