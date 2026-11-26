# Limiter les abus : débit, verrous et quota de calcul

Chaque route ouverte à Internet porte un plafond posé par l'application : codes d'accès, déclarations, consultation des collègues, serveur MCP, plus un quota de calcul que vous réglez si la machine est partagée. Cette page dit ce que chacun compte, ce qui se règle et pourquoi chacun compte à sa manière. Les compteurs vivent en mémoire (une seule instance de l'application) ; derrière un reverse proxy, ceux qui comptent par adresse supposent que vous avez renseigné `CONNEXION_PROXYS_FIABLES`, ce que décrit [la connexion à l'administration](securite-acces-administration.md#verrouiller-le-formulaire-de-connexion-de-ladmin), où figure aussi le verrou du formulaire. Celui de l'affichage mural est dans [les jetons publics](securite-jetons-publics.md#débit-cache-et-journaux).

## Les demandes de code de l'espace animateur

`POST /api/espace-animateur/{jeton}/code` est le seul endpoint public qui
**déclenche un envoi de mail**. Sans plafond, quiconque tient le lien d'un
animateur noie sa boîte sous les codes, épuise le quota du serveur SMTP, et
remet à cinq le compteur d'essais du code à chaque demande.

Ce qui est compté, ce sont les codes **jamais utilisés** : ouvrir la session
avec le code reçu efface le compteur. Un animateur qui se connecte
normalement, même souvent, n'atteint donc jamais le plafond ; seule
l'accumulation de demandes sans suite, qui est exactement l'abus, y mène. Au
delà, la réponse est `429` avec un `Retry-After`.

| Variable | Défaut | Usage |
| --- | --- | --- |
| `ESPACE_CODE_MAX_DEMANDES` | `3` | Codes non utilisés tolérés par animateur et par fenêtre |
| `ESPACE_CODE_FENETRE` | `PT10M` | Durée de la fenêtre, calée sur la validité d'un code |

Le compteur vit en mémoire (application mono-instance), comme le verrouillage
de la révélation de clé MCP.

## Débit des déclarations de disponibilités

`POST /api/espace-animateur/{jeton}/disponibilites` est la
**première route qui écrit** depuis l'espace, et l'espace est ouvert sur
Internet avec une URL pour tout justificatif. Deux bornes, qui ne se remplacent
pas :

- le **volume** est borné par le métier : une seule proposition en attente par
  animateur, garantie par un index unique partiel (`V59`) : renvoyer mille fois
  laisse une ligne, et l'admin n'a jamais deux versions contradictoires de la
  même personne à arbitrer ;
- le **rythme** est borné ici. Sans plafond, une session ouverte écrit et
  notifie à la vitesse du réseau. Personne ne déclare ses disponibilités vingt
  fois en dix minutes ; celui qui se corrige n'atteint jamais le plafond.

| Variable | Défaut | Usage |
| --- | --- | --- |
| `ESPACE_DECLARATION_MAX_ENVOIS` | `20` | Déclarations tolérées par animateur et par fenêtre |
| `ESPACE_DECLARATION_FENETRE` | `PT10M` | Durée de la fenêtre |

La demande de covoiturage (`POST /api/espace-animateur/{jeton}/covoiturage`)
est la seconde écriture de la collecte : même plafond, même fenêtre de temps,
mais un **compteur à part** (se corriger d'un côté n'épuise pas l'autre). Son
volume est borné de la même façon, par une seule demande en attente par
animateur.

Le signalement d'un empêchement (« je ne pourrai pas être là »,
`POST /api/espace-animateur/{jeton}/signalements`) est ouvert **toute
l'édition**, collecte et foire fermées comprises, et chaque envoi écrit à
l'organisation : même plafond, même fenêtre, **un troisième compteur**, compté
avant toute validation pour qu'une boucle de signalements refusés s'arrête
aussi. Son volume est borné par un seul signalement ouvert par personne et par
objet (index unique partiel, `V111`). Il n'écrit rien au planning : l'annuler,
le constater ou le classer sont les seuls gestes qui le suivent.

Au-delà, `429` avec un `Retry-After`, comme pour les codes. Le compte est tenu
**par animateur** et non par adresse IP : la session nomme déjà l'animateur, et
une IP est ce qu'un téléphone change entre deux cellules. Les deux compteurs
partagent la même arithmétique (`SlidingWindowCounter`) pour qu'ils ne dérivent
pas l'un de l'autre.

Deux garde-fous de plus, côté contenu : un jour hors des dates de l'événement
et une typologie inconnue sont refusés, et la déclaration **n'écrit rien dans
le référentiel** : elle attend une décision explicite de l'admin. Un porteur de
jeton ne peut donc pas modifier les données d'entrée du solveur, seulement
proposer.

## Consultation des postes d'un collègue

`GET /api/espace-animateur/{jeton}/collegues/{id}/postes` alimente le choix
« son créneau que je veux en échange ». L'accès est **large par conception** :
l'espace remet déjà à chaque animateur la liste nominative de ses collègues, et
une réponse ne dit que des créneaux et des stands, ce que le planning global
imprimé fait circuler. Il n'est pas restreint aux « collègues pertinents » :
un échange dirigé peut viser n'importe qui, et un filtre viderait la liste au
moment où l'animateur en a besoin.

Ce qui est borné, c'est **l'énumération** : sans plafond, une session enchaîne
autant de requêtes qu'il y a d'animateurs et reconstitue toute la grille, mineurs
compris. Le plafond compte les **collègues différents**, pas les requêtes :
revoir le même, recharger la page ou hésiter entre trois ne coûte rien, et
quelqu'un qui cherche un échange n'en regarde jamais quarante en une heure.

| Variable | Défaut | Usage |
| --- | --- | --- |
| `ESPACE_COLLEGUES_MAX` | `40` | Collègues différents consultables par animateur et par fenêtre |
| `ESPACE_COLLEGUES_FENETRE` | `PT1H` | Durée de la fenêtre |

Au-delà, `429` + `Retry-After`, compté par animateur comme les déclarations. Un
id inexistant compte comme un vrai : sonder les ids coûte autant que les lire.
Le refus **ne bloque jamais la demande d'échange** : la liste reste vide avec le
délai affiché, et la demande part sans créneau souhaité en retour.

Deux gardes de plus, qui ne comptent rien mais ferment la route :

- **la foire doit être ouverte.** La règle est déclarée **sur la route**
  (`@FoireOpenRequired`), dans la forme des deux gardes voisines : on lit les
  trois exigences d'un coup d'œil au lieu de chercher un contrôle au fond d'une
  méthode. Le filtre tourne **après** l'authentification, donc un appelant
  anonyme reçoit son `401` sans apprendre si la foire est ouverte. Les
  écritures de la foire gardent leur contrôle dans le service, là où la règle
  s'applique réellement, sans dépendre d'une route
  annotée ; elles portent en outre leur autorisation dans le `WHERE` de leur
  requête, le motif le plus sûr ;
- **le collègue doit exister dans l'édition**, sinon `404` nu. Un `200 []`
  laisserait sonder les identifiants existants, et un corps d'erreur ne
  donnerait à lire qu'à celui qui sonde.

**Reste ouvert** : remplacer le trombinoscope entier par une recherche à la
frappe. C'est le seul resserrement qui s'attaquerait à l'**agrégat** lui-même :
une session pouvant, dans les limites du plafond, reconstituer une part du
planning nominatif de l'événement, mineurs compris.

## Débit du serveur MCP

`/mcp` est la seule surface authentifiée dont l'identifiant est une **clé
partagée à longue durée de vie**, dans un en-tête, sans session ni second
facteur. Rien ne bornait le rythme auquel on pouvait l'essayer, ni celui auquel
son porteur pouvait appeler, et les outils MCP lancent une résolution,
réécrivent les référentiels et lisent le planning de chacun.

`McpRateLimiter` compte **toutes** les requêtes sous `/mcp`, authentifiées ou
non, par adresse. Au-delà du plafond : `429` + `Retry-After`, **y compris avec
la bonne clé**. Ne compter que les échecs laisserait une clé volée appeler au
rythme du réseau, et une clé collée dans un assistant hébergé voyage plus loin
qu'un mot de passe.

| Variable | Défaut | Usage |
| --- | --- | --- |
| `PLANNING_MCP_MAX_REQUESTS` | `120` | Requêtes tolérées par adresse et par fenêtre ; `0` ou moins désactive le plafond |
| `PLANNING_MCP_RATE_WINDOW` | `PT1M` | Durée de la fenêtre |
| `PLANNING_MCP_TRUSTED_PROXIES` | *(la valeur de `CONNEXION_PROXYS_FIABLES`)* | Proxys inverses dont `X-Forwarded-For` est cru |

La fenêtre est **fixe, ouverte à la première requête** : passé le plafond, elle
se referme quand même à l'heure : une requête refusée ne la repousse pas. C'est
l'inverse des deux verrous (celui des clés refusées, plus bas, et celui de la connexion), dont la
fenêtre court depuis le dernier échec, et la différence tient à ce que chacun
garde : un verrou veut qu'une tentative de plus ne rapporte rien, un plafond de
débit veut qu'un client légitime qui l'a touché sache quand revenir.

### Le verrou sur les clés refusées

Le plafond ci-dessus **ne rend pas la clé difficile à deviner** : à 120 requêtes
par minute, une adresse dispose de plus de sept mille essais par heure. Ce qui
rend une clé impraticable à deviner, c'est son entropie ; ce second garde fait
qu'une **série d'essais coûte du temps** plutôt que rien.

`McpRateLimiter` compte donc aussi les **clés refusées**, séparément du débit :
au-delà de `PLANNING_MCP_MAX_FAILURES` échecs consécutifs, l'adresse reçoit
`429` pendant `PLANNING_MCP_LOCKOUT_DURATION`, **y compris avec la bonne clé**,
sans quoi il suffirait d'attendre son tour.

| Variable | Défaut | Usage |
| --- | --- | --- |
| `PLANNING_MCP_MAX_FAILURES` | `5` | Clés refusées consécutives tolérées par adresse ; `0` ou moins désactive le verrou |
| `PLANNING_MCP_LOCKOUT_DURATION` | `PT10M` | Durée du blocage, comptée depuis le **dernier** échec |

Deux garde-fous qui évitent qu'il se retourne contre un client légitime :

- **une requête sans clé ne compte pas.** Elle reçoit son `401` et rien d'autre :
  un client pas encore configuré sonde avant d'être réglé, et le verrouiller
  pour ça punirait le seul cas qui n'est pas une attaque ;
- **une requête qui s'authentifie efface la série.** Un en-tête corrigé à
  l'essai suivant ne coûte donc rien.

Un `403` (la session admin d'un navigateur, qui ne porte pas le rôle `mcp`) ne
compte ni n'efface : aucune clé n'y a été essayée.

> **Le succès se lit sur le statut, jamais sur `context.user()`.** Quarkus pose
> une identité sur le contexte de routage même pour une requête qu'il s'apprête
> à refuser, donc `user() != null` est vrai sur les `401` aussi. La première
> version de ce verrou s'en servait comme test de succès : elle effaçait la série
> à chaque tentative et ne comptait rien du tout.

L'adresse retenue est celle que décrit [le verrou de la connexion à l'administration](securite-acces-administration.md#verrouiller-le-formulaire-de-connexion-de-ladmin), au mot près, et
c'est le même code (`ClientAddress`) qui la calcule : lecture de
`X-Forwarded-For` **par la droite**, et seulement si la connexion elle-même vient
d'un proxy déclaré. `PLANNING_MCP_TRUSTED_PROXIES` reprend par défaut la valeur
de `CONNEXION_PROXYS_FIABLES` (les proxys sont un fait du déploiement, pas d'un
endpoint), et une valeur **vide** compte comme absente : un fichier compose qui
transmet la variable non renseignée la pose à la chaîne vide, que le repli
`${A:${B:}}` de la configuration ne tient pas pour manquante. Sans ce repli en
Java, tout appel à `/mcp` arrivant par le proxy tombait sur un seul compteur. Voir [`mcp-connexion.md`](../comprendre/mcp-connexion.md#borner-le-rythme) pour le réglage du
plafond selon l'usage.

> **Derrière un proxy, cette liste n'est pas facultative ici.** Le verrou de
> connexion ne compte que des échecs, et un déploiement qui n'a rien déclaré
> reste utilisable : il faut cinq mauvais mots de passe pour gêner quelqu'un.
> Ce plafond-ci compte **chaque requête**, alors sans la liste tous les appels
> arrivant par le proxy tombent sur un seul compteur, et un appelant anonyme y
> tient le client MCP légitime en `429` à deux requêtes par seconde, sans clé,
> puisque le filtre passe avant l'authentification. Renseignez
> `CONNEXION_PROXYS_FIABLES` en même temps que `PLANNING_MCP_API_KEY`.

## Quota de calcul

Une instance publique (`PUBLIC_INSTANCE=true`), par exemple exposée avec un
mot de passe partagé pour une démonstration, laisse quiconque lancer des calculs, et un calcul occupe la machine
des minutes durant. Si cette machine porte aussi une production, deux gardes
d'exploitant bornent ce qu'un visiteur peut lui coûter :
`SOLVER_MAX_SOLVES_PER_HOUR` (calculs acceptés sur 60 minutes glissantes) et
`SOLVER_MAX_QUEUED_JOBS` (tâches en file), avec `SOLVER_SECONDS_LIMIT_MAX` pour
la durée de chacun. Contrairement aux limiteurs précédents, ils comptent pour
**toute l'instance**, pas par adresse : c'est la machine qu'on protège, et un
visiteur qui change d'adresse ne repart pas à zéro. Le refus est un `409` qui
dit quand revenir, pas un `429`. Coupés par défaut, et ignorés sur une instance
qui n'est pas déclarée publique ; réglage et périmètre dans
[le réglage du calcul](calcul-du-planning.md#borner-le-nombre-de-calculs-dune-instance-publique), choix
dans [l'ADR 0075](repo:docs/decisions/0075-quota-de-calcul-de-l-instance-dans-l-application.md)
et [l'ADR 0110](repo:docs/decisions/0110-budget-de-calcul-d-instance-file-en-memoire-quota-public.md).
