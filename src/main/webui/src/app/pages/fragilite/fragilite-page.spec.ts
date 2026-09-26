// Loading / error / refresh states of the fragility screen, now that they are
// a resource's rather than three signals written by hand. `fragilite.spec.ts`
// covers the pure filtering; what is pinned here is that a refresh keeps the
// report on screen, and that a failure shows a sentence and not a blank card.

import { Location } from '@angular/common';
import { Provider, provideZonelessChangeDetection, Signal, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter, Router } from '@angular/router';
import {
  AnimateurFragilite,
  CompetenceRare,
  RapportFragilite,
  Stand,
  TypologieItem,
} from '../../core/models';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { VerrouillageStore } from '../../core/verrouillage.store';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AnalysesApi } from '../../core/api/analyses-api';
import { FragilitePage } from './fragilite-page';

function rapport(partial: Partial<RapportFragilite> = {}): RapportFragilite {
  return {
    animateurs: [],
    competencesRares: [],
    totalCompetencesRares: 0,
    groupesSansSpecialiste: 0,
    groupesAnalyses: 12,
    groupesDejaSousEffectif: 0,
    animateursIrremplacables: 0,
    ninjaConfigure: true,
    aucunAnimateur: false,
    message: 'Douze groupes analysés.',
    ...partial,
  };
}

/** What « Verrouiller » writes through; its creations are read back by the gestures' tests. */
const verrous = {
  create: vi.fn(async () => []),
  reload: vi.fn(async () => undefined),
  estAnimateurVerrouille: vi.fn((): boolean => false),
};

/**
 * The referential the page names typologies and stands from — reloading it is
 * a no-op here —, and the locks and the solver its « Verrouiller » reads.
 */
function referentiel(
  typologies: Partial<TypologieItem>[] = [],
  stands: Partial<Stand>[] = [],
): Provider[] {
  return [
    {
      provide: ReferenceDataStore,
      useValue: {
        typologies: signal(typologies as TypologieItem[]),
        stands: signal(stands as Stand[]),
        reload: vi.fn(async () => undefined),
      },
    },
    { provide: VerrouillageStore, useValue: verrous },
    { provide: SolverJobService, useValue: { editingLocked: signal(false) } },
  ];
}

