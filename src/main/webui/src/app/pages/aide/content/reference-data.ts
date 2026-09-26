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
        {
          kind: 'paragraph',
          text: $localize`:@@aide.editions.vider:« Vider cette édition », en bas de la page Éditions, supprime les stands, créneaux, animateurs, affectations, ajustements manuels, verrouillages, validations de journées, demandes d'échange, consignes et préréglages de consigne de l'édition consultée, après avoir fait recopier son nom. Restent ses typologies, emplacements, journées types, instantanés et paramètres, les autres éditions et les réglages de l'instance. Refusé pendant une résolution et tant qu'une famille est figée.`,
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
      id: 'gel-du-referentiel',
      icon: 'lock',
      title: $localize`:@@aide.gel.title:Gel du référentiel`,
      summary: $localize`:@@aide.gel.summary:Figer une famille de fiches une fois sa préparation terminée, pour qu'aucune retouche ne fasse bouger le planning.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.gel.intro.parametres:Une fois les stands prêts, ou les créneaux arrêtés, on les fige : plus aucun chemin ne les modifie — formulaire, modification en masse, import de fichier, assistant — jusqu'à la levée du gel. Le gel se pose famille par famille, dans l'édition courante, depuis l'onglet Édition des Paramètres — l'État de l'édition en rappelle l'état et y mène. L'application le propose après le premier calcul et après la première publication, sans jamais l'imposer.`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.gel.stands.terme:Stands`,
              text: $localize`:@@aide.gel.stands.def:Création, suppression, effectifs, réserve aux majeurs, typologies proposées et horaires. Le nom, l'emplacement, premium et effort restent modifiables.`,
            },
            {
              term: $localize`:@@aide.gel.creneaux.terme:Créneaux`,
              text: $localize`:@@aide.gel.creneaux.def:Création, suppression, date, heures, relais repas, et ce qui génère la grille : journées types appliquées, séries, dérivation. Les journées types elles-mêmes restent modifiables.`,
            },
            {
              term: $localize`:@@aide.gel.typologies.terme:Typologies et emplacements`,
              text: $localize`:@@aide.gel.typologies.def:Création, suppression, plafond par typologie et typologie ninja. Libellés, descriptions, noms et positions restent modifiables.`,
            },
            {
              term: $localize`:@@aide.gel.competences.terme:Compétences`,
              text: $localize`:@@aide.gel.competences.def:Les appréciations des animateurs déjà inscrits, par la fiche, la grille ou l'import. Un nouvel animateur arrive avec les siennes.`,
            },
          ],
        },
        {
          kind: 'callout',
          title: $localize`:@@aide.gel.ouvert.titre:Ce qui reste ouvert`,
          text: $localize`:@@aide.gel.ouvert.texte:Disponibilités, souhaits, déclarations, e-mails, ajustements manuels, verrouillages et consignes. Un stand à fermer en urgence se ferme par une consigne, sans lever le gel. L'import d'un scénario et la remise à zéro, qui remplacent tout, sont refusés tant qu'une famille est figée.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.gel.lever:Un champ figé s'affiche en lecture seule, avec un cadenas et la date du gel ; « Lever le gel » demande confirmation et rappelle si le planning est déjà publié. Pose et levée figurent dans l'Historique des actions. Le gel n'est pas le verrouillage : le verrou fige des affectations d'un planning calculé, le gel fige les fiches dont il est calculé. Une édition dupliquée repart sans gel.`,
        },
      ],
      links: [
        { route: '/parametres', label: $localize`:@@nav.link.parametres:Paramètres` },
        {
          route: '/consignes-solveur',
          queryParams: { onglet: 'verrouillages' },
          label: $localize`:@@consignesSolveur.onglet.verrouillages:Verrouillages`,
        },
        {
          route: '/consignes-solveur',
          queryParams: { onglet: 'consignes' },
          label: $localize`:@@consignesSolveur.onglet.consignes:Consignes`,
        },
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
              text: $localize`:@@aide.data.def.animateurs:Identité, date de naissance, compétences par typologie avec un niveau (débutant, autonome, référent), jours d'indisponibilité. Le régime légal — moins de 16 ans, 16-18 ans, majeur — ne se saisit jamais : il est recalculé à la date de chaque créneau. Par défaut un animateur est disponible, ne saisissez que les absences : le plus court est un clic sur le jour, dans la frise de sa fiche ; dans le formulaire, un clic sur un jour de la frise le marque absent, et « Absent du » … « au » pose une plage d'un geste. Les appréciations se saisissent en masse dans la grille des compétences, que « Saisir dans la grille (ligne pré-filtrée) » ouvre sur la personne. La colonne « Âge / régime » dit l'âge au premier jour de l'édition. Le nom ouvre la fiche de la personne, qui garde le filtre et le tri de la liste pour « Précédent » et « Suivant » ; « Filtrer » réduit la liste aux mineurs, aux managers, à une compétence ou à un souhait, chaque filtre restant affiché au-dessus du tableau. Une fois un planning calculé, la colonne « Postes » compte les sièges de chacun.`,
            },
            {
              term: $localize`:@@aide.data.term.stands:Stands`,
              text: $localize`:@@aide.data.def.stands:Typologies proposées, effectif minimum et maximum simultané, restriction éventuelle aux majeurs, indicateurs premium et effort, horaires d'ouverture. « Ajouter » guide la création en quatre étapes — identité, typologies, lieu, horaires : les créneaux de l'édition sont proposés ouverts, on décoche ceux où le stand est fermé, on tape combien de personnes il tient sur chacun et on décoche les jours de la semaine où il n'ouvre pas ; aucune règle à écrire. Le tableau se trie sur chaque colonne ; « Ouvert » compte ses jours ouverts et ses postes, et « Couverture » apparaît une fois un planning calculé. « Édition groupée » modifie d'un coup les stands cochés, ou à défaut tous ceux que le tableau affiche ; « Saisir en grille », « Importer la grille » et « Compacter les horaires » sont rangés dans le menu « Plus ». Le nom ouvre la fiche du stand : sa grille jour × créneau se modifie sur place, comme sa ligne dans la grille des Horaires des stands — une case vide ferme, un bloc se colle depuis un tableur, « Recopier ce jour » pose un jour sur les autres, Ctrl+D reprend le jour du dessus —, puis ses anomalies d'ouverture, ses sièges après calcul (un siège vide s'ouvre dans le panneau du siège de la page Planning), « Comparer avec… » et l'historique de ses modifications. « Modifier l'identité » ne montre que les champs d'identité. La section « Règles », repliée, donne la forme condensée que la grille enregistre — règles valables tous les jours sur une ligne comme « 10:00-12:00, 14:00- », cas particuliers, exceptions datées, fenêtre qui nomme son propre effectif « @2 » — pour qui veut la lire ou l'écrire avec « Modifier les règles ».`,
            },
            {
              term: $localize`:@@aide.data.term.lieux:Lieux et typologies`,
              text: $localize`:@@aide.data.def.autres:Un lieu — un emplacement — est un endroit géolocalisé auquel rattacher un stand : il sert à éviter les allers-retours d'un créneau à l'autre. Les lieux sont l'onglet « Lieux » de la page Stands : une carte les montre tous avec leurs stands, et faire glisser un marqueur y corrige la position sans ouvrir de fiche ; le tableau, trié à volonté, dit quels stands sont rattachés à chacun. Le formulaire d'un stand propose « Nouveau lieu… » sans quitter la page. Une typologie est le vocabulaire commun entre les compétences d'un animateur et les jeux d'un stand — sans typologie partagée, l'animateur ne peut pas tenir le stand. Cochez aussi, dans la colonne « Typologie ninja » de l'écran Typologies, la typologie dont les porteurs sont polyvalents : le solveur essaie d'en garder un libre sur chaque créneau. Sans elle, cette réserve n'existe pas.`,
            },
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.data.typologiesOrphelines:L'écran Typologies compte, pour chacune, les animateurs qui la maîtrisent (les polyvalents à part), ceux qui l'ont souhaitée et les stands qui la proposent ; chaque chiffre mène à la liste qu'il compte, et l'icône de grille aux appréciations de cette typologie. Un badge « Orpheline » signale une typologie proposée par un stand que personne ne maîtrise : seul un polyvalent peut alors tenir ce stand. « Fragile » veut dire une seule personne, « Inutilisée » aucun stand. Une fois un planning calculé, trois colonnes disent ce qu'il en a fait : les postes, les heures, et les personnes « Affectés sans la compétence ». L'État de l'édition compte les orphelines, avant tout calcul.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.data.identifiants:Aucun identifiant ne se saisit : l'application en attribue un à chaque fiche qu'elle crée — A12 pour un animateur, S3 pour un stand, T2 pour une typologie, L1 pour un emplacement, C4 pour un ajustement. Un stand, une typologie ou un emplacement peut porter en plus un code, facultatif et unique dans l'édition, comme « STRATEGIE » ou « PAVILLON » : c'est la clé lisible que citent les fichiers d'import et qu'écrivent les exports, qui ne portent aucun identifiant — sans code, une ligne y est désignée par son nom.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.data.tableaux:Les tableaux gardent les réflexes du tableur. Chaque colonne se trie d'un clic sur son en-tête, et les identifiants suivent l'ordre naturel : T2 avant T10. Le nom d'une ligne l'ouvre ; son menu la modifie, la duplique ou la supprime. « Exporter cette liste » télécharge en CSV ce que le tableau montre, filtres et tri compris. Un bloc copié d'un tableur se colle sur une ligne (Ctrl+V) : les colonnes se remplissent dans l'ordre du tableau, ou selon la première ligne si elle les nomme — « Id » y désigne les lignes par leur identifiant —, et un aperçu montre chaque case avant qu'« Appliquer » n'enregistre. Un avertissement d'enregistrement reste marqué sur sa ligne tant qu'une nouvelle saisie ne l'a pas levé.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.data.ordre:Les référentiels se remplissent dans un ordre : les typologies d'abord, puis les créneaux qui donnent ses dates à l'édition, les stands qui proposent les typologies et ouvrent sur les créneaux, et les animateurs en dernier. Un tableau vide le rappelle, mène à l'étape précédente quand elle manque, et, sur une édition encore vide, propose « Charger un exemple ».`,
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
          text: $localize`:@@aide.data.brouillon:La fiche animateur, la fiche stand et le formulaire de consigne gardent un brouillon de la saisie en cours. Après un rechargement, une session expirée ou un onglet fermé, rouvrir le même formulaire propose « Reprendre » ou « Ignorer » ; si la fiche a changé entre-temps, le bandeau le dit et l'enregistrement vous fera choisir entre recharger et écraser. Fermer un formulaire modifié — Échap, clic à côté, « Annuler » — demande confirmation. Le brouillon s'efface à l'enregistrement, à l'abandon, à la déconnexion et au bout de 24 h ; celui d'une fiche animateur ne survit pas à la fermeture de l'onglet, et celui d'une fiche supprimée entre-temps est effacé avec un message.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.data.avertissements:Certaines saisies sont enregistrées avec un avertissement à lire. Les plus fréquentes : une indisponibilité posée hors des dates de l'événement ou sur un jour sans créneau, une date de naissance qui rend l'animateur mineur pendant l'événement, un créneau qu'aucun stand n'est ouvert à couvrir, une fenêtre horaire qui ne recoupe aucun créneau. Une modification ne signale que ce qu'elle change, et tant que l'édition n'a aucun créneau ces avertissements se taisent.`,
        },
      ],
      links: [
        { route: '/animateurs', label: $localize`:@@nav.link.animateurs:Animateurs` },
        { route: '/stands', label: $localize`:@@nav.link.stands:Stands` },
        {
          route: '/stands',
          queryParams: { onglet: 'lieux' },
          label: $localize`:@@stands.onglet.lieuxCourt:Lieux`,
        },
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
          text: $localize`:@@aide.creneaux.grille:Une grille de créneaux est toujours faite de vacations : des tranches de travail réelles. La page déclare ce que la grille contient — des amplitudes encore à découper, ou des vacations finales — parce que chaque verdict en dépend : un chevauchement est une erreur entre amplitudes, et la forme normale de vacations décalées. Le « Contrôle de la grille » s'exécute seul, à chaque visite et après chaque écriture, et s'affiche en bandeau sous le calendrier, le détail à déplier. Chaque créneau de la liste compte ses « Stands ouverts · postes », un lien vers les horaires des stands ce jour-là.`,
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
              term: $localize`:@@aide.creneaux.term.serie:Appliquer sans mémoriser`,
              text: $localize`:@@aide.creneaux.def.serie:La même chose sans mémoire, pour un besoin ponctuel : dans le dialogue d'une journée type, ce bouton pose ses créneaux sur les jours choisis, après un aperçu, sans garder la journée type. Les relais repas ne suivent pas.`,
            },
            {
              term: $localize`:@@aide.creneaux.term.deriver:Dériver des horaires des stands`,
              text: $localize`:@@aide.creneaux.def.deriver:Quand les stands ont déjà leurs horaires, la grille peut en découler : une coupure à chaque heure d'ouverture ou de fermeture, un créneau sur chaque tranche où au moins un stand est ouvert. Prévisualisé, puis ajouté à la grille ; « Remplacer la grille… » la met à la place de l'actuelle, après une confirmation qui dit que le planning calculé part avec elle. Avec la reconnaissance, il se trouve dans le menu « Autres façons de créer la grille », à côté de l'ajout d'un créneau isolé.`,
            },
            {
              term: $localize`:@@aide.creneaux.term.reconnaitre:Reconnaître les journées types depuis les créneaux`,
              text: $localize`:@@aide.creneaux.def.reconnaitre:Le chemin inverse, sur une grille déjà écrite : l'application en déduit les journées types et le calendrier qui les porte.`,
            },
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.creneaux.feries:Les jours fériés sont marqués partout où l'on pose une date — la table des créneaux, le calendrier des journées types, l'aperçu d'une série, le formulaire d'un créneau — avec leur nom : Fête nationale, Assomption… Rien n'est interdit, un festival ouvre légitimement le 14 juillet. Mais un mineur ne travaille pas un jour férié : quand l'édition en compte un ce jour-là, le contrôle de la grille le rappelle, pour qu'on y pense avant le calcul plutôt qu'au diagnostic. Seuls les onze jours du Code du travail en métropole sont connus, sans les fériés d'Alsace-Moselle ni d'outre-mer.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.creneaux.enchainement:Deux créneaux qui se touchent s'enchaînent pour une même personne : rien n'impose d'écart entre deux vacations. Ce qui décide, c'est la longueur de la séquence ainsi formée — au-delà de six heures, elle doit une pause, prise comme un trou ou relayée par un collègue du stand.`,
        },
      ],
      links: [
        { route: '/creneaux', label: $localize`:@@nav.link.creneaux:Créneaux` },
        { route: '/stands', label: $localize`:@@nav.link.stands:Stands` },
        {
          route: '/regles',
          queryParams: { onglet: 'legal' },
          label: $localize`:@@nav.link.regles:Règles du planning`,
        },
      ],
    },
    {
      id: 'horaires-des-stands',
      icon: 'storefront',
      title: $localize`:@@aide.ouvertures.title:Horaires des stands`,
      summary: $localize`:@@aide.ouvertures.summary:Saisir l'effectif de chaque stand sur chaque créneau, comme dans un tableur, et voir aussitôt ce que le solveur lira.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.ouvertures.intro:Ouvrez cet écran avant toute résolution. On y saisit et on y relit au même endroit : chaque case dit ce que le solveur lira une fois les règles étendues, les exceptions et la consigne du jour appliquées. La synthèse et les anomalies sont au-dessus de la grille : un stand finalement ouvert aucun jour, une fenêtre hors des heures du jour, une plage trop courte pour valoir une vacation. Chaque anomalie mène à la fiche du stand, ou à la grille des créneaux quand c'est elle qui est en cause.`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.ouvertures.term.grille:Grille`,
              text: $localize`:@@aide.ouvertures.def.grille:Une ligne par stand, une colonne par tranche de besoin, l'effectif dans chaque case. Les colonnes suivent les changements d'effectif, pas les vacations — un stand qui change d'effectif à 19 h scinde le créneau 14-20 en deux colonnes pour tout le monde. Une case vide est fermée : on la vide, on tape « - » ou 0 pour fermer. Un chiffre en italique n'a pas été saisi : le stand, sans horaire déclaré, suit sa règle et ouvre par défaut. Les gestes d'un tableur — flèches, Entrée, collage d'un bloc, recopie d'un jour sur les autres jours affichés, reprise de la ligne du dessus, application d'une case à toute sa colonne. Ces deux-là ont chacune un bouton, qui apparaît au survol en tête de ligne et en tête de colonne, et un raccourci : Ctrl+D et Ctrl+Maj+Bas. Le nom d'un stand mène à sa fiche ; « Du » et « Au » réduisent la grille à quelques jours.`,
            },
            {
              term: $localize`:@@aide.ouvertures.term.couches:Ce que dit une case`,
              text: $localize`:@@aide.ouvertures.def.couches:Derrière le chiffre, des barres fines : en bas l'ouverture retenue, pleine pour une règle récurrente, rouge pour une exception datée ; en haut les horaires du stand avant la consigne ; sur toute la hauteur, teintée, la bande que la consigne du jour ferme ; en pointillé, un créneau que la consigne a ajouté. « Afficher dans les cases » masque chacune de ces couches, et l'adresse garde le choix. Une case modifiée montre tout de suite l'ouverture qu'elle écrira. Un clic sur une case l'explique en une phrase au-dessus de la grille, par exemple « Ouvert 14:00–18:00 : règle récurrente 14:00–20:00, amputé par la consigne « Plan canicule » 18:00–20:00 », avec les sièges du jour, une fenêtre hors de tout créneau s'il y en a une, et trois liens : la fiche du stand, la journée type du jour et la consigne du jour.`,
            },
            {
              term: $localize`:@@aide.ouvertures.term.parJourneeType:Par journée type`,
              text: $localize`:@@aide.ouvertures.def.parJourneeType:Disponible dès que l'édition a des journées types : une colonne par vacation, une case qui vaut d'un coup pour toutes les dates gouvernées. Soixante colonnes deviennent une quinzaine. Une case « ≠ » signale des dates qui ne disent pas la même chose ; la retaper les aligne. Tant qu'elle diverge, elle ne dit rien à propager : appliquer sa colonne est refusé plutôt que de trancher pour vous.`,
            },
            {
              term: $localize`:@@aide.ouvertures.term.comparer:Comparer`,
              text: $localize`:@@aide.ouvertures.def.comparer:Deux à huit stands posés sur les mêmes jours et les mêmes colonnes, par exemple les buvettes qui devraient avoir les mêmes horaires. On les choisit un par un ou par typologie ; le premier sert de référence, et un autre peut prendre ce rôle. Chaque case qui s'écarte de la référence est encadrée et dit la nature de l'écart — ouverture, heures ou effectif. La comparaison porte sur ce que le solveur lira : une règle et des exceptions datées qui ouvrent de la même façon ne font aucun écart. La synthèse compte, par stand, les jours en écart et dit le premier ; « Seulement les jours qui diffèrent » masque les autres. Sous la grille, les règles et exceptions de chaque stand côte à côte, une règle absente de la référence ou manquante étant signalée. Un jour sous consigne est marqué. Le comparateur n'écrit rien : « Copier les horaires de la référence vers… » ouvre la modification en masse des stands, réglée sur « Remplacer par ceux d'un stand », qui attend son propre enregistrement. On y arrive aussi depuis la page Stands, par la sélection ou par « Comparer avec… » dans la fiche d'un stand ; l'adresse garde la sélection.`,
            },
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.ouvertures.anciennesVues:La journée posée sur l'axe du temps est devenue la page Planning, une fois un planning calculé ; avant, une ancienne adresse de cette vue ouvre la grille sur ce seul jour. Le calendrier combiné est devenu le rendu des cases : son adresse ouvre la grille avec les mêmes couches.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.ouvertures.enregistrement:À l'enregistrement, chaque stand modifié voit tout son horaire réécrit depuis ses cases, ramené en règles quand un motif se répète ; ses effectifs minimum et maximum suivent la plus petite et la plus grande case. Une case en pointillés porte une borne qu'aucune colonne ne suit : enregistrée telle quelle elle garde ses fenêtres, retapée elle vaut pour toute la colonne. « Aligner les fenêtres partielles » les étend toutes, en disant d'abord combien d'heures cela ajoute.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.ouvertures.chevauchements:Trois autres points sont signalés pour information, sans jamais bloquer l'enregistrement : deux règles d'un même stand qui se recouvrent (c'est alors l'effectif le plus haut qui compte), une règle qu'aucun jour n'applique parce qu'une règle plus précise ou une exception datée la remplace partout, deux fenêtres d'une même règle qui se recouvrent à des effectifs différents. L'éditeur de règles de la fiche stand les dit en direct sous la règle concernée, et indique quand une règle plus précise prime sur une autre, par exemple une fermeture le week-end sur une ouverture de tous les jours.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.ouvertures.feries:Un jour férié porte la pastille « Férié » en tête de colonne, et sa colonne est teintée. Son nom et ce qu'il change — les mineurs n'y travaillent pas, les heures y sont comptées fériées — se lisent au survol ou au clavier. C'est une information : rien n'empêche d'ouvrir ce jour-là.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.ouvertures.fermetures:Un stand n'a pas à déclarer ses fermetures : tout jour non déclaré est fermé. « Compacter les horaires », dans le menu « Plus » de la page Stands, retire les fermetures devenues inutiles après avoir vérifié qu'aucune ouverture ne bouge.`,
        },
      ],
      links: [
        { route: '/ouvertures', label: $localize`:@@nav.link.ouvertures:Horaires des stands` },
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
          text: $localize`:@@aide.competences.grille:L'écran « Compétences » montre toutes les appréciations d'un coup, animateurs en lignes et typologies en colonnes ; le nom d'une personne mène à sa fiche, l'en-tête d'une colonne à sa typologie, et « Typologies affichées » réduit les colonnes. Une case se change d'un clic (les niveaux défilent) ou d'une touche : 0 vide la case, 1, 2 et 3 posent le niveau. Les flèches, Entrée, Début et Fin déplacent la sélection comme dans un tableur, et la flèche bas depuis le filtre entre dans la grille. Ctrl+D reprend la ligne du dessus, Ctrl+Maj+Bas applique la case courante à toute sa colonne ; les mêmes gestes ont un bouton, qui apparaît au survol en tête de ligne et en tête de colonne. Un bloc copié d'un tableur se colle depuis la case courante (Ctrl+V) — 0 à 3, D, A, R ou le niveau en toutes lettres —, et un aperçu montre chaque case avant de l'écrire dans la grille.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.competences.enregistrer:Rien n'est écrit avant « Enregistrer » : les cases changées sont encadrées, le bouton compte les fiches concernées, et quitter la page sans enregistrer demande confirmation. Le cœur d'une case dit que l'animateur souhaite cette typologie : un clic, ou la touche S, le pose ou le retire, et il s'enregistre avec le reste — l'appréciation se saisit en regard du souhait, sans que l'un décide de l'autre. Pendant une résolution, la saisie est verrouillée.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.competences.concurrence:L'enregistrement écrit une fiche par ligne modifiée. Si une autre session a modifié une fiche entre-temps, cette ligne seule est refusée, les autres sont écrites, et l'écran propose de recharger ou d'écraser en connaissance de cause.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.competences.saisieEcran:La grille ne s'exporte ni ne s'importe en fichier : c'est à l'écran qu'elle se saisit et s'enregistre.`,
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
      summary: $localize`:@@aide.importCsv.summary:Reprendre votre tableur d'animateurs sans le ressaisir : les onglets disponibles, le format attendu, et les deux refus qui surprennent la première fois.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.importCsv.ecran.fichiers:Tous les fichiers qui remplissent une édition sont sur l'écran « Fichiers », onglet Importer, une carte par référentiel et dans l'ordre où les données se tiennent ; le bouton « Importer » de chaque écran de référentiel ouvre la même carte sans quitter l'écran, et chaque carte accepte aussi un collage depuis un tableur. Rien n'est écrit tant que vous n'avez pas validé l'aperçu, et une colonne que le fichier ne porte pas n'efface rien : renommer des stands par un fichier de trois colonnes ne touche ni leurs horaires ni leur emplacement.`,
        },
        {
          kind: 'list',
          items: [
            $localize`:@@aide.importCsv.onglet.typologies:Typologies, emplacements, stands : un code et un libellé suffisent — le code est la clé que les autres fichiers citent ; une ligne sans code est désignée par son libellé ou son nom, et un identifiant n'est jamais lu, l'application l'attribue. Latitude et longitude sont facultatives pour un emplacement ; l'effectif l'est pour un stand, qui tient alors à une personne. Une typologie qu'un stand cite sans qu'elle existe est créée, et l'aperçu la nomme.`,
            $localize`:@@aide.importCsv.onglet.creneaux:Créneaux et journées types : un créneau se reconnaît à sa date et à ses deux heures, si bien qu'un fichier rejoué met la grille à jour au lieu de la doubler. Une journée type se reconnaît à son nom. Attention : importer des journées types ne déplace aucun créneau — la grille ne bouge qu'en appliquant le calendrier.`,
            $localize`:@@aide.importCsv.onglet.animateurs:Animateurs : le plus utile, détaillé ci-dessous.`,
            $localize`:@@aide.importCsv.onglet.grille:Grille des stands : la matrice du classeur, stands en lignes, jours et créneaux en colonnes. Elle a sa propre section dans ce guide.`,
            $localize`:@@aide.importCsv.onglet.scenario:Scénario : un fichier YAML qui porte l'édition entière et la remplace au lieu de la compléter.`,
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.importCsv.exports.fichiers:L'onglet Exporter de la même page fait le chemin inverse : une archive ZIP, un fichier par référentiel, dans la forme exacte que ces cartes relisent. C'est ce qui permet de recopier une édition sur l'autre, ou de corriger en masse dans un tableur puis de réimporter.`,
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
          text: $localize`:@@aide.importCsv.callout.text:Créez vos créneaux d'abord : sans dates d'événement, un jour d'indisponibilité importé serait invisible dans l'espace animateur, puis effacé. Et donnez une date de naissance à chaque nouvelle fiche : tout le régime mineur / majeur en dépend. Une ligne désigne une fiche par son adresse e-mail, à défaut par son prénom et son nom — jamais par un identifiant. Si deux personnes portent le même nom, ajoutez une colonne e-mail : l'import refuse la ligne plutôt que de choisir à votre place.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.importCsv.colonnes:Les compétences, les souhaits et les jours d'indisponibilité tiennent plusieurs valeurs dans une même cellule, séparées par « | ». Une compétence s'écrit « typologie » ou « typologie:REFERENT » ; sans niveau, elle vaut « autonome ». Les dates se lisent en JJ/MM/AAAA comme en AAAA-MM-JJ, mais préférez la forme ISO : un tableur transforme « 12/09/2026 » en « 12/09/26 », que l'import lit alors comme l'année la plus récente possible — et l'aperçu signale chaque date ainsi comprise.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.importCsv.exemple:« Télécharger un fichier d'exemple » donne un CSV court, prêt à ouvrir dans un tableur : les huit colonnes lues, remplies d'une douzaine de personnes fictives. C'est le plus court chemin pour voir à quoi doit ressembler une cellule de compétences. Son contenu vient du scénario de démonstration : recopiez-en la forme, pas les valeurs.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.importCsv.remplacement:Si vous cochez le remplacement complet, lisez l'avertissement de l'aperçu : supprimer une fiche emporte aussi la déclaration de disponibilités de la personne, son accusé de réception et son code d'accès à l'espace.`,
        },
      ],
      links: [
        {
          route: '/fichiers',
          queryParams: { cible: 'animateurs' },
          label: $localize`:@@nav.tab.importAnimateurs:Importer des animateurs`,
        },
        {
          route: '/fichiers',
          queryParams: { onglet: 'exporter' },
          label: $localize`:@@aide.lien.exporter:Fichiers — Exporter`,
        },
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
          text: $localize`:@@aide.importGrille.format:L'onglet lit un CSV tel qu'un tableur l'exporte : une première colonne qui nomme le stand par son code, ou à défaut par son nom exact, puis une colonne par jour et par créneau, un effectif par case, vide, « - » ou 0 pour fermé. Deux lignes d'en-tête — les dates, puis les bandes « 10h00-12h00 » — ou une seule, « 2026-07-08 10h00-12h00 ». Le plus simple est de partir de « Télécharger la grille actuelle comme modèle ».`,
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
          text: $localize`:@@aide.importGrille.stands:Un stand accepté est réécrit comme depuis la grille de saisie : tout son horaire suit ses cases, et ses effectifs minimum et maximum suivent la plus petite et la plus grande. Un stand absent du fichier n'est pas touché, et l'import n'en crée aucun : un code inconnu, ou un nom porté par deux stands, rejette la ligne en le disant.`,
        },
      ],
      links: [
        {
          route: '/fichiers',
          queryParams: { cible: 'grille-stands' },
          label: $localize`:@@aide.lien.importGrille.fichiers:Fichiers — Grille des stands`,
        },
        { route: '/stands', label: $localize`:@@nav.link.stands:Stands` },
        { route: '/ouvertures', label: $localize`:@@nav.link.ouvertures:Horaires des stands` },
        { route: '/creneaux', label: $localize`:@@nav.link.creneaux:Créneaux` },
      ],
    },
  ];
}
