// The navigation drawer's content, the command palette's destinations and the
// `g`+letter table, in one place: data, kept apart from the shell that renders
// it. The drawer is one menu for everybody (no « simple » / « avancé » mode),
// grouped by moment of the edition's cycle; a screen that is rare goes down
// its group, it is not hidden.

/**
 * A tab or a view of a page, reachable from the palette as « Page › Tab »:
 * the address the page reads (`?onglet=`, `?vue=`, `?mode=`…) and the words a
 * reader may type to find it.
 */
export interface NavTab {
  /** The query param the page reads its tab or view from. */
  param: string;
  value: string;
  label: string;
  /** Extra words the palette matches, beyond the label: « swagger » finds the raw analysis. */
  keywords?: string;
}

export interface NavLink {
  path: string;
  label: string;
  /** A Material Icons ligature: `nav-groups.spec.ts` refuses two entries sharing one. */
  icon: string;
  /**
   * Served by the backend rather than by the Angular router (the Quarkus Dev
   * UI): the palette opens it in a new tab, since routing to it would only
   * produce a client-side 404. Listed in no group, so the drawer never draws one.
   */
  externe?: boolean;
  /** The letter pressed after `g` to reach it: the initial of the label wherever it was free. */
  shortcut?: string;
  /** Its tabs or views, indexed by the palette. */
  tabs?: NavTab[];
  /** Words the palette matches beyond the label. */
  keywords?: string;
}

export interface NavGroup {
  /** Stable across languages: what the collapsed state is stored under. */
  id: string;
  title: string;
  links: NavLink[];
}

/**
 * The menu, one group per moment of the cycle: Accueil, Planning, Préparer,
 * Construire, Diffuser, Aujourd'hui, Administrer.
 *
 * <p>Built lazily (called from a constructor, not at module scope): $localize
 * resolves translations from whatever `loadTranslations()` has registered at
 * call time, and that only happens once `main.ts` has fetched the English
 * catalog — before `bootstrapApplication()` runs, but after this module has
 * already been imported.</p>
 */
