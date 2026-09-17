import { describe, expect, it } from 'vitest';
import { LigneTypologie } from '../../core/models';
import {
  EMPTY_FILTERS,
  barres,
  classeHeures,
  filterTypologies,
  maximumParJour,
  underTension,
} from './typologies-planning';

function ligne(overrides: Partial<LigneTypologie> = {}): LigneTypologie {
  return {
    typologie: 'ambiance',
    label: 'Ambiance',
    ninja: false,
    maxCreneauxParAnimateur: null,
    description: null,
    animateursAffectes: [{ animateurId: 'a1', nom: 'Alice' }],
    animateursCompetents: [{ animateurId: 'a1', nom: 'Alice' }],
    competentsJamaisAffectes: [],
    affectesSansCompetence: [],
    heures: 10,
    postes: 4,
    heuresParJour: { '2026-07-06': 10 },
    ...overrides,
  };
}

describe('filterTypologies', () => {
  const lignes = [
    ligne({ typologie: 'ambiance', label: 'Ambiance', heures: 10 }),
    ligne({
      typologie: 'strategie',
      label: 'Stratégie',
      description: "Nécessite d'apprendre 45 jeux",
      maxCreneauxParAnimateur: 3,
      heures: 20,
    }),
    ligne({
      typologie: 'enfants',
      label: 'Enfants',
      maxCreneauxParAnimateur: 6,
      postes: 0,
      heures: 0,
      heuresParJour: {},
    }),
  ];

  it('keeps everything when nothing is asked', () => {
    expect(filterTypologies(lignes, EMPTY_FILTERS)).toHaveLength(3);
  });

  it('searches the description too, which is where the organiser wrote why it is hard', () => {
    const trouves = filterTypologies(lignes, { ...EMPTY_FILTERS, search: '45 jeux' });
    expect(trouves.map((found) => found.typologie)).toEqual(['strategie']);
  });

  it('separates the capped from the uncapped', () => {
    expect(
      filterTypologies(lignes, { ...EMPTY_FILTERS, cap: 'capped' }).map((found) => found.typologie),
    ).toEqual(['strategie', 'enfants']);
    expect(
      filterTypologies(lignes, { ...EMPTY_FILTERS, cap: 'uncapped' }).map(
        (found) => found.typologie,
      ),
    ).toEqual(['ambiance']);
  });

  // « au moins 5 » is a question about ceilings; a typologie without one has no
  // number to answer it with, and is not a very high ceiling either.
  it('never lets an uncapped typologie answer a minimum', () => {
    expect(
      filterTypologies(lignes, { ...EMPTY_FILTERS, capMinimum: 5 }).map((found) => found.typologie),
    ).toEqual(['enfants']);
  });

  it('shortlists the typologies something is off about', () => {
    const tendues = filterTypologies(lignes, { ...EMPTY_FILTERS, tensionOnly: true });
    expect(tendues.map((found) => found.typologie)).toEqual(['enfants']);
  });

  it('applies the four together', () => {
    expect(
      filterTypologies(lignes, {
        search: 'e',
        cap: 'capped',
        capMinimum: 5,
        tensionOnly: true,
      }).map((found) => found.typologie),
    ).toEqual(['enfants']);
  });
});

describe('underTension', () => {
  it('flags a typologie nobody was sat at', () => {
    expect(underTension(ligne({ postes: 0 }))).toBe(true);
  });

  it('flags a reserve nobody drew on, and a seat nobody was vetted for', () => {
    expect(
      underTension(ligne({ competentsJamaisAffectes: [{ animateurId: 'a2', nom: 'Bob' }] })),
    ).toBe(true);
    expect(
      underTension(ligne({ affectesSansCompetence: [{ animateurId: 'a3', nom: 'Chloé' }] })),
    ).toBe(true);
  });

  it('says nothing about a typologie held by exactly who was vetted on it', () => {
    expect(underTension(ligne())).toBe(false);
  });
});

describe('barres', () => {
  it('orders by hours and measures each bar against the busiest one', () => {
    const construites = barres([
      ligne({ typologie: 'ambiance', heures: 10, postes: 4 }),
      ligne({ typologie: 'strategie', heures: 20, postes: 5 }),
    ]);

    expect(construites.map((barre) => barre.ligne.typologie)).toEqual(['strategie', 'ambiance']);
    expect(construites[0].partHeures).toBe(100);
    expect(construites[1].partHeures).toBe(50);
    expect(construites[1].partPostes).toBe(80);
  });

  it('draws nothing rather than dividing by zero on an unsolved edition', () => {
    const construites = barres([ligne({ heures: 0, postes: 0 })]);
    expect(construites[0].partHeures).toBe(0);
    expect(construites[0].partPostes).toBe(0);
  });
});

describe('classeHeures', () => {
  it('reads the heat against the busiest cell, not against an absolute', () => {
    expect(classeHeures(9, 10)).toBe('heatmap-cell heatmap-cell-critical');
    expect(classeHeures(5, 10)).toBe('heatmap-cell heatmap-cell-warning');
    expect(classeHeures(1, 10)).toBe('heatmap-cell heatmap-cell-ok');
    expect(classeHeures(0, 10)).toBe('heatmap-cell heatmap-cell-none');
    expect(classeHeures(3, 0)).toBe('heatmap-cell heatmap-cell-none');
  });
});

describe('maximumParJour', () => {
  it('is the busiest day of the busiest typologie, and zero when nothing was held', () => {
    const lignes = [
      ligne({ heuresParJour: { '2026-07-06': 4, '2026-07-07': 9 } }),
      ligne({ heuresParJour: { '2026-07-06': 12 } }),
    ];
    expect(maximumParJour(lignes, ['2026-07-06', '2026-07-07'])).toBe(12);
    expect(maximumParJour([ligne({ heuresParJour: {} })], ['2026-07-06'])).toBe(0);
  });
});
