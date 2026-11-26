# Tester le solveur : scénarios, banc, moteur direct et mutation

Vous saurez où vivent les scénarios de test et lesquels tournent dans la suite par défaut, comment mesurer une résolution ou une variante de règles avant de la committer, comment les deux moteurs sont tenus d'accord et comment la mutation éprouve les tests de contraintes.

## Où vivent les scénarios

Tous les scénarios (fixtures écrites à la main, gamme, cas extrêmes) sont à plat dans le seul dossier `src/main/resources/scenarios/`. Aucun sous-dossier, aucun second dossier `scenarios/` ailleurs sur le classpath : `ScenarioYamlReader.scenarioPath` refuse un nom qui porte un composant de chemin, et `getResource("scenarios")` ne rend que la première occurrence du classpath, jamais leur union. Un fichier rangé autrement est un fichier que personne ne peut charger depuis la carte « Exemples » de Fichiers › Importer. Un fichier ajouté au dossier y apparaît sans rien coder ; son libellé lisible et sa phrase (et leur traduction) s'ajoutent dans `pages/imports/exemples.ts`.

Côté tests, `ScenariosLivres` est la seule lecture de ce dossier :

- `all()` : tout, pour les contrôles qui valent pour chaque fichier (validateur, binder) ;
- `references()` : les fixtures écrites à la main seulement, pour les tests différentiels qui figent une forme canonique par fichier (une référence engendrée pour un fichier engendré ne serait lue par personne) ;
- `noms(prefixe)` : la gamme ou les extrêmes, pour leurs contrôles de catalogue.

## Ce qui est lent est étiqueté

Les tests de cette classe de poids sont étiquetés `@Tag("scenario-lent")`, exclus de `./mvnw test` et `verify` par la propriété `test.excludedGroups` du `pom.xml` et du workflow principal. Étiquetez ainsi tout futur test de ce poids plutôt que de ralentir la boucle ordinaire. `./mvnw test -Pscenario-tests` lève l'exclusion (les `scenario-extreme` restent exclus), éventuellement restreint par `-Dtest=…`. Ce sont notamment `PlanningServiceScenarioCompletTest`, `PlanningServiceScenarioContinuTest`, `PlanningServiceScenarioFestivalRealisteTest`, `PlanningServiceScenarioFestivalHivernalTest` et `PlanningServiceScenarioPublishedRunCapTest`, qui résolvent de grands problèmes jusqu'à zéro dur. `festival-realiste-canicule.yaml` et `festival-hivernal.yaml` sont des fixtures réelles anonymisées, les seules dont les stands portent des horaires récurrents : un harnais en Java pur doit les développer par `HoraireStandResolver.appliquer` avant de construire les postes, sinon il résout un problème cinq fois trop grand.

La CI les joue seulement quand quelque chose qui peut casser la convergence change : `.github/workflows/scenario-tests.yml` se déclenche sur `solver/`, `domain/`, `solverConfig.xml`, `application.properties`, les fichiers de scénario et `pom.xml`. Gardez cette liste de chemins à jour quand ces fichiers bougent : un réglage du solveur livré sans que ces tests aient tourné est le trou que ce workflow ferme. Lancez-les en tâche de fond depuis une session d'agent. Sur une montée de Timefold, ils sont exactement ce qu'il faut rejouer, avec `ConstraintDiagnosticServiceContractTest`.

## La gamme de scénarios

Les trente fichiers `gamme-…` sont rangés par taille : un jour, deux stands et trois animateurs au barreau 1 ; un mois, 150 stands et 320 animateurs au barreau 25. Chaque barreau exerce une partie de ce qu'un fichier sait dire : rotation du midi, stands premium, mineurs et jour férié, exceptions ad hoc, horaires récurrents sous toutes leurs portées, relais repas à effectif plein ou réduit, journées types, contraintes désactivées, effectifs portés par la fenêtre, édition cible. Les barreaux 26 à 30 ne doivent jamais se résoudre, chacun pour une raison nommée, et le test dit si l'analyse de faisabilité la voit.

