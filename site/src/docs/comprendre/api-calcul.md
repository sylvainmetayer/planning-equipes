# Lancer, suivre et arrêter un calcul par l'API

Vous saurez lancer un calcul (complet, en file ou incrémental), prévoir ce qu'il va recevoir, suivre sa progression en direct, lire ce qu'il laisse derrière lui (plan précédent, versions, comparaison A/B) et éviter ses pièges : budget, redémarrage du serveur, point de départ. La mécanique interne du solveur est dans [le calcul du planning](comment-le-planning-est-calcule.md).

## Quatre lancements, un seul parcours

Le calcul synchrone, l'asynchrone depuis un problème envoyé, l'asynchrone depuis le référentiel et l'incrémental passent tous par [`SolvePipeline`](repo:src/main/java/dev/sylvain/planning/service/solve/SolvePipeline.java) et font donc la même chose : capturer le plan sur le point d'être écrasé, construire le problème, résoudre, persister, diagnostiquer, alimenter l'écran Règles du planning, écrire la ligne de KPI, annoncer la fin si l'édition l'a demandé. Un chemin qui s'arrêterait avant la fin laisserait cet écran sur l'analyse du calcul précédent sans lever d'erreur. `SolveSynchronePipelineTest` tient les deux bouts.

**Le passé est figé** ([ADR 0044](repo:docs/decisions/0044-le-passe-est-fige.md)). Pendant l'événement, chaque problème construit depuis le référentiel reprend du plan enregistré les places des créneaux déjà commencés (date passée, ou aujourd'hui avec un début atteint) et les épingle : titulaire gardé même devenu indisponible, place vide restée vide. « Aujourd'hui » est celui de l'horloge du jour J ([jour J et animateurs](api-jour-j-et-animateurs.md)). Ces places comptent dans les règles sans jamais être reprochées, donc le score se lit sur ce qui reste à jouer. Un problème envoyé dans le corps de la requête voit aussi ses places passées épinglées, sans réamorçage : le serveur ne connaît pas le point de départ de l'appelant. Un calcul dont toutes les places sont passées est refusé plutôt que de produire un plan vide : le job finit `FAILED` avec « Rien à planifier : tous les créneaux sont déjà commencés… » dans `error`, sans alerte de panne. `PASSE_FIGE=false` coupe la règle.

### Un seul calcul à la fois

Un seul job de résolution tourne pour toute l'application, verrou porté par le serveur. Les autres sessions voient le job actif par `GET /api/jobs/active` et reçoivent `409` à un second lancement, le corps portant le job en cours. C'est le seul `409` de lancement dont le corps est un job : le quota horaire et la file plafonnée d'une instance publique (`SOLVER_MAX_SOLVES_PER_HOUR`, `SOLVER_MAX_QUEUED_JOBS`, voir [le réglage du calcul](../installer/calcul-du-planning.md)) et le référentiel figé répondent `409` avec un simple `{ message }`. Un client les distingue par la forme du corps (un `id` et un `status`), pas par le statut.

## Le budget

Le budget est celui de l'**instance** (ADR 0110), le même pour toutes les éditions : `planning.solver.seconds-limit` et `planning.solver.unimproved-seconds-limit`, que `GET /api/parametres/calcul` expose sous `instance` avec le plafond d'un lancement (`maxSecondsLimit`, `SOLVER_SECONDS_LIMIT_MAX`). Aucune écriture ne le change : `PUT /api/parametres/calcul` ne règle que le mail de fin de résolution de l'édition. Écran, script et MCP obtiennent donc le même budget. Trois pièges :

- **un plafond refuse, il ne tronque pas.** Une durée au-dessus du plafond est refusée en `400` qui cite le plafond, sur un `?seconds=` comme sur un `secondes` MCP ;
- **le plateau reste conditionné à la faisabilité** : l'arrêt est « faisable ET N secondes sans amélioration », il ne coupe jamais un calcul qui a encore des places à pourvoir. Un `?seconds=` explicite, différent de la durée de l'instance, veut dire « exactement cette durée » : pas de plateau ;
- **un job garde le budget de son lancement.** Il est résolu à la soumission. La replanification incrémentale a sa durée propre (60 s sans `?seconds=`) et pas de plateau, sous le même plafond.

