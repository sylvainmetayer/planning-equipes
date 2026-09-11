// The page over the four renderings: what it reads once for all of them, how
// the day and the rendering come from the URL and go back to it, and that
// switching the rendering fetches nothing again.

import { Location } from '@angular/common';
import { provideZonelessChangeDetection, Signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AffectationExplanationService } from '../../core/affectation-explanation.service';
import { AnalysesApi } from '../../core/api/analyses-api';
import { ApiService } from '../../core/api.service';
import { Creneau, PlanningEvenement, PosteAffectation, Stand } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { PlanningStateService } from '../../core/planning-state.service';
import { SolverJobService } from '../../core/solver-job.service';
import { VerrouillageStore } from '../../core/verrouillage.store';
import { JourEvenement, JourneeView } from './journee';
import { JourneePage } from './journee-page';

function stand(id: string): Stand {
  return {
    id,
    nom: id,
    typologiesProposees: [],
    effectifMin: 1,
    effectifMax: 1,
    reserveMajeurs: false,
    premium: false,
    niveauEffort: 'NORMAL',
    emplacement: null,
    indisponibilites: [],
    ouvertures: [],
    horaires: [],
  };
}

function creneau(overrides: Partial<Creneau> & { id: number }): Creneau {
  return { jour: 1, date: '2026-08-01', heureDebut: '10:00', heureFin: '12:00', ...overrides };
}

const ALICE = {
  id: 'alice',
  prenom: 'Alice',
  nom: 'Martin',
  dateNaissance: '1990-01-01',
  manager: false,
  competences: {},
  souhaits: [],
  joursIndisponibles: [],
};

function planningDeuxJours(): PlanningEvenement {
  const postes: PosteAffectation[] = [
    { id: 'p1', stand: stand('Tir'), creneau: creneau({ id: 1 }), animateur: ALICE },
    {
      id: 'p2',
      stand: stand('Dixit'),
      creneau: creneau({ id: 2, jour: 2, date: '2026-08-02' }),
      animateur: ALICE,
    },
  ];
  return { animateurs: [ALICE], postes, score: null };
}

type PageInternals = {
  view: Signal<JourneeView>;
  jourCourant: Signal<JourEvenement | null>;
  jours: Signal<JourEvenement[]>;
  stands: Signal<{ id: string; label: string }[]>;
  animateurs: Signal<{ id: string; label: string }[]>;
  error: Signal<string>;
  viewChanged: Signal<boolean>;
  changeView: (vue: JourneeView) => void;
  selectJour: (key: string) => void;
  decalerJour: (delta: number) => void;
  resetView: () => void;
  recharger: () => Promise<void>;
};

