// The pure side of the Repas et pauses tab: the days it can show, and the tight
// walks of one day. The walking time is the server's; nothing is recomputed here.

import { RapportIntendance, RapportPauses, WalkSequenceReport, WalkView } from '../../core/models';
import { reportDays } from '../pauses/pauses';

/**
 * The days a break, a meal or a walk names, chronological, each once: the day
 * selector's options. A day holding only meals has no break row, yet the
 * intendance counts it: it is offered too.
 */
export function joursRepasPauses(
  rapport: RapportPauses | null,
  walks: WalkSequenceReport | null,
  intendance: RapportIntendance | null = null,
): string[] {
  const dates = new Set(reportDays(rapport).map((jour) => jour.date));
  for (const journee of intendance?.journees ?? []) {
    dates.add(journee.date);
  }
  for (const walk of walks?.walks ?? []) {
    if (walk.date) {
      dates.add(walk.date);
    }
  }
  return [...dates].sort((a, b) => a.localeCompare(b));
}

/** The tight walks of one day, the ones the gap does not cover first, then by time. */
export function walksOfDay(walks: WalkSequenceReport | null, date: string | null): WalkView[] {
  return (walks?.walks ?? [])
    .filter((walk) => date !== null && walk.date === date)
    .sort(
      (a, b) =>
        Number(b.missingMinutes > 0) - Number(a.missingMinutes > 0) ||
        (a.end ?? '').localeCompare(b.end ?? ''),
    );
}
