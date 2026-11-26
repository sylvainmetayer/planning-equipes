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

## Le banc des jours d'affilée

`ConsecutiveRunsBenchTest` mesure ce que coûterait la fusion des deux formes de la règle des jours d'affilée (`maxJoursConsecutifsTravailles`, medium, et `maxJoursConsecutifsTravaillesDur`, dure) en une règle à niveau réglable, et le retrait de l'ordre de construction par calendrier ([ADR 0068](repo:docs/decisions/0068-sous-la-regle-dure-la-construction-suit-le-calendrier.md)). Il ne change aucun comportement : les variantes sont des interrupteurs, un poids qui porte la règle medium au niveau dur ([ADR 0111](repo:docs/decisions/0111-le-niveau-d-une-regle-est-le-niveau-de-son-poids.md)) et une couture de test sur le comparateur de construction.

```bash
./mvnw test -Pscenario-tests -Dtest=ConsecutiveRunsBenchTest \
    -Dbench.scenario=festival-realiste-canicule.yaml -Dbench.engine=timefold \
    -Dbench.seconds=120 -Dbench.seeds=3 -Dbench.cap=6
```

La classe se lance aussi comme un `main` sur le classpath de test, pour faire tourner plusieurs scénarios dans des JVM parallèles. Chaque résolution dure tout son budget, sans arrêt sur plateau, graines 0 à 2. Les variantes sont `preference` (la forme medium seule : ce qui est livré, et la règle fusionnée au niveau préférence), `both` (les deux formes, comme un organisateur qui veut un plafond dur les allume aujourd'hui), `hard` (la forme dure seule : la règle fusionnée au niveau obligatoire), `hard-par-poids` (la forme medium dont le poids vaut un point dur : la règle fusionnée telle que l'ADR 0111 l'écrirait) et, sous Timefold seulement, `both-sans-calendrier` et `hard-sans-calendrier`. Le moteur direct construit toujours par rareté : l'ordre par calendrier n'existe pas chez lui, le retirer n'y change rien. Le plafond est fixé à six jours (`bench.cap`).

La dernière colonne relit chaque plan final sous la règle obligatoire : le nombre de graines dont aucun animateur ne dépasse le plafond.

Mesures du 26 novembre 2026, une machine de 12 cœurs et 24 Go (neuf JVM en parallèle, un cœur chacune), 120 s par résolution (15 à 30 s pour `gamme-28` et `gamme-16`), médianes de trois graines :

| Scénario | Moteur | Variante | Faisable (règles de la variante) | Temps jusqu'à la faisabilité | Hard (méd.) | Medium (méd.) | Soft (méd.) | Plafond tenu (règle obligatoire) |
|---|---|---|---|---|---|---|---|---|
| `festival-realiste-canicule` | direct | `preference` | 3/3 | 0.4 s | 0 | -16400 | -69935 | 3/3 |
| `festival-realiste-canicule` | direct | `both` | 3/3 | 0.4 s | 0 | -16395 | -70470 | 3/3 |
| `festival-realiste-canicule` | direct | `hard` | 3/3 | 0.3 s | 0 | -16395 | -70170 | 3/3 |
| `festival-realiste-canicule` | direct | `hard-par-poids` | 3/3 | 0.3 s | 0 | -16395 | -70180 | 3/3 |
| `festival-realiste-canicule` | Timefold | `preference` | 3/3 | 9.7 s | 0 | -18520 | -52480 | 3/3 |
| `festival-realiste-canicule` | Timefold | `both` | 3/3 | 7.6 s | 0 | -17405 | -48375 | 3/3 |
| `festival-realiste-canicule` | Timefold | `both-sans-calendrier` | 3/3 | 9.1 s | 0 | -18415 | -52615 | 3/3 |
| `festival-realiste-canicule` | Timefold | `hard` | 3/3 | 7.5 s | 0 | -17395 | -47975 | 3/3 |
| `festival-realiste-canicule` | Timefold | `hard-sans-calendrier` | 3/3 | 8.3 s | 0 | -18405 | -52310 | 3/3 |
| `festival-realiste-canicule` | Timefold | `hard-par-poids` | 3/3 | 8.3 s | 0 | -18400 | -52525 | 3/3 |
| `festival-hivernal` | direct | `preference` | 3/3 | 2.5 s | 0 | -23700 | -98785 | 0/3 |
| `festival-hivernal` | direct | `both` | 0/3 | jamais | -2 | -24285 | -98865 | 0/3 |
| `festival-hivernal` | direct | `hard` | 1/3 | 12.3 s | -1 | -24205 | -98715 | 1/3 |
| `festival-hivernal` | direct | `hard-par-poids` | 1/3 | 11.9 s | -1 | -24180 | -98785 | 1/3 |
| `festival-hivernal` | Timefold | `preference` | 3/3 | 27.5 s | 0 | -31105 | -95875 | 0/3 |
| `festival-hivernal` | Timefold | `both` | 0/3 | jamais | -16 | -30035 | -97215 | 0/3 |
| `festival-hivernal` | Timefold | `both-sans-calendrier` | 0/3 | jamais | -13 | -34715 | -103785 | 0/3 |
| `festival-hivernal` | Timefold | `hard` | 0/3 | jamais | -16 | -29890 | -97565 | 0/3 |
| `festival-hivernal` | Timefold | `hard-sans-calendrier` | 0/3 | jamais | -13 | -34655 | -103350 | 0/3 |
| `festival-hivernal` | Timefold | `hard-par-poids` | 0/3 | jamais | -20 | -34620 | -100345 | 0/3 |
| `scenario-complet` | direct | `preference` | 3/3 | 0.8 s | 0 | -18255 | -113000 | 0/3 |
| `scenario-complet` | direct | `both` | 3/3 | 0.9 s | 0 | -18360 | -43330 | 3/3 |
| `scenario-complet` | direct | `hard` | 3/3 | 0.9 s | 0 | -18350 | -42085 | 3/3 |
| `scenario-complet` | direct | `hard-par-poids` | 3/3 | 0.9 s | 0 | -18350 | -42085 | 3/3 |
| `scenario-complet` | Timefold | `preference` | 3/3 | 11.7 s | 0 | -22235 | -156930 | 0/3 |
| `scenario-complet` | Timefold | `both` | 3/3 | 10.8 s | 0 | -19495 | -121160 | 3/3 |
| `scenario-complet` | Timefold | `both-sans-calendrier` | 3/3 | 11.8 s | 0 | -20985 | -144835 | 3/3 |
| `scenario-complet` | Timefold | `hard` | 3/3 | 9.6 s | 0 | -19415 | -118720 | 3/3 |
| `scenario-complet` | Timefold | `hard-sans-calendrier` | 3/3 | 10.3 s | 0 | -20915 | -144525 | 3/3 |
| `scenario-complet` | Timefold | `hard-par-poids` | 3/3 | 11.0 s | 0 | -20865 | -145135 | 3/3 |
| `gamme-16` | direct | `preference` | 3/3 | 0.1 s | 0 | -5010 | -1735 | 3/3 |
| `gamme-16` | direct | `both` | 3/3 | 0.1 s | 0 | -4825 | -1720 | 3/3 |
| `gamme-16` | direct | `hard` | 3/3 | 0.1 s | 0 | -4825 | -1720 | 3/3 |
| `gamme-16` | direct | `hard-par-poids` | 3/3 | 0.1 s | 0 | -4825 | -1720 | 3/3 |
| `gamme-16` | Timefold | `preference` | 3/3 | 1.7 s | 0 | -6990 | -1300 | 3/3 |
| `gamme-16` | Timefold | `both` | 3/3 | 1.6 s | 0 | -6835 | -1395 | 3/3 |
| `gamme-16` | Timefold | `both-sans-calendrier` | 3/3 | 1.6 s | 0 | -6960 | -1310 | 3/3 |
| `gamme-16` | Timefold | `hard` | 3/3 | 1.7 s | 0 | -6865 | -1375 | 3/3 |
| `gamme-16` | Timefold | `hard-sans-calendrier` | 3/3 | 1.6 s | 0 | -6970 | -1300 | 3/3 |
| `gamme-16` | Timefold | `hard-par-poids` | 3/3 | 1.6 s | 0 | -6980 | -1310 | 3/3 |
| `gamme-20` | direct | `preference` | 3/3 | 2.3 s | 0 | -17100 | -21665 | 2/3 |
| `gamme-20` | direct | `both` | 3/3 | 1.9 s | 0 | -17710 | -15335 | 3/3 |
| `gamme-20` | direct | `hard` | 3/3 | 1.7 s | 0 | -17685 | -14235 | 3/3 |
| `gamme-20` | direct | `hard-par-poids` | 3/3 | 1.6 s | 0 | -17670 | -13910 | 3/3 |
| `gamme-20` | Timefold | `preference` | 3/3 | 33.8 s | 0 | -34615 | -122915 | 0/3 |
| `gamme-20` | Timefold | `both` | 3/3 | 26.9 s | 0 | -24385 | -67850 | 3/3 |
| `gamme-20` | Timefold | `both-sans-calendrier` | 3/3 | 29.2 s | 0 | -32910 | -122295 | 3/3 |
| `gamme-20` | Timefold | `hard` | 3/3 | 25.7 s | 0 | -24340 | -67585 | 3/3 |
| `gamme-20` | Timefold | `hard-sans-calendrier` | 3/3 | 29.0 s | 0 | -32925 | -122265 | 3/3 |
| `gamme-20` | Timefold | `hard-par-poids` | 3/3 | 27.7 s | 0 | -32580 | -122235 | 3/3 |
| `gamme-25` | direct | `preference` | 3/3 | 2.1 s | 0 | -13250 | -46440 | 3/3 |
| `gamme-25` | direct | `both` | 3/3 | 1.9 s | 0 | -13230 | -46465 | 3/3 |
| `gamme-25` | direct | `hard` | 3/3 | 1.9 s | 0 | -13240 | -46755 | 3/3 |
| `gamme-25` | direct | `hard-par-poids` | 3/3 | 1.9 s | 0 | -13230 | -46425 | 3/3 |
| `gamme-25` | Timefold | `preference` | 3/3 | 57.4 s | 0 | -26095 | -48100 | 3/3 |
| `gamme-25` | Timefold | `both` | 3/3 | 51.1 s | 0 | -18605 | -19250 | 3/3 |
| `gamme-25` | Timefold | `both-sans-calendrier` | 3/3 | 51.3 s | 0 | -25030 | -47250 | 3/3 |
| `gamme-25` | Timefold | `hard` | 3/3 | 44.1 s | 0 | -18475 | -19010 | 3/3 |
| `gamme-25` | Timefold | `hard-sans-calendrier` | 3/3 | 49.3 s | 0 | -25030 | -47260 | 3/3 |
| `gamme-25` | Timefold | `hard-par-poids` | 3/3 | 51.4 s | 0 | -25080 | -47240 | 3/3 |
| `gamme-28` | direct | `preference` | 0/3 | jamais | -3 | -180 | 0 | 0/3 |
| `gamme-28` | direct | `both` | 0/3 | jamais | -3 | -180 | 0 | 0/3 |
| `gamme-28` | direct | `hard` | 0/3 | jamais | -3 | -180 | 0 | 0/3 |
| `gamme-28` | direct | `hard-par-poids` | 0/3 | jamais | -3 | -180 | 0 | 0/3 |
| `gamme-28` | Timefold | `preference` | 0/3 | jamais | -3 | -180 | 0 | 0/3 |
| `gamme-28` | Timefold | `both` | 0/3 | jamais | -3 | -180 | 0 | 0/3 |
| `gamme-28` | Timefold | `both-sans-calendrier` | 0/3 | jamais | -3 | -180 | 0 | 0/3 |
| `gamme-28` | Timefold | `hard` | 0/3 | jamais | -3 | -180 | 0 | 0/3 |
| `gamme-28` | Timefold | `hard-sans-calendrier` | 0/3 | jamais | -3 | -180 | 0 | 0/3 |
| `gamme-28` | Timefold | `hard-par-poids` | 0/3 | jamais | -3 | -180 | 0 | 0/3 |

Ce qu'on en retient :

- **Le moteur direct** : la forme obligatoire coûte au plus 3,4 % de medium sur `gamme-20`, 0,5 % sur `scenario-complet`, rien sur `gamme-25` et la canicule, et fait mieux sur `gamme-16`. Elle tient le plafond partout où la grille le permet, ce que la préférence ne fait ni sur `scenario-complet`, ni sur `festival-hivernal`, ni toujours sur `gamme-20`. La faisabilité arrive en moins de 2 s, 12 s sur `festival-hivernal` dont la grille ne tient à six jours que dans une graine sur trois. `hard-par-poids` et `hard` donnent les mêmes plans à la graine près.
- **Timefold** : l'ordre par calendrier vaut 6 à 8 % de medium sur la canicule et `scenario-complet`, 35 % sur `gamme-20` et `gamme-25`, et 10 à 15 % de temps jusqu'à la faisabilité. La règle fusionnée par le poids (`hard-par-poids`) retombe sur `hard-sans-calendrier`, puisque rien n'indique au comparateur que la règle est obligatoire.
- `gamme-28` (infaisable par construction) l'est dans toutes les variantes, aux deux moteurs.

## Le banc des règles de repas

`MealRulesBenchTest` mesure ce que coûterait la fusion des deux règles de repas, `coupureRepasObligatoire` (la coupure due, dure) et `coupureRepasPlacementPrefere` (l'endroit de la fenêtre où elle est prise, souple), en une règle unique dont le placement préféré serait un paramètre, avant que personne ne l'écrive. Il ne change aucun comportement et ne demande aucune couture dans le code de production : les variantes sont des interrupteurs, des poids qui portent une règle à un autre niveau ([ADR 0111](repo:docs/decisions/0111-le-niveau-d-une-regle-est-le-niveau-de-son-poids.md)) et une réécriture des faits `FenetreRepas` du problème, dont l'indicateur `auPlusTard` est le placement que la règle fusionnée lirait comme paramètre.

```bash
./mvnw test -Pscenario-tests -Dtest=MealRulesBenchTest \
    -Dbench.scenario=festival-hivernal.yaml -Dbench.engine=hand \
    -Dbench.seconds=60 -Dbench.seeds=3
```

La classe se lance aussi comme un `main` sur le classpath de test. Chaque résolution dure tout son budget, sans arrêt sur plateau, graines 0 à 2. Les variantes sont `actuel` (ce qui est livré : coupure dure, placement souple), `fusion-dur` (une règle au niveau obligatoire : l'écart de placement compte un point dur par minute, comme la coupure manquante), `fusion-preference` (une règle au niveau préférence : coupure et placement pèsent chacun un point medium de poids 5 par minute), `sans-placement` (paramètre « aucune préférence » : la coupure seule), `placement-debut` et `placement-fin` (paramètre fixé au début, ou à la fin, de chaque fenêtre ; le livré est la fin à midi et le début le soir, placement souple).

Les trois dernières colonnes relisent chaque plan final sous les règles livrées : le nombre de graines dont toutes les règles dures sont tenues, coupure comprise, puis le medium et le soft sous ces règles. C'est la seule règle commune : les scores propres des variantes ne se comparent pas entre eux, le poids de `fusion-preference` multipliant les minutes par cinq.

Mesures du 26 novembre 2026, une machine de 8 cœurs et 30 Go (huit JVM en parallèle), 60 s par résolution (30 s pour `gamme-14`), médianes de trois graines :

| Scénario | Moteur | Variante | Faisable (règles de la variante) | Temps jusqu'à la faisabilité | Hard (méd.) | Medium (méd.) | Soft (méd.) | Dur tenu (règles livrées) | Medium (règles livrées) | Soft (règles livrées) |
|---|---|---|---|---|---|---|---|---|---|---|
| `festival-hivernal` | direct | `actuel` | 3/3 | 4.0 s | 0 | -25490 | -96235 | 3/3 | -25490 | -96235 |
| `festival-hivernal` | direct | `fusion-dur` | 0/3 | jamais | -234 | -27095 | -4230 | 0/3 | -27095 | -4230 |
| `festival-hivernal` | direct | `fusion-preference` | 3/3 | 4.5 s | 0 | -96890 | -4265 | 0/3 | -26690 | -73565 |
| `festival-hivernal` | direct | `sans-placement` | 3/3 | 2.8 s | 0 | -26015 | -4560 | 3/3 | -26015 | -121260 |
| `festival-hivernal` | direct | `placement-debut` | 3/3 | 3.1 s | 0 | -26020 | -11560 | 3/3 | -26020 | -122560 |
| `festival-hivernal` | direct | `placement-fin` | 3/3 | 3.1 s | 0 | -25810 | -99175 | 3/3 | -25810 | -99175 |
| `festival-hivernal` | Timefold | `actuel` | 3/3 | 36.0 s | 0 | -34800 | -91755 | 3/3 | -34800 | -91755 |
| `festival-hivernal` | Timefold | `fusion-dur` | 0/3 | jamais | -246 | -37105 | -1385 | 0/3 | -37105 | -1385 |
| `festival-hivernal` | Timefold | `fusion-preference` | 3/3 | 38.2 s | 0 | -109540 | -2045 | 1/3 | -35140 | -75825 |
| `festival-hivernal` | Timefold | `sans-placement` | 3/3 | 50.4 s | 0 | -36625 | -1645 | 3/3 | -36625 | -110150 |
| `festival-hivernal` | Timefold | `placement-debut` | 3/3 | 35.4 s | 0 | -35475 | -9750 | 3/3 | -35475 | -109975 |
| `festival-hivernal` | Timefold | `placement-fin` | 3/3 | 43.3 s | 0 | -35615 | -92520 | 3/3 | -35615 | -92520 |
| `festival-realiste-canicule` | direct | `actuel` | 3/3 | 1.3 s | 0 | -16620 | -66730 | 3/3 | -16620 | -66730 |
| `festival-realiste-canicule` | direct | `fusion-dur` | 0/3 | jamais | -100 | -15490 | -2715 | 0/3 | -15490 | -2715 |
| `festival-realiste-canicule` | direct | `fusion-preference` | 3/3 | 0.5 s | 0 | -47415 | -2690 | 3/3 | -17415 | -32690 |
| `festival-realiste-canicule` | direct | `sans-placement` | 3/3 | 0.6 s | 0 | -16665 | -2865 | 3/3 | -16665 | -81465 |
| `festival-realiste-canicule` | direct | `placement-debut` | 3/3 | 0.6 s | 0 | -16665 | -2895 | 3/3 | -16665 | -80595 |
| `festival-realiste-canicule` | direct | `placement-fin` | 3/3 | 0.5 s | 0 | -16620 | -65995 | 3/3 | -16620 | -65995 |
| `festival-realiste-canicule` | Timefold | `actuel` | 3/3 | 17.3 s | 0 | -19400 | -55090 | 3/3 | -19400 | -55090 |
| `festival-realiste-canicule` | Timefold | `fusion-dur` | 0/3 | jamais | -100 | -24220 | -2025 | 0/3 | -24220 | -2025 |
| `festival-realiste-canicule` | Timefold | `fusion-preference` | 3/3 | 14.2 s | 0 | -49760 | -2215 | 3/3 | -19600 | -32245 |
| `festival-realiste-canicule` | Timefold | `sans-placement` | 3/3 | 17.1 s | 0 | -19500 | -2180 | 3/3 | -19500 | -72355 |
| `festival-realiste-canicule` | Timefold | `placement-debut` | 3/3 | 17.4 s | 0 | -19555 | -2185 | 3/3 | -19555 | -72360 |
| `festival-realiste-canicule` | Timefold | `placement-fin` | 3/3 | 17.5 s | 0 | -19400 | -54760 | 3/3 | -19400 | -54760 |
| `gamme-14` | direct | `actuel` | 3/3 | 0.1 s | 0 | -4600 | 0 | 3/3 | -4600 | 0 |
| `gamme-14` | direct | `fusion-dur` | 3/3 | 0.0 s | 0 | -5140 | 0 | 3/3 | -5140 | 0 |
| `gamme-14` | direct | `fusion-preference` | 3/3 | 0.0 s | 0 | -5145 | 0 | 3/3 | -5145 | 0 |
| `gamme-14` | direct | `sans-placement` | 3/3 | 0.0 s | 0 | -5200 | 0 | 3/3 | -5200 | -3075 |
| `gamme-14` | direct | `placement-debut` | 3/3 | 0.0 s | 0 | -4600 | 0 | 3/3 | -4600 | -3525 |
| `gamme-14` | direct | `placement-fin` | 3/3 | 0.0 s | 0 | -4600 | 0 | 3/3 | -4600 | 0 |
| `gamme-14` | Timefold | `actuel` | 3/3 | 0.6 s | 0 | -4655 | -150 | 3/3 | -4655 | -150 |
| `gamme-14` | Timefold | `fusion-dur` | 3/3 | 0.8 s | 0 | -4670 | 0 | 3/3 | -4670 | 0 |
| `gamme-14` | Timefold | `fusion-preference` | 3/3 | 0.5 s | 0 | -4680 | 0 | 3/3 | -4680 | 0 |
| `gamme-14` | Timefold | `sans-placement` | 3/3 | 0.5 s | 0 | -4640 | 0 | 3/3 | -4640 | -3450 |
| `gamme-14` | Timefold | `placement-debut` | 3/3 | 0.5 s | 0 | -4675 | 0 | 3/3 | -4675 | -3375 |
| `gamme-14` | Timefold | `placement-fin` | 3/3 | 0.5 s | 0 | -4660 | -150 | 3/3 | -4660 | -150 |
| `gamme-20` | direct | `actuel` | 3/3 | 1.7 s | 0 | -18355 | -26815 | 3/3 | -18355 | -26815 |
| `gamme-20` | direct | `fusion-dur` | 3/3 | 1.6 s | 0 | -22680 | -11700 | 3/3 | -22680 | -11700 |
| `gamme-20` | direct | `fusion-preference` | 3/3 | 1.6 s | 0 | -22455 | -11725 | 3/3 | -22455 | -11725 |
| `gamme-20` | direct | `sans-placement` | 3/3 | 1.6 s | 0 | -19545 | -11795 | 3/3 | -19545 | -76820 |
| `gamme-20` | direct | `placement-debut` | 3/3 | 1.5 s | 0 | -18205 | -33560 | 3/3 | -18205 | -86165 |
| `gamme-20` | direct | `placement-fin` | 3/3 | 1.5 s | 0 | -18205 | -63290 | 3/3 | -18205 | -33890 |
| `gamme-20` | Timefold | `actuel` | 3/3 | 27.1 s | 0 | -35100 | -118770 | 3/3 | -35100 | -118770 |
| `gamme-20` | Timefold | `fusion-dur` | 3/3 | 32.3 s | 0 | -38260 | -10880 | 3/3 | -38260 | -10880 |
| `gamme-20` | Timefold | `fusion-preference` | 3/3 | 33.1 s | 0 | -99960 | -10985 | 0/3 | -46025 | -39185 |
| `gamme-20` | Timefold | `sans-placement` | 3/3 | 31.8 s | 0 | -38165 | -11845 | 3/3 | -38165 | -132015 |
| `gamme-20` | Timefold | `placement-debut` | 3/3 | 29.6 s | 0 | -37450 | -119385 | 3/3 | -37450 | -131970 |
| `gamme-20` | Timefold | `placement-fin` | 3/3 | 29.5 s | 0 | -37260 | -153670 | 3/3 | -37260 | -118270 |
| `scenario-complet` | direct | `actuel` | 3/3 | 2.1 s | 0 | -18205 | -111355 | 3/3 | -18205 | -111355 |
| `scenario-complet` | direct | `fusion-dur` | 0/3 | jamais | -71 | -21355 | -1070 | 0/3 | -21355 | -1070 |
| `scenario-complet` | direct | `fusion-preference` | 3/3 | 0.7 s | 0 | -41020 | -305 | 3/3 | -19720 | -21745 |
| `scenario-complet` | direct | `sans-placement` | 3/3 | 0.9 s | 0 | -18200 | -545 | 3/3 | -18200 | -179345 |
| `scenario-complet` | direct | `placement-debut` | 3/3 | 0.8 s | 0 | -18170 | -108035 | 3/3 | -18170 | -182435 |
| `scenario-complet` | direct | `placement-fin` | 3/3 | 0.8 s | 0 | -18170 | -2055 | 3/3 | -18170 | -146655 |
| `scenario-complet` | Timefold | `actuel` | 3/3 | 19.0 s | 0 | -25340 | -160975 | 3/3 | -25340 | -160975 |
| `scenario-complet` | Timefold | `fusion-dur` | 0/3 | jamais | -100 | -27795 | -1135 | 0/3 | -27795 | -1135 |
| `scenario-complet` | Timefold | `fusion-preference` | 3/3 | 14.1 s | 0 | -55395 | -715 | 3/3 | -24795 | -31315 |
| `scenario-complet` | Timefold | `sans-placement` | 3/3 | 18.7 s | 0 | -24275 | -855 | 3/3 | -24275 | -176575 |
| `scenario-complet` | Timefold | `placement-debut` | 3/3 | 19.4 s | 0 | -25510 | -155090 | 3/3 | -25510 | -180835 |
| `scenario-complet` | Timefold | `placement-fin` | 3/3 | 19.7 s | 0 | -24885 | -35335 | 3/3 | -24885 | -171815 |
| `scenario-continu-avec-coupure` | direct | `actuel` | 3/3 | 3.6 s | 0 | -24745 | -51165 | 3/3 | -24745 | -51165 |
| `scenario-continu-avec-coupure` | direct | `fusion-dur` | 0/3 | jamais | -224 | -29980 | -1090 | 0/3 | -29980 | -1090 |
| `scenario-continu-avec-coupure` | direct | `fusion-preference` | 3/3 | 2.0 s | 0 | -52215 | -1120 | 1/3 | -29815 | -23410 |
| `scenario-continu-avec-coupure` | direct | `sans-placement` | 3/3 | 2.2 s | 0 | -24805 | -805 | 3/3 | -24805 | -106015 |
| `scenario-continu-avec-coupure` | direct | `placement-debut` | 3/3 | 1.8 s | 0 | -24610 | -12415 | 3/3 | -24610 | -83075 |
| `scenario-continu-avec-coupure` | direct | `placement-fin` | 3/3 | 2.2 s | 0 | -24805 | -56360 | 3/3 | -24805 | -56360 |
| `scenario-continu-avec-coupure` | Timefold | `actuel` | 3/3 | 34.0 s | 0 | -29125 | -73740 | 3/3 | -29125 | -73740 |
| `scenario-continu-avec-coupure` | Timefold | `fusion-dur` | 0/3 | jamais | -241 | -32490 | -585 | 0/3 | -32490 | -585 |
| `scenario-continu-avec-coupure` | Timefold | `fusion-preference` | 3/3 | 30.5 s | 0 | -65220 | -600 | 0/3 | -34665 | -24915 |
| `scenario-continu-avec-coupure` | Timefold | `sans-placement` | 3/3 | 43.4 s | 0 | -29270 | -580 | 3/3 | -29270 | -108585 |
| `scenario-continu-avec-coupure` | Timefold | `placement-debut` | 3/3 | 44.7 s | 0 | -29735 | -13765 | 3/3 | -29735 | -91540 |
| `scenario-continu-avec-coupure` | Timefold | `placement-fin` | 3/3 | 44.7 s | 0 | -29670 | -73725 | 3/3 | -29670 | -73725 |

Ce qu'on en retient :

- **Un seul niveau pour les deux termes est exclu.** `fusion-dur` ne trouve aucun plan faisable sur `festival-hivernal`, `festival-realiste-canicule`, `scenario-complet` et `scenario-continu-avec-coupure`, aux deux moteurs : 24 résolutions sur 24 ne sont jamais faisables, et la médiane des points durs de violation de chaque ligne va de 71 à 246. Là où elle reste faisable, elle coûte du medium : 24 % sur `gamme-20` au moteur direct, 9 % sous Timefold.
- **Le niveau préférence relâche la coupure.** Sous `fusion-preference`, les plans finals tiennent toutes les règles dures livrées dans 3 graines sur 3 sur la canicule, `scenario-complet` et `gamme-14`, mais pas sur `festival-hivernal` (0/3 au moteur direct, 1/3 sous Timefold), `scenario-continu-avec-coupure` (1/3 et 0/3) ni `gamme-20` sous Timefold (0/3) : une coupure devenue préférence est sacrifiée quand la grille est serrée.
- **Le placement comme paramètre ne coûte rien de mesurable.** Dans les variantes `sans-placement`, `placement-debut` et `placement-fin`, la faisabilité reste de 3/3 partout et le medium sous les règles livrées reste à moins de 3 % de `actuel` au moteur direct, hors `gamme-14` (13 % pour `sans-placement`, sur de petits nombres) et `gamme-20` (6 % pour `sans-placement`) ; sous Timefold il bouge jusqu'à 9 % selon le scénario, dans les deux sens, sans que la variante l'explique. Le temps jusqu'à la faisabilité ne change pas au moteur direct ; sous Timefold il bouge sur trois scénarios : `scenario-continu-avec-coupure` (34 s contre 43 à 45 s), `festival-hivernal` (36 s contre 43 à 50 s, hors `placement-debut`) et `gamme-20` (27 s contre 29 à 33 s).
- **Le sens du placement pèse sur le soft, pas sur le reste.** Le soft propre de `placement-debut` est environ huit fois plus bas que celui de `actuel` sur `festival-hivernal` (-11 560 contre -96 235), celui de `placement-fin` lui est égal : sur ce scénario, presque tout l'écart livré est la préférence « tard à midi ». Le sens qui coûte dépend du scénario : sur `scenario-complet` c'est `placement-debut` qui égale `actuel` (-108 035 contre -111 355) et `placement-fin` qui tombe à -2 055. `sans-placement` laisse le soft livré se dégrader (par exemple -76 820 contre -26 815 sur `gamme-20`) : la préférence agit bien.
- `gamme-14` (peu de repas à arbitrer) sépare à peine les variantes : faisabilité et temps sont les mêmes, le medium ne bouge que de 4 600 à 5 200 sur de petits nombres, et seul le soft livré montre que la préférence agit (0 pour `actuel`, -3 075 pour `sans-placement`).

## Le moteur direct : deux tests qui tiennent les règles en double

`solver/handsearch` écrit les 44 règles une seconde fois, pour chercher sans traverser les contraintes en flux ([ADR 0087](repo:docs/decisions/0087-un-second-moteur-de-recherche-arbitre-par-timefold.md), moteur par défaut depuis l'[ADR 0088](repo:docs/decisions/0088-le-moteur-direct-devient-le-moteur-par-defaut.md)). Deux tests de la suite par défaut, sans conteneur, empêchent les deux exemplaires de diverger.

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
