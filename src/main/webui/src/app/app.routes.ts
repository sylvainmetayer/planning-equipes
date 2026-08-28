import { Routes } from '@angular/router';

/**
 * One route per functional block; every page is lazy-loaded. A `title` names
 * the page and nothing else: the product name is appended by
 * `core/branding-title.strategy.ts`, from the deployment's configuration. Admin pages live
 * under the admin shell (toolbar + drawer, behind the admin session); /login
 * and the espace animateur (issue #165) render standalone, without any admin
 * chrome or polling.
 */
const adminRoutes: Routes = [
  {
    path: '',
    title: 'Solver',
    loadComponent: () => import('./pages/solver/solver-page').then((m) => m.SolverPage)
  },
  {
    path: 'debug',
    title: 'Debug',
    loadComponent: () => import('./pages/debug/debug-page').then((m) => m.DebugPage)
  },
  {
    path: 'notifications',
    title: 'Notifications',
    loadComponent: () => import('./pages/notifications/notifications-page').then((m) => m.NotificationsPage)
  },
  {
    path: 'jour-j',
    title: 'Jour J',
    loadComponent: () => import('./pages/jour-j/jour-j-page').then((m) => m.JourJPage)
  },
  {
    path: 'problemes',
    title: 'Problems',
    loadComponent: () => import('./pages/problemes/problemes-page').then((m) => m.ProblemesPage)
  },
  {
    path: 'echanges',
    title: 'Échanges',
    loadComponent: () => import('./pages/echanges/echanges-page').then((m) => m.EchangesPage)
  },
  {
    path: 'disponibilites',
    title: 'Disponibilités',
    loadComponent: () =>
      import('./pages/disponibilites/disponibilites-page').then((m) => m.DisponibilitesPage)
  },
  {
    // Not `/mcp`: that very path is the backend's MCP transport endpoint —
    // the SPA would never be served there (405 on GET).
    path: 'mcp-client',
    title: 'MCP',
    loadComponent: () => import('./pages/mcp/mcp-page').then((m) => m.McpPage)
  },
  {
    path: 'parametres',
    title: 'Paramètres',
    loadComponent: () => import('./pages/parametres/parametres-page').then((m) => m.ParametresPage)
  },
  {
    path: 'aide',
    title: 'Aide',
    loadComponent: () => import('./pages/aide/aide-page').then((m) => m.AidePage)
  },
  {
    path: 'graphe',
    title: 'Graphe',
    loadComponent: () => import('./pages/graphe/graphe-page').then((m) => m.GraphePage)
  },
  {
    path: 'kpi',
    title: 'KPI',
    loadComponent: () => import('./pages/kpi/kpi-page').then((m) => m.KpiPage)
  },
  {
    path: 'comparateur',
    title: 'Comparateur A/B',
    loadComponent: () => import('./pages/comparateur/comparateur-page').then((m) => m.ComparateurPage)
  },
  {
    path: 'instantanes',
    title: 'Instantanés',
    loadComponent: () => import('./pages/snapshots/snapshots-page').then((m) => m.SnapshotsPage)
  },
  {
    path: 'editions',
    title: 'Éditions',
    loadComponent: () => import('./pages/editions/editions-page').then((m) => m.EditionsPage)
  },
  { path: 'exports', redirectTo: '' },
  { path: 'solver', redirectTo: '' },
  // Pre-Paramètres URLs (bookmarks, aide links): the pages were merged there.
  { path: 'data-transfer', redirectTo: 'parametres' },
  { path: 'data-setup', redirectTo: 'parametres' },
  { path: 'decoupage', redirectTo: 'parametres' },
  { path: 'validateur-yaml', redirectTo: 'debug' },
  {
    path: 'stands',
    title: 'Stands',
    loadComponent: () => import('./pages/stands/stands-page').then((m) => m.StandsPage)
  },
  {
    path: 'emplacements',
    title: 'Locations',
    loadComponent: () => import('./pages/emplacements/emplacements-page').then((m) => m.EmplacementsPage)
  },
  {
    path: 'animateurs',
    title: 'Animateurs',
    loadComponent: () => import('./pages/animateurs/animateurs-page').then((m) => m.AnimateursPage)
  },
  {
    path: 'creneaux',
    title: 'Créneaux',
    loadComponent: () => import('./pages/creneaux/creneaux-page').then((m) => m.CreneauxPage)
  },
  {
    path: 'typologies',
    title: 'Typologies',
    loadComponent: () => import('./pages/typologies/typologies-page').then((m) => m.TypologiesPage)
  },
  {
    path: 'ad-hoc-constraints',
    title: 'Ad hoc constraints',
    loadComponent: () => import('./pages/ad-hoc-constraints/ad-hoc-constraints-page').then((m) => m.AdHocConstraintsPage)
  },
  {
    path: 'verrouillages',
    title: 'Planning locks',
    loadComponent: () => import('./pages/verrouillages/verrouillages-page').then((m) => m.VerrouillagesPage)
  },
  {
    path: 'calendar',
    title: 'Assignment calendar',
    loadComponent: () => import('./pages/calendar-month/calendar-month-page').then((m) => m.CalendarMonthPage)
  },
  {
    path: 'day-calendar',
    title: 'Day calendar',
    loadComponent: () => import('./pages/calendar-day/calendar-day-page').then((m) => m.CalendarDayPage)
  },
  {
    path: 'constraints',
    title: 'Constraints',
    loadComponent: () => import('./pages/constraints/constraints-page').then((m) => m.ConstraintsPage)
  },
  {
    path: 'hours',
    title: 'Hours',
    loadComponent: () => import('./pages/hours/hours-page').then((m) => m.HoursPage)
  },
  {
    path: 'staffing',
    title: 'Staffing need',
    loadComponent: () => import('./pages/staffing/staffing-page').then((m) => m.StaffingPage)
  },
  {
    path: 'fragilite',
    title: 'Planning fragility',
    loadComponent: () => import('./pages/fragilite/fragilite-page').then((m) => m.FragilitePage)
  },
  {
    path: 'banc-de-touche',
    title: 'Banc de touche',
    loadComponent: () =>
      import('./pages/banc-de-touche/banc-de-touche-page').then((m) => m.BancDeTouchePage)
  },
  {
    path: 'ouvertures',
    title: 'Stand opening schedule',
    loadComponent: () =>
      import('./pages/ouvertures/ouvertures-page').then((m) => m.OuverturesPage)
  },
  {
    path: 'heatmap',
    title: 'Load heatmap',
    loadComponent: () => import('./pages/heatmap/heatmap-page').then((m) => m.HeatmapPage)
  },
  {
    path: 'timeline',
    title: 'Animateur timeline',
    loadComponent: () =>
      import('./pages/animateur-timeline/animateur-timeline-page').then((m) => m.AnimateurTimelinePage)
  },
  {
    path: 'rail-jour',
    title: 'Day rail',
    loadComponent: () => import('./pages/rail-jour/rail-jour-page').then((m) => m.RailJourPage)
  },
  { path: '**', redirectTo: '' }
];

