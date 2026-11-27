---
twin: installer/publier-une-version
---

# Publish a version and patch the previous one

You build a release (a GitHub form, no command) and, the day a real operator cannot upgrade, you fix an earlier line without losing anything. `main` stays the only development branch, one PR per subject, no `develop`. This guide is for whoever publishes the image; to deploy a version, see [Upgrade to a new version](mettre-a-jour.md), and for what a number promises, [version numbers](versioning.md).

## 1. The source of truth: a git tag

A deployment's version is the **git tag** `vX.Y.Z` placed on `main`. It is born from GitHub's release form (§ 2), which creates and pushes it; no command needs to be typed.

Annotated or lightweight makes no difference, and the repository holds both: `git describe --tags --exact-match`, the only reading that counts, the one done by `generate-version.js` and by the `Dockerfile`'s `-Drevision`, treats them identically. What an annotated tag used to bring, a dated summary attached to a specific commit, now lives in the **release** itself, somewhere it can be read without cloning.

Everything else **derives** from the tag, with no intervention:

| Where | How |
| --- | --- |
| Frontend (`APP_VERSION`, footer of both shells, admin and staff espace, and the *Debug* page) | `generate-version.js`: the exact tag (`v1.2.0`), otherwise the short SHA. The link leads to the GitHub release page for a tag (`/releases/tag/v1.2.0`, which GitHub also renders for a tag with no release), to the commit for a SHA (`core/version-link.ts`) |
| *What's new* screen (`/nouveautes`) | `generate-news.js`: the git history read at build time, split by `vX.Y.Z` tag; whatever follows the last tag is shown under "Coming up" |
| Backend (`quarkus.application.version`, Quarkus startup line) | The `Dockerfile` passes `-Drevision=1.2.0` derived from the same `git describe`, with the `v` removed; outside a release, the short SHA. In a local build, `999-SNAPSHOT` |
| Sentry, **frontend only** | `release: APP_VERSION`: browser errors are grouped by version, not by commit. Backend events do not carry a `release` yet: `SentryInitializer` sets only the DSN and the environment |
| Docker image | `docker-ghcr.yml` publishes `ghcr.io/…:1.2.0` and `ghcr.io/…:1.2` on the tag push |
| GitHub release | One per tag, body written by `release.yml` from the commit messages (§ 2) |

The `pom.xml` therefore carries **no hard-coded number** any more: its version is `${revision}`, and a workflow that builds a release fails before building if the tag is not visible to the build (guard step of `docker-ghcr.yml`).

## 2. Build a release

A release is **one form**: *Releases* → *Draft a new release* on GitHub. Choose the `vX.Y.Z` number ([what each number promises](versioning.md)), target `main`, publish. The body can stay empty.

![Building a release and patching an earlier version](repo:docs/diagrammes/release.svg)

<sub>Source: [`diagrammes/release.puml`](repo:docs/diagrammes/release.puml).</sub>

Everything else derives from this gesture, with no command or clone:

| What starts | On what | What it does |
| --- | --- | --- |
| `docker-ghcr.yml` | the tag push | `linux/amd64` build pushed under `sha-…` only, smoke test of the published image, SBOM, cosign signature, then tags `1.2.0` and `1.2`, and `:latest` if the tag is the most recent |
| `release.yml` | the release publication | writes the release body: the section rendered by `git cliff --current` |

The two are independent and run side by side; neither places nor moves a tag, the number stays a human decision.

