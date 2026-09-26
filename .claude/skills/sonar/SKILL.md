---
name: sonar
description: Work with the SonarCloud analysis of planning-equipes — read its findings, fix one, add an exclusion, or check the quality gate — through the SonarQube plugin (sonar-* skills, sonarqube-reviewer agent) or by hand. Use whenever a task mentions Sonar, SonarCloud, SonarQube, a quality gate, a rule key such as java:S107, or a `sonar-*` skill is about to run on this repository.
---

# SonarCloud on planning-equipes

The repository is analysed by **SonarCloud automatic analysis** — no scanner in
the build, no CI step, no `sonar-project.properties`. Its only configuration is
`.sonarcloud.properties` at the root.

| Setting | Value |
| --- | --- |
| Organization | `sylvainmetayer-github` |
| Project key | `sylvainmetayer_planning-equipes` (the key SonarCloud gives a GitHub import — check it with `sonar-list-projects` before relying on it) |
| Analysis | automatic, on every push and pull request |
| Configuration | `.sonarcloud.properties` (exclusions only) |

Pass the organization and the project key explicitly to every `sonar-*` skill
of the SonarQube plugin: nothing in the repository lets it infer them.

## Reaching SonarCloud

The network policy of a cloud session may block `sonarcloud.io` (a `403` on the
`CONNECT` tunnel). A host opened afterwards stays blocked until the session
restarts. When that happens, say so and stop: **never guess a finding**, and
never reconstruct one from a rule's documentation. The pull request's SonarCloud
check and its comment are then the only source.

The plugin authenticates with a SonarCloud user token (`SONAR_TOKEN`). It is a
secret of the environment, never a file of the repository.

## The exclusions are decisions

Every block of `.sonarcloud.properties` carries its reason as a comment: a rule
is silenced only where following it would break a repository rule or the thing
the file is for. Treat each one as settled.

- A finding on a rule excluded for that path is not a finding. Do not "fix"
  code to satisfy it — e.g. do not zone the event's `LocalDateTime` hours
  (`java:S8700`, wall-clock time on purpose), do not split an MCP tool's
  arguments into an object (`java:S107`), do not rewrite a structural test's
  regex (`java:S8786`), do not touch an applied Flyway migration (`plsql:*`).
- A new exclusion goes in `.sonarcloud.properties`: a new key appended to
  `sonar.issue.ignore.multicriteria`, its `ruleKey`/`resourceKey` pair scoped to
  the narrowest path, and a comment above it saying why. **Never a
  `// NOSONAR`** or an `@SuppressWarnings` naming a Sonar rule: the reason would
  live where nobody reviews the list.
- Exclude only when following the rule would break something named in
  `AGENTS.md` or the file's purpose. "It is noisy" is not a reason — fix it.

## When Sonar and the repository disagree

`AGENTS.md` wins. In particular a Sonar suggestion never justifies:

- translating a domain name (`Animateur`, `Creneau`, `PosteAffectation`, …) or
  a French test method name nobody else is touching;
- editing an applied migration — schema change is a new versioned file;
- a `try/catch` for `BusinessError` in a resource — `BusinessErrorMapper`
  owns it;
- a new frontend dependency, or `new ObjectMapper()`;
- demoting a hard constraint, or calling `SolutionManager.analyze()`.

If the only fix Sonar accepts breaks one of these, it is an exclusion, argued as
above.

## Fixing a finding

1. Read the finding on the current head (`sonar-list-issues` /
   `sonar-fix-issue`), not an older analysis.
2. Keep the fix minimal and inside the code the task already touches — a
   Sonar sweep does not widen a pull request.
3. Run what CI runs before pushing: `./mvnw spotless:apply` then `./mvnw test`
   (or `-Punit` for the inner loop, see the `sandbox-tests` skill without
   Docker); `npm run lint`, `npm run format-check` and `npm test` from
   `src/main/webui` for the frontend.
4. The commit follows the repository's rules (short subject, conventional
   prefix, `Signed-off-by`) — a Sonar fix is usually `refactor:` or `fix:`,
   never a scope of its own.

## Quality gate

On a pull request, the SonarCloud quality gate is one more check to get green,
next to the Tests workflow and Claude Approvals. A failing gate on new code is
this pull request's to fix; a finding on code the diff does not touch is not a
reason to widen it — report it instead.
