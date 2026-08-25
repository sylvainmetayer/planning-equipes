/**
 * Content of the in-app user guide, kept as data rather than as template
 * markup: the page renders it generically, the filter box searches it, and a
 * unit test can assert on it without rendering anything.
 *
 * Every string is a translatable message, so the guide is bilingual like the
 * rest of the UI. See `docs/` for the technical documentation this summarises
 * — the guide answers "how do I use this screen", not "how is it built".
 */

/**
 * Where the reader should go to act on what a section describes: an in-app
 * route, or an external destination (mailto:, GitHub) for the contact section.
 * Exactly one of `route`/`href` is set.
 */
export interface HelpLink {
  route?: string;
  href?: string;
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
        { route: '/parametres', label: $localize`:@@nav.link.parametres:Paramètres` },
        { route: '/', label: $localize`:@@nav.link.solver:Solveur` }
      ]
    },
    {
      id: 'editions',
      icon: 'layers',
      title: $localize`:@@aide.editions.title:Éditions et plans alternatifs`,
      summary: $localize`:@@aide.editions.summary:L'édition est l'unique porteur de variante : un plan canicule est une édition dupliquée, pas une grille parallèle.`,
      blocks: [
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.editions.term.edition:Édition`,
              text: $localize`:@@aide.editions.def.edition:Une édition de l'événement — ou une variante de plan — avec ses propres stands, animateurs, créneaux, paramètres et planning résolu. Rien ne circule d'une édition à l'autre : « Année 2025 » reste consultable pendant qu'on prépare « Année 2026 », et « 2026 canicule » vit à côté de « 2026 » sans la toucher. L'édition consultée est propre à chaque onglet du navigateur, et rappelée par le bandeau en haut de l'écran.`
            }
          ]
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.editions.variante:Pour préparer un plan alternatif (canicule, repli), dupliquez l'édition : la copie embarque stands, horaires, animateurs et leurs indisponibilités du moment — mais ni les affectations ni les jetons d'espace (chaque édition frappe les siens, un lien d'espace désigne toujours exactement une édition). Appliquez ensuite le delta dans la copie (l'édition en masse des horaires s'y prête), lancez la résolution, et basculez d'édition le matin venu.`
        },
        {
          kind: 'list',
          items: [
            $localize`:@@aide.editions.rituel1:1. La veille : dupliquer l'édition courante (« 2026 » → « 2026-canicule »).`,
            $localize`:@@aide.editions.rituel2:2. Appliquer les restrictions dans la copie (horaires, effectifs…).`,
            $localize`:@@aide.editions.rituel3:3. Lancer la résolution dans la copie (la nuit fait le reste).`,
            $localize`:@@aide.editions.rituel4:4. Le matin : basculer d'édition dans le bandeau.`,
            $localize`:@@aide.editions.rituel5:5. « Envoyer à tous » — les animateurs reçoivent le plan et les liens de cette édition.`,
            $localize`:@@aide.editions.rituel6:6. Au retour à la normale : re-basculer vers l'édition nominale, intacte, et ré-envoyer les plannings.`
          ]
        }
      ],
      links: [
        { route: '/editions', label: $localize`:@@nav.link.editions:Éditions` },
        { route: '/creneaux', label: $localize`:@@nav.link.creneaux:Créneaux` },
        { route: '/instantanes', label: $localize`:@@nav.link.snapshots:Instantanés` },
        { route: '/comparateur', label: $localize`:@@nav.link.comparateur:Comparateur A/B` }
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
              text: $localize`:@@aide.data.def.creneaux:Jour de l'événement, date, heures de début et de fin. C'est le découpage temporel que le solveur remplit ; l'effectif minimum d'un stand y est multiplié par le nombre de créneaux où il est ouvert.`
            },
            {
              term: $localize`:@@aide.data.term.familles:Familles de créneaux`,
              text: $localize`:@@aide.data.def.familles:Quand le découpage est généré avec plusieurs grilles de relais décalées, chaque vacation existe en plusieurs variantes aux coupures légèrement décalées : les « familles » (colonne Famille de la page Créneaux). Chaque stand est rattaché à une seule famille, si bien que tous les stands ne changent pas d'équipe au même instant — sans décalage, chaque relais viderait simultanément l'ensemble de l'événement. Des créneaux identiques appartenant à des familles différentes ne sont donc pas des doublons. Côté besoins, un stand ne génère des postes que sur les créneaux de sa propre famille : le besoin total en animateurs ne se multiplie pas avec le nombre de familles, il se répartit — la page Besoin en animateurs en tient compte.`
            },
            {
              term: $localize`:@@aide.data.term.autres:Emplacements et typologies`,
              text: $localize`:@@aide.data.def.autres:Les emplacements sont des lieux géolocalisés auxquels rattacher un stand — ils servent à éviter les changements de lieu éloignés d'un créneau à l'autre. Les typologies sont le vocabulaire commun entre les compétences d'un animateur et les jeux d'un stand : un animateur ne peut tenir un stand que s'il en maîtrise au moins une typologie. Désignez-y aussi la typologie « ninja » : ses porteurs sont considérés polyvalents (affectables sur n'importe quel stand), et le solveur essaie d'en garder un libre sur chaque créneau — votre marge de manœuvre en cas d'absence de dernière minute. Sans typologie ninja désignée, cette réserve n'existe pas et chacun reste cantonné à ses compétences ; elle se choisit sur la page Paramètres, qui vous avertit si elle manque.`
            }
          ]
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.data.bulk:Chaque écran de référentiel permet de cocher plusieurs lignes pour les supprimer ou les modifier d'un seul geste. Dans une modification en masse, chaque champ vaut « ne pas modifier » tant qu'il n'est pas renseigné : les autres valeurs propres à chaque ligne sont préservées. Cela vaut aussi pour les horaires d'ouverture : une règle valable pour plusieurs stands (« tous fermés avant 18h en soirée canicule ») se saisit une seule fois — cochez les stands, « Modifier la sélection », puis ajoutez, remplacez ou effacez leurs règles d'un coup. Les créneaux, eux, sont déjà communs à tous les stands : on n'en crée jamais par stand, ce sont les horaires qui restreignent ce que chaque stand ouvre.`
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
              text: $localize`:@@aide.config.def.duree:Temps maximal accordé à une résolution, réglé sur la page Solveur (3 min par défaut) et partagé par tous les navigateurs. C'est le réglage qui compte le plus : sur un événement complet, quelques minutes suffisent rarement à atteindre un score dur nul. Commencez court (1 à 3 min) pour révéler les blocages structurels, puis passez à 15-30 min, voire davantage, pour la résolution finale.`
            },
            {
              term: $localize`:@@aide.config.term.mailFin:Prévenir à la fin d'une résolution`,
              text: $localize`:@@aide.config.def.mailFin:Sur la page Paramètres, un interrupteur fait écrire à l'administrateur dès qu'une résolution de cette édition se termine : l'édition, le score et si le planning est faisable. De quoi lancer un calcul de trente minutes et partir. Le réglage vaut pour l'édition, pas pour le serveur : on est prévenu de la résolution qu'on attend, pas de chaque essai lancé ailleurs. Il reste sans effet tant qu'aucune adresse administrateur n'est configurée sur le serveur — l'écran le dit alors explicitement.`
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
              term: $localize`:@@aide.config.term.adhoc:Ajustements manuels`,
              text: $localize`:@@aide.config.def.adhoc:Exceptions saisies au cas par cas, avec une raison tracée : indisponibilité forcée, incompatibilité entre deux animateurs, affectation forcée. Les trois sont traitées au même niveau que les contraintes dures, donc jamais contournées silencieusement — et chacune retire des possibilités au solveur. Un ajustement mal posé bloque un planning aussi sûrement qu'un manque d'effectif. Voir la section « Ajustements manuels » pour ce que chaque type recouvre exactement.`
            },
            {
              term: $localize`:@@aide.config.term.verrouillages:Verrouillages`,
              text: $localize`:@@aide.config.def.verrouillages:Geler un animateur, un stand, une journée ou un créneau pour que la prochaine résolution n'y touche plus et optimise le reste. Utile pour figer une partie validée du planning. Une place restée non pourvue n'est jamais gelée, et les places gelées continuent d'être évaluées : un verrou peut donc laisser une alerte visible plutôt que de masquer un problème.`
            },
            {
              term: $localize`:@@aide.config.term.decoupage:Paramètres de découpage`,
              text: $localize`:@@aide.config.def.decoupage:Pour un scénario « continu », le découpage transforme l'amplitude d'ouverture d'une journée en vacations réelles : durée cible, minimale et maximale d'une vacation, chevauchement de relais, fenêtres et durée de pause repas, et stratégie de couverture pendant la pause (fermer le stand, faire une relève, ou tourner à effectif réduit). Ces réglages s'éditent sur la page Paramètres ; la prévisualisation et la génération se lancent depuis la page Créneaux, car le découpage remplace les créneaux de l'édition en place — pour re-découper avec d'autres paramètres, ré-importez le scénario source. Ces paramètres déterminent le nombre de postes à pourvoir : les modifier change la taille du problème bien plus que n'importe quel réglage du solveur.`
            }
          ]
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.config.lock:Une seule résolution tourne à la fois pour tout le serveur — éventuellement lancée depuis un autre poste ou un autre navigateur ; le moniteur de la barre d'outils indique laquelle, sur quelle édition et depuis combien de temps. Le calcul travaille sur l'édition depuis laquelle il a été lancé : seule celle-ci est verrouillée en saisie et en import. Si vous basculez sur une autre édition, vous pouvez continuer à y saisir des données ou y importer un scénario pendant la résolution.`
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.config.file:Pendant qu'un calcul tourne, les deux boutons de lancement deviennent « Planifier la résolution » et « Planifier la replanification » : la tâche est mise en file et démarre d'elle-même dès que la précédente se termine. C'est le geste à faire pour enchaîner deux éditions sans attendre devant l'écran — préparez la suivante, planifiez son calcul, fermez l'écran. Une tâche en attente ne verrouille rien, et elle lit son édition telle qu'elle sera au moment de démarrer : une correction apportée entre-temps sera bien prise en compte. La file s'affiche sous les boutons, on peut en retirer une ligne tant qu'elle n'a pas démarré, et planifier deux fois la même tâche (même édition, même calcul) est refusé — le classique double-clic. Planifier une nouvelle résolution de l'édition en cours de calcul reste permis : le calcul qui tourne a démarré avant vos dernières corrections et ne peut pas en tenir compte. La file vit en mémoire : un redémarrage du serveur la vide.`
        }
      ],
      links: [
        { route: '/', label: $localize`:@@nav.link.solver:Solveur` },
        { route: '/constraints', label: $localize`:@@nav.link.constraints:Contraintes` },
        { route: '/parametres', label: $localize`:@@nav.link.parametres:Paramètres` },
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
              text: $localize`:@@aide.results.def.problemes:La liste complète et hiérarchisée des causes, là où le bandeau n'affiche que les plus sévères. Elle mêle deux sources : les causes d'infaisabilité structurelles, calculées sans résolution (capacité insuffisante sur une tranche horaire, typologie que personne ne maîtrise, stand réservé aux majeurs sans majeur disponible…) — visibles dès la saisie des données, avant tout solve — et les règles en défaut de la dernière analyse, avec leur nombre de correspondances. Chaque ligne est triée par gravité et pointe vers l'écran où corriger. C'est le premier écran à ouvrir quand une résolution ne donne pas zéro, et un bon réflexe avant même de lancer la première.`
            },
            {
              term: $localize`:@@aide.results.term.contraintes:Page Contraintes`,
              text: $localize`:@@aide.results.def.contraintes:Le catalogue des règles, chacune avec son niveau, son état actif/inactif et le résultat de la dernière analyse : combien de fois elle est violée, et le détail des violations. C'est ce qui transforme « -14 hard » en « quatorze postes non pourvus sur tel stand ».`
            },
            {
              term: $localize`:@@aide.results.term.pourquoiLui:« Pourquoi lui ? »`,
              text: $localize`:@@aide.results.def.pourquoiLui:Un clic sur un animateur affecté, dans le calendrier journalier ou le calendrier des affectations, explique cette affectation précise : les règles respectées ou violées pour ce poste. À la demande, l'écran cherche aussi qui pourrait le remplacer, et ne propose que les remplacements qui tiennent — ceux qui n'introduisent aucune violation dure — chacun avec son effet sur le score et les règles qu'il débloque. La recherche est bornée : elle annonce combien de candidats elle a évalués et si elle s'est arrêtée au plafond, car une liste courte ne prouve pas qu'il n'existe rien d'autre. « Appliquer » pose le remplacement dans le planning.`
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
              text: $localize`:@@aide.tuning.def.adhoc:Cherchez du côté des ajustements manuels et des verrouillages ajoutés depuis la dernière résolution réussie : ils ont le poids d'une contrainte dure. Supprimez temporairement le dernier ajout et relancez pour confirmer. Si deux ajustements se contredisent franchement, la page Problèmes les nomme sans qu'aucune résolution soit nécessaire.`
            },
            {
              term: $localize`:@@aide.tuning.term.gelerBloc:Une part du planning est acquise et ralentit la recherche`,
              text: $localize`:@@aide.tuning.def.gelerBloc:Certaines journées sont volumineuses et sans difficulté — le montage et le démontage, typiquement : beaucoup de places, une seule typologie, aucune contrainte de compétence. Le solveur y consacre pourtant une part de ses mouvements proportionnelle à leur nombre de places. Une fois qu'une résolution les a correctement pourvues, posez un verrouillage de type Stand sur ces stands : les places restent intégralement dans le planning, nominatives et visibles partout, leurs heures continuent de compter dans les plafonds légaux, mais le solveur ne cherche plus à les déplacer et concentre son budget sur les journées difficiles. À réserver au diagnostic et aux gros scénarios : le verrou fige ces personnes-là sur ces places, donc il n'est sain que si l'affectation gelée est déjà bonne — d'où l'ordre « résoudre, vérifier, puis verrouiller ». Il se retire à tout moment depuis la page Verrouillages.`
            },
            {
              term: $localize`:@@aide.tuning.term.incremental:Un changement tombe après que le planning est validé`,
              text: $localize`:@@aide.tuning.def.incremental:Ne relancez pas une résolution complète : le bouton « Replanifier (incrémental) » de la page Solveur repart du planning enregistré, fige tout ce qui reste valable et ne recalcule que les postes qu'un changement a invalidés (animateur supprimé, indisponibilité saisie depuis la résolution) plus ceux restés vides. Vous pouvez rouvrir davantage en désignant un animateur, une journée ou un stand ; tout le reste est garanti inchangé. Le compte rendu liste les équipes qui ont bougé, donc les personnes à prévenir. Saisissez d'abord l'indisponibilité, puis replanifiez : c'est elle qui ouvre les postes concernés.`
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
        { route: '/parametres', label: $localize`:@@nav.link.parametres:Paramètres` },
        { route: '/ad-hoc-constraints', label: $localize`:@@nav.link.adHocConstraints:Ajustements manuels` },
        { route: '/verrouillages', label: $localize`:@@nav.link.verrouillages:Verrouillages` }
      ]
    },
    {
      id: 'ajustements-manuels',
      icon: 'rule',
      title: $localize`:@@aide.adHoc.title:Ajustements manuels`,
      summary: $localize`:@@aide.adHoc.summary:Forcer, interdire, rapprocher : ce que chaque type recouvre exactement, et les cas limites qui surprennent.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.adHoc.intro:Un ajustement manuel est une exception que vous saisissez sur vos propres données, à côté des règles du catalogue. Trois des quatre types sont appliqués au même niveau que le cadre légal : le solveur ne les contournera jamais, quitte à rendre un planning en défaut. Chacun se saisit avec une raison, qui reste lisible partout où l'ajustement est cité.`
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.adHoc.term.indisponibilite:Indisponibilité forcée`,
              text: $localize`:@@aide.adHoc.def.indisponibilite:Interdit à la personne d'occuper un poste dans le périmètre choisi. À distinguer des jours d'indisponibilité saisis sur la fiche de l'animateur, qui portent sur une journée entière : ici, vous pouvez viser un créneau précis, un stand précis, ou les deux.`
            },
            {
              term: $localize`:@@aide.adHoc.term.affectation:Affectation forcée`,
              text: $localize`:@@aide.adHoc.def.affectation:Exige qu'au moins un poste du périmètre soit tenu par la personne. Attention : si vous nommez plusieurs animateurs, l'ajustement est satisfait dès que l'un d'eux tient le poste — c'est un « l'un de ces animateurs », jamais un « tous ». Pour imposer deux personnes, saisissez deux ajustements.`
            },
            {
              term: $localize`:@@aide.adHoc.term.incompatibilite:Incompatibilité`,
              text: $localize`:@@aide.adHoc.def.incompatibilite:Interdit à deux personnes de travailler sur le même créneau. Elle porte sur le créneau, pas sur le stand : deux stands différents à la même heure sont tout autant interdits. Restreindre l'ajustement à un stand ne l'assouplit donc que dans ce stand-là. Seuls les deux premiers animateurs de la liste sont pris en compte : pour trois personnes qui ne doivent pas se croiser, saisissez les trois paires.`
            },
            {
              term: $localize`:@@aide.adHoc.term.affinite:Affinité (paire à privilégier)`,
              text: $localize`:@@aide.adHoc.def.affinite:Le seul type qui n'est pas une règle dure : une simple préférence, récompensée chaque fois que les deux personnes tiennent un poste sur le même stand au même créneau. Elle ne force rien — la rendre dure en ferait une affectation imposée déguisée, qui entrerait en conflit avec l'équilibrage des charges et les disponibilités de chacun.`
            }
          ]
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.adHoc.perimetre:Le créneau et le stand sont facultatifs, et les laisser vides veut dire « partout » : une indisponibilité forcée sans périmètre écarte la personne de tout l'événement. Un ajustement conserve l'identifiant du créneau visé — si ce créneau est supprimé ou régénéré par un découpage, la colonne Périmètre affiche « créneau supprimé » et l'ajustement ne s'applique plus à rien. Après un découpage, revérifiez ceux qui visaient un créneau.`
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.adHoc.contradictions:Deux ajustements qui ne peuvent pas tenir ensemble sont refusés à l'enregistrement, avec un message qui les nomme tous les deux — plutôt qu'un planning déclaré infaisable plusieurs minutes plus tard, sans que rien n'en désigne la cause. Quatre situations sont refusées :`
        },
        {
          kind: 'list',
          items: [
            $localize`:@@aide.adHoc.refus1:la même paire déclarée à la fois incompatible et en affinité ;`,
            $localize`:@@aide.adHoc.refus2:une affectation forcée dont tout le périmètre est couvert par une indisponibilité forcée visant chacun des animateurs qu'elle nomme ;`,
            $localize`:@@aide.adHoc.refus3:deux affectations forcées qui fixent la même personne sur des périmètres se chevauchant dans le temps — personne ne tient deux postes à la même heure ;`,
            $localize`:@@aide.adHoc.refus4:deux affectations forcées qui placent sur un même créneau deux personnes déclarées incompatibles.`
          ]
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.adHoc.limites:Le contrôle ne refuse que ce qui est certainement impossible : un ajustement refusé à tort vous coûterait une saisie légitime, sans contournement. Passent donc délibérément une affectation forcée nommant deux animateurs quand un seul d'entre eux est indisponible (l'autre peut la satisfaire), une indisponibilité plus étroite que le périmètre forcé (le poste peut se poser ailleurs dedans), et deux affectations forcées sur le même créneau dont une seule précise un stand (un seul poste les satisfait toutes les deux). Ces combinaisons-là ne sont pas refusées, mais elles peuvent quand même mener à un planning en défaut : c'est la résolution qui tranchera.`
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.adHoc.chevauchement:Le chevauchement se calcule sur les horaires du créneau tels qu'ils sont saisis. Une fermeture de stand peut réduire la plage réellement couverte par un poste : deux affectations forcées sur des créneaux qui se recouvrent, mais sur deux stands fermés à des heures complémentaires, seront donc refusées alors que le solveur aurait pu les poser. Le périmètre d'un ajustement est ce que vous avez saisi ; dans ce cas, visez des créneaux qui ne se recouvrent pas.`
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.adHoc.modifier:Modifier un ajustement, c'est le réenregistrer sous son propre identifiant : la nouvelle version remplace la précédente, et ce cas n'est jamais refusé pour contradiction avec elle-même. Un ajustement enregistré avant que ce contrôle existe, ou importé, peut en revanche subsister : la page Problèmes le signale alors comme cause bloquante, et l'écran des ajustements marque d'un avertissement les lignes concernées. Rien d'autre ne les désignerait.`
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.adHoc.import:À l'import d'un scénario, les ajustements manuels du fichier remplacent en bloc ceux de l'édition : un fichier qui n'en porte aucun n'en installe aucun. Ce qui les préserve d'un aller-retour, c'est l'export, qui écrit la section dès que l'édition en porte. Un fichier dont les ajustements se contredisent est refusé en entier, sans rien écrire.`
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.adHoc.apres:Quand une résolution se termine malgré tout en défaut, la page Problèmes nomme les ajustements que le solveur n'a pas pu honorer, un par un, avec le nombre de violations que chacun porte — c'est ce qui distingue « le solveur n'y arrive pas » de « ces trois exceptions-là sont à arbitrer ».`
        }
      ],
      links: [
        { route: '/ad-hoc-constraints', label: $localize`:@@nav.link.adHocConstraints:Ajustements manuels` },
        { route: '/problemes', label: $localize`:@@nav.link.problemes:Problèmes` },
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
              text: $localize`:@@aide.views.def.calendar:Vue mensuelle avec filtres par animateur et par stand, et détail des affectations au clic sur une journée. Pour se repérer dans l'ensemble de l'événement.`
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
              term: $localize`:@@aide.views.term.comparateur:Comparateur A/B`,
              text: $localize`:@@aide.views.def.comparateur:Deux plannings côte à côte — deux instantanés, ou un instantané et le planning actuel — sur le score, la couverture, l'équité et les violations, avec le sens de chaque écart écrit en toutes lettres. Les instantanés de toutes les éditions sont proposés : c'est ainsi qu'on compare une variante (canicule, repli) à l'édition nominale. L'écran prévient quand les deux plannings n'ont pas la même taille ou ne viennent pas de la même édition : une partie de l'écart vient alors du problème posé, pas de la qualité de la résolution. Comparer ne lance jamais de calcul.`
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
        { route: '/hours', label: $localize`:@@nav.link.hours:Heures` },
        { route: '/comparateur', label: $localize`:@@nav.link.comparateur:Comparateur A/B` }
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
          text: $localize`:@@aide.foire.intro:Chaque animateur dispose d'un espace personnel, accessible par le lien imprimé sur son planning PDF — aucun compte à créer, mais un code d'accès envoyé à l'adresse e-mail de sa fiche est demandé une fois par appareil (le planning se télécharge depuis l'espace, un minimum d'authentification s'impose ; un animateur sans adresse doit la faire ajouter). Il y consulte son planning à jour (avec ses coéquipiers) et peut proposer d'échanger un de ses créneaux avec un collègue — soit en cédant simplement son créneau, soit en désignant en plus le créneau du collègue qu'il veut récupérer en échange (« je te laisse mon lundi, je prends ton mardi »). Le collègue ciblé donne d'abord son accord depuis son espace (l'onglet Échanges lui présente les demandes reçues) : une demande n'atteint l'écran Échanges de l'organisation qu'une fois les deux animateurs d'accord, et un refus du collègue la clôt sans arbitrage. Chaque demande est prévalidée contre les règles dures du planning ; une demande irréalisable est signalée en langage métier, mais transmise quand même : c'est vous qui tranchez.`
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.foire.term.espace:Espace animateur`,
              text: $localize`:@@aide.foire.def.espace:L'animateur constitue sa liste de demandes (créneau concerné, collègue avec qui échanger, motif) puis la soumet en une fois. Quand il n'a personne en tête — il ne veut simplement pas ce créneau — « qui peut me remplacer ? » cherche les collègues avec qui l'échange tient réellement, sous ses trois formes : un collègue libre le remplace, une permutation sur le même créneau, ou un troc contre un créneau d'un autre jour. Il choisit dans la liste et le champ collègue se remplit. Il suit ensuite le statut de chacune — en attente, acceptée, refusée — avec votre commentaire éventuel, et peut annuler une demande tant qu'elle n'est pas décidée. Il peut aussi télécharger son planning en PDF ou l'ajouter à son agenda (ICS) depuis l'onglet « Mon planning ». Le lien de son espace se copie (et se régénère, si un PDF a fuité) depuis sa fiche sur la page Animateurs.`
            },
            {
              term: $localize`:@@aide.foire.term.ecran:Écran Échanges`,
              text: $localize`:@@aide.foire.def.ecran:Les demandes en attente, avec pour chacune son impact mesuré sur le planning actuel : échange croisé (les deux permutent) ou simple reprise (le collègue est libre sur le créneau), effet sur le score, règles dures qui seraient cassées. Accepter applique l'échange immédiatement, exactement comme simulé, et le verrouille sur son créneau : une régénération ultérieure ne le défera pas — elle reste à lancer depuis la page Solveur. Refuser ne modifie rien ; le motif saisi est transmis à l'animateur.`
            },
            {
              term: $localize`:@@aide.foire.term.connexion:Connexion et notifications`,
              text: $localize`:@@aide.foire.def.connexion:L'administration est protégée par le compte admin ; seuls les espaces animateurs restent accessibles par leur lien personnel. Si la messagerie est configurée, vous êtes prévenu par e-mail à chaque soumission, et l'animateur reçoit le résultat de ses demandes à l'adresse renseignée sur sa fiche.`
            },
            {
              term: $localize`:@@aide.foire.term.ouverture:Ouverture et fermeture`,
              text: $localize`:@@aide.foire.def.ouverture:L'interrupteur en tête de l'écran Échanges ouvre ou ferme la foire pour l'édition courante. Fermée, les espaces animateurs passent en consultation seule — le planning reste visible et téléchargeable (PDF, ICS), mais plus aucune demande ne peut être soumise ni annulée, et le refus est appliqué côté serveur, pas seulement masqué à l'écran.`
            },
            {
              term: $localize`:@@aide.foire.term.envoi:Envoi des plannings`,
              text: $localize`:@@aide.foire.def.envoi:Le bouton « Envoyer à tous » de la page Solveur (à côté de l'export du planning global) envoie à chaque animateur tenant au moins un poste son planning individuel par e-mail : le PDF en pièce jointe, avec le lien de son espace personnel — après confirmation. La page Timeline animateur offre le même envoi pour l'animateur affiché seulement. Le compte rendu nomme les animateurs sans adresse e-mail et les envois en échec.`
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
            $localize`:@@aide.exchange.item.yamlValidator:Validateur YAML (sur la page Débogage) : vérifie un fichier scénario sans rien importer, pour corriger avant de toucher aux données.`,
            $localize`:@@aide.exchange.item.notifications:Notifications : l'historique des événements — fin de résolution, import, erreur. Une alerte non lue est signalée dans la navigation.`,
            $localize`:@@aide.exchange.item.debug:Débogage : l'état brut renvoyé par le serveur, utile pour rapporter un problème précisément.`
          ]
        }
      ],
      links: [
        { route: '/parametres', label: $localize`:@@nav.link.parametres:Paramètres` },
        { route: '/debug', label: $localize`:@@nav.link.debug:Débogage` },
        { route: '/notifications', label: $localize`:@@nav.link.notifications:Notifications` }
      ]
    },
    {
      id: 'contact',
      icon: 'contact_support',
      title: $localize`:@@aide.contact.title:Contact et support`,
      summary: $localize`:@@aide.contact.summary:Une question, un blocage, une erreur métier : à qui s'adresser et comment.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.contact.mail:Pour une question d'utilisation, un doute sur un résultat ou un besoin d'accompagnement, écrivez à planning@sylvain.dev — joignez si possible une capture d'écran et la version affichée en bas de la page Débogage.`
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.contact.issue:Pour une erreur métier reproductible (un score faux, une contrainte non respectée, un export incorrect), ouvrez un rapport de problème sur GitHub : le formulaire guide la description. Attention, GitHub est public : n'y mettez aucune information nominative — désignez les personnes par leur identifiant d'animateur (visible sur la page Animateurs), jamais par leur nom. Les données nominatives, elles, passent par l'e-mail ci-dessus.`
        }
      ],
      links: [
        { href: 'mailto:planning@sylvain.dev', label: $localize`:@@aide.contact.lienMail:Contacter le support` },
        {
          href: 'https://github.com/sylvainmetayer/planning-equipes/issues/new?template=erreur-metier.yml',
          label: $localize`:@@aide.contact.lienIssue:Rapporter un problème (GitHub)`
        }
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
