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
}

export function summarizeVacationsByDay(vacations: Creneau[]): JourResume[] {
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
    }));
}