The smoke test starts the published image, by its digest, against a PostgreSQL 19 and a Mailpit, with the minimal production environment. It checks availability, the exposed version (the tag's), uid 1000, that a `pg_dump` of the same major as the `postgres:` of `docker-compose.prod.yml` is present, and that `/backup` is writable; on failure, the container logs are displayed. Only then is the image signed, then the readable tags placed, by digest, without rebuilding. An image that does not start therefore stays reachable under `sha-…` for diagnosis, but is never `1.2.0`, `:main` or `:latest`.

If this check fails on a release tag, the git tag exists but the `1.2.0` image does not: the repair is that of the checks below. Delete the release and its tag, fix, and start again at the same number.

If you write something in the body when creating the release, the summary an annotated tag's message used to carry, it is kept: `release.yml` leaves it at the top and writes the generated section only below it, after a separator line and the `<!-- git-cliff -->` marker. Everything after that marker belongs to it and is rewritten at each run; rerunning it by hand (*Actions* → *Notes de release* → *Run workflow*, with the tag) is therefore safe, and it is also what lets you catch up an older release.

Two checks then run, and they fail **after** writing the notes: the release is already published when the workflow starts, nothing can prevent it any more, and a red job is the only channel that warns you. They say that a number lies (a `!` merged under a PATCH bump, a `feat` under a PATCH) or that the tag is neither on `main` nor on a `release/X.Y` branch (§ 3). In both cases the repair is the same: delete the release and its tag, start again at the right number. A pre-release is compared with the last final version; the check refuses a pre-release of a version already published, or whose `N` does not exceed that of a pre-release of the same number, and each refusal states its reason in the job log. Re-running the workflow by hand on a tag replays these checks with the scripts of `main`, not those of the tag.

### `:latest` never moves implicitly

`docker/metadata-action` by default (`latest=auto`) would move `:latest` on **every** tag push: publishing a `1.2.4` fix after `1.3.0` is out would bring `:latest` back to the old line, silently. The workflow therefore forces `latest=false`, and a separate step sets `:latest` only if the pushed tag is the greatest `vX.Y.Z` in the whole repository. `:latest` only moves forward.

### Notes are generated, not written

There is no `CHANGELOG.md`. `cliff.toml` at the root turns conventional messages (`feat:`, `fix:`…, the convention is in [`AGENTS.md`](repo:AGENTS.md)) into dated sections. It feeds the **release body**, and nothing else.

> A file would have said the same thing as the *Releases* page, with a defect it does not have: regenerated at tag time, its commit necessarily arrives *after* the tag, so the tagged tree never contains its own entry. The release page, for its part, is dated from the tag it documents.

So the generated section is not edited by hand: a badly worded entry is fixed by rewording the commit *before* merging, not after.

The same subjects feed the application's **What's new** screen (`/nouveautes`), built from the git history at build time and not from a hand-maintained file: same filters and same subheadings as `cliff.toml`, down to "⚠️ Attention". A badly worded subject is therefore read twice, in the release notes and in the application, which is one more reason to fix it before merging.

## 3. Patch an earlier version

Maintenance branches are created on demand, never in advance. As long as all deployments are on the latest version, no maintenance branch exists: you create one only the day a real operator cannot upgrade.

Here is the procedure for that day (example: a flaw to fix, a production on `1.2.0`, `main` already on `1.3.x`):

```bash
# 1. The fix goes to main first. Always. No exception.
#    A fix that exists only on a maintenance branch is a regression
#    scheduled for the next version.
git switch main && … && git commit     # normal PR, green CI, merge

# 2. The maintenance branch, created from the TAG, not from main
git switch -c release/1.2 v1.2.0

# 3. Bring back the fix already merged on main
git cherry-pick -x <sha>               # -x records the origin in the message

# 4. Push the branch; the tag is born from the release (§ 2): target
#    `release/1.2` in the form, not `main`
git push origin release/1.2
```

`release.yml` goes back to the previous tag of **that line** to compose the notes and check the bump: on `release/1.2`, a `v1.2.1` reads after `v1.2.0`, never after `main`'s `v1.3.0`.

The rules that make this safe:

- `main` first, always: this is what guarantees that no fix is lost;
- `cherry-pick -x`, so that the maintenance commit says which `main` commit it comes from. If the fix does not apply as is, write an adaptation, and review it like new code, because that is what it is;
- nothing on a maintenance branch that is not already on `main`;
- no Flyway migration in a maintenance patch, unless strictly additive: the schema being *forward-only*, a database already moved to `1.3.0` will not go back down. If the patch touches the schema, it is no longer a patch, it is a version upgrade;
- `release/1.2` never merges into `main`: it lives and dies on its own line;
- `:latest` does not move: `v1.2.1` is not the most recent tag of the repository, the workflow step leaves it in place.

The branch name is exactly `release/X.Y`: `release.yml` does not accept `release/3.x`. The 3.x line therefore lives on `release/3.5`, created from the `v3.5.0` tag, and its fixes ship as `v3.5.1`, `v3.5.2`… How long a line lives after a major is in [version numbers](versioning.md#combien-de-temps-une-ligne-est-corrigée).

How many lines do we maintain? Just one: the latest published `MAJOR.MINOR` outside the current line, and only as long as a real operator is on it. Beyond that, the answer is "upgrade": a lone developer does not maintain three lines.