function deferred<T>(): {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (error: Error) => void;
} {
  let resolve: (value: T) => void = () => undefined;
  let reject: (error: Error) => void = () => undefined;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

type PageInternals = {
  rapport: Signal<RapportFragilite | null>;
  chargement: Signal<boolean>;
  erreur: Signal<string>;
  recharger: () => void;
};

describe('FragilitePage loading', () => {
  const analysesApi = { fragility: vi.fn() };
  let fixture: ComponentFixture<FragilitePage>;

  beforeEach(() => {
    analysesApi.fragility.mockReset();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: AnalysesApi, useValue: analysesApi },
        referentiel(),
        { provide: Location, useValue: { path: () => '/fragilite', replaceState: vi.fn() } },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap({}) } },
        },
      ],
    });
  });

  function createPage(): PageInternals {
    fixture = TestBed.createComponent(FragilitePage);
    return fixture.componentInstance as unknown as PageInternals;
  }

  function text(): string {
    fixture.detectChanges();
    return (fixture.nativeElement as HTMLElement).textContent!.replace(/\s+/g, ' ');
  }

  /**
   * The page is a tab of « Diagnostic »: it is destroyed when another tab is
   * shown and built again on the way back, while its filters live in the
   * address bar — written there by `keepViewInQueryParams`, which never
   * navigates. The router snapshot therefore still holds what the last real
   * navigation parsed, here nothing at all. Reading it, the tab came back
   * empty and then wrote its defaults over the URL, losing the search twice.
   */
  it('restores its filters from the address bar, not from the frozen router snapshot', async () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: AnalysesApi, useValue: analysesApi },
        referentiel(),
        {
          provide: Location,
          useValue: {
            path: () => '/diagnostic?onglet=fragilite&vue=COMPETENCES&filtre=CRITIQUES&q=Alice',
            replaceState: vi.fn(),
          },
        },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap({}) } },
        },
      ],
    });
    analysesApi.fragility.mockResolvedValue(rapport());

    const page = TestBed.createComponent(FragilitePage).componentInstance as unknown as {
      view: Signal<string>;
      filtre: Signal<string>;
      recherche: Signal<string>;
    };

    expect(page.view()).toBe('COMPETENCES');
    expect(page.filtre()).toBe('CRITIQUES');
    expect(page.recherche()).toBe('Alice');
  });

  it('says it is analysing while the first report is in flight, and shows it once it lands', async () => {
    const pending = deferred<RapportFragilite>();
    analysesApi.fragility.mockReturnValue(pending.promise);
    const page = createPage();

    expect(page.chargement()).toBe(true);
    expect(text()).toContain('Analyse de la fragilité du planning');

    pending.resolve(rapport());
    await vi.waitFor(() => expect(page.chargement()).toBe(false));
    expect(text()).toContain('Douze groupes analysés.');
    expect(analysesApi.fragility).toHaveBeenCalledOnce();
  });

  it('says no animateur is entered instead of inviting a solve nothing could run', async () => {
    // A stale plan whose animateurs are all gone still has groups to analyse,
    // and its server message would otherwise read as nothing to worry about.
    analysesApi.fragility.mockResolvedValue(
      rapport({ aucunAnimateur: true, message: "Aucun animateur n'est saisi." }),
    );
    const page = createPage();
    await vi.waitFor(() => expect(page.rapport()).not.toBeNull());

    expect(text()).toContain('Aucun animateur saisi');
    expect(text()).not.toContain('Aucun planning persisté');
    expect(text()).not.toContain('animateurs affectés');
  });

  it('shows the failure as a sentence, not a blank card', async () => {
    analysesApi.fragility.mockRejectedValue(new Error('Aucun planning enregistré.'));
    const page = createPage();

    await vi.waitFor(() => expect(page.erreur()).toContain('Aucun planning enregistré.'));
    expect(page.rapport()).toBeNull();
    expect(text()).toContain('Aucun planning enregistré.');
  });

  it('keeps the report on screen while a refresh is in flight, without a second progress bar', async () => {
    analysesApi.fragility.mockResolvedValueOnce(rapport());
    const page = createPage();
    await vi.waitFor(() => expect(page.rapport()).not.toBeNull());
    const pending = deferred<RapportFragilite>();
    analysesApi.fragility.mockReturnValue(pending.promise);

    page.recharger();
    await vi.waitFor(() => expect(page.chargement()).toBe(true));

    expect(page.rapport()).not.toBeNull();
    expect(text()).toContain('Douze groupes analysés.');
    expect((fixture.nativeElement as HTMLElement).querySelector('mat-progress-bar')).toBeNull();

    pending.resolve(rapport({ message: 'Treize groupes analysés.' }));
    await vi.waitFor(() => expect(page.chargement()).toBe(false));
    expect(text()).toContain('Treize groupes analysés.');
  });

  it('shows the failure of a refresh in place of the report, and the report again on the next success', async () => {
    analysesApi.fragility.mockResolvedValueOnce(rapport());
    const page = createPage();
    await vi.waitFor(() => expect(page.rapport()).not.toBeNull());

    analysesApi.fragility.mockRejectedValueOnce(new Error('Serveur injoignable.'));
    page.recharger();
    await vi.waitFor(() => expect(page.erreur()).toContain('Serveur injoignable.'));
    expect(text()).toContain('Serveur injoignable.');
    expect(text()).not.toContain('Douze groupes analysés.');

    analysesApi.fragility.mockResolvedValueOnce(rapport());
    page.recharger();
    await vi.waitFor(() => expect(page.erreur()).toBe(''));
    expect(text()).toContain('Douze groupes analysés.');
  });
});

function animateur(partial: Partial<AnimateurFragilite> = {}): AnimateurFragilite {
  return {
    animateurId: 'a1',
    nom: 'Alice Martin',
    ninja: false,
    affectations: 3,
    postesEffondres: 3,
    postesIrremplacables: 1,
    competencesRares: 1,
    severite: 'CRITIQUE',
    postes: [],
    postesNonDetailles: 0,
    ...partial,
  };
}

function competence(partial: Partial<CompetenceRare> = {}): CompetenceRare {
  return {
    standId: 'S1',
    standNom: 'Escape game',
    creneauId: 1,
    date: '2026-07-08',
    jour: 1,
    heureDebut: '10:00:00',
    heureFin: '12:00:00',
    typologies: ['ESCAPE', 'QUIZ'],
    specialistes: 1,
    animateurId: 'a1',
    nom: 'Alice Martin',
    renforts: 0,
    pourvu: true,
    severite: 'CRITIQUE',
    ...partial,
  };
}

