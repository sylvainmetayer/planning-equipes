// Builders of the « où se concentrent les écarts » cross-table (issue #496):
// pure functions over the cells the server counts, kept out of the component
// so the grouping, the ordering and the cap are tested without rendering.

import { AxePivot, CellulePivot } from '../../core/models';

/** One column of the pivot: a day, a stand or an animateur. */
export interface ColonnePivot {
  cle: string;
  /** What the reader sees: a date, a stand name, a full name — resolved by the caller. */
  libelle: string;
  /** Breaches on this key, every constraint together — what the columns are ordered on. */
  total: number;
}

/** One row: a constraint in default, and its count on each column. */
export interface LignePivot {
  contrainte: string;
  /** One count per column of `colonnes`, in the same order; 0 where nothing matched. */
  ecarts: number[];
  total: number;
}

/**
 * The table to draw, or an empty one.
 *
 * `colonnesMasquees` is how many keys the cap left out: a 153-animateur
 * edition would otherwise draw a table nobody can read sideways, and a
 * truncation nobody is told about is worse than a narrower table.
 */
export interface TableauPivot {
  colonnes: ColonnePivot[];
  lignes: LignePivot[];
  colonnesMasquees: number;
  /** Largest cell of the table, the reference the colour scale is read against. */
  maximum: number;
}

/** Columns kept, most breaches first. Beyond this the table stops being readable. */
export const MAX_COLONNES = 25;

/**
 * The cross-table of one axis: constraints down, keys across.
 *
 * Rows are ordered by total, most breaches first — the rule to look at is the
 * one at the top. Columns are ordered by the caller's `ordre` when it gives
 * one (the days, chronologically), by total otherwise: on stands and
 * animateurs, « where does it concentrate » is read left to right.
 */
export function buildPivot(
  cellules: CellulePivot[],
  axe: AxePivot,
  libelle: (cle: string) => string,
  ordre?: (a: ColonnePivot, b: ColonnePivot) => number,
): TableauPivot {
  const retenues = cellules.filter((cellule) => cellule.axe === axe && cellule.ecarts > 0);
  if (retenues.length === 0) {
    return { colonnes: [], lignes: [], colonnesMasquees: 0, maximum: 0 };
  }

  const keyTotals = new Map<string, number>();
  const constraintTotals = new Map<string, number>();
  for (const cellule of retenues) {
    keyTotals.set(cellule.cle, (keyTotals.get(cellule.cle) ?? 0) + cellule.ecarts);
    constraintTotals.set(
      cellule.contrainte,
      (constraintTotals.get(cellule.contrainte) ?? 0) + cellule.ecarts,
    );
  }

  const allColumns: ColonnePivot[] = [...keyTotals.entries()].map(([cle, total]) => ({
    cle,
    libelle: libelle(cle),
    total,
  }));
  allColumns.sort((a, b) => b.total - a.total || a.libelle.localeCompare(b.libelle));
  const colonnes = allColumns.slice(0, MAX_COLONNES);
  if (ordre) {
    colonnes.sort(ordre);
  }
  const index = new Map(colonnes.map((colonne, position) => [colonne.cle, position]));

  const lignes: LignePivot[] = [...constraintTotals.entries()]
    .map(([contrainte, total]) => ({
      contrainte,
      ecarts: colonnes.map(() => 0),
      total,
    }))
    .sort((a, b) => b.total - a.total || a.contrainte.localeCompare(b.contrainte));
  const rowsByConstraint = new Map(lignes.map((ligne) => [ligne.contrainte, ligne]));

  let maximum = 0;
  for (const cellule of retenues) {
    const position = index.get(cellule.cle);
    const ligne = rowsByConstraint.get(cellule.contrainte);
    if (position === undefined || ligne === undefined) {
      continue;
    }
    ligne.ecarts[position] += cellule.ecarts;
    maximum = Math.max(maximum, ligne.ecarts[position]);
  }

  return { colonnes, lignes, colonnesMasquees: allColumns.length - colonnes.length, maximum };
}

/**
 * The heat class of a cell, on the four steps the heatmap already defines.
 * Relative to the largest cell of the table, because « six breaches » means
 * one thing on a rule in default twice and another on a rule in default four
 * hundred times.
 */
export function classeCellule(ecarts: number, maximum: number): string {
  if (ecarts === 0 || maximum === 0) {
    return 'heatmap-cell heatmap-cell-none';
  }
  const part = ecarts / maximum;
  if (part > 0.66) {
    return 'heatmap-cell heatmap-cell-critical';
  }
  return part > 0.33 ? 'heatmap-cell heatmap-cell-warning' : 'heatmap-cell heatmap-cell-ok';
}

/** Where a cell of the pivot leads: the screen on which that breach is actually corrected. */
export interface LienPivot {
  route: string;
  queryParams: Record<string, string>;
  label: string;
}

/**
 * The link out of an opened cell.
 *
 * A cell used to open on a count and stop there — a dead end. Each axis has
 * exactly one screen where its breaches are looked at and fixed: a day is read
 * on the Journée screen, a stand on the assignment calendar filtered on it, a
 * person on their own timeline. The label names the target rather than the
 * screen, because that is what the reader was looking at when they clicked.
 */
export function cellLink(axe: AxePivot, cle: string, libelle: string): LienPivot {
  if (axe === 'ANIMATEUR') {
    return {
      route: '/timeline',
      queryParams: { animateur: cle },
      label: $localize`:@@constraints.pivot.detail.versTimeline:Voir la journée de ${libelle}:qui:`,
    };
  }
  if (axe === 'STAND') {
    return {
      route: '/calendar',
      queryParams: { stand: cle },
      label: $localize`:@@constraints.pivot.detail.versStand:Voir le planning de ${libelle}:stand:`,
    };
  }
  return {
    route: '/journee',
    queryParams: { jour: cle },
    label: $localize`:@@constraints.pivot.detail.versJournee:Voir la journée du ${libelle}:jour:`,
  };
}
