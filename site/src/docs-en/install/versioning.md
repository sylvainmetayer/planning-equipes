---
twin: installer/versioning
---

# Understanding what a version number promises

A version number serves to answer two questions: "which version am I on?" and "is the fix in it?". Here you learn what each number promises you before you upgrade, and what, in a version upgrade, requires an action on your part. The upgrade procedure is in [Upgrade to a new version](mettre-a-jour.md); building a release and patching an old line, for whoever publishes, in [Publish a version and patch the previous one](publier-une-version.md).

We start at `1.0.0`, at public opening. There is no `0.x`: a `0.x` says "I commit to nothing", which is false, the product runs in production on a real event.

## What counts as "the API"

"API break" means nothing until we say what the API is. Here, the public surface is not the Java code. It is what you or a customer handle.

Part of the public surface, and changing it incompatibly is a MAJOR upgrade:

- the YAML scenario format (`festival:` and `festival.dateDebut` keys, shape of stands and timeslots): it is a file the customer writes;
- the environment variables (`BRANDING_*`, `LEGAL_*`, `PLANNING_MCP_*`, `FLYWAY_REPAIR_AT_START`…): removing one, or changing a default in a way that changes behaviour;
- the stable URLs, first of all the staff espace links printed on PDFs handed out to humans: breaking them invalidates paper already in circulation;
- the REST API consumed by anything other than the product's frontend, MCP server included;
- a Flyway migration that is irreversible in operational terms: dropping or renaming a column, a constraint that rejects existing data. The schema is *forward-only*: going back to the previous image is not enough to roll back, you have to restore a dump ([back up and restore](sauvegarde-et-restauration.md)). A version that makes rollback impossible must be visible from afar.

An older image **refuses to start** on a more recent database, and you have two ways out, detailed in [A database ahead of the image](mettre-a-jour.md#une-base-en-avance-sur-limage): redeploy the version that migrated the database, or restore a backup from before the upgrade.

## What the other two numbers promise

MINOR: feature added, new environment variable with a default that preserves behaviour, additive migration (new table, nullable column), new solver constraint that can be *turned off*.

PATCH: bug fix, security, dependencies, documentation. No destructive migration, no variable removed.

## Upgrades that require an action on your part

A new or hardened legal constraint can make a schedule that used to pass unsatisfiable. Technically additive, it is practically an operational break. The verdict: MINOR, with a mandatory "⚠️ Attention" entry in the release notes. MAJOR would change the major version every time the Labour Code (*Code du travail*) moves.

The marker is the reserved scope `contraintes-legales` (`feat(contraintes-legales): …`), not the `!`, which remains what the convention says of it, the sign of a MAJOR break and what `git cliff --bumped-version` reads to compute the upgrade. Both lead to the same "⚠️ Attention" section (`cliff.toml`), without the same character having to mean MAJOR here and MINOR there.

The other non-major operational breaks (a setting whose scale changes, a value to convert by hand in a file, an environment variable or a dump from before the update) follow the same rule: MINOR, with an "⚠️ Attention" entry. Their marker is the reserved scope `attention` (`feat(attention): …`); the commit body says what you must do. `contraintes-legales` stays reserved for legal constraints, so that the section reads at a glance.

So read the "⚠️ Attention" entry of the notes before each upgrade: it tells you whether something awaits you. Real examples of actions to take are in [the upgrades that require an action](mettre-a-jour.md#4-les-montées-qui-demandent-un-geste).

## How long a line is fixed

Only one maintenance line exists at a time: the last `MAJOR.MINOR` published before the current one ([how it is kept](publier-une-version.md#3-patcher-une-version-antérieure)). For 3.x, that is the `release/3.5` branch, which receives only fixes.

**Since the release of 4.0.0, 3.x receives security fixes for six months**, then nothing more: the branch is abandoned and the answer becomes "upgrade". These six months run from the date of the 4.0.0 release. Only security flaws are fixed, not functional defects nor dependencies with no known flaw.

## What the release expects for a major

A MAJOR upgrade is prepared in the commit messages. Between two tags, `release.yml` reads the subjects: a `!` after the type (`feat(scenario)!: …`) calls for a MAJOR upgrade, a `feat` calls for at least a MINOR. The check fails if the number set is **lower** than what the subjects call for, for example a `4.0.1` on commits with `!`, or a `3.5.1` that would contain a `feat`.

It does not refuse the reverse: a `4.0.0` set without any `!` between the two tags would pass. Yet it is this `!` that files the change under "⚠️ Attention" in the notes and in the *What's new* screen, and that lets `git cliff --bumped-version` propose 4.0.0. Every 4.0 break PR therefore carries a `!` in its subject, and its paragraph in [the moving guide](passer-a-la-4-0.md).

## Pre-releases (release candidates)

Before a major, a pre-release `vX.Y.Z-rc.N` (for example `v4.0.0-rc.1`) lets an operator try the version on a copy of their data. It follows the same form rules as a release, with its suffix:

- the `release.yml` check compares it with the last **stable** tag (a `v4.0.0-rc.2` is compared with `v3.5.0`, not with `rc.1`), and the number set cannot be lower than what the subjects call for; it also refuses a pre-release set after its final version, or whose `N` does not exceed that of a pre-release of the same number;
- its release notes list everything that has entered since that last stable tag; those of the final version `4.0.0` take the same range, pre-releases included;
- its image is published under `:X.Y.Z-rc.N` **and nothing else**: neither `:X.Y` nor `:latest`, so an operator who pulls `latest` or follows an `X.Y` line never receives a pre-release. *Cautious choice, to be confirmed:* the repository had not decided it, and nothing prevents setting a moving `:X.Y-rc` label later.

A pre-release promises nothing more than a trial: it may migrate the schema, and the schema does not go back down ([back up first](sauvegarde-et-restauration.md)).

## Reading an instance's version

The version displayed in the footer, on the *Debug* page, in `GET /api/config` (`version` field, public: the interface code every visitor loads already carries it) and in the startup line is that of the git tag `vX.Y.Z`: `X.Y.Z` (or `X.Y.Z-rc.N` for a pre-release) on a release image, the short SHA on an acceptance image, `999-SNAPSHOT` in a local build, a value that deliberately looks like no published version. Where this tag comes from: [Releasing a version](publier-une-version.md#1-la-source-de-vérité--un-tag-git).
