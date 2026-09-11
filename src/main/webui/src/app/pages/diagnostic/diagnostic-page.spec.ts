// The page over the four analyses: which tab the URL names, that switching
// writes the tab back, and that a tab's own view state lives next to it.

import { Location } from '@angular/common';
import { provideZonelessChangeDetection, Signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AnalysesApi } from '../../core/api/analyses-api';
import { ApiService } from '../../core/api.service';
import { ConstraintsApi } from '../../core/api/constraints-api';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { OngletDiagnostic } from './diagnostic';
import { DiagnosticPage } from './diagnostic-page';

type PageInternals = {
  onglet: Signal<OngletDiagnostic>;
  changerOnglet: (onglet: OngletDiagnostic) => void;
};

describe('DiagnosticPage', () => {
  let fixture: ComponentFixture<DiagnosticPage>;
  // Each analysis answers nothing: what is pinned here is which tab is on
  // screen, never what an analysis draws — their own specs cover that.
  const analysesApi = {
    staffing: vi.fn(async () => null),
    fragility: vi.fn(async () => null),
    bench: vi.fn(async () => null),
    breaks: vi.fn(async () => null),
  };

  beforeEach(() => {
    for (const stub of Object.values(analysesApi)) {
      stub.mockClear();
    }
  });

  async function monter(queryParams: Record<string, string> = {}): Promise<PageInternals> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ApiService, useValue: { get: vi.fn(async () => null) } },
        { provide: AnalysesApi, useValue: analysesApi },
        { provide: ConstraintsApi, useValue: { catalogue: vi.fn(async () => null) } },
        { provide: SolverJobService, useValue: { onResult: () => () => undefined } },
        {
          provide: ReferenceDataStore,
          useValue: {
            stands: () => [],
            animateurs: () => [],
            reload: vi.fn(async () => undefined),
          },
        },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap(queryParams) } },
        },
      ],
    });
    fixture = TestBed.createComponent(DiagnosticPage);
    await fixture.whenStable();
    return fixture.componentInstance as unknown as PageInternals;
  }

  function racine(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  it('opens on the problems, without a second heading under its own', async () => {
    const page = await monter();

    expect(page.onglet()).toBe('problemes');
    expect(racine().querySelector('app-problemes-page')).not.toBeNull();
    expect(racine().querySelectorAll('h1')).toHaveLength(1);
    expect(analysesApi.staffing).not.toHaveBeenCalled();
  });

  it("opens on the tab the URL names, and keeps that tab's own state next to it", async () => {
    const page = await monter({ onglet: 'banc', stand: 'tir' });

    expect(page.onglet()).toBe('banc');
    expect(racine().querySelector('app-banc-de-touche-page')).not.toBeNull();
    // The bench wrote its own key, the page its own: neither erased the other.
    const url = TestBed.inject(Location).path();
    expect(url).toContain('onglet=banc');
    expect(url).toContain('stand=tir');
  });

  it('switches the tab and writes it back, nothing for the default one', async () => {
    const page = await monter();

    page.changerOnglet('besoin');
    TestBed.tick();
    await fixture.whenStable();
    expect(racine().querySelector('app-staffing-page')).not.toBeNull();
    expect(TestBed.inject(Location).path()).toContain('onglet=besoin');

    page.changerOnglet('problemes');
    TestBed.tick();
    await fixture.whenStable();
    expect(TestBed.inject(Location).path()).not.toContain('onglet=');
  });
});
