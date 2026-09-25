// The replay of an edition's solves on the Autopsie page, with no rendering
// and no Angular: which rows belong to the edition replayed, what moved from
// one solve to the next, and the geometry of the small multiples — one curve
// per figure, each on its OWN scale, like the live score curve of the Solveur
// page (`pages/solver/score-curve.ts`, whose coordinate space this reuses).
//
// The horizontal axis is the RANK of the solve, not real time: two solves
// three minutes apart and one three days later stay equally readable. A solve
// without a figure keeps its rank and simply draws no point — a failed run is
// a hole in the curve, not a shift of every solve after it.

import { KpiHistoriqueEntry, PlanningKpi } from '../../core/models';
import { HAUTEUR_COURBE, LARGEUR_COURBE } from '../solver/score-curve';

/** An edition the history holds rows of — deleted ones included, named as the rows remember them. */
export interface RejeuEdition {
  editionId: string;
  editionNom: string;
  count: number;
}

/** Every edition the history knows, most solves first, then by name. */
export function editionsOfHistory(entries: readonly KpiHistoriqueEntry[]): RejeuEdition[] {
  const byId = new Map<string, RejeuEdition>();
  for (const entry of entries) {
    const known = byId.get(entry.editionId);
    if (known) {
      known.count += 1;
    } else {
      byId.set(entry.editionId, {
        editionId: entry.editionId,
        editionNom: entry.editionNom ?? entry.editionId,
        count: 1,
      });
    }
  }
  return [...byId.values()].sort(
    (a, b) => b.count - a.count || a.editionNom.localeCompare(b.editionNom),
  );
}

/** The solves of one edition, oldest first — the order of the replay. */
export function resolutionsOfEdition(
  entries: readonly KpiHistoriqueEntry[],
  editionId: string,
): KpiHistoriqueEntry[] {
  return entries
    .filter((entry) => entry.editionId === editionId)
    .sort((a, b) => timeOf(a.creeLe) - timeOf(b.creeLe) || a.id - b.id);
}

/**
 * How many solves one press of « Lecture » advances: one by one, except past a
 * hundred, where one per second would take minutes to replay.
 */
export function playbackStep(count: number): number {
  return count > 100 ? 5 : 1;
}

/** The rank the replay points at: the one asked for when it exists, else the latest solve. */
export function clampRank(requested: number | null, count: number): number {
  if (count === 0) {
    return 0;
  }
  if (requested === null || !Number.isFinite(requested)) {
    return count - 1;
  }
  return Math.min(count - 1, Math.max(0, Math.round(requested)));
}

/** A violation count that moved between two solves: « pauseSansRelais : 0 → 3 ». */
export interface RuleDelta {
  name: string;
  before: number;
  after: number;
  /** Broken now, not before — or the reverse. */
  kind: 'appeared' | 'disappeared' | 'changed';
}

/** What moved from the previous solve to this one; `null` for the first, which has nothing to compare to. */
export interface KpiDelta {
  postesPourvus: number;
  postesTotal: number;
  scoreHard: number | null;
  /** Net of its floor when both solves measured it, raw otherwise. */
  scoreMedium: number | null;
  mediumNetOfFloor: boolean;
  scoreSoft: number | null;
  heuresEcartType: number | null;
  rules: RuleDelta[];
}

export function kpiDelta(previous: PlanningKpi | null, current: PlanningKpi): KpiDelta | null {
  if (!previous) {
    return null;
  }
  const net = previous.scoreMediumHorsPlancher !== null && current.scoreMediumHorsPlancher !== null;
  return {
    postesPourvus: current.postesPourvus - previous.postesPourvus,
    postesTotal: current.postesTotal - previous.postesTotal,
    scoreHard: difference(previous.scoreHard, current.scoreHard),
    scoreMedium: net
      ? difference(previous.scoreMediumHorsPlancher, current.scoreMediumHorsPlancher)
      : difference(previous.scoreMedium, current.scoreMedium),
    mediumNetOfFloor: net,
    scoreSoft: difference(previous.scoreSoft, current.scoreSoft),
    heuresEcartType: difference(previous.heuresEcartType, current.heuresEcartType),
    rules: ruleDeltas(previous.violationsParContrainte, current.violationsParContrainte),
  };
}

