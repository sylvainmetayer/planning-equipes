// Using the planning once computed: the day-of mode, the views to read it
// by, and the keyboard shortcuts.
//
// One theme of the organisers' guide; `aide-content.ts` assembles the
// themes in reading order. Built lazily, never at module scope: `$localize`
// only resolves once `main.ts` has loaded the translation catalog.

import { buildRaccourcisNavigation } from '../../../core/keyboard-shortcuts';
import { HelpSection } from '../help-section';

export function buildOperationsSections(): HelpSection[] {
  return [
    {
      id: 'jour-j',
      icon: 'emergency',
      title: $localize`:@@aide.jourJ.title:Mode jour J`,
      summary: $localize`:@@aide.jourJ.summary:Quelqu'un ne s'est pas présenté : le marquer absent, trouver un remplaçant, appliquer — sans relancer de calcul.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.jourJ.intro:Tous les autres écrans travaillent en amont de l'événement. Celui-ci est pensé pour le jour même, debout dans l'allée, sur un téléphone : peu de clics, de grandes cibles, et les trois étapes dans l'ordre du geste — qui manque, quelles places cela ouvre, qui peut les reprendre.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.jourJ.essai:Cet écran est en cours de développement, et ses impacts ne sont pas encore garantis. Il se distingue des autres écrans à l'essai sur un point qui compte : il agit. Marquer un absent écrit de vraies indisponibilités et vide de vrais sièges du planning enregistré, tout de suite. Aucune résolution ne revérifie l'ensemble entre-temps : la cohérence globale n'est établie qu'à la prochaine que vous lancerez. Utilisez-le en le sachant, et relancez une résolution dès que la situation le permet.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.jourJ.portee:Marquer quelqu'un absent le déclare indisponible sur les créneaux restants de la journée, et sur ceux-là seulement. Un créneau est restant tant qu'il n'est pas terminé — celui qui est en cours en fait partie, c'est justement celui où personne n'est au poste. Les créneaux déjà passés ne sont jamais touchés : la personne les a réellement tenus, et le planning doit continuer à le dire.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.jourJ.journee:La journée commence à son premier créneau, pas à minuit : un festival commence quand le premier stand ouvre. Une soirée qui se prolonge après minuit reste donc la même journée, et à une heure du matin l'écran affiche encore celle de la veille tant que son dernier créneau tourne. Un créneau de 22h à 2h appartient au soir qui l'ouvre, et à lui seul. Quand plus rien ne tourne — entre deux journées, ou sur une date sans aucun créneau programmé — l'écran le dit et n'invente pas de journée.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.jourJ.refus:L'absence est appliquée en entier ou pas du tout. Elle est refusée, sans rien écrire, si elle contredit une affectation forcée sur l'un des créneaux visés — le message nomme les deux ajustements — ou si l'un des postes à libérer est verrouillé : levez le verrou d'abord.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.jourJ.suggestions:Pour chaque place ouverte, l'assistant propose les remplaçants viables, du meilleur au moins bon, et n'en propose aucun qui casserait une règle dure. Il s'arrête aux vingt premiers candidats éligibles, parce que chacun coûte une analyse complète du planning : l'écran affiche toujours combien ont été évalués sur combien d'éligibles, pour qu'une liste écourtée ne se lise pas comme « il n'y a personne d'autre ». Appliquer une suggestion réaffecte ce seul siège, sans relancer de résolution.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.jourJ.publication:Rien n'est envoyé aux animateurs depuis cet écran : leur espace continue d'afficher le planning publié tant que vous n'avez pas republié. Le bandeau rappelle combien de personnes attendent un changement et renvoie vers le bouton Publier, sur la page Solveur — un envoi à cent cinquante personnes à portée de pouce dans un écran d'urgence se paierait cher.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.jourJ.trace:Chaque absence marquée est un ajustement manuel enregistré, avec sa raison, son auteur et son horodatage : elle se retrouve telle quelle le lendemain sur la page Ajustements manuels. Elle s'annule créneau par créneau ou d'un bloc — mais les postes déjà réaffectés ne reviennent pas d'eux-mêmes : qui tient un poste reste une décision.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.jourJ.mock:Cet écran ne parle que d'aujourd'hui, ce qui le rend difficile à découvrir hors de la période de l'événement. En développement seulement, la page Débogage permet donc de figer la date que le serveur considère comme aujourd'hui ; une icône d'avertissement apparaît alors dans la barre du haut, sur tous les écrans, et son lien ramène directement au champ pour la modifier ou l'effacer. L'espace animateur la suit aussi : son repère du jour se lit sur la date figée, et sa barre du haut l'affiche. Sur une instance déployée, le réglage n'existe pas et le serveur refuse de le poser : l'écran y lit toujours l'horloge réelle.`,
        },
      ],
      links: [
        { route: '/jour-j', label: $localize`:@@nav.link.jourJ:Mode jour J` },
        {
          route: '/ad-hoc-constraints',
          label: $localize`:@@nav.link.adHocConstraints:Ajustements manuels`,
        },
        { route: '/verrouillages', label: $localize`:@@nav.link.verrouillages:Verrouillages` },
      ],
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
              text: $localize`:@@aide.views.def.calendar:Vue mensuelle avec filtres par animateur et par stand, et détail des affectations au clic sur une journée. Pour se repérer dans l'ensemble de l'événement.`,
            },
            {
              term: $localize`:@@aide.views.term.day:Calendrier journalier`,
              text: $localize`:@@aide.views.def.day:Une journée, stand par stand et créneau par créneau. Pour vérifier une journée précise ou préparer une réparation manuelle. On y corrige aussi à la main, en glissant un nom vers un autre stand de la journée : sur un siège libre la personne y est déplacée et son siège se vide, sur une personne les deux échangent leurs sièges. Le serveur simule le geste sur le plan enregistré et le refuse, en nommant la règle, s'il cassait une règle dure ; un siège verrouillé ne se déplace pas. Le clic sur un nom reste le chemin au clavier : « Pourquoi lui ? » et son assistant de réparation. Le déplacement se prend par la poignée ⠿ à gauche du nom : le nom lui-même reste sélectionnable, et sur écran tactile le doigt continue de faire défiler la journée. Au clavier, « Pourquoi lui ? » et l'assistant de réparation donnent un siège à quelqu'un sans glisser ; échanger deux personnes ou viser un siège libre précis n'a pas d'équivalent au clavier aujourd'hui. Le glisser-déposer est en cours de test : vérifiez le planning après un déplacement.`,
            },
            {
              term: $localize`:@@aide.views.term.heatmap:Heatmap de charge`,
              text: $localize`:@@aide.views.def.heatmap:Jour croisé avec le stand (places pourvues sur places requises : les trous de couverture) ou avec l'animateur (postes par jour : les surcharges). Pour repérer un déséquilibre d'un coup d'œil.`,
            },
            {
              term: $localize`:@@aide.views.term.marge:Marge disponible`,
              text: $localize`:@@aide.views.def.marge:Journée croisée avec la tranche horaire, et dans chaque case ce qui reste : les animateurs disponibles à ce moment-là moins les sièges à pourvoir. Rouge en dessous de zéro, vert au-dessus. Deux lectures : « avant résolution » compare la capacité brute — qui n'a pas déclaré cette date indisponible — aux sièges qu'une résolution devrait pourvoir, et répond donc avant toute résolution ; « après résolution » lit le planning enregistré et ne compte libre que celui qui n'est pas déjà en poste à cette heure-là, qui a eu sa pause légale entre deux vacations et qu'aucune règle dure n'écarte du siège — face aux seuls sièges restés vides. Les tranches sont les créneaux de la grille, donc la vue se lit aussi bien en amplitudes qu'en vacations. Une case mène là où on agit : le banc de touche du créneau après résolution, les ouvertures de la journée avant. Sous la grille, la pire tranche de chaque journée. La lecture est optimiste : une case annoncée négative l'est, une case confortable ne le garantit pas — elle ignore plafonds hebdomadaires, repos quotidien et compétences.`,
            },
            {
              term: $localize`:@@aide.views.term.timeline:Timeline animateur`,
              text: $localize`:@@aide.views.def.timeline:Le planning d'une personne : stands à couvrir, amplitude journalière, vacations et pauses entre elles. C'est la vue à envoyer à l'intéressé, exportable en PDF ou en ICS.`,
            },
            {
              term: $localize`:@@aide.views.term.railJour:Rail de la journée`,
              text: $localize`:@@aide.views.def.railJour:La même journée que le calendrier journalier, mais vue par personne : une ligne par animateur, les vacations placées dans le temps. Les trous, les amplitudes et les enchaînements sautent aux yeux, et les lignes vides disent qui reste mobilisable — celles marquées « indisponible » signalent au contraire de ne pas solliciter la personne. Une vacation se glisse vers une autre ligne : la personne la prend, ou échange la sienne si elle travaille déjà à cette heure, aux mêmes conditions que sur le calendrier journalier.`,
            },
            {
              term: $localize`:@@aide.views.term.repos:Jours de repos`,
              text: $localize`:@@aide.views.def.repos:L'inverse des autres vues : non pas qui est où, mais qui souffle. Une ligne par animateur, une colonne par journée de l'événement, et trois états seulement — journée travaillée (avec le nombre d'heures), jour de repos, indisponible. Un jour de repos est une journée que la personne pouvait faire et sur laquelle le planning ne l'a pas affectée ; une journée qu'elle avait déclarée indisponible n'en est pas un, elle n'était pas mobilisable. Les lignes les plus tendues sont en haut : celles qui enchaînent le plus de jours travaillés d'affilée, celles qui n'ont aucune journée libre. La colonne « Série » donne cette plus longue série, et l'histogramme en tête d'écran dit, jour par jour, quelle part de l'effectif se repose — donc combien restent à appeler. Deux affichages : la grille, une colonne par jour, qui montre les heures travaillées quand on lui demande la densité « Heures » ; et la frise, une barre par animateur découpée en séries de jours, qui tient sur un écran même sur un événement d'un mois. Une case rouge signale une affectation posée sur une journée déclarée indisponible : une anomalie du plan, à corriger.`,
            },
            {
              term: $localize`:@@aide.views.term.carteJour:Carte de la journée`,
              text: $localize`:@@aide.views.def.carteJour:Encore la même journée, mais sur la carte des emplacements : un curseur temporel, et chaque emplacement coloré par ce que le planning enregistré dit qu'il s'y passe à cet instant — ouvert et entièrement pourvu, ouvert avec des places vides, ou ouvert sans personne. Rien n'est recalculé : un stand est ouvert quand le plan porte un poste qui couvre cet instant. Les stands rattachés à aucun emplacement géolocalisé sont listés à côté de la carte plutôt qu'escamotés.`,
            },
            {
              term: $localize`:@@aide.views.term.graphe:Graphe`,
              text: $localize`:@@aide.views.def.graphe:La même donnée prise par le terrain : un emplacement, les stands qui s'y trouvent, les créneaux sur lesquels ils sont armés, qui y est affecté. Une colonne par niveau, on clique pour ouvrir la suivante. C'est la vue de qui connaît le plan de l'événement mieux que la liste de ses stands. Les deux dernières colonnes se lisent sur le planning enregistré et restent vides tant qu'aucune résolution n'a tourné ; les emplacements et leurs stands, eux, viennent des données de référence et se parcourent tout de suite.`,
            },
            {
              term: $localize`:@@aide.views.term.kpi:Autopsie du planning`,
              text: $localize`:@@aide.views.def.kpi:Une ligne par résolution terminée, toutes éditions confondues : score, couverture, dispersion des heures, nombre de modifications manuelles, durée. C'est la mémoire des campagnes passées — elle dit si l'édition en cours se règle mieux ou moins bien que la précédente, et combien de reprises à la main il a fallu. Rien n'y est nominatif, et l'historique survit à la suppression de l'édition qu'il décrit.`,
            },
            {
              term: $localize`:@@aide.views.term.comparateur:Comparateur A/B`,
              text: $localize`:@@aide.views.def.comparateur:Deux plannings côte à côte — deux instantanés, ou un instantané et le planning actuel — sur le score, la couverture, l'équité et les écarts aux règles, avec le sens de chaque différence écrit en toutes lettres. Les instantanés de toutes les éditions sont proposés : c'est ainsi qu'on compare une variante (canicule, repli) à l'édition nominale. L'écran prévient quand les deux plannings n'ont pas la même taille ou ne viennent pas de la même édition : une partie de l'écart vient alors du problème posé, pas de la qualité de la résolution. Comparer ne lance jamais de calcul.`,
            },
            {
              term: $localize`:@@aide.views.term.banc:Banc de touche`,
              text: $localize`:@@aide.views.def.banc:Pour un créneau, qui n'est affecté nulle part — et, pour chacun, la règle qui l'empêcherait de prendre la place restée libre : indisponible ce jour-là, repos légal, plafond d'heures atteint, appréciation manquante. Toutes les raisons applicables sont affichées, pas seulement la première : c'est ce qui dit si lever un obstacle suffirait. La vue est en lecture seule ; pour agir, passez par l'assistant de réparation du calendrier journalier. Elle lit le planning enregistré et ne propose donc que les créneaux qu'il pourvoit : un créneau où aucun stand n'est ouvert n'y figure pas, et tant qu'aucune résolution n'a tourné l'écran le dit au lieu de rester vide.`,
            },
            {
              term: $localize`:@@aide.views.term.hours:Heures et besoin en animateurs`,
              text: $localize`:@@aide.views.def.hours:Les heures travaillées par animateur d'un côté, l'estimation du nombre minimum d'animateurs à recruter de l'autre. La seconde se calcule avant toute résolution, à partir des seuls stands et créneaux : quatre bornes, dont la plus grande est retenue — le pic de sièges simultanés, le même pic prolongé de la pause légale, la charge horaire de la semaine ISO la plus chargée rapportée à ce qu'une personne peut y travailler, et la rotation sur les jours (nul ne travaille plus de six jours dans la même semaine, donc sept journées d'affilée exigeant cent personnes ne se tiennent pas à cent). Chacune est un plancher prouvé, jamais une cible : un événement réellement couvert par 153 personnes peut n'avoir qu'un plancher de 117, parce qu'un planning réel répartit le travail loin en dessous des plafonds légaux. Elle se lit aussi typologie par typologie, en bas de la page : une typologie dont le minimum dépasse le nombre d'animateurs qui la déclarent est le goulot — il n'y manque pas des animateurs en général, mais des animateurs compétents sur ce jeu-là. Deux règles rendent cette lecture honnête : un siège n'est compté pour une typologie que si son stand ne propose qu'elle, et un polyvalent ne compte comme spécialiste que dans les compétences qu'il déclare — ailleurs il apparaît à part, comme un renfort mobilisable sur n'importe quelle typologie mais sur un siège à la fois.`,
            },
            {
              term: $localize`:@@aide.views.term.equite:Équité`,
              text: $localize`:@@aide.views.def.equite:Ce que chacun a reçu, pour arbitrer avant de publier et répondre après — « j'ai trois nocturnes et lui aucune », « je n'ai jamais eu le jeu que j'avais demandé ». Une ligne par animateur affecté du planning enregistré : heures totales et par semaine, heures de soirée (à partir de l'heure réglée dans les paramètres légaux, jusqu'à la fin d'un poste qui franchit minuit, et jusqu'à 6 h pour un poste commencé après minuit), de week-end et de jour férié (celles-là comptées en entier sur la date du créneau, sans prorata : un poste du vendredi 20 h au samedi 2 h compte zéro heure de week-end), postes et postes pénibles (stand épuisant ou premium), stands, typologies et emplacements distincts, part des postes sur une typologie souhaitée ou appréciée, jours travaillés, jours de repos et plus longue série. À côté de chaque valeur, son écart à la médiane de la colonne : vert au-dessus, rouge en dessous ; le pied de tableau donne la médiane, le minimum, le maximum et l'écart-type. Le tableau se trie par colonne, se filtre par nom, s'exporte en CSV, et un clic sur un nom ouvre la timeline de la personne. L'icône d'en-tête et la légende disent quelles colonnes une règle du solveur mesure et si elle est active : les heures de soirée, de week-end ou de jour férié ne sont pesées par aucune règle — l'écran le dit, il ne corrige pas.`,
            },
            {
              term: $localize`:@@aide.views.term.fragilite:Fragilité du planning`,
              text: $localize`:@@aide.views.def.fragilite:Qui est irremplaçable. Pour chaque personne, les créneaux qui passeraient sous l'effectif minimum si elle se désiste — et, colonne décisive, ceux que personne d'autre ne pourrait reprendre ce jour-là. La seconde vue liste les stands tenus par une seule personne compétente pour leurs typologies, ce qui désigne où recruter ou former. Les polyvalents « ninja » y sont comptés à part, en renforts : ils peuvent dépanner partout, mais un stand qu'ils sont seuls à pouvoir tenir n'a toujours aucun spécialiste. Rien n'est recalculé par le solveur : l'écran lit le planning enregistré.`,
            },
            {
              term: $localize`:@@aide.views.term.pauses:Pauses`,
              text: $localize`:@@aide.views.def.pauses:La rotation des pauses légales, jour par jour et stand par stand : qui sort de quelle heure à quelle heure, une personne à la fois par stand, et qui est là pour relayer. Le solveur ne planifie pas la pause de vingt minutes due à la sixième heure (trente à quatre heures et demie pour un mineur) ; quand elle est déclarée prise sur le poste, l'écran la pose au plus tard possible avant la sixième heure, après celle du collègue. Les trous déjà planifiés par la grille sont listés à part. Une seconde section liste les coupures repas : qui en doit une, dans quelle fenêtre, et combien de minutes manquent quand la grille ne lui laisse pas de place — les journées en rouge sont celles que le solveur refuse. Chaque animateur retrouve ses pauses sur son propre planning.`,
            },
          ],
        },
      ],
      links: [
        { route: '/calendar', label: $localize`:@@nav.link.calendar:Calendrier des affectations` },
        { route: '/heatmap', label: $localize`:@@nav.link.heatmap:Heatmap de charge` },
        { route: '/marge', label: $localize`:@@nav.link.marge:Marge disponible` },
        { route: '/timeline', label: $localize`:@@nav.link.timeline:Timeline animateur` },
        {
          route: '/journee',
          queryParams: { vue: 'rail' },
          label: $localize`:@@aide.link.railJour:Rail de la journée`,
        },
        {
          route: '/journee',
          queryParams: { vue: 'carte' },
          label: $localize`:@@aide.link.carteJour:Carte de la journée`,
        },
        { route: '/hours', label: $localize`:@@nav.link.hours:Heures` },
        { route: '/equite', label: $localize`:@@nav.link.equite:Équité` },
        { route: '/repos', label: $localize`:@@nav.link.repos:Jours de repos` },
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
        {
          route: '/diagnostic',
          queryParams: { onglet: 'banc' },
          label: $localize`:@@aide.link.bancDeTouche:Banc de touche`,
        },
        { route: '/graphe', label: $localize`:@@nav.link.graphe:Graphe` },
        { route: '/kpi', label: $localize`:@@nav.link.kpi:Autopsie du planning` },
        { route: '/comparateur', label: $localize`:@@nav.link.comparateur:Comparateur A/B` },
      ],
    },
    {
      id: 'raccourcis-clavier',
      icon: 'keyboard',
      title: $localize`:@@aide.shortcuts.title:Raccourcis clavier`,
      summary: $localize`:@@aide.shortcuts.summary:Se déplacer d'un écran à l'autre et retrouver un animateur sans lâcher le clavier.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.shortcuts.intro:Ctrl+K ouvre la palette de commandes : une seule zone de saisie qui mène à n'importe quelle page de l'application et qui cherche aussi dans les données de référence — un animateur (sa timeline s'ouvre), un stand ou un créneau (le calendrier s'ouvre dessus). Les flèches parcourent la liste, Entrée ouvre, Échap referme. C'est le point d'entrée à retenir : tous les autres raccourcis ne sont que des abrégés de ce qu'elle sait déjà faire.`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.shortcuts.term.palette:Ctrl+K`,
              text: $localize`:@@aide.shortcuts.def.palette:Ouvre (et referme) la palette de commandes. Le navigateur ne reçoit pas ce raccourci tant qu'un onglet de l'application est au premier plan.`,
            },
            {
              term: $localize`:@@aide.shortcuts.term.help:?`,
              text: $localize`:@@aide.shortcuts.def.help:Affiche la liste complète des raccourcis, sans quitter l'écran en cours.`,
            },
            {
              term: $localize`:@@aide.shortcuts.term.filter:/`,
              text: $localize`:@@aide.shortcuts.def.filter:Place le curseur dans le filtre de la page courante, quand elle en a un (les pages de données de référence, et cette page d'aide).`,
            },
            {
              term: $localize`:@@aide.shortcuts.term.submit:Ctrl+Entrée`,
              text: $localize`:@@aide.shortcuts.def.submit:Valide le formulaire en cours de saisie, comme un clic sur son bouton d'enregistrement. Un formulaire dont le bouton est désactivé (une saisie incomplète) n'est pas enregistré pour autant : le clavier ne fait rien que la souris ne ferait.`,
            },
            {
              term: $localize`:@@aide.shortcuts.term.escape:Échap`,
              text: $localize`:@@aide.shortcuts.def.escape:Ferme la fenêtre ouverte (palette, formulaire, confirmation) et rend le focus à l'endroit d'où elle a été ouverte.`,
            },
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.shortcuts.go:Pour aller directement sur un écran, tapez « g » puis la lettre de la destination — « g » puis « a » pour les animateurs. Les pages qui n'ont pas de lettre restent atteignables par la palette, qui les liste toutes.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.shortcuts.menuMode:Le menu latéral s'ouvre en mode simple : une quinzaine d'écrans spécialisés n'y figurent pas — les vues d'analyse (rail et carte de la journée, heatmap de charge, marge disponible, timeline animateur, jours de repos, pauses), les écrans de diagnostic approfondi (fragilité du planning, banc de touche, contraintes, instantanés, comparateur A/B, autopsie du planning, graphe) et les outils techniques (historique, MCP, débogage). « Menu simple », en tête du menu, bascule vers le menu avancé qui affiche tout, et ce navigateur retient le choix. Un écran masqué reste atteignable par la palette, par un lien de l'aide ou par son adresse : il apparaît alors dans le menu le temps de la visite.`,
        },
        {
          kind: 'definitions',
          items: buildRaccourcisNavigation().map((raccourci) => ({
            term: `g ${raccourci.touche}`,
            text: raccourci.label,
          })),
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.shortcuts.tables:Les tableaux de données de référence (animateurs, stands, créneaux, emplacements, typologies) se parcourent aussi au clavier. Le chemin le plus court pour y entrer : « / » place le curseur dans le filtre de la page, tapez de quoi réduire la liste, puis Flèche bas saute directement sur la première ligne. Tab y entre également, sur une seule ligne — jamais ligne par ligne — mais il traverse d'abord les commandes de l'en-tête (la case « tout sélectionner » et chaque en-tête triable). Les flèches déplacent ensuite le focus d'un cran ; Tab ressort de la ligne vers ses boutons, puis vers le reste de la page.`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.shortcuts.term.rowEnter:Flèche bas depuis le filtre`,
              text: $localize`:@@aide.shortcuts.def.rowEnter:Entre dans le tableau sans compter les tabulations : le focus saute sur la ligne courante, celle qu'un contour marque. C'est le geste à retenir — filtrer, puis descendre. Un clic sur une ligne la focalise de la même façon, et les flèches enchaînent aussitôt.`,
            },
            {
              term: $localize`:@@aide.shortcuts.term.rowMove:Flèches haut et bas`,
              text: $localize`:@@aide.shortcuts.def.rowMove:Passent d'une ligne à l'autre. Début et Fin sautent à la première et à la dernière ligne affichée. Le focus suit la ligne, pas son rang : changer le tri ne le fait pas sauter ailleurs, et filtrer la ligne focalisée le repose sur celle qui prend sa place.`,
            },
            {
              term: $localize`:@@aide.shortcuts.term.rowOpen:Entrée`,
              text: $localize`:@@aide.shortcuts.def.rowOpen:Ouvre la ligne focalisée : sa fiche de consultation, ou directement son formulaire sur les créneaux, qui n'ont pas de fiche. Comme les boutons de la ligne, la touche reste sans effet tant qu'une résolution verrouille l'édition.`,
            },
            {
              term: $localize`:@@aide.shortcuts.term.rowSelect:Espace`,
              text: $localize`:@@aide.shortcuts.def.rowSelect:Coche ou décoche la ligne focalisée. La barre d'actions groupées apparaît dès la première ligne cochée, exactement comme avec la souris.`,
            },
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.shortcuts.guard:Aucun raccourci à une touche ne se déclenche pendant que vous saisissez du texte : tant que le curseur est dans un champ, « g », « / » et « ? » restent des caractères ordinaires. Ils reprennent dès que le focus quitte le champ.`,
        },
      ],
      links: [
        { route: '/animateurs', label: $localize`:@@nav.link.animateurs:Animateurs` },
        { route: '/timeline', label: $localize`:@@nav.link.timeline:Timeline animateur` },
        { route: '/calendar', label: $localize`:@@nav.link.calendar:Calendrier des affectations` },
      ],
    },
  ];
}
