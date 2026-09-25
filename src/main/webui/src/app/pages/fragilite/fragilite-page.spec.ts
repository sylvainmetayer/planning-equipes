// Loading / error / refresh states of the fragility screen, now that they are
// a resource's rather than three signals written by hand. `fragilite.spec.ts`
// covers the pure filtering; what is pinned here is that a refresh keeps the
// report on screen, and that a failure shows a sentence and not a blank card.

import { Location } from '@angular/common';
import { provideZonelessChangeDetection, Signal, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter, Router } from '@angular/router';
import {
  AnimateurFragilite,
  CompetenceRare,
  RapportFragilite,
  TypologieItem,
} from '../../core/models';
import { ReferenceDataStore } from '../../core/reference-data.store';
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

/** The referential the page names typologies from; reloading it is a no-op here. */
function referentiel(typologies: Partial<TypologieItem>[] = []): {
  provide: typeof ReferenceDataStore;
  useValue: unknown;
} {
  return {
    provide: ReferenceDataStore,
    useValue: {
      typologies: signal(typologies as TypologieItem[]),
      reload: vi.fn(async () => undefined),
    },
  };
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
 * The links out of a fragile row (issue #489): an irreplaceable person leads
 * to their timeline, a scarce competence to the animateurs holding it.
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

  it('leads from an irreplaceable person to their timeline', async () => {
    analysesApi.fragility.mockResolvedValue(
      rapport({ animateurs: [animateur()], animateursIrremplacables: 1 }),
    );

    expect(await hrefs('')).toEqual(['/timeline?animateur=a1']);
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
