---
twin: comprendre/tests-de-scenarios
---
# Testing the solver: scenarios, bench, direct engine and mutation

You will know where the test scenarios live and which ones run in the default suite, how to measure a solve or a rule variant before committing it, how the two engines are kept in agreement, and how mutation puts the constraint tests to the test.

## Where the scenarios live

All scenarios (hand-written fixtures, the ladder, extreme cases) are flat in the single folder `src/main/resources/scenarios/`. No subfolder, no second `scenarios/` folder elsewhere on the classpath: `ScenarioYamlReader.scenarioPath` refuses a name that carries a path component, and `getResource("scenarios")` returns only the first occurrence on the classpath, never their union. A file stored any other way is a file nobody can load from the "Examples" card of Files › Import. A file added to the folder shows up there with no coding; its readable label and its sentence (and their translation) are added in `pages/imports/exemples.ts`.

On the test side, `ScenariosLivres` is the only reader of this folder:

- `all()`: everything, for the checks that apply to every file (validator, binder);
- `references()`: the hand-written fixtures only, for the differential tests that freeze a canonical form per file (a reference generated for a generated file would be read by nobody);
- `noms(prefixe)`: the ladder or the extremes, for their catalogue checks.

## What is slow is tagged

Tests of this weight class are tagged `@Tag("scenario-lent")`, excluded from `./mvnw test` and `verify` by the `test.excludedGroups` property of the `pom.xml` and of the main workflow. Tag any future test of this weight the same way rather than slowing the ordinary loop. `./mvnw test -Pscenario-tests` lifts the exclusion (the `scenario-extreme` ones stay excluded), optionally restricted by `-Dtest=…`. These notably include `PlanningServiceScenarioCompletTest`, `PlanningServiceScenarioContinuTest`, `PlanningServiceScenarioFestivalRealisteTest`, `PlanningServiceScenarioFestivalHivernalTest` and `PlanningServiceScenarioPublishedRunCapTest`, which solve large problems down to zero hard. `festival-realiste-canicule.yaml` and `festival-hivernal.yaml` are anonymised real fixtures, written before plages, whose stands still carry recurring opening hours: the reader converts them into plages (link 2 → 3, [ADR 0104](repo:docs/decisions/0104-les-horaires-d-un-stand-sont-une-liste-de-plages-datees.md)), so a plain-Java harness has nothing left to expand before building the seats. `ShippedSchedulesDigestTest` keeps, for each shipped scenario, the digest of the windows 3.x drew from it day by day.

CI runs them only when something that can break convergence changes: `.github/workflows/scenario-tests.yml` triggers on `solver/`, `domain/`, `solverConfig.xml`, `application.properties`, the scenario files and `pom.xml`. Keep this path list up to date when these files move: a solver tuning shipped without these tests having run is the hole this workflow closes. Run them as a background task from an agent session. On a Timefold upgrade, they are exactly what to replay, along with `ConstraintDiagnosticServiceContractTest`.

## The scenario ladder

The thirty `gamme-…` files are sorted by size: one day, two stands and three staff members at rung 1; one month, 150 stands and 320 staff members at rung 25. Each rung exercises part of what a file can express: midday rotation, premium stands, minors and public holidays, ad hoc exceptions, recurring opening hours under all their scopes, meal relays at full or reduced headcount, typical days, disabled constraints, headcounts carried by the window, target edition. Rungs 26 to 30 must never be solved, each for a named reason, and the test says whether the feasibility analysis sees it.

A file's name is its index: `gamme-18-14j-35stands-132animateurs-effectifs-par-fenetre`. `ScenarioLadderCatalogTest` fails if a name no longer states the file's days, stands and staff members, if a file is played by no test or if the numbering has a gap.

The problem is built as in production, without a database: `ScenarioLadder` resolves the opening hours, splits the opening spans, numbers the split shifts the way the database would and applies `contraintes.desactivees`. After the hard score, `assertCoreRules` rereads the plan itself (unavailability days, overlaps, minors, six days per week, ad hoc exceptions) so that a constraint that stopped penalising does not turn green with it.

