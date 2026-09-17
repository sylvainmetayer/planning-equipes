// The navigation drawer's content: one entry per route, mirroring the page
// split exactly. Data, kept apart from the shell that renders it.

import { NavMode } from '../core/nav-mode';

export interface NavLink {
  path: string;
  label: string;
  icon: string;
  /** Shows the unread notification count as a mat-badge on this link only. */
  /**
   * Served by the backend rather than by the Angular router (the Quarkus Dev
   * UI): rendered as a plain anchor opening a new tab, since routing to it
   * would only produce a client-side 404.
   */
  externe?: boolean;
  /**
   * Only needed for a deep diagnostic: hidden while the menu is in its
   * `simple` mode (`core/nav-mode`), which is the default. The route, the
   * command palette and the links of the help still reach it — this is a
   * matter of what the drawer lists, never of rights.
   */
  avance?: boolean;
}

export interface NavGroup {
  /** Stable across languages: what the collapsed state is stored under. */
  id: string;
  title: string;
  links: NavLink[];
}

/**
 * The groups the drawer lists under a mode: every entry in `avance`; in
 * `simple`, the entries not flagged `avance` — plus the one carrying the route
 * currently displayed, so a link from the help or the palette to a hidden
 * screen shows where the reader landed, for the time of the visit. A group
 * left with no entry disappears with them.
 */
export function visibleNavGroups(
  groups: readonly NavGroup[],
  mode: NavMode,
  currentPath: string,
): NavGroup[] {
  if (mode === 'avance') {
    return [...groups];
  }
  return groups
    .map((group) => ({
      ...group,
      links: group.links.filter((link) => !link.avance || link.path === currentPath),
    }))
    .filter((group) => group.links.length > 0);
}

/**
 * One entry per route: the navigation mirrors the page split exactly.
 *
 * Built lazily (called from the component constructor, not at module scope):
 * $localize resolves translations from whatever `loadTranslations()` has
 * registered at call time, and that only happens once `main.ts` has fetched
 * the English catalog — before `bootstrapApplication()` runs, but after this
 * module has already been imported.
 */
