// The pure geometry of the Équité radar: which axes, in which order, where a
// value sits on one, and what the accessible name picks out. No rendering.

import { describe, expect, it } from 'vitest';
import { LigneEquite, RapportEquite, SyntheseColonne } from '../../core/models';
import {
  DEFAULT_AXES,
  RADAR_RADIUS,
  RADAR_SIZE,
  band,
  formatValue,
  medians,
  normalise,
  notableRanks,
  pointOn,
  polygon,
  radarAxes,
  readOptionalAxes,
  valuesOf,
} from './radar';

function row(partial: Partial<LigneEquite> & { animateurId: string }): LigneEquite {
  return {
    nom: partial.animateurId,
    heuresTotal: 0,
    heuresParSemaine: {},
    heuresSoiree: 0,
    heuresWeekEnd: 0,
    heuresJourFerie: 0,
    postes: 0,
    postesPenibles: 0,
    standsDistincts: 0,
    typologiesDistinctes: 0,
    emplacementsDistinctsParJourMax: 0,
    tauxSouhaits: 0,
    tauxAppreciation: 0,
    joursTravailles: 0,
    joursRepos: 0,
    plusLongueSerie: 0,
    ...partial,
  };
}

function summary(min: number, mediane: number, max: number): SyntheseColonne {
  return { min, mediane, max, ecartType: 0 };
}

function report(rows: LigneEquite[], syntheses: Record<string, SyntheseColonne>): RapportEquite {
  return {
    heureDebutSoiree: '19:00:00',
    semaines: [],
    lignes: rows,
    syntheses,
    colonnesSolveur: [
      { colonne: 'postesPenibles', contrainte: 'equitePenibilite', active: true },
      { colonne: 'tauxSouhaits', contrainte: 'souhaitsIncompatibles', active: false },
    ],
  };
}

const CENTER = RADAR_SIZE / 2;

describe('radarAxes', () => {
  const base = report([row({ animateurId: 'A' })], {
    heuresTotal: summary(10, 20, 40),
    heuresSoiree: summary(0, 0, 0),
    tauxSouhaits: summary(0, 0, 0),
    postesPenibles: summary(0, 1, 4),
  });

  it('draws the five default axes, the first at the top, evenly spread', () => {
    const axes = radarAxes(base, []);
    expect(axes.map((axis) => axis.column)).toEqual([...DEFAULT_AXES]);
    expect(axes[0].angle).toBe(0);
    expect(axes[1].angle).toBeCloseTo((2 * Math.PI) / 5);
  });

  it('keeps a stable order whatever order the optional axes are asked in', () => {
    const one = radarAxes(base, ['plusLongueSerie', 'heuresJourFerie']).map((a) => a.column);
    const other = radarAxes(base, ['heuresJourFerie', 'plusLongueSerie']).map((a) => a.column);
    expect(one).toEqual(other);
    expect(one.slice(5)).toEqual(['heuresJourFerie', 'plusLongueSerie']);
    expect(readOptionalAxes('plusLongueSerie,nimporte,heuresJourFerie')).toEqual([
      'heuresJourFerie',
      'plusLongueSerie',
    ]);
    expect(readOptionalAxes(null)).toEqual([]);
  });

  it('marks the axes that read better further out, and those the solver measures', () => {
    const axes = radarAxes(base, []);
    const byColumn = new Map(axes.map((axis) => [axis.column, axis]));
    expect(byColumn.get('tauxSouhaits')!.higherIsBetter).toBe(true);
    expect(byColumn.get('heuresSoiree')!.higherIsBetter).toBe(false);
    expect(byColumn.get('postesPenibles')!.measuredBySolver).toBe(true);
    // Measured, but the rule is off: no mark, as in the table.
    expect(byColumn.get('tauxSouhaits')!.measuredBySolver).toBe(false);
  });

  it('flags an axis without dispersion, and a rate nobody scores on as meaningless', () => {
    const byColumn = new Map(radarAxes(base, []).map((axis) => [axis.column, axis]));
    expect(byColumn.get('heuresSoiree')).toMatchObject({ spread: false, meaningless: false });
    expect(byColumn.get('tauxSouhaits')).toMatchObject({ spread: false, meaningless: true });
    expect(byColumn.get('heuresTotal')).toMatchObject({ spread: true, meaningless: false });
  });
});

