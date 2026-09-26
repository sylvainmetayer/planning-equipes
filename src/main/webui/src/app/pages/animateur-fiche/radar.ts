// Geometry of the Équité radar, with no rendering and no Angular beyond
// `$localize`: which axes are drawn, where a value sits on one, and the
// polygons an SVG <polygon> needs.
//
// Every axis is scaled on the edition's own [min, max] for its column — the
// synthesis the report already carries: the centre is the edition's minimum,
// the rim its maximum. Readable without statistics, and consistent with the
// « Sur l'ensemble » column of the fiche. An axis nobody differs on (min = max,
// everybody at 0 evenings) has no scale at all: its points sit at the centre
// and the axis says « aucune dispersion », rather than dividing by zero.

import { formatNumber, formatPercent } from '@angular/common';
import { LigneEquite, RapportEquite } from '../../core/models';
import { columnConstraint, formatColonne, libelleColonne, valeurColonne } from '../equite/equite';

/** The five axes of the original idea: hours, evenings, week-ends, demanding seats, honoured wishes. */
export const DEFAULT_AXES = [
  'heuresTotal',
  'heuresSoiree',
  'heuresWeekEnd',
  'postesPenibles',
  'tauxSouhaits',
] as const;

/** The axes a reader may add, in the order they join the radar. */
export const OPTIONAL_AXES = [
  'heuresJourFerie',
  'tauxAppreciation',
  'joursTravailles',
  'plusLongueSerie',
] as const;

/** Axes where further out is better, not heavier. */
const HIGHER_IS_BETTER: ReadonlySet<string> = new Set(['tauxSouhaits', 'tauxAppreciation']);

/** Coordinate space of the radar: drawn in these units, stretched by CSS. */
export const RADAR_SIZE = 400;
export const RADAR_RADIUS = 130;
const CENTER = RADAR_SIZE / 2;

export interface RadarAxis {
  column: string;
  label: string;
  /** Radians, clockwise from the top. */
  angle: number;
  min: number;
  median: number;
  max: number;
  /** False when min = max: nothing to scale on, every point at the centre. */
  spread: boolean;
  /** True for a rate nobody scores on at all — no wish honoured anywhere: the axis says nothing. */
  meaningless: boolean;
  higherIsBetter: boolean;
  /** The column is measured by a solver rule that is switched on, as the table's icon says. */
  measuredBySolver: boolean;
}

/** The optional axes named in a `?axes=` value, in canonical order; unknown names are dropped. */
export function readOptionalAxes(value: string | null): string[] {
  const asked = new Set((value ?? '').split(',').map((part) => part.trim()));
  return OPTIONAL_AXES.filter((column) => asked.has(column));
}

/**
 * The radar's axes: the five defaults, then the optional ones asked for, in
 * that fixed order whatever order they were asked in — a radar whose axes
 * swap places between two visits cannot be compared with itself. Spread
 * evenly, the first at the top.
 */
export function radarAxes(report: RapportEquite, optional: readonly string[]): RadarAxis[] {
  const asked = new Set(optional);
  const columns = [...DEFAULT_AXES, ...OPTIONAL_AXES.filter((column) => asked.has(column))];
  return columns.map((column, index) => {
    const summary = report.syntheses[column];
    const min = summary?.min ?? 0;
    const max = summary?.max ?? 0;
    const rate = formatColonne(column) === 'taux';
    return {
      column,
      label: libelleColonne(column),
      angle: (2 * Math.PI * index) / columns.length,
      min,
      median: summary?.mediane ?? 0,
      max,
      spread: max > min,
      meaningless: rate && report.lignes.length > 0 && max <= 0,
      higherIsBetter: HIGHER_IS_BETTER.has(column),
      measuredBySolver: columnConstraint(report, column)?.active === true,
    };
  });
}

/**
 * Where a value sits on its axis, from 0 (the edition's minimum, the centre)
 * to 1 (its maximum, the rim). Clamped: a value outside [min, max] cannot come
 * from the report the bounds were computed on, but a radar spilling out of
 * its frame would be a worse answer than its edge.
 */
