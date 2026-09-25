// Pure summarization logic shared by every preview that shows a grid day by
// day — the series dialog, the derivation from the stands' hours: groups the
// vacations by calendar day and sorts them chronologically, so a dialog can
// show "on this day, these vacations" without any component/rendering
// machinery involved. Kept separate so it can be unit-tested directly,
// following the same split as `staffing.ts`.

import { Creneau } from '../../core/models';

export interface VacationResumee {
  heureDebut: string;
  heureFin: string;
}

export interface JourResume {
  date: string;
  vacations: VacationResumee[];
  /** The public holiday's name that day, `null` on an ordinary day. */
  ferie: string | null;
}

/**
 * The vacations by day, each day with its public holiday when `feries` (date
 * → name, from `JoursFeriesService`) names one — the frontend never computes
 * one itself.
 */
export function summarizeVacationsByDay(
  vacations: Creneau[],
  feries: ReadonlyMap<string, string> = new Map(),
): JourResume[] {
  const byDate = new Map<string, Creneau[]>();
  for (const vacation of vacations) {
    const date = vacation.date ?? '';
    const list = byDate.get(date);
    if (list) {
      list.push(vacation);
    } else {
      byDate.set(date, [vacation]);
    }
  }
  return Array.from(byDate.entries())
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([date, jour]) => ({
      date,
      vacations: [...jour]
        .sort((a, b) => a.heureDebut.localeCompare(b.heureDebut))
        .map((creneau) => ({ heureDebut: creneau.heureDebut, heureFin: creneau.heureFin })),
      ferie: feries.get(date) ?? null,
    }));
}

/** The days of a summary that fall on a public holiday, in order. */
export function holidayDays(resume: readonly JourResume[]): JourResume[] {
  return resume.filter((jour) => jour.ferie !== null);
}

/** `2026-07-14` → `14/07`, the short form a list of dates reads in. */
export function dayMonth(date: string): string {
  const [, mois, jour] = date.split('-');
  return `${jour}/${mois}`;
}