/** Every rule whose count moved, the ones that appeared or disappeared first, then by name. */
function ruleDeltas(
  before: Record<string, number> | null | undefined,
  after: Record<string, number> | null | undefined,
): RuleDelta[] {
  const names = new Set([...Object.keys(before ?? {}), ...Object.keys(after ?? {})]);
  const deltas: RuleDelta[] = [];
  for (const name of names) {
    const a = before?.[name] ?? 0;
    const b = after?.[name] ?? 0;
    if (a === b) {
      continue;
    }
    const kind = a === 0 ? 'appeared' : b === 0 ? 'disappeared' : 'changed';
    deltas.push({ name, before: a, after: b, kind });
  }
  const order = { appeared: 0, disappeared: 1, changed: 2 };
  return deltas.sort((x, y) => order[x.kind] - order[y.kind] || (x.name < y.name ? -1 : 1));
}

/** The five figures of the replay, each drawn on its own scale. */
export const REPLAY_FIGURES = ['hard', 'medium', 'soft', 'coverage', 'fairness'] as const;

export type ReplayFigure = (typeof REPLAY_FIGURES)[number];

/** The value of one figure for one solve; `null` when that solve did not measure it. */
export function figureValue(kpi: PlanningKpi, figure: ReplayFigure): number | null {
  switch (figure) {
    case 'hard':
      return kpi.scoreHard;
    case 'medium':
      return kpi.scoreMediumHorsPlancher ?? kpi.scoreMedium;
    case 'soft':
      return kpi.scoreSoft;
    case 'coverage':
      return kpi.postesTotal === 0 ? null : (kpi.postesPourvus / kpi.postesTotal) * 100;
    case 'fairness':
      return kpi.heuresEcartType;
  }
}

/** One small multiple, in the live curve's coordinate space. */
export interface ReplaySeries {
  figure: ReplayFigure;
  /** `points` of the `<polyline>`; empty when no solve measured the figure. */
  polyline: string;
  /** One mark per measured solve, drawn only while they stay distinguishable. */
  points: { x: number; y: number; rank: number }[];
  top: number;
  bottom: number;
}

/**
 * The small multiples of an edition's solves. Scores and coverage include
 * zero, like the live curve — the top edge of a score box means « nothing left
 * to fix » — while the spread of hours is scaled on what it reached: a σ has
 * no meaningful zero to anchor on.
 */
export function replaySeries(resolutions: readonly KpiHistoriqueEntry[]): ReplaySeries[] {
  return REPLAY_FIGURES.map((figure) => serie(figure, resolutions));
}

function serie(figure: ReplayFigure, resolutions: readonly KpiHistoriqueEntry[]): ReplaySeries {
  const measured = resolutions
    .map((entry, rank) => ({ rank, value: figureValue(entry.kpi, figure) }))
    .filter((measure): measure is { rank: number; value: number } => measure.value !== null);
  if (measured.length === 0) {
    return { figure, polyline: '', points: [], top: 0, bottom: 0 };
  }
  const values = measured.map((measure) => measure.value);
  const withZero = figure !== 'fairness';
  const top = figure === 'coverage' ? 100 : Math.max(withZero ? 0 : -Infinity, ...values);
  const bottom = Math.min(withZero ? 0 : Infinity, ...values);
  const amplitude = top - bottom;
  const y = (value: number) =>
    amplitude === 0 ? HAUTEUR_COURBE / 2 : ((top - value) / amplitude) * HAUTEUR_COURBE;
  const points = measured.map((measure) => ({
    x: rankX(measure.rank, resolutions.length),
    y: round(y(measure.value)),
    rank: measure.rank,
  }));
  return {
    figure,
    polyline: points.map((point) => `${point.x},${point.y}`).join(' '),
    points,
    top,
    bottom,
  };
}

/** Where rank `rank` of `count` sits on the horizontal axis. */
export function rankX(rank: number, count: number): number {
  return round(count <= 1 ? LARGEUR_COURBE / 2 : (rank / (count - 1)) * LARGEUR_COURBE);
}

/** One day of the coverage band of a solve. */
export interface CoverageDay {
  date: string;
  postes: number;
  pourvus: number;
  /** Share staffed, 0 to 1 — what the cell's shade says. */
  ratio: number;
}

/** The day × coverage band of a solve, in calendar order; `null` when the row predates the figure. */
export function coverageBand(kpi: PlanningKpi): CoverageDay[] | null {
  const days = kpi.couvertureParJour;
  if (!days) {
    return null;
  }
  return Object.entries(days)
    .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
    .map(([date, day]) => ({
      date,
      postes: day.postes,
      pourvus: day.pourvus,
      ratio: day.postes === 0 ? 1 : day.pourvus / day.postes,
    }));
}

function difference(before: number | null, after: number | null): number | null {
  return before === null || after === null ? null : after - before;
}

function timeOf(at: string | null): number {
  const time = at ? Date.parse(at) : NaN;
  return Number.isNaN(time) ? 0 : time;
}

function round(value: number): number {
  return Math.round(value * 100) / 100;
}
