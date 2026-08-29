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
//
// The horizontal axis spans the RUN, not the points. Timefold announces a new
// best score only when it strictly improves, so a solve that stops progressing
// stops producing points — the very state this screen exists to show. Scaled on
// the points, such a run would always touch the right edge and read as still
// climbing; scaled on the run's real duration, its last value extends flat to
// that edge, which is what a plateau looks like.

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
   * How long that value has held, in milliseconds — measured to *now* (the
   * run's elapsed time), never to the last recorded point. The signal the whole
   * screen exists for: "hard has been at 0 for two minutes, soft is still
   * moving" is read off this, not off the shape of the curve.
   *
   * Measured to the last point instead, it would freeze on the gap between the
   * last two improvements and stay there however long the wait — the one label
   * an operator is meant to decide on would be the one that lies.
   */
  plateauMs: number;
}

/**
 * Builds the three curves, or `null` when there is nothing to draw yet: a solve
 * whose solver has not announced a first complete solution.
 *
 * @param dureeMs how long the run has been going. The curve's right edge; it
 *                cannot be derived from the points — see the note above.
 */
export function construireSeries(points: readonly ScorePoint[], dureeMs: number): SerieScore[] | null {
  if (points.length === 0) {
    return null;
  }
  // Never shorter than the last point: a snapshot taken between an improvement
  // and the next reading of the clock must not produce an axis that ends before
  // the data it has to show.
  const fin = Math.max(dureeMs, points[points.length - 1].tempsMs);
  return NIVEAUX_SCORE.map((niveau) => serie(niveau, points, fin));
}

function serie(niveau: NiveauScore, points: readonly ScorePoint[], fin: number): SerieScore {
  const valeurs = points.map((point) => point[niveau]);
  // Zero is forced into the range so the top edge always means "nothing left to
  // fix" — a curve that flattens against it is the readable end state.
  const haut = Math.max(0, ...valeurs);
  const bas = Math.min(0, ...valeurs);
  const amplitude = haut - bas;

  const y = (valeur: number): number =>
    amplitude === 0 ? 0 : ((haut - valeur) / amplitude) * HAUTEUR_COURBE;
  // From zero, not from the first point: the seconds before the solver held a
  // first complete solution are part of the run too, and an axis anchored on
  // the first point would rescale itself under every improvement.
  const x = (tempsMs: number): number => (fin === 0 ? 0 : (tempsMs / fin) * LARGEUR_COURBE);

  const dernier = valeurs[valeurs.length - 1];
  // One point is a level, not a shape: held across the whole box, since a
  // polyline with a single vertex draws nothing at all and the first score of a
  // run would simply never appear.
  const sommets =
    points.length === 1
      ? [coord(0, y(dernier)), coord(LARGEUR_COURBE, y(dernier))]
      : points.map((point) => coord(x(point.tempsMs), y(point[niveau])));
  // Held flat to the right edge — that segment *is* the plateau. Without it a
  // run that stopped improving would end wherever its last improvement was, and
  // a solve at a standstill would draw exactly like one still under way.
  if (points.length > 1 && fin > points[points.length - 1].tempsMs) {
    sommets.push(coord(LARGEUR_COURBE, y(dernier)));
  }

  return {
    niveau,
    polyline: sommets.join(' '),
    zeroY: y(0),
    haut,
    bas,
    dernier,
    plateauMs: plateau(points, valeurs, fin)
  };
}

/**
 * Milliseconds the last value has held: from the point that first reached it up
 * to *now*, never up to the last recorded point.
 */
function plateau(points: readonly ScorePoint[], valeurs: readonly number[], fin: number): number {
  const dernier = valeurs[valeurs.length - 1];
  let index = valeurs.length - 1;
  while (index > 0 && valeurs[index - 1] === dernier) {
    index -= 1;
  }
  return Math.max(0, fin - points[index].tempsMs);
}

/** Rounded to a hundredth: three decimals of SVG precision nobody can see. */
function coord(x: number, y: number): string {
  return `${arrondir(x)},${arrondir(y)}`;
}

function arrondir(valeur: number): number {
  return Math.round(valeur * 100) / 100;
}