Le nom d'un fichier est son index : `gamme-18-14j-35stands-132animateurs-effectifs-par-fenetre`. `ScenarioLadderCatalogTest` échoue si un nom ne dit plus les jours, stands et animateurs du fichier, si un fichier n'est joué par aucun test ou si la numérotation a un trou.

Le problème est construit comme en production, sans base : `ScenarioLadder` résout les horaires, découpe les amplitudes, numérote les vacations découpées comme le ferait la base et applique `contraintes.desactivees`. Après le score dur, `assertCoreRules` relit le plan lui-même (jours d'indisponibilité, chevauchements, mineurs, six jours par semaine, exceptions ad hoc) pour qu'une contrainte qui cesserait de pénaliser ne passe pas au vert avec lui.

| Classe | Barreaux | Où |
| --- | --- | --- |
| `ScenarioLadderSmallTest` | 1 à 7 | suite par défaut |
| `ScenarioLadderMediumTest` | 8 à 15 | suite par défaut |
| `ScenarioLadderInfeasibleTest` | 26 à 30 | suite par défaut |
| `ScenarioLadderImportTest` | 2, 7, 8, 10 | suite par défaut (`@QuarkusTest`), rejoue ces barreaux par l'import réel et vérifie que les deux chemins produisent les mêmes sièges |
| `ScenarioLadderLargeTest` | 16 à 25 | `scenario-lent`, `-Pscenario-tests` |
| `StaffingBoundEquivalenceTest` | 1 à 25 | `scenario-lent` : la borne exacte du besoin contre le besoin réel de l'équipe fictive, rang par rang. Le besoin vient de la recherche, ou, aux rangs 10, 11, 18 et 20, d'une preuve exacte (`scripts/besoin-exact/modele.py`, CP-SAT, hors image : [ADR 0098](repo:docs/decisions/0098-la-borne-exacte-s-affiche-d-abord-le-calcul-reste-le-defaut.md)) ; `-Dequivalence.rungs=1,2` pour en choisir |

Les barreaux sont dimensionnés avec de la marge, au plus trois quarts de l'effectif sollicité le jour le plus chargé : tous atteignent zéro écart dur dès l'heuristique de construction, de sorte que ce qu'ils relisent ne dépend pas de la vitesse de la machine. Ils gardent la couverture fonctionnelle ; la convergence d'un problème tendu reste l'affaire des scénarios `festival-*`. Quand une évolution fait bouger un barreau, c'est une décision : corrigez le fichier ou l'assertion, en disant pourquoi, jamais les deux en silence.

## Les scénarios extrêmes

Les quinze fichiers `extreme-…` poussent chaque dimension au-delà de ce qu'une édition réelle demande, pour savoir où l'application cède avant qu'une édition ne le découvre. Même harnais que la gamme, mêmes invariants relus sur le plan, et `ScenarioExtremeCatalogTest` (suite par défaut) vérifie que chaque fichier est valide, joué par un test et nommé d'après sa taille.

| Fichier | Limite explorée |
| --- | --- |
| `extreme-01` | 1 000 animateurs pour 240 sièges |
| `extreme-02` | un mois, 150 stands, 1 000 animateurs |
| `extreme-03` | 120 jours, six jours fériés, saisonniers |
| `extreme-04` | 120 jours sans relâche, 15 % de marge |
| `extreme-05` | 500 stands le même jour |
| `extreme-06` | exploitation 24 h/24 pendant une semaine |
| `extreme-07` | vacations d'une heure |
| `extreme-08` | un stand de 200 places |
| `extreme-09` | tout à la fois : 120 jours, 400 stands, 1 000 animateurs |
| `extreme-10` à `15` | sans animateur, un siège pour 1 000, uniquement des mineurs, 2 000 exceptions ad hoc, stands jamais ouverts, sans créneau |

Ce qui cède en premier est l'heuristique de construction : elle évalue chaque siège contre chaque candidat, son coût suit sièges × animateurs, et elle ne se parallélise pas sans l'édition Enterprise. Au-delà de 15 millions de couples, elle est échantillonnée (voir [le réglage du solveur](regler-le-solveur.md)). Lecture, construction du problème, analyse et recherche locale jusqu'au zéro dur tiennent l'échelle. Le contrôle des contradictions ad hoc lit les exceptions par index d'animateur, parce que toutes ses règles supposent un animateur en commun : `ContrainteAdHocContradictionsIndexTest` tient cette lecture égale à la comparaison deux à deux, ordre compris.

Les cas dégénérés (`ScenarioExtremeDegenerateTest`) tournent dans la suite par défaut. Les axes et le cumul (`ScenarioExtremeAxisTest`, `ScenarioExtremeCumulTest`) portent le tag `scenario-extreme`, exclu de la suite par défaut et du profil `scenario-tests` : une classe à la fois, avec un tas à la mesure, rien d'autre ne tournant en parallèle.

```bash
./mvnw test -Pscenario-extreme -DargLine=-Xmx3g -Dtest=ScenarioExtremeAxisTest
```

Leurs plafonds de temps valent une dizaine de fois le temps mesuré : ils disent qu'une régression a eu lieu, pas combien de temps prend la machine. Un tas plus grand n'y gagne rien, et c'est lui que la pression mémoire d'une machine partagée tue en premier.

## Le banc du solveur

Des classes de `service/solve`, étiquetées `scenario-lent`, servent à mesurer une résolution plutôt qu'à la valider. Elles ne font rien sans leurs propriétés système, donc `-Pscenario-tests` les ignore.

`SolverBenchTest` résout un scénario sous la recherche de production et écrit la courbe de son meilleur score : un fichier de `scenarios/` par son nom, ou n'importe quel YAML par son chemin absolu, de sorte qu'une édition exportée se mesure sans être versionnée. Le résultat s'ajoute en une ligne Markdown à `target/bench/results.md` et la courbe complète en CSV.

```bash
./mvnw test -Pscenario-tests -Dtest=SolverBenchTest \
    -Dbench.scenario=festival-hivernal.yaml -Dbench.label=avant
```

| Option | Effet |
| --- | --- |
| `bench.seconds`, `bench.plateau` | budget et arrêt sur plateau (900 et 300 par défaut) |
| `bench.stopWhenFeasible=true` | la phase de faisabilité seule |
| `bench.calculations=N` | arrêt après N calculs de score : deux configurations qui cherchent de la même façon finissent sur le même plan, quelle que soit leur vitesse, ce qui prouve qu'une réécriture de contrainte n'a changé que le coût |
| `bench.drWindow`, `bench.drRatio` | une terminaison par rendements décroissants à la place du plateau |
| `bench.seed` | la graine |
| `bench.jfr=<fichier>` | enregistre le solve seul au Java Flight Recorder (réglage `profile`), à lire avec `jfr print --events jdk.ExecutionSample` |
| `bench.engine=hand` | mesure le moteur direct, sous le même budget, le même plateau et la même graine ; `calculations`, `stopWhenFeasible` et `drWindow`, qui réécrivent la terminaison de Timefold, sont alors sans effet |

`RuleBenchTest` mesure ce que coûterait un changement de règles avant de l'écrire. Chaque scénario est résolu deux fois, au même budget et à la même graine, tel quel puis avec la variante. Le banc écrit le score par niveau avant et après, le temps jusqu'à la faisabilité, les sièges changés de main et un verdict contre le seuil de décision (au plus 5 % de medium perdu). La variante se dit par `bench.off=regleA,regleB` (règles désactivées : une suppression, ou ce qu'une fusion remplacerait) et/ou `bench.level=regle:HARD|MEDIUM|SOFT[:poids]` (une règle changée de niveau). Les scénarios sont les `gamme-*` (hors `infaisable`) et les `festival-*`, restreints par `bench.only=gamme-01,gamme-02` ; `bench.scenario=<chemin absolu>` ajoute un fichier local lu en place, jamais copié dans le dépôt. Par solve : `bench.seconds` (60), `bench.plateau` (30), `bench.seed` (0), `bench.engine=timefold` pour la recherche de Timefold au lieu du moteur direct, `bench.label`.

Le plan de la variante est re-scoré sous les règles actuelles : un plan résolu sans une règle se note mieux par ses propres règles qu'il ne le mérite, le verdict se lit sur ce medium re-scoré, et un hard pire qu'avant refuse toujours. Les sièges changés se comptent sur la clé naturelle (stand, jour, horaires) par la comparaison de `ChangementsJourneeService`, jamais sur l'id du poste. Un scénario en erreur garde sa ligne puis fait échouer le test. Les sorties sont `target/bench/regles-<label>.md` et `.csv`.

```bash
# essai court : deux marches, quelques secondes chacune
./mvnw test -Pscenario-tests -Dtest=RuleBenchTest \
    -Dbench.off=preserverBufferPolyvalents -Dbench.only=gamme-05,gamme-08 \
    -Dbench.seconds=8 -Dbench.plateau=4 -Dbench.label=essai
```

Trois autres classes complètent le banc :

- `HandSearchFuzzTest` chasse les plans que le moteur direct et les contraintes ne scorent pas pareil. Pendant `-Dfuzz.seconds=N` (60 par défaut), sur `-Dfuzz.scenario=…` (nom versionné ou chemin absolu), il tire des variations du problème, compare une série de plans règle par règle, puis promène le score incrémental sur des milliers de mouvements en contrôlant son total. Chaque divergence s'écrit dans `-Dfuzz.out=<dossier>` avec de quoi la rejouer (`-Dfuzz.seed=S -Dfuzz.round=N`), sans rien d'autre que des identifiants, et le test échoue à la fin s'il en a trouvé.
- `HandSearchThroughputTest` chronomètre le moteur direct sur un travail fixé (`-Dthroughput.scenario=…`) : une descente semée du score incrémental, et une recherche arrêtée après un nombre donné de regards sur son drapeau d'arrêt, en mouvements par seconde après quelques passes de chauffe. Deux versions qui décident pareil font les mêmes mouvements : le temps ne mesure que leur vitesse, et le condensé imprimé dit si le travail était bien le même.
- `TerminationReplayTest` rejoue des règles d'arrêt sur une courbe enregistrée (`-Dreplay.curve=<csv>`) : le plateau à plusieurs durées, les rendements décroissants de Timefold à plusieurs fenêtres et ratios, un gain minimal de medium sur une fenêtre. Une règle qui ne lit que le meilleur score au fil du temps s'arrête en un point de la courbe, donc une résolution longue par scénario répond pour toutes les variantes.

Pour mesurer comme sur l'hôte de production, bornez la JVM du banc par `-DargLine` (le tas à 75 % de la limite du conteneur et le ramasse-miettes de l'image) :

```bash
./mvnw test -Pscenario-tests -Dtest=SolverBenchTest \
    "-DargLine=-Xmx1536m -XX:+UseParallelGC -XX:ActiveProcessorCount=2" \
    -Dbench.scenario=festival-hivernal.yaml -Dbench.engine=hand \
    -Dbench.seconds=300 -Dbench.seed=0 -Dbench.label=petite-machine
```

Les graines étant fixées, deux runs d'une même configuration tracent la même courbe, aux secondes près. Les mesures qui justifient un réglage de `solverConfig.xml` viennent de ce banc.

## Le moteur direct : deux tests qui tiennent les règles en double

`solver/handsearch` écrit les 46 règles une seconde fois, pour chercher sans traverser les contraintes en flux ([ADR 0087](repo:docs/decisions/0087-un-second-moteur-de-recherche-arbitre-par-timefold.md), moteur par défaut depuis l'[ADR 0088](repo:docs/decisions/0088-le-moteur-direct-devient-le-moteur-par-defaut.md)). Deux tests de la suite par défaut, sans conteneur, empêchent les deux exemplaires de diverger.

`HandSearchCoverageStructuralTest` exige que toute contrainte de `ConstraintCatalog` ait sa constante dans `Rule` et que toute constante soit comptée dans le score du moteur. Il échoue le premier, par son nom, quand une contrainte n'est ajoutée que d'un côté.

`HandSearchDifferentialTest` compare le score du moteur à celui des contraintes, contrainte par contrainte, sur onze cas et une douzaine de plans par cas. Les cas sont de petits scénarios de la gamme, tels quels ou avec les branches fabriquées par `HandSearchFixtures.fabricateBranches` : verrous, sièges épinglés, passé, plan publié, sièges scindés (dont après minuit), plafonds, exceptions ad hoc, anniversaires, dates de naissance inconnues. Chaque plan est comparé deux fois : son score relu de zéro, et le score que la recherche a tenu mouvement par mouvement, qui doit être celui des contraintes aussi. Un contrôle final nomme toute contrainte jamais comparée sur un score non nul. Un cas de taille réelle (`festival-hivernal`, `gamme-25`) est étiqueté `scenario-lent`, et une modification de règle, dans l'un ou l'autre moteur, appelle sa relance.

Ajouter une contrainte demande donc trois écritures : le flux dans `solver/constraints`, le catalogue, et son terme dans `IncrementalScore` (ou dans `FlatProblem.staticTerms` si elle ne lit qu'un siège et son titulaire). Quand le différentiel échoue, son message donne la contrainte et les deux valeurs, « moteur / contraintes », sur le plan fautif. [Ajouter une règle](ajouter-une-regle.md) déroule le geste complet.

Quatre classes tiennent ce qui entoure la recherche :

- `HandSearchSolverTest` : le moteur derrière l'interface du solveur, l'arrêt, et le refus d'un plan que l'arbitre ne score pas pareil.
- `SolveEnginesPerimetersTest` : périmètre incrémental, réamorçage, verrous, passé figé et plan publié, construits par les pièces mêmes de `ProblemBuilder` puis résolus par `SolveRunner.solvePrepared` une fois par moteur. Un siège épinglé ne bouge sous aucun des deux, la recherche part du plan en place, le plan rendu est celui que les contraintes scorent. Sans conteneur ; son cas `scenario-lent` joue un jour rouvert et un réamorçage sur `festival-hivernal`.
- `SolverJobHandEngineTest` : l'application démarrée sous `planning.solver.engine=hand`. Le réglage atteint la couture, et un job passé par la file laisse sa courbe, son plan enregistré et son analyse des contraintes, et s'arrête quand on l'arrête, en résolution complète, réamorcée et incrémentale. Elle démarre l'application, donc figure dans `container-tests.txt`.
- `PlateauTest` : l'arrêt sur plateau du moteur direct, sur des suites de scores datées à la main. Les instants attendus sont déduits des sources des terminaisons de Timefold, que le test ne fait pas tourner.

## Les tests de mutation

La couverture dit qu'une ligne a tourné, pas qu'un test échouerait si elle était fausse. PIT le mesure sur les contraintes du solveur : il altère leur bytecode (une comparaison inversée, un `>` devenu `>=`, une pénalité annulée), rejoue les `*ConstraintsTest` sur chaque mutant et compte ceux qu'aucun test ne remarque.

```bash
./mvnw -Pmutation test-compile pitest:mutationCoverage   # environ 4 min
# rapport : target/pit-reports/index.html, mutant par mutant
```

Le profil `mutation` vise `dev.sylvain.planning.solver.constraints.*` et exclut les étiquettes `scenario-lent` et `scenario-extreme`. `mutationThreshold` vaut 80 : sous ce seuil, le build échoue. Un mutant survivant se tue par un cas de test, le plus souvent la valeur limite qu'aucun test ne posait (exactement le repos minimal, exactement l'âge de la majorité). Le seuil ne descend jamais ; il monte quand la mesure monte. Le workflow `mutation.yml` joue le contrôle chaque mardi, et sur une pull request qui touche `solver/constraints/` ou ses tests.
