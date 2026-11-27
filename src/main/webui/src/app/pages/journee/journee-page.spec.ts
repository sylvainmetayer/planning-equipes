// The page over its four views: what it reads once for all of them, how
// the day and the rendering come from the URL and go back to it, and that
// switching the rendering fetches nothing again.

import { Location } from '@angular/common';
import { Component, provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, ParamMap, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AnalysesApi } from '../../core/api/analyses-api';
import { ConstraintsApi } from '../../core/api/constraints-api';
import { PlanningApi } from '../../core/api/planning-api';
import { PostesApi } from '../../core/api/postes-api';
import { ConsignesStore } from '../../core/consignes.store';
import { ApiService } from '../../core/api.service';
import {
  Creneau,
  HoursReading,
  HoursSource,
  PlanningEvenement,
  PosteAffectation,
  Stand,
} from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { PlanningStateService } from '../../core/planning-state.service';
import { SolverJobService } from '../../core/solver-job.service';
import { VerrouillageStore } from '../../core/verrouillage.store';
import { SolverPage } from '../solver/solver-page';
import { VersionsPage } from '../versions/versions-page';
import { JourneePage } from './journee-page';

/** « Calculer » and « Versions du plan » stand in: what they do is their own specs'. */
@Component({ selector: 'app-solver-page', template: '' })
class SolverPageStub {}

@Component({ selector: 'app-versions-page', template: '' })
class VersionsPageStub {}

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

