// Pure summarization logic for the découpage preview: groups the generated
// vacations by calendar day and sorts them chronologically, so the page can
// show "on this day, the amplitude was split into these vacations" without
// any component/rendering machinery involved — kept separate so it can be
// unit-tested directly, following the same split as `staffing.ts`.

import { Creneau } from '../../core/models';

export interface DecoupageVacationSummary {
  heureDebut: string;
  heureFin: string;
}

export interface DecoupageJourSummary {
  date: string;
  vacations: DecoupageVacationSummary[];
}

export function summarizeVacationsByDay(vacations: Creneau[]): DecoupageJourSummary[] {
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
