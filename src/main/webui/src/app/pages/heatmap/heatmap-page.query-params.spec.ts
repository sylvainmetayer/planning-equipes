// Query-param sync of the heatmap: the chosen view and the name search survive
// a refresh. Kept apart from heatmap-page.spec.ts, which renders the grid and
// its keyboard navigation and has no business knowing about the router.

import { Signal, WritableSignal, provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { PlanningStateService } from '../../core/planning-state.service';
import { HeatmapPage, HeatmapView } from './heatmap-page';

/** Reaches the protected members the template binds to / user actions call. */
type PageInternals = {
  view: Signal<HeatmapView>;
  animateurFilter: WritableSignal<string>;
  vueModifiee: Signal<boolean>;
  setView(view: HeatmapView): void;
  reinitialiserVue(): void;
};

function setUp(queryParams: Record<string, string>) {
  const navigate = vi.fn(async () => true);
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: ApiService, useValue: { get: vi.fn(async () => []) } },
      { provide: PlanningStateService, useValue: { loadForDisplay: vi.fn(async () => ({ animateurs: [], postes: [] })) } },
      { provide: Router, useValue: { navigate } },
      { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap(queryParams) } } }
    ]
  });
  const fixture = TestBed.createComponent(HeatmapPage);
  return { fixture, navigate, page: fixture.componentInstance as unknown as PageInternals };
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
    expect(page.vueModifiee()).toBe(false);
  });

  it('ignores a view this page does not know, instead of showing a grid under the wrong toggle', () => {
    const { page } = setUp({ view: 'par-jour' });

    expect(page.view()).toBe('stand');
  });

  it('writes the current view back to the URL (replacing, not pushing history)', async () => {
    const { fixture, navigate } = setUp({ view: 'animateur', q: 'durand' });

    await fixture.whenStable();

    expect(navigate).toHaveBeenCalledWith(
      [],
      expect.objectContaining({ queryParams: { view: 'animateur', q: 'durand' }, replaceUrl: true })
    );
  });

  it('clears both params from the URL once the view is reset', async () => {
    const { fixture, navigate, page } = setUp({ view: 'animateur', q: 'durand' });
    await fixture.whenStable();
    expect(page.vueModifiee()).toBe(true);
    navigate.mockClear();

    page.reinitialiserVue();
    await fixture.whenStable();

    expect(page.vueModifiee()).toBe(false);
    expect(navigate).toHaveBeenLastCalledWith([], expect.objectContaining({ queryParams: { view: null, q: null } }));
  });

  it('drops a search cleared down to blanks rather than trailing an empty param', async () => {
    const { fixture, navigate, page } = setUp({ view: 'animateur' });
    await fixture.whenStable();
    navigate.mockClear();

    page.animateurFilter.set('   ');
    await fixture.whenStable();

    expect(navigate).toHaveBeenLastCalledWith(
      [],
      expect.objectContaining({ queryParams: expect.objectContaining({ q: null }) })
    );
  });
});
