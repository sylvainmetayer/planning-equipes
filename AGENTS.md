# AGENTS.md — Planning Équipes

Single source of repository memory for every AI agent (Copilot CLI, Copilot
coding agent, Claude Code, …). **Do not recreate `CLAUDE.md` or
`.github/copilot-instructions.md`** — they were merged into this file. If an
instruction needs to change, change it here.

Custom reviewer agents are maintained for both ecosystems:
`.claude/agents/*.md` (Claude) and `.github/chatmodes/*.chatmode.md` (Copilot).
When one version changes, update the other one in the same commit to keep them
strictly synchronized.

This file is written in English; user-facing documentation (`README.md`, `docs/`)
is written in French, and domain identifiers stay in French business vocabulary.
The Angular frontend UI (`src/main/webui`) is bilingual French/English via
`@angular/localize`, translated at runtime (`loadTranslations()`, no per-locale
build): French is the source language written directly in templates/components
(`i18n="@@id"` / `` $localize`:@@id:…` ``), and the English string for every
id lives in `public/i18n/messages.en.json` — a new user-visible string needs
both. Never call `$localize` at module scope (only from a method, a
`computed()`, or a constructor): it must run after `main.ts` has loaded
translations, not at import time. See `docs/developpement.md` for the full
workflow. Code comments and non-domain identifiers stay in English.

## Project overview

Quarkus + Timefold Solver application that schedules ~150 `Animateur`s (staff)
onto game festival `Stand`s over 15 days, under hard/medium/soft constraints.

Read before working on constraints or the domain model:

- `README.md` (section *Fonctionnalités métier*) — functional scope and business capabilities
- `docs/domaine.md` — Timefold model and its invariants
- `docs/contraintes.md` — implemented constraints and how to add one
- `docs/architecture.md` — backend/frontend module layout

## Build, test, run

- Dev server: `./mvnw quarkus:dev` (needs Postgres; `docker compose up postgres`
  or Quarkus dev services)
- Full test suite (fast — excludes `scenario-lent`, see below): `./mvnw test`
- Single class: `./mvnw test -Dtest=PlanningHardConstraintsTest`
- Single method:
  `./mvnw test -Dtest=PlanningHardConstraintsTest#generatedPlanningDoesNotViolateAnyHardConstraintOnNominalCase`
- Integration tests (`*IT.java`, failsafe, `skipITs=true` by default):
  `./mvnw verify -DskipITs=false`
- Full stack: `docker compose --profile app up --build`

### Costly test jobs

`PlanningServiceScenarioCompletTest` and `PlanningServiceScenarioContinuTest`
solve a ~2000-poste scenario to hard-feasibility and take ~25s/~75s
respectively — tagged `@Tag("scenario-lent")`, excluded from the default
`./mvnw test`/`./mvnw verify` run via the `test.excludedGroups` property in
`pom.xml`, and **not run in CI** (`.github/workflows/tests.yml` uses the
default exclusion). Run them explicitly with `./mvnw test -Pscenario-tests`
(the profile clears the exclusion), optionally narrowed with `-Dtest=...`.
Add the same tag to any future test in this weight class instead of letting
it slow down the default loop.

