// The data an organiser enters before anything can be computed: editions,
// the reference screens, the opening grid and the bulk imports.
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
      summary: $localize`:@@aide.editions.summary:Un plan canicule est une édition dupliquée, pas une grille parallèle.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.editions.intro:Une édition, c'est un événement — ou une variante de plan — avec ses propres stands, animateurs, créneaux, paramètres et planning. Rien ne circule d'une édition à l'autre : « 2025 » reste consultable pendant qu'on prépare « 2026 ». L'édition consultée est propre à chaque onglet du navigateur et rappelée par le bandeau du haut.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.editions.variante:Dupliquer une édition copie stands, horaires, animateurs et indisponibilités du moment, mais ni les affectations ni les liens d'espace — un lien désigne toujours exactement une édition.`,
        },
        {
          kind: 'steps',
          items: [
            $localize`:@@aide.editions.rituel1:La veille : dupliquer l'édition courante (« 2026 » → « 2026-canicule »).`,
            $localize`:@@aide.editions.rituel2:Appliquer les restrictions dans la copie : horaires, effectifs.`,
            $localize`:@@aide.editions.rituel3:Lancer la résolution dans la copie.`,
            $localize`:@@aide.editions.rituel4:Le matin : basculer d'édition dans le bandeau.`,
            $localize`:@@aide.editions.rituel5:Publier — les animateurs concernés reçoivent le plan et les liens de cette édition.`,
            $localize`:@@aide.editions.rituel6:Au retour à la normale : revenir à l'édition nominale, intacte, et publier à nouveau.`,
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
              text: $localize`:@@aide.data.def.animateurs:Identité, date de naissance, compétences par typologie avec un niveau (débutant, autonome, référent), jours d'indisponibilité. Le régime légal — moins de 16 ans, 16-18 ans, majeur — ne se saisit jamais : il est recalculé à la date de chaque créneau. Par défaut un animateur est disponible, ne saisissez que les absences. Chaque colonne du tableau se trie, en remontant d'abord ce qui reste à faire.`,
            },
            {
              term: $localize`:@@aide.data.term.stands:Stands`,
              text: $localize`:@@aide.data.def.stands:Typologies proposées, effectif minimum et maximum simultané, restriction éventuelle aux majeurs, indicateurs premium et effort, horaires d'ouverture. Les horaires se saisissent en règles valables tous les jours, sur une ligne : « 10:00-12:00, 14:00- », une fin vide courant jusqu'à la fermeture. « Cas particulier » restreint une règle à certains jours ou en fait une fermeture, et des exceptions datées priment sur les règles du jour qu'elles nomment. Chaque fenêtre peut nommer son propre effectif — « @2 » — pour un stand qui tient à deux le matin et à quatre l'après-midi ; sans cela, elle reprend le minimum du stand.`,
            },
            {
              term: $localize`:@@aide.data.term.autres:Emplacements et typologies`,
              text: $localize`:@@aide.data.def.autres:Un emplacement est un lieu géolocalisé auquel rattacher un stand : il sert à éviter les allers-retours d'un créneau à l'autre. Une typologie est le vocabulaire commun entre les compétences d'un animateur et les jeux d'un stand — sans typologie partagée, l'animateur ne peut pas tenir le stand. Désignez aussi, sur la page Paramètres, la typologie « ninja » : ses porteurs sont polyvalents, et le solveur essaie d'en garder un libre sur chaque créneau. Sans elle, cette réserve n'existe pas.`,
            },
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.data.bulk:Chaque écran permet de cocher plusieurs lignes pour les supprimer ou les modifier d'un geste. En modification groupée, un champ laissé vide veut dire « ne pas modifier » : les valeurs propres à chaque ligne sont préservées. Cela vaut aussi pour les horaires : une règle valable pour vingt stands se saisit une fois.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.data.suppression:Avant une suppression, la confirmation annonce ce qui référence la sélection — affectations, ajustements manuels, verrouillages. C'est une information, pas un verrou : aucun chiffre n'empêche de supprimer.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.data.copieHoraires:Quand plusieurs stands suivent la même journée, ne ressaisissez pas. Dans une fiche, « Copier les horaires de… » reprend tout l'horaire d'un autre stand, effectifs de fenêtre compris ; rien n'est enregistré avant « Modifier le stand ». En modification groupée, « Remplacer par ceux d'un stand » fait la même chose sur toute la sélection. Une fenêtre copiée qui dépasse l'effectif maximum du stand cible est signalée avant l'enregistrement.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.data.avertissements:Certaines saisies sont enregistrées avec un avertissement à lire. Les plus fréquentes : une indisponibilité posée hors des dates de l'événement ou sur un jour sans créneau, une date de naissance qui rend l'animateur mineur pendant l'événement, un créneau qu'aucun stand n'est ouvert à couvrir, une fenêtre horaire qui ne recoupe aucun créneau. Une modification ne signale que ce qu'elle change, et tant que l'édition n'a aucun créneau ces avertissements se taisent.`,
        },
      ],
      links: [
        { route: '/animateurs', label: $localize`:@@nav.link.animateurs:Animateurs` },
        { route: '/stands', label: $localize`:@@nav.link.stands:Stands` },
        { route: '/emplacements', label: $localize`:@@nav.link.emplacements:Emplacements` },
        { route: '/typologies', label: $localize`:@@nav.link.typologies:Typologies` },
        {
          route: '/diagnostic',
          queryParams: { onglet: 'besoin' },
          label: $localize`:@@aide.link.staffing:Besoin en animateurs`,
        },
      ],
    },
    {
      id: 'creneaux-et-journees-types',
      icon: 'schedule',
      title: $localize`:@@aide.creneaux.title:Créneaux et journées types`,
      summary: $localize`:@@aide.creneaux.summary:Le découpage du temps que le solveur remplit, et comment l'écrire sans le taper jour par jour.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.creneaux.intro:Un créneau, c'est un jour, une date et deux heures. C'est le découpage que le solveur remplit : sur chaque créneau, chaque stand ouvert reçoit autant de sièges que sa fenêtre d'ouverture en nomme. Les créneaux sont communs à tous les stands — on n'en crée jamais par stand, ce sont les horaires qui disent ce que chacun ouvre. Et les dates des créneaux sont les dates de l'édition.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.creneaux.grille:Une grille de créneaux est toujours faite de vacations : des tranches de travail réelles. La page déclare ce que la grille contient — des amplitudes encore à découper, ou des vacations finales — parce que chaque verdict en dépend : un chevauchement est une erreur entre amplitudes, et la forme normale de vacations décalées. Le « Contrôle de la grille » relit ce verdict à chaque visite.`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.creneaux.term.journeesTypes:Les journées types`,
              text: $localize`:@@aide.creneaux.def.journeesTypes:Décrivez une fois chaque sorte de journée — « Jour normal », « Nocturne », « Montage » — par ses vacations sur une ligne : « 09:00-12:00, 12:00-13:00 R, 13:00-14:00 R, 14:00-20:00 », un R marquant un relais repas où le stand ne tient que la moitié de ses sièges. Un calendrier pose ensuite une journée type sur chaque date.`,
            },
            {
              term: $localize`:@@aide.creneaux.term.appliquer:Appliquer le calendrier`,
              text: $localize`:@@aide.creneaux.def.appliquer:Écrit les créneaux, après un aperçu chiffré. Un créneau identique garde ses sièges, un créneau en trop sur une date gouvernée est supprimé, une date sans journée type n'est pas touchée. Une journée type modifiée après coup ne change rien tant qu'on ne réapplique pas : ses dates passent « en écart ».`,
            },
            {
              term: $localize`:@@aide.creneaux.term.serie:Créer une série`,
              text: $localize`:@@aide.creneaux.def.serie:La même chose sans mémoire, pour un besoin ponctuel : les créneaux sur une ligne, les jours couverts, un aperçu, puis l'écriture. Aucune journée type n'est créée.`,
            },
            {
              term: $localize`:@@aide.creneaux.term.deriver:Dériver des horaires des stands`,
              text: $localize`:@@aide.creneaux.def.deriver:Quand les stands ont déjà leurs horaires, la grille peut en découler : une coupure à chaque heure d'ouverture ou de fermeture, un créneau sur chaque tranche où au moins un stand est ouvert. Prévisualisé avant d'être ajouté ou mis à la place de la grille.`,
            },
            {
              term: $localize`:@@aide.creneaux.term.reconnaitre:Reconnaître depuis les créneaux`,
              text: $localize`:@@aide.creneaux.def.reconnaitre:Le chemin inverse, sur une grille déjà écrite : l'application en déduit les journées types et le calendrier qui les porte.`,
            },
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.creneaux.enchainement:Deux créneaux qui se touchent ne s'enchaînent pas forcément pour une même personne : c'est la pause minimale entre vacations, réglée dans les paramètres légaux, qui décide.`,
        },
      ],
      links: [
        { route: '/creneaux', label: $localize`:@@nav.link.creneaux:Créneaux` },
        { route: '/stands', label: $localize`:@@nav.link.stands:Stands` },
        { route: '/parametres', label: $localize`:@@nav.link.parametres:Paramètres` },
      ],
    },
    {
      id: 'ouvertures-des-stands',
      icon: 'storefront',
      title: $localize`:@@aide.ouvertures.title:Ouvertures des stands`,
      summary: $localize`:@@aide.ouvertures.summary:Voir ce que le solveur lira vraiment, et saisir les effectifs comme dans un tableur.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.ouvertures.intro:Ouvrez cet écran avant toute résolution. Il montre ce que le solveur lira une fois les règles étendues et les exceptions appliquées, et signale les trois erreurs de saisie habituelles : un stand finalement ouvert aucun jour, une fenêtre hors des heures du jour, une plage trop courte pour valoir une vacation. La fiche de chaque stand liste aussi ses propres anomalies.`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.ouvertures.term.parDate:Par date`,
              text: $localize`:@@aide.ouvertures.def.parDate:La vue de référence : une colonne par tranche de besoin, une case par stand. Les colonnes suivent les changements d'effectif, pas les vacations — un stand qui change d'effectif à 19 h scinde le créneau 14-20 en deux colonnes pour tout le monde.`,
            },
            {
              term: $localize`:@@aide.ouvertures.term.parJourneeType:Par journée type`,
              text: $localize`:@@aide.ouvertures.def.parJourneeType:Disponible dès que l'édition a des journées types : une colonne par vacation, une case qui vaut d'un coup pour toutes les dates gouvernées. Soixante colonnes deviennent une quinzaine. Une case « ≠ » signale des dates qui ne disent pas la même chose ; la retaper les aligne.`,
            },
            {
              term: $localize`:@@aide.ouvertures.term.journee:Journée`,
              text: $localize`:@@aide.ouvertures.def.journee:Une journée sur l'axe du temps : une ligne par stand, chaque ouverture en bloc avec son effectif. Une fenêtre déclarée à une heure qu'aucune vacation ne couvre apparaît hachurée — la seule erreur que les grilles ne montrent pas.`,
            },
            {
              term: $localize`:@@aide.ouvertures.term.saisir:Saisir`,
              text: $localize`:@@aide.ouvertures.def.saisir:La même grille en écriture : l'effectif à tenir dans chaque case, « - » ou 0 pour fermer, une case vidée gardant sa valeur. Les gestes d'un tableur — flèches, Entrée, collage d'un bloc, recopie d'un jour sur les autres. Les en-têtes et la colonne des stands restent en place pendant le défilement.`,
            },
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.ouvertures.enregistrement:À l'enregistrement, chaque stand modifié voit tout son horaire réécrit depuis ses cases, ramené en règles quand un motif se répète ; ses effectifs minimum et maximum suivent la plus petite et la plus grande case. Une case en pointillés porte une borne qu'aucune colonne ne suit : enregistrée telle quelle elle garde ses fenêtres, retapée elle vaut pour toute la colonne. « Aligner les fenêtres partielles » les étend toutes, en disant d'abord combien d'heures cela ajoute.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.ouvertures.fermetures:Un stand n'a pas à déclarer ses fermetures : tout jour non déclaré est fermé. « Compacter les horaires », sur la page Stands, retire les fermetures devenues inutiles après avoir vérifié qu'aucune ouverture ne bouge.`,
        },
      ],
      links: [
        { route: '/ouvertures', label: $localize`:@@nav.link.ouvertures:Ouvertures des stands` },
        { route: '/stands', label: $localize`:@@nav.link.stands:Stands` },
        { route: '/creneaux', label: $localize`:@@nav.link.creneaux:Créneaux` },
      ],
    },
    {
      id: 'competences',
      icon: 'grid_on',
      title: $localize`:@@aide.competences.title:Grille des compétences`,
      summary: $localize`:@@aide.competences.summary:Saisir l'appréciation de chacun sur chaque typologie en une seule grille, au clavier, en regard de ses souhaits.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.competences.grille:La fiche animateur permet de saisir les appréciations une personne à la fois ; l'écran « Compétences » les montre toutes d'un coup, animateurs en lignes et typologies en colonnes. Une case se change d'un clic (les niveaux défilent) ou d'une touche : 0 vide la case, 1, 2 et 3 posent le niveau. Les flèches, Entrée, Début et Fin déplacent la sélection comme dans un tableur, et la flèche bas depuis le filtre entre dans la grille.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.competences.enregistrer:Rien n'est écrit avant « Enregistrer » : les cases changées sont encadrées, le bouton compte les fiches concernées, et quitter la page sans enregistrer demande confirmation. Un cœur dans une case signale que l'animateur souhaite cette typologie — l'appréciation se saisit en regard du souhait, sans que l'un décide de l'autre. Pendant une résolution, la saisie est verrouillée.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.competences.concurrence:L'enregistrement écrit une fiche par ligne modifiée. Si une autre session a modifié une fiche entre-temps, cette ligne seule est refusée, les autres sont écrites, et l'écran propose de recharger ou d'écraser en connaissance de cause.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.competences.csv:« Exporter en CSV » rend la grille telle quelle : une colonne « animateur » avec l'identifiant — jamais le nom —, puis une colonne par typologie, et dans chaque case DEBUTANT, AUTONOME, REFERENT ou rien. « Importer un CSV » relit ce format, après un aperçu ligne par ligne. Le point qui compte : une case vide du fichier laisse l'appréciation telle qu'elle est. L'import ajoute et met à jour, il ne retire jamais — retirer une appréciation reste un geste de la grille.`,
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
      summary: $localize`:@@aide.importCsv.summary:Reprendre le tableur des bénévoles sans le ressaisir : les onglets disponibles, le format attendu, et les deux refus qui surprennent la première fois.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.importCsv.ecran:Tous les fichiers qui remplissent une édition sont sur l'écran « Imports », un onglet par référentiel et dans l'ordre où les données se tiennent. Rien n'est écrit tant que vous n'avez pas validé l'aperçu, et une colonne que le fichier ne porte pas n'efface rien : renommer des stands par un fichier de trois colonnes ne touche ni leurs horaires ni leur emplacement.`,
        },
        {
          kind: 'list',
          items: [
            $localize`:@@aide.importCsv.onglet.typologies:Typologies, emplacements, stands : un identifiant et un libellé suffisent. Latitude et longitude sont facultatives pour un emplacement ; l'effectif l'est pour un stand, qui tient alors à une personne. Une typologie qu'un stand cite sans qu'elle existe est créée, et l'aperçu la nomme.`,
            $localize`:@@aide.importCsv.onglet.creneaux:Créneaux et journées types : un créneau se reconnaît à sa date et à ses deux heures, si bien qu'un fichier rejoué met la grille à jour au lieu de la doubler. Une journée type se reconnaît à son nom. Attention : importer des journées types ne déplace aucun créneau — la grille ne bouge qu'en appliquant le calendrier.`,
            $localize`:@@aide.importCsv.onglet.animateurs:Animateurs : le plus utile, détaillé ci-dessous.`,
            $localize`:@@aide.importCsv.onglet.grille:Grille des stands : la matrice du classeur, stands en lignes, jours et créneaux en colonnes. Elle a sa propre section dans ce guide.`,
            $localize`:@@aide.importCsv.onglet.scenario:Scénario : un fichier YAML qui porte l'édition entière et la remplace au lieu de la compléter.`,
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.importCsv.exports:L'écran « Exports » fait le chemin inverse : une archive ZIP, un fichier par référentiel, dans la forme exacte que ces onglets relisent. C'est ce qui permet de recopier une édition sur l'autre, ou de corriger en masse dans un tableur puis de réimporter.`,
        },
        {
          kind: 'steps',
          items: [
            $localize`:@@aide.importCsv.etape1:Enregistrez la feuille au format « CSV UTF-8 ». Le .xlsx n'est pas lu, et un autre encodage est refusé — ses accents sont déjà perdus.`,
            $localize`:@@aide.importCsv.etape2:Déposez le fichier et dites quelle colonne est quel champ. Une correspondance est proposée d'après les en-têtes, vous la corrigez.`,
            $localize`:@@aide.importCsv.etape3:Lisez l'aperçu : chaque ligne est annoncée acceptée ou rejetée, avec son numéro de ligne et le motif.`,
            $localize`:@@aide.importCsv.etape4:Validez. Par défaut l'import ajoute et met à jour sans supprimer personne, et les jours d'indisponibilité complètent ceux déjà enregistrés. Deux cases inversent chacun de ces choix.`,
          ],
        },
        {
          kind: 'callout',
          title: $localize`:@@aide.importCsv.callout.title:Les deux refus qui surprennent`,
          text: $localize`:@@aide.importCsv.callout.text:Créez vos créneaux d'abord : sans dates d'événement, un jour d'indisponibilité importé serait invisible dans l'espace animateur, puis effacé. Et donnez une date de naissance à chaque nouvelle fiche : tout le régime mineur / majeur en dépend. Si deux personnes portent le même nom, ajoutez une colonne identifiant ou e-mail — l'import refuse la ligne plutôt que de choisir à votre place.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.importCsv.colonnes:Les compétences, les souhaits et les jours d'indisponibilité tiennent plusieurs valeurs dans une même cellule, séparées par « | ». Une compétence s'écrit « typologie » ou « typologie:REFERENT » ; sans niveau, elle vaut « autonome ». Les dates se lisent en JJ/MM/AAAA comme en AAAA-MM-JJ, mais préférez la forme ISO : un tableur transforme « 12/09/2026 » en « 12/09/26 », que l'import lit alors comme l'année la plus récente possible — et l'aperçu signale chaque date ainsi comprise.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.importCsv.exemple:« Télécharger un fichier d'exemple » donne un CSV court, prêt à ouvrir dans un tableur : les neuf colonnes lues, remplies d'une douzaine de personnes fictives. C'est le plus court chemin pour voir à quoi doit ressembler une cellule de compétences. Son contenu vient du scénario de démonstration : recopiez-en la forme, pas les valeurs.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.importCsv.remplacement:Si vous cochez le remplacement complet, lisez l'avertissement de l'aperçu : supprimer une fiche emporte aussi la déclaration de disponibilités de la personne, son accusé de réception et son code d'accès à l'espace.`,
        },
      ],
      links: [
        {
          route: '/imports',
          label: $localize`:@@aide.lien.importAnimateurs:Imports — onglet Animateurs`,
        },
        { route: '/exports', label: $localize`:@@nav.link.exports:Exports` },
        { route: '/animateurs', label: $localize`:@@nav.link.animateurs:Animateurs` },
        { route: '/creneaux', label: $localize`:@@nav.link.creneaux:Créneaux` },
      ],
    },
    {
      id: 'import-grille-stands',
      icon: 'grid_view',
      title: $localize`:@@aide.importGrille.title:Import de la grille des stands`,
      summary: $localize`:@@aide.importGrille.summary:Verser la matrice du classeur — stands en lignes, jours et créneaux en colonnes — sans la ressaisir.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.importGrille.format:L'onglet lit un CSV tel qu'un tableur l'exporte : une première colonne qui nomme le stand par son identifiant ou son nom exact, puis une colonne par jour et par créneau, un effectif par case, vide, « - » ou 0 pour fermé. Deux lignes d'en-tête — les dates, puis les bandes « 10h00-12h00 » — ou une seule, « 2026-07-08 10h00-12h00 ». Le plus simple est de partir de « Télécharger la grille actuelle comme modèle ».`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.importGrille.heures:Le modèle écrit ses bandes avec un « h » à dessein : Excel transforme « 13:00-16:00 » en une date, et la bande est alors perdue.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.importGrille.colonnes:Chaque colonne se pose sur le créneau de même date qui contient ses heures ; une colonne plus étroite écrit une fenêtre à ses propres bornes. Une colonne sans créneau est ignorée et listée, sans faire refuser le fichier. Un créneau que le fichier ne nomme pas garde la case actuelle de chaque stand importé. Sans aucun créneau dans l'édition, l'import est refusé.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.importGrille.stands:Un stand accepté est réécrit comme depuis la grille de saisie : tout son horaire suit ses cases, et ses effectifs minimum et maximum suivent la plus petite et la plus grande. Un stand absent du fichier n'est pas touché, et l'import n'en crée aucun : un identifiant inconnu, ou un nom porté par deux stands, rejette la ligne en le disant.`,
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
