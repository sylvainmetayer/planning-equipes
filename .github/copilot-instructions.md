# Copilot Instructions — Planning Équipes

Quarkus + Timefold Solver app for scheduling ~150 `Animateur`s (staff) to game
festival `Stand`s over 15 days, under hard/medium/soft constraints. Full spec:
`CAHIER_DES_CHARGES.md` and `CLAUDE.md` at repo root — read these for the
detailed constraint catalogue and domain rationale before adding constraints.

## Build, test, run

- Local dev server: `./mvnw quarkus:dev` (needs Postgres; Quarkus dev services
  can spin up a container automatically, or use `docker compose up postgres`).
- Full test suite: `./mvnw test`
- Single test class: `./mvnw test -Dtest=PlanningHardConstraintsTest`
- Single test method: `./mvnw test -Dtest=PlanningHardConstraintsTest#generatedPlanningDoesNotViolateAnyHardConstraintOnNominalCase`
- Integration tests (`*IT.java`, run via failsafe): `./mvnw verify`
- Docker Compose (full app + Postgres): `docker compose --profile app up --build`
- Java 17 / Maven toolchain pinned via `mise.toml` (`temurin-17`, `maven 3.9.9`).

## Architecture

- Single Quarkus service, no separate solver microservice. Package root:
  `dev.sylvain.planning`.
  - `domain/` — Timefold model: `Animateur`, `Stand`, `Creneau`,
    `PosteAffectation` (planning entity, one instance per seat to fill —
    **not** one per stand×slot), `ContrainteAdHoc` (ad hoc rules), and
    `PlanningFestival` (`@PlanningSolution`, holds the `HardMediumSoftScore`).
  - `solver/PlanningConstraintProvider.java` — all constraint-stream rules in
    one class, one private method per constraint, registered in the array
    returned by `defineConstraints`.
  - `service/PlanningService.java` — builds the `SolverFactory` from
    `solver/solverConfig.xml`, loads the demo scenario from
    `src/main/resources/scenario.yml` (YAML via SnakeYAML, not JSON), and
    exposes `construireExemple()` / `resoudre(PlanningFestival)`.
  - `service/ReferenceDataService.java` — in-memory/DB-backed CRUD for stands,
    créneaux, animateurs, typologies, and `ContrainteAdHoc`; snapshots ad hoc
    constraints into a solved `PlanningFestival` if none were supplied.
  - `api/` — JAX-RS resources: `PlanningResource` (`/api/planning/sample`,
    `/api/solve`), `ReferenceDataResource` (CRUD under `/api/stands`,
    `/api/creneaux`, `/api/animateurs`, `/api/typologies`,
    `/api/contraintes-ad-hoc`), `PlanningExportResource` (PDF via OpenPDF and
    ICS export under `/api/planning/export/...`).
  - `service/PlanningExportService.java` — server-side PDF/ICS generation, no
    client-side export logic.
- Frontend is vanilla JS served as Quarkus static resources from
  `src/main/resources/META-INF/resources/` (`index.html`, `style.css`, and ES
  modules under `js/`) — calls the REST API with native `fetch`. No bundler, no
  framework; don't introduce one without explicit sign-off.
  - JS is split into ES modules loaded via `<script type="module"
    src="/js/app.js">`; ES modules require HTTP serving (Quarkus), not
    `file://`. One responsibility per file: `js/app.js` (entry point: page nav +
    module init), `js/api.js` (fetch helpers; `downloadFile` returns a status
    string, never touches the DOM), `js/utils.js`, `js/date-utils.js` (calendar
    date math, week starts Monday), `js/planning-state.js` (shared planning
    state behind get/setLastSolvedPlanning + `ensurePlanning()` — no free global
    var), `js/calendar-month.js` (monthly view + animator/stand filters, keeps
    its own view state), `js/calendar-day.js` (day view), `js/admin.js`
    (planning actions, exports, reference-data CRUD; owns its local CRUD state).
  - Each view module exports `initX()` (wires DOM events once) and `renderX()`
    used by nav; keep DOM lookups inside the owning module. State flows one way:
    admin solve/analyze -> `setLastSolvedPlanning`, calendars read it via
    `ensurePlanning`. Don't reintroduce shared mutable globals or recreate the
    old monolithic `planning.js`.
  - CSS mirrors the same split: `style.css` is a thin aggregator of `@import`
    rules only (Google font first, then partials), and component styles live in
    partials under `css/` (`base.css` design tokens/reset/typography/controls,
    `layout.css` nav, `calendar-month.css`, `calendar-day.css`), each holding
    its own responsive `@media` rules. Add new styles as new partials; don't
    recreate the old monolithic `style.css`.
- Persistence: PostgreSQL + Flyway migrations in
  `src/main/resources/db/migration/` (`V1__init.sql`, etc.). Add new schema
  changes as new versioned migration files, never edit an applied one.
- Solver tuning: `planning.solver.seconds-limit` in `application.properties`
  (30s default, 3s in `%test` profile) controls how long `resoudre()` runs.

## Domain conventions specific to this repo

- `PosteAffectation` is generated per seat (respecting `Stand.effectifMin/Max`)
  at scenario/seed time, not one entity per stand+slot pair — effectif
  min/max is enforced by counting non-null `animateur` assignments grouped by
  stand+créneau, not by a dedicated constraint class.
- Minor/adult status (`estMineurLe(LocalDate)` / `estMajeurLe(LocalDate)` on
  `Animateur`) is always derived from `dateNaissance` at the créneau's date —
  never stored as a boolean flag, to avoid drift.
- `ContrainteAdHoc` (types `INDISPONIBILITE_FORCEE`, `INCOMPATIBILITE`,
  `AFFECTATION_FORCEE`) models one-off admin exceptions and is evaluated as
  `HardScore` inside `PlanningConstraintProvider`, at the same priority as the
  legal/minor hard constraints — never special-case these as soft/medium.
- Hard constraints must never be violated in a valid solved plan.
  `PlanningHardConstraintsTest` asserts `solved.getScore().hardScore()` is
  zero on the nominal scenario — add/extend this style of test whenever a new
  hard constraint is introduced, and don't consider a constraint change done
  until it passes.
- Domain class/field names stay in French business vocabulary (`Animateur`,
  `Creneau`, `TypologieJeu`, `disponibilites`, etc.) to match the spec
  document; code comments and non-domain identifiers are in English.
