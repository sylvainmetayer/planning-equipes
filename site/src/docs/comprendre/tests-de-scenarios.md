# Tester le solveur : scénarios, banc, moteur direct et mutation

Vous saurez où vivent les scénarios de test et lesquels tournent dans la suite par défaut, comment mesurer une résolution ou une variante de règles avant de la committer, comment les deux moteurs sont tenus d'accord et comment la mutation éprouve les tests de contraintes.

## Où vivent les scénarios

Tous les scénarios (fixtures écrites à la main, gamme, cas extrêmes) sont à plat dans le seul dossier `src/main/resources/scenarios/`. Aucun sous-dossier, aucun second dossier `scenarios/` ailleurs sur le classpath : `ScenarioYamlReader.scenarioPath` refuse un nom qui porte un composant de chemin, et `getResource("scenarios")` ne rend que la première occurrence du classpath, jamais leur union. Un fichier rangé autrement est un fichier que personne ne peut charger depuis la carte « Exemples » de Fichiers › Importer. Un fichier ajouté au dossier y apparaît sans rien coder ; son libellé lisible et sa phrase (et leur traduction) s'ajoutent dans `pages/imports/exemples.ts`.

Côté tests, `ScenariosLivres` est la seule lecture de ce dossier :

- `all()` : tout, pour les contrôles qui valent pour chaque fichier (validateur, binder) ;
- `references()` : les fixtures écrites à la main seulement, pour les tests différentiels qui figent une forme canonique par fichier (une référence engendrée pour un fichier engendré ne serait lue par personne) ;
- `noms(prefixe)` : la gamme ou les extrêmes, pour leurs contrôles de catalogue.

## Ce qui est lent est étiqueté

Les tests de cette classe de poids sont étiquetés `@Tag("scenario-lent")`, exclus de `./mvnw test` et `verify` par la propriété `test.excludedGroups` du `pom.xml` et du workflow principal. Étiquetez ainsi tout futur test de ce poids plutôt que de ralentir la boucle ordinaire. `./mvnw test -Pscenario-tests` lève l'exclusion (les `scenario-extreme` restent exclus), éventuellement restreint par `-Dtest=…`. Ce sont notamment `PlanningServiceScenarioCompletTest`, `PlanningServiceScenarioContinuTest`, `PlanningServiceScenarioFestivalRealisteTest`, `PlanningServiceScenarioFestivalHivernalTest` et `PlanningServiceScenarioPublishedRunCapTest`, qui résolvent de grands problèmes jusqu'à zéro dur. `festival-realiste-canicule.yaml` et `festival-hivernal.yaml` sont des fixtures réelles anonymisées, écrites avant les plages, leurs stands portent encore des horaires récurrents : le lecteur les convertit en plages (maillon 4 → 5, [ADR 0104](repo:docs/decisions/0104-les-horaires-d-un-stand-sont-une-liste-de-plages-datees.md)), si bien qu'un harnais en Java pur n'a plus rien à développer avant de construire les postes. `ShippedSchedulesDigestTest` garde, pour chacun des scénarios livrés, le condensé des fenêtres que la 3.x en tirait jour par jour.

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

