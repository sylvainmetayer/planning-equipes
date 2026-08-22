# API REST

Toutes les ressources sont exposées par le service Quarkus sous `/api`, au format
JSON sauf mention contraire.

## Documentation OpenAPI

| Méthode | Chemin | Description |
| --- | --- | --- |
| `GET` | `/q/openapi` | Spécification OpenAPI générée automatiquement par Quarkus (YAML par défaut) |
| `GET` | `/q/swagger-ui` | Interface Swagger UI pour explorer et tester les endpoints |

Le service expose aussi un serveur MCP (`/mcp`), pour piloter l'application en
langage naturel depuis un assistant IA : voir [`mcp.md`](mcp.md).

## Configuration

| Méthode | Chemin | Description |
| --- | --- | --- |
| `GET` | `/api/config` | Configuration d'observabilité lue par le frontend au démarrage (DSN Sentry/Bugsink, token Cloudflare Web Analytics) — voir [`observabilite.md`](observabilite.md) |
| `POST` | `/api/debug/test-exception` | Lève systématiquement une exception de test, pour vérifier le suivi d'erreurs (bouton « Exception back » de l'onglet Débogage) — voir [`observabilite.md`](observabilite.md) |

## Authentification

Toute l'API `/api/*` est réservée à la session admin (issue #165), avec trois
exceptions volontaires : l'espace animateur (`/api/espace-animateur/*`, dont le
jeton d'URL est la clé d'accès), les routes de session ci-dessous et
`/api/config`. Le serveur MCP garde sa propre clé d'API (voir
[`mcp.md`](mcp.md)). Un appel non authentifié répond `401` — jamais une
redirection HTML.

