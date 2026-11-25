# L'espace animateur côté API

Vous saurez comment un animateur entre dans son espace, ce que l'API lui montre et ce qu'elle lui laisse écrire : échanges de créneaux, abonnement ICS, déclaration de disponibilités, covoiturage, empêchement signalé. Chaque borne qui protège une route ouverte sans session admin est donnée avec la raison qui la justifie.

## Entrer dans l'espace

Les routes de `/api/espace-animateur/{jeton}` sont, avec l'abonnement ICS, les seules accessibles sans session admin. Le jeton, le lien imprimé sur le PDF individuel, résout à lui seul l'animateur et son édition : `X-Edition-Id` n'y est pas lu. Un jeton inconnu répond `404`, jamais `401`, et le jeton d'une édition qui n'est pas l'édition active répond exactement la même chose, pour ne rien dire de sa validité ([décision 0072](repo:docs/decisions/0072-une-seule-edition-active.md)).

![Entrée dans l'espace animateur et accusé de réception](repo:docs/diagrammes/espace-animateur.svg)

<sub>Source : [`diagrammes/espace-animateur.puml`](repo:docs/diagrammes/espace-animateur.puml).</sub>

Le lien ne suffit pas : l'espace sert le planning en téléchargement, donc toutes les routes sauf `POST .../code` et `POST .../session` exigent aussi la session du cookie `planning-espace`, liée à l'animateur que le jeton résout. L'e-mail de la fiche est le second facteur : sans adresse, pas de code, et `400`. Les demandes de code sont plafonnées (`ESPACE_CODE_MAX_DEMANDES`, 3 par défaut, sur `ESPACE_CODE_FENETRE`, 10 minutes) et au-delà répondent `429` ; ouvrir la session efface le compteur.

`POST /api/animateurs/{id}/token` fait tourner le jeton et invalide aussitôt le lien distribué : c'est le geste à faire quand un planning individuel a fuité. Il ne touche pas au jeton d'abonnement ICS, qui est une clé distincte.

## Ce que l'espace montre

L'espace montre le plan publié, pas le plan de travail : l'animateur voit ce qu'on lui a envoyé. Un échange validé, une réparation appliquée ou un solve ne déplacent pas son espace sans publication. `publieLe` nul signifie que rien n'a été communiqué : les postes sont vides et l'interface le dit. Les suggestions d'échange se calculent sur ce même plan, on ne troque que ce qu'on nous a annoncé.

Une décision prise et pas encore publiée doit pouvoir se nommer. `communiqueeLe` est nul tant que la publication qui l'annonce n'est pas partie. L'espace ne le dit que d'une acceptation, le seul cas où le statut contredit ce que la personne voit ; l'écran d'administration le dit de toute décision, c'est son reste à publier. Sans cet état, « Acceptée » surmonte un planning inchangé, et l'animateur croit à une panne, redemande un échange accordé ou se présente au mauvais stand.

Le lieu voyage avec le poste : `emplacementNom`, `emplacementLatitude` et `emplacementLongitude` accompagnent chaque poste et chaque pause, lus sur le référentiel au moment de la lecture (un hall renommé se lit renommé). Ils sont nuls quand le stand n'a pas d'emplacement, et les coordonnées ne sortent que complètes (`Emplacement.isGeocoded()`, la question que se posent déjà la ligne `GEO` de l'ICS et l'épingle du PDF) : une latitude seule n'est pas une position.

Foire fermée, soumissions et annulations sont refusées côté serveur (`400`) ; `foireOuverte` ne sert qu'à l'afficher, et une borne datée est appliquée de la même façon. `foireOuvreLe` distingue « pas encore ouverte » de « fermée » (même booléen côté serveur, sens inverse pour qui lit) ; il est nul quand la foire est ouverte et quand la fenêtre est passée. Le planning reste visible et téléchargeable.

## Abonnement ICS permanent

`GET /api/abonnements/{token}/planning.ics` sert le planning publié de l'animateur, reconstruit à chaque appel, en `text/calendar`. C'est l'adresse qu'on donne une fois à son agenda : une republication apparaît à la synchronisation suivante. Le téléchargement direct `GET /api/espace-animateur/{jeton}/planning.ics` reste une photo à un instant.

Un client d'agenda n'a pas de cookie et ne sait pas répondre à un défi : exiger la session rendrait l'abonnement impossible. Plutôt que d'en faire une preuve d'accès complète sans second facteur pour le jeton d'espace, l'abonnement a sa propre clé, révocable séparément ([décision 0019](repo:docs/decisions/0019-jeton-et-chemin-dedies-pour-l-abonnement-ics.md), [jetons publics](../installer/securite-jetons-publics.md)).

| | Jeton d'espace (`access_token`) | Jeton d'abonnement (`abonnement_token`) |
| --- | --- | --- |
| Chemin | `/api/espace-animateur/{jeton}/…` | `/api/abonnements/{token}/planning.ics` |
| Requis en plus | la session ouverte par code e-mail | rien |
| Ouvre | tout l'espace : planning, PDF, échanges, disponibilités, écritures | un document, en lecture |
| Rotation | `POST /api/animateurs/{id}/token` (admin) | `POST /api/espace-animateur/{jeton}/abonnement` (l'animateur) |

Le préfixe `/api/abonnements/` n'existe que pour cette route : c'est le motif qu'un proxy d'accès excepte de son authentification, il ne doit désigner que ça et aucune autre route n'y est liée. Deux comportements :

- jeton inconnu ou révoqué, ou édition terminée : `404` avec une phrase fixe (« Abonnement inconnu, révoqué, ou édition terminée »), la même dans tous les cas, qui n'aide ni à deviner un jeton ni à savoir s'il a existé ;
- rien de publié : `200` et un calendrier vide, jamais `404`. Un client qui rencontre un `404` répété désactive le flux, et l'animateur abonné avant la première publication devrait se réabonner sans qu'on le lui dise.

La réponse porte `Cache-Control: private, no-cache` : le document est nominatif, il ne doit ni transiter par un cache partagé ni être rejoué après une publication. `POST /api/espace-animateur/{jeton}/abonnement` (session requise) révoque l'adresse en cours et en crée une neuve (`{"abonnementToken": "…"}`) ; le lien déjà imprimé sur un PDF continue de fonctionner, et la vue de l'espace porte le jeton courant dans `abonnementToken`.

## Échanges

`GET` et `PUT /api/echanges/configuration` règlent la fenêtre de la foire au planning.

| Champ | Rôle |
| --- | --- |
| `foireOuverte` | L'interrupteur, et le maître : une fenêtre datée mais fermée n'accepte rien |
| `debut` / `fin` | Bornes facultatives, incluses ; vide = pas de borne |
| `ouverteAujourdhui` | Lecture seule : l'interrupteur et la date du jour dans les bornes. Distinct de `foireOuverte` pour dire « ouverte, mais hors période » |

Une fin antérieure au début est refusée (`400`) avant l'écriture : une configuration refusée laisse la précédente en place.

![Une demande d'échange, de la soumission à la décision](repo:docs/diagrammes/echange.svg)

<sub>Source : [`diagrammes/echange.puml`](repo:docs/diagrammes/echange.puml).</sub>

![Cycle de vie d'une demande d'échange](repo:docs/diagrammes/echange-etats.svg)

<sub>Source : [`diagrammes/echange-etats.puml`](repo:docs/diagrammes/echange-etats.puml).</sub>

Une demande naît `EN_ATTENTE_CIBLE` et n'entre dans la file décidable (`PROPOSEE`) qu'une fois acceptée par le collègue ciblé ; un refus du collègue est terminal, l'admin n'arbitre jamais. Elle est prévalidée contre les contraintes dures mais enregistrée quel que soit le verdict : la réponse porte `prevalidationOk` et les contraintes non respectées. Si le collègue tient aussi un poste sur le créneau, les deux permutent ; sinon il reprend le poste. Un échange dirigé cède un créneau et en récupère un autre désigné.

L'acceptation applique l'échange comme simulé, pose deux verrous `ANIMATEUR_CRENEAU` sur le créneau que chacun reçoit et n'écrit à personne : le plan de travail a changé, pas le plan publié, et la publication suivante l'annonce. Le solveur n'est pas relancé. Un créneau déjà commencé, donné ou reçu, refuse l'échange à la proposition comme à l'acceptation (`400`, [décision 0044](repo:docs/decisions/0044-le-passe-est-fige.md)). Un lot sollicite chaque collègue visé par une notification propre, qui ne compte que ses seules demandes : personne n'apprend ce qui a été proposé à un autre.

### Qui peut me remplacer

`GET /api/espace-animateur/{jeton}/suggestions-echange?creneauId=X&standId=Y&plafond=N` sert l'animateur qui ne veut pas d'un créneau et n'a personne en tête : il ne désigne que son siège, et l'assistant cherche les échanges qui tiennent. Même mécanique que l'assistant de réparation (filtre d'éligibilité du solveur des deux côtés du troc, une simulation par piste, verdict sur le plan entier), plafond de 20 par défaut et 100 au plus. Trois familles, portées par `nature` :

| `nature` | Effet pour le demandeur |
| --- | --- |
| `LIBERE` | Le collègue est libre à cette heure : le créneau quitte ses mains |
| `CROISE` | Le collègue travaille ce même créneau : les sièges permutent, le demandeur change de stand |
| `DIRIGE` | Un siège d'un autre créneau revient ; la suggestion porte `creneauCibleId`, sa date, ses horaires et son stand, de quoi construire la demande dirigée telle quelle |

Les familles sont évaluées en alternance : avec 150 collègues libres, le plafond partirait entier en « on vous libère » sans jamais demander si un mardi s'échange contre un lundi. Au plus deux sièges par collègue sont proposés, pour que le choix porte sur des jours et pas sur une personne. `listeTronquee` dit que la recherche s'est arrêtée au plafond. La réponse ne porte aucun score : un `HardMediumSoftScore` ne dit rien à un animateur, et la santé globale du plan ne doit pas sortir par un jeton. La route est en lecture seule, ne cherche que pour ses propres sièges (`400` sinon) et seulement foire ouverte.

## Déclaration de disponibilités

L'animateur déclare ses jours d'indisponibilité et ses souhaits de typologies. Rien n'est écrit au référentiel à la soumission : la déclaration attend une décision explicite de l'admin.

![Collecte des disponibilités](repo:docs/diagrammes/declaration-disponibilites.svg)

<sub>Source : [`diagrammes/declaration-disponibilites.puml`](repo:docs/diagrammes/declaration-disponibilites.puml).</sub>

C'est la première route en écriture ouverte depuis l'espace, et trois bornes la tiennent.

| Borne | Ce qu'elle empêche |
| --- | --- |
| Fenêtre de collecte fermée par défaut | La foire est ouverte tant que personne ne l'a fermée, par compatibilité ; une route qui écrit ne s'ouvre pas par omission |
| Une seule proposition en attente par animateur (index unique partiel) | Le volume : renvoyer mille fois laisse une ligne |
| `ESPACE_DECLARATION_MAX_ENVOIS` par animateur et par fenêtre (20 par défaut) | Le rythme : au-delà, `429` et `Retry-After` |

Un jour hors des dates de l'événement et une typologie inconnue sont refusés (`400`) ; tant qu'aucun créneau n'existe, aucun jour n'est imposé, car collecter avant la grille est le cas d'usage. Les compétences ne se déclarent pas ici : une compétence auto-déclarée alimente des contraintes dures, l'enjeu de validation n'est pas le même.

`GET` et `POST /api/espace-animateur/{jeton}/disponibilites` : le `GET` rend l'état de la fenêtre, les jours, les typologies, ce que le référentiel dit de la personne, sa proposition en attente et son historique, y compris fenêtre fermée (on doit pouvoir relire ce qu'on a déclaré). Le `POST` remplace la proposition en attente et répond la même vue. Le formulaire s'ouvre sur la proposition en attente, sinon sur le référentiel : une page blanche voudrait dire « disponible tous les jours ». `collecteOuverte` est faux dans deux situations opposées, pas encore commencée et terminée, que sépare `collecteDebut` : « revenez à partir du 15 » n'est pas « la collecte est fermée ».

Côté administration, `GET /api/disponibilites` liste les déclarations de l'édition, les plus récentes d'abord. `GET` et `PUT /api/disponibilites/configuration` lisent et écrivent la fenêtre ; `prevenirAnimateurs` déclenche en plus l'envoi des invitations. `POST /api/disponibilites/invitations` renvoie les liens d'une collecte ouverte (`409` sinon, ou édition inactive) sans toucher à la fenêtre ; `destinataires` vaut `NOT_YET_INVITED` (défaut) ou `NOT_YET_DECLARED`. `POST /api/disponibilites/{id}/application` applique la proposition entière sur la fiche via `AnimateurService` (donc `dataStale` se déclenche), `POST /api/disponibilites/{id}/refus` ne touche à rien et son commentaire est lu par l'animateur.

- Tout ou rien : l'admin accepte ou refuse la proposition entière, jamais ligne à ligne ; l'animateur renvoie une version corrigée tant que la fenêtre est ouverte.
- L'application réclame la décision avant d'écrire la fiche. Dans l'autre sens, un refus et une application simultanés laissaient le second écraser les jours de l'animateur puis recevoir un `409`, le référentiel ayant bougé pour une déclaration enregistrée refusée. Réclamer d'abord fait de la course un `409` net, fiche intacte.
- `prevenirAnimateurs` est une action, pas un réglage : il n'est jamais renvoyé. L'invitation est indispensable au premier tour et lassante à la réouverture, donc elle se coche à chaque fois. La réponse porte `invitation` (envoyés, `sansEmail`, échecs), et le lien pointe l'onglet de déclaration, construit par `ApplicationLinks`.
- Le `PUT` est atomique : sans `PUBLIC_URL`, une ouverture cochée « prévenir » répond `400` sans rien enregistrer, la collecte reste fermée. Ce qui échoue ensuite l'est par animateur et se lit dans `invitation` sans être levé : une adresse manquante va dans `sansEmail`, un jeton d'espace manquant dans `echecs` avec sa cause.
- « Cette fenêtre » n'a pas d'identité : `parametres_collecte` est une ligne par édition sans date d'ouverture. « Déjà invité » se lit donc dans `envoi_mail` (gabarit `invitation-declaration`, envois partis seulement), et fermer puis rouvrir ne remet pas le compte à zéro ; « tous ceux qui n'ont rien déclaré » est le moyen de tout renvoyer.

## Covoiturage

« Je viens avec… » (un à trois coéquipiers) ne voyage pas avec la déclaration : l'espace l'envoie par `POST /api/espace-animateur/{jeton}/covoiturage`, et l'admin la décide sous `/api/disponibilites/coequipiers`. Appliquer ou refuser une déclaration ne la touche jamais, et l'inverse non plus : deux décisions indépendantes sur deux tables (`declaration_coequipier`).

| Borne | Raison |
| --- | --- |
| La fenêtre de la collecte, la même que les déclarations | Une seule période où l'animateur écrit avant la construction ; fermée, l'onglet se lit mais l'envoi répond `400` |
| Une demande en attente par animateur | Renvoyer remplace ; une liste vide retire la demande |
| Un compteur de débit à part, au même plafond | Se corriger d'un côté ne consomme pas les envois de l'autre |
| `409` tant qu'une arrivée groupée validée nomme l'animateur | Le groupe validé appartient à l'organisation : elle l'annule, jamais l'espace |

`POST /api/disponibilites/coequipiers/{id}/validation` et `.../{id}/ecart` décident ; l'écart accepte un `reason` facultatif (500 caractères au plus), relu par l'animateur et absent du journal des actions, qui ne garde que l'identifiant. Chaque décision part en notification, donc au mieux : la validation écrit à chaque membre, l'écart au seul demandeur, avec un lien vers l'onglet Covoiturage. Un SMTP en panne n'annule jamais la décision.

`POST /api/disponibilites/coequipiers/{id}/annulation` vise le groupe, pas la seule demande `{id}` : il supprime l'ajustement et passe `ANNULEE` toutes les demandes validées avec lui, en une transaction. `404` pour un `{id}` inconnu, `409` pour une demande qui n'est plus validée, ou dont l'ajustement a disparu, et pendant qu'une résolution tient l'édition (le calcul en cours note encore l'arrivée groupée reçue). Le piège est de l'autre côté : `DELETE /api/contraintes-ad-hoc/{id}` et le `POST` qui réécrit un ajustement répondent `409` sur une arrivée groupée qu'une demande validée soutient (`issueDeCovoiturage` dans la vue), faute de quoi le groupe croirait à une voiture qui n'existe plus.

