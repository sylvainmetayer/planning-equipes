// The data an organiser enters before anything can be computed: editions,
// the reference screens, and the two bulk imports.
//
// One theme of the organisers' guide; `aide-content.ts` assembles the
// themes in reading order. Built lazily, never at module scope: `$localize`
// only resolves once `main.ts` has loaded the translation catalog.

import { HelpSection } from '../help-section';

export function buildReferenceDataSections(): HelpSection[] {
  return [
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
              text: $localize`:@@aide.editions.def.edition:Une édition de l'événement — ou une variante de plan — avec ses propres stands, animateurs, créneaux, paramètres et planning résolu. Rien ne circule d'une édition à l'autre : « Année 2025 » reste consultable pendant qu'on prépare « Année 2026 », et « 2026 canicule » vit à côté de « 2026 » sans la toucher. L'édition consultée est propre à chaque onglet du navigateur, et rappelée par le bandeau en haut de l'écran.`,
            },
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.editions.variante:Pour préparer un plan alternatif (canicule, repli), dupliquez l'édition : la copie embarque stands, horaires, animateurs et leurs indisponibilités du moment — mais ni les affectations ni les jetons d'espace (chaque édition frappe les siens, un lien d'espace désigne toujours exactement une édition). Appliquez ensuite le delta dans la copie (l'édition en masse des horaires s'y prête), lancez la résolution, et basculez d'édition le matin venu.`,
        },
        {
          kind: 'steps',
          items: [
            $localize`:@@aide.editions.rituel1:La veille : dupliquer l'édition courante (« 2026 » → « 2026-canicule »).`,
            $localize`:@@aide.editions.rituel2:Appliquer les restrictions dans la copie (horaires, effectifs…).`,
            $localize`:@@aide.editions.rituel3:Lancer la résolution dans la copie (la nuit fait le reste).`,
            $localize`:@@aide.editions.rituel4:Le matin : basculer d'édition dans le bandeau.`,
            $localize`:@@aide.editions.rituel5:« Publier » — les animateurs concernés reçoivent le plan et les liens de cette édition.`,
            $localize`:@@aide.editions.rituel6:Au retour à la normale : re-basculer vers l'édition nominale, intacte, et publier à nouveau.`,
          ],
        },
      ],
      links: [
        { route: '/editions', label: $localize`:@@nav.link.editions:Éditions` },
        { route: '/creneaux', label: $localize`:@@nav.link.creneaux:Créneaux` },
        { route: '/instantanes', label: $localize`:@@nav.link.snapshots:Instantanés` },
        { route: '/comparateur', label: $localize`:@@nav.link.comparateur:Comparateur A/B` },
      ],
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
              text: $localize`:@@aide.data.def.animateurs:Identité, date de naissance, compétences par typologie avec un niveau (débutant / autonome / référent), et jours d'indisponibilité. Le régime légal applicable (moins de 16 ans, 16-18 ans, majeur) n'est jamais saisi : il est recalculé à la date de chaque créneau. Par défaut un animateur est disponible : ne saisissez que les absences. Le tableau, lui, ne montre que ce qui tient dans une cellule — identité, majorité, manager, nombre de jours d'indisponibilité, accusé de réception — et chacune de ces colonnes se trie, en remontant d'abord ce qui reste à faire : les managers, les majeurs, puis les silencieux avant les relancés et les confirmés. L'appréciation est une liste par personne : elle se lit dans la fiche de consultation et se modifie dans le formulaire, y compris en édition groupée.`,
            },
            {
              term: $localize`:@@aide.data.term.stands:Stands`,
              text: $localize`:@@aide.data.def.stands:Typologies proposées, effectif minimum et maximum d'animateurs simultanés, restriction éventuelle aux majeurs, indicateurs premium et niveau d'effort, et horaires d'ouverture. Les horaires se saisissent en règles récurrentes : une règle vaut tous les jours et se tape en une ligne, « 10:00-12:00, 14:00- » — une fin vide court jusqu'à la fermeture, « @2 » fixe l'effectif d'une fenêtre — et « Cas particulier » la restreint à certains jours ou en fait une fermeture. Des exceptions datées les complètent, et priment sur les règles pour le jour qu'elles nomment. Chaque fenêtre d'ouverture — d'une règle comme d'une ouverture datée — peut nommer son propre effectif : un stand qui tient à deux le matin, à quatre l'après-midi et à un en nocturne se décrit ainsi, fenêtre par fenêtre ; une fenêtre sans effectif reprend le minimum du stand.`,
            },
            {
              term: $localize`:@@aide.data.term.creneaux:Créneaux`,
              text: $localize`:@@aide.data.def.creneaux:Jour de l'événement, date, heures de début et de fin. C'est le découpage temporel que le solveur remplit : sur chaque créneau, chaque stand ouvert reçoit autant de sièges que sa fenêtre d'ouverture en nomme — ou son effectif minimum, à défaut. Une édition qui tape ses vacations commence par ses journées types : chaque sorte de journée — « Jour normal », « Nocturne », « Montage » — décrite une fois par ses vacations sur une ligne, « 09:00-12:00, 12:00-13:00 R, 13:00-14:00 R, 14:00-20:00 », un R marquant un relais repas où chaque stand ne tient que la moitié de ses sièges ; puis un calendrier qui pose une journée type sur chaque date — ce sont les dates de l'édition. « Appliquer le calendrier » écrit les créneaux après un aperçu chiffré : un créneau identique garde ses sièges, un créneau en trop sur une date gouvernée est supprimé, une date sans journée type n'est pas touchée, et la grille est déclarée en vacations. Une journée type modifiée après coup ne change rien tant qu'on ne réapplique pas : ses dates passent « en écart ». « Reconnaître depuis les créneaux » fait le chemin inverse sur une grille déjà écrite. Une journée type se pose aussi en une règle sans mémoire (« Créer une série ») : ses créneaux sur une ligne, « 09:00-12:00, 14:00-18:00 », les jours couverts, et un aperçu de ce que la grille deviendrait avant d'écrire. La page déclare aussi ce que la grille contient — des amplitudes à découper, ou des vacations finales — parce que les données seules ne le disent pas et que chaque verdict en dépend : un chevauchement est une erreur entre amplitudes et la forme normale de vacations décalées. Quand les stands ont déjà leurs horaires, « Dériver des horaires des stands » fait découler la grille de leurs fenêtres : une coupure à chaque heure où un stand ouvre ou ferme, un créneau sur chaque tranche où au moins un stand est ouvert, prévisualisés avant d'être ajoutés ou mis à la place de la grille. Le « Contrôle de la grille » lit ce verdict à chaque visite ; il signale aussi un relais repas posé hors de toute fenêtre repas. Une grille déclarée en vacations range le découpage, ses paramètres et la dérivation : une ligne dit comment les retrouver. Deux créneaux qui se touchent ne s'enchaînent pas forcément pour une même personne : la pause minimale entre vacations, réglée dans les paramètres légaux, décide si le même animateur peut tenir les deux.`,
            },
            {
              term: $localize`:@@aide.data.term.autres:Emplacements et typologies`,
              text: $localize`:@@aide.data.def.autres:Les emplacements sont des lieux géolocalisés auxquels rattacher un stand — ils servent à éviter les changements de lieu éloignés d'un créneau à l'autre. Les typologies sont le vocabulaire commun entre les compétences d'un animateur et les jeux d'un stand : un animateur ne peut tenir un stand que s'il en maîtrise au moins une typologie. Désignez-y aussi la typologie « ninja » : ses porteurs sont considérés polyvalents (affectables sur n'importe quel stand), et le solveur essaie d'en garder un libre sur chaque créneau — votre marge de manœuvre en cas d'absence de dernière minute. Sans typologie ninja désignée, cette réserve n'existe pas et chacun reste cantonné à ses compétences ; elle se choisit sur la page Paramètres, qui vous avertit si elle manque.`,
            },
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.data.bulk:Chaque écran de référentiel permet de cocher plusieurs lignes pour les supprimer ou les modifier d'un seul geste. Dans une modification en masse, chaque champ vaut « ne pas modifier » tant qu'il n'est pas renseigné : les autres valeurs propres à chaque ligne sont préservées. Cela vaut aussi pour les horaires d'ouverture : une règle valable pour plusieurs stands (« tous fermés avant 18h en soirée canicule ») se saisit une seule fois — cochez les stands, « Modifier la sélection », puis ajoutez, remplacez ou effacez leurs règles d'un coup. Les créneaux, eux, sont déjà communs à tous les stands : on n'en crée jamais par stand, ce sont les horaires qui restreignent ce que chaque stand ouvre. Avant une suppression, seule ou en lot, la confirmation annonce ce qui référence la sélection : affectations du planning enregistré, ajustements manuels, verrouillages. C'est une information, pas un verrou — aucun chiffre n'empêche de supprimer.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.data.copieHoraires:Quand plusieurs stands suivent la même journée type — le pavillon, la nocturne, le stand éditeur — il n'y a pas à la ressaisir. Dans la fiche d'un stand, « Copier les horaires de… » reprend les règles récurrentes, les fermetures et ouvertures datées d'un autre stand, effectifs de fenêtre compris : le brouillon est remplacé, l'aperçu jour par jour se met à jour, et rien n'est enregistré avant « Modifier le stand ». Pour plusieurs stands d'un coup, la modification en masse propose « Remplacer par ceux d'un stand » : chaque stand coché reçoit tout l'horaire du stand modèle, exceptions datées comprises — là où « Ajouter », « Remplacer » et « Effacer » ne touchent qu'aux règles. Dans les deux cas, une fenêtre copiée qui demande plus que l'effectif maximum du stand cible est signalée avant d'enregistrer : relevez le maximum, ou baissez l'effectif de la fenêtre, sinon l'enregistrement de ce stand est refusé. La page Ouvertures des stands lit le résultat à sa prochaine visite, ou avec « Actualiser ».`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.data.avertissements:Certaines saisies sont enregistrées avec un avertissement : la ligne est bien écrite, et un message reste affiché jusqu'à ce que vous le fermiez. Plusieurs cas, à la création comme à la modification — une indisponibilité posée hors des dates de l'événement (elle ne recouvre aucun créneau, donc elle ne protège personne) ; une indisponibilité posée sur un jour de l'événement qui ne porte aucun créneau, par exemple un lundi de relâche entre deux week-ends : la date n'est pas fautive, mais l'espace animateur ne l'affichera pas et la première déclaration de disponibilités appliquée l'effacera ; une date de naissance qui rend l'animateur mineur pendant l'événement, le message précisant à partir de quel jour il devient majeur ; un créneau qu'aucun stand n'est ouvert à couvrir, en tout ou seulement à ses extrémités. Côté stands, la même vigilance à l'enregistrement d'une fiche : une fenêtre qui ne recoupe aucun créneau de son jour, une exception datée hors des jours de l'événement, ou un stand qui finit ouvert sur aucun créneau — dits seulement quand l'écriture touche à l'horaire, jamais sur un simple renommage. Ce ne sont jamais des refus : un refus s'affiche en rouge et rien n'est enregistré. Une modification ne signale que ce qu'elle change : reprendre l'adresse d'un mineur ne relance pas le message sur sa minorité, et une édition en lot ne réveille pas ce que personne n'a touché. Tant que l'édition n'a aucun créneau, l'événement n'a pas de dates et ces avertissements se taisent.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.data.ouvertures:Avant toute résolution, ouvrez « Ouvertures des stands » : la grille stand × jour montre ce que le solveur lira réellement une fois les règles étendues et les exceptions appliquées, et signale les trois erreurs de saisie habituelles — un stand finalement ouvert aucun jour, une fenêtre horaire hors des heures du jour (donc sans effet), et une plage trop courte pour être une vraie vacation. La même page se retourne en grille de saisie (« Saisir ») : une case par stand et par colonne, l'effectif à tenir, « - » ou 0 pour fermer, une case vidée gardant sa valeur — la forme même d'un tableur, avec ses gestes : flèches et Entrée, collage d'un bloc copié, recopie d'un jour sur les autres. Les colonnes suivent le besoin, pas les vacations : quand un stand change d'effectif à 19 h, le créneau 14-20 s'affiche en deux colonnes 14-19 et 19-20 pour tout le monde, et une colonne se scinde à une heure depuis son en-tête pour dire un nouveau changement. À l'enregistrement, chaque stand modifié voit tout son horaire réécrit depuis ses cases, ramené en règles quand un motif se répète, et ses effectifs minimum et maximum suivent la plus petite et la plus grande case. Une case en pointillés, rare, porte une borne qu'aucune colonne ne suit : enregistrée telle quelle elle garde ses fenêtres, modifiée elle prend la valeur tapée sur toute la colonne ; « Aligner les fenêtres partielles » les étend toutes, en disant d'abord combien d'heures cela ajoute. Quand l'édition a des journées types, une troisième vue les prend pour colonnes (« Par journée type ») : une case par stand et par vacation, qui vaut d'un coup pour toutes les dates que cette journée type gouverne — soixante-deux colonnes deviennent une quinzaine, et déclarer un stand tient en une ligne. Une case qui affiche « ≠ » signale des dates qui ne disent pas la même chose : la retaper les aligne, sinon elles se règlent une à une dans la grille par date, qui reste la vue de référence. Un stand n'a plus à dire qu'il ferme les jours où il n'ouvre pas : déclarer des ouvertures suffit, tout jour non déclaré est fermé, et « Compacter les horaires », sur la page Stands, retire les fermetures devenues inutiles après avoir vérifié qu'aucune ouverture ne bouge. La quatrième vue, « Journée », pose une journée sur l'axe du temps : une ligne par stand, les créneaux en bandes, chaque ouverture en bloc avec son effectif, et une fenêtre déclarée à une heure qu'aucune vacation ne couvre hachurée là où elle tombe — la seule erreur que les deux grilles ne montrent pas. La fiche de chaque stand liste ses propres anomalies d'ouverture, et l'accueil compte à part ces fenêtres hors de toute vacation.`,
        },
      ],
      links: [
        { route: '/animateurs', label: $localize`:@@nav.link.animateurs:Animateurs` },
        {
          route: '/imports',
          label: $localize`:@@aide.lien.importAnimateurs:Imports — onglet Animateurs`,
        },
        { route: '/stands', label: $localize`:@@nav.link.stands:Stands` },
        { route: '/ouvertures', label: $localize`:@@nav.link.ouvertures:Ouvertures des stands` },
        {
          route: '/diagnostic',
          queryParams: { onglet: 'besoin' },
          label: $localize`:@@aide.link.staffing:Besoin en animateurs`,
        },
      ],
    },
    {
      id: 'competences',
      icon: 'grid_on',
      title: $localize`:@@aide.competences.title:Grille des compétences`,
      summary: $localize`:@@aide.competences.summary:Saisir l'appréciation de chaque animateur sur chaque typologie en une seule grille, au clavier, en regard de ses souhaits — et l'échanger en CSV sans rien retirer.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.competences.grille:La fiche animateur permet de saisir les appréciations une personne à la fois ; l'écran « Compétences » les montre toutes d'un coup, animateurs en lignes et typologies en colonnes. Une case porte le niveau — vide, débutant, autonome ou référent — et se change d'un clic (les niveaux défilent) ou d'une touche : 0 vide la case, 1, 2 et 3 posent le niveau ; les flèches, Entrée, Début et Fin déplacent la sélection comme dans un tableur, et la flèche bas depuis le filtre entre dans la grille. Rien n'est écrit avant « Enregistrer » : les cases changées sont encadrées et le bouton compte les fiches concernées ; « Annuler les modifications » remet la grille telle qu'elle a été lue, et quitter la page avec des cases non enregistrées demande confirmation. Un petit cœur dans une case signale que l'animateur a déclaré souhaiter cette typologie : l'appréciation se saisit en regard du souhait, sans que l'un décide de l'autre. La colonne de la typologie ninja est repérée — la détenir rend polyvalent — et suit la règle habituelle. Le filtre par nom et le choix des typologies affichées restent dans l'adresse de la page, donc dans un lien partagé.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.competences.concurrence:L'enregistrement écrit une fiche par ligne modifiée, chacune avec son propre garde de modification concurrente : si une autre session a modifié une fiche après l'ouverture de la grille, cette ligne seule est refusée, les autres sont écrites, et l'écran demande — comme pour une fiche — de recharger ou d'écraser en connaissance de cause. Fermer la question laisse la ligne telle que saisie, non envoyée. Pendant une résolution, la saisie est verrouillée : le solveur réécrirait les fiches à l'arrivée.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.competences.csv:« Exporter en CSV » rend la grille telle qu'elle est : une colonne « animateur » avec l'identifiant — jamais le nom —, puis une colonne par typologie nommée par son identifiant, et dans chaque case DEBUTANT, AUTONOME, REFERENT ou rien. « Importer un CSV » lit ce même format, après un aperçu ligne par ligne comme les autres imports : rien n'est écrit avant « Importer », puis tout d'un seul tenant. Une ligne nomme un animateur par son identifiant — inconnu, elle est rejetée, l'import ne crée pas de fiche ; une colonne qui ne nomme aucune typologie est ignorée et listée ; un niveau illisible rejette sa ligne. Le point qui compte : une case vide du fichier laisse l'appréciation telle qu'elle est. L'import ajoute et met à jour, il ne retire jamais — retirer une appréciation reste un geste de la grille à l'écran. Un fichier exporté puis réimporté sans changement n'écrit donc rien, et le dit.`,
        },
      ],
      links: [
        { route: '/competences', label: $localize`:@@nav.link.competences:Compétences` },
        { route: '/animateurs', label: $localize`:@@nav.link.animateurs:Animateurs` },
        { route: '/typologies', label: $localize`:@@nav.link.typologies:Typologies` },
        { route: '/disponibilites', label: $localize`:@@nav.link.disponibilites:Disponibilités` },
      ],
    },
    {
      id: 'import-csv-animateurs',
      icon: 'upload_file',
      title: $localize`:@@aide.importCsv.title:Import CSV des animateurs`,
      summary: $localize`:@@aide.importCsv.summary:Reprendre le tableur des bénévoles sans le ressaisir : format attendu, aperçu ligne à ligne, et les deux refus qui surprennent la première fois.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.data.importCsv:Tous les fichiers qui remplissent une édition sont sur un seul écran, « Imports », un onglet par référentiel et dans l'ordre où les données se tiennent : typologies, emplacements, stands, animateurs, puis la grille des stands. Les trois premiers onglets demandent peu : un identifiant et un libellé pour une typologie, un identifiant et un nom pour un emplacement — latitude et longitude sont facultatives —, un identifiant, un nom et des typologies pour un stand, dont l'effectif est facultatif : sans lui le stand tient à une personne, à ajuster sur la grille des ouvertures. Une typologie qu'un stand cite sans qu'elle existe est créée, et l'aperçu la nomme avant que rien ne soit écrit. Dans les trois cas, une colonne que le fichier ne porte pas n'efface rien : renommer des stands par un fichier de trois colonnes ne touche ni leurs horaires ni leur emplacement. L'onglet Animateurs évite de tout ressaisir. Le chemin inverse existe : « Export CSV », juste sous Imports, réécrit les référentiels de l'édition courante dans une archive ZIP, un fichier par référentiel et dans la forme exacte que ces onglets relisent — à cocher ce qu'on emporte. C'est ce qui permet de recopier une édition sur l'autre, ou de corriger en masse dans un tableur puis de réimporter. Un fichier d'animateurs ne se réimporte que dans une édition qui a déjà ses créneaux, faute de quoi un jour d'indisponibilité serait perdu. Enregistrez la feuille au format « CSV UTF-8 » (le .xlsx n'est pas lu, et un fichier enregistré dans un autre encodage est refusé : ses accents sont déjà perdus quand le fichier arrive), déposez-la, et dites quelle colonne est quel champ : une correspondance est proposée d'après les en-têtes, vous la corrigez. L'écran affiche alors, ligne par ligne, ce que l'import ferait — acceptée, rejetée et pourquoi, avec le numéro de ligne du fichier — et rien n'est écrit tant que vous n'avez pas validé. Par défaut il ajoute et met à jour sans supprimer personne, et les jours d'indisponibilité du fichier viennent compléter ceux déjà enregistrés plutôt que les effacer ; deux cases à cocher inversent chacun de ces deux choix. Si vous cochez le remplacement complet, lisez l'avertissement de l'aperçu avant de valider : supprimer une fiche emporte aussi la déclaration de disponibilités de la personne, son accusé de réception du planning publié et son code d'accès à l'espace. Deux refus surprennent au premier essai et sont volontaires : créez d'abord vos créneaux (sans dates d'événement, un jour d'indisponibilité importé serait invisible dans l'espace animateur puis effacé), et donnez une date de naissance à chaque nouvelle fiche (tout le régime mineur / majeur en dépend). Enfin, si deux personnes portent le même nom, ajoutez une colonne identifiant ou e-mail : l'import refuse la ligne plutôt que de choisir à votre place.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.importCsv.colonnes:Une seule colonne est vraiment obligatoire en plus du prénom et du nom : la date de naissance, dont tout le régime mineur / majeur se déduit à la date de chaque créneau. Les compétences, les souhaits et les jours d'indisponibilité tiennent plusieurs valeurs dans une même cellule, séparées par « | » (un point-virgule, une virgule ou un retour à la ligne dans une cellule entre guillemets font aussi l'affaire, mais jamais un « / », qu'une date utilise). Une compétence s'écrit « typologie » ou « typologie:REFERENT » — sans niveau, elle vaut « autonome ». Les dates se lisent aussi bien en JJ/MM/AAAA qu'en AAAA-MM-JJ, mais le fichier d'exemple les écrit en AAAA-MM-JJ à dessein : ouvert puis réenregistré dans un tableur, « 12/09/2026 » revient « 12/09/26 », l'année sur deux chiffres, là où la forme ISO ressort intacte. Une telle date est lue quand même — comme l'année la plus récente possible : jusqu'à aujourd'hui pour une date de naissance (« 00 » vaut 2000, « 95 » vaut 1995), jusqu'à la fin de l'événement pour un jour d'indisponibilité — et l'aperçu signale chaque date ainsi comprise pour que vous la vérifiiez avant d'importer. Si vous devez rouvrir un CSV dans un tableur, passez par son assistant d'import en forçant la colonne des dates au type « Texte ». Une colonne laissée sur « — » n'est pas une colonne vide : le champ correspondant n'est pas touché sur les fiches déjà enregistrées.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.importCsv.exemple:Le bouton « Télécharger un fichier d'exemple » de l'écran d'import donne un CSV court, prêt à ouvrir dans un tableur : les neuf colonnes lues, remplies d'une douzaine de personnes fictives — des mineurs pendant l'événement, un manager, des compétences avec et sans niveau, des souhaits, des jours d'indisponibilité, une adresse e-mail sur certaines lignes seulement. C'est le plus court chemin pour voir à quoi doit ressembler une cellule de compétences ou de jours d'indisponibilité. Ses jours d'indisponibilité et ses typologies sont ceux du scénario de démonstration « festival réaliste » : importé tel quel dans une édition qui a d'autres dates ou d'autres typologies, il se fera rejeter des lignes — recopiez-en la forme, pas le contenu.`,
        },
      ],
      links: [
        {
          route: '/imports',
          label: $localize`:@@aide.lien.importAnimateurs:Imports — onglet Animateurs`,
        },
        { route: '/animateurs', label: $localize`:@@nav.link.animateurs:Animateurs` },
        { route: '/creneaux', label: $localize`:@@nav.link.creneaux:Créneaux` },
        { route: '/typologies', label: $localize`:@@nav.link.typologies:Typologies` },
      ],
    },
    {
      id: 'import-grille-stands',
      icon: 'grid_view',
      title: $localize`:@@aide.importGrille.title:Import de la grille des stands`,
      summary: $localize`:@@aide.importGrille.summary:Verser la matrice du classeur — stands en lignes, jours et créneaux en colonnes — sans la ressaisir : format attendu, ce que fait chaque colonne, et ce qu'un stand importé devient.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.importGrille.format:L'écran « Import de la grille des stands » lit un CSV tel qu'un tableur l'exporte : une première colonne qui nomme le stand par son identifiant ou son nom exact, puis une colonne par jour et par créneau, un effectif par case, vide, « - » ou 0 pour fermé. Deux lignes d'en-tête — les dates, les cellules fusionnées d'un tableur laissant les suivantes vides, puis les bandes « 10h00-12h00 » ou « 10:00-12:00 » — ou une seule, « 2026-07-08 10h00-12h00 ». Le bouton « Télécharger la grille actuelle comme modèle » rend l'édition telle qu'elle est, dans ce format exact : le plus simple est de partir de lui. Le modèle écrit ses bandes avec un « h » à dessein : Excel transforme « 13:00-16:00 » en la date « 30/11/1999 13:16:00 », et la bande est alors perdue.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.importGrille.colonnes:Chaque colonne se pose sur le créneau de même date qui contient ses heures ; une colonne plus étroite que son créneau écrit une fenêtre à ses propres bornes, le reste du créneau restant tel quel. Une colonne sans créneau — une bande de montage que l'édition n'a pas, une colonne de commentaire — est ignorée et listée, sans faire refuser le fichier. Un créneau que le fichier ne nomme pas garde la case actuelle de chaque stand importé, fenêtres comprises : l'import ne réécrit que ce que le fichier dit. Sans aucun créneau dans l'édition, l'import est refusé : créez-les d'abord, en série ou dérivés des horaires des stands.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.importGrille.stands:Un stand accepté est réécrit comme depuis la grille de saisie : tout son horaire suit ses cases, ramené en règles quand un motif se répète, et ses effectifs minimum et maximum suivent la plus petite et la plus grande case. Un stand absent du fichier n'est pas touché, et l'import ne crée pas de stand : un identifiant inconnu, ou un nom porté par deux stands, rejette la ligne en le disant. Rien n'est écrit avant « Importer », et l'écriture est d'un seul tenant.`,
        },
      ],
      links: [
        {
          route: '/imports',
          label: $localize`:@@aide.lien.importGrille:Imports — onglet Grille des stands`,
        },
        { route: '/stands', label: $localize`:@@nav.link.stands:Stands` },
        { route: '/ouvertures', label: $localize`:@@nav.link.ouvertures:Ouvertures des stands` },
        { route: '/creneaux', label: $localize`:@@nav.link.creneaux:Créneaux` },
      ],
    },
  ];
}
