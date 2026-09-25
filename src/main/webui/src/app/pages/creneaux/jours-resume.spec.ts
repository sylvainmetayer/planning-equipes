import { describe, expect, it } from 'vitest';
import { Creneau } from '../../core/models';
import { dayMonth, holidayDays, summarizeVacationsByDay } from './jours-resume';

function creneau(overrides: Partial<Creneau> & { id: number }): Creneau {
  return {
    jour: 1,
    date: '2026-08-01',
    heureDebut: '09:00',
    heureFin: '12:00',
    ...overrides,
  };
}

describe('summarizeVacationsByDay', () => {
  it('groups vacations by date and sorts each day chronologically', () => {
    const vacations = [
      creneau({ id: 1, date: '2026-07-10', heureDebut: '18:30', heureFin: '00:00' }),
      creneau({ id: 2, date: '2026-07-10', heureDebut: '10:00', heureFin: '14:00' }),
      creneau({ id: 3, date: '2026-07-10', heureDebut: '13:30', heureFin: '19:00' }),
      creneau({ id: 4, date: '2026-07-09', heureDebut: '10:00', heureFin: '15:00' }),
    ];

    const summary = summarizeVacationsByDay(vacations);

    expect(summary.map((jour) => jour.date)).toEqual(['2026-07-09', '2026-07-10']);
    expect(summary[1].vacations).toEqual([
      { heureDebut: '10:00', heureFin: '14:00' },
      { heureDebut: '13:30', heureFin: '19:00' },
      { heureDebut: '18:30', heureFin: '00:00' },
    ]);
  });

  it('returns an empty list for no vacations', () => {
    expect(summarizeVacationsByDay([])).toEqual([]);
  });

  it('names the public holiday of a day from the map it is given, and nothing else', () => {
    const summary = summarizeVacationsByDay(
      [creneau({ id: 1, date: '2026-07-13' }), creneau({ id: 2, date: '2026-07-14' })],
      new Map([['2026-07-14', 'Fête nationale']]),
    );

    expect(summary.map((jour) => jour.ferie)).toEqual([null, 'Fête nationale']);
    expect(holidayDays(summary).map((jour) => dayMonth(jour.date))).toEqual(['14/07']);
  });
});
