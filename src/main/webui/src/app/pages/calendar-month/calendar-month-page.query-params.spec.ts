// Query-param sync (issue: keep month/day/filters across an F5 refresh),
// kept separate from calendar-month-page.spec.ts since it needs TestBed
// (ActivatedRoute/Router), unlike that file's pure buildAssignmentsByDate
// tests.

import { Signal, provideZonelessChangeDetection } from '@angular/core';
import { Location } from '@angular/common';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { PlanningStateService } from '../../core/planning-state.service';
import { CalendarMonthPage } from './calendar-month-page';

/** Reaches the protected members the template binds to / user actions call. */
type PageInternals = {
  month: Signal<Date>;
  selectedDateKey: Signal<string | null>;
  animateurFilter: Signal<string>;
  standFilter: Signal<string>;
  resetFilters(): void;
  selectAnimateur(value: string): void;
};

function setUp(queryParams: Record<string, string>) {
  const replaceState = vi.fn();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: PlanningStateService, useValue: { loadForDisplay: vi.fn(async () => ({ animateurs: [], postes: [] })) } },
      { provide: Location, useValue: { path: () => '/calendar', replaceState } },
      { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap(queryParams) } } }
    ]
  });
  const fixture = TestBed.createComponent(CalendarMonthPage);
  return { fixture, replaceState, page: fixture.componentInstance as unknown as PageInternals };
}

describe('CalendarMonthPage query-param sync', () => {
  beforeEach(() => {
    TestBed.resetTestingModule();
  });

  it('seeds month/day/filters from the URL on construction, so a refresh restores the view', () => {
    const { page } = setUp({ month: '2026-07', date: '2026-07-10', animateur: 'A1', stand: 'S1' });

    expect(page.month().getFullYear()).toBe(2026);
    expect(page.month().getMonth()).toBe(6); // July, 0-indexed
    expect(page.selectedDateKey()).toBe('2026-07-10');
    expect(page.animateurFilter()).toBe('A1');
    expect(page.standFilter()).toBe('S1');
  });

  it('falls back to today/no filters when the URL carries none', () => {
    const { page } = setUp({});

    expect(page.selectedDateKey()).toBeNull();
    expect(page.animateurFilter()).toBe('ALL');
    expect(page.standFilter()).toBe('ALL');
  });

  it('ignores a malformed month param instead of producing an invalid date', () => {
    const { page } = setUp({ month: 'not-a-month' });

    const now = new Date();
    expect(page.month().getFullYear()).toBe(now.getFullYear());
    expect(page.month().getMonth()).toBe(now.getMonth());
  });

  it('writes the current state back to the URL (replacing, not pushing history)', async () => {
    const { fixture, replaceState } = setUp({ month: '2026-07', date: '2026-07-10', animateur: 'A1', stand: 'S1' });

    fixture.detectChanges();
    await fixture.whenStable();

    expect(replaceState).toHaveBeenCalledWith('/calendar?month=2026-07&date=2026-07-10&animateur=A1&stand=S1');
  });

  it('clears animateur/stand from the URL (null, not "ALL") once filters are reset', async () => {
    const { fixture, replaceState, page } = setUp({ animateur: 'A1', stand: 'S1' });
    fixture.detectChanges();
    await fixture.whenStable();
    replaceState.mockClear();

    page.resetFilters();
    fixture.detectChanges();
    await fixture.whenStable();

    expect(replaceState).toHaveBeenLastCalledWith(expect.not.stringContaining('animateur='));
    expect(replaceState).toHaveBeenLastCalledWith(expect.not.stringContaining('stand='));
  });
});
