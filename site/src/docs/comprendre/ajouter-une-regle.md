# Ajouter une règle

Vous savez écrire une nouvelle règle du solveur de bout en bout : sa méthode de contrainte, son entrée au catalogue, sa seconde écriture dans le moteur direct, ses tests, et les listes que d'autres tests tiennent à jour. Une règle n'est terminée que lorsque tous ces tests passent.

Une règle existe **deux fois** : dans les flux de contraintes de Timefold, qui la définissent, et dans le moteur direct de `solver/handsearch`, qui doit la noter à l'identique ([ADR 0087](repo:docs/decisions/0087-un-second-moteur-de-recherche-arbitre-par-timefold.md)). Changer ce qu'une règle compte, son niveau ou les faits qu'elle lit revient à changer les deux. Le pourquoi des niveaux est dans [Les règles du solveur et leur catalogue](pourquoi-ces-regles.md).

## 1. Choisir la famille et le niveau

Les règles sont rangées par famille dans `solver/constraints/`, une méthode privée par règle :

| Famille | Contenu |
|---|---|
| `AffectationConstraints` | mécanique des sièges : couverture, disponibilité, chevauchement, quotas |
| `LegalConstraints` | cadre légal : mineurs, durées, repos, jours par semaine |
| `RepasConstraints` | la coupure repas |
| `AdHocConstraints` | exceptions posées à la main (`ContrainteAdHoc`) |
| `VerrouillageConstraints` | ce qu'un verrou fige |
| `QualiteConstraints` | qualité d'organisation (medium) |
| `PreferenceConstraints` | préférences (soft) |

