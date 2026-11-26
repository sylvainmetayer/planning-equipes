---
twin: comprendre/ci-et-livraison
---
# Continuous integration and delivery

You will know what each workflow in `.github/workflows/` checks and why a push triggers only some of them, how actions are locked, and how the image is built, signed and published. Dependency updates and the licence inventory have [their own page](renovate-et-dependances.md).

All workflows run on GitHub runners (`ubuntu-latest`). On a public repository these standard runners are free, so CI is parallelised into jobs rather than larger runners. This is also what makes the image's provenance verifiable. The guard that stops a fork's PR from triggering these workflows stays in place: it was there because of the Docker socket of the former self-hosted runner, and lifting it is part of opening the repository to external contributions.

## The workflows

PRs to a maintenance branch `release/X.Y` replay CI like those to `main` (no workflow filters target branches). After merge, only `securite.yml` also runs on a push to `release/**`: adding that branch to the pushes of `tests.yml` or `scenario-tests.yml` makes zizmor fail (`cache-poisoning`).

| Workflow | Role |
| --- | --- |
| `tests.yml` | Backend suite, E2E, frontend checks, Sonar |
| `scenario-tests.yml` | Slow scenarios, on the paths that can break the solver's convergence |
| `e2e-lourd.yml`, `e2e.yml`, `e2e-suite.yml` | Playwright `@lourd` specs; manual E2E run; reusable suite |
| `securite.yml`, `scorecard.yml` | Security analyses, detailed in [automated checks](../installer/securite-analyses-automatiques.md) |
| `docker-ghcr.yml`, `image-pr.yml` | Image publication; dry-run build of a PR's image |
| `restauration.yml` | Replays `scripts/restaurer.sh` on a real stack |
| `release.yml` | Body of a published release |
| `licences-renovate.yml`, `artefacts-renovate.yml`, `angular-renovate.yml` | Catch-up commits on Renovate PRs, see [Renovate](renovate-et-dependances.md#renovate) |
| `numeros-reserves.yml` | Collision of migration or ADR numbers between open PRs |
| `verify-lockfile.yml` | Check of the actions lock |
| `mutation.yml`, `analyse-dynamique.yml` | Every night on `main`: mutation tests on the constraints (PIT); OWASP ZAP *baseline* on the production jar |
| `site.yml`, `oidc-keycloak.yml` | Showcase site; OIDC trial against a real Keycloak |

## Why a push does not run everything

A push consumes tens of minutes of runner time whatever its content: the suite, the E2E in two halves, the scenarios as soon as a PR has touched the solver, security. Four filters limit this cost.

- **Documentation pushes skip the suite.** `tests.yml` ignores `docs/`, `site/` and Markdown, minus the files a test rereads or that the job compares with its build (the OpenAPI contract, the licence inventory, the ADRs, the diagrams, the pages that `DiagramsStructuralTest` or another structural test reads), which are explicitly included again. The `e2e` job has a narrower filter, computed by a small `e2e-scope` job that rereads the diff the way GitHub reads `paths`. A page that shows a diagram must be in this list, otherwise a push touching only that page would not run the diagram check.
- **`@lourd` specs have their own workflow.** It starts on a PR only under the `e2e-lourd` label (or `timefold`, `quarkus`, `playwright`), and every night on `main`.
- **A Renovate PR does not run the scenarios** outside the `timefold` or `quarkus` labels.
- **Mutation and dynamic analysis no longer run on PRs.** Six PRs open at once saturated GitHub's limit of concurrent jobs, and these two measurements change little from one PR to the next. `mutation.yml` (PIT, about five minutes) and `analyse-dynamique.yml` (ZAP *baseline* on the production jar, with the same ZAP image and production profile as before) run every night on `main`, and on demand through `workflow_dispatch`. A nightly failure opens an issue, or comments on the one already open, like the weekly site check. Before pushing a constraint, run PIT locally ([mutation tests](tests-de-scenarios.md#les-tests-de-mutation)).

On a PR, GitHub evaluates the `paths` filter on all the files of the PR, not on the last push: a PR mixing code and documentation replays everything on every push. This is intended, because a "skipped" check on the last push would hide the red of the previous one.

On a PR, a push cancels the runs of the previous one: every workflow triggered by `pull_request` carries a `concurrency` group per ref, with `cancel-in-progress: ${{ github.event_name == 'pull_request' }}`. On `main` and tags, nothing is cancelled: a started run goes to the end, and in a burst of merges only the last pending run starts after it (GitHub keeps one per group). The heavy E2E also sorts its group by event type, so that a merge neither delays nor skips the nightly catch-up. The Renovate catch-up workflows (`artefacts-renovate.yml`, `angular-renovate.yml`) never cancel: they push a commit, and interrupting one mid-work would leave the branch half regenerated.

## The test suite in parallel

- **The backend suite is played in three jobs.** `test` runs `verify -DskipITs=false -Pcoverage,unit`: the classes that need only a JVM, the integration tests, the packaging. The two halves of `test-conteneur` play the classes of `src/test/container-tests.txt`, every other line each (`-Dsurefire.includesFile`). Each class is in exactly one of the three, and `ContainerTestsInventoryTest` keeps the file equal to the annotations. The three JaCoCo reports go to Sonar, which merges them.
- **The E2E is played in two halves**, each on its own stack (`--shard=1/2` and `--shard=2/2`, by spec file since `fullyParallel` is false). They are independent by construction: each spec calls `repartirDeLaReference` in its `beforeAll` (the database goes back to the reference dump, Mailpit is emptied), `globalSetup` captures the reference on the stack of its half, and the only spec without this return (`perimetre-authentification`) reads no data. The checks are called "e2e (1) / e2e" and "e2e (2) / e2e".
- **The jar is built once.** The `test` job uploads `target/quarkus-app` (artefact `quarkus-app`, kept seven days so that a "Re-run failed jobs" of the E2E finds it again) and `e2e-suite.yml` picks it up when it is given `app-artifact`. Without this argument, the stack packages the application itself, which is what `e2e-lourd.yml` and `e2e.yml` do. The E2E waits only for `test`: it runs during the container tests, and a red container class keeps the PR red without stopping it from starting. The Angular bundle is built only twice per push: by Quinoa in the `verify` of `test`, and by `npm run build` in `frontend`, which needs it for the budgets of `angular.json`.
- **The coverage of a `@QuarkusTest` requires the `quarkus-jacoco` extension.** The JaCoCo agent alone measures nothing: Quarkus rewrites the bytecode of the beans it loads, which no longer match `target/classes`, and every bean passed for uncovered. The `coverage` profile therefore adds the `quarkus-jacoco` extension (in `test` scope, it does not enter the jar), the agent leaves the `QuarkusClassLoader` aside, and both write to `target/jacoco-quarkus.exec`.
- **SonarCloud is launched by the `sonar` job**, not by SonarCloud's automatic analysis, which is disabled: that one read neither the coverage nor the exclusions. The job waits for `test` and `frontend`, takes up the compiled classes and the JaCoCo reports (artefact `sonar-backend`) and the Vitest `lcov` (`coverage-frontend`), then runs the scanner with the `SONAR_TOKEN` secret. It runs even when a suite has failed: coverage is then partial, the findings stay readable. Its configuration is `sonar-project.properties`: scope, coverage paths and every rule exclusion with its reason.
- **`.mvn/maven.config` turns off the discovery of prefix files** of Maven 3.10 (`aether.remoteRepositoryFilter.prefixes.resolvePrefixFiles=false`). Otherwise every Maven session, including the one Quarkus opens in `generate-code-tests`, requests `/.meta/prefixes.txt` from every repository declared by a dependency's POM, with three retries: a repository answering 503 costs the `test` job up to ten minutes. The `Dockerfile` copies `.mvn/`, so the image build reads the same setting.
- **The same file makes Maven patient with Maven Central's refusals.** A 429, 502, 503 or 504 is retried five times, after 10, 20, 30, 40 then 50 seconds, instead of three times within 30 seconds, and a request silent for more than two minutes is dropped rather than awaited for half an hour. Only these transient refusals are retried: a missing artefact (404) fails at once, and a transfer failure is never remembered in `~/.m2` from one run to the next, so the restored cache holds none.

## Migration and ADR numbers

Two PRs open at the same time readily take the same "next" number, and `FlywayMigrationsFrozenTest` sees it only once both are on `main`. Nobody reserves a number: a migration that no longer exceeds the highest version of the base (Flyway refuses a version lower than one already applied) is renumbered right before the merge by [the coordinator's script](conventions-du-depot.md#les-numéros-de-migration). The `frontend` job runs `.github/scripts/check-reserved-numbers.sh` against the tip of the base, not against the branch's starting point: such a migration is only a warning there (the push to `main` refuses it, if the script was forgotten), an added ADR must carry a free number, and the message names the file already there. For an ADR, the second PR fails at its next run after the first one is merged, before its own merge.

`numeros-reserves.yml` looks earlier, among the open PRs: a collision there is a warning placed on the file, never a failure, since neither of the two is wrong as long as nothing is merged. It fails only when the API does not answer, so as not to conclude "no collision" without having read. Finally, `DocumentationStructuralTest` refuses two ADRs with the same number in the tree.

## The actions lock

Actions are pinned by SHA in the workflows and locked in `.github/workflows/actions.lock` ([`gh-actions-lock`](https://github.com/github/gh-actions-lock), a preview `gh` extension whose format is still moving). The lock freezes each action, direct or transitive, at a commit, with the numeric identifiers of the owner and the repository. The runner rechecks it at the start of each job, and `verify-lockfile.yml` runs `gh actions-lock --verify` on every PR and on `main`. The SHAs stay inline (`@<sha> # vX`): the lock adds to the pins, it does not replace them.

After any addition or change of a `uses:`:

```bash
gh extension install github/gh-actions-lock --pin v0.5.1   # the version in verify-lockfile.yml
gh actions-lock --no-narrow   # WITHOUT --no-narrow, the tool rewrites SHAs as tags
gh actions-lock --verify      # read-only, like CI
```

The lock is not edited by hand. Renovate regenerates it at each bump of an action (it calls the tool with `--no-narrow`), and a `custom.regex` manager follows the extension's version (`--pin vX.Y.Z` in `verify-lockfile.yml`), in a separate PR and without automerge.

The three `uses: ./.github/workflows/e2e-suite.yml` stay as `./`: zizmor's `self-repository` rule is turned off in `.github/zizmor.yml`, the tool accepts them and the called workflow has its own entry in the lock. Container images without `@sha256` (`postgres`, `mailpit`, the Playwright image, the one of `scorecard-action`) are not covered: the tool warns without blocking.

A workflow that is added is read by actionlint and zizmor (`securite.yml`, `workflows` job): no `${{ … }}` in the body of a `run:` (the value goes through `env:`), `persist-credentials: false` on every `actions/checkout` that pushes nothing, no cache read by a build that publishes a version. The results of Trivy, Semgrep, zizmor, CodeQL and Scorecard are published in the *Security* tab (SARIF) in addition to failing in the log.

## The image

### Publication

`docker-ghcr.yml` pushes the image under the single `sha-…` tag, starts it against a real PostgreSQL (smoke test), scans it with Trivy, signs it, then publishes `:main` at each merge and `X.Y.Z` and `X.Y` on a `vX.Y.Z` tag. Three safeguards:

- **Trivy runs before the signature.** A fixable CVE of severity HIGH or CRITICAL in the system packages stops the job, and the image then exists only under `sha-…`.
- **Signature and attestations.** The image carries a CycloneDX SBOM and a keyless cosign signature, plus the native GitHub attestations (provenance, SBOM).
- **No cache for a version.** A `v*` tag is built without cache, layers and Maven repository included. `:main` is also rebuilt without cache every Sunday and on manual launch, for Ubuntu fixes.

`:latest` is never set by `metadata-action` (`latest=false`): a dedicated step sets it, only when the pushed tag is the most recent of the repository, otherwise publishing a fix on an old line would move it backwards. The image's Maven repository is kept from one build to the next: the `RUN --mount=type=cache` of the `Dockerfile` lives in a throwaway BuildKit builder, and `cache-to: type=gha` exports only the layers, so `actions/cache` (keyed on `pom.xml`) and `buildkit-cache-dance` pour `.m2` into the builder before the build and take it out at the end.

`release.yml` writes the body of a published release (git-cliff on the tag's commit messages) and touches nothing else: no tag, no branch, no file. There is no `CHANGELOG.md` to maintain. It starts on `release: published`, alongside `docker-ghcr.yml`, which the push of the same tag triggers. Its two consistency guards, the bump the subjects called for and the branch where the tag lives, run after the write: the release is already published, and a red job is the only channel that warns. The version policy is in [versions and releases](../installer/versioning.md).

### Building a PR's image

The jobs of `tests.yml` disable Quinoa and never go through its npm installation with the Node it downloads: a Node bump could pass green on a PR and then break the `main` image. `image-pr.yml` therefore builds the image without publishing it. It starts only on the `Dockerfile`, `docker/entrypoint.sh`, `.dockerignore`, `pom.xml`, `.mvn/`, `mise.toml`, the npm manifests, the Angular build configuration and `application.properties` (which carries Quinoa's Node version). The three Renovate workflows relaunch it after their own commit, with no path filter since `workflow_dispatch` has none. The `package` step of the `Dockerfile` is not in `-q`, so that npm's output stays in the log.

`restauration.yml` plays `scripts/restaurer.sh` for real: production stack brought up with the commit's image, backup dropped in the volume, database modified, restore, check. It also covers the `--essai` mode, the refusal of a file that is not a dump or of a truncated dump, and the return to the previous state of a restore that fails midway. It starts only on `scripts/`, `docker-compose.prod.yml`, the `Dockerfile` and itself.

### The build context

The Docker daemon receives the whole context before any `COPY` is evaluated, and the `Dockerfile` copies only `pom.xml`, `.mvn`, `src` and `.git`. `.dockerignore` therefore excludes `explorbot/`, `assets/`, `site/`, `.quinoa/`, `.claude/worktrees/` and `src/main/webui/coverage/`. In CI the gain is negligible (the repository there is a fresh `checkout`), but it matters for the local `docker compose --profile app up --build`, and `assets/` holds real data that has no business in an image context. Adding a large folder at the root means remembering to exclude it here.
