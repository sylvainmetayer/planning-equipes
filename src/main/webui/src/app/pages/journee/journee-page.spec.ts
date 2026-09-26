// The page over the four renderings: what it reads once for all of them, how
// the day and the rendering come from the URL and go back to it, and that
// switching the rendering fetches nothing again.

import { Location } from '@angular/common';
import { provideZonelessChangeDetection, Signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, ParamMap, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AffectationExplanationService } from '../../core/affectation-explanation.service';
import { AnalysesApi } from '../../core/api/analyses-api';
import { ConstraintsApi } from '../../core/api/constraints-api';
import { JourneesApi } from '../../core/api/journees-api';
import { PostesApi } from '../../core/api/postes-api';
import { ConsignesStore } from '../../core/consignes.store';
import { ApiService } from '../../core/api.service';
import {
  ChangementsJournee,
  Creneau,
  PlanningEvenement,
  PosteAffectation,
  Stand,
} from '../../core/models';
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
  applyChip: (pastille: 'aucune' | 'vides' | 'pauses' | 'verrous' | 'changements') => void;
  resetView: () => void;
  recharger: () => Promise<void>;
  openSeatId: Signal<string | null>;
  message: Signal<string>;
  openSeat: (request: { posteId: string }) => void;
  closeSeat: () => void;
};

