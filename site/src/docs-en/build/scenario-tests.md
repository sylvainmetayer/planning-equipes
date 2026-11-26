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

Tests of this weight class are tagged `@Tag("scenario-lent")`, excluded from `./mvnw test` and `verify` by the `test.excludedGroups` property of the `pom.xml` and of the main workflow. Tag any future test of this weight the same way rather than slowing the ordinary loop. `./mvnw test -Pscenario-tests` lifts the exclusion (the `scenario-extreme` ones stay excluded), optionally restricted by `-Dtest=…`. These notably include `PlanningServiceScenarioCompletTest`, `PlanningServiceScenarioContinuTest`, `PlanningServiceScenarioFestivalRealisteTest`, `PlanningServiceScenarioFestivalHivernalTest` and `PlanningServiceScenarioPublishedRunCapTest`, which solve large problems down to zero hard. `festival-realiste-canicule.yaml` and `festival-hivernal.yaml` are anonymised real fixtures, written before plages, whose stands still carry recurring opening hours: the reader converts them into plages (link 4 → 5, [ADR 0104](repo:docs/decisions/0104-les-horaires-d-un-stand-sont-une-liste-de-plages-datees.md)), so a plain-Java harness has nothing left to expand before building the seats. `ShippedSchedulesDigestTest` keeps, for each shipped scenario, the digest of the windows 3.x drew from it day by day.

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
- **Timefold**: the calendar order is worth 6 to 8% of medium on the heatwave and `scenario-complet`, 35% on `gamme-20` and `gamme-25`, and 10 to 15% of time to feasibility. The rule merged by weight (`hard-par-poids`) fell back to `hard-sans-calendrier`, since nothing told the comparator that the rule was mandatory. The calendar order is withdrawn in 4.0: the regression is announced in the [Moving to 4.0](../installer/passer-a-la-4-0.md) guide. Replayed after the merge, on the same machine and under Timefold, `obligatoire` reaches feasibility in all three seeds and holds the cap everywhere, within 0.3% of the medium of `hard-sans-calendrier`; the 3.x `hard` scored 6% (heatwave), 8% (`scenario-complet`) and 35% (`gamme-20`, `gamme-25`) less medium.
- `gamme-28` (infeasible by construction) is so in every variant, on both engines.

## The place-rules bench

`PlaceRulesBenchTest` measures what merging the place rules into two would cost: `trajet impossible` (`trajetInsuffisantEntrePostes`: speed, detour, tolerance) and `dispersion sur la journée`, which would absorb `eviterChangementEmplacementEloigne` (two consecutive seats more than 300 m apart) and `limiterEmplacementsParJour` (more than N distinct locations in a day). It leaves production untouched: the variants are rule toggles and the location cap of the quality parameters.

```bash
./mvnw test -Pscenario-tests -Dtest=PlaceRulesBenchTest \
    -Dbench.scenario=gamme-20-21j-50stands-172animateurs-festival-type.yaml -Dbench.engine=hand \
    -Dbench.seconds=60 -Dbench.seeds=3
```

The class also launches as a plain `main` on the test classpath. Each solve lasts its whole budget, with no plateau bailout, seeds 0 to 2. The variants are `actuel` (the three rules, what ships), `dispersion-plafond` (the 300 m rule removed, the daily cap alone), `dispersion-plafond-serre` (the same with a cap of 2), `dispersion-distance` (the daily cap removed, the 300 m rule alone), `sans-dispersion` (both removed, the impossible trip alone) and `sans-trajet` (the impossible trip removed, both dispersion rules kept). Every final plan is read again under the rules and cap of `actuel`: that is the « Medium under the `actuel` rules » column, the only one comparable from one variant to the next. The gap is measured on it, median of the three seeds: a minus sign is a loss of medium.

Measured on 26 November 2026, one machine with 8 cores and 24 GB (eight JVMs in parallel), 60 s per solve (30 s for `scenario-complet`), medians of three seeds:

| Scenario | Engine | Variant | Feasible | Time to feasibility | Hard (med.) | Medium (med.) | Soft (med.) | Medium under the `actuel` rules | Medium gap |
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

What to take from it:

- **Feasibility** does not move in any variant: three seeds out of three, on both engines, and the time to feasibility stays within noise (a few seconds at most under Timefold).
- **The daily cap melts into the distance.** `dispersion-distance` (the 300 m rule alone) stays between -0.5% and +1.3% of medium on the five scenarios, on both engines: seed noise.
- **The distance cannot be replaced by the cap.** `dispersion-plafond` loses 12.1% (direct) and 9.3% (Timefold) of medium on `gamme-20`, 8.6% (direct) on `scenario-complet`; tightening it to 2 makes `gamme-20` worse (-13.6% and -11.3%). `sans-dispersion` gives the same losses: the 300 m rule carries the measure, the cap adds almost nothing.
- **The impossible trip cannot be removed**: `sans-trajet` loses 25.6% (direct) and 17.3% (Timefold) on `gamme-24`, the scenario with distant locations.
- Both festivals and `gamme-24` stay within 1.5% in every dispersion variant.

