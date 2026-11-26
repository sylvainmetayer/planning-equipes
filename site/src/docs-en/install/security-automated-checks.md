---
twin: installer/securite-analyses-automatiques
---

# The security checks that run on every version

Automated scans protect the code and the image you deploy, on every push. You know what they see, what blocks a release and how an exception is justified: enough to answer an auditor or a reviewer, more than an operating task.

## Automated scans

What a tool finds on its own is checked on every push, not discovered by an
audit. Each scan **fails in the PR's log**; those that can
produce SARIF also publish it in the repository's *Security* tab, where an
alert is triaged (fixed, false positive, accepted risk) with its reason. As the
repository is public, this tab costs nothing.

| Scan | Where | What it sees | Its exceptions |
| --- | --- | --- | --- |
| Trivy, dependencies | `securite.yml`, job `dependances` | Fixable HIGH/CRITICAL CVEs in `pom.xml` and `package-lock.json` | `.trivyignore` |
| Dependency review | `securite.yml`, job `revue-dependances` (PR only) | What a PR **adds**: vulnerable dependency (HIGH and above, development included), non-free licence | in the job |
| Semgrep OSS | `securite.yml`, job `code` | Java, TypeScript, OWASP, Dockerfile, workflow and agent-configuration patterns | `nosemgrep` site by site, see below |
| actionlint, zizmor | `securite.yml`, job `workflows` | Validity of the workflows; their security: template injection, credentials left by the checkout, cache poisoning, permissions | `.github/zizmor.yml`, each rule with its reason |
| hadolint | `securite.yml`, job `workflows` | The `Dockerfile` and the shell in its `RUN` steps | `# hadolint ignore=…` on the line, reason above |
| Trivy, image | `docker-ghcr.yml` before signing; `securite.yml`, job `image`, every week | The image's system packages: Ubuntu layer, JRE, `postgresql-client` | `.trivyignore` |
| CodeQL | "default" configuration of the repository settings | Data flow from one class to another (Java, TypeScript, Python, workflows) | in the *Security* tab |
| OpenSSF Scorecard | `scorecard.yml` | The repository's supply-chain practices, public score (README badge) | in the *Security* tab |
| OWASP ZAP *baseline* | `analyse-dynamique.yml`, every night on `main` | The running application seen without an account: headers, cookies, errors | `.github/zap-baseline.tsv` |
| GitGuardian | GitHub application | Pushed secrets | `.gitguardian.yaml` |

The image scan in `docker-ghcr.yml` **blocks publication**: an image
carrying a fixable HIGH or CRITICAL CVE gets neither a signature, nor
`:main`, nor a version number: it stays under its `sha-…` alone, for
diagnosis. The ordinary case is settled by rebuilding: the `Dockerfile`
applies the day's Ubuntu fixes (`apt-get upgrade`), but that layer
stays cached as long as the `eclipse-temurin` base image does not change.
`docker-ghcr.yml` therefore rebuilds `:main` **without cache every Sunday**
(3:00 UTC, the day before the weekly scan), as on a manual run.
`:latest` and the versions do not benefit, because a release tag is never
rebuilt: a CVE fixed after a version is settled by a patch version. What comes from no
package does not benefit either: `pebble`, a Go binary that the base image installs
next to `apt` and that the application never runs, is removed by the
`Dockerfile` rather than waited for. A release version
is built **with no cache at all** (layers and `.m2`): a GitHub cache can
be written from other jobs of the repository, and a signed version must inherit nothing from it.

**Adding an entry to `.trivyignore`** (the file does not exist until none
is needed): the CVE identifier, and above it, as a comment, the
date, the reason (code path not reached, no fix published for the
image's package) and the condition that will allow removing it. An entry
without a date is never reread.

Two protections are **repository settings**, not files, and are
checked in *Settings → Advanced Security*: *secret scanning* with
*push protection* (which refuses the `git push` carrying a secret, where
GitGuardian only sees it once pushed) and the dependency graph, which
the dependency review reads. CodeQL also lives there, in "default"
configuration: GitHub chooses the languages and runs the scan on every PR, with no
workflow in the repository. An "advanced" CodeQL workflow cannot coexist
with it: GitHub refuses its results.

## Static analysis: suppressions and their justification

The `code` job of `securite.yml` (Semgrep OSS) fails the CI on any
rule that finds something, whatever its severity. One rule currently hits
nine places in the code, all for the same reason, and all annotated `nosemgrep`
**site by site** rather than disabled wholesale: a new unannotated
SQL concatenation therefore still turns the CI red.

`java.lang.security.audit.formatted-sql-string` flags any query
built by concatenation. In this repository, what is concatenated is
**always an identifier** (a table or column name), and SQL does not
allow binding an identifier as a parameter: `SELECT * FROM ?` does not
exist. These identifiers come without exception from literals written in the calling
code or from constant lists (`DatabaseDumpService.TABLES`), never from user
input; the dump import even checks its own against
`ALLOWED_TABLES`. The **values**, for their part, all travel as bound parameters
(`ps.setString`, `ps.setLong`, `ps.setObject`).

**Before adding a `nosemgrep`**, check the callers: if an identifier
can come from an HTTP request, it is an SQL injection and not a false positive.
That is what was done for these nine: every call site passes a literal
string.
