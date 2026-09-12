// What the animateurs do from their espace, seen from the organiser's
// side: the fair, the availability collection, acknowledgements and
// reminders.
//
// One theme of the organisers' guide; `aide-content.ts` assembles the
// themes in reading order. Built lazily, never at module scope: `$localize`
// only resolves once `main.ts` has loaded the translation catalog.

import { HelpSection } from '../help-section';

export function buildAnimateurSideSections(): HelpSection[] {
  return [
    {
      id: 'foire-au-planning',
      icon: 'handshake',
      title: $localize`:@@aide.foire.title:Foire au planning (échanges de créneaux)`,
      summary: $localize`:@@aide.foire.summary:Les animateurs proposent leurs échanges de créneaux en libre-service ; rien n'est appliqué sans votre validation.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.foire.intro:Chaque animateur dispose d'un espace personnel, accessible par le lien imprimé sur son planning PDF — aucun compte à créer, mais un code d'accès envoyé à l'adresse e-mail de sa fiche est demandé une fois par appareil (le planning se télécharge depuis l'espace, un minimum d'authentification s'impose ; un animateur sans adresse doit la faire ajouter). Il y consulte son planning à jour (avec ses coéquipiers) et peut proposer d'échanger un de ses créneaux avec un collègue — soit en cédant simplement son créneau, soit en désignant en plus le créneau du collègue qu'il veut récupérer en échange (« je te laisse mon lundi, je prends ton mardi »). Le collègue ciblé donne d'abord son accord depuis son espace (l'onglet Échanges lui présente les demandes reçues) : une demande n'atteint l'écran Échanges de l'organisation qu'une fois les deux animateurs d'accord, et un refus du collègue la clôt sans arbitrage. Chaque demande est prévalidée contre les règles dures du planning ; une demande irréalisable est signalée en langage métier, mais transmise quand même : c'est vous qui tranchez.`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.foire.term.espace:Espace animateur`,
              text: $localize`:@@aide.foire.def.espace:L'animateur constitue sa liste de demandes (créneau concerné, collègue avec qui échanger, motif) puis la soumet en une fois. Quand il n'a personne en tête — il ne veut simplement pas ce créneau — « qui peut me remplacer ? » cherche les collègues avec qui l'échange tient réellement, sous ses trois formes : un collègue libre le remplace, une permutation sur le même créneau, ou un troc contre un créneau d'un autre jour. Il choisit dans la liste et le champ collègue se remplit. Il suit ensuite le statut de chacune — en attente, acceptée, refusée — avec votre commentaire éventuel, et peut annuler une demande tant qu'elle n'est pas décidée. Il peut aussi, depuis l'onglet « Mon planning », emporter son planning : la bande « Emporter mon planning » propose d'abord d'y abonner son agenda, puis de télécharger un PDF ou un fichier ICS. Le lien de son espace se copie (et se régénère, si un PDF a fuité) depuis sa fiche sur la page Animateurs.`,
            },
            {
              term: $localize`:@@aide.foire.term.ecran:Écran Échanges`,
              text: $localize`:@@aide.foire.def.ecran:Les demandes en attente, avec pour chacune son impact mesuré sur le planning actuel : échange croisé (les deux permutent) ou simple reprise (le collègue est libre sur le créneau), effet sur le score, règles dures qui seraient cassées. Accepter applique l'échange immédiatement, exactement comme simulé, et le verrouille sur son créneau : une régénération ultérieure ne le défera pas — elle reste à lancer depuis la page Solveur. Refuser ne modifie rien ; le motif saisi est transmis à l'animateur. Une demande décidée porte « pas encore communiquée — la prochaine publication l'annonce » tant que cette publication n'est pas partie : c'est votre reste à publier, et d'ici là l'espace de l'animateur montre encore le plan d'avant votre décision.`,
            },
            {
              term: $localize`:@@aide.foire.term.connexion:Connexion et notifications`,
              text: $localize`:@@aide.foire.def.connexion:L'administration est protégée par le compte admin ; seuls les espaces animateurs restent accessibles par leur lien personnel. Si la messagerie est configurée, vous êtes prévenu par e-mail à chaque soumission. L'animateur, lui, apprend le sort de ses demandes à la publication suivante, avec le planning qui les porte : accepter un échange change le plan de travail, pas encore celui qu'il a reçu.`,
            },
            {
              term: $localize`:@@aide.foire.term.planPublie:Plan publié et plan de travail`,
              text: $localize`:@@aide.foire.def.planPublie:L'espace d'un animateur montre le plan qu'on lui a envoyé, pas celui sur lequel vous travaillez : un échange validé, un remplacement appliqué ou une nouvelle résolution ne déplacent son espace qu'une fois publiés. Tant que rien ne l'a été sur l'édition, les espaces restent vides et le disent — l'application ne peut pas affirmer avoir communiqué un planning qu'elle n'a jamais envoyé. La première publication concerne donc tout le monde. Publier est refusé pendant une résolution : ce serait figer un plan sur le point d'être réécrit.`,
            },
            {
              term: $localize`:@@aide.foire.term.abonnement:Abonnement au calendrier`,
              text: $localize`:@@aide.foire.def.abonnement:Depuis la bande « Emporter mon planning » de son espace, un animateur donne à son agenda une adresse d'abonnement permanente, au lieu de télécharger un fichier ICS qui se périme dès la republication. Ce que cet agenda relit à chaque synchronisation, c'est le planning publié : « mon agenda ne se met pas à jour » veut donc presque toujours dire que le changement n'a pas encore été publié, et jamais qu'il faut se réabonner. Tant que rien n'a été publié sur l'édition, l'abonnement répond un calendrier vide plutôt qu'une erreur — un agenda à qui l'on répond en erreur coupe l'abonnement sans prévenir personne. Cette adresse est un second identifiant, distinct du lien de l'espace : régénérer le lien d'un animateur depuis sa fiche ne coupe pas son abonnement, et l'animateur remplace lui-même son adresse d'abonnement si elle a fuité, sans que son lien d'espace change. Vous ne la voyez pas et n'avez pas à la manipuler.`,
            },
            {
              term: $localize`:@@aide.foire.term.ouverture:Ouverture et fermeture`,
              text: $localize`:@@aide.foire.def.ouverture:L'interrupteur en tête de l'écran Échanges ouvre ou ferme la foire pour l'édition courante. Fermée, les espaces animateurs passent en consultation seule — le planning reste visible, téléchargeable (PDF, ICS) et les agendas abonnés continuent de le suivre, mais plus aucune demande ne peut être soumise ni annulée, et le refus est appliqué côté serveur, pas seulement masqué à l'écran. Vous pouvez aussi borner la foire par une date de début et une date de fin, comme la collecte des disponibilités : laissez une date vide pour ne pas borner ce côté-là. L'interrupteur reste maître — une période renseignée n'ouvre jamais une foire fermée — et les bornes sont appliquées côté serveur elles aussi. Avant la date d'ouverture, l'espace annonce « pas encore ouverte » et la date de retour, et non « fermée » : quelqu'un à qui l'on dit que c'est terminé deux semaines trop tôt ne revient pas.`,
            },
            {
              term: $localize`:@@aide.foire.term.envoi:Publier le planning`,
              text: $localize`:@@aide.foire.def.envoi:Le bouton « Publier » de la page Solveur (à côté de l'export du planning global) porte son décompte : « Publier — 3 personnes concernées ». Il n'écrit qu'aux animateurs dont l'emploi du temps a changé depuis la dernière publication, et le message dit ce qui change pour chacun — la même liste que vous relisez à l'écran avant de valider. Une correction qui ne déplace personne ne déclenche donc aucun envoi, et dix corrections d'affilée ne font pas dix courriels : elles remplissent une file que vous videz quand vous avez fini. Le compte rendu nomme les animateurs sans adresse e-mail et les envois en échec ; la page Timeline animateur permet de renvoyer à l'un d'eux le plan publié. Sur un gros effectif, l'envoi prend plusieurs dizaines de secondes : l'écran l'annonce et neutralise le bouton le temps qu'il parte, un seul envoi à la fois — inutile de recliquer.`,
            },
          ],
        },
      ],
      links: [
        { route: '/echanges', label: $localize`:@@nav.link.echanges:Échanges` },
        { route: '/animateurs', label: $localize`:@@nav.link.animateurs:Animateurs` },
        { route: '/verrouillages', label: $localize`:@@nav.link.verrouillages:Verrouillages` },
      ],
    },
    {
      id: 'disponibilites',
      icon: 'event_available',
      title: $localize`:@@aide.dispo.title:Collecte des disponibilités et des souhaits`,
      summary: $localize`:@@aide.dispo.summary:Les animateurs déclarent eux-mêmes leurs jours d'indisponibilité et leurs souhaits ; vous appliquez, ou non.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.dispo.intro:Avant de construire un planning, il faut savoir qui ne peut pas venir quand. Plutôt que de collecter ça hors de l'application puis de le ressaisir, vous ouvrez une fenêtre de collecte : chaque animateur déclare depuis son espace, sur son téléphone, les jours où il est indisponible et les types de jeux qu'il aimerait animer. Ce qu'il envoie ne touche à rien : c'est une proposition, qui attend votre décision sur l'écran Disponibilités.`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.dispo.term.fenetre:Ouvrir la collecte`,
              text: $localize`:@@aide.dispo.def.fenetre:Le bandeau « Fenêtre de collecte », en tête de la page Disponibilités, porte les deux boutons « Ouvrir la collecte » et « Fermer la collecte ». Elle est fermée tant que vous ne l'avez pas ouverte — contrairement à la foire au planning, ouverte par défaut. Les deux dates sont facultatives : elles bornent la période, l'interrupteur reste le maître. Fermée, l'espace refuse toute déclaration côté serveur, pas seulement à l'écran ; l'animateur garde l'accès à ce qu'il a déclaré et à vos réponses.`,
            },
            {
              term: $localize`:@@aide.dispo.term.prevenir:Prévenir les animateurs`,
              text: $localize`:@@aide.dispo.def.prevenir:Une case à cocher au moment d'ouvrir, jamais un réglage permanent : elle envoie à chacun le lien de son espace, directement sur l'onglet de déclaration. Cochez-la au premier tour ; laissez-la de côté quand vous rouvrez la fenêtre après une correction, sinon tout le monde reçoit une relance pour rien. Le compte rendu nomme ceux qui n'ont pas d'adresse e-mail et les envois en échec.`,
            },
            {
              term: $localize`:@@aide.dispo.term.decision:Appliquer ou refuser, en bloc`,
              text: $localize`:@@aide.dispo.def.decision:L'écran montre côte à côte ce que l'animateur déclare et ce que sa fiche dit aujourd'hui. Appliquer écrit la proposition entière sur sa fiche, exactement comme si vous aviez ouvert sa fiche pour la modifier — le planning enregistré est alors signalé comme périmé, et la régénération reste une action à part depuis la page Solveur. Refuser ne modifie rien ; le motif que vous saisissez est lu par l'animateur dans son espace. Il n'y a délibérément pas de validation ligne à ligne : un désaccord se règle par un mot, et l'animateur renvoie une version corrigée.`,
            },
            {
              term: $localize`:@@aide.dispo.term.remplacement:Une seule proposition par personne`,
              text: $localize`:@@aide.dispo.def.remplacement:Un animateur qui se corrige remplace sa proposition en attente, il n'en empile pas une seconde : vous n'aurez jamais à arbitrer deux versions contradictoires de la même personne, et c'est toujours la dernière qui vous parvient. Vous n'êtes prévenu par e-mail qu'à l'arrivée d'une proposition sur votre bureau, pas à chaque correction qu'il y apporte avant que vous ne la traitiez.`,
            },
            {
              term: $localize`:@@aide.dispo.term.competences:Ce qui ne se déclare pas`,
              text: $localize`:@@aide.dispo.def.competences:Les compétences restent décidées avec vous. Une compétence auto-déclarée alimente des règles dures — qui a le droit de tenir quel stand — et l'enjeu n'est pas celui d'une indisponibilité ou d'un souhait, qui se rattrapent. Un animateur qui déclare un jour hors des dates de l'événement, ou une typologie qui n'existe pas, est refusé à l'envoi.`,
            },
          ],
        },
      ],
      links: [
        { route: '/disponibilites', label: $localize`:@@nav.link.disponibilites:Disponibilités` },
        { route: '/animateurs', label: $localize`:@@nav.link.animateurs:Animateurs` },
        { route: '/creneaux', label: $localize`:@@nav.link.creneaux:Créneaux` },
      ],
    },
    {
      id: 'rappels',
      icon: 'notifications_active',
      title: $localize`:@@aide.rappels.title:Accusés de réception et rappels automatiques`,
      summary: $localize`:@@aide.rappels.summary:Savoir qui a lu son planning, et laisser l'application relancer les autres — une fois.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.rappels.intro:Une fois le planning publié, chaque animateur voit dans son espace un bouton « J'ai lu et je serai là ». La colonne « Accusé de réception » de la page Animateurs vous rend la réponse, et le filtre rapide de la table accepte « confirmé », « relancé » ou « silencieux » comme n'importe quel autre mot.`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.rappels.term.statuts:Les trois statuts`,
              text: $localize`:@@aide.rappels.def.statuts:« Silencieux » : rien n'est revenu. « Confirmé » : le bouton a été cliqué, la date s'affiche au survol. « Relancé » : la relance automatique est partie et reste sans réponse. Un animateur sans aucun poste au planning publié affiche « — » : il n'est pas silencieux, on ne lui a rien demandé, et il ne compte pas parmi les gens à relancer.`,
            },
            {
              term: $localize`:@@aide.rappels.term.republication:Republier ne remet pas tout le monde à zéro`,
              text: $localize`:@@aide.rappels.def.republication:Une nouvelle publication ne redemande une confirmation qu'aux personnes dont l'emploi du temps a réellement changé. Quelqu'un qu'on prévient seulement d'une décision d'échange lit les mêmes journées qu'avant : lui reposer la question transformerait le bouton en réflexe plutôt qu'en réponse.`,
            },
            {
              term: $localize`:@@aide.rappels.term.activation:Activer l'édition, sur la page Paramètres`,
              text: $localize`:@@aide.rappels.def.activation:Les envois de nuit sont désactivés tant que vous ne les activez pas, édition par édition. C'est volontaire et c'est le seul garde-fou : une édition passée porte les mêmes animateurs, et rien d'autre ne distingue les bénévoles de cette année de ceux de l'an dernier. Dupliquer une édition ne recopie pas ce réglage.`,
            },
            {
              term: $localize`:@@aide.rappels.term.delais:Les trois délais`,
              text: $localize`:@@aide.rappels.def.delais:L'heure d'envoi du rappel de la veille (23h00 au plus tard : la tâche s'exécute une fois par heure, et un rappel réglé plus tard ne partirait jamais), le silence toléré après une publication avant de relancer, et l'ancienneté d'une demande d'échange qui déclenche une alerte. Tous trois se règlent par édition sur la page Paramètres.`,
            },
            {
              term: $localize`:@@aide.rappels.term.rappel:Le rappel de la veille`,
              text: $localize`:@@aide.rappels.def.rappel:La veille au soir, chaque animateur affecté le lendemain reçoit la liste de ses créneaux. Elle est tirée du planning publié, jamais du plan de travail : personne n'est rappelé pour un créneau que vous ne lui avez pas communiqué. Un animateur sans adresse e-mail est sauté, et signalé nommément sur la page Notifications — ce sont les gens à prévenir à la main.`,
            },
            {
              term: $localize`:@@aide.rappels.term.relance:Une relance, pas une série`,
              text: $localize`:@@aide.rappels.def.relance:Passé le délai, les silencieux reçoivent un rappel de confirmation et passent à « Relancé ». Ils n'en recevront pas d'autre : relancer quelqu'un tous les soirs ne le fait pas répondre plus vite, ça le fait filtrer vos messages. À vous de reprendre la main sur les derniers.`,
            },
            {
              term: $localize`:@@aide.rappels.term.relanceManuelle:Relancer à la main, sans attendre la nuit`,
              text: $localize`:@@aide.rappels.def.relanceManuelle:La veille de l'événement, une nuit de plus est une nuit de trop. Sur la page Animateurs, le filtre « Accusés » isole les personnes jamais confirmées ou silencieuses depuis N jours ; sélectionnez-les et « Relancer maintenant » leur envoie le même rappel que la nuit. La règle ne change pas : une seule relance par personne et par publication, que la nuit ou vous l'ayez envoyée — le compte rendu nomme ceux qu'elle a laissés de côté et pourquoi. La synthèse en tête de page compte les confirmés, les relancés et les silencieux du planning publié.`,
            },
            {
              term: $localize`:@@aide.rappels.term.echanges:Les demandes d'échange qui dorment`,
              text: $localize`:@@aide.rappels.def.echanges:Une demande qui attend votre décision depuis plus longtemps que le délai fixé remonte sur la page Notifications, et une seule fois — même si elle vieillit encore. L'ancienneté se compte à partir de l'accord du collègue : une demande qui attend encore sa réponse n'attend pas après vous.`,
            },
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.rappels.silence:« Je n'ai rien reçu » a presque toujours la même cause : l'édition n'a pas été activée. Ensuite viennent le planning jamais publié, puis les fiches sans adresse e-mail. Ces alertes-là se referment en traitant ce qu'elles signalent, pas en les effaçant.`,
        },
      ],
      links: [
        { route: '/animateurs', label: $localize`:@@nav.link.animateurs:Animateurs` },
        { route: '/parametres', label: $localize`:@@nav.link.parametres:Paramètres` },
        { route: '/notifications', label: $localize`:@@nav.link.notifications:Notifications` },
      ],
    },
  ];
}