`ConsecutiveRunsBenchTest` mesure la règle des jours d'affilée à ses deux niveaux : `preference`, ce qui est livré, et `obligatoire`, son poids porté à un point dur ([ADR 0111](repo:docs/decisions/0111-le-niveau-d-une-regle-est-le-niveau-de-son-poids.md)). Écrit avant la fusion des deux formes de la 3.x, il en a donné le prix, et celui du retrait de l'ordre de construction par calendrier ([ADR 0068](repo:docs/decisions/0068-sous-la-regle-dure-la-construction-suit-le-calendrier.md), retiré par l'[ADR 0118](repo:docs/decisions/0118-une-seule-regle-de-jours-d-affilee-a-niveau-reglable.md)) : ce sont les mesures plus bas.

```bash
./mvnw test -Pscenario-tests -Dtest=ConsecutiveRunsBenchTest \
    -Dbench.scenario=festival-realiste-canicule.yaml -Dbench.engine=timefold \
    -Dbench.seconds=120 -Dbench.seeds=3 -Dbench.cap=6
```

La classe se lance aussi comme un `main` sur le classpath de test, pour faire tourner plusieurs scénarios dans des JVM parallèles. Chaque résolution dure tout son budget, sans arrêt sur plateau, graines 0 à 2. Le plafond est fixé à six jours (`bench.cap`).

Les mesures ci-dessous ont été prises avant la fusion, sur les variantes d'alors : `preference` (la forme medium seule), `both` (les deux formes allumées), `hard` (la forme dure seule, avec l'ordre par calendrier sous Timefold), `hard-par-poids` (la forme medium dont le poids vaut un point dur : la règle fusionnée, `obligatoire` aujourd'hui) et, sous Timefold seulement, `both-sans-calendrier` et `hard-sans-calendrier`. Le moteur direct construit toujours par rareté : l'ordre par calendrier n'existait pas chez lui. Depuis la fusion, la recherche par jours entiers ([ADR 0049](repo:docs/decisions/0049-la-regle-dure-des-jours-d-affilee-se-cherche-par-jours-entiers.md)) suit la règle obligatoire : `obligatoire` vaut `hard-sans-calendrier`.

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
- **Timefold** : l'ordre par calendrier vaut 6 à 8 % de medium sur la canicule et `scenario-complet`, 35 % sur `gamme-20` et `gamme-25`, et 10 à 15 % de temps jusqu'à la faisabilité. La règle fusionnée par le poids (`hard-par-poids`) retombait sur `hard-sans-calendrier`, puisque rien n'indiquait au comparateur que la règle était obligatoire. L'ordre par calendrier est retiré en 4.0 : la régression est annoncée dans le guide [Passer à la 4.0](../installer/passer-a-la-4-0.md). Rejoué après la fusion, sur la même machine et sous Timefold, `obligatoire` atteint la faisabilité dans les trois graines et tient le plafond partout, au medium de `hard-sans-calendrier` à 0,3 % près ; `hard` en 3.x faisait 6 % (canicule), 8 % (`scenario-complet`) et 35 % (`gamme-20`, `gamme-25`) de medium en moins.
- `gamme-28` (infaisable par construction) l'est dans toutes les variantes, aux deux moteurs.

## Le banc des règles de lieu

`PlaceRulesBenchTest` mesure ce que coûterait la fusion des règles de lieu en deux : « trajet impossible » (`trajetInsuffisantEntrePostes` : vitesse, détour, tolérance) et « dispersion sur la journée », qui absorberait `eviterChangementEmplacementEloigne` (deux postes consécutifs à plus de 300 m) et `limiterEmplacementsParJour` (plus de N emplacements distincts dans la journée). Il ne touche pas la production : les variantes sont des interrupteurs de règles et le plafond d'emplacements des paramètres de qualité.

```bash
./mvnw test -Pscenario-tests -Dtest=PlaceRulesBenchTest \
    -Dbench.scenario=gamme-20-21j-50stands-172animateurs-festival-type.yaml -Dbench.engine=hand \
    -Dbench.seconds=60 -Dbench.seeds=3
```

La classe se lance aussi comme un `main` sur le classpath de test. Chaque résolution dure tout son budget, sans arrêt sur plateau, graines 0 à 2. Les variantes sont `actuel` (les trois règles, ce qui est livré), `dispersion-plafond` (la règle des 300 m retirée, le plafond journalier seul), `dispersion-plafond-serre` (de même, avec un plafond de 2), `dispersion-distance` (le plafond journalier retiré, la règle des 300 m seule), `sans-dispersion` (les deux retirées, le trajet impossible seul) et `sans-trajet` (le trajet impossible retiré, les deux règles de dispersion gardées). Chaque plan final est relu sous les règles et le plafond de `actuel` : c'est la colonne « Medium sous les règles de `actuel` », la seule comparable d'une variante à l'autre. L'écart est mesuré sur elle, médiane des trois graines : un signe moins est une perte de medium.

Mesures du 26 novembre 2026, une machine de 8 cœurs et 24 Go (huit JVM en parallèle), 60 s par résolution (30 s pour `scenario-complet`), médianes de trois graines :

| Scénario | Moteur | Variante | Faisable | Temps jusqu'à la faisabilité | Hard (méd.) | Medium (méd.) | Soft (méd.) | Medium sous les règles de `actuel` | Écart de medium |
|---|---|---|---|---|---|---|---|---|---|
| `festival-realiste-canicule` | direct | `actuel` | 3/3 | 0.4 s | 0 | -16460 | -69125 | -16460 | +0.0 % |
| `festival-realiste-canicule` | direct | `dispersion-plafond` | 3/3 | 0.3 s | 0 | -16430 | -69145 | -16430 | +0.2 % |
| `festival-realiste-canicule` | direct | `dispersion-plafond-serre` | 3/3 | 0.3 s | 0 | -16435 | -69350 | -16435 | +0.2 % |
| `festival-realiste-canicule` | direct | `dispersion-distance` | 3/3 | 0.3 s | 0 | -16445 | -68825 | -16445 | +0.1 % |
| `festival-realiste-canicule` | direct | `sans-dispersion` | 3/3 | 0.3 s | 0 | -16430 | -69375 | -16430 | +0.2 % |
| `festival-realiste-canicule` | direct | `sans-trajet` | 3/3 | 0.3 s | 0 | -16435 | -69145 | -16435 | +0.2 % |
| `festival-realiste-canicule` | Timefold | `actuel` | 3/3 | 7.3 s | 0 | -18755 | -51875 | -18755 | +0.0 % |
| `festival-realiste-canicule` | Timefold | `dispersion-plafond` | 3/3 | 6.0 s | 0 | -18650 | -52255 | -18650 | +0.6 % |
| `festival-realiste-canicule` | Timefold | `dispersion-plafond-serre` | 3/3 | 6.3 s | 0 | -18680 | -51935 | -18680 | +0.4 % |
| `festival-realiste-canicule` | Timefold | `dispersion-distance` | 3/3 | 6.9 s | 0 | -18745 | -51875 | -18745 | +0.1 % |
| `festival-realiste-canicule` | Timefold | `sans-dispersion` | 3/3 | 6.3 s | 0 | -18670 | -51935 | -18670 | +0.5 % |
| `festival-realiste-canicule` | Timefold | `sans-trajet` | 3/3 | 6.1 s | 0 | -18665 | -51945 | -18665 | +0.5 % |
| `festival-hivernal` | direct | `actuel` | 3/3 | 1.9 s | 0 | -24105 | -96435 | -24105 | +0.0 % |
| `festival-hivernal` | direct | `dispersion-plafond` | 3/3 | 1.6 s | 0 | -23940 | -96985 | -24130 | -0.1 % |
| `festival-hivernal` | direct | `dispersion-plafond-serre` | 3/3 | 1.6 s | 0 | -23915 | -95765 | -24100 | +0.0 % |
| `festival-hivernal` | direct | `dispersion-distance` | 3/3 | 1.5 s | 0 | -23985 | -95480 | -23985 | +0.5 % |
| `festival-hivernal` | direct | `sans-dispersion` | 3/3 | 1.6 s | 0 | -23960 | -94635 | -24190 | -0.4 % |
| `festival-hivernal` | direct | `sans-trajet` | 3/3 | 1.4 s | 0 | -24045 | -97035 | -24045 | +0.2 % |
| `festival-hivernal` | Timefold | `actuel` | 3/3 | 17.8 s | 0 | -31720 | -91335 | -31720 | +0.0 % |
| `festival-hivernal` | Timefold | `dispersion-plafond` | 3/3 | 17.0 s | 0 | -31385 | -91885 | -31660 | +0.2 % |
| `festival-hivernal` | Timefold | `dispersion-plafond-serre` | 3/3 | 18.8 s | 0 | -31365 | -89440 | -31605 | +0.4 % |
| `festival-hivernal` | Timefold | `dispersion-distance` | 3/3 | 18.2 s | 0 | -31720 | -91315 | -31720 | +0.0 % |
| `festival-hivernal` | Timefold | `sans-dispersion` | 3/3 | 17.2 s | 0 | -31440 | -90300 | -31785 | -0.2 % |
| `festival-hivernal` | Timefold | `sans-trajet` | 3/3 | 16.4 s | 0 | -31535 | -90675 | -31535 | +0.6 % |
| `scenario-complet` | direct | `actuel` | 3/3 | 0.7 s | 0 | -18200 | -111315 | -18200 | +0.0 % |
| `scenario-complet` | direct | `dispersion-plafond` | 3/3 | 0.5 s | 0 | -18340 | -105325 | -19765 | -8.6 % |
| `scenario-complet` | direct | `dispersion-plafond-serre` | 3/3 | 0.4 s | 0 | -18185 | -107705 | -19280 | -5.9 % |
| `scenario-complet` | direct | `dispersion-distance` | 3/3 | 0.4 s | 0 | -18170 | -113145 | -18175 | +0.1 % |
| `scenario-complet` | direct | `sans-dispersion` | 3/3 | 0.4 s | 0 | -18325 | -103455 | -19720 | -8.4 % |
| `scenario-complet` | direct | `sans-trajet` | 3/3 | 0.4 s | 0 | -18190 | -110715 | -18190 | +0.1 % |
| `scenario-complet` | Timefold | `actuel` | 3/3 | 7.7 s | 0 | -25365 | -160975 | -25365 | +0.0 % |
| `scenario-complet` | Timefold | `dispersion-plafond` | 3/3 | 7.4 s | 0 | -24690 | -158115 | -25395 | -0.1 % |
| `scenario-complet` | Timefold | `dispersion-plafond-serre` | 3/3 | 7.1 s | 0 | -25110 | -156575 | -25455 | -0.4 % |
| `scenario-complet` | Timefold | `dispersion-distance` | 3/3 | 7.2 s | 0 | -25385 | -160995 | -25395 | -0.1 % |
| `scenario-complet` | Timefold | `sans-dispersion` | 3/3 | 7.1 s | 0 | -24805 | -158395 | -25510 | -0.6 % |
| `scenario-complet` | Timefold | `sans-trajet` | 3/3 | 7.3 s | 0 | -25310 | -160970 | -25310 | +0.2 % |
| `gamme-20` | direct | `actuel` | 3/3 | 1.7 s | 0 | -17950 | -21185 | -17950 | +0.0 % |
| `gamme-20` | direct | `dispersion-plafond` | 3/3 | 1.3 s | 0 | -17235 | -19195 | -20130 | -12.1 % |
| `gamme-20` | direct | `dispersion-plafond-serre` | 3/3 | 1.3 s | 0 | -17515 | -18480 | -20385 | -13.6 % |
| `gamme-20` | direct | `dispersion-distance` | 3/3 | 1.3 s | 0 | -17880 | -21410 | -17880 | +0.4 % |
| `gamme-20` | direct | `sans-dispersion` | 3/3 | 1.3 s | 0 | -17100 | -19055 | -20005 | -11.4 % |
| `gamme-20` | direct | `sans-trajet` | 3/3 | 1.3 s | 0 | -17845 | -18645 | -17845 | +0.6 % |
| `gamme-20` | Timefold | `actuel` | 3/3 | 22.4 s | 0 | -33800 | -118260 | -33800 | +0.0 % |
| `gamme-20` | Timefold | `dispersion-plafond` | 3/3 | 22.5 s | 0 | -34140 | -121615 | -36930 | -9.3 % |
| `gamme-20` | Timefold | `dispersion-plafond-serre` | 3/3 | 21.0 s | 0 | -35025 | -122710 | -37610 | -11.3 % |
| `gamme-20` | Timefold | `dispersion-distance` | 3/3 | 22.1 s | 0 | -33975 | -118590 | -33975 | -0.5 % |
| `gamme-20` | Timefold | `sans-dispersion` | 3/3 | 21.1 s | 0 | -33975 | -121535 | -36765 | -8.8 % |
| `gamme-20` | Timefold | `sans-trajet` | 3/3 | 20.4 s | 0 | -33455 | -118560 | -33455 | +1.0 % |
| `gamme-24` | direct | `actuel` | 3/3 | 1.7 s | 0 | -32625 | -12035 | -32625 | +0.0 % |
| `gamme-24` | direct | `dispersion-plafond` | 3/3 | 1.7 s | 0 | -32515 | -12120 | -32515 | +0.3 % |
| `gamme-24` | direct | `dispersion-plafond-serre` | 3/3 | 1.6 s | 0 | -32435 | -12165 | -32435 | +0.6 % |
| `gamme-24` | direct | `dispersion-distance` | 3/3 | 1.6 s | 0 | -32330 | -12250 | -32330 | +0.9 % |
| `gamme-24` | direct | `sans-dispersion` | 3/3 | 1.6 s | 0 | -32315 | -12195 | -32315 | +1.0 % |
| `gamme-24` | direct | `sans-trajet` | 3/3 | 1.6 s | 0 | -32375 | -12105 | -40980 | -25.6 % |
| `gamme-24` | Timefold | `actuel` | 3/3 | 34.9 s | 0 | -43450 | -7560 | -43450 | +0.0 % |
| `gamme-24` | Timefold | `dispersion-plafond` | 3/3 | 33.3 s | 0 | -43270 | -7665 | -43270 | +0.4 % |
| `gamme-24` | Timefold | `dispersion-plafond-serre` | 3/3 | 32.3 s | 0 | -42945 | -7730 | -42945 | +1.2 % |
| `gamme-24` | Timefold | `dispersion-distance` | 3/3 | 30.1 s | 0 | -42880 | -7745 | -42880 | +1.3 % |
| `gamme-24` | Timefold | `sans-dispersion` | 3/3 | 32.0 s | 0 | -42850 | -7760 | -42850 | +1.4 % |
| `gamme-24` | Timefold | `sans-trajet` | 3/3 | 31.3 s | 0 | -42675 | -7620 | -50965 | -17.3 % |

Ce qu'on en retient :

- **La faisabilité** ne bouge dans aucune variante : trois graines sur trois, aux deux moteurs, et le temps jusqu'à la faisabilité reste dans le bruit (au plus quelques secondes d'écart sous Timefold).
- **Le plafond journalier se laisse fondre dans la distance.** `dispersion-distance` (la règle des 300 m seule) reste entre -0,5 % et +1,3 % de medium sur les cinq scénarios, aux deux moteurs : c'est du bruit entre graines.
- **La distance ne se laisse pas remplacer par le plafond.** `dispersion-plafond` perd 12,1 % (direct) et 9,3 % (Timefold) de medium sur `gamme-20`, 8,6 % (direct) sur `scenario-complet` ; le resserrer à 2 dégrade `gamme-20` (-13,6 % et -11,3 %). `sans-dispersion` donne les mêmes pertes : la règle des 300 m porte la mesure, le plafond n'y ajoute presque rien.
- **Le trajet impossible ne se retire pas** : `sans-trajet` perd 25,6 % (direct) et 17,3 % (Timefold) sur `gamme-24`, le scénario aux emplacements éloignés.
- Les deux festivals et `gamme-24` restent à moins de 1,5 % dans toutes les variantes de dispersion.

