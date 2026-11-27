// The page over the analyses: which tab the URL names, that switching
// writes the tab back, and that a tab's own view state lives next to it.

import { Location } from '@angular/common';
import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideLocationMocks } from '@angular/common/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AnalysesApi } from '../../core/api/analyses-api';
import { ApiService } from '../../core/api.service';
import { ConstraintsApi } from '../../core/api/constraints-api';
import { RhApi } from '../../core/api/rh-api';
import { EditionStore } from '../../core/edition.store';
import { SessionStore } from '../../core/session.store';
import { PlanningApi } from '../../core/api/planning-api';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { VerrouillageStore } from '../../core/verrouillage.store';
import { DiagnosticPage } from './diagnostic-page';

describe('DiagnosticPage', () => {
  let fixture: ComponentFixture<DiagnosticPage>;
  // Each analysis answers nothing: what is pinned here is which tab is on
  // screen, never what an analysis draws — their own specs cover that.
  const planningApi = {
    persistedCount: vi.fn(() => Promise.resolve(null)),
    typologiesReport: vi.fn(() => Promise.resolve({ typologies: [], jours: [] })),
    equityReport: vi.fn(() =>
      Promise.resolve({
        heureDebutSoiree: '20:00:00',
        semaines: [],
        lignes: [],
        syntheses: {},
        colonnesSolveur: [],
      }),
    ),
    hoursReport: vi.fn(() => Promise.resolve(null)),
  };
  const analysesApi = {
    staffing: vi.fn(() => Promise.resolve(null)),
    fragility: vi.fn(() => Promise.resolve(null)),
    breaks: vi.fn(() => Promise.resolve(null)),
    walks: vi.fn(() => Promise.resolve(null)),
    intendance: vi.fn(() => Promise.resolve(null)),
    groupedArrivals: vi.fn(() => Promise.resolve(null)),
    trainingPlan: vi.fn(() => Promise.resolve(null)),
    margin: vi.fn(() => Promise.resolve(null)),
    tension: vi.fn(() => Promise.resolve(null)),
  };

  beforeEach(() => {
    for (const stub of Object.values(analysesApi)) {
      stub.mockClear();
    }
  });

  /** Who is signed in, and which editions their RH rights name; nobody by default. */
  interface Qui {
    roles: string[];
    rhEditions?: string[];
  }

  async function monter(
    queryParams: Record<string, string> = {},
    qui: Qui | null = null,
  ): Promise<DiagnosticPage> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        provideLocationMocks(),
        { provide: ApiService, useValue: { get: vi.fn(() => Promise.resolve(null)) } },
        { provide: AnalysesApi, useValue: analysesApi },
        {
          provide: RhApi,
          useValue: {
            editions: vi.fn(() =>
              Promise.resolve((qui?.rhEditions ?? []).map((id) => ({ id, nom: id }))),
            ),
            rapport: vi.fn(() => Promise.resolve(null)),
          },
        },
        { provide: ConstraintsApi, useValue: { catalogue: vi.fn(() => Promise.resolve(null)) } },
        { provide: PlanningApi, useValue: planningApi },
        {
          provide: SolverJobService,
          useValue: {
            onResult: () => () => undefined,
            activeJob: () => null,
            editingLocked: () => false,
          },
        },
        {
          provide: ReferenceDataStore,
          useValue: {
            stands: () => [],
            animateurs: () => [],
            creneaux: () => [],
            typologies: () => [],
            scale: () => ({
              animateurCount: 0,
              posteCount: 0,
              contrainteAdHocCount: 0,
              hoursToFill: 0,
              hoursAvailable: 0,
            }),
            reload: vi.fn(() => Promise.resolve(undefined)),
          },
        },
        {
          provide: VerrouillageStore,
          useValue: {
            verrouillages: () => [],
            isAnimateurCreneauVerrouille: () => false,
            reload: vi.fn(() => Promise.resolve(undefined)),
          },
        },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { queryParamMap: convertToParamMap(queryParams) },
            // Followed, not read once: a link inside the page navigates to this
            // same route with another tab, and the component is reused.
            queryParamMap: of(convertToParamMap(queryParams)),
          },
        },
      ],
    });
    // The address bar as a real navigation would have left it: the tabs restore
    // their own state from there, the router snapshot being frozen.
    const query = new URLSearchParams(queryParams).toString();
    TestBed.inject(Location).replaceState(query ? `/diagnostic?${query}` : '/diagnostic');
    if (qui) {
      TestBed.inject(SessionStore)['_statut'].set({
        authentifie: true,
        nom: null,
        roles: qui.roles,
        compteId: 'C1',
      });
      TestBed.inject(EditionStore)['_courant'].set({
        id: 'E1',
        nom: 'Édition 1',
        active: true,
        creeLe: null,
      });
    }
    fixture = TestBed.createComponent(DiagnosticPage);
    await fixture.whenStable();
    return fixture.componentInstance;
  }

  function racine(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  it('opens on the problems, without a second heading under its own', async () => {
    const page = await monter();

    expect(page['onglet']()).toBe('problemes');
    expect(racine().querySelector('app-problemes-page')).not.toBeNull();
    expect(racine().querySelectorAll('h1')).toHaveLength(1);
    expect(analysesApi.staffing).not.toHaveBeenCalled();
  });

  it('opens Risques from ?onglet=risques: fragility, « À former » and tension, under the page heading alone', async () => {
    const page = await monter({ onglet: 'risques' });

    expect(page['onglet']()).toBe('risques');
    const ids = [...racine().querySelectorAll('app-risques-tab > section')].map((s) => s.id);
    expect(ids).toEqual(['risques-fragilite', 'risques-former', 'risques-tension']);
    expect(racine().querySelector('#risques-fragilite app-fragilite-page')).not.toBeNull();
    expect(racine().querySelector('#risques-former app-formation-page')).not.toBeNull();
    expect(racine().querySelector('#risques-tension app-tension-tab')).not.toBeNull();
    expect(racine().querySelectorAll('h1')).toHaveLength(1);
    expect(analysesApi.fragility).toHaveBeenCalled();
    expect(analysesApi.trainingPlan).toHaveBeenCalled();
    expect(analysesApi.tension).toHaveBeenCalled();
  });

  // « À former » left Besoin for Risques: the « avant » margin stays a column of Besoin.
  it('shows the « avant » margin, the volumetry and the plan by typologie on the Besoin tab, no « À former »', async () => {
    await monter({ onglet: 'besoin' });

    expect(racine().querySelector('app-staffing-page app-formation-page')).toBeNull();
    // The volumetry and the plan by typologie close the tab.
    expect(
      racine().querySelector('app-staffing-page #besoin-volumetrie app-solver-volumetry'),
    ).not.toBeNull();
    expect(racine().querySelector('app-staffing-page app-besoin-typologie')).not.toBeNull();
    expect(planningApi.typologiesReport).toHaveBeenCalled();
    expect(analysesApi.margin).toHaveBeenCalledWith('AVANT');
  });

  it('opens Charge from ?onglet=charge: the synthesis of the people, under the page heading alone', async () => {
    const page = await monter({ onglet: 'charge' });

    expect(page['onglet']()).toBe('charge');
    expect(
      racine().querySelector('app-charge-tab app-planning-personne-vue .planning-axe-barre'),
    ).not.toBeNull();
    expect(racine().querySelectorAll('h1')).toHaveLength(1);
    expect(planningApi.equityReport).toHaveBeenCalled();
  });

  it('opens Repas et pauses from ?onglet=repas-pauses: breaks, meals and walks, under the page heading alone', async () => {
    const page = await monter({ onglet: 'repas-pauses' });

    expect(page['onglet']()).toBe('repas-pauses');
    expect(racine().querySelector('app-repas-pauses-tab app-pauses-vue')).not.toBeNull();
    expect(racine().querySelector('app-repas-pauses-tab app-intendance-jour')).not.toBeNull();
    expect(racine().querySelectorAll('h1')).toHaveLength(1);
    expect(analysesApi.breaks).toHaveBeenCalled();
    expect(analysesApi.walks).toHaveBeenCalled();
    expect(
      racine().querySelector('a[href="/regles?regle=coupureRepasObligatoire"]'),
    ).not.toBeNull();
    expect(racine().querySelector('a[href="/parametres?onglet=organisation"]')).not.toBeNull();
  });

  // Charge and the fragility filter each wrote `q`: the one typed on a tab was read, and written
  // back, by the other as soon as the reader moved between them.
  it('keeps the Charge name filter and the Risques one apart when changing tab', async () => {
    const page = await monter({ onglet: 'charge', nom: 'Ali' });

    page['changerOnglet']('risques');
    TestBed.tick();
    await fixture.whenStable();

    const url = TestBed.inject(Location).path();
    expect(url).toContain('nom=Ali');
    expect(url).not.toContain('q=');
    expect(
      racine().querySelector<HTMLInputElement>(
        'app-fragilite-page input[type="search"], app-fragilite-page app-table-filter input',
      )?.value ?? '',
    ).toBe('');
  });

  it('offers five tabs, and none of the former Fragilité, Tension, « À former » or bench', async () => {
    await monter();

    const onglets = [...racine().querySelectorAll('mat-button-toggle')].map((toggle) =>
      toggle.getAttribute('value'),
    );
    expect(onglets).toEqual(['problemes', 'besoin', 'risques', 'charge', 'repas-pauses']);
  });

  it("opens on the tab the URL names, and keeps that tab's own state next to it", async () => {
    const page = await monter({ onglet: 'risques', q: 'Alice' });

    expect(page['onglet']()).toBe('risques');
    expect(racine().querySelector('app-risques-tab app-fragilite-page')).not.toBeNull();
    // The tab wrote its own key, the page its own: neither erased the other.
    const url = TestBed.inject(Location).path();
    expect(url).toContain('onglet=risques');
    expect(url).toContain('q=Alice');
  });

  // A tab is destroyed when another is shown and built again on the way back.
  // Reading the router snapshot, it then restored its defaults and its own
  // effect wrote them over the address bar: the filter typed a moment earlier
  // was gone from the screen and from the URL.
  it("keeps a tab's own state when it is left and opened again", async () => {
    const page = await monter({ onglet: 'risques', q: 'Alice' });

    page['changerOnglet']('besoin');
    TestBed.tick();
    await fixture.whenStable();
    expect(TestBed.inject(Location).path()).toContain('q=Alice');

    page['changerOnglet']('risques');
    TestBed.tick();
    await fixture.whenStable();

    const url = TestBed.inject(Location).path();
    expect(url).toContain('onglet=risques');
    expect(url).toContain('q=Alice');
  });

  it('switches the tab and writes it back, nothing for the default one', async () => {
    const page = await monter();

    page['changerOnglet']('besoin');
    TestBed.tick();
    await fixture.whenStable();
    expect(racine().querySelector('app-staffing-page')).not.toBeNull();
    expect(TestBed.inject(Location).path()).toContain('onglet=besoin');

    page['changerOnglet']('problemes');
    TestBed.tick();
    await fixture.whenStable();
    expect(TestBed.inject(Location).path()).not.toContain('onglet=');
  });

  function onglets(): (string | null)[] {
    return [...racine().querySelectorAll('mat-button-toggle')].map((toggle) =>
      toggle.getAttribute('value'),
    );
  }

  it('offers Conformité to an administrator', async () => {
    await monter({}, { roles: ['admin'] });

    expect(onglets()).toContain('conformite');
  });

  it('offers Conformité to an RH right on the current edition only', async () => {
    await monter({}, { roles: ['organisateur', 'rh'], rhEditions: ['E1'] });
    expect(onglets()).toContain('conformite');

    await monter({}, { roles: ['organisateur', 'rh'], rhEditions: ['E2'] });
    expect(onglets()).not.toContain('conformite');
  });

  it('sends an organiser asking for ?onglet=conformite back to the default tab', async () => {
    const page = await monter({ onglet: 'conformite' }, { roles: ['organisateur'] });

    expect(onglets()).not.toContain('conformite');
    expect(page['onglet']()).toBe('problemes');
    expect(racine().querySelector('app-problemes-page')).not.toBeNull();
    expect(racine().querySelector('app-conformite-rapport')).toBeNull();
  });
});
