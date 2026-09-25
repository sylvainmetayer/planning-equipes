// The tight walks of `GET /api/planning/enchainements`, indexed for the views
// that draw a person's day: the day rail and the timeline put a chevron in the
// gap between the two vacations, and say what it lacks.
//
// Pure functions: the report is the server's — the walking time is never
// recomputed here — the geometry is each view's.

import { WalkView, WalkSequenceReport } from './models';
import { formatHeure, minutesOfDay } from './time-of-day';

/** `date|animateurId` → the tight walks of that day, in time order. */
export type WalksIndex = Map<string, WalkView[]>;

function walksKey(date: string | null | undefined, animateurId: string | null | undefined): string {
  return `${date ?? ''}|${animateurId ?? ''}`;
}

/** Indexes the report by animateur-day; empty for a missing report. */
export function indexWalks(report: WalkSequenceReport | null | undefined): WalksIndex {
  const index: WalksIndex = new Map();
  for (const walk of report?.walks ?? []) {
    const key = walksKey(walk.date, walk.animateurId);
    index.set(key, [...(index.get(key) ?? []), walk]);
  }
  return index;
}

export function walksOf(
  index: WalksIndex,
  date: string | null | undefined,
  animateurId: string,
): WalkView[] {
  return index.get(walksKey(date, animateurId)) ?? [];
}

/** One tight walk, placed on a day's track. */
export interface WalkSegment {
  /** Where the gap starts (end of the vacation left), as a 0-100 percentage of the track. */
  offsetPercent: number;
  /** Width of the gap; zero when the two vacations touch. */
  widthPercent: number;
  /** True when the walk does not fit the gap: the red chevron. */
  tight: boolean;
  /** « 14:00 → 14:10 : 20 min à pied, 10 min de battement ». */
  label: string;
}

/** The walks of one day, positioned against a track starting at `origin` and `span` minutes long. */
export function walkSegments(walks: WalkView[], origin: number, span: number): WalkSegment[] {
  return walks.map((walk) => {
    const from = minutesOfDay(walk.end ?? '00:00');
    const to = Math.max(from, minutesOfDay(walk.start ?? '00:00'));
    return {
      offsetPercent: ((from - origin) / span) * 100,
      widthPercent: ((to - from) / span) * 100,
      tight: walk.missingMinutes > 0,
      label: walkLabel(walk),
    };
  });
}

/** What a chevron says, in the words of the ticket: the walk against the gap. */
export function walkLabel(walk: WalkView): string {
  const fin = formatHeure(walk.end ?? '');
  const debut = formatHeure(walk.start ?? '');
  const trajet = walk.walkMinutes;
  const battement = walk.gapMinutes;
  return walk.missingMinutes > 0
    ? $localize`:@@walks.tight:${fin}:fin: → ${debut}:debut: : ${trajet}:trajet: min à pied, ${battement}:battement: min de battement`
    : $localize`:@@walks.onBreak:${fin}:fin: → ${debut}:debut: : ${trajet}:trajet: min à pied, pris sur la pause (${battement}:battement: min de battement)`;
}