| Class | Rungs | Where |
| --- | --- | --- |
| `ScenarioLadderSmallTest` | 1 to 7 | default suite |
| `ScenarioLadderMediumTest` | 8 to 15 | default suite |
| `ScenarioLadderInfeasibleTest` | 26 to 30 | default suite |
| `ScenarioLadderImportTest` | 2, 7, 8, 10 | default suite (`@QuarkusTest`), replays these rungs through the real import and checks that both paths produce the same seats |
| `ScenarioLadderLargeTest` | 16 to 25 | `scenario-lent`, `-Pscenario-tests` |
| `StaffingBoundEquivalenceTest` | 1 to 25 | `scenario-lent`: the exact staffing bound against the real need of the made-up team, rung by rung. The need comes from the search, or, at rungs 10, 11, 18 and 20, from an exact proof (`scripts/besoin-exact/modele.py`, CP-SAT, outside the image: [ADR 0098](repo:docs/decisions/0098-la-borne-exacte-s-affiche-d-abord-le-calcul-reste-le-defaut.md)); `-Dequivalence.rungs=1,2` to choose |

The rungs are sized with margin, at most three quarters of the staff called on the busiest day: all of them reach zero hard gap from the construction heuristic onward, so that what they reread does not depend on the machine's speed. They keep the functional coverage; the convergence of a tight problem remains the business of the `festival-*` scenarios. When a change moves a rung, that is a decision: fix the file or the assertion, saying why, never both silently.

## The extreme scenarios

The fifteen `extreme-…` files push each dimension beyond what a real edition requires, to learn where the application gives way before an edition finds out. Same harness as the ladder, same invariants reread on the plan, and `ScenarioExtremeCatalogTest` (default suite) checks that each file is valid, played by a test and named after its size.

| File | Limit explored |
| --- | --- |
| `extreme-01` | 1,000 staff members for 240 seats |
| `extreme-02` | one month, 150 stands, 1,000 staff members |
| `extreme-03` | 120 days, six public holidays, seasonal staff |
| `extreme-04` | 120 days without a break, 15% margin |
| `extreme-05` | 500 stands on the same day |
| `extreme-06` | 24/7 operation for a week |
| `extreme-07` | one-hour shifts |
| `extreme-08` | a stand with 200 seats |
| `extreme-09` | everything at once: 120 days, 400 stands, 1,000 staff members |
| `extreme-10` to `15` | no staff, one seat for 1,000, minors only, 2,000 ad hoc exceptions, stands never open, no timeslot |

What gives way first is the construction heuristic: it evaluates each seat against each candidate, its cost follows seats × staff members, and it does not parallelise without the Enterprise edition. Beyond 15 million pairs, it is sampled (see [solver tuning](regler-le-solveur.md)). Reading, building the problem, analysis and local search down to zero hard all hold up at that scale. The ad hoc contradiction check reads the exceptions by staff member index, because all its rules assume a staff member in common: `ContrainteAdHocContradictionsIndexTest` keeps this reading equal to the pairwise comparison, order included.

The degenerate cases (`ScenarioExtremeDegenerateTest`) run in the default suite. The axes and the cumulative case (`ScenarioExtremeAxisTest`, `ScenarioExtremeCumulTest`) carry the `scenario-extreme` tag, excluded from the default suite and from the `scenario-tests` profile: one class at a time, with a heap sized for it, nothing else running in parallel.

```bash
./mvnw test -Pscenario-extreme -DargLine=-Xmx3g -Dtest=ScenarioExtremeAxisTest
```

Their time ceilings are worth about ten times the measured time: they say that a regression has happened, not how long the machine takes. A larger heap gains nothing there, and it is the first thing that memory pressure on a shared machine kills.

## The solver bench

Classes of `service/solve`, tagged `scenario-lent`, serve to measure a solve rather than to validate it. They do nothing without their system properties, so `-Pscenario-tests` ignores them.

`SolverBenchTest` solves a scenario under the production search and writes the curve of its best score: a file of `scenarios/` by its name, or any YAML by its absolute path, so that an exported edition can be measured without being versioned. The result is appended as one Markdown line to `target/bench/results.md` and the full curve as CSV.

