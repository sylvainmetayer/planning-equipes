import { Routes } from '@angular/router';

/** One route per functional block; every page is lazy-loaded. */
export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'solver' },
  {
    path: 'solver',
    title: 'Solver — Planning Équipes',
    loadComponent: () => import('./pages/solver/solver-page').then((m) => m.SolverPage)
  },
  {
    path: 'data-setup',
    title: 'Data setup — Planning Équipes',
    loadComponent: () => import('./pages/data-setup/data-setup-page').then((m) => m.DataSetupPage)
  },
  { path: 'exports', redirectTo: 'solver' },
  {
    path: 'data-transfer',
    title: 'Data transfer — Planning Équipes',
    loadComponent: () => import('./pages/data-transfer/data-transfer-page').then((m) => m.DataTransferPage)
  },
  {
    path: 'stands',
    title: 'Stands — Planning Équipes',
    loadComponent: () => import('./pages/stands/stands-page').then((m) => m.StandsPage)
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
  { path: '**', redirectTo: 'solver' }
];
