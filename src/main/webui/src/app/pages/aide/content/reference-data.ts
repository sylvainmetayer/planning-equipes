// The data an organiser enters before anything can be computed: editions,
// the reference screens, the stands' hours and the bulk imports.
//
// One theme of the organisers' guide; `aide-content.ts` assembles the
// themes in reading order. Built lazily, never at module scope: `$localize`
// only resolves once `main.ts` has loaded the translation catalog.

import { HelpSection } from '../help-section';
import {
  lienConsignes,
  lienDisponibilites,
  lienStaffing,
  lienTypologies,
  lienVerrouillages,
  lienVersions,
} from './liens';

export function buildReferenceDataSections(): HelpSection[] {
  return [
    {
      id: 'editions',
      icon: 'layers',
      title: $localize`:@@aide.editions.title:Éditions et édition active`,
      summary: $localize`:@@aide.editions.summary:Une seule édition parle aux animateurs ; on prépare la suivante à côté, puis on bascule d'un geste.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.editions.intro:Une édition est un événement, avec ses propres stands, animateurs, créneaux, paramètres et planning. Rien ne circule d'une édition à l'autre : « 2025 » reste consultable pendant qu'on prépare « 2026 ». Chaque onglet du navigateur consulte sa propre édition, que rappelle le bandeau du haut ; un navigateur qui n'en a encore choisi aucune s'ouvre sur l'édition active.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.editions.active:Une seule édition est active, marquée « Active » sur la page Éditions. Elle seule publie, envoie des courriels (planning, code d'accès, invitation à déclarer, relances, rappel de la veille, notifications d'échange) et ouvre l'espace animateur, les abonnements de calendrier et l'affichage mural. Une édition inactive reste entièrement utilisable ici : référentiel, résolution, verrous, simulations, versions ; elle n'écrit simplement à personne, ce que le bandeau du haut rappelle, et ses liens répondent comme un lien inconnu. Entre deux événements, aucune édition n'est active et rien ne part. Toute édition naît inactive, qu'elle soit créée, dupliquée ou importée ; seule la première d'une installation neuve naît active.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.editions.variante:Dupliquer une édition copie sa structure (stands, typologies, horaires, créneaux, paramètres) et, si vous le cochez, ses animateurs avec leurs compétences, indisponibilités, souhaits et ajustements. Les affectations et les liens d'espace ne sont pas copiés : un lien désigne toujours exactement une édition. La copie naît inactive : c'est le modèle de l'année suivante, ou une variante à résoudre à côté sans rien envoyer. Un plan de repli du jour (canicule, orage) ne passe pas par une copie : c'est une consigne, posée dans l'édition active.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.editions.rappel:Un bandeau « Action requise », sous la barre du haut, signale ce qui demande une décision : l'édition active a passé son dernier jour, une édition inactive commence dans les jours qui viennent (sept par défaut, réglage de l'exploitant), ou, plus urgent, une édition inactive a déjà commencé, son espace animateur fermé. Les dates sont lues dans les créneaux : une édition sans créneau ne déclenche rien. Le bandeau n'envoie aucun courriel.`,
        },
        {
          kind: 'steps',
          items: [
            $localize`:@@aide.editions.bascule1:Créer l'édition suivante, ou dupliquer la précédente sans les animateurs pour en garder le modèle : elle naît inactive.`,
            $localize`:@@aide.editions.bascule2:La préparer comme n'importe quelle édition, référentiels, grille, premières résolutions : rien n'en part.`,
            $localize`:@@aide.editions.bascule3:Avant d'écrire aux animateurs (invitation à déclarer, publication), cliquer « Activer » sur sa ligne de la page Éditions. L'aperçu dit ce que la bascule ferme dans l'édition sortante : liens d'espace et de calendrier, affichages muraux, demandes d'échange restées ouvertes, résolutions en file.`,
            $localize`:@@aide.editions.bascule4:Confirmer : l'ancienne cesse d'être active et la nouvelle le devient d'un seul geste. C'est refusé tant qu'une résolution tourne sur l'une des deux.`,
            $localize`:@@aide.editions.bascule5:L'événement passé, « Désactiver » quand plus rien ne doit partir : l'édition reste consultable, mais ses liens imprimés cessent de répondre.`,
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.editions.comparer:« Comparer deux éditions », sur la page Éditions, dit ce qui a changé dans le référentiel de l'une à l'autre — l'an dernier et l'édition qu'on prépare, ou une édition et sa variante : stands, animateurs, typologies, emplacements, gabarits, créneaux, paramètres et ajustements (comptés par type ; les verrous ne sont pas comparés), et la volumétrie des deux côte à côte. Aucun planning n'est lu, rien n'est modifié. « Comparer à l'édition consultée », sur une ligne de la table, ouvre la paire toute prête. Rien n'est rapproché par identifiant, deux éditions numérotant leurs fiches à partir du même compteur : un stand, une typologie ou un emplacement par son code, puis par son nom (signalé, à vérifier) ; un animateur par son e-mail, puis par prénom, nom et date de naissance ; un gabarit par son nom ; les créneaux par rang du jour d'ouverture, puis par heure de début. Une édition sans aucun animateur, dupliquée sans les personnes, est signalée : tous ceux de l'autre y paraissent partis, et la liste reste repliée. « Ouvrir dans » mène à la fiche dans l'édition qui la porte et bascule le navigateur sur cette édition. L'export CSV ne nomme aucun animateur.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.editions.vider:« Vider cette édition », en bas de la page Éditions, supprime les stands, créneaux, animateurs, affectations, ajustements manuels, verrouillages, validations de journées, demandes d'échange, consignes et préréglages de consigne de l'édition consultée, après avoir fait recopier son nom. Restent ses typologies, emplacements, gabarits, versions et paramètres, les autres éditions et les réglages de l'instance. Refusé pendant une résolution et tant qu'une famille est figée.`,
        },
      ],
      links: [
        { route: '/editions', label: $localize`:@@nav.link.editions:Éditions` },
        {
          route: '/comparer',
          queryParams: { vue: 'editions' },
          label: $localize`:@@nav.link.comparerEditions:Comparer deux éditions`,
        },
        { route: '/creneaux', label: $localize`:@@nav.link.creneaux:Créneaux` },
        lienVersions(),
        lienConsignes(),
      ],
    },
    {
      id: 'phase-de-l-edition',
      icon: 'lock',
      title: $localize`:@@aide.phase.title:Phase de l'édition`,
      summary: $localize`:@@aide.phase.summary:Dire où en est l'édition (Préparation, Calcul, Publiée, Terminée), ce qui fige les fiches dont le planning est calculé.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.phase.intro:Une édition passe par quatre phases. En Préparation, tout se modifie. En Calcul, les stands, les créneaux, les typologies, les emplacements et les compétences des animateurs sont figés : ni formulaire, ni modification en masse, ni import de fichier, ni assistant ne peut les modifier. En Publiée, la structure (stands, créneaux, typologies, emplacements) reste figée et les compétences des animateurs se modifient. Terminée fige comme Calcul. La phase se change depuis l'État de l'édition ou depuis Administrer › Éditions, après une confirmation qui dit ce que la nouvelle phase fige ou ouvre ; tout retour en arrière est permis.`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.phase.stands.terme:Stands`,
              text: $localize`:@@aide.phase.stands.def:Création, suppression, effectifs, réserve aux majeurs, typologies proposées et horaires. Le nom, l'emplacement, premium et effort restent modifiables.`,
            },
            {
              term: $localize`:@@aide.phase.creneaux.terme:Créneaux`,
              text: $localize`:@@aide.phase.creneaux.def:Création, suppression, date, heures, et ce qui génère la grille : gabarits appliqués, séries, dérivation. Les gabarits eux-mêmes restent modifiables.`,
            },
            {
              term: $localize`:@@aide.phase.typologies.terme:Typologies et emplacements`,
              text: $localize`:@@aide.phase.typologies.def:Création, suppression, plafond par typologie et typologie polyvalente. Libellés, descriptions, noms et positions restent modifiables.`,
            },
            {
              term: $localize`:@@aide.phase.competences.terme:Compétences`,
              text: $localize`:@@aide.phase.competences.def:Les appréciations des animateurs déjà inscrits, par la fiche, la grille ou l'import. Un nouvel animateur arrive avec les siennes.`,
            },
          ],
        },
        {
          kind: 'callout',
          title: $localize`:@@aide.phase.ouvert.titre:Ce qui reste ouvert`,
          text: $localize`:@@aide.phase.ouvert.texte:Disponibilités, souhaits, déclarations, e-mails, ajustements manuels, verrouillages et consignes. Un stand à fermer en urgence se ferme par une consigne, sans changer de phase. L'import d'un scénario et la remise à zéro, qui remplacent tout, sont refusés tant que la phase fige une famille.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.phase.publiee:La première publication fait passer l'édition en Publiée. Pour un changement de dernière minute, « Débloquer la structure » rouvre stands, créneaux, typologies et emplacements sans quitter la phase, un bandeau le rappelle tant qu'elle est débloquée, et la publication suivante la refige (« Refiger » le fait à la main). Un compte en lecture seule voit la phase et n'a aucun bouton.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.phase.lever:Un champ figé s'affiche en lecture seule, avec un cadenas et la date de la phase ; la confirmation d'un déblocage rappelle si le planning est déjà publié. Les changements de phase figurent dans l'Historique des actions. La phase n'est pas le verrouillage : le verrou fige des affectations d'un planning calculé, la phase fige les fiches dont il est calculé. Une édition dupliquée repart en Préparation.`,
        },
      ],
      links: [
        { route: '/editions', label: $localize`:@@nav.link.editions:Éditions` },
        lienVerrouillages(),
        lienConsignes(),
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
              text: $localize`:@@aide.data.def.animateurs:Identité, date de naissance, compétences par typologie avec un niveau (débutant, autonome, référent), jours d'indisponibilité. Le régime légal (moins de 16 ans, 16-18 ans, majeur) ne se saisit jamais : il est recalculé à la date de chaque créneau. Par défaut un animateur est disponible, ne saisissez que les absences : le plus court est un clic sur le jour, dans la frise de sa fiche ; dans le formulaire, un clic sur un jour de la frise le marque absent, et « Absent du » … « au » pose une plage d'un geste. Les appréciations se saisissent en masse dans la grille des compétences, que « Saisir dans la grille (ligne pré-filtrée) » ouvre sur la personne. La colonne « Âge / régime » dit l'âge au premier jour de l'édition. Le nom ouvre la fiche de la personne, qui garde le filtre et le tri de la liste pour « Précédent » et « Suivant » ; « Filtrer » réduit la liste aux mineurs, aux managers, à une compétence ou à un souhait, chaque filtre restant affiché au-dessus du tableau. Une fois un planning calculé, la colonne « Postes » compte les sièges de chacun. L'indicateur « manager » est une étiquette : il sert aux filtres et aux exports, aucune règle du planning ne le lit, et un manager coché est planifié comme n'importe quel animateur.`,
            },
            {
              term: $localize`:@@aide.data.term.stands:Stands`,
              text: $localize`:@@aide.data.def.stands:Typologies proposées, effectif minimum et maximum simultané, restriction éventuelle aux majeurs, indicateurs premium et effort, horaires d'ouverture. « Ajouter » guide la création en quatre étapes (identité, typologies, lieu, horaires). Les créneaux de l'édition sont proposés ouverts : on décoche ceux où le stand est fermé, on tape combien de personnes il tient sur chacun et on décoche les jours de la semaine où il n'ouvre pas, sans aucune règle à écrire. Le tableau se trie sur chaque colonne ; « Ouvert » compte ses jours ouverts et ses postes, et « Couverture » apparaît une fois un planning calculé. « Édition groupée » modifie d'un coup les stands cochés, ou à défaut tous ceux que le tableau affiche. Le nom ouvre la fiche du stand. Il s'y règle en deux temps : en haut le modèle récurrent (une règle comme « chaque samedi et dimanche de 10:00 à 18:00 », ou une ligne « 10:00-12:00, 14:00- », avec une fenêtre qui nomme son propre effectif « @2 »), enregistré puis posé en plages sur les jours de l'événement ; en dessous la liste des plages datées, chacune marquée « modèle » ou « saisie », qu'on filtre par jour, à laquelle on ajoute une ouverture ou une fermeture, et dont on retire une plage ou un jour entier. Suivent ses anomalies d'ouverture, ses sièges après calcul (un siège vide s'ouvre dans le panneau du siège de la page Planning), « Comparer avec… » et l'historique de ses modifications. « Modifier l'identité » ne montre que les champs d'identité. Le maximum borne la saisie (un effectif de fenêtre ne peut pas le dépasser) mais le planning ne pourvoit jamais plus que l'effectif de la fenêtre, ou à défaut le minimum : relever le maximum n'ajoute aucun poste.`,
            },
            {
              term: $localize`:@@aide.data.term.lieux:Lieux et typologies`,
              text: $localize`:@@aide.data.def.autres:Un lieu (ou emplacement) est un endroit géolocalisé auquel rattacher un stand ; il sert à éviter les allers-retours d'un créneau à l'autre. Les lieux sont l'onglet « Lieux » de la page Stands : une carte les montre tous avec leurs stands, et faire glisser un marqueur y corrige la position sans ouvrir de fiche ; le tableau, trié à volonté, dit quels stands sont rattachés à chacun. Le formulaire d'un stand propose « Nouveau lieu… » sans quitter la page. Une typologie est le vocabulaire commun entre les compétences d'un animateur et les jeux d'un stand. Sans typologie partagée, l'animateur ne peut pas tenir le stand. Cochez aussi, dans la colonne « Typologie polyvalente » de l'écran Typologies, la typologie dont les porteurs sont polyvalents : le solveur essaie d'en garder un libre sur chaque créneau. Sans elle, cette réserve n'existe pas.`,
            },
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.data.typologiesOrphelines:L'écran Typologies compte, pour chacune, les animateurs qui la maîtrisent (les polyvalents à part), ceux qui l'ont souhaitée et les stands qui la proposent ; chaque chiffre mène à la liste qu'il compte, et l'icône de grille aux appréciations de cette typologie. Un badge « Orpheline » signale une typologie proposée par un stand que personne ne maîtrise : seul un polyvalent peut alors tenir ce stand. « Fragile » veut dire une seule personne, « Inutilisée » aucun stand. Une fois un planning calculé, trois colonnes disent ce qu'il en a fait : les postes, les heures, et les personnes « Affectés sans la compétence ». L'État de l'édition compte les orphelines, avant tout calcul.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.data.identifiants:Aucun identifiant ne se saisit : l'application en attribue un à chaque fiche qu'elle crée, A12 pour un animateur, S3 pour un stand, T2 pour une typologie, L1 pour un emplacement, C4 pour un ajustement. Un stand, une typologie ou un emplacement peut porter en plus un code, facultatif et unique dans l'édition, comme « STRATEGIE » ou « PAVILLON ». C'est la clé lisible que citent les fichiers d'import et qu'écrivent les exports, qui ne portent aucun identifiant. Sans code, une ligne y est désignée par son nom.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.data.tableaux:Les tableaux gardent les réflexes du tableur. Chaque colonne se trie d'un clic sur son en-tête, et les identifiants suivent l'ordre naturel : T2 avant T10. Le nom d'une ligne l'ouvre ; son menu la modifie, la duplique ou la supprime. « Exporter cette liste » télécharge en CSV ce que le tableau montre, filtres et tri compris. Un bloc copié d'un tableur se colle sur une ligne (Ctrl+V) : les colonnes se remplissent dans l'ordre du tableau, ou selon la première ligne si elle les nomme (« Id » y désigne alors les lignes par leur identifiant), et un aperçu montre chaque case avant qu'« Appliquer » n'enregistre. Un avertissement d'enregistrement reste marqué sur sa ligne tant qu'une nouvelle saisie ne l'a pas levé.`,
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
          text: $localize`:@@aide.data.suppression:Avant une suppression, la confirmation annonce ce qui référence la sélection (affectations, ajustements manuels, verrouillages), à titre d'information : aucun chiffre n'empêche de supprimer.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.data.copieHoraires:Quand plusieurs stands suivent la même journée, ne ressaisissez pas. Dans une fiche, « Copier les horaires de… » reprend tout l'horaire d'un autre stand, effectifs de fenêtre compris ; rien n'est enregistré avant « Modifier le stand ». En modification groupée, « Remplacer par ceux d'un stand » fait la même chose sur toute la sélection. Une fenêtre copiée qui dépasse l'effectif maximum du stand cible est signalée avant l'enregistrement.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.data.brouillon:La fiche animateur, la fiche stand et le formulaire de consigne gardent un brouillon de la saisie en cours. Après un rechargement, une session expirée ou un onglet fermé, rouvrir le même formulaire propose « Reprendre » ou « Ignorer » ; si la fiche a changé entre-temps, le bandeau le dit et l'enregistrement vous fera choisir entre recharger et écraser. Fermer un formulaire modifié (Échap, clic à côté, « Annuler ») demande confirmation. Le brouillon s'efface à l'enregistrement, à l'abandon, à la déconnexion et au bout de 24 h ; celui d'une fiche animateur ne survit pas à la fermeture de l'onglet, et celui d'une fiche supprimée entre-temps est effacé avec un message.`,
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
        lienTypologies(),
        lienStaffing(),
      ],
    },
    {
      id: 'calendrier',
      icon: 'schedule',
      title: $localize`:@@aide.creneaux.title:Créneaux et gabarits`,
      summary: $localize`:@@aide.creneaux.summary:Le découpage du temps que le solveur remplit, et le générateur qui l'écrit sans le taper jour par jour.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.creneaux.intro:Un créneau, c'est un jour, une date et deux heures. C'est le découpage que le solveur remplit : sur chaque créneau, chaque stand ouvert reçoit autant de sièges que sa fenêtre d'ouverture en nomme. Les créneaux sont communs à tous les stands. On n'en crée jamais par stand, ce sont les horaires qui disent ce que chacun ouvre. Les dates des créneaux sont aussi celles de l'édition.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.creneaux.grille:Une grille de créneaux est toujours faite de vacations, des tranches de travail réelles. La page déclare ce que la grille contient, des amplitudes encore à découper ou des vacations finales, parce que chaque verdict en dépend : un chevauchement est une erreur entre amplitudes, mais la forme normale de vacations décalées. Le « Contrôle de la grille » s'exécute seul, à chaque visite et après chaque écriture, et s'affiche en bandeau sous le calendrier, le détail à déplier. Chaque créneau de la liste compte ses « Stands ouverts · postes », un lien vers les horaires des stands ce jour-là.`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.creneaux.term.journeesTypes:Les gabarits`,
              text: $localize`:@@aide.creneaux.def.journeesTypes:Décrivez une fois chaque sorte de journée (« Jour normal », « Nocturne », « Montage ») par ses vacations sur une ligne : « 09:00-12:00, 12:00-13:00, 13:00-14:00, 14:00-20:00 ». Un calendrier pose ensuite un gabarit sur chaque date.`,
            },
            {
              term: $localize`:@@aide.creneaux.term.appliquer:Générer depuis les gabarits (appliquer le calendrier)`,
              text: $localize`:@@aide.creneaux.def.appliquer:Écrit les créneaux, après un aperçu chiffré. Un créneau identique garde ses sièges, un créneau en trop sur une date gouvernée est supprimé, une date sans gabarit n'est pas touchée. Un gabarit modifié après coup ne change rien tant qu'on ne réapplique pas : ses dates passent « en écart ».`,
            },
            {
              term: $localize`:@@aide.creneaux.term.serie:Générer depuis un motif récurrent`,
              text: $localize`:@@aide.creneaux.def.serie:Pour un besoin ponctuel, des fenêtres répétées sur les jours choisis (une plage de dates et des jours de la semaine, ou des dates une à une), après un aperçu, sans garder de gabarit. Elles s'ajoutent à la grille. Le même geste est offert depuis le dialogue d'un gabarit, « Appliquer sans mémoriser ».`,
            },
            {
              term: $localize`:@@aide.creneaux.term.deriver:Générer depuis les horaires des stands`,
              text: $localize`:@@aide.creneaux.def.deriver:Quand les stands ont déjà leurs horaires, la grille peut en découler : une coupure à chaque heure d'ouverture ou de fermeture, un créneau sur chaque tranche où au moins un stand est ouvert. Prévisualisé, puis ajouté à la grille ; « Remplacer la grille… » la met à la place de l'actuelle, après une confirmation qui dit que le planning calculé part avec elle. Les trois sources et la reconnaissance sont dans le menu « Générer des créneaux », à côté de l'ajout d'un créneau isolé ; chacune montre un aperçu avant d'écrire. Retirer une date du calendrier ne supprime pas ses créneaux.`,
            },
            {
              term: $localize`:@@aide.creneaux.term.reconnaitre:Reconnaître les gabarits depuis les créneaux`,
              text: $localize`:@@aide.creneaux.def.reconnaitre:Le chemin inverse, sur une grille déjà écrite : l'application en déduit les gabarits et le calendrier qui les porte.`,
            },
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.creneaux.feries:Les jours fériés sont marqués avec leur nom (Fête nationale, Assomption…) partout où l'on pose une date : la table des créneaux, le calendrier des gabarits, l'aperçu d'une série, le formulaire d'un créneau. Rien n'est interdit, un festival ouvre légitimement le 14 juillet. Mais un mineur ne travaille pas un jour férié : quand l'édition en compte un ce jour-là, le contrôle de la grille le rappelle, pour qu'on y pense avant le calcul plutôt qu'au diagnostic. Seuls les onze jours du Code du travail en métropole sont connus, sans les fériés d'Alsace-Moselle ni d'outre-mer.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.creneaux.enchainement:Deux créneaux qui se touchent s'enchaînent pour une même personne : rien n'impose d'écart entre deux vacations. Ce qui compte est la longueur de la séquence ainsi formée. Au-delà de six heures, elle doit une pause, prise comme un trou ou relayée par un collègue du stand.`,
        },
      ],
      links: [
        { route: '/creneaux', label: $localize`:@@nav.link.creneaux:Créneaux` },
        { route: '/stands', label: $localize`:@@nav.link.stands:Stands` },
        {
          route: '/regles',
          queryParams: { onglet: 'regles' },
          label: $localize`:@@nav.link.regles:Règles du planning`,
        },
      ],
    },
    {
      id: 'horaires-des-stands',
      icon: 'storefront',
      title: $localize`:@@aide.ouvertures.title:Horaires des stands`,
      summary: $localize`:@@aide.ouvertures.resume:Le modèle récurrent et les plages datées d'un stand se lisent et se saisissent sur sa fiche.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.ouvertures.introFiche:Les horaires d'un stand se règlent sur sa fiche (Stands, puis le nom du stand). Le modèle récurrent, en haut, dit son régime normal, par exemple « chaque samedi et dimanche de 10 h à 18 h, effectif 2 » ; il se déplie en plages datées, listées en dessous, chacune marquée modèle ou saisie. On y ajoute à la main une ouverture ou une fermeture, on retire une plage ou tout un jour. Un jour sans plage, le stand est ouvert toute la journée ; avec une ouverture, il n'est ouvert que pendant ses fenêtres ; avec seulement des fermetures, il est ouvert sauf pendant elles.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.ouvertures.lecture:Le nombre de jours ouverts et de postes d'un stand s'affiche en tête de sa fiche, avant toute résolution. Un stand jamais ouvert, une fenêtre hors de tout créneau ou un segment trop court pour valoir une vacation sont comptés sur l'accueil (ligne « Horaires des stands ») et dans le contrôle du Calendrier. Un stand dont personne ne doit s'occuper à une heure donnée se déclare fermé, jamais à effectif zéro.`,
        },
      ],
      links: [
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
          text: $localize`:@@aide.competences.grille:L'écran « Compétences » montre toutes les appréciations d'un coup, animateurs en lignes et typologies en colonnes ; le nom d'une personne mène à sa fiche, l'en-tête d'une colonne à sa typologie, et « Typologies affichées » réduit les colonnes. Une case se change d'un clic (les niveaux défilent) ou d'une touche : 0 vide la case, 1, 2 et 3 posent le niveau. Les flèches, Entrée, Début et Fin déplacent la sélection comme dans un tableur, et la flèche bas depuis le filtre entre dans la grille. Ctrl+D reprend la ligne du dessus, Ctrl+Maj+Bas applique la case courante à toute sa colonne ; les mêmes gestes ont un bouton, qui apparaît au survol en tête de ligne et en tête de colonne. Un bloc copié d'un tableur (0 à 3, D, A, R ou le niveau en toutes lettres) se colle depuis la case courante avec Ctrl+V, et un aperçu montre chaque case avant de l'écrire dans la grille.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.competences.enregistrer:Rien n'est écrit avant « Enregistrer » : les cases changées sont encadrées, le bouton compte les fiches concernées, et quitter la page sans enregistrer demande confirmation. Le cœur d'une case dit que l'animateur souhaite cette typologie : un clic, ou la touche S, le pose ou le retire, et il s'enregistre avec le reste. L'appréciation se saisit en regard du souhait, sans que l'un décide de l'autre. Pendant une résolution, la saisie est verrouillée.`,
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
        {
          route: '/animateurs',
          queryParams: { onglet: 'competences' },
          label: $localize`:@@nav.link.competences:Compétences`,
        },
        { route: '/animateurs', label: $localize`:@@nav.link.animateurs:Animateurs` },
        lienTypologies(),
        lienDisponibilites(),
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
          text: $localize`:@@aide.importCsv.ecran.fichiers:Les fichiers qui remplissent une édition sont sur l'écran « Fichiers », onglet Importer : le scénario YAML, qui porte l'édition entière (typologies, emplacements, stands, créneaux, gabarits), et l'import CSV des animateurs, seul CSV encore importable ; le bouton « Importer » de l'écran Animateurs ouvre la même carte, qui accepte aussi un collage depuis un tableur. Rien n'est écrit tant que vous n'avez pas validé l'aperçu.`,
        },
        {
          kind: 'list',
          items: [
            $localize`:@@aide.importCsv.onglet.animateurs:Animateurs : le plus utile, détaillé ci-dessous.`,
            $localize`:@@aide.importCsv.onglet.scenario:Scénario : un fichier YAML qui porte l'édition entière et la remplace au lieu de la compléter.`,
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.importCsv.exports.fichiers:L'onglet Exporter de la même page fait le chemin inverse : une archive ZIP, un fichier par référentiel, à ouvrir dans un tableur — seul celui des animateurs se réimporte — et l'édition entière en YAML, qui se corrige puis se réimporte.`,
        },
        {
          kind: 'steps',
          items: [
            $localize`:@@aide.importCsv.etape1:Enregistrez la feuille au format « CSV UTF-8 ». Le .xlsx n'est pas lu, et un autre encodage est refusé, car ses accents sont déjà perdus.`,
            $localize`:@@aide.importCsv.etape2:Déposez le fichier et dites quelle colonne est quel champ. Une correspondance est proposée d'après les en-têtes, vous la corrigez.`,
            $localize`:@@aide.importCsv.etape3:Lisez l'aperçu : chaque ligne est annoncée acceptée ou rejetée, avec son numéro de ligne et le motif.`,
            $localize`:@@aide.importCsv.etape4:Validez. Par défaut l'import ajoute et met à jour sans supprimer personne, et les jours d'indisponibilité complètent ceux déjà enregistrés. Deux cases inversent chacun de ces choix.`,
          ],
        },
        {
          kind: 'callout',
          title: $localize`:@@aide.importCsv.callout.title:Les deux refus qui surprennent`,
          text: $localize`:@@aide.importCsv.callout.text:Créez vos créneaux d'abord : sans dates d'événement, un jour d'indisponibilité importé serait invisible dans l'espace animateur, puis effacé. Et donnez une date de naissance à chaque nouvelle fiche : tout le régime mineur / majeur en dépend. Une ligne désigne une fiche par son adresse e-mail, à défaut par son prénom et son nom, jamais par un identifiant. Si deux personnes portent le même nom, ajoutez une colonne e-mail : l'import refuse la ligne plutôt que de choisir à votre place.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.importCsv.colonnes:Les compétences, les souhaits et les jours d'indisponibilité tiennent plusieurs valeurs dans une même cellule, séparées par « | ». Une compétence s'écrit « typologie » ou « typologie:REFERENT » ; sans niveau, elle vaut « autonome ». Les dates se lisent en JJ/MM/AAAA comme en AAAA-MM-JJ, mais préférez la forme ISO : un tableur transforme « 12/09/2026 » en « 12/09/26 », que l'import lit alors comme l'année la plus récente possible. L'aperçu signale chaque date ainsi comprise.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.importCsv.exemple:« Télécharger un fichier d'exemple » donne un CSV court, prêt à ouvrir dans un tableur : les huit colonnes lues, remplies d'une douzaine de personnes fictives. C'est le plus court chemin pour voir à quoi doit ressembler une cellule de compétences. Son contenu vient du scénario de démonstration : recopiez-en la forme, pas les valeurs.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.importCsv.remplacement:Si vous cochez le remplacement complet, lisez l'avertissement de l'aperçu : supprimer une fiche emporte aussi la déclaration de disponibilités de la personne, son accusé de réception et son code d'accès à l'espace.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.importCsv.doublons:L'aperçu compare aussi le prénom, le nom et la date de naissance, sans tenir compte de la casse, des accents ni de la ponctuation : entre les lignes du fichier, et avec les fiches que l'import conserve. Deux lignes qui décrivent la même personne sous deux adresses, ou une ligne qui donnerait à une fiche l'identité d'une autre, sont marquées « Doublon probable », avec un lien vers l'autre ligne ou vers la fiche. Une ligne rattachée par son seul nom à une fiche née un autre jour est marquée « Homonyme ? » : l'importer changerait la date de naissance de cette personne, donc son régime mineur / majeur. Une ligne dont l'adresse ne correspond à aucune fiche se rattache elle aussi par son nom : si la fiche trouvée porte une autre adresse, la ligne est marquée « Nouvelle adresse ? », car l'importer remplacerait l'adresse où partent les codes d'accès et les envois de cette personne. Des jumeaux, qui partagent nom et date de naissance mais pas le prénom, ne sont pas signalés. Ce sont des avertissements, pas des refus : le compteur « Doublons ou homonymes probables » au-dessus du tableau en donne le nombre, « N'afficher que les lignes signalées » réduit le tableau à ces lignes, et « Importer » redit ce nombre avant d'écrire. Pour mettre une fiche à jour plutôt que d'en créer une seconde, donnez à la ligne l'adresse e-mail de cette fiche.`,
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
  ];
}
