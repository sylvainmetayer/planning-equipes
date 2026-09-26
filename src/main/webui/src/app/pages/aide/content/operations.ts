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
          text: $localize`:@@aide.relecture.intro:Entre le premier planning qui tient et l'envoi aux animateurs, il y a une relecture : on ouvre les journées une par une, on regarde, on corrige. Sur douze journées, la vraie question est « où en étais-je ? ». Sur la page Planning, le menu de la barre de relecture ouvre « Relu et accepté… », dont « Marquer relu et accepté » enregistre votre passage, avec la date et un commentaire si vous en laissez un. Le sélecteur de jour marque ensuite les journées relues, et la page Solveur dit « 3 journées sur 12 ».`,
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
          text: $localize`:@@aide.relecture.prerequis:La barre de relecture, sous le sélecteur de jour, compte ce qu'il faut regarder : sièges vides, pauses sans relais, sièges verrouillés, changements depuis la publication. Un clic sur une pastille filtre le rendu sur ce qu'elle compte — le tableau sur ses sièges vides ou verrouillés, les pauses sur celles sans relais, les changements — et un second clic le rend entier. Avant d'accepter, « Relu et accepté… » affiche aussi les écarts durs et les postes reposant sur quelqu'un d'irremplaçable : les chiffres des écrans Problèmes, Pauses et Fragilité, filtrés sur la date. Aucun ne bloque : si vous savez pourquoi un siège reste vide, acceptez et écrivez-le dans le commentaire.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.relecture.resolution:Une résolution qui déplace un siège d'une journée acceptée retire cette validation, et le récapitulatif le dit : personne n'a relu ce que le calcul vient d'écrire. Une journée aussi verrouillée garde sa validation, puisque rien n'a pu y bouger. Au moment de publier, le panneau de diffusion rappelle combien de journées non relues partiraient.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.relecture.changements:Le rendu « Changements » de la page Planning liste ce qui a bougé ce jour-là : siège par siège (stand, créneau, qui était là, qui y est maintenant) ou personne par personne, dans les phrases mêmes du courriel de publication. Deux références : depuis la dernière publication — ce que les gens ont reçu — ou depuis la dernière résolution, pour voir ce que le calcul vient de réécrire. La dernière résolution est celle qui a réellement remplacé le planning enregistré : un calcul interrompu ou refusé ne déplace pas ce repère, et une restauration ou un import faits entre deux calculs comptent dans les changements. Une personne gardée sur son stand avec des horaires rognés se lit « horaires modifiés », pas retirée puis nouvelle. Les compteurs suivent les filtres de la page et le disent. Tant qu'aucune référence n'existe, l'onglet le dit plutôt que de compter zéro changement.`,
        },
      ],
      links: [
        { route: '/journee', label: $localize`:@@nav.link.journee:Planning` },
        {
          route: '/journee',
          queryParams: { vue: 'changements' },
          label: $localize`:@@aide.link.changementsJour:Changements de la journée`,
        },
        { route: '/verrouillages', label: $localize`:@@nav.link.verrouillages:Verrouillages` },
      ],
    },
    {
      id: 'consignes',
      icon: 'gavel',
      title: $localize`:@@aide.consignes.title:Consignes (arrêté, canicule)`,
      summary: $localize`:@@aide.consignes.summary:Fermer tous les stands sur une bande horaire de quelques jours, par décision, et rouvrir sur des fenêtres de compensation — sans rien détruire.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.consignes.intro:Un arrêté préfectoral tombe la veille au soir : de midi à 18 heures, plus personne dehors, pendant trois jours d'un événement qui en dure dix. Puis il est prolongé, puis levé. La page Consignes tient ce geste en une ligne par date : la bande fermée, le motif — obligatoire, il est imprimé partout où la journée est dite modifiée —, et les stands rouverts en compensation, le soir ou le matin.`,
        },
        {
          kind: 'steps',
          items: [
            $localize`:@@aide.consignes.etape.poser:Poser : choisissez un préréglage (« Plan canicule » : bande, motif, fenêtres par défaut) ou saisissez librement, puis les dates. Seuls les jours à venir de la grille sont proposés : une journée commencée garde pour toujours la consigne qui l'a gouvernée.`,
            $localize`:@@aide.consignes.etape.stands:Les stands sont lus pour la première date et la bande : ceux qui perdent des minutes arrivent pré-cochés avec les fenêtres par défaut et l'effectif hérité — le plus fort effectif perdu dans la bande. Un stand qui a posé des horaires datés ce jour-là est écarté, avec la raison, mais reste cochable. Chaque fenêtre porte son effectif — vide, elle hérite ; un stand peut rouvrir à 2 le matin et à 7 le soir. Filtrez par nom, typologie, emplacement ou premium ; « Appliquer à la sélection » règle d'un coup les fenêtres, ou l'effectif de toutes les fenêtres, des stands cochés affichés.`,
            $localize`:@@aide.consignes.etape.apercu:« Aperçu » dit, date par date, ce que l'écriture ferait : sièges et minutes avant et après, créneaux à ajouter ou à retirer, vacations sans siège, stands entrants et sortants, mineurs concernés, verrous et ajustements touchés, animateurs assis dans la bande, et si la validation de relecture sera retirée. « Enregistrer » envoie exactement la même demande.`,
            $localize`:@@aide.consignes.etape.modifier:Modifier remplace la consigne d'une date en place, Prolonger reprend sa bande, son motif et ses ouvertures sur d'autres dates, Lever la retire des dates choisies — les créneaux qu'elle avait ajoutés partent avec leurs sièges. Les trois gestes sont prévisualisés, et refusés sur une date déjà commencée.`,
            $localize`:@@aide.consignes.etape.resolution:Le planning suit ensuite le chemin ordinaire : une résolution incrémentale replace les gens sur les créneaux qui restent et sur ceux ajoutés, puis la publication annonce les journées aux horaires modifiés. L'espace animateur, le jour J et le PDF individuel affichent le motif sous la date — aussi pour la personne dont la bande a vidé toute la journée, qui lit « Repos » et la raison.`,
          ],
        },
        {
          kind: 'callout',
          title: $localize`:@@aide.consignes.callout.title:Rien n'est détruit`,
          text: $localize`:@@aide.consignes.callout.text:La bande ferme tous les stands, sans exception, au-dessus de leurs règles et de leurs exceptions datées — et elle ne ferme que cela : un stand non coché garde ses horaires hors bande. Les créneaux nominaux gardent leur identifiant, leurs sièges et leurs verrous ; les vacations de la bande deviennent simplement sans siège. Les créneaux manquants pour couvrir une fenêtre sont ajoutés et marqués « ajouté par consigne » ; les journées types ignorent ces dates, affichées « sous consigne » plutôt qu'en écart. Les statistiques comptent les heures réellement travaillées, et un instantané emporte les consignes en vigueur à sa capture.`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.consignes.term.soirBloque:Le soir reste à moitié vide : la coupure repas`,
              text: $localize`:@@aide.consignes.def.soirBloque:Une fenêtre du soir derrière un après-midi raccourci — 18 h-22 h derrière un 14 h-20 h devenu 18 h-20 h — demande deux personnes par siège tant que la coupure repas du soir (19 h-21 h) interdit d'enchaîner 18 h-20 h puis 20 h-22 h. La réponse est dans la consigne elle-même, section « Fenêtres repas ce jour-là » : alignez la fenêtre du soir sur la compensation (18 h-22 h) en donnant la raison — les équipes ont mangé pendant la fermeture. Elle ne vaut que sur ces dates et part avec la consigne ; les paramètres de l'édition ne bougent pas. Puis relancez la résolution. L'autre levier est de rouvrir moins de stands le soir. Le repos quotidien de onze heures n'est pas en cause.`,
            },
            {
              term: $localize`:@@aide.consignes.term.relancer:Relancer après une consigne : verrouiller, puis résoudre en entier`,
              text: $localize`:@@aide.consignes.def.relancer:Posez un verrou « journée » sur chaque journée à préserver, puis lancez une résolution complète : elle repart du plan en place et ne touche qu'aux journées libres. Évitez la replanification incrémentale ici — elle fige tout siège dont le titulaire est encore disponible, y compris, après une levée, un après-midi redevenu 14 h-20 h à côté d'un autre siège, et elle n'échange que très lentement des sièges quand une soirée saturée en laisse un vide.`,
            },
          ],
        },
      ],
      links: [
        { route: '/consignes', label: $localize`:@@nav.link.consignes:Consignes` },
        { route: '/journee', label: $localize`:@@nav.link.journee:Planning` },
        { route: '/solveur', label: $localize`:@@nav.link.solver:Solveur` },
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
          text: $localize`:@@aide.jourJ.portee:Marquer quelqu'un absent le déclare indisponible sur les créneaux restants de la journée, et sur ceux-là seulement. Un créneau est restant tant qu'il n'est pas terminé, celui en cours compris. Les créneaux passés ne sont jamais touchés : la personne les a réellement tenus. Le siège d'un créneau déjà commencé n'est pas vidé non plus — le passé ne se modifie plus, l'absence y est seulement enregistrée.`,
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
          text: $localize`:@@aide.jourJ.horsJour:Hors du jour même, pour un siège encore à venir, la page Planning fait les mêmes gestes un par un : son panneau du siège remplace, libère, déplace, et « Placer » remplit un siège vide depuis « Qui peut tenir ce siège ? ». Le mode jour J reste l'écran de l'absence du jour, qui libère d'un coup tous les sièges restants de la personne.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.jourJ.publication:Rien n'est envoyé aux animateurs depuis cet écran : leur espace continue d'afficher le planning publié tant que vous n'avez pas republié. Le bandeau rappelle combien de personnes attendent un changement et renvoie vers le bouton Publier, sur la page Publication.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.jourJ.trace:Chaque absence marquée est un ajustement manuel enregistré, avec sa raison, son auteur et son horodatage : elle se retrouve le lendemain sur la page Ajustements manuels. Elle s'annule créneau par créneau ou d'un bloc, mais les postes déjà réaffectés ne reviennent pas d'eux-mêmes.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.jourJ.mock.instance:Cet écran ne parle que d'aujourd'hui, ce qui le rend difficile à découvrir hors période. En développement, ou sur un serveur de démonstration ou de recette qui l'autorise, la carte « Date et heure simulées » de Paramètres, onglet Instance, fige la date et l'heure que le serveur considère comme maintenant ; un sablier apparaît alors dans la barre du haut et ramène au champ pour la modifier, et « Revenir à l'horloge de la machine » la lève. L'espace animateur suit la même horloge. Sur une instance de production, le réglage n'existe pas.`,
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
      id: 'affichage-mural',
      icon: 'tv',
      title: $localize`:@@aide.mural.title:Affichage mural`,
      summary: $localize`:@@aide.mural.summary:Un écran pour la TV de la salle de contrôle : qui tient quel stand maintenant et ensuite, mis à jour chaque minute, sans session d'administration.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.mural.intro:Paramètres, onglet « Affichage mural » : donnez un libellé au lien (« TV PC sécurité »), choisissez au besoin les emplacements à afficher — une TV par zone — et créez-le. L'adresse n'est montrée qu'une fois : copiez-la ou scannez le QR code avec l'appareil qui pilote la TV. Perdue, elle ne se retrouve pas : créez un autre lien et révoquez l'ancien.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.mural.ecran:L'écran regroupe les stands ouverts du jour par emplacement. Chaque tuile montre la vacation en cours et la suivante, avec les noms et, en rouge, chaque place libre ; un stand fermé à cet instant est estompé avec son heure de réouverture. En bas, les places à pourvoir dans les deux heures, les pauses sans relais et la consigne du jour. Quand tout ne tient pas, les pages tournent toutes les quinze secondes.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.mural.donnees:C'est le planning enregistré qui s'affiche, pas celui envoyé aux animateurs : un remplacement fait en Mode jour J apparaît à la minute suivante, sans republier. L'heure est celle du serveur, jamais celle de la TV. Si le réseau tombe, l'écran garde ce qu'il montrait et indique depuis quand il est hors ligne.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.mural.noms:Par défaut, l'écran montre le prénom et l'initiale du nom : tout le monde le voit dans la pièce. Le nom complet est une option du lien, à choisir à sa création. Ni téléphone, ni âge, ni motif d'absence n'y figurent jamais.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.mural.securite:Le lien n'ouvre que cet écran, pour cette édition, en lecture seule. La liste des liens donne la date du dernier accès ; révoquer un lien coupe l'écran à sa lecture suivante, et supprimer l'édition emporte ses liens. Pour une affiche grand format de la journée entière, ajoutez ?impression=1 à l'adresse et imprimez la page — ou, sans lien, « Imprimer cette journée » sur la page Planning, derrière votre session.`,
        },
      ],
      links: [
        {
          route: '/parametres',
          queryParams: { onglet: 'mural' },
          label: $localize`:@@aide.link.affichageMural:Paramètres — Affichage mural`,
        },
        { route: '/jour-j', label: $localize`:@@nav.link.jourJ:Mode jour J` },
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
              term: $localize`:@@aide.views.term.planning:La page Planning`,
              text: $localize`:@@aide.views.def.planning:Le planning d'abord : le titre sur une ligne, puis le jour, les filtres et le rendu. Le jour se choisit sur un mini-mois dépliable, chaque case marquée de ses sièges vides, de sa relecture, de son verrou et de sa consigne ; « aujourd'hui » y est la date du serveur, jamais celle de votre ordinateur. Les filtres Stand et Animateur se tapent : deux lettres suffisent. Dessous, la barre de relecture et la consigne du jour, en une ligne chacune. « Imprimer cette journée » ouvre la journée entière mise en page pour le papier, celle de l'affichage mural, sans lien à créer ; « Afficher sur la TV » mène à Paramètres, onglet Affichage mural, où se crée le lien de la télévision et son QR code. Le mois, la charge des repas et le graphe des emplacements, autrefois des écrans à part, sont là : leurs anciennes adresses y mènent.`,
            },
            {
              term: $localize`:@@aide.views.term.day:Tableau de la journée`,
              text: $localize`:@@aide.views.def.day:Une journée en tableau sur toute la largeur : un stand par ligne, son emplacement sous son nom, un créneau par colonne, un nom par étiquette et chaque siège vide en rouge. Un stand fermé sur un créneau le dit en gris, et une ouverture partielle donne simplement ses heures. Le nom d'un stand mène à sa fiche. C'est là qu'on corrige à la main : un clic sur un nom ou sur un « siège libre » ouvre à droite le panneau du siège (voir Panneau Siège ci-dessous), qui explique le siège et porte tous les gestes. « Déplacer vers… » y est toujours proposé : choisissez le siège libre où aller ou la personne avec qui échanger ; le serveur simule le geste et le refuse, en nommant la règle, s'il cassait une règle dure, et un siège verrouillé ne bouge pas. Si votre instance a en plus activé le glisser-déposer, encore en test, un nom se glisse aussi, par la poignée à sa gauche, vers un autre stand de la journée — sur un siège libre la personne est déplacée, sur une personne les deux échangent. Le glisser-déposer est désactivé par défaut : il ne règle que ce geste à la souris, jamais le déplacement lui-même.`,
            },
            {
              term: $localize`:@@aide.views.term.railJour:Rail de la journée`,
              text: $localize`:@@aide.views.def.railJour:La même journée vue par personne : une ligne par animateur, les vacations placées dans le temps. Les trous, les amplitudes et les enchaînements sautent aux yeux, et les lignes vides disent qui reste mobilisable — celles marquées « indisponible » disent de ne pas solliciter la personne. Un clic sur une vacation ouvre le panneau de son siège ; au clavier, Espace sur une ligne l'ouvre sur sa vacation — sur la suivante, pressée à nouveau — et Entrée ouvre « Déplacer une vacation de … », que le glisser-déposer soit activé ou non. Quand il l'est, une vacation se glisse aussi vers une autre ligne, aux mêmes conditions que sur le tableau de la journée.`,
            },
            {
              term: $localize`:@@aide.views.term.carteJour:Carte de la journée`,
              text: $localize`:@@aide.views.def.carteJour:La même journée sur la carte des emplacements : un curseur temporel, et chaque emplacement coloré selon ce qui s'y passe à cet instant — ouvert et pourvu, ouvert avec des places vides, ou ouvert sans personne. Les stands sans emplacement géolocalisé sont listés à côté de la carte. Chaque pastille porte le nombre de personnes présentes, suivi des places prévues, et grandit avec l'effectif ; le survol détaille la répartition par stand. Sous la carte, la grille « Charge par emplacement » croise chaque lieu avec les tranches de la journée — une tranche commence ou finit dès qu'une place s'ouvre ou se ferme — et donne dans chaque case présents / places, la dernière ligne faisant le total sur le site ; les stands sans emplacement y ont leur ligne. Un clic ou Entrée sur une case place le curseur sur cette tranche. « Tout l'événement » passe à une colonne par jour, chaque case donnant le pic de la journée. Une personne en pause relayée reste comptée sur son stand ; une place vide, ou libérée par une absence du jour J, ne compte personne.`,
            },
            {
              term: $localize`:@@aide.views.term.comparaisonJours:Comparer deux journées`,
              text: $localize`:@@aide.views.def.comparaisonJours:Sur le tableau et le rail de la page Planning, « Comparer avec… » pose un second jour à côté du premier — deux samedis, par exemple. Un bandeau donne pour chaque jour les sièges à pourvoir, pourvus et vides, les pauses sans relais, les animateurs mobilisés et les stands ouverts, avec l'écart du second jour sur le premier. Dessous, une ligne par stand (ou par animateur sur le rail), dans le même ordre des deux côtés : un stand fermé l'un des deux jours y est grisé, et les lignes qui diffèrent — horaires, sièges ou nombre de sièges pourvus — portent la mention « écart ». « Seulement les différences » masque le reste, les filtres de la page s'appliquent aux deux jours — le bandeau ne compte alors que les lignes retenues et porte la mention « filtré » —, et deux raccourcis proposent le même jour la semaine précédente ou suivante. La comparaison est en lecture seule : le glisser-déposer revient en la quittant. Le panneau de relecture est masqué, une relecture portant sur une journée entière ; chaque colonne dit seulement si son jour est relu. Sur un téléphone, les deux jours se lisent par onglets A et B. L'adresse garde les deux jours : la recharger ou la partager rouvre la même comparaison.`,
            },
            {
              term: $localize`:@@aide.views.term.pauses:Pauses et repas`,
              text: $localize`:@@aide.views.def.pauses:La rotation des pauses légales, jour par jour et stand par stand : qui sort de quelle heure à quelle heure, une personne à la fois, et qui relaie. Le solveur exige qu'une pause due soit prenable — un trou, ou un collègue sur le stand — sans dire à quelle minute elle est prise ; l'écran la pose au plus tard possible. Une seconde section liste les coupures repas et combien de minutes manquent quand la grille ne laisse pas de place — les journées en rouge sont celles que le solveur refuse. Dessous, l'intendance du jour : combien de personnes sont en coupure repas, heure par heure et par emplacement, midi et soir — combien de sandwichs préparer, et où les porter. Personne n'y est nommé, les mineurs y sont comptés à part ; « Tout l'événement en CSV » exporte tous les jours d'un coup.`,
            },
            {
              term: $localize`:@@aide.views.term.heatmap:Heatmap de charge`,
              text: $localize`:@@aide.views.def.heatmap:Jour croisé avec le stand (places pourvues sur places requises : les trous de couverture) ou avec l'animateur (postes par jour : les surcharges). Pour repérer un déséquilibre d'un coup d'œil.`,
            },
            {
              term: $localize`:@@aide.views.term.repartitionHeures:Répartition des heures`,
              text: $localize`:@@aide.views.def.repartitionHeures:Ce qui pèse dans l'édition, en une image. Chaque rectangle est un stand, sa surface les heures-sièges à pourvoir sur la période — sièges pourvus et vides confondus, sur la fenêtre réelle du siège : la pause d'une personne ne réduit pas le besoin de son stand. Sa couleur dit la part de ces heures réellement tenue, avec les couleurs de la Heatmap mais des seuils propres : critique sous 80 %, rayé en plus d'être rouge, partiel jusqu'à 99 %, pourvu à 100 % — la Heatmap, elle, ne dit critique qu'une case que personne ne tient. Les stands se regroupent par emplacement, ou par typologie : un stand qui propose plusieurs typologies est rangé une seule fois, sous leur combinaison (« Ambiance + Stratégie »), pour que les surfaces s'additionnent au total de l'édition — le Planning par typologie, lui, compte un poste pour chaque typologie. Quand un groupe écrase les autres, les plus petits se rangent sous « Autres ». Un clic agrandit un groupe, le fil d'Ariane ramène en arrière, et un clic sur un stand ouvre sa journée. Le tableau replié sous l'image donne les mêmes chiffres, stands à 0 h compris. Le sur-effectif n'y apparaît pas : le planning ne crée aucun siège au-delà du besoin.`,
            },
            {
              term: $localize`:@@aide.views.term.marge:Marge disponible`,
              text: $localize`:@@aide.views.def.marge:Journée croisée avec la tranche horaire, et dans chaque case les animateurs disponibles moins les sièges à pourvoir. Rouge en dessous de zéro, vert au-dessus. « Avant résolution » compare la capacité brute aux sièges à pourvoir et répond donc sans aucun calcul ; « après résolution » ne compte libre que celui qui n'est pas déjà en poste et qu'aucune règle dure n'écarte, face aux seuls sièges restés vides. La lecture est optimiste : une case négative l'est vraiment, une case confortable ne garantit rien.`,
            },
            {
              term: $localize`:@@aide.views.term.tension:Tension`,
              text: $localize`:@@aide.views.def.tension:Troisième lecture de la Marge, quand un planning est enregistré : la marge après résolution croisée avec la fragilité. Critique : des sièges vides que personne ne peut tenir, un siège qu'aucun autre ne pourrait reprendre, ou un stand sans spécialiste — la case est alors hachurée. Élevée : marge nulle avec des sièges vides, ou plus de sièges fragiles (un remplaçant au plus) que de monde à revendre. Surveillée : des sièges fragiles mais assez de marge, ou un stand à spécialiste unique sans polyvalent en renfort. Calme sinon. Le badge « 2 ⚠ » compte les sièges fragiles ; une case ouvre ses raisons, avec « Qui peut tenir un siège vide » — le panneau du siège, sur la page Planning —, la fiche de la personne irremplaçable et la fragilité du stand. Une tranche déjà commencée est grisée.`,
            },
            {
              term: $localize`:@@aide.views.term.repos:Jours de repos`,
              text: $localize`:@@aide.views.def.repos:L'inverse des autres vues : non pas qui est où, mais qui souffle. Une ligne par animateur, une colonne par journée, trois états — journée travaillée, jour de repos, indisponible. Un jour de repos est une journée que la personne pouvait faire et sur laquelle le planning ne l'a pas affectée. Les lignes les plus tendues remontent en haut, la colonne « Série » donne la plus longue série travaillée, et l'histogramme dit jour par jour quelle part de l'effectif se repose. Une case rouge signale une affectation posée sur une journée déclarée indisponible : une anomalie à corriger.`,
            },
            {
              term: $localize`:@@aide.views.term.fiche:Fiche animateur`,
              text: $localize`:@@aide.views.def.fiche:Tout ce qu'on sait d'une personne sur une page, et tout ce qu'on fait pour elle depuis cette page. En tête, les gestes : « Copier le lien d'espace », « Envoyer son planning » (le même e-mail que la publication, pour elle seule, après confirmation), « Relancer », le PDF et l'ICS de son planning, « Verrouiller tout son planning » ou « Libérer tout son planning », « Poser un ajustement » (le formulaire, déjà à son nom) et « Modifier la fiche ». Précédent et suivant parcourent la liste de la page Animateurs telle que vous l'aviez filtrée et triée. Sept sections repliables, les trois premières ouvertes : identité et contact ; disponibilités, sur une frise des jours — un clic sur un jour, puis « Indisponible ce jour » : le jour est écrit dans ses indisponibilités et, si le planning enregistré la place ce jour-là, ses sièges de ce jour sont libérés dans le même geste (un siège déjà commencé reste tel quel ; un siège verrouillé aussi, et la fiche le nomme avec le lien « Gérer les verrous ») ; chaque siège libéré propose « Qui peut tenir ce siège ? », et « Annuler l'absence » rend le jour disponible sans rendre les sièges ; le planning, jour par jour — amplitude, vacations, coupures, pauses et trajets —, chaque vacation menant à la page Planning, son siège ouvert ; la charge et l'équité, sa ligne de l'écran Équité avec l'écart à la médiane et le radar, « Comparer avec… » posant une seconde personne dessus ; compétences et souhaits, modifiables sur place ; fragilité, avec « Verrouiller » et « Former » sur chaque poste fragile ; échanges et suivi — publication, accusé, demandes, ajustements, verrous. Les chiffres sont ceux des écrans spécialisés, jamais recalculés. Tout nom d'animateur de l'application mène ici.`,
            },
            {
              term: $localize`:@@aide.views.term.equite:Équité`,
              text: $localize`:@@aide.views.def.equite:Ce que chacun a reçu, pour arbitrer avant de publier et répondre après — « j'ai trois nocturnes et lui aucune ». Une ligne par animateur affecté : heures totales et par semaine, heures de soirée, de week-end et de jour férié, postes et postes pénibles, stands, typologies et emplacements distincts, part des postes sur une typologie souhaitée, jours travaillés, jours de repos, plus longue série. À côté de chaque valeur, son écart à la médiane. Le tableau se trie, se filtre et s'exporte en CSV. La légende dit quelles colonnes une règle du solveur mesure vraiment : les heures de soirée, de week-end et de jour férié ne sont pesées par aucune — l'écran les montre, il ne les corrige pas. Chaque nom mène à la fiche de la personne, dont la section « Charge et équité » lit ses indicateurs l'un sous l'autre à côté d'un radar : un axe par indicateur — heures, soirées, week-ends, postes pénibles, souhaits satisfaits, et au choix jours fériés, appréciations, jours travaillés, plus longue série. Chaque axe va du minimum de l'édition, au centre, à son maximum, au bord ; le polygone plein est la personne, le pointillé la médiane, la zone grisée l'étendue. Plus loin veut dire plus chargé, sauf sur les axes marqués « ↑ mieux ». Un axe où tout le monde a la même valeur est dessiné au centre et dit « aucune dispersion ». « Comparer avec… » pose une seconde personne sur le même radar, pour arbitrer entre deux.`,
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
              term: $localize`:@@aide.views.term.former:À former`,
              text: $localize`:@@aide.views.def.former:Qui former, typologie par typologie. Une typologie y figure quand le Besoin en animateurs la dit en manque, ou quand la Fragilité y trouve un stand tenu par un seul spécialiste ou aucun ; les chiffres sont ceux de ces deux onglets, jamais recalculés. Les candidats sont les débutants et les autonomes de la typologie — ni un référent, ni un polyvalent, ni quelqu'un sans la compétence —, classés par jours en tension où ils sont disponibles, puis souhait, puis niveau. Une typologie sans candidat relève du recrutement. Sans planning persisté, seul le besoin parle. L'onglet s'exporte en CSV ; monter un niveau reste un geste de la grille Compétences.`,
            },
            {
              term: $localize`:@@aide.views.term.banc:Panneau Siège`,
              text: $localize`:@@aide.views.def.banc:Le panneau qui s'ouvre à droite de la page Planning au clic sur une case — un nom ou un siège libre du tableau, une vacation du rail, le bouton « Siège » d'un stand de la carte, « Ouvrir le siège » d'une pause sans relais, l'icône d'un siège dans les changements. Il dit le stand, le créneau, qui tient le siège et les verrous qui le figent ; sur un siège tenu, « Pourquoi lui ? » nomme les règles en défaut dans les mots de la page Contraintes, puis viennent « Remplacer » (les remplaçants viables, cherchés sur le planning enregistré avec les règles de l'édition, avec « Appliquer »), « Déplacer vers… », « Libérer », « Verrouiller » ou « Déverrouiller », « Qui peut tenir ce siège ? » en lecture seule, et « Ouvrir sa fiche ». « Libérer » demande confirmation et, tant que « La tenir à l'écart de ce créneau au prochain calcul » reste cochée, verrouille la personne hors de ce créneau : sans cela, « Corriger le reste » pourrait l'y remettre aussitôt. « Appliquer » et « Libérer » ne touchent le siège que s'il est encore tenu par la personne affichée. Sur un siège vide, « Qui peut tenir ce siège ? » ouvre l'ancien banc de touche, limité à ce siège : qui n'est de service nulle part à ce moment, et ce qui empêcherait chacun de le tenir — toutes les raisons, pas seulement la première. Les personnes disponibles viennent d'abord, avec « Placer » ; celles qu'une règle dure écarte se déplient à la demande. « Placer » affecte la personne et, tant que « La garder au prochain calcul » reste cochée, la verrouille sur ce créneau pour que la prochaine résolution la laisse en place ; le serveur refuse en nommant la règle un placement qui casserait une règle dure, et prévient quand il coûte en qualité. « Placer » n'est offert ni à une personne qu'un verrou tient hors de ce créneau — la ligne le dit —, ni sur un créneau déjà commencé. « Poser un ajustement » ouvre le formulaire d'ajustement manuel, prérempli avec ce stand et ce créneau. Après un geste, le panneau dit ce qui reste à faire : « Non publié : Prévenir » mène à la publication, et « Corriger le reste » lance une résolution incrémentale quand le geste a laissé un siège vide. Échap ou la croix le referment. Le panneau se retrouve depuis la palette en tapant « banc ».`,
            },
            {
              term: $localize`:@@aide.views.term.comparateur:Comparateur A/B`,
              text: $localize`:@@aide.views.def.comparateur:Deux plannings côte à côte — deux instantanés, ou un instantané et le planning actuel — sur le score, la couverture, l'équité et les écarts aux règles, avec le sens de chaque différence écrit en toutes lettres. Les instantanés de toutes les éditions sont proposés : c'est ainsi qu'on compare une variante à l'édition nominale. L'écran prévient quand les deux plannings n'ont pas la même taille, et quand ils ont été calculés sous des dosages différents — des poids ou des activations de règles qui ne sont pas les mêmes : leurs scores ne se comparent alors pas à poids égaux, et il liste les règles en cause.`,
            },
            {
              term: $localize`:@@aide.views.term.kpi:Autopsie du planning`,
              text: $localize`:@@aide.views.def.kpi:Une ligne par résolution terminée, toutes éditions confondues : score, couverture, dispersion des heures, nombre de modifications manuelles, durée, et le dosage sous lequel la résolution a été lancée — « défaut », « N règle(s) repondérée(s) », ou « inconnu » pour une ligne antérieure à cette mesure, le détail au survol. Le bouton de filtre d'une ligne ne garde que les résolutions calculées sous le même dosage, pour comparer à poids égaux. Au-dessus du tableau, le rejeu parcourt les résolutions d'une édition — l'édition courante par défaut, une édition supprimée reste choisissable : une petite courbe par mesure, chacune à sa propre échelle, un curseur à la souris, aux flèches ou par les boutons, et une lecture qui avance d'une résolution par seconde (de cinq au-delà de cent), désactivée quand le navigateur demande de limiter les animations. La carte dit ce qui a bougé depuis la résolution précédente, règles apparues ou disparues comprises, et une bande montre la couverture jour par jour — « non mesurée » pour une résolution antérieure à cette mesure. Le tableau suit l'édition choisie et surligne la résolution pointée ; un clic sur une ligne y place le curseur. Une ligne supprimée à la main disparaît du rejeu sans laisser de trou : les rangs se referment. C'est la mémoire des campagnes passées. Rien n'y est nominatif, et l'historique survit à la suppression de l'édition qu'il décrit.`,
            },
          ],
        },
      ],
      links: [
        { route: '/journee', label: $localize`:@@nav.link.journee:Planning` },
        { route: '/heatmap', label: $localize`:@@nav.link.heatmap:Heatmap de charge` },
        {
          route: '/repartition-heures',
          label: $localize`:@@nav.link.repartitionHeures:Répartition des heures`,
        },
        { route: '/marge', label: $localize`:@@nav.link.marge:Marge disponible` },
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
        {
          route: '/typologies-planning',
          label: $localize`:@@nav.link.typologiesPlanning:Planning par typologie`,
        },
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
          label: $localize`:@@aide.link.pauses:Pauses et repas`,
        },
        { route: '/publication', label: $localize`:@@nav.link.publication:Publication` },
        {
          route: '/diagnostic',
          queryParams: { onglet: 'former' },
          label: $localize`:@@aide.link.former:À former`,
        },
        { route: '/journee', label: $localize`:@@aide.link.siege:Panneau Siège (Planning)` },
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
          text: $localize`:@@aide.shortcuts.onglets:La palette connaît aussi les onglets et les vues des pages : « problèmes » ou « mural » ouvrent directement le bon onglet ; « banc » mène à la page Planning, dont le panneau du siège a repris le banc de touche. Sans rien taper, elle liste le menu dans son ordre.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.shortcuts.go:Pour aller directement sur un écran, tapez « g » puis l'initiale de la destination — « g » puis « a » pour les animateurs, « g » puis « s » pour le solveur. « g » « g » ramène à l'accueil. Les pages sans lettre restent atteignables par la palette.`,
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
          text: $localize`:@@aide.shortcuts.menu:Le menu latéral est le même pour tout le monde et suit le cycle d'une édition : Accueil, Planning, Préparer, Construire, Diffuser, Aujourd'hui, Administrer. Aucun écran n'y est caché ; les plus rares sont en bas de leur groupe. Débogage n'y figure pas : il s'ouvre par la palette — « débogage », « swagger », « mailpit » — ou par son adresse. Les pages légales et les Nouveautés sont en pied de menu. Après une mise à jour, « nouveautés » s'allume à côté d'Aide et une pastille marque le lien du pied de menu, jusqu'à l'ouverture de la page.`,
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
          text: $localize`:@@aide.shortcuts.grilles:Les trois grilles de saisie — « Horaires des stands », par date et par journée type, et « Compétences » — se remplissent comme un tableur : les flèches passent d'une case à l'autre, Entrée descend, et deux gestes évitent de retaper ce qui se répète. Rien n'est enregistré avant « Enregistrer » : une reprise de trop s'annule avec le reste des modifications.`,
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
        {
          kind: 'paragraph',
          text: $localize`:@@aide.shortcuts.disable.instance:Si vous dictez à la voix, ou qu'une touche vous échappe, désactivez les raccourcis à une touche : une case dans la liste des raccourcis (« ? ») et dans Paramètres, onglet Instance. Le réglage vaut pour ce navigateur ; Ctrl+K, le menu et les adresses restent.`,
        },
      ],
      links: [
        { route: '/animateurs', label: $localize`:@@nav.link.animateurs:Animateurs` },
        { route: '/journee', label: $localize`:@@nav.link.journee:Planning` },
      ],
    },
  ];
}
