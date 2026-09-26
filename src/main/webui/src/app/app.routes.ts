import { inject } from '@angular/core';
import { CanActivateFn, Params, RedirectFunction, Router, Routes } from '@angular/router';
import { TODAY_ANCHOR } from './core/date-mock.service';
import type { CompetencesPage } from './pages/competences/competences-page';
import { retiredOpeningsViews } from './pages/ouvertures/vues-retirees';
import type { StandFichePage } from './pages/stand-fiche/stand-fiche-page';

/**
 * One route per functional block; every page is lazy-loaded. A `title` names
 * the page and nothing else: the product name is appended by
 * `core/branding-title.strategy.ts`, from the deployment's configuration. Admin pages live
 * under the admin shell (toolbar + drawer, behind the admin session); /login,
 * the espace animateur (issue #165) and the wall display render standalone,
 * without any admin chrome or polling.
 */
/**
 * The four day screens and the four diagnostic screens became one page each:
 * their addresses redirect and carry their query params along, renamed where
 * the new page owns the key (`vue` was the rail's own selector, it now names
 * the rendering; `jour` is understood by the page for the bookmarks that carry
 * it). A bookmark, a link of the help or a link printed elsewhere still lands
 * on the screen it named.
 */
/** How a former screen's query param travels: its key renamed, and its values translated when they changed too. */
type ParamRename = string | { key: string; values: Record<string, string> };

/** A query param as `String()` would print it: a repeated param is joined by commas. */
function paramText(value: string | readonly string[]): string {
  return typeof value === 'string' ? value : value.join(',');
}

function redirectToJournee(
  vue: string,
  renames: Record<string, ParamRename> = {},
): RedirectFunction {
  return ({ queryParams }) => {
    const params = new URLSearchParams();
    params.set('vue', vue);
    for (const [key, value] of Object.entries(queryParams)) {
      if (value === undefined || value === null) {
        continue;
      }
      const rename = renames[key];
      const renamedKey = typeof rename === 'string' ? rename : (rename?.key ?? key);
      const text = paramText(value);
      const renamedValue = typeof rename === 'object' ? (rename.values[text] ?? text) : text;
      if (renamedKey !== 'vue') {
        params.set(renamedKey, renamedValue);
      }
    }
    return `/journee?${params.toString()}`;
  };
}

function redirectToDiagnostic(onglet: string): RedirectFunction {
  return redirectToOnglet('diagnostic', onglet);
}

/** A former screen that became a tab: its address keeps its own query params and gains the `onglet`. */
function redirectToOnglet(page: string, onglet: string): RedirectFunction {
  return ({ queryParams }) => {
    const params = new URLSearchParams();
    params.set('onglet', onglet);
    for (const [key, valeur] of Object.entries(queryParams)) {
      if (key !== 'onglet' && valeur !== undefined && valeur !== null) {
        params.set(key, paramText(valeur));
      }
    }
    return `/${page}?${params.toString()}`;
  };
}

/**
 * The bench — the Banc de touche screen, then the `banc` tab of the
 * Diagnostic — became « Qui peut tenir ce siège ? » in the Siège panel of the
 * Journée. Its addresses land there with their `creneau` and `stand`: the
 * page resolves them to a seat, moves to its day and opens the panel on it.
 */
export function benchToJournee(queryParams: Params): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(queryParams)) {
    if (key !== 'onglet' && value !== undefined && value !== null) {
      params.set(key, paramText(value as string | readonly string[]));
    }
  }
  const query = params.toString();
  return query ? `/journee?${query}` : '/journee';
}

/**
 * `/timeline?animateur=X`: the « Timeline animateur » screen became the
 * « Planning » section of the fiche, which the address opens; without a person
 * it lands on the list the fiches are reached from.
 */
export function timelineToFiche(queryParams: Params): string {
  const animateur = queryParams['animateur'];
  const id = typeof animateur === 'string' ? animateur.trim() : '';
  return id ? `/animateurs/${encodeURIComponent(id)}?section=timeline` : '/animateurs';
}

/**
 * `/stands?edit=S1` — the eight links that name a stand to fix (a problem, a
 * warning, the combined calendar…): the stand has a page of its own, which
 * the link opens with its identity form. The locations tab keeps its own
 * `edit`, which names a location.
 */
