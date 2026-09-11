// The navigation drawer's content: one entry per route, mirroring the page
// split exactly. Data, kept apart from the shell that renders it.

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
}

export interface NavGroup {
  /** Stable across languages: what the collapsed state is stored under. */
  id: string;
  title: string;
  links: NavLink[];
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
        { path: '/', label: $localize`:@@nav.link.solver:Solveur`, icon: 'play_circle' },
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
          path: '/problemes',
          label: $localize`:@@nav.link.problemes:Problèmes`,
          icon: 'report_problem',
        },
        {
          path: '/constraints',
          label: $localize`:@@nav.link.constraints:Contraintes`,
          icon: 'fact_check',
        },
        {
          path: '/instantanes',
          label: $localize`:@@nav.link.snapshots:Instantanés`,
          icon: 'history',
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
        // The only one of these that *writes*: it records real forced
        // unavailabilities and empties real seats of the persisted plan. Its
        // banner says so rather than borrowing the default wording.
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
          path: '/staffing',
          label: $localize`:@@nav.link.staffing:Besoin en animateurs`,
          icon: 'engineering',
        },
        {
          path: '/fragilite',
          label: $localize`:@@nav.link.fragilite:Fragilité du planning`,
          icon: 'personal_injury',
        },
        {
          path: '/banc-de-touche',
          label: $localize`:@@nav.link.bancDeTouche:Banc de touche`,
          icon: 'airline_seat_recline_normal',
        },
        {
          path: '/kpi',
          label: $localize`:@@nav.link.kpi:Autopsie du planning`,
          icon: 'query_stats',
        },
        {
          path: '/comparateur',
          label: $localize`:@@nav.link.comparateur:Comparateur A/B`,
          icon: 'compare_arrows',
        },
      ],
    },
    {
      id: 'reference-data',
      title: $localize`:@@nav.group.referenceData:Données de référence`,
      links: [
        { path: '/stands', label: $localize`:@@nav.link.stands:Stands`, icon: 'storefront' },
        {
          path: '/emplacements',
          label: $localize`:@@nav.link.emplacements:Emplacements`,
          icon: 'place',
        },
        {
          path: '/animateurs',
          label: $localize`:@@nav.link.animateurs:Animateurs`,
          icon: 'groups',
        },
        { path: '/creneaux', label: $localize`:@@nav.link.creneaux:Créneaux`, icon: 'schedule' },
        {
          path: '/typologies',
          label: $localize`:@@nav.link.typologies:Typologies`,
          icon: 'category',
        },
        {
          path: '/import-animateurs',
          label: $localize`:@@nav.link.importAnimateurs:Import CSV des animateurs`,
          icon: 'table_view',
        },
        {
          path: '/import-grille-stands',
          label: $localize`:@@nav.link.importGrilleStands:Import de la grille des stands`,
          icon: 'grid_view',
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
        {
          path: '/day-calendar',
          label: $localize`:@@nav.link.dayCalendar:Calendrier journalier`,
          icon: 'view_day',
        },
        { path: '/hours', label: $localize`:@@nav.link.hours:Heures`, icon: 'schedule' },
        { path: '/repos', label: $localize`:@@nav.link.repos:Jours de repos`, icon: 'weekend' },
        {
          path: '/heatmap',
          label: $localize`:@@nav.link.heatmap:Heatmap de charge`,
          icon: 'grid_view',
        },
        {
          path: '/timeline',
          label: $localize`:@@nav.link.timeline:Timeline animateur`,
          icon: 'timeline',
        },
        {
          path: '/rail-jour',
          label: $localize`:@@nav.link.railJour:Rail de la journée`,
          icon: 'view_timeline',
        },
        {
          path: '/carte-jour',
          label: $localize`:@@nav.link.carteJour:Carte de la journée`,
          icon: 'map',
        },
        { path: '/pauses', label: $localize`:@@nav.link.pauses:Pauses`, icon: 'free_breakfast' },
        { path: '/graphe', label: $localize`:@@nav.link.graphe:Graphe`, icon: 'hub' },
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
          path: '/historique',
          label: $localize`:@@nav.link.historique:Historique`,
          icon: 'manage_search',
        },

        { path: '/mcp-client', label: $localize`:@@nav.link.mcp:MCP`, icon: 'smart_toy' },
        { path: '/debug', label: $localize`:@@nav.link.debug:Débogage`, icon: 'bug_report' },
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
