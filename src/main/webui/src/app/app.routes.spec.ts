import { Component, provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Title } from '@angular/platform-browser';
import {
  ActivatedRouteSnapshot,
  convertToParamMap,
  RedirectFunction,
  RouterStateSnapshot,
  ResolveFn,
  Router,
  Routes,
  TitleStrategy,
  UrlTree,
  provideRouter,
} from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { describe, expect, it } from 'vitest';

import {
  benchTabToJournee,
  equityFicheUrl,
  parametresOngletsDeplaces,
  redirectConstraintsToRegles,
  standEditToFicheUrl,
  routes,
} from './app.routes';
import { BRANDING } from './core/branding';
import { BrandingTitleStrategy } from './core/branding-title.strategy';

@Component({ template: '' })
class PageVide {}

/**
 * Every route of the tree, children included.
 *
 * <p>Does not descend into `loadChildren`: there is none today, and resolving
 * one would mean loading the modules. The day one arrives, the "whole tree"
 * guarantee stops holding without anything saying so.</p>
 */
function allRoutes(list: Routes): Routes {
  return list.flatMap((route) => [route, ...allRoutes(route.children ?? [])]);
}

/** Where the legacy route `path` redirects, given these query params. */
function redirectTarget(
  path: string,
  queryParams: Record<string, string>,
  fragment: string | null = null,
): string {
  const route = allRoutes(routes).find((candidate) => candidate.path === path);
  const redirectTo = route?.redirectTo as RedirectFunction;
  return redirectTo({ queryParams, fragment } as unknown as ActivatedRouteSnapshot) as string;
}

/**
 * Les titres de route étaient des chaînes en dur, moitié françaises moitié
 * anglaises, qu'aucun contrôle ne voyait : `check-i18n` ne lit que ce qui passe
 * par `$localize`. Ce n'est pas cosmétique — `admin-shell` annonce
 * `title.getTitle()` au lecteur d'écran à chaque navigation, donc la moitié des
 * écrans s'annonçait dans une langue que l'utilisateur ne lit pas.
 *
 * Deux choses sont vérifiées ici, et la seconde est celle qui manquait : que le
 * routeur résout bien un titre **fonction** et que la stratégie de marque le
 * reçoit. La spec de `BrandingTitleStrategy` simule `buildTitle`, donc rien ne
 * couvrait ce passage-là — un titre fonction qui ne serait jamais appelé
 * viderait chaque onglet sans faire échouer un seul test.
 */
