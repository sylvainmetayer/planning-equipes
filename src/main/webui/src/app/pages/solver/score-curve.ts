// Geometry of the live score curve (issue #304), with no rendering and no
// Angular: turning a series of score points into what an SVG <polyline> needs.
//
// Three curves, one per score level, each on its OWN scale — never one shared
// axis. On a shared one a hard score at -36 is a flat line indistinguishable
// from zero next to a soft score at -400 000, and the hard score is precisely
// the one that decides whether the planning is workable at all.
//
// Every scale includes zero, whatever the values do. That is what makes the
// picture readable without axis labels: the top edge of a box is "nothing left
// to fix at this level", and a curve reaching it is the answer to the only
// question asked of a running solve.

import { ScorePoint } from '../../core/models';

/** Score levels, in the order Timefold compares them. */
export const NIVEAUX_SCORE = ['hard', 'medium', 'soft'] as const;

export type NiveauScore = (typeof NIVEAUX_SCORE)[number];

/**
 * Coordinate space of one curve. Fixed rather than measured: the SVG is drawn
 * in these units and stretched by CSS, so nothing here depends on the width the
 * card happens to have — and nothing has to be recomputed when it changes.
 */
export const LARGEUR_COURBE = 600;
export const HAUTEUR_COURBE = 72;

/** One level's curve, ready to be handed to a `<polyline points="…">`. */
export interface SerieScore {
  niveau: NiveauScore;
  /** `points` attribute of the polyline, in the coordinate space above. */
  polyline: string;
  /** Where the zero line sits — the top edge whenever every value is negative. */
  zeroY: number;
  /** Best (highest) and worst (lowest) values reached, zero always included. */
  haut: number;
  bas: number;
  /** Value at the last point: what the solve stands at. */
  dernier: number;
  /**
   * How long that value has held, in milliseconds. The signal the whole screen
   * exists for: "hard has been at 0 for two minutes, soft is still moving" is
   * read off this, not off the shape of the curve.
   */
  plateauMs: number;
}

/**
 * Builds the three curves, or `null` when there is nothing to draw yet: a solve
 * whose solver has not announced a first complete solution.
 */
export function construireSeries(points: readonly ScorePoint[]): SerieScore[] | null {
  if (points.length === 0) {
    return null;
  }
  return NIVEAUX_SCORE.map((niveau) => serie(niveau, points));
}

function serie(niveau: NiveauScore, points: readonly ScorePoint[]): SerieScore {
  const valeurs = points.map((point) => point[niveau]);
  // Zero is forced into the range so the top edge always means "nothing left to
  // fix" — a curve that flattens against it is the readable end state.
  const haut = Math.max(0, ...valeurs);
  const bas = Math.min(0, ...valeurs);
  const amplitude = haut - bas;
  const debut = points[0].tempsMs;
  const duree = points[points.length - 1].tempsMs - debut;

  const y = (valeur: number): number =>
    amplitude === 0 ? 0 : ((haut - valeur) / amplitude) * HAUTEUR_COURBE;
  const x = (tempsMs: number): number =>
    duree === 0 ? 0 : ((tempsMs - debut) / duree) * LARGEUR_COURBE;

  const dernier = valeurs[valeurs.length - 1];
  const sommets =
    // A single point, or every point at the same instant, has no width to be
    // drawn across: a flat segment says "this is where it stands" where a
    // one-vertex polyline would draw nothing at all.
    duree === 0
      ? [coord(0, y(dernier)), coord(LARGEUR_COURBE, y(dernier))]
      : points.map((point) => coord(x(point.tempsMs), y(point[niveau])));

  return {
    niveau,
    polyline: sommets.join(' '),
    zeroY: y(0),
    haut,
    bas,
    dernier,
    plateauMs: plateau(points, valeurs)
  };
}

/** Milliseconds the last value has held, walking back while it does not change. */
function plateau(points: readonly ScorePoint[], valeurs: readonly number[]): number {
  const dernier = valeurs[valeurs.length - 1];
  let index = valeurs.length - 1;
  while (index > 0 && valeurs[index - 1] === dernier) {
    index -= 1;
  }
  return points[points.length - 1].tempsMs - points[index].tempsMs;
}

/** Rounded to a hundredth: three decimals of SVG precision nobody can see. */
function coord(x: number, y: number): string {
  return `${arrondir(x)},${arrondir(y)}`;
}

function arrondir(valeur: number): number {
  return Math.round(valeur * 100) / 100;
}
