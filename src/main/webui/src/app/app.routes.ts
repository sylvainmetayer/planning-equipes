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
    title: () => $localize`:@@route.solver:Solveur`,
    loadComponent: () => import('./pages/solver/solver-page').then((m) => m.SolverPage)
  },
  {
    path: 'debug',
    title: () => $localize`:@@route.debug:Débogage`,
    loadComponent: () => import('./pages/debug/debug-page').then((m) => m.DebugPage)
  },
  {
    path: 'notifications',
    title: () => $localize`:@@route.notifications:Notifications`,
    loadComponent: () => import('./pages/notifications/notifications-page').then((m) => m.NotificationsPage)
  },
  {
    path: 'jour-j',
    title: () => $localize`:@@route.jourJ:Jour J`,
    loadComponent: () => import('./pages/jour-j/jour-j-page').then((m) => m.JourJPage)
  },
  {
    path: 'problemes',
    title: () => $localize`:@@route.problemes:Problèmes`,
    loadComponent: () => import('./pages/problemes/problemes-page').then((m) => m.ProblemesPage)
  },
  {
    path: 'echanges',
    title: () => $localize`:@@route.echanges:Échanges`,
    loadComponent: () => import('./pages/echanges/echanges-page').then((m) => m.EchangesPage)
  },
  {
    path: 'disponibilites',
    title: () => $localize`:@@route.disponibilites:Disponibilités`,
    loadComponent: () =>
      import('./pages/disponibilites/disponibilites-page').then((m) => m.DisponibilitesPage)
  },
  {
    // Not `/mcp`: that very path is the backend's MCP transport endpoint —
    // the SPA would never be served there (405 on GET).
    path: 'mcp-client',
    title: () => $localize`:@@route.mcp:MCP`,
    loadComponent: () => import('./pages/mcp/mcp-page').then((m) => m.McpPage)
  },
  {
    path: 'parametres',
    title: () => $localize`:@@route.parametres:Paramètres`,
    loadComponent: () => import('./pages/parametres/parametres-page').then((m) => m.ParametresPage)
  },
  {
    path: 'historique',
    title: () => $localize`:@@route.historique:Historique des actions`,
    loadComponent: () =>
      import('./pages/historique/historique-page').then((m) => m.HistoriquePage)
  },
  {
    path: 'aide',
    title: () => $localize`:@@route.aide:Aide`,
    loadComponent: () => import('./pages/aide/aide-page').then((m) => m.AidePage)
  },
  {
    path: 'graphe',
    title: () => $localize`:@@route.graphe:Graphe`,
    loadComponent: () => import('./pages/graphe/graphe-page').then((m) => m.GraphePage)
  },
  {
    path: 'kpi',
    title: () => $localize`:@@route.kpi:KPI`,
    loadComponent: () => import('./pages/kpi/kpi-page').then((m) => m.KpiPage)
  },
  {
    path: 'comparateur',
    title: () => $localize`:@@route.comparateur:Comparateur A/B`,
    loadComponent: () => import('./pages/comparateur/comparateur-page').then((m) => m.ComparateurPage)
  },
  {
    path: 'instantanes',
    title: () => $localize`:@@route.instantanes:Instantanés`,
    loadComponent: () => import('./pages/snapshots/snapshots-page').then((m) => m.SnapshotsPage)
  },
  {
    path: 'editions',
    title: () => $localize`:@@route.editions:Éditions`,
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
    title: () => $localize`:@@route.stands:Stands`,
    loadComponent: () => import('./pages/stands/stands-page').then((m) => m.StandsPage)
  },
  {
    path: 'emplacements',
    title: () => $localize`:@@route.emplacements:Emplacements`,
    loadComponent: () => import('./pages/emplacements/emplacements-page').then((m) => m.EmplacementsPage)
  },
  {
    path: 'animateurs',
    title: () => $localize`:@@route.animateurs:Animateurs`,
    loadComponent: () => import('./pages/animateurs/animateurs-page').then((m) => m.AnimateursPage)
  },
  {
    path: 'creneaux',
    title: () => $localize`:@@route.creneaux:Créneaux`,
    loadComponent: () => import('./pages/creneaux/creneaux-page').then((m) => m.CreneauxPage)
  },
  {
    path: 'import-animateurs',
    title: () => $localize`:@@route.importAnimateurs:Import animateurs`,
    loadComponent: () =>
      import('./pages/import-animateurs/import-animateurs-page').then((m) => m.ImportAnimateursPage)
  },
  {
    path: 'import-grille-stands',
    title: () => $localize`:@@route.importGrilleStands:Import grille des stands`,
    loadComponent: () =>
      import('./pages/import-grille-stands/import-grille-stands-page').then((m) => m.ImportGrilleStandsPage)
  },
  {
    path: 'typologies',
    title: () => $localize`:@@route.typologies:Typologies`,
    loadComponent: () => import('./pages/typologies/typologies-page').then((m) => m.TypologiesPage)
  },
  {
    path: 'ad-hoc-constraints',
    title: () => $localize`:@@route.adHocConstraints:Ajustements manuels`,
    loadComponent: () => import('./pages/ad-hoc-constraints/ad-hoc-constraints-page').then((m) => m.AdHocConstraintsPage)
  },
  {
    path: 'verrouillages',
    title: () => $localize`:@@route.verrouillages:Verrouillages`,
    loadComponent: () => import('./pages/verrouillages/verrouillages-page').then((m) => m.VerrouillagesPage)
  },
  {
    path: 'calendar',
    title: () => $localize`:@@route.calendar:Calendrier des affectations`,
    loadComponent: () => import('./pages/calendar-month/calendar-month-page').then((m) => m.CalendarMonthPage)
  },
  {
    path: 'day-calendar',
    title: () => $localize`:@@route.dayCalendar:Calendrier journalier`,
    loadComponent: () => import('./pages/calendar-day/calendar-day-page').then((m) => m.CalendarDayPage)
  },
  {
    path: 'constraints',
    title: () => $localize`:@@route.constraints:Contraintes`,
    loadComponent: () => import('./pages/constraints/constraints-page').then((m) => m.ConstraintsPage)
  },
  {
    path: 'hours',
    title: () => $localize`:@@route.hours:Heures`,
    loadComponent: () => import('./pages/hours/hours-page').then((m) => m.HoursPage)
  },
  {
    path: 'repos',
    title: () => $localize`:@@route.repos:Jours de repos`,
    loadComponent: () => import('./pages/repos/repos-page').then((m) => m.ReposPage)
  },
  {
    path: 'staffing',
    title: () => $localize`:@@route.staffing:Besoin en animateurs`,
    loadComponent: () => import('./pages/staffing/staffing-page').then((m) => m.StaffingPage)
  },
  {
    path: 'fragilite',
    title: () => $localize`:@@route.fragilite:Fragilité du planning`,
    loadComponent: () => import('./pages/fragilite/fragilite-page').then((m) => m.FragilitePage)
  },
  {
    path: 'pauses',
    title: () => $localize`:@@route.pauses:Pauses`,
    loadComponent: () => import('./pages/pauses/pauses-page').then((m) => m.PausesPage)
  },
  {
    path: 'banc-de-touche',
    title: () => $localize`:@@route.bancDeTouche:Banc de touche`,
    loadComponent: () =>
      import('./pages/banc-de-touche/banc-de-touche-page').then((m) => m.BancDeTouchePage)
  },
  {
    path: 'ouvertures',
    title: () => $localize`:@@route.ouvertures:Ouvertures des stands`,
    loadComponent: () =>
      import('./pages/ouvertures/ouvertures-page').then((m) => m.OuverturesPage)
  },
  {
    path: 'heatmap',
    title: () => $localize`:@@route.heatmap:Heatmap de charge`,
    loadComponent: () => import('./pages/heatmap/heatmap-page').then((m) => m.HeatmapPage)
  },
  {
    path: 'timeline',
    title: () => $localize`:@@route.timeline:Timeline animateur`,
    loadComponent: () =>
      import('./pages/animateur-timeline/animateur-timeline-page').then((m) => m.AnimateurTimelinePage)
  },
  {
    path: 'rail-jour',
    title: () => $localize`:@@route.railJour:Rail de la journée`,
    loadComponent: () => import('./pages/rail-jour/rail-jour-page').then((m) => m.RailJourPage)
  },
  {
    // Lazy like every other route, and that matters here beyond the rule:
    // `leaflet` must not reach the initial bundle, so nothing outside this
    // chunk and the /emplacements one may import it.
    path: 'carte-jour',
    title: () => $localize`:@@route.carteJour:Carte de la journée`,
    loadComponent: () => import('./pages/carte-jour/carte-jour-page').then((m) => m.CarteJourPage)
  },
  { path: '**', redirectTo: '' }
];

