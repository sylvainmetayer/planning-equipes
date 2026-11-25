---
twin: comprendre/renovate-et-dependances
---

# Dependencies: Renovate and licences

You will know how dependency updates arrive as PRs, which derived files the catch-up workflows recompute in place of Renovate, and how the inventory of licences that the image redistributes is kept.

## Renovate

Renovate (`renovate.json`) covers Maven, the Maven wrapper, Docker, GitHub Actions, npm and `mise.toml`, on Mondays before 6:00. It waits seven days after a version is published before proposing it, and nothing is merged automatically. The rules worth knowing:

- **Minor and patch updates are grouped**: test dependencies, Maven build plugins, GitHub Actions, container images, frontend dependencies other than Angular.
- **Quarkus and Timefold are grouped by ecosystem**, and the `@angular/*` packages in a single PR: a partial bump breaks the build. These groups carry the labels `quarkus`, `timefold` and `frontend`, which govern the heavy workflows.
- **What is pinned twice is grouped.** Maven (`mise.toml` and the wrapper), Playwright (the `@playwright/test` package and the e2e container image), Quarkus (`quarkus-maven-plugin` and the BOM share `quarkus.platform.version`, and the `quarkus` of `mise.toml` joins the group). In the first two cases a `custom.regex` manager reads the second pin under the name of the first: the Maven of `mise.toml` as the artifact `org.apache.maven:apache-maven`, the tag of the Playwright image as the npm package `@playwright/test`. Read as an image, the latter had no publication date on `mcr.microsoft.com`, and the seven-day delay held it back indefinitely. One dependency, one date, one PR. `ToolchainPinsStructuralTest` also refuses an image tag that is not the locked version of the package.
- **Major versions of Java, PostgreSQL, victools and TypeScript go through the dashboard** (`dependencyDashboardApproval`). TypeScript waits for Angular to accept the major: `@angular/compiler-cli` bounds its range.
- **A `release/X.Y` maintenance branch receives only security fixes.** Renovate follows it (`baseBranchPatterns`) but every other update is disabled there; only vulnerability alerts open a PR on it.
- **Commit subjects fit under 72 characters.** For Maven, where `depName` is `groupId:artifactId`, the subject keeps only the artifactId (`fix(deps): update timefold-solver-core to v2.6.0`). Renovate PRs go through the same `check-commits.sh` as the others.
- **An image launched by a workflow `run:`** escapes the `github-actions` manager, which reads only `uses:`, `container:` and `services:`. A `custom.regex` manager catches it if the image is in an environment variable preceded by its annotation, as the Semgrep job of `securite.yml` does:

```yaml
env:
  # renovate: datasource=docker depName=semgrep/semgrep
  SEMGREP_IMAGE: semgrep/semgrep:1.185.0
```

Every image carries an explicit tag, including in `docker-compose.yml`: with no tag or under `latest`, Renovate has nothing to propose and the installed version depends on the day of the `pull`.

What Renovate does not read (the `node-version` of `application.properties`, the `java-version:` entries of the workflows, the `FROM` lines of the `Dockerfile`) is kept in agreement with `mise.toml` by `ToolchainPinsStructuralTest`: a bump that does only part of the job breaks the build and names the file that lags.

### The files Renovate cannot recompute

Renovate changes the versions, then recomputes the files derived from them. When that computation fails ("Artifact update problem"), it pushes only the source file, and three workflows complete the PR. Each follows the same split into two jobs: the first has only `contents: read` and runs code from the PR (Maven plugins, npm scripts), the second, which holds the write token, sees no build. The push is never forced: if Renovate rebased in the meantime, it is refused and the next run puts the commit back. A push made with `GITHUB_TOKEN` triggers no workflow, so each job re-launches Tests (E2E included) and Security itself through `workflow_dispatch`, and the heavy workflows under the labels that govern them, after cancelling what was running on the replaced commit. `gitIgnoredAuthors` in `renovate.json` prevents Renovate from considering the branch "modified" and ceasing to rebase it.