describe('JourneePage', () => {
  const loadForDisplay = vi.fn();
  const set = vi.fn();
  const analysesApi = {
    typologies: vi.fn(() => Promise.resolve([])),
    breaks: vi.fn(() => Promise.resolve(null)),
    emplacements: vi.fn(() => Promise.resolve([])),
    walks: vi.fn(() => Promise.resolve(null)),
    groupedArrivals: vi.fn(() => Promise.resolve(null)),
    intendance: vi.fn(() => Promise.resolve({ pasMinutes: 60, journees: [], message: '' })),
  };
  const planningApi = {
    equityReport: vi.fn(() =>
      Promise.resolve({
        heureDebutSoiree: '20:00:00',
        semaines: [],
        lignes: [],
        syntheses: {},
        colonnesSolveur: [],
      }),
    ),
    hoursReport: vi.fn((source: HoursSource | null = null): Promise<HoursReading> =>
      Promise.resolve({
        source: source ?? 'persiste',
        planDate: null,
        publicationAvailable: false,
        report: { heureDebutSoiree: '20:00:00', semaines: [], animateurs: [] },
      }),
    ),
    typologiesReport: vi.fn(() => Promise.resolve({ typologies: [], jours: [] })),
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
    loadForDisplay.mockResolvedValue(planningDeuxJours());
  });

  // Most cases below were written on the table, the page's 3.x default: they
  // mount it explicitly; `{ tableau: false }` mounts the address as given.
  async function mount(
    queryParams: Record<string, string> = {},
    { tableau = true } = {},
  ): Promise<JourneePage> {
    queryParams$ = new BehaviorSubject(
      convertToParamMap(tableau ? { mode: 'tableau', ...queryParams } : queryParams),
    );
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: PlanningStateService, useValue: { loadForDisplay, set } },
        { provide: AnalysesApi, useValue: analysesApi },
        { provide: PlanningApi, useValue: planningApi },
        {
          provide: ConsignesStore,
          useValue: {
            reload: vi.fn(() => Promise.resolve(undefined)),
            etat: () => null,
            consignes: () => [],
            aujourdhui: () => null,
            byDate: () => new Map(),
            creneauxAjoutes: () => new Set(),
            consigneOf: () => null,
          },
        },
        {
          provide: ApiService,
          useValue: {
            // Lists for the referentials the treemap reads, a volumetry for the rest.
            get: vi.fn((url: string) =>
              Promise.resolve(
                url === '/api/typologies' || url === '/api/stands' || url === '/api/etats-journee'
                  ? []
                  : { assignments: 2 },
              ),
            ),
          },
        },
        { provide: SolverJobService, useValue: { editingLocked: () => false } },
        { provide: NotificationService, useValue: { notify: vi.fn() } },
        {
          provide: PostesApi,
          useValue: {
            move: vi.fn(),
            explanation: vi.fn(() =>
              Promise.resolve({ contraintesViolees: [], contraintesRespectees: [] }),
            ),
          },
        },
        {
          provide: ConstraintsApi,
          useValue: { catalogue: vi.fn(() => Promise.resolve({ contraintes: [] })) },
        },
        {
          provide: VerrouillageStore,
          useValue: {
            verrouillages: () => [],
            reload: vi.fn(() => Promise.resolve(undefined)),
            estJourVerrouille: () => false,
            isStandVerrouille: () => false,
            isAnimateurCreneauVerrouille: () => false,
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
    TestBed.overrideComponent(JourneePage, {
      remove: { imports: [SolverPage, VersionsPage] },
      add: { imports: [SolverPageStub, VersionsPageStub] },
    });
    await TestBed.compileComponents();
    // The tabs are read from the address bar, as `ongletHote` reads them.
    if (queryParams['onglet']) {
      TestBed.inject(Location).replaceState('/journee', `onglet=${queryParams['onglet']}`);
    }
    fixture = TestBed.createComponent(JourneePage);
    await fixture.whenStable();
    return fixture.componentInstance;
  }

  function racine(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  it('reads the plan, the breaks and the referentials once, and opens on the rail of the first day', async () => {
    const page = await mount({}, { tableau: false });

    expect(loadForDisplay).toHaveBeenCalledOnce();
    expect(analysesApi.breaks).toHaveBeenCalledOnce();
    expect(page['view']()).toBe('rail');
    expect(page['jourCourant']()?.key).toBe('2026-08-01');
    expect(racine().querySelector('app-rail-jour-vue')).not.toBeNull();
    expect(page['stands']().map((each) => each.id)).toEqual(['Dixit', 'Tir']);
    expect(page['animateurs']()[0].label).toBe('Alice Martin');
    // The default names neither the view nor the mode.
    expect(TestBed.inject(Location).path()).not.toMatch(/vue=|mode=/);
  });

  it('rewrites the 3.x keys of the table and the rail to the view « Jour »', async () => {
    const table = await mount({ vue: 'calendrier' }, { tableau: false });
    expect(table['view']()).toBe('calendrier');
    expect(TestBed.inject(Location).path()).toContain('vue=jour&mode=tableau');

    const rail = await mount({ vue: 'rail' }, { tableau: false });
    expect(rail['view']()).toBe('rail');
    expect(TestBed.inject(Location).path()).not.toMatch(/vue=|mode=/);
  });

  // Q49: the former Solveur page is the « Calculer » tab, its versions the tab beside it.
  it('opens « Calculer » from the address without reading the plan, and reads it on the way back', async () => {
    const page = await mount({ onglet: 'calculer' }, { tableau: false });

    expect(racine().querySelector('app-solver-page')).not.toBeNull();
    expect(racine().querySelector('.planning-barre')).toBeNull();
    expect(loadForDisplay).not.toHaveBeenCalled();

    await page['selectToggle']('stand');
    TestBed.tick();
    await fixture.whenStable();

    expect(racine().querySelector('app-solver-page')).toBeNull();
    expect(page['currentView']()).toBe('stand');
    expect(loadForDisplay).toHaveBeenCalledOnce();
    expect(TestBed.inject(Location).path()).not.toContain('onglet=');
  });

  it('writes the tab alone in the address, and the plan nothing, while « Calculer » or « Versions » is open', async () => {
    const page = await mount({ date: '2026-08-02' }, { tableau: false });
    expect(TestBed.inject(Location).path()).toContain('date=2026-08-02');

    await page['selectToggle']('versions');
    TestBed.tick();
    await fixture.whenStable();
    expect(racine().querySelector('app-versions-page')).not.toBeNull();
    expect(page['toggleValue']()).toBe('versions');
    expect(TestBed.inject(Location).path()).toMatch(/\?onglet=versions$/);

    await page['selectToggle']('calculer');
    TestBed.tick();
    await fixture.whenStable();
    expect(racine().querySelector('app-solver-page')).not.toBeNull();
    expect(TestBed.inject(Location).path()).toMatch(/\?onglet=calculer$/);
  });

  it('comes back to « Jour » in the mode it was left in', async () => {
    const page = await mount({ vue: 'jour', mode: 'tableau' }, { tableau: false });
    page['selectView']('carte');
    expect(page['view']()).toBe('carte');
    page['selectView']('stand');
    page['selectView']('jour');
    expect(page['view']()).toBe('calendrier');
  });

  // #711: the bench's old address names a timeslot and a stand. The page
  // resolves it to a seat once the plan is read, moves to its day, opens the
  // Siège panel on it, and writes `siege` in place of `creneau`.
  it('opens the Siège panel on the seat an old bench address names, on its day', async () => {
    const page = await mount({ creneau: '2', stand: 'Dixit' });

    expect(page['jourCourant']()?.jour).toBe(2);
    expect(page['openSeatId']()).toBe('p2');
    expect(racine().querySelector('app-siege-panel aside')).not.toBeNull();
    const url = TestBed.inject(Location).path();
    expect(url).toContain('siege=p2');
    expect(url).not.toContain('creneau=');
  });

  it('says so when the timeslot an address names holds no seat in the plan', async () => {
    const page = await mount({ creneau: '99' });

    expect(page['openSeatId']()).toBeNull();
    expect(page['message']()).toContain("n'est pas dans le planning enregistré");
    expect(TestBed.inject(Location).path()).not.toContain('creneau=');
  });

  it('opens the panel on a clicked seat and closes it, the URL following', async () => {
    const page = await mount();

    page['openSeat']({ posteId: 'p1' });
    await fixture.whenStable();
    expect(racine().querySelector('app-siege-panel')).not.toBeNull();
    expect(TestBed.inject(Location).path()).toContain('siege=p1');

    page['closeSeat']();
    await fixture.whenStable();
    expect(racine().querySelector('app-siege-panel')).toBeNull();
    expect(TestBed.inject(Location).path()).not.toContain('siege=');
  });

  // A gesture re-reads the plan: the rendering is drawn again and the cell the
  // focus came from is gone. The focus goes to the one drawn in its place.
  it('gives the focus back to the cell drawn anew when the panel closes during a re-read', async () => {
    const page = await mount();
    const opener = racine().querySelector<HTMLElement>('[data-siege-cle="p1"]')!;
    opener.focus();
    page['openSeat']({ posteId: 'p1' });
    await fixture.whenStable();

    let deliver: (planning: PlanningEvenement) => void = () => undefined;
    loadForDisplay.mockReturnValueOnce(
      new Promise<PlanningEvenement>((resolve) => {
        deliver = resolve;
      }),
    );
    const reread = page['recharger']();
    await fixture.whenStable();
    expect(opener.isConnected).toBe(false);

    page['closeSeat']();
    deliver(planningDeuxJours());
    await reread;
    await fixture.whenStable();

    const redrawn = racine().querySelector<HTMLElement>('[data-siege-cle="p1"]')!;
    expect(redrawn).not.toBe(opener);
    expect(document.activeElement).toBe(redrawn);
  });

  it('gives the focus back to the cell drawn anew when the panel closes after a re-read', async () => {
    const page = await mount();
    racine().querySelector<HTMLElement>('[data-siege-cle="p1"]')!.focus();
    page['openSeat']({ posteId: 'p1' });
    await fixture.whenStable();
    await page['recharger']();
    await fixture.whenStable();

    page['closeSeat']();
    await fixture.whenStable();

    expect(document.activeElement).toBe(racine().querySelector('[data-siege-cle="p1"]'));
  });

  it('closes the panel when another day is chosen: its seat is not on screen any more', async () => {
    const page = await mount({ siege: 'p1' });
    expect(page['openSeatId']()).toBe('p1');

    page['selectJour']('2026-08-02');
    await fixture.whenStable();

    expect(page['openSeatId']()).toBeNull();
  });

  it('switches the rendering without reading anything again', async () => {
    const page = await mount();

    page['changeView']('rail');
    await fixture.whenStable();
    expect(racine().querySelector('app-rail-jour-vue')).not.toBeNull();
    expect(racine().querySelector('app-calendar-day-vue')).toBeNull();

    page['changeView']('carte');
    TestBed.tick();
    await fixture.whenStable();
    expect(racine().querySelector('app-carte-jour-vue')).not.toBeNull();

    expect(loadForDisplay).toHaveBeenCalledOnce();
    expect(analysesApi.breaks).toHaveBeenCalledOnce();
    expect(TestBed.inject(Location).path()).toContain('vue=carte');
  });

  // The palette's « Journée › Rail », used from the Journée page itself: the
  // router reuses the component and only the query params move.
  it('follows a navigation to itself with another rendering, on the same day', async () => {
    const page = await mount({ date: '2026-08-02', mode: 'rail' });
    expect(page['view']()).toBe('rail');

    queryParams$.next(convertToParamMap({ vue: 'jour', mode: 'tableau' }));
    TestBed.tick();
    await fixture.whenStable();

    expect(page['view']()).toBe('calendrier');
    expect(racine().querySelector('app-calendar-day-vue')).not.toBeNull();
    expect(page['jourCourant']()?.jour).toBe(2);
    expect(loadForDisplay).toHaveBeenCalledOnce();
  });

  // The palette's stand and timeslot land on this very route: the stand, the
  // day and the seat they name used to wait for a reload.
  it('follows a navigation to itself naming a stand, a day or a seat', async () => {
    const page = await mount();

    queryParams$.next(convertToParamMap({ stand: 'Dixit', date: '2026-08-02' }));
    TestBed.tick();
    await fixture.whenStable();
    expect(page['stand']()).toBe('Dixit');
    expect(page['jourCourant']()?.jour).toBe(2);

    queryParams$.next(convertToParamMap({ animateur: 'alice', jour: '1' }));
    TestBed.tick();
    await fixture.whenStable();
    expect(page['animateur']()).toBe('alice');
    expect(page['jourCourant']()?.jour).toBe(1);
    // A key the address leaves out keeps what is on screen.
    expect(page['stand']()).toBe('Dixit');

    queryParams$.next(convertToParamMap({ siege: 'p2' }));
    TestBed.tick();
    await fixture.whenStable();
    expect(page['openSeatId']()).toBe('p2');
    expect(page['jourCourant']()?.jour).toBe(2);
    expect(racine().querySelector('app-siege-panel')).not.toBeNull();
    expect(loadForDisplay).toHaveBeenCalledOnce();
  });

  it('opens on the rendering and the day the URL names, by date or by the older day number', async () => {
    const byDate = await mount({ vue: 'rail', date: '2026-08-02' });
    expect(byDate['view']()).toBe('rail');
    expect(byDate['jourCourant']()?.jour).toBe(2);

    const byNumber = await mount({ jour: '2' });
    expect(byNumber['jourCourant']()?.jour).toBe(2);
    // Known by its date now: the day is written back under `date`, and `jour` retired.
    expect(TestBed.inject(Location).path()).toContain('date=2026-08-02');
    expect(TestBed.inject(Location).path()).not.toContain('jour=');
  });

  it('lands the day the selector chose in the URL, nothing on the first day', async () => {
    const page = await mount();
    expect(TestBed.inject(Location).path()).not.toContain('date=');

    page['selectJour']('2026-08-02');
    TestBed.tick();
    await fixture.whenStable();
    expect(page['jourCourant']()?.jour).toBe(2);
    expect(TestBed.inject(Location).path()).toContain('date=2026-08-02');
  });

  // #712: `?jour=2026-09-05` was ignored in silence.
  it('opens the day a date in the jour param names', async () => {
    const page = await mount({ jour: '2026-08-02' });

    expect(page['jourCourant']()?.jour).toBe(2);
    expect(TestBed.inject(Location).path()).toContain('date=2026-08-02');
  });

  // #728: a day the edition does not hold opened the first one in silence.
  it('says the day asked for is not one of the edition, then drops it once another is chosen', async () => {
    const page = await mount({ jour: '2026-10-05' });

    expect(page['jourCourant']()?.jour).toBe(1);
    // An <output>: the implicit live region of role="status".
    const notice = racine().querySelector('output.journee-jour-inconnu');
    expect(notice?.textContent?.replace(/\s+/g, ' ').trim()).toContain(
      "Le 05/10/2026 n'est pas un jour du planning : voici le premier jour.",
    );

    page['selectJour']('2026-08-02');
    TestBed.tick();
    await fixture.whenStable();
    expect(racine().querySelector('.journee-jour-inconnu')).toBeNull();
  });

  it('puts the plan at the top: one title, the day selector, the filters as autocompletes', async () => {
    const page = await mount();
    // Location and game category are folded under « Réglages avancés » (ADR 0090).
    page['avance'].basculer();
    TestBed.tick();
    await fixture.whenStable();

    expect(racine().querySelector('h1')?.textContent?.trim()).toBe('Planning');
    expect(racine().querySelector('app-mini-mois')).not.toBeNull();
    expect(racine().querySelector('mat-select')).toBeNull();
    // Stand, animateur, location, game category: the four filters every axis keeps.
    expect(racine().querySelectorAll('app-selection-recherche')).toHaveLength(4);
    expect(racine().querySelector('app-relecture-barre')).not.toBeNull();
    expect(racine().querySelector('app-consigne-ligne')).not.toBeNull();
  });

  it('prints the day on screen and leads to the television link', async () => {
    const page = await mount();
    page['selectJour']('2026-08-02');
    await fixture.whenStable();

    const liens = Array.from(racine().querySelectorAll<HTMLAnchorElement>('.planning-barre a'));
    expect(liens.map((lien) => lien.getAttribute('href'))).toEqual([
      '/impression/2026-08-02',
      '/parametres?onglet=mural',
    ]);
    expect(liens[0].getAttribute('target')).toBe('_blank');
  });

  // #713: the same page, its grids, the filters kept from one to the other.
  it('draws each axis under the same filters, and keeps the person from one to the other', async () => {
    const page = await mount({
      vue: 'personne',
      animateur: 'alice',
    });
    expect(page['axe']()).toBe('personne');
    expect(racine().querySelector('app-planning-personne-vue')).not.toBeNull();
    expect(racine().querySelector('app-relecture-barre')).toBeNull();

    page['selectView']('jour');
    TestBed.tick();
    await fixture.whenStable();
    expect(racine().querySelector('app-rail-jour-vue')).not.toBeNull();
    const url = TestBed.inject(Location).path();
    expect(url).toContain('animateur=alice');
    expect(url).not.toContain('vue=');
    // The sort is the person axis's own: it leaves the address with it.
    expect(url).not.toContain('sort=');

    page['selectView']('stand');
    TestBed.tick();
    await fixture.whenStable();
    expect(racine().querySelector('app-planning-stand-vue')).not.toBeNull();
    expect(TestBed.inject(Location).path()).toContain('vue=stand');

    expect(page['animateur']()).toBe('alice');
  });

  // A filter drawn over a rendering that ignores it narrows nothing while
  // saying it does, and the relecture panel then warned of a filter hiding
  // nothing.
  it('shows only the filters the rendering on screen reads, and counts only those', async () => {
    const page = await mount({ emplacement: 'aucun' });
    page['avance'].basculer();
    TestBed.tick();
    await fixture.whenStable();
    const filtres = () =>
      Array.from(racine().querySelectorAll('.planning-barre .planning-filtre')).map((filtre) =>
        filtre.querySelector('mat-label')?.textContent?.trim(),
      );
    const redraw = async () => {
      TestBed.tick();
      await fixture.whenStable();
    };

    expect(filtres()).toEqual([
      'Stand',
      'Animateur',
      'Emplacement',
      'Typologie',
      'Filtrer par nom',
    ]);
    expect(page['filtreActif']()).toBe(true);

    page['changeView']('rail');
    await redraw();
    expect(filtres()).toEqual(['Stand', 'Animateur', 'Filtrer par nom']);
    expect(page['filtreActif']()).toBe(false);

    page['changeView']('carte');
    await redraw();
    expect(filtres()).toEqual(['Stand']);

    page['selectView']('stand');
    page['standView'].set('treemap');
    await redraw();
    expect(filtres()).toEqual(['Emplacement']);
  });

  it('reads the 3.x axe and the vue of the grid it carried, and writes the 4.0 keys', async () => {
    const page = await mount({ axe: 'personne', vue: 'frise' });
    expect(page['axe']()).toBe('personne');
    expect(page['personView']()).toBe('frise');
    expect(TestBed.inject(Location).path()).toContain('vue=personne&rendu=frise');
    expect(TestBed.inject(Location).path()).not.toContain('axe=');

    const treemap = await mount({ axe: 'stand', vue: 'treemap' });
    expect(treemap['standView']()).toBe('treemap');
    expect(TestBed.inject(Location).path()).toContain('vue=stand&rendu=treemap');
  });

  // The synthesis is the Diagnostic's Charge: the route sends its addresses there,
  // and a page reached some other way draws the grid rather than a screen it no
  // longer offers.
  it('never draws the synthesis, nor reads the hours', async () => {
    const page = await mount({ vue: 'personne', rendu: 'synthese' });
    expect(page['personView']()).toBe('grille');
    expect(racine().querySelector('.planning-grille-jour')).not.toBeNull();
    expect(racine().querySelector('mat-button-toggle[value="synthese"]')).toBeNull();
    expect(planningApi.hoursReport).not.toHaveBeenCalled();
    expect(TestBed.inject(Location).path()).not.toContain('rendu=synthese');
  });

  it('keeps the span of the grids in the address, the week left out', async () => {
    const page = await mount({ vue: 'personne', portee: 'jour' });
    const location = TestBed.inject(Location);
    expect(page['gridSpan']()).toBe('jour');
    expect(racine().querySelector('.planning-grille-portee-jour')).not.toBeNull();
    expect(location.path()).toContain('portee=jour');

    // The one span serves both grids.
    page['selectView']('stand');
    TestBed.tick();
    await fixture.whenStable();
    expect(location.path()).toContain('portee=jour');

    // The day's own renderings read no span: the key leaves with the grids.
    page['selectView']('jour');
    TestBed.tick();
    await fixture.whenStable();
    expect(location.path()).not.toContain('portee=');

    page['selectView']('personne');
    page['gridSpan'].set('semaine');
    TestBed.tick();
    await fixture.whenStable();
    expect(location.path()).not.toContain('portee=');
  });

  it('holds every cell to its colour over the whole event, and says why', async () => {
    await mount({ vue: 'personne', portee: 'evenement' });
    expect(racine().querySelector('.planning-personne-densite-compact')).not.toBeNull();
    const note = racine().querySelector('#planning-grille-couleur-imposee');
    expect(note?.textContent).toContain('couleur');
    const densite = racine().querySelector('mat-button-toggle-group[aria-describedby]');
    expect(densite?.getAttribute('aria-describedby')).toBe('planning-grille-couleur-imposee');
  });

  it('clears the keys of the treemap once it leaves the screen', async () => {
    const page = await mount({ vue: 'stand' });
    // The treemap restores its state from the address bar, as a link gives it.
    const location = TestBed.inject(Location);
    location.replaceState('/journee', 'vue=stand&regroupement=typologie&semaine=2026-07-27');
    page['standView'].set('treemap');
    TestBed.tick();
    await fixture.whenStable();
    expect(location.path()).toContain('regroupement=typologie');
    expect(location.path()).toContain('semaine=2026-07-27');

    page['selectView']('personne');
    TestBed.tick();
    await fixture.whenStable();

    const url = TestBed.inject(Location).path();
    expect(url).not.toContain('regroupement=');
    expect(url).not.toContain('semaine=');
  });

  it('opens the Siège panel from a cell of a grid, on its day', async () => {
    const page = await mount({ vue: 'stand' });

    page['openSeatOnDay']({ posteId: 'p2', jour: '2026-08-02' });
    TestBed.tick();
    await fixture.whenStable();

    expect(page['openSeatId']()).toBe('p2');
    expect(page['jourCourant']()?.jour).toBe(2);
    expect(racine().querySelector('app-siege-panel')).not.toBeNull();
  });

  it('carries the shared filters in the URL and clears them all in one action', async () => {
    const page = await mount({ stand: 'Tir', q: 'ali', lignes: 'libres' });
    expect(page['viewChanged']()).toBe(true);

    page['resetView']();
    TestBed.tick();
    await fixture.whenStable();

    expect(page['viewChanged']()).toBe(false);
    const url = TestBed.inject(Location).path();
    expect(url).not.toContain('stand=');
    expect(url).not.toContain('q=');
    // Including the key of a rendering that is not on screen. Written by the
    // rendering itself, `lignes=libres` survived its own reset — the rail was
    // gone, nothing wrote the key any more, and a reload brought the filter
    // back on a link that showed no sign of it.
    expect(url).not.toContain('lignes=');
  });

  it('drops the session copy of the plan and re-reads when a rendering wrote to it', async () => {
    const page = await mount();

    await page['recharger']();

    expect(set).toHaveBeenCalledWith(null);
    expect(loadForDisplay).toHaveBeenCalledTimes(2);
  });

  it('shows the failure instead of an empty day when the plan cannot be read', async () => {
    loadForDisplay.mockRejectedValue(new Error('planning illisible'));

    const page = await mount();

    expect(page['error']()).toContain('planning illisible');
    expect(racine().querySelector('app-calendar-day-vue')).toBeNull();
  });
});
