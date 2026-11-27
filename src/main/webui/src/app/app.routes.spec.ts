import { Component, provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Title } from '@angular/platform-browser';
import {
  ActivatedRouteSnapshot,
  CanActivateFn,
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
  planningFormerViews,
  planningFormerViewUrl,
  redirectCalculToParametres,
  redirectConstraintsToRegles,
  redirectEditionComparison,
  redirectFormerRealiseTab,
  redirectKpiToVersions,
  openingsToStandsUrl,
  standEditToFicheUrl,
  routes,
  formerTabsToRisques,
} from './app.routes';
import { BRANDING } from './core/branding';
import { MesuresPage } from './pages/mesures/mesures-page';
import { ResponsablePage } from './pages/responsable/responsable-page';
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
  const route = allRoutes(routes).find(
    (candidate) => candidate.path === path && candidate.redirectTo,
  );
  const redirectTo = route?.redirectTo as RedirectFunction | string;
  return typeof redirectTo === 'string'
    ? redirectTo
    : (redirectTo(snapshotOf(queryParams, fragment)) as string);
}

/**
 * The snapshot a guard or a redirect receives, holding these query params and
 * this fragment — what the functions under test read of it.
 */
function snapshotOf(
  queryParams: Record<string, string>,
  fragment: string | null = null,
): ActivatedRouteSnapshot {
  return Object.assign(new ActivatedRouteSnapshot(), { queryParams, fragment });
}

function harness(paths: string[]): Promise<RouterTestingHarness> {
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
  const router = await RouterTestingHarness.create();
  await router.navigateByUrl(url);
  return TestBed.inject(Router).url;
}

function constraintsTarget(queryParams: Record<string, string>, fragment: string | null): string {
  return redirectConstraintsToRegles(snapshotOf(queryParams, fragment)) as string;
}

function kpiTarget(queryParams: Record<string, string>): string {
  return redirectKpiToVersions(snapshotOf(queryParams)) as string;
}