## Empêchement signalé

« Je ne pourrai pas être là » : l'animateur signale qu'il sera absent toute une journée ou sur un poste, par `POST /api/espace-animateur/{jeton}/signalements` ; `DELETE /api/espace-animateur/{jeton}/signalements/{signalementId}` annule le sien. C'est la deuxième route en écriture de l'espace, ouverte toute l'édition, foire et collecte fermées comprises. Rien n'est écrit au planning : l'organisation constate ou classe ([décision 0065](repo:docs/decisions/0065-empechement-signale-declaration-constatee.md)).

| Borne | Ce qu'elle empêche |
| --- | --- |
| Une journée où l'animateur tient un poste dans le planning communiqué, pas encore terminée ; pour un poste, le sien et pas terminé (`400`) | Un signalement sur ce qu'il ne voit pas, ou sur du passé |
| Un seul signalement ouvert par personne et par objet (index unique partiel) ; une journée signalée entière couvre ses postes (`409`) | Le volume |
| `ESPACE_DECLARATION_MAX_ENVOIS`, compté à part de la déclaration et avant toute validation | Le rythme : `429` et `Retry-After` |

Le poste est désigné par sa clé naturelle (`creneauId` et `standId`), jamais par l'identifiant du siège qu'une résolution renumérote. Le motif est facultatif et en liste fermée (`PERSONNEL`, `TRANSPORT`, `AUTRE`) : aucun champ libre, c'est là qu'on écrirait une raison de santé que personne n'a demandée. L'organisation est prévenue par courriel au mieux, un courriel perdu n'annule pas le signalement que l'écran Aujourd'hui liste de toute façon. L'animateur annule le sien tant qu'il est ouvert, jamais après (`409`).

La décision passe par `POST /api/jour-j/signalements/{signalementId}/traitement` (« Marquer absent et remplacer ») et `.../classement` (« Classer »). Le traitement réserve le signalement dans la transaction qui écrit l'absence : deux décisions à une seconde d'écart, ou une annulation qui croise la décision, laissent un seul gagnant et l'autre reçoit `409`, sans absence posée deux fois. Si l'absence est refusée (verrou, contradiction, résolution en cours), la réservation est annulée avec elle et le signalement reste ouvert. L'animateur apprend la décision par courriel, au mieux : « pris en compte » une fois l'absence écrite, « classé » sans toucher au planning. Le courriel nomme la journée et le poste, jamais le motif.

`GET` et `PUT /api/jour-j/signalements/configuration` désactivent le geste par édition (table `parametres_signalement`, activé par défaut). Éteint, il disparaît partout : l'espace ne le propose plus (`signalementsActifs`) et refuse un envoi, les signalements reçus ne s'affichent plus, et les annuler, classer ou traiter répond `400`. Ils restent en base, et rallumer les rend tels quels.