export function standEditToFicheUrl(queryParams: Params): string | null {
  const edit = queryParams['edit'];
  if (typeof edit !== 'string' || edit.trim() === '' || queryParams['onglet'] === 'lieux') {
    return null;
  }
  return `/stands/${encodeURIComponent(edit.trim())}?modifier=1`;
}

export const standEditToFiche: CanActivateFn = (route) => {
  const url = standEditToFicheUrl(route.queryParams);
  return url ? inject(Router).parseUrl(url) : true;
};

/**
 * `/equite?vue=fiche&animateur=X`: the « Fiche » reading of the Équité screen
 * — one person's indicators and the radar — lives on the fiche animateur now,
 * in its « Charge et équité » section, the radar's `axes` and `comparer`
 * kept. Without a person, the address lands on the table, its other keys
 * kept.
 */
export function equityFicheUrl(queryParams: Params): string | null {
  if (queryParams['vue'] !== 'fiche') {
    return null;
  }
  const animateur = queryParams['animateur'];
  const id = typeof animateur === 'string' ? animateur.trim() : '';
  const params = new URLSearchParams();
  if (id) {
    params.set('section', 'equite');
    for (const key of ['axes', 'comparer']) {
      const value = queryParams[key];
      if (value !== undefined && value !== null && value !== '') {
        params.set(key, paramText(value as string | readonly string[]));
      }
    }
    return `/animateurs/${encodeURIComponent(id)}?${params.toString()}`;
  }
  for (const [key, value] of Object.entries(queryParams)) {
    if (
      !['vue', 'animateur', 'axes', 'comparer'].includes(key) &&
      value !== undefined &&
      value !== null
    ) {
      params.set(key, paramText(value as string | readonly string[]));
    }
  }
  const query = params.toString();
  return query ? `/equite?${query}` : '/equite';
}

export const equityFicheToAnimateur: CanActivateFn = (route) => {
  const url = equityFicheUrl(route.queryParams);
  return url ? inject(Router).parseUrl(url) : true;
};

/** `/diagnostic?onglet=banc…`: the tab is gone, its address goes to the Journée. */
export const benchTabToJournee: CanActivateFn = (route) =>
  route.queryParamMap.get('onglet') === 'banc'
    ? inject(Router).parseUrl(benchToJournee(route.queryParams))
    : true;

/**
 * A former screen of what Fichiers gathers: its address lands on a tab of the
 * page (`onglet`), and on one card of the Importer tab (`cible`) when the
 * former screen named one — the Imports page's own `onglet` becomes that
 * `cible`. Every other query param travels along.
 */
function redirectToFichiers(onglet: string, importCard?: string): RedirectFunction {
  return ({ queryParams, fragment }) => {
    const params = new URLSearchParams();
    // The end-of-event archive was a fragment of the Export page.
    params.set('onglet', fragment === 'archive-evenement' ? 'archive' : onglet);
    const former = queryParams['onglet'];
    const card = importCard ?? (onglet === 'importer' && former ? paramText(former) : undefined);
    if (card !== undefined) {
      params.set('cible', card);
    }
    for (const [key, valeur] of Object.entries(queryParams)) {
      if (key !== 'onglet' && key !== 'cible' && valeur !== undefined && valeur !== null) {
        params.set(key, paramText(valeur));
      }
    }
    return `/fichiers?${params.toString()}`;
  };
}

/**
 * The two tabs Débogage gave up to Fichiers — the bundled scenarios and the
 * YAML validator — redirect before the page is built, and so does the
 * simulated clock's field (`?focus=date-du-jour`, the toolbar's former link),
 * which lives in Paramètres › Instance now; every other address of `/debug`
 * is served as it is.
 */
const FORMER_DEBUG_TABS: Readonly<Record<string, string>> = {
  donnees: 'exemples',
  yaml: 'verifier',
};

function redirectFormerDebugTabs(route: { queryParamMap: { get(name: string): string | null } }) {
  if (route.queryParamMap.get('focus') === TODAY_ANCHOR) {
    return inject(Router).createUrlTree(['/parametres'], {
      queryParams: { onglet: 'instance', focus: TODAY_ANCHOR },
    });
  }
  const card = FORMER_DEBUG_TABS[route.queryParamMap.get('onglet') ?? ''];
  return card === undefined
    ? true
    : inject(Router).createUrlTree(['/fichiers'], {
        queryParams: { onglet: 'importer', cible: card },
      });
}

