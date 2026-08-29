# API REST

Tout est sous `/api`, en JSON. **La liste des endpoints, leurs schémas et leurs
codes de retour sont dans la spécification générée** : `/q/openapi`, ou
`/q/swagger-ui` pour l'explorer. Ce document ne la recopie pas : il porte ce
qu'elle ne peut pas exprimer — les invariants transverses, les arbitrages
métier, et les pièges qui coûtent une demi-journée.

Le serveur MCP (`/mcp`) a sa propre porte et sa propre clé : [`mcp.md`](mcp.md).

## L'édition, désignée à chaque requête

Tout le référentiel est cloisonné par édition (« Année 2025 », « Année 2026 »),
et **rien ne circule de l'une à l'autre** — voir
[`decisions/0001-cloisonnement-par-edition.md`](decisions/0001-cloisonnement-par-edition.md).

Le client la désigne par l'en-tête `X-Edition-Id`, sur **tous** les endpoints
sauf le dump SQL (`/api/database/*`), qui sauvegarde l'instance entière.

Deux propriétés qui expliquent la plupart des surprises :

- un en-tête absent ou nommant une édition inconnue **retombe silencieusement**
  sur l'édition `defaut`, jamais une erreur — un onglet resté ouvert sur une
  édition supprimée continue de fonctionner. `GET /api/editions/courant` est la
  façon de découvrir que son en-tête a été ignoré ;
- ce n'est pas un état global : deux onglets travaillent sur deux éditions
  différentes en même temps.

Le verrou « un solveur à la fois » reste, lui, **global à l'instance**.

## Authentification

Toute l'API est réservée à la session admin, avec quatre exceptions
volontaires : l'espace animateur (le jeton d'URL est la clé), les routes de
session, `/api/config` et `/api/branding` — lus par le frontend avant son
démarrage, page de connexion comprise — et `/api/mentions-legales`. Un appel
non authentifié répond **401, jamais une redirection HTML**.

Le form login `/j_security_check` répond `429` après cinq échecs depuis la même
adresse, **y compris avec le bon mot de passe** ([`securite.md`](securite.md)).

`POST /api/mcp/cle` échange le mot de passe admin contre la clé MCP : la
session ne suffit pas, parce que cette clé donne un accès complet en écriture et
**survit à la session** d'où elle a été copiée. La révélation doit être liée à
quelqu'un présent au clavier.

`GET /api/mcp/prompts` sert les prompts que le serveur MCP annonce — nom,
description, texte — pour que la page MCP les propose aux clients qui ne savent
pas les récupérer eux-mêmes. Rien de secret ici : c'est la session admin qui
protège la route, comme le reste de l'API. Elle existe pour que la page **ne
recopie pas** ces textes ; voir [`mcp.md`](mcp.md).

### Derrière un reverse proxy qui termine le TLS

Quarkus fabrique la redirection de connexion en **absolu**, depuis le schéma de
la requête reçue. Derrière un proxy qui termine le TLS, cette requête arrive en
clair : la redirection repartait en `http://`, que le navigateur refuse de
suivre depuis une page `https` — connexion impossible.

`PROXY_ADDRESS_FORWARDING` (**activé par défaut**) fait suivre les
`X-Forwarded-*` : schéma, hôte et chiffrement correspondent alors à ce que le
visiteur a demandé, et le cookie de session est marqué `Secure`. Sans proxy en
amont, rien ne change.

Deux compléments : `QUARKUS_HTTP_PROXY_TRUSTED_PROXIES` — **à renseigner si
l'origine est joignable sans passer par le proxy**, sinon n'importe quel client
annonce le schéma et l'adresse de son choix — et
`QUARKUS_HTTP_PROXY_ENABLE_FORWARDED_HOST` si le proxy réécrit `Host`.

### Mode « remote user » (facultatif, désactivé par défaut)

Pour un proxy d'accès qui authentifie lui-même ses visiteurs et transmet
l'adresse retenue. Le form login reste disponible en parallèle : une origine
atteinte directement, ou un proxy mal configuré, laisse toujours `/login`
utilisable. Variables `REMOTE_USER_*`.

**Le secret partagé n'est pas une option.** Un en-tête est une affirmation, pas
une preuve : sans lui, quiconque atteint l'origine sans passer par le proxy
devient administrateur en envoyant une ligne d'en-tête — et une origine
joignable en direct est l'état ordinaire (un port publié pour déboguer, une
seconde ingress, un réseau interne). Activer le mode sans secret **fait échouer
le démarrage**. Le secret doit voyager sur un en-tête que le proxy **écrase
inconditionnellement** en entrée.

Qui est qui : `REMOTE_USER_ADMIN_EMAIL` obtient le rôle admin ; **toute autre
adresse est un animateur**, et l'attestation du proxy remplace alors le code à
6 chiffres — c'est exactement le fait que ce code prouve, établi une étape plus
tôt. Le jeton reste nécessaire et doit désigner la fiche portant cette adresse :
l'attestation d'un animateur n'ouvre pas l'espace d'un collègue dont il aurait
ramassé le lien. Si l'adresse admin est aussi celle d'un animateur, l'admin
l'emporte et la personne perd son espace — collision signalée au démarrage.

## Résolution

Les quatre façons de lancer un solve — synchrone, asynchrone depuis un problème
envoyé, asynchrone depuis le référentiel, incrémentale — passent toutes par
`SolvePipeline` et font donc **toujours** la même chose : capturer le plan sur
le point d'être écrasé, construire le problème, résoudre, persister,
diagnostiquer, alimenter l'écran Contraintes, écrire la ligne de KPI, annoncer
la fin si l'édition l'a demandé.

Un chemin qui s'arrêterait avant la fin laisserait l'écran Contraintes sur
l'analyse du solve précédent, sans lever d'erreur : le seul symptôme serait un
écran qui ment. `SolveSynchronePipelineTest` verrouille les deux bouts.

**Un seul job de résolution à la fois pour toute l'application**, verrou porté
par le serveur. Toute autre session voit le même job actif via
`/api/jobs/active` et se voit refuser un second lancement en `409`, le corps
portant le job en cours.

### File d'attente

`enFile=true` met la tâche en file au lieu de la refuser. Trois propriétés :

- **le problème est construit au démarrage effectif**, pas au clic : on peut
  continuer à préparer l'édition visée entre-temps ;
- **une tâche en file ne tient pas le solveur** : la saisie reste ouverte sur
  l'édition visée, le verrou de saisie étant par édition ;
- **un doublon est refusé en `409`** — même édition, même type, *déjà
  planifié*. Planifier une résolution de l'édition **en cours de calcul** reste
  autorisé : le calcul en cours a démarré avant les dernières corrections, en
  planifier un autre est la façon de dire « refais-le avec ce que je viens de
  corriger ».

FIFO. Statuts : `QUEUED` → `PENDING` → `RUNNING` → `COMPLETED` / `FAILED` /
`CANCELLED` / `INTERROMPU`.

### La file survit au redémarrage

Ce qui est persisté (`solver_job`) est l'**intention** — type, budget, édition,
périmètre —, jamais l'état du solveur Timefold. C'est suffisant précisément
parce qu'une tâche en file construit déjà son problème au démarrage.

Deux conséquences :

- **le calcul en cours est perdu** : le job revient en `INTERROMPU`, un état
  terminal, pour ne pas garder le verrou global indéfiniment ;
- **le résultat d'un job n'est pas stocké** : un job restauré expose
  `result: null`. Ce qu'il décrivait est déjà en base.

Désactivable par `planning.jobs.reprise-au-demarrage=false`.

### Suivi poussé, et pourquoi le polling reste

`GET /api/jobs/stream` pousse en *server-sent events* l'agrégat
`{ active, file }`. Le premier événement **arrive à la connexion**, sans
attendre une transition : un solveur au repos n'en produirait aucune, et un
client resterait sur « état inconnu ». Chaque événement est un instantané
entier, jamais un delta.

