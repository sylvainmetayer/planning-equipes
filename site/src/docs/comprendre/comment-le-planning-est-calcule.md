# Du problème au plan : le solveur et ses deux moteurs

Chaque calcul de planning suit le même parcours, quelle que soit la façon de le lancer, et deux moteurs peuvent y chercher le plan, l'un arbitrant l'autre. Vous saurez ce que fait un calcul de bout en bout, qui cherche le plan et qui le contrôle, où il peut tourner, et pourquoi le diagnostic d'un plan ne passe plus par l'API réservée de Timefold.

Le déroulé côté utilisateur est dans [Calculer le planning](../regles/calculer-planning.md), le réglage de l'exploitant dans [le calcul du planning](../installer/calcul-du-planning.md), les routes dans [lancer, suivre et arrêter un calcul](api-calcul.md).

Pour un organisateur qui ne lit pas de code, [comment marche le calcul](/comment-marche-le-calcul/) en donne la version courte.

## Un calcul fait toujours la même chose

Quatre façons de lancer un calcul existent : synchrone depuis un problème envoyé dans la requête, asynchrone depuis un problème envoyé, asynchrone depuis le référentiel, incrémentale. Toutes passent par [`SolvePipeline`](repo:src/main/java/dev/sylvain/planning/service/solve/SolvePipeline.java), et font donc toujours les mêmes huit choses :

1. photographier le plan sur le point d'être écrasé (`captureBeforeSolve`) ;
2. construire le problème ;
3. résoudre ;
4. enregistrer le plan ;
5. le diagnostiquer ;
6. alimenter l'écran Règles du planning (`ConstraintAnalysisStore`) ;
7. écrire la ligne d'historique des KPI ;
8. annoncer la fin, par courriel si l'édition l'a demandé.

La raison d'être de cette classe est un défaut qu'elle a supprimé : un chemin qui s'arrêtait après l'enregistrement laissait l'écran des règles sur l'analyse du calcul précédent, sans erreur, et n'écrivait pas la ligne de KPI. Le seul symptôme était un écran qui ment. `SolveSynchronePipelineTest` verrouille les deux bouts.

Deux coutures seulement varient d'un appelant à l'autre : comment le problème est construit, et qui doit tenir le solveur pour pouvoir l'arrêter. Un travail en file construit son problème au démarrage effectif, pas au clic : il calcule l'édition telle qu'elle est quand son tour vient.

