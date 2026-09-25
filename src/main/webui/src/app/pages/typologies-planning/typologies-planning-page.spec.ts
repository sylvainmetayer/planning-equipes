// « Planning par typologie » (issue #590): the screen around the pure builders
// — what it loads, what its filters hide, and where a name leads.

import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { PlanningApi } from '../../core/api/planning-api';
import { LigneTypologie } from '../../core/models';
import { OngletTypologies, TypologiesPlanningPage } from './typologies-planning-page';
import { CapFilter } from './typologies-planning';

function ligne(overrides: Partial<LigneTypologie> = {}): LigneTypologie {
  return {
    typologie: 'STRATEGIE',
    label: 'Stratégie',
    ninja: false,
    maxCreneauxParAnimateur: null,
    description: null,
    animateursAffectes: [{ animateurId: 'ada', nom: 'Ada Martin' }],
    animateursCompetents: [{ animateurId: 'ada', nom: 'Ada Martin' }],
    competentsJamaisAffectes: [],
    affectesSansCompetence: [],
    heures: 8,
    postes: 2,
    heuresParJour: { '2026-07-06': 8 },
    ...overrides,
  };
}

type PageInternals = {
  lignes: () => LigneTypologie[];
  masquees: () => number;
  aucuneAffectation: () => boolean;
  output: () => string;
  onglet: { set: (onglet: OngletTypologies) => void };
  search: { set: (texte: string) => void };
  cap: { set: (cap: CapFilter) => void };
  tensionOnly: { set: (only: boolean) => void };
  setCapMinimum: (valeur: string | number | null) => void;
  capMinimum: () => number | null;
  resetFilters: () => void;
  filtresActifs: () => boolean;
  load: () => Promise<void>;
};

function mount(): {
  fixture: ComponentFixture<TypologiesPlanningPage>;
  page: TypologiesPlanningPage & PageInternals;
} {
  const fixture = TestBed.createComponent(TypologiesPlanningPage);
  return {
    fixture,
    page: fixture.componentInstance as TypologiesPlanningPage & PageInternals,
  };
}

describe('TypologiesPlanningPage', () => {
  const planningApi = { typologiesReport: vi.fn() };

  beforeEach(() => {
    planningApi.typologiesReport.mockReset();
    planningApi.typologiesReport.mockResolvedValue({
      typologies: [
        ligne(),
        ligne({
          typologie: 'AMBIANCE',
          label: 'Ambiance',
          description: 'Une dizaine de jeux à connaître',
          maxCreneauxParAnimateur: 4,
          postes: 0,
          heures: 0,
          animateursAffectes: [],
          competentsJamaisAffectes: [{ animateurId: 'bob', nom: 'Bob Martin' }],
          heuresParJour: {},
        }),
      ],
      jours: ['2026-07-06'],
    });
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: PlanningApi, useValue: planningApi },
      ],
    });
  });

  // The card it replaces loaded on demand, because it sat on a referential page
  // nobody opened for the plan. This screen has no other subject.
  it('reads the plan as soon as it is opened', async () => {
    const { page } = mount();
    await page.load();

    expect(planningApi.typologiesReport).toHaveBeenCalled();
    expect(page.lignes()).toHaveLength(2);
  });

  it('says how many rows the filters hide, so a short table is not read as an empty plan', async () => {
    const { page } = mount();
    await page.load();

    page.search.set('ambiance');

    expect(page.lignes().map((found) => found.typologie)).toEqual(['AMBIANCE']);
    expect(page.masquees()).toBe(1);
    expect(page.filtresActifs()).toBe(true);
  });

  it('puts every filter back, and takes an empty minimum for « no minimum »', async () => {
    const { page } = mount();
    await page.load();

    page.setCapMinimum('3');
    expect(page.capMinimum()).toBe(3);
    page.setCapMinimum('');
    expect(page.capMinimum()).toBeNull();
    page.setCapMinimum(0);
    expect(page.capMinimum()).toBeNull();

    page.search.set('x');
    page.cap.set('capped');
    page.tensionOnly.set(true);
    page.resetFilters();

    expect(page.filtresActifs()).toBe(false);
    expect(page.lignes()).toHaveLength(2);
  });

  /**
   * The point of the rework: a nominative list one cannot act on is a dead end.
   * Every name is a link to that person's timeline, on every rendering.
   */
  it('links every animateur to their own timeline', async () => {
    const { fixture, page } = mount();
    await page.load();
    await fixture.whenStable();

    const liens = () =>
      [...fixture.nativeElement.querySelectorAll('a.typologies-lien')].map((lien) =>
        (lien as HTMLAnchorElement).getAttribute('href'),
      );

    expect(liens()).toContain('/timeline?animateur=ada');
    expect(liens()).toContain('/timeline?animateur=bob');

    page.onglet.set('cartes');
    await fixture.whenStable();
    expect(liens()).toContain('/timeline?animateur=ada');
  });

  it('draws each of the four renderings without failing', async () => {
    const { fixture, page } = mount();
    await page.load();

    for (const onglet of ['table', 'barres', 'heatmap', 'cartes'] as const) {
      page.onglet.set(onglet);
      await fixture.whenStable();
      expect(fixture.nativeElement.textContent).toContain('Stratégie');
    }
  });

  it('reports a failed read instead of leaving the screen silent', async () => {
    planningApi.typologiesReport.mockRejectedValue(new Error('boom'));
    const { page } = mount();

    await page.load();

    expect(page.output()).not.toBe('');
    expect(page.lignes()).toEqual([]);
  });

  /** An edition whose plan holds nothing is a legitimate state, said in words. */
  it('says when nothing is assigned at all', async () => {
    planningApi.typologiesReport.mockResolvedValue({
      typologies: [ligne({ postes: 0, heures: 0, animateursAffectes: [], heuresParJour: {} })],
      jours: [],
    });
    const { page } = mount();

    await page.load();

    expect(page.aucuneAffectation()).toBe(true);
  });
});
