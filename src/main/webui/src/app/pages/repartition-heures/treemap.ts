// Geometry and aggregation of the « Répartition des heures » treemap, with no
// rendering and no Angular: turning the persisted plan into a tree of
// seat-hours, then that tree into rectangles.
//
// Two decisions carry the whole screen.
//
// The SIZE is the seat-hours to staff — filled and empty seats alike — so a
// rectangle is the mass of the need, not only of what was held. An hour is the
// seat's effective window (its own narrowed window when a partial closure cut
// it, its timeslot's otherwise), like the Planning page's other readings: a
// stand's need is not reduced by its holder's legal break.
//
// The tree is an EXACT PARTITION. The game-category reading of the plan
// (« Par typologie ») counts a seat once per game category its stand
// offers, on purpose; a treemap cannot, since overlapping surfaces lie about
// the mass, which is the only thing a treemap says. A stand offering several
// categories is therefore filed under one « combination » group named by all
// of them, and the surfaces add up to the edition's total.
//
// The layout is the squarified algorithm of Bruls, Huizing and van Wijk
// (2000), written by hand: the frontend carries no charting dependency.

import { parseDateKey, toDateKey } from '../../core/date-utils';
import { PosteAffectation, Stand } from '../../core/models';
import { compareCodeUnits } from '../../core/string-order';
import { endMinutesOfDay, minutesOfDay } from '../../core/time-of-day';

/** How the first level of the tree is formed. */
export type TreemapGrouping = 'stand' | 'typologie';

/** The stretch of the edition the hours are counted over. */
export type TreemapPeriod =
  { kind: 'all' } | { kind: 'week'; monday: string } | { kind: 'day'; date: string };

/**
 * Coverage of a node, read on a rate: under 80 %, 80–99 %, 100 %. Drawn in the
 * Heatmap's colours, but on thresholds of its own — the Heatmap calls a cell
 * critical only when nobody holds it, which on a whole stand or group would
 * hide every shortfall short of total.
 */
export type CoverageLevel = 'none' | 'critical' | 'partial' | 'full';

/** Below this rate a group is « critique ». */
export const CRITICAL_RATE = 0.8;

/** Emplacement filter value selecting the stands tied to no emplacement. */
export const NO_EMPLACEMENT = 'aucun';

/** A node of the hours tree: the root, a group, a stand, or the « Autres » bucket. */
export interface HoursNode {
  /** Unique across the tree and stable across reloads: what the zoom is kept in the URL under. */
  id: string;
  label: string;
  kind: 'root' | 'group' | 'stand' | 'others';
  /** Set on a stand only. */
  standId: string | null;
  /** Seat-minutes to staff, filled or empty. */
  requiredMinutes: number;
  /** Seat-minutes somebody holds. */
  filledMinutes: number;
  /** Stands under this node, itself included when it is one. */
  standCount: number;
  children: HoursNode[];
}

/** Everything {@link aggregateHours} reads. */
export interface HoursInput {
  postes: readonly PosteAffectation[];
  /**
   * The edition's stands from the referential. A stand with no seat in the
   * period still has a line in the table (0 h), and only this list knows it.
   * Stands met on a seat but missing here are added from the seat.
   */
  stands: readonly Stand[];
  grouping: TreemapGrouping;
  period: TreemapPeriod;
  /** An emplacement id, {@link NO_EMPLACEMENT}, or null for every place. */
  emplacementId: string | null;
  /** Typologie id → display label; an unknown id is shown raw. */
  typologieLabels: ReadonlyMap<string, string>;
  /** Labels the pure module cannot word by itself without the i18n runtime. */
  labels: {
    root: string;
    noEmplacement: string;
    noTypologie: string;
  };
}

/** Minutes a seat covers: its own narrowed window when it has one, its timeslot's otherwise. */
export function seatMinutes(poste: PosteAffectation): number {
  const creneau = poste.creneau;
  if (!creneau) {
    return 0;
  }
  const start = minutesOfDay(poste.heureDebutEffective ?? creneau.heureDebut);
  const end = endMinutesOfDay(poste.heureFinEffective ?? creneau.heureFin);
  return Math.max(end - start, 0);
}

/** Monday of the week a `yyyy-MM-dd` date falls in, as a date key. */
export function mondayOf(date: string): string {
  const day = parseDateKey(date);
  const offset = (day.getDay() + 6) % 7;
  return toDateKey(new Date(day.getFullYear(), day.getMonth(), day.getDate() - offset));
}