export function buildNavGroups(devMode: boolean): NavGroup[] {
  return [
    {
      id: 'planning',
      title: $localize`:@@nav.group.planning:Planning`,
      links: [
        {
          path: '/',
          label: $localize`:@@nav.link.accueil:État de l'édition`,
          icon: 'checklist',
        },
        { path: '/solveur', label: $localize`:@@nav.link.solver:Solveur`, icon: 'play_circle' },
        // Right under the solver: it is the step after, and the one that
        // reaches real people (issue #320).
        {
          path: '/publication',
          label: $localize`:@@nav.link.publication:Publication`,
          icon: 'outgoing_mail',
        },
        {
          path: '/disponibilites',
          label: $localize`:@@nav.link.disponibilites:Disponibilités`,
          icon: 'event_available',
        },
        {
          path: '/ad-hoc-constraints',
          label: $localize`:@@nav.link.adHocConstraints:Ajustements manuels`,
          icon: 'rule',
        },
        {
          path: '/verrouillages',
          label: $localize`:@@nav.link.verrouillages:Verrouillages`,
          icon: 'lock',
        },
        {
          path: '/diagnostic',
          label: $localize`:@@nav.link.diagnostic:Diagnostic`,
          icon: 'report_problem',
        },
        {
          path: '/constraints',
          label: $localize`:@@nav.link.constraints:Contraintes`,
          icon: 'fact_check',
          avance: true,
        },
        {
          path: '/instantanes',
          label: $localize`:@@nav.link.snapshots:Instantanés`,
          icon: 'history',
          avance: true,
        },
      ],
    },
    {
      // What happens once the plan is out: the animateurs trading seats among
      // themselves, and the day itself. Kept apart from « Planning » because
      // these two screens act on the published plan, not on the next solve.
      id: 'pendant-evenement',
      title: $localize`:@@nav.group.pendantEvenement:Pendant l'événement`,
      links: [
        { path: '/echanges', label: $localize`:@@nav.link.echanges:Échanges`, icon: 'swap_horiz' },
        // Both screens write on the published plan — « Échanges » applies or
        // refuses a trade — but this one is the blunt instrument: it records
        // real forced unavailabilities and empties real seats. Its banner says
        // so rather than borrowing the default wording.
        { path: '/jour-j', label: $localize`:@@nav.link.jourJ:Mode jour J`, icon: 'emergency' },
      ],
    },
    {
      id: 'decision-support',
      title: $localize`:@@nav.group.decisionSupport:Aide à la décision`,
      links: [
        {
          path: '/ouvertures',
          label: $localize`:@@nav.link.ouvertures:Ouvertures des stands`,
          icon: 'storefront',
        },
        {
          path: '/kpi',
          label: $localize`:@@nav.link.kpi:Autopsie du planning`,
          icon: 'query_stats',
          avance: true,
        },
        {
          path: '/comparateur',
          label: $localize`:@@nav.link.comparateur:Comparateur A/B`,
          icon: 'compare_arrows',
          avance: true,
        },
      ],
    },
    {
      id: 'reference-data',
      title: $localize`:@@nav.group.referenceData:Données de référence`,
      // Dans l'ordre où une édition se remplit : tout part des typologies, et
      // les créneaux donnent ses dates à l'édition avant que les stands ne
      // disent leurs ouvertures (ADR 0032).
      links: [
        {
          path: '/typologies',
          label: $localize`:@@nav.link.typologies:Typologies`,
          icon: 'category',
        },
        {
          path: '/emplacements',
          label: $localize`:@@nav.link.emplacements:Emplacements`,
          icon: 'place',
        },
        { path: '/creneaux', label: $localize`:@@nav.link.creneaux:Créneaux`, icon: 'schedule' },
        { path: '/stands', label: $localize`:@@nav.link.stands:Stands`, icon: 'storefront' },
        {
          path: '/animateurs',
          label: $localize`:@@nav.link.animateurs:Animateurs`,
          icon: 'groups',
        },
        {
          path: '/competences',
          label: $localize`:@@nav.link.competences:Compétences`,
          icon: 'grid_on',
        },
        {
          path: '/imports',
          label: $localize`:@@nav.link.imports:Imports`,
          icon: 'upload_file',
        },
        {
          // `file_export` n'existe pas dans la police Material Icons embarquée
          // (c'est un nom Material Symbols) : un <mat-icon> sans glyphe affiche
          // son texte rogné. `file_download` est le miroir de l'import.
          path: '/exports',
          label: $localize`:@@nav.link.exports:Export`,
          icon: 'file_download',
        },
      ],
    },
    {
      id: 'views',
      title: $localize`:@@nav.group.views:Vues`,
      links: [
        {
          path: '/calendar',
          label: $localize`:@@nav.link.calendar:Calendrier des affectations`,
          icon: 'calendar_month',
        },
        { path: '/journee', label: $localize`:@@nav.link.journee:Journée`, icon: 'view_day' },
        { path: '/hours', label: $localize`:@@nav.link.hours:Heures`, icon: 'schedule' },
        {
          path: '/intendance',
          label: $localize`:@@nav.link.intendance:Intendance des repas`,
          icon: 'restaurant',
          avance: true,
        },
        {
          path: '/typologies-planning',
          label: $localize`:@@nav.link.typologiesPlanning:Planning par typologie`,
          icon: 'category',
          avance: true,
        },
        {
          path: '/equite',
          label: $localize`:@@nav.link.equite:Équité`,
          icon: 'balance',
          avance: true,
        },
        {
          path: '/repos',
          label: $localize`:@@nav.link.repos:Jours de repos`,
          icon: 'weekend',
          avance: true,
        },
        {
          path: '/heatmap',
          label: $localize`:@@nav.link.heatmap:Heatmap de charge`,
          icon: 'grid_view',
          avance: true,
        },
        {
          path: '/marge',
          label: $localize`:@@nav.link.marge:Marge disponible`,
          icon: 'exposure',
          avance: true,
        },
        {
          path: '/timeline',
          label: $localize`:@@nav.link.timeline:Timeline animateur`,
          icon: 'timeline',
          avance: true,
        },
        { path: '/graphe', label: $localize`:@@nav.link.graphe:Graphe`, icon: 'hub', avance: true },
      ],
    },
    {
      id: 'tools',
      title: $localize`:@@nav.group.tools:Outils`,
      links: [
        { path: '/editions', label: $localize`:@@nav.link.editions:Éditions`, icon: 'layers' },
        {
          path: '/parametres',
          label: $localize`:@@nav.link.parametres:Paramètres`,
          icon: 'settings',
        },
        { path: '/aide', label: $localize`:@@nav.link.aide:Aide`, icon: 'help_outline' },
        {
          path: '/nouveautes',
          label: $localize`:@@nav.link.nouveautes:Nouveautés`,
          icon: 'new_releases',
        },
        {
          path: '/historique',
          label: $localize`:@@nav.link.historique:Historique`,
          icon: 'manage_search',
          avance: true,
        },

        {
          path: '/mcp-client',
          label: $localize`:@@nav.link.mcp:MCP`,
          icon: 'smart_toy',
          avance: true,
        },
        {
          path: '/debug',
          label: $localize`:@@nav.link.debug:Débogage`,
          icon: 'bug_report',
          avance: true,
        },
        {
          path: '/mentions-legales',
          label: $localize`:@@nav.link.mentionsLegales:Mentions légales`,
          icon: 'gavel',
        },
        {
          path: '/politique-confidentialite',
          label: $localize`:@@nav.link.confidentialite:Politique de confidentialité`,
          icon: 'privacy_tip',
        },
        {
          path: '/conditions-utilisation',
          label: $localize`:@@nav.link.cgu:Conditions d'utilisation`,
          icon: 'handshake',
        },
        // Dev mode only: in a packaged application the Dev UI does not exist,
        // and the entry would lead nowhere.
        ...(devMode
          ? [
              {
                path: '/q/dev-ui',
                label: $localize`:@@nav.link.devUi:Quarkus Dev UI`,
                icon: 'developer_mode',
                externe: true,
              },
            ]
          : []),
      ],
    },
  ];
}