![Séquence d'un solve lancé depuis le référentiel](repo:docs/diagrammes/solve.svg)

<sub>Source : [`diagrammes/solve.puml`](repo:docs/diagrammes/solve.puml).</sub>

La seconde couture porte deux usages : arrêter le solveur, et le suivre. `SolverScoreTrace` s'abonne au même `Solver`, au même instant, pour garder la courbe de score du calcul en cours ; elle est lue par `GET /api/jobs/score` et poussée sur le flux des jobs. Un seul consommateur reçoit le solveur, ce qui évite que les deux besoins se désynchronisent. L'échantillonnage de la courbe est détaillé dans [l'API du calcul](api-calcul.md).

### Ce que le calcul reçoit

`SolveRunner.prepareToSolve` complète le problème depuis l'édition, épingle le passé (`FrozenPast.pin`) et annonce le score du plan de départ. Cette dernière étape existe parce que Timefold n'annonce un meilleur plan que lorsque le meilleur change : une replanification qui part d'un plan déjà faisable serait utilisable dès sa première seconde, et ne le dirait qu'à sa première amélioration, ou jamais.

**Le passé est figé** ([ADR 0044](repo:docs/decisions/0044-le-passe-est-fige.md)). Pendant l'événement, chaque construction depuis le référentiel reprend du plan enregistré les places des créneaux déjà commencés et les épingle : titulaire gardé même devenu indisponible, place vide restée vide. Ces places comptent dans les règles (repos, plafonds hebdomadaires, jours consécutifs) sans jamais être reprochées. « Aujourd'hui » est celui de l'horloge du jour J. Un calcul dont toutes les places sont passées échoue avec « Rien à planifier » sans être remonté comme une panne. `PASSE_FIGE=false` coupe la règle.

Un problème envoyé dans le corps de la requête voit ses places passées épinglées aussi, mais le calcul synchrone est refusé tant qu'une famille du référentiel est gelée (`@RefusedWhileFrozen`) : atterrir réécrit stands, créneaux et compétences que le problème porte par-dessus ceux de l'édition ([ADR 0052](repo:docs/decisions/0052-gel-du-referentiel-distinct-du-verrou.md)). Il est aussi compté dans le quota horaire de l'exploitant, comme un job : il prend les cœurs hors de la file.

### Un arrêt du serveur n'écrase pas le plan

Quand le conteneur s'arrête sous un calcul, Timefold rend sa meilleure solution comme si le budget était épuisé. Ce n'est pas un calcul abouti. Le job finit `INTERROMPU`, aucune ligne de KPI ni courriel n'est produit, et le plan partiel ne remplace le plan enregistré que s'il le bat strictement (dur, puis medium, puis soft), ou s'il n'y avait aucun plan (`keepsPartialPlan`). Toute lecture impossible, du compte ou du score, répond non : le plan que l'exploitant a déjà vaut plus que celui qu'un redémarrage a coupé.

## Deux moteurs derrière le même solveur

`planning.solver.engine` (`SOLVER_ENGINE`) choisit qui cherche le plan d'un problème préparé, et `SolveRunner.solvePrepared` est le seul endroit qui le lit.

| Moteur | Qui cherche | Particularités |
| --- | --- | --- |
| `hand` (défaut depuis la 3.0.0) | `HandSearchSolver` : une recherche locale écrite à la main, `solver/handsearch`, avec un score tenu à jour mouvement par mouvement | Un seul temps, même sur un plan publié. Le plan final est re-scoré par Timefold |
| `timefold` | La recherche locale de Timefold sur les contraintes en flux | Sur un plan publié, deux temps : la stabilité est pesée à zéro jusqu'à la faisabilité (au plus les deux tiers du budget), puis reprend son poids sur ce qui reste (`FeasibilityFirstSolve`) |

Les deux sont des `Solver` : la file, l'arrêt, la courbe de score et le worker du calcul déporté ne savent pas lequel tourne. Le réglage est celui de l'instance et voyage avec le problème jusqu'au worker, dont la variable propre ne compte pas. Une ligne de journal dit après coup lequel a tourné (« Solve with the … engine »). `SOLVER_ENGINE=timefold` et un redémarrage rendent la recherche d'origine, sans migration : un plan enregistré sous l'un est lisible, re-scorable et réamorçable sous l'autre ([ADR 0088](repo:docs/decisions/0088-le-moteur-direct-devient-le-moteur-par-defaut.md)).

### Timefold est l'arbitre, jamais le moteur direct

Le moteur direct garde son propre score, où les 43 règles sont écrites une seconde fois : un déplacement ne change le score que de la personne qui quitte le siège et de celle qui le prend, et relire ces deux personnes coûte quelques microsecondes, là où chaque mouvement traverse tout le réseau de règles en flux. Ce score n'est jamais cru. Une fois le meilleur plan posé sur le problème, `HandSearchSolver` le fait re-scorer par les contraintes en flux (le `SolutionManager`, la voie sans licence du diagnostic). Ce que l'application enregistre et explique vient de ce second calcul. Deux instants sont arbitrés, ceux sur lesquels l'application s'engage : le premier plan sans écart dur, avant d'annoncer « faisable », et le plan final.

**Deux scores différents font échouer le calcul.** Le job est en erreur avec les deux scores dans son message, le plan enregistré reste intact. Il n'y a jamais de repli sur l'autre moteur : un moteur qui a dérivé des règles est une panne à voir, et un plan calculé autrement sans le dire la cacherait. Le moteur refuse aussi de lui-même, en clair, ce qu'il ne sait pas scorer (un siège sans horaire complet, un siège tenu par quelqu'un que le problème ne connaît pas, un fait porté en double, un problème de plus de 15 millions de couples siège × animateur, au-delà de la construction échantillonnée). Le paquet n'importe rien de `ai.timefold.solver.core.impl`, n'injecte aucun bean et ne lit aucune configuration : il tourne tel quel dans un worker.

Ce qui reste sur Timefold : la vérification du besoin et les calculs « jusqu'à la faisabilité », le diagnostic, et les définitions des règles, que les contraintes en flux continuent de porter.

### Une règle existe deux fois

Une contrainte nouvelle ou modifiée s'écrit dans les flux, qui la définissent, et dans le moteur direct : sa constante dans `Rule`, son terme dans `IncrementalScore` (ou dans `FlatProblem.staticTerms` quand elle ne lit qu'un siège et son titulaire). Deux tests tiennent ce second exemplaire :