/** True when a seat of that date belongs to the period. */
export function inPeriod(date: string | null | undefined, period: TreemapPeriod): boolean {
  if (period.kind === 'all') {
    return true;
  }
  if (!date) {
    return false;
  }
  return period.kind === 'day' ? date === period.date : mondayOf(date) === period.monday;
}

/** The distinct dates carrying a seat, and the Mondays of their weeks, sorted. */
export function periodChoices(postes: readonly PosteAffectation[]): {
  weeks: string[];
  days: string[];
} {
  const days = new Set<string>();
  for (const poste of postes) {
    if (poste.creneau?.date) {
      days.add(poste.creneau.date);
    }
  }
  const sortedDays = [...days].sort(compareCodeUnits);
  return { weeks: [...new Set(sortedDays.map(mondayOf))], days: sortedDays };
}

/** Coverage level of a filled / required pair: critical under {@link CRITICAL_RATE}. */
export function coverageLevel(filledMinutes: number, requiredMinutes: number): CoverageLevel {
  if (requiredMinutes <= 0) {
    return 'none';
  }
  const rate = filledMinutes / requiredMinutes;
  if (rate < CRITICAL_RATE) {
    return 'critical';
  }
  return filledMinutes < requiredMinutes ? 'partial' : 'full';
}

/**
 * The hours tree of the period: root, one group per emplacement (by stand) or
 * per typologie combination (by typologie), one leaf per stand. Every stand
 * of the referential passing the emplacement filter is a leaf, at 0 h when the
 * period gives it no seat. Siblings are sorted by hours, heaviest first, then
 * by label.
 */
export function aggregateHours(input: HoursInput): HoursNode {
  const stands = knownStands(input.stands, input.postes);
  const totals = new Map<string, { required: number; filled: number }>();
  for (const poste of input.postes) {
    if (!poste.stand || !poste.creneau || !inPeriod(poste.creneau.date, input.period)) {
      continue;
    }
    const minutes = seatMinutes(poste);
    const total = totals.get(poste.stand.id) ?? { required: 0, filled: 0 };
    total.required += minutes;
    if (poste.animateur) {
      total.filled += minutes;
    }
    totals.set(poste.stand.id, total);
  }

  const groups = new Map<string, HoursNode>();
  for (const stand of stands) {
    if (!matchesEmplacement(stand, input.emplacementId)) {
      continue;
    }
    const { key, label } = groupOf(stand, input);
    let group = groups.get(key);
    if (!group) {
      group = node(key, label, 'group', null, []);
      groups.set(key, group);
    }
    const total = totals.get(stand.id) ?? { required: 0, filled: 0 };
    group.children.push({
      id: `s:${stand.id}`,
      label: stand.nom || stand.id,
      kind: 'stand',
      standId: stand.id,
      requiredMinutes: total.required,
      filledMinutes: total.filled,
      standCount: 1,
      children: [],
    });
  }
  const root = node('root', input.labels.root, 'root', null, [...groups.values()]);
  return summed(root);
}

/**
 * The stands the screen knows of: the referential's, then those met on a seat
 * but missing from it — a plan outliving a stand's deletion, or a referential
 * that failed to load. In that order, each stand once.
 */
export function knownStands(
  referential: readonly Stand[],
  postes: readonly PosteAffectation[],
): Stand[] {
  const stands = new Map<string, Stand>();
  for (const stand of referential) {
    stands.set(stand.id, stand);
  }
  for (const poste of postes) {
    if (poste.stand && !stands.has(poste.stand.id)) {
      stands.set(poste.stand.id, poste.stand);
    }
  }
  return [...stands.values()];
}

function matchesEmplacement(stand: Stand, emplacementId: string | null): boolean {
  if (emplacementId === null) {
    return true;
  }
  const own = stand.emplacement?.id ?? null;
  return emplacementId === NO_EMPLACEMENT ? own === null : own === emplacementId;
}

function groupOf(stand: Stand, input: HoursInput): { key: string; label: string } {
  if (input.grouping === 'stand') {
    const emplacement = stand.emplacement;
    return emplacement
      ? { key: `e:${emplacement.id}`, label: emplacement.nom || emplacement.id }
      : { key: 'e:', label: input.labels.noEmplacement };
  }
  const typologies = [...new Set(stand.typologiesProposees ?? [])].sort(compareCodeUnits);
  if (typologies.length === 0) {
    return { key: 't:', label: input.labels.noTypologie };
  }
  const label = typologies
    .map((id) => input.typologieLabels.get(id) ?? id)
    .sort((left, right) => left.localeCompare(right))
    .join(' + ');
  return { key: `t:${typologies.join('+')}`, label };
}

