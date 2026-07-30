import { describe, expect, it } from 'vitest';
import {
  buildMonthCells,
  getMonthStart,
  parseDateKey,
  pickDefaultDateKey,
  shiftMonth,
  toDateKey,
  uniqueById
} from './date-utils';

describe('getMonthStart', () => {
  it('returns the first day of the month at midnight', () => {
    const start = getMonthStart(new Date(2026, 6, 30, 14, 30));
    expect(start.getFullYear()).toBe(2026);
    expect(start.getMonth()).toBe(6);
    expect(start.getDate()).toBe(1);
  });
});

describe('shiftMonth', () => {
  it('moves forward across a year boundary', () => {
    const next = shiftMonth(new Date(2026, 11, 1), 1);
    expect(next.getFullYear()).toBe(2027);
    expect(next.getMonth()).toBe(0);
    expect(next.getDate()).toBe(1);
  });

  it('moves backward across a year boundary', () => {
    const prev = shiftMonth(new Date(2026, 0, 1), -1);
    expect(prev.getFullYear()).toBe(2025);
    expect(prev.getMonth()).toBe(11);
  });
});

describe('toDateKey / parseDateKey', () => {
  it('formats a date as a zero-padded yyyy-MM-dd key', () => {
    expect(toDateKey(new Date(2026, 6, 8))).toBe('2026-07-08');
  });

  it('is the inverse of parseDateKey', () => {
    const key = '2026-01-05';
    expect(toDateKey(parseDateKey(key))).toBe(key);
  });

  it('parses a key into a local date', () => {
    const date = parseDateKey('2026-07-08');
    expect(date.getFullYear()).toBe(2026);
    expect(date.getMonth()).toBe(6);
    expect(date.getDate()).toBe(8);
  });
});

describe('buildMonthCells', () => {
  it('produces exactly six weeks (42 cells)', () => {
    expect(buildMonthCells(new Date(2026, 6, 1))).toHaveLength(42);
  });

  it('starts on the Monday of the week containing the first of the month', () => {
    // 1 July 2026 is a Wednesday; the grid must start on Monday 29 June 2026.
    const cells = buildMonthCells(new Date(2026, 6, 1));
    expect(cells[0].getDay()).toBe(1); // Monday
    expect(toDateKey(cells[0])).toBe('2026-06-29');
  });

  it('keeps the first of the month as a Monday when the month starts on Monday', () => {
    // 1 June 2026 is a Monday: the grid starts on that very day.
    const cells = buildMonthCells(new Date(2026, 5, 1));
    expect(toDateKey(cells[0])).toBe('2026-06-01');
  });

  it('produces consecutive days', () => {
    const cells = buildMonthCells(new Date(2026, 6, 1));
    for (let i = 1; i < cells.length; i++) {
      const diffDays = (cells[i].getTime() - cells[i - 1].getTime()) / 86_400_000;
      expect(Math.round(diffDays)).toBe(1);
    }
  });
});

describe('pickDefaultDateKey', () => {
  it('prefers the first key belonging to the given month', () => {
    const keys = ['2026-06-30', '2026-07-08', '2026-07-09'];
    expect(pickDefaultDateKey(keys, '2026-07')).toBe('2026-07-08');
  });

  it('falls back to the first available key when the month has none', () => {
    const keys = ['2026-06-30', '2026-08-01'];
    expect(pickDefaultDateKey(keys, '2026-07')).toBe('2026-06-30');
  });
});

describe('uniqueById', () => {
  it('keeps a single entry per id and preserves the last occurrence', () => {
    const items = [
      { id: 'a', v: 1 },
      { id: 'b', v: 2 },
      { id: 'a', v: 3 }
    ];
    const result = uniqueById(items);
    expect(result).toHaveLength(2);
    expect(result.find((i) => i.id === 'a')?.v).toBe(3);
  });

  it('returns an empty array unchanged', () => {
    expect(uniqueById([])).toEqual([]);
  });
});