La règle des 5 % décidée pour ces mesures : une perte de medium au-delà de 5 %, ou un score par défaut qui bouge, et l'on ne fusionne pas. Une fusion qui ne garde que le plafond (`dispersion-plafond`, `dispersion-plafond-serre`) la dépasse sur `gamme-20` et `scenario-complet` : elle est refusée. La forme `dispersion-distance` la respecte ; mais elle retire une pénalité qui existe aujourd'hui (au-delà de trois emplacements dans la journée) : le score d'un plan par défaut peut donc bouger, et c'est à relire avant d'écrire la fusion.

## Le banc des règles de repas

`MealRulesBenchTest` mesure ce que coûterait la fusion des deux règles de repas, `coupureRepasObligatoire` (la coupure due, dure) et `coupureRepasPlacementPrefere` (l'endroit de la fenêtre où elle est prise, souple), en une règle unique dont le placement préféré serait un paramètre, avant que personne ne l'écrive. Il ne change aucun comportement et ne demande aucune couture dans le code de production : les variantes sont des interrupteurs, des poids qui portent une règle à un autre niveau ([ADR 0111](repo:docs/decisions/0111-le-niveau-d-une-regle-est-le-niveau-de-son-poids.md)) et une réécriture des faits `FenetreRepas` du problème, dont l'indicateur `auPlusTard` est le placement que la règle fusionnée lirait comme paramètre. Ce que le banc a montré a été suivi : la fusion se fait dans le catalogue et à l'écran, pas dans le score, et le placement est un paramètre de l'édition ([ADR 0123](repo:docs/decisions/0123-une-seule-entree-de-repas-placement-en-parametre.md)).

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

## Le banc des règles de confort

`ComfortRulesBenchTest` mesure ce que coûterait la fusion de règles de confort voisines, avant que personne ne l'écrive (la cible est d'en retirer quatre environ). Trois paires sont regardées : les deux équilibrages (`equilibrerCharge`, medium, et `equilibrerCreneauxPenibles`, soft), les deux règles des stands premium et épuisants (`eviterRoulementStandsPremium` et `eviterEnchainementStandsEpuisants`), et les deux écarts d'un siège (`appreciationIncompatible` et `experienceRequisePourStandsPremium`, cette dernière mesurée seule). `souhaitsIncompatibles`, `standComplexeAvecReferent`, `mineurNecessiteEncadrementMajeur` et la répartition des mineurs ne sont pas candidats. Il ne touche pas la production : les variantes sont des interrupteurs de règles et un poids qui porte une règle à un autre niveau ([ADR 0111](repo:docs/decisions/0111-le-niveau-d-une-regle-est-le-niveau-de-son-poids.md)).

