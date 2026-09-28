import { describe, expect, it } from 'vitest';
import { JourEvenement } from '../journee/journee';
import { joursGrille, readGridSpan, sliceDays, spanWindow } from './jours-grille';
import { LigneGrille, PiedGrille } from './planning-grille';

/** From Thursday 2026-07-09: eighteen days are a short first week, then two full ones. */
function days(count: number, firstDate = '2026-07-09'): JourEvenement[] {
  const start = new Date(`${firstDate}T00:00:00`);
  return Array.from({ length: count }, (_, index) => {
    const date = new Date(start);
    date.setDate(start.getDate() + index);
    const key = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
    return { key, jour: index + 1, date: key, title: `Jour ${index + 1} — ${key}` };
  });
}

describe('The span of the grids', () => {
  const columns = joursGrille(days(18));

  it('reads the week when the key is absent or unknown', () => {
    expect(readGridSpan(null)).toBe('semaine');
    expect(readGridSpan('mois')).toBe('semaine');
    expect(readGridSpan('jour')).toBe('jour');
    expect(readGridSpan('evenement')).toBe('evenement');
  });

  it('keeps the week of the marked day, cut on the bands the header draws', () => {
    // Wednesday 2026-07-15: the week of Monday 13 to Sunday 19.
    const window = spanWindow(columns, '2026-07-15', 'semaine');
    expect(columns.slice(window.start, window.end).map((column) => column.key)).toEqual([
      '2026-07-13',
      '2026-07-14',
      '2026-07-15',
      '2026-07-16',
      '2026-07-17',
      '2026-07-18',
      '2026-07-19',
    ]);
    // The event opens on a Thursday: its first week is the event's four days.
    expect(spanWindow(columns, '2026-07-10', 'semaine')).toEqual({ start: 0, end: 4 });
  });

  it('keeps the marked day alone, or every day', () => {
    expect(spanWindow(columns, '2026-07-16', 'jour')).toEqual({ start: 7, end: 8 });
    expect(spanWindow(columns, '2026-07-16', 'evenement')).toEqual({ start: 0, end: 18 });
    // Nothing marked: the first day, and its week.
    expect(spanWindow(columns, null, 'jour')).toEqual({ start: 0, end: 1 });
    expect(spanWindow(columns, null, 'semaine')).toEqual({ start: 0, end: 4 });
  });

  it('cuts the weeks of an event closed on Mondays on the calendar, not on a Monday column', () => {
    // Wednesday to Sunday, three weeks running: no column is ever a Monday.
    const closedMondays = joursGrille(
      days(19, '2026-07-08').filter(
        (jour) => ![1, 2].includes(new Date(`${jour.date}T00:00:00`).getDay()),
      ),
    );
    const window = spanWindow(closedMondays, '2026-07-16', 'semaine');
    expect(closedMondays.slice(window.start, window.end).map((column) => column.key)).toEqual([
      '2026-07-15',
      '2026-07-16',
      '2026-07-17',
      '2026-07-18',
      '2026-07-19',
    ]);
    // A gap from a Sunday to a Wednesday is a new week, as the header draws it.
    expect(closedMondays.map((column) => column.debutSemaine)).toEqual([
      false,
      false,
      false,
      false,
      false,
      true,
      false,
      false,
      false,
      false,
      true,
      false,
      false,
      false,
      false,
    ]);
  });

  it('cuts an undated plan every seventh day', () => {
    const undated = joursGrille(
      Array.from({ length: 10 }, (_, index) => ({
        key: `J${index + 1}`,
        jour: index + 1,
        date: null,
        title: `Jour ${index + 1}`,
      })),
    );
    expect(spanWindow(undated, 'J9', 'semaine')).toEqual({ start: 7, end: 10 });
  });

  it('narrows the day cells and the day totals, never the summary', () => {
    const line: LigneGrille = {
      id: 'l',
      cases: ['a', 'b', 'c', 'd'].map((libelle) => ({ classe: '', libelle, active: true })),
      synthese: { total: { texte: '4' } },
    };
    const pied: PiedGrille = {
      libelle: 'Total',
      cases: ['1', '2', '3', '4'],
      synthese: { total: '4' },
    };

    const narrowed = sliceDays({ lignes: [line], pied, extra: 'kept' }, { start: 1, end: 3 });

    expect(narrowed.lignes[0].cases.map((cell) => cell.libelle)).toEqual(['b', 'c']);
    expect(narrowed.lignes[0].synthese).toEqual({ total: { texte: '4' } });
    expect(narrowed.pied.cases).toEqual(['2', '3']);
    expect(narrowed.pied.synthese).toEqual({ total: '4' });
    expect(narrowed.extra).toBe('kept');
  });
});