The 5% rule decided for these measures: a medium loss beyond 5%, or a default score that moves, and the rules are not merged. A merge that keeps only the cap (`dispersion-plafond`, `dispersion-plafond-serre`) exceeds it on `gamme-20` and `scenario-complet`: it is rejected. The `dispersion-distance` form respects it, but it drops a penalty that exists today (beyond three locations in a day): the score of a default plan can therefore move, and that is to be read again before the merge is written.

## The meal-rules bench

`MealRulesBenchTest` measures what merging the two meal rules, `coupureRepasObligatoire` (the break owed, hard) and `coupureRepasPlacementPrefere` (where in the window it is taken, soft), into one rule whose preferred placement is a parameter would cost, before anybody writes it. It changes no behaviour and needs no seam in production code: the variants are toggles, weights that carry a rule to another level ([ADR 0111](repo:docs/decisions/0111-le-niveau-d-une-regle-est-le-niveau-de-son-poids.md)) and a rewrite of the problem's `FenetreRepas` facts, whose `auPlusTard` flag is the placement the merged rule would read as a parameter. What the bench showed was followed: the merge happens in the catalogue and on screen, not in the score, and the placement is an edition setting ([ADR 0123](repo:docs/decisions/0123-une-seule-entree-de-repas-placement-en-parametre.md)).

```bash
./mvnw test -Pscenario-tests -Dtest=MealRulesBenchTest \
    -Dbench.scenario=festival-hivernal.yaml -Dbench.engine=hand \
    -Dbench.seconds=60 -Dbench.seeds=3
```

The class also launches as a plain `main` on the test classpath. Each solve lasts its whole budget, with no plateau bailout, seeds 0 to 2. The variants are `actuel` (what ships: break hard, placement soft), `fusion-dur` (one rule at the mandatory level: the placement gap counts one hard point per minute, like the missing break), `fusion-preference` (one rule at the preference level: break and placement each weigh one medium point of weight 5 per minute), `sans-placement` (parameter set to "no preference": the break alone), `placement-debut` and `placement-fin` (parameter set to the start, or the end, of every window; the shipped one is the end at midday and the start in the evening, placement soft).

The last three columns read every final plan again under the shipped rules: the number of seeds in which every hard rule holds, break included, then the medium and soft under those rules. It is the only common ruler: the variants' own scores cannot be compared with each other, since the weight of `fusion-preference` multiplies the minutes by five.

Measured on 26 November 2026, one machine with 8 cores and 30 GB (eight JVMs in parallel), 60 s per solve (30 s for `gamme-14`), medians of three seeds. "never" means no feasible plan in any seed:

| Scenario | Engine | Variant | Feasible (the variant's rules) | Time to feasibility | Hard (med.) | Medium (med.) | Soft (med.) | Hard held (shipped rules) | Medium (shipped rules) | Soft (shipped rules) |
|---|---|---|---|---|---|---|---|---|---|---|
| `festival-hivernal` | direct | `actuel` | 3/3 | 4.0 s | 0 | -25490 | -96235 | 3/3 | -25490 | -96235 |
| `festival-hivernal` | direct | `fusion-dur` | 0/3 | never | -234 | -27095 | -4230 | 0/3 | -27095 | -4230 |
| `festival-hivernal` | direct | `fusion-preference` | 3/3 | 4.5 s | 0 | -96890 | -4265 | 0/3 | -26690 | -73565 |
| `festival-hivernal` | direct | `sans-placement` | 3/3 | 2.8 s | 0 | -26015 | -4560 | 3/3 | -26015 | -121260 |
| `festival-hivernal` | direct | `placement-debut` | 3/3 | 3.1 s | 0 | -26020 | -11560 | 3/3 | -26020 | -122560 |
| `festival-hivernal` | direct | `placement-fin` | 3/3 | 3.1 s | 0 | -25810 | -99175 | 3/3 | -25810 | -99175 |
| `festival-hivernal` | Timefold | `actuel` | 3/3 | 36.0 s | 0 | -34800 | -91755 | 3/3 | -34800 | -91755 |
| `festival-hivernal` | Timefold | `fusion-dur` | 0/3 | never | -246 | -37105 | -1385 | 0/3 | -37105 | -1385 |
| `festival-hivernal` | Timefold | `fusion-preference` | 3/3 | 38.2 s | 0 | -109540 | -2045 | 1/3 | -35140 | -75825 |
| `festival-hivernal` | Timefold | `sans-placement` | 3/3 | 50.4 s | 0 | -36625 | -1645 | 3/3 | -36625 | -110150 |
| `festival-hivernal` | Timefold | `placement-debut` | 3/3 | 35.4 s | 0 | -35475 | -9750 | 3/3 | -35475 | -109975 |
| `festival-hivernal` | Timefold | `placement-fin` | 3/3 | 43.3 s | 0 | -35615 | -92520 | 3/3 | -35615 | -92520 |
| `festival-realiste-canicule` | direct | `actuel` | 3/3 | 1.3 s | 0 | -16620 | -66730 | 3/3 | -16620 | -66730 |
| `festival-realiste-canicule` | direct | `fusion-dur` | 0/3 | never | -100 | -15490 | -2715 | 0/3 | -15490 | -2715 |
| `festival-realiste-canicule` | direct | `fusion-preference` | 3/3 | 0.5 s | 0 | -47415 | -2690 | 3/3 | -17415 | -32690 |
| `festival-realiste-canicule` | direct | `sans-placement` | 3/3 | 0.6 s | 0 | -16665 | -2865 | 3/3 | -16665 | -81465 |
| `festival-realiste-canicule` | direct | `placement-debut` | 3/3 | 0.6 s | 0 | -16665 | -2895 | 3/3 | -16665 | -80595 |
| `festival-realiste-canicule` | direct | `placement-fin` | 3/3 | 0.5 s | 0 | -16620 | -65995 | 3/3 | -16620 | -65995 |
| `festival-realiste-canicule` | Timefold | `actuel` | 3/3 | 17.3 s | 0 | -19400 | -55090 | 3/3 | -19400 | -55090 |
| `festival-realiste-canicule` | Timefold | `fusion-dur` | 0/3 | never | -100 | -24220 | -2025 | 0/3 | -24220 | -2025 |
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
| `scenario-complet` | direct | `fusion-dur` | 0/3 | never | -71 | -21355 | -1070 | 0/3 | -21355 | -1070 |
| `scenario-complet` | direct | `fusion-preference` | 3/3 | 0.7 s | 0 | -41020 | -305 | 3/3 | -19720 | -21745 |
| `scenario-complet` | direct | `sans-placement` | 3/3 | 0.9 s | 0 | -18200 | -545 | 3/3 | -18200 | -179345 |
| `scenario-complet` | direct | `placement-debut` | 3/3 | 0.8 s | 0 | -18170 | -108035 | 3/3 | -18170 | -182435 |
| `scenario-complet` | direct | `placement-fin` | 3/3 | 0.8 s | 0 | -18170 | -2055 | 3/3 | -18170 | -146655 |
| `scenario-complet` | Timefold | `actuel` | 3/3 | 19.0 s | 0 | -25340 | -160975 | 3/3 | -25340 | -160975 |
| `scenario-complet` | Timefold | `fusion-dur` | 0/3 | never | -100 | -27795 | -1135 | 0/3 | -27795 | -1135 |
| `scenario-complet` | Timefold | `fusion-preference` | 3/3 | 14.1 s | 0 | -55395 | -715 | 3/3 | -24795 | -31315 |
| `scenario-complet` | Timefold | `sans-placement` | 3/3 | 18.7 s | 0 | -24275 | -855 | 3/3 | -24275 | -176575 |
| `scenario-complet` | Timefold | `placement-debut` | 3/3 | 19.4 s | 0 | -25510 | -155090 | 3/3 | -25510 | -180835 |
| `scenario-complet` | Timefold | `placement-fin` | 3/3 | 19.7 s | 0 | -24885 | -35335 | 3/3 | -24885 | -171815 |
| `scenario-continu-avec-coupure` | direct | `actuel` | 3/3 | 3.6 s | 0 | -24745 | -51165 | 3/3 | -24745 | -51165 |
| `scenario-continu-avec-coupure` | direct | `fusion-dur` | 0/3 | never | -224 | -29980 | -1090 | 0/3 | -29980 | -1090 |
| `scenario-continu-avec-coupure` | direct | `fusion-preference` | 3/3 | 2.0 s | 0 | -52215 | -1120 | 1/3 | -29815 | -23410 |
| `scenario-continu-avec-coupure` | direct | `sans-placement` | 3/3 | 2.2 s | 0 | -24805 | -805 | 3/3 | -24805 | -106015 |
| `scenario-continu-avec-coupure` | direct | `placement-debut` | 3/3 | 1.8 s | 0 | -24610 | -12415 | 3/3 | -24610 | -83075 |
| `scenario-continu-avec-coupure` | direct | `placement-fin` | 3/3 | 2.2 s | 0 | -24805 | -56360 | 3/3 | -24805 | -56360 |
| `scenario-continu-avec-coupure` | Timefold | `actuel` | 3/3 | 34.0 s | 0 | -29125 | -73740 | 3/3 | -29125 | -73740 |
| `scenario-continu-avec-coupure` | Timefold | `fusion-dur` | 0/3 | never | -241 | -32490 | -585 | 0/3 | -32490 | -585 |
| `scenario-continu-avec-coupure` | Timefold | `fusion-preference` | 3/3 | 30.5 s | 0 | -65220 | -600 | 0/3 | -34665 | -24915 |
| `scenario-continu-avec-coupure` | Timefold | `sans-placement` | 3/3 | 43.4 s | 0 | -29270 | -580 | 3/3 | -29270 | -108585 |
| `scenario-continu-avec-coupure` | Timefold | `placement-debut` | 3/3 | 44.7 s | 0 | -29735 | -13765 | 3/3 | -29735 | -91540 |
| `scenario-continu-avec-coupure` | Timefold | `placement-fin` | 3/3 | 44.7 s | 0 | -29670 | -73725 | 3/3 | -29670 | -73725 |

What to take from it:

- **One level for both terms is ruled out.** `fusion-dur` finds no feasible plan on `festival-hivernal`, `festival-realiste-canicule`, `scenario-complet` and `scenario-continu-avec-coupure`, with either engine: 24 solves out of 24 are never feasible, and each row's median of hard points of violation runs from 71 to 246. Where it stays feasible it costs medium: 24% on `gamme-20` with the direct engine, 9% under Timefold.
- **The preference level loosens the break.** Under `fusion-preference`, the final plans hold every shipped hard rule in 3 seeds out of 3 on the heatwave scenario, `scenario-complet` and `gamme-14`, but not on `festival-hivernal` (0/3 with the direct engine, 1/3 under Timefold), `scenario-continu-avec-coupure` (1/3 and 0/3) or `gamme-20` under Timefold (0/3): a break turned into a preference is given up when the grid is tight.
- **The placement as a parameter costs nothing measurable.** In `sans-placement`, `placement-debut` and `placement-fin`, feasibility stays 3/3 everywhere and the medium under the shipped rules stays within 3% of `actuel` with the direct engine, `gamme-14` (13% for `sans-placement`, on small numbers) and `gamme-20` (6% for `sans-placement`) aside; under Timefold it moves by up to 9% depending on the scenario, in both directions, with nothing in the variant to explain it. The time to feasibility does not change with the direct engine; under Timefold it moves on three scenarios: `scenario-continu-avec-coupure` (34 s against 43 to 45 s), `festival-hivernal` (36 s against 43 to 50 s, `placement-debut` aside) and `gamme-20` (27 s against 29 to 33 s).
- **The direction of the placement weighs on the soft level, not on the rest.** The own soft of `placement-debut` is about eight times lower than `actuel`'s on `festival-hivernal` (-11,560 against -96,235) and `placement-fin`'s equals it: on that scenario, nearly all of the shipped gap is the "late at midday" preference. The direction that costs depends on the scenario: on `scenario-complet` it is `placement-debut` that equals `actuel` (-108,035 against -111,355) and `placement-fin` that falls to -2,055. `sans-placement` lets the shipped soft degrade (for instance -76,820 against -26,815 on `gamme-20`): the preference does act.
- `gamme-14` (few meals to arbitrate) barely tells the variants apart: feasibility and time are the same, the medium only moves from 4,600 to 5,200 on small numbers, and only the shipped soft shows the preference acting (0 for `actuel`, -3,075 for `sans-placement`).

## The comfort-rules bench

`ComfortRulesBenchTest` measures what merging neighbouring comfort rules would cost, before anybody writes it (the target is to remove about four). Three pairs are looked at: the two balances (`equilibrerCharge`, medium, and `equilibrerCreneauxPenibles`, soft), the two rules of premium and exhausting stands (`eviterRoulementStandsPremium` and `eviterEnchainementStandsEpuisants`), and the two mismatches of a seat (`appreciationIncompatible` and `experienceRequisePourStandsPremium`, the latter measured alone). `souhaitsIncompatibles`, `standComplexeAvecReferent`, `mineurNecessiteEncadrementMajeur` and the distribution of minors are not candidates. It touches nothing in production: the variants are rule toggles and a weight that carries a rule to another level ([ADR 0111](repo:docs/decisions/0111-le-niveau-d-une-regle-est-le-niveau-de-son-poids.md)).

```bash
./mvnw test -Pscenario-tests -Dtest=ComfortRulesBenchTest \
    -Dbench.scenario=festival-hivernal.yaml -Dbench.engine=hand \
    -Dbench.seconds=60 -Dbench.seeds=3
```

The class also launches as a plain `main` on the test classpath. Each solve lasts its whole budget, with no plateau bailout, seeds 0 to 2. The variants are `actuel` (what ships), `sans-penibles` and `sans-charge` (one balance does the work of both), `penibles-medium` (the balance of demanding seats carried to the medium level, weight 1, as one single balance rule would), `sans-roulement`, `sans-enchainement` and `sans-experience-premium` (the rule a merge would absorb, off). Every final plan is scored again under the shipped rules, and the five rules above are read off it one by one: the last column is what each costs in the plan, at its own level (soft for `equilibrerCreneauxPenibles`, medium for the others). A rule at zero has nothing to merge on that scenario: `scenario-complet` and `gamme-24` have neither premium stands nor exhausting stands that touch.

**A merge only passes if it keeps today's penalties exactly.** That is judged on paper before any bench: the same set of tuples, the same weight, hence the same default score.

- `equilibrerCharge` and `equilibrerCreneauxPenibles`: the same shape (`loadBalance` per animateur) but not the same population (every held seat, against the demanding seats only), not the same level (medium, weight 5; soft, weight 1) and not the same per-edition setting. A rule has one level: the soft penalty vanishes or changes level. The default score moves.
- `eviterRoulementStandsPremium` and `eviterEnchainementStandsEpuisants`: one counts, per premium stand, the faces beyond the crew; the other, the pairs of back-to-back seats of one person on exhausting stands. Neither the tuples nor the grouping key overlap: there is no common form that keeps both penalties.
- `appreciationIncompatible` and `experienceRequisePourStandsPremium`: one medium point per seat, weight 5 on both sides, over the same stream of seats. A rule with two reasons would keep the default score as it is. What is lost is not in the score: the weight and the switch of each rule, which the organiser sets separately (the administrator's appreciation is the one weighed more heavily).

What the bench adds to the analysis is the price of a merge that would remove a penalty (a `sans-…` variant) or change its level (`penibles-medium`). The plan is scored again under `actuel`'s rules; the gap is the medium under those rules, median of three seeds; a minus sign is a loss of medium. Noise between seeds is of the order of 5%: `gamme-24` gives `actuel` at -37,750 and every other variant at about -32,500, with no explanation for any of it.

Measures of 26 November 2026, an 8-core, 32 GB machine (eight JVMs in parallel), 60 s per solve (30 s for `scenario-complet`), medians of three seeds:

| Scenario | Engine | Variant | Feasible | Time to feasibility | Medium under `actuel`'s rules | Medium gap | Soft under `actuel`'s rules | Cost in the plan (`equilibrerCharge` / `equilibrerCreneauxPenibles` / `eviterRoulementStandsPremium` / `eviterEnchainementStandsEpuisants` / `experienceRequisePourStandsPremium`) |
|---|---|---|---|---|---|---|---|---|
| `festival-realiste-canicule` | direct | `actuel` | 3/3 | 1.8 s | -16,590 | +0.0 % | -68,000 | 110 / 1,325 / 630 / 0 / 30 |
| `festival-realiste-canicule` | direct | `sans-penibles` | 3/3 | 0.6 s | -16,535 | +0.3 % | -66,730 | 110 / 1,750 / 620 / 0 / 45 |
| `festival-realiste-canicule` | direct | `sans-charge` | 3/3 | 0.5 s | -18,005 | -8.5 % | -35,385 | 1,780 / 2,030 / 460 / 0 / 20 |
| `festival-realiste-canicule` | direct | `penibles-medium` | 3/3 | 0.5 s | -16,630 | -0.2 % | -73,570 | 110 / 1,045 / 650 / 0 / 40 |
| `festival-realiste-canicule` | direct | `sans-roulement` | 3/3 | 0.4 s | -18,645 | -12.4 % | -70,450 | 110 / 1,320 / 2,755 / 0 / 20 |
| `festival-realiste-canicule` | direct | `sans-enchainement` | 3/3 | 0.4 s | -16,565 | +0.2 % | -67,550 | 110 / 1,340 / 610 / 0 / 30 |
| `festival-realiste-canicule` | direct | `sans-experience-premium` | 3/3 | 0.4 s | -16,955 | -2.2 % | -63,400 | 110 / 1,295 / 605 / 0 / 445 |
| `festival-realiste-canicule` | Timefold | `actuel` | 3/3 | 15.6 s | -19,360 | +0.0 % | -54,545 | 130 / 1,105 / 1,440 / 0 / 90 |
| `festival-realiste-canicule` | Timefold | `sans-penibles` | 3/3 | 14.6 s | -19,050 | +1.6 % | -51,370 | 165 / 1,360 / 1,095 / 0 / 120 |
| `festival-realiste-canicule` | Timefold | `sans-charge` | 3/3 | 13.9 s | -18,750 | +3.2 % | -46,180 | 2,235 / 2,145 / 555 / 0 / 15 |
| `festival-realiste-canicule` | Timefold | `penibles-medium` | 3/3 | 14.4 s | -19,245 | +0.6 % | -58,590 | 110 / 995 / 1,415 / 0 / 80 |
| `festival-realiste-canicule` | Timefold | `sans-roulement` | 3/3 | 14.1 s | -20,770 | -7.3 % | -55,955 | 180 / 980 / 3,125 / 0 / 90 |
| `festival-realiste-canicule` | Timefold | `sans-enchainement` | 3/3 | 13.8 s | -19,175 | +1.0 % | -53,590 | 110 / 1,105 / 1,425 / 0 / 85 |
| `festival-realiste-canicule` | Timefold | `sans-experience-premium` | 3/3 | 14.1 s | -19,785 | -2.2 % | -54,150 | 150 / 930 / 1,400 / 0 / 500 |
| `festival-hivernal` | direct | `actuel` | 3/3 | 3.7 s | -25,270 | +0.0 % | -100,475 | 315 / 2,570 / 2,325 / 0 / 15 |
| `festival-hivernal` | direct | `sans-penibles` | 3/3 | 2.9 s | -25,235 | +0.1 % | -99,870 | 315 / 2,965 / 2,315 / 0 / 10 |
| `festival-hivernal` | direct | `sans-charge` | 3/3 | 1.8 s | -26,605 | -5.3 % | -93,600 | 3,800 / 3,860 / 1,965 / 0 / 0 |
| `festival-hivernal` | direct | `penibles-medium` | 3/3 | 2.3 s | -24,915 | +1.4 % | -109,980 | 315 / 2,155 / 2,165 / 0 / 10 |
| `festival-hivernal` | direct | `sans-roulement` | 3/3 | 2.8 s | -27,490 | -8.8 % | -96,040 | 315 / 2,730 / 4,910 / 0 / 35 |
| `festival-hivernal` | direct | `sans-enchainement` | 3/3 | 2.5 s | -24,980 | +1.1 % | -96,855 | 315 / 2,565 / 2,150 / 0 / 15 |
| `festival-hivernal` | direct | `sans-experience-premium` | 3/3 | 2.7 s | -26,345 | -4.3 % | -88,715 | 315 / 2,400 / 2,165 / 0 / 1,605 |
| `festival-hivernal` | Timefold | `actuel` | 3/3 | 39.6 s | -35,110 | +0.0 % | -91,980 | 315 / 1,065 / 5,240 / 0 / 725 |
| `festival-hivernal` | Timefold | `sans-penibles` | 3/3 | 36.2 s | -34,020 | +3.1 % | -97,540 | 315 / 1,520 / 4,310 / 0 / 670 |
| `festival-hivernal` | Timefold | `sans-charge` | 0/3 | never | -27,000 | +23.1 % | -110,660 | 3,820 / 4,335 / 1,390 / 0 / 110 |
| `festival-hivernal` | Timefold | `penibles-medium` | 3/3 | 36.4 s | -34,990 | +0.3 % | -97,365 | 330 / 905 / 5,295 / 0 / 740 |
| `festival-hivernal` | Timefold | `sans-roulement` | 3/3 | 41.4 s | -36,350 | -3.5 % | -88,950 | 310 / 1,105 / 7,205 / 0 / 690 |
| `festival-hivernal` | Timefold | `sans-enchainement` | 3/3 | 38.1 s | -34,815 | +0.8 % | -91,975 | 315 / 1,100 / 5,205 / 0 / 715 |
| `festival-hivernal` | Timefold | `sans-experience-premium` | 3/3 | 39.4 s | -35,785 | -1.9 % | -94,660 | 330 / 1,040 / 5,295 / 0 / 1,645 |
| `scenario-complet` | direct | `actuel` | 3/3 | 1.0 s | -18,275 | +0.0 % | -122,610 | 200 / 465 / 0 / 0 / 0 |
| `scenario-complet` | direct | `sans-penibles` | 3/3 | 0.7 s | -18,265 | +0.1 % | -122,865 | 235 / 500 / 0 / 0 / 0 |
| `scenario-complet` | direct | `sans-charge` | 3/3 | 0.6 s | -19,945 | -9.1 % | -61,550 | 3,290 / 0 / 0 / 0 / 0 |
| `scenario-complet` | direct | `penibles-medium` | 3/3 | 0.7 s | -18,280 | -0.0 % | -123,185 | 185 / 465 / 0 / 0 / 0 |
| `scenario-complet` | direct | `sans-roulement` | 3/3 | 0.7 s | -18,240 | +0.2 % | -119,815 | 185 / 465 / 0 / 0 / 0 |
| `scenario-complet` | direct | `sans-enchainement` | 3/3 | 0.7 s | -18,235 | +0.2 % | -119,810 | 185 / 465 / 0 / 0 / 0 |
| `scenario-complet` | direct | `sans-experience-premium` | 3/3 | 0.6 s | -18,235 | +0.2 % | -121,415 | 185 / 465 / 0 / 0 / 0 |
| `scenario-complet` | Timefold | `actuel` | 3/3 | 18.0 s | -29,520 | +0.0 % | -161,290 | 1,770 / 0 / 0 / 0 / 0 |
| `scenario-complet` | Timefold | `sans-penibles` | 3/3 | 16.3 s | -28,560 | +3.3 % | -161,310 | 1,780 / 0 / 0 / 0 / 0 |
| `scenario-complet` | Timefold | `sans-charge` | 3/3 | 16.5 s | -21,405 | +27.5 % | -70,190 | 4,055 / 0 / 0 / 0 / 0 |
| `scenario-complet` | Timefold | `penibles-medium` | 3/3 | 16.1 s | -28,495 | +3.5 % | -161,315 | 1,760 / 0 / 0 / 0 / 0 |
| `scenario-complet` | Timefold | `sans-roulement` | 3/3 | 16.3 s | -28,485 | +3.5 % | -161,310 | 1,780 / 0 / 0 / 0 / 0 |
| `scenario-complet` | Timefold | `sans-enchainement` | 3/3 | 16.3 s | -28,275 | +4.2 % | -161,300 | 1,770 / 0 / 0 / 0 / 0 |
| `scenario-complet` | Timefold | `sans-experience-premium` | 3/3 | 15.6 s | -28,515 | +3.4 % | -161,305 | 1,770 / 0 / 0 / 0 / 0 |
| `gamme-20` | direct | `actuel` | 3/3 | 3.0 s | -20,075 | +0.0 % | -43,855 | 435 / 2,775 / 2,000 / 30 / 0 |
| `gamme-20` | direct | `sans-penibles` | 3/3 | 2.4 s | -20,060 | +0.1 % | -43,700 | 430 / 3,485 / 1,920 / 15 / 0 |
| `gamme-20` | direct | `sans-charge` | 3/3 | 2.3 s | -20,065 | +0.0 % | -63,080 | 10,130 / 6,045 / 830 / 0 / 0 |
| `gamme-20` | direct | `penibles-medium` | 3/3 | 2.3 s | -19,630 | +2.2 % | -53,655 | 440 / 1,605 / 1,840 / 20 / 0 |
| `gamme-20` | direct | `sans-roulement` | 3/3 | 2.3 s | -21,835 | -8.8 % | -47,650 | 350 / 2,430 / 3,530 / 20 / 10 |
| `gamme-20` | direct | `sans-enchainement` | 3/3 | 2.2 s | -20,030 | +0.2 % | -37,260 | 435 / 2,610 / 1,920 / 265 / 0 |
| `gamme-20` | direct | `sans-experience-premium` | 3/3 | 2.1 s | -21,800 | -8.6 % | -39,275 | 435 / 2,435 / 2,090 / 20 / 2,430 |
| `gamme-20` | Timefold | `actuel` | 3/3 | 43.8 s | -38,530 | +0.0 % | -118,705 | 3,250 / 4,695 / 2,290 / 425 / 1,010 |
| `gamme-20` | Timefold | `sans-penibles` | 3/3 | 42.6 s | -38,600 | -0.2 % | -119,915 | 3,560 / 4,980 / 2,250 / 410 / 870 |
| `gamme-20` | Timefold | `sans-charge` | 3/3 | 44.2 s | -22,830 | +40.7 % | -86,910 | 9,250 / 6,440 / 950 / 15 / 0 |
| `gamme-20` | Timefold | `penibles-medium` | 3/3 | 46.2 s | -43,635 | -13.2 % | -124,395 | 3,885 / 5,040 / 2,590 / 435 / 600 |
| `gamme-20` | Timefold | `sans-roulement` | 3/3 | 46.4 s | -45,330 | -17.6 % | -123,810 | 4,615 / 3,945 / 3,470 / 425 / 940 |
| `gamme-20` | Timefold | `sans-enchainement` | 3/3 | 45.2 s | -40,790 | -5.9 % | -109,150 | 3,890 / 4,280 / 2,290 / 750 / 760 |
| `gamme-20` | Timefold | `sans-experience-premium` | 3/3 | 44.5 s | -42,700 | -10.8 % | -122,410 | 4,505 / 4,545 / 2,380 / 315 / 3,130 |
| `gamme-24` | direct | `actuel` | 3/3 | 3.3 s | -37,750 | +0.0 % | -11,165 | 5,090 / 0 / 0 / 0 / 0 |
| `gamme-24` | direct | `sans-penibles` | 3/3 | 3.3 s | -37,720 | +0.1 % | -11,220 | 5,135 / 0 / 0 / 0 / 0 |
| `gamme-24` | direct | `sans-charge` | 3/3 | 2.9 s | -31,465 | +16.6 % | -12,350 | 11,735 / 0 / 0 / 0 / 0 |
| `gamme-24` | direct | `penibles-medium` | 3/3 | 3.0 s | -33,095 | +12.3 % | -11,850 | 5,030 / 0 / 0 / 0 / 0 |
| `gamme-24` | direct | `sans-roulement` | 3/3 | 1.6 s | -32,515 | +13.9 % | -12,140 | 5,055 / 0 / 0 / 0 / 0 |
| `gamme-24` | direct | `sans-enchainement` | 3/3 | 1.6 s | -32,495 | +13.9 % | -12,125 | 4,990 / 0 / 0 / 0 / 0 |
| `gamme-24` | direct | `sans-experience-premium` | 3/3 | 1.6 s | -32,625 | +13.6 % | -12,090 | 4,975 / 0 / 0 / 0 / 0 |
| `gamme-24` | Timefold | `actuel` | 0/3 | never | -38,545 | +0.0 % | -6,135 | 1,690 / 0 / 0 / 0 / 0 |
| `gamme-24` | Timefold | `sans-penibles` | 0/3 | never | -39,410 | -2.2 % | -6,250 | 1,845 / 0 / 0 / 0 / 0 |
| `gamme-24` | Timefold | `sans-charge` | 0/3 | never | -35,045 | +9.1 % | -8,545 | 12,995 / 0 / 0 / 0 / 0 |
| `gamme-24` | Timefold | `penibles-medium` | 2/3 | 42.6 s | -43,090 | -11.8 % | -7,410 | 3,525 / 0 / 0 / 0 / 0 |
| `gamme-24` | Timefold | `sans-roulement` | 3/3 | 34.9 s | -43,250 | -12.2 % | -7,655 | 3,510 / 0 / 0 / 0 / 0 |
| `gamme-24` | Timefold | `sans-enchainement` | 3/3 | 33.0 s | -43,115 | -11.9 % | -7,680 | 3,495 / 0 / 0 / 0 / 0 |
| `gamme-24` | Timefold | `sans-experience-premium` | 3/3 | 34.8 s | -43,420 | -12.6 % | -7,615 | 3,525 / 0 / 0 / 0 / 0 |

What we take from it:

- **`eviterRoulementStandsPremium` cannot be removed.** `sans-roulement` loses 12.4% (direct) and 7.3% (Timefold) on the heatwave, 8.8% (direct) on `festival-hivernal`, 8.8% (direct) and 17.6% (Timefold) on `gamme-20`. It is the rule that costs the most when taken away.
- **`eviterEnchainementStandsEpuisants` weighs little, but not nothing.** It costs nothing in the plan of the two festivals or of `scenario-complet`: the solver already avoids the back-to-back. On `gamme-20` it costs 30 (direct) and 425 (Timefold) in `actuel`'s plan, and removing it loses 5.9% of medium under Timefold. A penalty that exists would vanish: the default score would move.
- **The balances do not replace one another.** `sans-charge` loses 8.5% and 9.1% (direct) on the heatwave and `scenario-complet`, and `festival-hivernal` finds no feasible plan under Timefold; `penibles-medium` loses 13.2% under Timefold on `gamme-20`. Conversely `sans-penibles` stays within 3.5% everywhere, but its soft penalty exists (2,570 on `festival-hivernal`, direct): the default score would move.
- **`sans-experience-premium` loses 8.6% (direct) and 10.8% (Timefold) on `gamme-20`**, 4.3% on `festival-hivernal` (direct): the rule is not inert, but a merge with two reasons would not remove it.
- The losses measured on `gamme-24` and the gain of `sans-charge` under Timefold (+27.5% on `scenario-complet`, +40.7% on `gamme-20`) are noise or search dynamics: a plan that, without a rule, settles elsewhere. No conclusion rests on them. Under Timefold, `gamme-24` does not reach feasibility within 60 s in `actuel`, `sans-penibles` or `sans-charge` (eight JVMs on one machine); the direct engine holds it everywhere.

The 5% rule decided for these measures: a loss of medium beyond 5%, or a default score that moves, and we do not merge. Recommendation for merging the comfort rules:

| Pair | Merge keeps the score? | Worst medium loss measured | Recommendation |
|---|---|---|---|
| `equilibrerCharge` + `equilibrerCreneauxPenibles` | no (two levels, two populations) | -13.2% (`penibles-medium`, Timefold, `gamme-20`) | group on screen only: one « load balance » group, two rules |
| `eviterRoulementStandsPremium` + `eviterEnchainementStandsEpuisants` | no (no common tuple) | -17.6% (`sans-roulement`, Timefold, `gamme-20`) | keep |
| `appreciationIncompatible` + `experienceRequisePourStandsPremium` | yes, by construction (two reasons, one medium point each) | -10.8% (`sans-experience-premium`, Timefold, `gamme-20`), which does not apply to a merge at equal score | keep: the score would hold, but two organiser settings would disappear |

No pair therefore merges while meeting both criteria: merging the comfort rules removes no rule. The differential test and a before/after test on the scenarios would be enough, were the third pair chosen despite the lost setting.

## The direct engine: two tests that keep the rules in duplicate

`solver/handsearch` writes every rule of the catalogue a second time, to search without going through the constraint streams ([ADR 0087](repo:docs/decisions/0087-un-second-moteur-de-recherche-arbitre-par-timefold.md), default engine since [ADR 0088](repo:docs/decisions/0088-le-moteur-direct-devient-le-moteur-par-defaut.md)). Two tests of the default suite, with no container, keep the two copies from diverging.

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

The `mutation` profile targets `dev.sylvain.planning.solver.constraints.*` and excludes the `scenario-lent` and `scenario-extreme` tags. `mutationThreshold` is 80: below that threshold, the build fails. A surviving mutant is killed by a test case, most often the boundary value that no test set (exactly the minimum rest, exactly the age of majority). The threshold never goes down; it goes up when the measurement goes up. The `mutation.yml` workflow runs the check every night on `main`, never on a pull request: run the command before pushing a constraint. A nightly failure opens an issue.