const adminRoutes: Routes = [
  {
    // The home: where the edition stands in its cycle, before any screen
    // that acts on it (issue #485). The solver keeps its page under /solveur.
    path: '',
    title: () => $localize`:@@route.accueil:État de l'édition`,
    loadComponent: () => import('./pages/accueil/accueil-page').then((m) => m.AccueilPage),
  },
  {
    path: 'solveur',
    title: () => $localize`:@@route.solver:Solveur`,
    loadComponent: () => import('./pages/solver/solver-page').then((m) => m.SolverPage),
  },
  {
    path: 'debug',
    title: () => $localize`:@@route.debug:Débogage`,
    canActivate: [redirectFormerDebugTabs],
    loadComponent: () => import('./pages/debug/debug-page').then((m) => m.DebugPage),
  },
  // The alerts of the night and the history of the application's messages
  // live under « À traiter aujourd'hui » of the home screen now: the bell and
  // the former address land there.
  { path: 'notifications', redirectTo: () => '/#a-traiter' },
  {
    path: 'jour-j',
    title: () => $localize`:@@route.jourJ:Jour J`,
    loadComponent: () => import('./pages/jour-j/jour-j-page').then((m) => m.JourJPage),
  },
  {
    path: 'diagnostic',
    title: () => $localize`:@@route.diagnostic:Diagnostic`,
    canActivate: [benchTabToJournee],
    loadComponent: () => import('./pages/diagnostic/diagnostic-page').then((m) => m.DiagnosticPage),
  },
  // The screens the Diagnostic page gathers, kept for the bookmarks; the
  // bench's own went on to the Journée with it.
  { path: 'problemes', redirectTo: redirectToDiagnostic('problemes') },
  { path: 'staffing', redirectTo: redirectToDiagnostic('besoin') },
  { path: 'fragilite', redirectTo: redirectToDiagnostic('fragilite') },
  { path: 'banc-de-touche', redirectTo: ({ queryParams }) => benchToJournee(queryParams) },
  {
    path: 'echanges',
    title: () => $localize`:@@route.echanges:Échanges`,
    loadComponent: () => import('./pages/echanges/echanges-page').then((m) => m.EchangesPage),
  },
  {
    path: 'disponibilites',
    title: () => $localize`:@@route.disponibilites:Disponibilités`,
    loadComponent: () =>
      import('./pages/disponibilites/disponibilites-page').then((m) => m.DisponibilitesPage),
  },
  {
    // Not `/mcp`: that very path is the backend's MCP transport endpoint —
    // the SPA would never be served there (405 on GET).
    path: 'mcp-client',
    title: () => $localize`:@@route.mcp:MCP`,
    loadComponent: () => import('./pages/mcp/mcp-page').then((m) => m.McpPage),
  },
  {
    path: 'parametres',
    title: () => $localize`:@@route.parametres:Paramètres`,
    loadComponent: () => import('./pages/parametres/parametres-page').then((m) => m.ParametresPage),
  },
  {
    path: 'historique',
    title: () => $localize`:@@route.historique:Historique des actions`,
    loadComponent: () => import('./pages/historique/historique-page').then((m) => m.HistoriquePage),
  },
  {
    // What the running version brought, read from the repository's history at
    // build time (`scripts/generate-news.js`): no endpoint, no stored page.
    path: 'nouveautes',
    title: () => $localize`:@@route.nouveautes:Nouveautés`,
    loadComponent: () => import('./pages/nouveautes/news-page').then((m) => m.NewsPage),
  },
  {
    path: 'aide',
    title: () => $localize`:@@route.aide:Aide`,
    loadComponent: () => import('./pages/aide/aide-page').then((m) => m.AidePage),
  },
  {
    path: 'graphe',
    title: () => $localize`:@@route.graphe:Graphe`,
    loadComponent: () => import('./pages/graphe/graphe-page').then((m) => m.GraphePage),
  },
  {
    path: 'kpi',
    title: () => $localize`:@@route.kpi:KPI`,
    loadComponent: () => import('./pages/kpi/kpi-page').then((m) => m.KpiPage),
  },
  {
    path: 'comparateur',
    title: () => $localize`:@@route.comparateur:Comparateur A/B`,
    loadComponent: () =>
      import('./pages/comparateur/comparateur-page').then((m) => m.ComparateurPage),
  },
  {
    path: 'instantanes',
    title: () => $localize`:@@route.instantanes:Instantanés`,
    loadComponent: () => import('./pages/snapshots/snapshots-page').then((m) => m.SnapshotsPage),
  },
  {
    path: 'editions',
    title: () => $localize`:@@route.editions:Éditions`,
    loadComponent: () => import('./pages/editions/editions-page').then((m) => m.EditionsPage),
  },
  // The solver was the home page until #485, under `/exports` and `/solver`.
  // `/exports` became the export screen, now a tab of Fichiers; only
  // `/solver` still lands on the solver.
  { path: 'solver', redirectTo: 'solveur' },
  // Pre-Paramètres URLs (bookmarks, aide links): the pages were merged there.
  { path: 'data-transfer', redirectTo: 'parametres' },
  { path: 'data-setup', redirectTo: 'parametres' },
  // The découpage had a page of its own, then a card on Paramètres, then
  // nothing: a grid is made of vacations, so the address lands on the grid.
  { path: 'decoupage', redirectTo: 'creneaux' },
  // The card it became, not the page's default one: the address named the
  // validator, and a redirection that drops it lands on the typologies.
  { path: 'validateur-yaml', redirectTo: redirectToFichiers('importer', 'verifier') },
  {
    path: 'stands',
    title: () => $localize`:@@route.stands:Stands`,
    loadComponent: () => import('./pages/stands/stands-page').then((m) => m.StandsPage),
    canActivate: [standEditToFiche],
    // A « Voir la fiche » link names `?edit=` while the table is on screen: a
    // query-only change, on which the guard must run again to open the fiche.
    runGuardsAndResolvers: 'paramsOrQueryParamsChange',
  },
  {
    path: 'stands/:id',
    title: () => $localize`:@@route.standFiche:Fiche stand`,
    loadComponent: () =>
      import('./pages/stand-fiche/stand-fiche-page').then((m) => m.StandFichePage),
    // Unsaved cells of its grid would vanish with the page: it asks first.
    canDeactivate: [(page: StandFichePage) => page.canLeave()],
  },
  // The locations became the « Lieux » tab of the Stands page, map included.
  { path: 'emplacements', redirectTo: redirectToOnglet('stands', 'lieux') },
  {
    path: 'animateurs',
    title: () => $localize`:@@route.animateurs:Animateurs`,
    loadComponent: () => import('./pages/animateurs/animateurs-page').then((m) => m.AnimateursPage),
  },
  {
    path: 'animateurs/:id',
    title: () => $localize`:@@route.animateurFiche:Fiche animateur`,
    loadComponent: () =>
      import('./pages/animateur-fiche/animateur-fiche-page').then((m) => m.AnimateurFichePage),
  },
  {
    path: 'competences',
    title: () => $localize`:@@route.competences:Compétences`,
    loadComponent: () =>
      import('./pages/competences/competences-page').then((m) => m.CompetencesPage),
    // Unsaved cells would silently survive, invisible, until the next reload: the page asks first.
    canDeactivate: [(page: CompetencesPage) => page.canLeave()],
  },
  {
    path: 'creneaux',
    title: () => $localize`:@@route.creneaux:Créneaux`,
    loadComponent: () => import('./pages/creneaux/creneaux-page').then((m) => m.CreneauxPage),
  },
  {
    // Imports, Export and the end-of-event archive: the two halves of one
    // gesture and what closes an edition, on one page of three tabs.
    path: 'fichiers',
    title: () => $localize`:@@route.fichiers:Fichiers`,
    loadComponent: () => import('./pages/fichiers/fichiers-page').then((m) => m.FichiersPage),
  },
  // The two screens Fichiers gathers, kept for the bookmarks: the Imports
  // page's `onglet` names a card of the Importer tab.
  { path: 'imports', redirectTo: redirectToFichiers('importer') },
  {
    // The planning leaves through here, the data through /fichiers (issue #320).
    path: 'publication',
    title: () => $localize`:@@route.publication:Publication`,
    loadComponent: () =>
      import('./pages/publication/publication-page').then((m) => m.PublicationPage),
  },
  { path: 'exports', redirectTo: redirectToFichiers('exporter') },
  // The CSV archive was the whole screen until the scenario export joined it.
  { path: 'export-csv', redirectTo: redirectToFichiers('exporter') },
  {
    path: 'typologies',
    title: () => $localize`:@@route.typologies:Typologies`,
    loadComponent: () => import('./pages/typologies/typologies-page').then((m) => m.TypologiesPage),
  },
  {
    // The referential screen manages the typologies; this one reads the plan
    // through them (issue #590). Two questions, two addresses.
    path: 'typologies-planning',
    title: () => $localize`:@@route.typologiesPlanning:Planning par typologie`,
    loadComponent: () =>
      import('./pages/typologies-planning/typologies-planning-page').then(
        (m) => m.TypologiesPlanningPage,
      ),
  },
  {
    path: 'ad-hoc-constraints',
    title: () => $localize`:@@route.adHocConstraints:Ajustements manuels`,
    loadComponent: () =>
      import('./pages/ad-hoc-constraints/ad-hoc-constraints-page').then(
        (m) => m.AdHocConstraintsPage,
      ),
  },
  {
    path: 'verrouillages',
    title: () => $localize`:@@route.verrouillages:Verrouillages`,
    loadComponent: () =>
      import('./pages/verrouillages/verrouillages-page').then((m) => m.VerrouillagesPage),
  },
  {
    // A band every stand is shut on for one date, by decision (issue #4).
    path: 'consignes',
    title: () => $localize`:@@route.consignes:Consignes`,
    loadComponent: () => import('./pages/consignes/consignes-page').then((m) => m.ConsignesPage),
  },
  {
    path: 'calendar',
    title: () => $localize`:@@route.calendar:Calendrier des affectations`,
    loadComponent: () =>
      import('./pages/calendar-month/calendar-month-page').then((m) => m.CalendarMonthPage),
  },
  {
    path: 'journee',
    title: () => $localize`:@@route.journee:Journée`,
    loadComponent: () => import('./pages/journee/journee-page').then((m) => m.JourneePage),
  },
  // The four screens the Journée page gathers, kept for the bookmarks. The
  // rail's `vue` (which lines) and the breaks' `vue` (only those without a
  // relay) are renamed to the keys the views own now.
  { path: 'day-calendar', redirectTo: redirectToJournee('calendrier') },
  { path: 'rail-jour', redirectTo: redirectToJournee('rail', { vue: 'lignes' }) },
  { path: 'carte-jour', redirectTo: redirectToJournee('carte') },
  {
    path: 'pauses',
    redirectTo: redirectToJournee('pauses', {
      vue: { key: 'relais', values: { 'sans-relais': 'sans' } },
      jour: 'date',
    }),
  },
  {
    path: 'intendance',
    title: () => $localize`:@@route.intendance:Intendance des repas`,
    loadComponent: () => import('./pages/intendance/intendance-page').then((m) => m.IntendancePage),
  },
  {
    path: 'constraints',
    title: () => $localize`:@@route.constraints:Contraintes`,
    loadComponent: () =>
      import('./pages/constraints/constraints-page').then((m) => m.ConstraintsPage),
  },
  {
    path: 'hours',
    title: () => $localize`:@@route.hours:Heures`,
    loadComponent: () => import('./pages/hours/hours-page').then((m) => m.HoursPage),
  },
  {
    path: 'equite',
    title: () => $localize`:@@route.equite:Équité`,
    loadComponent: () => import('./pages/equite/equite-page').then((m) => m.EquitePage),
    // Its « Fiche » reading moved to the fiche animateur: the address follows it.
    canActivate: [equityFicheToAnimateur],
  },
  {
    path: 'repos',
    title: () => $localize`:@@route.repos:Jours de repos`,
    loadComponent: () => import('./pages/repos/repos-page').then((m) => m.ReposPage),
  },
  {
    path: 'ouvertures',
    title: () => $localize`:@@route.ouvertures:Horaires des stands`,
    // `?vue=journee` and `?vue=calendrier` were folded into the grid: their
    // addresses land where the same question is now answered.
    canActivate: [retiredOpeningsViews],
    loadComponent: () => import('./pages/ouvertures/ouvertures-page').then((m) => m.OuverturesPage),
  },
  {
    path: 'heatmap',
    title: () => $localize`:@@route.heatmap:Heatmap de charge`,
    loadComponent: () => import('./pages/heatmap/heatmap-page').then((m) => m.HeatmapPage),
  },
  {
    path: 'repartition-heures',
    title: () => $localize`:@@route.repartitionHeures:Répartition des heures`,
    loadComponent: () =>
      import('./pages/repartition-heures/repartition-heures-page').then(
        (m) => m.RepartitionHeuresPage,
      ),
  },
  {
    path: 'marge',
    title: () => $localize`:@@route.marge:Marge disponible`,
    loadComponent: () => import('./pages/marge/marge-page').then((m) => m.MargePage),
  },
  // The timeline became the « Planning » section of the fiche animateur.
  { path: 'timeline', redirectTo: ({ queryParams }) => timelineToFiche(queryParams) },
  { path: '**', redirectTo: '' },
];