When an agent session needs to run this profile (or any other job on this
order of a minute or more — a `docker build`, a long solve), launch it as a
background command and let the harness notify on completion instead of
blocking the turn on a foreground wait or a manual sleep/poll loop: both cost
real wall-clock time for nothing and, over a sleep-poll loop specifically,
burn tokens on repeated status checks for no benefit over one notification.
- Frontend only (from `src/main/webui`): `npm install`, `npm run build`,
  `npm start` (`ng serve` on 4200, `proxy.conf.json` forwards `/api/*` to
  `:8080`), `npm test` (Vitest unit tests, Node/jsdom, one pass in CI / watch
  in a terminal). `quarkus:dev` already starts and proxies the dev server —
  but a direct navigation (curl, F5, deep link) to any route but `/` on
  `:8080` 404s in dev mode (upstream Quinoa bug, doesn't affect production);
  test deep links against `:4200` directly instead — see
  `docs/developpement.md`.
- Toolchain pinned in `mise.toml` (`temurin-25`, `maven 3.9.9`, `node 24`); the
  Maven build downloads its own Node through Quinoa, so CI/Docker need none.

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
  `PlanningPersistenceService`, `DatabaseDumpService`,
  `PlanningExportService` (PDF/ICS, server-side only).
- `api/` — JAX-RS resources: `PlanningResource`, `SolverJobResource`,
  `ReferenceDataResource`, `EditionResource`, `ConstraintResource`,
  `DatabaseResource`, `PlanningExportResource`. Endpoint list in `docs/api.md`.
- `mcp/` — MCP tools (`@Tool`) exposing the same capabilities to an AI
  assistant, delegating to the services above. One hard rule: animateur
  nom/prénom/dateNaissance never leave over MCP (issue #107) — return
  dedicated view records, never domain objects, and run violation messages
  through `AnonymisationViolations`. `McpConfidentialiteStructurelleTest`
  enforces it reflectively over every tool. See `docs/mcp.md`.
- Persistence: PostgreSQL + Flyway migrations in
  `src/main/resources/db/migration/`. Schema change = **new versioned file**;
  never edit an applied migration.
- **Everything is partitioned by `edition`** ("Année 2025", "Année
  2026"). Every business table carries an `edition_id`, business ids have a
  composite `(edition_id, id)` primary key, and the edition a request works in
  comes from its `X-Edition-Id` header via `EditionContext`. A new reference
  table must follow the same convention, and its SQL must go through
  `ReferenceDataRepository` — the single point of passage that makes the
  `edition_id` predicate verifiable. See `docs/editions.md`.
- Solver tuning: `planning.solver.seconds-limit` /
  `planning.solver.unimproved-seconds-limit` in `application.properties`.

### Frontend

Angular 22 application in `src/main/webui`, built during `mvn package` and served
as Quarkus static resources by the **Quinoa** extension (`quarkus.quinoa.*` in
`application.properties`). Single deployment, no Node server in production; in
`quarkus:dev` Quinoa runs `ng serve` and proxies it on port 8080.

- Standalone components only (no `NgModule`), `signal()` / `computed()` for
  state, new control flow (`@if` / `@for`) in templates, lazy-loaded routes in
  `app/app.routes.ts`.
- UI built with **Angular Material** (Material Design 3). The theme lives in
  `src/material-theme.scss` (`mat.theme()`, azure/blue palettes, Roboto); use the
  `--mat-sys-*` tokens in custom CSS instead of hard-coded colours. The app has
  no `@angular/animations` dependency: Material components animate through CSS,
  so don't add `provideAnimations*()` back.
- Shell: `app/app.ts` renders a `mat-toolbar` + `mat-sidenav` with the navigation
  grouped in Planning / Reference data / Views, and the solver `app-job-monitor`
  in the toolbar.
- **One route = one page = one block.** Routes: `/` (default, the solver page),
  `/debug`, `/notifications`, `/data-setup`, `/stands`, `/emplacements`,
  `/animateurs`, `/creneaux`, `/decoupage`, `/typologies`,
  `/ad-hoc-constraints`, `/calendar`, `/day-calendar`, `/constraints`,
  `/problemes`, `/hours`, `/staffing` (`/solver`, `/exports` and
  `/data-transfer` are legacy redirects, kept for old bookmarks/links).
  Adding a functional block means adding a route and a `app/pages/<block>/`
  folder, never a new section inside an existing page.
- Layout: `app/core/` holds shared services (`api.service.ts` — the only place
  doing HTTP, `downloadFile` returns a status string and never touches the DOM;
  `models.ts`; `date-utils.ts`, week starts Monday; `planning-state.service.ts`;
  `reference-data.store.ts`; `reference-crud.service.ts` — save/delete, single
  or in bulk, plus snack-bar feedback shared by the five reference pages;
  `table-selection.ts` — multi-row selection of those pages, always intersected
  with the displayed rows; `bulk-edit.ts` — the "leave unchanged / add / remove
  / replace" modes a bulk edit applies to one row; `entity-labels.ts` — plural
  entity labels of the bulk actions;
  `solver-job.service.ts`; `notification.service.ts`, backed by `MatSnackBar`),
  `app/shared/` holds cross-page components (`job-monitor.ts`,
  `confirm-dialog.ts` — replaces `window.confirm`, `output-panel.ts`,
  `bulk-actions-bar.ts`), and `app/pages/<page>/` holds one folder per route.
- Bulk edits go through one dialog per entity (`<entity>-bulk-edit-dialog.ts`),
  whose rules live in a plain `<entity>-bulk-edit.ts` next to it so they are
  unit-tested without rendering. Every field defaults to "ne pas modifier": a
  bulk edit only writes what the user explicitly filled in.
- State flows one way: the solver page pushes the solved planning into
  `PlanningStateService`, calendars read it back read-only and never start a
  solve. Components don't call `fetch` directly.
- The "a solver is running" state is never stored in the browser
  (`localStorage` / `sessionStorage`): `SolverJobService` polls
  `/api/jobs/active` every 2 s so a solve started from another browser or a
  private window also locks the buttons here, shows the server-computed elapsed
  time and delivers its result.
- CSS stays **global** and limited to what Material does not cover:
  `src/styles.css` is a thin aggregator of `@import` rules only and the partials
  live in `src/styles/` (`pages.css` for the shared card/form/table scaffolding,
  `feedback.css` for the job monitor and snack-bar variants,
  `calendar-month.css`, `calendar-day.css`, `constraints.css`,
  `problemes.css`), each holding its
  own `@media` rules. Add new styles as new partials; don't recreate a monolithic
  stylesheet and don't restyle what a Material component already themes.
- Keep the frontend dependency-light: Angular, its CLI, Angular Material and
  `@angular/localize` are the whole frontend stack. Don't add another UI
  component library, a state-management library, a CSS framework or a
  third-party i18n library (`ngx-translate`, `transloco`, …) without explicit
  sign-off.
- Frontend tests are Vitest specs (`*.spec.ts` next to the code, `TestBed` for
  anything DI/rendering, `provideZonelessChangeDetection()` since the app is
  zoneless). They run via `npm test` in a Node/jsdom environment — no browser,
  not wired into the Maven `%test` phase (Quinoa stays disabled there), run by a
  dedicated CI job. Favour testing `core/` logic (services with a mocked
  `ApiService`, pure helpers) over heavy component-rendering tests.

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
- Every constraint has an isolated unit test in the matching
  `solver/constraints/*ConstraintsTest` (Timefold `ConstraintVerifier`, no
  Quarkus/DB, shared `ConstraintTestBase`): at least one penalized case and one
  valid case. A new constraint isn't done without one.
- Hard constraints must never be violated in a valid solved plan.
  `PlanningHardConstraintsTest` asserts `solved.getScore().hardScore()` is zero on
  the nominal scenario — extend this style of test whenever a new hard constraint
  is introduced, and don't consider the change done until it passes.
- Domain class/field names stay in French business vocabulary (`Animateur`,
  `Creneau`, `typologie`, `joursIndisponibles`) to match the spec; code
  comments and non-domain identifiers are in English.
- Typologies de jeu are a CRUD referential (`typologie` table, `/api/typologies`),
  not a Java enum — `Stand.typologiesProposees`/`Animateur.competences` reference
  ids validated against that table (FK-enforced), so new categories can be
  created from the `/typologies` admin page without a code change.

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
   `developpement.md`. Adding a topic means adding a file there and a row in
   `docs/README.md` — not a new root-level file and not a new README section.
4. Docs are written in **French**; keep the existing tone and Markdown style
   (tables for enumerable facts, fenced code blocks for commands).
5. When behaviour changes, update the doc that owns it: new endpoint →
   `docs/api.md`; new constraint → `docs/contraintes.md` **and**
   `ConstraintCatalog`; new business capability → README section 2; new
   command/CI/tooling → `docs/developpement.md`.
6. Don't create planning/notes/tracking Markdown files in the repository.

## Dependency updates

Renovate (`renovate.json` at the repo root) tracks Maven dependencies (including
the `quarkus.platform.version` / `timefold.solver.version` properties), the npm
dependencies of `src/main/webui`, Docker images, GitHub Actions and the
`mise.toml` toolchain. Keep the config in that
single file; document behaviour changes in `docs/developpement.md`. Quarkus and
Timefold bumps must be validated with `./mvnw verify -DskipITs=false`.

## Working conventions

- Code and comments in English, domain names in French business vocabulary.
- Write/extend a test proving no hard constraint is violated before considering a
  step done.
- No additional frontend dependency (UI kit, state library, CSS framework)
  without explicit sign-off — plain Angular served by Quinoa is a deliberate
  choice.