- `HandSearchCoverageStructuralTest` échoue par son nom sur une contrainte que seul un moteur connaît ;
- `HandSearchDifferentialTest` compare les deux scores contrainte par contrainte, pas au total où deux erreurs se compensent, sur des plans vides, aléatoires, trouvés puis perturbés, et sur les scénarios versionnés complétés des branches qu'aucun ne porte (verrous, passé figé, plan publié, plafonds par typologie, les cinq types d'exception). Chaque règle doit y avoir été comparée au moins une fois sur un score non nul : une égalité à zéro ne prouve rien.

C'est un coût permanent, accepté. Voir [l'ADR 0087](repo:docs/decisions/0087-un-second-moteur-de-recherche-arbitre-par-timefold.md) pour le raisonnement et les alternatives écartées, et [ajouter une règle](ajouter-une-regle.md) pour le geste.

## Où le calcul tourne

Par défaut sur la machine de l'application. Un calcul complet construit depuis le référentiel peut partir sur un worker : un programme Java sans conteneur d'injection, dans la même image (`SOLVER_WORKER_MODE=true`), que l'application atteint à une adresse permanente ou par une machine demandée à un lanceur HTTP de l'exploitant ([ADR 0086](repo:docs/decisions/0086-le-calcul-deporte-un-worker-sans-conteneur-et-un-crochet.md)).

- `SolveExchange` est la forme que prend un problème préparé pour partir, et celle de son plan au retour. Ce que le JSON public cache à un appelant voyage sous une forme contrôlée : un animateur part en liste blanche de champs, sous un pseudonyme et une date de naissance de remplacement, le texte libre reste à la maison.
- `RemoteSolver` est un worker derrière l'interface `Solver` : la file n'a pas à savoir où tourne la recherche. Il pose le plan reçu sur le problème de l'application, recalcule le score et refuse le plan au moindre écart.
- `WorkerClient` porte tous les appels, avec le certificat du worker épinglé.
- Le worker (`SolveWorker` derrière `SolveWorkerServer`) ne lit aucun réglage de solveur : durée, plateau et moteur arrivent avec le problème (`SolverConfiguration.SearchSettings`), et il refuse un problème d'une autre version. Rien dans ces deux classes ne doit injecter de bean, lire la configuration de l'instance ni ouvrir la base.

**Un échec distant fait échouer le job, il ne se replie jamais sur place** : machine indisponible, version différente, plan refusé. Seuls restent sur place la replanification partielle, le calcul d'un problème envoyé dans une requête et la vérification du besoin. Quand l'application s'arrête pendant un calcul distant, elle laisse le worker résoudre ; au démarrage suivant elle reconstruit le problème, retrouve le calcul par son identifiant et le suit de nouveau. Le côté exploitant est dans [le calcul sur une autre machine](../installer/calcul-sur-une-autre-machine.md).

## La file et la vérification du besoin

`SolverJobService` porte les calculs asynchrones et leur file, **en mémoire** (ADR 0110) : un redémarrage perd les tâches qui attendaient. L'historique, lui, est inscrit dans `solver_job` dès qu'une tâche tient le solveur ; une tâche en file n'y est jamais écrite. Au démarrage, une ligne laissée en file (une 3.x) ou qui tenait le solveur revient `INTERROMPU`, un état terminal, avec la raison sur la tâche et dans le journal, pour ne pas garder le verrou indéfiniment ; un calcul resté sur une machine distante est suivi de nouveau. `SolverJobTasks` et `SolverJobPersistence` portent ce qui ne tient pas le verrou. Le quota horaire et la file plafonnée d'une instance publique se vérifient à la soumission (`SolverQuota`, sans effet hors instance publique), jamais dans une ressource : une nouvelle façon de lancer un calcul appelle `consume()` (et `checkQueue` si elle peut attendre). Le verrou « un calcul à la fois » est global à l'instance, jamais par édition ; seul le `409` d'un lancement refusé parce qu'un calcul tourne porte un job dans son corps, ce qui permet à un client de le distinguer d'un refus de quota.

