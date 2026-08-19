/**
 * Content of the in-app user guide, kept as data rather than as template
 * markup: the page renders it generically, the filter box searches it, and a
 * unit test can assert on it without rendering anything.
 *
 * Every string is a translatable message, so the guide is bilingual like the
 * rest of the UI. See `docs/` for the technical documentation this summarises
 * — the guide answers "how do I use this screen", not "how is it built".
 */

/** Route the reader should open to act on what a section describes. */
export interface HelpLink {
  route: string;
  label: string;
}

/** A term (a screen, a setting, a score component) and what it means. */
export interface HelpDefinition {
  term: string;
  text: string;
}

export type HelpBlock =
  | { kind: 'paragraph'; text: string }
  | { kind: 'list'; items: string[] }
  | { kind: 'definitions'; items: HelpDefinition[] };

export interface HelpSection {
  /** Anchor id, also used as the `track` key. */
  id: string;
  icon: string;
  title: string;
  /** One-line answer to "what is this section about", shown under the title. */
  summary: string;
  blocks: HelpBlock[];
  links: HelpLink[];
}

/**
 * Built lazily (never at module scope): `$localize` only resolves once
 * `main.ts` has loaded the translation catalog, which happens after this
 * module is imported. Same reasoning as `buildNavGroups()` in `app.ts`.
 */
