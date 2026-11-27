// Loading / error / rendering of the home checklist: a resource's states
// rather than hand-written signals, the three line states drawn with their
// label, and every line carrying a link to the screen that moves it. The
// wording itself is `accueil.spec.ts`'s business.

import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AnalysesApi } from '../../core/api/analyses-api';
import { EditionsApi } from '../../core/api/editions-api';
import { ConsignesStore } from '../../core/consignes.store';
import { EtatEdition } from '../../core/models';
import { SolverJobService } from '../../core/solver-job.service';
import { AccueilPage } from './accueil-page';

function etat(partial: Partial<EtatEdition> = {}): EtatEdition {
  return {
    editionId: 'DEFAUT',
    editionNom: 'Année 2026',
    referentiels: {
      stands: 12,
      animateurs: 40,
      creneaux: 30,
      typologiesOrphelines: 0,
      statut: 'FAIT',
    },
    gel: {
      familles: [
        { famille: 'STANDS', libelle: 'Stands', fige: true, figeLe: '2026-05-01T09:00:00Z' },
        { famille: 'CRENEAUX', libelle: 'Créneaux', fige: true, figeLe: '2026-05-01T09:00:00Z' },
      ],
      statut: 'FAIT',
    },
    coherence: { bloquants: 0, aVerifier: 0, informations: 0, statut: 'FAIT' },
    collecte: {
      ouverte: true,
      declarationsEnAttente: 3,
      declarationsTraitees: 1,
      statut: 'ATTENTION',
    },
    ouvertures: {
      anomalies: 0,
      fenetresSansEffet: 0,
      standsJamaisOuverts: 0,
      statut: 'FAIT',
    },
    besoin: { animateurs: 40, minimum: 32, manque: 0, statut: 'FAIT' },
    resolution: {
      resolue: false,
      resoluLe: null,
      score: null,
      scoreHorsPlancher: null,
      faisable: null,
      dataStale: false,
      solveEnCours: false,
      statut: 'A_FAIRE',
      lecture: [],
    },
    problemes: { bloquants: 0, avertissements: 0, reglesAnalysees: true, statut: 'FAIT' },
    relecture: { journees: 0, journeesValidees: 0, statut: 'A_FAIRE' },
    publication: {
      jamaisPublie: true,
      dernierePublicationLe: null,
      personnesAPrevenir: 0,
      envoisEnEchec: 0,
      statut: 'A_FAIRE',
    },
    confirmations: {
      confirmes: 0,
      relances: 0,
      silencieux: 0,
      echecsEnvoi: 0,
      statut: 'A_FAIRE',
      relancesAutomatiques: true,
      delaiRelanceHeures: 72,
    },
    foire: { ouverte: true, demandesEnAttente: 0, statut: 'A_FAIRE' },
    aTraiter: {
      aujourdhui: '2026-07-10',
      declarationsEnAttente: 0,
      plusAncienneDeclaration: null,
      echangesAArbitrer: 0,
      echangesEnAlerte: 0,
      seuilAncienneteJours: 7,
      plusAncienEchange: null,
      horizonJours: 7,
      journeesNonRelues: [],
      silencieuxARelancer: 0,
      silenceJours: 3,
      donneesModifiees: false,
      personnesAPrevenir: 0,
      rappelsNonEnvoyes: 0,
      relancesNonEnvoyees: 0,
      envoisEnEchec: 0,
      sauvegardeEnEchec: false,
      sauvegardeEchecLe: null,
    },
    evenement: {
      premierJour: null,
      dernierJour: null,
      termine: false,
      aujourdhui: '2026-07-10',
      phase: 'PREPARATION',
      jour: null,
    },
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

describe('AccueilPage', () => {
  const editionsApi = { etat: vi.fn(), coherence: vi.fn() };
  const analysesApi = { alerts: vi.fn(() => Promise.resolve([])) };
  const jobs = {
    onResult: vi.fn<(type: string, handler: () => void) => () => void>(() => () => undefined),
  };
  let fixture: ComponentFixture<AccueilPage>;

  beforeEach(() => {
    editionsApi.etat.mockReset();
    editionsApi.coherence.mockReset();
    jobs.onResult.mockClear();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: EditionsApi, useValue: editionsApi },
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
        { provide: SolverJobService, useValue: jobs },
        { provide: AnalysesApi, useValue: analysesApi },
      ],
    });
  });

  function createPage(): AccueilPage {
    fixture = TestBed.createComponent(AccueilPage);
    return fixture.componentInstance;
  }

  function element(): HTMLElement {
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  function text(): string {
    return element().textContent!.replace(/\s+/g, ' ');
  }

  it('says it is reading while the first state is in flight, then lists the twelve lines', async () => {
    const pending = deferred<EtatEdition>();
    editionsApi.etat.mockReturnValue(pending.promise);
    const page = createPage();

    expect(page['chargement']()).toBe(true);
    expect(text()).toContain("Lecture de l'état de l'édition");

    pending.resolve(etat());
    await vi.waitFor(() => expect(page['chargement']()).toBe(false));
    expect(element().querySelectorAll('li.accueil-ligne')).toHaveLength(12);
    expect(text()).toContain('Année 2026');
    expect(editionsApi.etat).toHaveBeenCalledOnce();
  });

  it('draws each state with its label, on the line it belongs to', async () => {
    editionsApi.etat.mockResolvedValue(etat());
    const page = createPage();
    await vi.waitFor(() => expect(page['etatEdition']()).not.toBeNull());
    const racine = element();

    const ligne = (id: string) => racine.querySelector<HTMLElement>(`li[data-ligne="${id}"]`)!;
    expect(ligne('referentiels').classList.contains('accueil-ligne-fait')).toBe(true);
    expect(ligne('referentiels').textContent).toContain('Fait');
    expect(ligne('collecte').classList.contains('accueil-ligne-attention')).toBe(true);
    expect(ligne('collecte').textContent).toContain('À vérifier');
    expect(ligne('collecte').textContent).toContain('3 déclaration(s) à appliquer ou refuser');
    expect(ligne('resolution').classList.contains('accueil-ligne-a_faire')).toBe(true);
    expect(ligne('resolution').textContent).toContain('À faire');
    expect(text()).toContain('6 étape(s) faite(s) · 1 à vérifier · 5 à faire');
    expect(text()).not.toContain('pour information');
  });

  // Warnings alone are drawn apart from what blocks: same line, its own state,
  // and the summary counts it outside « à vérifier ».
  it('draws a warning-only diagnostic as information, not as something to check', async () => {
    editionsApi.etat.mockResolvedValue(
      etat({
        problemes: { bloquants: 0, avertissements: 8, reglesAnalysees: true, statut: 'INFO' },
      }),
    );
    const page = createPage();
    await vi.waitFor(() => expect(page['etatEdition']()).not.toBeNull());

    const ligne = element().querySelector<HTMLElement>('li[data-ligne="problemes"]')!;
    expect(ligne.classList.contains('accueil-ligne-info')).toBe(true);
    expect(ligne.classList.contains('accueil-ligne-attention')).toBe(false);
    expect(ligne.textContent).toContain('Pour information');
    expect(ligne.textContent).toContain('8 avertissement(s), rien de bloquant');
    expect(text()).toContain('5 étape(s) faite(s) · 1 à vérifier · 1 pour information · 5 à faire');
  });

  it('links every line to its screen, tab and filter included', async () => {
    editionsApi.etat.mockResolvedValue(
      etat({
        confirmations: {
          confirmes: 1,
          relances: 0,
          silencieux: 2,
          echecsEnvoi: 0,
          statut: 'ATTENTION',
          relancesAutomatiques: true,
          delaiRelanceHeures: 72,
        },
      }),
    );
    const page = createPage();
    await vi.waitFor(() => expect(page['etatEdition']()).not.toBeNull());
    await fixture.whenStable();

    const hrefs = Array.from(element().querySelectorAll<HTMLAnchorElement>('a.accueil-lien')).map(
      (lien) => lien.getAttribute('href'),
    );
    expect(hrefs).toEqual([
      '/stands',
      '/animateurs?onglet=disponibilites',
      '/stands',
      '/diagnostic?onglet=besoin',
      '/journee?onglet=calculer',
      '/diagnostic?onglet=problemes',
      '/journee',
      '/publication',
      '/publication?filtre=silencieux',
      '/echanges',
    ]);
  });

  it('shows the failure as a sentence, not a blank card', async () => {
    editionsApi.etat.mockRejectedValue(new Error('Serveur injoignable.'));
    const page = createPage();

    await vi.waitFor(() => expect(page['erreur']()).toContain('Serveur injoignable.'));
    expect(page['etatEdition']()).toBeNull();
    expect(text()).toContain('Serveur injoignable.');
    expect(element().querySelectorAll('li.accueil-ligne')).toHaveLength(0);
  });

  it('keeps the checklist on screen behind the failure of a refresh', async () => {
    editionsApi.etat.mockResolvedValueOnce(etat());
    const page = createPage();
    await vi.waitFor(() => expect(page['etatEdition']()).not.toBeNull());

    editionsApi.etat.mockRejectedValueOnce(new Error('Serveur injoignable.'));
    page['recharger']();
    await vi.waitFor(() => expect(page['erreur']()).toContain('Serveur injoignable.'));
    expect(element().querySelectorAll('li.accueil-ligne')).toHaveLength(12);
  });

  // Both kinds of solve: « Corriger après un changement » rewrites the plan and
  // its score just as a full solve does, and a checklist that ignores it stays
  // wrong until an F5.
  it('reloads once either kind of solve lands, and lets go of the hooks with the page', async () => {
    editionsApi.etat.mockResolvedValue(etat());
    const unregister = vi.fn();
    jobs.onResult.mockReturnValue(unregister);
    const page = createPage();
    await vi.waitFor(() => expect(page['etatEdition']()).not.toBeNull());

    expect(jobs.onResult.mock.calls.map(([type]) => type)).toEqual(['SOLVE', 'SOLVE_INCREMENTAL']);
    const [[, surSolve], [, surIncremental]] = jobs.onResult.mock.calls;
    surSolve();
    await vi.waitFor(() => expect(editionsApi.etat).toHaveBeenCalledTimes(2));
    surIncremental();
    await vi.waitFor(() => expect(editionsApi.etat).toHaveBeenCalledTimes(3));

    fixture.destroy();
    expect(unregister).toHaveBeenCalledTimes(2);
  });

  // The sentence naming a minor is shown, never logged (AVERTISSEMENTS_HORS_JOURNAL):
  // the panel reads it from the API and writes nothing to the browser's storage.
  it('writes nothing to localStorage, the line about a minor included', async () => {
    editionsApi.etat.mockResolvedValue(
      etat({ coherence: { bloquants: 0, aVerifier: 0, informations: 1, statut: 'INFO' } }),
    );
    editionsApi.coherence.mockResolvedValue({
      bloquants: 0,
      aVerifier: 0,
      informations: 1,
      familles: [{ famille: 'ANIMATEURS', bloquants: 0, aVerifier: 0, informations: 1 }],
      anomalies: [
        {
          famille: 'ANIMATEURS',
          gravite: 'INFORMATION',
          code: 'MINEUR_PENDANT_EVENEMENT',
          message: "L'animateur a-12 sera mineur pendant l'événement.",
          objet: 'ANIMATEUR',
          objetId: 'a-12',
          date: null,
        },
      ],
    });
    const setItem = vi.spyOn(Storage.prototype, 'setItem');
    try {
      const page = createPage();
      await vi.waitFor(() => expect(page['etatEdition']()).not.toBeNull());
      await fixture.whenStable();
      await vi.waitFor(() => expect(text()).toContain('sera mineur pendant'));
      expect(setItem).not.toHaveBeenCalled();
    } finally {
      setItem.mockRestore();
    }
  });

  // The phase is moved on the home screen itself: the line unfolds the control,
  // with no link out and no second editable card.
  it('unfolds the phase control under its line', async () => {
    editionsApi.etat.mockResolvedValue(etat());
    const page = createPage();
    await vi.waitFor(() => expect(page['etatEdition']()).not.toBeNull());
    await fixture.whenStable();

    const ligne = element().querySelector<HTMLElement>('li[data-ligne="phase"]')!;
    expect(ligne.querySelector('a')).toBeNull();
    expect(ligne.querySelector('app-phase-edition-control')).not.toBeNull();
  });

  // The coherence line counts, and leads to the feed narrowed to its type;
  // the feed lists each anomaly with the fiche that fixes it.
  it('leads the coherence line to the feed, which lists the anomalies with their fiche', async () => {
    editionsApi.etat.mockResolvedValue(
      etat({ coherence: { bloquants: 0, aVerifier: 2, informations: 0, statut: 'ATTENTION' } }),
    );
    const horsOuverture = {
      famille: 'CRENEAUX' as const,
      gravite: 'A_VERIFIER' as const,
      code: 'CRENEAU_HORS_OUVERTURE_STANDS',
      message: "Aucun des 2 stands de l'édition n'est ouvert pendant le créneau.",
      objet: 'CRENEAU' as const,
      objetId: '7',
      date: null,
    };
    editionsApi.coherence.mockResolvedValue({
      bloquants: 0,
      aVerifier: 2,
      informations: 0,
      familles: [{ famille: 'CRENEAUX', bloquants: 0, aVerifier: 2, informations: 0 }],
      anomalies: [horsOuverture, { ...horsOuverture, objetId: '8' }],
    });
    const page = createPage();
    await vi.waitFor(() => expect(page['etatEdition']()).not.toBeNull());
    await fixture.whenStable();
    await vi.waitFor(() => expect(editionsApi.coherence).toHaveBeenCalledOnce());

    const lien = element().querySelector<HTMLAnchorElement>(
      'li[data-ligne="coherence"] a.accueil-lien',
    )!;
    expect(lien.getAttribute('href')).toBe('/?type=coherence#a-traiter');
    // The old unfolded panel is gone.
    expect(element().querySelector('#accueil-coherence-panneau')).toBeNull();

    const fil = element().querySelector<HTMLElement>('app-fil-accueil')!;
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fil.textContent).toContain("n'est ouvert pendant le créneau");
    });
    expect(fil.querySelector('li[data-type="coherence"] a')!.getAttribute('href')).toBe(
      '/creneaux?edit=7',
    );
  });

  it("keeps « À traiter aujourd'hui » where the bell lands, its list only when something waits", async () => {
    editionsApi.etat.mockResolvedValueOnce(etat());
    const page = createPage();
    await vi.waitFor(() => expect(page['etatEdition']()).not.toBeNull());
    expect(element().querySelector('#a-traiter')).not.toBeNull();
    expect(element().querySelector('.accueil-a-traiter-liste')).toBeNull();
    expect(text()).toContain("Rien n'attend de décision aujourd'hui.");
    expect(element().querySelector('app-fil-accueil')).not.toBeNull();

    editionsApi.etat.mockResolvedValueOnce(
      etat({ aTraiter: { ...etat().aTraiter, echangesAArbitrer: 2, echangesEnAlerte: 1 } }),
    );
    page['recharger']();
    await vi.waitFor(() =>
      expect(element().querySelector('.accueil-a-traiter-liste')).not.toBeNull(),
    );
    const sujet = element().querySelector<HTMLElement>('li[data-sujet="echanges"]')!;
    expect(sujet.classList.contains('accueil-a-traiter-alerte')).toBe(true);
    // Said, not only coloured: the icon is decorative.
    expect(sujet.querySelector('.visually-hidden')!.textContent).toBe('Alerte : ');
    expect(sujet.querySelector('a')!.getAttribute('href')).toBe('/echanges?statut=a-arbitrer');
    expect(text()).toContain("À traiter aujourd'hui");
  });

  it('offers to archive the edition once its event is over, and only then', async () => {
    editionsApi.etat.mockResolvedValueOnce(etat());
    const page = createPage();
    await vi.waitFor(() => expect(page['etatEdition']()).not.toBeNull());
    expect(element().querySelector('[data-bloc="archive"]')).toBeNull();

    editionsApi.etat.mockResolvedValueOnce(
      etat({
        evenement: {
          premierJour: '2026-07-04',
          dernierJour: '2026-07-08',
          termine: true,
          aujourdhui: '2026-07-10',
          phase: 'APRES',
          jour: null,
        },
      }),
    );
    page['recharger']();
    await vi.waitFor(() => expect(element().querySelector('[data-bloc="archive"]')).not.toBeNull());
    const lien = element().querySelector('[data-bloc="archive"] a')!;
    expect(lien.textContent).toContain("Archiver l'édition");
    expect(lien.getAttribute('href')).toBe('/fichiers?onglet=archive');
  });

  it('offers three ways to start on an empty edition, and never the Débogage', async () => {
    editionsApi.etat.mockResolvedValue(
      etat({
        referentiels: {
          stands: 0,
          animateurs: 0,
          creneaux: 0,
          typologiesOrphelines: 0,
          statut: 'A_FAIRE',
        },
      }),
    );
    const page = createPage();
    await vi.waitFor(() => expect(page['etatEdition']()).not.toBeNull());
    await fixture.whenStable();

    const bloc = element().querySelector<HTMLElement>('[data-bloc="demarrer"]')!;
    expect(Array.from(bloc.querySelectorAll('a')).map((lien) => lien.getAttribute('href'))).toEqual(
      ['/fichiers', '/editions', '/fichiers?onglet=importer&cible=exemples'],
    );
    expect(element().querySelectorAll('li.accueil-ligne')).toHaveLength(0);
    expect(text()).not.toContain('Débogage');
  });

  it('opens the event on the day under way, the checklist folded', async () => {
    editionsApi.etat.mockResolvedValue(
      etat({
        evenement: {
          premierJour: '2026-07-06',
          dernierJour: '2026-07-20',
          termine: false,
          aujourdhui: '2026-07-10',
          phase: 'EVENEMENT',
          jour: {
            date: '2026-07-10',
            numero: 5,
            standsOuverts: 60,
            placesVides: 23,
            absents: 0,
            echangesAArbitrer: 2,
          },
        },
      }),
    );
    const page = createPage();
    await vi.waitFor(() => expect(page['etatEdition']()).not.toBeNull());
    await fixture.whenStable();

    const jour = element().querySelector<HTMLElement>('[data-bloc="jour"]')!;
    expect(jour.querySelector('a.accueil-jour-lien')!.getAttribute('href')).toBe('/aujourdhui');
    expect(jour.textContent).toContain('J5');
    expect(jour.textContent).toContain('Afficher sur la TV');
    expect(element().querySelectorAll('li.accueil-ligne')).toHaveLength(0);

    element().querySelector<HTMLButtonElement>('button.accueil-checklist-bascule')!.click();
    await fixture.whenStable();
    expect(element().querySelectorAll('li.accueil-ligne')).toHaveLength(12);
  });

  /** The checklist of the referential is read with the page, and follows a solve. */
  it('reads the coherence report again when a solve lands', async () => {
    editionsApi.etat.mockResolvedValue(
      etat({ coherence: { bloquants: 0, aVerifier: 1, informations: 0, statut: 'ATTENTION' } }),
    );
    editionsApi.coherence.mockResolvedValue({
      bloquants: 0,
      aVerifier: 1,
      informations: 0,
      familles: [],
      anomalies: [],
    });
    const page = createPage();
    await vi.waitFor(() => expect(page['etatEdition']()).not.toBeNull());
    await fixture.whenStable();
    await vi.waitFor(() => expect(editionsApi.coherence).toHaveBeenCalledOnce());
    const [[, surSolve]] = jobs.onResult.mock.calls;

    surSolve();
    await vi.waitFor(() => expect(editionsApi.etat).toHaveBeenCalledTimes(2));
    await vi.waitFor(() => expect(editionsApi.coherence).toHaveBeenCalledTimes(2));
  });

  /**
   * « Voir qui » while the address already names the section: the router
   * moves the feed to the alerts, and the page scrolls to it itself.
   */
  it("scrolls to the feed, narrowed to the alerts, when their line is followed from the bell's anchor", async () => {
    const scroll = vi.fn();
    Element.prototype.scrollIntoView = scroll;
    await TestBed.inject(Router).navigateByUrl('/#a-traiter');
    editionsApi.etat.mockResolvedValue(
      etat({ aTraiter: { ...etat().aTraiter, rappelsNonEnvoyes: 1 } }),
    );
    const page = createPage();
    await vi.waitFor(() => expect(page['etatEdition']()).not.toBeNull());
    await fixture.whenStable();
    scroll.mockClear();

    const lien = element().querySelector<HTMLAnchorElement>('li[data-sujet="rappels"] a')!;
    expect(lien.getAttribute('href')).toBe('/?type=alertes#a-traiter');
    lien.click();
    await fixture.whenStable();

    await vi.waitFor(() => expect(scroll).toHaveBeenCalled());
    expect(TestBed.inject(Router).url).toBe('/?type=alertes#a-traiter');
    await TestBed.inject(Router).navigateByUrl('/');
  });
});
