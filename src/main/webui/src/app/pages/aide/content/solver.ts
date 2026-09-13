// Computing a planning: launching a solve, configuring it, parametrising
// for a complete planning, reading a score, tuning, and the manual
// adjustments that sit before the solve.
//
// One theme of the organisers' guide; `aide-content.ts` assembles the
// themes in reading order. Built lazily, never at module scope: `$localize`
// only resolves once `main.ts` has loaded the translation catalog.

import { HelpSection } from '../help-section';

export function buildSolverSections(): HelpSection[] {
  return [
    {
      id: 'calculer',
      icon: 'play_arrow',
      title: $localize`:@@aide.calculer.title:Calculer, corriger, recommencer`,
      summary: $localize`:@@aide.calculer.summary:Les trois façons de lancer le solveur, et laquelle choisir selon la situation.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.calculer.intro:La page Solveur ne demande pas de choisir un mécanisme mais de dire ce qu'on veut : le meilleur planning possible, une correction qui bouge le moins possible, ou une page blanche. Les trois lisent le même référentiel et respectent les mêmes règles ; ce qui change, c'est d'où le calcul part et ce qu'il s'autorise à bouger.`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.calculer.term.calculer:Calculer le planning`,
              text: $localize`:@@aide.calculer.def.calculer:Le bouton de tous les jours. S'il existe un plan enregistré, le calcul en repart et cherche à l'améliorer avec tout le budget ; sinon il part de zéro. Rien n'est figé hormis les verrouillages : tout peut bouger, mais le résultat ne peut pas finir en dessous du plan de départ à données égales, puisque le solveur garde le meilleur de ce qu'il a vu. À utiliser après avoir saisi ou corrigé des données, ou simplement pour laisser tourner plus longtemps. La ligne sous les boutons dit d'où partira le prochain calcul.`,
            },
            {
              term: $localize`:@@aide.calculer.term.corriger:Corriger après un changement`,
              text: $localize`:@@aide.calculer.def.corriger:Un désistement, une indisponibilité saisie tard, un stand ajouté : le planning est déjà diffusé et l'on veut bouger le moins possible. Tout ce qui reste valable est figé le temps du calcul, seuls les postes que le changement a invalidés (et ceux que vous rouvrez à la main) sont recalculés, en quelques dizaines de secondes. Le reste du planning est garanti inchangé, et le compte rendu nomme qui a bougé. Ne réoptimise rien : pour améliorer le plan, c'est « Calculer le planning ».`,
            },
            {
              term: $localize`:@@aide.calculer.term.recommencer:Recommencer de zéro`,
              text: $localize`:@@aide.calculer.def.recommencer:Ignore le plan enregistré et repart de rien. C'est le seul geste qui peut perdre la qualité déjà atteinte — sur un événement réel, une relance de zéro a coûté plus d'un millier de points au niveau de couverture — d'où la confirmation. À réserver au plan qu'on ne veut pas garder : un essai qu'on abandonne, des données refaites en profondeur. Sans plan enregistré, le bouton est inactif : « Calculer le planning » part déjà de zéro.`,
            },
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.calculer.verrous:Les verrouillages ne sont pas un quatrième mode : ils s'appliquent aux trois. Un animateur, un stand, une journée ou un créneau verrouillé n'est touché ni par un calcul complet ni par une correction. Et quand le solveur est occupé, chacun des trois boutons planifie le calcul au lieu de le refuser : il démarrera de lui-même, sur l'édition courante, dès que la tâche en cours sera terminée.`,
        },
      ],
      links: [
        { route: '/solveur', label: $localize`:@@nav.link.solver:Solveur` },
        { route: '/verrouillages', label: $localize`:@@nav.link.verrouillages:Verrouillages` },
      ],
    },
    {
      id: 'configuration-solveur',
      icon: 'tune',
      title: $localize`:@@aide.config.title:Configuration du solveur`,
      summary: $localize`:@@aide.config.summary:Les cinq réglages qui changent le comportement du solveur, et lequel toucher en premier.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.config.intro:Le solveur n'a pas de « niveau de qualité » à régler : il cherche, dans le temps qu'on lui donne, le meilleur planning au sens des contraintes actives. Les réglages agissent donc soit sur le temps accordé, soit sur les règles à respecter.`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.config.term.duree:Durée de résolution`,
              text: $localize`:@@aide.config.def.duree:Temps maximal accordé à une résolution, réglé sur la page Solveur (3 min par défaut) et partagé par tous les navigateurs. C'est le réglage qui compte le plus : sur un événement complet, quelques minutes suffisent rarement à atteindre un score dur nul. Commencez court (1 à 3 min) pour révéler les blocages structurels, puis passez à 15-30 min, voire davantage, pour la résolution finale.`,
            },
            {
              term: $localize`:@@aide.config.term.mailFin:Prévenir à la fin d'une résolution`,
              text: $localize`:@@aide.config.def.mailFin:Sur la page Paramètres, un interrupteur fait écrire à l'administrateur dès qu'une résolution de cette édition se termine : l'édition, le score et si le planning est faisable. De quoi lancer un calcul de trente minutes et partir. Le réglage vaut pour l'édition, pas pour le serveur : on est prévenu de la résolution qu'on attend, pas de chaque essai lancé ailleurs. Il reste sans effet tant qu'aucune adresse administrateur n'est configurée sur le serveur — l'écran le dit alors explicitement.`,
            },
            {
              term: $localize`:@@aide.config.term.legaux:Paramètres légaux`,
              text: $localize`:@@aide.config.def.legaux:Sur la page Paramètres. Les plafonds hebdomadaires de temps de travail (48 h pour les majeurs, 35 h pour les mineurs) sont d'ordre public — une valeur supérieure est refusée, une valeur inférieure reste libre. Le repos quotidien minimal (11 h, 9 h par accord collectif) et la pause minimale entre deux vacations (30 min par défaut, sans base légale : à 0, deux vacations peuvent s'enchaîner) se règlent au même endroit. Enfin, la « pause légale prise sur le poste » déclare que les vingt minutes dues à la sixième heure se prennent par relais entre collègues plutôt que comme un trou entre deux vacations : cochée, une séquence de sept heures devient possible, et les plafonds quotidiens déduisent cette pause.`,
            },
            {
              term: $localize`:@@aide.config.term.contraintes:Activation des contraintes`,
              text: $localize`:@@aide.config.def.contraintes:Chaque règle du catalogue peut être désactivée depuis la page Contraintes. À utiliser pour diagnostiquer (« sans cette règle, le planning devient-il faisable ? ») bien plus que pour produire : désactiver une contrainte dure produit un planning que la réalité refusera. En revanche, désactiver une contrainte souple qui ne mesure rien — par exemple les souhaits quand aucun animateur n'en a déclaré — rend le score lisible sans rien changer au résultat.`,
            },
            {
              term: $localize`:@@aide.config.term.adhoc:Ajustements manuels`,
              text: $localize`:@@aide.config.def.adhoc:Exceptions saisies au cas par cas, avec une raison tracée : indisponibilité forcée, incompatibilité entre deux animateurs, affectation forcée. Les trois sont traitées au même niveau que les contraintes dures, donc jamais contournées silencieusement — et chacune retire des possibilités au solveur. Un ajustement mal posé bloque un planning aussi sûrement qu'un manque d'effectif. Voir la section « Ajustements manuels » pour ce que chaque type recouvre exactement.`,
            },
            {
              term: $localize`:@@aide.config.term.verrouillages:Verrouillages`,
              text: $localize`:@@aide.config.def.verrouillages:Geler un animateur, un stand, une journée ou un créneau pour que la prochaine résolution n'y touche plus et optimise le reste. Utile pour figer une partie validée du planning. Une place restée non pourvue n'est jamais gelée, et les places gelées continuent d'être évaluées : un verrou peut donc laisser une alerte visible plutôt que de masquer un problème. Ce n'est pas un ajustement manuel : le verrou conserve ce qu'un calcul a produit, l'ajustement dit d'avance où placer ou ne pas placer quelqu'un — voir l'encart « Ajustement manuel ou verrouillage ? » de la section Ajustements manuels.`,
            },
            {
              term: $localize`:@@aide.config.term.journeesTypes:Journées types`,
              text: $localize`:@@aide.config.def.journeesTypes:Une grille de créneaux est toujours faite de vacations : des tranches de travail réelles, sur lesquelles on affecte. Plutôt que de saisir chaque jour, décrivez une fois chaque forme de journée — « Jour normal », « Nocturne », « Montage » — avec ses vacations, puis affectez-lui des dates : appliquer le calendrier écrit la grille. Une vacation marquée « relais repas » garde le stand ouvert à la moitié de son effectif, arrondie au supérieur, pendant que l'autre moitié déjeune. Le nombre de vacations détermine le nombre de postes à pourvoir : le changer change la taille du problème bien plus que n'importe quel réglage du solveur.`,
            },
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.config.lock:Une seule résolution tourne à la fois pour tout le serveur — éventuellement lancée depuis un autre poste ou un autre navigateur ; le moniteur de la barre d'outils indique laquelle, sur quelle édition et depuis combien de temps. Le calcul travaille sur l'édition depuis laquelle il a été lancé : seule celle-ci est verrouillée en saisie et en import. Si vous basculez sur une autre édition, vous pouvez continuer à y saisir des données ou y importer un scénario pendant la résolution.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.config.file:Pendant qu'un calcul tourne, les deux boutons de lancement deviennent « Planifier la résolution » et « Planifier la replanification » : la tâche est mise en file et démarre d'elle-même dès que la précédente se termine. C'est le geste à faire pour enchaîner deux éditions sans attendre devant l'écran — préparez la suivante, planifiez son calcul, fermez l'écran. Une tâche en attente ne verrouille rien, et elle lit son édition telle qu'elle sera au moment de démarrer : une correction apportée entre-temps sera bien prise en compte. La file s'affiche sous les boutons, on peut en retirer une ligne tant qu'elle n'a pas démarré, et planifier deux fois la même tâche (même édition, même calcul) est refusé — le classique double-clic. Planifier une nouvelle résolution de l'édition en cours de calcul reste permis : le calcul qui tourne a démarré avant vos dernières corrections et ne peut pas en tenir compte. La file vit en mémoire : un redémarrage du serveur la vide.`,
        },
      ],
      links: [
        { route: '/solveur', label: $localize`:@@nav.link.solver:Solveur` },
        { route: '/constraints', label: $localize`:@@nav.link.constraints:Contraintes` },
        { route: '/parametres', label: $localize`:@@nav.link.parametres:Paramètres` },
        { route: '/verrouillages', label: $localize`:@@nav.link.verrouillages:Verrouillages` },
      ],
    },
    {
      id: 'parametrer-pour-un-planning-complet',
      icon: 'checklist',
      title: $localize`:@@aide.setup.title:Paramétrer pour un planning complet`,
      summary: $localize`:@@aide.setup.summary:Créneaux, stands et options : ce qui fait tenir un planning sans le moindre écart avec l'effectif dont vous disposez, et dans quel ordre le vérifier.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.setup.intro:Un planning qui ne se remplit pas n'est presque jamais une affaire de temps de calcul. Trois causes reviennent, dans cet ordre : un besoin mal décrit (des stands dont l'effectif ne varie pas dans la journée alors qu'il varie), une grille de créneaux qui oblige à deux équipes là où une suffit, et des réglages qui disent plus que la loi. Cette section décrit le paramétrage qui les évite, tel qu'il a été mesuré sur une édition réelle de cent cinquante-trois animateurs.`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.setup.term.stands:Les stands : un effectif par fenêtre, pas par stand`,
              text: $localize`:@@aide.setup.def.stands:L'effectif minimum du stand s'applique à toute la journée. Si un stand tient à deux le matin et à quatre l'après-midi, saisir « 2 » sous-dote l'après-midi d'un tiers, et saisir « 4 » sur-dote le matin. Donnez à chaque fenêtre d'ouverture son effectif, dans la fiche du stand — règles récurrentes et ouvertures datées — et laissez vide les fenêtres qui reprennent le minimum. Sur une vacation de relais repas — celle que le découpage génère, ou celle qu'une journée type marque d'un R —, la moitié de cet effectif est demandée, arrondie au supérieur. Le besoin en animateurs, la faisabilité, la fragilité et la règle des stands premium lisent tous ce même compte de sièges.`,
            },
            {
              term: $localize`:@@aide.setup.term.ouvertures:Les ouvertures : vérifier ce que le solveur lira`,
              text: $localize`:@@aide.setup.def.ouvertures:La grille « Ouvertures des stands » montre, stand par jour, les fenêtres résolues et le nombre de sièges qu'elles génèrent. Un stand jamais ouvert, une fenêtre hors des créneaux, un segment trop court pour valoir une vacation s'y voient avant tout calcul ; la vue « Journée » de la même page pose ces ouvertures sur l'axe du temps, et la fiche de chaque stand liste ses propres anomalies. Un stand dont personne ne doit s'occuper à une heure donnée se déclare fermé, jamais à effectif zéro.`,
            },
            {
              term: $localize`:@@aide.setup.term.creneaux:Les créneaux : des blocs, et ce que coûte une coupe`,
              text: $localize`:@@aide.setup.def.creneaux:Le solveur affecte une personne par créneau et par siège. Chaque coupe dans la journée est une relève : si la pause minimale entre vacations vaut 30 minutes, l'équipe sortante est encore en pause quand l'équipe entrante prend le poste, et la coupe double le besoin en personnes distinctes à cet instant. Préférez des blocs — matin, midi, après-midi, nocturne — à une grille fine, et gardez la relève de midi comme trou planifié : c'est la pause déjeuner. Découper un après-midi de six heures en deux ou trois sessions ne réduit jamais le besoin ; il l'augmente.`,
            },
            {
              term: $localize`:@@aide.setup.term.pause:La pause entre vacations : un réglage, pas la loi`,
              text: $localize`:@@aide.setup.def.pause:La seule pause que le Code du travail impose est celle de vingt minutes dès que le travail atteint six heures d'affilée. Les 30 minutes entre deux vacations sont un confort d'organisation : mettez-les à 0 sur la page Contraintes si vos créneaux forment des blocs qui se touchent, sinon la relève de midi impose une seconde équipe. Mesuré : ce seul réglage a rendu quarante-quatre sièges sur une grille de soixante-deux créneaux.`,
            },
            {
              term: $localize`:@@aide.setup.term.surPoste:La pause prise sur le poste : déclarer ce que vous organisez`,
              text: $localize`:@@aide.setup.def.surPoste:Par défaut, l'outil ne sait exprimer une pause que comme un trou entre deux vacations ; une relève de 13 h à 20 h est alors refusée, alors qu'elle est légale si les vingt minutes se prennent par relais sur le stand. Cochez « Pause légale prise sur le poste » dans les paramètres légaux si c'est ainsi que vous fonctionnez : la règle des six heures continues lit alors la séquence comme contenant sa pause, et les plafonds quotidiens la déduisent — 14 h à minuit vaut 9 h 40 de travail effectif et passe, 13 h à minuit reste refusé. L'écran Pauses pose ensuite la rotation : sur chaque stand, les pauses se suivent, une personne à la fois, chacune de telle heure à telle heure, et chaque animateur lit la sienne sur son planning.`,
            },
            {
              term: $localize`:@@aide.setup.term.repos:Le repos hebdomadaire : lu d'un tenant`,
              text: $localize`:@@aide.setup.def.repos:Trente-cinq heures consécutives par semaine, c'est vingt-quatre heures plus le repos quotidien. Un repos pris le dimanche ou le lundi enjambe le changement de semaine : il compte en entier pour la semaine où il tombe. Sur un événement ouvert sept jours sur sept, c'est ce qui rend possible un jour de repos par personne et par semaine, et c'est la règle qui tenait les derniers sièges vides avant d'être corrigée.`,
            },
            {
              term: $localize`:@@aide.setup.term.contraintes:Les autres règles : n'en désactivez aucune pour remplir`,
              text: $localize`:@@aide.setup.def.contraintes:Six jours par semaine, dix heures par jour, onze heures de repos quotidien, quarante-huit heures par semaine sont le droit, et le planning décrit ci-dessus les respecte toutes. Désactiver une règle légale sert à comprendre ce qui bloque, jamais à produire le planning qu'on diffuse. Les règles de confort — six jours consécutifs au plus, roulement limité sur les stands premium — pèsent en score moyen : elles n'empêchent pas un planning complet, elles le classent.`,
            },
            {
              term: $localize`:@@aide.setup.term.coupureRepas:La coupure repas : une règle à part`,
              text: $localize`:@@aide.setup.def.coupureRepas:La coupure repas ne se confond pas avec la pause légale de vingt minutes, et déclarer celle-ci prise sur le poste ne la lève pas. Toute personne qui travaille de part et d'autre d'une fenêtre repas — midi et soir, réglées avec les paramètres légaux sur la page Paramètres — doit disposer, entièrement dans cette fenêtre, d'un trou libre de la durée demandée. Commencer son service à l'ouverture de la fenêtre ou le terminer à sa fermeture ne doit rien : on a mangé avant, ou on mangera après. Une journée à cheval sur les deux fenêtres en doit deux. Ce n'est pas une obligation du Code du travail mais la règle que l'organisation s'est donnée ; elle est tenue en contrainte dure, et se désactive depuis la page Contraintes sous confirmation. À savoir avant de découper, ou de tracer une journée type : si un seul créneau couvre toute la fenêtre — une vacation de 10 h à 20 h d'un bloc, ou une nocturne de 18 h à 22 h — son titulaire ne peut pas s'absenter, et aucune résolution n'atteindra zéro écart dur tant que la grille n'est pas recoupée ; une vacation qui commence à l'ouverture de la fenêtre du soir, 19 h, ne doit rien.`,
            },
          ],
        },
        {
          kind: 'steps',
          items: [
            $localize`:@@aide.setup.step1:Besoin en animateurs : les cinq bornes disent le plancher. Si l'effectif est sous le plancher, aucun réglage n'y changera rien ; si le plancher dépasse l'effectif de peu, revoyez la grille et la pause entre vacations avant tout.`,
            $localize`:@@aide.setup.step2:Ouvertures des stands, puis faisabilité : le besoin lu fenêtre par fenêtre, et la capacité jour par jour, sans calcul.`,
            $localize`:@@aide.setup.step3:Une résolution courte, puis la page Problèmes : les écarts restants disent laquelle des règles tient les sièges vides.`,
            $localize`:@@aide.setup.step4:Une résolution longue, une fois la configuration stable — en ne changeant qu'une chose à la fois entre deux essais.`,
            $localize`:@@aide.setup.step5:Fragilité, puis Pauses : qui est irremplaçable, et quels relais organiser avant de publier.`,
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.setup.repere:Un repère pour arbitrer : retirer un siège au moment du pic vaut à peu près un demi-recrutement, et une grille dont la borne d'effectif est plus basse ne se résout pas forcément mieux — la borne dit ce qui est impossible, jamais ce qui est faisable. Seule la résolution tranche.`,
        },
      ],
      links: [
        { route: '/stands', label: $localize`:@@nav.link.stands:Stands` },
        { route: '/ouvertures', label: $localize`:@@nav.link.ouvertures:Ouvertures des stands` },
        { route: '/creneaux', label: $localize`:@@nav.link.creneaux:Créneaux` },
        { route: '/constraints', label: $localize`:@@nav.link.constraints:Contraintes` },
        {
          route: '/diagnostic',
          queryParams: { onglet: 'besoin' },
          label: $localize`:@@aide.link.staffing:Besoin en animateurs`,
        },
        { route: '/diagnostic', label: $localize`:@@aide.link.problemes:Problèmes` },
        {
          route: '/diagnostic',
          queryParams: { onglet: 'fragilite' },
          label: $localize`:@@aide.link.fragilite:Fragilité du planning`,
        },
        {
          route: '/journee',
          queryParams: { vue: 'pauses' },
          label: $localize`:@@aide.link.pauses:Pauses`,
        },
      ],
    },
    {
      id: 'lire-les-resultats',
      icon: 'insights',
      title: $localize`:@@aide.results.title:Lire et interpréter les résultats`,
      summary: $localize`:@@aide.results.summary:Le score, le bandeau de faisabilité, la page Problèmes : ce que chacun dit, et lequel croire.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.results.score:Une résolution rend un score à trois composantes, de la forme « 0hard / -6675medium / -120soft ». Elles ne se compensent jamais : le solveur préfère toujours un planning meilleur en dur, quel que soit le prix payé en medium et en souple.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.results.courbe:Pendant la résolution, la page Solveur trace ces trois composantes en direct, chacune dans son propre cadre — un score dur à -36 et un score souple à -400 000 ne se comparent pas sur le même axe. Le haut d'un cadre est le zéro : une courbe qui vient s'y coller veut dire « plus rien à corriger à ce niveau-là ». C'est ce qui permet de décider quand arrêter : le dur à zéro depuis plusieurs minutes et le souple qui ne bouge plus, la résolution plafonne et le bouton « Arrêter le solveur » ne vous coûtera rien — un solveur arrêté enregistre et analyse quand même ce qu'il a trouvé. Seule la résolution en cours est tracée : l'historique des résolutions passées n'est pas conservé. Si les graphes prennent de la place pour rien — vous lancez une résolution de quinze minutes et vous partez faire autre chose — « Réduire la courbe » les remplace par une ligne de scores, et l'écran s'en souvient à la visite suivante. Réduire n'interrompt rien : la rouvrir montre la résolution depuis son début.`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.results.term.hard:Score dur (hard)`,
              text: $localize`:@@aide.results.def.hard:La seule composante à regarder d'abord. À zéro, le planning est valide : toutes les places sont pourvues, aucune indisponibilité, aucune compétence manquante, aucun chevauchement, et tout le cadre légal est respecté. Négatif, le planning n'est pas utilisable tel quel, quel que soit le reste du score.`,
            },
            {
              term: $localize`:@@aide.results.term.medium:Score medium`,
              text: $localize`:@@aide.results.def.medium:Ce qui doit être respecté autant que possible et signalé sinon — référent par stand, équilibrage de charge, encadrement des mineurs. Un medium très négatif n'invalide pas le planning ; il indique un confort dégradé.`,
            },
            {
              term: $localize`:@@aide.results.term.soft:Score souple (soft)`,
              text: $localize`:@@aide.results.def.soft:Ce qui départage deux plannings valides : rotation des stands, mixité des niveaux, équité des créneaux pénibles. Ne l'optimisez jamais avant d'avoir un dur à zéro.`,
            },
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.results.mediumFloor:Attention aux points de medium qui ne mesurent rien : une contrainte qui pénalise chaque poste faute de donnée saisie — aucun souhait déclaré, aucun animateur de niveau référent — produit un plancher constant, parfois plus de 80 % du total. L'application le détecte : sur la page Contraintes, une règle qui pénalise au moins 95 % de ce qu'elle évalue porte le badge « mesure une donnée absente », nomme cette donnée et renvoie vers l'écran où la saisir ; le score hors plancher s'affiche à côté du score brut, sur la page Solveur, dans le Comparateur A/B et dans l'Autopsie. Rien n'est désactivé à votre place : la règle reste active, et c'est à vous de saisir la donnée — ou de baisser le poids de la règle. Comparez des scores entre deux résolutions du même jeu de données, pas la valeur absolue.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.results.previousPlan:Un calcul relancé repart du plan enregistré et ne peut pas finir en dessous de lui à données égales. Il peut quand même le dégrader quand le référentiel a changé entre-temps — des places ajoutées, une personne en moins — ou quand on a choisi de recommencer de zéro. La page Solveur affiche donc le score d'avant à côté de celui d'après, et le signale quand la nouvelle résolution est moins bonne — un score dur toujours à zéro ne veut pas dire que rien n'a été perdu, l'écart se lit sur le medium. Un bouton « Revenir au plan d'avant » remet alors en place le planning précédent. Ne tardez pas : ce filet est un instantané automatique, et seuls les cinq derniers sont conservés par édition.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.results.instantanes:Un seul planning est enregistré à la fois, et chaque résolution écrase le précédent : c'est la page Instantanés qui garde les autres. Une capture y est prise automatiquement avant chaque résolution — c'est ce filet que « Revenir au plan d'avant » utilise. Un plan que vous voulez garder au-delà des cinq dernières se met de côté explicitement, avec un libellé : celui-là n'est jamais purgé, et sert de terme de comparaison au Comparateur A/B. Restaurer pendant une résolution est refusé, par le serveur et non seulement par l'écran : la résolution écraserait en se terminant le plan qu'on vient de remettre en place. Attendez sa fin, ou arrêtez-la depuis la page Solveur.`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.results.term.faisabilite:Bandeau de faisabilité`,
              text: $localize`:@@aide.results.def.faisabilite:Estimation de capacité calculée sans résoudre, affichée en haut des pages Solveur et Contraintes. Elle nomme les créneaux et stands en cause. Elle est optimiste : elle ignore le découpage en vacations et le cadre légal des mineurs, donc elle peut annoncer « réalisable » un planning que le solveur n'amènera pas à zéro. L'inverse n'arrive pas : si elle signale un manque de capacité, c'est réel.`,
            },
            {
              term: $localize`:@@aide.results.term.problemes:Page Problèmes`,
              text: $localize`:@@aide.results.def.problemes:La liste complète et hiérarchisée des causes, là où le bandeau n'affiche que les plus sévères. Elle mêle deux sources : les causes d'infaisabilité structurelles, calculées sans résolution (capacité insuffisante sur une tranche horaire, typologie que personne ne maîtrise, stand réservé aux majeurs sans majeur disponible…) — visibles dès la saisie des données, avant tout solve — et les règles en défaut de la dernière analyse, avec leur nombre de correspondances. Chaque ligne est triée par gravité et pointe vers l'écran où corriger — la fiche du créneau, du stand ou de l'animateur en cause s'ouvre directement. C'est le premier écran à ouvrir quand une résolution ne donne pas zéro, et un bon réflexe avant même de lancer la première.`,
            },
            {
              term: $localize`:@@aide.results.term.contraintes:Page Contraintes`,
              text: $localize`:@@aide.results.def.contraintes:Le catalogue des règles, chacune avec son niveau, son état actif/inactif et le résultat de la dernière analyse : combien de fois elle n'est pas respectée, et le détail des écarts. C'est ce qui transforme « -14 hard » en « quatorze postes non pourvus sur tel stand ».`,
            },
            {
              term: $localize`:@@aide.results.term.stabilite:Stabilité du plan publié`,
              text: $localize`:@@aide.results.def.stabilite:Une fois un planning publié, chaque personne déplacée d'un siège qu'elle tenait dans le plan publié coûte un point medium au solveur : un calcul relancé après un changement tardif ne bouscule les gens déjà prévenus que si le gain vaut le dérangement. La règle se dose sur la page Contraintes comme les autres règles de qualité, et reste muette tant que rien n'a été publié. Après chaque calcul, le récapitulatif dit combien de personnes la publication préviendrait ; ce n'est pas un gel — pour figer, il y a les verrouillages et « Corriger après un changement ».`,
            },
            {
              term: $localize`:@@aide.results.term.pourquoiLui:« Pourquoi lui ? »`,
              text: $localize`:@@aide.results.def.pourquoiLui:Un clic sur un animateur affecté, dans le calendrier journalier ou le calendrier des affectations, explique cette affectation précise : les règles respectées ou non pour ce poste. À la demande, l'écran cherche aussi qui pourrait le remplacer, et ne propose que les remplacements qui tiennent — ceux qui n'introduisent aucun écart dur — chacun avec son effet sur le score et les règles qu'il débloque. La recherche est bornée : elle annonce combien de candidats elle a évalués et si elle s'est arrêtée au plafond, car une liste courte ne prouve pas qu'il n'existe rien d'autre. « Appliquer » pose le remplacement dans le planning.`,
            },
          ],
        },
      ],
      links: [
        { route: '/diagnostic', label: $localize`:@@aide.link.problemes:Problèmes` },
        { route: '/constraints', label: $localize`:@@nav.link.constraints:Contraintes` },
        {
          route: '/journee',
          label: $localize`:@@aide.link.dayCalendar:Calendrier journalier`,
        },
        { route: '/instantanes', label: $localize`:@@nav.link.snapshots:Instantanés` },
      ],
    },
    {
      id: 'tuner',
      icon: 'troubleshoot',
      title: $localize`:@@aide.tuning.title:Tuner la configuration`,
      summary: $localize`:@@aide.tuning.summary:Symptôme par symptôme : ce qu'il faut changer, et dans quel ordre.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.tuning.order:Réglez toujours dans cet ordre : d'abord les données, ensuite la durée de résolution, et seulement en dernier les contraintes. Désactiver une règle est un outil de diagnostic, pas une méthode de production.`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.tuning.term.postesVides:Des postes restent non pourvus`,
              text: $localize`:@@aide.tuning.def.postesVides:Vérifiez d'abord la page Besoin en animateurs : si l'effectif saisi est sous le minimum estimé, il manque des animateurs, pas du temps de calcul. Sinon, regardez quels créneaux sont concernés sur la page Problèmes — c'est souvent une poignée de créneaux de pointe, un stand ouvert par erreur, ou une typologie que presque personne ne maîtrise.`,
            },
            {
              term: $localize`:@@aide.tuning.term.plateau:Le score dur stagne à quelques unités de zéro`,
              text: $localize`:@@aide.tuning.def.plateau:C'est le cas typique où allonger la durée de résolution paie : doublez-la et relancez. Si deux résolutions longues s'arrêtent exactement au même score, ce n'est plus un problème de temps mais de structure — identifiez la contrainte en défaut sur la page Contraintes et regardez ses écarts un par un.`,
            },
            {
              term: $localize`:@@aide.tuning.term.legal:Les écarts portent tous sur des règles de temps de travail`,
              text: $localize`:@@aide.tuning.def.legal:Les plafonds hebdomadaires et le repos entre journées se heurtent au découpage. Revoyez les paramètres de découpage (vacations plus courtes, chevauchement, stratégie de couverture) plutôt que les plafonds légaux. Deux réglages disent souvent plus que la loi : la pause minimale entre vacations à 30 minutes interdit d'enchaîner deux blocs qui se touchent, et sans la « pause prise sur le poste » aucune séquence ne peut dépasser six heures — voir la section « Paramétrer pour un planning complet ».`,
            },
            {
              term: $localize`:@@aide.tuning.term.mineurs:Les écarts concernent les mineurs`,
              text: $localize`:@@aide.tuning.def.mineurs:Le cadre des mineurs est plus strict et dépend de l'âge à la date du créneau : pas de travail de nuit, amplitude quotidienne réduite, pauses plus longues, deux jours de repos consécutifs, accompagnement obligatoire par un majeur. Vérifiez les dates de naissance saisies et la proportion de majeurs disponibles sur les créneaux tardifs.`,
            },
            {
              term: $localize`:@@aide.tuning.term.adhoc:Le planning est devenu infaisable après une modification`,
              text: $localize`:@@aide.tuning.def.adhoc:Cherchez du côté des ajustements manuels et des verrouillages ajoutés depuis la dernière résolution réussie : ils ont le poids d'une contrainte dure. Supprimez temporairement le dernier ajout et relancez pour confirmer. Si deux ajustements se contredisent franchement, la page Problèmes les nomme sans qu'aucune résolution soit nécessaire.`,
            },
            {
              term: $localize`:@@aide.tuning.term.gelerBloc:Une part du planning est acquise et ralentit la recherche`,
              text: $localize`:@@aide.tuning.def.gelerBloc:Certaines journées sont volumineuses et sans difficulté — le montage et le démontage, typiquement : beaucoup de places, une seule typologie, aucune contrainte de compétence. Le solveur y consacre pourtant une part de ses mouvements proportionnelle à leur nombre de places. Une fois qu'une résolution les a correctement pourvues, posez un verrouillage de type Stand sur ces stands : les places restent intégralement dans le planning, nominatives et visibles partout, leurs heures continuent de compter dans les plafonds légaux, mais le solveur ne cherche plus à les déplacer et concentre son budget sur les journées difficiles. À réserver au diagnostic et aux gros scénarios : le verrou fige ces personnes-là sur ces places, donc il n'est sain que si l'affectation gelée est déjà bonne — d'où l'ordre « résoudre, vérifier, puis verrouiller ». Il se retire à tout moment depuis la page Verrouillages.`,
            },
            {
              term: $localize`:@@aide.tuning.term.incremental:Un changement tombe après que le planning est validé`,
              text: $localize`:@@aide.tuning.def.incremental:Ne relancez pas une résolution complète : le bouton « Replanifier (incrémental) » de la page Solveur repart du planning enregistré, fige tout ce qui reste valable et ne recalcule que les postes qu'un changement a invalidés (animateur supprimé, indisponibilité saisie depuis la résolution) plus ceux restés vides. Vous pouvez rouvrir davantage en désignant un animateur, une journée ou un stand ; tout le reste est garanti inchangé. Le compte rendu liste les équipes qui ont bougé, donc les personnes à prévenir. Saisissez d'abord l'indisponibilité, puis replanifiez : c'est elle qui ouvre les postes concernés.`,
            },
            {
              term: $localize`:@@aide.tuning.term.mediumEleve:Le score medium ou souple paraît énorme`,
              text: $localize`:@@aide.tuning.def.mediumEleve:Vérifiez d'abord qu'il ne s'agit pas d'un plancher lié à une donnée absente (souhaits, niveau référent). Enrichir les compétences et les souhaits des animateurs améliore ces composantes bien plus que n'importe quel réglage du solveur.`,
            },
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.tuning.method:Ne changez qu'une chose à la fois entre deux résolutions, et relancez toujours sur la même durée pour que les scores restent comparables. Le nombre de postes et la volumétrie affichés en haut de la page Solveur permettent de vérifier qu'une modification de données a bien eu l'effet attendu, avant même de relancer un calcul.`,
        },
      ],
      links: [
        { route: '/diagnostic', label: $localize`:@@aide.link.problemes:Problèmes` },
        {
          route: '/diagnostic',
          queryParams: { onglet: 'besoin' },
          label: $localize`:@@aide.link.staffing:Besoin en animateurs`,
        },
        { route: '/parametres', label: $localize`:@@nav.link.parametres:Paramètres` },
        {
          route: '/ad-hoc-constraints',
          label: $localize`:@@nav.link.adHocConstraints:Ajustements manuels`,
        },
        { route: '/verrouillages', label: $localize`:@@nav.link.verrouillages:Verrouillages` },
      ],
    },
    {
      id: 'ajustements-manuels',
      icon: 'rule',
      title: $localize`:@@aide.adHoc.title:Ajustements manuels`,
      summary: $localize`:@@aide.adHoc.summary:Forcer, interdire, rapprocher : ce que chaque type recouvre exactement, et les cas limites qui surprennent.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.adHoc.intro:Un ajustement manuel est une exception que vous saisissez sur vos propres données, à côté des règles du catalogue. Trois des quatre types sont appliqués au même niveau que le cadre légal : le solveur ne les contournera jamais, quitte à rendre un planning en défaut. Chacun se saisit avec une raison, qui reste lisible partout où l'ajustement est cité.`,
        },
        {
          kind: 'callout',
          title: $localize`:@@aide.adHoc.callout.title:Ajustement manuel ou verrouillage ?`,
          text: $localize`:@@aide.adHoc.callout.text:Les deux retirent de la liberté au solveur, mais pas au même moment ni sur la même matière. Un ajustement manuel est une règle sur mesure, posée avant le calcul, qui dit où placer — ou ne pas placer — quelqu'un : « elle ne peut pas venir dimanche », « ces deux-là ne travaillent pas ensemble », « il tient l'accueil samedi matin ». Le solveur en tient compte à chaque résolution, y compris en repartant de zéro, et un ajustement qu'il ne peut pas honorer laisse un planning en défaut qui le nomme. Un verrouillage est un geste sur un plan déjà calculé : il fige ce qu'une résolution a produit sur une partie du planning — un animateur, un stand, une journée, un créneau — pour que la suivante n'y touche plus et optimise le reste. Il ne dit rien de ce qui devrait s'y trouver, il conserve ce qui s'y trouve ; il ne vaut que pour le plan enregistré, et ne gèle jamais une place vide. En pratique : pour imposer ou interdire une affectation, un ajustement ; pour protéger une partie validée d'un plan que vous relancez, un verrouillage.`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.adHoc.term.indisponibilite:Indisponibilité forcée`,
              text: $localize`:@@aide.adHoc.def.indisponibilite:Interdit à la personne d'occuper un poste dans le périmètre choisi. À distinguer des jours d'indisponibilité saisis sur la fiche de l'animateur, qui portent sur une journée entière : ici, vous pouvez viser un créneau précis, un stand précis, ou les deux.`,
            },
            {
              term: $localize`:@@aide.adHoc.term.affectation:Affectation forcée`,
              text: $localize`:@@aide.adHoc.def.affectation:Exige qu'au moins un poste du périmètre soit tenu par la personne. Attention : si vous nommez plusieurs animateurs, l'ajustement est satisfait dès que l'un d'eux tient le poste — c'est un « l'un de ces animateurs », jamais un « tous ». Pour imposer deux personnes, saisissez deux ajustements.`,
            },
            {
              term: $localize`:@@aide.adHoc.term.incompatibilite:Incompatibilité`,
              text: $localize`:@@aide.adHoc.def.incompatibilite:Interdit à deux personnes de travailler sur le même créneau. Elle porte sur le créneau, pas sur le stand : deux stands différents à la même heure sont tout autant interdits. Restreindre l'ajustement à un stand ne l'assouplit donc que dans ce stand-là. Seuls les deux premiers animateurs de la liste sont pris en compte : pour trois personnes qui ne doivent pas se croiser, saisissez les trois paires.`,
            },
            {
              term: $localize`:@@aide.adHoc.term.affinite:Affinité (paire à privilégier)`,
              text: $localize`:@@aide.adHoc.def.affinite:Le seul type qui n'est pas une règle dure : une simple préférence, récompensée chaque fois que les deux personnes tiennent un poste sur le même stand au même créneau. Elle ne force rien — la rendre dure en ferait une affectation imposée déguisée, qui entrerait en conflit avec l'équilibrage des charges et les disponibilités de chacun.`,
            },
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.adHoc.perimetre:Le créneau et le stand sont facultatifs, et les laisser vides veut dire « partout » : une indisponibilité forcée sans périmètre écarte la personne de tout l'événement. Un ajustement conserve l'identifiant du créneau visé — si ce créneau est supprimé, régénéré par un découpage ou retiré par l'application d'un calendrier de journées types, la colonne Périmètre affiche « créneau supprimé » et l'ajustement ne s'applique plus à rien. Après un découpage ou une application, revérifiez ceux qui visaient un créneau.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.adHoc.contradictions:Deux ajustements qui ne peuvent pas tenir ensemble sont refusés à l'enregistrement, avec un message qui les nomme tous les deux — plutôt qu'un planning déclaré infaisable plusieurs minutes plus tard, sans que rien n'en désigne la cause. Quatre situations sont refusées :`,
        },
        {
          kind: 'list',
          items: [
            $localize`:@@aide.adHoc.refus1:la même paire déclarée à la fois incompatible et en affinité ;`,
            $localize`:@@aide.adHoc.refus2:une affectation forcée dont tout le périmètre est couvert par une indisponibilité forcée visant chacun des animateurs qu'elle nomme ;`,
            $localize`:@@aide.adHoc.refus3:deux affectations forcées qui fixent la même personne sur des périmètres se chevauchant dans le temps — personne ne tient deux postes à la même heure ;`,
            $localize`:@@aide.adHoc.refus4:deux affectations forcées qui placent sur un même créneau deux personnes déclarées incompatibles.`,
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.adHoc.limites:Le contrôle ne refuse que ce qui est certainement impossible : un ajustement refusé à tort vous coûterait une saisie légitime, sans contournement. Passent donc délibérément une affectation forcée nommant deux animateurs quand un seul d'entre eux est indisponible (l'autre peut la satisfaire), une indisponibilité plus étroite que le périmètre forcé (le poste peut se poser ailleurs dedans), et deux affectations forcées sur le même créneau dont une seule précise un stand (un seul poste les satisfait toutes les deux). Ces combinaisons-là ne sont pas refusées, mais elles peuvent quand même mener à un planning en défaut : c'est la résolution qui tranchera.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.adHoc.chevauchement:Le chevauchement se calcule sur les horaires du créneau tels qu'ils sont saisis. Une fermeture de stand peut réduire la plage réellement couverte par un poste : deux affectations forcées sur des créneaux qui se recouvrent, mais sur deux stands fermés à des heures complémentaires, seront donc refusées alors que le solveur aurait pu les poser. Le périmètre d'un ajustement est ce que vous avez saisi ; dans ce cas, visez des créneaux qui ne se recouvrent pas.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.adHoc.modifier:Modifier un ajustement, c'est le réenregistrer sous son propre identifiant : la nouvelle version remplace la précédente, et ce cas n'est jamais refusé pour contradiction avec elle-même. Un ajustement enregistré avant que ce contrôle existe, ou importé, peut en revanche subsister : la page Problèmes le signale alors comme cause bloquante, et l'écran des ajustements marque d'un avertissement les lignes concernées. Rien d'autre ne les désignerait.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.adHoc.import:À l'import d'un scénario, les ajustements manuels du fichier remplacent en bloc ceux de l'édition : un fichier qui n'en porte aucun n'en installe aucun. Ce qui les préserve d'un aller-retour, c'est l'export, qui écrit la section dès que l'édition en porte. Un fichier dont les ajustements se contredisent est refusé en entier, sans rien écrire.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.adHoc.apres:Quand une résolution se termine malgré tout en défaut, la page Problèmes nomme les ajustements que le solveur n'a pas pu honorer, un par un, avec le nombre d'écarts que chacun porte — c'est ce qui distingue « le solveur n'y arrive pas » de « ces trois exceptions-là sont à arbitrer ».`,
        },
      ],
      links: [
        {
          route: '/ad-hoc-constraints',
          label: $localize`:@@nav.link.adHocConstraints:Ajustements manuels`,
        },
        { route: '/diagnostic', label: $localize`:@@aide.link.problemes:Problèmes` },
        { route: '/verrouillages', label: $localize`:@@nav.link.verrouillages:Verrouillages` },
      ],
    },
  ];
}
