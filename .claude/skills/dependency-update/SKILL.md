---
name: dependency-update
description: Validate a dependency bump of planning-equipes (Quarkus, Timefold, npm, Docker image, GitHub Action, mise toolchain), regenerate the third-party licence inventory docs/licences-tierces.md, and understand how Renovate PRs behave (labels, licence commit, re-dispatched checks). Use when reviewing or merging a Renovate PR, bumping a version by hand, or when the licences file is stale in CI.
---

# Dependency update

## What Renovate tracks

`renovate.json` at the repo root — the single config file — tracks Maven
dependencies (including the `quarkus.platform.version` /
`timefold.solver.version` properties), the npm dependencies of
`src/main/webui`, Docker images, GitHub Actions and the `mise.toml` toolchain.
Keep the config in that one file; document behaviour changes in
`docs/developpement.md`.

## A Renovate PR

- `licences-renovate.yml` regenerates `docs/licences-tierces.md`, commits it on
  the branch and re-dispatches the checks itself — a push made with
  `GITHUB_TOKEN` triggers nothing. `gitIgnoredAuthors` in `renovate.json` is
  what lets Renovate keep rebasing a branch that commit sits on.
- The scenario workflow runs on a Renovate PR only when it carries the
  `timefold` or `quarkus` label, which `renovate.json` puts on those two groups.

## Validating a Quarkus or Timefold bump

Both commands, not one:

```bash
./mvnw verify -DskipITs=false
./mvnw test -Pscenario-tests
```

The five files that depend on `ai.timefold.solver.core.impl` (listed by
`TimefoldInternalApiStructuralTest`) are covered by a compile error for the
filters and the move factory and by the contract test for the diagnostic
(`ConstraintDiagnosticServiceContractTest` — read its result, not the build's:
on a Community 2.x build it reports itself skipped), but a filter that still
compiles and is no longer asked only shows in the scenarios. The scenario run
takes minutes: launch it as a background command. See the `heavy-tests` skill
for the profiles and fixtures.

## Regenerating `docs/licences-tierces.md`

The file is generated, never hand-edited. The `test` job of the Tests workflow
re-runs both steps and fails on a stale file:

```bash
./mvnw license:add-third-party        # from the repo root: compile + runtime closure
cd src/main/webui && npm run licences  # reads package-lock.json, needs no node_modules
```

The *why* and the two known limits are in `docs/developpement.md`.
