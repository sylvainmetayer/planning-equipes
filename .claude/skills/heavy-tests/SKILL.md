---
name: heavy-tests
description: Run the costly test jobs of planning-equipes — the scenario-lent classes (PlanningServiceScenario*Test, the anonymised festival fixtures), the scenario ladder (gamme-… files, ScenarioLadder*Test), the extreme scenarios (extreme-… files, -Pscenario-extreme) — and what to validate on a Timefold or Quarkus bump. Use when the user asks to run, add or move one of these tests, or when a change touches solver/, domain/, solverConfig.xml or the scenario files.
---

# Costly test jobs

What is excluded from the default `./mvnw test`, why, and how to run it. The
rules that hold without this skill (tag any new heavy test, keep the CI path
list in step, keep scenarios flat, run minute-long jobs in the background) are
in the root `AGENTS.md`; this file carries the detail.

`PlanningServiceScenarioCompletTest`, `PlanningServiceScenarioContinuTest`,
`PlanningServiceScenarioFestivalRealisteTest` and
`PlanningServiceScenarioFestivalHivernalTest` solve large scenarios to
hard-feasibility — the first two take ~25s/~75s on hand-built problems, the
last two run the **anonymised real-world fixtures**
(`festival-realiste-canicule.yaml`, the heatwave variant, and
`festival-hivernal.yaml`, the same event on the organiser's own grid: 153
animateurs, 65 stands, 45 premium, per-stand recurring schedules) — the only ones whose
stands carry recurring horaires, which a plain-Java harness must expand with
`HoraireStandResolver.appliquer` before building postes or it solves a problem
five times too large. They are tagged `@Tag("scenario-lent")`, excluded from the
default
`./mvnw test`/`./mvnw verify` run via the `test.excludedGroups` property in
`pom.xml`, and not run by the main CI workflow (`.github/workflows/tests.yml`
uses the default exclusion). Run them explicitly with
`./mvnw test -Pscenario-tests` (the profile clears the exclusion), optionally
narrowed with `-Dtest=...`.

CI does run them, but **only when something that can break convergence
changes**: `.github/workflows/scenario-tests.yml` triggers on the `solver/`
and `domain/` packages, `solverConfig.xml`, `application.properties`, the
scenario files and `pom.xml` (a Timefold bump is exactly when you want them).
Keep that path list in step with any move of those files — a solver tuning
that ships without these tests having run is the hole this workflow exists to
close.
Add the same tag to any future test in this weight class instead of letting
it slow down the default loop.

The **scenario ladder** — the thirty `gamme-…` files of
`src/main/resources/scenarios/`,
from one day and two stands to a month and 150 stands, the last five never
solvable — is played by `ScenarioLadder*Test` in `service/scenario`. Rungs 1 to
15 and the infeasible ones stay in the default run (each reaches zero hard in
its construction heuristic); `ScenarioLadderLargeTest` is `scenario-lent`.
When a change moves a rung, fix the file or the assertion knowingly — the
table and the rules are in `docs/developpement.md` (*La gamme de scénarios*).

The **extreme scenarios** (the `extreme-…` files of the same folder) probe the
limits — a thousand animateurs, 120 days, 500 stands a day, two thousand ad hoc
rules, empty editions. `ScenarioExtremeDegenerateTest` and
`ScenarioExtremeCatalogTest` run by default; `ScenarioExtremeAxisTest` and
`ScenarioExtremeCumulTest` carry `@Tag("scenario-extreme")`, excluded from both
the default run and `-Pscenario-tests`, and run only with `-Pscenario-extreme`,
one class at a time, with `-DargLine=-Xmx3g` and nothing else testing.

Both sets, and the hand-written fixtures beside them, live in the **single**
classpath folder `src/main/resources/scenarios/`, flat: the Débogage screen
lists that folder and `ScenarioYamlReader.scenarioPath` refuses a name carrying
a path component, so a scenario in a subfolder — or in a second `scenarios/`
directory, which `getResource` would hide entirely — is one nobody can pick.
`ScenariosLivres` in the tests is what reads it: `all()` for the checks that
apply to every file, `references()` for the differential tests, which pin a
canonical form per file and have no business pinning a generated one.

When an agent session needs to run this profile (or any other job on this
order of a minute or more — a `docker build`, a long solve), launch it as a
background command and let the harness notify on completion instead of
blocking the turn on a foreground wait or a manual sleep/poll loop: both cost
real wall-clock time for nothing and, over a sleep-poll loop specifically,
burn tokens on repeated status checks for no benefit over one notification.