export const routes: Routes = [
  {
    path: 'login',
    title: 'Connexion',
    loadComponent: () => import('./pages/login/login-page').then((m) => m.LoginPage)
  },
  {
    // Outside both shells on purpose: a legal notice must stay readable
    // without a session and without a valid access token — the reader who
    // needs it most is often the one who has neither.
    path: 'mentions-legales',
    title: 'Mentions légales',
    loadComponent: () =>
      import('./pages/mentions-legales/mentions-legales-page').then((m) => m.MentionsLegalesPage)
  },
  {
    // Publique pour les mêmes raisons que les mentions légales : la personne
    // qui lit cette page est celle dont on traite les données, et elle n'a pas
    // toujours de session ni de lien valide.
    path: 'politique-confidentialite',
    title: 'Politique de confidentialité',
    loadComponent: () =>
      import('./pages/mentions-legales/politique-confidentialite-page').then(
        (m) => m.PolitiqueConfidentialitePage
      )
  },
  {
    path: 'conditions-utilisation',
    title: "Conditions d'utilisation",
    loadComponent: () =>
      import('./pages/mentions-legales/conditions-utilisation-page').then((m) => m.ConditionsUtilisationPage)
  },
  {
    path: 'animateur/:jeton',
    loadComponent: () =>
      import('./pages/espace-animateur/espace-animateur-shell').then((m) => m.EspaceAnimateurShell),
    children: [
      {
        path: '',
        title: 'Mon planning',
        loadComponent: () =>
          import('./pages/espace-animateur/espace-planning-page').then((m) => m.EspacePlanningPage)
      },
      {
        path: 'echanges',
        title: 'Mes échanges',
        loadComponent: () =>
          import('./pages/espace-animateur/espace-echanges-page').then((m) => m.EspaceEchangesPage)
      },
      {
        path: 'disponibilites',
        title: 'Mes disponibilités',
        loadComponent: () =>
          import('./pages/espace-animateur/espace-disponibilites-page').then(
            (m) => m.EspaceDisponibilitesPage
          )
      },
      {
        path: 'aide',
        title: 'Aide',
        loadComponent: () =>
          import('./pages/espace-animateur/espace-aide-page').then((m) => m.EspaceAidePage)
      }
    ]
  },
  {
    path: '',
    loadComponent: () => import('./shell/admin-shell').then((m) => m.AdminShell),
    children: adminRoutes
  }
];
