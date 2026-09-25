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
      summary: $localize`:@@aide.foire.summary:Les animateurs proposent leurs échanges en libre-service ; rien n'est appliqué sans votre validation.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.foire.intro:Chaque animateur a un espace personnel, ouvert par un lien qui lui est propre. Aucun compte à créer, mais un code envoyé à l'adresse e-mail de sa fiche lui est demandé une fois par appareil : le planning se télécharge depuis l'espace, un minimum d'authentification s'impose. Un animateur sans adresse ne peut pas y entrer — ajoutez-la.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.foire.echange:Il y consulte son planning à jour et peut proposer d'échanger un créneau avec un collègue, soit en le cédant simplement, soit en désignant en plus le créneau qu'il veut récupérer. Le collègue donne d'abord son accord depuis son propre espace : une demande n'arrive sur votre écran Échanges qu'une fois les deux d'accord, et un refus la clôt sans arbitrage. Chaque demande est prévalidée contre les règles dures ; une demande irréalisable est signalée, mais transmise quand même — c'est vous qui tranchez.`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.foire.term.ecran:Écran Échanges`,
              text: $localize`:@@aide.foire.def.ecran:Les demandes en attente, avec pour chacune son impact mesuré : échange croisé ou simple reprise, effet sur le score, règles dures qui seraient cassées. Accepter applique l'échange immédiatement, exactement comme simulé, et le verrouille sur son créneau : une régénération ne le défera pas. Refuser ne modifie rien, et le motif est transmis à l'animateur. Une demande décidée reste marquée « pas encore communiquée » tant que vous n'avez pas publié.`,
            },
            {
              term: $localize`:@@aide.foire.term.espace:Ce que l'animateur voit`,
              text: $localize`:@@aide.foire.def.espace:Il monte sa liste de demandes puis la soumet en une fois. Quand il n'a personne en tête, « qui peut me remplacer ? » cherche les collègues avec qui l'échange tient réellement. Il suit ensuite le statut de chacune, avec votre commentaire, et peut annuler tant que rien n'est décidé. Le lien de son espace se copie — et se régénère, si un PDF a fuité — depuis sa fiche sur la page Animateurs.`,
            },
            {
              term: $localize`:@@aide.foire.term.planPublie:Plan publié et plan de travail`,
              text: $localize`:@@aide.foire.def.planPublie:L'espace d'un animateur montre le plan qu'on lui a envoyé, pas celui sur lequel vous travaillez : un échange validé, un remplacement ou une nouvelle résolution ne déplacent son espace qu'une fois publiés. Tant que rien n'a été publié, les espaces restent vides et le disent. Publier est refusé pendant une résolution.`,
            },
            {
              term: $localize`:@@aide.foire.term.abonnement:Abonnement au calendrier`,
              text: $localize`:@@aide.foire.def.abonnement:Un animateur peut donner à son agenda une adresse d'abonnement permanente, au lieu de télécharger un ICS qui se périme. Ce que l'agenda relit à chaque synchronisation, c'est le planning publié : « mon agenda ne se met pas à jour » veut donc presque toujours dire que le changement n'a pas encore été publié. Cette adresse est un second identifiant, distinct du lien de l'espace : régénérer le lien ne coupe pas son abonnement, et l'animateur remplace lui-même son adresse si elle a fuité. Vous ne la voyez pas.`,
            },
            {
              term: $localize`:@@aide.foire.term.ouverture:Ouverture et fermeture`,
              text: $localize`:@@aide.foire.def.ouverture:L'interrupteur en tête de l'écran Échanges ouvre ou ferme la foire pour l'édition courante ; elle est ouverte par défaut. Fermée, les espaces passent en consultation seule : le planning reste visible et téléchargeable, mais plus aucune demande n'est acceptée, et le refus vient du serveur. Vous pouvez aussi borner la foire par deux dates — l'interrupteur reste maître. Avant la date d'ouverture, l'espace annonce « pas encore ouverte » et la date de retour, jamais « fermée ».`,
            },
            {
              term: $localize`:@@aide.foire.term.envoi:Publier le planning`,
              text: $localize`:@@aide.foire.def.envoi:Le bouton « Publier » de la page Publication porte son décompte : « Publier — 3 personnes concernées ». Il n'écrit qu'aux animateurs dont l'emploi du temps a changé depuis la dernière publication, et le message dit ce qui change pour chacun. Dix corrections d'affilée ne font donc pas dix courriels : elles remplissent une file que vous videz quand vous avez fini. Le compte rendu nomme les animateurs sans adresse et les envois en échec. Sur un gros effectif, l'envoi prend plusieurs dizaines de secondes.`,
            },
            {
              term: $localize`:@@aide.foire.term.relecture:Relire la liste avant d'envoyer`,
              text: $localize`:@@aide.foire.def.relecture:« Voir qui est concerné » ouvre une ligne par personne : ce qui change pour elle, sa dernière confirmation, et une case cochée. Triez par ampleur pour commencer par les plus gros changements, repliez les changements mineurs — même stand, un quart d'heure de décalage au plus — et exportez le tout en CSV pour le relire ailleurs. Le filtre ne décide rien : les lignes repliées partent quand même.`,
            },
            {
              term: $localize`:@@aide.foire.term.differer:Ne pas prévenir quelqu'un ce soir`,
              text: $localize`:@@aide.foire.def.differer:Décochez une personne et son message est différé, pas perdu : elle ne reçoit rien, et la publication suivante la nomme à nouveau avec l'écart cumulé depuis son dernier message — celui qu'elle a vraiment reçu, pas le plan publié entre-temps. C'est le geste de « je l'appelle d'abord ». Tout décocher est refusé : publier sans prévenir personne n'aurait aucun sens.`,
            },
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.foire.notifications:Si la messagerie est configurée, vous êtes prévenu par e-mail à chaque soumission. L'animateur, lui, apprend le sort de ses demandes à la publication suivante : accepter un échange change le plan de travail, pas encore celui qu'il a reçu.`,
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
      summary: $localize`:@@aide.dispo.summary:Les animateurs déclarent eux-mêmes leurs absences et leurs souhaits ; vous appliquez, ou non.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.dispo.intro:Avant de construire un planning, il faut savoir qui ne peut pas venir quand. Plutôt que de collecter ça hors de l'application puis de le ressaisir, ouvrez une fenêtre de collecte : chacun déclare depuis son espace, sur son téléphone, ses jours d'indisponibilité et les jeux qu'il aimerait animer. Ce qu'il envoie ne touche à rien : c'est une proposition, qui attend votre décision.`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.dispo.term.fenetre:Ouvrir la collecte`,
              text: $localize`:@@aide.dispo.def.fenetre:Le bandeau en tête de la page Disponibilités porte les boutons « Ouvrir » et « Fermer ». Contrairement à la foire au planning, la collecte est fermée tant que vous ne l'ouvrez pas. Les deux dates sont facultatives et bornent la période ; l'interrupteur reste maître. Fermée, l'espace refuse toute déclaration côté serveur, mais l'animateur garde l'accès à ce qu'il a déclaré et à vos réponses.`,
            },
            {
              term: $localize`:@@aide.dispo.term.prevenir:Prévenir les animateurs`,
              text: $localize`:@@aide.dispo.def.prevenir:Une case à cocher au moment d'ouvrir, jamais un réglage permanent : elle envoie à chacun le lien de son espace, directement sur l'onglet de déclaration. Cochez-la au premier tour ; laissez-la de côté quand vous rouvrez après une correction, sinon tout le monde reçoit une relance pour rien.`,
            },
            {
              term: $localize`:@@aide.dispo.term.decision:Appliquer ou refuser, en bloc`,
              text: $localize`:@@aide.dispo.def.decision:L'écran montre côte à côte ce que l'animateur déclare et ce que sa fiche dit aujourd'hui. Appliquer écrit la proposition entière sur sa fiche ; le planning enregistré est alors signalé comme périmé, et la régénération reste une action à part. Refuser ne modifie rien, et votre motif est lu par l'animateur dans son espace. Il n'y a délibérément pas de validation ligne à ligne : un désaccord se règle par un mot, et l'animateur renvoie une version corrigée.`,
            },
            {
              term: $localize`:@@aide.dispo.term.remplacement:Une seule proposition par personne`,
              text: $localize`:@@aide.dispo.def.remplacement:Un animateur qui se corrige remplace sa proposition en attente, il n'en empile pas une seconde : vous n'aurez jamais deux versions contradictoires à arbitrer. Vous n'êtes prévenu qu'à l'arrivée d'une proposition, pas à chaque correction qu'il y apporte avant que vous ne la traitiez.`,
            },
            {
              term: $localize`:@@aide.dispo.term.competences:Ce qui ne se déclare pas`,
              text: $localize`:@@aide.dispo.def.competences:Les compétences restent décidées avec vous : une compétence auto-déclarée alimente des règles dures — qui a le droit de tenir quel stand — là où une indisponibilité ou un souhait se rattrapent. Un animateur qui déclare un jour hors des dates de l'événement, ou une typologie inexistante, est refusé à l'envoi.`,
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
      title: $localize`:@@aide.rappels.title:Accusés de réception et rappels`,
      summary: $localize`:@@aide.rappels.summary:Savoir qui a lu son planning, et laisser l'application relancer les autres — une fois.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.rappels.intro:Une fois le planning publié, chaque animateur voit dans son espace un bouton « J'ai lu et je serai là ». La colonne « Accusé de réception » de la page Animateurs vous rend la réponse, et le filtre de la table accepte « confirmé », « relancé » ou « silencieux ».`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.rappels.term.statuts:Les trois statuts`,
              text: $localize`:@@aide.rappels.def.statuts:« Silencieux » : rien n'est revenu. « Confirmé » : le bouton a été cliqué, la date s'affiche au survol. « Relancé » : la relance est partie et reste sans réponse. Un animateur sans aucun poste au planning publié affiche « — » : on ne lui a rien demandé, il ne compte pas parmi les gens à relancer.`,
            },
            {
              term: $localize`:@@aide.rappels.term.republication:Republier ne remet pas tout le monde à zéro`,
              text: $localize`:@@aide.rappels.def.republication:Une nouvelle publication ne redemande une confirmation qu'aux personnes dont l'emploi du temps a réellement changé. Quelqu'un qu'on prévient d'une décision d'échange lit les mêmes journées qu'avant : lui reposer la question transformerait le bouton en réflexe plutôt qu'en réponse.`,
            },
            {
              term: $localize`:@@aide.rappels.term.activation:Activer l'édition`,
              text: $localize`:@@aide.rappels.def.activation:Les envois de nuit sont désactivés tant que vous ne les activez pas, édition par édition, sur la page Paramètres. C'est le seul garde-fou : une édition passée porte les mêmes fiches, et rien ne distingue les animateurs de cette année de ceux de l'an dernier. Dupliquer une édition ne recopie pas ce réglage.`,
            },
            {
              term: $localize`:@@aide.rappels.term.delais:Les trois délais`,
              text: $localize`:@@aide.rappels.def.delais:L'heure d'envoi du rappel de la veille (18 h par défaut, 23 h au plus tard : la tâche passe une fois par heure, et un rappel réglé plus tard ne partirait jamais), le silence toléré après une publication avant de relancer, et l'ancienneté d'une demande d'échange qui déclenche une alerte. Tous trois se règlent par édition.`,
            },
            {
              term: $localize`:@@aide.rappels.term.rappel:Le rappel de la veille`,
              text: $localize`:@@aide.rappels.def.rappel:La veille au soir, chaque animateur affecté le lendemain reçoit la liste de ses créneaux. Elle est tirée du planning publié, jamais du plan de travail : personne n'est rappelé pour un créneau que vous ne lui avez pas communiqué. Un animateur sans adresse est sauté et signalé nommément sur la page Notifications.`,
            },
            {
              term: $localize`:@@aide.rappels.term.relance:Une relance, pas une série`,
              text: $localize`:@@aide.rappels.def.relance:Passé le délai, les silencieux reçoivent un rappel de confirmation et passent à « Relancé ». Ils n'en recevront pas d'autre : relancer quelqu'un tous les soirs ne le fait pas répondre plus vite. À vous de reprendre la main sur les derniers.`,
            },
            {
              term: $localize`:@@aide.rappels.term.relanceManuelle:Relancer à la main`,
              text: $localize`:@@aide.rappels.def.relanceManuelle:La veille de l'événement, une nuit de plus est une nuit de trop. Sur la page Animateurs, le filtre « Accusés » isole les personnes jamais confirmées ou silencieuses depuis N jours ; sélectionnez-les et « Relancer maintenant » leur envoie le même rappel que la nuit. La règle ne change pas : une seule relance par personne et par publication, et le compte rendu nomme ceux qu'elle a laissés de côté.`,
            },
            {
              term: $localize`:@@aide.rappels.term.echecEnvoi:Échec d'envoi`,
              text: $localize`:@@aide.rappels.def.echecEnvoi:Le dernier courriel envoyé à cette personne n'est pas parti : elle n'est pas silencieuse, personne n'a pu la joindre. La date et la cause s'affichent au survol, et le filtre « Accusés » les isole. Quand le serveur d'envoi a refusé l'adresse, ni la relance de nuit ni « Relancer maintenant » n'y réécrivent tant que la fiche n'a pas été modifiée : corrigez l'adresse, ou appelez. Un échec temporaire, lui, n'empêche pas la relance suivante. « Parti » veut dire accepté par le serveur d'envoi, jamais lu : une boîte pleine signalée plus tard n'arrive pas jusqu'ici.`,
            },
            {
              term: $localize`:@@aide.rappels.term.echanges:Les demandes d'échange qui dorment`,
              text: $localize`:@@aide.rappels.def.echanges:Une demande qui attend votre décision depuis plus longtemps que le délai fixé remonte sur la page Notifications, une seule fois. L'ancienneté se compte à partir de l'accord du collègue : une demande qui attend encore sa réponse n'attend pas après vous.`,
            },
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.rappels.silence:« Je n'ai rien reçu » a presque toujours la même cause : l'édition n'a pas été activée. Ensuite viennent le planning jamais publié, puis les fiches sans adresse e-mail. Ces alertes se referment en traitant ce qu'elles signalent, pas en les effaçant.`,
        },
      ],
      links: [
        { route: '/animateurs', label: $localize`:@@nav.link.animateurs:Animateurs` },
        {
          route: '/parametres',
          queryParams: { onglet: 'emails' },
          label: $localize`:@@aide.lien.parametresEmails:Paramètres — onglet E-mails automatiques`,
        },
        { route: '/notifications', label: $localize`:@@nav.link.notifications:Notifications` },
      ],
    },
  ];
}
