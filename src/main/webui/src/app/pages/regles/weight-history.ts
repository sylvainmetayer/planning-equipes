// The weight history of one rule, read for the screen: a dated list mixing the
// changes and the solves that followed them, and the geometry of a small
// hand-drawn SVG chart — this rule's violations per solve, the changes as
// vertical markers. Pure, so both are unit tested without rendering; there is
// no charting dependency (see src/main/webui/AGENTS.md).

import { dosageKey } from '../../core/dosage';
import { ConstraintHistory, ResolutionUnderDosage, WeightChange } from '../../core/models';

/** One line of the dated list, oldest first. */
export type HistoryItem =
  | { kind: 'change'; at: string; change: WeightChange }
  | {
      kind: 'resolution';
      at: string;
      resolution: ResolutionUnderDosage;
      /** The deployment's own weights moved since the previous solve: not an edition gesture. */
      instanceChanged: boolean;
    };

/**
 * Changes and solves merged by date — a change dated exactly like a solve goes
 * first, since a solve takes its dosage at launch.
 */
export function historyItems(history: ConstraintHistory): HistoryItem[] {
  const items: HistoryItem[] = [
    ...history.changes.map((change) => ({ kind: 'change' as const, at: change.createdAt, change })),
    ...history.resolutions.map((resolution, index) => ({
      kind: 'resolution' as const,
      at: resolution.createdAt,
      resolution,
      instanceChanged: instanceChanged(history.resolutions[index - 1], resolution),
    })),
  ];
  return items
    .map((item, order) => ({ item, order }))
    .sort((a, b) => {
      const byDate = timeOf(a.item.at) - timeOf(b.item.at);
      if (byDate !== 0) {
        return byDate;
      }
      if (a.item.kind !== b.item.kind) {
        return a.item.kind === 'change' ? -1 : 1;
      }
      return a.order - b.order;
    })
    .map(({ item }) => item);
}

/** Both dosages known, and the deployment's layer of the two differs. */
function instanceChanged(
  previous: ResolutionUnderDosage | undefined,
  current: ResolutionUnderDosage,
): boolean {
  if (!previous?.dosage || !current.dosage) {
    return false;
  }
  return (
    JSON.stringify(sortedWeights(previous.dosage.instanceWeights)) !==
    JSON.stringify(sortedWeights(current.dosage.instanceWeights))
  );
}

/** « 1 → 20 », « 20 → défaut (1) », « active → désactivée ». */
export function changeLabel(change: WeightChange): string {
  if (change.weightAfter !== null) {
    const before = change.weightBefore ?? '?';
    return change.backToDefault
      ? $localize`:@@weightHistory.change.backToDefault:Poids ${before}:before: → défaut (${change.weightAfter}:after:)`
      : $localize`:@@weightHistory.change.weight:Poids ${before}:before: → ${change.weightAfter}:after:`;
  }
  const before = activeLabel(change.activeBefore);
  const after = activeLabel(change.activeAfter);
  return $localize`:@@weightHistory.change.active:${before}:before: → ${after}:after:`;
}

function activeLabel(active: boolean | null): string {
  if (active === null) {
    return '?';
  }
  return active
    ? $localize`:@@weightHistory.active:active`
    : $localize`:@@weightHistory.inactive:désactivée`;
}

/** Where a change came from, in words — never who. */
export function originLabel(change: WeightChange): string {
  switch (change.origin) {
    case 'SCREEN':
      return $localize`:@@weightHistory.origin.screen:écran`;
    case 'ASSISTANT':
      return $localize`:@@weightHistory.origin.assistant:assistant`;
    case 'SCENARIO':
      return $localize`:@@weightHistory.origin.scenario:import de scénario`;
    case 'DUPLICATION':
      return change.sourceEdition
        ? $localize`:@@weightHistory.origin.duplicationFrom:hérité de ${change.sourceEdition}:edition:`
        : $localize`:@@weightHistory.origin.duplication:duplication`;
  }
}

/** A mark of the chart: a solve and its violations of the rule, or a change. */
export interface ChartPoint {
  x: number;
  y: number;
  violations: number;
  at: string;
}

export interface ChartMarker {
  x: number;
  at: string;
  label: string;
}

export interface HistoryChart {
  width: number;
  height: number;
  /** The violations polyline, `x,y` pairs; solves that did not measure the rule are left out, their rank kept. */
  polyline: string;
  points: ChartPoint[];
  markers: ChartMarker[];
  maxViolations: number;
}

/** Inner margins of the chart, in its own units. */
const PAD = { left: 28, right: 8, top: 8, bottom: 16 };

/**
 * The chart of `items`: the horizontal axis is the rank in the dated list, not
 * real time — three solves in a minute and one a week later stay readable —
 * and the vertical one this rule's violations, on its own scale from zero.
 * `null` when there is nothing to draw: no solve measured the rule.
 */
export function historyChart(items: HistoryItem[], width = 480, height = 120): HistoryChart | null {
  const measured = items.filter(
    (item) => item.kind === 'resolution' && item.resolution.ruleViolations !== null,
  );
  if (measured.length === 0) {
    return null;
  }
  const maxViolations = Math.max(
    1,
    ...measured.map((item) =>
      item.kind === 'resolution' ? (item.resolution.ruleViolations ?? 0) : 0,
    ),
  );
  const innerWidth = width - PAD.left - PAD.right;
  const innerHeight = height - PAD.top - PAD.bottom;
  const step = items.length > 1 ? innerWidth / (items.length - 1) : 0;
  const xOf = (index: number) =>
    round(PAD.left + (items.length > 1 ? index * step : innerWidth / 2));
  const points: ChartPoint[] = [];
  const markers: ChartMarker[] = [];
  items.forEach((item, index) => {
    if (item.kind === 'change') {
      markers.push({ x: xOf(index), at: item.at, label: changeLabel(item.change) });
    } else if (item.resolution.ruleViolations !== null) {
      const violations = item.resolution.ruleViolations;
      points.push({
        x: xOf(index),
        y: round(PAD.top + innerHeight - (violations / maxViolations) * innerHeight),
        violations,
        at: item.at,
      });
    }
  });
  return {
    width,
    height,
    polyline: points.map((point) => `${point.x},${point.y}`).join(' '),
    points,
    markers,
    maxViolations,
  };
}

/** How many distinct weightings the solves of the list ran under — a known dosage each. */
export function distinctDosages(items: HistoryItem[]): number {
  const keys = new Set<string>();
  for (const item of items) {
    if (item.kind === 'resolution') {
      const key = dosageKey(item.resolution.dosage);
      if (key !== null) {
        keys.add(key);
      }
    }
  }
  return keys.size;
}

function sortedWeights(record: Record<string, number> | undefined): [string, number][] {
  return Object.entries(record ?? {}).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0));
}

function timeOf(at: string): number {
  const time = Date.parse(at);
  return Number.isNaN(time) ? 0 : time;
}

function round(value: number): number {
  return Math.round(value * 10) / 10;
}