export function buildNavGroups(): NavGroup[] {
  return [
    {
      id: 'accueil',
      title: $localize`:@@nav.group.accueil:Accueil`,
      links: [
        {
          path: '/',
          label: $localize`:@@nav.link.accueil:État de l'édition`,
          icon: 'checklist',
          // The historic « go home » pair, kept: `e` is Échanges'.
          shortcut: 'g',
          // The bell's destination: « À traiter aujourd'hui » and the recent messages.
          keywords: $localize`:@@nav.keywords.accueil:à traiter notifications messages alertes démarrer`,
        },
      ],
    },
    {
      // The plan read from every angle, until the Planning page gathers them.
      id: 'planning',
      title: $localize`:@@nav.group.planning:Planning`,
      links: [
        {
          path: '/journee',
          label: $localize`:@@nav.link.journee:Journée`,
          icon: 'view_day',
          shortcut: 'j',
          // The bench left the Diagnostic for the Siège panel of this page:
          // « banc » still finds where it went.
          keywords: $localize`:@@nav.keywords.journee:banc de touche siège remplaçant placer`,
          tabs: [
            tab('vue', 'calendrier', $localize`:@@journee.vue.calendrier:Calendrier`),
            tab('vue', 'rail', $localize`:@@journee.vue.rail:Rail`),
            tab(
              'vue',
              'carte',
              $localize`:@@journee.vue.carte:Carte`,
              $localize`:@@nav.keywords.journeeCarte:plan emplacements`,
            ),
            tab(
              'vue',
              'pauses',
              $localize`:@@journee.vue.pauses:Pauses`,
              $localize`:@@nav.keywords.journeePauses:relais repas`,
            ),
            tab('vue', 'changements', $localize`:@@journee.vue.changements:Changements`),
          ],
        },
        {
          path: '/calendar',
          label: $localize`:@@nav.link.calendar:Calendrier des affectations`,
          icon: 'calendar_month',
        },
        {
          path: '/hours',
          label: $localize`:@@nav.link.hours:Heures`,
          icon: 'timer',
          shortcut: 'h',
          keywords: $localize`:@@nav.keywords.hours:paie`,
        },
        { path: '/equite', label: $localize`:@@nav.link.equite:Équité`, icon: 'balance' },
        { path: '/repos', label: $localize`:@@nav.link.repos:Jours de repos`, icon: 'weekend' },
        {
          path: '/heatmap',
          label: $localize`:@@nav.link.heatmap:Heatmap de charge`,
          icon: 'grid_view',
        },
        {
          path: '/marge',
          label: $localize`:@@nav.link.marge:Marge disponible`,
          icon: 'exposure',
          tabs: [
            tab('mode', 'apres', $localize`:@@marge.mode.apres:Après résolution`),
            tab('mode', 'tension', $localize`:@@nav.tab.margeTension:Tension`),
          ],
        },
        { path: '/graphe', label: $localize`:@@nav.link.graphe:Graphe`, icon: 'hub' },
        {
          path: '/intendance',
          label: $localize`:@@nav.link.intendance:Intendance des repas`,
          icon: 'restaurant',
        },
        {
          path: '/repartition-heures',
          label: $localize`:@@nav.link.repartitionHeures:Répartition des heures`,
          icon: 'dashboard',
        },
        {
          path: '/typologies-planning',
          label: $localize`:@@nav.link.typologiesPlanning:Planning par typologie`,
          icon: 'donut_small',
        },
      ],
    },
    {
      // In the order an edition fills up: everything starts from the game
      // categories, the timeslots give the edition its dates before the
      // stands say when they open (ADR 0032), and a stand stands on a
      // location.
      id: 'preparer',
      title: $localize`:@@nav.group.preparer:Préparer`,
      links: [
        {
          path: '/typologies',
          label: $localize`:@@nav.link.typologies:Typologies`,
          icon: 'category',
          shortcut: 't',
        },
        {
          path: '/creneaux',
          label: $localize`:@@nav.link.creneaux:Créneaux`,
          icon: 'schedule',
          shortcut: 'c',
        },
        {
          path: '/emplacements',
          label: $localize`:@@nav.link.emplacements:Emplacements`,
          icon: 'place',
        },
        { path: '/stands', label: $localize`:@@nav.link.stands:Stands`, icon: 'storefront' },
        {
          path: '/ouvertures',
          label: $localize`:@@nav.link.ouvertures:Horaires des stands`,
          icon: 'door_front',
          keywords: $localize`:@@nav.keywords.ouvertures:ouvertures saisie calendrier combiné`,
          tabs: [
            tab(
              'vue',
              'journees-types',
              $localize`:@@ouvertures.vue.journeesTypes:Par journée type`,
            ),
            tab('vue', 'comparer', $localize`:@@ouvertures.vue.comparer:Comparer`),
          ],
        },
        {
          path: '/animateurs',
          label: $localize`:@@nav.link.animateurs:Animateurs`,
          icon: 'groups',
          shortcut: 'a',
        },
        {
          path: '/competences',
          label: $localize`:@@nav.link.competences:Compétences`,
          icon: 'grid_on',
        },
        {
          path: '/disponibilites',
          label: $localize`:@@nav.link.disponibilites:Disponibilités`,
          icon: 'event_available',
          tabs: [tab('onglet', 'covoiturage', $localize`:@@dispo.onglet.covoiturage:Covoiturage`)],
        },
        {
          // Imports, Export and the end-of-event archive: one page, three tabs.
          path: '/fichiers',
          label: $localize`:@@nav.link.fichiers:Fichiers`,
          icon: 'folder_open',
          shortcut: 'f',
          keywords: $localize`:@@nav.keywords.fichiers:importer exporter csv tableur`,
          tabs: [
            tab(
              'cible',
              'typologies',
              $localize`:@@nav.tab.importTypologies:Importer des typologies`,
            ),
            tab(
              'cible',
              'emplacements',
              $localize`:@@nav.tab.importEmplacements:Importer des emplacements`,
            ),
            tab('cible', 'stands', $localize`:@@nav.tab.importStands:Importer des stands`),
            tab('cible', 'creneaux', $localize`:@@nav.tab.importCreneaux:Importer des créneaux`),
            tab(
              'cible',
              'journees-types',
              $localize`:@@nav.tab.importJourneesTypes:Importer des journées types`,
            ),
            tab(
              'cible',
              'animateurs',
              $localize`:@@nav.tab.importAnimateurs:Importer des animateurs`,
            ),
            tab('cible', 'grille-stands', $localize`:@@imports.onglet.grille:Grille des stands`),
            tab('cible', 'scenario', $localize`:@@imports.onglet.scenario:Scénario`, 'yaml'),
            tab(
              'cible',
              'exemples',
              $localize`:@@imports.onglet.exemples:Exemples`,
              $localize`:@@nav.keywords.exemples:scénario démonstration démo charger`,
            ),
            tab(
              'cible',
              'verifier',
              $localize`:@@imports.onglet.verifier:Vérifier un fichier`,
              $localize`:@@nav.keywords.verifier:validateur yaml`,
            ),
            tab('onglet', 'exporter', $localize`:@@fichiers.onglet.exporter:Exporter`, 'csv yaml'),
            tab(
              'onglet',
              'archive',
              $localize`:@@fichiers.onglet.archive:Archive`,
              $localize`:@@nav.keywords.archive:fin d'événement zip`,
            ),
          ],
        },
      ],
    },
    {
      // What the next solve receives, the solve itself and what it produced.
      id: 'construire',
      title: $localize`:@@nav.group.construire:Construire`,
      links: [
        {
          path: '/solveur',
          label: $localize`:@@nav.link.solver:Solveur`,
          icon: 'play_circle',
          shortcut: 's',
          keywords: $localize`:@@nav.keywords.solver:calculer résolution`,
        },
        {
          path: '/diagnostic',
          label: $localize`:@@nav.link.diagnostic:Diagnostic`,
          icon: 'report_problem',
          shortcut: 'd',
          tabs: [
            tab('onglet', 'problemes', $localize`:@@diagnostic.onglet.problemes:Problèmes`),
            tab(
              'onglet',
              'besoin',
              $localize`:@@diagnostic.onglet.besoin:Besoin en animateurs`,
              'staffing',
            ),
            tab('onglet', 'fragilite', $localize`:@@diagnostic.onglet.fragilite:Fragilité`),
            tab(
              'onglet',
              'former',
              $localize`:@@diagnostic.onglet.former:À former`,
              $localize`:@@nav.keywords.former:formation`,
            ),
          ],
        },
        {
          path: '/ad-hoc-constraints',
          label: $localize`:@@nav.link.adHocConstraints:Ajustements manuels`,
          icon: 'rule',
          tabs: [
            tab(
              'vue',
              'reseau',
              $localize`:@@nav.tab.reseau:Réseau`,
              $localize`:@@nav.keywords.reseau:affinités incompatibilités paires`,
            ),
          ],
        },
        {
          path: '/verrouillages',
          label: $localize`:@@nav.link.verrouillages:Verrouillages`,
          icon: 'lock',
          shortcut: 'v',
        },
        {
          // Closing every stand on a band of one date, by decision (ADR 0043).
          path: '/consignes',
          label: $localize`:@@nav.link.consignes:Consignes`,
          icon: 'policy',
          keywords: $localize`:@@nav.keywords.consignes:arrêté canicule fermeture`,
        },
        {
          path: '/constraints',
          label: $localize`:@@nav.link.constraints:Contraintes`,
          icon: 'fact_check',
          keywords: $localize`:@@nav.keywords.constraints:règles poids`,
        },
        {
          path: '/instantanes',
          label: $localize`:@@nav.link.snapshots:Instantanés`,
          icon: 'history',
          shortcut: 'i',
        },
        {
          path: '/comparateur',
          label: $localize`:@@nav.link.comparateur:Comparateur A/B`,
          icon: 'compare_arrows',
        },
        {
          path: '/kpi',
          label: $localize`:@@nav.link.kpi:Autopsie du planning`,
          icon: 'query_stats',
        },
      ],
    },
    {
      id: 'diffuser',
      title: $localize`:@@nav.group.diffuser:Diffuser`,
      links: [
        {
          path: '/publication',
          label: $localize`:@@nav.link.publication:Publication`,
          icon: 'outgoing_mail',
          shortcut: 'p',
          keywords: $localize`:@@nav.keywords.publication:publier envoyer pdf`,
        },
        {
          path: '/echanges',
          label: $localize`:@@nav.link.echanges:Échanges`,
          icon: 'swap_horiz',
          shortcut: 'e',
          keywords: $localize`:@@nav.keywords.echanges:foire`,
        },
      ],
    },
    {
      id: 'aujourdhui',
      title: $localize`:@@nav.group.aujourdhui:Aujourd'hui`,
      links: [
        {
          path: '/jour-j',
          label: $localize`:@@nav.link.jourJ:Mode jour J`,
          icon: 'emergency',
          shortcut: 'm',
          keywords: $localize`:@@nav.keywords.jourJ:absent remplacer`,
        },
      ],
    },
    {
      id: 'administrer',
      title: $localize`:@@nav.group.administrer:Administrer`,
      links: [
        { path: '/editions', label: $localize`:@@nav.link.editions:Éditions`, icon: 'layers' },
        {
          path: '/parametres',
          label: $localize`:@@nav.link.parametres:Paramètres`,
          icon: 'settings',
          tabs: [
            tab(
              'onglet',
              'legaux',
              $localize`:@@parametres.onglet.legaux:Légaux`,
              $localize`:@@nav.keywords.legaux:paramètres légaux durée repos`,
            ),
            tab(
              'onglet',
              'edition',
              $localize`:@@parametres.onglet.edition:Édition`,
              $localize`:@@nav.keywords.parametresEdition:ninja seuils`,
            ),
            tab(
              'onglet',
              'emails',
              $localize`:@@parametres.onglet.emails:E-mails automatiques`,
              $localize`:@@nav.keywords.emails:relances rappels`,
            ),
            tab(
              'onglet',
              'mural',
              $localize`:@@parametres.onglet.mural:Affichage mural`,
              $localize`:@@nav.keywords.mural:tv télévision salle de contrôle`,
            ),
            tab(
              'onglet',
              'instance',
              $localize`:@@parametres.onglet.instance:Instance`,
              $localize`:@@nav.keywords.instance:sauvegarde sql raccourcis date simulée horloge`,
            ),
          ],
        },
        {
          path: '/historique',
          label: $localize`:@@nav.link.historique:Historique`,
          icon: 'manage_search',
          keywords: $localize`:@@nav.keywords.historique:journal actions`,
        },
        { path: '/aide', label: $localize`:@@nav.link.aide:Aide`, icon: 'help_outline' },
        { path: '/mcp-client', label: $localize`:@@nav.link.mcp:MCP`, icon: 'smart_toy' },
      ],
    },
  ];
}