Décidez aussi le niveau. Les types prescriptifs d'exception ad hoc (`INDISPONIBILITE_FORCEE`, `INCOMPATIBILITE`, `AFFECTATION_FORCEE`) sont des écarts durs, au même rang que le légal et les mineurs : ne les rétrogradez jamais. `AFFINITE` est la seule exception voulue, une récompense soft : ne la promouvez jamais en dure, ce serait une affectation forcée déguisée. Un poids ne change jamais le niveau d'une règle : si le même contrôle doit pouvoir être dosé *et* tenu en dur, ce sont deux règles, comme pour les [jours d'affilée](repos-et-jours-d-affilee.md).

## 2. Écrire la méthode de contrainte

Lisez deux méthodes voisines pour reprendre leur style, puis :

- partez d'un flux de `SeatStreams` (les sièges tenus, par jour, par animateur, par ligne stand × créneau) ;
- enrobez le flux **final**, les écarts, juste avant `penalize`, de `ConstraintToggleSupport.actif(...)` : sans cela l'interrupteur de la règle n'a aucun effet, et `ConstraintToggleStructurelleTest` échoue sur toute règle du catalogue dont le nom n'est pas enrobé ;
- enregistrez la méthode dans la méthode `define` de la famille. Une famille nouvelle s'ajoute dans `PlanningConstraintProvider.defineConstraints` ;
- respectez le passé : une place passée compte mais n'est jamais reprochée, une correspondance n'est facturée que si au moins une de ses places ne l'est pas (`PastSeats`, voir [Compté, non reproché](regles-de-couverture-et-de-confort.md)).

Le coût d'une règle se mesure au nombre de tuples que le flux construit et maintient à chaque mouvement, pas à la longueur du code :

- **restreignez avant de joindre** : un `filter` placé après un `forEachUniquePair` a déjà payé la construction de toutes les paires ;
- **préférez `Joiners.equal`, `lessThan`, `overlapping` à `Joiners.filtering`** : un joiner indexé est un accès par hachage, un `filtering` un prédicat évalué sur chaque combinaison. `lessThan` sur le `@PlanningId` reproduit la sémantique « chaque paire une seule fois » tout en autorisant un flux d'entrée déjà filtré.

Une règle qui lit un réglage de l'édition doit le déclarer dans `ConstraintParameters`, sinon `ConstraintParametersStructuralTest` échoue ([doser et désactiver](doser-et-desactiver-les-regles.md)).

## 3. L'inscrire au catalogue

Ajoutez son entrée dans [`ConstraintCatalog`](repo:src/main/java/dev/sylvain/planning/solver/ConstraintCatalog.java) : nom, niveau, catégorie, libellé court, description métier, consigne de correction, et l'article de loi s'il y en a un (écrire `art. L3162-1` suffit à obtenir le lien Légifrance à l'écran). Le catalogue est servi par `GET /api/constraints` : c'est la documentation que l'application distribue elle-même. `ConstraintCatalogTest` échoue si le catalogue et le fournisseur divergent, dans un sens comme dans l'autre.

Selon la règle, d'autres listes à la main suivent :

| Si la règle est | Il faut aussi | Test qui le garde |
|---|---|---|
| medium ou soft | ses leviers dans le catalogue (le geste à proposer quand elle est en défaut) | `BlockerPlaybookTest` |
| medium ou soft | sa ligne dans `ConstraintFloorRules` (le grain de son ratio de plancher) | `ConstraintFloorRulesTest` |
| lisant un réglage | sa déclaration dans `ConstraintParameters` | `ConstraintParametersStructuralTest` |

Un poids par défaut n'est pas à écrire : il vient de `ConstraintCatalog.defaultWeight` (1 pour une règle dure, 5 pour les autres) et `planning.constraint-weights.<nom>` ne sert qu'à le surcharger au niveau du déploiement.

## 4. L'écrire une seconde fois dans le moteur direct

Dans [`handsearch`](repo:src/main/java/dev/sylvain/planning/solver/handsearch/Rule.java) :

1. Ajoutez la constante à `Rule`, sous le nom de la règle en majuscules séparées par des soulignés : `posteDoitEtrePourvu` devient `POSTE_DOIT_ETRE_POURVU`. Ce nom est le seul lien entre les deux moteurs.
2. Chargez-la par `cost(Rule.X, n)` à l'endroit où sa forme l'appelle. La javadoc d'[`IncrementalScore`](repo:src/main/java/dev/sylvain/planning/solver/handsearch/IncrementalScore.java) en liste quatre :

| Forme de la règle | Où la charger |
|---|---|
| un siège et son titulaire, rien d'autre | `FlatProblem.staticTerms`, lu dans une table |
| les sièges triés d'un animateur | `eval` |
| un compte partagé par une ligne, un stand, un créneau ou le plan | `lineCost`, `standCost`, `slotCost`, `balance`, tenus par `set` |
| le coût d'un animateur qui lit les sièges d'un autre (un relais, le majeur dont un mineur a besoin, une paire ad hoc) | `set` relit alors quiconque dépend du siège qui a bougé |

3. Respectez ce que le flux respecte : les sièges passés (`PastSeats`) sont à votre charge, l'interrupteur et le poids viennent de `cost`.

Rien dans ce paquet n'importe `ai.timefold.solver.core.impl`, n'injecte de bean ni ne lit de configuration : il tourne tel quel dans le worker de calcul déporté.

Deux tests disent si la seconde écriture est juste. `HandSearchCoverageStructuralTest` échoue, par son nom, sur une règle que le catalogue connaît et que le score ignore, ou sur une constante que rien ne charge. `HandSearchDifferentialTest` compare les deux moteurs règle par règle, et exige que chaque règle ait été comparée au moins une fois sur un score non nul : si aucun cas ne la fait mordre, donnez à `HandSearchFixtures.fabricateBranches` de quoi le faire.

## 5. Tester

Dans le `*ConstraintsTest` de la famille (`solver/constraints/`, Timefold `ConstraintVerifier`, sans Quarkus ni base, avec la base `ConstraintTestBase`), au minimum :

- un cas qui déclenche la pénalité ;
- un cas valide qui ne la déclenche pas.

Pour une règle dure, `PlanningHardConstraintsTest` doit continuer d'affirmer que le score dur d'un plan résolu est zéro sur le scénario nominal ; étendez-le à la nouvelle règle. Ajoutez au besoin un cas dans `ConstraintToggleTest` si la règle ouvre une famille de flux que l'interrupteur n'a jamais couverte : le même jeu de données doit être pénalisé sans le toggle et valoir zéro avec.

Lancez la classe visée, puis le tour complet :

```bash
./mvnw test -Dtest=QualiteConstraintsTest
./mvnw test -Dtest=PlanningHardConstraintsTest
./mvnw test -Dtest='HandSearch*'
./mvnw test
```

Une règle qui change ce que le solveur converge à atteindre relève aussi des tests de scénarios lents : voir [les tests](tests.md).

## 6. Dire pourquoi

La description du catalogue dit *quoi*. Si la règle arbitre quelque chose qu'elle ne peut pas porter (un choix de niveau, une tension avec une autre règle, ce qu'elle refuse volontairement de compter), ajoutez-en le *pourquoi* à la page qui traite de sa famille : [légal et pauses](regles-legales-et-pauses.md), [repos et jours d'affilée](repos-et-jours-d-affilee.md) ou [couverture et confort](regles-de-couverture-et-de-confort.md). Si la règle ouvre une capacité visible de l'organisateur, la partie fonctionnelle de la documentation s'en trouve changée aussi, en langage métier, sans nom de classe.