describe('JourneePage', () => {
  const loadForDisplay = vi.fn();
  const set = vi.fn();
  const analysesApi = {
    typologies: vi.fn(async () => []),
    breaks: vi.fn(async () => null),
    emplacements: vi.fn(async () => []),
    walks: vi.fn(async () => null),
    groupedArrivals: vi.fn(async () => null),
    intendance: vi.fn(async () => ({ pasMinutes: 60, journees: [], message: '' })),
  };
  const journeesApi = {
    changements: vi.fn(
      async (jour: string, reference: string | null): Promise<ChangementsJournee> => ({
        jour,
        reference: reference === 'resolution' ? 'RESOLUTION' : 'PUBLICATION',
        referenceDisponible: true,
        referenceLe: '2026-07-20T10:00:00Z',
        nouveaux: 1,
        retires: 0,
        remplaces: 0,
        horairesModifies: 0,
        animateursConcernes: 1,
        parVacation: [
          {
            standId: 'Tir',
            standNom: 'Tir',
            date: jour,
            heureDebut: '10:00:00',
            heureFin: '12:00:00',
            heureDebutAvant: null,
            heureFinAvant: null,
            avant: null,
            apres: { animateurId: 'alice', nomAffiche: 'Alice Martin' },
            type: 'NOUVEAU',
          },
        ],
        parAnimateur: [
          {
            animateurId: 'alice',
            nomAffiche: 'Alice Martin',
            changements: [{ type: 'AJOUT', libelle: 'samedi 01/08 : Tir 10h-12h (nouveau)' }],
          },
        ],
      }),
    ),
  };
  let fixture: ComponentFixture<JourneePage>;
  /** The query params as the router emits them: a navigation to this same route pushes a new map. */
  let queryParams$: BehaviorSubject<ParamMap>;

  beforeEach(() => {
    loadForDisplay.mockReset();
    set.mockReset();
    analysesApi.typologies.mockClear();
    analysesApi.breaks.mockClear();
    analysesApi.emplacements.mockClear();
    journeesApi.changements.mockClear();
    loadForDisplay.mockResolvedValue(planningDeuxJours());
  });

  async function monter(queryParams: Record<string, string> = {}): Promise<PageInternals> {
    queryParams$ = new BehaviorSubject(convertToParamMap(queryParams));
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: PlanningStateService, useValue: { loadForDisplay, set } },
        { provide: AnalysesApi, useValue: analysesApi },
        { provide: JourneesApi, useValue: journeesApi },
        {
          provide: ConsignesStore,
          useValue: {
            reload: vi.fn(async () => undefined),
            etat: () => null,
            consignes: () => [],
            aujourdhui: () => null,
            byDate: () => new Map(),
            creneauxAjoutes: () => new Set(),
            consigneOf: () => null,
          },
        },
        { provide: ApiService, useValue: { get: vi.fn(async () => ({ assignments: 2 })) } },
        { provide: SolverJobService, useValue: { editingLocked: () => false } },
        { provide: NotificationService, useValue: { notify: vi.fn() } },
        {
          provide: AffectationExplanationService,
          useValue: {
            deplacer: vi.fn(),
          },
        },
        {
          provide: PostesApi,
          useValue: {
            explanation: vi.fn(async () => ({ contraintesViolees: [], contraintesRespectees: [] })),
          },
        },
        {
          provide: ConstraintsApi,
          useValue: { catalogue: vi.fn(async () => ({ contraintes: [] })) },
        },
        {
          provide: VerrouillageStore,
          useValue: {
            verrouillages: () => [],
            reload: vi.fn(async () => undefined),
            estJourVerrouille: () => false,
            estStandVerrouille: () => false,
            estCreneauVerrouille: () => false,
          },
        },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { queryParamMap: convertToParamMap(queryParams) },
            queryParamMap: queryParams$.asObservable(),
          },
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

  // #711: the bench's old address names a timeslot and a stand. The page
  // resolves it to a seat once the plan is read, moves to its day, opens the
  // Siège panel on it, and writes `siege` in place of `creneau`.
  it('opens the Siège panel on the seat an old bench address names, on its day', async () => {
    const page = await monter({ creneau: '2', stand: 'Dixit' });

    expect(page.jourCourant()?.jour).toBe(2);
    expect(page.openSeatId()).toBe('p2');
    expect(racine().querySelector('app-siege-panel aside')).not.toBeNull();
    const url = TestBed.inject(Location).path();
    expect(url).toContain('siege=p2');
    expect(url).not.toContain('creneau=');
  });

  it('says so when the timeslot an address names holds no seat in the plan', async () => {
    const page = await monter({ creneau: '99' });

    expect(page.openSeatId()).toBeNull();
    expect(page.message()).toContain("n'est pas dans le planning enregistré");
    expect(TestBed.inject(Location).path()).not.toContain('creneau=');
  });

  it('opens the panel on a clicked seat and closes it, the URL following', async () => {
    const page = await monter();

    page.openSeat({ posteId: 'p1' });
    await fixture.whenStable();
    expect(racine().querySelector('app-siege-panel')).not.toBeNull();
    expect(TestBed.inject(Location).path()).toContain('siege=p1');

    page.closeSeat();
    await fixture.whenStable();
    expect(racine().querySelector('app-siege-panel')).toBeNull();
    expect(TestBed.inject(Location).path()).not.toContain('siege=');
  });

  // A gesture re-reads the plan: the rendering is drawn again and the cell the
  // focus came from is gone. The focus goes to the one drawn in its place.
  it('gives the focus back to the cell drawn anew when the panel closes during a re-read', async () => {
    const page = await monter();
    const opener = racine().querySelector<HTMLElement>('[data-siege-cle="p1"]')!;
    opener.focus();
    page.openSeat({ posteId: 'p1' });
    await fixture.whenStable();

    let deliver: (planning: PlanningEvenement) => void = () => undefined;
    loadForDisplay.mockReturnValueOnce(
      new Promise<PlanningEvenement>((resolve) => {
        deliver = resolve;
      }),
    );
    const reread = page.recharger();
    await fixture.whenStable();
    expect(opener.isConnected).toBe(false);

    page.closeSeat();
    deliver(planningDeuxJours());
    await reread;
    await fixture.whenStable();

    const redrawn = racine().querySelector<HTMLElement>('[data-siege-cle="p1"]')!;
    expect(redrawn).not.toBe(opener);
    expect(document.activeElement).toBe(redrawn);
  });

  it('gives the focus back to the cell drawn anew when the panel closes after a re-read', async () => {
    const page = await monter();
    racine().querySelector<HTMLElement>('[data-siege-cle="p1"]')!.focus();
    page.openSeat({ posteId: 'p1' });
    await fixture.whenStable();
    await page.recharger();
    await fixture.whenStable();

    page.closeSeat();
    await fixture.whenStable();

    expect(document.activeElement).toBe(racine().querySelector('[data-siege-cle="p1"]'));
  });

  it('closes the panel when another day is chosen: its seat is not on screen any more', async () => {
    const page = await monter({ siege: 'p1' });
    expect(page.openSeatId()).toBe('p1');

    page.selectJour('2026-08-02');
    await fixture.whenStable();

    expect(page.openSeatId()).toBeNull();
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

  // The palette's « Journée › Rail », used from the Journée page itself: the
  // router reuses the component and only the query params move.
  it('follows a navigation to itself with another rendering, on the same day', async () => {
    const page = await monter({ date: '2026-08-02' });
    expect(page.view()).toBe('calendrier');

    queryParams$.next(convertToParamMap({ vue: 'rail' }));
    TestBed.tick();
    await fixture.whenStable();

    expect(page.view()).toBe('rail');
    expect(racine().querySelector('app-rail-jour-vue')).not.toBeNull();
    expect(page.jourCourant()?.jour).toBe(2);
    expect(loadForDisplay).toHaveBeenCalledOnce();
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

  it('lands the day the selector chose in the URL, nothing on the first day', async () => {
    const page = await monter();
    expect(TestBed.inject(Location).path()).not.toContain('date=');

    page.selectJour('2026-08-02');
    TestBed.tick();
    await fixture.whenStable();
    expect(page.jourCourant()?.jour).toBe(2);
    expect(TestBed.inject(Location).path()).toContain('date=2026-08-02');
  });

  // #712: `?jour=2026-09-05` was ignored in silence.
  it('opens the day a date in the jour param names', async () => {
    const page = await monter({ jour: '2026-08-02' });

    expect(page.jourCourant()?.jour).toBe(2);
    expect(TestBed.inject(Location).path()).toContain('date=2026-08-02');
  });

  it('puts the plan at the top: one title, the day selector, the filters as autocompletes', async () => {
    await monter();

    expect(racine().querySelector('h1')?.textContent?.trim()).toBe('Planning');
    expect(racine().querySelector('app-mini-mois')).not.toBeNull();
    expect(racine().querySelector('mat-select')).toBeNull();
    expect(racine().querySelectorAll('app-selection-recherche')).toHaveLength(2);
    expect(racine().querySelector('app-relecture-barre')).not.toBeNull();
    expect(racine().querySelector('app-consigne-ligne')).not.toBeNull();
  });

  it('prints the day on screen and leads to the television link', async () => {
    const page = await monter();
    page.selectJour('2026-08-02');
    await fixture.whenStable();

    const liens = Array.from(racine().querySelectorAll<HTMLAnchorElement>('.planning-barre a'));
    expect(liens.map((lien) => lien.getAttribute('href'))).toEqual([
      '/impression/2026-08-02',
      '/parametres?onglet=mural',
    ]);
    expect(liens[0].getAttribute('target')).toBe('_blank');
  });

  it('narrows the rendering on what a relecture chip counts, and widens it back', async () => {
    const page = await monter({ vue: 'rail' });

    page.applyChip('vides');
    TestBed.tick();
    await fixture.whenStable();
    expect(page.view()).toBe('calendrier');
    expect(TestBed.inject(Location).path()).toContain('sieges=vides');

    page.applyChip('pauses');
    TestBed.tick();
    await fixture.whenStable();
    expect(page.view()).toBe('pauses');
    expect(TestBed.inject(Location).path()).toContain('relais=sans');
    expect(TestBed.inject(Location).path()).not.toContain('sieges=');

    page.applyChip('changements');
    page.applyChip('aucune');
    TestBed.tick();
    await fixture.whenStable();
    expect(page.view()).toBe('calendrier');
    expect(TestBed.inject(Location).path()).not.toContain('relais=');
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
    // Including the key of a rendering that is not on screen. Written by the
    // rendering itself, `lignes=libres` survived its own reset — the rail was
    // gone, nothing wrote the key any more, and a reload brought the filter
    // back on a link that showed no sign of it.
    expect(url).not.toContain('lignes=');
  });

  it('reads the changes of the day on screen against the reference the URL names, and writes both back', async () => {
    const page = await monter({ vue: 'changements', date: '2026-08-02', reference: 'resolution' });
    await fixture.whenStable();

    expect(journeesApi.changements).toHaveBeenCalledWith('2026-08-02', 'resolution');
    expect(loadForDisplay).toHaveBeenCalledOnce();
    const rendering = racine().querySelector('app-changements-vue');
    expect(rendering).not.toBeNull();
    expect(rendering?.textContent).toContain('Alice Martin');
    expect(page.viewChanged()).toBe(true);
    expect(TestBed.inject(Location).path()).toContain('reference=resolution');
    expect(TestBed.inject(Location).path()).not.toContain('lecture=');

    page.resetView();
    TestBed.tick();
    await fixture.whenStable();
    expect(TestBed.inject(Location).path()).not.toContain('reference=');
    // The choice is the server's again: nothing is sent for the reference.
    expect(journeesApi.changements).toHaveBeenLastCalledWith('2026-08-02', null);
  });

  it('leaves the reference to the server when nobody chose one, and shows the one it answered with', async () => {
    const rienAComparer: ChangementsJournee = {
      jour: '2026-08-01',
      reference: 'RESOLUTION',
      referenceDisponible: false,
      referenceLe: null,
      nouveaux: 0,
      retires: 0,
      remplaces: 0,
      horairesModifies: 0,
      animateursConcernes: 0,
      parVacation: [],
      parAnimateur: [],
    };
    // Asked twice: by the relecture bar's chip, and by the rendering.
    journeesApi.changements
      .mockResolvedValueOnce(rienAComparer)
      .mockResolvedValueOnce(rienAComparer);
    await monter({ vue: 'changements' });
    await fixture.whenStable();

    expect(journeesApi.changements).toHaveBeenCalledWith('2026-08-01', null);
    const rendering = racine().querySelector('app-changements-vue');
    expect(rendering?.textContent).toContain('Rien à comparer');
    expect(TestBed.inject(Location).path()).not.toContain('reference=');
  });

  it('drops the session copy of the plan and re-reads when a rendering wrote to it', async () => {
    const page = await monter();

    await page.recharger();

    expect(set).toHaveBeenCalledWith(null);
    expect(loadForDisplay).toHaveBeenCalledTimes(2);
  });

  it('puts the two days of the URL side by side, read-only, and restores the renderings on leaving', async () => {
    const page = (await monter({
      date: '2026-08-01',
      comparer: '2026-08-02',
    })) as PageInternals & { quitterComparaison: () => void };
    await fixture.whenStable();

    const comparaison = racine().querySelector('app-comparaison-vue');
    expect(comparaison).not.toBeNull();
    // No calendar, hence no drag handle nor drop target, while two days are on screen.
    expect(racine().querySelector('app-calendar-day-vue')).toBeNull();
    expect(racine().querySelector('[cdkdrag], .affectation-poignee')).toBeNull();
    // A reading covers one whole day, never a pair.
    expect(racine().querySelector('app-relecture-barre')).toBeNull();
    // Tir is open on the first day only: a line on both sides, closed on the second.
    expect(comparaison?.textContent).toContain('fermé ce jour-là');
    expect(TestBed.inject(Location).path()).toContain('comparer=2026-08-02');

    page.quitterComparaison();
    TestBed.tick();
    await fixture.whenStable();
    expect(racine().querySelector('app-comparaison-vue')).toBeNull();
    expect(racine().querySelector('app-calendar-day-vue')).not.toBeNull();
    expect(racine().querySelector('app-relecture-barre')).not.toBeNull();
    expect(TestBed.inject(Location).path()).not.toContain('comparer=');
  });

  it('ignores a second day equal to the first, or unknown, and says so', async () => {
    await monter({ date: '2026-08-01', comparer: '2026-08-01' });
    await fixture.whenStable();
    expect(racine().querySelector('app-comparaison-vue')).toBeNull();
    expect(racine().querySelector('app-calendar-day-vue')).not.toBeNull();
    expect(racine().textContent).toContain('Comparaison ignorée');

    await monter({ comparer: '2030-01-01' });
    await fixture.whenStable();
    expect(racine().querySelector('app-comparaison-vue')).toBeNull();
    expect(racine().textContent).toContain("ce jour n'existe pas");
  });

  it('keeps a way out of a comparison requested on a plan of one day', async () => {
    const oneDay = planningDeuxJours();
    oneDay.postes = oneDay.postes.slice(0, 1);
    loadForDisplay.mockResolvedValue(oneDay);
    const page = await monter({ comparer: '2026-08-02' });
    await fixture.whenStable();

    expect(racine().textContent).toContain("ce jour n'existe pas");
    const quitter = [...racine().querySelectorAll('button')].find((bouton) =>
      bouton.textContent?.includes('Quitter la comparaison'),
    );
    expect(quitter).toBeDefined();
    // No second selector: there is no other day to offer.
    expect(racine().textContent).not.toContain('Comparer avec');
    expect(page.viewChanged()).toBe(true);

    quitter?.click();
    TestBed.tick();
    await fixture.whenStable();
    expect(racine().textContent).not.toContain('Comparaison ignorée');
    expect(racine().textContent).not.toContain('Quitter la comparaison');
    expect(TestBed.inject(Location).path()).not.toContain('comparer=');
  });

  it('drops the second day on « Réinitialiser la vue »', async () => {
    const page = await monter({ date: '2026-08-01', comparer: '2026-08-02', ecarts: '1' });
    await fixture.whenStable();
    expect(racine().querySelector('app-comparaison-vue')).not.toBeNull();

    page.resetView();
    TestBed.tick();
    await fixture.whenStable();
    expect(racine().querySelector('app-comparaison-vue')).toBeNull();
    expect(page.viewChanged()).toBe(false);
    expect(TestBed.inject(Location).path()).not.toContain('comparer=');
    expect(TestBed.inject(Location).path()).not.toContain('ecarts=');
  });

  it('keeps the map, the breaks and the changes on one day', async () => {
    await monter({ vue: 'pauses', comparer: '2026-08-02' });
    await fixture.whenStable();
    expect(racine().querySelector('app-comparaison-vue')).toBeNull();
    expect(racine().querySelector('app-pauses-vue')).not.toBeNull();
    expect(racine().textContent).not.toContain('Comparer avec');
  });

  it('shows the failure instead of an empty day when the plan cannot be read', async () => {
    loadForDisplay.mockRejectedValue(new Error('planning illisible'));

    const page = await monter();

    expect(page.error()).toContain('planning illisible');
    expect(racine().querySelector('app-calendar-day-vue')).toBeNull();
  });
});