function node(
  id: string,
  label: string,
  kind: HoursNode['kind'],
  standId: string | null,
  children: HoursNode[],
): HoursNode {
  return {
    id,
    label,
    kind,
    standId,
    requiredMinutes: 0,
    filledMinutes: 0,
    standCount: 0,
    children,
  };
}

/** Sums the leaves up the tree and sorts every level, heaviest first. */
function summed(tree: HoursNode): HoursNode {
  if (tree.children.length === 0) {
    return tree;
  }
  const children = tree.children.map(summed).sort(byWeight);
  return {
    ...tree,
    children,
    requiredMinutes: children.reduce((sum, child) => sum + child.requiredMinutes, 0),
    filledMinutes: children.reduce((sum, child) => sum + child.filledMinutes, 0),
    standCount: children.reduce((sum, child) => sum + child.standCount, 0),
  };
}

function byWeight(left: HoursNode, right: HoursNode): number {
  return right.requiredMinutes - left.requiredMinutes || left.label.localeCompare(right.label);
}

/** Share of its parent above which a child is said to crush the others. */
export const DOMINANT_SHARE = 0.5;
/** Share of its parent under which a child joins « Autres » once one crushes the rest. */
export const SMALL_SHARE = 0.05;

/**
 * The tree the treemap draws: at every level where one child weighs more than
 * half of its parent, the children under 5 % of it are gathered in one
 * « Autres (n stands) » node — they would otherwise be slivers nobody can read
 * nor click. The bucket is a node like any other: it can be zoomed into, and
 * shows what it holds. Only formed when it gathers two children or more, and
 * children at 0 h are dropped, since a treemap has no room for them — the
 * table, built on the full tree, keeps them.
 */
export function withOthers(
  tree: HoursNode,
  othersLabel: (standCount: number) => string,
): HoursNode {
  const children = tree.children
    .filter((child) => child.requiredMinutes > 0)
    .map((child) => withOthers(child, othersLabel));
  const total = tree.requiredMinutes;
  const dominant = children.some((child) => child.requiredMinutes > total * DOMINANT_SHARE);
  const small = dominant
    ? children.filter((child) => child.requiredMinutes < total * SMALL_SHARE)
    : [];
  if (small.length < 2) {
    return { ...tree, children };
  }
  const kept = children.filter((child) => !small.includes(child));
  const standCount = small.reduce((sum, child) => sum + child.standCount, 0);
  const others: HoursNode = {
    id: `o:${tree.id}`,
    label: othersLabel(standCount),
    kind: 'others',
    standId: null,
    requiredMinutes: small.reduce((sum, child) => sum + child.requiredMinutes, 0),
    filledMinutes: small.reduce((sum, child) => sum + child.filledMinutes, 0),
    standCount,
    children: small,
  };
  return { ...tree, children: [...kept, others] };
}

/** The node of that id, and the path of nodes leading to it from the root, root first. */
export function findPath(tree: HoursNode, id: string): HoursNode[] | null {
  if (tree.id === id) {
    return [tree];
  }
  for (const child of tree.children) {
    const path = findPath(child, id);
    if (path) {
      return [tree, ...path];
    }
  }
  return null;
}

/** A rectangle of the layout. */
export interface Rect {
  x: number;
  y: number;
  width: number;
  height: number;
}

/** One weighted item handed to {@link squarify}. */
export interface WeightedItem {
  id: string;
  value: number;
}

export interface PlacedRect extends Rect {
  id: string;
}

/**
 * Squarified treemap of Bruls, Huizing and van Wijk: the items, heaviest
 * first, are laid in rows along the shorter side of what is left, and a row
 * takes one more item only while that does not worsen its worst aspect ratio.
 * The rectangles tile `bounds` exactly — their areas add up to its area, in
 * proportion to the values — and never overlap. Items at zero or below are
 * left out.
 */
export function squarify(items: readonly WeightedItem[], bounds: Rect): PlacedRect[] {
  const positive = items.filter((item) => item.value > 0).sort((a, b) => b.value - a.value);
  const total = positive.reduce((sum, item) => sum + item.value, 0);
  if (total <= 0 || bounds.width <= 0 || bounds.height <= 0) {
    return [];
  }
  const scale = (bounds.width * bounds.height) / total;
  const areas = positive.map((item) => ({ id: item.id, area: item.value * scale }));

  const placed: PlacedRect[] = [];
  let free: Rect = { ...bounds };
  let row: { id: string; area: number }[] = [];
  for (let index = 0; index < areas.length; index++) {
    const item = areas[index];
    const side = Math.min(free.width, free.height);
    if (row.length === 0 || worst([...row, item], side) <= worst(row, side)) {
      row.push(item);
      continue;
    }
    free = layRow(row, free, placed);
    row = [item];
  }
  if (row.length > 0) {
    layRow(row, free, placed, true);
  }
  return placed;
}

