// Query-param sync of the hours table: the column sorted on, and its
// direction, survive a refresh. Kept apart from hours-page.spec.ts, which pins
// the loading/error/empty states of the report itself.

import { Signal, WritableSignal, provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Sort } from '@angular/material/sort';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { HeuresAnimateur, HeuresRapport, PlanningEvenement } from '../../core/models';
import { PlanningStateService } from '../../core/planning-state.service';
import { HoursPage } from './hours-page';

const PLANNING = { postes: [] } as unknown as PlanningEvenement;

const RAPPORT: HeuresRapport = {
  semaines: ['2026-W33'],
  animateurs: [
    { animateurId: 'bob', nom: 'Bob', heuresParSemaine: { '2026-W33': 30 }, total: 30 },
    { animateurId: 'alice', nom: 'Alice', heuresParSemaine: { '2026-W33': 12 }, total: 12 }
  ]
} as HeuresRapport;

type PageInternals = {
  sort: WritableSignal<Sort>;
  vueModifiee: Signal<boolean>;
  sortedAnimateurs: Signal<HeuresAnimateur[]>;
  reinitialiserVue(): void;
};

function setUp(queryParams: Record<string, string>) {
  const navigate = vi.fn(async () => true);
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: ApiService, useValue: { post: vi.fn(async () => RAPPORT), downloadPost: vi.fn() } },
      { provide: PlanningStateService, useValue: { require: vi.fn(async () => PLANNING) } },
      { provide: Router, useValue: { navigate } },
      { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap(queryParams) } } }
    ]
  });
  const fixture = TestBed.createComponent(HoursPage);
  return { fixture, navigate, page: fixture.componentInstance as unknown as PageInternals };
}

describe('HoursPage query-param sync', () => {
  beforeEach(() => {
    TestBed.resetTestingModule();
  });

  it('seeds the sort from the URL and applies it to the rows', async () => {
    const { fixture, page } = setUp({ sort: 'total', dir: 'desc' });
    await fixture.whenStable();

    expect(page.sort()).toEqual({ active: 'total', direction: 'desc' });
    expect(page.sortedAnimateurs().map((row) => row.nom)).toEqual(['Bob', 'Alice']);
  });

  it('leaves the report in its own order when the URL carries no sort', async () => {
    const { fixture, page } = setUp({});
    await fixture.whenStable();

    expect(page.sort()).toEqual({ active: '', direction: '' });
    expect(page.vueModifiee()).toBe(false);
    expect(page.sortedAnimateurs().map((row) => row.nom)).toEqual(['Bob', 'Alice']);
  });

  it('ignores a malformed direction instead of failing the page', async () => {
    const { fixture, page } = setUp({ sort: 'total', dir: 'sideways' });
    await fixture.whenStable();

    expect(page.sort()).toEqual({ active: '', direction: '' });
  });

  it('ignores a week column that no longer exists, and shows the report unsorted', async () => {
    // Exactly what a link bookmarked on last year's edition carries.
    const { fixture, page } = setUp({ sort: '2019-W01', dir: 'asc' });
    await fixture.whenStable();

    expect(page.sortedAnimateurs().map((row) => row.nom)).toEqual(['Bob', 'Alice']);
  });

  it('writes the sort back to the URL (replacing, not pushing history)', async () => {
    const { fixture, navigate } = setUp({ sort: 'total', dir: 'desc' });
    await fixture.whenStable();

    expect(navigate).toHaveBeenCalledWith(
      [],
      expect.objectContaining({ queryParams: { sort: 'total', dir: 'desc' }, replaceUrl: true })
    );
  });

  it('clears both params once the sort is reset', async () => {
    const { fixture, navigate, page } = setUp({ sort: 'total', dir: 'desc' });
    await fixture.whenStable();
    expect(page.vueModifiee()).toBe(true);
    navigate.mockClear();

    page.reinitialiserVue();
    await fixture.whenStable();

    expect(navigate).toHaveBeenLastCalledWith([], expect.objectContaining({ queryParams: { sort: null, dir: null } }));
  });
});