export function buildHelpSections(): HelpSection[] {
  return [
    {
      id: 'prise-en-main',
      icon: 'rocket_launch',
      title: $localize`:@@aide.start.title:Prise en main`,
      summary: $localize`:@@aide.start.summary:L'ordre dans lequel enchaîner les écrans, de la saisie des données au planning exporté.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.start.intro:L'application produit un planning en affectant des animateurs à des postes. Un poste, c'est une place à pourvoir sur un stand pendant un créneau : le solveur ne « crée » rien, il choisit qui occupe chaque place déjà décrite par vos données de référence. Tout part donc de la qualité de la saisie.`
        },
        {
          kind: 'list',
          items: [
            $localize`:@@aide.start.step1:1. Choisir (ou créer) l'édition sur laquelle travailler — tout le reste lui appartient.`,
            $localize`:@@aide.start.step2:2. Saisir les référentiels : typologies de jeux, emplacements, stands, animateurs, créneaux. Un import de scénario YAML ou un dump SQL peut remplacer cette étape.`,
            $localize`:@@aide.start.step3:3. Vérifier les ouvertures de stands : c'est là que se voient les erreurs d'horaires, sans attendre une résolution.`,
            $localize`:@@aide.start.step4:4. Vérifier le besoin en animateurs : si l'effectif saisi est sous le minimum estimé, aucun réglage du solveur ne sauvera le planning.`,
            $localize`:@@aide.start.step5:5. Si les journées sont décrites par une simple amplitude d'ouverture, générer le découpage en vacations.`,
            $localize`:@@aide.start.step6:6. Régler la durée de résolution, puis lancer le solveur.`,
            $localize`:@@aide.start.step7:7. Lire le score et les problèmes signalés, corriger, relancer — puis exporter.`
          ]
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.start.iterate:Les étapes 6 et 7 se répètent : une première résolution courte sert surtout à révéler ce qui bloque, et non à produire le planning final.`
        }
      ],
      links: [
        { route: '/editions', label: $localize`:@@nav.link.editions:Éditions` },
        { route: '/data-setup', label: $localize`:@@nav.link.dataSetup:Données` },
        { route: '/', label: $localize`:@@nav.link.solver:Solveur` }
      ]
    },
    {
      id: 'editions',
      icon: 'layers',
      title: $localize`:@@aide.editions.title:Éditions et plannings alternatifs`,
      summary: $localize`:@@aide.editions.summary:Deux niveaux de cloisonnement : l'édition du festival, et la grille de créneaux active à l'intérieur.`,
      blocks: [
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.editions.term.edition:Édition`,
              text: $localize`:@@aide.editions.def.edition:Une année de festival, avec ses propres stands, animateurs, créneaux, paramètres et planning résolu. Rien ne circule d'une édition à l'autre : « Année 2025 » reste consultable pendant qu'on prépare « Année 2026 ». L'édition consultée est propre à chaque onglet du navigateur, et rappelée par le bandeau en haut de l'écran.`
            },
            {
              term: $localize`:@@aide.editions.term.groupe:Groupe de créneaux (planning)`,
              text: $localize`:@@aide.editions.def.groupe:À l'intérieur d'une édition, les créneaux sont organisés en grilles : un planning normal, un planning de repli en cas d'imprévu, le résultat d'un découpage automatique… Une seule grille est active à la fois, et le solveur ne voit que celle-là.`
            }
          ]
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.editions.stale:Si le groupe actif change après une résolution, ou si une donnée de référence est modifiée, l'application le signale — indicateur dans la barre d'outils, rappel sur la page Solveur. Le planning affiché reste consultable, mais il ne décrit plus tout à fait les données courantes : il faut relancer une résolution.`
        }
      ],
      links: [
        { route: '/editions', label: $localize`:@@nav.link.editions:Éditions` },
        { route: '/creneaux', label: $localize`:@@nav.link.creneaux:Créneaux` }
      ]
    },
    {
      id: 'donnees',
      icon: 'inventory_2',
      title: $localize`:@@aide.data.title:Données de référence`,
      summary: $localize`:@@aide.data.summary:Ce que décrit chaque écran de référentiel, et les pièges de saisie les plus fréquents.`,
      blocks: [
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.data.term.animateurs:Animateurs`,
              text: $localize`:@@aide.data.def.animateurs:Identité, date de naissance, compétences par typologie avec un niveau (débutant / autonome / référent), et jours d'indisponibilité. Le régime légal applicable (moins de 16 ans, 16-18 ans, majeur) n'est jamais saisi : il est recalculé à la date de chaque créneau. Par défaut un animateur est disponible : ne saisissez que les absences.`
            },
            {
              term: $localize`:@@aide.data.term.stands:Stands`,
              text: $localize`:@@aide.data.def.stands:Typologies proposées, effectif minimum et maximum d'animateurs simultanés, restriction éventuelle aux majeurs, indicateurs premium et niveau d'effort, et horaires d'ouverture. Les horaires se saisissent en règles récurrentes (« tous les jours de 10 h à 12 h puis de 14 h à la fermeture ») complétées par des exceptions datées, qui priment sur les règles pour le jour qu'elles nomment.`
            },
            {
              term: $localize`:@@aide.data.term.creneaux:Créneaux`,
              text: $localize`:@@aide.data.def.creneaux:Jour du festival, date, heures de début et de fin, et grille d'appartenance. C'est le découpage temporel que le solveur remplit ; l'effectif minimum d'un stand y est multiplié par le nombre de créneaux où il est ouvert.`
            },
            {
              term: $localize`:@@aide.data.term.autres:Emplacements et typologies`,
              text: $localize`:@@aide.data.def.autres:Les emplacements sont des lieux géolocalisés auxquels rattacher un stand — ils servent à éviter les changements de lieu éloignés d'un créneau à l'autre. Les typologies sont le vocabulaire commun entre les compétences d'un animateur et les jeux d'un stand : un animateur ne peut tenir un stand que s'il en maîtrise au moins une typologie.`
            }
          ]
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.data.bulk:Chaque écran de référentiel permet de cocher plusieurs lignes pour les supprimer ou les modifier d'un seul geste. Dans une modification en masse, chaque champ vaut « ne pas modifier » tant qu'il n'est pas renseigné : les autres valeurs propres à chaque ligne sont préservées.`
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.data.ouvertures:Avant toute résolution, ouvrez « Ouvertures des stands » : la grille stand × jour montre ce que le solveur lira réellement une fois les règles étendues et les exceptions appliquées, et signale les trois erreurs de saisie habituelles — un stand finalement ouvert aucun jour, une fenêtre horaire hors des heures du jour (donc sans effet), et une plage trop courte pour être une vraie vacation.`
        }
      ],
      links: [
        { route: '/animateurs', label: $localize`:@@nav.link.animateurs:Animateurs` },
        { route: '/stands', label: $localize`:@@nav.link.stands:Stands` },
        { route: '/ouvertures', label: $localize`:@@nav.link.ouvertures:Ouvertures des stands` },
        { route: '/staffing', label: $localize`:@@nav.link.staffing:Besoin en animateurs` }
      ]
    },
    {
      id: 'configuration-solveur',
      icon: 'tune',
      title: $localize`:@@aide.config.title:Configuration du solveur`,
      summary: $localize`:@@aide.config.summary:Les cinq réglages qui changent le comportement du solveur, et lequel toucher en premier.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.config.intro:Le solveur n'a pas de « niveau de qualité » à régler : il cherche, dans le temps qu'on lui donne, le meilleur planning au sens des contraintes actives. Les réglages agissent donc soit sur le temps accordé, soit sur les règles à respecter.`
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.config.term.duree:Durée de résolution`,
              text: $localize`:@@aide.config.def.duree:Temps maximal accordé à une résolution, réglé sur la page Solveur (3 min par défaut) et partagé par tous les navigateurs. C'est le réglage qui compte le plus : sur un festival complet, quelques minutes suffisent rarement à atteindre un score dur nul. Commencez court (1 à 3 min) pour révéler les blocages structurels, puis passez à 15-30 min, voire davantage, pour la résolution finale.`
            },
            {
              term: $localize`:@@aide.config.term.legaux:Paramètres légaux`,
              text: $localize`:@@aide.config.def.legaux:Plafonds hebdomadaires de temps de travail, sur la page Contraintes : 48 h pour les majeurs, 35 h pour les mineurs. Ces maximums sont d'ordre public — une valeur supérieure est refusée. Une valeur inférieure, plus protectrice, est acceptée mais durcit fortement le problème : c'est souvent elle, et non le nombre d'animateurs, qui rend un planning infaisable.`
            },
            {
              term: $localize`:@@aide.config.term.contraintes:Activation des contraintes`,
              text: $localize`:@@aide.config.def.contraintes:Chaque règle du catalogue peut être désactivée depuis la page Contraintes. À utiliser pour diagnostiquer (« sans cette règle, le planning devient-il faisable ? ») bien plus que pour produire : désactiver une contrainte dure produit un planning que la réalité refusera. En revanche, désactiver une contrainte souple qui ne mesure rien — par exemple les souhaits quand aucun animateur n'en a déclaré — rend le score lisible sans rien changer au résultat.`
            },
            {
              term: $localize`:@@aide.config.term.adhoc:Contraintes ad hoc`,
              text: $localize`:@@aide.config.def.adhoc:Exceptions saisies au cas par cas, avec une raison tracée : indisponibilité forcée, incompatibilité entre deux animateurs, affectation forcée. Elles sont traitées au même niveau que les contraintes dures, donc jamais contournées silencieusement — et chacune retire des possibilités au solveur. Une contrainte ad hoc mal posée bloque un planning aussi sûrement qu'un manque d'effectif.`
            },
            {
              term: $localize`:@@aide.config.term.verrouillages:Verrouillages`,
              text: $localize`:@@aide.config.def.verrouillages:Geler un animateur, un stand, une journée ou un créneau pour que la prochaine résolution n'y touche plus et optimise le reste. Utile pour figer une partie validée du planning. Une place restée non pourvue n'est jamais gelée, et les places gelées continuent d'être évaluées : un verrou peut donc laisser une alerte visible plutôt que de masquer un problème.`
            },
            {
              term: $localize`:@@aide.config.term.decoupage:Paramètres de découpage`,
              text: $localize`:@@aide.config.def.decoupage:Pour un scénario « continu », la page Découpage transforme l'amplitude d'ouverture d'une journée en vacations réelles : durée cible, minimale et maximale d'une vacation, chevauchement de relais, fenêtres et durée de pause repas, et stratégie de couverture pendant la pause (fermer le stand, faire une relève, ou tourner à effectif réduit). Ces paramètres déterminent le nombre de postes à pourvoir : les modifier change la taille du problème bien plus que n'importe quel réglage du solveur.`
            }
          ]
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.config.lock:Une seule résolution tourne à la fois pour tout le serveur. Si les boutons sont verrouillés, c'est qu'un calcul est en cours — éventuellement lancé depuis un autre poste ou un autre navigateur ; le moniteur de la barre d'outils indique lequel et depuis combien de temps.`
        }
      ],
      links: [
        { route: '/', label: $localize`:@@nav.link.solver:Solveur` },
        { route: '/constraints', label: $localize`:@@nav.link.constraints:Contraintes` },
        { route: '/decoupage', label: $localize`:@@nav.link.decoupage:Découpage` },
        { route: '/verrouillages', label: $localize`:@@nav.link.verrouillages:Verrouillages` }
      ]
    },
    {
      id: 'lire-les-resultats',
      icon: 'insights',
      title: $localize`:@@aide.results.title:Lire et interpréter les résultats`,
      summary: $localize`:@@aide.results.summary:Le score, le bandeau de faisabilité, la page Problèmes : ce que chacun dit, et lequel croire.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.results.score:Une résolution rend un score à trois composantes, de la forme « 0hard / -6675medium / -120soft ». Elles ne se compensent jamais : le solveur préfère toujours un planning meilleur en dur, quel que soit le prix payé en medium et en souple.`
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.results.term.hard:Score dur (hard)`,
              text: $localize`:@@aide.results.def.hard:La seule composante à regarder d'abord. À zéro, le planning est valide : toutes les places sont pourvues, aucune indisponibilité, aucune compétence manquante, aucun chevauchement, et tout le cadre légal est respecté. Négatif, le planning n'est pas utilisable tel quel, quel que soit le reste du score.`
            },
            {
              term: $localize`:@@aide.results.term.medium:Score medium`,
              text: $localize`:@@aide.results.def.medium:Ce qui doit être respecté autant que possible et signalé sinon — référent par stand, équilibrage de charge, encadrement des mineurs. Un medium très négatif n'invalide pas le planning ; il indique un confort dégradé.`
            },
            {
              term: $localize`:@@aide.results.term.soft:Score souple (soft)`,
              text: $localize`:@@aide.results.def.soft:Ce qui départage deux plannings valides : rotation des stands, mixité des niveaux, équité des créneaux pénibles. Ne l'optimisez jamais avant d'avoir un dur à zéro.`
            }
          ]
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.results.mediumFloor:Attention aux points de medium qui ne mesurent rien : une contrainte qui pénalise chaque poste faute de donnée saisie — aucun souhait déclaré, aucun animateur de niveau référent — produit un plancher constant, parfois plus de 80 % du total. Comparez des scores entre deux résolutions du même jeu de données, pas la valeur absolue.`
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.results.term.faisabilite:Bandeau de faisabilité`,
              text: $localize`:@@aide.results.def.faisabilite:Estimation de capacité calculée sans résoudre, affichée en haut des pages Solveur et Contraintes. Elle nomme les créneaux et stands en cause. Elle est optimiste : elle ignore le découpage en vacations et le cadre légal des mineurs, donc elle peut annoncer « réalisable » un planning que le solveur n'amènera pas à zéro. L'inverse n'arrive pas : si elle signale un manque de capacité, c'est réel.`
            },
            {
              term: $localize`:@@aide.results.term.problemes:Page Problèmes`,
              text: $localize`:@@aide.results.def.problemes:La liste complète et hiérarchisée des causes, là où le bandeau n'affiche que les plus sévères. C'est le premier écran à ouvrir quand une résolution ne donne pas zéro.`
            },
            {
              term: $localize`:@@aide.results.term.contraintes:Page Contraintes`,
              text: $localize`:@@aide.results.def.contraintes:Le catalogue des règles, chacune avec son niveau, son état actif/inactif et le résultat de la dernière analyse : combien de fois elle est violée, et le détail des violations. C'est ce qui transforme « -14 hard » en « quatorze postes non pourvus sur tel stand ».`
            },
            {
              term: $localize`:@@aide.results.term.pourquoiLui:« Pourquoi lui ? »`,
              text: $localize`:@@aide.results.def.pourquoiLui:Un clic sur un animateur affecté, dans le calendrier journalier ou le calendrier des affectations, explique cette affectation précise : les règles respectées ou violées pour ce poste, et une simulation à la demande du gain ou de la perte de score si le poste était confié à un autre animateur compétent — sans rien modifier au planning.`
            }
          ]
        }
      ],
      links: [
        { route: '/problemes', label: $localize`:@@nav.link.problemes:Problèmes` },
        { route: '/constraints', label: $localize`:@@nav.link.constraints:Contraintes` },
        { route: '/day-calendar', label: $localize`:@@nav.link.dayCalendar:Calendrier journalier` }
      ]
    },
    {
      id: 'tuner',
      icon: 'troubleshoot',
      title: $localize`:@@aide.tuning.title:Tuner la configuration`,
      summary: $localize`:@@aide.tuning.summary:Symptôme par symptôme : ce qu'il faut changer, et dans quel ordre.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.tuning.order:Réglez toujours dans cet ordre : d'abord les données, ensuite la durée de résolution, et seulement en dernier les contraintes. Désactiver une règle est un outil de diagnostic, pas une méthode de production.`
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.tuning.term.postesVides:Des postes restent non pourvus`,
              text: $localize`:@@aide.tuning.def.postesVides:Vérifiez d'abord la page Besoin en animateurs : si l'effectif saisi est sous le minimum estimé, il manque des animateurs, pas du temps de calcul. Sinon, regardez quels créneaux sont concernés sur la page Problèmes — c'est souvent une poignée de créneaux de pointe, un stand ouvert par erreur, ou une typologie que presque personne ne maîtrise.`
            },
            {
              term: $localize`:@@aide.tuning.term.plateau:Le score dur stagne à quelques unités de zéro`,
              text: $localize`:@@aide.tuning.def.plateau:C'est le cas typique où allonger la durée de résolution paie : doublez-la et relancez. Si deux résolutions longues s'arrêtent exactement au même score, ce n'est plus un problème de temps mais de structure — identifiez la contrainte en défaut sur la page Contraintes et regardez ses violations une par une.`
            },
            {
              term: $localize`:@@aide.tuning.term.legal:Les violations sont toutes des règles de temps de travail`,
              text: $localize`:@@aide.tuning.def.legal:Les plafonds hebdomadaires et le repos entre journées se heurtent au découpage. Revoyez les paramètres de découpage (vacations plus courtes, chevauchement, stratégie de couverture pendant la pause) plutôt que les plafonds légaux, qui ne sont pas négociables vers le haut. Un plafond volontairement abaissé sous le maximum légal est souvent le vrai coupable.`
            },
            {
              term: $localize`:@@aide.tuning.term.mineurs:Les violations concernent les mineurs`,
              text: $localize`:@@aide.tuning.def.mineurs:Le cadre des mineurs est plus strict et dépend de l'âge à la date du créneau : pas de travail de nuit, amplitude quotidienne réduite, pauses plus longues, deux jours de repos consécutifs, accompagnement obligatoire par un majeur. Vérifiez les dates de naissance saisies et la proportion de majeurs disponibles sur les créneaux tardifs.`
            },
            {
              term: $localize`:@@aide.tuning.term.adhoc:Le planning est devenu infaisable après une modification`,
              text: $localize`:@@aide.tuning.def.adhoc:Cherchez du côté des contraintes ad hoc et des verrouillages ajoutés depuis la dernière résolution réussie : ils ont le poids d'une contrainte dure. Désactivez temporairement le dernier ajout et relancez pour confirmer.`
            },
            {
              term: $localize`:@@aide.tuning.term.gelerBloc:Une part du planning est acquise et ralentit la recherche`,
              text: $localize`:@@aide.tuning.def.gelerBloc:Certaines journées sont volumineuses et sans difficulté — le montage et le démontage, typiquement : beaucoup de places, une seule typologie, aucune contrainte de compétence. Le solveur y consacre pourtant une part de ses mouvements proportionnelle à leur nombre de places. Une fois qu'une résolution les a correctement pourvues, posez un verrouillage de type Stand sur ces stands : les places restent intégralement dans le planning, nominatives et visibles partout, leurs heures continuent de compter dans les plafonds légaux, mais le solveur ne cherche plus à les déplacer et concentre son budget sur les journées difficiles. À réserver au diagnostic et aux gros scénarios : le verrou fige ces personnes-là sur ces places, donc il n'est sain que si l'affectation gelée est déjà bonne — d'où l'ordre « résoudre, vérifier, puis verrouiller ». Il se retire à tout moment depuis la page Verrouillages.`
            },
            {
              term: $localize`:@@aide.tuning.term.mediumEleve:Le score medium ou souple paraît énorme`,
              text: $localize`:@@aide.tuning.def.mediumEleve:Vérifiez d'abord qu'il ne s'agit pas d'un plancher lié à une donnée absente (souhaits, niveau référent). Enrichir les compétences et les souhaits des animateurs améliore ces composantes bien plus que n'importe quel réglage du solveur.`
            }
          ]
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.tuning.method:Ne changez qu'une chose à la fois entre deux résolutions, et relancez toujours sur la même durée pour que les scores restent comparables. Le nombre de postes et la volumétrie affichés en haut de la page Solveur permettent de vérifier qu'une modification de données a bien eu l'effet attendu, avant même de relancer un calcul.`
        }
      ],
      links: [
        { route: '/problemes', label: $localize`:@@nav.link.problemes:Problèmes` },
        { route: '/staffing', label: $localize`:@@nav.link.staffing:Besoin en animateurs` },
        { route: '/decoupage', label: $localize`:@@nav.link.decoupage:Découpage` },
        { route: '/ad-hoc-constraints', label: $localize`:@@nav.link.adHocConstraints:Contraintes ad hoc` },
        { route: '/verrouillages', label: $localize`:@@nav.link.verrouillages:Verrouillages` }
      ]
    },
    {
      id: 'consulter',
      icon: 'calendar_month',
      title: $localize`:@@aide.views.title:Consulter le planning`,
      summary: $localize`:@@aide.views.summary:Chaque vue répond à une question différente ; choisir la bonne fait gagner du temps.`,
      blocks: [
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.views.term.calendar:Calendrier des affectations`,
              text: $localize`:@@aide.views.def.calendar:Vue mensuelle avec filtres par animateur et par stand, et détail des affectations au clic sur une journée. Pour se repérer dans l'ensemble du festival.`
            },
            {
              term: $localize`:@@aide.views.term.day:Calendrier journalier`,
              text: $localize`:@@aide.views.def.day:Une journée, stand par stand et créneau par créneau. Pour vérifier une journée précise ou préparer une réparation manuelle.`
            },
            {
              term: $localize`:@@aide.views.term.heatmap:Heatmap de charge`,
              text: $localize`:@@aide.views.def.heatmap:Jour croisé avec le stand (places pourvues sur places requises : les trous de couverture) ou avec l'animateur (postes par jour : les surcharges). Pour repérer un déséquilibre d'un coup d'œil.`
            },
            {
              term: $localize`:@@aide.views.term.timeline:Timeline animateur`,
              text: $localize`:@@aide.views.def.timeline:Le planning d'une personne : stands à couvrir, amplitude journalière, vacations et pauses entre elles. C'est la vue à envoyer à l'intéressé, exportable en PDF ou en ICS.`
            },
            {
              term: $localize`:@@aide.views.term.hours:Heures et besoin en animateurs`,
              text: $localize`:@@aide.views.def.hours:Les heures travaillées par animateur d'un côté, l'estimation du nombre minimum d'animateurs à recruter de l'autre. La seconde se calcule avant toute résolution, à partir des seuls stands et créneaux.`
            }
          ]
        }
      ],
      links: [
        { route: '/calendar', label: $localize`:@@nav.link.calendar:Calendrier des affectations` },
        { route: '/heatmap', label: $localize`:@@nav.link.heatmap:Heatmap de charge` },
        { route: '/timeline', label: $localize`:@@nav.link.timeline:Timeline animateur` },
        { route: '/hours', label: $localize`:@@nav.link.hours:Heures` }
      ]
    },
    {
      id: 'foire-au-planning',
      icon: 'handshake',
      title: $localize`:@@aide.foire.title:Foire au planning (échanges de créneaux)`,
      summary: $localize`:@@aide.foire.summary:Les animateurs proposent leurs échanges de créneaux en libre-service ; rien n'est appliqué sans votre validation.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.foire.intro:Chaque animateur dispose d'un espace personnel, accessible par le lien imprimé sur son planning PDF — aucun compte à créer. Il y consulte son planning à jour (avec ses coéquipiers) et peut proposer d'échanger un de ses créneaux avec un collègue. Chaque demande est prévalidée contre les règles dures du planning ; une demande irréalisable est signalée en langage métier, mais transmise quand même : c'est vous qui tranchez.`
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.foire.term.espace:Espace animateur`,
              text: $localize`:@@aide.foire.def.espace:L'animateur constitue sa liste de demandes (créneau concerné, collègue avec qui échanger, motif) puis la soumet en une fois. Il suit ensuite le statut de chacune — en attente, acceptée, refusée — avec votre commentaire éventuel, et peut annuler une demande tant qu'elle n'est pas décidée. Le lien de son espace se copie (et se régénère, si un PDF a fuité) depuis sa fiche sur la page Animateurs.`
            },
            {
              term: $localize`:@@aide.foire.term.ecran:Écran Échanges`,
              text: $localize`:@@aide.foire.def.ecran:Les demandes en attente, avec pour chacune son impact mesuré sur le planning actuel : échange croisé (les deux permutent) ou simple reprise (le collègue est libre sur le créneau), effet sur le score, règles dures qui seraient cassées. Accepter applique l'échange immédiatement, exactement comme simulé, et le verrouille sur son créneau : une régénération ultérieure ne le défera pas — elle reste à lancer depuis la page Solveur. Refuser ne modifie rien ; le motif saisi est transmis à l'animateur.`
            },
            {
              term: $localize`:@@aide.foire.term.connexion:Connexion et notifications`,
              text: $localize`:@@aide.foire.def.connexion:L'administration est protégée par le compte admin ; seuls les espaces animateurs restent accessibles par leur lien personnel. Si la messagerie est configurée, vous êtes prévenu par e-mail à chaque soumission, et l'animateur reçoit le résultat de ses demandes à l'adresse renseignée sur sa fiche.`
            }
          ]
        }
      ],
      links: [
        { route: '/echanges', label: $localize`:@@nav.link.echanges:Échanges` },
        { route: '/animateurs', label: $localize`:@@nav.link.animateurs:Animateurs` },
        { route: '/verrouillages', label: $localize`:@@nav.link.verrouillages:Verrouillages` }
      ]
    },
    {
      id: 'echanges',
      icon: 'swap_horiz',
      title: $localize`:@@aide.exchange.title:Imports, exports et outils`,
      summary: $localize`:@@aide.exchange.summary:Faire entrer des données, en sortir des plannings, et diagnostiquer le reste.`,
      blocks: [
        {
          kind: 'list',
          items: [
            $localize`:@@aide.exchange.item.scenario:Import d'un scénario YAML depuis votre poste : la façon la plus rapide de remplir une édition vide. Un fichier invalide donne une notification détaillée plutôt qu'un import partiel.`,
            $localize`:@@aide.exchange.item.sql:Export et import d'un dump SQL complet, pour dupliquer ou restaurer un jeu de données entier.`,
            $localize`:@@aide.exchange.item.pdfIcs:Export du planning individuel d'un animateur en PDF ou en ICS (importable dans Google Calendar, Apple Calendar ou Outlook), à l'unité ou en archive ZIP pour tout le monde.`,
            $localize`:@@aide.exchange.item.yamlValidator:Validateur YAML : vérifie un fichier scénario sans rien importer, pour corriger avant de toucher aux données.`,
            $localize`:@@aide.exchange.item.notifications:Notifications : l'historique des événements — fin de résolution, import, erreur. Une alerte non lue est signalée dans la navigation.`,
            $localize`:@@aide.exchange.item.debug:Débogage : l'état brut renvoyé par le serveur, utile pour rapporter un problème précisément.`
          ]
        }
      ],
      links: [
        { route: '/data-setup', label: $localize`:@@nav.link.dataSetup:Données` },
        { route: '/validateur-yaml', label: $localize`:@@nav.link.yamlValidator:Validateur YAML` },
        { route: '/notifications', label: $localize`:@@nav.link.notifications:Notifications` }
      ]
    }
  ];
}

/** Lowercases and strips diacritics so "référent" is found by typing "referent". */
function normalize(value: string): string {
  return value
    .toLowerCase()
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '');
}

/** Every searchable string of a section, flattened. */
function searchableText(section: HelpSection): string {
  const blockText = section.blocks.flatMap((block) => {
    switch (block.kind) {
      case 'paragraph':
        return [block.text];
      case 'list':
        return block.items;
      case 'definitions':
        return block.items.flatMap((item) => [item.term, item.text]);
    }
  });
  return [section.title, section.summary, ...blockText, ...section.links.map((link) => link.label)].join(' ');
}

/**
 * Whole-section filter: a section either matches the query or is hidden. The
 * guide is read section by section, so hiding individual paragraphs inside a
 * section would strip the context that makes the matching sentence useful.
 */
export function filterHelpSections(sections: HelpSection[], query: string): HelpSection[] {
  const terms = normalize(query).split(/\s+/).filter(Boolean);
  if (terms.length === 0) {
    return sections;
  }
  return sections.filter((section) => {
    const haystack = normalize(searchableText(section));
    return terms.every((term) => haystack.includes(term));
  });
}