`StaffingVerificationService` vérifie le besoin en animateurs : par défaut une équipe fictive est résolue jusqu'à la faisabilité sur son propre fil, hors file, sur le moteur de l'instance (`SolveRunner.solveCheck`) ; avec `calcul: false`, la borne exacte répond seule ([ADR 0098](repo:docs/decisions/0098-la-borne-exacte-s-affiche-d-abord-le-calcul-reste-le-defaut.md)). Elle cède la place à tout calcul qui démarre (`SolveStarting`) et garde chaque vérification dans `verification_besoin`.

## Le diagnostic ne passe pas par `analyze()`

Décomposer un score règle par règle alimente beaucoup de monde : les pages des règles et des problèmes, la heatmap, l'explication d'une affectation, les simulations d'échange, l'assistant de réparation et leurs équivalents MCP. Tout cela passait par `SolutionManager.analyze()`, réservé à l'édition Enterprise de Timefold à partir de la 2.x. Le verrou dépassait les écrans : la fin de chaque calcul l'appelait, donc sans licence un calcul réussi était rapporté en échec.

`service/diagnostic/` offre donc une couture unique, `ConstraintDiagnosticService`, qui rend un `PlanningAnalysis`, un type du projet. Deux implémentations la satisfont.

| Implémentation | Chemin | Licence |
| --- | --- | --- |
| `ScoreDirectorConstraintDiagnosticService` | le score director du solveur | aucune |
| `SolutionManagerConstraintDiagnosticService` | `SolutionManager.analyze()` | Enterprise en 2.x |

Ce ne sont pas un mode complet et un mode dégradé. Seule la façade est verrouillée (`analyze()`, `diff()`, `recommendAssignment()`) ; le calcul des correspondances, justifications comprises, est du code Community ordinaire. Les deux implémentations rendent la même analyse (score, contraintes, correspondances, faits), ce que `ConstraintDiagnosticServiceContractTest` vérifie en les exécutant côte à côte. Il n'y a rien à détecter au démarrage ni à signaler à l'utilisateur ([ADR 0013](repo:docs/decisions/0013-diagnostic-par-le-score-director.md)).

Le choix se fait par `planning.diagnostic.mode` (`score-director` par défaut), et `ConstraintDiagnosticService.of` est le seul endroit qui sait qu'il y a deux implémentations. Celle qui passe par `analyze()` est l'oracle du test de contrat, pas un secours. C'est ce qui rend tenable de s'appuyer sur `ai.timefold.solver.core.impl`, hors semver : une montée de version qui change le comportement fait échouer la comparaison et nomme l'écart, au lieu de déformer cinq écrans en silence.

**À chaque montée de Timefold, lancez ce test et lisez son résultat plutôt que celui du build.** Sur une édition Community 2.x l'oracle est verrouillé, et le test se déclare alors ignoré au lieu d'échouer : un build vert ne prouve alors rien sur le diagnostic. Une licence restaure la comparaison sans configuration.

### Les autres dépendances aux internes

Quatre filtres de mouvement (`EligibleAnimateurMoveFilter`, `HoleNeighbourPosteFilter`, `UnassignedPosteFilter`, `DoubleBookedPosteFilter`) et deux fabriques de mouvements (`WeekRelocationMoveIteratorFactory`, `DaySwapMoveIteratorFactory`) dépendent aussi de `core.impl`, car les interfaces des filtres et des fabriques n'existent que là. Les mouvements qu'elles produisent sont ceux de l'API *preview* publique, et `MoveFactorySupport` ne dépend que de ceux-là. Leur filet est d'une autre nature que l'oracle : une montée de version qui change les types ne compile plus, et une qui cesse de les interroger sans le dire se voit dans les tests de scénario, comme un calcul qui ne converge plus.

`TimefoldInternalApiStructuralTest` tient l'inventaire, sept fichiers avec le service de diagnostic : un nouveau fichier qui importe `core.impl` fait échouer le build tant qu'il n'a pas nommé son filet. Les scénarios lourds qui servent de filet se lancent selon [les tests](tests.md).