```bash
./mvnw test -Pscenario-tests -Dtest=ComfortRulesBenchTest \
    -Dbench.scenario=festival-hivernal.yaml -Dbench.engine=hand \
    -Dbench.seconds=60 -Dbench.seeds=3
```

La classe se lance aussi comme un `main` sur le classpath de test. Chaque résolution dure tout son budget, sans arrêt sur plateau, graines 0 à 2. Les variantes sont `actuel` (ce qui est livré), `sans-penibles` et `sans-charge` (un équilibrage fait le travail des deux), `penibles-medium` (l'équilibrage des postes pénibles porté au niveau medium, poids 1, comme le ferait une règle d'équilibrage unique), `sans-roulement`, `sans-enchainement` et `sans-experience-premium` (la règle qu'une fusion absorberait, éteinte). Chaque plan final est relu sous les règles livrées, et les cinq règles ci-dessus y sont lues une à une : la dernière colonne est ce que chacune coûte dans le plan, à son niveau (soft pour `equilibrerCreneauxPenibles`, medium pour les autres). Une règle à zéro n'a rien à fusionner sur ce scénario : `scenario-complet` et `gamme-24` n'ont ni stand premium ni stand épuisant qui se touche.

**Une fusion ne passe que si elle garde exactement les pénalités actuelles.** Cela se juge avant tout banc, sur le papier : le même ensemble de tuples, le même poids, donc le même score par défaut.

- `equilibrerCharge` et `equilibrerCreneauxPenibles` : même forme (`loadBalance` par animateur) mais pas la même population (tous les sièges tenus, contre les seuls sièges pénibles), pas le même niveau (medium, poids 5 ; soft, poids 1) et pas le même réglage par édition. Une règle n'a qu'un niveau : la pénalité soft disparaît ou change de niveau. Le score par défaut bouge.
- `eviterRoulementStandsPremium` et `eviterEnchainementStandsEpuisants` : l'une compte, par stand premium, les visages au-delà de l'équipe ; l'autre, les paires de sièges enchaînés d'une même personne sur des stands épuisants. Ni les tuples, ni la clé de regroupement ne se recoupent : aucune forme commune qui garde les deux pénalités.
- `appreciationIncompatible` et `experienceRequisePourStandsPremium` : un point medium par siège, poids 5 des deux côtés, sur le même flux de sièges. Une règle à deux motifs garderait le score par défaut à l'identique. Ce qui se perd n'est pas dans le score : le poids et l'interrupteur propres à chaque règle, que l'organisateur règle séparément (l'appréciation de l'administrateur est celle que l'on pèse plus fort).

Ce que le banc ajoute à l'analyse est le prix d'une fusion qui retirerait une pénalité (une variante `sans-…`) ou la changerait de niveau (`penibles-medium`). Plan relu sous les règles de `actuel` ; l'écart est celui du medium sous ces règles, médiane de trois graines ; un signe moins est une perte de medium. Le bruit entre graines est de l'ordre de 5 % : `gamme-24` donne `actuel` à -37 750 et toutes les autres variantes à -32 500 environ, sans qu'aucune ne s'explique.

Mesures du 26 novembre 2026, une machine de 8 cœurs et 32 Go (huit JVM en parallèle), 60 s par résolution (30 s pour `scenario-complet`), médianes de trois graines :

| Scénario | Moteur | Variante | Faisable | Temps jusqu'à la faisabilité | Medium sous les règles de `actuel` | Écart de medium | Soft sous les règles de `actuel` | Coût dans le plan (`equilibrerCharge` / `equilibrerCreneauxPenibles` / `eviterRoulementStandsPremium` / `eviterEnchainementStandsEpuisants` / `experienceRequisePourStandsPremium`) |
|---|---|---|---|---|---|---|---|---|
| `festival-realiste-canicule` | direct | `actuel` | 3/3 | 1.8 s | -16590 | +0.0 % | -68000 | 110 / 1325 / 630 / 0 / 30 |
| `festival-realiste-canicule` | direct | `sans-penibles` | 3/3 | 0.6 s | -16535 | +0.3 % | -66730 | 110 / 1750 / 620 / 0 / 45 |
| `festival-realiste-canicule` | direct | `sans-charge` | 3/3 | 0.5 s | -18005 | -8.5 % | -35385 | 1780 / 2030 / 460 / 0 / 20 |
| `festival-realiste-canicule` | direct | `penibles-medium` | 3/3 | 0.5 s | -16630 | -0.2 % | -73570 | 110 / 1045 / 650 / 0 / 40 |
| `festival-realiste-canicule` | direct | `sans-roulement` | 3/3 | 0.4 s | -18645 | -12.4 % | -70450 | 110 / 1320 / 2755 / 0 / 20 |
| `festival-realiste-canicule` | direct | `sans-enchainement` | 3/3 | 0.4 s | -16565 | +0.2 % | -67550 | 110 / 1340 / 610 / 0 / 30 |
| `festival-realiste-canicule` | direct | `sans-experience-premium` | 3/3 | 0.4 s | -16955 | -2.2 % | -63400 | 110 / 1295 / 605 / 0 / 445 |
| `festival-realiste-canicule` | Timefold | `actuel` | 3/3 | 15.6 s | -19360 | +0.0 % | -54545 | 130 / 1105 / 1440 / 0 / 90 |
| `festival-realiste-canicule` | Timefold | `sans-penibles` | 3/3 | 14.6 s | -19050 | +1.6 % | -51370 | 165 / 1360 / 1095 / 0 / 120 |
| `festival-realiste-canicule` | Timefold | `sans-charge` | 3/3 | 13.9 s | -18750 | +3.2 % | -46180 | 2235 / 2145 / 555 / 0 / 15 |
| `festival-realiste-canicule` | Timefold | `penibles-medium` | 3/3 | 14.4 s | -19245 | +0.6 % | -58590 | 110 / 995 / 1415 / 0 / 80 |
| `festival-realiste-canicule` | Timefold | `sans-roulement` | 3/3 | 14.1 s | -20770 | -7.3 % | -55955 | 180 / 980 / 3125 / 0 / 90 |
| `festival-realiste-canicule` | Timefold | `sans-enchainement` | 3/3 | 13.8 s | -19175 | +1.0 % | -53590 | 110 / 1105 / 1425 / 0 / 85 |
| `festival-realiste-canicule` | Timefold | `sans-experience-premium` | 3/3 | 14.1 s | -19785 | -2.2 % | -54150 | 150 / 930 / 1400 / 0 / 500 |
| `festival-hivernal` | direct | `actuel` | 3/3 | 3.7 s | -25270 | +0.0 % | -100475 | 315 / 2570 / 2325 / 0 / 15 |
| `festival-hivernal` | direct | `sans-penibles` | 3/3 | 2.9 s | -25235 | +0.1 % | -99870 | 315 / 2965 / 2315 / 0 / 10 |
| `festival-hivernal` | direct | `sans-charge` | 3/3 | 1.8 s | -26605 | -5.3 % | -93600 | 3800 / 3860 / 1965 / 0 / 0 |
| `festival-hivernal` | direct | `penibles-medium` | 3/3 | 2.3 s | -24915 | +1.4 % | -109980 | 315 / 2155 / 2165 / 0 / 10 |
| `festival-hivernal` | direct | `sans-roulement` | 3/3 | 2.8 s | -27490 | -8.8 % | -96040 | 315 / 2730 / 4910 / 0 / 35 |
| `festival-hivernal` | direct | `sans-enchainement` | 3/3 | 2.5 s | -24980 | +1.1 % | -96855 | 315 / 2565 / 2150 / 0 / 15 |
| `festival-hivernal` | direct | `sans-experience-premium` | 3/3 | 2.7 s | -26345 | -4.3 % | -88715 | 315 / 2400 / 2165 / 0 / 1605 |
| `festival-hivernal` | Timefold | `actuel` | 3/3 | 39.6 s | -35110 | +0.0 % | -91980 | 315 / 1065 / 5240 / 0 / 725 |
| `festival-hivernal` | Timefold | `sans-penibles` | 3/3 | 36.2 s | -34020 | +3.1 % | -97540 | 315 / 1520 / 4310 / 0 / 670 |
| `festival-hivernal` | Timefold | `sans-charge` | 0/3 | jamais | -27000 | +23.1 % | -110660 | 3820 / 4335 / 1390 / 0 / 110 |
| `festival-hivernal` | Timefold | `penibles-medium` | 3/3 | 36.4 s | -34990 | +0.3 % | -97365 | 330 / 905 / 5295 / 0 / 740 |
| `festival-hivernal` | Timefold | `sans-roulement` | 3/3 | 41.4 s | -36350 | -3.5 % | -88950 | 310 / 1105 / 7205 / 0 / 690 |
| `festival-hivernal` | Timefold | `sans-enchainement` | 3/3 | 38.1 s | -34815 | +0.8 % | -91975 | 315 / 1100 / 5205 / 0 / 715 |
| `festival-hivernal` | Timefold | `sans-experience-premium` | 3/3 | 39.4 s | -35785 | -1.9 % | -94660 | 330 / 1040 / 5295 / 0 / 1645 |
| `scenario-complet` | direct | `actuel` | 3/3 | 1.0 s | -18275 | +0.0 % | -122610 | 200 / 465 / 0 / 0 / 0 |
| `scenario-complet` | direct | `sans-penibles` | 3/3 | 0.7 s | -18265 | +0.1 % | -122865 | 235 / 500 / 0 / 0 / 0 |
| `scenario-complet` | direct | `sans-charge` | 3/3 | 0.6 s | -19945 | -9.1 % | -61550 | 3290 / 0 / 0 / 0 / 0 |
| `scenario-complet` | direct | `penibles-medium` | 3/3 | 0.7 s | -18280 | -0.0 % | -123185 | 185 / 465 / 0 / 0 / 0 |
| `scenario-complet` | direct | `sans-roulement` | 3/3 | 0.7 s | -18240 | +0.2 % | -119815 | 185 / 465 / 0 / 0 / 0 |
| `scenario-complet` | direct | `sans-enchainement` | 3/3 | 0.7 s | -18235 | +0.2 % | -119810 | 185 / 465 / 0 / 0 / 0 |
| `scenario-complet` | direct | `sans-experience-premium` | 3/3 | 0.6 s | -18235 | +0.2 % | -121415 | 185 / 465 / 0 / 0 / 0 |
| `scenario-complet` | Timefold | `actuel` | 3/3 | 18.0 s | -29520 | +0.0 % | -161290 | 1770 / 0 / 0 / 0 / 0 |
| `scenario-complet` | Timefold | `sans-penibles` | 3/3 | 16.3 s | -28560 | +3.3 % | -161310 | 1780 / 0 / 0 / 0 / 0 |
| `scenario-complet` | Timefold | `sans-charge` | 3/3 | 16.5 s | -21405 | +27.5 % | -70190 | 4055 / 0 / 0 / 0 / 0 |
| `scenario-complet` | Timefold | `penibles-medium` | 3/3 | 16.1 s | -28495 | +3.5 % | -161315 | 1760 / 0 / 0 / 0 / 0 |
| `scenario-complet` | Timefold | `sans-roulement` | 3/3 | 16.3 s | -28485 | +3.5 % | -161310 | 1780 / 0 / 0 / 0 / 0 |
| `scenario-complet` | Timefold | `sans-enchainement` | 3/3 | 16.3 s | -28275 | +4.2 % | -161300 | 1770 / 0 / 0 / 0 / 0 |
| `scenario-complet` | Timefold | `sans-experience-premium` | 3/3 | 15.6 s | -28515 | +3.4 % | -161305 | 1770 / 0 / 0 / 0 / 0 |
| `gamme-20` | direct | `actuel` | 3/3 | 3.0 s | -20075 | +0.0 % | -43855 | 435 / 2775 / 2000 / 30 / 0 |
| `gamme-20` | direct | `sans-penibles` | 3/3 | 2.4 s | -20060 | +0.1 % | -43700 | 430 / 3485 / 1920 / 15 / 0 |
| `gamme-20` | direct | `sans-charge` | 3/3 | 2.3 s | -20065 | +0.0 % | -63080 | 10130 / 6045 / 830 / 0 / 0 |
| `gamme-20` | direct | `penibles-medium` | 3/3 | 2.3 s | -19630 | +2.2 % | -53655 | 440 / 1605 / 1840 / 20 / 0 |
| `gamme-20` | direct | `sans-roulement` | 3/3 | 2.3 s | -21835 | -8.8 % | -47650 | 350 / 2430 / 3530 / 20 / 10 |
| `gamme-20` | direct | `sans-enchainement` | 3/3 | 2.2 s | -20030 | +0.2 % | -37260 | 435 / 2610 / 1920 / 265 / 0 |
| `gamme-20` | direct | `sans-experience-premium` | 3/3 | 2.1 s | -21800 | -8.6 % | -39275 | 435 / 2435 / 2090 / 20 / 2430 |
| `gamme-20` | Timefold | `actuel` | 3/3 | 43.8 s | -38530 | +0.0 % | -118705 | 3250 / 4695 / 2290 / 425 / 1010 |
| `gamme-20` | Timefold | `sans-penibles` | 3/3 | 42.6 s | -38600 | -0.2 % | -119915 | 3560 / 4980 / 2250 / 410 / 870 |
| `gamme-20` | Timefold | `sans-charge` | 3/3 | 44.2 s | -22830 | +40.7 % | -86910 | 9250 / 6440 / 950 / 15 / 0 |
| `gamme-20` | Timefold | `penibles-medium` | 3/3 | 46.2 s | -43635 | -13.2 % | -124395 | 3885 / 5040 / 2590 / 435 / 600 |
| `gamme-20` | Timefold | `sans-roulement` | 3/3 | 46.4 s | -45330 | -17.6 % | -123810 | 4615 / 3945 / 3470 / 425 / 940 |
| `gamme-20` | Timefold | `sans-enchainement` | 3/3 | 45.2 s | -40790 | -5.9 % | -109150 | 3890 / 4280 / 2290 / 750 / 760 |
| `gamme-20` | Timefold | `sans-experience-premium` | 3/3 | 44.5 s | -42700 | -10.8 % | -122410 | 4505 / 4545 / 2380 / 315 / 3130 |
| `gamme-24` | direct | `actuel` | 3/3 | 3.3 s | -37750 | +0.0 % | -11165 | 5090 / 0 / 0 / 0 / 0 |
| `gamme-24` | direct | `sans-penibles` | 3/3 | 3.3 s | -37720 | +0.1 % | -11220 | 5135 / 0 / 0 / 0 / 0 |
| `gamme-24` | direct | `sans-charge` | 3/3 | 2.9 s | -31465 | +16.6 % | -12350 | 11735 / 0 / 0 / 0 / 0 |
| `gamme-24` | direct | `penibles-medium` | 3/3 | 3.0 s | -33095 | +12.3 % | -11850 | 5030 / 0 / 0 / 0 / 0 |
| `gamme-24` | direct | `sans-roulement` | 3/3 | 1.6 s | -32515 | +13.9 % | -12140 | 5055 / 0 / 0 / 0 / 0 |
| `gamme-24` | direct | `sans-enchainement` | 3/3 | 1.6 s | -32495 | +13.9 % | -12125 | 4990 / 0 / 0 / 0 / 0 |
| `gamme-24` | direct | `sans-experience-premium` | 3/3 | 1.6 s | -32625 | +13.6 % | -12090 | 4975 / 0 / 0 / 0 / 0 |
| `gamme-24` | Timefold | `actuel` | 0/3 | jamais | -38545 | +0.0 % | -6135 | 1690 / 0 / 0 / 0 / 0 |
| `gamme-24` | Timefold | `sans-penibles` | 0/3 | jamais | -39410 | -2.2 % | -6250 | 1845 / 0 / 0 / 0 / 0 |
| `gamme-24` | Timefold | `sans-charge` | 0/3 | jamais | -35045 | +9.1 % | -8545 | 12995 / 0 / 0 / 0 / 0 |
| `gamme-24` | Timefold | `penibles-medium` | 2/3 | 42.6 s | -43090 | -11.8 % | -7410 | 3525 / 0 / 0 / 0 / 0 |
| `gamme-24` | Timefold | `sans-roulement` | 3/3 | 34.9 s | -43250 | -12.2 % | -7655 | 3510 / 0 / 0 / 0 / 0 |
| `gamme-24` | Timefold | `sans-enchainement` | 3/3 | 33.0 s | -43115 | -11.9 % | -7680 | 3495 / 0 / 0 / 0 / 0 |
| `gamme-24` | Timefold | `sans-experience-premium` | 3/3 | 34.8 s | -43420 | -12.6 % | -7615 | 3525 / 0 / 0 / 0 / 0 |

Ce qu'on en retient :

- **`eviterRoulementStandsPremium` ne se retire pas.** `sans-roulement` perd 12,4 % (direct) et 7,3 % (Timefold) sur la canicule, 8,8 % (direct) sur `festival-hivernal`, 8,8 % (direct) et 17,6 % (Timefold) sur `gamme-20`. C'est la règle qui coûte le plus quand on l'enlève.
- **`eviterEnchainementStandsEpuisants` pèse peu, mais pas rien.** Elle ne coûte rien dans le plan des deux festivals ni de `scenario-complet` : le solveur évite déjà l'enchaînement. Sur `gamme-20` elle coûte 30 (direct) et 425 (Timefold) dans le plan de `actuel`, et la retirer perd 5,9 % de medium sous Timefold. Une pénalité qui existe disparaîtrait : le score par défaut bougerait.
- **Les équilibrages ne se remplacent pas l'un par l'autre.** `sans-charge` perd 8,5 % et 9,1 % (direct) sur la canicule et `scenario-complet`, et `festival-hivernal` ne trouve plus de plan faisable sous Timefold ; `penibles-medium` perd 13,2 % sous Timefold sur `gamme-20`. À l'inverse, `sans-penibles` reste à moins de 3,5 % partout, mais sa pénalité soft existe (2 570 sur `festival-hivernal`, direct) : le score par défaut bougerait.
- **`sans-experience-premium` perd 8,6 % (direct) et 10,8 % (Timefold) sur `gamme-20`**, 4,3 % sur `festival-hivernal` (direct) : la règle n'est pas inerte, mais une fusion à deux motifs ne la retirerait pas.
- Les pertes mesurées sur `gamme-24` et le gain de `sans-charge` sous Timefold (+27,5 % sur `scenario-complet`, +40,7 % sur `gamme-20`) sont du bruit ou de la dynamique de recherche : un plan qui, sans une règle, se stabilise ailleurs. Aucune conclusion ne s'appuie dessus. Sous Timefold, `gamme-24` n'atteint pas la faisabilité en 60 s dans `actuel` ni dans `sans-penibles` ou `sans-charge` (huit JVM sur la même machine) ; le moteur direct la tient partout.

La règle des 5 % décidée pour ces mesures : une perte de medium au-delà de 5 %, ou un score par défaut qui bouge, et l'on ne fusionne pas. Recommandation pour la fusion des règles de confort :

| Paire | Fusion à score inchangé ? | Pire perte de medium mesurée | Recommandation |
|---|---|---|---|
| `equilibrerCharge` + `equilibrerCreneauxPenibles` | non (deux niveaux, deux populations) | -13,2 % (`penibles-medium`, Timefold, `gamme-20`) | regrouper à l'écran seulement : un même groupe « équilibre de la charge », deux règles |
| `eviterRoulementStandsPremium` + `eviterEnchainementStandsEpuisants` | non (aucun tuple commun) | -17,6 % (`sans-roulement`, Timefold, `gamme-20`) | garder |
| `appreciationIncompatible` + `experienceRequisePourStandsPremium` | oui, par construction (deux motifs, un point medium chacun) | -10,8 % (`sans-experience-premium`, Timefold, `gamme-20`), qui ne s'applique pas à une fusion à score égal | garder : le score tiendrait, mais deux réglages de l'organisateur disparaîtraient |

Aucune paire ne se fond donc en respectant les deux critères : la fusion des règles de confort ne retire aucune règle. Le test différentiel et un test « avant/après » sur les scénarios suffiraient, si l'on choisissait la troisième paire malgré la perte de réglage.

## Le moteur direct : deux tests qui tiennent les règles en double

`solver/handsearch` écrit chaque règle du catalogue une seconde fois, pour chercher sans traverser les contraintes en flux ([ADR 0087](repo:docs/decisions/0087-un-second-moteur-de-recherche-arbitre-par-timefold.md), moteur par défaut depuis l'[ADR 0088](repo:docs/decisions/0088-le-moteur-direct-devient-le-moteur-par-defaut.md)). Deux tests de la suite par défaut, sans conteneur, empêchent les deux exemplaires de diverger.

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

Le profil `mutation` vise `dev.sylvain.planning.solver.constraints.*` et exclut les étiquettes `scenario-lent` et `scenario-extreme`. `mutationThreshold` vaut 80 : sous ce seuil, le build échoue. Un mutant survivant se tue par un cas de test, le plus souvent la valeur limite qu'aucun test ne posait (exactement le repos minimal, exactement l'âge de la majorité). Le seuil ne descend jamais ; il monte quand la mesure monte. Le workflow `mutation.yml` joue le contrôle chaque nuit sur `main`, jamais sur une pull request : lancez la commande avant de pousser une contrainte. Un échec nocturne ouvre une issue.
