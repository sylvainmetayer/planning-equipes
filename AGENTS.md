# AGENTS.md — Planning Équipes

Single source of repository memory for every AI agent (Copilot CLI, Copilot
coding agent, Claude Code, …). **Do not recreate `CLAUDE.md` or
`.github/copilot-instructions.md`** — they were merged into this file. If an
instruction needs to change, change it here.

This file is written in English; user-facing documentation (`README.md`, `docs/`)
is written in French, and domain identifiers stay in French business vocabulary.

## Project overview

Quarkus + Timefold Solver application that schedules ~150 `Animateur`s (staff)
onto game festival `Stand`s over 15 days, under hard/medium/soft constraints.

Read before working on constraints or the domain model:

- `docs/CAHIER_DES_CHARGES.md` — functional spec, legal constraint catalogue
- `docs/domaine.md` — Timefold model and its invariants
- `docs/contraintes.md` — implemented constraints and how to add one
- `docs/architecture.md` — backend/frontend module layout

## Build, test, run

- Dev server: `./mvnw quarkus:dev` (needs Postgres; `docker compose up postgres`
  or Quarkus dev services)
- Full test suite: `./mvnw test`
- Single class: `./mvnw test -Dtest=PlanningHardConstraintsTest`
- Single method:
  `./mvnw test -Dtest=PlanningHardConstraintsTest#generatedPlanningDoesNotViolateAnyHardConstraintOnNominalCase`
- Integration tests (`*IT.java`, failsafe, `skipITs=true` by default):
  `./mvnw verify -DskipITs=false`
- Full stack: `docker compose --profile app up --build`
- Toolchain pinned in `mise.toml` (`temurin-25`, `maven 3.9.9`)

## Architecture (essentials)

Single Quarkus service, no separate solver microservice. Package root:
`dev.sylvain.planning`.

- `domain/` — Timefold model: `Animateur`, `Stand`, `Creneau`, `PosteAffectation`
  (planning entity, **one instance per seat to fill**, not one per stand×slot),
  `ContrainteAdHoc`, `PlanningFestival` (`@PlanningSolution`,
  `HardMediumSoftScore`).
- `solver/PlanningConstraintProvider.java` — aggregates constraints; the actual
  rules live in `solver/constraints/` split by family (`AffectationConstraints`,
  `LegalConstraints`, `AdHocConstraints`, `QualiteConstraints`,
  `PreferenceConstraints`), one private method per constraint.
- `solver/ConstraintCatalog.java` — business description of every constraint,
  served by `GET /api/constraints`; **every new constraint must be registered
  there too**.
- `service/` — `PlanningService` (SolverFactory from `solver/solverConfig.xml`,
  loads `scenario.yml` via SnakeYAML), `SolverJobService` (async solve/analyze),
  `ConstraintAnalysisStore`, `ReferenceDataService` / `ReferenceDataRepository`,
  `PlanningPersistenceService`, `CsvImportService`, `DatabaseDumpService`,
  `PlanningExportService` (PDF/ICS, server-side only).
- `api/` — JAX-RS resources: `PlanningResource`, `SolverJobResource`,
  `ReferenceDataResource`, `ConstraintResource`, `CsvImportResource`,
  `DatabaseResource`, `PlanningExportResource`. Endpoint list in `docs/api.md`.
- Persistence: PostgreSQL + Flyway migrations in
  `src/main/resources/db/migration/`. Schema change = **new versioned file**;
  never edit an applied migration.
- Solver tuning: `planning.solver.seconds-limit` /
  `planning.solver.unimproved-seconds-limit` in `application.properties`.

### Frontend

Vanilla JS served as Quarkus static resources from
`src/main/resources/META-INF/resources/` — no bundler, no framework, no Node
dependency; don't introduce one without explicit sign-off. ES modules require
HTTP serving (Quarkus), not `file://`.

- One responsibility per file: `js/app.js` (entry point: page nav + module init),
  `js/api.js` (fetch helpers; `downloadFile` returns a status string, never
  touches the DOM), `js/utils.js`, `js/date-utils.js` (week starts Monday),
  `js/planning-state.js` (shared planning state behind
  `get/setLastSolvedPlanning` + `ensurePlanning()` — no free global var),
  `js/calendar-month.js`, `js/calendar-day.js`, `js/constraints.js`, `js/jobs.js`,
  `js/notifications.js`, `js/reference-data.js`, `js/data-transfer.js`,
  `js/admin.js`.
