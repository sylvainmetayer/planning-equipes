---
twin: installer/mettre-a-jour
---

# Upgrade to a new version

You move the instance to a newer version without breaking open browsers or the database, and you know how to roll back or unblock an interrupted migration. Before you start, make a [backup](sauvegarde-et-restauration.md#2-sauvegarder-à-la-main): the database schema only moves forward, and going back to the previous image is not enough to go back.

The version policy, in short, is in [self-hosting](/heberger/): you deploy a specific number, never `latest`.

## Finding out that a newer version exists

You do not have to watch the repository: on a build **sitting on a tag**, the administration toolbar shows, next to the notification bell, an icon whose tooltip names the latest published release when it is newer than the one running. Clicking it opens its release notes.

It is the administrator's browser that queries GitHub's public API (`/releases/latest`, once per page load), never the server, which may well be deployed with no outbound network access. **Only the browser of a signed-in administrator** does it: the administration shell has no route guard, it renders and then redirects on the first 401, so any visitor would have reached GitHub before that redirect. The IP address of a staff member, often a minor, would have been handed over for nothing, and GitHub's anonymous per-IP quota used up to the detriment of the real administrators. The session is therefore confirmed on `/api/auth/me` before any outbound call.

A build on a SHA queries nothing: "a newer version exists" is true every day there and teaches nothing. GitHub unreachable, API quota exhausted, unexpected response: the icon stays absent, with no message, since it is a courtesy and not an alert. If you list endpoints in `CSP` ([the browser headers](securite-navigateur.md)), keep `https://api.github.com` in it to preserve the icon, or leave it out to disable it.

What each number promises, and how to read the release notes, is in [version numbers](versioning.md).

## 1. Choose the version to deploy

A production deployment references a **named version** (`1.2.0`), never `:main` or `:latest`. The workflow also publishes a `:main` image on every merge and rebuilds it without cache every Sunday for Ubuntu fixes: this is the **acceptance-test image**, used to validate what will ship, not to run at a client's. `:main` points to a different commit every day; `:latest` points to whatever the registry decides; neither answers "what were you running?".

`docker-compose.prod.yml` takes the version from the `APP_VERSION` variable of `.env.prod`. The version can also be read **in the application's responses**: `GET /api/config` carries a `version` field (`X.Y.Z` on a release image, the short SHA on an acceptance-test image, `999-SNAPSHOT` locally), the same as the startup line.

Read the release notes before upgrading: the "⚠️ Attention" entry flags what needs action on your part, such as a new legal constraint that can make a schedule that used to pass unsatisfiable, or a value to convert by hand.

## 2. Deploy

1. Set `APP_VERSION` to the new version in `.env.prod`.
2. Pull the image and recreate the stack:

   ```bash
   docker compose -f docker-compose.prod.yml --env-file .env.prod up -d
   ```

3. Check: `scripts/verifier-deploiement.sh https://<domaine>` compares the exposed version with `APP_VERSION`, along with availability and the legal notice ([the script](installation.md#mettre-la-pile-en-route)).

Every successful startup of a version **different** from the previous one is recorded in the database (`version_applicative` table: version, first startup, last migration at that moment). On a startup forced by `ALLOW_SCHEMA_AHEAD`, what is recorded is the last migration that version knows, so that it does not afterwards pass for the one that migrated the database. The *Debug* page shows it: enough to write in an incident report "database migrated by 1.3.0, then opened by 1.2.4".

## 3. Redeploy without breaking open browsers

There is nothing to clear and nothing to ask users to do: after an upgrade, the application reloads its own files, on the next visit or on the first click of a tab left open. No setting is needed, redeploying is enough. The mechanism in detail, useful only to diagnose a blank screen after an upgrade, is in [The Angular frontend](../comprendre/frontend.md#redéployer-sans-casser-les-navigateurs-ouverts).

## 4. Upgrades that need an action

### Migration `V126` and the time zone

Migration `V126` converts five timestamps without a time zone into `timestamptz`, reading them in the instance's time zone at migration time. That zone must therefore be the one the rows were written under (the JVM's zone, `TZ`): migrating under another shifts those dates. The hour repeated at the switch to winter time is ambiguous in every case. The migration also refuses to start, with a message naming the table, if rows point to a timeslot of another edition: that is a data incident to examine, never repaired automatically.

### The weight scale

Migration `V108` multiplies by five the weights each edition has saved: the importance of a rule now reads low 1, normal 5, high 25 ([decision 0057](repo:docs/decisions/0057-importance-d-une-regle-en-trois-positions.md)). It rewrites only the database. Whatever carries a weight elsewhere keeps the old scale, and the rule concerned would then weigh five times less than the others:

| What keeps the old scale | What to do |
| --- | --- |
| a `planning.constraint-weights.<règle>` override of the deployment | multiply it by five along with the upgrade, or remove it if it only repeated the old default `1`: the default is now the catalogue's |
| an SQL export from the application (removed from the screen in 3.2) taken before the upgrade and reimported after | multiply by five the weights of the file's `ponderation_contrainte` table before importing it, or set the importances again on the Planning rules screen afterwards |
| a scenario file written before | multiply its `contraintes.poids` by five ([the scenario format](../comprendre/scenario-yaml.md)) |

A restore with `scripts/restaurer.sh` of a `pg_dump` dump taken before the upgrade is not concerned: the dump's Flyway history does not know `V108`, which the application replays at startup.

### The legal notice and the active edition

A live instance that has never set the legal notice does not restart ([setting it](mentions-legales.md#monter-de-version-avec-une-instance-déjà-en-service)). On upgrading to `V118`, the active edition is chosen automatically ([the automatic sends](envois-automatiques.md#2-activer-lédition-qui-doit-écrire)).

## The PostgreSQL version

The `postgres:` image of `docker-compose.prod.yml` and the `pg_dump` client of the application image are on **version 19**, and always move together: a `pg_dump` older than the server refuses to run ("server version mismatch"), and the nightly backup would then fail without the application stopping. Do not change one without the other.

## Unblock an interrupted migration

A startup cut off in the middle of a migration leaves a failed row in Flyway's history table, and all the following migrations refuse to start. `FLYWAY_REPAIR_AT_START=true` removes that row at the next startup.

**Set it back to `false` once the instance is running again.** Left on `true`, the repair also realigns the history's checksums on the shipped files: a migration modified after the fact would then pass without a word, when that is precisely what Flyway's refusal reports.

The repair does **not** touch successful migrations the image does not know: a database ahead of the binary is not an interrupted migration, and `FLYWAY_REPAIR_AT_START` does not unblock it. On such a database the repair is even **refused**, with the message of the next section: it would erase the interrupted migration of a newer version, and the old image would restart on a half-migrated schema with no trace left in the history.

## A database ahead of the image

Going back to an older image after an upgrade that migrated the database starts older code on a newer schema. **An older image refuses to start on a newer database.** At startup, the application compares the database's Flyway history with the migrations it embeds. If the database carries a migration the binary does not know, it **stops** with a message that names its version, the last migration it knows, the last one applied, and the application version that applied it. To read it:

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod logs app
```

Two ways out, and neither is to edit the schema by hand:

| Way out | When |
| --- | --- |
| Redeploy the version that migrated the database (`APP_VERSION`) | the ordinary case: going back was a mistake, or the fix you were waiting for exists in an equally recent version or later |
| Restore a backup from before the upgrade, with `scripts/restaurer.sh` ([the procedure](sauvegarde-et-restauration.md#3-restaurer)) | you really need the old version, at the cost of the writes made since the upgrade. The script also warns when the dump is newer than the image |

`ALLOW_SCHEMA_AHEAD=true` forces startup anyway, with a `WARN` warning in the log and in Sentry, for those who know that the migration ahead is additive and that the old code copes with it. The readiness probe then stops counting the newer version's failed migrations, which the forced startup accepted: otherwise the instance would never be declared ready. **Set it back to `false` afterwards**, like the repair: left on `true`, it would silently let through the next rollback, the one nobody checked.

The check is based on migrations, never on the version number: a local `999-SNAPSHOT` build on a copy of the production database follows the same rule, and a maintenance-branch fix that added a migration `main` does not have would show up at the first startup of the next version.

The *Debug* page lists the versions that have opened the database, with the last migration applied at the time of their first startup.

![The Debug page: version, migrations and versions that have opened the database](/images/fr/docs/debug.webp "The Debug page")
