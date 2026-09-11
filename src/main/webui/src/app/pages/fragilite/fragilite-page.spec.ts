// Loading / error / refresh states of the fragility screen, now that they are
// a resource's rather than three signals written by hand. `fragilite.spec.ts`
// covers the pure filtering; what is pinned here is that a refresh keeps the
// report on screen, and that a failure shows a sentence and not a blank card.

import { Location } from '@angular/common';
import { provideZonelessChangeDetection, Signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AnalysesApi } from '../../core/api/analyses-api';
import { RapportFragilite } from '../../core/models';
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
