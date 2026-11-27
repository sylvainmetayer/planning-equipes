// Computing a planning: launching a solve, configuring it, parametrising
// for a complete planning, reading a score, tuning, and the manual
// adjustments that sit before the solve.
//
// One theme of the organisers' guide; `aide-content.ts` assembles the
// themes in reading order. Built lazily, never at module scope: `$localize`
// only resolves once `main.ts` has loaded the translation catalog.

import { HelpSection } from '../help-section';
import {
  lienCalculer,
  lienAjustements,
  lienFragilite,
  lienHoraires,
  lienPauses,
  lienStaffing,
  lienVerrouillages,
  lienVersions,
} from './liens';

export function buildSolverSections(): HelpSection[] {
  return [
    {
      id: 'calculer',
      icon: 'play_arrow',
      title: $localize`:@@aide.calculer.title:Calculer et corriger`,
      summary: $localize`:@@aide.calculer.summary:Les deux façons de lancer le solveur, laquelle choisir, et quand recommencer de zéro.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.calculer.intro:Le calcul se lance depuis l'onglet Calculer de la page Planning. Deux boutons : le meilleur planning possible, ou une correction qui bouge le moins possible. Les deux lisent les mêmes données et respectent les mêmes règles ; seul change ce que le calcul a le droit de bouger.`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.calculer.term.calculer:Calculer le planning`,
              text: $localize`:@@aide.calculer.def.calculer:Le bouton de tous les jours : le calcul complet. S'il existe un plan enregistré, le calcul en repart et cherche à l'améliorer ; sinon il part de zéro. Tout peut bouger, hormis les verrouillages et le passé figé, mais à données égales le résultat ne peut pas finir en dessous du plan de départ. Avec un plan enregistré, il demande confirmation en rappelant d'où il partira ; la ligne sous les boutons le dit aussi.`,
            },
            {
              term: $localize`:@@aide.calculer.term.corriger:Corriger après un changement`,
              text: $localize`:@@aide.calculer.def.corriger:Un désistement, une indisponibilité saisie tard, un stand ajouté : le planning est déjà diffusé et vous voulez bouger le moins possible. Tout ce qui reste valable est figé, seuls les postes que le changement a invalidés sont recalculés, en quelques dizaines de secondes. La fenêtre montre d'abord ce qui a changé depuis le plan, puis propose d'en rouvrir davantage : des animateurs, des journées, des stands. Le compte rendu nomme qui a bougé. Ne réoptimise rien.`,
            },
            {
              term: $localize`:@@aide.calculer.term.recommencer:Recommencer de zéro`,
              text: $localize`:@@aide.calculer.def.recommencer:Une case de la confirmation de « Calculer le planning », décochée par défaut : le calcul ignore le plan enregistré et repart de rien. C'est le seul geste qui peut perdre la qualité déjà atteinte, d'où une seconde confirmation, qui dit quelles relectures de journées survivront : réservez-le à un essai que vous abandonnez ou à des données refaites en profondeur. Sans plan enregistré, la case n'est pas proposée.`,
            },
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.calculer.entrees:L'onglet s'ouvre sur ce qui empêche un planning sans écart (faisabilité et alertes), puis, dès qu'un plan existe, sur l'avancement de la relecture (journées relues ou figées). Vient « Ce calcul tiendra compte de » : les verrouillages, les ajustements manuels, les consignes et leurs dates, les déclarations de disponibilité encore en attente (le calcul ne les verra pas), les règles désactivées et les modifications faites depuis la dernière résolution. Chaque compteur mène à l'écran qui les liste ; un compteur à zéro reste affiché, en retrait. Viennent ensuite les deux boutons, chacun avec sa phrase. Après un calcul, son résultat se lit en phrases. Juste dessous se trouvent « Voir le planning », « Relire » (la première journée pas encore relue) et « Publier », puis, en bas, « Réglages avancés » : le lieu du calcul et la machine distante, la courbe de score (la machine reste affichée tant qu'elle est allumée). Les versions du plan sont dans l'onglet voisin, « Versions du plan » ; le budget de calcul se consulte dans Paramètres, onglet Calcul.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.calculer.verrous:Les verrouillages s'appliquent aux deux gestes. Un animateur sur un créneau, un stand sur une journée ou une journée verrouillé n'est touché ni par un calcul complet ni par une correction.`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.calculer.term.passe:Le passé est figé`,
              text: $localize`:@@aide.calculer.def.passe.instance:Pendant l'événement, les trois calculs reprennent du planning enregistré les postes des créneaux déjà commencés (journée passée, ou créneau d'aujourd'hui dont l'heure est atteinte) et les figent tels qu'ils ont été travaillés, même si la personne a depuis déclaré la journée indisponible, même si le poste est resté vide. Ces postes comptent dans les repos, les cumuls et les jours d'affilée, mais aucune règle ne les reproche : le zéro dur se lit sur ce qui reste à jouer. Le compte rendu les annonce (« postes déjà commencés »). En développement, en démonstration ou en recette, la date que lit cette règle se pose depuis Paramètres, onglet Instance ; en exploitation, la variable PASSE_FIGE=false la coupe, pour rejouer une édition ancienne.`,
            },
            {
              term: $localize`:@@aide.calculer.term.passeGestes:Le passé ne se modifie plus`,
              text: $localize`:@@aide.calculer.def.passeGestes:Les gestes à la main suivent la même règle. Déplacer une affectation sur une vue journalière, accepter un échange, appliquer ou demander une réparation sur un poste dont le créneau est déjà commencé est refusé depuis l'écran, l'API ou un assistant, avec le message « Ce créneau est déjà commencé : le passé ne se modifie plus ». Le mode jour J continue d'agir sur les créneaux restants de la journée ; le siège d'un créneau en cours reste tel qu'il est, l'absence y est seulement enregistrée. Quand PASSE_FIGE=false, rien n'est refusé.`,
            },
            {
              term: $localize`:@@aide.calculer.term.rienAPlanifier:Rien à planifier`,
              text: $localize`:@@aide.calculer.def.rienAPlanifier:Un calcul lancé alors que tous les créneaux sont déjà commencés (l'édition est terminée, ou la date simulée est après l'événement) est refusé au lieu de produire un planning vide à zéro dur ; le job le dit dans son erreur. Quand des postes passés sont restés vides (aucun titulaire enregistré, par exemple au premier calcul lancé pendant l'événement), le calcul a lieu et le compte rendu l'annonce : « N sièges passés sont restés vides ».`,
            },
            {
              term: $localize`:@@aide.calculer.term.plancherPasse:Un plancher pendant l'événement`,
              text: $localize`:@@aide.calculer.def.plancherPasse:Les équilibres de charge et de créneaux pénibles mesurent l'édition entière, postes passés compris, et le calcul ne peut plus bouger que l'avenir : un écart déjà creusé avant aujourd'hui ne se rattrape pas et laisse un score medium qu'aucun calcul ne ramènera à zéro. C'est attendu, et c'est pourquoi le score se compare d'un calcul à l'autre plutôt qu'au zéro.`,
            },
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.calculer.bloquants:Quand le bandeau de faisabilité porte un problème qu'aucun calcul ne peut résoudre (deux ajustements manuels qui se contredisent, un plafond de jours d'affilée intenable), les deux boutons demandent confirmation avant de partir et nomment la cause : le calcul finirait en score dur négatif quel que soit le temps qu'il tourne. Vous pouvez lancer quand même, et le reste du planning s'améliore. Un manque d'animateurs ne demande aucune confirmation : le solveur remplit encore ce qu'il peut.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.calculer.attente:Quand le solveur est occupé, chacun des deux boutons planifie le calcul au lieu de le refuser : il démarrera de lui-même dès que la tâche en cours sera terminée.`,
        },
      ],
      links: [lienCalculer(), lienVerrouillages()],
    },
    {
      id: 'configuration-solveur',
      icon: 'tune',
      title: $localize`:@@aide.config.title:Configuration du solveur`,
      summary: $localize`:@@aide.config.summary:Les réglages qui changent le comportement du solveur, et lequel toucher en premier.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.config.intro:Le solveur n'a pas de « niveau de qualité » à régler : il cherche, dans le temps qu'on lui donne, le meilleur planning au sens des contraintes actives. Les réglages agissent donc soit sur le temps accordé, soit sur les règles à respecter.`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.config.term.duree:Budget de calcul`,
              text: $localize`:@@aide.config.def.budget:Réglé par l'exploitant de l'instance et le même pour toutes les éditions : la page Paramètres, onglet Calcul, l'affiche sans le modifier, et le serveur l'applique à tout calcul, depuis l'écran comme depuis l'assistant. Deux réglages. La durée maximale (15 min par défaut) est celui qui compte le plus : commencez court (1 à 3 min) pour révéler les blocages structurels, puis passez à 15 ou 30 min, voire davantage, pour la résolution finale. L'arrêt sans amélioration (5 min par défaut, 0 pour jamais) écourte un calcul qui ne progresse plus ; il ne compte qu'une fois le score dur à zéro, de sorte qu'un calcul n'abandonne jamais tant que des places restent à pourvoir. Pour les changer, l'exploitant modifie SOLVER_SECONDS_LIMIT et SOLVER_UNIMPROVED_SECONDS_LIMIT et redémarre ; un script ou l'assistant peut demander une durée plus courte à un lancement, jusqu'à un plafond que fixe l'exploitant.`,
            },
            {
              term: $localize`:@@aide.config.term.mailFin:Prévenir à la fin d'une résolution`,
              text: $localize`:@@aide.config.def.mailFin:Un interrupteur de Paramètres, onglet Calcul, fait écrire à l'administrateur dès qu'une résolution de cette édition se termine : l'édition, le score, et si le planning est faisable. De quoi lancer un calcul de trente minutes et partir. Il reste sans effet tant qu'aucune adresse administrateur n'est configurée sur le serveur.`,
            },
            {
              term: $localize`:@@aide.config.term.legaux:Paramètres légaux`,
              text: $localize`:@@aide.config.def.legaux:Dans Paramètres, onglet Légal, le seul endroit où ils s'écrivent (la ligne de chaque règle légale, dans Règles du planning, y renvoie) : les plafonds hebdomadaires, la durée de la pause légale, la fenêtre et la durée du repas. Les plafonds (48 h pour les majeurs, 35 h pour les mineurs) sont d'ordre public : une valeur supérieure est refusée, une valeur inférieure reste libre. La pause dure au moins 20 minutes (art. L3121-16), portée à 30 pour un mineur (art. L3162-3), 30 par défaut. Le repos quotidien minimal (11 h, 9 h par accord collectif) et la durée maximale d'une vacation, que seul le contrôle de la grille lit, se règlent dans ce même onglet. Rien n'impose d'écart entre deux vacations.`,
            },
            {
              term: $localize`:@@aide.config.term.contraintes:Activation des contraintes`,
              text: $localize`:@@aide.config.def.contraintes:Chaque règle d'organisation et chaque règle de base se désactive sur sa ligne de la page Règles du planning (bloc « Politique de l'organisation » ou « Avancé »), puis « Enregistrer » ; les obligations légales y sont en lecture seule. À utiliser pour diagnostiquer (« sans cette règle, le planning devient-il faisable ? ») bien plus que pour produire : désactiver une contrainte dure produit un planning que la réalité refusera. Désactiver une règle souple qui ne mesure rien, faute de donnée saisie, rend en revanche le score lisible sans rien changer au résultat.`,
            },
            {
              term: $localize`:@@aide.config.term.importance:Importance d'une règle`,
              text: $localize`:@@aide.config.def.importance:Dans le bloc « Politique de l'organisation », chaque règle a un niveau : obligatoire (un plan qui l'enfreint n'est pas livrable, aucun poids n'est alors offert) ou une préférence forte, normale (son défaut) ou légère, qui écrivent un poids de 25, 5 ou 1. Les préférences du bloc « Avancé » ont trois positions : faible, normale et forte. À niveau égal, un écart d'une règle forte pèse cinq fois celui d'une règle normale, qui en pèse cinq d'une faible. Deux règles sont rangées ensemble sous un même titre (« Dispersion sur la journée »), chacune gardant sa ligne, son niveau et son interrupteur. Un autre poids, de 1 à 500, se saisit dans le panneau de la règle et s'affiche « personnalisée ». Une règle dure ne se dose pas : elle s'active ou non ; un poids qu'elle aurait gardé d'avant s'affiche « poids personnalisé » sous son nom, et son panneau la fait « Revenir à 1 ». Rien ne s'écrit avant « Enregistrer », et « Relancer le calcul », en pied de tableau, fait prendre en compte ce qui vient d'être enregistré.`,
            },
            {
              term: $localize`:@@aide.config.term.historique:Historique des réglages`,
              text: $localize`:@@aide.config.def.historique:Chaque changement d'activation, de poids ou de niveau d'une règle entre dans le journal des actions de l'édition : quelle règle, quand, par qui, jamais la valeur. Le panneau d'une règle, qui s'ouvre d'un clic sur son nom dans Règles du planning, y renvoie (Paramètres, onglet Historique, filtre « Règles »). Le dosage sous lequel chaque résolution a tourné reste lisible dans les mesures de l'édition et dans la comparaison de Versions du plan.`,
            },
            {
              term: $localize`:@@aide.config.term.adhoc:Ajustements manuels`,
              text: $localize`:@@aide.config.def.adhoc:Exceptions saisies au cas par cas, avec une raison tracée : incompatibilité, affinité, arrivée groupée. L'incompatibilité est traitée au même niveau que les contraintes dures, donc jamais contournée, et retire des possibilités au solveur : un ajustement mal posé bloque un planning aussi sûrement qu'un manque d'effectif.`,
            },
            {
              term: $localize`:@@aide.config.term.verrouillages:Verrouillages`,
              text: $localize`:@@aide.config.def.verrouillages:Geler un animateur sur un créneau, un stand sur une journée ou une journée entière pour que la prochaine résolution n'y touche plus et optimise le reste. Une place non pourvue n'est jamais gelée, et les places gelées continuent d'être évaluées : un verrou laisse donc une alerte visible au lieu de masquer un problème. Quand ce que vous figez casse déjà une règle dure, l'enregistrement le dit, pour que le score dur négatif du prochain calcul ne soit pas une surprise. Ils se posent sur l'onglet Verrouillages de la page Règles du planning, sur un animateur et un créneau par défaut ; avant d'enregistrer, l'aperçu compte les affectations que le verrou figera et mène à la Journée qui les montre. Ce n'est pas un ajustement manuel : voir l'encart « Ajustement manuel ou verrouillage ? ».`,
            },
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.config.lock:Une seule résolution tourne à la fois pour tout le serveur, éventuellement lancée depuis un autre poste ; le moniteur de la barre d'outils indique laquelle et depuis combien de temps. Seule l'édition d'où le calcul a été lancé est verrouillée en saisie : sur une autre, vous continuez à travailler.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.config.saisieEnCours:Un calcul ne lit que ce qui est enregistré. « Calculer » demande donc confirmation quand une fiche animateur, une fiche stand ou une consigne est en cours de saisie sans avoir été enregistrée, dans cet onglet ou laissée en brouillon. À l'inverse, si un calcul démarre pendant que vous saisissez (depuis un autre onglet, un autre poste, l'assistant ou une routine), le formulaire le dit, avec l'heure de fin au plus tard : votre saisie reste à l'écran et s'enregistre à la fin du calcul. Les décisions de la page Disponibilités attendent aussi la fin du calcul. Un assistant peut, lui, continuer à écrire certaines données pendant un calcul ; sa réponse l'avertit alors que le calcul en cours n'en tiendra pas compte.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.config.file:Pendant qu'un calcul tourne, les boutons deviennent « Planifier » : la tâche est mise en file et démarre dès que la précédente se termine. C'est le geste pour enchaîner deux éditions sans attendre devant l'écran. Une tâche en attente ne verrouille rien et lit son édition telle qu'elle sera au démarrage, donc une correction apportée entre-temps sera prise en compte. La file s'affiche sous les boutons, une ligne s'en retire tant qu'elle n'a pas démarré, et un redémarrage du serveur la vide.`,
        },
      ],
      links: [
        lienCalculer(),
        { route: '/regles', label: $localize`:@@nav.link.regles:Règles du planning` },
        {
          route: '/parametres',
          queryParams: { onglet: 'calcul' },
          label: $localize`:@@nav.link.parametres:Paramètres`,
        },
        lienVerrouillages(),
      ],
    },
    {
      id: 'parametrer-pour-un-planning-complet',
      icon: 'checklist',
      title: $localize`:@@aide.setup.title:Paramétrer pour un planning complet`,
      summary: $localize`:@@aide.setup.summary:Ce qui fait tenir un planning sans le moindre écart avec l'effectif dont vous disposez, et dans quel ordre le vérifier.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.setup.intro:Un planning qui ne se remplit pas est rarement une affaire de temps de calcul. Trois causes reviennent, dans cet ordre : un besoin mal décrit, une grille de créneaux qui oblige à deux équipes là où une suffit, et des réglages qui disent plus que la loi.`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.setup.term.stands:Un effectif par fenêtre, pas par stand`,
              text: $localize`:@@aide.setup.def.stands:L'effectif minimum du stand s'applique à toute la journée. Si un stand tient à deux le matin et à quatre l'après-midi, saisir « 2 » sous-dote l'après-midi et saisir « 4 » sur-dote le matin. Donnez à chaque fenêtre d'ouverture son effectif, et laissez vides celles qui reprennent le minimum. Sur une vacation de relais repas, la moitié de cet effectif est demandée, arrondie au supérieur.`,
            },
            {
              term: $localize`:@@aide.setup.term.ouvertures:Vérifier ce que le solveur lira`,
              text: $localize`:@@aide.setup.def.ouvertures:La grille « Horaires des stands » montre, stand par créneau, les effectifs résolus et les sièges qu'ils génèrent. Un stand jamais ouvert, une fenêtre hors des créneaux, un segment trop court pour valoir une vacation s'y voient avant tout calcul. Un stand dont personne ne doit s'occuper à une heure donnée se déclare fermé, jamais à effectif zéro.`,
            },
            {
              term: $localize`:@@aide.setup.term.creneaux:Des blocs, et ce que coûte une coupe`,
              text: $localize`:@@aide.setup.def.creneaux:Le solveur affecte une personne par créneau et par siège. Des vacations qui se touchent s'enchaînent librement : rien n'impose d'écart entre deux d'entre elles, et un trou plus court que la pause légale compte comme du travail. Préférez tout de même des blocs (matin, midi, après-midi, nocturne) et gardez la relève de midi comme trou planifié : c'est la pause déjeuner. Découper un après-midi en deux sessions ne réduit jamais le besoin en personnes.`,
            },
            {
              term: $localize`:@@aide.setup.term.pause:La pause légale : un trou, ou un relais`,
              text: $localize`:@@aide.setup.def.pause:Le Code du travail impose une pause dès six heures d'affilée (quatre heures et demie pour un mineur). L'outil la reconnaît de deux manières seulement. Soit un trou d'au moins la durée réglée dans la grille, qui coupe la séquence : plus rien n'est dû. Soit un relais : un collègue du même stand tient une place pendant toute la pause. Une pause que personne ne peut prendre est un écart dur.`,
            },
            {
              term: $localize`:@@aide.setup.term.surPoste:Une relève enchaînée à l'après-midi reste possible`,
              text: $localize`:@@aide.setup.def.surPoste:Une relève de 13 h à 20 h est sept heures d'affilée : elle doit une pause vers la sixième heure, et il suffit qu'un collègue tienne le stand à ce moment-là pour qu'elle soit légale. Rien à déclarer : ouvrez la place, le solveur s'en sert. Le Diagnostic nomme ensuite, dans ses Problèmes, les pauses où le relais manque. La pause est du repos, donc déduite des plafonds et de tous les compteurs d'heures.`,
            },
            {
              term: $localize`:@@aide.setup.term.repos:Le repos hebdomadaire, lu d'un tenant`,
              text: $localize`:@@aide.setup.def.repos:Trente-cinq heures consécutives par semaine, c'est vingt-quatre heures plus le repos quotidien. Un repos pris le dimanche ou le lundi enjambe le changement de semaine : il compte en entier pour la semaine où il tombe. Sur un événement ouvert sept jours sur sept, c'est ce qui rend possible un jour de repos par personne et par semaine.`,
            },
            {
              term: $localize`:@@aide.setup.term.contraintes:N'en désactivez aucune pour remplir`,
              text: $localize`:@@aide.setup.def.contraintes:Six jours par semaine, dix heures par jour, onze heures de repos quotidien, quarante-huit heures par semaine sont le droit. Désactiver une règle légale sert à comprendre ce qui bloque, jamais à produire le planning qu'on diffuse. Les règles de confort, elles, n'empêchent pas un planning complet : elles le classent.`,
            },
            {
              term: $localize`:@@aide.setup.term.coupureRepas:La coupure repas, une règle à part`,
              text: $localize`:@@aide.setup.def.coupureRepas:Elle ne se confond pas avec la pause légale, et un relais ne la lève pas. Toute personne qui travaille de part et d'autre d'une fenêtre repas doit disposer, entièrement dans cette fenêtre, d'un trou libre de la durée demandée. Commencer son service à l'ouverture de la fenêtre ou le terminer à sa fermeture ne doit rien. À savoir avant de découper : si un seul créneau couvre toute la fenêtre, son titulaire ne peut pas s'absenter, et aucune résolution n'atteindra zéro écart dur tant que la grille n'est pas recoupée.`,
            },
          ],
        },
        {
          kind: 'steps',
          items: [
            $localize`:@@aide.setup.step1:Besoin en animateurs : les bornes disent le plancher. Sous le plancher, aucun réglage n'y changera rien.`,
            $localize`:@@aide.setup.step2:Horaires des stands, puis faisabilité : le besoin fenêtre par fenêtre, la capacité jour par jour, sans calcul.`,
            $localize`:@@aide.setup.step3:Une résolution courte, puis la page Problèmes : les écarts restants disent quelle règle tient les sièges vides.`,
            $localize`:@@aide.setup.step4:Une résolution longue, une fois la configuration stable, en ne changeant qu'une chose à la fois.`,
            $localize`:@@aide.setup.step5:Fragilité, puis Problèmes : qui est irremplaçable, et quels relais organiser avant de publier.`,
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.setup.repere:Un repère pour arbitrer : une grille dont la borne d'effectif est plus basse ne se résout pas forcément mieux. La borne dit ce qui est impossible, jamais ce qui est faisable : seule la résolution tranche.`,
        },
      ],
      links: [
        { route: '/stands', label: $localize`:@@nav.link.stands:Stands` },
        lienHoraires(),
        { route: '/creneaux', label: $localize`:@@nav.link.creneaux:Créneaux` },
        { route: '/regles', label: $localize`:@@nav.link.regles:Règles du planning` },
        lienStaffing(),
        { route: '/diagnostic', label: $localize`:@@aide.link.problemes:Problèmes` },
        lienFragilite(),
        lienPauses(),
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
          text: $localize`:@@aide.results.score:Une résolution rend un score à trois composantes, de la forme « 0hard / -6675medium / -120soft ». Elles ne se compensent jamais : le solveur préfère toujours un planning meilleur en dur, quel que soit le prix payé ailleurs.`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.results.term.hard:Score dur (hard)`,
              text: $localize`:@@aide.results.def.hard:La seule composante à regarder d'abord. À zéro, le planning est valide : toutes les places sont pourvues, aucune indisponibilité ni compétence manquante, et tout le cadre légal est respecté. Négatif, le planning n'est pas utilisable tel quel.`,
            },
            {
              term: $localize`:@@aide.results.term.medium:Score medium`,
              text: $localize`:@@aide.results.def.medium:Ce qui doit être respecté autant que possible et signalé sinon : référent par stand, équilibrage de charge, encadrement des mineurs. Un medium très négatif n'invalide pas le planning, il indique un confort dégradé.`,
            },
            {
              term: $localize`:@@aide.results.term.soft:Score souple (soft)`,
              text: $localize`:@@aide.results.def.soft:Ce qui départage deux plannings valides : rotation des stands, mixité des niveaux, équité des créneaux pénibles. Ne l'optimisez jamais avant d'avoir un dur à zéro.`,
            },
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.results.lecture:La « Lecture du score », en tête de l'onglet Calculer et du Diagnostic et pour chaque plan comparé dans Versions du plan, dit la même chose en quelques phrases : si les règles impératives sont respectées, combien de places restent vides et quel jour, quelles règles pèsent le plus sur l'organisation et pour quelle part, ce qui vient d'une donnée absente et quels ajustements manuels sont en cause. Juste après une résolution, une dernière phrase dit ce qui a changé par rapport au plan d'avant. Chaque règle citée mène à son panneau dans Règles du planning, chaque jour à sa Journée ; aucune personne n'y est nommée.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.results.courbe:Pendant la résolution, l'onglet Calculer trace les trois composantes en direct, chacune dans son cadre. Le haut d'un cadre est le zéro : une courbe qui vient s'y coller veut dire « plus rien à corriger à ce niveau-là ». Quand le dur est à zéro depuis plusieurs minutes et que le souple ne bouge plus, la résolution plafonne : « Arrêter le solveur » ne vous coûtera rien, un solveur arrêté enregistre et analyse ce qu'il a trouvé. Seule la résolution en cours est tracée.`,
        },
        {
          kind: 'callout',
          title: $localize`:@@aide.results.callout.title:Des points qui ne mesurent rien`,
          text: $localize`:@@aide.results.callout.text:Une contrainte qui pénalise chaque poste faute de donnée saisie (aucun souhait déclaré, aucun référent) produit un plancher constant, parfois l'essentiel du total. Sur la page Règles du planning, une règle qui pénalise au moins 95 % de ce qu'elle évalue porte une marque dans sa colonne Écarts ; son panneau nomme la donnée manquante et renvoie vers l'écran où la saisir. Rien n'est désactivé à votre place. Comparez des scores entre deux résolutions du même jeu de données, jamais leur valeur absolue.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.results.previousPlan:Un calcul relancé repart du plan enregistré et ne peut pas finir en dessous de lui à données égales. Il peut quand même le dégrader si le référentiel a changé entre-temps, ou si vous avez recommencé de zéro. L'onglet Calculer affiche donc le score d'avant à côté de celui d'après et signale une résolution moins bonne. Un dur toujours à zéro ne veut pas dire que rien n'a été perdu : l'écart se lit sur le medium. « Revenir au plan d'avant » remet le planning précédent ; ne tardez pas, seules les cinq dernières versions prises avant un calcul sont conservés par édition.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.results.instantanes:Un seul planning est enregistré à la fois, et chaque résolution écrase le précédent : ce sont les versions, sur la page Versions du plan, qui gardent les autres. Une version est prise automatiquement avant chaque résolution. Un plan que vous voulez garder se met de côté explicitement, avec « Enregistrer cet état » et un libellé : celle-là n'est jamais purgée, et sert de terme de comparaison. Restaurer pendant une résolution est refusé par le serveur : la résolution écraserait le plan qu'on vient de remettre en place.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.results.fraicheur:Chaque version indique s'il est encore à jour. « Périmé » veut dire que les données (stands, animateurs, créneaux, paramètres, règles) ont été modifiées après la capture, et l'infobulle donne la date. Le restaurer reste possible, avec une confirmation à part : remettre ce plan en place annulerait la prise en compte de ce qui a changé depuis. Le plus souvent, mieux vaut relancer une résolution.`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.results.term.faisabilite:Bandeau de faisabilité`,
              text: $localize`:@@aide.results.def.faisabilite:Estimation de capacité calculée sans résoudre, en haut de l'onglet Calculer et de la page Règles du planning, qui nomme les créneaux et stands en cause. Elle est optimiste : elle ignore le découpage en vacations et le cadre légal des mineurs, donc elle peut annoncer « réalisable » un planning que le solveur n'amènera pas à zéro. L'inverse n'arrive pas : si elle signale un manque de capacité, il est réel.`,
            },
            {
              term: $localize`:@@aide.results.term.problemes:Page Problèmes`,
              text: $localize`:@@aide.results.def.problemes:La liste complète et hiérarchisée des causes. Elle mêle deux sources : les infaisabilités structurelles, calculées sans résolution et visibles dès la saisie (capacité insuffisante sur une tranche, typologie que personne ne maîtrise, stand réservé aux majeurs sans majeur disponible), et les règles en défaut de la dernière analyse. Chaque ligne pointe vers l'écran où corriger. C'est le premier écran à ouvrir quand une résolution ne donne pas zéro, et un bon réflexe avant même de lancer la première.`,
            },
            {
              term: $localize`:@@aide.results.term.contraintes:Page Règles du planning`,
              text: $localize`:@@aide.results.def.contraintes:Le catalogue des règles en trois onglets (Légal, Qualité, Calcul), chacune sur une ligne avec son fondement, son état, ses réglages et le résultat de la dernière analyse : combien de fois elle n'est pas respectée, et le détail des écarts dans son panneau. C'est ce qui transforme « -14 hard » en « quatorze postes non pourvus sur tel stand ».`,
            },
            {
              term: $localize`:@@aide.results.term.stabilite:Stabilité du plan publié`,
              text: $localize`:@@aide.results.def.stabilite:Une fois un planning publié, chaque personne déplacée d'un siège qu'elle tenait coûte un point medium : un calcul relancé après un changement tardif ne bouscule les gens déjà prévenus que si le gain vaut le dérangement. La règle se dose comme les autres et reste muette tant que rien n'a été publié. Ce n'est pas un gel : pour figer, il y a les verrouillages.`,
            },
            {
              term: $localize`:@@aide.results.term.recalculApresPublication:Recalculer après une publication`,
              text: $localize`:@@aide.results.def.recalculApresPublication:Dès qu'un planning est publié, le calcul se fait en deux étapes dans le même job : d'abord trouver un planning sans écart dur, sans tenir compte de la stabilité, puis un polissage qui ramène sur leur siège publié autant de personnes que les règles dures et le temps restant le permettent. Sans cela, la stabilité pouvait empêcher d'atteindre zéro en dur. Le récapitulatif du calcul dit combien de places publiées la seconde étape a rendues à leur titulaire. Resserrer une règle après publication, comme allumer la règle dure des jours d'affilée ou baisser un seuil, déplace forcément du monde, et la prochaine publication préviendra chacun. Pour déplacer moins de monde, resserrez avant la première publication, ou verrouillez les journées qui ne doivent plus bouger.`,
            },
            {
              term: $localize`:@@aide.results.term.pourquoiLui:« Pourquoi lui ? »`,
              text: $localize`:@@aide.results.def.pourquoiLui:Un clic sur un animateur affecté explique cette affectation précise : les règles respectées ou non pour ce poste, dans le panneau du siège de la page Planning. À la demande, l'écran cherche aussi qui pourrait le remplacer, et ne propose que les remplacements qui n'introduisent aucun écart dur, chacun avec son effet sur le score. La recherche est bornée et annonce combien de candidats elle a évalués : une liste courte ne prouve pas qu'il n'existe rien d'autre.`,
            },
          ],
        },
      ],
      links: [
        { route: '/diagnostic', label: $localize`:@@aide.link.problemes:Problèmes` },
        { route: '/regles', label: $localize`:@@nav.link.regles:Règles du planning` },
        {
          route: '/journee',
          label: $localize`:@@aide.link.dayCalendar:Tableau de la journée`,
        },
        lienVersions(),
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
          kind: 'paragraph',
          text: $localize`:@@aide.tuning.playbookGestes:Sous chaque problème du Diagnostic, le bloc « Que faire ? » propose les gestes qui le règlent, dans l'ordre que le catalogue des règles leur donne, chacun avec une phrase d'explication. Un seul s'accomplit sur place : « Qui peut tenir ce siège ? » ouvre la liste de qui pourrait tenir un siège libre du créneau, et son « Placer » le remplit sans quitter le Diagnostic. Les autres ouvrent l'écran déjà positionné, qui garde ses propres aperçus et confirmations. Pour un créneau en sous-effectif : qui peut tenir ce siège, puis saisir la compétence sur la typologie du stand, puis baisser l'effectif demandé, enfin revoir les indisponibilités du jour. Quand l'écart tombe sur un siège tenu par une seule personne, le même geste s'appelle « Qui peut remplacer ? » et ouvre la page Planning sur ce siège-là, dont le panneau propose « Remplacer ». Pour une règle légale en défaut : proposer une réparation depuis la page Planning, ou chercher un remplaçant depuis le panneau du siège ; une règle légale ne se règle pas. Pour une règle de qualité : d'abord le geste qui change le plan ou le référentiel (le siège, la compétence, la fiche du stand, le plafond de la règle, la charge par personne, le verrou), et « Baisser l'importance » toujours en dernier, masqué quand l'importance est déjà au plus bas. Pour une règle en plancher : saisir d'abord la donnée manquante. Pour une pause sans relais : ouvrir une place de relais sur le stand, ou raccourcir la vacation. Pour des ajustements manuels ou un verrou en cause : les revoir, affichés seuls. Une journée déjà commencée ne propose rien qui la modifierait : son plan est figé tel que travaillé. L'explication du premier geste d'une règle reprend mot pour mot son conseil, sauf quand ce conseil propose de baisser l'importance : seul le dernier bouton en parle.`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.tuning.term.postesVides:Des postes restent non pourvus`,
              text: $localize`:@@aide.tuning.def.postesVides:Regardez d'abord le besoin en animateurs : si l'effectif saisi est sous le minimum estimé, il manque des animateurs, pas du temps de calcul. Sinon, la page Problèmes dit quels créneaux sont concernés : c'est souvent une poignée de créneaux de pointe, un stand ouvert par erreur, ou une typologie que presque personne ne maîtrise.`,
            },
            {
              term: $localize`:@@aide.tuning.term.plateau:Le score dur stagne près de zéro`,
              text: $localize`:@@aide.tuning.def.plateau:C'est le cas typique où allonger la durée paie : doublez-la et relancez. Si deux résolutions longues s'arrêtent exactement au même score, c'est la structure qui bloque : identifiez la contrainte en défaut et regardez ses écarts un par un.`,
            },
            {
              term: $localize`:@@aide.tuning.term.legal:Les écarts portent sur le temps de travail`,
              text: $localize`:@@aide.tuning.def.legal:Les plafonds hebdomadaires et le repos entre journées se heurtent au découpage. Revoyez la grille plutôt que les plafonds légaux. Quand une séquence de plus de six heures bloque, la réponse est presque toujours une place de plus sur le stand à l'heure de la pause, ou un trou dans la grille, plutôt qu'un plafond relevé.`,
            },
            {
              term: $localize`:@@aide.tuning.term.mineurs:Les écarts concernent les mineurs`,
              text: $localize`:@@aide.tuning.def.mineurs:Le cadre des mineurs est plus strict et dépend de l'âge à la date du créneau : pas de travail de nuit, amplitude réduite, pauses plus longues, deux jours de repos consécutifs, accompagnement obligatoire par un majeur. Vérifiez les dates de naissance et la proportion de majeurs disponibles sur les créneaux tardifs.`,
            },
            {
              term: $localize`:@@aide.tuning.term.adhoc:Le planning est devenu infaisable après une modification`,
              text: $localize`:@@aide.tuning.def.adhoc:Cherchez du côté des ajustements manuels et des verrouillages ajoutés depuis la dernière résolution réussie : ils ont le poids d'une contrainte dure. Supprimez temporairement le dernier ajout et relancez pour confirmer. Si deux ajustements se contredisent franchement, la page Problèmes les nomme sans aucune résolution.`,
            },
            {
              term: $localize`:@@aide.tuning.term.gelerBloc:Une part du planning est acquise et ralentit la recherche`,
              text: $localize`:@@aide.tuning.def.gelerBloc:Le montage et le démontage sont volumineux et sans difficulté, mais le solveur y consacre une part de ses mouvements proportionnelle à leur nombre de places. Une fois qu'une résolution les a correctement pourvus, posez un verrouillage de type Stand : les places restent au planning, nominatives et comptées dans les plafonds légaux, mais le solveur concentre son budget ailleurs. À ne faire qu'après vérification, puisque le verrou fige ces personnes sur ces places ; il se retire à tout moment.`,
            },
            {
              term: $localize`:@@aide.tuning.term.incremental:Un changement tombe après validation du planning`,
              text: $localize`:@@aide.tuning.def.incremental:Ne relancez pas une résolution complète : « Corriger après un changement » repart du planning enregistré, fige tout ce qui reste valable et ne recalcule que les postes qu'un changement a invalidés, plus ceux restés vides. Vous pouvez rouvrir davantage en désignant un animateur, une journée ou un stand. Saisissez d'abord l'indisponibilité, puis replanifiez : c'est elle qui ouvre les postes concernés.`,
            },
            {
              term: $localize`:@@aide.tuning.term.mediumEleve:Le score medium ou souple paraît énorme`,
              text: $localize`:@@aide.tuning.def.mediumEleve:Vérifiez d'abord qu'il ne s'agit pas d'un plancher lié à une donnée absente (souhaits, niveau référent). Enrichir les compétences et les souhaits améliore ces composantes bien plus que n'importe quel réglage du solveur.`,
            },
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.tuning.method:Ne changez qu'une chose à la fois entre deux résolutions, et relancez toujours sur la même durée pour que les scores restent comparables. Le nombre de postes affiché en haut de l'onglet Calculer permet de vérifier qu'une modification de données a bien eu l'effet attendu, avant même de relancer.`,
        },
      ],
      links: [
        { route: '/diagnostic', label: $localize`:@@aide.link.problemes:Problèmes` },
        lienStaffing(),
        { route: '/parametres', label: $localize`:@@nav.link.parametres:Paramètres` },
        lienAjustements(),
        lienVerrouillages(),
      ],
    },
    {
      id: 'ajustements-manuels',
      icon: 'rule',
      title: $localize`:@@aide.adHoc.title:Ajustements manuels`,
      summary: $localize`:@@aide.adHoc.summary:Interdire, rapprocher, faire venir ensemble : ce que chaque type recouvre, et les cas limites qui surprennent.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.adHoc.intro:Un ajustement manuel est une exception que vous saisissez sur vos propres données, à côté des règles du catalogue. Il se saisit sur le premier onglet de la page Règles du planning, qui porte aussi les verrouillages et les consignes. Il n'en reste que trois types : l'incompatibilité est appliquée au même niveau que le cadre légal, le solveur ne la contournera jamais, quitte à rendre un planning en défaut ; l'affinité et l'arrivée groupée sont des préférences. Chacun se saisit avec une raison, lisible partout où l'ajustement est cité. L'affectation forcée et l'indisponibilité forcée n'existent plus depuis la 4.0 : voir « Comment faire sans affectation forcée ».`,
        },
        {
          kind: 'callout',
          title: $localize`:@@aide.adHoc.callout.title:Ajustement manuel ou verrouillage ?`,
          text: $localize`:@@aide.adHoc.callout.text:Les deux retirent de la liberté au solveur, mais pas au même moment. Un ajustement manuel est une règle sur mesure, posée avant le calcul, qui dit qui ne doit pas travailler avec qui : « ces deux-là ne travaillent pas ensemble ». Le solveur en tient compte à chaque résolution, y compris en repartant de zéro. Un verrouillage est un geste sur un plan déjà calculé : il fige ce qu'une résolution a produit pour que la suivante n'y touche plus ; il ne dit rien de ce qui devrait s'y trouver, et ne gèle jamais une place vide. En pratique : pour interdire que deux personnes travaillent ensemble, un ajustement ; pour garder quelqu'un sur un créneau ou protéger une partie validée, un verrouillage ; pour écarter quelqu'un d'un jour, les indisponibilités de sa fiche.`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.adHoc.term.incompatibilite:Incompatibilité`,
              text: $localize`:@@aide.adHoc.def.incompatibilite:Interdit à deux personnes de travailler sur le même créneau. Elle porte sur le créneau, pas sur le stand : deux stands différents à la même heure sont tout autant interdits. Seuls les deux premiers animateurs de la liste sont pris en compte ; pour trois personnes, saisissez les trois paires.`,
            },
            {
              term: $localize`:@@aide.adHoc.term.affinite:Affinité (paire à privilégier)`,
              text: $localize`:@@aide.adHoc.def.affinite:Une préférence, pas une règle dure : elle est récompensée chaque fois que les deux personnes tiennent un poste sur le même stand au même créneau, sans rien forcer.`,
            },
            {
              term: $localize`:@@aide.adHoc.term.arriveeGroupee:Arrivée groupée (covoiturage)`,
              text: $localize`:@@aide.adHoc.def.arriveeGroupee:De 2 à 4 personnes qui arrivent et repartent ensemble. Une préférence, pas une règle dure : le solveur cherche à leur donner les mêmes jours, et des premières arrivées et derniers départs à la tolérance près (réglage « Tolérance d'une arrivée groupée », 30 min par défaut, sur la ligne de la règle dans Règles du planning, onglet Qualité). Au-delà, chaque minute d'écart et chaque jour où un membre travaille sans les autres coûtent. Chacun peut tenir un stand différent : elle ne désigne ni créneau ni stand. Elle naît le plus souvent d'une demande « Je viens avec… » envoyée depuis l'onglet Covoiturage de l'espace et validée sur la page Disponibilités, onglet Covoiturage ; elle se crée aussi à la main ici. Celle qui vient d'une demande validée ne se modifie ni ne se supprime ici : ses boutons sont grisés et mènent à Disponibilités, onglet Covoiturage, où l'annuler prévient le groupe ; celle écrite à la main reste modifiable. Elle est refusée si deux de ses membres forment une incompatibilité sans stand ni créneau : on ne fait pas arriver ensemble deux personnes qu'on refuse de faire travailler ensemble.`,
            },
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.adHoc.perimetre:Pour l'incompatibilité et l'affinité, le créneau et le stand sont facultatifs, et les laisser vides veut dire « partout ». Un ajustement conserve l'identifiant du créneau visé : si ce créneau disparaît, la colonne Périmètre affiche « créneau supprimé » et l'ajustement ne s'applique plus à rien. Après un découpage ou l'application d'un calendrier, revérifiez ceux qui visaient un créneau.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.adHoc.contradictions:Deux ajustements qui ne peuvent pas tenir ensemble sont refusés à l'enregistrement, avec un message qui les nomme tous les deux, au lieu d'un planning déclaré infaisable plusieurs minutes plus tard. Deux situations sont refusées :`,
        },
        {
          kind: 'list',
          items: [
            $localize`:@@aide.adHoc.refus1:la même paire déclarée à la fois incompatible et en affinité ;`,
            $localize`:@@aide.adHoc.refus5:une arrivée groupée dont deux membres sont déclarés incompatibles.`,
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.adHoc.modifier:Modifier un ajustement, c'est le réenregistrer sous son propre identifiant : la nouvelle version remplace la précédente, et ce cas n'est jamais refusé pour contradiction avec elle-même. Un ajustement enregistré avant que ce contrôle existe, ou importé, peut en revanche subsister : la page Problèmes le signale alors comme cause bloquante, et l'écran marque les lignes concernées.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.adHoc.import:À l'import d'un scénario, les ajustements du fichier remplacent en bloc ceux de l'édition : un fichier qui n'en porte aucun n'en installe aucun. L'export, lui, écrit la section dès que l'édition en porte. Un fichier dont les ajustements se contredisent est refusé en entier.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.adHoc.apres:Quand une résolution se termine malgré tout en défaut, la page Problèmes nomme les ajustements que le solveur n'a pas pu honorer, avec le nombre d'écarts de chacun. C'est ce qui distingue « le solveur n'y arrive pas » de « ces trois exceptions-là sont à arbitrer ».`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.adHoc.liste:Le dialogue commence par la personne, puis le type : on pense « Alice », pas « incompatibilité ». Le champ « Personne » au-dessus de la liste ne garde que les ajustements qui nomment quelqu'un (par son nom ou son identifiant) ; la fiche d'un animateur y mène filtrée sur lui. L'adresse garde le filtre.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.consignesSolveur.selection:Sur les onglets Ajustements et Verrouillages, cochez plusieurs lignes (« Tout sélectionner » suit le filtre, Espace coche la ligne au clavier) pour les supprimer ou les déverrouiller d'un geste, après une seule confirmation. Chaque ligne part seule : un refus, celui d'une résolution en cours par exemple, n'arrête pas les autres, et le message final dit ce qui est passé et ce qui a été refusé. Une arrivée groupée issue d'un covoiturage ne se coche pas.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.consignesSolveur.relancer:Après toute saisie sur l'un des trois onglets (un ajustement, un verrou, une consigne), la page propose « Relancer le calcul » sur place. Après une consigne, c'est une correction quand un plan existe : elle ne rouvre que les places que la bande a ôtées ou ajoutées. Après un ajustement ou un verrou, c'est un calcul complet qui repart du plan : une correction figerait toutes les places encore valides, et le verrou levé n'y changerait rien. L'onglet Calculer compte ces saisies dans « Ce calcul tiendra compte de ».`,
        },
      ],
      links: [
        lienAjustements(),
        { route: '/diagnostic', label: $localize`:@@aide.link.problemes:Problèmes` },
        lienVerrouillages(),
      ],
    },
    {
      id: 'sans-affectation-forcee',
      icon: 'push_pin',
      title: $localize`:@@aide.sansForcee.title:Comment faire sans affectation forcée`,
      summary: $localize`:@@aide.sansForcee.summary:Depuis la 4.0, deux gestes qui se défont d'un clic remplacent l'affectation et l'indisponibilité forcées.`,
      blocks: [
        {
          kind: 'list',
          items: [
            $localize`:@@aide.sansForcee.verrou:Garder quelqu'un sur un créneau : placez-le sur le siège depuis la page Planning (« Placer », case « La garder au prochain calcul » cochée), ou verrouillez son siège. Le verrou lie cette personne à ce créneau ; le prochain calcul le respecte, et le reste de sa journée reste libre pour le calcul. C'est ce que devient une affectation forcée.`,
            $localize`:@@aide.sansForcee.indisponibilite:Écarter quelqu'un d'un jour : ajoutez ce jour à ses indisponibilités, sur sa fiche (« Indisponible ce jour »). Elle ou il n'est plus proposé ce jour-là, et ses sièges du jour sont libérés dans le même geste. C'est ce que devient une indisponibilité forcée, mais le jour est toujours entier : la fiche ne connaît pas le créneau.`,
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.sansForcee.quand:Les affectations et indisponibilités forcées d'avant la 4.0 ont été converties à la migration : un verrou animateur + créneau sur la place tenue, un jour indisponible sur la fiche. Ce qui ne se convertissait pas (une indisponibilité visant un stand, une affectation que personne ne tenait) est gardé dans une archive jusqu'en 5.0 et signalé au démarrage. Un verrou fige ce que porte le plan enregistré, rien de plus : après une réinitialisation ou un import, posez-le de nouveau.`,
        },
      ],
      links: [lienVerrouillages(), lienAjustements()],
    },
  ];
}
