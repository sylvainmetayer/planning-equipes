---
twin: comprendre/ajouter-une-regle
---

# Adding a rule

You will know how to write a new solver rule from end to end: its constraint method, its catalogue entry, its second writing in the direct engine, its tests, and the lists that other tests keep up to date. A rule is finished only when all these tests pass.

A rule exists **twice**: in Timefold's constraint streams, which define it, and in the direct engine of `solver/handsearch`, which must score it identically ([ADR 0087](repo:docs/decisions/0087-un-second-moteur-de-recherche-arbitre-par-timefold.md)). Changing what a rule counts, its level or the facts it reads means changing both. The reason for the levels is in [The solver's rules and their catalogue](pourquoi-ces-regles.md).

## 1. Choose the family and the level

Rules are grouped by family in `solver/constraints/`, one private method per rule:

| Family | Content |
|---|---|
| `AffectationConstraints` | seat mechanics: coverage, availability, overlap, quotas |
| `LegalConstraints` | legal framework: minors, durations, rest, days per week |
| `RepasConstraints` | the meal break |
| `AdHocConstraints` | exceptions set by hand (`ContrainteAdHoc`) |
| `VerrouillageConstraints` | what a lock freezes |
| `QualiteConstraints` | organisational quality (medium) |
| `PreferenceConstraints` | preferences (soft) |

Decide the level too. The prescriptive types of ad hoc exception (`INDISPONIBILITE_FORCEE`, `INCOMPATIBILITE`, `AFFECTATION_FORCEE`) are hard breaches, at the same rank as the legal rules and the minors: never demote them. `AFFINITE` is the only deliberate exception, a soft reward: never promote it to hard, it would be a forced assignment in disguise. A weight never changes the level of a rule: if the same check must be both weighted *and* held hard, that is two rules, as for [days in a row](repos-et-jours-d-affilee.md).

## 2. Write the constraint method

Read two neighbouring methods to pick up their style, then:

- start from a `SeatStreams` stream (the seats held, per day, per staff member, per stand × timeslot line);
- wrap the **final** stream, the breaches, just before `penalize`, in `ConstraintToggleSupport.actif(...)`: without it the rule's switch has no effect, and `ConstraintToggleStructurelleTest` fails on any catalogue rule whose name is not wrapped;
- register the method in the family's `define` method. A new family is added in `PlanningConstraintProvider.defineConstraints`;
- respect the past: a past seat counts but is never blamed, and a match is charged only if at least one of its seats is not past (`PastSeats`, see [Counted, not blamed](regles-de-couverture-et-de-confort.md)).

The cost of a rule is measured by the number of tuples the stream builds and maintains at every move, not by the length of the code:

- **restrict before joining**: a `filter` placed after a `forEachUniquePair` has already paid for building all the pairs;
- **prefer `Joiners.equal`, `lessThan`, `overlapping` to `Joiners.filtering`**: an indexed joiner is a hash lookup, a `filtering` a predicate evaluated on every combination. `lessThan` on the `@PlanningId` reproduces the "each pair once" semantics while allowing an already filtered input stream.

A rule that reads an edition setting must declare it in `ConstraintParameters`, otherwise `ConstraintParametersStructuralTest` fails ([weighting and disabling](doser-et-desactiver-les-regles.md)).

## 3. Register it in the catalogue

Add its entry to [`ConstraintCatalog`](repo:src/main/java/dev/sylvain/planning/solver/ConstraintCatalog.java): name, level, category, short label, business description, correction advice, and the article of law if there is one (writing `art. L3162-1` is enough to get the Légifrance link on screen). The catalogue is served by `GET /api/constraints`: it is the documentation the application hands out itself. `ConstraintCatalogTest` fails if the catalogue and the provider diverge, in either direction.

Depending on the rule, other hand-kept lists follow:

| If the rule is | You also need | Test that guards it |
|---|---|---|
| medium or soft | its levers in the catalogue (the action to suggest when it is in default) | `BlockerPlaybookTest` |
| medium or soft | its line in `ConstraintFloorRules` (the grain of its floor ratio) | `ConstraintFloorRulesTest` |
| reading a setting | its declaration in `ConstraintParameters` | `ConstraintParametersStructuralTest` |

A default weight is not something to write: it comes from `ConstraintCatalog.defaultWeight` (1 for a hard rule, 5 for the others) and `planning.constraint-weights.<name>` is only there to override it at deployment level.

## 4. Write it a second time in the direct engine

In [`handsearch`](repo:src/main/java/dev/sylvain/planning/solver/handsearch/Rule.java):

1. Add the constant to `Rule`, under the rule's name in upper case separated by underscores: `posteDoitEtrePourvu` becomes `POSTE_DOIT_ETRE_POURVU`. This name is the only link between the two engines.
2. Load it with `cost(Rule.X, n)` where its shape calls for it. The javadoc of [`IncrementalScore`](repo:src/main/java/dev/sylvain/planning/solver/handsearch/IncrementalScore.java) lists four:

| Shape of the rule | Where to load it |
|---|---|
| one seat and its holder, nothing else | `FlatProblem.staticTerms`, read from a table |
| the sorted seats of a staff member | `eval` |
| a count shared by a line, a stand, a timeslot or the plan | `lineCost`, `standCost`, `slotCost`, `balance`, held by `set` |
| the cost of a staff member that reads another's seats (a relay, the adult a minor needs, an ad hoc pair) | `set` then rereads whoever depends on the seat that moved |

3. Respect what the stream respects: past seats (`PastSeats`) are your responsibility, the switch and the weight come from `cost`.

Nothing in this package imports `ai.timefold.solver.core.impl`, injects a bean or reads a configuration: it runs as is in the remote solve worker.

Two tests say whether the second writing is right. `HandSearchCoverageStructuralTest` fails, by name, on a rule the catalogue knows and the score ignores, or on a constant nothing loads. `HandSearchDifferentialTest` compares the two engines rule by rule, and requires every rule to have been compared at least once on a non-zero score: if no case makes it bite, give `HandSearchFixtures.fabricateBranches` something to do so.

## 5. Test

In the family's `*ConstraintsTest` (`solver/constraints/`, Timefold `ConstraintVerifier`, no Quarkus and no database, with the `ConstraintTestBase` base), at least:

- one case that triggers the penalty;
- one valid case that does not.

For a hard rule, `PlanningHardConstraintsTest` must keep asserting that the hard score of a solved plan is zero on the nominal scenario; extend it to the new rule. If needed, add a case in `ConstraintToggleTest` when the rule opens a family of streams the switch has never covered: the same dataset must be penalised without the toggle and score zero with it.

Run the target class, then the full round:

```bash
./mvnw test -Dtest=QualiteConstraintsTest
./mvnw test -Dtest=PlanningHardConstraintsTest
./mvnw test -Dtest='HandSearch*'
./mvnw test
```

A rule that changes what the solver converges to also falls under the slow scenario tests: see [the tests](tests.md).

## 6. Say why

The catalogue description says *what*. If the rule arbitrates something it cannot carry (a choice of level, a tension with another rule, what it deliberately refuses to count), add the *why* to the page that covers its family: [legal rules and breaks](regles-legales-et-pauses.md), [rest and consecutive days](repos-et-jours-d-affilee.md) or [coverage and comfort](regles-de-couverture-et-de-confort.md). If the rule opens a capability visible to the organiser, the functional part of the documentation changes too, in business language, with no class names.