async function hrefs(view: string): Promise<string[]> {
  await TestBed.inject(Router).navigateByUrl(view ? `/?vue=${view}` : '/');
  const fixture = TestBed.createComponent(FragilitePage);
  await fixture.whenStable();
  fixture.detectChanges();
  return Array.from(
    (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLAnchorElement>('a.fragilite-lien'),
  ).map((link) => link.getAttribute('href') ?? '');
}

/**
 * The links out of a fragile row (issue #489): a person leads to their fiche
 * and nowhere else, a scarce competence to the animateurs holding it.
 */
describe('FragilitePage contextual links', () => {
  const analysesApi = { fragility: vi.fn() };

  beforeEach(() => {
    analysesApi.fragility.mockReset();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: AnalysesApi, useValue: analysesApi },
        referentiel(),
      ],
    });
  });

  it('leads from a person to their fiche, and never to the timeline screen', async () => {
    analysesApi.fragility.mockResolvedValue(
      rapport({ animateurs: [animateur()], animateursIrremplacables: 1 }),
    );
    await TestBed.inject(Router).navigateByUrl('/');
    const fixture = TestBed.createComponent(FragilitePage);
    await fixture.whenStable();
    fixture.detectChanges();

    const root = fixture.nativeElement as HTMLElement;
    expect(root.querySelector('a.fragilite-nom')!.getAttribute('href')).toBe('/animateurs/a1');
    expect(root.querySelector('a[href^="/timeline"]')).toBeNull();
    // No « on trial » banner any more: the screen is here to stay.
    expect(root.querySelector('app-work-in-progress-banner')).toBeNull();
  });

  it('leads from a scarce competence to the animateurs holding its typologies', async () => {
    analysesApi.fragility.mockResolvedValue(
      rapport({ competencesRares: [competence()], totalCompetencesRares: 1 }),
    );

    expect(await hrefs('COMPETENCES')).toEqual(['/animateurs?typologie=ESCAPE,QUIZ']);
  });

  it('offers no animateurs link for a stand carrying no typologie', async () => {
    analysesApi.fragility.mockResolvedValue(
      rapport({ competencesRares: [competence({ typologies: [] })], totalCompetencesRares: 1 }),
    );

    expect(await hrefs('COMPETENCES')).toEqual([]);
  });
});

describe('FragilitePage typologie names', () => {
  const analysesApi = { fragility: vi.fn() };

  beforeEach(() => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: AnalysesApi, useValue: analysesApi },
        referentiel([{ id: 'T1', label: 'Escape game' }]),
      ],
    });
    analysesApi.fragility.mockResolvedValue(
      rapport({
        competencesRares: [competence({ typologies: ['T1', 'T9'] })],
        totalCompetencesRares: 1,
      }),
    );
  });

  it('shows a scarce competence by typologie label, an unknown id kept as-is', async () => {
    await TestBed.inject(Router).navigateByUrl('/?vue=COMPETENCES');
    const fixture = TestBed.createComponent(FragilitePage);
    await fixture.whenStable();
    fixture.detectChanges();

    const cell = (fixture.nativeElement as HTMLElement).querySelector('tbody td:nth-of-type(2)');
    expect(cell?.textContent).toContain('Escape game, T9');
    expect(cell?.textContent).not.toContain('T1');
  });
});

/**
 * The three gestures of a fragile person: keep their schedule, find who could
 * stand in on the seat they would leave hardest to fill, train somebody on
 * the game categories of their stands.
 */
describe('FragilitePage gestures', () => {
  const analysesApi = { fragility: vi.fn() };
  const poste = {
    standId: 'S1',
    standNom: 'Escape',
    creneauId: 12,
    date: '2026-07-08',
    jour: 1,
    heureDebut: '10:00:00',
    heureFin: '12:00:00',
    effectifMin: 1,
    couverturePause: false,
    siegesRequis: 1,
    siegesPourvus: 1,
    siegesLiberes: 1,
    remplacants: 0,
    irremplacable: true,
  };

  beforeEach(() => {
    TestBed.resetTestingModule();
    verrous.create.mockClear();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: AnalysesApi, useValue: analysesApi },
        referentiel([], [{ id: 'S1', typologiesProposees: ['QUIZ', 'ESCAPE'] }]),
      ],
    });
    analysesApi.fragility.mockResolvedValue(
      rapport({ animateurs: [animateur({ postes: [poste] })], animateursIrremplacables: 1 }),
    );
  });

  async function render(): Promise<HTMLElement> {
    await TestBed.inject(Router).navigateByUrl('/');
    const fixture = TestBed.createComponent(FragilitePage);
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('opens the Siège panel on the seat nobody could take over, and the competences to train', async () => {
    const root = await render();
    const hrefs = Array.from(root.querySelectorAll<HTMLAnchorElement>('.fragilite-gestes a')).map(
      (link) => link.getAttribute('href'),
    );

    expect(hrefs).toEqual([
      '/journee?creneau=12&stand=S1&animateur=a1',
      '/competences?typologies=ESCAPE,QUIZ',
    ]);
  });

  it("locks the person's whole schedule", async () => {
    const root = await render();
    const button = root.querySelector<HTMLButtonElement>('.fragilite-gestes button')!;

    expect(button.getAttribute('aria-label')).toBe('Verrouiller le planning de Alice Martin');
    button.click();
    await vi.waitFor(() =>
      expect(verrous.create).toHaveBeenCalledWith({ type: 'ANIMATEUR', animateurId: 'a1' }),
    );
  });
});