export const routes: Routes = [
  {
    path: 'login',
    title: () => $localize`:@@route.login:Connexion`,
    loadComponent: () => import('./pages/login/login-page').then((m) => m.LoginPage),
  },
  {
    // Outside both shells on purpose: a legal notice must stay readable
    // without a session and without a valid access token — the reader who
    // needs it most is often the one who has neither.
    path: 'mentions-legales',
    title: () => $localize`:@@route.mentionsLegales:Mentions légales`,
    loadComponent: () =>
      import('./pages/mentions-legales/mentions-legales-page').then((m) => m.MentionsLegalesPage),
  },
  {
    // Publique pour les mêmes raisons que les mentions légales : la personne
    // qui lit cette page est celle dont on traite les données, et elle n'a pas
    // toujours de session ni de lien valide.
    path: 'politique-confidentialite',
    title: () => $localize`:@@route.politiqueConfidentialite:Politique de confidentialité`,
    loadComponent: () =>
      import('./pages/mentions-legales/politique-confidentialite-page').then(
        (m) => m.PolitiqueConfidentialitePage,
      ),
  },
  {
    path: 'conditions-utilisation',
    title: () => $localize`:@@route.conditionsUtilisation:Conditions d'utilisation`,
    loadComponent: () =>
      import('./pages/mentions-legales/conditions-utilisation-page').then(
        (m) => m.ConditionsUtilisationPage,
      ),
  },
  {
    path: 'declaration-accessibilite',
    title: () => $localize`:@@route.declarationAccessibilite:Déclaration d'accessibilité`,
    loadComponent: () =>
      import('./pages/mentions-legales/declaration-accessibilite-page').then(
        (m) => m.DeclarationAccessibilitePage,
      ),
  },
  {
    path: 'animateur/:jeton',
    loadComponent: () =>
      import('./pages/espace-animateur/espace-animateur-shell').then((m) => m.EspaceAnimateurShell),
    children: [
      {
        path: '',
        title: () => $localize`:@@route.espace.planning:Mon planning`,
        loadComponent: () =>
          import('./pages/espace-animateur/espace-planning-page').then((m) => m.EspacePlanningPage),
      },
      {
        path: 'echanges',
        title: () => $localize`:@@route.espace.echanges:Mes échanges`,
        loadComponent: () =>
          import('./pages/espace-animateur/espace-echanges-page').then((m) => m.EspaceEchangesPage),
      },
      {
        path: 'disponibilites',
        title: () => $localize`:@@route.espace.disponibilites:Mes disponibilités`,
        loadComponent: () =>
          import('./pages/espace-animateur/espace-disponibilites-page').then(
            (m) => m.EspaceDisponibilitesPage,
          ),
      },
      {
        path: 'covoiturage',
        title: () => $localize`:@@route.espace.covoiturage:Mon covoiturage`,
        loadComponent: () =>
          import('./pages/espace-animateur/espace-covoiturage-page').then(
            (m) => m.EspaceCovoituragePage,
          ),
      },
      {
        path: 'aide',
        title: () => $localize`:@@route.espace.aide:Aide`,
        loadComponent: () =>
          import('./pages/espace-animateur/espace-aide-page').then((m) => m.EspaceAidePage),
      },
    ],
  },
  {
    // The control room's television (ADR 0053): outside both shells, no
    // session — the token in the URL opens this one read and nothing else.
    path: 'mural/:jeton',
    title: () => $localize`:@@route.mural:Affichage mural`,
    loadComponent: () => import('./pages/mural/mural-page').then((m) => m.MuralPage),
  },
  {
    path: '',
    loadComponent: () => import('./shell/admin-shell').then((m) => m.AdminShell),
    children: adminRoutes,
  },
];