describe('app.routes', () => {
  /**
   * Une route qui affiche un écran terminal doit porter un titre : sans lui
   * l'onglet ne montre que le nom du produit, et `admin-shell` n'annonce rien à
   * l'arrivée sur la page (son `annoncerNavigation` sort sur `if (titre)`).
   *
   * <p>Les coquilles de mise en page — le shell admin et celui de l'espace
   * animateur — en sont exemptes : elles portent des enfants, et ce sont eux qui
   * nomment l'écran.</p>
   */
  it('donne un titre à toute route qui affiche un écran terminal', () => {
    const untitled = allRoutes(routes)
      .filter((route) => route.component !== undefined || route.loadComponent !== undefined)
      .filter((route) => route.children === undefined || route.children.length === 0)
      .filter((route) => route.title === undefined)
      .map((route) => route.path ?? '(vide)');

    expect(untitled).toEqual([]);
  });

  it('donne un titre traduisible et non vide à chaque route qui en porte un', () => {
    const titrees = allRoutes(routes).filter((route) => route.title !== undefined);

    // Le compte exact plutôt qu'un plancher : un plancher laisse supprimer six
    // titres sans rien dire, et c'est ce chiffre-là que les descriptions de PR
    // annonçaient de travers.
    expect(titrees).toHaveLength(40);
    for (const route of titrees) {
      // Une fonction, et non une chaîne : c'est ce qui permet au titre de
      // passer par $localize sans être évalué au chargement du module, avant
      // que les traductions ne soient chargées.
      expect(typeof route.title, `route ${route.path}`).toBe('function');
      const resolu = (route.title as ResolveFn<string>)(undefined as never, undefined as never);
      expect(resolu, `route ${route.path}`).toBeTruthy();
    }
  });

  it("résout un titre fonction jusqu'au titre du document", async () => {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([{ path: 'essai', title: () => 'Titre résolu', component: PageVide }]),
        {
          provide: BRANDING,
          useValue: { productName: 'Produit', organisation: '', logoUrl: '', accentColor: '' },
        },
        { provide: TitleStrategy, useClass: BrandingTitleStrategy },
      ],
    });

    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/essai');

    expect(TestBed.inject(Title).getTitle()).toBe('Titre résolu — Produit');
  });

  /**
   * The former addresses of the day's renderings redirect to the page, query
   * params included: the key renamed when the rendering owns another one, and
   * the value translated when it changed along with the key.
   */
  describe('les anciennes adresses des rendus de la journée', () => {
    it('garde les filtres et renomme la clé du rail', () => {
      expect(redirectTarget('rail-jour', { vue: 'libres', stand: 'S1' })).toBe(
        '/journee?vue=rail&lignes=libres&stand=S1',
      );
    });

    it('traduit la vue « sans relais » des pauses en son filtre, et le jour en date', () => {
      // `/pauses` always wrote an ISO date in `jour`: that is the bookmark the
      // redirect has to read back, not a day number.
      expect(redirectTarget('pauses', { vue: 'sans-relais', jour: '2026-08-02' })).toBe(
        '/journee?vue=pauses&relais=sans&date=2026-08-02',
      );
    });

    it('mène au calendrier sans paramètre superflu', () => {
      expect(redirectTarget('day-calendar', {})).toBe('/journee?vue=calendrier');
    });

    // #712: the month calendar, the intendance and the graph became the
    // Planning page's day selector, its breaks and meals, and its map.
    it('sends the three screens the Planning page absorbed there, params kept', () => {
      expect(
        redirectTarget('calendar', { date: '2026-09-05', month: '2026-09', stand: 'S1' }),
      ).toBe('/journee?date=2026-09-05&stand=S1');
      expect(redirectTarget('calendar', {})).toBe('/journee');
      expect(redirectTarget('intendance', {})).toBe('/journee?vue=pauses');
      expect(redirectTarget('graphe', { q: 'x' })).toBe('/journee?vue=carte&q=x');
    });

    // #713: six screens became the two grids and the table of the page.
    it('sends the Heatmap to the stand or the person axis, as its mode said', () => {
      expect(redirectTarget('heatmap', { q: 'tir' })).toBe('/journee?axe=stand&q=tir');
      expect(redirectTarget('heatmap', { view: 'animateur' })).toBe('/journee?axe=personne');
    });

    it('sends the rest days, the hours and the equity to the person axis, their state kept', () => {
      expect(
        redirectTarget('repos', { vue: 'frise', densite: 'confort', date: '2026-09-05', q: 'a' }),
      ).toBe('/journee?axe=personne&vue=frise&densite=confort&date=2026-09-05&q=a');
      expect(redirectTarget('hours', { sort: 'nuit', dir: 'desc' })).toBe(
        '/journee?axe=personne&sort=heuresNuit&dir=desc',
      );
      expect(redirectTarget('equite', { q: 'ali', sort: 'heuresSoiree', dir: 'asc' })).toBe(
        '/journee?axe=personne&q=ali&sort=heuresSoiree&dir=asc',
      );
    });

    it("sends one person's equity to their fiche, where the radar went", () => {
      expect(
        redirectTarget('equite', { vue: 'fiche', animateur: 'A7', axes: 'x', comparer: 'B2' }),
      ).toBe('/animateurs/A7?section=equite&axes=x&comparer=B2');
      expect(redirectTarget('equite', { vue: 'fiche', animateur: 'A7' })).toBe(
        '/animateurs/A7?section=equite',
      );
      expect(redirectTarget('equite', { vue: 'fiche' })).toBe('/journee?axe=personne');
    });

    it('sends the treemap to the stand axis, its day under a key of its own', () => {
      expect(
        redirectTarget('repartition-heures', { date: '2026-09-05', regroupement: 'typologie' }),
      ).toBe('/journee?axe=stand&vue=treemap&jourTreemap=2026-09-05&regroupement=typologie');
      expect(redirectTarget('typologies-planning', {})).toBe('/journee?axe=typologie');
    });

    // The two addresses carrying the most params: the map's cursor, and the
    // créneau and stand pair of the bench.
    it('garde le jour et le curseur de la carte', () => {
      expect(redirectTarget('carte-jour', { jour: '2026-08-02', t: '540' })).toBe(
        '/journee?vue=carte&jour=2026-08-02&t=540',
      );
    });

    it("mène aux onglets du diagnostic avec l'état de chacun", () => {
      expect(
        redirectTarget('fragilite', { vue: 'COMPETENCES', filtre: 'CRITIQUES', q: 'Alice' }),
      ).toBe('/diagnostic?onglet=fragilite&vue=COMPETENCES&filtre=CRITIQUES&q=Alice');
    });

    // The bench became the Siège panel of the Journée: both of its addresses
    // land there, the timeslot and the stand kept for the page to resolve.
    it('sends the bench to the Journée, timeslot and stand kept', () => {
      expect(redirectTarget('banc-de-touche', { creneau: '12', stand: 'S1' })).toBe(
        '/journee?creneau=12&stand=S1',
      );
      expect(redirectTarget('banc-de-touche', {})).toBe('/journee');
    });

    // The locations became the « Lieux » tab of the Stands page.
    it('sends the locations to the Lieux tab of the Stands page, their params kept', () => {
      expect(redirectTarget('emplacements', {})).toBe('/stands?onglet=lieux');
      expect(redirectTarget('emplacements', { edit: 'L1' })).toBe('/stands?onglet=lieux&edit=L1');
    });

    // `?edit=` on the stands: the stand has a page, which opens with its identity form.
    it('sends a stand named by `?edit=` to its fiche, form open, and leaves the Lieux tab its own', () => {
      expect(standEditToFicheUrl({ edit: 'S 1' })).toBe('/stands/S%201?modifier=1');
      expect(standEditToFicheUrl({})).toBeNull();
      expect(standEditToFicheUrl({ edit: ' ' })).toBeNull();
      expect(standEditToFicheUrl({ onglet: 'lieux', edit: 'L1' })).toBeNull();
    });

    // The timeline became the « Planning » section of the fiche animateur.
    it('sends the timeline to the fiche of its person, section open, or to the list without one', () => {
      expect(redirectTarget('timeline', { animateur: 'A 7' })).toBe(
        '/animateurs/A%207?section=timeline',
      );
      expect(redirectTarget('timeline', {})).toBe('/animateurs');
      expect(redirectTarget('timeline', { animateur: ' ' })).toBe('/animateurs');
    });

    // The « Fiche » reading of Équité became the fiche's « Charge et équité » section.
    it('sends the Fiche reading of Équité to the fiche, its radar kept, and nothing else', () => {
      expect(
        equityFicheUrl({
          vue: 'fiche',
          animateur: 'A 7',
          axes: 'heuresJourFerie',
          comparer: 'a2',
          q: 'x',
        }),
      ).toBe('/animateurs/A%207?section=equite&axes=heuresJourFerie&comparer=a2');
      expect(equityFicheUrl({ vue: 'fiche', animateur: 'a1' })).toBe(
        '/animateurs/a1?section=equite',
      );
      expect(equityFicheUrl({ vue: 'fiche', sort: 'heuresTotal', dir: 'desc' })).toBeNull();
      expect(equityFicheUrl({ vue: 'fiche', animateur: ' ' })).toBeNull();
      expect(equityFicheUrl({ q: 'Alice' })).toBeNull();
    });

    it('sends the former bench tab of the Diagnostic to the Journée, and no other tab', () => {
      TestBed.configureTestingModule({
        providers: [provideZonelessChangeDetection(), provideRouter([])],
      });
      const guard = (queryParams: Record<string, string>): unknown =>
        TestBed.runInInjectionContext(() =>
          benchTabToJournee(
            {
              queryParams,
              queryParamMap: convertToParamMap(queryParams),
            } as unknown as ActivatedRouteSnapshot,
            {} as RouterStateSnapshot,
          ),
        );

      expect(String(guard({ onglet: 'banc', creneau: '12', stand: 'S1' }))).toBe(
        '/journee?creneau=12&stand=S1',
      );
      expect(guard({ onglet: 'fragilite' })).toBe(true);
      expect(guard({})).toBe(true);
    });
  });

  /**
   * Imports, Export and the validator became one Fichiers page: each former
   * address lands on its tab, the Imports page's own tab on its card.
   */
  describe('les anciennes adresses de Fichiers', () => {
    it("mène d'Imports à la carte de l'onglet Importer qu'il nommait", () => {
      expect(redirectTarget('imports', { onglet: 'stands' })).toBe(
        '/fichiers?onglet=importer&cible=stands',
      );
      expect(redirectTarget('imports', {})).toBe('/fichiers?onglet=importer');
    });

    it("mène d'Export à l'onglet Exporter, ou à l'archive par son ancien fragment", () => {
      expect(redirectTarget('exports', {})).toBe('/fichiers?onglet=exporter');
      expect(redirectTarget('export-csv', {})).toBe('/fichiers?onglet=exporter');
      expect(redirectTarget('exports', {}, 'archive-evenement')).toBe('/fichiers?onglet=archive');
    });

    it('mène du validateur à sa carte', () => {
      expect(redirectTarget('validateur-yaml', {})).toBe(
        '/fichiers?onglet=importer&cible=verifier',
      );
    });
  });

  /** Addresses a guard sends elsewhere, followed through the router. */
  describe('les adresses que la page ne sert plus', () => {
    async function harness(paths: string[]): Promise<RouterTestingHarness> {
      TestBed.configureTestingModule({
        providers: [
          provideZonelessChangeDetection(),
          provideRouter(
            routes
              .flatMap((route) => route.children ?? [])
              .filter((route) => paths.includes(route.path ?? ''))
              .map((route) => ({
                ...route,
                loadComponent: undefined,
                canDeactivate: undefined,
                // A redirect keeps its target and takes no component.
                component: route.redirectTo ? undefined : PageVide,
              })),
          ),
        ],
      });
      return RouterTestingHarness.create();
    }

    // The snack bar's « Voir la fiche » names `/stands?edit=` while the table is on screen.
    it('opens the fiche of a stand named by `?edit=` from the Stands page itself', async () => {
      const router = await harness(['stands', 'stands/:id']);
      await router.navigateByUrl('/stands?sort=nom');
      await router.navigateByUrl('/stands?edit=S1');
      expect(TestBed.inject(Router).url).toBe('/stands/S1?modifier=1');
    });

    it('lands a former Fiche address of Équité on the fiche animateur, any other on the person axis', async () => {
      const router = await harness(['equite', 'animateurs/:id', 'journee']);
      await router.navigateByUrl('/equite?vue=fiche&animateur=a1&comparer=a2');
      expect(TestBed.inject(Router).url).toBe('/animateurs/a1?section=equite&comparer=a2');
      await router.navigateByUrl('/equite?q=Alice');
      expect(TestBed.inject(Router).url).toBe('/journee?axe=personne&q=Alice');
    });
  });

  /** The two tabs of Débogage that moved to Fichiers: a guard, since `/debug` itself still answers. */
  describe('les onglets du Débogage partis vers Fichiers', () => {
    async function naviguer(url: string): Promise<string> {
      TestBed.configureTestingModule({
        providers: [
          provideZonelessChangeDetection(),
          provideRouter(
            routes
              .flatMap((route) => route.children ?? [])
              .filter((route) => ['debug', 'fichiers', 'parametres'].includes(route.path ?? ''))
              .map((route) => ({ ...route, loadComponent: undefined, component: PageVide })),
          ),
        ],
      });
      const harness = await RouterTestingHarness.create();
      await harness.navigateByUrl(url);
      return TestBed.inject(Router).url;
    }

    it('envoie les scénarios livrés vers les exemples', async () => {
      expect(await naviguer('/debug?onglet=donnees')).toBe(
        '/fichiers?onglet=importer&cible=exemples',
      );
    });

    it('envoie le validateur vers sa carte', async () => {
      expect(await naviguer('/debug?onglet=yaml')).toBe('/fichiers?onglet=importer&cible=verifier');
    });

    it('sends the simulated clock field to Paramètres › Instance, where it lives now', async () => {
      expect(await naviguer('/debug?onglet=verifications&focus=date-du-jour')).toBe(
        '/parametres?onglet=instance&focus=date-du-jour',
      );
    });

    it('sert le Débogage pour tout le reste', async () => {
      expect(await naviguer('/debug?onglet=verifications')).toBe('/debug?onglet=verifications');
    });
  });

  /** Three screens became the tabs of « Consignes au solveur » (issue #719). */
  describe('les anciennes adresses des consignes au solveur', () => {
    it('mène aux ajustements filtrés sur la personne que nommait le réseau, sans le réseau', () => {
      expect(
        redirectTarget('ad-hoc-constraints', {
          vue: 'reseau',
          personne: 'Alice',
          paires: 'AFFINITE',
        }),
      ).toBe('/consignes-solveur?onglet=ajustements&personne=Alice');
      expect(redirectTarget('ad-hoc-constraints', { ids: 'A1,A2' })).toBe(
        '/consignes-solveur?onglet=ajustements&ids=A1%2CA2',
      );
    });

    it('garde les paramètres des verrouillages et des consignes', () => {
      expect(redirectTarget('verrouillages', { animateur: 'a1' })).toBe(
        '/consignes-solveur?onglet=verrouillages&animateur=a1',
      );
      expect(redirectTarget('consignes', { date: '2026-08-02', nouvelle: '1' })).toBe(
        '/consignes-solveur?onglet=consignes&date=2026-08-02&nouvelle=1',
      );
    });
  });

  /** The Autopsie, the Instantanés and the Comparateur became « Versions du plan » (issue #702). */
  it('mène les trois anciennes adresses des versions à la nouvelle page', () => {
    for (const path of ['kpi', 'instantanes', 'comparateur']) {
      expect(allRoutes(routes).find((route) => route.path === path)?.redirectTo).toBe('/versions');
    }
  });

  /** `/constraints` became « Règles du planning » (issue #720). */
  describe("l'ancienne adresse des contraintes", () => {
    function target(queryParams: Record<string, string>, fragment: string | null): string {
      return redirectConstraintsToRegles({
        queryParams,
        fragment,
      } as unknown as ActivatedRouteSnapshot) as string;
    }

    it('garde la règle demandée, que la page ouvre sur son onglet', () => {
      expect(target({ regle: 'equilibrerCharge' }, null)).toBe('/regles?regle=equilibrerCharge');
    });

    it("fait de l'ancre d'une règle la règle à ouvrir, et oublie celle d'une catégorie", () => {
      expect(target({}, 'coupureRepasObligatoire')).toBe('/regles?regle=coupureRepasObligatoire');
      expect(target({}, 'categorie-legal-mineurs')).toBe('/regles');
    });
  });

  /** Two tabs of Paramètres left the page (issue #720): the guard sends their bookmarks on. */
  describe('les onglets déplacés des paramètres', () => {
    function garde(queryParams: Record<string, string>): string | boolean {
      TestBed.resetTestingModule();
      TestBed.configureTestingModule({
        providers: [provideZonelessChangeDetection(), provideRouter([])],
      });
      const resultat = TestBed.runInInjectionContext(() =>
        parametresOngletsDeplaces(
          { queryParamMap: convertToParamMap(queryParams) } as ActivatedRouteSnapshot,
          {} as RouterStateSnapshot,
        ),
      );
      return typeof resultat === 'boolean'
        ? resultat
        : TestBed.inject(Router).serializeUrl(resultat as UrlTree);
    }

    it('envoie les paramètres légaux vers l’onglet Légal des règles', () => {
      expect(garde({ onglet: 'legaux', x: '1' })).toBe('/regles?x=1&onglet=legal');
    });

    it('envoie les e-mails automatiques vers leur section de l’onglet Édition', () => {
      expect(garde({ onglet: 'emails' })).toBe('/parametres?onglet=edition#emails');
    });

    it('laisse passer les onglets qui existent', () => {
      expect(garde({ onglet: 'instance' })).toBe(true);
      expect(garde({})).toBe(true);
    });
  });
});