export function normalise(value: number, axis: Pick<RadarAxis, 'min' | 'max'>): number {
  const range = axis.max - axis.min;
  // Tested positively, so a NaN bound — no range at all — lands on the centre too.
  return range > 0 ? Math.min(Math.max((value - axis.min) / range, 0), 1) : 0;
}

/** The point at that fraction of the radius on an axis. */
export function pointOn(axis: Pick<RadarAxis, 'angle'>, ratio: number): { x: number; y: number } {
  return {
    x: round(CENTER + Math.sin(axis.angle) * ratio * RADAR_RADIUS),
    y: round(CENTER - Math.cos(axis.angle) * ratio * RADAR_RADIUS),
  };
}

/** `points` attribute of a polygon through one value per axis. */
export function polygon(values: readonly number[], axes: readonly RadarAxis[]): string {
  return axes
    .map((axis, index) => pointOn(axis, normalise(values[index] ?? 0, axis)))
    .map(({ x, y }) => `${x},${y}`)
    .join(' ');
}

/** A person's values, one per axis, read as the table reads them. */
export function valuesOf(row: LigneEquite, axes: readonly RadarAxis[]): number[] {
  return axes.map((axis) => valeurColonne(row, axis.column));
}

/** The edition's median, one per axis. */
export function medians(axes: readonly RadarAxis[]): number[] {
  return axes.map((axis) => axis.median);
}

/**
 * The min–max band: the rim on every axis that has a spread, the centre on
 * those that have none — the shape of where the edition's values can be.
 */
export function band(axes: readonly RadarAxis[]): string {
  return axes
    .map((axis) => pointOn(axis, axis.spread ? 1 : 0))
    .map(({ x, y }) => `${x},${y}`)
    .join(' ');
}

/** A concentric ring of the grid, at that fraction of the radius. */
export function ring(axes: readonly RadarAxis[], ratio: number): string {
  return axes
    .map((axis) => pointOn(axis, ratio))
    .map(({ x, y }) => `${x},${y}`)
    .join(' ');
}

/**
 * A value written as the fiche's table writes it: hours with one decimal and
 * `h`, a rate as a whole percentage, a count as is. The same pipes' functions,
 * so the radar and the table cannot disagree on a figure.
 */
export function formatValue(column: string, value: number, locale: string): string {
  switch (formatColonne(column)) {
    case 'heures':
      return `${formatNumber(value, locale, '1.1-1')} h`;
    case 'taux':
      return formatPercent(value, locale, '1.0-0');
    default:
      return String(value);
  }
}

/** Where a person ranks on one axis among everybody, when that rank stands out. */
export interface NotableRank {
  column: string;
  label: string;
  rank: number;
  from: 'top' | 'bottom';
  count: number;
}

/**
 * The axes on which a person is among the `depth` highest or lowest of the
 * edition — what the radar's accessible name says instead of the shape.
 * A rank counts the people strictly above (or below), so ties share it. Axes
 * without spread say nothing, and neither do editions of `2 × depth` people or
 * fewer: there, everybody is among the highest or the lowest on every axis.
 */
export function notableRanks(
  rows: readonly LigneEquite[],
  row: LigneEquite,
  axes: readonly RadarAxis[],
  depth = 3,
): NotableRank[] {
  if (rows.length <= 2 * depth) {
    return [];
  }
  const notable: NotableRank[] = [];
  for (const axis of axes) {
    if (!axis.spread) {
      continue;
    }
    const own = valeurColonne(row, axis.column);
    const values = rows.map((other) => valeurColonne(other, axis.column));
    const fromTop = 1 + values.filter((value) => value > own).length;
    const fromBottom = 1 + values.filter((value) => value < own).length;
    const base = { column: axis.column, label: axis.label, count: rows.length };
    if (fromTop <= depth) {
      notable.push({ ...base, rank: fromTop, from: 'top' });
    } else if (fromBottom <= depth) {
      notable.push({ ...base, rank: fromBottom, from: 'bottom' });
    }
  }
  return notable;
}

function round(value: number): number {
  return Math.round(value * 100) / 100;
}
