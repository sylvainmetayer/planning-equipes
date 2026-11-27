// The navigation drawer's content, the command palette's destinations and the
// `g`+letter table, in one place: data, kept apart from the shell that renders
// it. The drawer is one menu for everybody, grouped by moment of the edition's
// cycle; a screen answering a variant of another's question is one of its tabs.

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
  /**
   * The letter pressed after `g` to reach it: a letter of its label, the
   * initial wherever it was free, and never one another destination holds.
   */
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
 * The menu, one group per moment of the cycle: Accueil, Préparer, Planifier,
 * Diffuser, Aujourd'hui, Administrer — thirteen entries, a screen that answers
 * a variant of another's question being one of its tabs.
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
      // In the order an edition fills up: the stands and what they offer, the
      // calendar they open on, the people, and the files that fill all three.
      id: 'preparer',
      title: $localize`:@@nav.group.preparer:Préparer`,
      links: [
        {
          path: '/stands',
          label: $localize`:@@nav.link.stands:Stands`,
          icon: 'storefront',
          keywords: $localize`:@@nav.keywords.stands:typologies catégories de jeu emplacements`,
          tabs: [
            tab(
              'onglet',
              'lieux',
              $localize`:@@stands.onglet.lieuxCourt:Lieux`,
              $localize`:@@nav.keywords.lieux:emplacements carte`,
            ),
            tab('onglet', 'typologies', $localize`:@@nav.link.typologies:Typologies`),
          ],
        },
        {
          path: '/creneaux',
          label: $localize`:@@nav.link.calendrier:Calendrier`,
          icon: 'schedule',
          shortcut: 'c',
          keywords: $localize`:@@nav.keywords.calendrier:créneaux journées types`,
        },
        {
          path: '/animateurs',
          label: $localize`:@@nav.link.animateurs:Animateurs`,
          icon: 'groups',
          // `a` is Aujourd'hui's, the page of the day of the event.
          shortcut: 'n',
          tabs: [
            tab('onglet', 'competences', $localize`:@@nav.link.competences:Compétences`),
            tab('onglet', 'disponibilites', $localize`:@@nav.link.disponibilites:Disponibilités`),
            tab('onglet', 'covoiturage', $localize`:@@dispo.onglet.covoiturage:Covoiturage`),
          ],
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
      // The solve, the plan it produced read from every angle, what blocks
      // it, and what the next solve must respect.
      id: 'planifier',
      title: $localize`:@@nav.group.planifier:Planifier`,
      links: [
        {
          path: '/solveur',
          label: $localize`:@@nav.link.solver:Solveur`,
          icon: 'play_circle',
          shortcut: 's',
          keywords: $localize`:@@nav.keywords.solver:calculer résolution`,
          tabs: [
            tab(
              'onglet',
              'versions',
              $localize`:@@nav.link.versions:Versions du plan`,
              $localize`:@@nav.keywords.versions:versions instantanés comparateur autopsie restaurer historique résolutions`,
            ),
          ],
        },
        {
          // The page every reading of the plan starts from (issue #712): the
          // former Journée, which absorbed the month calendar and the graph:
          // four views, Jour, Stand, Personne and Carte.
          path: '/journee',
          label: $localize`:@@nav.link.journee:Planning`,
          icon: 'view_day',
          shortcut: 'p',
          // The bench left the Diagnostic for the Siège panel of this page:
          // « banc » still finds where it went; the screens it absorbed too.
          keywords: $localize`:@@nav.keywords.journee:journée calendrier banc de touche siège remplaçant placer imprimer`,
          tabs: [
            tab(
              'vue',
              'jour',
              $localize`:@@journee.vue.jour:Jour`,
              $localize`:@@nav.keywords.journeeTableau:tableau rail calendrier des affectations`,
            ),
            tab(
              'vue',
              'stand',
              $localize`:@@planning.vue.stand:Stand`,
              $localize`:@@nav.keywords.axeStand:heatmap couverture répartition des heures treemap`,
            ),
            tab(
              'vue',
              'personne',
              $localize`:@@planning.vue.personne:Personne`,
              $localize`:@@nav.keywords.axePersonne:heures paie équité jours de repos soirées frise`,
            ),
            tab(
              'vue',
              'carte',
              $localize`:@@journee.vue.carte:Carte`,
              $localize`:@@nav.keywords.journeeCarte:plan emplacements graphe`,
            ),
          ],
        },
        {
          path: '/diagnostic',
          label: $localize`:@@nav.link.diagnostic:Diagnostic`,
          icon: 'report_problem',
          // `d` is Diffuser's.
          shortcut: 'i',
          tabs: [
            tab('onglet', 'problemes', $localize`:@@diagnostic.onglet.problemes:Problèmes`),
            tab(
              'onglet',
              'besoin',
              $localize`:@@diagnostic.onglet.besoin:Besoin en animateurs`,
              $localize`:@@nav.keywords.besoin:staffing marge avant volumétrie typologie borne exacte`,
            ),
            tab(
              'onglet',
              'risques',
              $localize`:@@diagnostic.onglet.risques:Risques`,
              $localize`:@@nav.keywords.risques:fragilité à former formation tension marge disponible après résolution`,
            ),
            tab(
              'onglet',
              'conformite',
              $localize`:@@diagnostic.onglet.conformite:Conformité`,
              $localize`:@@nav.keywords.conformite:rapport inspection du travail RH plafonds mineurs`,
            ),
          ],
        },
        {
          // What the next solve must respect: the adjustments, the locks and
          // the consignes (issue #719, ADR 0043), then the rules and their
          // thresholds, their importance, and the solve budget (issue #720).
          path: '/regles',
          label: $localize`:@@nav.link.regles:Règles du planning`,
          icon: 'fact_check',
          shortcut: 'r',
          keywords: $localize`:@@nav.keywords.regles:contraintes poids importance paramètres légaux seuils consignes au solveur`,
          tabs: [
            tab(
              'onglet',
              'ajustements',
              $localize`:@@consignesSolveur.onglet.ajustements:Ajustements`,
              $localize`:@@nav.keywords.ajustements:affinités incompatibilités arrivée groupée`,
            ),
            tab(
              'onglet',
              'verrouillages',
              $localize`:@@consignesSolveur.onglet.verrouillages:Verrouillages`,
              $localize`:@@nav.keywords.verrouillages:figer geler`,
            ),
            tab(
              'onglet',
              'consignes',
              $localize`:@@consignesSolveur.onglet.consignes:Consignes`,
              $localize`:@@nav.keywords.consignes:arrêté canicule fermeture`,
            ),
            tab('onglet', 'regles', $localize`:@@regles.onglet.regles:Règles`),
          ],
        },
      ],
    },
    {
      id: 'diffuser',
      title: $localize`:@@nav.group.diffuser:Diffuser`,
      links: [
        {
          path: '/publication',
          label: $localize`:@@nav.link.diffuser:Diffuser`,
          icon: 'outgoing_mail',
          shortcut: 'd',
          keywords: $localize`:@@nav.keywords.diffuser:publier publication envoyer renvoyer relancer accusés pdf documents`,
          tabs: [
            tab('onglet', 'envoyer', $localize`:@@diffuser.onglet.envoyer:Envoyer`),
            tab('onglet', 'documents', $localize`:@@diffuser.onglet.documents:Documents`),
          ],
        },
        {
          path: '/echanges',
          label: $localize`:@@nav.link.echanges:Échanges`,
          icon: 'swap_horiz',
          shortcut: 'e',
          keywords: $localize`:@@nav.keywords.echanges:foire`,
          tabs: [
            tab(
              'onglet',
              'stats',
              $localize`:@@echanges.onglet.stats:Statistiques`,
              $localize`:@@nav.keywords.echanges.stats:taux acceptation délais volumes foire`,
            ),
          ],
        },
      ],
    },
    {
      id: 'aujourdhui',
      title: $localize`:@@nav.group.aujourdhui:Aujourd'hui`,
      links: [
        {
          path: '/aujourdhui',
          label: $localize`:@@nav.link.aujourdhui:Aujourd'hui`,
          icon: 'emergency',
          shortcut: 'a',
          keywords: $localize`:@@nav.keywords.aujourdhui:jour J absent remplacer téléphone TV mural`,
        },
      ],
    },
    {
      id: 'administrer',
      title: $localize`:@@nav.group.administrer:Administrer`,
      links: [
        {
          path: '/parametres',
          label: $localize`:@@nav.link.parametres:Paramètres`,
          icon: 'settings',
          tabs: [
            tab(
              'onglet',
              'edition',
              $localize`:@@parametres.onglet.edition:Édition`,
              $localize`:@@nav.keywords.parametresEdition:guichets collecte foire covoiturage météo contact`,
            ),
            tab(
              'onglet',
              'notifications',
              $localize`:@@parametres.onglet.notifications:Notifications`,
              $localize`:@@nav.keywords.parametresNotifications:e-mails automatiques rappel veille relance`,
            ),
            tab(
              'onglet',
              'legal',
              $localize`:@@regles.onglet.legal:Légal`,
              $localize`:@@nav.keywords.parametresLegal:plafonds Code du travail pause coupure repas durée hebdomadaire`,
            ),
            tab(
              'onglet',
              'organisation',
              $localize`:@@parametres.onglet.organisation:Organisation`,
              $localize`:@@nav.keywords.parametresOrganisation:seuils confort emplacements typologies jours consécutifs trajet`,
            ),
            tab(
              'onglet',
              'calcul',
              $localize`:@@regles.onglet.calcul:Calcul`,
              $localize`:@@nav.keywords.reglesCalcul:budget durée polyvalent soirée`,
            ),
            tab(
              'onglet',
              'mural',
              $localize`:@@parametres.onglet.mural:Affichage mural`,
              $localize`:@@nav.keywords.mural:tv télévision salle de contrôle`,
            ),
            tab(
              'onglet',
              'comptes',
              $localize`:@@parametres.onglet.comptes:Comptes`,
              $localize`:@@nav.keywords.comptes:utilisateurs organisateurs administrateurs droits habilitations accès`,
            ),
            tab(
              'onglet',
              'instance',
              $localize`:@@parametres.onglet.instance:Instance`,
              $localize`:@@nav.keywords.instance:sauvegarde sql raccourcis date simulée horloge globaux`,
            ),
            tab(
              'onglet',
              'historique',
              $localize`:@@nav.link.historique:Historique`,
              $localize`:@@nav.keywords.historique:journal actions`,
            ),
            tab(
              'onglet',
              'connexions',
              $localize`:@@historique.onglet.connexions:Connexions`,
              $localize`:@@nav.keywords.historiqueConnexions:connexion login mot de passe verrouillage adresse`,
            ),
            tab('onglet', 'mcp', $localize`:@@nav.link.mcp:MCP`, 'assistant ia'),
          ],
        },
      ],
    },
  ];
}

