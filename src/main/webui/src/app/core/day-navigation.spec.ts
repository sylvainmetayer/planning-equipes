// Pure signals, no TestBed: what the three dated views used to each pin on
// their own — the fallback to the first day, the ends, and the URL param.

import { signal } from '@angular/core';
import { describe, expect, it, vi } from 'vitest';
import { dayNavigation, dayNumberParam } from './day-navigation';

interface Day {
  jour: number;
}

const three = (): Day[] => [{ jour: 1 }, { jour: 2 }, { jour: 3 }];

describe('dayNavigation', () => {
  it('shows the selected day when the list holds it, and the first one otherwise', () => {
    const days = signal<Day[]>(three());
    const navigation = dayNavigation(days, (day) => day.jour, { initial: 2 });
    expect(navigation.current()?.jour).toBe(2);

    // A bookmark naming a day the plan no longer holds: the first, not a blank page.
    navigation.select(9);
    expect(navigation.current()?.jour).toBe(1);

    days.set([]);
    expect(navigation.current()).toBeNull();
  });

  it('steps through the days and stops at both ends', () => {
    const navigation = dayNavigation(signal<Day[]>(three()), (day) => day.jour);
    expect(navigation.isFirst()).toBe(true);
    expect(navigation.isLast()).toBe(false);

    navigation.step(-1);
    expect(navigation.current()?.jour).toBe(1);

    navigation.step(1);
    navigation.step(1);
    expect(navigation.current()?.jour).toBe(3);
    expect(navigation.isLast()).toBe(true);

    navigation.step(1);
    expect(navigation.current()?.jour).toBe(3);
  });

  it('carries the day in the URL only when it is not the default first one', () => {
    const navigation = dayNavigation(signal<Day[]>(three()), (day) => day.jour);
    expect(navigation.queryParam()).toBeNull();

    navigation.select(3);
    expect(navigation.queryParam()).toBe('3');

    navigation.select(1);
    expect(navigation.queryParam()).toBeNull();
  });

  it('tells the view about every selection, stepped or picked, so it can reset what belongs to a day', () => {
    const onSelect = vi.fn();
    const navigation = dayNavigation(signal<Day[]>(three()), (day) => day.jour, { onSelect });

    navigation.select(2);
    navigation.step(1);
    navigation.step(1);

    expect(onSelect.mock.calls.map(([key]) => key)).toEqual([2, 3]);
  });

  it('keys days by a date as well as by a number', () => {
    const days = signal([{ date: '2026-07-08' }, { date: '2026-07-09' }]);
    const navigation = dayNavigation(days, (day) => day.date, { initial: '2026-07-09' });

    expect(navigation.current()?.date).toBe('2026-07-09');
    expect(navigation.queryParam()).toBe('2026-07-09');
  });

  it('reads a day number from the URL, and nothing from anything else', () => {
    expect(dayNumberParam('3')).toBe(3);
    expect(dayNumberParam(null)).toBeNull();
    expect(dayNumberParam('')).toBeNull();
    expect(dayNumberParam('0')).toBeNull();
    expect(dayNumberParam('mardi')).toBeNull();
  });
});
