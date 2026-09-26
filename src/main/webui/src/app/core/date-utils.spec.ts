import { describe, expect, it } from 'vitest';
import { buildMonthCells, parseDateKey, toDateKey, toMonthKey, uniqueById } from './date-utils';

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

describe('toMonthKey', () => {
  it('formats a date as a zero-padded yyyy-MM key', () => {
    expect(toMonthKey(new Date(2026, 6, 8))).toBe('2026-07');
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

describe('uniqueById', () => {
  it('keeps a single entry per id and preserves the last occurrence', () => {
    const items = [
      { id: 'a', v: 1 },
      { id: 'b', v: 2 },
      { id: 'a', v: 3 },
    ];
    const result = uniqueById(items);
    expect(result).toHaveLength(2);
    expect(result.find((i) => i.id === 'a')?.v).toBe(3);
  });

  it('returns an empty array unchanged', () => {
    expect(uniqueById([])).toEqual([]);
  });
});
