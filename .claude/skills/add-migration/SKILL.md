---
name: add-migration
description: Create a new Flyway migration file with the correct next version number for planning-equipes. Use when the user asks for a schema change, new table/column, or database migration.
---

# Add a new Flyway migration

Schema changes in this repo are **always a new versioned file** — never edit
a migration that has already been applied (checked into `main`).

## 1. Find the next version number

```
ls src/main/resources/db/migration/
```

Migrations follow `V<N>__<snake_or_words>.sql`. Take the highest existing
`N` and use `N+1`. Don't reuse or renumber existing files.

## 2. Name the file

`V<N>__<short_description>.sql`, matching the existing naming style (e.g.
`V6__animateur_manager.sql`, `V7__parametres_legaux.sql`) — lowercase,
underscores, no French accents in the filename.

## 3. Write the migration

Plain SQL, PostgreSQL dialect (this project targets Postgres, dev services or
`docker compose up postgres`). Keep it additive/reversible where possible;
don't drop columns/tables without confirming with the user first since that's
a destructive, hard-to-reverse action on shared state once deployed.

## 4. Update the domain model if needed

If the migration backs a new field on an existing Timefold entity, update the
corresponding class in
`src/main/java/dev/sylvain/planning/domain/` and check
whether `ReferenceDataService` / `ReferenceDataRepository` or the relevant
`api/` resource need the new field exposed.

## 5. Verify

```
./mvnw test
```

Flyway runs migrations automatically against the test datasource; a failing
migration will surface as a startup failure in tests that boot Quarkus. Also
sanity check locally with `docker compose up postgres` +
`./mvnw quarkus:dev` if the change is non-trivial.