export const routes: Routes = [
  {
    path: 'login',
    title: () => $localize`:@@route.login:Connexion`,
    loadComponent: () => import('./pages/login/login-page').then((m) => m.LoginPage)
  },
  {
    // Outside both shells on purpose: a legal notice must stay readable
    // without a session and without a valid access token — the reader who
    // needs it most is often the one who has neither.
    path: 'mentions-legales',
    title: () => $localize`:@@route.mentionsLegales:Mentions légales`,
    loadComponent: () =>
      import('./pages/mentions-legales/mentions-legales-page').then((m) => m.MentionsLegalesPage)
  },
  {
    // Publique pour les mêmes raisons que les mentions légales : la personne
    // qui lit cette page est celle dont on traite les données, et elle n'a pas
    // toujours de session ni de lien valide.
    path: 'politique-confidentialite',
    title: () => $localize`:@@route.politiqueConfidentialite:Politique de confidentialité`,
    loadComponent: () =>
      import('./pages/mentions-legales/politique-confidentialite-page').then(
        (m) => m.PolitiqueConfidentialitePage
      )
  },
  {
    path: 'conditions-utilisation',
    title: () => $localize`:@@route.conditionsUtilisation:Conditions d'utilisation`,
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
        title: () => $localize`:@@route.espace.planning:Mon planning`,
        loadComponent: () =>
          import('./pages/espace-animateur/espace-planning-page').then((m) => m.EspacePlanningPage)
      },
      {
        path: 'echanges',
        title: () => $localize`:@@route.espace.echanges:Mes échanges`,
        loadComponent: () =>
          import('./pages/espace-animateur/espace-echanges-page').then((m) => m.EspaceEchangesPage)
      },
      {
        path: 'disponibilites',
        title: () => $localize`:@@route.espace.disponibilites:Mes disponibilités`,
        loadComponent: () =>
          import('./pages/espace-animateur/espace-disponibilites-page').then(
            (m) => m.EspaceDisponibilitesPage
          )
      },
      {
        path: 'aide',
        title: () => $localize`:@@route.espace.aide:Aide`,
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