```bash
./mvnw test -Pscenario-tests -Dtest=SolverBenchTest \
    -Dbench.scenario=festival-hivernal.yaml -Dbench.label=avant
```

| Option | Effect |
| --- | --- |
| `bench.seconds`, `bench.plateau` | budget and stop on plateau (900 and 300 by default) |
| `bench.stopWhenFeasible=true` | the feasibility phase only |
| `bench.calculations=N` | stop after N score calculations: two configurations that search the same way end on the same plan, whatever their speed, which proves that a constraint rewrite changed only the cost |
| `bench.drWindow`, `bench.drRatio` | a diminishing-returns termination in place of the plateau |
| `bench.seed` | the seed |
| `bench.jfr=<file>` | records the solve alone with Java Flight Recorder (`profile` setting), to be read with `jfr print --events jdk.ExecutionSample` |
| `bench.engine=hand` | measures the direct engine, under the same budget, the same plateau and the same seed; `calculations`, `stopWhenFeasible` and `drWindow`, which rewrite Timefold's termination, then have no effect |

`RuleBenchTest` measures what a rule change would cost before writing it. Each scenario is solved twice, with the same budget and the same seed, as is and then with the variant. The bench writes the score per level before and after, the time to feasibility, the seats that changed hands and a verdict against the decision threshold (at most 5% of medium lost). The variant is stated by `bench.off=regleA,regleB` (disabled rules: a removal, or what a merge would replace) and/or `bench.level=regle:HARD|MEDIUM|SOFT[:poids]` (a rule moved to another level). The scenarios are the `gamme-*` (except `infaisable`) and the `festival-*`, restricted by `bench.only=gamme-01,gamme-02`; `bench.scenario=<absolute path>` adds a local file read in place, never copied into the repository. Per solve: `bench.seconds` (60), `bench.plateau` (30), `bench.seed` (0), `bench.engine=timefold` for Timefold's search instead of the direct engine, `bench.label`.

The variant's plan is rescored under the current rules: a plan solved without a rule scores better by its own rules than it deserves, the verdict is read on this rescored medium, and a hard worse than before always refuses. Changed seats are counted on the natural key (stand, day, hours) by the comparison of `ChangementsJourneeService`, never on the seat's id. A scenario in error keeps its line and then makes the test fail. The outputs are `target/bench/regles-<label>.md` and `.csv`.

```bash
# short trial: two rungs, a few seconds each
./mvnw test -Pscenario-tests -Dtest=RuleBenchTest \
    -Dbench.off=preserverBufferPolyvalents -Dbench.only=gamme-05,gamme-08 \
    -Dbench.seconds=8 -Dbench.plateau=4 -Dbench.label=essai
```

Three other classes complete the bench:

- `HandSearchFuzzTest` hunts for plans that the direct engine and the constraints do not score alike. During `-Dfuzz.seconds=N` (60 by default), on `-Dfuzz.scenario=…` (versioned name or absolute path), it draws variations of the problem, compares a series of plans rule by rule, then walks the incremental score through thousands of moves while checking its total. Each divergence is written to `-Dfuzz.out=<folder>` with what is needed to replay it (`-Dfuzz.seed=S -Dfuzz.round=N`), containing nothing but identifiers, and the test fails at the end if it found any.
- `HandSearchThroughputTest` times the direct engine on a fixed workload (`-Dthroughput.scenario=…`): a seeded descent of the incremental score, and a search stopped after a given number of looks at its stop flag, in moves per second after a few warm-up passes. Two versions that decide alike make the same moves: the time measures only their speed, and the printed digest says whether the work was indeed the same.
- `TerminationReplayTest` replays termination rules on a recorded curve (`-Dreplay.curve=<csv>`): the plateau at several durations, Timefold's diminishing returns at several windows and ratios, a minimal medium gain over a window. A rule that reads only the best score over time stops at one point of the curve, so one long solve per scenario answers for all the variants.

To measure as on the production host, bound the bench's JVM with `-DargLine` (heap at 75% of the container limit and the image's garbage collector):

