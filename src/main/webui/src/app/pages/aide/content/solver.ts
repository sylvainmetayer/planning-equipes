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
      summary: $localize`:@@aide.calculer.summary:Les trois façons de lancer le solveur, et laquelle choisir.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.calculer.intro:La page Solveur ne demande pas de choisir un mécanisme, mais de dire ce que vous voulez : le meilleur planning possible, une correction qui bouge le moins possible, ou une page blanche. Les trois lisent les mêmes données et respectent les mêmes règles ; ce qui change, c'est d'où le calcul part.`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.calculer.term.calculer:Calculer le planning`,
              text: $localize`:@@aide.calculer.def.calculer:Le bouton de tous les jours. S'il existe un plan enregistré, le calcul en repart et cherche à l'améliorer ; sinon il part de zéro. Tout peut bouger, hormis les verrouillages, mais à données égales le résultat ne peut pas finir en dessous du plan de départ. La ligne sous les boutons dit d'où partira le prochain calcul.`,
            },
            {
              term: $localize`:@@aide.calculer.term.corriger:Corriger après un changement`,
              text: $localize`:@@aide.calculer.def.corriger:Un désistement, une indisponibilité saisie tard, un stand ajouté : le planning est déjà diffusé et vous voulez bouger le moins possible. Tout ce qui reste valable est figé, seuls les postes que le changement a invalidés sont recalculés, en quelques dizaines de secondes. La fenêtre montre d'abord ce qui a changé depuis le plan, puis propose d'en rouvrir davantage : des animateurs, des journées, des stands. Le compte rendu nomme qui a bougé. Ne réoptimise rien.`,
            },
            {
              term: $localize`:@@aide.calculer.term.recommencer:Recommencer de zéro`,
              text: $localize`:@@aide.calculer.def.recommencer:Ignore le plan enregistré et repart de rien. C'est le seul geste qui peut perdre la qualité déjà atteinte, d'où la confirmation : réservez-le à un essai que vous abandonnez ou à des données refaites en profondeur. Sans plan enregistré, le bouton est inactif.`,
            },
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.calculer.entrees:La page s'ouvre sur ce qui empêche un planning sans écart — faisabilité et alertes —, puis sur « Ce calcul tiendra compte de » : les verrouillages, les ajustements manuels, les consignes et leurs dates, les déclarations de disponibilité encore en attente — que le calcul ne verra pas —, les règles désactivées et les modifications faites depuis la dernière résolution. Chaque compteur mène à l'écran qui les liste ; un compteur à zéro reste affiché, en retrait. Viennent ensuite les trois boutons, chacun avec sa phrase. Après un calcul, son résultat se lit en phrases, et « Voir le planning », « Relire » — la première journée pas encore relue — et « Publier » sont juste dessous, suivis des cinq dernières versions du plan. La volumétrie du problème est repliée en bas de page ; le budget de calcul se règle dans Règles du planning, onglet Calcul.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.calculer.verrous:Les verrouillages ne sont pas un quatrième mode : ils s'appliquent aux trois. Un animateur, un stand, une journée ou un créneau verrouillé n'est touché ni par un calcul complet ni par une correction.`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.calculer.term.passe:Le passé est figé`,
              text: $localize`:@@aide.calculer.def.passe.instance:Pendant l'événement, les trois calculs reprennent du planning enregistré les postes des créneaux déjà commencés — journée passée, ou créneau d'aujourd'hui dont l'heure est atteinte — et les figent tels qu'ils ont été travaillés, même si la personne a depuis déclaré la journée indisponible, même si le poste est resté vide. Ces postes comptent dans les repos, les cumuls et les jours d'affilée, mais aucune règle ne les reproche : le zéro dur se lit sur ce qui reste à jouer. Le compte rendu les annonce (« postes déjà commencés »). En développement, en démonstration ou en recette, la date que lit cette règle se pose depuis Paramètres, onglet Instance ; en exploitation, la variable PASSE_FIGE=false la coupe, pour rejouer une édition ancienne.`,
            },
            {
              term: $localize`:@@aide.calculer.term.passeGestes:Le passé ne se modifie plus`,
              text: $localize`:@@aide.calculer.def.passeGestes:Les gestes à la main suivent la même règle : déplacer une affectation sur une vue journalière, accepter un échange, appliquer ou demander une réparation sur un poste dont le créneau est déjà commencé est refusé — « Ce créneau est déjà commencé : le passé ne se modifie plus » — depuis l'écran, l'API ou un assistant. Le mode jour J continue d'agir sur les créneaux restants de la journée ; le siège d'un créneau en cours reste tel qu'il est, l'absence y est seulement enregistrée. Quand PASSE_FIGE=false, rien n'est refusé.`,
            },
            {
              term: $localize`:@@aide.calculer.term.rienAPlanifier:Rien à planifier`,
              text: $localize`:@@aide.calculer.def.rienAPlanifier:Un calcul lancé alors que tous les créneaux sont déjà commencés — l'édition est terminée, ou la date simulée est après l'événement — est refusé au lieu de produire un planning vide à zéro dur ; le job le dit dans son erreur. Quand des postes passés sont restés vides (aucun titulaire enregistré, par exemple au premier calcul lancé pendant l'événement), le calcul a lieu et le compte rendu l'annonce : « N sièges passés sont restés vides ».`,
            },
            {
              term: $localize`:@@aide.calculer.term.plancherPasse:Un plancher pendant l'événement`,
              text: $localize`:@@aide.calculer.def.plancherPasse:Les équilibres de charge et de créneaux pénibles mesurent l'édition entière, postes passés compris, et le calcul ne peut plus bouger que l'avenir : un écart déjà creusé avant aujourd'hui ne se rattrape pas et laisse un score medium qu'aucun calcul ne ramènera à zéro. C'est attendu, et c'est pourquoi le score se compare d'un calcul à l'autre plutôt qu'au zéro.`,
            },
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.calculer.bloquants:Quand le bandeau de faisabilité porte un problème qu'aucun calcul ne peut résoudre — deux ajustements manuels qui se contredisent, une affectation forcée que personne ne peut tenir — les trois boutons demandent confirmation avant de partir, et nomment la cause : le calcul finirait en score dur négatif quel que soit le temps qu'il tourne. Lancer quand même reste possible, le reste du planning s'améliore. Un manque d'animateurs, lui, ne demande rien : le solveur remplit encore ce qu'il peut.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.calculer.attente:Quand le solveur est occupé, chacun des trois boutons planifie le calcul au lieu de le refuser : il démarrera de lui-même dès que la tâche en cours sera terminée.`,
        },
      ],
      links: [
        { route: '/solveur', label: $localize`:@@nav.link.solver:Solveur` },
        {
          route: '/consignes-solveur',
          queryParams: { onglet: 'verrouillages' },
          label: $localize`:@@consignesSolveur.onglet.verrouillages:Verrouillages`,
        },
      ],
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
              text: $localize`:@@aide.config.def.duree:Réglé par édition sur la page Règles du planning, onglet Calcul, partagé par tous les navigateurs et appliqué par le serveur à tout calcul lancé sur l'édition, depuis l'écran comme depuis l'assistant. Deux réglages. La durée maximale (15 min par défaut) est celui qui compte le plus : commencez court — 1 à 3 min — pour révéler les blocages structurels, puis passez à 15 ou 30 min, voire davantage, pour la résolution finale. L'arrêt sans amélioration (5 min par défaut, 0 pour jamais) écourte un calcul qui ne progresse plus ; il ne compte qu'une fois le score dur à zéro, de sorte qu'un calcul n'abandonne jamais tant que des places restent à pourvoir. Les deux sont bornés par un plafond que fixe l'exploitant de l'instance, affiché au-dessus des champs : une valeur au-dessus est refusée. Si l'exploitant abaisse ce plafond sous une valeur déjà enregistrée, le calcul tourne au plafond et le dit. « Revenir au défaut » rend l'édition aux valeurs de l'instance, à l'enregistrement.`,
            },
            {
              term: $localize`:@@aide.config.term.mailFin:Prévenir à la fin d'une résolution`,
              text: $localize`:@@aide.config.def.mailFin:Un interrupteur de Règles du planning, onglet Calcul, fait écrire à l'administrateur dès qu'une résolution de cette édition se termine : l'édition, le score, et si le planning est faisable. De quoi lancer un calcul de trente minutes et partir. Il reste sans effet tant qu'aucune adresse administrateur n'est configurée sur le serveur.`,
            },
            {
              term: $localize`:@@aide.config.term.legaux:Paramètres légaux`,
              text: $localize`:@@aide.config.def.legaux:Sur la page Règles du planning, onglet Légal, chacun sur la ligne de la règle qui le lit : les plafonds hebdomadaires sur les durées hebdomadaires, la durée de la pause légale sur les durées et le travail continu, la fenêtre et la durée du repas sur la coupure repas. Les plafonds (48 h pour les majeurs, 35 h pour les mineurs) sont d'ordre public : une valeur supérieure est refusée, une valeur inférieure reste libre. La pause dure au moins 20 minutes (art. L3121-16), portée à 30 pour un mineur (art. L3162-3), 30 par défaut. Le repos quotidien minimal (11 h, 9 h par accord collectif) et la durée maximale d'une vacation, que seul le contrôle de la grille lit, se règlent sous le tableau. Rien n'impose d'écart entre deux vacations.`,
            },
            {
              term: $localize`:@@aide.config.term.contraintes:Activation des contraintes`,
              text: $localize`:@@aide.config.def.contraintes:Chaque règle du catalogue se désactive sur sa ligne de la page Règles du planning, puis « Enregistrer ». À utiliser pour diagnostiquer — « sans cette règle, le planning devient-il faisable ? » — bien plus que pour produire : désactiver une contrainte dure produit un planning que la réalité refusera. En revanche, désactiver une règle souple qui ne mesure rien, faute de donnée saisie, rend le score lisible sans rien changer au résultat.`,
            },
            {
              term: $localize`:@@aide.config.term.importance:Importance d'une règle`,
              text: $localize`:@@aide.config.def.importance:Sur l'onglet Qualité, chaque règle de qualité ou de préférence a trois positions : faible, normale — son défaut — et forte, qui écrivent un poids de 1, 5 ou 25. À niveau égal, un écart d'une règle forte pèse cinq fois celui d'une règle normale, qui en pèse cinq d'une faible. Un autre poids, de 1 à 500, se saisit dans le panneau de la règle et s'affiche « personnalisée ». Une règle dure ne se dose pas : elle s'active ou non. Rien ne s'écrit avant « Enregistrer », et « Relancer le calcul », en pied de tableau, fait prendre en compte ce qui vient d'être enregistré.`,
            },
            {
              term: $localize`:@@aide.config.term.historique:Historique des réglages`,
              text: $localize`:@@aide.config.def.historique:Le panneau d'une règle, qui s'ouvre d'un clic sur son nom dans Règles du planning, montre chaque changement de son poids ou de son activation — valeur avant et après, défaut compris, date et origine : écran, assistant, import de scénario ou duplication d'édition — et, entre deux changements, les résolutions qui ont suivi avec leur score et le nombre d'écarts à cette règle, en liste datée et en petit graphique. Remettre la même valeur n'écrit rien. C'est une juxtaposition, pas une mesure d'effet : le référentiel a pu changer entre deux résolutions. L'historique appartient à l'édition et disparaît avec elle ; une édition dupliquée démarre avec le dosage hérité de sa source, pas avec l'historique de celle-ci.`,
            },
            {
              term: $localize`:@@aide.config.term.adhoc:Ajustements manuels`,
              text: $localize`:@@aide.config.def.adhoc:Exceptions saisies au cas par cas, avec une raison tracée : indisponibilité forcée, incompatibilité, affectation forcée. Elles sont traitées au même niveau que les contraintes dures, donc jamais contournées — et chacune retire des possibilités au solveur. Un ajustement mal posé bloque un planning aussi sûrement qu'un manque d'effectif.`,
            },
            {
              term: $localize`:@@aide.config.term.verrouillages:Verrouillages`,
              text: $localize`:@@aide.config.def.verrouillages:Geler un animateur, un stand, une journée ou un créneau pour que la prochaine résolution n'y touche plus et optimise le reste. Une place non pourvue n'est jamais gelée, et les places gelées continuent d'être évaluées : un verrou laisse donc une alerte visible plutôt que de masquer un problème — et quand ce que vous figez casse déjà une règle dure, l'enregistrement le dit, pour que le score dur négatif du prochain calcul ne soit pas une surprise. Ils se posent sur l'onglet Verrouillages de la page Consignes au solveur, sur un animateur par défaut ; avant d'enregistrer, l'aperçu compte les affectations que le verrou figera et mène à la Journée qui les montre. Ce n'est pas un ajustement manuel — voir l'encart « Ajustement manuel ou verrouillage ? ».`,
            },
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.config.lock:Une seule résolution tourne à la fois pour tout le serveur, éventuellement lancée depuis un autre poste ; le moniteur de la barre d'outils indique laquelle et depuis combien de temps. Seule l'édition d'où le calcul a été lancé est verrouillée en saisie : sur une autre, vous continuez à travailler.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.config.saisieEnCours:Un calcul ne lit que ce qui est enregistré. « Calculer » demande donc confirmation quand une fiche animateur, une fiche stand ou une consigne est en cours de saisie sans avoir été enregistrée, dans cet onglet ou laissée en brouillon. À l'inverse, si un calcul démarre pendant que vous saisissez — depuis un autre onglet, un autre poste, l'assistant ou une routine —, le formulaire le dit, avec l'heure de fin au plus tard : votre saisie reste à l'écran et s'enregistre à la fin du calcul. Les décisions de la page Disponibilités attendent aussi la fin du calcul. Un assistant peut, lui, continuer à écrire certaines données pendant un calcul ; sa réponse l'avertit alors que le calcul en cours n'en tiendra pas compte.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.config.file:Pendant qu'un calcul tourne, les boutons deviennent « Planifier » : la tâche est mise en file et démarre dès que la précédente se termine. C'est le geste pour enchaîner deux éditions sans attendre devant l'écran. Une tâche en attente ne verrouille rien et lit son édition telle qu'elle sera au démarrage, donc une correction apportée entre-temps sera prise en compte. La file s'affiche sous les boutons, une ligne s'en retire tant qu'elle n'a pas démarré, et un redémarrage du serveur la vide.`,
        },
      ],
      links: [
        { route: '/solveur', label: $localize`:@@nav.link.solver:Solveur` },
        { route: '/regles', label: $localize`:@@nav.link.regles:Règles du planning` },
        { route: '/parametres', label: $localize`:@@nav.link.parametres:Paramètres` },
        {
          route: '/consignes-solveur',
          queryParams: { onglet: 'verrouillages' },
          label: $localize`:@@consignesSolveur.onglet.verrouillages:Verrouillages`,
        },
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
              text: $localize`:@@aide.setup.def.creneaux:Le solveur affecte une personne par créneau et par siège. Des vacations qui se touchent s'enchaînent librement : rien n'impose d'écart entre deux d'entre elles, et un trou plus court que la pause légale compte comme du travail. Préférez tout de même des blocs — matin, midi, après-midi, nocturne — et gardez la relève de midi comme trou planifié : c'est la pause déjeuner. Découper un après-midi en deux sessions ne réduit jamais le besoin en personnes.`,
            },
            {
              term: $localize`:@@aide.setup.term.pause:La pause légale : un trou, ou un relais`,
              text: $localize`:@@aide.setup.def.pause:Le Code du travail impose une pause dès six heures d'affilée (quatre heures et demie pour un mineur). L'outil la reconnaît de deux manières, et pas d'une troisième : un trou d'au moins la durée réglée dans la grille — il coupe la séquence, plus rien n'est dû — ou un relais, un collègue du même stand qui tient une place pendant toute la pause. Une pause que personne ne peut prendre est un écart dur.`,
            },
            {
              term: $localize`:@@aide.setup.term.surPoste:Une relève enchaînée à l'après-midi reste possible`,
              text: $localize`:@@aide.setup.def.surPoste:Une relève de 13 h à 20 h est sept heures d'affilée : elle doit une pause vers la sixième heure, et il suffit qu'un collègue tienne le stand à ce moment-là pour qu'elle soit légale. Rien à déclarer : ouvrez la place, le solveur s'en sert. L'écran Pauses pose ensuite la rotation, une personne à la fois par stand, et nomme les journées où le relais manque. La pause est du repos, donc déduite des plafonds et de tous les compteurs d'heures.`,
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
            $localize`:@@aide.setup.step5:Fragilité, puis Pauses : qui est irremplaçable, et quels relais organiser avant de publier.`,
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.setup.repere:Un repère pour arbitrer : une grille dont la borne d'effectif est plus basse ne se résout pas forcément mieux. La borne dit ce qui est impossible, jamais ce qui est faisable — seule la résolution tranche.`,
        },
      ],
      links: [
        { route: '/stands', label: $localize`:@@nav.link.stands:Stands` },
        { route: '/ouvertures', label: $localize`:@@nav.link.ouvertures:Horaires des stands` },
        { route: '/creneaux', label: $localize`:@@nav.link.creneaux:Créneaux` },
        { route: '/regles', label: $localize`:@@nav.link.regles:Règles du planning` },
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
          label: $localize`:@@aide.link.pauses:Pauses et repas`,
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
          text: $localize`:@@aide.results.lecture:La « Lecture du score », en tête de la page Solveur et du Diagnostic et pour chaque plan comparé dans Versions du plan, dit la même chose en quelques phrases : si les règles impératives sont respectées, combien de places restent vides et quel jour, quelles règles pèsent le plus sur l'organisation et pour quelle part, ce qui vient d'une donnée absente et quels ajustements manuels sont en cause. Juste après une résolution, une dernière phrase dit ce qui a changé par rapport au plan d'avant. Chaque règle citée mène à son panneau dans Règles du planning, chaque jour à sa Journée ; aucune personne n'y est nommée.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.results.courbe:Pendant la résolution, la page Solveur trace les trois composantes en direct, chacune dans son cadre. Le haut d'un cadre est le zéro : une courbe qui vient s'y coller veut dire « plus rien à corriger à ce niveau-là ». Quand le dur est à zéro depuis plusieurs minutes et que le souple ne bouge plus, la résolution plafonne : « Arrêter le solveur » ne vous coûtera rien, un solveur arrêté enregistre et analyse ce qu'il a trouvé. Seule la résolution en cours est tracée.`,
        },
        {
          kind: 'callout',
          title: $localize`:@@aide.results.callout.title:Des points qui ne mesurent rien`,
          text: $localize`:@@aide.results.callout.text:Une contrainte qui pénalise chaque poste faute de donnée saisie — aucun souhait déclaré, aucun référent — produit un plancher constant, parfois l'essentiel du total. Sur la page Règles du planning, une règle qui pénalise au moins 95 % de ce qu'elle évalue porte une marque dans sa colonne Écarts ; son panneau nomme la donnée manquante et renvoie vers l'écran où la saisir. Rien n'est désactivé à votre place. Comparez des scores entre deux résolutions du même jeu de données, jamais leur valeur absolue.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.results.previousPlan:Un calcul relancé repart du plan enregistré et ne peut pas finir en dessous de lui à données égales. Il peut quand même le dégrader si le référentiel a changé entre-temps, ou si vous avez recommencé de zéro. La page Solveur affiche donc le score d'avant à côté de celui d'après et signale une résolution moins bonne — un dur toujours à zéro ne veut pas dire que rien n'a été perdu, l'écart se lit sur le medium. « Revenir au plan d'avant » remet le planning précédent ; ne tardez pas, seuls les cinq derniers instantanés automatiques sont conservés par édition.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.results.instantanes:Un seul planning est enregistré à la fois, et chaque résolution écrase le précédent : ce sont les instantanés, sur la page Versions du plan, qui gardent les autres. Une capture est prise automatiquement avant chaque résolution. Un plan que vous voulez garder se met de côté explicitement, avec « Enregistrer cet état » et un libellé : celui-là n'est jamais purgé, et sert de terme de comparaison. Restaurer pendant une résolution est refusé par le serveur : la résolution écraserait le plan qu'on vient de remettre en place.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.results.fraicheur:Chaque instantané indique s'il est encore à jour. « Périmé » veut dire que les données — stands, animateurs, créneaux, paramètres, règles — ont été modifiées après la capture, et l'infobulle donne la date. Le restaurer n'est pas interdit, mais demande une confirmation à part : remettre ce plan en place annulerait la prise en compte de ce qui a changé depuis. Le plus souvent, mieux vaut relancer une résolution.`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.results.term.faisabilite:Bandeau de faisabilité`,
              text: $localize`:@@aide.results.def.faisabilite:Estimation de capacité calculée sans résoudre, en haut des pages Solveur et Règles du planning, qui nomme les créneaux et stands en cause. Elle est optimiste : elle ignore le découpage en vacations et le cadre légal des mineurs, donc elle peut annoncer « réalisable » un planning que le solveur n'amènera pas à zéro. L'inverse n'arrive pas : si elle signale un manque de capacité, il est réel.`,
            },
            {
              term: $localize`:@@aide.results.term.problemes:Page Problèmes`,
              text: $localize`:@@aide.results.def.problemes:La liste complète et hiérarchisée des causes. Elle mêle deux sources : les infaisabilités structurelles, calculées sans résolution et visibles dès la saisie (capacité insuffisante sur une tranche, typologie que personne ne maîtrise, stand réservé aux majeurs sans majeur disponible), et les règles en défaut de la dernière analyse. Chaque ligne pointe vers l'écran où corriger. C'est le premier écran à ouvrir quand une résolution ne donne pas zéro, et un bon réflexe avant même de lancer la première.`,
            },
            {
              term: $localize`:@@aide.results.term.contraintes:Page Règles du planning`,
              text: $localize`:@@aide.results.def.contraintes:Le catalogue des règles en trois onglets — Légal, Qualité, Calcul —, chacune sur une ligne avec son fondement, son état, ses réglages et le résultat de la dernière analyse : combien de fois elle n'est pas respectée, et le détail des écarts dans son panneau. C'est ce qui transforme « -14 hard » en « quatorze postes non pourvus sur tel stand ».`,
            },
            {
              term: $localize`:@@aide.results.term.stabilite:Stabilité du plan publié`,
              text: $localize`:@@aide.results.def.stabilite:Une fois un planning publié, chaque personne déplacée d'un siège qu'elle tenait coûte un point medium : un calcul relancé après un changement tardif ne bouscule les gens déjà prévenus que si le gain vaut le dérangement. La règle se dose comme les autres et reste muette tant que rien n'a été publié. Ce n'est pas un gel : pour figer, il y a les verrouillages.`,
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
        { route: '/versions', label: $localize`:@@nav.link.versions:Versions du plan` },
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
          text: $localize`:@@aide.tuning.playbookGestes:Sous chaque problème du Diagnostic, le bloc « Que faire ? » propose les gestes qui le règlent, dans l'ordre que le catalogue des règles leur donne, chacun avec une phrase d'explication. Un seul s'accomplit sur place : « Qui peut tenir ce siège ? » ouvre la liste de qui pourrait tenir un siège libre du créneau, et son « Placer » le remplit sans quitter le Diagnostic. Les autres ouvrent l'écran déjà positionné, qui garde ses propres aperçus et confirmations. Pour un créneau en sous-effectif : qui peut tenir ce siège, puis saisir la compétence sur la typologie du stand, puis baisser l'effectif demandé, enfin revoir les indisponibilités du jour. Pour une règle légale en défaut : proposer une réparation depuis la page Planning, ou chercher un remplaçant depuis le panneau du siège — une règle légale ne se règle pas. Pour une règle de qualité : le geste qui change le plan ou le référentiel d'abord — le siège, la compétence, la fiche du stand, le plafond de la règle, la charge par personne, le verrou —, et « Baisser l'importance » toujours en dernier, masqué quand l'importance est déjà au plus bas. Pour une règle en plancher : saisir d'abord la donnée manquante. Pour une pause sans relais : ouvrir une place de relais sur le stand, ou raccourcir la vacation. Pour des ajustements manuels ou un verrou en cause : les revoir, affichés seuls. Une journée déjà commencée ne propose rien qui la modifierait : son plan est figé tel que travaillé. L'explication du premier geste d'une règle reprend mot pour mot son conseil.`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.tuning.term.postesVides:Des postes restent non pourvus`,
              text: $localize`:@@aide.tuning.def.postesVides:Regardez d'abord le besoin en animateurs : si l'effectif saisi est sous le minimum estimé, il manque des animateurs, pas du temps de calcul. Sinon, la page Problèmes dit quels créneaux sont concernés — c'est souvent une poignée de créneaux de pointe, un stand ouvert par erreur, ou une typologie que presque personne ne maîtrise.`,
            },
            {
              term: $localize`:@@aide.tuning.term.plateau:Le score dur stagne près de zéro`,
              text: $localize`:@@aide.tuning.def.plateau:C'est le cas typique où allonger la durée paie : doublez-la et relancez. Si deux résolutions longues s'arrêtent exactement au même score, le problème n'est plus le temps mais la structure — identifiez la contrainte en défaut et regardez ses écarts un par un.`,
            },
            {
              term: $localize`:@@aide.tuning.term.legal:Les écarts portent sur le temps de travail`,
              text: $localize`:@@aide.tuning.def.legal:Les plafonds hebdomadaires et le repos entre journées se heurtent au découpage. Revoyez la grille plutôt que les plafonds légaux. Quand une séquence de plus de six heures bloque, la réponse est presque toujours une place de plus sur le stand à l'heure de la pause, ou un trou dans la grille — pas un plafond relevé.`,
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
              text: $localize`:@@aide.tuning.def.gelerBloc:Le montage et le démontage sont volumineux et sans difficulté, mais le solveur y consacre une part de ses mouvements proportionnelle à leur nombre de places. Une fois qu'une résolution les a correctement pourvus, posez un verrouillage de type Stand : les places restent au planning, nominatives et comptées dans les plafonds légaux, mais le solveur concentre son budget ailleurs. À ne faire qu'après vérification — le verrou fige ces personnes sur ces places — et il se retire à tout moment.`,
            },
            {
              term: $localize`:@@aide.tuning.term.incremental:Un changement tombe après validation du planning`,
              text: $localize`:@@aide.tuning.def.incremental:Ne relancez pas une résolution complète : « Corriger après un changement » repart du planning enregistré, fige tout ce qui reste valable et ne recalcule que les postes qu'un changement a invalidés, plus ceux restés vides. Vous pouvez rouvrir davantage en désignant un animateur, une journée ou un stand. Saisissez d'abord l'indisponibilité, puis replanifiez : c'est elle qui ouvre les postes concernés.`,
            },
            {
              term: $localize`:@@aide.tuning.term.mediumEleve:Le score medium ou souple paraît énorme`,
              text: $localize`:@@aide.tuning.def.mediumEleve:Vérifiez d'abord qu'il ne s'agit pas d'un plancher lié à une donnée absente — souhaits, niveau référent. Enrichir les compétences et les souhaits améliore ces composantes bien plus que n'importe quel réglage du solveur.`,
            },
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.tuning.method:Ne changez qu'une chose à la fois entre deux résolutions, et relancez toujours sur la même durée pour que les scores restent comparables. Le nombre de postes affiché en haut de la page Solveur permet de vérifier qu'une modification de données a bien eu l'effet attendu, avant même de relancer.`,
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
          route: '/consignes-solveur',
          queryParams: { onglet: 'ajustements' },
          label: $localize`:@@consignesSolveur.onglet.ajustements:Ajustements`,
        },
        {
          route: '/consignes-solveur',
          queryParams: { onglet: 'verrouillages' },
          label: $localize`:@@consignesSolveur.onglet.verrouillages:Verrouillages`,
        },
      ],
    },
    {
      id: 'ajustements-manuels',
      icon: 'rule',
      title: $localize`:@@aide.adHoc.title:Ajustements manuels`,
      summary: $localize`:@@aide.adHoc.summary:Forcer, interdire, rapprocher : ce que chaque type recouvre, et les cas limites qui surprennent.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.adHoc.intro:Un ajustement manuel est une exception que vous saisissez sur vos propres données, à côté des règles du catalogue — premier onglet de la page Consignes au solveur, avec les verrouillages et les consignes. Trois des quatre types sont appliqués au même niveau que le cadre légal : le solveur ne les contournera jamais, quitte à rendre un planning en défaut. Chacun se saisit avec une raison, lisible partout où l'ajustement est cité.`,
        },
        {
          kind: 'callout',
          title: $localize`:@@aide.adHoc.callout.title:Ajustement manuel ou verrouillage ?`,
          text: $localize`:@@aide.adHoc.callout.text:Les deux retirent de la liberté au solveur, mais pas au même moment. Un ajustement manuel est une règle sur mesure, posée avant le calcul, qui dit où placer ou ne pas placer quelqu'un : « elle ne peut pas venir dimanche », « ces deux-là ne travaillent pas ensemble ». Le solveur en tient compte à chaque résolution, y compris en repartant de zéro. Un verrouillage est un geste sur un plan déjà calculé : il fige ce qu'une résolution a produit pour que la suivante n'y touche plus ; il ne dit rien de ce qui devrait s'y trouver, et ne gèle jamais une place vide. En pratique : pour imposer ou interdire une affectation, un ajustement ; pour protéger une partie validée, un verrouillage.`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.adHoc.term.indisponibilite:Indisponibilité forcée`,
              text: $localize`:@@aide.adHoc.def.indisponibilite:Interdit à la personne d'occuper un poste dans le périmètre choisi. À distinguer des jours d'indisponibilité de la fiche, qui portent sur une journée entière : ici, vous visez un créneau précis, un stand précis, ou les deux.`,
            },
            {
              term: $localize`:@@aide.adHoc.term.affectation:Affectation forcée`,
              text: $localize`:@@aide.adHoc.def.affectation:Exige qu'au moins un poste du périmètre soit tenu par la personne. Attention : si vous nommez plusieurs animateurs, l'ajustement est satisfait dès que l'un d'eux tient le poste — c'est un « l'un de ces animateurs », jamais un « tous ». Pour imposer deux personnes, saisissez deux ajustements.`,
            },
            {
              term: $localize`:@@aide.adHoc.term.incompatibilite:Incompatibilité`,
              text: $localize`:@@aide.adHoc.def.incompatibilite:Interdit à deux personnes de travailler sur le même créneau. Elle porte sur le créneau, pas sur le stand : deux stands différents à la même heure sont tout autant interdits. Seuls les deux premiers animateurs de la liste sont pris en compte — pour trois personnes, saisissez les trois paires.`,
            },
            {
              term: $localize`:@@aide.adHoc.term.affinite:Affinité (paire à privilégier)`,
              text: $localize`:@@aide.adHoc.def.affinite:Pas une règle dure : une préférence, récompensée chaque fois que les deux personnes tiennent un poste sur le même stand au même créneau. Elle ne force rien.`,
            },
            {
              term: $localize`:@@aide.adHoc.term.arriveeGroupee:Arrivée groupée (covoiturage)`,
              text: $localize`:@@aide.adHoc.def.arriveeGroupee:De 2 à 4 personnes qui arrivent et repartent ensemble. Une préférence, pas une règle dure : le solveur cherche à leur donner les mêmes jours, et des premières arrivées et derniers départs à la tolérance près — réglage « Tolérance d'une arrivée groupée », 30 min par défaut, sur la ligne de la règle dans Règles du planning, onglet Qualité ; au-delà, chaque minute d'écart et chaque jour où un membre travaille sans les autres coûtent. Chacun peut tenir un stand différent. Ni créneau ni stand. Elle naît le plus souvent d'une demande « Je viens avec… » envoyée depuis l'onglet Covoiturage de l'espace et validée sur la page Disponibilités, onglet Covoiturage ; elle se crée aussi à la main ici. Celle qui vient d'une demande validée ne se modifie ni ne se supprime ici : ses boutons sont grisés et mènent à Disponibilités, onglet Covoiturage, où l'annuler prévient le groupe ; celle écrite à la main reste modifiable. Elle est refusée si deux de ses membres forment une incompatibilité sans stand ni créneau : on ne fait pas arriver ensemble deux personnes qu'on refuse de faire travailler ensemble.`,
            },
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.adHoc.perimetre:Le créneau et le stand sont facultatifs, et les laisser vides veut dire « partout » : une indisponibilité forcée sans périmètre écarte la personne de tout l'événement. Un ajustement conserve l'identifiant du créneau visé — si ce créneau disparaît, la colonne Périmètre affiche « créneau supprimé » et l'ajustement ne s'applique plus à rien. Après un découpage ou l'application d'un calendrier, revérifiez ceux qui visaient un créneau.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.adHoc.contradictions:Deux ajustements qui ne peuvent pas tenir ensemble sont refusés à l'enregistrement, avec un message qui les nomme tous les deux — plutôt qu'un planning déclaré infaisable plusieurs minutes plus tard. Cinq situations sont refusées :`,
        },
        {
          kind: 'list',
          items: [
            $localize`:@@aide.adHoc.refus1:la même paire déclarée à la fois incompatible et en affinité ;`,
            $localize`:@@aide.adHoc.refus2:une affectation forcée dont tout le périmètre est couvert par une indisponibilité forcée visant chacun des animateurs qu'elle nomme ;`,
            $localize`:@@aide.adHoc.refus3:deux affectations forcées qui fixent la même personne sur des périmètres se chevauchant dans le temps ;`,
            $localize`:@@aide.adHoc.refus4:deux affectations forcées qui placent sur un même créneau deux personnes déclarées incompatibles ;`,
            $localize`:@@aide.adHoc.refus5:une arrivée groupée dont deux membres sont déclarés incompatibles.`,
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.adHoc.limites:Le contrôle ne refuse que ce qui est certainement impossible, pour ne jamais vous coûter une saisie légitime. Passent donc délibérément une affectation forcée nommant deux animateurs dont un seul est indisponible, une indisponibilité plus étroite que le périmètre forcé, et deux affectations forcées sur le même créneau dont une seule précise un stand. Ces combinaisons peuvent quand même mener à un planning en défaut : c'est la résolution qui tranchera.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.adHoc.chevauchement:Le chevauchement se calcule sur les horaires du créneau tels qu'ils sont saisis, pas sur ce qu'une fermeture de stand en laisse réellement. Deux affectations forcées sur des créneaux qui se recouvrent, mais sur deux stands fermés à des heures complémentaires, seront donc refusées. Dans ce cas, visez des créneaux qui ne se recouvrent pas.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.adHoc.intenable:Trois autres situations sont signalées sans être refusées, parce que ce qui casse l'affectation forcée arrive le plus souvent après elle : tous les animateurs nommés se sont déclarés indisponibles sur tout le périmètre ; aucune place du périmètre ne peut les accueillir au regard d'une règle dure — un mineur la nuit, un jour férié, un stand réservé aux majeurs, un plafond quotidien dépassé ; leur emploi du temps est verrouillé sur tout le périmètre sans qu'ils y tiennent déjà de place. Un message le dit à l'enregistrement, la page Problèmes le reprend comme cause bloquante tant que ça tient, et « Calculer » demande confirmation avant de partir.`,
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
          text: $localize`:@@aide.adHoc.apres:Quand une résolution se termine malgré tout en défaut, la page Problèmes nomme les ajustements que le solveur n'a pas pu honorer, avec le nombre d'écarts de chacun — c'est ce qui distingue « le solveur n'y arrive pas » de « ces trois exceptions-là sont à arbitrer ».`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.adHoc.liste:Le dialogue commence par la personne, puis le type : on pense « Alice », pas « incompatibilité ». Le champ « Personne » au-dessus de la liste ne garde que les ajustements qui nomment quelqu'un — par son nom ou son identifiant ; la fiche d'un animateur y mène filtrée sur lui. L'adresse garde le filtre.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.consignesSolveur.relancer:Après toute saisie sur l'un des trois onglets — un ajustement, un verrou, une consigne —, la page propose « Relancer le calcul » sur place : une correction quand un plan existe, qui ne rouvre que ce que la saisie a invalidé, sinon un calcul complet. Le Solveur compte ces saisies dans « Ce calcul tiendra compte de ».`,
        },
      ],
      links: [
        {
          route: '/consignes-solveur',
          queryParams: { onglet: 'ajustements' },
          label: $localize`:@@consignesSolveur.onglet.ajustements:Ajustements`,
        },
        { route: '/diagnostic', label: $localize`:@@aide.link.problemes:Problèmes` },
        {
          route: '/consignes-solveur',
          queryParams: { onglet: 'verrouillages' },
          label: $localize`:@@consignesSolveur.onglet.verrouillages:Verrouillages`,
        },
      ],
    },
  ];
}