describe('JourneePage', () => {
  const loadForDisplay = vi.fn();
  const set = vi.fn();
  const analysesApi = {
    typologies: vi.fn(async () => []),
    breaks: vi.fn(async () => null),
    emplacements: vi.fn(async () => []),
  };
  let fixture: ComponentFixture<JourneePage>;

  beforeEach(() => {
    loadForDisplay.mockReset();
    set.mockReset();
    analysesApi.typologies.mockClear();
    analysesApi.breaks.mockClear();
    analysesApi.emplacements.mockClear();
    loadForDisplay.mockResolvedValue(planningDeuxJours());
  });

  async function monter(queryParams: Record<string, string> = {}): Promise<PageInternals> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: PlanningStateService, useValue: { loadForDisplay, set } },
        { provide: AnalysesApi, useValue: analysesApi },
        { provide: ApiService, useValue: { get: vi.fn(async () => ({ assignments: 2 })) } },
        { provide: SolverJobService, useValue: { editingLocked: () => false } },
        { provide: NotificationService, useValue: { notify: vi.fn() } },
        { provide: AffectationExplanationService, useValue: { deplacer: vi.fn() } },
        {
          provide: VerrouillageStore,
          useValue: {
            reload: vi.fn(async () => undefined),
            estJourVerrouille: () => false,
            estStandVerrouille: () => false,
            estCreneauVerrouille: () => false,
          },
        },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap(queryParams) } },
        },
      ],
    });
    fixture = TestBed.createComponent(JourneePage);
    await fixture.whenStable();
    return fixture.componentInstance as unknown as PageInternals;
  }

  function racine(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  it('reads the plan, the breaks and the referentials once, and opens on the calendar of the first day', async () => {
    const page = await monter();

    expect(loadForDisplay).toHaveBeenCalledOnce();
    expect(analysesApi.breaks).toHaveBeenCalledOnce();
    expect(page.view()).toBe('calendrier');
    expect(page.jourCourant()?.key).toBe('2026-08-01');
    expect(racine().querySelector('app-calendar-day-vue')).not.toBeNull();
    expect(page.stands().map((each) => each.id)).toEqual(['Dixit', 'Tir']);
    expect(page.animateurs()[0].label).toBe('Alice Martin');
  });

  it('switches the rendering without reading anything again', async () => {
    const page = await monter();

    page.changeView('rail');
    await fixture.whenStable();
    expect(racine().querySelector('app-rail-jour-vue')).not.toBeNull();
    expect(racine().querySelector('app-calendar-day-vue')).toBeNull();

    page.changeView('pauses');
    TestBed.tick();
    await fixture.whenStable();
    expect(racine().querySelector('app-pauses-vue')).not.toBeNull();

    expect(loadForDisplay).toHaveBeenCalledOnce();
    expect(analysesApi.breaks).toHaveBeenCalledOnce();
    expect(TestBed.inject(Location).path()).toContain('vue=pauses');
  });

  it('opens on the rendering and the day the URL names, by date or by the older day number', async () => {
    const byDate = await monter({ vue: 'rail', date: '2026-08-02' });
    expect(byDate.view()).toBe('rail');
    expect(byDate.jourCourant()?.jour).toBe(2);

    const byNumero = await monter({ jour: '2' });
    expect(byNumero.jourCourant()?.jour).toBe(2);
    // Known by its date now: the day is written back under `date`, and `jour` retired.
    expect(TestBed.inject(Location).path()).toContain('date=2026-08-02');
    expect(TestBed.inject(Location).path()).not.toContain('jour=');
  });

  it('steps from one day to the next and lands the day in the URL, nothing on the first day', async () => {
    const page = await monter();
    expect(TestBed.inject(Location).path()).not.toContain('date=');

    page.decalerJour(1);
    TestBed.tick();
    await fixture.whenStable();
    expect(page.jourCourant()?.jour).toBe(2);
    expect(TestBed.inject(Location).path()).toContain('date=2026-08-02');

    page.decalerJour(1);
    await fixture.whenStable();
    expect(page.jourCourant()?.jour).toBe(2);
  });

  it('carries the shared filters in the URL and clears them all in one action', async () => {
    const page = await monter({ stand: 'Tir', q: 'ali', lignes: 'libres' });
    expect(page.viewChanged()).toBe(true);

    page.resetView();
    TestBed.tick();
    await fixture.whenStable();

    expect(page.viewChanged()).toBe(false);
    const url = TestBed.inject(Location).path();
    expect(url).not.toContain('stand=');
    expect(url).not.toContain('q=');
  });

  it('drops the session copy of the plan and re-reads when a rendering wrote to it', async () => {
    const page = await monter();

    await page.recharger();

    expect(set).toHaveBeenCalledWith(null);
    expect(loadForDisplay).toHaveBeenCalledTimes(2);
  });

  it('shows the failure instead of an empty day when the plan cannot be read', async () => {
    loadForDisplay.mockRejectedValue(new Error('planning illisible'));

    const page = await monter();

    expect(page.error()).toContain('planning illisible');
    expect(racine().querySelector('app-calendar-day-vue')).toBeNull();
  });
});
