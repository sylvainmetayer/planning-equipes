// Query-param sync of the heatmap: the chosen view and the name search survive
// a refresh. Kept apart from heatmap-page.spec.ts, which renders the grid and
// its keyboard navigation and has no business knowing about the router.

import { Signal, WritableSignal, provideZonelessChangeDetection } from '@angular/core';
import { Location } from '@angular/common';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { PlanningStateService } from '../../core/planning-state.service';
import { HeatmapPage, HeatmapView } from './heatmap-page';

/** Reaches the protected members the template binds to / user actions call. */
type PageInternals = {
  view: Signal<HeatmapView>;
  animateurFilter: WritableSignal<string>;
  viewChanged: Signal<boolean>;
  setView(view: HeatmapView): void;
  reinitialiserVue(): void;
};

function setUp(queryParams: Record<string, string>) {
  const replaceState = vi.fn();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: ApiService, useValue: { get: vi.fn(async () => []) } },
      { provide: PlanningStateService, useValue: { loadForDisplay: vi.fn(async () => ({ animateurs: [], postes: [] })) } },
      { provide: Location, useValue: { path: () => '/heatmap', replaceState } },
      { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap(queryParams) } } }
    ]
  });
  const fixture = TestBed.createComponent(HeatmapPage);
  return { fixture, replaceState, page: fixture.componentInstance as unknown as PageInternals };
}

describe('HeatmapPage query-param sync', () => {
  beforeEach(() => {
    TestBed.resetTestingModule();
  });

  it('seeds the view and the name search from the URL, so a refresh restores them', () => {
    const { page } = setUp({ view: 'animateur', q: 'durand' });

    expect(page.view()).toBe('animateur');
    expect(page.animateurFilter()).toBe('durand');
  });

  it('opens on the stand view with no search when the URL carries none', () => {
    const { page } = setUp({});

    expect(page.view()).toBe('stand');
    expect(page.animateurFilter()).toBe('');
    expect(page.viewChanged()).toBe(false);
  });

  it('ignores a view this page does not know, instead of showing a grid under the wrong toggle', () => {
    const { page } = setUp({ view: 'par-jour' });

    expect(page.view()).toBe('stand');
  });

  it('writes the current view back to the URL (replacing, not pushing history)', async () => {
    const { fixture, replaceState } = setUp({ view: 'animateur', q: 'durand' });

    await fixture.whenStable();

    expect(replaceState).toHaveBeenCalledWith('/heatmap?view=animateur&q=durand');
  });

  it('clears both params from the URL once the view is reset', async () => {
    const { fixture, replaceState, page } = setUp({ view: 'animateur', q: 'durand' });
    await fixture.whenStable();
    expect(page.viewChanged()).toBe(true);
    replaceState.mockClear();

    page.reinitialiserVue();
    await fixture.whenStable();

    expect(page.viewChanged()).toBe(false);
    expect(replaceState).toHaveBeenLastCalledWith('/heatmap');
  });

  it('drops a search cleared down to blanks rather than trailing an empty param', async () => {
    const { fixture, replaceState, page } = setUp({ view: 'animateur' });
    await fixture.whenStable();
    replaceState.mockClear();

    page.animateurFilter.set('   ');
    await fixture.whenStable();

    expect(replaceState).toHaveBeenLastCalledWith(expect.not.stringContaining('q='));
  });
});
