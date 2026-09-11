// The breaks of `GET /api/pauses`, indexed for the views that draw a person's
// day: the timeline and the day rail overlay them on the vacations, the
// day calendar and the summaries count the ones without relay.
//
// Pure functions: the report is the server's, the geometry is each view's.

import { PauseDueView, RapportPauses } from './models';
import { formatHeure, minutesOfDay } from './time-of-day';

/** `date|animateurId` → the breaks of that day, in start order. */
export type IndexPauses = Map<string, PauseDueView[]>;

export function clePauses(date: string | null | undefined, animateurId: string): string {
  return `${date ?? ''}|${animateurId}`;
}

/** Indexes the report by animateur-day; empty for a missing report. */
export function indexerPauses(rapport: RapportPauses | null | undefined): IndexPauses {
  const index: IndexPauses = new Map();
  for (const journee of rapport?.journees ?? []) {
    const pauses = journee.sequences.flatMap((sequence) => sequence.pausesDues);
    if (pauses.length === 0) {
      continue;
    }
    index.set(
      clePauses(journee.date, journee.animateurId),
      [...pauses].sort((a, b) => a.debut.localeCompare(b.debut)),
    );
  }
  return index;
}

export function pausesDe(
  index: IndexPauses,
  date: string | null | undefined,
  animateurId: string,
): PauseDueView[] {
  return index.get(clePauses(date, animateurId)) ?? [];
}

/** A break drawn on a track: position as a share of the track, and what the tooltip says. */
export interface SegmentPause {
  heureDebut: string;
  heureFin: string;
  standId: string;
  standNom: string;
  /** The timeslot of the seat held during the break; null on an older payload. */
  creneauId: number | null;
  offsetPercent: number;
  widthPercent: number;
  /** Nobody else on the stand during the break: the mark to show first. */
  withoutRelais: boolean;
  /** Out at the same time as a colleague's break. */
  simultanee: boolean;
  label: string;
}

/**
 * Places breaks on a track whose origin is `debutMinutes` and whose length is
 * `amplitudeMinutes`. Breaks outside the track are dropped rather than drawn
 * off-scale.
 */
export function segmentsPause(
  pauses: PauseDueView[],
  debutMinutes: number,
  amplitudeMinutes: number,
): SegmentPause[] {
  const amplitude = Math.max(1, amplitudeMinutes);
  const segments: SegmentPause[] = [];
  for (const pause of pauses) {
    const debut = minutesOfDay(pause.debut);
    let fin = minutesOfDay(pause.fin);
    if (fin <= debut) {
      // A break crossing midnight ends the next day: drawn to the end of the track.
      fin = debutMinutes + amplitude;
    }
    if (fin <= debutMinutes || debut >= debutMinutes + amplitude) {
      continue;
    }
    segments.push({
      heureDebut: formatHeure(pause.debut),
      heureFin: formatHeure(pause.fin),
      standId: pause.standId,
      standNom: pause.standNom,
      creneauId: pause.creneauId ?? null,
      offsetPercent: ((Math.max(debut, debutMinutes) - debutMinutes) / amplitude) * 100,
      widthPercent:
        ((Math.min(fin, debutMinutes + amplitude) - Math.max(debut, debutMinutes)) / amplitude) *
        100,
      withoutRelais: !pause.relaisDisponible,
      simultanee: pause.simultanee,
      label: libellePause(pause),
    });
  }
  return segments;
}

/** « Pause 18:20 – 18:40 sur Village des jeux — personne d'autre sur le stand ». */
export function libellePause(pause: PauseDueView): string {
  const base = $localize`:@@pauses.segment.label:Pause ${formatHeure(pause.debut)}:debut: – ${formatHeure(pause.fin)}:fin: sur ${pause.standNom}:stand:`;
  if (!pause.relaisDisponible) {
    return $localize`:@@pauses.segment.sansRelais:${base}:pause: — personne d'autre sur le stand`;
  }
  if (pause.simultanee) {
    return $localize`:@@pauses.segment.simultanee:${base}:pause: — en même temps qu'une autre pause`;
  }
  return base;
}

/** How many breaks of the report — or of one day of it — have nobody to relay. */
export function compterSansRelais(
  rapport: RapportPauses | null | undefined,
  date?: string | null,
): number {
  let total = 0;
  for (const journee of rapport?.journees ?? []) {
    if (date && journee.date !== date) {
      continue;
    }
    for (const sequence of journee.sequences) {
      total += sequence.pausesDues.filter((pause) => !pause.relaisDisponible).length;
    }
  }
  return total;
}
