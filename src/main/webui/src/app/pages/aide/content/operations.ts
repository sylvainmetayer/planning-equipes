// Using the planning once computed: the review pass, the day-of mode, the
// views to read it by, and the keyboard shortcuts.
//
// One theme of the organisers' guide; `aide-content.ts` assembles the
// themes in reading order. Built lazily, never at module scope: `$localize`
// only resolves once `main.ts` has loaded the translation catalog.

import { buildRaccourcisNavigation } from '../../../core/keyboard-shortcuts';
import { HelpSection } from '../help-section';

export function buildOperationsSections(): HelpSection[] {
  return [
    {
      id: 'relecture',
      icon: 'fact_check',
      title: $localize`:@@aide.relecture.title:Relire le planning avant de publier`,
      summary: $localize`:@@aide.relecture.summary:Marquer où vous en êtes de votre relecture, journée par journée — sans rien figer.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.relecture.intro:Entre le premier planning qui tient et l'envoi aux animateurs, il y a une relecture : on ouvre les journées une par une, on regarde, on corrige. Sur douze journées, la vraie question est « où en étais-je ? ». Sur la page Journée, « Marquer relu et accepté » enregistre votre passage, avec la date et un commentaire si vous en laissez un. Un bandeau dit ensuite « 3 journées sur 12 ».`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.relecture.journeeEntiere:La validation porte toujours sur la journée entière. Les filtres de la page changent ce que vous regardez, pas ce que vous acceptez. Pour relire stand par stand, filtrez, parcourez, puis validez la journée une fois le tour fait.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.relecture.verrou:Accepter une journée ne la fige pas. Un verrouillage empêche le solveur de toucher à des sièges ; une validation dit qu'un humain a relu, et laisse le calcul améliorer le reste. La case « Verrouiller aussi cette journée » est proposée à côté parce que les deux gestes vont souvent ensemble, jamais parce que l'un impliquerait l'autre.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.relecture.prerequis:Avant d'accepter, l'écran affiche ce qu'il sait de cette journée : écarts durs, sièges sans animateur, pauses sans relais, postes reposant sur quelqu'un d'irremplaçable. Ce sont les chiffres des écrans Problèmes, Pauses et Fragilité, filtrés sur la date. Aucun ne bloque : si vous savez pourquoi un siège reste vide, acceptez et écrivez-le dans le commentaire.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.relecture.resolution:Une résolution qui déplace un siège d'une journée acceptée retire cette validation, et le récapitulatif le dit : personne n'a relu ce que le calcul vient d'écrire. Une journée aussi verrouillée garde sa validation, puisque rien n'a pu y bouger. Au moment de publier, le panneau de diffusion rappelle combien de journées non relues partiraient.`,
        },
      ],
      links: [
        { route: '/journee', label: $localize`:@@nav.link.journee:Journée` },
        { route: '/verrouillages', label: $localize`:@@nav.link.verrouillages:Verrouillages` },
      ],
    },
    {
      id: 'jour-j',
      icon: 'emergency',
      title: $localize`:@@aide.jourJ.title:Mode jour J`,
      summary: $localize`:@@aide.jourJ.summary:Quelqu'un ne s'est pas présenté : le marquer absent, trouver un remplaçant, appliquer — sans relancer de calcul.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.jourJ.intro:Tous les autres écrans travaillent en amont. Celui-ci est pensé pour le jour même, debout dans l'allée, sur un téléphone : peu de clics, de grandes cibles, et trois étapes dans l'ordre du geste — qui manque, quelles places cela ouvre, qui peut les reprendre.`,
        },
        {
          kind: 'callout',
          title: $localize`:@@aide.jourJ.callout.title:Cet écran agit vraiment`,
          text: $localize`:@@aide.jourJ.callout.text:Il est encore en cours de développement, et il se distingue des autres écrans à l'essai sur un point : marquer un absent écrit de vraies indisponibilités et vide de vrais sièges du planning enregistré, tout de suite. Aucune résolution ne revérifie l'ensemble entre-temps. Relancez-en une dès que la situation le permet.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.jourJ.portee:Marquer quelqu'un absent le déclare indisponible sur les créneaux restants de la journée, et sur ceux-là seulement. Un créneau est restant tant qu'il n'est pas terminé, celui en cours compris. Les créneaux passés ne sont jamais touchés : la personne les a réellement tenus.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.jourJ.journee:La journée commence à son premier créneau, pas à minuit. Une soirée qui se prolonge après minuit reste donc la même journée, et à une heure du matin l'écran affiche encore celle de la veille tant que son dernier créneau tourne. Quand plus rien ne tourne, l'écran le dit et n'invente pas de journée.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.jourJ.refus:L'absence est appliquée en entier ou pas du tout. Elle est refusée, sans rien écrire, si elle contredit une affectation forcée — le message nomme les deux ajustements — ou si l'un des postes à libérer est verrouillé.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.jourJ.suggestions:Pour chaque place ouverte, l'assistant propose les remplaçants viables, du meilleur au moins bon, et n'en propose aucun qui casserait une règle dure. Il s'arrête aux vingt premiers candidats éligibles et affiche toujours combien il en a évalués, pour qu'une liste écourtée ne se lise pas comme « il n'y a personne d'autre ». Appliquer une suggestion réaffecte ce seul siège.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.jourJ.publication:Rien n'est envoyé aux animateurs depuis cet écran : leur espace continue d'afficher le planning publié tant que vous n'avez pas republié. Le bandeau rappelle combien de personnes attendent un changement et renvoie vers le bouton Publier, sur la page Solveur.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.jourJ.trace:Chaque absence marquée est un ajustement manuel enregistré, avec sa raison, son auteur et son horodatage : elle se retrouve le lendemain sur la page Ajustements manuels. Elle s'annule créneau par créneau ou d'un bloc, mais les postes déjà réaffectés ne reviennent pas d'eux-mêmes.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.jourJ.mock:Cet écran ne parle que d'aujourd'hui, ce qui le rend difficile à découvrir hors période. En développement, ou sur un serveur de recette qui l'autorise, la page Débogage permet de figer la date et l'heure que le serveur considère comme maintenant ; une icône d'avertissement apparaît alors dans la barre du haut et ramène au champ pour la modifier. L'espace animateur suit la même horloge. Sur une instance de production, le réglage n'existe pas.`,
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
          kind: 'paragraph',
          text: $localize`:@@aide.views.intro:Toutes ces vues lisent le planning enregistré ; aucune ne lance de calcul. Tant qu'aucune résolution n'a tourné, elles le disent au lieu de rester vides.`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.views.term.calendar:Calendrier des affectations`,
              text: $localize`:@@aide.views.def.calendar:Vue mensuelle avec filtres par animateur et par stand, détail au clic sur une journée. Pour se repérer dans l'ensemble de l'événement.`,
            },
            {
              term: $localize`:@@aide.views.term.day:Calendrier journalier`,
              text: $localize`:@@aide.views.def.day:Une journée, stand par stand et créneau par créneau. C'est là qu'on corrige à la main : glissez un nom, par la poignée à sa gauche, vers un autre stand de la journée — sur un siège libre la personne est déplacée, sur une personne les deux échangent. Le serveur simule le geste et le refuse, en nommant la règle, s'il cassait une règle dure ; un siège verrouillé ne bouge pas. Au clavier, le clic sur un nom ouvre « Pourquoi lui ? » et son assistant de réparation. Le glisser-déposer est en cours de test : vérifiez le planning après un déplacement.`,
            },
            {
              term: $localize`:@@aide.views.term.railJour:Rail de la journée`,
              text: $localize`:@@aide.views.def.railJour:La même journée vue par personne : une ligne par animateur, les vacations placées dans le temps. Les trous, les amplitudes et les enchaînements sautent aux yeux, et les lignes vides disent qui reste mobilisable — celles marquées « indisponible » disent de ne pas solliciter la personne. Une vacation se glisse vers une autre ligne, aux mêmes conditions que sur le calendrier journalier.`,
            },
            {
              term: $localize`:@@aide.views.term.carteJour:Carte de la journée`,
              text: $localize`:@@aide.views.def.carteJour:La même journée sur la carte des emplacements : un curseur temporel, et chaque emplacement coloré selon ce qui s'y passe à cet instant — ouvert et pourvu, ouvert avec des places vides, ou ouvert sans personne. Les stands sans emplacement géolocalisé sont listés à côté de la carte.`,
            },
            {
              term: $localize`:@@aide.views.term.pauses:Pauses`,
              text: $localize`:@@aide.views.def.pauses:La rotation des pauses légales, jour par jour et stand par stand : qui sort de quelle heure à quelle heure, une personne à la fois, et qui relaie. Le solveur ne planifie pas la pause de vingt minutes due à la sixième heure ; quand elle est déclarée prise sur le poste, l'écran la pose au plus tard possible. Une seconde section liste les coupures repas et combien de minutes manquent quand la grille ne laisse pas de place — les journées en rouge sont celles que le solveur refuse.`,
            },
            {
              term: $localize`:@@aide.views.term.heatmap:Heatmap de charge`,
              text: $localize`:@@aide.views.def.heatmap:Jour croisé avec le stand (places pourvues sur places requises : les trous de couverture) ou avec l'animateur (postes par jour : les surcharges). Pour repérer un déséquilibre d'un coup d'œil.`,
            },
            {
              term: $localize`:@@aide.views.term.marge:Marge disponible`,
              text: $localize`:@@aide.views.def.marge:Journée croisée avec la tranche horaire, et dans chaque case les animateurs disponibles moins les sièges à pourvoir. Rouge en dessous de zéro, vert au-dessus. « Avant résolution » compare la capacité brute aux sièges à pourvoir et répond donc sans aucun calcul ; « après résolution » ne compte libre que celui qui n'est pas déjà en poste et qu'aucune règle dure n'écarte, face aux seuls sièges restés vides. La lecture est optimiste : une case négative l'est vraiment, une case confortable ne garantit rien.`,
            },
            {
              term: $localize`:@@aide.views.term.timeline:Timeline animateur`,
              text: $localize`:@@aide.views.def.timeline:Le planning d'une personne : stands, amplitude journalière, vacations et pauses. C'est la vue à envoyer à l'intéressé, exportable en PDF ou en ICS.`,
            },
            {
              term: $localize`:@@aide.views.term.repos:Jours de repos`,
              text: $localize`:@@aide.views.def.repos:L'inverse des autres vues : non pas qui est où, mais qui souffle. Une ligne par animateur, une colonne par journée, trois états — journée travaillée, jour de repos, indisponible. Un jour de repos est une journée que la personne pouvait faire et sur laquelle le planning ne l'a pas affectée. Les lignes les plus tendues remontent en haut, la colonne « Série » donne la plus longue série travaillée, et l'histogramme dit jour par jour quelle part de l'effectif se repose. Une case rouge signale une affectation posée sur une journée déclarée indisponible : une anomalie à corriger.`,
            },
            {
              term: $localize`:@@aide.views.term.equite:Équité`,
              text: $localize`:@@aide.views.def.equite:Ce que chacun a reçu, pour arbitrer avant de publier et répondre après — « j'ai trois nocturnes et lui aucune ». Une ligne par animateur affecté : heures totales et par semaine, heures de soirée, de week-end et de jour férié, postes et postes pénibles, stands, typologies et emplacements distincts, part des postes sur une typologie souhaitée, jours travaillés, jours de repos, plus longue série. À côté de chaque valeur, son écart à la médiane. Le tableau se trie, se filtre et s'exporte en CSV. La légende dit quelles colonnes une règle du solveur mesure vraiment : les heures de soirée, de week-end et de jour férié ne sont pesées par aucune — l'écran les montre, il ne les corrige pas.`,
            },
            {
              term: $localize`:@@aide.views.term.hours:Heures et besoin en animateurs`,
              text: $localize`:@@aide.views.def.hours:Les heures travaillées par animateur d'un côté, l'estimation du nombre minimum d'animateurs à recruter de l'autre. La seconde se calcule avant toute résolution, à partir des seuls stands et créneaux : quatre bornes, dont la plus grande est retenue — le pic de sièges simultanés, ce pic prolongé de la pause légale, la charge de la semaine la plus lourde, et la rotation sur les jours. Chacune est un plancher prouvé, jamais une cible. Elle se lit aussi typologie par typologie : une typologie dont le minimum dépasse le nombre d'animateurs qui la déclarent est le goulot.`,
            },
            {
              term: $localize`:@@aide.views.term.fragilite:Fragilité du planning`,
              text: $localize`:@@aide.views.def.fragilite:Qui est irremplaçable. Pour chaque personne, les créneaux qui passeraient sous l'effectif minimum si elle se désiste — et, colonne décisive, ceux que personne d'autre ne pourrait reprendre ce jour-là. La seconde vue liste les stands tenus par une seule personne compétente, ce qui désigne où recruter ou former. Les polyvalents « ninja » y sont comptés à part, en renforts.`,
            },
            {
              term: $localize`:@@aide.views.term.banc:Banc de touche`,
              text: $localize`:@@aide.views.def.banc:Pour un créneau, qui n'est affecté nulle part — et, pour chacun, ce qui l'empêcherait de prendre la place libre : indisponible ce jour-là, repos légal, plafond d'heures atteint, appréciation manquante. Toutes les raisons applicables sont affichées, pas seulement la première : c'est ce qui dit si lever un obstacle suffirait. La vue est en lecture seule ; pour agir, passez par l'assistant de réparation.`,
            },
            {
              term: $localize`:@@aide.views.term.graphe:Graphe`,
              text: $localize`:@@aide.views.def.graphe:La même donnée prise par le terrain : un emplacement, les stands qui s'y trouvent, les créneaux où ils sont armés, qui y est affecté. Une colonne par niveau, on clique pour ouvrir la suivante. Les emplacements et leurs stands se parcourent tout de suite ; les deux dernières colonnes attendent une résolution.`,
            },
            {
              term: $localize`:@@aide.views.term.comparateur:Comparateur A/B`,
              text: $localize`:@@aide.views.def.comparateur:Deux plannings côte à côte — deux instantanés, ou un instantané et le planning actuel — sur le score, la couverture, l'équité et les écarts aux règles, avec le sens de chaque différence écrit en toutes lettres. Les instantanés de toutes les éditions sont proposés : c'est ainsi qu'on compare une variante à l'édition nominale. L'écran prévient quand les deux plannings n'ont pas la même taille.`,
            },
            {
              term: $localize`:@@aide.views.term.kpi:Autopsie du planning`,
              text: $localize`:@@aide.views.def.kpi:Une ligne par résolution terminée, toutes éditions confondues : score, couverture, dispersion des heures, nombre de modifications manuelles, durée. C'est la mémoire des campagnes passées. Rien n'y est nominatif, et l'historique survit à la suppression de l'édition qu'il décrit.`,
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
        { route: '/intendance', label: $localize`:@@nav.link.intendance:Intendance des repas` },
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
          text: $localize`:@@aide.shortcuts.intro:Ctrl+K ouvre la palette de commandes : une seule zone de saisie qui mène à n'importe quelle page et qui cherche aussi dans les données — un animateur, un stand, un créneau. Les flèches parcourent la liste, Entrée ouvre, Échap referme. C'est le point d'entrée à retenir ; les autres raccourcis ne sont que des abrégés.`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.shortcuts.term.palette:Ctrl+K`,
              text: $localize`:@@aide.shortcuts.def.palette:Ouvre et referme la palette de commandes.`,
            },
            {
              term: $localize`:@@aide.shortcuts.term.help:?`,
              text: $localize`:@@aide.shortcuts.def.help:Affiche la liste complète des raccourcis, sans quitter l'écran en cours.`,
            },
            {
              term: $localize`:@@aide.shortcuts.term.filter:/`,
              text: $localize`:@@aide.shortcuts.def.filter:Place le curseur dans le filtre de la page, quand elle en a un.`,
            },
            {
              term: $localize`:@@aide.shortcuts.term.submit:Ctrl+Entrée`,
              text: $localize`:@@aide.shortcuts.def.submit:Valide le formulaire en cours, comme un clic sur son bouton d'enregistrement. Une saisie incomplète n'est pas enregistrée pour autant.`,
            },
            {
              term: $localize`:@@aide.shortcuts.term.escape:Échap`,
              text: $localize`:@@aide.shortcuts.def.escape:Ferme la fenêtre ouverte et rend le focus à l'endroit d'où elle a été ouverte.`,
            },
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.shortcuts.go:Pour aller directement sur un écran, tapez « g » puis la lettre de la destination — « g » puis « a » pour les animateurs. Les pages sans lettre restent atteignables par la palette, qui les liste toutes.`,
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
          text: $localize`:@@aide.shortcuts.menuMode:Le menu latéral s'ouvre en mode simple : treize écrans spécialisés n'y figurent pas — les vues d'analyse (équité, jours de repos, heatmap de charge, marge disponible, timeline animateur, graphe), les écrans de diagnostic approfondi (contraintes, instantanés, comparateur A/B, autopsie du planning) et les outils techniques (historique, MCP, débogage). « Menu simple », en tête du menu, bascule vers le menu avancé, et ce navigateur retient le choix. Un écran masqué reste atteignable par la palette, par un lien de l'aide ou par son adresse : il apparaît alors dans le menu le temps de la visite.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.shortcuts.tables:Les tableaux de données de référence se parcourent aussi au clavier. Le chemin le plus court : « / » place le curseur dans le filtre, tapez de quoi réduire la liste, puis Flèche bas saute sur la première ligne.`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.shortcuts.term.rowEnter:Flèche bas depuis le filtre`,
              text: $localize`:@@aide.shortcuts.def.rowEnter:Entre dans le tableau sans compter les tabulations : le focus saute sur la ligne courante. Un clic sur une ligne la focalise de la même façon.`,
            },
            {
              term: $localize`:@@aide.shortcuts.term.rowMove:Flèches haut et bas`,
              text: $localize`:@@aide.shortcuts.def.rowMove:Passent d'une ligne à l'autre ; Début et Fin sautent à la première et à la dernière. Le focus suit la ligne, pas son rang : changer le tri ne le fait pas sauter ailleurs.`,
            },
            {
              term: $localize`:@@aide.shortcuts.term.rowOpen:Entrée`,
              text: $localize`:@@aide.shortcuts.def.rowOpen:Ouvre la ligne focalisée : sa fiche, ou directement son formulaire sur les créneaux. Sans effet tant qu'une résolution verrouille l'édition.`,
            },
            {
              term: $localize`:@@aide.shortcuts.term.rowSelect:Espace`,
              text: $localize`:@@aide.shortcuts.def.rowSelect:Coche ou décoche la ligne focalisée, et fait apparaître la barre d'actions groupées.`,
            },
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.shortcuts.grilles:Les trois grilles de saisie — « Ouvertures des stands », par date et par journée type, et « Compétences » — se remplissent comme un tableur : les flèches passent d'une case à l'autre, Entrée descend, et deux gestes évitent de retaper ce qui se répète. Rien n'est enregistré avant « Enregistrer » : une reprise de trop s'annule avec le reste des modifications.`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.shortcuts.term.gridRow:Ctrl+D`,
              text: $localize`:@@aide.shortcuts.def.gridRow:Reprend dans la ligne courante ce que dit celle du dessus, colonne par colonne. « Celle du dessus » est celle que l'écran montre au-dessus : filtrez la grille, et c'est la ligne voisine à l'écran qui est reprise. La flèche en tête de ligne fait la même chose à la souris.`,
            },
            {
              term: $localize`:@@aide.shortcuts.term.gridColumn:Ctrl+Maj+Bas`,
              text: $localize`:@@aide.shortcuts.def.gridColumn:Applique la case courante à toutes les lignes affichées de sa colonne, et à elles seules : les autres colonnes de ces lignes ne bougent pas. La double flèche en tête de colonne fait la même chose à la souris, en partant de la case active ou, à défaut, de la première ligne.`,
            },
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.shortcuts.guard:Aucun raccourci à une touche ne se déclenche pendant que vous saisissez du texte : tant que le curseur est dans un champ, « g », « / » et « ? » restent des caractères ordinaires.`,
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
