import { Routes } from '@angular/router';

/**
 * One route per functional block; every page is lazy-loaded. Admin pages live
 * under the admin shell (toolbar + drawer, behind the admin session); /login
 * and the espace animateur (issue #165) render standalone, without any admin
 * chrome or polling.
 */
const adminRoutes: Routes = [
  {
    path: '',
    title: 'Solver — Planning Équipes',
    loadComponent: () => import('./pages/solver/solver-page').then((m) => m.SolverPage)
  },
  {
    path: 'debug',
    title: 'Debug — Planning Équipes',
    loadComponent: () => import('./pages/debug/debug-page').then((m) => m.DebugPage)
  },
  {
    path: 'notifications',
    title: 'Notifications — Planning Équipes',
    loadComponent: () => import('./pages/notifications/notifications-page').then((m) => m.NotificationsPage)
  },
  {
    path: 'problemes',
    title: 'Problems — Planning Équipes',
    loadComponent: () => import('./pages/problemes/problemes-page').then((m) => m.ProblemesPage)
  },
  {
    path: 'echanges',
    title: 'Échanges — Planning Équipes',
    loadComponent: () => import('./pages/echanges/echanges-page').then((m) => m.EchangesPage)
  },
  {
    path: 'parametres',
    title: 'Paramètres — Planning Équipes',
    loadComponent: () => import('./pages/parametres/parametres-page').then((m) => m.ParametresPage)
  },
  {
    path: 'aide',
    title: 'Aide — Planning Équipes',
    loadComponent: () => import('./pages/aide/aide-page').then((m) => m.AidePage)
  },
  {
    path: 'what-if',
    title: 'What-if — Planning Équipes',
    loadComponent: () => import('./pages/what-if/what-if-page').then((m) => m.WhatIfPage)
  },
  {
    path: 'instantanes',
    title: 'Instantanés — Planning Équipes',
    loadComponent: () => import('./pages/snapshots/snapshots-page').then((m) => m.SnapshotsPage)
  },
  {
    path: 'editions',
    title: 'Éditions — Planning Équipes',
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
    title: 'Stands — Planning Équipes',
    loadComponent: () => import('./pages/stands/stands-page').then((m) => m.StandsPage)
  },
  {
    path: 'emplacements',
    title: 'Locations — Planning Équipes',
    loadComponent: () => import('./pages/emplacements/emplacements-page').then((m) => m.EmplacementsPage)
  },
  {
    path: 'animateurs',
    title: 'Animateurs — Planning Équipes',
    loadComponent: () => import('./pages/animateurs/animateurs-page').then((m) => m.AnimateursPage)
  },
  {
    path: 'creneaux',
    title: 'Créneaux — Planning Équipes',
    loadComponent: () => import('./pages/creneaux/creneaux-page').then((m) => m.CreneauxPage)
  },
  {
    path: 'typologies',
    title: 'Typologies — Planning Équipes',
    loadComponent: () => import('./pages/typologies/typologies-page').then((m) => m.TypologiesPage)
  },
  {
    path: 'ad-hoc-constraints',
    title: 'Ad hoc constraints — Planning Équipes',
    loadComponent: () => import('./pages/ad-hoc-constraints/ad-hoc-constraints-page').then((m) => m.AdHocConstraintsPage)
  },
  {
    path: 'verrouillages',
    title: 'Planning locks — Planning Équipes',
    loadComponent: () => import('./pages/verrouillages/verrouillages-page').then((m) => m.VerrouillagesPage)
  },
  {
    path: 'calendar',
    title: 'Assignment calendar — Planning Équipes',
    loadComponent: () => import('./pages/calendar-month/calendar-month-page').then((m) => m.CalendarMonthPage)
  },
  {
    path: 'day-calendar',
    title: 'Day calendar — Planning Équipes',
    loadComponent: () => import('./pages/calendar-day/calendar-day-page').then((m) => m.CalendarDayPage)
  },
  {
    path: 'constraints',
    title: 'Constraints — Planning Équipes',
    loadComponent: () => import('./pages/constraints/constraints-page').then((m) => m.ConstraintsPage)
  },
  {
    path: 'hours',
    title: 'Hours — Planning Équipes',
    loadComponent: () => import('./pages/hours/hours-page').then((m) => m.HoursPage)
  },
  {
    path: 'staffing',
    title: 'Staffing need — Planning Équipes',
    loadComponent: () => import('./pages/staffing/staffing-page').then((m) => m.StaffingPage)
  },
  {
    path: 'ouvertures',
    title: 'Stand opening schedule — Planning Équipes',
    loadComponent: () =>
      import('./pages/ouvertures/ouvertures-page').then((m) => m.OuverturesPage)
  },
  {
    path: 'heatmap',
    title: 'Load heatmap — Planning Équipes',
    loadComponent: () => import('./pages/heatmap/heatmap-page').then((m) => m.HeatmapPage)
  },
  {
    path: 'timeline',
    title: 'Animateur timeline — Planning Équipes',
    loadComponent: () =>
      import('./pages/animateur-timeline/animateur-timeline-page').then((m) => m.AnimateurTimelinePage)
  },
  { path: '**', redirectTo: '' }
];

export const routes: Routes = [
  {
    path: 'login',
    title: 'Connexion — Planning Équipes',
    loadComponent: () => import('./pages/login/login-page').then((m) => m.LoginPage)
  },
  {
    path: 'animateur/:jeton',
    loadComponent: () =>
      import('./pages/espace-animateur/espace-animateur-shell').then((m) => m.EspaceAnimateurShell),
    children: [
      {
        path: '',
        title: 'Mon planning — Planning Équipes',
        loadComponent: () =>
          import('./pages/espace-animateur/espace-planning-page').then((m) => m.EspacePlanningPage)
      },
      {
        path: 'echanges',
        title: 'Mes échanges — Planning Équipes',
        loadComponent: () =>
          import('./pages/espace-animateur/espace-echanges-page').then((m) => m.EspaceEchangesPage)
      }
    ]
  },
  {
    path: '',
    loadComponent: () => import('./shell/admin-shell').then((m) => m.AdminShell),
    children: adminRoutes
  }
];