Un `heartbeat` toutes les 20 s porte aussi la ligne `:keep-alive`, sans
laquelle un proxy inverse ferme une connexion silencieuse. Le commentaire seul
n'aurait pas suffi : `EventSource` ne l'expose jamais au JavaScript, il ne peut
donc pas servir de preuve de vie.

**Le flux ne remplace pas le polling, il le double** : SSE échoue en silence
(proxy qui bufferise, coupure non reconnectée) et l'IHM resterait figée sur un
état périmé sans rien signaler. Le navigateur garde un poll lent (30 s) et
reprend la main après ~45 s de silence.

### Replanification incrémentale

Repart du plan persisté, épingle ce qu'un changement tardif n'a pas invalidé,
ne recalcule que le reste. Le corps est facultatif et **élargit** le périmètre :
les trois axes (`animateurIds`, `jours`, `standIds`) sont une **union**. Sans
corps, le périmètre est ce que les changements ont invalidé plus les postes
vides.

Budget par défaut court (60 s), pas celui d'une résolution complète. Le job
échoue explicitement s'il n'y a aucun plan à reprendre : la replanification part
d'un résultat, pas de rien. Mécanique dans
[`domaine.md`](domaine.md#replanification-incrémentale).

### Instantanés et comparateur A/B

Un seul plan est persisté à la fois par édition. Les instantanés sont la seule
persistance capable d'en garder plusieurs.

**Un instantané est pris automatiquement avant chaque solve** — c'est le vrai
filet anti-écrasement, celui qui protège l'utilisateur qui n'a pas pensé à
enregistrer. Les automatiques sont purgés au-delà des N derniers (5 par
défaut) ; ceux créés à la main, jamais.

**Le résultat d'un solve nomme cet instantané et le score qu'il porte**
(`previousPlan : { snapshotId, score, degraded }`). Un solve n'annonçait que
son propre score : relancer une résolution sur une édition qui avait déjà un
bon plan pouvait le dégrader sans que rien ne le dise, le score dur restant à
zéro. `degraded` compare les deux scores dans l'ordre de Timefold — dur, puis
medium, puis souple — et vaut `false` dès qu'un des deux scores manque ou est
illisible : mieux vaut ne rien affirmer qu'une fausse alerte. `previousPlan`
est `null` au premier solve d'une édition, et absent des résultats produits
avant cette version.

Le score d'un instantané est copié à la capture depuis la dernière analyse, qui
vit en mémoire : après un redémarrage, elle est vide. Le solve suivant
rétablit alors ce score en analysant le plan persisté avant de le remplacer, et
l'écrit dans l'instantané — une analyse par édition et par redémarrage, devant
un calcul qui va durer des minutes.

Une restauration répond `409` **sans rien écrire** si des références ont
disparu, en listant lesquelles.

**Un instantané peut porter l'état « publié »** (`publieLe`). C'est le plan que
les animateurs ont reçu, et celui que leur espace affiche — voir *Publication*
ci-dessous. Un instantané publié sort de la purge de rétention et refuse d'être
supprimé (`409`) : ce serait reprendre sans un mot ce qui a été annoncé.

La comparaison A/B **ne déclenche aucune résolution** : elle lit des KPI
mesurés à la capture, jamais un score recalculé. `editionsDifferentes` n'est
**pas** une anomalie — depuis la suppression des groupes de créneaux, une
variante *est* une autre édition — mais l'IHM doit le dire. Dans `diffViolations`,
`null` signifie **non mesuré**, jamais zéro. `kpiRecalcule` marque le mode
dégradé d'un instantané qui ne porte pas de KPI stockés : couverture et
volumétrie exactes, violations non mesurées.

## Publication

`GET /api/planning/publication` — qui serait prévenu, et ce qu'il lirait.
N'envoie rien. `POST` publie.

Publier, c'est **marquer le plan de travail comme le plan communiqué** et
n'écrire qu'aux personnes dont l'emploi du temps a changé depuis la dernière
publication. Le décompte est **par personne** : renommer un stand ne réveille
personne, échanger deux vacations réveille exactement deux personnes. Chaque
destinataire arrive avec les phrases exactes de son courriel (`changements`),
que l'administrateur relit **avant** tout envoi.

L'aperçu se lit **à la demande** : rien n'est poussé, rien n'est scruté. Le
seul garde-fou est de concurrence, pas de temps — publier pendant un solve
figerait un plan sur le point d'être réécrit, donc `409`. Idem quand personne
n'est concerné (`409`) : ce n'est pas une erreur à contourner, c'est le but.

`premiereDiffusion` distingue quelqu'un à qui rien n'a jamais été envoyé : son
planning entier est la nouvelle, pas une liste de corrections. Tant que
`jamaisPublie` vaut `true`, **l'espace animateur est vide** — l'application ne
peut pas prétendre avoir communiqué un plan qu'elle n'a pas envoyé.

Un envoi qui échoue ne revient **pas** dans le décompte suivant : le compteur
mesure ce qui a changé, pas ce qui a été délivré. Le rapport nomme les manqués,
et `POST /api/planning/envoi/animateur/{id}` est le rattrapage — il renvoie le
plan **publié**, refusé (`400`) tant que rien ne l'a été.

`GET /api/planning/publication/destinataires` rend la trace : qui a été prévenu
de quoi, et quand, y compris ceux qu'on n'a pas pu joindre (`SANS_EMAIL`,
`ECHEC`). Sans paramètre, celle de la dernière publication ; liste vide quand
il n'y en a jamais eu.

**Une décision d'échange n'est plus annoncée au moment où elle est prise**,
mais par la publication qui la porte : accepter un échange change le plan de
travail, pas le plan publié, et prévenir tout de suite promettrait un planning
que l'espace ne montre pas encore.

## Contraintes

Chaque entrée du catalogue porte `poids` (la valeur du déploiement, écrasée par
ce que l'édition a enregistré), `protegee` (règle d'ordre public ou de sécurité
des mineurs : l'IHM confirme avant désactivation) et `dosable` (les MEDIUM de
« Qualité d'organisation », les seules dont l'importance relative varie
réellement d'un organisateur à l'autre).

Le poids va de **1 à 100** ; `0` est refusé. Une règle pesée zéro serait éteinte
*en fait* tout en s'affichant active — et, pour une règle légale, sans passer
par la confirmation. Éteindre passe par l'interrupteur. `poids: null` supprime
la surcharge et rend la règle à la valeur du déploiement.

Une désactivation insère une ligne dans `constraint_toggle`, une réactivation la
supprime : c'est tout ce que la table porte.

`GET /api/constraints` porte aussi `contraintesAdHocEnCause` : les exceptions
saisies à la main que la dernière analyse a trouvées encore violées, la plus
violée d'abord, avec leur id, leur raison et le nombre de violations qu'elles
portent. C'est ce qui répond à « *lesquelles* de mes exceptions » quand une
règle ad hoc affiche douze correspondances. Liste vide quand le plan les honore
toutes — et quand rien n'a jamais été analysé.

### Rafraîchir l'analyse ne relance pas de solveur

`POST /api/constraints/diagnostic` recalcule le score, règle par règle, du
**plan persisté**, et renvoie la même vue que `GET /api/constraints`. Un seul
calcul de score, aucun solveur tenu : le bouton « Actualiser » de l'écran
Contraintes reste donc disponible pendant qu'une résolution tourne.

Il remplace un lancement d'analyse en tâche de fond — une résolution complète
dont le résultat n'était jamais persisté. L'écran affichait alors le score d'un
planning que rien d'autre ne montrait, au prix du budget d'un solve. Les trois
routes `/api/solve/analyze`, `/api/solve/analyze/async` et
`/api/solve/analyze/async/reference-data` ont disparu avec lui, ainsi que le
type de job `ANALYZE` (`V55` purge les lignes restantes de `solver_job`, que
`JobType.valueOf` ne saurait plus relire).

Sans rien de persisté, la réponse est la vue vide (`analysedAt` nul) : c'est
l'état que l'écran affiche déjà avant la première résolution.

### Une exception contradictoire est refusée

`POST /api/contraintes-ad-hoc` répond **400** quand la contrainte envoyée ne
peut pas être satisfaite en même temps qu'une exception déjà enregistrée, avec
un message qui les nomme toutes les deux. Le même contrôle s'applique à l'import
de scénario, qui écrit tout le jeu d'un coup. Ce qui est refusé, ce qui passe
délibérément, et pourquoi un plafond numérique a été écarté :
[`contraintes.md`](contraintes.md#contraintes-ad-hoc--les-contradictions-refusées-à-la-saisie).

Réenregistrer une contrainte **sous son propre id** n'est jamais refusé : la
version envoyée remplace la précédente au lieu de coexister avec elle. C'est
d'ailleurs la seule façon de modifier une exception — l'API n'expose que `POST`
et `DELETE`.

## Explicabilité

« Pourquoi lui ? » cible un seul poste du planning envoyé, **déjà résolu et
jamais re-résolu**.

« Respectée » signifie seulement qu'aucune violation n'a été trouvée pour ce
poste précis, **pas que la contrainte s'applique à lui** : l'IHM ne doit pas la
présenter comme un satisfecit. La simulation de remplacement ne persiste rien.

`POST /api/postes/{id}/simulation-swap` note un candidat qu'on lui désigne.
Aucun écran ne l'appelle : l'IHM n'expose que les remplaçants viables de
l'assistant ci-dessous, pour ne pas laisser choisir un remplacement qui casse
une règle dure. L'endpoint reste ouvert à l'API et à l'outil MCP
`simuler_swap`.

### Assistant de réparation

| Endpoint | Effet |
| --- | --- |
| `POST /api/postes/{id}/suggestions-reparation?plafond=N` | Cherche les remplaçants viables. Ne persiste rien. |
| `POST /api/postes/{id}/affectation?animateurId=X` | Applique un remplacement au plan enregistré. Écrit. |

Là où `simulation-swap` note le candidat qu'on lui donne, l'assistant
**énumère** les candidats lui-même : il ne garde que ceux que le filtre
d'éligibilité du solveur accepte, simule chacun d'eux, écarte ceux qui
dégraderaient le score dur **du plan entier** — et non du seul poste, car
déplacer un siège peut casser une règle ailleurs (heures hebdomadaires, repos)
— puis classe le reste par impact décroissant.

**Le coût est borné et annoncé.** Chaque candidat coûte une analyse complète du
planning, donc seuls les `plafond` premiers candidats éligibles sont simulés
(20 par défaut, 100 au maximum ; une valeur nulle ou négative retombe sur le
défaut). La réponse porte `candidatsEligibles` et `candidatsEvalues` : quand les
deux diffèrent, la liste est la meilleure de ce qui a été vu, **pas une réponse
exhaustive**, et l'IHM doit le dire. Les candidats libres au moment du poste
sont évalués en premier, pour que la troncature garde les plus prometteurs.

Appliquer une suggestion réaffecte **ce seul siège** dans `poste_affectation`,
sans relancer de solveur ni réécrire le reste du plan : le résultat est
exactement le plan simulé. Un poste couvert par un verrouillage est refusé
(400) — déverrouillez-le d'abord.

C'est un `UPDATE` nu : hors verrouillage, il ne revérifie rien. Une liste de
suggestions calculée **avant** une autre écriture est donc périmée, et l'appliquer
peut créer un chevauchement que personne ne signale. Une IHM qui garde plusieurs
listes à l'écran doit les invalider après chaque application — c'est ce que fait
le mode jour J.

**Un candidat qui casserait une règle dure sur ce siège n'est jamais proposé**,
même quand le score dur global ne bouge pas. Le cas n'est pas théorique : sur un
siège vide, pourvoir la place règle un point dur (`posteDoitEtrePourvu`) et peut
le redépenser aussitôt sur un autre — l'animateur qu'on vient de déclarer
indisponible se retrouvait alors en tête des suggestions, à delta nul.
`violationsIntroduites` ne porte donc jamais de `HARD`, comme sa documentation
l'annonçait déjà.

## Mode « jour J »

L'écran du jour même (`/jour-j`) : quelqu'un ne s'est pas présenté, et ses
sièges doivent changer de mains **maintenant**. Rien de neuf sous le capot — ce
sont les briques ci-dessus, appelées dans l'ordre du geste.

| Endpoint | Effet |
| --- | --- |
| `GET /api/jour-j?date=&maintenant=` | L'écran complet : créneaux restants, animateurs de service, places vides, absences du jour. Ne lit que. |
| `POST /api/jour-j/absences?date=&maintenant=` | `{ "animateurId": "A1", "raison": "…" }` — une `INDISPONIBILITE_FORCEE` par créneau restant, et les sièges tenus sur ces créneaux vidés. Écrit. |
| `DELETE /api/jour-j/absences/{animateurId}?date=&creneauId=` | Annule l'absence : un créneau si `creneauId` est donné, toute la journée sinon. |
| `POST /api/jour-j/postes/{posteId}/suggestions?plafond=N` | L'assistant de réparation ci-dessus, sur le **plan enregistré** au lieu d'un plan envoyé dans le corps. |

### Une journée commence à son premier créneau

**Ni minuit, ni une heure d'exploitation fixe : la borne est le premier créneau
réellement programmé.** Une journée de festival commence quand le premier stand
ouvre. La journée en cours à un instant donné est donc la plus récente dont le
premier créneau a déjà commencé et dont le dernier n'est pas terminé.

Ce qui en découle :

- **un créneau appartient à la journée que son début ouvre**, et à elle seule.
  Un créneau 22h→2h est du soir qui l'ouvre ; il ne se dédouble pas sur deux
  journées ;
- **à 1h du matin, la journée en cours est encore celle de la veille** tant que
  ce créneau tourne. `GET /api/jour-j` sans paramètre répond donc `date` =
  la veille ;
- une fois le dernier créneau terminé — ou avant que le premier n'ouvre —
  **aucune journée n'est en cours**, et la date du calendrier répond ;
- **une date sans aucun créneau n'a pas de journée** : l'écran répond
  `creneauxDuJour: 0` et des listes vides plutôt que d'échouer, et
  `POST /api/jour-j/absences` y est refusé (**400**, « Aucun créneau n'est
  programmé le … »).

`date` et `maintenant` valent par défaut la journée en cours et l'horloge du
serveur. Les deux se surchargent, pour une répétition ou un test : `date` nomme
la journée, `maintenant` nomme l'instant — et nomme aussi la journée quand
`date` est absent. Lire une journée qui n'est pas celle en cours la lit en
entier. `maintenant` est un **instant complet** (`AAAA-MM-JJTHH:MM`) et non une
heure, précisément parce qu'une journée qui déborde de minuit met les deux sur
des dates différentes : à `2026-07-09T01:00`, la journée regardée est
`2026-07-08`. La réponse renvoie `maintenant` pour que l'écran énonce l'heure du
serveur plutôt que celle du téléphone.

**La portée d'une absence, ce sont les créneaux restants, et eux seuls.** Un
créneau est restant quand sa **fin** est postérieure à l'heure de référence —
celui qui est en cours en fait donc partie, c'est précisément celui où personne
n'est au poste. Les créneaux déjà terminés ne sont jamais touchés : la personne
les a réellement tenus, les transformer en indisponibilités ferait mentir le
planning sur ce qui s'est passé.

Ce qui porte un créneau au-delà de minuit n'est donc pas son appartenance mais
sa **fin** : un créneau 22h→2h reste « devant » jusqu'à 2h du matin, et c'est la
règle de journée ci-dessus qui fait que la journée regardée est encore celle qui
l'a ouvert. La normalisation de minuit est la même que `Creneau.chevaucheNuit`.

Un animateur n'est marqué « déjà absent » que si l'une de ses indisponibilités
couvre un créneau **encore devant**. Une indisponibilité du matin seul, saisie
des semaines plus tôt, n'empêche donc pas de le marquer absent pour la suite de
la journée.

**Tout ou rien.** Une absence, c'est plusieurs exceptions et plusieurs sièges ;
un refus sur le troisième ne doit pas laisser les deux premiers écrits. Les
contradictions et les verrouillages sont donc vérifiés sur toute la portée avant
la moindre écriture. Deux refus possibles, tous deux en **400** :

- une `AFFECTATION_FORCEE` sur l'un des créneaux visés — c'est la règle 2 de
  [`ContrainteAdHocContradictions`](contraintes.md#contraintes-ad-hoc--les-contradictions-refusées-à-la-saisie),
  et le message **nomme les deux** exceptions ;
- un siège à libérer couvert par un verrouillage : le message nomme le stand et
  l'heure, et invite à lever le verrou d'abord.

Chaque exception écrite porte sa trace — `raison`, `creeParUtilisateurId` (le
compte admin de la session, `jour-j` à défaut) et `creeLe` — et se retrouve
telle quelle sur la page « Ajustements manuels » le lendemain.

Annuler une absence **ne rend pas les sièges** : qui tient un siège est une
décision, et supposer que l'occupant précédent doit le récupérer effacerait en
silence le remplacement appliqué entre-temps.

L'annulation ne supprime que **ce que cet écran a écrit**, reconnu à l'id qu'il
dérive du couple (animateur, créneau) — et seulement les exceptions ne visant
**que** cet animateur. Supprimer toute indisponibilité mono-cible tombant ce
jour-là aurait emporté, avec l'absence saisie par erreur le matin même, une
indisponibilité de longue date enregistrée depuis Ajustements manuels : ce que
la prochaine résolution a le droit de faire s'en serait trouvé élargi sans que
personne le voie.

Rien ici ne déclenche de résolution, et rien n'envoie de courriel : l'écran
affiche le compte de la [publication](#publication) et renvoie vers le bouton,
il ne le duplique pas.
### Banc de touche

| Endpoint | Effet |
| --- | --- |
| `GET /api/banc-de-touche/{creneauId}?standId=X&posteId=P` | Qui n'est pas de service sur ce créneau, et pourquoi il ne pourrait pas l'être. Lecture seule. |

Répond sur le **dernier plan enregistré**, sans relancer de solveur. Affecter
quelqu'un depuis cet écran est hors périmètre : cela passe par l'assistant de
réparation ci-dessus.

**Ne pas rien avoir à dire n'est pas une erreur.** Le sélecteur de l'écran est
alimenté par le référentiel, qui porte légitimement **plus** de créneaux que le
plan : un créneau sur lequel aucun stand n'est ouvert, ou qu'un découpage a créé
après la dernière résolution, ne porte aucun siège. Renvoyer `404` dans ce cas
ouvrait l'écran sur « Créneau inconnu » pour un créneau bien réel, sans rien à
faire pour en sortir. Le champ `statut` distingue donc les trois réponses :

| `statut` | Sens | Ce que l'écran dit |
| --- | --- | --- |
| `NO_PLAN` | Rien n'est enregistré | « Lancez une résolution » |
| `NO_SEAT` | Le plan existe, mais aucun siège sur ce créneau (ou sur le stand demandé) | « Choisissez un créneau porteur de sièges » |
| `EVALUATED` | Un siège a été sondé | La liste, et ses motifs |

Hors `EVALUATED`, `posteCibleId`, `standCibleId` et `animateurCibleId` sont nuls
et les listes vides. `creneauxAvecSieges` énumère, dans tous les cas, les
créneaux sur lesquels le plan enregistré porte au moins un siège : c'est ce qui
permet à l'écran de désigner un créneau utile au lieu de laisser essayer un par
un. Un `404` reste réservé à un créneau qui n'existe **nulle part** dans
l'édition — la seule requête qui soit vraiment fausse.

Les raisons ne sont **pas réécrites ici** : chacune porte le `contrainte` d'une
règle réellement appliquée par le solveur, avec le `niveau`, la `categorie` et
la `description` que `GET /api/constraints` en donne. Elles viennent de deux
sources, et d'aucune troisième :

- ce que le couple (poste, animateur) décide à lui seul — indisponibilité, stand
  réservé aux majeurs, jour férié, nuit, plafonds mineurs d'un créneau isolé —
  est lu dans le **filtre d'éligibilité du solveur lui-même**, celui que
  l'assistant de réparation applique déjà ;
- tout ce qui dépend du reste du plan — plafonds quotidiens et hebdomadaires,
  repos, pauses, chevauchements, encadrement d'un mineur, appréciation
  manquante — est obtenu en **posant le siège à chaque candidat et en demandant
  aux contraintes ce qui a empiré**. Aucun seuil n'est recopié.

**Empiré par rapport à quoi : au siège vide, jamais à son occupant.** La nuance
décide de la justesse de l'écran. Comparé à l'occupant en place, un candidat qui
casse précisément la règle que l'occupant casse déjà laisse le total de cette
contrainte inchangé, et l'écran répondait « rien contre lui » — c'est ainsi
qu'un animateur déjà de service ailleurs à cette heure-là ressortait
« Disponible ». Le siège est donc vidé avant de sonder, ce qui supprime la
classe entière : ce que le candidat tendrait est comparé à personne ne tendant
rien.

Le siège évalué est nommé par `posteCibleId` : celui que `posteId` désigne,
sinon le premier siège libre du créneau (restreint à `standId` s'il est fourni),
sinon son premier siège — auquel cas la question posée devient « qui pourrait le
remplacer ? », et `animateurCibleId` dit qui l'occupe.

Chaque ligne porte **deux verdicts, qui ne disent pas la même chose** :

| Champ | Sens |
| --- | --- |
| `disponible` | Aucune règle dure ne s'oppose à cette affectation, siège vide pour référence. C'est ce que l'écran affiche. |
| `degradeLePlan` | Le score dur du plan serait réellement plus mauvais qu'aujourd'hui. |

`disponible` implique `!degradeLePlan`, jamais l'inverse : reprendre le siège de
quelqu'un qui casse déjà une règle peut en casser une autre sans que le plan
empire globalement. `delta` chiffre l'écart par rapport au plan **actuel** — ce
qui suppose de sonder aussi l'occupant du siège, et c'est exactement ce que fait
le service.

**Ce que la vue garantit face à l'assistant de réparation**, et rien de plus :
tout animateur affiché `disponible` est un candidat que `suggererReparations`
propose sur le même siège (`CreneauAvailabilityCoherenceTest`). La réciproque
est fausse **volontairement** : l'assistant applique deux filtres, dont le
second lit les violations *du siège lui-même* match par match ; le reproduire
ici reviendrait à réimplémenter cette analyse, c'est-à-dire la duplication que
cet écran existe pour éviter. La vue applique à la place son propre instrument,
plus strict — aucune contrainte dure aggravée, siège vide pour référence — et
c'est l'implication qui est vérifiée, pas une égalité.

Tous les motifs applicables sont listés, pas seulement le plus bloquant : savoir
que lever l'indisponibilité en laisserait trois autres derrière est précisément
ce qui permet de décider s'il vaut la peine de négocier.

**« Compétence absente » n'est pas une exclusion.** L'appréciation est une
contrainte *medium* (`appreciationIncompatible`) depuis qu'elle a cessé d'être
une qualification bloquante : elle apparaît donc comme motif, au niveau
`MEDIUM`, sur une ligne qui peut rester `disponible`. Un **renfort**
(typologie ninja) n'en porte jamais : `Animateur.hasCompetenceFor` le considère
compétent partout, et cette vue lit ce verdict au lieu d'en produire un second.
Elle ne mesure aucune rareté de compétence — c'est le sujet du goulot par
compétence, où un renfort ne compte jamais comme **spécialiste**.

### Figer la date du jour — développement uniquement

Cet écran ne se teste, sinon, que le jour de l'événement. `/api/debug/date-du-jour`
remplace donc la date que le serveur considère comme « aujourd'hui ».

| Endpoint | Effet |
| --- | --- |
| `GET /api/debug/date-du-jour` | `{ "dateDuJour": "2026-07-08"\|null, "modifiable": true\|false }`. |
| `PUT /api/debug/date-du-jour` | `{ "dateDuJour": "2026-07-08" }` fige la date ; une valeur vide ou `null` rend la main à l'horloge de la machine. |

**Refusé (400) sur toute instance qui n'a pas été lancée avec `quarkus:dev`.**
`/debug` est une route d'administration ordinaire, disponible en production : un
mock activable là-bas ferait mentir l'écran jour J sur un vrai événement. Le
garde-fou est donc **sur l'écriture, côté serveur**, pas sur l'affichage du
champ — `modifiable` n'existe que pour que l'IHM masque un contrôle inutilisable,
et l'endpoint refuse quoi que croie l'appelant.

Le garde-fou porte aussi sur **la lecture**, pas seulement sur l'écriture : la
valeur vit dans une ligne ordinaire, qu'un dump rejoué par
`POST /api/database/import` emporte avec lui. Une date figée sur un poste de
développement puis restaurée sur une instance déployée y resterait, et le chemin
de retour est fermé puisque l'effacement y est refusé aussi. Hors `quarkus:dev`,
la valeur est donc **ignorée** — la ligne est laissée telle quelle, mais
l'horloge lue est celle de la machine.

**Ce que le mock remplace, exactement : la date, et seulement pour l'écran
jour J** — quel jour est regardé, quels créneaux de ce jour sont encore devant,
et lesquels une absence couvre. **L'heure de la journée n'est jamais figée** :
la figer rendrait l'écran statique, alors que ce qu'on veut vérifier est
justement que les créneaux passent derrière au fil de l'après-midi.

Délibérément hors de portée, et rien de tout cela ne passe par ce mock :

- le **statut mineur/majeur**, dérivé de la date de naissance contre la date du
  *créneau* et non contre aujourd'hui — un mock qui l'atteindrait pourrait faire
  travailler un mineur de nuit ;
- **l'horodatage de ce qui est écrit** (`creeLe` d'un ajustement, dates de
  publication et d'instantané) : ils enregistrent quand une chose s'est
  réellement produite ;
- la sauvegarde de nuit, les travaux du solveur, les codes de l'espace animateur
  et toute autre échéance : ils répondent à l'horloge de la machine.

Le réglage est persisté en base (table `horloge_jour_j`, non cloisonnée par
édition : c'est l'horloge du serveur, pas une propriété d'un événement) plutôt
que gardé en mémoire, pour la même raison que le budget de résolution manuel —
un réglage qui vit dans un processus disparaît au premier rechargement à chaud.
Quand il est actif, la barre d'outils porte une icône d'avertissement sur tous
les écrans, dont le lien mène directement au champ.

## Faisabilité et besoin en animateurs

Deux calculs de capacité **sans lancer le solveur**, donc affichables avant une
résolution de plusieurs minutes.

Les deux sont **délibérément optimistes** et se lisent comme un plancher, jamais
comme une cible :

- la faisabilité ignore quel stand précis chaque animateur pourrait tenir :
  **`feasible: true` ne garantit pas** un score dur nul après résolution ;
- le besoin minimum ignore les indisponibilités individuelles, et ne regarde
  les compétences que dans son volet par typologie, décrit plus bas.

La compétence n'entre pas dans le calcul **de faisabilité** : depuis sa bascule
en contrainte medium, n'importe quel animateur disponible peut tenir n'importe
quel stand. Un écart d'appréciation est signalé *après* résolution, jamais comme
cause bloquante avant. Le besoin minimum, lui, la regarde — mais seulement dans
son volet par typologie décrit plus bas, jamais dans ses trois bornes globales.

Une cause n'est pas toujours un manque d'animateurs : `GET /api/feasibility`
remonte aussi les **contraintes ad hoc contradictoires**
(`CONTRAINTES_AD_HOC_CONTRADICTOIRES`, `contrainteIds` nommant les exceptions
concernées). Elles sont refusées à la saisie, donc ce que cette cause désigne a
été enregistré avant ce contrôle, ou importé en un bloc — rien d'autre ne le
signalerait. Toujours `CRITIQUE`, et classée avant les sous-effectifs : elle se
corrige en supprimant une ligne que l'utilisateur a saisie lui-même.

Trois bornes pour le besoin minimum, la plus grande étant retenue : pic
simultané, **pic avec pause** (le nombre exact d'animateurs distincts qu'exige
la journée la plus chargée), et charge totale rapportée au plafond hebdomadaire.

Les mêmes trois bornes sont déclinées **par typologie** — `parCompetence` dans
la même réponse, le *goulot par compétence* : « il ne manque pas 4 personnes, il
manque 4 personnes compétentes en escape game ». Deux règles d'attribution la
rendent lisible :

- un siège n'est compté pour une typologie que si son stand **ne propose
  qu'elle** ; les sièges d'un stand qui en propose plusieurs peuvent être tenus
  par l'un ou l'autre vivier, donc aucune typologie ne les revendique et ils
  sont reportés à part (`siegesNonAttribues`). Un stand n'en proposant
  **aucune** est le cas inverse, pas le même : `hasCompetenceFor` ne répond
  alors oui qu'aux polyvalents, donc ses sièges sont la demande la plus serrée
  qui soit. Ils rejoignent la ligne de la typologie ninja — exactement la même
  population requise — et sont comptés dans `siegesReservesAuxPolyvalents` ;
  sans typologie ninja au référentiel, personne ne peut les tenir du tout ;
- un **polyvalent** (celui qui possède la typologie marquée `ninja`) compte
  comme **spécialiste** dans les seules compétences qu'il déclare, jamais dans
  toutes : le compter partout ajouterait la même personne à chaque vivier et
  masquerait le goulot. Il est reporté une fois de plus comme renfort partagé
  (`polyvalents`), mobilisable sur n'importe quelle typologie mais sur un siège
  à la fois — même asymétrie que l'écran « Fragilité », arbitrée par la décision
  « le ninja est un renfort, jamais un spécialiste ».
  Quand aucune typologie ne porte le drapeau, `typologieNinjaDefinie` est faux
  et une réserve à zéro se lit « pas de renfort ici », pas « pénurie de
  renforts ». Le manque de la ligne ninja elle-même est isolé
  (`manquePolyvalents`) : ce vivier **étant** celui des renforts, il ne peut pas
  être proposé contre son propre manque.

Les deux approximations qui restent vont dans le même sens — demande sous-estimée
pour les stands multi-typologies, vivier surestimé pour un animateur compétent
sur plusieurs — donc un goulot signalé en est un, et l'absence de goulot ne
prouve rien.

**Sans animateur au référentiel, il n'y a pas de bornes du tout** : le problème
ne peut pas être construit, la réponse est celle d'une édition vide (aucun jour,
toutes les bornes à 0) et `animateursTotal` vaut 0. C'est ce dernier champ, et
non une liste de typologies vide, qui distingue « la liste d'animateurs n'est pas
encore remplie » de « aucun stand ne propose une typologie unique ».

La variante d'une simulation est construite **côté serveur**, sur des copies en
mémoire — rien n'est écrit, elle meurt avec la requête. Côté serveur et non dans
le navigateur pour la même raison que les résolutions depuis le référentiel : le
JSON d'un planning réel dépasse la taille de corps admise.

## Fragilité du planning

`GET /api/fragilite` répond à une troisième question, distincte des deux
précédentes : **qui est irremplaçable**. Là où `/api/feasibility` dit pourquoi
un planning ne tient pas et `/api/staffing` combien recruter, celui-ci mesure ce
qu'un désistement emporterait. Comme eux, il ne lance **aucune résolution** : il
lit le plan déjà persisté et le référentiel des compétences.

Deux indicateurs dans la même réponse :

- **par animateur**, les couples stand × créneau qui passeraient sous
  l'effectif minimum s'il se désiste. Les sièges étant générés à
  `max(1, effectifMin)` par couple (moitié sur une vacation de couverture de
  pause), le nombre de sièges d'un groupe **est** son plancher : un groupe
  complet descend sous l'effectif dès qu'un siège se libère. Le chiffre qui
  classe vraiment est donc `postesIrremplacables` — les groupes où *personne
  d'autre* ne pourrait reprendre le siège. Un groupe **déjà** en sous-effectif
  n'est imputé à personne : il est compté à part
  (`groupesDejaSousEffectif`) ;
- **les couples stand × créneau tenus par au plus une personne compétente**
  pour les typologies du stand, avec la sévérité correspondante.

Un remplaçant possible est quelqu'un de compétent, non déclaré indisponible ce
jour-là, majeur si le stand est réservé aux majeurs, et qui ne tient pas déjà un
siège chevauchant. Repos quotidien, plafond hebdomadaire, contraintes ad hoc et
préférences ne sont **pas** vérifiés : la réponse reste optimiste, comme les
bornes du besoin minimum. Un « irremplaçable » l'est certainement ; un
« remplaçable » peut ne pas l'être après résolution. Un seul désistement est
simulé à la fois.

### Le cas des ninjas

`Animateur.hasCompetenceFor` répond « oui » pour un ninja sur **tous** les
stands : c'est le sens même de la polyvalence. Les deux indicateurs les comptent
donc différemment, délibérément :

- la **rareté de compétence** ne compte que les *spécialistes* — ceux qui
  détiennent une des typologies proposées par le stand — et affiche les ninjas
  disponibles à côté, en `renforts`. Les inclure ferait disparaître presque
  toutes les lignes sur un référentiel qui compte quelques ninjas, et ce vide
  serait un artefact de mesure, pas une bonne nouvelle. Un stand avec un
  spécialiste et trois ninjas reste un stand à un spécialiste, simplement moins
  grave qu'un stand sans aucun ;
- la **remplaçabilité** d'un siège les compte pleinement : la question y est de
  savoir si le siège peut être tenu demain, et le solveur y enverrait un ninja.

Le raisonnement complet, et les deux options écartées, sont dans
[`decisions/0017-fragilite-le-ninja-est-un-renfort-pas-un-specialiste.md`](decisions/0017-fragilite-le-ninja-est-un-renfort-pas-un-specialiste.md).

`ninjaConfigure: false` signale qu'aucune typologie n'est marquée ninja dans
l'édition — les deux comptages coïncident alors, et un `renforts` à zéro partout
ne veut pas dire pénurie.

Trois précisions sur le contrat. Un couple est en réalité un triplet
stand × créneau × **fenêtre** : un stand fermé en milieu de créneau produit
plusieurs segments, donc plusieurs lignes qui ne se distinguent que par
`heureDebut`. `groupesSansSpecialiste` se compte dans cette même unité, comme
`groupesAnalyses` — un stand que personne ne sait tenir, ouvert sur quarante
créneaux, y pèse quarante. Et `effectifMin` est le minimum **configuré sur le
stand**, pas le plancher du groupe : sur une vacation de couverture de pause
(`couverturePause: true`) les sièges sont générés à la moitié, arrondie au
supérieur, et c'est `siegesRequis` qui porte le plancher réellement applicable.

Les listes sont bornées (20 postes détaillés par animateur, 100 couples
stand × créneau), `postesNonDetailles` et `totalCompetencesRares` disant ce qui
n'est pas montré. Les animateurs sans aucune affectation ne sont pas listés :
ils ne peuvent pas être un point de défaillance.

## Espace animateur

Seules routes accessibles sans session admin. Le jeton — le lien imprimé sur le
PDF individuel — résout à lui seul l'animateur **et** son édition :
`X-Edition-Id` n'y est pas lu. Un jeton inconnu répond `404`, jamais `401`.

**Le lien ne suffit pas.** L'espace sert le planning en téléchargement, donc
toutes les routes sauf `/code` et `/session` exigent aussi la session du cookie
`planning-espace`, liée à l'animateur que le jeton résout. L'e-mail de la fiche
**est** le second facteur : sans adresse, pas de code, et `400`. Trois codes
jamais utilisés dans la fenêtre déclenchent un `429` — ouvrir la session efface
le compteur.

La rotation du jeton (`POST /api/animateurs/{id}/token`) invalide aussitôt le
lien déjà distribué : c'est le geste à faire quand un planning individuel a
fuité.

**L'espace montre le plan publié**, pas le plan de travail : ce qu'un animateur
voit est ce qu'on lui a envoyé. Un échange validé, une réparation appliquée ou
un solve ne déplacent pas son espace tout seuls — il faut publier. `publieLe`
nul signifie que rien n'a encore été communiqué : les postes sont alors vides,
et l'interface le dit. Les suggestions d'échange se calculent sur ce même plan
publié : on ne troque que ce qu'on nous a annoncé.

**Foire fermée** : soumissions et annulations sont refusées **côté serveur**
(`400`) ; la vue `foireOuverte` ne sert qu'à l'afficher. Le planning reste
visible et téléchargeable.

## Échanges

Une demande naît `EN_ATTENTE_CIBLE` et n'entre dans la file décidable
(`PROPOSEE`) qu'une fois **acceptée par le collègue ciblé**. Un refus du
collègue est terminal : l'admin n'arbitre jamais.

Chaque demande est prévalidée contre les contraintes dures mais **enregistrée
quel que soit le verdict** — la réponse porte `prevalidationOk` et les
contraintes violées.

Sémantique : si le collègue tient aussi un poste sur le créneau, les deux
permutent ; sinon il reprend simplement le poste. Un échange **dirigé** cède un
créneau et en récupère un autre désigné (« je te laisse mon lundi, je prends ton
mardi »).

L'acceptation applique l'échange exactement comme simulé, pose deux verrous
`ANIMATEUR_CRENEAU` sur le créneau que chacun **reçoit**, et notifie. **Le
solveur n'est pas relancé.**

Un lot sollicite **chaque collègue visé**, une notification par collègue et non
une par lot : le compte annoncé est celui de ses seules demandes, personne
n'apprend ce qui a été proposé à un autre.

### Qui peut me remplacer ?

`GET /api/espace-animateur/{jeton}/suggestions-echange?creneauId=X&standId=Y&plafond=N`

Pour l'animateur qui ne veut pas d'un créneau et n'a personne en tête : au lieu
de désigner un collègue, il ne désigne que **son** siège et l'assistant cherche
les échanges qui tiennent. Même mécanique que l'assistant de réparation —
filtre d'éligibilité du solveur des deux côtés du troc, une simulation par
piste, verdict sur le **plan entier**, plafond de 20 (100 au maximum).

**Trois familles**, portées par `nature`, parce qu'un échange n'est pas
seulement « quelqu'un prend ma place » :

| `nature` | Ce que ça fait au demandeur |
| --- | --- |
| `LIBERE` | Le collègue est libre à cette heure-là : le créneau quitte ses mains |
| `CROISE` | Le collègue travaille ce même créneau : les deux sièges permutent, le demandeur change de stand |
| `DIRIGE` | Un siège d'**un autre créneau** revient — « je te laisse mon lundi, je prends ton mardi » |

Une suggestion `DIRIGE` porte `creneauCibleId`, sa date, ses horaires et son
stand : de quoi construire la demande dirigée telle quelle. Les deux autres se
jouent sur le seul créneau du demandeur.

Les trois familles sont évaluées **en alternance**, pas l'une après l'autre :
avec 150 collègues libres, le plafond partirait entier en « on vous libère »
sans jamais demander si un mardi peut s'échanger contre un lundi. Au plus deux
sièges par collègue sont proposés en retour, pour que le choix porte sur des
jours et pas sur une seule personne. `listeTronquee` dit que la recherche s'est
arrêtée au plafond, pour que l'IHM ne laisse pas lire « rien d'autre n'est
possible ».

La réponse ne porte **aucun score** : un `HardMediumSoftScore` ne dit rien à un
animateur, et publier la santé globale du plan dans l'espace la donnerait à tout
porteur de jeton.

Route en lecture seule : elle ne crée aucune demande. On ne cherche que pour ses
propres sièges (`400` sinon), et seulement **foire ouverte** — chercher des
partenaires pour un échange que plus personne ne peut proposer n'induirait qu'en
erreur.

## Marque et mentions légales

`/api/branding` et `/api/mentions-legales` sont **publics**, comme
`/api/config` : une application incapable de dire son nom avant connexion
accueille ses visiteurs avec une barre vide, et une mention légale réservée aux
connectés manque ses lecteurs — celui qui décide s'il fait confiance au site, ou
l'animateur dont le lien a expiré et qui cherche à qui écrire.

Le frontend lit la marque **une fois, avant son démarrage**.

`productName` n'est jamais vide. **`logoUrl` vide signifie « n'affiche aucun
logo »**, jamais « affiche celui par défaut » : une instance par client, et
aucun client n'hérite de la marque d'un autre.

Les champs légaux valent la chaîne vide quand la variable `LEGAL_*`
correspondante manque, et la page affiche alors **ce qui manque** plutôt qu'un
éditeur inventé. Ils vivent en configuration et non dans le code : le dépôt est
public, et pour un éditeur personne physique l'adresse et le numéro
d'immatriculation *sont* des données personnelles.

La réponse porte aussi deux booléens, `mesureAudience` et `suiviErreurs` : vrai
quand le token Cloudflare et le DSN Sentry ont une valeur — celle que lisent
vraiment le beacon et le SDK, et non le fait d'avoir posé la variable
d'environnement. La nuance compte en `%prod`, qui fournit un token Cloudflare de
repli : `mesureAudience` y est vrai sans que `CLOUDFLARE_WEB_ANALYTICS_TOKEN`
soit posé, parce que la mesure tourne bel et bien (voir `docs/observabilite.md`).
La politique de confidentialité y accroche les paragraphes qui nomment Cloudflare
et Bugsink — sans eux, un déploiement qui laisse les deux vides annonçait deux
traitements qui n'ont pas lieu, dont un transfert hors UE. Ces drapeaux passent
par cet endpoint et non par `/api/config`, qui porte pourtant les mêmes clés :
sa lecture échoue en silence vers une configuration désactivée, ce qui
**masquerait** ces paragraphes là où les outils tournent vraiment. Ici, un échec
affiche un message d'erreur au lieu d'une page silencieusement fausse.

## Référentiels

Même schéma CRUD partout, tout cloisonné par édition — deux éditions portent les
mêmes identifiants métier sans se marcher dessus.

Les typologies de jeux sont un référentiel, **pas un enum figé** : les
compétences et les typologies proposées référencent leurs `id` par clé
étrangère. Un `id` inconnu répond `400`, et supprimer une typologie encore
utilisée aussi. `ninja` désigne la typologie des profils polyvalents, au plus
une à la fois — la poser sur une autre retire le drapeau de la précédente.

Un créneau requiert une date, une heure de début et une heure de fin : sans
l'une des trois, l'écriture répond `400` plutôt que d'aller heurter la colonne
`NOT NULL`. En revanche une fin **antérieure ou égale** au début est acceptée —
c'est ainsi que s'écrit un créneau franchissant minuit (20:00→00:00 dure quatre
heures), et lui seul lit une fenêtre de stand datée du lendemain.

**Supprimer un créneau ou un stand emporte les postes du planning persisté qui
s'y trouvaient**, et eux seuls — un poste ne survit ni au créneau sur lequel il
était placé, ni au stand sur lequel il était ouvert (`stand_id` et `creneau_id`
sont `NOT NULL` : le siège ne peut pas leur survivre). Le reste du plan est
conservé. C'est la même règle que le découpage applique déjà à la grille entière
lorsqu'il la remplace.

**Supprimer un animateur, en revanche, ne supprime pas ses postes : il les
vide.** `animateur_id` est nullable, et un poste sans animateur *est* la
représentation d'une place non pourvue dans ce modèle. Chaque siège qu'il tenait
redevient donc une place à pourvoir, visible et comptée comme telle — par
`postesNonPourvus`, par la heatmap, par les écrans de couverture — au lieu de
disparaître et de faire paraître le plan mieux couvert qu'il ne l'est. Des trous
apparaissent ainsi dans un plan que personne n'a redemandé de résoudre : c'est
voulu, ils sont réels, et c'est précisément ce qu'on veut voir avant de
replanifier.

Dans les deux cas la règle vaut ligne à ligne en suppression en lot, chaque
suppression étant sa propre transaction.

**Écrire le référentiel est refusé (`409`) tant qu'une résolution tient le
solveur** : supprimer **et modifier** un stand ou un animateur, supprimer un
créneau, compacter les horaires, réinitialiser l'édition
(`POST /api/planning/reset`) et importer un scénario. La modification est
concernée pour la même raison que la suppression — l'atterrissage réécrit `nom`,
les effectifs et `reserveMajeurs` d'un stand, `prenom`, `nom`, `dateNaissance`,
`manager`, compétences et jours d'indisponibilité d'un animateur, depuis les
objets capturés au démarrage : un renommage fait pendant reviendrait à
l'ancienne valeur sans que rien ne le dise. La **création**, elle, n'est pas
refusée : une entité qui n'existait pas au démarrage n'est nommée par aucun
poste du résultat, donc l'atterrissage ne la touche pas. Le corps de la
réponse porte le job en cause **et un `message`** lisible tel quel par l'IHM. Une
résolution construit son problème depuis le référentiel au démarrage et
réenregistre ce référentiel en persistant son résultat : supprimer entre les deux
serait annulé par l'atterrissage du solve, et l'entité reviendrait d'elle-même
plusieurs minutes plus tard — pour un animateur, une donnée personnelle qui
ressuscite. Une résolution seulement *en file* ne bloque rien : elle lira le
référentiel à son tour venu, suppression comprise.

La réinitialisation et l'import sont les cas les plus coûteux : leur
atterrissage réinsérerait stands, animateurs, créneaux et `poste_affectation`,
mais laisserait `emplacement`, `stand_horaire`, `stand_typologie`,
`stand_ouverture` et les contraintes ad hoc effacés — l'édition reviendrait à
moitié restaurée, et pour l'import les anciens créneaux réapparaîtraient par id
à côté des nouveaux.

Le refus est **cantonné à l'édition de la résolution**, comparée à l'édition
courante : un solve lancé sur une variante de repli ne bloque rien dans
l'édition qu'on prépare à côté, puisqu'il n'écrit que dans la sienne. C'est la
comparaison sur l'édition **du job**, pas sur celle qui l'a soumis — une
résolution lancée sur A reste donc bloquante pour A même si l'onglet est passé
sur B.

Contraintes ad hoc et verrouillages sont des **états** : on ne les met pas à
jour, on les supprime et on les recrée. Une même paire d'animateurs ne peut pas
être à la fois en incompatibilité et en affinité (`400`). Une cible déjà
verrouillée renvoie `200` sans doublon.

### Ce qu'une suppression emporte

`GET /api/stands/usages`, `/api/animateurs/usages` et `/api/creneaux/usages`
chiffrent ce qui référence une sélection — postes **pourvus** du planning
persisté, ajustements manuels, verrouillages — pour que la confirmation de
suppression le dise avant de supprimer. Répéter `id` compte plusieurs entités :
`?id=S1&id=S2` renvoie **un total agrégé**, pas un détail ligne par ligne, et
une suppression en lot n'a donc qu'un appel à faire. Le client redécoupe quand
la chaîne de requête approche la limite de ligne du serveur — budget sur la
**longueur encodée**, pas sur un nombre d'`id`, les identifiants de stand et
d'animateur étant du texte libre.

Quatre propriétés, et aucune n'est un oubli :

- **le décompte informe, il ne bloque pas** — aucun seuil, aucun refus au-delà
  d'un nombre ; la suppression reste celle que la ressource expose déjà ;
- **un `id` inconnu compte pour zéro**, il ne déclenche pas de `404` : l'appel
  sert un dialogue ouvert sur des lignes déjà affichées, et une ligne
  supprimée entre-temps ne doit pas transformer une confirmation en message
  d'erreur — c'est la suppression elle-même qui le dira. Deux requêtes sont
  refusées en `400`, et toutes deux nomment une requête cassée plutôt qu'une
  ligne absente : celle sans aucun `id`, et un `id` de créneau qui n'est pas un
  nombre. C'est pourquoi `/api/creneaux/usages` prend ses `id` en texte : liés
  en `List<Long>`, la conversion échouerait *avant* la ressource et répondrait
  `404` ;
- **un siège vide ne compte pas** : ce que le chiffre annonce, c'est le nombre
  de créneaux effectivement tenus par quelqu'un qui disparaîtront ;
- **trois compteurs, pas un inventaire.** Zéro partout se dit en les nommant
  (« aucune affectation, aucun ajustement manuel et aucun verrouillage »), et
  jamais « rien ne le référence » : d'autres tables suivent en cascade sans
  être comptées ici — demandes d'échange, horaires et ouvertures d'un stand,
  compétences et souhaits d'un animateur. Les compter aussi est un autre
  chantier ; affirmer qu'elles n'existent pas serait un mensonge.

### Horaires d'un stand

Deux niveaux, rendus tels quels sans expansion — c'est la vue que l'IHM édite :
les **règles récurrentes**, et les **exceptions datées** qui priment sur elles
pour le seul jour qu'elles nomment. `heureFin` nullable vaut « jusqu'à la
fermeture ». Arbitrage complet dans
[`domaine.md`](domaine.md#horaires-récurrents--trois-couches-un-seul-mode-par-jour).

Une écriture répond `400` si une règle est incohérente, ou si deux règles de
même portée sur des jours qui se croisent se contredisent — il n'y aurait pas de
gagnant non arbitraire.

Le **compactage** réécrit les fenêtres datées répétées en règles équivalentes.
`appliquer=false` est un essai à blanc. Un stand n'est réécrit que si les règles
proposées reproduisent ses propres segments ouverts. `gapMinutes` compte les
minutes d'ouverture en désaccord : `1` quand un ancien `23:59` était devenu la
fermeture réelle du jour — conséquence visible, un stand absent toute la journée
ne génère plus le poste d'une minute que ce `23:59` laissait derrière lui.

### Visualisation des ouvertures

Grille stand × jour construite **à partir des postes que le solveur recevrait**,
donc l'écran valide la donnée réelle et non une seconde interprétation de la
même saisie. `source` (`DEFAUT` / `REGLE` / `EXCEPTION`) dit quelle couche a
décidé, ce qui permet de remonter à la saisie fautive.

Trois anomalies, dont aucune ne bloque une résolution :
`STAND_JAMAIS_OUVERT`, `FENETRE_SANS_EFFET` (fenêtre qui ne recoupe aucun
créneau de son jour) et `SEGMENT_TROP_COURT` — la signature du contournement
`23:59`.

## Import de scénario

**C'est un diff, pas un remplacement aveugle.** Stands et animateurs du fichier
sont mis à jour **en place** : un animateur conservé garde son `access_token`
(les liens imprimés survivent), ses sessions, ses demandes hors créneaux
remplacés, et son e-mail si le fichier n'en porte pas. Seuls les absents du
fichier sont supprimés, en cascade.

Sont en revanche **toujours** effacés : le planning résolu, les verrous, les
contraintes ad hoc, la trace de résolution. Les créneaux sont remplacés par ceux
du fichier.

`GET /api/reference-data/impact-import` chiffre ce périmètre **avant**
d'importer — c'est ce qu'affiche le dialogue de confirmation, qui enregistre
aussi un instantané quand un planning résolu existe.

Les sections optionnelles du fichier (`parametresLegaux`, `parametresDecoupage`,
`parametresSolveur`, `typologies`, `contraintes`, `edition`) sont appliquées si
présentes, laissées telles quelles sinon. `edition:` route l'import vers
l'édition désignée, créée vide au besoin. Formats :
[`import-export.md`](import-export.md).

Le **validateur** (`valider-scenario-fichier`) contrôle la structure sans rien
importer et répond **toujours 200**, y compris pour un fichier vide ou mal
formé — le verdict est dans le corps. Il ne remplace pas les vérifications de
références croisées qu'effectue un import réel.

## Exports

Génération côté serveur. Le planning est envoyé dans le corps, **sauf l'export
global** : un planning de la taille de l'événement pèse plusieurs mégaoctets en
JSON, que l'appelant n'a pas à téléverser pour récupérer un document. Il lit
donc la même source que les calendriers.

L'envoi par e-mail est le renvoi individuel de `POST
/api/planning/envoi/animateur/{id}`, et répond un compte rendu nommant ceux
sans adresse et les échecs. L'envoi collectif, lui, est passé sous
*Publication* : il ne s'agit plus d'envoyer à tous mais de publier.

## Sauvegarde automatique

Deux endpoints, en lecture pour l'essentiel : la sauvegarde de nuit est réglée
par l'hébergeur, pas par l'API.

| Endpoint | Ce qu'il fait |
| --- | --- |
| `GET /api/backups` | L'état complet : emplacement et rétention (variables d'environnement), interrupteur, prochaine exécution, compte rendu de la dernière tentative, et la liste des dumps présents (nom, taille, date). |
| `PUT /api/backups/active` | `{ "active": false }` **suspend** la sauvegarde de nuit ; `true` la reprend. C'est la seule valeur que cet écran écrit. |

Trois choses volontairement absentes, et qui le resteront :

- **pas de téléchargement d'un dump.** Un fichier de sauvegarde porte les noms,
  dates de naissance et adresses de tous les animateurs, mineurs compris : le
  navigateur n'est pas un canal de diffusion pour ça ;
- **pas de restauration.** C'est une opération sur la base PostgreSQL, faite
  par l'exploitant (`pg_restore`) — voir
  [`exploitation.md`](exploitation.md) ;
- **pas d'écriture de l'emplacement ni de la rétention.** Un chemin de disque et
  le nombre de copies qu'un volume porte se décident avec ce volume
  (`BACKUP_DIR`, `BACKUP_RETENTION`), pas depuis un écran.

Comme le dump SQL, ces endpoints ignorent `X-Edition-Id` : une sauvegarde prend
l'instance entière.

## Deux pièges

**Un dump pris avant `V52` ne se réimporte plus.** Le dump nomme ses colonnes
une à une, et `V52` a renommé `animateur.jeton_acces` en `access_token` :
l'import refuse avec `column "jeton_acces" ... does not exist`. Assumé — faire
vivre les deux noms rendrait permanent le décalage que `V52` supprime. Pour
récupérer un tel fichier, y remplacer la chaîne avant de le charger, c'est la
seule occurrence.

**`/api/planning/reset` vide l'édition courante**, pas la base. Les autres
éditions ne sont pas touchées.