function garde(
  queryParams: Record<string, string>,
  fragment: string | null = null,
  guard: CanActivateFn = parametresOngletsDeplaces,
): string | boolean {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [provideZonelessChangeDetection(), provideRouter([])],
  });
  const resultat = TestBed.runInInjectionContext(() =>
    guard(
      { queryParamMap: convertToParamMap(queryParams), fragment } as ActivatedRouteSnapshot,
      {} as RouterStateSnapshot,
    ),
  );
  return typeof resultat === 'boolean'
    ? resultat
    : TestBed.inject(Router).serializeUrl(resultat as UrlTree);
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

  it('gives a translatable, non-empty title to every route that carries one', () => {
    const titrees = allRoutes(routes).filter((route) => route.title !== undefined);

    // Le compte exact plutôt qu'un plancher : un plancher laisse supprimer six
    // titres sans rien dire, et c'est ce chiffre-là que les descriptions de PR
    // annonçaient de travers.
    // `/marge` lost its title when it became two tabs of the Diagnostic;
    // `/mesures` (Mesures d'édition, the realised and the indicators) brought one, `/comparer` another;
    // the nine screens that became tabs of another page gave theirs up;
    // `/mot-de-passe`, the page of a mailed link, and `/responsable` (« Mon stand »)
    // brought one each, and `/rh` (« Conformité RH ») and `/animateur/:jeton/contacts` one more each;
    // `/solveur` gave its own up when it became the Planning's « Calculer » tab.
    expect(titrees).toHaveLength(36);
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideRouter([])],
    });
    const state = TestBed.inject(Router).routerState.snapshot;
    for (const route of titrees) {
      // Une fonction, et non une chaîne : c'est ce qui permet au titre de
      // passer par $localize sans être évalué au chargement du module, avant
      // que les traductions ne soient chargées.
      expect(typeof route.title, `route ${route.path}`).toBe('function');
      const resolu = (route.title as ResolveFn<string>)(state.root, state);
      expect(resolu, `route ${route.path}`).toBeTruthy();
    }
  });

  it('lazy-loads « Mon stand » from the responsable route', async () => {
    const route = routes.find((r) => r.path === 'responsable');
    const component = await (route?.loadComponent as () => Promise<unknown>)();
    expect(component).toBe(ResponsablePage);
  });

  it("lazy-loads « Mesures d'édition » from the mesures route", async () => {
    const route = allRoutes(routes).find((r) => r.path === 'mesures');
    const component = await (route?.loadComponent as () => Promise<unknown>)();
    expect(component).toBe(MesuresPage);
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

  /** The opening hours screen is gone: its addresses land on the fiche stand or the stands list. */
  it('sends the former opening hours addresses to the fiche stand or the stands', () => {
    expect(redirectTarget('ouvertures', {})).toBe('/stands');
    expect(redirectTarget('ouvertures', { vue: 'saisie', stand: 'S 1' })).toBe('/stands/S%201');
    expect(redirectTarget('ouvertures', { vue: 'comparer', stands: 'S2,S3' })).toBe('/stands/S2');
    expect(openingsToStandsUrl({ onglet: 'horaires', du: '2026-07-10' })).toBe('/stands');
  });

  /** « Mode jour J » became Aujourd'hui: its bookmarks land there, whatever they carried. */
  it('sends the former event-day address to Aujourd’hui with its query params', () => {
    expect(redirectTarget('jour-j', {})).toBe('/aujourdhui');
    expect(redirectTarget('jour-j', { date: '2026-08-02' })).toBe('/aujourdhui?date=2026-08-02');
  });

  /**
   * The former addresses of the day's renderings redirect to the page, query
   * params included: the key renamed when the rendering owns another one, and
   * the value translated when it changed along with the key.
   */
  describe('les anciennes adresses des rendus de la journée', () => {
    it('garde les filtres et renomme la clé du rail', () => {
      expect(redirectTarget('rail-jour', { vue: 'libres', stand: 'S1' })).toBe(
        '/journee?vue=jour&mode=rail&lignes=libres&stand=S1',
      );
    });

    it('mène les pauses et l’intendance aux problèmes du Diagnostic, faute de vue à elles', () => {
      expect(redirectTarget('pauses', { vue: 'sans-relais', jour: '2026-08-02' })).toBe(
        '/diagnostic?onglet=problemes',
      );
      expect(redirectTarget('intendance', {})).toBe('/diagnostic?onglet=problemes');
    });

    it('mène au tableau de la vue Jour sans paramètre superflu', () => {
      expect(redirectTarget('day-calendar', {})).toBe('/journee?vue=jour&mode=tableau');
    });

    // #712: the month calendar, the intendance and the graph became the
    // Planning page's day selector, its breaks and meals, and its map.
    it('sends the three screens the Planning page absorbed there, params kept', () => {
      expect(
        redirectTarget('calendar', { date: '2026-09-05', month: '2026-09', stand: 'S1' }),
      ).toBe('/journee?date=2026-09-05&stand=S1');
      expect(redirectTarget('calendar', {})).toBe('/journee');
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
        '/journee?axe=personne&vue=synthese&sort=heuresNuit&dir=desc',
      );
      expect(redirectTarget('equite', { q: 'ali', sort: 'heuresSoiree', dir: 'asc' })).toBe(
        '/journee?axe=personne&vue=synthese&q=ali&sort=heuresSoiree&dir=asc',
      );
    });

    it("sends one person's equity to their fiche, where the radar went", () => {
      expect(
        redirectTarget('equite', { vue: 'fiche', animateur: 'A7', axes: 'x', comparer: 'B2' }),
      ).toBe('/animateurs/A7?section=equite&axes=x&comparer=B2');
      expect(redirectTarget('equite', { vue: 'fiche', animateur: 'A7' })).toBe(
        '/animateurs/A7?section=equite',
      );
      expect(redirectTarget('equite', { vue: 'fiche' })).toBe('/journee?axe=personne&vue=synthese');
    });

    it('sends the treemap to the stand axis, its day under a key of its own', () => {
      expect(
        redirectTarget('repartition-heures', { date: '2026-09-05', regroupement: 'typologie' }),
      ).toBe('/journee?axe=stand&vue=treemap&jourTreemap=2026-09-05&regroupement=typologie');
      expect(redirectTarget('typologies-planning', {})).toBe(
        '/diagnostic?onglet=besoin&section=typologie',
      );
    });

    // 4.0: the Planning page keeps Jour, Stand, Personne and Carte. The views
    // it gave up land on the screen holding their reading today — and no
    // address, old or new, is left on an empty page.
    describe('les vues retirées de la page Planning', () => {
      const formerView = (queryParams: Record<string, string>): string | null =>
        planningFormerViewUrl(queryParams);

      it('mène « Par typologie » à la section Typologie du Besoin', () => {
        expect(formerView({ axe: 'typologie' })).toBe(
          '/diagnostic?onglet=besoin&section=typologie',
        );
        expect(formerView({ axe: 'typologie', typologie: 'T1', q: 'x' })).toBe(
          '/diagnostic?onglet=besoin&section=typologie',
        );
      });

      it('mène les pauses aux problèmes du Diagnostic', () => {
        expect(formerView({ vue: 'pauses', date: '2026-08-02', relais: 'sans' })).toBe(
          '/diagnostic?onglet=problemes',
        );
      });

      it('mène les changements à Diffuser, le jour gardé', () => {
        expect(formerView({ vue: 'changements', date: '2026-08-02' })).toBe(
          '/publication?onglet=envoyer&jour=2026-08-02',
        );
        expect(
          formerView({ vue: 'changements', jour: '2026-08-03', reference: 'resolution' }),
        ).toBe('/publication?onglet=envoyer&jour=2026-08-03');
        expect(formerView({ vue: 'changements', jour: '2' })).toBe('/publication?onglet=envoyer');
      });

      it('laisse passer chaque vue que la page lit encore, 3.x comprises', () => {
        const queries: Record<string, string>[] = [
          {},
          { vue: 'jour', mode: 'rail' },
          { vue: 'jour', mode: 'tableau' },
          { vue: 'calendrier' },
          { vue: 'rail' },
          { vue: 'carte' },
          { vue: 'stand' },
          { vue: 'personne', rendu: 'synthese' },
          { axe: 'stand', vue: 'treemap' },
          { axe: 'personne', vue: 'frise' },
          { vue: 'carte', comparer: '2026-08-02' },
        ];
        for (const query of queries) {
          expect(formerView(query)).toBeNull();
        }
      });

      it('est la garde de la route /journee', () => {
        const route = allRoutes(routes).find((candidate) => candidate.path === 'journee');
        expect(route?.canActivate).toContain(planningFormerViews);
      });
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
      ).toBe(
        '/diagnostic?onglet=risques&section=fragilite&vue=COMPETENCES&filtre=CRITIQUES&q=Alice',
      );
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
          benchTabToJournee(snapshotOf(queryParams), TestBed.inject(Router).routerState.snapshot),
        );

      expect(String(guard({ onglet: 'banc', creneau: '12', stand: 'S1' }))).toBe(
        '/journee?creneau=12&stand=S1',
      );
      expect(guard({ onglet: 'fragilite' })).toBe(true);
      expect(guard({})).toBe(true);
    });

    // The realised was a tab of Aujourd'hui, and a screen before that: both land on
    // the Mesures d'édition, whatever else the address carried.
    it('sends the former realised addresses to the Mesures d’édition, params kept', () => {
      expect(redirectTarget('realise', {})).toBe('/mesures');
      expect(redirectTarget('realise', { vue: 'indicateurs' })).toBe('/mesures?vue=indicateurs');
      TestBed.configureTestingModule({
        providers: [provideZonelessChangeDetection(), provideRouter([])],
      });
      const guard = (queryParams: Record<string, string>): unknown =>
        TestBed.runInInjectionContext(() =>
          redirectFormerRealiseTab(
            snapshotOf(queryParams),
            TestBed.inject(Router).routerState.snapshot,
          ),
        );

      expect(String(guard({ onglet: 'realise', vue: 'indicateurs' }))).toBe(
        '/mesures?vue=indicateurs',
      );
      expect(String(guard({ onglet: 'realise' }))).toBe('/mesures');
      expect(guard({ date: '2026-08-02' })).toBe(true);
    });

    // Marge disponible became two tabs of the Diagnostic: its « avant » reading
    // a column of Besoin, its « après » and « tension » readings the Tension section of Risques.
    it('sends the margin to Besoin, and its after-solve readings to Risques, params kept', () => {
      expect(redirectTarget('marge', {})).toBe('/diagnostic?onglet=besoin');
      expect(redirectTarget('marge', { mode: 'apres' })).toBe(
        '/diagnostic?onglet=risques&section=tension',
      );
      expect(redirectTarget('marge', { mode: 'tension', date: '2026-08-02' })).toBe(
        '/diagnostic?onglet=risques&section=tension&date=2026-08-02',
      );
    });

    it('sends the former Fragilité, « À former » and Tension tabs to Risques, and no other tab', () => {
      TestBed.configureTestingModule({
        providers: [provideZonelessChangeDetection(), provideRouter([])],
      });
      const guard = (queryParams: Record<string, string>): unknown =>
        TestBed.runInInjectionContext(() =>
          formerTabsToRisques(snapshotOf(queryParams), TestBed.inject(Router).routerState.snapshot),
        );

      expect(String(guard({ onglet: 'former' }))).toBe('/diagnostic?onglet=risques&section=former');
      expect(String(guard({ onglet: 'tension', date: '2026-08-02' }))).toBe(
        '/diagnostic?onglet=risques&section=tension&date=2026-08-02',
      );
      expect(String(guard({ onglet: 'fragilite', q: 'Alice' }))).toBe(
        '/diagnostic?onglet=risques&section=fragilite&q=Alice',
      );
      expect(String(guard({ onglet: 'besoin', section: 'former' }))).toBe(
        '/diagnostic?onglet=risques&section=former',
      );
      expect(guard({ onglet: 'besoin' })).toBe(true);
      expect(guard({ onglet: 'besoin', section: 'typologie' })).toBe(true);
      expect(guard({})).toBe(true);
    });

    it('runs its redirections again when only the query changes', () => {
      const diagnostic = allRoutes(routes).find((route) => route.path === 'diagnostic');
      expect(diagnostic?.runGuardsAndResolvers).toBe('paramsOrQueryParamsChange');
    });
  });

  /**
   * Imports, Export and the validator became one Fichiers page: each former
   * address lands on its tab, the Imports page's own tab on its card.
   */
  describe('les anciennes adresses de Fichiers', () => {
    it("mène d'Imports à la carte de l'onglet Importer qu'il nommait", () => {
      expect(redirectTarget('imports', { onglet: 'animateurs' })).toBe(
        '/fichiers?onglet=importer&cible=animateurs',
      );
      expect(redirectTarget('imports', {})).toBe('/fichiers?onglet=importer');
    });

    // The cards withdrawn in 4.0: a bookmark on the CSV of a referential or on
    // the stand matrix lands on the Importer tab, where the YAML is the way in.
    it.each([
      'typologies',
      'emplacements',
      'stands',
      'creneaux',
      'journees-types',
      'grille-stands',
    ])("mène d'Imports, carte %s retirée, à l'onglet Importer", (carte) => {
      expect(redirectTarget('imports', { onglet: carte })).toBe('/fichiers?onglet=importer');
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
      expect(TestBed.inject(Router).url).toBe('/journee?axe=personne&vue=synthese&q=Alice');
    });
  });

  /** The two tabs of Débogage that moved to Fichiers: a guard, since `/debug` itself still answers. */
  describe('les onglets du Débogage partis vers Fichiers', () => {
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
      ).toBe('/regles?onglet=ajustements&personne=Alice');
      expect(redirectTarget('ad-hoc-constraints', { ids: 'A1,A2' })).toBe(
        '/regles?onglet=ajustements&ids=A1%2CA2',
      );
    });

    it('garde les paramètres des verrouillages et des consignes', () => {
      expect(redirectTarget('verrouillages', { animateur: 'a1' })).toBe(
        '/regles?onglet=verrouillages&animateur=a1',
      );
      expect(redirectTarget('consignes', { date: '2026-08-02', nouvelle: '1' })).toBe(
        '/regles?onglet=consignes&date=2026-08-02&nouvelle=1',
      );
    });
  });

  /** The Autopsie, the Instantanés and the Comparateur became « Versions du plan » (issue #702). */
  it('mène les trois anciennes adresses des versions à la nouvelle page', () => {
    for (const path of ['instantanes', 'comparateur']) {
      expect(allRoutes(routes).find((route) => route.path === path)?.redirectTo).toBe(
        '/journee?onglet=versions',
      );
    }
    expect(allRoutes(routes).find((route) => route.path === 'kpi')?.redirectTo).toBe(
      redirectKpiToVersions,
    );
  });

  /** The editions' comparison became the « Éditions » view of `/comparer` (4.0). */
  it('mène l’ancienne comparaison d’éditions à sa vue, la paire gardée', () => {
    expect(redirectEditionComparison(snapshotOf({ a: 'E1', b: 'E2', x: 'y' }))).toBe(
      '/comparer?vue=editions&a=E1&b=E2',
    );
    expect(redirectEditionComparison(snapshotOf({}))).toBe('/comparer?vue=editions');
  });

  /** `/constraints` became « Règles du planning » (issue #720). */
  describe("l'ancienne adresse des contraintes", () => {
    it('keeps the requested rule, which the page opens on its tab', () => {
      expect(constraintsTarget({ regle: 'equilibrerCharge' }, null)).toBe(
        '/regles?regle=equilibrerCharge',
      );
    });

    it('turns the anchor of a rule into the rule to open, and forgets the anchor of a category', () => {
      expect(constraintsTarget({}, 'coupureRepasObligatoire')).toBe(
        '/regles?regle=coupureRepasObligatoire',
      );
      expect(constraintsTarget({}, 'categorie-legal-mineurs')).toBe('/regles?onglet=regles');
    });
  });

  /** The Autopsie became « Versions du plan » (issue #702): its every-edition view survives. */
  describe("l'ancienne adresse de l'Autopsie", () => {
    it('keeps the every-edition view, and that one alone', () => {
      expect(kpiTarget({ edition: '*' })).toBe('/journee?onglet=versions&editions=toutes');
      expect(kpiTarget({ edition: 'E1', rang: '2' })).toBe('/journee?onglet=versions');
      expect(kpiTarget({})).toBe('/journee?onglet=versions');
    });
  });

  /** The Solveur became the « Calculer » tab of the Planning page (4.0). */
  describe("l'ancienne adresse du Solveur", () => {
    it('lands on « Calculer », its other params kept', () => {
      expect(redirectTarget('solveur', {})).toBe('/journee?onglet=calculer');
      expect(redirectTarget('solver', {})).toBe('/journee?onglet=calculer');
      expect(redirectTarget('solveur', { onglet: 'calcul', avance: '1' })).toBe(
        '/journee?onglet=calculer&avance=1',
      );
    });

    it('lands its « Versions du plan » tab on the Planning tab of that name', () => {
      expect(redirectTarget('solveur', { onglet: 'versions', editions: 'toutes' })).toBe(
        '/journee?onglet=versions&editions=toutes',
      );
      expect(redirectTarget('versions', { comparer: '1,2' })).toBe(
        '/journee?onglet=versions&comparer=1%2C2',
      );
    });
  });

  /** Paramètres became four sections: the guard sends the bookmarks of what moved on. */
  describe('les onglets déplacés des paramètres', () => {
    it('envoie les paramètres légaux vers la section Légal des paramètres', () => {
      expect(garde({ onglet: 'legaux', x: '1' })).toBe('/parametres?x=1&onglet=legal');
    });

    it('envoie les e-mails automatiques vers la section Notifications', () => {
      expect(garde({ onglet: 'emails' })).toBe('/parametres?onglet=notifications');
      expect(garde({ onglet: 'edition' }, 'emails')).toBe('/parametres?onglet=notifications');
      expect(garde({}, 'emails')).toBe('/parametres?onglet=notifications');
    });

    it('envoie l’onglet Calcul des règles vers la section Calcul des paramètres', () => {
      expect(garde({ onglet: 'calcul', avance: '1' }, null, redirectCalculToParametres)).toBe(
        '/parametres?onglet=calcul&avance=1',
      );
      expect(garde({ onglet: 'legal' }, null, redirectCalculToParametres)).toBe(true);
    });

    it('laisse passer les onglets qui existent', () => {
      expect(garde({ onglet: 'instance' })).toBe(true);
      expect(garde({})).toBe(true);
    });
  });
});

