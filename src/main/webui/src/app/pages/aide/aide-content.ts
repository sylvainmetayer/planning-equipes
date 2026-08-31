/**
 * Content of the in-app user guide, kept as data rather than as template
 * markup: the page renders it generically, the filter box searches it, and a
 * unit test can assert on it without rendering anything.
 *
 * Every string is a translatable message, so the guide is bilingual like the
 * rest of the UI. See `docs/` for the technical documentation this summarises
 * — the guide answers "how do I use this screen", not "how is it built".
 */

import { buildRaccourcisNavigation } from '../../core/keyboard-shortcuts';

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
      summary: $localize`:@@aide.start.summary:Le cycle complet d'une édition : collecter, construire, résoudre, publier, suivre.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.start.intro:L'application affecte des animateurs à des postes. Un poste, c'est une place à pourvoir sur un stand pendant un créneau : le solveur n'en crée aucune, il choisit qui occupe celles que vos stands et vos créneaux décrivent déjà. Ce qui se réglait dans la mise en page du classeur se règle donc en amont, dans la saisie.`
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.start.classeur:Trois choses changent la méthode par rapport à un tableur. Le cadre légal est vérifié en continu, pas à la relecture. Un changement tardif se rejoue par une replanification incrémentale, qui laisse intact tout ce qui restait valable. Et le planning diffusé est daté : l'application sait qui a reçu quelle version, et qui ne l'a pas encore lue.`
        },
        {
          kind: 'list',
          items: [
            $localize`:@@aide.start.step1:1. Choisir ou créer l'édition : tout le reste lui appartient, et rien ne circule d'une édition à l'autre.`,
            $localize`:@@aide.start.step2:2. Saisir les référentiels : typologies de jeux, emplacements, stands, animateurs, créneaux. Les animateurs entrent aussi d'un seul geste par l'import CSV, si vos bénévoles arrivent déjà dans un tableur ; un scénario YAML ou un dump SQL remplace l'étape entière. Une saisie douteuse mais tenable est enregistrée avec un avertissement à lire, jamais refusée en silence.`,
            $localize`:@@aide.start.step3:3. Ouvrir la collecte des disponibilités, case « prévenir » cochée : elle envoie à chacun le lien de son espace, où il déclare depuis son téléphone ses jours d'absence et les jeux qu'il aimerait animer. Les fiches animateurs doivent donc déjà exister ; et si vous ouvrez la collecte avant d'avoir saisi les créneaux, l'espace n'offre aucun jour à cocher — seuls les souhaits se déclarent.`,
            $localize`:@@aide.start.step4:4. Appliquer ou refuser les déclarations reçues, puis fermer la collecte : appliquer écrit la proposition entière sur la fiche de l'animateur.`,
            $localize`:@@aide.start.step5:5. Vérifier les ouvertures des stands, puis le besoin en animateurs : ces deux écrans répondent sans qu'aucun calcul ait tourné.`,
            $localize`:@@aide.start.step6:6. Si les journées sont décrites par une simple amplitude d'ouverture, générer le découpage en vacations.`,
            $localize`:@@aide.start.step7:7. Lancer une résolution courte, lire la page Problèmes, corriger — puis relancer sur une durée longue.`,
            $localize`:@@aide.start.step8:8. Publier : seules les personnes dont l'emploi du temps a changé reçoivent un message, avec le lien de leur espace. C'est aussi le seul moment où les agendas abonnés bougent — tant que vous n'avez pas publié, ils montrent la version précédente.`,
            $localize`:@@aide.start.step9:9. Suivre les accusés de réception sur la page Animateurs, et activer les envois de nuit sur la page Paramètres pour que les silencieux soient relancés — une fois.`,
            $localize`:@@aide.start.step10:10. Vérifier que la foire au planning est ouverte — elle l'est par défaut — pour que les échanges se négocient entre animateurs, la fermer quand le planning est figé. Le jour même, le mode jour J prend le relais.`
          ]
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.start.guichets:Deux guichets s'ouvrent et se ferment à la main, et ce sont les seuls endroits où un animateur écrit quelque chose : la collecte des disponibilités (page Disponibilités) avant la construction, la foire au planning (page Échanges) après la publication. Chacun accepte en plus une période datée, mais l'interrupteur reste maître — une période renseignée n'ouvre jamais un guichet fermé. La collecte est fermée tant que vous ne l'ouvrez pas ; la foire, elle, est ouverte par défaut.`
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.start.iterate:Les étapes de résolution se répètent : une première résolution courte sert à révéler ce qui bloque, pas à produire le planning final. Ne changez qu'une chose à la fois entre deux essais, sinon plus rien n'est comparable.`
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.start.theme:En haut à droite, un bouton fait tourner l'affichage entre trois réglages : automatique, clair et sombre. « Automatique » suit le réglage de votre ordinateur ou de votre téléphone — c'est pourquoi l'application peut passer au sombre toute seule le soir. Choisir explicitement « clair » ou « sombre » fige l'affichage : ce navigateur s'en souvient et ne change plus d'avis.`
        }
      ],
      links: [
        { route: '/editions', label: $localize`:@@nav.link.editions:Éditions` },
        {
          route: '/import-animateurs',
          label: $localize`:@@nav.link.importAnimateurs:Import CSV des animateurs`
        },
        { route: '/disponibilites', label: $localize`:@@nav.link.disponibilites:Disponibilités` },
        { route: '/', label: $localize`:@@nav.link.solver:Solveur` },
        { route: '/echanges', label: $localize`:@@nav.link.echanges:Échanges` },
        { route: '/parametres', label: $localize`:@@nav.link.parametres:Paramètres` }
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
            $localize`:@@aide.editions.rituel5:5. « Publier » — les animateurs concernés reçoivent le plan et les liens de cette édition.`,
            $localize`:@@aide.editions.rituel6:6. Au retour à la normale : re-basculer vers l'édition nominale, intacte, et publier à nouveau.`
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
              text: $localize`:@@aide.data.def.animateurs:Identité, date de naissance, compétences par typologie avec un niveau (débutant / autonome / référent), et jours d'indisponibilité. Le régime légal applicable (moins de 16 ans, 16-18 ans, majeur) n'est jamais saisi : il est recalculé à la date de chaque créneau. Par défaut un animateur est disponible : ne saisissez que les absences. Le tableau, lui, ne montre que ce qui tient dans une cellule — identité, majorité, manager, nombre de jours d'indisponibilité, accusé de réception — et chacune de ces colonnes se trie, en remontant d'abord ce qui reste à faire : les managers, les majeurs, puis les silencieux avant les relancés et les confirmés. L'appréciation est une liste par personne : elle se lit dans la fiche de consultation et se modifie dans le formulaire, y compris en édition groupée.`
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
          text: $localize`:@@aide.data.bulk:Chaque écran de référentiel permet de cocher plusieurs lignes pour les supprimer ou les modifier d'un seul geste. Dans une modification en masse, chaque champ vaut « ne pas modifier » tant qu'il n'est pas renseigné : les autres valeurs propres à chaque ligne sont préservées. Cela vaut aussi pour les horaires d'ouverture : une règle valable pour plusieurs stands (« tous fermés avant 18h en soirée canicule ») se saisit une seule fois — cochez les stands, « Modifier la sélection », puis ajoutez, remplacez ou effacez leurs règles d'un coup. Les créneaux, eux, sont déjà communs à tous les stands : on n'en crée jamais par stand, ce sont les horaires qui restreignent ce que chaque stand ouvre. Avant une suppression, seule ou en lot, la confirmation annonce ce qui référence la sélection : affectations du planning enregistré, ajustements manuels, verrouillages. C'est une information, pas un verrou — aucun chiffre n'empêche de supprimer.`
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.data.avertissements:Certaines saisies sont enregistrées avec un avertissement : la ligne est bien écrite, et un message reste affiché jusqu'à ce que vous le fermiez. Quatre cas, à la création comme à la modification — une indisponibilité posée hors des dates de l'événement (elle ne recouvre aucun créneau, donc elle ne protège personne) ; une indisponibilité posée sur un jour de l'événement qui ne porte aucun créneau, par exemple un lundi de relâche entre deux week-ends : la date n'est pas fautive, mais l'espace animateur ne l'affichera pas et la première déclaration de disponibilités appliquée l'effacera ; une date de naissance qui rend l'animateur mineur pendant l'événement, le message précisant à partir de quel jour il devient majeur ; un créneau qu'aucun stand n'est ouvert à couvrir, en tout ou seulement à ses extrémités. Ce ne sont jamais des refus : un refus s'affiche en rouge et rien n'est enregistré. Une modification ne signale que ce qu'elle change : reprendre l'adresse d'un mineur ne relance pas le message sur sa minorité, et une édition en lot ne réveille pas ce que personne n'a touché. Tant que l'édition n'a aucun créneau, l'événement n'a pas de dates et ces avertissements se taisent.`
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.data.ouvertures:Avant toute résolution, ouvrez « Ouvertures des stands » : la grille stand × jour montre ce que le solveur lira réellement une fois les règles étendues et les exceptions appliquées, et signale les trois erreurs de saisie habituelles — un stand finalement ouvert aucun jour, une fenêtre horaire hors des heures du jour (donc sans effet), et une plage trop courte pour être une vraie vacation.`
        }
      ],
      links: [
        { route: '/animateurs', label: $localize`:@@nav.link.animateurs:Animateurs` },
        {
          route: '/import-animateurs',
          label: $localize`:@@nav.link.importAnimateurs:Import CSV des animateurs`
        },
        { route: '/stands', label: $localize`:@@nav.link.stands:Stands` },
        { route: '/ouvertures', label: $localize`:@@nav.link.ouvertures:Ouvertures des stands` },
        { route: '/staffing', label: $localize`:@@nav.link.staffing:Besoin en animateurs` }
      ]
    },
    {
      id: 'import-csv-animateurs',
      icon: 'upload_file',
      title: $localize`:@@aide.importCsv.title:Import CSV des animateurs`,
      summary: $localize`:@@aide.importCsv.summary:Reprendre le tableur des bénévoles sans le ressaisir : format attendu, aperçu ligne à ligne, et les deux refus qui surprennent la première fois.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.data.importCsv:Si vos bénévoles arrivent dans un tableur, l'écran « Import CSV des animateurs » évite de tout ressaisir. Enregistrez la feuille au format « CSV UTF-8 » (le .xlsx n'est pas lu, et un fichier enregistré dans un autre encodage est refusé : ses accents sont déjà perdus quand le fichier arrive), déposez-la, et dites quelle colonne est quel champ : une correspondance est proposée d'après les en-têtes, vous la corrigez. L'écran affiche alors, ligne par ligne, ce que l'import ferait — acceptée, rejetée et pourquoi, avec le numéro de ligne du fichier — et rien n'est écrit tant que vous n'avez pas validé. Par défaut il ajoute et met à jour sans supprimer personne, et les jours d'indisponibilité du fichier viennent compléter ceux déjà enregistrés plutôt que les effacer ; deux cases à cocher inversent chacun de ces deux choix. Si vous cochez le remplacement complet, lisez l'avertissement de l'aperçu avant de valider : supprimer une fiche emporte aussi la déclaration de disponibilités de la personne, son accusé de réception du planning publié et son code d'accès à l'espace. Deux refus surprennent au premier essai et sont volontaires : créez d'abord vos créneaux (sans dates d'événement, un jour d'indisponibilité importé serait invisible dans l'espace animateur puis effacé), et donnez une date de naissance à chaque nouvelle fiche (tout le régime mineur / majeur en dépend). Enfin, si deux personnes portent le même nom, ajoutez une colonne identifiant ou e-mail : l'import refuse la ligne plutôt que de choisir à votre place.`
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.importCsv.colonnes:Une seule colonne est vraiment obligatoire en plus du nom : la date de naissance, dont tout le régime mineur / majeur se déduit à la date de chaque créneau. Les compétences, les souhaits et les jours d'indisponibilité tiennent plusieurs valeurs dans une même cellule, séparées par « | » (un point-virgule, une virgule ou un retour à la ligne dans une cellule entre guillemets font aussi l'affaire, mais jamais un « / », qu'une date utilise). Une compétence s'écrit « typologie » ou « typologie:REFERENT » — sans niveau, elle vaut « autonome ». Les dates se lisent aussi bien en JJ/MM/AAAA qu'en AAAA-MM-JJ, et une colonne laissée sur « — » n'est pas une colonne vide : le champ correspondant n'est pas touché sur les fiches déjà enregistrées.`
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.importCsv.exemple:Le bouton « Télécharger un fichier d'exemple » de l'écran d'import donne un CSV complet, prêt à ouvrir dans un tableur : les neuf colonnes lues, remplies avec les 153 animateurs du scénario de démonstration « festival réaliste ». C'est le plus court chemin pour voir à quoi doit ressembler une cellule de compétences ou de jours d'indisponibilité. Ses jours d'indisponibilité et ses typologies sont ceux de ce scénario : importé tel quel dans une édition qui a d'autres dates ou d'autres typologies, il se fera rejeter des lignes — recopiez-en la forme, pas le contenu.`
        }
      ],
      links: [
        {
          route: '/import-animateurs',
          label: $localize`:@@nav.link.importAnimateurs:Import CSV des animateurs`
        },
        { route: '/animateurs', label: $localize`:@@nav.link.animateurs:Animateurs` },
        { route: '/creneaux', label: $localize`:@@nav.link.creneaux:Créneaux` },
        { route: '/typologies', label: $localize`:@@nav.link.typologies:Typologies` }
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
          kind: 'paragraph',
          text: $localize`:@@aide.results.courbe:Pendant la résolution, la page Solveur trace ces trois composantes en direct, chacune dans son propre cadre — un score dur à -36 et un score souple à -400 000 ne se comparent pas sur le même axe. Le haut d'un cadre est le zéro : une courbe qui vient s'y coller veut dire « plus rien à corriger à ce niveau-là ». C'est ce qui permet de décider quand arrêter : le dur à zéro depuis plusieurs minutes et le souple qui ne bouge plus, la résolution plafonne et le bouton « Arrêter le solveur » ne vous coûtera rien — un solveur arrêté enregistre et analyse quand même ce qu'il a trouvé. Seule la résolution en cours est tracée : l'historique des résolutions passées n'est pas conservé. Si les graphes prennent de la place pour rien — vous lancez une résolution de quinze minutes et vous partez faire autre chose — « Réduire la courbe » les remplace par une ligne de scores, et l'écran s'en souvient à la visite suivante. Réduire n'interrompt rien : la rouvrir montre la résolution depuis son début.`
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
          kind: 'paragraph',
          text: $localize`:@@aide.results.previousPlan:Relancer une résolution sur une édition qui a déjà un bon planning peut le dégrader : le solveur repart de zéro et n'est pas tenu de retrouver aussi bien. La page Solveur affiche donc le score d'avant à côté de celui d'après, et le signale quand la nouvelle résolution est moins bonne — un score dur toujours à zéro ne veut pas dire que rien n'a été perdu, l'écart se lit sur le medium. Un bouton « Revenir au plan d'avant » remet alors en place le planning précédent. Ne tardez pas : ce filet est un instantané automatique, et seuls les cinq derniers sont conservés par édition.`
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.results.instantanes:Un seul planning est enregistré à la fois, et chaque résolution écrase le précédent : c'est la page Instantanés qui garde les autres. Une capture y est prise automatiquement avant chaque résolution — c'est ce filet que « Revenir au plan d'avant » utilise. Un plan que vous voulez garder au-delà des cinq dernières se met de côté explicitement, avec un libellé : celui-là n'est jamais purgé, et sert de terme de comparaison au Comparateur A/B. Le bouton « Restaurer ce plan » est désactivé pendant une résolution : celle-ci écraserait en se terminant le plan qu'on vient de remettre en place.`
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
        { route: '/day-calendar', label: $localize`:@@nav.link.dayCalendar:Calendrier journalier` },
        { route: '/instantanes', label: $localize`:@@nav.link.snapshots:Instantanés` }
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
      id: 'jour-j',
      icon: 'emergency',
      title: $localize`:@@aide.jourJ.title:Mode jour J`,
      summary: $localize`:@@aide.jourJ.summary:En cours de développement. Quelqu'un ne s'est pas présenté : le marquer absent, trouver un remplaçant, appliquer — sans relancer de calcul.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.jourJ.intro:Tous les autres écrans travaillent en amont de l'événement. Celui-ci est pensé pour le jour même, debout dans l'allée, sur un téléphone : peu de clics, de grandes cibles, et les trois étapes dans l'ordre du geste — qui manque, quelles places cela ouvre, qui peut les reprendre.`
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.jourJ.essai:Cet écran est en cours de développement, et ses impacts ne sont pas encore garantis. Il se distingue des autres écrans à l'essai sur un point qui compte : il agit. Marquer un absent écrit de vraies indisponibilités et vide de vrais sièges du planning enregistré, tout de suite. Aucune résolution ne revérifie l'ensemble entre-temps : la cohérence globale n'est établie qu'à la prochaine que vous lancerez. Utilisez-le en le sachant, et relancez une résolution dès que la situation le permet.`
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.jourJ.portee:Marquer quelqu'un absent le déclare indisponible sur les créneaux restants de la journée, et sur ceux-là seulement. Un créneau est restant tant qu'il n'est pas terminé — celui qui est en cours en fait partie, c'est justement celui où personne n'est au poste. Les créneaux déjà passés ne sont jamais touchés : la personne les a réellement tenus, et le planning doit continuer à le dire.`
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.jourJ.journee:La journée commence à son premier créneau, pas à minuit : un festival commence quand le premier stand ouvre. Une soirée qui se prolonge après minuit reste donc la même journée, et à une heure du matin l'écran affiche encore celle de la veille tant que son dernier créneau tourne. Un créneau de 22h à 2h appartient au soir qui l'ouvre, et à lui seul. Quand plus rien ne tourne — entre deux journées, ou sur une date sans aucun créneau programmé — l'écran le dit et n'invente pas de journée.`
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.jourJ.refus:L'absence est appliquée en entier ou pas du tout. Elle est refusée, sans rien écrire, si elle contredit une affectation forcée sur l'un des créneaux visés — le message nomme les deux ajustements — ou si l'un des postes à libérer est verrouillé : levez le verrou d'abord.`
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.jourJ.suggestions:Pour chaque place ouverte, l'assistant propose les remplaçants viables, du meilleur au moins bon, et n'en propose aucun qui casserait une règle dure. Il s'arrête aux vingt premiers candidats éligibles, parce que chacun coûte une analyse complète du planning : l'écran affiche toujours combien ont été évalués sur combien d'éligibles, pour qu'une liste écourtée ne se lise pas comme « il n'y a personne d'autre ». Appliquer une suggestion réaffecte ce seul siège, sans relancer de résolution.`
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.jourJ.publication:Rien n'est envoyé aux animateurs depuis cet écran : leur espace continue d'afficher le planning publié tant que vous n'avez pas republié. Le bandeau rappelle combien de personnes attendent un changement et renvoie vers le bouton Publier, sur la page Solveur — un envoi à cent cinquante personnes à portée de pouce dans un écran d'urgence se paierait cher.`
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.jourJ.trace:Chaque absence marquée est un ajustement manuel enregistré, avec sa raison, son auteur et son horodatage : elle se retrouve telle quelle le lendemain sur la page Ajustements manuels. Elle s'annule créneau par créneau ou d'un bloc — mais les postes déjà réaffectés ne reviennent pas d'eux-mêmes : qui tient un poste reste une décision.`
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.jourJ.mock:Cet écran ne parle que d'aujourd'hui, ce qui le rend difficile à découvrir hors de la période de l'événement. En développement seulement, la page Débogage permet donc de figer la date que le serveur considère comme aujourd'hui ; une icône d'avertissement apparaît alors dans la barre du haut, sur tous les écrans, et son lien ramène directement au champ pour la modifier ou l'effacer. Sur une instance déployée, le réglage n'existe pas et le serveur refuse de le poser : l'écran y lit toujours l'horloge réelle.`
        }
      ],
      links: [
        { route: '/jour-j', label: $localize`:@@nav.link.jourJ:Mode jour J` },
        { route: '/ad-hoc-constraints', label: $localize`:@@nav.link.adHocConstraints:Ajustements manuels` },
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
              term: $localize`:@@aide.views.term.railJour:Rail de la journée`,
              text: $localize`:@@aide.views.def.railJour:La même journée que le calendrier journalier, mais vue par personne : une ligne par animateur, les vacations placées dans le temps. Les trous, les amplitudes et les enchaînements sautent aux yeux, et les lignes vides disent qui reste mobilisable — celles marquées « indisponible » signalent au contraire de ne pas solliciter la personne.`
            },
            {
              term: $localize`:@@aide.views.term.carteJour:Carte de la journée`,
              text: $localize`:@@aide.views.def.carteJour:Encore la même journée, mais sur la carte des emplacements : un curseur temporel, et chaque emplacement coloré par ce que le planning enregistré dit qu'il s'y passe à cet instant — ouvert et entièrement pourvu, ouvert avec des places vides, ou ouvert sans personne. Rien n'est recalculé : un stand est ouvert quand le plan porte un poste qui couvre cet instant. Les stands rattachés à aucun emplacement géolocalisé sont listés à côté de la carte plutôt qu'escamotés.`
            },
            {
              term: $localize`:@@aide.views.term.graphe:Graphe`,
              text: $localize`:@@aide.views.def.graphe:La même donnée prise par le terrain : un emplacement, les stands qui s'y trouvent, les créneaux sur lesquels ils sont armés, qui y est affecté. Une colonne par niveau, on clique pour ouvrir la suivante. C'est la vue de qui connaît le plan de l'événement mieux que la liste de ses stands. Les deux dernières colonnes se lisent sur le planning enregistré et restent vides tant qu'aucune résolution n'a tourné ; les emplacements et leurs stands, eux, viennent des données de référence et se parcourent tout de suite.`
            },
            {
              term: $localize`:@@aide.views.term.kpi:Autopsie du planning`,
              text: $localize`:@@aide.views.def.kpi:Une ligne par résolution terminée, toutes éditions confondues : score, couverture, dispersion des heures, nombre de modifications manuelles, durée. C'est la mémoire des campagnes passées — elle dit si l'édition en cours se règle mieux ou moins bien que la précédente, et combien de reprises à la main il a fallu. Rien n'y est nominatif, et l'historique survit à la suppression de l'édition qu'il décrit.`
            },
            {
              term: $localize`:@@aide.views.term.comparateur:Comparateur A/B`,
              text: $localize`:@@aide.views.def.comparateur:Deux plannings côte à côte — deux instantanés, ou un instantané et le planning actuel — sur le score, la couverture, l'équité et les violations, avec le sens de chaque écart écrit en toutes lettres. Les instantanés de toutes les éditions sont proposés : c'est ainsi qu'on compare une variante (canicule, repli) à l'édition nominale. L'écran prévient quand les deux plannings n'ont pas la même taille ou ne viennent pas de la même édition : une partie de l'écart vient alors du problème posé, pas de la qualité de la résolution. Comparer ne lance jamais de calcul.`
            },
            {
              term: $localize`:@@aide.views.term.banc:Banc de touche`,
              text: $localize`:@@aide.views.def.banc:Pour un créneau, qui n'est affecté nulle part — et, pour chacun, la règle qui l'empêcherait de prendre la place restée libre : indisponible ce jour-là, repos légal, plafond d'heures atteint, appréciation manquante. Toutes les raisons applicables sont affichées, pas seulement la première : c'est ce qui dit si lever un obstacle suffirait. La vue est en lecture seule ; pour agir, passez par l'assistant de réparation du calendrier journalier. Elle lit le planning enregistré et ne propose donc que les créneaux qu'il pourvoit : un créneau où aucun stand n'est ouvert n'y figure pas, et tant qu'aucune résolution n'a tourné l'écran le dit au lieu de rester vide. Il est livré à l'essai, sous « En cours de développement » : il pourra être retiré s'il ne s'avère pas utile.`
            },
            {
              term: $localize`:@@aide.views.term.hours:Heures et besoin en animateurs`,
              text: $localize`:@@aide.views.def.hours:Les heures travaillées par animateur d'un côté, l'estimation du nombre minimum d'animateurs à recruter de l'autre. La seconde se calcule avant toute résolution, à partir des seuls stands et créneaux. Elle se lit aussi typologie par typologie, en bas de la page : une typologie dont le minimum dépasse le nombre d'animateurs qui la déclarent est le goulot — il n'y manque pas des animateurs en général, mais des animateurs compétents sur ce jeu-là. Deux règles rendent cette lecture honnête : un siège n'est compté pour une typologie que si son stand ne propose qu'elle, et un polyvalent ne compte comme spécialiste que dans les compétences qu'il déclare — ailleurs il apparaît à part, comme un renfort mobilisable sur n'importe quelle typologie mais sur un siège à la fois.`
            },
            {
              term: $localize`:@@aide.views.term.fragilite:Fragilité du planning`,
              text: $localize`:@@aide.views.def.fragilite:Qui est irremplaçable. Pour chaque personne, les créneaux qui passeraient sous l'effectif minimum si elle se désiste — et, colonne décisive, ceux que personne d'autre ne pourrait reprendre ce jour-là. La seconde vue liste les stands tenus par une seule personne compétente pour leurs typologies, ce qui désigne où recruter ou former. Les polyvalents « ninja » y sont comptés à part, en renforts : ils peuvent dépanner partout, mais un stand qu'ils sont seuls à pouvoir tenir n'a toujours aucun spécialiste. Rien n'est recalculé par le solveur : l'écran lit le planning enregistré. Il est livré à l'essai, sous « En cours de développement » : il pourra être retiré s'il ne s'avère pas utile.`
            }
          ]
        }
      ],
      links: [
        { route: '/calendar', label: $localize`:@@nav.link.calendar:Calendrier des affectations` },
        { route: '/heatmap', label: $localize`:@@nav.link.heatmap:Heatmap de charge` },
        { route: '/timeline', label: $localize`:@@nav.link.timeline:Timeline animateur` },
        { route: '/rail-jour', label: $localize`:@@nav.link.railJour:Rail de la journée` },
        { route: '/carte-jour', label: $localize`:@@nav.link.carteJour:Carte de la journée` },
        { route: '/hours', label: $localize`:@@nav.link.hours:Heures` },
        { route: '/fragilite', label: $localize`:@@nav.link.fragilite:Fragilité du planning` },
        { route: '/banc-de-touche', label: $localize`:@@nav.link.bancDeTouche:Banc de touche` },
        { route: '/graphe', label: $localize`:@@nav.link.graphe:Graphe` },
        { route: '/kpi', label: $localize`:@@nav.link.kpi:Autopsie du planning` },
        { route: '/comparateur', label: $localize`:@@nav.link.comparateur:Comparateur A/B` }
      ]
    },
    {
      id: 'raccourcis-clavier',
      icon: 'keyboard',
      title: $localize`:@@aide.shortcuts.title:Raccourcis clavier`,
      summary: $localize`:@@aide.shortcuts.summary:Se déplacer d'un écran à l'autre et retrouver un animateur sans lâcher le clavier.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.shortcuts.intro:Ctrl+K ouvre la palette de commandes : une seule zone de saisie qui mène à n'importe quelle page de l'application et qui cherche aussi dans les données de référence — un animateur (sa timeline s'ouvre), un stand ou un créneau (le calendrier s'ouvre dessus). Les flèches parcourent la liste, Entrée ouvre, Échap referme. C'est le point d'entrée à retenir : tous les autres raccourcis ne sont que des abrégés de ce qu'elle sait déjà faire.`
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.shortcuts.term.palette:Ctrl+K`,
              text: $localize`:@@aide.shortcuts.def.palette:Ouvre (et referme) la palette de commandes. Le navigateur ne reçoit pas ce raccourci tant qu'un onglet de l'application est au premier plan.`
            },
            {
              term: $localize`:@@aide.shortcuts.term.help:?`,
              text: $localize`:@@aide.shortcuts.def.help:Affiche la liste complète des raccourcis, sans quitter l'écran en cours.`
            },
            {
              term: $localize`:@@aide.shortcuts.term.filter:/`,
              text: $localize`:@@aide.shortcuts.def.filter:Place le curseur dans le filtre de la page courante, quand elle en a un (les pages de données de référence, et cette page d'aide).`
            },
            {
              term: $localize`:@@aide.shortcuts.term.submit:Ctrl+Entrée`,
              text: $localize`:@@aide.shortcuts.def.submit:Valide le formulaire en cours de saisie, comme un clic sur son bouton d'enregistrement. Un formulaire dont le bouton est désactivé (une saisie incomplète) n'est pas enregistré pour autant : le clavier ne fait rien que la souris ne ferait.`
            },
            {
              term: $localize`:@@aide.shortcuts.term.escape:Échap`,
              text: $localize`:@@aide.shortcuts.def.escape:Ferme la fenêtre ouverte (palette, formulaire, confirmation) et rend le focus à l'endroit d'où elle a été ouverte.`
            }
          ]
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.shortcuts.go:Pour aller directement sur un écran, tapez « g » puis la lettre de la destination — « g » puis « a » pour les animateurs. Les pages qui n'ont pas de lettre restent atteignables par la palette, qui les liste toutes.`
        },
        {
          kind: 'definitions',
          items: buildRaccourcisNavigation().map((raccourci) => ({
            term: `g ${raccourci.touche}`,
            text: raccourci.label
          }))
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.shortcuts.tables:Les tableaux de données de référence (animateurs, stands, créneaux, emplacements, typologies) se parcourent aussi au clavier. Le chemin le plus court pour y entrer : « / » place le curseur dans le filtre de la page, tapez de quoi réduire la liste, puis Flèche bas saute directement sur la première ligne. Tab y entre également, sur une seule ligne — jamais ligne par ligne — mais il traverse d'abord les commandes de l'en-tête (la case « tout sélectionner » et chaque en-tête triable). Les flèches déplacent ensuite le focus d'un cran ; Tab ressort de la ligne vers ses boutons, puis vers le reste de la page.`
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.shortcuts.term.rowEnter:Flèche bas depuis le filtre`,
              text: $localize`:@@aide.shortcuts.def.rowEnter:Entre dans le tableau sans compter les tabulations : le focus saute sur la ligne courante, celle qu'un contour marque. C'est le geste à retenir — filtrer, puis descendre. Un clic sur une ligne la focalise de la même façon, et les flèches enchaînent aussitôt.`
            },
            {
              term: $localize`:@@aide.shortcuts.term.rowMove:Flèches haut et bas`,
              text: $localize`:@@aide.shortcuts.def.rowMove:Passent d'une ligne à l'autre. Début et Fin sautent à la première et à la dernière ligne affichée. Le focus suit la ligne, pas son rang : changer le tri ne le fait pas sauter ailleurs, et filtrer la ligne focalisée le repose sur celle qui prend sa place.`
            },
            {
              term: $localize`:@@aide.shortcuts.term.rowOpen:Entrée`,
              text: $localize`:@@aide.shortcuts.def.rowOpen:Ouvre la ligne focalisée : sa fiche de consultation, ou directement son formulaire sur les créneaux, qui n'ont pas de fiche. Comme les boutons de la ligne, la touche reste sans effet tant qu'une résolution verrouille l'édition.`
            },
            {
              term: $localize`:@@aide.shortcuts.term.rowSelect:Espace`,
              text: $localize`:@@aide.shortcuts.def.rowSelect:Coche ou décoche la ligne focalisée. La barre d'actions groupées apparaît dès la première ligne cochée, exactement comme avec la souris.`
            }
          ]
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.shortcuts.guard:Aucun raccourci à une touche ne se déclenche pendant que vous saisissez du texte : tant que le curseur est dans un champ, « g », « / » et « ? » restent des caractères ordinaires. Ils reprennent dès que le focus quitte le champ.`
        }
      ],
      links: [
        { route: '/animateurs', label: $localize`:@@nav.link.animateurs:Animateurs` },
        { route: '/timeline', label: $localize`:@@nav.link.timeline:Timeline animateur` },
        { route: '/calendar', label: $localize`:@@nav.link.calendar:Calendrier des affectations` }
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
              text: $localize`:@@aide.foire.def.espace:L'animateur constitue sa liste de demandes (créneau concerné, collègue avec qui échanger, motif) puis la soumet en une fois. Quand il n'a personne en tête — il ne veut simplement pas ce créneau — « qui peut me remplacer ? » cherche les collègues avec qui l'échange tient réellement, sous ses trois formes : un collègue libre le remplace, une permutation sur le même créneau, ou un troc contre un créneau d'un autre jour. Il choisit dans la liste et le champ collègue se remplit. Il suit ensuite le statut de chacune — en attente, acceptée, refusée — avec votre commentaire éventuel, et peut annuler une demande tant qu'elle n'est pas décidée. Il peut aussi, depuis l'onglet « Mon planning », emporter son planning : la bande « Emporter mon planning » propose d'abord d'y abonner son agenda, puis de télécharger un PDF ou un fichier ICS. Le lien de son espace se copie (et se régénère, si un PDF a fuité) depuis sa fiche sur la page Animateurs.`
            },
            {
              term: $localize`:@@aide.foire.term.ecran:Écran Échanges`,
              text: $localize`:@@aide.foire.def.ecran:Les demandes en attente, avec pour chacune son impact mesuré sur le planning actuel : échange croisé (les deux permutent) ou simple reprise (le collègue est libre sur le créneau), effet sur le score, règles dures qui seraient cassées. Accepter applique l'échange immédiatement, exactement comme simulé, et le verrouille sur son créneau : une régénération ultérieure ne le défera pas — elle reste à lancer depuis la page Solveur. Refuser ne modifie rien ; le motif saisi est transmis à l'animateur.`
            },
            {
              term: $localize`:@@aide.foire.term.connexion:Connexion et notifications`,
              text: $localize`:@@aide.foire.def.connexion:L'administration est protégée par le compte admin ; seuls les espaces animateurs restent accessibles par leur lien personnel. Si la messagerie est configurée, vous êtes prévenu par e-mail à chaque soumission. L'animateur, lui, apprend le sort de ses demandes à la publication suivante, avec le planning qui les porte : accepter un échange change le plan de travail, pas encore celui qu'il a reçu.`
            },
            {
              term: $localize`:@@aide.foire.term.planPublie:Plan publié et plan de travail`,
              text: $localize`:@@aide.foire.def.planPublie:L'espace d'un animateur montre le plan qu'on lui a envoyé, pas celui sur lequel vous travaillez : un échange validé, un remplacement appliqué ou une nouvelle résolution ne déplacent son espace qu'une fois publiés. Tant que rien ne l'a été sur l'édition, les espaces restent vides et le disent — l'application ne peut pas affirmer avoir communiqué un planning qu'elle n'a jamais envoyé. La première publication concerne donc tout le monde. Publier est refusé pendant une résolution : ce serait figer un plan sur le point d'être réécrit.`
            },
            {
              term: $localize`:@@aide.foire.term.abonnement:Abonnement au calendrier`,
              text: $localize`:@@aide.foire.def.abonnement:Depuis la bande « Emporter mon planning » de son espace, un animateur donne à son agenda une adresse d'abonnement permanente, au lieu de télécharger un fichier ICS qui se périme dès la republication. Ce que cet agenda relit à chaque synchronisation, c'est le planning publié : « mon agenda ne se met pas à jour » veut donc presque toujours dire que le changement n'a pas encore été publié, et jamais qu'il faut se réabonner. Tant que rien n'a été publié sur l'édition, l'abonnement répond un calendrier vide plutôt qu'une erreur — un agenda à qui l'on répond en erreur coupe l'abonnement sans prévenir personne. Cette adresse est un second identifiant, distinct du lien de l'espace : régénérer le lien d'un animateur depuis sa fiche ne coupe pas son abonnement, et l'animateur remplace lui-même son adresse d'abonnement si elle a fuité, sans que son lien d'espace change. Vous ne la voyez pas et n'avez pas à la manipuler.`
            },
            {
              term: $localize`:@@aide.foire.term.ouverture:Ouverture et fermeture`,
              text: $localize`:@@aide.foire.def.ouverture:L'interrupteur en tête de l'écran Échanges ouvre ou ferme la foire pour l'édition courante. Fermée, les espaces animateurs passent en consultation seule — le planning reste visible, téléchargeable (PDF, ICS) et les agendas abonnés continuent de le suivre, mais plus aucune demande ne peut être soumise ni annulée, et le refus est appliqué côté serveur, pas seulement masqué à l'écran. Vous pouvez aussi borner la foire par une date de début et une date de fin, comme la collecte des disponibilités : laissez une date vide pour ne pas borner ce côté-là. L'interrupteur reste maître — une période renseignée n'ouvre jamais une foire fermée — et les bornes sont appliquées côté serveur elles aussi. Avant la date d'ouverture, l'espace annonce « pas encore ouverte » et la date de retour, et non « fermée » : quelqu'un à qui l'on dit que c'est terminé deux semaines trop tôt ne revient pas.`
            },
            {
              term: $localize`:@@aide.foire.term.envoi:Publier le planning`,
              text: $localize`:@@aide.foire.def.envoi:Le bouton « Publier » de la page Solveur (à côté de l'export du planning global) porte son décompte : « Publier — 3 personnes concernées ». Il n'écrit qu'aux animateurs dont l'emploi du temps a changé depuis la dernière publication, et le message dit ce qui change pour chacun — la même liste que vous relisez à l'écran avant de valider. Une correction qui ne déplace personne ne déclenche donc aucun envoi, et dix corrections d'affilée ne font pas dix courriels : elles remplissent une file que vous videz quand vous avez fini. Le compte rendu nomme les animateurs sans adresse e-mail et les envois en échec ; la page Timeline animateur permet de renvoyer à l'un d'eux le plan publié. Sur un gros effectif, l'envoi prend plusieurs dizaines de secondes : l'écran l'annonce et neutralise le bouton le temps qu'il parte, un seul envoi à la fois — inutile de recliquer.`
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
      id: 'disponibilites',
      icon: 'event_available',
      title: $localize`:@@aide.dispo.title:Collecte des disponibilités et des souhaits`,
      summary: $localize`:@@aide.dispo.summary:Les animateurs déclarent eux-mêmes leurs jours d'indisponibilité et leurs souhaits ; vous appliquez, ou non.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.dispo.intro:Avant de construire un planning, il faut savoir qui ne peut pas venir quand. Plutôt que de collecter ça hors de l'application puis de le ressaisir, vous ouvrez une fenêtre de collecte : chaque animateur déclare depuis son espace, sur son téléphone, les jours où il est indisponible et les types de jeux qu'il aimerait animer. Ce qu'il envoie ne touche à rien : c'est une proposition, qui attend votre décision sur l'écran Disponibilités.`
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.dispo.term.fenetre:Ouvrir la collecte`,
              text: $localize`:@@aide.dispo.def.fenetre:Le bandeau « Fenêtre de collecte », en tête de la page Disponibilités, porte les deux boutons « Ouvrir la collecte » et « Fermer la collecte ». Elle est fermée tant que vous ne l'avez pas ouverte — contrairement à la foire au planning, ouverte par défaut. Les deux dates sont facultatives : elles bornent la période, l'interrupteur reste le maître. Fermée, l'espace refuse toute déclaration côté serveur, pas seulement à l'écran ; l'animateur garde l'accès à ce qu'il a déclaré et à vos réponses.`
            },
            {
              term: $localize`:@@aide.dispo.term.prevenir:Prévenir les animateurs`,
              text: $localize`:@@aide.dispo.def.prevenir:Une case à cocher au moment d'ouvrir, jamais un réglage permanent : elle envoie à chacun le lien de son espace, directement sur l'onglet de déclaration. Cochez-la au premier tour ; laissez-la de côté quand vous rouvrez la fenêtre après une correction, sinon tout le monde reçoit une relance pour rien. Le compte rendu nomme ceux qui n'ont pas d'adresse e-mail et les envois en échec.`
            },
            {
              term: $localize`:@@aide.dispo.term.decision:Appliquer ou refuser, en bloc`,
              text: $localize`:@@aide.dispo.def.decision:L'écran montre côte à côte ce que l'animateur déclare et ce que sa fiche dit aujourd'hui. Appliquer écrit la proposition entière sur sa fiche, exactement comme si vous aviez ouvert sa fiche pour la modifier — le planning enregistré est alors signalé comme périmé, et la régénération reste une action à part depuis la page Solveur. Refuser ne modifie rien ; le motif que vous saisissez est lu par l'animateur dans son espace. Il n'y a délibérément pas de validation ligne à ligne : un désaccord se règle par un mot, et l'animateur renvoie une version corrigée.`
            },
            {
              term: $localize`:@@aide.dispo.term.remplacement:Une seule proposition par personne`,
              text: $localize`:@@aide.dispo.def.remplacement:Un animateur qui se corrige remplace sa proposition en attente, il n'en empile pas une seconde : vous n'aurez jamais à arbitrer deux versions contradictoires de la même personne, et c'est toujours la dernière qui vous parvient. Vous n'êtes prévenu par e-mail qu'à l'arrivée d'une proposition sur votre bureau, pas à chaque correction qu'il y apporte avant que vous ne la traitiez.`
            },
            {
              term: $localize`:@@aide.dispo.term.competences:Ce qui ne se déclare pas`,
              text: $localize`:@@aide.dispo.def.competences:Les compétences restent décidées avec vous. Une compétence auto-déclarée alimente des règles dures — qui a le droit de tenir quel stand — et l'enjeu n'est pas celui d'une indisponibilité ou d'un souhait, qui se rattrapent. Un animateur qui déclare un jour hors des dates de l'événement, ou une typologie qui n'existe pas, est refusé à l'envoi.`
            }
          ]
        }
      ],
      links: [
        { route: '/disponibilites', label: $localize`:@@nav.link.disponibilites:Disponibilités` },
        { route: '/animateurs', label: $localize`:@@nav.link.animateurs:Animateurs` },
        { route: '/creneaux', label: $localize`:@@nav.link.creneaux:Créneaux` }
      ]
    },
    {
      id: 'rappels',
      icon: 'notifications_active',
      title: $localize`:@@aide.rappels.title:Accusés de réception et rappels automatiques`,
      summary: $localize`:@@aide.rappels.summary:Savoir qui a lu son planning, et laisser l'application relancer les autres — une fois.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.rappels.intro:Une fois le planning publié, chaque animateur voit dans son espace un bouton « J'ai lu et je serai là ». La colonne « Accusé de réception » de la page Animateurs vous rend la réponse, et le filtre rapide de la table accepte « confirmé », « relancé » ou « silencieux » comme n'importe quel autre mot.`
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.rappels.term.statuts:Les trois statuts`,
              text: $localize`:@@aide.rappels.def.statuts:« Silencieux » : rien n'est revenu. « Confirmé » : le bouton a été cliqué, la date s'affiche au survol. « Relancé » : la relance automatique est partie et reste sans réponse. Un animateur sans aucun poste au planning publié affiche « — » : il n'est pas silencieux, on ne lui a rien demandé, et il ne compte pas parmi les gens à relancer.`
            },
            {
              term: $localize`:@@aide.rappels.term.republication:Republier ne remet pas tout le monde à zéro`,
              text: $localize`:@@aide.rappels.def.republication:Une nouvelle publication ne redemande une confirmation qu'aux personnes dont l'emploi du temps a réellement changé. Quelqu'un qu'on prévient seulement d'une décision d'échange lit les mêmes journées qu'avant : lui reposer la question transformerait le bouton en réflexe plutôt qu'en réponse.`
            },
            {
              term: $localize`:@@aide.rappels.term.activation:Activer l'édition, sur la page Paramètres`,
              text: $localize`:@@aide.rappels.def.activation:Les envois de nuit sont désactivés tant que vous ne les activez pas, édition par édition. C'est volontaire et c'est le seul garde-fou : une édition passée porte les mêmes animateurs, et rien d'autre ne distingue les bénévoles de cette année de ceux de l'an dernier. Dupliquer une édition ne recopie pas ce réglage.`
            },
            {
              term: $localize`:@@aide.rappels.term.delais:Les trois délais`,
              text: $localize`:@@aide.rappels.def.delais:L'heure d'envoi du rappel de la veille (23h00 au plus tard : la tâche s'exécute une fois par heure, et un rappel réglé plus tard ne partirait jamais), le silence toléré après une publication avant de relancer, et l'ancienneté d'une demande d'échange qui déclenche une alerte. Tous trois se règlent par édition sur la page Paramètres.`
            },
            {
              term: $localize`:@@aide.rappels.term.rappel:Le rappel de la veille`,
              text: $localize`:@@aide.rappels.def.rappel:La veille au soir, chaque animateur affecté le lendemain reçoit la liste de ses créneaux. Elle est tirée du planning publié, jamais du plan de travail : personne n'est rappelé pour un créneau que vous ne lui avez pas communiqué. Un animateur sans adresse e-mail est sauté, et signalé nommément sur la page Notifications — ce sont les gens à prévenir à la main.`
            },
            {
              term: $localize`:@@aide.rappels.term.relance:Une relance, pas une série`,
              text: $localize`:@@aide.rappels.def.relance:Passé le délai, les silencieux reçoivent un rappel de confirmation et passent à « Relancé ». Ils n'en recevront pas d'autre : relancer quelqu'un tous les soirs ne le fait pas répondre plus vite, ça le fait filtrer vos messages. À vous de reprendre la main sur les derniers.`
            },
            {
              term: $localize`:@@aide.rappels.term.echanges:Les demandes d'échange qui dorment`,
              text: $localize`:@@aide.rappels.def.echanges:Une demande qui attend votre décision depuis plus longtemps que le délai fixé remonte sur la page Notifications, et une seule fois — même si elle vieillit encore. L'ancienneté se compte à partir de l'accord du collègue : une demande qui attend encore sa réponse n'attend pas après vous.`
            }
          ]
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.rappels.silence:« Je n'ai rien reçu » a presque toujours la même cause : l'édition n'a pas été activée. Ensuite viennent le planning jamais publié, puis les fiches sans adresse e-mail. Ces alertes-là se referment en traitant ce qu'elles signalent, pas en les effaçant.`
        }
      ],
      links: [
        { route: '/animateurs', label: $localize`:@@nav.link.animateurs:Animateurs` },
        { route: '/parametres', label: $localize`:@@nav.link.parametres:Paramètres` },
        { route: '/notifications', label: $localize`:@@nav.link.notifications:Notifications` }
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
            $localize`:@@aide.exchange.item.csvAnimateurs:Import CSV des animateurs : reprendre le tableur des bénévoles sans le ressaisir, avec un aperçu ligne à ligne avant que quoi que ce soit ne soit écrit. Le format attendu a sa propre section dans ce guide.`,
            $localize`:@@aide.exchange.item.sql:Export et import d'un dump SQL complet, pour dupliquer ou restaurer un jeu de données entier.`,
            $localize`:@@aide.exchange.item.pdfIcs:Export du planning individuel d'un animateur en PDF ou en ICS (importable dans Google Calendar, Apple Calendar ou Outlook), à l'unité ou en archive ZIP pour tout le monde. Un fichier ICS est une photo : il ne bougera plus. Pour un agenda qui suit les republications, c'est l'abonnement de l'espace animateur qu'il faut — voir « Foire au planning ».`,
            $localize`:@@aide.exchange.item.yamlValidator:Validateur YAML (sur la page Débogage) : vérifie un fichier scénario sans rien importer, pour corriger avant de toucher aux données.`,
            $localize`:@@aide.exchange.item.notifications:Notifications : l'historique des événements — fin de résolution, import, erreur. Une alerte non lue est signalée dans la navigation.`,
            $localize`:@@aide.exchange.item.debug:Débogage : l'état brut renvoyé par le serveur, utile pour rapporter un problème précisément.`,
            $localize`:@@aide.exchange.item.sauvegarde:Sauvegarde de nuit (page Paramètres) : la base entière est copiée sur le disque du serveur chaque nuit, et l'écran dit où vont les copies, lesquelles existent et si la dernière s'est bien passée. Elle ne se télécharge pas — elle porte les noms, les dates de naissance et les adresses de tout le monde — et la restauration est une opération de l'exploitant sur le serveur, pas un bouton.`
          ]
        }
      ],
      links: [
        { route: '/parametres', label: $localize`:@@nav.link.parametres:Paramètres` },
        {
          route: '/import-animateurs',
          label: $localize`:@@nav.link.importAnimateurs:Import CSV des animateurs`
        },
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