| Méthode | Chemin | Description |
| --- | --- | --- |
| `POST` | `/j_security_check` | Form login Quarkus : corps `application/x-www-form-urlencoded` avec `j_username` / `j_password`. Succès : cookie `planning-session` chiffré + `302` vers `/api/auth/me` (que le navigateur suit) ; échec : `401` (jamais de page HTML). Compte unique `admin`, mot de passe via `ADMIN_PASSWORD`. `429` + `Retry-After` après cinq échecs consécutifs depuis la même adresse, **y compris avec le bon mot de passe** (voir [`securite.md`](securite.md)) |
| `GET` | `/api/auth/me` | Statut de session : `{ "authentifie": bool, "nom": "admin" \| null }` — accessible anonymement |
| `POST` | `/api/auth/logout` | Supprime le cookie de session (`204`), idempotent |
| `GET` | `/api/mcp/statut` | `{ configuree, header }` : si une clé MCP est configurée côté serveur, et l'en-tête qui la porte. **Jamais la clé** — c'est ce qui permet à la page MCP d'avertir « MCP inutilisable en l'état » au lieu de laisser paramétrer un client qui n'obtiendra que des 401 |
| `POST` | `/api/mcp/cle` | Échange `{ motDePasse }` (celui de l'admin) contre `{ cle, pangolinAccessTokenId, pangolinAccessToken }` — les deux derniers `null` sauf si `PLANNING_MCP_PANGOLIN_ACCESS_TOKEN_ID`/`PLANNING_MCP_PANGOLIN_ACCESS_TOKEN` sont positionnées côté serveur. `401` mot de passe faux ou absent, `404` mot de passe bon mais aucune clé configurée, `429` après cinq échecs (blocage de 5 min, que le bon mot de passe ne lève pas). La session admin ne suffit pas : la clé donne un accès complet en écriture et **survit à la session** d'où elle a été copiée, donc la révélation est liée à quelqu'un présent au clavier |

### Derrière un reverse proxy qui termine le TLS

La redirection émise par une connexion réussie est fabriquée par Quarkus en
**absolu**, à partir du schéma de la requête reçue. Derrière un proxy qui
termine le TLS, cette requête arrive en clair sur l'origine : la redirection
repartait donc vers `http://<hôte>/api/auth/me`, que le navigateur refuse de
suivre depuis une page `https` (« blocage du contenu mixte actif »), rendant la
connexion impossible.

`quarkus.http.proxy.proxy-address-forwarding` (variable `PROXY_ADDRESS_FORWARDING`,
**activé par défaut**) fait suivre à Quarkus les en-têtes `X-Forwarded-*` du
proxy : schéma, hôte et « la requête est-elle chiffrée » correspondent alors à
ce que le visiteur a réellement demandé — redirections en `https`, et cookie de
session marqué `Secure`. Sans proxy en amont, aucun en-tête `X-Forwarded-*`
n'arrive et rien ne change.

Deux réglages Quarkus le complètent au besoin, par variable d'environnement :

| Variable | Usage |
| --- | --- |
| `QUARKUS_HTTP_PROXY_TRUSTED_PROXIES` | Épingle les adresses des proxys dont les en-têtes `X-Forwarded-*` sont acceptés. À renseigner si l'origine est joignable sans passer par le proxy, sinon n'importe quel client peut annoncer le schéma et l'adresse de son choix |
| `QUARKUS_HTTP_PROXY_ENABLE_FORWARDED_HOST` | À activer si le proxy réécrit `Host` au lieu de transmettre celui du visiteur (l'hôte est alors lu dans `X-Forwarded-Host`) |

### Authentification par en-tête (remote user, facultative)

Pour un déploiement derrière un proxy d'accès qui authentifie lui-même ses
visiteurs (Pangolin et consorts) et transmet l'adresse retenue. **Désactivée
par défaut** : le form login reste le mode normal, et les deux modes coexistent
— une origine atteinte directement, ou un proxy mal configuré, laisse toujours
`/login` utilisable.

| Propriété | Défaut | Rôle |
| --- | --- | --- |
| `planning.auth.remote-user.enabled` (`REMOTE_USER_ENABLED`) | `false` | Active le mode. |
| `planning.auth.remote-user.header` (`REMOTE_USER_HEADER`) | `Remote-Email` | En-tête portant l'adresse authentifiée par le proxy. |
| `planning.auth.remote-user.secret-header` (`REMOTE_USER_SECRET_HEADER`) | `Remote-Auth-Secret` | En-tête portant le secret partagé. |
| `planning.auth.remote-user.secret` (`REMOTE_USER_SECRET`) | vide | Secret partagé. **Obligatoire** quand le mode est actif. |
| `planning.auth.remote-user.admin-email` (`REMOTE_USER_ADMIN_EMAIL`) | vide | La seule adresse qui obtient le rôle admin. |

**Le secret n'est pas une option.** Un en-tête est une affirmation, pas une
preuve : sans lui, quiconque atteint l'origine sans passer par le proxy devient
administrateur en envoyant une ligne d'en-tête — et une origine joignable en
direct est l'état ordinaire des choses (un port publié pour déboguer, une
seconde ingress, un réseau interne), pas une exotisme. Activer le mode sans
secret **fait donc échouer le démarrage** au lieu de laisser en place une
configuration silencieusement grande ouverte. Le secret doit voyager sur un
en-tête que le proxy **écrase inconditionnellement** en entrée, faute de quoi
un client peut le forger.

Qui est qui :

- l'adresse `admin-email` obtient le rôle admin, exactement comme le form login
  (même principal `admin`, mêmes droits) ;
- **toute autre adresse est un animateur**, identifié par l'e-mail de sa fiche.
  L'attestation du proxy prend alors la place du code à 6 chiffres de l'espace
  animateur : cet e-mail est précisément ce que le code prouve, donc l'affirmer
  depuis le proxy qui a déjà authentifié la personne établit le même fait une
  étape plus tôt. Le lien (jeton) reste nécessaire, et l'adresse doit
  correspondre à la fiche qu'il désigne — l'attestation d'un animateur n'ouvre
  donc pas l'espace d'un collègue dont il aurait ramassé le lien ;
- une adresse qui n'est ni l'admin ni le propriétaire du jeton présenté n'obtient
  rien.

Si `admin-email` est aussi l'adresse d'un animateur, l'admin l'emporte et la
personne perd l'accès à son espace : la collision est signalée par un
avertissement au démarrage plutôt que découverte à l'usage.


## Planning

| Méthode | Chemin | Description |
| --- | --- | --- |
| `GET` | `/api/planning/sample` | Jeu d'exemple construit depuis `scenario.yml` (non résolu) |
| `GET` | `/api/planning/volumetrie` | Volumétrie réelle du prochain solve : nombre d'animateurs (value count Timefold), de postes à pourvoir (entity count Timefold, un par siège requis et non par stand) et de contraintes ad hoc actives ; tout à 0 si aucune donnée de référence n'est chargée |
| `POST` | `/api/solve` | Résout un `PlanningFestival` envoyé en JSON (synchrone). Suit exactement le même chemin qu'un solve asynchrone — capture du plan précédent, persistance, diagnostic, écran Contraintes, ligne de KPI, annonce de fin — voir *Le pipeline de résolution* |
| `POST` | `/api/solve/analyze` | Analyse un planning : score et contraintes violées |
| `POST` | `/api/planning/reset` | Vide l'**édition courante** (stands, créneaux, animateurs, affectations, contraintes) sans charger de scénario. Les autres éditions ne sont pas touchées (bouton « Vider la base de données » de l'onglet Débogage) |
| `GET` | `/api/planning/persisted` | Planning persisté en base, lecture seule (utilisé par les vues calendrier, qui ne déclenchent jamais de résolution) |
| `GET` | `/api/planning/persisted/count` | Nombre d'affectations persistées |
| `GET` | `/api/planning/persisted/resolution` | Groupe de créneaux et date de la dernière résolution persistée (`solved: false` si aucune résolution n'a encore eu lieu), plus `derniereModificationDonnees` : date de la dernière modification d'une donnée de référence (`null` si aucune depuis le démarrage du serveur) |

## Instantanés de plan

Un seul plan est persisté à la fois par édition (`poste_affectation` est
réécrite en entier à chaque solve). Les instantanés sont la seule persistance
capable d'en garder plusieurs : ils mettent un plan de côté avant qu'il ne soit
écrasé.

| Méthode | Chemin | Description |
| --- | --- | --- |
| `GET` | `/api/planning/snapshots` | Liste les instantanés de l'édition courante, du plus récent au plus ancien (métadonnées seules, sans le contenu) |
| `GET` | `/api/planning/snapshots/{id}` | Un instantané avec ses affectations |
| `POST` | `/api/planning/snapshots` | Enregistre le plan actuellement persisté. Corps : `{ "libelle": "…" }`. `409` s'il n'y a aucun plan à enregistrer |
| `POST` | `/api/planning/snapshots/{id}/restore` | Réécrit `poste_affectation` et `planning_resolution` depuis l'instantané, puis re-dérive l'analyse de contraintes du plan restauré. `409` **sans rien écrire** si des références ont disparu (liste `referencesManquantes` : `stand:…`, `creneau:…`, `animateur:…`) |
| `DELETE` | `/api/planning/snapshots/{id}` | Supprime un instantané |
| `GET` | `/api/planning/snapshots/comparables` | Les instantanés de **toutes** les éditions, pour le comparateur A/B — la seule lecture d'instantanés non cloisonnée |
| `GET` | `/api/planning/snapshots/compare?base=&variante=` | Comparaison A/B (issue #70). Chaque côté vaut un id d'instantané ou `courant` (le plan actuellement persisté). `404` si un instantané cité n'existe pas |

Un instantané est pris **automatiquement avant chaque solve** (`automatique:
true`, libellé « Avant solve du … ») : c'est le vrai filet anti-écrasement,
celui qui protège l'utilisateur qui n'a pas pensé à enregistrer. Ces
instantanés-là sont purgés au-delà des N derniers
(`planning.snapshots.automatiques-conservees`, 5 par défaut) ; ceux créés à la
main ne le sont jamais.


## Historique des KPI

Une mesure par résolution terminée, écrite par le job de solve lui-même
(jamais par cette API). Volontairement **non cloisonné par édition** et sans
clé étrangère : comparer 2025 à 2026 est l'objet même de la table, et une
ligne doit survivre à la suppression de l'édition qu'elle décrit — c'est
pourquoi le libellé de l'édition y est dénormalisé.

Aucune donnée nominative : l'équité est stockée en dispersion agrégée des
heures (total, moyenne, écart-type, min, max), jamais par animateur.

| Méthode | Chemin | Description |
| --- | --- | --- |
| `GET` | `/api/kpi/historique` | Toutes les mesures, toutes éditions confondues, de la plus récente à la plus ancienne |
| `DELETE` | `/api/kpi/historique/{id}` | Supprime une mesure |

## Replanification incrémentale

| Méthode | Chemin | Description |
| --- | --- | --- |
| `POST` | `/api/solve/incremental/async?seconds=&enFile=` | Replanification incrémentale (issue #86) : repart du plan persisté, épingle tout ce qu'un changement tardif n'a pas invalidé, ne recalcule que le reste. `202` avec le job, `409` si le solveur est déjà occupé — sauf `enFile=true`, qui la met alors en file (voir *Résolution asynchrone*) |

Corps **facultatif** — le périmètre rouvert *en plus* de ce que les changements
ont invalidé :

```json
{ "animateurIds": ["ANIM-12"], "jours": ["2026-08-22"], "standIds": ["STAND-A"] }
```

Les trois axes sont une **union** : un poste est rouvert dès qu'il correspond à
l'un d'eux. Sans corps, le périmètre est exactement ce que les changements
tardifs ont invalidé, plus les postes restés vides.

Sans `seconds`, le serveur applique son propre budget court (60 s), et non la
durée configurée pour une résolution complète. Le job échoue explicitement s'il
n'y a aucun plan persisté à reprendre : la replanification part d'un résultat,
pas de rien.

Le résultat du job (`GET /api/jobs/{id}`) porte `diagnostic` (comme une
résolution complète), `statistiques` (`postesFiges`, `postesLiberes`,
`postesLiberesManuellement`, `postesNouveaux`) et `changements` : le diff des
équipes par stand × créneau, avec les noms de chaque côté. Voir
[`domaine.md`](domaine.md#replanification-incrémentale) pour la mécanique.

## Comparateur A/B

`GET /api/planning/snapshots/compare` confronte deux plans et **ne déclenche
aucune résolution** : il lit des KPI déjà mesurés, jamais un score recalculé.
Chaque instantané emporte ses KPI au moment de la capture (colonne
`plan_snapshot.kpi`, migration V49).

Chaque côté (`base`, `variante`) désigne soit un id d'instantané, soit
`courant` pour le plan actuellement persisté dans l'édition de la requête.
La réponse porte :

- `base` / `variante` : `snapshotId` (`null` pour le plan courant), `libelle`
  (`null` pour le plan courant — le nommer est le travail de l'IHM, dans la
  langue de l'utilisateur), `editionId`, `editionNom`, `creeLe`, `kpi`, et
  `kpiRecalcule` ;
- `editionsDifferentes` : les deux côtés appartiennent à des éditions
  différentes, donc à des référentiels différents. Ce n'est **pas** une
  anomalie — depuis la suppression des groupes de créneaux, une variante *est*
  une autre édition — mais l'IHM doit le dire ;
- `volumetriesDifferentes` : les deux plans n'ont pas le même nombre de postes,
  une partie des écarts vient donc de la taille du problème ;
- `diffViolations` : nombre de violations par contrainte de chaque côté.
  `null` d'un côté signifie **non mesuré**, jamais zéro.

`kpiRecalcule` marque le **mode dégradé** : un instantané antérieur à la
migration V49 n'a pas de KPI stockés, ils sont alors recalculés depuis son
contenu, dans l'édition qui l'a produit. Couverture et volumétrie restent
exactes ; les violations ne sont pas mesurées.

## Le pipeline de résolution

Les quatre façons de lancer un solve — synchrone (`POST /api/solve`), asynchrone
depuis un problème envoyé, asynchrone depuis le référentiel, et incrémentale —
passent toutes par `SolvePipeline`, et font donc **toujours** la même
chose :

1. capturer le plan sur le point d'être écrasé (issue #138) ;
2. construire le problème — la seule étape qui varie vraiment ;
3. résoudre, persister, diagnostiquer ;
4. alimenter l'écran Contraintes (`ConstraintAnalysisStore`) ;
5. écrire la ligne d'historique KPI, avec la durée réelle (issue #89) ;
6. annoncer la fin, si l'édition l'a demandé (`mailFinResolution`).

Ce n'était pas le cas : le pipeline était écrit quatre fois et la copie du solve
synchrone s'arrêtait après « persister ». L'écran Contraintes restait alors sur
l'analyse du solve **précédent** — il affichait des violations qui ne
décrivaient plus le plan persisté — et aucun KPI n'était écrit. Rien ne levait
d'erreur : le seul symptôme était un écran qui mentait.
`SolveSynchronePipelineTest` verrouille les deux bouts.

Une seule chose reste propre à chaque appelant : ce qui doit tenir le solveur
pour pouvoir l'arrêter en cours de route. Un job de fond en a besoin
(`Solver#terminateEarly`), un appel synchrone non — l'appelant attend déjà.

## Résolution asynchrone

La résolution complète dure plusieurs minutes : l'IHM lance un job, reste
navigable et notifie à la fin.

Le verrou « un solveur à la fois » est **porté par le serveur**, pas par le
navigateur : un seul job de résolution ou d'analyse peut tourner à la fois pour
toute l'application. Toute autre session (autre navigateur, navigation privée,
autre onglet) voit le même job actif et le même temps écoulé via
`GET /api/jobs/active`, et se voit refuser un second lancement en `409 Conflict`
(le corps de la réponse contient le job en cours).

| Méthode | Chemin | Description |
| --- | --- | --- |
| `POST` | `/api/solve/async?seconds={n}` | Démarre une résolution en tâche de fond (`202`, ou `409` si le solveur est occupé) |
| `POST` | `/api/solve/analyze/async?seconds={n}` | Démarre une analyse en tâche de fond (`202`, ou `409` si le solveur est occupé) |
| `GET` | `/api/jobs` | Liste des jobs |
| `GET` | `/api/jobs/active` | Job en cours (`200`) ou solveur libre (`204`) |
| `GET` | `/api/jobs/file` | Les tâches en attente, dans l'ordre où elles démarreront |
| `GET` | `/api/jobs/stream` | Flux `text/event-stream` : job actif **et** file, poussés à chaque transition |
| `GET` | `/api/jobs/{id}` | État et résultat d'un job |
| `DELETE` | `/api/jobs/{id}` | Retire une tâche de la file, ou supprime un job terminé (`409` si le job tourne encore) |

### Suivi poussé : `GET /api/jobs/stream`

Le même état que `/api/jobs/active` **plus** la file, en un seul événement, en
*server-sent events*. C'est ce que suit l'IHM ; les deux requêtes précédentes
restent servies et restent le **repli**, pas un vestige.

Deux noms d'événement :

- `state` — un agrégat complet `{ "active": JobView | null, "file": [JobView] }`.
  Le **premier arrive à la connexion**, sans attendre une transition : un
  solveur au repos n'en produit aucune, et un client qui attendrait resterait
  sur « état inconnu ». Chaque événement est un instantané entier, jamais un
  delta : un client trop lent reçoit le dernier, jamais un retard accumulé.
- `heartbeat` — toutes les 20 s (`planning.jobs.stream.heartbeat`). Il porte aussi
  la ligne de commentaire `:keep-alive`, sans laquelle un proxy inverse
  (Pangolin dans ce déploiement) ferme une connexion silencieuse depuis
  plusieurs minutes. Le commentaire seul n'aurait pas suffi : `EventSource` ne
  l'expose jamais au JavaScript, donc il ne peut pas servir de preuve de vie au
  client — d'où l'événement nommé en plus.

Le flux ne remplace pas le *polling*, il le double : SSE échoue en silence (un
proxy qui bufferise, une coupure qui ne se reconnecte pas) et l'IHM resterait
figée sur un état périmé sans rien signaler. Le navigateur garde donc un poll
lent (30 s) et **reprend la main** après ~45 s sans `state` ni `heartbeat`.

L'authentification est celle de toutes les routes `/api/*` (`authenticated`,
cookie de session) : elle est vérifiée à l'ouverture du flux, et `EventSource`
envoie le cookie de lui-même en *same-origin*. Une session expirée fait donc
échouer l'ouverture — exactement le signal dont le repli a besoin.

### File d'attente

`enFile=true` sur `/api/solve/async/reference-data` ou
`/api/solve/incremental/async` **met la tâche en file** au lieu de la refuser
quand le solveur est occupé : elle démarre d'elle-même dès que la tâche en
cours se termine. C'est ce qui permet de préparer une autre édition pendant un
solve et de planifier le suivant sans rester devant l'écran.

Trois propriétés à connaître :

- **le problème est construit au démarrage effectif**, pas au moment du clic :
  une tâche en file lit le référentiel tel qu'il sera quand son tour viendra —
  on peut donc continuer à préparer l'édition visée entre-temps ;
- **une tâche en file ne tient pas le solveur** : `GET /api/jobs/active`
  continue de renvoyer la tâche en cours, et la saisie reste ouverte sur
  l'édition visée par la tâche planifiée (le verrou de saisie est par édition) ;
- **un doublon en file est refusé en `409`** : même édition et même type
  **déjà planifié**. Le corps porte le job fautif, dont le `status`
  (`RUNNING` / `QUEUED`) dit s'il tourne ou s'il attend — l'IHM en tire deux
  messages distincts. Planifier une résolution de l'édition **en cours de
  calcul** reste au contraire autorisé : le calcul en cours a démarré avant les
  dernières corrections et ne peut pas en tenir compte, en planifier un autre
  est précisément la façon de dire « refais-le avec ce que je viens de
  corriger ».

La file est FIFO. `statut` d'un job : `QUEUED` (en attente, ne tient rien),
`PENDING` (promu, sur le point de démarrer), `RUNNING`, puis `COMPLETED` /
`FAILED` / `CANCELLED` / `INTERROMPU`.

### La file survit au redémarrage

La file et le journal des jobs sont **persistés** (table `solver_job`) : un
redémarrage du serveur ne perd plus les résolutions planifiées. Au démarrage,
elles retournent en file dans leur ordre d'arrivée et la première prend le
solveur d'elle-même.

Ce qui est stocké est l'**intention** (type, budget, édition visée, périmètre
d'une replanification incrémentale), jamais l'état du solveur Timefold. C'est
suffisant justement parce qu'une tâche en file construit déjà son problème au
moment où elle démarre : une file rejouée lit le référentiel du redémarrage,
exactement comme elle aurait lu celui de son tour de file.

Deux conséquences à connaître :

- **le calcul en cours, lui, est perdu.** Un job qui tenait le solveur revient
  en `INTERROMPU` — un état terminal, pour ne pas garder le verrou global
  indéfiniment. Ce qui reste en base est ce qu'un solve précédent y avait écrit.
  Reprendre le calcul lui-même (warm start depuis un instantané) est
  l'objet de l'issue #174 ;
- **le résultat d'un job n'est pas stocké** : un job restauré expose
  `result: null`. Ce qu'il décrivait est déjà en base (plan persisté,
  historique de KPI, instantanés). La rétention d'une heure des jobs terminés
  est inchangée, et s'applique aussi aux lignes en base.

La reprise se désactive avec `planning.jobs.reprise-au-demarrage=false` (voir
[`developpement.md`](developpement.md#réglage-du-solveur)).

Chaque job expose `editionId` et `editionNom`, captés à la soumission :
l'édition dans laquelle il écrit son résultat (voir `docs/editions.md` §5),
que l'IHM affiche et utilise pour ne verrouiller la saisie que sur cette
édition. Il expose aussi `elapsedSeconds`, calculé côté serveur : le temps écoulé
affiché est identique quel que soit le client, son horloge ou son heure de
connexion.

## Contraintes

| Méthode | Chemin | Description |
| --- | --- | --- |
| `GET` | `/api/constraints` | Catalogue métier des contraintes + résultat de la dernière analyse |
| `PUT` | `/api/constraints/{name}` | Active/désactive une contrainte pour le prochain solve |

Corps du `PUT /api/constraints/{name}` :

```json
{ "actif": false }
```

Une désactivation insère une ligne dans `constraint_toggle`, une réactivation
la supprime : c'est tout ce que la table porte (voir
[`contraintes.md`](contraintes.md)).

## Explicabilité par affectation

Explicabilité individuelle (« Pourquoi lui ? »), complémentaire du diagnostic
global de `/api/solve/analyze` : au lieu du score global, ces routes ciblent
un seul `PosteAffectation` du planning envoyé en corps de requête (déjà
résolu — jamais re-résolu ici, contrairement à `/api/solve/analyze`).

| Méthode | Chemin | Description |
| --- | --- | --- |
| `POST` | `/api/postes/{posteId}/explication` | Contraintes violées / respectées pour ce poste, avec le score global |
| `POST` | `/api/postes/{posteId}/simulation-swap?animateurId={id}` | Simule le remplacement de l'occupant actuel du poste par `animateurId` : score avant/après, delta, et contraintes violées avant/après pour ce même poste |

Les deux renvoient `404` avec `{ "message": "…" }` si `posteId` ou
`animateurId` ne figure pas dans le planning envoyé. « Respectée » signifie
seulement qu'aucune violation n'a été trouvée pour ce poste précis, pas que la
contrainte s'applique nécessairement à lui — l'IHM ne doit pas la présenter
comme un satisfecit positif. `simulation-swap` ne persiste rien : c'est une
simulation en mémoire, à usage d'aide à la décision uniquement.

## Foire au planning (échanges de créneaux)

Un animateur propose d'échanger un de ses créneaux avec un collègue depuis son
espace en libre-service ; rien n'est appliqué au planning sans une validation
admin explicite (issue #165). Voir
[`domaine.md`](domaine.md#demandes-déchange) pour le modèle.

### Espace animateur (public, par jeton)

Seules routes de l'API accessibles sans session admin : le jeton d'accès —
le lien imprimé sur le planning PDF individuel — est la clé, et résout à lui
seul l'animateur **et** son édition (l'en-tête `X-Edition-Id` n'est pas lu
ici). Un jeton inconnu répond `404 { "message": "…" }`, jamais `401`.

| Méthode | Chemin | Description |
| --- | --- | --- |
| `GET` | `/api/espace-animateur/{jeton}` | Identité, planning personnel (postes + coéquipiers, lecture seule) et collègues avec qui échanger |
| `GET` | `/api/espace-animateur/{jeton}/demandes` | Ses demandes d'échange, tous statuts, la plus récente d'abord |
| `GET` | `/api/espace-animateur/{jeton}/demandes-recues` | Les demandes qui ME ciblent — celles qui attendent mon accord avant d'atteindre l'admin, plus leur historique |
| `POST` | `/api/espace-animateur/{jeton}/demandes-recues/{id}/accord` | J'accepte une demande qui me cible : elle passe `EN_ATTENTE_CIBLE` → `PROPOSEE` (file admin) et l'admin est notifié |
| `POST` | `/api/espace-animateur/{jeton}/demandes-recues/{id}/refus` | Je décline : terminal (`REFUSEE_CIBLE`), le demandeur est prévenu, l'admin n'arbitre jamais |
| `GET` | `/api/espace-animateur/{jeton}/collegues/{collegueId}/postes` | Les postes d'un collègue (créneaux et stands, sans coéquipiers) — la source du sélecteur « son créneau que je veux en échange » d'un échange dirigé. `400` **foire fermée** (`@FoireOpenRequired` ; le sélecteur n'existe alors pas côté interface), `404` collègue inconnu de l'édition — voir [`securite.md`](securite.md#lecture-des-cr%C3%A9neaux-dun-coll%C3%A8gue) |
| `POST` | `/api/espace-animateur/{jeton}/demandes` | Soumet une **liste** de demandes `[{ creneauId, standId, cibleId, motif?, creneauCibleId?, standCibleId? }]`. `creneauCibleId`/`standCibleId` renseignés = échange **dirigé** : le demandeur cède son créneau ET récupère le créneau désigné du collègue (« je te laisse mon lundi, je prends ton mardi »). Chacune est prévalidée contre les contraintes dures (`simulateEchange`/`simulateDirectedEchange`) mais enregistrée quel que soit le verdict ; la réponse porte `prevalidationOk` et `contraintesViolees` (descriptions métier du catalogue). Un mail est envoyé à l'admin (si `planning.mail.admin` est configurée) |
| `POST` | `/api/espace-animateur/{jeton}/demandes/{id}/annulation` | Annule une de **ses** demandes encore en attente (`204` ; `400` si déjà décidée) |
| `GET` | `/api/espace-animateur/{jeton}/planning.pdf` | Son planning individuel en PDF (même document que l'export admin), toujours disponible — foire fermée comprise |
| `GET` | `/api/espace-animateur/{jeton}/planning.ics` | Son planning au format calendrier ICS |
| `POST` | `/api/espace-animateur/{jeton}/code` | Envoie un code d'accès à 6 chiffres (10 min, 5 essais) à l'adresse de la fiche ; répond `{ emailMasque }`. `400` si la fiche n'a pas d'adresse — l'e-mail EST le second facteur. `429` + `Retry-After` au-delà de trois codes **jamais utilisés** dans la fenêtre de 10 min (voir [`securite.md`](securite.md)) : ouvrir la session efface le compteur |
| `POST` | `/api/espace-animateur/{jeton}/session` | Échange `{ "code": "…" }` contre une session de 30 jours, posée dans le cookie HttpOnly `planning-espace` (`Path=/api/espace-animateur`, `SameSite=Strict`, et `Secure` dès que la visite est en HTTPS — `X-Forwarded-Proto` compris) — `204`, ou `400` (code faux, expiré ou épuisé) |

**Authentification de l'espace** : le lien (jeton) ne suffit plus — l'espace
sert le planning en téléchargement, donc toutes les routes ci-dessus **sauf**
`/code` et `/session` exigent aussi la session du cookie `planning-espace`, liée à
l'animateur que le jeton résout ; sans elle, un jeton valide répond `401`
(l'interface affiche alors l'écran du code). La soumission et l'annulation
répondent par ailleurs `400` quand la **foire est fermée** (voir
`/api/echanges/configuration`) : la fermeture est appliquée côté serveur, la
vue (`foireOuverte`) ne sert qu'à l'afficher.

### Échanges (admin)

| Méthode | Chemin | Description |
| --- | --- | --- |
| `GET` | `/api/echanges` | Toutes les demandes de l'édition courante, la plus récente d'abord. Une demande naît `EN_ATTENTE_CIBLE` : elle n'entre dans la file décidable (`PROPOSEE`) qu'une fois acceptée par le collègue ciblé |
| `GET` | `/api/echanges/{id}/impact` | Re-simule la demande contre le planning persisté **actuel** : delta de score, échange croisé ou reprise simple, contraintes dures nouvellement violées. |
| `POST` | `/api/echanges/{id}/acceptation` | Applique l'échange exactement comme simulé (mise à jour chirurgicale de `poste_affectation`), pose deux verrous `ANIMATEUR_CRENEAU` — sur le créneau échangé pour un échange simple, sur le créneau que chacun REÇOIT pour un échange dirigé — et notifie le demandeur par mail. Corps optionnel `{ "commentaire": "…" }`. Le solveur n'est **pas** relancé. |
| `POST` | `/api/echanges/{id}/refus` | Refuse sans rien modifier ; `{ "commentaire": "…" }` est transmis à l'animateur |
| `GET` | `/api/echanges/configuration` | État de la foire : `{ "foireOuverte": true }` (ouverte par défaut) |
| `PUT` | `/api/echanges/configuration` | Ouvre ou ferme la foire pour l'édition courante. Fermée, les espaces animateurs passent en consultation seule (planning visible et téléchargeable, soumissions et annulations refusées côté serveur) |

Sémantique de l'échange : si le collègue ciblé tient aussi un poste sur le
créneau, les deux postes permutent (échange croisé) ; s'il est libre, il
reprend simplement le poste du demandeur. `400` si la demande n'est plus en
attente ; accepter puis refuser la même demande est donc impossible.

## Faisabilité

| Méthode | Chemin | Description |
| --- | --- | --- |
| `GET` | `/api/feasibility` | Diagnostic d'infaisabilité calculé sur les données de référence actuelles, **sans lancer le solveur** |
| `POST` | `/api/what-if` | Même diagnostic, sur une **variante** des données de référence, comparé à celui des données actuelles. Rien n'est écrit |
| `POST` | `/api/what-if/analyze?seconds=n` | Lance un job `ANALYZE` **court** sur cette variante, pour un score comparable. Rien n'est persisté (une analyse ne persiste jamais de plan) ; `409` si le solveur est déjà occupé |

### Simulation « et si ? »

Corps commun aux deux routes — toutes les clés sont facultatives :

```json
{
  "animateursAjoutes": 3,
  "animateursRetires": ["ANIM-12", "ANIM-45"],
  "standsFermes": ["STAND-EXT"],
  "effectifsMin": { "STAND-CENTRAL": 4 }
}
```

Le serveur applique ces mutations à des **copies en mémoire** des données de
référence : aucun appel d'écriture, aucune persistance, la variante meurt avec
la requête. Elle est construite côté serveur et non dans le navigateur pour la
même raison que les résolutions « depuis les données de référence » : le JSON
d'un `PlanningFestival` réel dépasse la taille de corps HTTP admise.

Les animateurs ajoutés sont fictifs, majeurs, toujours disponibles et
polyvalents — hypothèse délibérément **optimiste**, à lire comme telle : un
recrutement réel, limité à deux typologies, aide moins que celui-là.

`POST /api/what-if` répond avec les deux rapports côte à côte, pour lire un
écart et pas seulement un verdict :

```json
{
  "animateurs": 156, "animateursReference": 153,
  "standsOuverts": 64, "standsOuvertsReference": 65,
  "creneaux": 354,
  "reference": { "feasible": true, "…": "…" },
  "simulation": { "feasible": false, "…": "…" }
}
```

Le diagnostic est un simple calcul de capacité (aucune résolution, réponse
immédiate) : l'écran de préparation des données peut donc l'afficher avant même
de lancer une résolution de plusieurs minutes. Les créneaux pris en compte sont
ceux de l'édition, comme pour une résolution.

```json
{
  "feasible": false,
  "manqueAnimateurs": 2,
  "causes": [
    {
      "type": "CRENEAU_SOUS_EFFECTIF",
      "severite": "ELEVE",
      "message": "Le 2026-07-18 12:30-15:30, il manque 2 animateurs pour couvrir Tir à l'arc.",
      "creneauId": 42,
      "date": "2026-07-18",
      "heureDebut": "12:30",
      "heureFin": "15:30",
      "standIds": ["STAND-TIR"],
      "demande": 6,
      "capacite": 4,
      "manque": 2
    }
  ],
  "totalCauses": 7,
  "message": "Ce planning n'est pas réalisable avec les animateurs actuels : …"
}
```

| Champ | Description |
| --- | --- |
| `feasible` | `true` si aucune cause bloquante n'a été détectée |
| `manqueAnimateurs` | Manque le plus élevé constaté sur un créneau (`0` si aucun) |
| `causes` | Causes classées de la plus bloquante à la moins bloquante, **plafonnées aux 10 premières** |
| `totalCauses` | Nombre total de causes **avant** plafonnement (permet d'afficher « +N autres ») |
| `message` | Phrase de synthèse prête à afficher |

Un seul type de cause (`type`) existe :

| Type | Sévérité | Signification |
| --- | --- | --- |
| `CRENEAU_SOUS_EFFECTIF` | `CRITIQUE` si `manque >= demande`, sinon `ELEVE` | Les animateurs disponibles ce jour-là ne suffisent pas à couvrir les postes ouverts sur le créneau |

La compétence (appréciation de l'administrateur) n'entre **pas** dans ce calcul
de capacité : depuis sa bascule en contrainte medium (`appreciationIncompatible`,
voir [`contraintes.md`](contraintes.md)), n'importe quel animateur disponible
peut littéralement être affecté à n'importe quel stand — un écart d'appréciation
est signalé après résolution (score medium, badge calendrier), jamais comme une
cause bloquante avant résolution.

Le tri place les causes `CRITIQUE` avant les `ELEVE`, puis les manques
décroissants.

Le même rapport est également renvoyé, après résolution, dans le champ
`faisabilite` de `GET /api/constraints` : celui-ci reflète les données de la
dernière analyse, alors que `GET /api/feasibility` reflète toujours le
référentiel courant. Comme il s'agit d'une estimation **optimiste** (elle ignore
quel stand précis chaque animateur pourrait tenir), `feasible: true` ne garantit
pas un score dur nul après résolution — voir [`domaine.md`](domaine.md).

## Besoin minimum en animateurs

| Méthode | Chemin | Description |
| --- | --- | --- |
| `GET` | `/api/staffing` | Nombre minimal d'animateurs qu'exigent les stands et créneaux actuels, **sans lancer le solveur** (écran « Besoin en animateurs ») |

Le calcul part des **sièges** qu'une résolution aurait à pourvoir : le problème
est construit exactement comme pour `POST /api/solve/async/reference-data`
(horaires récurrents résolus, familles de relais, effectif réduit pendant les
pauses), si bien que le compte ne peut pas diverger du réel. Trois bornes sont
calculées, la plus grande est retenue dans `minimumTotal` :

| Champ | Borne |
| --- | --- |
| `picSimultane` | sièges ouverts au même instant : personne n'en tient deux à la fois |
| `picAvecPause` | même pic, chaque vacation prolongée de `pauseMinimaleEntreVacationsMinutes` — c'est le nombre **exact** d'animateurs distincts qu'exige la journée la plus chargée |
| `chargeTotal` | heures-personne totales divisées par le plafond hebdomadaire légal × nombre de semaines ISO |

`parJour` détaille chaque journée (`standsOuverts`, `sieges`, `heures`,
`picSimultane`, `picAvecPause`) et `jourCritique` pointe celle qui fixe
`picAvecPause`. Les trois bornes restent **optimistes** : elles ignorent les
compétences et les indisponibilités individuelles. À traiter comme un plancher
de recrutement à dépasser, jamais comme une cible.

```json
{
  "minimumTotal": 122,
  "minimumMajeurs": 87,
  "minimumMineurs": 35,
  "picSimultane": 105,
  "picAvecPause": 122,
  "chargeTotal": 67,
  "borneRetenue": "PIC_AVEC_PAUSE",
  "nombreSemaines": 3,
  "totalDemandeHeures": 9583.75,
  "jourCritique": { "date": "2026-07-10", "jour": 5, "picAvecPause": 122 }
}
```

## Paramètres légaux

| Méthode | Chemin | Description |
| --- | --- | --- |
| `GET` | `/api/parametres-legaux` | Plafonds hebdomadaires de temps de travail |
| `PUT` | `/api/parametres-legaux` | Met à jour ces plafonds |

```json
{ "dureeHebdomadaireMaxMinutes": 2880, "dureeHebdomadaireMaxMineurMinutes": 2100 }
```

Le `PUT` répond **400** avec `{ "message": "…" }` si une valeur dépasse son
plafond d'ordre public : 48 h pour les majeurs (Code du travail art. L3121-20),
35 h pour les mineurs (art. L3162-1). Une valeur inférieure, plus protectrice,
est acceptée.

## Paramètres du solveur

| Méthode | Chemin | Description |
| --- | --- | --- |
| `GET` | `/api/parametres-solveur` | Durée de résolution par défaut (page Solveur) et notification de fin de résolution (page Paramètres) |
| `PUT` | `/api/parametres-solveur` | Met à jour les deux |

```json
{ "dureeResolutionSecondes": 180, "mailFinResolution": false }
```

Persistés côté serveur (et non en `localStorage`) : les mêmes valeurs sont lues
et modifiées depuis n'importe quel navigateur. Le `PUT` porte l'objet
**entier** — n'envoyer qu'un champ remet l'autre à sa valeur par défaut — et
répond **400** avec `{ "message": "…" }` si la durée n'est pas strictement
positive.

`mailFinResolution` (par édition, `false` par défaut) fait écrire à
l'administrateur à la fin de chaque résolution de cette édition : l'édition, le
score et la faisabilité, rien d'autre. L'envoi est *best-effort* et
silencieusement inerte tant qu'aucune adresse n'est configurée
(`MAIL_ADMIN`) ; `GET /api/debug/mail-config` dit laquelle, ou `null`.

## Mentions légales

| Méthode | Chemin | Description |
| --- | --- | --- |
| `GET` | `/api/mentions-legales` | Faits légaux propres au déploiement : éditeur, directeur de publication, hébergeur, contact, responsable de traitement, base légale et durée de conservation |

**Public**, comme `/api/config` : une mention légale que seul un utilisateur
connecté pourrait lire manquerait ses lecteurs — celui qui décide s'il fait
confiance au site, ou l'animateur dont le lien d'accès a expiré et qui cherche
à qui écrire.

Chaque champ vaut la chaîne vide quand la variable d'environnement
correspondante n'est pas renseignée (`LEGAL_EDITEUR`,
`LEGAL_DIRECTEUR_PUBLICATION`, `LEGAL_HEBERGEUR`, `LEGAL_CONTACT`,
`LEGAL_RESPONSABLE_TRAITEMENT`, `LEGAL_BASE_LEGALE`, `LEGAL_CONSERVATION`) :
la page affiche alors ce qui
manque, plutôt qu'un éditeur inventé. Ces informations vivent en configuration
et non dans le code — le dépôt est public, et pour un éditeur personne physique
l'adresse et le numéro d'immatriculation *sont* des données personnelles.

## Éditions

Tout le référentiel est cloisonné par **édition** — une édition complète du
festival, « Année 2025 », « Année 2026 » — avec ses propres stands, animateurs,
typologies, emplacements, paramètres et planning résolu.
Rien ne circule de l'une à l'autre. Voir [`editions.md`](editions.md).

**Le client désigne l'édition qu'il consulte à chaque requête**, via l'en-tête
`X-Edition-Id` :

- absent, ou nommant une édition inconnue → le serveur retombe silencieusement
  sur l'édition marquée `defaut`, jamais une erreur : un onglet resté ouvert
  sur une édition supprimée entre-temps continue de fonctionner ;
- ce n'est donc pas un état global : deux onglets peuvent travailler sur deux
  éditions différentes en même temps.

L'en-tête vaut pour **tous** les endpoints de ce document, à la seule exception
du dump SQL (`/api/database/*`), qui reste une sauvegarde de l'instance entière.

| Méthode | Chemin | Description |
| --- | --- | --- |
| `GET` | `/api/editions` | Liste des éditions |
| `GET` | `/api/editions/courant` | Édition à laquelle *cette* requête a réellement été résolue — la façon dont un client découvre que son `X-Edition-Id` a été ignoré |
| `POST` | `/api/editions` | Crée une édition vide (`{ "id", "nom" }`) ; **400** si l'identifiant est déjà pris |
| `PUT` | `/api/editions/{id}` | Renomme l'édition |
| `POST` | `/api/editions/{id}/dupliquer` | Crée une nouvelle édition (`{ "id", "nom" }`) et y recopie **tout** le référentiel de `{id}` — les résultats de solveur (`poste_affectation`, `planning_resolution`) sont exclus : « 2026 = 2025 moins les affectations » |
| `PUT` | `/api/editions/{id}/defaut` | Désigne l'édition de repli pour les appelants sans en-tête |
| `DELETE` | `/api/editions/{id}` | Supprime l'édition **et tout son référentiel** ; **400** s'il s'agit de l'édition par défaut, de l'édition courante, ou de la dernière restante |

Le verrou « un solveur à la fois » reste global à l'instance, toutes éditions
confondues (voir *Résolution asynchrone*) ; un job écrit son résultat dans
l'édition pour laquelle il a été lancé, même si le client bascule ensuite.

## Référentiels (CRUD)

Même schéma pour chaque référentiel : `GET` (liste), `POST` (création),
`PUT /{id}` (mise à jour), `DELETE /{id}` (suppression). Tout est lu et écrit
dans l'édition désignée par `X-Edition-Id` (voir ci-dessus) : deux éditions
peuvent porter les mêmes identifiants métier sans se marcher dessus.

| Ressource | Chemin |
| --- | --- |
| Stands | `/api/stands` |
| Créneaux | `/api/creneaux` |
| Animateurs | `/api/animateurs` |
| Typologies de jeux | `/api/typologies` |

Les typologies de jeux sont un référentiel comme les autres, pas un enum figé
côté serveur : `stands.typologiesProposees` et
`animateurs.competences` référencent des `id` de `/api/typologies` (contrainte
`FOREIGN KEY` en base). `POST`/`PUT /api/stands`|`/api/animateurs` répondent
**400** si un `id` de typologie référencé n'existe pas encore, et
`DELETE /api/typologies/{id}` répond **400** si la typologie est encore
utilisée par au moins un stand ou animateur.

Un item de `/api/typologies` porte `{ id, label, ninja }`. `ninja` désigne la
typologie des profils polyvalents : `POST`/`PUT` avec `ninja: true` retire
automatiquement le drapeau de la typologie qui le portait (au plus une à la
fois) — voir [`contraintes.md`](contraintes.md#typologie-ninja-et-buffer-de-polyvalents).

### Horaires d'un stand

Un stand porte son planning d'ouverture sur deux niveaux, et `GET /api/stands`
les rend tels quels — sans expansion, c'est la vue que l'IHM édite :

- `horaires` : les **règles récurrentes**, `{ mode, jours, joursSemaine,
  dateDebut, dateFin, dates, fenetres, motif }`. `mode` vaut `OUVERTURE` ou
  `FERMETURE`, `jours` vaut `TOUS` (défaut), `JOURS_SEMAINE`, `PLAGE` ou
  `DATES`, et seuls les champs que ce sélecteur utilise sont lus ;
- `indisponibilites` / `ouvertures` : les **exceptions datées**, qui priment sur
  les règles pour le seul jour qu'elles nomment.

Dans les deux cas, `heureFin` est **nullable** et vaut alors « jusqu'à la
fermeture » : la fenêtre court jusqu'à la fin du créneau évalué. Voir
[`domaine.md`](domaine.md#horaires-récurrents) pour l'arbitrage complet.

`POST`/`PUT /api/stands` répondent **400** si une règle est incohérente (aucune
fenêtre, heure de fin antérieure à l'heure de début, sélecteur sans les données
qu'il exige) ou si deux règles de même portée, portant sur des jours qui se
croisent, se contredisent (l'une ouverture, l'autre fermeture) — il n'y aurait
pas de gagnant non arbitraire.

| Méthode | Chemin | Description |
| --- | --- | --- |
| `POST` | `/api/stands/compactage-horaires?appliquer=false` | Réécrit les fenêtres datées répétées en règles équivalentes. `appliquer=false` (défaut) est un **essai à blanc** : rien n'est écrit, le rapport décrit ce qui *serait* fait |
| `POST` | `/api/stands/compactage-horaires?appliquer=true` | Idem, et persiste les stands compactés |

Le rapport porte `{ applique, standsCompactes, fenetresAvant, fenetresApres,
stands[] }`, une ligne par stand : `{ standId, fenetresAvant, reglesApres,
exceptionsApres, ecartMinutes, compacte, raison }`. Un stand n'est réécrit que si
les règles proposées reproduisent ses propres segments ouverts ; sinon `compacte`
est `false` et `raison` dit pourquoi.

`gapMinutes` compte les minutes d'ouverture en désaccord (au pire sur un
créneau) : `0` dans le cas général, `1` quand un ancien `23:59` est devenu la
fermeture réelle du jour. Ce cas a une conséquence visible : un stand absent
toute la journée ne génère plus le poste d'une minute que ce `23:59` laissait
derrière lui. Voir
[`domaine.md`](domaine.md#horaires-récurrents).

### Visualisation des ouvertures

| Méthode | Chemin | Description |
| --- | --- | --- |
| `GET` | `/api/ouvertures-stands` | Grille stand × jour des ouvertures réellement en vigueur, plus les anomalies à relire (écran « Ouvertures des stands ») |

Lecture seule, aucune résolution déclenchée — le pendant, pour les horaires, de
ce que `GET /api/feasibility` est pour la capacité en animateurs. La grille est
construite **à partir des postes que le solveur recevrait** (les stands sont
résolus, les créneaux ceux du groupe actif) : l'écran valide donc la donnée
réelle, pas une seconde interprétation de la même saisie.

```
{ jours: [{ date, jour, heureDebut, heureFin, minutes, nombreCreneaux }],
  stands: [{ standId, nom, effectifMin, minutesOuvertes, postes,
             jours: [{ date, etat, source, fenetres, minutesOuvertes,
                       minutesAmplitude, postes }] }],
  standsJamaisOuverts, postesTotal,
  anomalies: [{ type, standId, standNom, date, message }] }
```

- `etat` vaut `OUVERT_TOTAL`, `OUVERT_PARTIEL` ou `FERME` — la part de
  l'amplitude du jour que le stand couvre ;
- `source` vaut `DEFAUT`, `REGLE` ou `EXCEPTION` : quelle couche a décidé de ce
  jour-là, ce qui permet de remonter à la saisie fautive ;
- `fenetres` sont les plages ouvertes en heures réelles, fusionnées et bornées
  aux créneaux (les vacations d'un même jour se chevauchent par construction :
  une journée continue doit se lire comme une seule plage) ;
- `anomalies` porte trois types : `STAND_JAMAIS_OUVERT` (aucun poste sur tout le
  groupe), `FENETRE_SANS_EFFET` (une fenêtre saisie qui ne recoupe aucun créneau
  de son jour) et `SEGMENT_TROP_COURT` (une plage ouverte trop courte pour être
  un vrai créneau de travail — la signature du contournement `23:59`). Aucune ne
  bloque une résolution.

Contraintes ad hoc (pas de mise à jour, on supprime et on recrée) :

| Méthode | Chemin |
| --- | --- |
| `GET` | `/api/contraintes-ad-hoc` |
| `POST` | `/api/contraintes-ad-hoc` |
| `DELETE` | `/api/contraintes-ad-hoc/{id}` |

Le `POST` répond `400` avec un message explicite quand la contrainte déclare
une contradiction : une même paire d'animateurs ne peut pas être à la fois en
`INCOMPATIBILITE` et en `AFFINITE` (issue #80).

### Verrouillages du planning

Parties du planning validées par l'utilisateur et que le solveur ne doit plus
modifier (voir [`domaine.md`](domaine.md#verrouillage-partiel-du-planning)).
Comme les contraintes ad hoc, un verrou est un état : on ne le met pas à jour,
on le supprime et on le recrée.

| Méthode | Chemin |
| --- | --- |
| `GET` | `/api/verrouillages` |
| `POST` | `/api/verrouillages` |
| `DELETE` | `/api/verrouillages/{id}` |

`GET` renvoie les verrous de **tous** les groupes de créneaux, du plus récent
au plus ancien ; seuls ceux du groupe actif sont appliqués par la résolution.

Corps du `POST` : `type` (`ANIMATEUR`, `STAND`, `JOUR` ou `CRENEAU`) et la
**seule** cible correspondante — `animateurId`, `standId`, `jour` (date ISO) ou
`creneauId`. `raison` est optionnelle, `groupeCreneauId` vaut par défaut le
groupe actif et `id` un UUID généré. Une cible déjà verrouillée sur le même
groupe renvoie `200` sans créer de doublon ; un type sans cible correspondante,
ou une cible inconnue, renvoie `400 {"message": "..."}`.

```bash
curl -X POST http://localhost:8080/api/verrouillages \
  -H 'Content-Type: application/json' \
  -d '{"type":"JOUR","jour":"2026-07-12","raison":"Journée validée avec les responsables"}'
```

Import global du référentiel depuis un `PlanningFestival` :
`POST /api/reference-data/import`.

**Sémantique d'un import de scénario** (les deux endpoints ci-dessous) :
c'est un **diff**, pas un remplacement aveugle. Les stands et animateurs du
fichier sont mis à jour **en place** — un animateur conservé garde son
`jeton_acces` (les liens d'espace imprimés survivent), ses sessions, ses
demandes d'échange hors créneaux remplacés, et son e-mail si le fichier n'en
porte pas ; seuls les stands/animateurs **absents du fichier** sont supprimés
(leurs demandes, sessions et codes d'accès partent en cascade). Le planning
résolu, les verrous, les contraintes ad hoc et la trace de résolution
(`planning_resolution`) sont en revanche toujours effacés, et les créneaux du
groupe actif remplacés par ceux du fichier.

`GET /api/reference-data/impact-import` chiffre ce périmètre **avant**
d'importer : `{ animateurs, stands, postes, planningResolu, groupeResoluNom,
demandesEchange, demandesEnAttente, verrous }` — c'est ce que le dialogue de
confirmation de la page Paramètres affiche, qui enregistre aussi un instantané
du plan automatiquement quand un planning résolu existe.

`POST /api/reference-data/import-scenario?name={fichier}` charge un scénario
du dossier `scenarios/` côté serveur et importe son référentiel. Si le
fichier définit `parametresLegaux:`, `parametresDecoupage:` et/ou
`parametresSolveur:` (sections optionnelles, voir
[`domaine.md`](domaine.md#découpage-automatique-en-vacations)), ces réglages
sont aussi appliqués — `parametresSolveur.dureeResolutionSecondes` reconfigure
la durée de résolution (page Solveur) ; absents, les réglages actuellement
en base sont laissés tels quels. Une section `typologies: [{ id, label, ninja? }, …]`
optionnelle fixe le libellé du référentiel `typologie` (voir
[`import-export.md`](import-export.md#chargement-de-scénario)) pour les ids
que le fichier utilise, au lieu de laisser l'import leur donner un libellé
identique à leur id.

Les deux imports honorent la section optionnelle `edition:` du fichier (voir
[`import-export.md`](import-export.md)) : l'import est alors routé vers
l'édition désignée — créée vide au besoin — et la réponse (`200`) porte
`{ decoupageAuto, editionId, editionNom, editionCreee }`, `editionId` restant
`null` quand le fichier ne désigne rien.

`POST /api/reference-data/import-scenario-fichier` fait la même chose pour un
scénario envoyé en corps de requête (bouton « Importer un fichier » de
la page Paramètres), plutôt qu'un nom de fichier du dossier `scenarios/` —
même format YAML, typiquement celui produit par « Exporter les données
actuelles en scénario ». Un fichier invalide (YAML mal formé, section
manquante) renvoie `400` avec `{"message": "…"}` décrivant l'erreur, sans
rien importer.

`POST /api/reference-data/valider-scenario-fichier` valide la **structure**
d'un fichier YAML (types, sections/champs requis, plages de valeurs — voir
[`docs/schema/scenario-schema.json`](schema/scenario-schema.json) et
`ScenarioValidator`) sans rien importer ni persister — c'est l'endpoint de
l'outil « Validateur YAML » de la page Débogage. Toujours **200**, y compris pour un fichier
vide ou un YAML mal formé : `{"valide": bool, "erreurs": ["…", …]}`, une
liste vide signifiant que le fichier est valide. Ne remplace pas les
vérifications de références croisées (`standId`/`creneauId` d'un poste)
qu'effectue `import-scenario-fichier` sur un import réel.

## Découpage automatique en vacations

Découpe les créneaux courants de l'édition, lus comme des amplitudes, en
vacations plus courtes et chevauchantes — paramètres sur la page Paramètres,
génération depuis la page Créneaux (voir [`domaine.md`](domaine.md#découpage-automatique-en-vacations)).

| Méthode | Chemin | Description |
| --- | --- | --- |
| `GET` | `/api/decoupage/preview` | Prévisualise les vacations que les créneaux courants de l'édition (lus comme des amplitudes) produiraient, sans rien persister |
| `POST` | `/api/decoupage/generer` | Matérialise le découpage **en place** : les créneaux de l'édition (les amplitudes) sont remplacés par les vacations générées, et le plan résolu est effacé avec eux |
| `GET` | `/api/parametres-decoupage` | Paramètres de découpage courants |
| `PUT` | `/api/parametres-decoupage` | Met à jour les paramètres de découpage |

## Import / export de données

| Méthode | Chemin | Description |
| --- | --- | --- |
| `GET` | `/api/database/export` | Dump SQL autonome de toutes les tables métier |
| `POST` | `/api/database/import` | Rejoue un dump SQL dans une transaction unique |

Détail des formats : [`import-export.md`](import-export.md).

## Exports planning

Génération **côté serveur** (OpenPDF pour le PDF, texte pour l'ICS). Le planning
à exporter est envoyé dans le corps de la requête, sauf pour l'export global qui
lit le planning persisté lui-même (voir ci-dessous).

| Méthode | Chemin | Description |
| --- | --- | --- |
| `GET` | `/api/planning/export/pdf/global` | **Un seul PDF** reprenant toutes les affectations, pour l'organisateur : section « par journée » puis section « par stand » (bouton « Exporter le planning global » de la page Solveur) |
| `POST` | `/api/planning/export/bundle/all` | ZIP contenant un PDF **et** un ICS par animateur (utilisé par l'IHM) |
| `POST` | `/api/planning/export/pdf/all` | ZIP contenant un PDF par animateur |
| `POST` | `/api/planning/export/pdf/animateur/{animateurId}` | PDF du planning individuel |
| `POST` | `/api/planning/export/ics/all` | ZIP contenant un ICS par animateur |
| `POST` | `/api/planning/export/ics/animateur/{animateurId}` | ICS du planning individuel |
| `POST` | `/api/planning/envoi/tous` | Envoie par e-mail à chaque animateur **titulaire d'au moins un poste** son planning individuel (PDF joint + lien de son espace), construit côté serveur depuis le planning persisté ; répond un compte rendu `{envoyes, sansEmail, echecs}` nommant les animateurs sans adresse et les échecs (bouton « Envoyer à tous » de la page Solveur) |
| `POST` | `/api/planning/envoi/animateur/{animateurId}` | Même envoi pour un seul animateur — `400` s'il n'a pas d'adresse e-mail, `404` s'il est inconnu (bouton « Envoyer par e-mail » de la timeline animateur) |

L'export global est le seul en `GET` : un planning de la taille du festival pèse
plusieurs mégaoctets en JSON, que l'appelant n'a pas à téléverser pour récupérer
un document. Il lit la même source que les calendriers
(`GET /api/planning/persisted`). Chaque ligne du document vaut pour un stand ×
vacation : elle porte les animateurs affectés, l'effectif `pourvus/sièges`, et
signale en rouge les sièges non pourvus.

## Heures planifiées

Calcule, pour chaque animateur, le nombre d'heures planifiées par semaine
calendaire ISO (`AAAA-Wss`) et le total. Le planning à analyser est envoyé
dans le corps de la requête, comme pour les exports.

| Méthode | Chemin | Description |
| --- | --- | --- |
| `POST` | `/api/planning/hours` | Rapport JSON (semaines + heures par animateur) |
| `POST` | `/api/planning/hours/export` | Le même rapport, au format CSV |
