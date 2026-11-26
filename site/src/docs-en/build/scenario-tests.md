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

Tests of this weight class are tagged `@Tag("scenario-lent")`, excluded from `./mvnw test` and `verify` by the `test.excludedGroups` property of the `pom.xml` and of the main workflow. Tag any future test of this weight the same way rather than slowing the ordinary loop. `./mvnw test -Pscenario-tests` lifts the exclusion (the `scenario-extreme` ones stay excluded), optionally restricted by `-Dtest=…`. These notably include `PlanningServiceScenarioCompletTest`, `PlanningServiceScenarioContinuTest`, `PlanningServiceScenarioFestivalRealisteTest`, `PlanningServiceScenarioFestivalHivernalTest` and `PlanningServiceScenarioPublishedRunCapTest`, which solve large problems down to zero hard. `festival-realiste-canicule.yaml` and `festival-hivernal.yaml` are anonymised real fixtures, the only ones whose stands carry recurring opening hours: a plain-Java harness must expand them through `HoraireStandResolver.appliquer` before building the seats, otherwise it solves a problem five times too large.

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

## The consecutive-days bench

`ConsecutiveRunsBenchTest` measures the consecutive-days rule at its two levels: `preference`, what ships, and `obligatoire`, its weight carried to one hard point ([ADR 0111](repo:docs/decisions/0111-le-niveau-d-une-regle-est-le-niveau-de-son-poids.md)). Written before the merge of the two 3.x forms, it priced that merge, and the removal of the calendar construction order ([ADR 0068](repo:docs/decisions/0068-sous-la-regle-dure-la-construction-suit-le-calendrier.md), withdrawn by [ADR 0118](repo:docs/decisions/0118-une-seule-regle-de-jours-d-affilee-a-niveau-reglable.md)): these are the measurements below.

```bash
./mvnw test -Pscenario-tests -Dtest=ConsecutiveRunsBenchTest \
    -Dbench.scenario=festival-realiste-canicule.yaml -Dbench.engine=timefold \
    -Dbench.seconds=120 -Dbench.seeds=3 -Dbench.cap=6
```

The class also launches as a plain `main` on the test classpath, to run several scenarios in parallel JVMs. Each solve lasts its whole budget, with no plateau bailout, seeds 0 to 2. The cap is set to six days (`bench.cap`).

The measurements below were taken before the merge, on the variants of the time: `preference` (the medium form alone), `both` (both forms on), `hard` (the hard form alone, with the calendar order under Timefold), `hard-par-poids` (the medium form whose weight is one hard point: the merged rule, `obligatoire` today) and, under Timefold only, `both-sans-calendrier` and `hard-sans-calendrier`. The direct engine always builds by scarcity: the calendar order did not exist there. Since the merge, the whole-day search ([ADR 0049](repo:docs/decisions/0049-la-regle-dure-des-jours-d-affilee-se-cherche-par-jours-entiers.md)) follows the mandatory rule: `obligatoire` is `hard-sans-calendrier`.

The last column reads every final plan again under the mandatory rule: the number of seeds in which no animateur exceeds the cap.

Measured on 26 November 2026, one machine with 12 cores and 24 GB (nine JVMs in parallel, one core each), 120 s per solve (15 to 30 s for `gamme-28` and `gamme-16`), medians of three seeds:

| Scenario | Engine | Variant | Feasible (the variant's rules) | Time to feasibility | Hard (med.) | Medium (med.) | Soft (med.) | Cap held (mandatory rule) |
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
| `festival-hivernal` | direct | `both` | 0/3 | never | -2 | -24285 | -98865 | 0/3 |
| `festival-hivernal` | direct | `hard` | 1/3 | 12.3 s | -1 | -24205 | -98715 | 1/3 |
| `festival-hivernal` | direct | `hard-par-poids` | 1/3 | 11.9 s | -1 | -24180 | -98785 | 1/3 |
| `festival-hivernal` | Timefold | `preference` | 3/3 | 27.5 s | 0 | -31105 | -95875 | 0/3 |
| `festival-hivernal` | Timefold | `both` | 0/3 | never | -16 | -30035 | -97215 | 0/3 |
| `festival-hivernal` | Timefold | `both-sans-calendrier` | 0/3 | never | -13 | -34715 | -103785 | 0/3 |
| `festival-hivernal` | Timefold | `hard` | 0/3 | never | -16 | -29890 | -97565 | 0/3 |
| `festival-hivernal` | Timefold | `hard-sans-calendrier` | 0/3 | never | -13 | -34655 | -103350 | 0/3 |
| `festival-hivernal` | Timefold | `hard-par-poids` | 0/3 | never | -20 | -34620 | -100345 | 0/3 |
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
| `gamme-28` | direct | `preference` | 0/3 | never | -3 | -180 | 0 | 0/3 |
| `gamme-28` | direct | `both` | 0/3 | never | -3 | -180 | 0 | 0/3 |
| `gamme-28` | direct | `hard` | 0/3 | never | -3 | -180 | 0 | 0/3 |
| `gamme-28` | direct | `hard-par-poids` | 0/3 | never | -3 | -180 | 0 | 0/3 |
| `gamme-28` | Timefold | `preference` | 0/3 | never | -3 | -180 | 0 | 0/3 |
| `gamme-28` | Timefold | `both` | 0/3 | never | -3 | -180 | 0 | 0/3 |
| `gamme-28` | Timefold | `both-sans-calendrier` | 0/3 | never | -3 | -180 | 0 | 0/3 |
| `gamme-28` | Timefold | `hard` | 0/3 | never | -3 | -180 | 0 | 0/3 |
| `gamme-28` | Timefold | `hard-sans-calendrier` | 0/3 | never | -3 | -180 | 0 | 0/3 |
| `gamme-28` | Timefold | `hard-par-poids` | 0/3 | never | -3 | -180 | 0 | 0/3 |

What stands out:

- **The direct engine**: the mandatory form costs at most 3.4% of medium on `gamme-20`, 0.5% on `scenario-complet`, nothing on `gamme-25` and the heatwave, and does better on `gamme-16`. It holds the cap wherever the grid allows, which the preference does not on `scenario-complet`, on `festival-hivernal`, nor always on `gamme-20`. Feasibility comes in under 2 s, 12 s on `festival-hivernal`, whose grid holds at six days in only one seed out of three. `hard-par-poids` and `hard` give the same plans, seed for seed.
- **Timefold**: the calendar order is worth 6 to 8% of medium on the heatwave and `scenario-complet`, 35% on `gamme-20` and `gamme-25`, and 10 to 15% of time to feasibility. The rule merged by weight (`hard-par-poids`) fell back to `hard-sans-calendrier`, since nothing told the comparator that the rule was mandatory. The calendar order is withdrawn in 4.0: the regression is announced in the [Moving to 4.0](../installer/passer-a-la-4-0.md) guide.
- `gamme-28` (infeasible by construction) is so in every variant, on both engines.

## The direct engine: two tests that keep the rules in duplicate

`solver/handsearch` writes the 41 rules a second time, to search without going through the constraint streams ([ADR 0087](repo:docs/decisions/0087-un-second-moteur-de-recherche-arbitre-par-timefold.md), default engine since [ADR 0088](repo:docs/decisions/0088-le-moteur-direct-devient-le-moteur-par-defaut.md)). Two tests of the default suite, with no container, keep the two copies from diverging.

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