/**
 * The screens served everywhere but listed in no group: the raw technical
 * page, the news and the Quarkus Dev UI. The palette finds
 * them on a query and never on an empty one; an address still answers.
 */
export function buildOffMenuLinks(devMode: boolean): NavLink[] {
  return [
    {
      path: '/debug',
      label: $localize`:@@nav.link.debug:Débogage`,
      icon: 'bug_report',
      keywords: $localize`:@@nav.keywords.debug:json swagger mailpit version technique`,
      tabs: [
        tab(
          'onglet',
          'resolution',
          $localize`:@@debug.onglet.resolution:Résolution`,
          $localize`:@@nav.keywords.debugResolution:json swagger api version analyse`,
        ),
        tab(
          'onglet',
          'verifications',
          $localize`:@@debug.onglet.verifications:Vérifications`,
          $localize`:@@nav.keywords.debugVerifications.brut:mailpit mail test exception pgadmin`,
        ),
      ],
    },
    {
      path: '/nouveautes',
      label: $localize`:@@nav.link.nouveautes:Nouveautés`,
      icon: 'new_releases',
      keywords: 'version changelog',
    },
    // Dev mode only: in a packaged application the Dev UI does not exist, and
    // the entry would lead nowhere.
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
  ];
}

/**
 * The four public legal pages: at the foot of the drawer, in text, and in the
 * palette on a query only. The accessibility statement must stay reachable
 * from every page (RGAA), which the foot of the drawer is.
 */
export function buildLegalLinks(): NavLink[] {
  return [
    {
      path: '/mentions-legales',
      label: $localize`:@@nav.link.mentionsLegales:Mentions légales`,
      icon: 'gavel',
    },
    {
      path: '/politique-confidentialite',
      label: $localize`:@@nav.link.confidentialite:Politique de confidentialité`,
      icon: 'privacy_tip',
      keywords: 'rgpd',
    },
    {
      path: '/conditions-utilisation',
      label: $localize`:@@nav.link.cgu:Conditions d'utilisation`,
      icon: 'handshake',
      keywords: 'cgu',
    },
    {
      path: '/declaration-accessibilite',
      label: $localize`:@@nav.link.accessibilite:Accessibilité`,
      icon: 'accessibility_new',
      keywords: 'rgaa',
    },
  ];
}

function tab(param: string, value: string, label: string, keywords?: string): NavTab {
  return keywords === undefined ? { param, value, label } : { param, value, label, keywords };
}