interface Leavable {
  canLeave(): Promise<boolean>;
}

describe('the screens that became tabs', () => {
  it('keeps the covoiturage tab of Disponibilités, and opens its main tab otherwise', () => {
    expect(redirectTarget('disponibilites', { onglet: 'covoiturage' })).toBe(
      '/animateurs?onglet=covoiturage',
    );
    expect(redirectTarget('disponibilites', { onglet: 'autre' })).toBe(
      '/animateurs?onglet=disponibilites',
    );
  });

  it('keeps the connexions tab of the journal, and opens its main tab otherwise', () => {
    expect(redirectTarget('historique', { onglet: 'connexions' })).toBe(
      '/parametres?onglet=connexions',
    );
    expect(redirectTarget('historique', {})).toBe('/parametres?onglet=historique');
    // The weight history was folded into the journal: its filter survives the move.
    expect(redirectTarget('historique', { nature: 'regles' })).toBe(
      '/parametres?onglet=historique&nature=regles',
    );
  });

  it('sends the retired hours tab of the Calendrier to the fiche stand, and lets the timeslots through', () => {
    const guard = allRoutes(routes).find((route) => route.path === 'creneaux')
      ?.canActivate?.[0] as (route: unknown) => unknown;
    const run = (params: Record<string, string>) =>
      TestBed.runInInjectionContext(() =>
        guard({ queryParams: params, queryParamMap: convertToParamMap(params) }),
      );
    expect(String(run({ onglet: 'horaires', stand: 'S1' }))).toBe('/stands/S1');
    expect(String(run({ onglet: 'horaires' }))).toBe('/stands');
    expect(run({})).toBe(true);
  });

  // A hosted tab's unsaved cells must not leave silently with the route.
  it.each([
    ['animateurs', true],
    ['animateurs', false],
  ])('asks the %s page whether it can be left (%s)', async (path, answer) => {
    const guard = allRoutes(routes).find((route) => route.path === path)?.canDeactivate?.[0];
    const page: Leavable = { canLeave: () => Promise.resolve(answer) };
    const result = TestBed.runInInjectionContext(() =>
      (guard as (page: Leavable) => Promise<boolean>)(page),
    );
    expect(await result).toBe(answer);
  });
});
