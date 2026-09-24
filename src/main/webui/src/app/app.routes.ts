import { RedirectFunction, Routes } from '@angular/router';
import type { CompetencesPage } from './pages/competences/competences-page';

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
    loadComponent: () => import('./pages/debug/debug-page').then((m) => m.DebugPage),
  },
  {
    path: 'notifications',
    title: () => $localize`:@@route.notifications:Notifications`,
    loadComponent: () =>
      import('./pages/notifications/notifications-page').then((m) => m.NotificationsPage),
  },
  {
    path: 'jour-j',
    title: () => $localize`:@@route.jourJ:Jour J`,
    loadComponent: () => import('./pages/jour-j/jour-j-page').then((m) => m.JourJPage),
  },
  {
    path: 'diagnostic',
    title: () => $localize`:@@route.diagnostic:Diagnostic`,
    loadComponent: () => import('./pages/diagnostic/diagnostic-page').then((m) => m.DiagnosticPage),
  },
  // The four screens the Diagnostic page gathers, kept for the bookmarks.
  { path: 'problemes', redirectTo: redirectToDiagnostic('problemes') },
  { path: 'staffing', redirectTo: redirectToDiagnostic('besoin') },
  { path: 'fragilite', redirectTo: redirectToDiagnostic('fragilite') },
  { path: 'banc-de-touche', redirectTo: redirectToDiagnostic('banc') },
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
    // Named accounts and their delegated rights (ADR 0049); the credentials stay Keycloak's.
    path: 'comptes',
    title: () => $localize`:@@route.comptes:Comptes et droits`,
    loadComponent: () => import('./pages/comptes/comptes-page').then((m) => m.ComptesPage),
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
  // `/exports` is now the export screen, which is what the word says; only
  // `/solver` still lands on the solver.
  { path: 'solver', redirectTo: 'solveur' },
  // Pre-Paramètres URLs (bookmarks, aide links): the pages were merged there.
  { path: 'data-transfer', redirectTo: 'parametres' },
  { path: 'data-setup', redirectTo: 'parametres' },
  // The découpage had a page of its own, then a card on Paramètres, then
  // nothing: a grid is made of vacations, so the address lands on the grid.
  { path: 'decoupage', redirectTo: 'creneaux' },
  // The tab it became, not the page's default one: the address named the
  // validator, and a redirection that drops it lands on the raw analysis.
  { path: 'validateur-yaml', redirectTo: redirectToOnglet('debug', 'yaml') },
  {
    path: 'stands',
    title: () => $localize`:@@route.stands:Stands`,
    loadComponent: () => import('./pages/stands/stands-page').then((m) => m.StandsPage),
  },
  {
    path: 'emplacements',
    title: () => $localize`:@@route.emplacements:Emplacements`,
    loadComponent: () =>
      import('./pages/emplacements/emplacements-page').then((m) => m.EmplacementsPage),
  },
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
    path: 'imports',
    title: () => $localize`:@@route.imports:Imports`,
    loadComponent: () => import('./pages/imports/imports-page').then((m) => m.ImportsPage),
  },
  {
    // The planning leaves through here, the data through /exports (issue #320).
    path: 'publication',
    title: () => $localize`:@@route.publication:Publication`,
    loadComponent: () =>
      import('./pages/publication/publication-page').then((m) => m.PublicationPage),
  },
  {
    path: 'exports',
    title: () => $localize`:@@route.exports:Export`,
    loadComponent: () => import('./pages/exports/exports-page').then((m) => m.ExportsPage),
  },
  // The CSV archive was the whole screen until the scenario export joined it.
  { path: 'export-csv', redirectTo: 'exports' },
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
  },
  {
    path: 'repos',
    title: () => $localize`:@@route.repos:Jours de repos`,
    loadComponent: () => import('./pages/repos/repos-page').then((m) => m.ReposPage),
  },
  {
    path: 'ouvertures',
    title: () => $localize`:@@route.ouvertures:Ouvertures des stands`,
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
  {
    path: 'timeline',
    title: () => $localize`:@@route.timeline:Timeline animateur`,
    loadComponent: () =>
      import('./pages/animateur-timeline/animateur-timeline-page').then(
        (m) => m.AnimateurTimelinePage,
      ),
  },
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
