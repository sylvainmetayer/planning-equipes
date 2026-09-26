import { describe, expect, it } from 'vitest';
import { addRange, editionDays, rangeDates, toggleDayOff } from './jours-indisponibles';

describe('jours-indisponibles', () => {
  it('reads the edition days from its timeslots, once each, in order', () => {
    expect(
      editionDays([{ date: '2026-07-09' }, { date: '2026-07-08' }, { date: '2026-07-09' }]),
    ).toEqual(['2026-07-08', '2026-07-09']);
  });

  it('ticks and unticks a day, the list kept sorted', () => {
    expect(toggleDayOff(['2026-07-10'], '2026-07-08')).toEqual(['2026-07-08', '2026-07-10']);
    expect(toggleDayOff(['2026-07-08', '2026-07-10'], '2026-07-08')).toEqual(['2026-07-10']);
  });

  it('says « absent du 8 au 12 » in one range, on the edition days only', () => {
    const edition = ['2026-07-08', '2026-07-09', '2026-07-11', '2026-07-12', '2026-07-20'];
    const result = addRange(['2026-07-09'], '2026-07-08', '2026-07-12', edition);
    expect(result.days).toEqual(['2026-07-08', '2026-07-09', '2026-07-11', '2026-07-12']);
    expect(result.added).toBe(3);
  });

  it('takes every date of the range while the edition has no day yet', () => {
    expect(addRange([], '2026-07-08', '2026-07-10', []).days).toEqual([
      '2026-07-08',
      '2026-07-09',
      '2026-07-10',
    ]);
  });

  it('adds nothing for an inverted or unreadable range', () => {
    expect(rangeDates('2026-07-12', '2026-07-08')).toEqual([]);
    expect(rangeDates('', '2026-07-08')).toEqual([]);
  });
});
