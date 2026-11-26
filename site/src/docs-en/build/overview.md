---
twin: comprendre/vue-d-ensemble
---
# The stack and how the code is split

Planning Équipes is a single service: one Quarkus application (Java 25) that embeds the Timefold solver in the same JVM, serves the already-compiled Angular interface and talks to a PostgreSQL database. This page covers what the stack is made of, how the code is split, where each capability lives, and how the image and the database behave at startup.

No microservice, no Node server in production. The model (seats, timeslots, staff members) is assumed to be known; if not, start with [the data model](modele-des-donnees.md).

## The stack

| Layer | Choice |
| --- | --- |
| Backend | Quarkus: REST and static resources in the same deployment |
| Optimisation | Timefold Solver Community, `HardMediumSoftScore` score; a second, hand-written engine refereed by Timefold ([the solve](comment-le-planning-est-calcule.md)) |
| Persistence | PostgreSQL and Flyway |
| Frontend | Angular 22, standalone components, signals, zoneless, served by Quinoa ([the frontend](frontend.md)) |
| Exports | OpenPDF for the PDFs, `zxing-core` for the QR code, ICS written in the repository, always server-side |
| MCP | `quarkus-mcp-server-http` ([MCP connection](mcp-connexion.md)) |
| Tooling | `mise.toml`: Temurin 25, Maven 3.10, Node 26 |

## How the code is split

The root package is `dev.sylvain.planning` ([`src/main/java/dev/sylvain/planning`](repo:src/main/java/dev/sylvain/planning)). Each subpackage answers one question.

| Package | What it holds |
| --- | --- |
| `domain/` | The Timefold model: `Animateur`, `Stand`, `Creneau`, `PosteAffectation` (one instance per seat to fill), `ContrainteAdHoc`, `VerrouillagePlanning`, `DemandeEchange`, `PlanningEvenement` (the `@PlanningSolution`) |
| `solver/` | The rules: `PlanningConstraintProvider` aggregates them, `solver/constraints/` files them by family, `ConstraintCatalog` describes them, `solver/handsearch/` is the direct engine |
| `service/` | The business logic, one subpackage per capability (see below) |
| `api/` | The JAX-RS resources: transport only (statuses, body shapes). Anything that decides something lives in a service |
| `mcp/` | The MCP tools, which call the same services as the API |
| `scenario/` | The DTO of the scenario file |
| `config/`, `observability/` | Typed configuration, metrics and error tracking |

Two layering rules hold the split together. An MCP tool delegates to services, never to a resource: MCP and REST are two callers of the same rules, and a tool that injected a resource would only read an HTTP `Response` it never received (`LayeringStructuralTest`). A business refusal is a `BusinessError`, which the API and MCP each translate in their own way; a resource does not write a `try/catch` for it.

### The subpackages of `service/`

| Subpackage | Question it answers |
| --- | --- |
| `solve/` | How a problem is built, solved, stored, queued or sent to a worker |
| `analyse/` | What is read from a plan without solving it again (fairness, hours, realised versus planned) |
| `diagnostic/` | The score breakdown, rule by rule |
| `referentiel/` | The reference data (stands, staff, timeslots, game categories...), their validations, their imports, the freeze |
| `scenario/` | Reading and writing the scenario file, import |
| `edition/` | Editions, their activation, their comparison |
| `publication/` | Publishing, sending, acknowledging receipt; `MailService` |
| `espace/` | The staff espace, swaps, the event-day clock |
| `compte/`, `responsable/`, `rh/` | Accounts and their rights ([access and accounts](acces-et-comptes.md)) |
| `consigne/`, `validation/`, `journee/`, `mural/` | Edition consignes, the review of a day, what moved on a day, the wall display |
| `notification/`, `mail/`, `webhook/`, `weather/`, `backup/`, `journal/` | Everything that goes out or leaves a trace ([background tasks](taches-de-fond-et-notifications.md)) |
| `export/`, `profile/`, `schema/` | The PDFs and the ICS, a staff member's profile, the schema guard |

The root of `service/` keeps only what all of them share: `EditionContext`, `JdbcEditionScope`, `BusinessError`, the identifiers. A member that another package needs is public, and its name is then in English.

## Where to look

| You are looking for... | Page |
| --- | --- |
| Why a query cannot read another edition | [One edition, one partition of the data](cloisonnement-par-edition.md) |
| What a solve does from end to end, and why two engines | [From problem to plan](comment-le-planning-est-calcule.md) |
| Who can call what | [Accounts, roles and access](acces-et-comptes.md) |
| Night tasks, mails, webhooks, weather, the action journal, backup | [Scheduled tasks and notifications](taches-de-fond-et-notifications.md) |
| The conventions of the Angular interface | [The Angular frontend](frontend.md) |

## The database

Flyway migrates at startup (`quarkus.flyway.migrate-at-start=true`). A schema change is a new versioned file in `src/main/resources/db/migration/`; a migration that has already been applied is never modified. `FlywayMigrationsFrozenTest` holds both halves: a new file must have its line in `src/test/resources/migrations-empreintes.txt` (the failure prints it), and an applied file whose fingerprint has moved is refused.

`FLYWAY_REPAIR_AT_START` (false by default) enables repair of the history table. It only makes sense in operation, to unblock an interrupted migration ([operations](../installer/exploitation.md)).

### A database ahead of the binary

An image that is rolled back can find itself facing a schema that a more recent version has migrated. Flyway must not decide alone, because it would propose a repair that deletes those history rows. Three pieces share the decision:

- `ignore-migration-patterns=*:future` lets validation pass on future migrations;
- `SchemaCompatibilityGuard` (`service/schema/`) reads `Flyway.info()` once the migration is done and refuses to start with a message that names the versions and the steps to follow, unless `ALLOW_SCHEMA_AHEAD=true`;
- `SchemaCompatibilityRepairGuard`, a Flyway callback, takes the same decision right before any repair. The `*:future` pattern only spares successful future migrations: repair deletes every failed row, including one from a more recent binary, and `FLYWAY_REPAIR_AT_START` runs it before the guard.

The decision itself is the pure class `SchemaCompatibility`. A second observer, run after the other startup checks, records each change of application version in `version_applicative`; the *Debug* screen lists it ([versions](../installer/versioning.md)).

## Containerisation

The [`Dockerfile`](repo:Dockerfile) is multi-stage: Maven and Temurin 25 compile (Quinoa builds the Angular bundle during `package`), and a JRE image keeps only the application. It runs as the unprivileged user with uid 1000 and ships `postgresql-client-19` for the nightly backup: a `pg_dump` older than the server refuses to run, so this version follows the one of the stack's `postgres:` image.

The same image carries a second program. Under `SOLVER_WORKER_MODE=true`, `docker/entrypoint.sh` starts the remote solve worker instead of the application ([solving on another machine](../installer/calcul-sur-une-autre-machine.md)).

Two Compose stacks, not to be confused:

| File | Role |
| --- | --- |
| [`docker-compose.yml`](repo:docker-compose.yml) | Development: PostgreSQL, an SMTP sink (Mailpit) and pgAdmin; the application only starts under the `app` profile |
| [`docker-compose.prod.yml`](repo:docker-compose.prod.yml) | Production: the application and PostgreSQL, a single published port, on the loopback, behind the reverse proxy that terminates TLS |

The production configuration is in [operations](../installer/exploitation.md), the hardening in [security](../installer/securite.md).