```bash
./mvnw test -Pscenario-tests -Dtest=SolverBenchTest \
    "-DargLine=-Xmx1536m -XX:+UseParallelGC -XX:ActiveProcessorCount=2" \
    -Dbench.scenario=festival-hivernal.yaml -Dbench.engine=hand \
    -Dbench.seconds=300 -Dbench.seed=0 -Dbench.label=petite-machine
```

Since the seeds are fixed, two runs of the same configuration trace the same curve, to within a few seconds. The measurements that justify a `solverConfig.xml` setting come from this bench.

## The direct engine: two tests that keep the rules in duplicate

`solver/handsearch` writes the 46 rules a second time, to search without going through the constraint streams ([ADR 0087](repo:docs/decisions/0087-un-second-moteur-de-recherche-arbitre-par-timefold.md), default engine since [ADR 0088](repo:docs/decisions/0088-le-moteur-direct-devient-le-moteur-par-defaut.md)). Two tests of the default suite, with no container, keep the two copies from diverging.

`HandSearchCoverageStructuralTest` requires that every constraint of `ConstraintCatalog` have its constant in `Rule` and that every constant be counted in the engine's score. It fails first, by name, when a constraint is added on one side only.

`HandSearchDifferentialTest` compares the engine's score with the constraints', constraint by constraint, on eleven cases and a dozen plans per case. The cases are small scenarios of the ladder, as is or with the branches built by `HandSearchFixtures.fabricateBranches`: locks, pinned seats, the past, published plan, split seats (including after midnight), ceilings, ad hoc exceptions, birthdays, unknown dates of birth. Each plan is compared twice: its score reread from scratch, and the score that the search kept move by move, which must also be the constraints' one. A final check names any constraint never compared on a non-zero score. A real-size case (`festival-hivernal`, `gamme-25`) is tagged `scenario-lent`, and a rule change, in either engine, calls for rerunning it.

Adding a constraint therefore takes three writings: the stream in `solver/constraints`, the catalogue, and its term in `IncrementalScore` (or in `FlatProblem.staticTerms` if it reads only a seat and its holder). When the differential fails, its message gives the constraint and the two values, "engine / constraints", on the faulty plan. [Adding a rule](ajouter-une-regle.md) walks through the whole procedure.

Four classes hold what surrounds the search:

- `HandSearchSolverTest`: the engine behind the solver interface, the stop, and the refusal of a plan that the referee does not score alike.
- `SolveEnginesPerimetersTest`: incremental perimeter, reseeding, locks, frozen past and published plan, built by the very pieces of `ProblemBuilder` and then solved by `SolveRunner.solvePrepared` once per engine. A pinned seat moves under neither, the search starts from the plan in place, the returned plan is the one the constraints score. No container; its `scenario-lent` case plays a reopened day and a reseeding on `festival-hivernal`.
- `SolverJobHandEngineTest`: the application started under `planning.solver.engine=hand`. The setting reaches the seam, and a job that went through the queue leaves its curve, its stored plan and its constraint analysis, and stops when stopped, in full, reseeded and incremental solves. It starts the application, so it is listed in `container-tests.txt`.
- `PlateauTest`: the direct engine's stop on plateau, on score series dated by hand. The expected instants are deduced from the sources of Timefold's terminations, which the test does not run.

## Mutation tests

Coverage says a line ran, not that a test would fail if it were wrong. PIT measures it on the solver's constraints: it alters their bytecode (an inverted comparison, a `>` turned into `>=`, a cancelled penalty), replays the `*ConstraintsTest` on each mutant and counts those that no test notices.

```bash
./mvnw -Pmutation test-compile pitest:mutationCoverage   # about 4 min
# report: target/pit-reports/index.html, mutant by mutant
```

The `mutation` profile targets `dev.sylvain.planning.solver.constraints.*` and excludes the `scenario-lent` and `scenario-extreme` tags. `mutationThreshold` is 80: below that threshold, the build fails. A surviving mutant is killed by a test case, most often the boundary value that no test set (exactly the minimum rest, exactly the age of majority). The threshold never goes down; it goes up when the measurement goes up. The `mutation.yml` workflow runs the check every Tuesday, and on a pull request that touches `solver/constraints/` or its tests.