- Each view module exports `initX()` (wires DOM events once) and `renderX()` used
  by nav; keep DOM lookups inside the owning module. State flows one way: admin
  solve/analyze -> `setLastSolvedPlanning`, calendars read it via
  `ensurePlanning`. Don't reintroduce shared mutable globals or a monolithic
  `planning.js`.
- CSS mirrors the split: `style.css` is a thin aggregator of `@import` rules only
  (Google font first, then partials); component styles live in partials under
  `css/`, each holding its own `@media` rules. Add new styles as new partials;
  don't recreate a monolithic `style.css`.

## Domain invariants (never break these)

- `PosteAffectation` is generated **per seat** (respecting
  `Stand.effectifMin/Max`) at scenario/seed time; effectif min/max is enforced by
  counting non-null `animateur` assignments grouped by stand+créneau, not by a
  dedicated constraint class.
- Minor/adult status (`estMineurLe(LocalDate)` / `estMajeurLe(LocalDate)`) is
  always derived from `dateNaissance` at the créneau's date — never stored as a
  boolean flag.
- Availability is opt-out: an animateur is available unless the date is listed in
  `joursIndisponibles` (`estIndisponibleLe(LocalDate)`).
- `ContrainteAdHoc` (`INDISPONIBILITE_FORCEE`, `INCOMPATIBILITE`,
  `AFFECTATION_FORCEE`) is evaluated as `HardScore`, at the same priority as
  legal/minor hard constraints — never demote these to medium/soft.
- Hard constraints must never be violated in a valid solved plan.
  `PlanningHardConstraintsTest` asserts `solved.getScore().hardScore()` is zero on
  the nominal scenario — extend this style of test whenever a new hard constraint
  is introduced, and don't consider the change done until it passes.
- Domain class/field names stay in French business vocabulary (`Animateur`,
  `Creneau`, `TypologieJeu`, `joursIndisponibles`) to match the spec; code
  comments and non-domain identifiers are in English.

## Documentation rules (keep this structure)

The doc layout is intentional — respect it when adding or updating docs.

1. **One memory file**: this `AGENTS.md`. Never split repository memory across
   `CLAUDE.md`, `.github/copilot-instructions.md`, or similar files.
2. **`README.md` has exactly two content sections**:
   1. *Démarrer l'application en local* — prerequisites, Docker Compose, dev
      mode, first steps, configuration, tests;
   2. *Fonctionnalités métier* — what the app does, in business language, no
      class names, no endpoint tables, no file paths.
   A short intro and a closing documentation index table are allowed; anything
   else technical goes to `docs/`.
3. **All technical documentation lives in `docs/`**: `architecture.md`,
   `domaine.md`, `contraintes.md`, `api.md`, `import-export.md`,
   `developpement.md`, plus `CAHIER_DES_CHARGES.md`. Adding a topic means adding
   a file there and a row in `docs/README.md` — not a new root-level file and not
   a new README section.
4. Docs are written in **French**; keep the existing tone and Markdown style
   (tables for enumerable facts, fenced code blocks for commands).
5. When behaviour changes, update the doc that owns it: new endpoint →
   `docs/api.md`; new constraint → `docs/contraintes.md` **and**
   `ConstraintCatalog`; new business capability → README section 2; new
   command/CI/tooling → `docs/developpement.md`.
6. Don't create planning/notes/tracking Markdown files in the repository.

## Dependency updates

Renovate (`renovate.json` at the repo root) tracks Maven dependencies (including
the `quarkus.platform.version` / `timefold.solver.version` properties), Docker
images, GitHub Actions and the `mise.toml` toolchain. Keep the config in that
single file; document behaviour changes in `docs/developpement.md`. Quarkus and
Timefold bumps must be validated with `./mvnw verify -DskipITs=false`.

## Working conventions

- Code and comments in English, domain names in French business vocabulary.
- Write/extend a test proving no hard constraint is violated before considering a
  step done.
- No additional frontend dependency (no bundler, no framework) without explicit
  sign-off — statically served vanilla JS is a deliberate choice, not a temporary
  step.