| Workflow | What it recomputes | Pitfall |
| --- | --- | --- |
| `licences-renovate.yml` | `docs/licences-tierces.md`, which a bump makes fail by construction | the `test` job checks it first, before the tests |
| `artefacts-renovate.yml` | the npm lock and the Maven wrapper, on any PR that touches a `package.json` or `maven-wrapper.properties` | see below |
| `angular-renovate.yml` | the `ng update` migrations of a PR that touches an `@angular/…` entry of `package.json` | see below |

**`artefacts-renovate.yml`.** The npm lock is recomputed from the existing one, with the npm of `packageManager`, installing each bumped package at the version the PR proposes, the lower bound of the new range (`.github/scripts/renovate-proposed-versions.js`). A bare recomputation would take the latest version of the range, younger than the seven days Renovate waits; `npm --before` is no good either, because it also hides the lock versions published during the week and reproduces Renovate's `ERESOLVE … @angular/common@undefined`. The wrapper is regenerated by `maven-wrapper-plugin` under Java 25, because `.mvn/jvm.config` carries options that only Java 24+ reads (`--sun-misc-unsafe-memory-access`) and a `constraints` rule imposes Java 25 on Renovate for this manager. The licence inventory follows the frontend lock in the same commit. A real peer conflict, two packages of one monorepo bumped in two PRs, fails and says so: the PR is what needs regrouping.

**`angular-renovate.yml`.** Renovate does not run Angular's schematics, and its `postUpgradeTasks` exists only self-hosted. The PR is recognised by the `package.json` diff, not by the branch name: for minor and patch updates, the "frontend dependencies" group supersedes the "angular" group. The job installs the proposed version, then runs `ng update <package> --migrate-only --from <base lock> --to <installed version>` for `core`, `cli`, `cdk` and `material` (`--migrate-only`, because the target is already installed and `ng update` would answer "already up to date"). The starting versions come from the lock at the PR's starting point (`merge-base`), not from the tip of the base. The second job extracts the fix at the exact head where it was built, checks that it touches only `src/main/webui/` and the licence inventory, applies it and pushes without forcing; a refusal fails the job, it does not promise a replay that a commit pushed with `GITHUB_TOKEN` would not trigger. The inventory is regenerated in the same fix, and `licences-renovate.yml` steps aside on the PRs this workflow takes over (`.github/scripts/angular-bump.sh`, read by both): two workflows pushing to the same branch refuse each other, and a shared concurrency group would not prevent it, since GitHub keeps only one pending run per group. The workflow does not replay a migration already committed on the branch.

## The licence inventory

The image is distributed under AGPL-3.0-only, but it also redistributes its dependencies, which keep their licences: MIT, BSD or Apache require their notice to travel with the binary. The [licence inventory](../installer/licences-tierces.md) is **generated**, never written by hand:

```bash
./mvnw license:add-third-party          # from the root: the Java side
cd src/main/webui && npm run licences   # merges both sides into the document
```

On the Java side, `license-maven-plugin` resolves the closure of the `compile` and `runtime` scopes, which is what the image embeds in `target/quarkus-app/`, and brings licence names back to their SPDX identifier (otherwise six spellings of Apache-2.0, each POM writing its own). The plugin is bound to no phase: resolving the whole tree at every build would cost a minute nobody asked for. On the npm side, the script reads `package-lock.json` and nothing else: a v3 lock carries the `license` field of each package, so neither `node_modules` nor an extra tool is needed.

The `test` job redoes both and compares with the committed file (`npm run licences-check`): a dependency added, removed or relicensed by a version bump fails the branch until the inventory has followed. It is the same ratchet as for the OpenAPI contract: a committed file the repository no longer produces is a file one stops believing.

Two limits. The npm side lists the closure of production dependencies, hence a superset of what the bundle embeds (underestimating would cost a missing notice, overestimating costs a line). And the packages of the image's base system (JRE, PostgreSQL client, distribution) are not in it: they fall under the SBOM attached to every published image.