/** Worst aspect ratio of a row laid along a side of that length. */
function worst(row: readonly { area: number }[], side: number): number {
  const sum = row.reduce((total, item) => total + item.area, 0);
  const max = Math.max(...row.map((item) => item.area));
  const min = Math.min(...row.map((item) => item.area));
  const squared = side * side;
  const rowSquared = sum * sum;
  return Math.max((squared * max) / rowSquared, rowSquared / (squared * min));
}

/**
 * Lays a row along the shorter side of `free` and answers what is left. The
 * last row fills the remainder exactly rather than by its own arithmetic, so
 * rounding never leaves a gap at the far edge.
 */
function layRow(
  row: readonly { id: string; area: number }[],
  free: Rect,
  placed: PlacedRect[],
  last = false,
): Rect {
  const sum = row.reduce((total, item) => total + item.area, 0);
  if (free.width >= free.height) {
    // A column on the left, as wide as the row's area over the height.
    const width = last ? free.width : sum / free.height;
    let y = free.y;
    row.forEach((item, index) => {
      const height =
        index === row.length - 1 ? free.y + free.height - y : (item.area / sum) * free.height;
      placed.push({ id: item.id, x: free.x, y, width, height });
      y += height;
    });
    return { x: free.x + width, y: free.y, width: free.width - width, height: free.height };
  }
  // A row along the top, as tall as the row's area over the width.
  const height = last ? free.height : sum / free.width;
  let x = free.x;
  row.forEach((item, index) => {
    const width =
      index === row.length - 1 ? free.x + free.width - x : (item.area / sum) * free.width;
    placed.push({ id: item.id, x, y: free.y, width, height });
    x += width;
  });
  return { x: free.x, y: free.y + height, width: free.width, height: free.height - height };
}

/** One tile the page draws: a node, where it sits, and how deep it is under the zoomed node. */
export interface Tile extends Rect {
  node: HoursNode;
  /** 1 for a child of the zoomed node, 2 for a grandchild drawn inside it. */
  depth: 1 | 2;
}

/**
 * Room a group keeps at its top for its own title, in the units of the bounds
 * — rendered pixels on the page. 40 px is the title as drawn at a 16 px root
 * font: the tile's top padding and two lines of body-small (name, figures),
 * with a little air; the page passes its own when the root font differs.
 */
export const GROUP_HEADER = 40;
/** Inset of a group's children from its edges, in the units of the bounds. */
export const GROUP_PADDING = 3;

/**
 * The tiles of one zoom level: the children of `tree`, squarified into
 * `bounds`, and inside each child that has children of its own, those
 * grandchildren squarified into what remains under its title — `header` below
 * the child's top, so they never cover it. Two levels, never more: the next
 * one is a click away, and a third nesting would be too small to read.
 */
export function layoutTiles(tree: HoursNode, bounds: Rect, header = GROUP_HEADER): Tile[] {
  const byId = new Map(tree.children.map((child) => [child.id, child]));
  const tiles: Tile[] = [];
  const top = squarify(
    tree.children.map((child) => ({ id: child.id, value: child.requiredMinutes })),
    bounds,
  );
  for (const rect of top) {
    const child = byId.get(rect.id)!;
    tiles.push({ ...rect, node: child, depth: 1 });
    const inner: Rect = {
      x: rect.x + GROUP_PADDING,
      y: rect.y + header,
      width: rect.width - 2 * GROUP_PADDING,
      height: rect.height - header - GROUP_PADDING,
    };
    if (child.children.length === 0 || inner.width <= 0 || inner.height <= 0) {
      continue;
    }
    const grandchildren = new Map(child.children.map((grandchild) => [grandchild.id, grandchild]));
    for (const placed of squarify(
      child.children.map((grandchild) => ({
        id: grandchild.id,
        value: grandchild.requiredMinutes,
      })),
      inner,
    )) {
      tiles.push({ ...placed, node: grandchildren.get(placed.id)!, depth: 2 });
    }
  }
  return tiles;
}

/** Flat rows of the table alternative: every node of the tree under the root, depth first. */
export interface HoursRow {
  node: HoursNode;
  depth: number;
}

export function tableRows(tree: HoursNode): HoursRow[] {
  const rows: HoursRow[] = [];
  const walk = (current: HoursNode, depth: number): void => {
    for (const child of current.children) {
      rows.push({ node: child, depth });
      walk(child, depth + 1);
    }
  };
  walk(tree, 0);
  return rows;
}
