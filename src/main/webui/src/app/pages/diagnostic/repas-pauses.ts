// The pure side of the Repas et pauses tab: the days it can show, and the tight
// walks of one day. The walking time is the server's; nothing is recomputed here.

import { RapportPauses, WalkSequenceReport, WalkView } from '../../core/models';
import { joursDuRapport } from '../pauses/pauses';

/** The days a break or a walk names, chronological, each once: the day selector's options. */
export function joursRepasPauses(
  rapport: RapportPauses | null,
  walks: WalkSequenceReport | null,
): string[] {
  const dates = new Set(joursDuRapport(rapport).map((jour) => jour.date));
  for (const walk of walks?.walks ?? []) {
    if (walk.date) {
      dates.add(walk.date);
    }
  }
  return [...dates].sort();
}

/** The tight walks of one day, the ones the gap does not cover first, then by time. */
export function enchainementsDuJour(
  walks: WalkSequenceReport | null,
  date: string | null,
): WalkView[] {
  return (walks?.walks ?? [])
    .filter((walk) => date !== null && walk.date === date)
    .sort(
      (a, b) =>
        Number(b.missingMinutes > 0) - Number(a.missingMinutes > 0) ||
        (a.end ?? '').localeCompare(b.end ?? ''),
    );
}