## Prévoir ce que le calcul va recevoir

`GET /api/planning/volumetrie` (outil MCP `volumes`) décrit le problème que le prochain calcul construirait, jamais un produit stands × créneaux : `animateurCount` (les valeurs possibles d'un poste), `posteCount` (un poste par siège à pourvoir), `contrainteAdHocCount`, `hoursToFill` (durées effectives des postes, fermetures déduites) et `hoursAvailable`, le plafond légal de ce que les animateurs peuvent travailler les jours de l'événement (par animateur et par semaine ISO, au plus six jours non indisponibles, chacun au plafond quotidien de son âge ce jour-là, le tout borné par le plafond hebdomadaire). Leur rapport est un taux de remplissage, mais c'est un plafond et non une prévision : compétences, repos et pauses en retranchent encore, et un taux proche de 1 annonce déjà un planning infaisable.

Les postes ne dépendent que des stands et des créneaux : ils sont comptés avant la saisie d'aucun animateur (`hoursAvailable` vaut alors zéro), et tout est à zéro sans stand ni créneau. `GET /api/staffing` fait de même (`referentielsManquants` nomme ce qui manque). `GET /api/feasibility` et `GET /api/fragilite` disent au contraire explicitement que rien n'est réalisable sans animateur, plutôt que de ne détecter aucun problème, et `GET /api/feasibility` dit aussi une édition sans créneau ou dont aucun stand n'ouvre : rien à pourvoir n'est pas un feu vert.

### Le besoin en animateurs

`GET /api/staffing` (outil MCP `analyser_effectifs`) rend des **minimums prouvés** : aucune équipe plus petite ne tient les sièges. Trois chiffres se lisent autrement qu'il n'y paraît :

- `mineursMax` est un plafond, calculé pour `effectifReference` (le minimum, ou les animateurs saisis s'ils sont plus nombreux) : au-delà, recruter des mineurs empêche de tenir. Il ne dit jamais combien de mineurs recruter, et il ignore les jours d'affilée et les 4 h 30 de travail continu des mineurs ;
- `absentsMax` (par jour) et `budgetIndisponibilites` (par semaine) se lisent contre le même effectif. Le budget s'entend au-delà du jour de repos que chacun doit déjà prendre, et `indisponibilitesAuDela` compte les jours déclarés au-delà de ce repos ;
- `joursConsecutifsMax` n'est lu que si `maxJoursConsecutifsTravailles` est obligatoire, `null` sinon : une préférence peut être enfreinte, elle ne doit pas faire recruter.

`POST /api/staffing/verification` lance par défaut un calcul des sièges avec une **équipe fictive** (`majeurs`, `mineurs` de seize ans, tous disponibles et compétents partout) et répond `202`. Avec `calcul: false`, il répond au contraire **par la borne exacte** : il compare l'effectif demandé (sans `majeurs`, le minimum moins les mineurs) au minimum exact et aux majeurs que la journée la plus exigeante demande, et rend `202` avec une vérification déjà `TERMINEE` (`methode: BORNE`, `minimum` : le chiffre comparé), sans calcul, sans quota, sans attente. Cette réponse dit « au moins N », jamais « un plan existe ». Une requête qui ne dit rien reçoit le défaut de l'instance, `planning.staffing.verification-default` (`calcul`, ou `borne` quand la borne égalera le calcul sur toute la gamme : [ADR 0098](repo:docs/decisions/0098-la-borne-exacte-s-affiche-d-abord-le-calcul-reste-le-defaut.md)). Ce qui suit ne vaut que pour le calcul. `GET /api/staffing/verification` rend la dernière vérification, ou `204` s'il n'y en a jamais eu. Sans `dureeSecondes`, la durée est celle d'une résolution de l'édition (minimum 10 s, plafond d'exploitant) : plus court, la vérification échouerait là où le calcul réussit. Le calcul tourne sur le moteur de l'instance et s'arrête dès qu'un plan est réalisable ([ADR 0071](repo:docs/decisions/0071-le-besoin-en-animateurs-un-minimum-exact-verifie-par-une-equipe-fictive.md)). À savoir :

- ni le plan, ni les animateurs, ni la file ne sont écrits ; la vérification est conservée (`verification_besoin`) et l'historique trace son lancement et sa fin ;
- une vérification à la fois, toutes éditions confondues, et aucune pendant un calcul : une seconde demande reçoit `409`, et un calcul qui démarre (`SolveStarting`) interrompt la vérification, lue alors en `ECHEC` avec la raison, comme après un arrêt du serveur. Elle voyage dans le flux d'état (champ `verification`) ;
- **un échec ne prouve rien** : `realisable: false` dit seulement qu'aucun plan complet n'a été trouvé dans le temps imparti. `posteDoitEtrePourvu` est toujours tenue, même si l'édition l'a éteinte ;
- les exceptions ad hoc et le plan publié sont laissés de côté, car ils nomment des personnes que l'équipe fictive ne contient pas. Le plan en place sert de point de départ : chaque animateur réel passe à un membre fictif, majeur à majeur, mineur à mineur, les plus chargés d'abord.

`GET /api/solve/entrees` compte en une lecture ce que la page Solveur annonce sous « Ce calcul tiendra compte de », sans rien calculer. `pendingDeclarations` compte les déclarations de disponibilité **en attente** : un calcul lit les fiches, jamais les déclarations, donc ce chiffre dit ce qu'il ne verra pas. `changesSinceSolve` compte les actions journalisées depuis le plan persisté, avec le filtre de `GET /api/historique/changements` (0 sans plan). `disabledRules` ne compte que les règles livrées actives que l'édition a éteintes.

## La file d'attente

![Cycle de vie d'un job de résolution](repo:docs/diagrammes/job-etats.svg)

<sub>Source : [`diagrammes/job-etats.puml`](repo:docs/diagrammes/job-etats.puml).</sub>

`enFile=true` met la tâche en file au lieu de la refuser, en FIFO. Les statuts sont `QUEUED`, `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED` et `INTERROMPU`. Le problème est construit au **démarrage effectif**, pas au clic, et une tâche en file ne tient pas le solveur : la saisie reste ouverte sur l'édition visée. Un doublon (même édition, même type, déjà planifié) est refusé en `409`, mais planifier un calcul de l'édition en cours de calcul reste permis : c'est la façon de dire « refais-le avec ce que je viens de corriger ».

Ce qui est persisté (`solver_job`) est l'**intention** (type, budget, édition, périmètre), jamais l'état du solveur, ce qui suffit puisqu'une tâche en file construit son problème au démarrage. Un redémarrage perd le calcul en cours, dont le job passe en `INTERROMPU`, état terminal pour ne pas garder le verrou global. Le résultat d'un job n'est pas stocké : un job restauré expose `result: null`, ce qu'il décrivait étant déjà en base. `planning.jobs.reprise-au-demarrage=false` coupe la reprise.

**Un arrêt du serveur n'écrase pas le plan.** Quand le conteneur s'arrête sous un calcul (arrêt gracieux, live reload), Timefold rend sa meilleure solution comme si le budget était épuisé. Ce n'est pas un calcul abouti : le job finit `INTERROMPU`, jamais `COMPLETED`, et le plan partiel ne remplace le plan enregistré que s'il le **bat strictement** (dur, puis medium, puis soft) ou s'il n'y en avait pas. `result.interruption: { partialPlanKept, partialScore, persistedScore }` le dit à un script ; quand le plan est écarté, ni ligne de KPI ni courriel de fin ne sont produits. Le serveur laisse au solveur quelques secondes pour écrire ce verdict ; un calcul qui ne les respecte pas est retrouvé `RUNNING` au démarrage suivant et passe par le cas précédent.

## Le point de départ

`POST /api/solve/async/reference-data?reamorcage=AUTO|PLAN_COURANT|AUCUN` choisit d'où part un calcul complet. `AUTO`, le défaut, repart du plan enregistré s'il existe et part de zéro sinon. `PLAN_COURANT` l'exige et fait échouer le job sans plan. `AUCUN` est le départ à froid. Une valeur inconnue répond `400`. Le choix est persisté avec le job (`solver_job.reamorcage`).

Repartir du plan ne l'épingle pas : chaque siège reçoit l'animateur que le plan lui donnait et reste mobile, seuls les verrouillages explicites sont figés. C'est ce qui sépare ce réamorçage de la replanification incrémentale, qui fige tout ce qui reste valable. Le solveur score la solution de départ en premier et garde la meilleure qu'il ait vue : à données égales, le résultat ne descend jamais sous le plan de départ. Un siège dont le titulaire a disparu ou s'est déclaré indisponible repart vide, car semer un écart à défaire est un moins bon départ qu'un trou. Le défaut n'est pas le départ à froid, même pour un script ou un assistant, parce que le départ à froid peut finir sous le plan déjà en base ; il se demande. Un plan de départ infaisable n'achète aucun raccourci : la terminaison sur faisabilité ne se déclenche pas.

Le résultat restitue ce qui a été fait :

- `reamorcage: { mode, postes, postesLiberes, postesPasses, postesPassesVides }`, où `mode` vaut `PLAN_COURANT` ou `AUCUN`, jamais `AUTO`. Il est absent quand le problème est venu dans le corps de la requête. `postesPasses` compte les places déjà commencées, épinglées quel que soit le mode, et `postesPassesVides` celles qui ne tiennent personne : un avertissement, jamais un point dur ;
- `previousPlan`, voir plus bas ;
- `impactPublication: { personnes, publieLe }` : combien de personnes verraient leur emploi du temps changer par rapport au dernier plan publié, compté comme la publication le compte, `null` tant que rien n'a été publié. C'est le compte à lire avant de publier ; la règle `stabiliteDuPlanPublie` le tient bas ([les règles](pourquoi-ces-regles.md)).

## Suivre un calcul en direct

`GET /api/jobs/stream` pousse en server-sent events l'agrégat `{ active, file }`. Le premier événement arrive à la connexion, sans attendre une transition (un solveur au repos n'en produirait aucune), et chaque événement est un instantané entier. Un `heartbeat` toutes les 20 s (`JOBS_STREAM_HEARTBEAT`) porte aussi la ligne `:keep-alive`, sans laquelle un proxy ferme une connexion silencieuse ; le commentaire seul ne suffirait pas, `EventSource` ne l'exposant pas au JavaScript. Le flux double le polling au lieu de le remplacer : SSE échoue en silence (proxy qui bufferise, coupure non reconnectée) et l'interface resterait figée sur un état périmé. Le navigateur garde un poll lent et reprend la main après un silence d'environ deux heartbeats.

### La courbe de score

Le même flux porte l'événement `score` : les points (temps écoulé, hard, medium, soft) que Timefold annonce par `bestSolutionChanged`. `GET /api/jobs/score` rend la même courbe d'un bloc. Ce qui est tracé est le calcul en cours, ou le dernier jusqu'au suivant ; rien n'est persisté.

Timefold n'annonce une meilleure solution que lorsque le score s'améliore strictement : un calcul qui plafonne cesse de produire des points, justement l'état que l'écran doit montrer. Une courbe normalisée sur ses seuls points toucherait toujours le bord droit. La **durée voyage donc à part** (`dureeMs`, à l'horloge) : c'est le bord droit, le navigateur y prolonge la dernière valeur à plat et ce segment est le plateau. `dureeMs` est figé à la fin du run. Un calcul en cours émet par conséquent un événement à chaque tick, même sans nouveau point (`points: []`) ; une courbe terminée se tait.

Deux bornes d'échantillonnage, côté serveur :

| Borne | Effet |
| --- | --- |
| un point par seconde au plus | le plus récent de la fenêtre est retenu, jamais le premier |
| 400 points au plus | au-delà, la série est divisée par deux et l'intervalle double |

Un calcul de 15 min et un de 4 h coûtent donc la même mémoire. Les annonces portant une solution non initialisée sont ignorées : leur score décrit une affectation partielle, presque sans écart, et les tracer dessinerait une falaise au moment où le plan devient complet. Le rythme se règle par `JOBS_STREAM_SCORE` (1 s).

Les événements `score` sont des **deltas** : `depuis` est l'indice du premier point envoyé (`0` remplace tout, connexion neuve, nouveau calcul ou série divisée ; sinon on ajoute à cet indice), et `generation` change dès que la série cesse d'être une extension d'elle-même. Trois précisions :

- `EventSource` ne pose pas d'en-tête, donc le flux n'a pas d'édition : chaque événement porte l'`editionId` du calcul et le navigateur n'affiche que la courbe de son édition. `GET /api/jobs/score` filtre côté serveur et répond `204` pour la courbe d'une autre édition ;
- un événement `score` **sans `jobId`** veut dire « il n'y a plus de courbe », envoyé une fois par connexion. Il vise le redémarrage : sans lui, un client dont le dernier événement disait `termine: false` laisserait un graphe à l'écran comme s'il était vivant ;
- la courbe se ferme à la fin du job (succès, échec, annulation) : le dernier score est ajouté, `dureeMs` est figé, `termine` passe à `true`. Un job annulé avant que son solveur ait démarré ne touche pas la courbe, sinon une trace vide effacerait celle du run précédent.

### L'instant de faisabilité

Le premier plan faisable (zéro dur) arrive souvent bien avant la fin, et c'est ce que l'organisateur attend : le planning est alors utilisable, le reste est de la qualité. Le job le porte dans `feasibleAtSeconds` (`JobView`, `consulter_calcul` en MCP), `null` tant que le plan ne l'est pas, et pousse un événement d'état à cet instant. « Arrêter et garder ce plan » n'est que l'arrêt ordinaire, le solveur gardant toujours le meilleur plan atteint. Timefold n'annonçant une solution que lorsqu'elle change, un calcul qui part déjà d'un plan à zéro dur ne dirait rien : le plan de départ complet est donc noté une fois avant le démarrage (`SolveListener`).

## La replanification incrémentale

`POST /api/solve/incremental/async` repart du plan persisté, épingle ce qu'un changement tardif n'a pas invalidé et ne recalcule que le reste. Le corps est facultatif et **élargit** le périmètre : `animateurIds`, `jours` et `standIds` forment une union. Sans corps, le périmètre est ce que les changements ont invalidé plus les postes vides. Le job échoue s'il n'y a aucun plan à reprendre. Les places déjà commencées sont réglées avant le périmètre : épinglées, jamais rouvertes (`jours` nommant une journée passée ne rouvre rien), comptées dans `statistiques.postesPasses` et `postesPassesVides`, et une replanification dont toutes les places sont passées est refusée comme le calcul complet. Le mécanisme est dans [verrous, gel et replanification](plan-verrous-et-publication.md).

## Versions et comparaison A/B

Un seul plan est persisté par édition ; les **versions** sont la seule persistance capable d'en garder plusieurs ([ADR 0108](repo:docs/decisions/0108-un-seul-type-version-a-trois-origines.md)). Une version a trois origines, dites par `origine` : `MANUELLE` (demandée par une personne), `AVANT_CALCUL` (prise d'office) et `PUBLIEE` (ce que les animateurs ont reçu). Les routes sont `/api/planning/versions` ; les racines 3.x `/api/planning/snapshots` en sont des alias dépréciés, retirés en 5.0, et `automatique` reste servi (vrai pour `AVANT_CALCUL`) pour le même temps. Une version est **prise automatiquement avant chaque calcul** : c'est le filet anti-écrasement de qui n'a pas pensé à enregistrer. Les automatiques sont purgés au-delà des N derniers (`planning.snapshots.automatiques-conservees`, 5), ceux créés à la main jamais.

Le résultat d'un calcul nomme cet instantané et son score : `previousPlan : { snapshotId, score, degraded }`. `degraded` compare les deux scores dans l'ordre de Timefold (dur, medium, soft) et vaut `false` dès qu'un des deux manque ou est illisible, car mieux vaut ne rien affirmer qu'une fausse alerte. `previousPlan` est `null` au premier calcul d'une édition. Le score d'un instantané est copié à la capture depuis la dernière analyse, qui vit en mémoire : après un redémarrage, la première lecture la recalcule depuis le plan enregistré, et le calcul suivant rétablit ce score avant de remplacer le plan s'il manque encore.

### Fraîcheur et restauration

Un instantané dit s'il est encore à jour (`perime`, `referenceModifieLe`). Le référentiel porte la date de sa dernière mutation (`edition.reference_modifie_le`, écrite par [`ReferenceDataChangeTracker`](repo:src/main/java/dev/sylvain/planning/service/ReferenceDataChangeTracker.java)) ; un instantané capturé avant elle est périmé. Cette date est persistée : après un redémarrage, une valeur en mémoire se lirait « jamais modifié », donc « à jour », et c'est devant le bouton *Restaurer* qu'un faux « à jour » coûte le plus cher ([ADR 0038](repo:docs/decisions/0038-fraicheur-du-referentiel-persistee.md)).

`POST /api/planning/versions/{id}/restore` répond `409` **sans rien écrire** dans trois cas :

- une **référence a disparu** : le corps liste lesquelles. Ce refus est vérifié en premier, un instantané étant le plus souvent périmé parce que des ids ont disparu ;
- l'instantané est **périmé** : le corps porte `perime: true` et la date. Le geste se rejoue avec `?forcer=true`, car le plan reste restaurable, il ne décrit simplement plus le référentiel d'aujourd'hui. `forcer` ne lève que ce refus ;
- un calcul tient le solveur **de cette édition** : il écraserait le plan remis en place. Le corps est celui des écritures du référentiel, avec le job nommé. La garde est dans le service, que l'API et l'outil MCP `restaurer_version` partagent ; un calcul d'une autre édition ne refuse rien.

Une restauration remet le plan avec le dosage sous lequel il avait été calculé.

### Publication et suppression

Un instantané peut porter l'état « publié » (`publieLe`) : c'est le plan que les animateurs ont reçu, et il sort de la purge. Deux refus `409` à la suppression :

- **la dernière publication** : l'espace la lit, et la supprimer reprendrait sans un mot ce qui a été annoncé. Les publications remplacées se suppriment comme n'importe quel instantané ;
- **une publication qui mesure une journée écoulée** : Réalisé vs planifié compare chaque journée terminée au plan publié en vigueur à son premier créneau, et supprimer cette publication réécrirait l'écart après coup. Le refus nomme la date de la journée, jamais une personne, et vaut pour l'outil MCP `supprimer_version`. Il est évalué avant la suppression, car la réponse dépend de l'horloge et des créneaux.

Le repère de comparaison d'une personne différée (`animateur.plan_notifie_id`) peut désigner l'une de ces publications. La supprimer la fait relire comme « jamais prévenue » : sa prochaine publication lui annoncera son planning entier plutôt que ses écarts.

### Comparer deux plans

`GET /api/planning/versions/compare` ne déclenche **aucun calcul** : il lit des KPI mesurés à la capture. `GET /api/planning/versions/comparables` traverse les éditions, d'où `editionsDifferentes`, qui n'est pas une anomalie (une variante est une autre édition) mais que l'interface doit dire. Dans `diffViolations`, `null` signifie **non mesuré**, jamais zéro, et `kpiRecalcule` marque le mode dégradé d'un instantané sans KPI stockés (couverture et volumétrie exactes, écarts non mesurés).

**Deux plans calculés sous des dosages différents ne se comparent pas à poids égaux** : `dosagesDifferents` le dit quand les deux `kpi.dosage` sont connus et diffèrent. Un instantané porte aussi les consignes en vigueur à sa capture (`consignes`, par date) : `null` sur un instantané antérieur veut dire **inconnu**, un tableau vide « il n'y en avait pas ». `consignesDifferentes` reste à `false` dès qu'un côté ne sait pas, et signale quand une différence de sièges ou d'heures vient d'une bande qu'un arrêté a fermée plutôt que d'un réglage du solveur ([les consignes](api-planning-et-publication.md#consignes)).
