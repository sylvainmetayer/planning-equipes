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
          text: $localize`:@@aide.foire.intro:Chaque animateur a un espace personnel, ouvert par un lien qui lui est propre. Il n'y a pas de compte à créer, mais un code envoyé à l'adresse e-mail de sa fiche lui est demandé une fois par appareil : comme le planning se télécharge depuis l'espace, un minimum d'authentification s'impose. Un animateur sans adresse ne peut pas y entrer, pensez à l'ajouter.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.foire.echange:Il y consulte son planning à jour et peut proposer d'échanger un créneau avec un collègue, soit en le cédant simplement, soit en désignant en plus le créneau qu'il veut récupérer. Le collègue donne d'abord son accord depuis son propre espace : une demande n'arrive sur votre écran Échanges qu'une fois les deux d'accord, et un refus la clôt sans arbitrage. Chaque demande est prévalidée contre les règles dures ; une demande irréalisable est signalée mais vous est transmise quand même, et c'est vous qui tranchez.`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.foire.term.ecran:Écran Échanges`,
              text: $localize`:@@aide.foire.def.ecran:L'écran s'ouvre sur la file : les demandes en attente de votre décision, avec pour chacune son impact mesuré (échange croisé ou simple reprise, effet sur le score, règles dures qui seraient cassées). Accepter applique l'échange immédiatement, exactement comme simulé, et le verrouille sur son créneau : une régénération ne le défera pas. Sa carte propose alors trois suites sur place : « Prévenir les 2 personnes » publie pour ces deux-là seulement, « Corriger le reste » lance une résolution incrémentale, et « Voir la journée » ouvre le jour concerné. Refuser ne modifie rien, et le motif est transmis à l'animateur. Une demande décidée reste marquée « pas encore communiquée » tant qu'elle n'a pas été publiée. L'en-tête compte les demandes arrivées depuis votre dernière visite sur ce navigateur.`,
            },
            {
              term: $localize`:@@aide.foire.term.statistiques:Statistiques de la foire`,
              text: $localize`:@@aide.foire.def.statistiques:L'onglet Statistiques de l'écran Échanges dit si la foire fonctionne, sur la fenêtre de la foire quand elle est datée, sur toute l'édition ou entre deux dates de création. Il compte les demandes créées, dont dirigées, et celles qui attendent le collègue ou l'organisation. Trois taux ne disent pas la même chose : l'accord des collègues (parmi les demandes auxquelles le collègue a répondu : un refus que vous prononcez avant sa réponse n'en est pas une, un accord suivi d'une annulation par le demandeur en est une), l'acceptation par l'organisation (parmi celles que vous avez tranchées, jamais une annulation) et l'aboutissement (parmi toutes les demandes terminées, annulations comprises). Sous cinq demandes, un taux s'affiche « 2 sur 3 » plutôt qu'en pourcentage. Les quatre délais — réponse du collègue, arbitrage, communication par la publication, annulation — donnent la médiane et le délai sous lequel tiennent neuf demandes sur dix ; la moyenne s'affiche au survol, car quelques demandes oubliées la tirent vers le haut. L'arbitrage se mesure depuis l'accord du collègue : un refus prononcé avant sa réponse n'entre ni dans ce délai ni dans celui de réponse, puisque personne n'a répondu. Une décision pas encore publiée attend son délai de communication. Une demande plus ancienne qu'un horodatage est comptée dans les volumes mais pas dans le délai, et signalée « sans horodatage ». Les répartitions montrent les jours de création, les jours et les stands qu'on cherche à quitter, et les règles que cassent les demandes non prévalidées. Chaque chiffre ouvre la liste filtrée sur exactement les demandes qu'il compte — pour un délai, celles qui ont été mesurées : l'onglet ne montre aucun nom.`,
            },
            {
              term: $localize`:@@aide.foire.term.espace:Ce que l'animateur voit`,
              text: $localize`:@@aide.foire.def.espace:Il monte sa liste de demandes puis la soumet en une fois. Quand il n'a personne en tête, « qui peut me remplacer ? » cherche les collègues avec qui l'échange tient réellement. Il suit ensuite le statut de chacune, avec votre commentaire, et peut annuler tant que rien n'est décidé. Le lien de son espace se copie depuis sa fiche, sur la page Animateurs, où on le régénère aussi si un PDF a fuité.`,
            },
            {
              term: $localize`:@@aide.foire.term.planPublie:Plan publié et plan de travail`,
              text: $localize`:@@aide.foire.def.planPublie:L'espace d'un animateur montre le plan qu'on lui a envoyé, pas celui sur lequel vous travaillez : un échange validé, un remplacement ou une nouvelle résolution ne déplacent son espace qu'une fois publiés. Tant que rien n'a été publié, les espaces restent vides et le disent. Publier est refusé pendant une résolution, et sur une édition qui n'est pas l'édition active.`,
            },
            {
              term: $localize`:@@aide.foire.term.abonnement:Abonnement au calendrier`,
              text: $localize`:@@aide.foire.def.abonnement:Un animateur peut donner à son agenda une adresse d'abonnement permanente, au lieu de télécharger un ICS qui se périme. À chaque synchronisation, l'agenda relit le planning publié : si « mon agenda ne se met pas à jour », c'est presque toujours que le changement n'a pas encore été publié. Cette adresse est un second identifiant, distinct du lien de l'espace : régénérer le lien ne coupe pas son abonnement, et l'animateur remplace lui-même son adresse si elle a fuité. Vous ne la voyez pas.`,
            },
            {
              term: $localize`:@@aide.foire.term.ouverture:Ouverture et fermeture`,
              text: $localize`:@@aide.foire.def.ouverture:L'interrupteur de la foire, sur la page Paramètres, onglet Édition, carte Guichets, l'ouvre ou la ferme pour l'édition courante ; elle est ouverte par défaut. L'écran Échanges en garde une ligne d'état, avec un bouton pour la fermer. Une fois la foire fermée, les espaces passent en consultation seule : le planning reste visible et téléchargeable, mais plus aucune demande n'est acceptée, et le refus vient du serveur. Vous pouvez aussi borner la foire par deux dates, mais l'interrupteur reste maître. Avant la date d'ouverture, l'espace annonce « pas encore ouverte » et la date de retour, jamais « fermée ».`,
            },
            {
              term: $localize`:@@aide.foire.term.envoi:Publier le planning`,
              text: $localize`:@@aide.foire.def.envoi:En tête de l'onglet Envoyer de la page Diffuser, le bouton porte son décompte : « Publier — 3 personnes concernées ». La phrase à côté dit ses deux effets : il envoie leur nouveau planning aux seules personnes dont il a changé, et il met à jour leur espace. Dix corrections d'affilée ne font donc pas dix courriels : elles remplissent une file que vous videz quand vous avez fini. Le compte rendu nomme les animateurs sans adresse et les envois en échec. Sur un gros effectif, l'envoi prend plusieurs dizaines de secondes.`,
            },
            {
              term: $localize`:@@aide.foire.term.relecture:Relire la liste avant d'envoyer`,
              text: $localize`:@@aide.foire.def.relecture:« Voir ce qui change pour chacune » ouvre une ligne par personne : ce qui change pour elle, sa dernière confirmation, et une case cochée. Triez par ampleur pour commencer par les plus gros changements et repliez les changements mineurs (même stand, un quart d'heure de décalage au plus). Le filtre ne décide rien : les lignes repliées partent quand même. L'onglet Documents en donne le détail en CSV, pour le relire ailleurs.`,
            },
            {
              term: $localize`:@@aide.diffuser.term.table:Qui a reçu quelle version`,
              text: $localize`:@@aide.diffuser.def.table:Sous le bouton, une table permanente liste toute l'édition, qu'il reste ou non quelqu'un à prévenir : la version reçue (« v3 · 01/09 »), l'état de l'envoi (envoyé, échec avec sa cause, sans e-mail, différé), le rappel de la veille, la relance et l'accusé de réception. Un envoi en échec apparaît dans la table, et pas seulement dans le journal du serveur ; l'accueil n'affiche pas « à jour » tant qu'il en reste. Sur chaque ligne, « Renvoyer son planning » renvoie le planning publié, « Relancer » envoie le rappel de confirmation et « Différer » retire la personne de la prochaine publication. Les filtres isolent les personnes à prévenir, les envois en échec, les fiches sans e-mail, les silencieux et les différés ; « Ce qui change le » ne garde que les personnes dont les changements tombent ce jour-là, selon la règle de l'onglet « Changements » de la Journée. Un bandeau dit si les relances automatiques partent : elles sont coupées tant que l'édition n'est pas l'édition active, et le lien mène à la page Éditions.`,
            },
            {
              term: $localize`:@@aide.diffuser.term.documents:Les documents`,
              text: $localize`:@@aide.diffuser.def.documents:L'onglet Documents donne les quatre fichiers que devient le planning, chacun avec son destinataire : une feuille recto-verso par personne à remettre en main propre, l'archive des plannings individuels (PDF et calendrier), le classeur de l'organisateur et le détail des changements en CSV. Imprimer une journée se fait depuis la Journée.`,
            },
            {
              term: $localize`:@@aide.foire.term.differer:Ne pas prévenir quelqu'un ce soir`,
              text: $localize`:@@aide.foire.def.differer:Décochez une personne, ou cliquez « Différer » sur sa ligne de la table, et son message est différé : elle ne reçoit rien pour l'instant, et la publication suivante la nomme à nouveau. L'écart est alors cumulé depuis le dernier message qu'elle a vraiment reçu, et non depuis un plan publié entre-temps. C'est ce qu'on fait quand on préfère l'appeler d'abord. Tout décocher est refusé, puisque publier sans prévenir personne n'aurait aucun sens.`,
            },
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.foire.notifications:Si la messagerie est configurée, vous êtes prévenu par e-mail à chaque soumission. L'animateur apprend le sort de ses demandes à la publication suivante, ou tout de suite si vous cliquez « Prévenir les 2 personnes ». Accepter un échange change en effet le plan de travail, et pas encore celui qu'il a reçu.`,
        },
      ],
      links: [
        { route: '/echanges', label: $localize`:@@nav.link.echanges:Échanges` },
        { route: '/publication', label: $localize`:@@nav.link.diffuser:Diffuser` },
        { route: '/animateurs', label: $localize`:@@nav.link.animateurs:Animateurs` },
        {
          route: '/consignes-solveur',
          queryParams: { onglet: 'verrouillages' },
          label: $localize`:@@consignesSolveur.onglet.verrouillages:Verrouillages`,
        },
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
              text: $localize`:@@aide.dispo.def.fenetre:La collecte s'ouvre et se ferme sur la page Paramètres, onglet Édition, carte Guichets ; la page Disponibilités en garde une ligne d'état, avec un bouton pour la fermer. Le covoiturage la suit : il s'ouvre et se ferme avec elle. Contrairement à la foire au planning, la collecte est fermée tant que vous ne l'ouvrez pas. Les deux dates sont facultatives et bornent la période ; l'interrupteur reste maître. Fermée, l'espace refuse toute déclaration côté serveur, mais l'animateur garde l'accès à ce qu'il a déclaré et à vos réponses.`,
            },
            {
              term: $localize`:@@aide.dispo.term.prevenir:Prévenir les animateurs`,
              text: $localize`:@@aide.dispo.def.prevenir:Une case à cocher au moment d'ouvrir, jamais un réglage permanent : elle envoie à chacun le lien de son espace, directement sur l'onglet de déclaration. Cochez-la au premier tour ; laissez-la de côté quand vous rouvrez après une correction, sinon tout le monde reçoit une relance pour rien. Elle est refusée hors de l'édition active : ouvrir la collecte d'une édition en préparation est permis, écrire à ses animateurs non, et leur espace reste fermé jusqu'à l'activation.`,
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
              term: $localize`:@@aide.dispo.term.covoiturage:Covoiturage : un onglet à part`,
              text: $localize`:@@aide.dispo.def.covoiturage:Pendant la même fenêtre, l'espace de chaque animateur porte un onglet « Covoiturage » : « Je viens avec… », de un à trois coéquipiers, jamais soi-même. La demande est envoyée et décidée à part de la déclaration : appliquer ou refuser une déclaration ne la touche jamais. Elle arrive sur l'onglet « Covoiturage » de cette page (le nombre en attente s'affiche sur l'onglet), une carte par demande : « Confirmé par tous » quand chaque membre a nommé exactement le même groupe, et le nombre de jours où leurs indisponibilités déclarées divergent. Ces jours-là, ils ne pourront pas arriver ensemble.`,
            },
            {
              term: $localize`:@@aide.dispo.term.covoiturageDecision:Valider ou écarter un covoiturage`,
              text: $localize`:@@aide.dispo.def.covoiturageDecision:Chaque demande se décide seule. « Valider l'arrivée groupée » crée un ajustement manuel « Arrivée groupée » qui nomme le demandeur et ses coéquipiers ; les demandes des autres membres qui nomment le même groupe sont validées avec elle, et un groupe identique déjà présent est rejoint plutôt que doublé. Le planning n'en tient compte qu'à la prochaine résolution. « Écarter » n'écrit rien : un motif facultatif (500 caractères au plus) est lu par l'animateur dans son espace, et il peut envoyer une nouvelle demande tant que la collecte est ouverte. Dans les deux cas, les personnes concernées reçoivent un e-mail (chaque membre pour une validation, le demandeur pour un écart), et un envoi qui échoue n'annule pas la décision. Une fois validé, le groupe ne se modifie plus depuis l'espace, ni depuis l'onglet Ajustements de Consignes au solveur : il s'annule d'ici.`,
            },
            {
              term: $localize`:@@aide.dispo.term.covoiturageAnnulation:Annuler une arrivée groupée validée`,
              text: $localize`:@@aide.dispo.def.covoiturageAnnulation:Dans « Déjà traitées », un groupe validé porte « Annuler l'arrivée groupée ». Un motif facultatif (500 caractères au plus, jamais recopié dans le journal ni rendu à l'assistant) est lu par chaque membre dans son espace. L'ajustement « Arrivée groupée » est supprimé, toutes les demandes validées avec lui passent « Annulée » et restent dans l'historique avec le motif, et chaque membre reçoit un e-mail qui nomme les autres : il peut envoyer une nouvelle demande si la collecte est ouverte, sinon il est invité à s'adresser à vous. Un envoi qui échoue n'annule pas l'annulation. Le planning n'en tient compte qu'à la prochaine résolution. Tant qu'une demande validée s'appuie sur une arrivée groupée, l'onglet Ajustements de Consignes au solveur la montre sans permettre de la modifier ni de la supprimer : c'est ici que le groupe est prévenu.`,
            },
            {
              term: $localize`:@@aide.dispo.term.competences:Ce qui ne se déclare pas`,
              text: $localize`:@@aide.dispo.def.competences:Les compétences restent décidées avec vous : une compétence auto-déclarée alimente des règles dures (qui a le droit de tenir quel stand), là où une indisponibilité ou un souhait se rattrapent. Un animateur qui déclare un jour hors des dates de l'événement, ou une typologie inexistante, est refusé à l'envoi.`,
            },
          ],
        },
      ],
      links: [
        { route: '/disponibilites', label: $localize`:@@nav.link.disponibilites:Disponibilités` },
        { route: '/animateurs', label: $localize`:@@nav.link.animateurs:Animateurs` },
        { route: '/creneaux', label: $localize`:@@nav.link.creneaux:Créneaux` },
        {
          route: '/consignes-solveur',
          queryParams: { onglet: 'ajustements' },
          label: $localize`:@@consignesSolveur.onglet.ajustements:Ajustements`,
        },
      ],
    },
    {
      id: 'rappels',
      icon: 'notifications_active',
      title: $localize`:@@aide.rappels.title:Accusés de réception et rappels`,
      summary: $localize`:@@aide.rappels.summary:Savoir qui a lu son planning, et laisser l'application relancer les autres, une seule fois.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.rappels.intro:Une fois le planning publié, chaque animateur voit dans son espace un bouton « J'ai lu et je serai là ». La colonne « Accusé de réception » de la page Animateurs vous rend la réponse, et le filtre de la table accepte « confirmé », « relancé », « silencieux » ou « échec d'envoi ».`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.rappels.term.statuts:Les quatre statuts`,
              text: $localize`:@@aide.rappels.def.statuts:« Silencieux » : rien n'est revenu. « Confirmé » : le bouton a été cliqué, la date s'affiche au survol. « Relancé » : la relance est partie et reste sans réponse. « Échec d'envoi » : le dernier courriel n'est pas parti, avec sa date et sa raison à côté. Un animateur sans aucun poste au planning publié affiche « — » : on ne lui a rien demandé, il ne compte pas parmi les gens à relancer.`,
            },
            {
              term: $localize`:@@aide.rappels.term.echecEnvoi:Un échec d'envoi n'est pas un silence`,
              text: $localize`:@@aide.rappels.def.echecEnvoi:Chaque courriel adressé à un animateur est noté, parti ou en échec, avec la raison de l'échec : serveur de messagerie injoignable, identifiants refusés, adresse refusée, refus temporaire ou autre erreur. « Parti » veut dire remis au serveur de messagerie, pas reçu ni lu ; un serveur qui n'envoie rien pour de vrai le dit « simulé ». La synthèse en tête de la page Animateurs compte les échecs à part, son filtre « Échec d'envoi » les rassemble, la fiche animateur les signale et la ligne « Accusés de réception » de l'accueil y mène. Une adresse refusée n'est plus relancée, ni la nuit ni à la main, tant que la fiche n'a pas été modifiée : corrigez l'adresse, ou joignez la personne autrement. N'importe quelle modification de la fiche lève le signal, et la relance suivante dira si l'adresse est bonne.`,
            },
            {
              term: $localize`:@@aide.rappels.term.republication:Republier ne remet pas tout le monde à zéro`,
              text: $localize`:@@aide.rappels.def.republication:Une nouvelle publication ne redemande une confirmation qu'aux personnes dont l'emploi du temps a réellement changé. Quelqu'un qu'on prévient d'une décision d'échange lit les mêmes journées qu'avant : lui reposer la question transformerait le bouton en réflexe plutôt qu'en réponse.`,
            },
            {
              term: $localize`:@@aide.rappels.term.activation:Activer l'édition`,
              text: $localize`:@@aide.rappels.def.activation:Les envois de nuit ne partent que de l'édition active, choisie sur la page Éditions ; il n'y a pas d'autre interrupteur. C'est le seul garde-fou : une édition passée porte les mêmes fiches, et rien ne distingue les animateurs de cette année de ceux de l'an dernier. Toute édition naît inactive, même dupliquée : quelqu'un doit décider de l'activer.`,
            },
            {
              term: $localize`:@@aide.rappels.term.delais:Les trois délais`,
              text: $localize`:@@aide.rappels.def.delais:L'heure d'envoi du rappel de la veille (18 h par défaut, 23 h au plus tard : la tâche passe une fois par heure, et un rappel réglé plus tard ne partirait jamais), le silence toléré après une publication avant de relancer, et l'ancienneté d'une demande d'échange qui déclenche une alerte. Tous trois se règlent par édition.`,
            },
            {
              term: $localize`:@@aide.rappels.term.rappel:Le rappel de la veille`,
              text: $localize`:@@aide.rappels.def.rappel.accueil:La veille au soir, chaque animateur affecté le lendemain reçoit la liste de ses créneaux. Elle est tirée du planning publié, jamais du plan de travail : personne n'est rappelé pour un créneau que vous ne lui avez pas communiqué. Un animateur sans adresse est sauté : « À traiter aujourd'hui » le compte tant que son jour est à venir, et « Messages récents » le nomme.`,
            },
            {
              term: $localize`:@@aide.rappels.term.relance:Une relance, pas une série`,
              text: $localize`:@@aide.rappels.def.relance:Passé le délai, les silencieux reçoivent un rappel de confirmation et passent à « Relancé ». Une relance de nuit qui échoue ne compte pas : la personne reste « échec d'envoi », une alerte reste sur l'accueil, et la nuit ne réessaie pas, sauf pour une adresse refusée, relancée au passage qui suit la correction de la fiche. Ceux qui l'ont reçue n'en recevront pas d'autre : relancer quelqu'un tous les soirs ne le fait pas répondre plus vite. À vous de reprendre la main sur les derniers.`,
            },
            {
              term: $localize`:@@aide.rappels.term.relanceManuelle:Relancer à la main`,
              text: $localize`:@@aide.rappels.def.relanceManuelle:À la veille de l'événement, vous ne pouvez plus attendre une nuit de plus. Sur la page Diffuser, le filtre « Silencieux » isole les personnes jamais confirmées ; « Relancer » sur une ligne, ou « Relancer les N silencieux » au-dessus de la table, leur envoie le même rappel que celui de la nuit. La page Animateurs le permet aussi, sur une sélection. La règle ne change pas : une seule relance par personne et par publication, et le compte rendu dit combien de relances sont parties. Une relance de nuit en échec ne compte pas : « Relancer maintenant » reste possible une fois la messagerie rétablie. Une adresse refusée est sautée tant que sa fiche n'a pas été modifiée, et le compte rendu la nomme.`,
            },
            {
              term: $localize`:@@aide.rappels.term.echanges:Les demandes d'échange qui dorment`,
              text: $localize`:@@aide.rappels.def.echanges.accueil:Une demande qui attend votre décision depuis plus longtemps que le délai fixé passe en alerte dans « À traiter aujourd'hui », sur la page d'accueil. L'ancienneté se compte à partir de l'accord du collègue : une demande qui attend encore sa réponse n'attend pas après vous.`,
            },
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.rappels.silence:« Je n'ai rien reçu » a presque toujours la même cause : l'édition n'est pas l'édition active. Ensuite viennent le planning jamais publié, puis les fiches sans adresse e-mail, puis les envois en échec, que la page Animateurs dit « échec d'envoi ». Ces alertes se referment en traitant ce qu'elles signalent, pas en les effaçant.`,
        },
      ],
      links: [
        {
          route: '/publication',
          queryParams: { filtre: 'silencieux' },
          label: $localize`:@@aide.lien.diffuserSilencieux:Diffuser — les silencieux`,
        },
        { route: '/animateurs', label: $localize`:@@nav.link.animateurs:Animateurs` },
        {
          route: '/animateurs',
          queryParams: { envoi: 'echec' },
          label: $localize`:@@aide.lien.animateursEchecEnvoi:Animateurs — échecs d'envoi`,
        },
        {
          route: '/parametres',
          queryParams: { onglet: 'edition' },
          label: $localize`:@@aide.lien.parametresEmails:Paramètres — e-mails automatiques`,
        },
        {
          route: '/',
          fragment: 'a-traiter',
          label: $localize`:@@aide.lien.aTraiter:Accueil — À traiter aujourd'hui`,
        },
        { route: '/editions', label: $localize`:@@nav.link.editions:Éditions` },
      ],
    },
  ];
}