describe('normalise', () => {
  it('scales on the edition’s min and max', () => {
    expect(normalise(10, { min: 10, max: 40 })).toBe(0);
    expect(normalise(25, { min: 10, max: 40 })).toBe(0.5);
    expect(normalise(40, { min: 10, max: 40 })).toBe(1);
  });

  it('puts every value at the centre when min = max, without dividing by zero', () => {
    expect(normalise(0, { min: 0, max: 0 })).toBe(0);
    expect(normalise(3, { min: 3, max: 3 })).toBe(0);
    expect(Number.isFinite(normalise(5, { min: 3, max: 3 }))).toBe(true);
  });

  it('clamps a value outside the bounds to the rim or the centre', () => {
    expect(normalise(50, { min: 10, max: 40 })).toBe(1);
    expect(normalise(-5, { min: 10, max: 40 })).toBe(0);
  });

  it('scales a rate like any value, in its own 0–1 unit', () => {
    expect(normalise(0.75, { min: 0.5, max: 1 })).toBe(0.5);
  });
});

describe('polygons', () => {
  const rep = report([row({ animateurId: 'A', heuresTotal: 40, postesPenibles: 2 })], {
    heuresTotal: summary(10, 20, 40),
    heuresSoiree: summary(0, 0, 0),
    heuresWeekEnd: summary(0, 2, 4),
    postesPenibles: summary(0, 1, 4),
    tauxSouhaits: summary(0, 0.5, 1),
  });
  const axes = radarAxes(rep, []);

  it('places the rim of the top axis straight above the centre', () => {
    expect(pointOn(axes[0], 1)).toEqual({ x: CENTER, y: CENTER - RADAR_RADIUS });
    expect(pointOn(axes[0], 0)).toEqual({ x: CENTER, y: CENTER });
  });

  it('draws a person and the median through one point per axis', () => {
    const person = polygon(valuesOf(rep.lignes[0], axes), axes).split(' ');
    expect(person).toHaveLength(5);
    expect(person[0]).toBe(`${CENTER},${CENTER - RADAR_RADIUS}`);
    // Soirées has no spread: the centre.
    expect(person[1]).toBe(`${CENTER},${CENTER}`);
    const median = polygon(medians(axes), axes).split(' ');
    expect(median[0]).toBe(`${CENTER},${Math.round((CENTER - RADAR_RADIUS / 3) * 100) / 100}`);
  });

  it('spans the band to the rim where there is a spread, the centre where there is none', () => {
    const points = band(axes).split(' ');
    expect(points[0]).toBe(`${CENTER},${CENTER - RADAR_RADIUS}`);
    expect(points[1]).toBe(`${CENTER},${CENTER}`);
  });
});

describe('formatValue', () => {
  it('writes the values as the fiche table does', () => {
    expect(formatValue('heuresTotal', 42, 'en-US')).toBe('42.0 h');
    expect(formatValue('tauxSouhaits', 0.666, 'en-US')).toBe('67%');
    expect(formatValue('postesPenibles', 3, 'en-US')).toBe('3');
  });
});

describe('notableRanks', () => {
  const rows = [
    row({ animateurId: 'A', heuresSoiree: 9, heuresTotal: 10 }),
    row({ animateurId: 'B', heuresSoiree: 12, heuresTotal: 30 }),
    row({ animateurId: 'C', heuresSoiree: 1, heuresTotal: 30 }),
    row({ animateurId: 'D', heuresSoiree: 2, heuresTotal: 30 }),
    row({ animateurId: 'E', heuresSoiree: 3, heuresTotal: 30 }),
    row({ animateurId: 'F', heuresSoiree: 4, heuresTotal: 30 }),
    row({ animateurId: 'G', heuresSoiree: 5, heuresTotal: 30 }),
  ];
  const rep = report(rows, {
    heuresTotal: summary(10, 30, 30),
    heuresSoiree: summary(1, 4, 12),
  });
  const axes = radarAxes(rep, []);

  it('names the axes a person is among the highest or lowest on', () => {
    expect(notableRanks(rows, rows[0], axes)).toEqual([
      { column: 'heuresTotal', label: 'Heures', rank: 1, from: 'bottom', count: 7 },
      { column: 'heuresSoiree', label: 'Soirée', rank: 2, from: 'top', count: 7 },
    ]);
  });

  it('says nothing on an edition too small for a rank to mean anything', () => {
    expect(notableRanks(rows.slice(0, 2), rows[0], axes)).toEqual([]);
  });

  it('says nothing up to twice the depth, where everybody is among the top or the bottom', () => {
    expect(notableRanks(rows.slice(0, 6), rows[0], axes)).toEqual([]);
    expect(notableRanks(rows, rows[0], axes)).not.toEqual([]);
  });
});
