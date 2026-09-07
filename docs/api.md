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

Toute l'API est réservée à la session admin, avec cinq exceptions
volontaires : l'espace animateur (le jeton d'URL est la clé), l'abonnement ICS
(`/api/abonnements/*`, voir plus bas), les routes de
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

### Volumétrie du problème

`GET /api/planning/volumetrie` (et l'outil MCP `volumes`) décrit le problème
que la prochaine résolution construirait, calculé sur ce problème même — jamais
sur un produit stands × créneaux :

| Clé | Ce qu'elle compte |
| --- | --- |
| `animateurCount` | les valeurs possibles d'un poste, au sens Timefold |
| `posteCount` | les entités : un poste par siège à pourvoir |
| `contrainteAdHocCount` | les ajustements manuels appliqués par-dessus |
| `hoursToFill` | la somme des durées effectives des postes, fermetures de stands déduites — la base de l'écran Heures |
| `hoursAvailable` | le plafond légal de ce que les animateurs peuvent travailler sur les jours de l'événement : par animateur et par semaine ISO, les jours où il n'est pas indisponible (six au plus), chacun au plafond quotidien de son âge ce jour-là, le tout borné par le plafond hebdomadaire des paramètres légaux |

Le rapport des deux dernières est un taux de remplissage. C'est un plafond,
pas une prévision : compétences, repos entre vacations et règle de pause en
retranchent encore, si bien qu'un taux proche de 1 annonce déjà un planning
infaisable. Tout à zéro tant que l'édition n'a pas de données de référence.

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

### Point de départ d'une résolution complète

`POST /api/solve/async/reference-data?reamorcage=AUTO|PLAN_COURANT|AUCUN`
(#174). `AUTO`, le défaut, **repart du plan enregistré** s'il en existe un et
part de zéro sinon ; `PLAN_COURANT` l'exige et fait échouer le job quand il
n'y a rien d'où repartir ; `AUCUN` est le départ à froid, par son nom. Une
valeur inconnue répond `400`.

Repartir du plan, ce n'est **pas** l'épingler : chaque siège reçoit
l'animateur que le plan lui donnait et reste mobile, seuls les verrouillages
explicites sont figés. C'est ce qui distingue ce réamorçage de la
replanification incrémentale, qui fige tout ce qui reste valable. Le solveur
score la solution de départ en premier et conserve la meilleure qu'il ait
vue : à données égales, le résultat ne descend jamais sous le plan de départ.

Pourquoi le défaut n'est pas le départ à froid, y compris pour un script ou
un assistant : mesuré sur une édition réelle (3 499 postes, 600 s), un solve
à froid a fini **1 202 points de medium sous** le plan déjà en base, quand le
réamorçage l'a conservé et amélioré de ~3 %. Le départ à froid est le cas qui
détruit l'acquis, il se demande. Un siège dont le titulaire a disparu, ou qui
s'est déclaré indisponible depuis, repart vide — semer une violation que le
solveur devrait d'abord défaire est un moins bon départ qu'un trou. Un plan
de départ infaisable n'achète aucun raccourci : la terminaison sur
faisabilité ne se déclenche pas, le budget est consommé en entier.

Chaque résultat porte aussi `impactPublication: { personnes, publieLe }` —
combien de personnes verraient leur emploi du temps changer par rapport au
dernier plan publié, compté comme la publication le compte — ou `null` tant
que rien n'a été publié. C'est le compte à lire avant de publier ; la règle
`stabiliteDuPlanPublie` (voir [`contraintes.md`](contraintes.md#stabilité-du-plan-publié))
est ce qui le tient bas.

Le choix est **persisté avec le job** (`solver_job.reamorcage`, V68) : une
résolution en file rejouée après un redémarrage démarre comme on le lui avait
demandé. Le résultat le restitue — `reamorcage: { mode, postes, postesLiberes }`,
où `mode` vaut ce qui a été fait (`PLAN_COURANT` ou `AUCUN`, jamais `AUTO`,
et `reamorcage` est absent quand le problème est venu dans le corps de la
requête : son point de départ est celui de l'appelant, que le serveur ne peut
pas nommer)
— à côté de `previousPlan`, qui dit si le plan remplacé était meilleur. La
même brique servira la reprise d'un job `INTERROMPU` (#183) : elle n'est
spécifique à aucun écran.

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

### Un arrêt du serveur n'écrase pas le plan

Le cas ci-dessus est celui de la JVM qui meurt. Quand c'est le conteneur qui
s'arrête **sous** un calcul en cours — arrêt gracieux de l'application, live
reload en développement —, Timefold rend sa meilleure solution du moment comme
si le budget était épuisé. Ce résultat n'est pas un calcul abouti, et il est
traité comme tel :

- le job finit `INTERROMPU`, jamais `COMPLETED`, avec un message `error` qui
  dit ce qu'il est advenu du plan partiel ;
- ce plan partiel **ne remplace le plan enregistré que s'il le bat
  strictement** (dur, puis medium, puis soft) — ou s'il n'y avait aucun plan.
  Un calcul de quelques secondes interrompu par un redémarrage ne défait
  donc plus un plan à 0 dur ;
- `result.interruption: { partialPlanKept, partialScore, persistedScore }`
  porte la même information pour un script ; ni ligne d'historique KPI, ni
  courriel de fin de résolution ne sont produits quand le plan est écarté.

Le serveur laisse au solveur quelques secondes pour s'arrêter et écrire ce
verdict ; un calcul qui ne les respecte pas est retrouvé `RUNNING` au
démarrage suivant et passe par le cas précédent.

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

### Courbe de score en direct

Le même flux porte un troisième type d'événement, `score` : les points
(temps écoulé, hard, medium, soft) que Timefold annonce déjà par son
`bestSolutionChanged`. `GET /api/jobs/score` rend la même courbe d'un bloc.

**Ce qui est tracé est la résolution en cours** — ou la dernière, jusqu'à ce
que la suivante la remplace. Rien n'est persisté : un redémarrage oublie tout,
et rejouer les solves passés est hors périmètre.

### Pourquoi la durée voyage à part des points

Timefold ne déclenche `bestSolutionChanged` **que lorsque le meilleur score
s'améliore strictement**. Une résolution qui plafonne cesse donc de produire
des points — et c'est exactement l'état que l'écran existe pour montrer. Une
courbe normalisée sur ses seuls points toucherait toujours le bord droit : un
solve dont la dernière amélioration date de t=200 s sur un budget de 900 s se
dessinerait comme s'il progressait encore.

D'où `dureeMs`, mesuré à l'horloge et non aux événements : c'est le bord droit
de la courbe. Le navigateur y prolonge la dernière valeur à plat — ce segment
*est* le plateau — et en déduit depuis combien de temps chaque niveau ne bouge
plus. L'alternative (ajouter un point « rien de neuf » chaque seconde) mettrait
le plateau dans la série au prix d'un ordonnanceur, de mémoire et de points sur
le fil qui ne portent aucune information. `dureeMs` est **figé à la fin du
run**, pour qu'une courbe laissée à l'écran cesse de s'élargir.

Conséquence sur le flux : **une résolution en cours émet un événement à chaque
tick, même sans nouveau point** — c'est `dureeMs` qui bouge. L'événement est
alors minuscule (`points: []`). Une courbe terminée, elle, se tait.

### Deux bornes d'échantillonnage

Un solve annonce bien plus d'améliorations par seconde qu'une courbe n'a de
pixels. **Deux bornes, toutes deux côté serveur** :

| Borne | Effet |
| --- | --- |
| un point par seconde au plus | c'est **le plus récent** de la fenêtre qui est retenu, jamais le premier — le score ne fait que s'améliorer |
| 400 points au plus | au-delà, la série est **divisée par deux** et l'intervalle d'échantillonnage double avec elle |

Un solve de 15 min et un de 4 h coûtent donc la même mémoire et le même volume
sur le fil, et la courbe garde sa forme. Les annonces portant une solution
**non encore initialisée** sont ignorées : leur score décrit une affectation
partielle — un planning aux trois quarts vide ne viole presque rien — et les
tracer dessinerait une falaise au moment précis où le plan devient complet.

Les événements `score` sont des **deltas**, seuls voyagent les points que cette
connexion n'a pas encore reçus. `depuis` est l'indice du premier point envoyé :
`0` veut dire « remplace ce que tu as » (connexion neuve, nouvelle résolution,
série qui vient d'être divisée), toute autre valeur « ajoute à cet indice ».
`generation` bouge dès que la série cesse d'être une extension de elle-même,
c'est ce qui rend la lecture incrémentale sûre. Un tick sans rien de neuf
n'émet rien du tout.

**Cloisonnement.** `EventSource` ne peut pas poser d'en-tête, donc le flux n'a
pas d'édition : chaque événement `score` porte l'`editionId` de la résolution
et le navigateur n'affiche la courbe que si c'est la sienne — exactement ce
qu'il fait déjà de `JobView.editionId`. La lecture qui, elle, porte un en-tête
— `GET /api/jobs/score` — applique le filtre **côté serveur** et répond `204`
pour une courbe d'une autre édition. C'est elle que la page Solveur appelle à
son ouverture.

**Un événement `score` sans `jobId` veut dire « il n'y a plus de courbe »**,
et il est envoyé une fois par connexion. Le cas visé est un redémarrage : le
client tient une courbe dont le dernier événement disait `termine: false`, le
serveur auquel il se reconnecte n'a plus de trace, et le silence laisserait ce
graphe à l'écran comme s'il était vivant — pour une résolution que le serveur
rapporte déjà en `INTERROMPU`.

La courbe se ferme à la fin du job, dans les trois cas — succès, échec,
annulation à la main : le dernier score annoncé est ajouté même si la fenêtre
d'échantillonnage n'était pas écoulée, `dureeMs` est figé, et `termine` passe à
`true`. Un job annulé **avant même que son solveur démarre** ne touche pas à la
courbe : ouvrir une trace vide effacerait celle du run précédent, et l'écran
annoncerait « pas encore de première solution » pour une résolution déjà
terminée.

Réglage : `planning.jobs.stream.score` (1 s par défaut,
`JOBS_STREAM_SCORE`).

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
disparu, en listant lesquelles. Elle répond aussi `409` — même corps que les
écritures du référentiel, le job en cause nommé — tant qu'une résolution tient
le solveur **de cette édition** : le solve écraserait en se terminant le plan
qu'on vient de remettre en place. La garde est dans le service, pas dans
l'écran : l'API et l'outil MCP `restaurer_instantane` la partagent. Une
résolution sur une autre édition ne refuse rien.

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
volumétrie exactes, écarts non mesurés.

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

## Accusé de réception du planning

`POST /api/espace-animateur/{jeton}/confirmation` — « j'ai lu et je serai là ».
La seule chose que l'espace écrit à propos du planning lui-même. Cliquer deux
fois répond `200` avec la **première** date, jamais une erreur : c'est un geste
normal sur une page rafraîchie. Confirmer avant toute publication répond `409`
— il n'y a rien à confirmer.

`GET /api/animateurs/confirmations` — une ligne par animateur, `NON_VU`
compris, pour la colonne de la page Animateurs.

Trois statuts, et l'absence de ligne en base **vaut** `NON_VU` : la remise à
zéro est une suppression, il n'y a donc jamais deux façons d'écrire « cette
personne n'a pas répondu ».

| Statut | Ce qu'il dit |
| --- | --- |
| `NON_VU` | Rien n'est revenu — personne n'a encore été interrogé, ou le plan a bougé depuis |
| `CONFIRME` | Le bouton a été cliqué, avec la date |
| `RELANCE` | La relance automatique est partie et reste sans réponse |

`affecte` distingue quelqu'un qui n'a **aucun poste** dans le plan publié : il
n'est pas silencieux, on ne lui a rien demandé. La colonne ne le compte pas
parmi les gens à relancer.

**Republier ne remet à `NON_VU` que les personnes dont l'emploi du temps a
réellement changé** (`PublicationDiffService`). Quelqu'un qu'on prévient
seulement d'une décision d'échange lit les mêmes journées qu'avant : lui
redemander de confirmer transformerait le bouton en réflexe plutôt qu'en
réponse.

## Historique des actions

`GET /api/historique?limite=200` — ce qui a été fait dans l'édition courante,
du plus récent au plus ancien, plafonné à 500 lignes. `GET
/api/historique/actions` rend l'inventaire des actions que l'application sait
décrire, pour que l'écran propose un filtre qu'il n'a pas inventé.

Lecture seule, et cette forme est définitive : un journal qu'on peut modifier
n'est pas un journal. Aucune suppression non plus — ce qui borne la table est
une **rétention** (`JOURNAL_RETENTION`, 90 jours par défaut) appliquée par la
tâche de nuit, pas un bouton.

Une ligne porte : quand, **qui** (`ADMIN`, `ANIMATEUR`, `ANONYME` pour un
appel qui n'a présenté aucun justificatif valable — les routes de l'espace
sont ouvertes, donc un jeton faux ou périmé va jusqu'au refus —, `ASSISTANT`
pour un appel MCP, `SYSTEME` pour la nuit), l'action et sa phrase en français,
ce qu'elle visait, **les noms des champs qu'une modification a réellement
changés**, et si elle a abouti ou été refusée — avec son code HTTP.

Deux choses n'y sont pas, et c'est le contrat :

- **aucune valeur.** « nom, email » dit ce qui a bougé, jamais ce que c'est
  devenu ;
- **aucune identité.** La table stocke un identifiant ; `acteurNom` et
  `entiteNom` sont résolus depuis le référentiel **à la lecture**, si bien
  qu'une fiche supprimée laisse une ligne qui ne nomme plus personne.

Sont tracées les écritures et les sorties de données (exports PDF, ICS, CSV,
dump de base, envois de courriel), jamais les simples consultations : un
`POST` qui ne fait que calculer — une prévisualisation, une simulation,
l'analyse préalable d'un fichier — est déclaré **sans trace, avec son motif**,
dans `CatalogueActions`.

## Notifications planifiées

`GET` / `PUT /api/parametres-notifications` — ce que les envois de nuit ont le
droit de faire **sur cette édition**.

| Champ | Rôle |
| --- | --- |
| `actives` | Le garde-fou. `false` par défaut : rien ne part d'une édition que personne n'a armée |
| `heureRappelVeille` | Heure locale à partir de laquelle le rappel J-1 peut partir. **23h00 au plus tard** : la tâche s'exécute une fois par heure et la fenêtre se ferme à minuit (après, « demain » serait faux), donc une heure plus tardive tomberait entre deux exécutions et ne partirait jamais. Refusée (`400`) plutôt qu'acceptée et silencieuse |
| `delaiRelanceHeures` | Silence toléré après la publication avant une relance (1 à 720) |
| `ancienneteEchangeJours` | Attente d'une demande d'échange avant alerte (1 à 60) |

`actives` est un booléen explicite parce qu'une `Edition` ne porte **ni dates ni
drapeau « en cours »** : aucun job ne peut deviner que les animateurs de
l'édition 2025 ne sont pas ceux qu'il faut prévenir pour demain. La duplication
d'une édition ne recopie pas ce réglage — une édition neuve naît muette.

Le rythme de la machine, lui, n'est pas dans l'API : `NOTIFICATIONS_CRON` et
`NOTIFICATIONS_TIMEZONE` (voir [`exploitation.md`](exploitation.md)).

`GET /api/alertes` — ce que ces jobs ont laissé sur le bureau, plus récent
d'abord. `limite` s'applique **par type** (50 par défaut, 500 au maximum), et
c'est le point : une fiche sans adresse produit une alerte *chaque soir*, si
bien qu'un plafond global laissait une poignée d'injoignables enterrer les
`ALERTE_ECHANGE` en quelques jours — les seules qui appellent une **décision**,
et dont cet écran est le seul point de sortie. En lecture seule : une
alerte se referme en traitant ce qu'elle signale, pas en l'effaçant. Le journal
ne stocke qu'un `animateurId` ; `nomAffiche` est résolu **à la lecture** depuis
le référentiel, donc une fiche supprimée laisse une alerte qui ne nomme plus
personne.

**Chaque envoi est réservé en base avant de partir**, sur une clé primaire
`(edition_id, type, cle)` : le job insère, et n'envoie que si l'insertion a
écrit une ligne. Tourner toutes les heures, redémarrer au milieu ou rejouer le
job à la main n'écrit donc à personne deux fois — et une demande d'échange
n'est signalée **qu'une seule fois**, quel que soit son âge ensuite.

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
saisies à la main que la dernière analyse a trouvées encore en écart, la plus
en écart d'abord, avec leur id, leur raison et le nombre d'écarts qu'elles
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

« Respectée » signifie seulement qu'aucun écart n'a été trouvé pour ce
poste précis, **pas que la contrainte s'applique à lui** : l'IHM ne doit pas la
présenter comme un satisfecit. La simulation de remplacement ne persiste rien.

`POST /api/postes/{id}/simulation-swap` note un candidat qu'on lui désigne.
Aucun écran ne l'appelle : l'IHM n'expose que les remplaçants viables de
l'assistant ci-dessous, pour ne pas laisser choisir un remplacement qui casse
une règle dure. L'endpoint reste ouvert à l'API et à l'outil MCP
`simuler_swap`.

### Déplacer une affectation à la main

Le glisser-déposer des vues journalières (#308), et le même geste par l'API
et par les outils MCP `simuler_deplacement` / `deplacer_affectation`.

| Endpoint | Effet |
| --- | --- |
| `POST /api/postes/{id}/deplacement/simulation?cible=P` ou `?animateur=A` | Chiffre le geste sur le plan enregistré. Sans corps : le verdict se lit sur les règles de l'édition — celles que l'organisateur a désactivées comprises —, qui ne sont pas à l'appelant de fournir. Ne persiste rien. |
| `POST /api/postes/{id}/deplacement?cible=P` ou `?animateur=A` | Applique le geste au plan enregistré. Écrit. `&occupant=X` dit qui l'appelant croit sur le siège de départ : si quelqu'un d'autre l'occupe, `409` et rien n'est écrit. |

Une règle, trois gestes. Après le geste, le siège `{id}` tient
`animateurCibleId` (personne quand il est nul) et `posteCibleId`, s'il y en a
un, tient `animateurSourceId` :

- déposé sur un siège **vide** (`cible`) : la personne y va, son siège reste
  vide — le trou se déplace, le score dur ne bouge pas ;
- déposé sur un siège **tenu** : les deux personnes échangent leurs sièges ;
- déposé sur une **personne** (`animateur`, le rail) : elle prend le siège —
  et si elle en tient déjà un sur le même créneau, les deux sièges sont
  échangés plutôt que de la mettre à deux endroits à la fois.

**Le verdict est celui du planning entier**, comme pour un échange : déplacer
un siège peut casser une règle dure sur un siège qu'il ne touche pas (heures
hebdomadaires, repos). `casseContrainteDure` vrai → l'écriture répond `400`
en nommant les règles (`nouvellesViolationsDures`), et n'écrit rien. Cette
simulation est faite **côté serveur au moment d'écrire**, sur le plan
enregistré, quoi que le navigateur ait vérifié — les vues journalières
n'appellent d'ailleurs que l'écriture, le refus portant déjà le motif. Un
siège verrouillé refuse (`400`), une résolution en cours aussi (`409`), les
deux lignes s'écrivent dans une seule transaction, et l'analyse de contraintes
stockée est re-dérivée du plan après coup, comme après une restauration.

Le clic sur un nom reste le chemin clavier de ce geste : « Pourquoi lui ? »
et l'assistant de réparation ci-dessous.

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
| `GET /api/banc-de-touche?standId=X` | Idem, sur le premier créneau que le plan pourvoit. |

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
et les listes vides. Un `404` reste réservé à un créneau qui n'existe **nulle
part** dans l'édition — la seule requête qui soit vraiment fausse.

**`creneauxAvecSieges` est tout ce que le sélecteur propose**, et c'est
délibérément la seule source de créneaux de l'écran. Le référentiel en porte
davantage — 354 vacations sur le scénario de référence, dont le plan n'en
pourvoit qu'une partie — et proposer les autres, c'était envoyer des données que
l'écran n'affichera pas, en laissant l'utilisateur tomber sur un créneau dont la
réponse ne peut être que vide. La liste porte de quoi étiqueter chaque option
(`id`, `jour`, `date`, `heureDebut`, `heureFin`, `famille`) : l'écran ne lit plus
`/api/creneaux` du tout.

Un sélecteur ne peut pas nommer un créneau valide avant sa première réponse,
d'où la variante **sans identifiant** : le serveur répond sur le premier créneau
qu'il pourvoit et dit lequel dans `creneauId`. Quand il n'en pourvoit aucun,
`creneauId` est nul, `creneauxAvecSieges` vide, et `statut` vaut `NO_PLAN` — les
deux cas coïncident, et l'écran l'explique au lieu d'afficher une liste vide.

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
second lit les écarts *du siège lui-même* match par match ; le reproduire
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
- le besoin minimum ne regarde les compétences que dans son volet par
  typologie, décrit plus bas, et ne tient pas compte du repos quotidien ni du
  fait qu'un planning réel répartit le travail loin en dessous des plafonds
  légaux. Les indisponibilités déclarées, elles, sont désormais reportées à
  part — voir `minimumAvecIndisponibilites` plus bas.

La compétence n'entre pas dans le calcul **de faisabilité** : depuis sa bascule
en contrainte medium, n'importe quel animateur disponible peut tenir n'importe
quel stand. Un écart d'appréciation est signalé *après* résolution, jamais comme
cause bloquante avant. Le besoin minimum, lui, la regarde — mais seulement dans
son volet par typologie décrit plus bas, jamais dans ses quatre bornes globales.

Une cause n'est pas toujours un manque d'animateurs : `GET /api/feasibility`
remonte aussi les **contraintes ad hoc contradictoires**
(`CONTRAINTES_AD_HOC_CONTRADICTOIRES`, `contrainteIds` nommant les exceptions
concernées). Elles sont refusées à la saisie, donc ce que cette cause désigne a
été enregistré avant ce contrôle, ou importé en un bloc — rien d'autre ne le
signalerait. Toujours `CRITIQUE`, et classée avant les sous-effectifs : elle se
corrige en supprimant une ligne que l'utilisateur a saisie lui-même.

Quatre bornes pour le besoin minimum, la plus grande étant retenue. Chacune
découle d'une règle que `LegalConstraints` applique vraiment, si bien que le
plancher et le solveur ne peuvent pas se contredire :

- **pic simultané** — des sièges ouverts au même instant : personne n'en tient
  deux à la fois ;
- **pic avec pause** — le même pic sur des intervalles prolongés de la pause
  légale entre deux vacations, soit le nombre exact d'animateurs distincts
  qu'exige la journée la plus chargée ;
- **charge horaire** — les heures de la **semaine ISO la plus chargée**,
  rapportées à ce qu'un animateur peut y travailler ;
- **rotation sur les jours** — les jours-personne de cette même semaine,
  rapportés aux six jours qu'un animateur peut y travailler (art. L3132-1).

Les deux dernières se prouvent **semaine par semaine**, jamais sur le total de
l'événement : les heures de la semaine 28 ne peuvent être couvertes que par les
personnes qui travaillent la semaine 28, et la capacité inemployée d'une semaine
que l'événement effleure n'en dote aucune autre. La capacité d'une semaine est
elle-même plafonnée deux fois — par le plafond hebdomadaire, et par les jours
que l'événement y occupe (six au plus, de dix heures au plus) : une semaine de
deux jours ne vaut pas 48 h de travail par personne. La borne de **rotation**
manquait purement et simplement : une journée exigeant cent personnes, répétée
sept jours d'affilée, ne se tient pas à cent, puisque personne ne peut
travailler les sept jours. Sur une édition réelle de seize jours, ces deux
corrections font passer le plancher de 102 à 117, pour un événement réellement
couvert par 153 personnes —
l'écart restant tient aux compétences, au repos quotidien et à l'équité, qu'un
plancher ne peut pas prouver.

Le besoin d'une journée est lui-même le plus grand du pic avec pause et de ses
heures divisées par le plafond quotidien : une journée de 600 heures-personne
demande au moins soixante personnes, quelle que soit sa forme.

À côté de ces bornes, `minimumAvecIndisponibilites` corrige le plancher par les
**indisponibilités déclarées** : une journée dont seuls 70 % du vivier connu
sont libres exige un vivier de `besoin / 0,7`. C'est une **projection**, pas une
borne — elle suppose les recrues à venir aussi indisponibles que les personnes
déjà connues — d'où son champ distinct ; `indisponibilitesDeclarees` dit si
quiconque a déclaré quoi que ce soit, ce qui distingue « tout le monde est
libre » de « personne n'a répondu ».

Les mêmes quatre bornes sont déclinées **par typologie** — `parCompetence` dans
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
  `max(1, effectif du segment ouvert)` par couple — l'effectif de la fenêtre
  d'ouverture, ou `effectifMin` à défaut, moitié sur une vacation de couverture
  de pause —, le nombre de sièges d'un groupe **est** son plancher : un groupe
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
créneaux, y pèse quarante. Et `effectifMin` est l'effectif **configuré pour la
fenêtre** du groupe — celui de la fenêtre d'ouverture quand elle en nomme un,
sinon le minimum du stand —, pas le plancher du groupe : sur une vacation de
couverture de pause (`couverturePause: true`) les sièges sont générés à la
moitié, arrondie au supérieur, et c'est `siegesRequis` qui porte le plancher
réellement applicable.

Les listes sont bornées (20 postes détaillés par animateur, 100 couples
stand × créneau), `postesNonDetailles` et `totalCompetencesRares` disant ce qui
n'est pas montré. Les animateurs sans aucune affectation ne sont pas listés :
ils ne peuvent pas être un point de défaillance.

## Pauses légales

`GET /api/pauses` répond à la question que la règle des six heures ne pose
qu'en creux : **où tombent les pauses**, pour qui, de quelle heure à quelle
heure, sur quel stand, et qui est là pour relayer. Le solveur décide qui tient quel siège ; il ne planifie jamais la
pause de vingt minutes que l'art. L3121-16 doit dès que le travail atteint six
heures (trente minutes à 4 h 30 pour un mineur, art. L3162-3). Quand
l'organisateur déclare la pause **prise sur le poste** (`pauseSurPoste` des
paramètres légaux), une séquence peut dépasser ce seuil, et quelqu'un doit
organiser le relais : c'est ce que ce rapport donne. Sans la déclaration, une
telle séquence est un écart que les contraintes refusent ; un plan
persisté peut encore en porter une, et elle est rapportée de la même façon,
signalée comme non couverte.

Une lecture du plan persisté, jamais une résolution ; sous les paramètres
légaux **courants**, pas ceux du dernier solve — la question est « avec ce que
je déclare aujourd'hui, que reste-t-il à organiser ». Rien de persisté : un
rapport vide, `journeesAnalysees` à zéro, pas d'erreur.

- `journees[]` — une entrée par animateur et par jour, seulement celles qui
  doivent une pause ou en listent une planifiée ; triées par date puis par
  nom. Chacune porte `mineur` (le barème appliqué), `sequences[]` et
  `pausesPlanifiees[]`.
- `sequences[]` — les séquences de travail ininterrompu de la journée, sur les
  fenêtres **effectives** des sièges, fusionnées quand le trou entre deux est
  plus court que la pause légale (un trou de quinze minutes n'est pas une
  pause). Chaque séquence porte `pausesDues[]` : `debut` et `fin` (la pause
  telle que la rotation la pose), `heureLimite` (au plus tard, la séquence
  atteint le seuil à cet instant ; `debut` ne le dépasse jamais), `dureeMinutes`
  (20 ou 30), `standId`/`standNom` (le stand tenu pendant la pause), `relais[]`
  (les collègues qui y tiennent un siège pendant toute la pause),
  `relaisDisponible` et `simultanee`. Une séquence de douze heures et demie en
  doit deux : la seconde tombe six heures vingt après la première.
- **La rotation** : sur chaque stand et chaque jour, les pauses sont posées
  l'une après l'autre, au plus tard possible — la première traitée est celle
  dont l'heure limite est la plus tardive, la suivante se termine où la
  précédente commence —, si bien qu'une seule personne du stand est sortie à
  la fois. Une pause ne peut pas non plus commencer trop tôt : ce qui reste de
  la séquence après elle doit tenir sous le seuil. Quand les fenêtres ne
  laissent pas la place (trois personnes sur douze heures d'affilée), la pause
  est posée au début de sa fenêtre et `simultanee` le dit : deux personnes
  sortent en même temps, le plan n'est pas modifié.
- `pausesPlanifiees[]` — les trous d'au moins la pause légale entre deux
  séquences, typiquement la relève de midi : ce que la grille donne déjà.
- `journeesAnalysees`, `pausesDues`, `relaisManquants`, `pauseSurPoste`,
  `message` — les compteurs de l'écran et la phrase qui les résume.

Une date de naissance inconnue est lue au barème adulte : les contraintes
l'ignorent des deux côtés, et la pause adulte est celle qui est toujours due.

La même lecture sert le planning individuel : l'espace de l'animateur
(`pauses[]` de `GET /api/espace-animateur/{jeton}`, sur le plan **publié**),
son PDF (« Pause de 18:20 à 18:40 (20 min) », sous la vacation qui la doit) et
son flux de calendrier (dans la description de l'événement).

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
fuité. Elle ne touche **pas** au jeton d'abonnement ICS, qui est une clé
distincte (voir la section suivante).

**L'espace montre le plan publié**, pas le plan de travail : ce qu'un animateur
voit est ce qu'on lui a envoyé. Un échange validé, une réparation appliquée ou
un solve ne déplacent pas son espace tout seuls — il faut publier. `publieLe`
nul signifie que rien n'a encore été communiqué : les postes sont alors vides,
et l'interface le dit. Les suggestions d'échange se calculent sur ce même plan
publié : on ne troque que ce qu'on nous a annoncé.

**Foire fermée** : soumissions et annulations sont refusées **côté serveur**
(`400`) ; la vue `foireOuverte` ne sert qu'à l'afficher. Une borne datée est
appliquée de la même façon — une date vérifiée seulement à l'affichage ne
serait pas une borne. `foireOuvreLe` distingue « pas encore ouverte » de
« fermée » : c'est le même booléen côté serveur et l'inverse pour qui le lit,
et un animateur à qui l'on annonce que c'est terminé deux semaines avant
l'ouverture ne revient pas. Il est nul quand la foire est ouverte, et nul aussi
quand la fenêtre est passée — il n'y a alors rien à attendre. Le planning reste
visible et téléchargeable.

## Abonnement ICS permanent

`GET /api/abonnements/{token}/planning.ics` — le planning **publié** de
l'animateur, reconstruit à chaque appel, en `text/calendar`. C'est l'adresse
qu'on donne une fois à son agenda et qu'il rappelle tout seul : une
republication apparaît à la synchronisation suivante, sans rien à refaire. Le
téléchargement direct de l'espace (`.../planning.ics`) reste ce qu'il est — une
photo à un instant — et l'abonnement s'ajoute à côté, il ne le remplace pas.

**Un préfixe à part, et un jeton à part.** Les deux vont ensemble :

| | Jeton d'espace (`access_token`) | Jeton d'abonnement (`abonnement_token`) |
| --- | --- | --- |
| Chemin | `/api/espace-animateur/{jeton}/…` | `/api/abonnements/{token}/planning.ics` |
| Ce qu'il faut en plus | la session ouverte par code e-mail | **rien** |
| Ce qu'il ouvre | tout l'espace : planning, PDF, échanges, disponibilités, et une écriture | **un document**, en lecture |
| Rotation | `POST /api/animateurs/{id}/token` (admin) | `POST /api/espace-animateur/{jeton}/abonnement` (l'animateur, depuis son espace) |

Un client d'agenda ne porte aucun cookie et ne sait pas répondre à un défi
d'authentification : exiger la session ici rendrait l'abonnement impossible.
Plutôt que d'élargir ce que le jeton d'espace permet — il deviendrait, sur une
route au moins, une preuve d'accès complète et durable au planning nominatif
sans second facteur —, l'abonnement a sa propre clé, révocable séparément. Voir
[décision 0019](decisions/0019-jeton-et-chemin-dedies-pour-l-abonnement-ics.md)
et [`securite.md`](securite.md).

Le préfixe `/api/abonnements/` n'existe que pour cette route, et c'est
délibéré : c'est le motif d'URL qu'un proxy d'accès (Pangolin et consorts)
excepte de son authentification, et il ne doit désigner que ça.

Deux comportements à connaître :

- **jeton inconnu ou révoqué → `404`**, dont le corps est une phrase fixe
  (« Abonnement inconnu ou révoqué ») : un client d'agenda affiche ce qu'on lui
  donne, et cette phrase est la même dans les deux cas, donc elle n'aide ni à
  deviner un jeton, ni à savoir si celui-ci a existé ;
- **rien de publié → `200` et un calendrier vide**, jamais `404`. Un client
  d'abonnement qui rencontre un `404` répété désactive le flux ou alerte son
  propriétaire ; l'animateur qui s'était abonné avant la première publication
  devrait alors se réabonner sans que rien ne le lui dise. « Rien de prévu pour
  vous, pour l'instant » est une réponse valable.

La réponse porte `Cache-Control: private, no-cache` : le document est nominatif
et ne doit ni transiter par un cache partagé, ni être rejoué après une nouvelle
publication.

`POST /api/espace-animateur/{jeton}/abonnement` (session requise) révoque
l'adresse en cours et en crée une neuve — `{"abonnementToken": "…"}`. Le jeton
d'espace, lui, ne bouge pas : le lien déjà imprimé sur un PDF continue de
fonctionner. La vue de l'espace porte le jeton courant dans
`abonnementToken`.

## Échanges

`GET` / `PUT /api/echanges/configuration` — la fenêtre de la foire, sur le
modèle de `parametres_collecte` (#291) : un interrupteur et deux dates
facultatives.

| Champ | Rôle |
| --- | --- |
| `foireOuverte` | L'interrupteur, et le maître : une fenêtre datée mais fermée n'accepte rien |
| `debut` / `fin` | Bornes facultatives, incluses. Vide = pas de borne de ce côté |
| `ouverteAujourdhui` | En lecture seule : l'interrupteur **et** la date du jour dans les bornes. Distinct de `foireOuverte` pour que l'écran puisse dire « ouverte, mais hors période » au lieu d'afficher un interrupteur qui prétend le contraire |

Une fin antérieure au début est refusée (`400`) **avant** l'écriture : une
configuration refusée laisse la précédente en place, jamais la moitié d'une
nouvelle.

Une demande naît `EN_ATTENTE_CIBLE` et n'entre dans la file décidable
(`PROPOSEE`) qu'une fois **acceptée par le collègue ciblé**. Un refus du
collègue est terminal : l'admin n'arbitre jamais.

Chaque demande est prévalidée contre les contraintes dures mais **enregistrée
quel que soit le verdict** — la réponse porte `prevalidationOk` et les
contraintes non respectées.

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

## Déclaration de disponibilités

L'animateur déclare lui-même, depuis son espace, ses **jours d'indisponibilité**
et ses **souhaits** de typologies. Rien n'est écrit dans le référentiel à la
soumission : la déclaration attend une décision explicite de l'admin.

C'est la **première route en écriture** ouverte depuis l'espace animateur, qui
est public. Trois bornes, et aucune n'est cosmétique :

| Borne | Ce qu'elle empêche |
| --- | --- |
| Fenêtre de collecte **fermée par défaut** | La foire au planning (V42) est ouverte tant que personne ne l'a fermée, par compatibilité ; une route qui écrit ne s'ouvre pas par omission |
| **Une seule proposition en attente par animateur** (index unique partiel, V59) | Le volume : renvoyer mille fois laisse une ligne |
| `ESPACE_DECLARATION_MAX_ENVOIS` par animateur et par fenêtre | Le rythme : une boucle d'écritures et de notifications à la vitesse du réseau. Au-delà, `429` + `Retry-After` (voir `securite.md`) |

Un jour hors des dates de l'événement et une typologie inconnue sont refusés
(`400`) — l'IHM n'offre que les bonnes valeurs, cette vérification ne rejette
donc que des charges utiles fabriquées à la main. Tant qu'aucun créneau
n'existe, aucun jour n'est imposé : collecter les disponibilités **avant** que
la grille existe est précisément le cas d'usage.

### Espace animateur

| Route | Effet |
| --- | --- |
| `GET /api/espace-animateur/{jeton}/disponibilites` | État de la fenêtre, jours de l'événement, typologies, ce que le référentiel dit de moi aujourd'hui, ma proposition en attente et mon historique. **Lisible fenêtre fermée** : on doit pouvoir relire ce qu'on a déclaré |
| `POST /api/espace-animateur/{jeton}/disponibilites` | Déclare ; **remplace** la proposition en attente, s'il y en avait une. Répond la même vue que le `GET` |

Le formulaire s'ouvre sur la proposition en attente s'il y en a une, sinon sur
ce que le référentiel contient : une page blanche voudrait dire « je suis
disponible tous les jours », ce que personne n'a voulu déclarer.

`collecteOuverte` est faux dans **deux** situations opposées pour qui lit :
pas encore commencée, et terminée. `collecteDebut` les sépare — une fenêtre
fermée dont le début est à venir se lit « revenez à partir du 15 », pas « la
collecte est fermée ». Un animateur à qui on annonce la fin d'une collecte qui
commence dans deux semaines n'y revient pas.

### Écran admin

| Route | Effet |
| --- | --- |
| `GET /api/disponibilites` | Toutes les déclarations de l'édition, les plus récentes d'abord |
| `GET /api/disponibilites/configuration` | La fenêtre de collecte (fermée tant que rien n'a été décidé) |
| `PUT /api/disponibilites/configuration` | Ouvre ou ferme ; `prevenirAnimateurs` déclenche **en plus** l'envoi des invitations |
| `POST /api/disponibilites/{id}/application` | Applique la proposition **entière** sur la fiche, via `AnimateurService` — donc `dataStale` se déclenche |
| `POST /api/disponibilites/{id}/refus` | Ne touche à rien ; le commentaire est lu par l'animateur dans son espace |

**Tout ou rien** : l'admin accepte ou refuse la proposition entière, jamais
ligne à ligne. Le désaccord se règle hors application — l'animateur renvoie une
version corrigée tant que la fenêtre est ouverte.

L'application **réclame la décision avant d'écrire la fiche**. Dans l'autre
sens, deux admins qui tranchent en même temps — l'un refuse pendant que l'autre
applique — laissaient le second écraser les jours et les souhaits de l'animateur
*puis* recevoir un `409` : le référentiel avait bougé pour une déclaration
enregistrée REFUSEE. Réclamer d'abord transforme la course en un `409` net,
fiche intacte.

`prevenirAnimateurs` est une **action, pas un réglage** : il n'est jamais
renvoyé dans la réponse. L'invitation est indispensable au premier tour et
lassante à la réouverture après correction, donc elle se coche à chaque fois
qu'on la veut. La réponse porte alors `invitation` : envoyés, sans adresse,
échecs. Le lien pointe l'onglet de déclaration de chaque espace, construit par
`ApplicationLinks` — jamais par concaténation.

**Le `PUT` est atomique.** Sans `PUBLIC_URL` — un déploiement supporté —, une
ouverture cochée « prévenir » répond `400` **sans rien enregistrer** : la
collecte reste fermée. Elle s'enregistrait avant que l'invitation n'échoue, et
l'admin lisait une erreur sur une collecte déjà ouverte, qui acceptait déjà des
déclarations. Ce qui peut encore échouer après l'écriture est **par animateur**
et se lit dans `invitation`, jamais levé : une adresse manquante va dans
`sansEmail`, un jeton d'espace manquant dans `echecs` avec sa cause — dire
« sans adresse » à qui en a une envoie l'opérateur corriger ce qui n'est pas
cassé.

Les compétences ne se déclarent **pas** ici : une compétence auto-déclarée
alimente des contraintes *dures*, et l'enjeu de validation n'est pas le même.

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
d'environnement. La nuance a compté : le profil `%prod` fournissait un token
Cloudflare de repli, et `mesureAudience` y était donc vrai sans que
`CLOUDFLARE_WEB_ANALYTICS_TOKEN` soit posé, parce que la mesure tournait bel et
bien. Ce repli a été retiré (voir `docs/observabilite.md`).
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

**Un stand porte toujours au moins une typologie** (#343) : `POST` et `PUT
/api/stands` répondent `400` sur `typologiesProposees` vide, en nommant le
stand — sans typologie, seuls les polyvalents pourraient le tenir. Le contrôle
est à l'écriture seulement : une ligne ancienne sans typologie se lit et se
solve encore, et se voit refuser à sa prochaine sauvegarde, jusqu'à ce qu'on
lui en donne une.

Un créneau requiert une date, une heure de début et une heure de fin : sans
l'une des trois, l'écriture répond `400` plutôt que d'aller heurter la colonne
`NOT NULL`. En revanche une fin **antérieure ou égale** au début est acceptée —
c'est ainsi que s'écrit un créneau franchissant minuit (20:00→00:00 dure quatre
heures), et lui seul lit une fenêtre de stand datée du lendemain.

### La grille de créneaux lue comme un tout

Un créneau se crée un par un, ou en **série** : une règle — les créneaux d'une
journée type, et les jours qu'elle couvre — en ajoute des dizaines d'un coup,
comme le fait déjà l'outil MCP `creer_creneaux_recurrents`.

```json
POST /api/creneaux/recurrence/apercu?mode=AMPLITUDES
{ "jours": "JOURS_SEMAINE", "dateDebut": "2026-07-06", "dateFin": "2026-07-19",
  "joursSemaine": ["MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY"],
  "exclusions": ["2026-07-14"],
  "fenetres": [ { "heureDebut": "09:00", "heureFin": "12:00" },
                { "heureDebut": "14:00", "heureFin": "18:00" } ] }
→ { "nombreGeneres": 18, "creneaux": [ … ], "controle": { … } }
```

`jours` reprend le sélecteur des horaires de stand (`TOUS`, `JOURS_SEMAINE`,
`PLAGE`, `DATES`) ; chaque fenêtre porte **début et fin**, un créneau étant
l'amplitude du jour elle-même. `/recurrence/apercu` n'écrit rien et rend le
verdict sur la grille **qui résulterait** ; `POST /api/creneaux/recurrence`
ajoute les créneaux — jamais ne remplace — et rend le verdict sur la grille
obtenue. Une règle qui répète un créneau existant ressort en `DOUBLON`,
sévérité `ERREUR`. `400` sur une règle mal formée.

**Le mode est déclaré par édition.** Une grille contient soit des
`AMPLITUDES` (journées à découper), soit des `VACATIONS` (vacations finales,
résolues telles quelles) ; les données seules ne le prouvent pas, et le verdict
en dépend — un chevauchement le même jour est une faute entre amplitudes et la
forme normale de vacations décalées. La déclaration vit dans
`parametresDecoupage.modeGrille` (défaut `AMPLITUDES`) et sert de valeur par
défaut à tout appel qui n'en nomme pas — le paramètre `mode` des routes
ci-dessous comme l'argument des outils MCP.

Elle a **son propre écrit**, `PUT /api/parametres-decoupage/mode-grille`, corps
`{ "modeGrille": "AMPLITUDES" }` : le mode se décide sur la page Créneaux
tandis que le reste des réglages de découpage s'édite sur Paramètres, et un
onglet Paramètres resté ouvert le ramènerait en arrière en enregistrant sa
charge utile. `PUT /api/parametres-decoupage` ignore donc ce champ et conserve
le mode enregistré. `GET` le rend, comme les autres.

Générer le découpage bascule la déclaration en `VACATIONS` **côté serveur** :
un assistant qui appelle `generer_decoupage` puis `valider_creneaux` lit bien
des vacations, et non des amplitudes dont chaque chevauchement de relais
passerait pour une faute de saisie.

Un `mode` mal orthographié répond `400` — l'énumération est convertie par le
service, pas par le conteneur, dont l'échec serait un `404`.

`GET /api/creneaux/controle?mode=` rend le verdict : les anomalies de la
grille (`severite` `ERREUR` ou `AVERTISSEMENT`, `type`, `date`, `message`),
les anomalies d'ouverture des stands, et le rapport de faisabilité — `null`
sans stand ou sans créneau. `GET /api/creneaux/diagnostic` décrit la grille et
suggère un mode (`modeProbable`, `modeCertain`) sans jamais trancher : seules
des familles ou des créneaux de couverture de pause prouvent des vacations.

**Dériver la grille des stands.** Quand les horaires des stands existent déjà
— règles saisies, grille importée — la grille de créneaux découle d'eux au lieu
d'être tapée une seconde fois : chaque heure où un stand ouvre ou ferme est une
coupure, chaque tranche entre deux coupures où au moins un stand est ouvert
devient un créneau. `POST /api/creneaux/derivation/apercu` et
`POST /api/creneaux/derivation`, corps
`{ dateDebut, dateFin, heureFermeture, dureeMinimaleMinutes, remplacer }` :
`heureFermeture` donne une fin aux fenêtres « jusqu'à la fermeture » (`00:00`
pour minuit, le dernier créneau franchit alors minuit ; une fenêtre « jusqu'à la
fermeture » qui commence après cette heure court jusqu'au lendemain) ; une
tranche plus courte que `dureeMinimaleMinutes` (défaut 15) rejoint celle qui la
suit — celle d'avant à défaut — et un trou plus court qu'elle est refermé plutôt
que de couper le créneau en deux ; les `coupures` rendues sont celles que la
grille porte encore, jamais celles que ces fusions ont effacées ;
`remplacer` juge — et, à l'écriture, remplace — toute la grille, le planning
résolu partant avec elle comme pour le découpage, sinon les créneaux s'ajoutent.
Seuls les jours qu'un stand déclare « ouvert sur ces fenêtres » participent :
un stand qui ne dit rien d'un jour est ouvert quand les autres le sont, un stand
qui ne déclare que des fermetures n'a pas de borne de départ connue. La réponse
porte les créneaux, les `coupures` (jour, heure, premiers stands responsables),
les `joursSansFenetre`, et le verdict `controle`. `400` sans aucune fenêtre.

Le **découpage** lui-même : `GET /api/decoupage/preview` rend les vacations
que les paramètres produiraient, `POST /api/decoupage/generer` (corps vide,
`204`) les écrit à la place des amplitudes et efface le planning résolu.

### Modification concurrente

Deux sessions — deux onglets, un compte partagé — peuvent ouvrir la même fiche
et l'enregistrer l'une après l'autre. Sans rien, la seconde écrase la première
en silence. Chaque ligne des six référentiels (stands, animateurs, créneaux,
typologies, emplacements, ajustements manuels) porte donc `modifieLe`, la date
de sa dernière écriture, que le `GET` renvoie et que le formulaire **renvoie
telle quelle** à l'enregistrement :

- `modifieLe` égal à celui de la ligne → écrit ;
- `modifieLe` différent → `409` **sans rien écrire**, corps
  `{ message, code: "MODIFICATION_CONCURRENTE", modifieLe }` où `modifieLe`
  est la valeur courante de la ligne. C'est le `code` qui distingue ce 409
  de celui du solveur occupé : l'IHM y répond par un choix, **Recharger**
  (rien n'est écrit, le formulaire se ferme sur la version de l'autre
  session) ou **Écraser quand même** (le même payload repart sans
  `modifieLe`). Fermer ce dialogue sans choisir — Échap, un clic à côté — ne
  fait ni l'un ni l'autre : la saisie reste à l'écran. Le message ne date pas
  le conflit, le corps porte l'instant et le navigateur l'affiche à l'heure du
  lecteur ;
- `modifieLe` absent ou `null` → écrit sans contrôle. C'est ainsi qu'un
  import, un script, un ancien client ou un écrasement délibéré disent qu'ils
  n'ont rien à comparer.

L'édition en masse envoie un `PUT` par ligne, chacun avec le `modifieLe` de sa
ligne : une ligne modifiée ailleurs est refusée seule, les autres passent, et
le compte rendu la nomme — en disant de refaire la sélection, parce que le
dialogue tient encore les valeurs qu'il a ouvertes et renverrait le même
horodatage périmé. La **grille des ouvertures** porte la même précondition,
une par stand (`stands[].modifieLe`, la valeur que la grille a lue) : c'est
l'écriture qui réécrit le plus, elle ne pouvait pas être la seule à écraser en
silence. Les outils MCP `modifier_*` acceptent le même
horodatage en argument facultatif, lu dans la vue de `consulter_*` ; omis, la
fusion qu'ils font relit la ligne juste avant d'écrire, ce qui revient au
même. L'import de scénario et l'import CSV ne le portent pas : ils remplacent
ou fusionnent un référentiel entier, pas une fiche qu'un écran a affichée.

La vérification **fait partie de l'écriture** : un seul ordre SQL, dont le
`ON CONFLICT DO UPDATE` porte la précondition et ne renvoie aucune ligne
quand elle échoue. Une lecture suivie d'une écriture laisserait passer deux
enregistrements séparés d'une milliseconde — la perte de modification que ce
mécanisme existe pour empêcher — et coûterait un aller-retour de plus par
enregistrement. La création est vérifiée de la même façon : un `POST` dont
l'identifiant est déjà pris répond `409` au lieu de remplacer la ligne
existante en silence.

Ce n'est **pas un verrou** : deux sessions peuvent toujours modifier, la
seconde est simplement prévenue. Avec un seul compte partagé, le serveur ne
sait rien de plus (#294). La persistance d'un solve, qui réécrit le
référentiel tel qu'il était au départ du calcul, ne touche pas `modifieLe` :
sinon toute fiche ouverte entrerait en conflit après chaque résolution. Voir la
[décision 0023](decisions/0023-modification-concurrente-par-horodatage.md).

### Avertissements de saisie

Ces incohérences ne sont **pas** des refus : une indisponibilité hors des
bornes de l'édition, une date de naissance qui rend l'animateur mineur pendant
l'événement, un créneau qui déborde l'amplitude d'ouverture de tous les stands,
un horaire de stand qui ne recoupe aucun créneau. Un organisateur a le droit de
les saisir — la doctrine du dépôt est de ne
refuser que ce qui est *certainement* insatisfiable — alors la ligne est
écrite et l'avertissement voyage **dans la réponse de succès**, à côté de
l'entité :

```json
POST /api/animateurs  →  200
{
  "animateur": { "id": "A-12", "prenom": "…", … },
  "avertissements": [
    { "type": "MINEUR_PENDANT_EVENEMENT",
      "message": "L'animateur A-12 est mineur du 2026-07-08 au 2026-07-08 et devient majeur le 2026-07-09 …" }
  ]
}
```

`POST` et `PUT /api/animateurs` répondent `{ animateur, avertissements }`,
`POST` et `PUT /api/creneaux` répondent `{ creneau, avertissements }` — le
créneau y porte son `id` généré, comme avant — et `POST` et `PUT /api/stands`
répondent `{ stand, avertissements }`. Les autres référentiels répondent
toujours l'entité nue : une clé `avertissements` absente veut dire « rien à
signaler ». `avertissements` est toujours présent sur ces trois ressources, vide
quand tout va bien.

| `type` | Ce qui l'a déclenché |
| --- | --- |
| `INDISPONIBILITE_HORS_EVENEMENT` | Un jour d'indisponibilité hors de l'intervalle `[premier créneau, dernier créneau]` : il ne recouvre aucun créneau, donc il ne protège personne. |
| `INDISPONIBILITE_JOUR_SANS_CRENEAU` | Un jour d'indisponibilité **dans** l'intervalle mais sur une date qui ne porte aucun créneau (un lundi de relâche entre deux week-ends). La date n'est pas fautive, mais l'espace animateur ne l'affichera pas et la première déclaration de disponibilités appliquée l'effacera — l'import CSV refuse la ligne pour ce même motif, la saisie manuelle avertit. |
| `MINEUR_PENDANT_EVENEMENT` | L'animateur est mineur au moins un jour de l'événement. Le message dit à partir de quelle date il devient majeur, ou qu'il est mineur du début à la fin. Émis **seulement quand l'écriture pose ou change la date de naissance** : être mineur est un état légitime, pas une faute de saisie, et le redire à chaque modification d'une autre colonne apprend à ignorer le message. |
| `CRENEAU_HORS_OUVERTURE_STANDS` | Aucun stand n'est ouvert une seule minute du créneau : il n'ouvrira aucun poste. |
| `CRENEAU_DEBORDE_OUVERTURE_STANDS` | Le créneau commence avant que tous les stands n'ouvrent, ou finit après qu'ils ont tous fermé, d'au moins un quart d'heure. |
| `STAND_FENETRE_SANS_EFFET` | Une fenêtre du stand — d'une règle étendue comme d'une exception datée — ne recoupe aucun créneau de son jour : elle est enregistrée et ne change rien. Le message cite jusqu'à cinq jours. |
| `STAND_EXCEPTION_HORS_EVENEMENT` | Une exception datée du stand nomme un jour hors de l'intervalle `[premier créneau, dernier créneau]` — le lendemain d'un créneau qui franchit minuit est exclu de ce compte : le domaine lit vraiment cette date. |
| `STAND_JAMAIS_OUVERT` | Après l'écriture, le stand n'est ouvert sur aucun créneau : il n'ouvrira aucun poste. |

Les trois avertissements de stand ne sont émis **que si l'écriture touche à
l'horaire** (règles, fermetures, ouvertures) : renommer un stand qui n'a jamais
ouvert nulle part n'est pas le moment de le dire, et l'édition en masse envoie
un `PUT` par ligne. La grille de saisie (`PUT /api/ouvertures-stands/grille`)
n'en émet aucun : ses fenêtres suivent les créneaux par construction.

**Les bornes se dérivent, elles ne se stockent pas** : une `Edition` ne porte ni
dates ni drapeau « en cours », donc l'événement court du premier au dernier
créneau de l'édition — la même dérivation que la collecte des disponibilités
(*Déclaration de disponibilités*). Une édition **sans aucun créneau** n'a donc
pas de bornes du tout, et n'émet aucun avertissement : inventer une borne là
serait un faux positif sur l'écran même par lequel une nouvelle édition
commence.

Les horaires des stands sont lus **récurrences développées** (voir *Horaires
d'un stand*), et développées contre le créneau qu'on est en train d'écrire —
sinon un créneau créé sur une date neuve verrait fermés tous les stands
programmés par règle, et l'avertissement crierait au loup sur la saisie la plus
banale.

**Seul ce que l'écriture change est signalé.** Sur une modification, chaque
règle se lit contre la fiche telle qu'elle était avant : un jour
d'indisponibilité déjà enregistré et une date de naissance non touchée ne
produisent rien. C'est ce qui rend l'édition en lot tenable — elle émet un
`PUT` par ligne portant la fiche **entière** fusionnée, donc sans cette
comparaison, cocher trente bénévoles pour leur ajouter une compétence
finirait sur un message citant chaque mineur de la sélection. À la création,
tout est nouveau et tout est examiné.

**Ce que les messages ne disent pas.** Un avertissement nomme un animateur par
son **identifiant seul** — jamais par ses nom et prénom, jamais par sa date de
naissance. Ce n'est pas une économie de caractères : le navigateur recopie
chaque message dans un journal `localStorage` qui survit à la déconnexion
(`rgpd.md` §7), et l'identité d'un mineur n'a rien à y faire. L'IHM va plus
loin sur ce seul type : la phrase `MINEUR_PENDANT_EVENEMENT` est affichée mais
**pas journalisée**, parce qu'en nommant le jour des 18 ans elle laisserait
recalculer la date de naissance.

Non couverts, délibérément : l'import de scénario (un fichier vaut pour
lui-même et son rapport d'impact dit déjà ce qu'il change) et les outils MCP,
qui continuent d'appeler les écritures nues et de répondre l'entité seule.
L'édition en lot, elle, est couverte : elle émet un `PUT` par ligne et les
avertissements du lot sont regroupés en un seul message.

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
verrouillée renvoie `200` sans doublon. Ils ne répondent pas à la même
question : la contrainte ad hoc dit, *avant* le calcul, où placer ou ne pas
placer quelqu'un et vaut pour toute résolution ; le verrouillage fige, *après
coup*, ce que la dernière résolution a produit sur une partie du plan, et ne
vaut que pour le plan enregistré (voir
[`domaine.md`](domaine.md#verrouillage-partiel-du-planning)).

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

### Import CSV des animateurs

Deux endpoints, même corps, et un seul écrit :

| Méthode | Chemin | Effet |
| --- | --- | --- |
| `POST` | `/api/animateurs/import-csv/analyse` | Lit le fichier et rend le rapport ligne par ligne. **N'ouvre aucune transaction** |
| `POST` | `/api/animateurs/import-csv` | Relit le même fichier, rejoue toutes les vérifications, puis écrit les lignes acceptées en **une** transaction |
| `GET` | `/api/animateurs/import-csv/exemple` | Rend le CSV d'exemple versionné (`text/csv`, en pièce jointe). Lecture pure, hors édition — le fichier est une ressource du classpath, pas une donnée |

Le corps est identique aux deux : `{ fileName, content, mapping,
replaceAnimateurs, replaceJoursIndisponibles }`. `content` est le texte du
fichier, `mapping` associe un champ d'animateur à un **index de colonne**
(`null` = champ absent du fichier, donc jamais écrasé). Le `mapping` **absent**
(`null`) demande la correspondance proposée d'après les en-têtes ; un mapping
**fourni mais entièrement vide** est pris tel quel — c'est ainsi que l'écran
repart d'une correspondance blanche sans se la voir redessiner.

**L'écriture reçoit le fichier, pas le rapport**, et c'est le point : elle ne
fait confiance à rien de ce que le navigateur a affiché. Un rejeu de requête ou
un corps fabriqué à la main repasse par les mêmes contrôles.

La réponse est le même rapport dans les deux cas — `applied` distingue l'aperçu
de l'écriture, `rows[]` porte une entrée par ligne du fichier avec son numéro de
ligne **physique**, son verdict (`CREATED`, `UPDATED`, `REJECTED`), ses motifs
de refus et ses avertissements.

Refus qui portent sur le fichier entier, avant toute écriture :

| Cas | Réponse |
| --- | --- |
| Fichier vide, `.xlsx` / `.xls` / `.ods`, binaire, plus de 1 000 000 caractères ou 5 000 lignes | `400`, message disant quoi faire |
| Fichier non encodé en UTF-8 (accents déjà illisibles) | `400`, message nommant l'encodage et citant un extrait |
| Mapping ne désignant ni identifiant, ni prénom, ni nom | `400` |
| Remplacement complet demandé alors qu'une ligne est rejetée | `400` |
| Aucun créneau dans l'édition | `409` |
| Une résolution tient le solveur de l'édition | `409`, comme toute écriture de référentiel |

L'endpoint `/exemple` sert
`src/main/resources/scenarios/festival-realiste-animateurs.csv` tel quel,
sous le nom `festival-realiste-animateurs.csv` : les neuf colonnes lues,
remplies avec les 153 animateurs du scénario anonymisé du même nom. C'est ce
que le bouton « Télécharger un fichier d'exemple » de l'écran d'import
récupère — servi depuis le classpath plutôt que copié dans le bundle, pour
qu'il n'existe qu'un seul fichier à garder juste.

Le fichier n'est **jamais écrit sur disque**. Règles métier détaillées dans
[`import-export.md`](import-export.md#import-csv-des-animateurs).

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

Chaque jour porte aussi ses créneaux (`jours[].creneaux`), et chaque cellule
une entrée par créneau (`creneaux[]` : `creneauId`, `effectif`, `partiel`) —
l'effectif **configuré** que le stand y demande, `null` s'il y est fermé.
`partiel` signale des fenêtres qui ne suivent pas les bornes du créneau, ou un
effectif qui change en cours de créneau ; `effectif` est alors le plus haut.

### Saisie en grille

`PUT /api/ouvertures-stands/grille` écrit l'horaire des stands sous la forme
même du classeur de l'organisateur : **un entier par créneau**, vide pour
fermé. Le corps ne porte que les stands modifiés, chacun avec **toutes** ses
cases :

```json
{ "stands": [ { "standId": "BOURSE",
                "cellules": [ { "creneauId": 1, "effectif": 2 },
                              { "creneauId": 2, "effectif": null } ] } ] }
```

Un stand envoyé voit ses règles et ses exceptions **remplacées en totalité**
par ses cases : les créneaux consécutifs à même effectif deviennent une fenêtre
datée, la fenêtre qui atteint la fin du jour est laissée ouverte (« jusqu'à la
fermeture »), un jour sans aucune case est une fermeture explicite — rien dire
d'un jour voudrait dire ouvert. Le compactage (ci-dessus) ramène ensuite les
jours répétés en règles quand il peut prouver l'équivalence, et laisse le reste
daté : la réponse le dit stand par stand (`regles`, `exceptions`, `compacte`,
`raison`). Les bornes suivent les cases : `effectifMin` est la plus petite
valeur saisie, `effectifMax` la plus grande, et une fenêtre ne nomme son
effectif que s'il diffère du minimum.

**Familles de relais.** Une grille découpée en plusieurs familles porte une
variante de chaque vacation par famille, et un stand n'est apparié qu'à une
seule d'entre elles ([`domaine.md`](domaine.md#familles-de-créneaux)). Les
cellules des autres familles sortent avec `horsFamille: true`, effectif `null` :
l'écran les rend inertes, et une case envoyée pour l'une d'elles est ignorée —
l'écrire rouvrirait un jour que ce stand ne tient jamais.

`400` sur un créneau inconnu, un effectif nul (« laissez la case vide pour
fermer ») ou un stand inconnu — l'identifiant vient du corps, pas du chemin.
`409` pendant une résolution. Les stands sont tous convertis et validés
d'abord, puis écrits **en une seule transaction** : le rapport annonce ce que
la base contient, jamais un lot à moitié écrit.

### Import de la grille des stands

Le second import **partiel** du produit, calqué sur celui des animateurs
([décision 0022](decisions/0022-import-de-la-grille-des-stands.md)) : la matrice
de l'organisateur — une ligne par stand, une colonne par jour et par créneau, un
entier par case — analysée, prévisualisée, rejouée, jamais écrite sur disque.

| Méthode | Route | Rôle |
| --- | --- | --- |
| `POST` | `/api/stands/import-grille/analyse` | Lit le fichier et rend le rapport : chaque colonne et le créneau où elle se pose, une ligne par stand. **N'ouvre aucune transaction** |
| `POST` | `/api/stands/import-grille` | Relit le même fichier, rejoue toutes les vérifications, puis réécrit les stands acceptés en **une** transaction |
| `GET` | `/api/stands/import-grille/exemple` | Rend la grille actuelle de l'édition en CSV (`text/csv`, en pièce jointe) : ses créneaux en colonnes, ses stands en lignes — réimportable telle quelle |

Corps des deux `POST` : `{ fileName, content }`. Deux lignes d'en-tête (les
dates, les cellules fusionnées d'un tableur laissant les suivantes vides, puis
les bandes `10:00-12:00`) ou une seule (`2026-07-08 10:00-12:00`) ; dates ISO
ou `08/07/2026`, heures `10:00`, `10h`, `10h30`, `24:00` lu comme minuit — une
minute à un seul chiffre (`9:5`) est refusée plutôt que complétée. Le fichier
d'exemple écrit ses bandes `10h00-12h00` : Excel convertit `13:00-16:00` en la
date `30/11/1999 13:16:00`, ce qui détruit la bande, alors qu'il laisse la
forme en `h` telle quelle.

- Une colonne se pose sur **tous** les créneaux de même date et mêmes heures —
  une grille décalée en familles en porte un par famille, et `columns[].creneaux`
  dit combien ; une colonne sans créneau est **ignorée et listée**
  (`columns[].reason`), pas un motif de refus. Un créneau sans colonne
  (`creneauxAbsents`) **garde la case actuelle** de chaque stand importé :
  l'import ne réécrit que ce que le fichier dit. Un stand qui n'ouvre qu'une
  partie d'un tel créneau en ressort élargi au créneau entier, un
  `warnings[]` le nommant.
- Une ligne nomme un stand par son identifiant, sinon par son nom exact (casse
  et accents indifférents) ; un nom porté par deux stands, ou un stand inconnu,
  rejette la ligne — l'import ne crée pas de stand. Une case vide, `-` ou `0`
  ferme ; un entier est un effectif ; tout autre contenu rejette la ligne en
  nommant la colonne.
- Un stand accepté est réécrit comme depuis la grille de saisie
  (`PUT /api/ouvertures-stands/grille`) : règles par compaction, bornes
  dérivées. Les stands absents du fichier ne sont pas touchés ; rien n'est
  jamais supprimé.

`400` sur un classeur `.xlsx`, un fichier vide ou trop gros, une édition sans
créneau, un fichier dont aucune colonne ne correspond ; `409` pendant une
résolution.

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