/**
 * The screens served everywhere but listed in no group: the raw technical
 * page, the editions, the help, the news and the Quarkus Dev UI. The palette finds
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
    // Reached by « Gérer » of the edition bar, above every page.
    {
      path: '/editions',
      label: $localize`:@@nav.link.editions:Éditions`,
      icon: 'layers',
      keywords: $localize`:@@nav.keywords.editions:phase préparation calcul publiée terminée gel figer débloquer structure`,
    },
    // The toolbar's « ? » icon, on every admin page.
    { path: '/aide', label: $localize`:@@nav.link.aide:Aide`, icon: 'help_outline' },
    // Opened from Versions and from the Éditions page with the pair to compare;
    // reached here too: two plan versions, or `?vue=editions` two editions.
    {
      path: '/comparer',
      label: $localize`:@@nav.link.comparer:Comparer deux versions`,
      icon: 'difference',
      keywords: $localize`:@@nav.keywords.comparer:différences écart score couverture année précédente référentiel`,
      tabs: [
        tab(
          'vue',
          'editions',
          $localize`:@@nav.link.comparerEditions:Comparer deux éditions`,
          $localize`:@@nav.keywords.comparerEditions:delta différences année précédente référentiel`,
        ),
      ],
    },
    // What the edition leaves behind (issue #272): the realised against the
    // published plan, and the indicators of the solves. Served without a menu
    // entry — the thirteen are the moments of the cycle —, found by the palette
    // and the help.
    {
      path: '/mesures',
      label: $localize`:@@nav.link.mesures:Mesures d'édition`,
      icon: 'balance',
      keywords: $localize`:@@nav.keywords.mesures:réalisé écart bilan absences remplacements tenu publié indicateurs historique kpi`,
      tabs: [
        tab(
          'vue',
          'indicateurs',
          $localize`:@@mesures.vue.indicateurs:Indicateurs des résolutions`,
          $localize`:@@nav.keywords.mesuresIndicateurs:historique kpi résolutions score couverture comparer éditions`,
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
