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
both — and `npm run i18n-check` (job `frontend` of the Tests workflow) fails
the build when they drift: a missing id, an orphan key, or a placeholder
renamed between source and translation. Never call `$localize` at module scope
(only from a method, a `computed()`, or a constructor): it must run after
`main.ts` has loaded translations, not at import time. See
`docs/developpement.md` for the full workflow. Code comments and non-domain identifiers stay in English.

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

`PlanningServiceScenarioCompletTest`, `PlanningServiceScenarioContinuTest`
and `PlanningServiceScenarioFestivalRealisteTest` solve large scenarios to
hard-feasibility — the first two take ~25s/~75s on hand-built problems, the
third runs the two **anonymised real-world fixtures**
(`festival-realiste.yaml` and its `-canicule` variant: 153 animateurs, 65
stands, 45 premium, per-stand recurring schedules) — the only ones whose
stands carry recurring horaires, which a plain-Java harness must expand with
`HoraireStandResolver.appliquer` before building postes or it solves a problem
five times too large. They are tagged `@Tag("scenario-lent")`, excluded from the
default
`./mvnw test`/`./mvnw verify` run via the `test.excludedGroups` property in
`pom.xml`, and not run by the main CI workflow (`.github/workflows/tests.yml`
uses the default exclusion). Run them explicitly with
`./mvnw test -Pscenario-tests` (the profile clears the exclusion), optionally
narrowed with `-Dtest=...`.

CI does run them, but **only when something that can break convergence
changes**: `.github/workflows/scenario-tests.yml` triggers on the `solver/`
and `domain/` packages, `solverConfig.xml`, `application.properties`, the
scenario files and `pom.xml` (a Timefold bump is exactly when you want them).
Keep that path list in step with any move of those files — a solver tuning
that ships without these tests having run is the hole this workflow exists to
close.
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
  `ContrainteAdHoc`, `VerrouillagePlanning`, `DemandeEchange` (self-service
  swap proposals, issue #165), `PlanningFestival` (`@PlanningSolution`,
  `HardMediumSoftScore`).
- `solver/PlanningConstraintProvider.java` — aggregates constraints; the actual
  rules live in `solver/constraints/` split by family (`AffectationConstraints`,
  `LegalConstraints`, `AdHocConstraints`, `QualiteConstraints`,
  `PreferenceConstraints`), one private method per constraint.
- `solver/ConstraintCatalog.java` — business description of every constraint,
  served by `GET /api/constraints`; **every new constraint must be registered
  there too**.
- `service/` — `PlanningService` (SolverFactory from `solver/solverConfig.xml`,
  loads `scenario.yml` via SnakeYAML), `SolverJobService` (async solve/analyze,
  plus the solver queue — persisted through `SolverJobRepository` and replayed
  at startup, so a restart no longer loses the planned runs),
  `ConstraintAnalysisStore`, `ReferenceDataService` (facade over one service
  per referential family — `StandService`, `AnimateurService`,
  `CreneauService`, …) over one repository per family
  (`StandRepository`, `AnimateurRepository`, …, plus
  `ReferenceDataImportRepository` for the one write that spans all of them),
  `PlanningPersistenceService`, `DatabaseDumpService`,
  `PlanningExportService` (facade over `AnimateurPlanningPdf`,
  `GlobalPlanningPdf` and `PlanningIcs`, sharing `PdfTheme` — server-side only),
  `DemandeEchangeService` / `EspaceAnimateurService` (foire au planning, issue
  #165), `ApplicationLinks` (every public URL printed in a mail or a PDF).
- **Mails follow two opposite failure policies, and the split is structural.**
  `MailService` holds only what an admin explicitly asks for (an animateur's
  planning, an espace access code, the Débogage test mail): the mail *is* the
  operation, so a failure **propagates** and the caller reports who could not
  be reached. Everything best-effort — échange notifications, end-of-solve —
  goes through `service/notification/`: business code fires a `Notification`
  (a sealed interface; `NotificationWriter` switches over it exhaustively,
  so a new case does not compile until its wording exists), and
  `NotificationDispatcher` observes it and owns the single `catch`. Do not add
  a `notifierXxx` to `MailService`: that would put two opposite policies behind
  identically-shaped methods again, which is what this split removed.
- `api/` — JAX-RS resources: `PlanningResource`, `SolverJobResource`,
  `EditionResource`, `ConstraintResource`, `DatabaseResource`,
  `PlanningExportResource`, `EspaceAnimateurResource` (token-authenticated, the
  only public part of the API), `DemandeEchangeResource`, `AuthResource`, plus
  one resource per referential family (`StandResource`, `AnimateurResource`, …)
  and `ReferenceDataResource` for scenario import. A resource holds transport
  only — status codes and payload shapes; anything that decides something
  belongs to a service. Endpoint list in `docs/api.md`.
- HTTP security (issue #165): everything under `/api` requires the admin form
  login (single `admin` account from config) **except**
  `/api/espace-animateur/*` (its URL token is the credential and resolves the
  edition by itself), `/api/auth/*` and `/api/config`; `/mcp` keeps its own
  API-key mechanism. An **opt-in** header mode (`planning.auth.remote-user.*`,
  off by default) lets an access proxy assert an already-authenticated
  address: `admin-email` gets the admin role, any other recognised address is
  an animateur whose espace opens without the e-mail code. It refuses to boot
  without a shared secret — a header is a claim, not a proof. Hardening for an
  Internet-facing deployment — browser security headers
  (`SecurityHeadersFilter`), HTTP limits, the two rate limiters
  (`AdminLoginLimiter` on `/j_security_check`,
  `CodeRequestLimiter` on the espace access codes), the production compose
  stack and what is left to the reverse proxy — lives in `docs/securite.md`;
  a change to any of them belongs there. The `%test`
  profile opens the API (`permit`) so
  functional tests skip the session; `AuthentificationAdminTest` restores and
  covers the real policy.
- `mcp/` — MCP tools (`@Tool`) exposing the same capabilities to an AI
  assistant, delegating to the services above. Two hard rules, both enforced
  reflectively over every tool so a new one cannot opt out by omission:
  1. animateur nom/prénom/dateNaissance never leave over MCP (issue #107) —
     return dedicated view records, never domain objects, and run violation
     messages through `AnonymisationViolations`
     (`McpConfidentialiteStructurelleTest`);
  2. a tool that works inside an edition takes an `edition` argument marked
     `@EditionArg`, on a class annotated `@EditionCiblee` (issue #181) — an MCP
     call carries no `X-Edition-Id`, so without it the tool silently reads and
     writes the default edition (`McpEditionStructurelleTest`). Beware CDI
     self-invocation: a tool calling another tool on `this` bypasses the
     interceptor.

  See `docs/mcp.md`.
- Persistence: PostgreSQL + Flyway migrations in
  `src/main/resources/db/migration/`. Schema change = **new versioned file**;
  never edit an applied migration.
- **Everything is partitioned by `edition`** ("Année 2025", "Année
  2026"). Every business table carries an `edition_id`, business ids have a
  composite `(edition_id, id)` primary key, and the edition a request works in
  comes from its `X-Edition-Id` header via `EditionContext`. A new reference
  table must follow the same convention, and its SQL must go through
  `JdbcEditionScope` — the single helper that binds the edition to the
  statement's first placeholder and owns the transaction dance.
  **The invariant is the helper, not one class.** It used to say "all SQL goes
  through `ReferenceDataRepository`", a 1 600-line class holding eight
  referentials; the referential SQL now lives in one repository per family in
  the `service` package, which keeps the predicate auditable by a `grep` over
  the package instead of over a file. What makes that safe is not the reader's
  diligence: `IsolationEditionStructurelleTest` reads the backend's SQL and
  fails on any business-table statement without an `edition_id` predicate. See
  `docs/editions.md`.
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
- Shell: `app/app.ts` is a bare `<router-outlet/>`; the admin chrome
  (`mat-toolbar` + `mat-sidenav`, navigation grouped in Planning / Reference
  data / Views, the solver `app-job-monitor`, the logout button) lives in
  `app/shell/admin-shell.ts`, a layout route wrapping every admin page. The
  standalone routes `/login` and `/animateur/:jeton` (espace animateur, issue
  #165) render outside it — no admin navigation, no polling.
- **One route = one page = one block.** Admin routes (children of the shell):
  `/` (default, the solver page),
  `/debug`, `/mcp-client`, `/notifications`, `/parametres`, `/stands`, `/emplacements`,
  `/animateurs`, `/creneaux`, `/typologies`,
  `/ad-hoc-constraints`, `/calendar`, `/day-calendar`, `/constraints`,
  `/problemes`, `/echanges`, `/hours`, `/staffing`, `/aide` (`/solver`,
  `/exports`, `/data-transfer`, `/data-setup`, `/decoupage` and
  `/validateur-yaml` are legacy redirects, kept for old bookmarks/links).
  Adding a functional block means adding a route and a `app/pages/<block>/`
  folder, never a new section inside an existing page.
- Layout: `app/core/` holds shared services (`api.service.ts` — the only place
  doing HTTP, `downloadFile` returns a status string and never touches the DOM;
  `models.ts`; `date-utils.ts`, week starts Monday; `planning-state.service.ts`;
  `reference-data.store.ts`; `reference-crud.service.ts` — save/delete, single
  or in bulk, plus snack-bar feedback shared by the five reference pages;
  `table-selection.ts` — multi-row selection of those pages, always intersected
  with the displayed rows; `text-filter.ts` — accent/case-insensitive
  "contains every term" matching behind those pages' quick filter; `bulk-edit.ts` — the "leave unchanged / add / remove
  / replace" modes a bulk edit applies to one row; `entity-labels.ts` — plural
  entity labels of the bulk actions;
  `solver-job.service.ts`; `notification.service.ts`, backed by `MatSnackBar`),
  `app/shared/` holds cross-page components (`job-monitor.ts`,
  `confirm-dialog.ts` — replaces `window.confirm`, `output-panel.ts`,
  `bulk-actions-bar.ts`, `table-filter.ts` — the reference pages' quick-filter
  field, `detail-dialog.ts` — their read-only "consultation" view, whose
  content each page builds in a plain `<entity>-detail.ts` next to it), and `app/pages/<page>/` holds one folder per route.
- Bulk edits go through one dialog per entity (`<entity>-bulk-edit-dialog.ts`),
  whose rules live in a plain `<entity>-bulk-edit.ts` next to it so they are
  unit-tested without rendering. Every field defaults to "ne pas modifier": a
  bulk edit only writes what the user explicitly filled in.
- State flows one way: the solver page pushes the solved planning into
  `PlanningStateService`, calendars read it back read-only and never start a
  solve. Components don't call `fetch` directly.
- The "a solver is running" state is never stored in the browser
  (`localStorage` / `sessionStorage`): `SolverJobService` reads it from the
  server, so a solve started from another browser or a private window also
  locks the buttons here, shows the server-computed elapsed time and delivers
  its result. It reads it **two ways, on purpose**: a server-sent events stream
  (`/api/jobs/stream`, one event carrying the active job *and* the queue) for
  latency, and a polling loop underneath as the safety net. **Do not remove the
  poll** — SSE fails silently (a buffering proxy, a cut that never reconnects)
  and the screen would freeze on a stale state without a word, which is worse
  than polling. The poll keeps the lead until the stream has proved it is alive
  (a `state` or a `heartbeat`), drops to 30 s while it is, and takes the lead
  back — fast pace, immediate refresh — after 45 s of silence. Without the
  stream the loop paces itself as before: every 2 s while a job runs or one is
  queued, every 30 s otherwise. Both are stopped with the shell that started
  them (the service is `providedIn: 'root'` and would outlive it).
- CSS stays **global** and limited to what Material does not cover:
  `src/styles.css` is a thin aggregator of `@import` rules only and the partials
  live in `src/styles/` (`pages.css` for the shared card/form/table scaffolding,
  `feedback.css` for the job monitor and snack-bar variants,
  `calendar-month.css`, `calendar-day.css`, `constraints.css`,
  `problemes.css`), each holding its
  own `@media` rules. Add new styles as new partials; don't recreate a monolithic
  stylesheet and don't restyle what a Material component already themes.
- Keep the frontend dependency-light. What is actually there, and why: Angular
  + its CLI + Angular Material + `@angular/localize` (the stack proper);
  `@sentry/angular` (error reporting, loaded by a dynamic `import()` only when a
  DSN is configured — see `core/observability.ts`); `leaflet` + `@types/leaflet`
  (the emplacement map picker, reached only by the lazy `/emplacements` route).
  Dev-only: `@playwright/test`, `vitest`, `@vitest/coverage-v8`
  (`npm run test:coverage`, run in CI — the report is published as an artifact
  and nothing consumes it: no threshold, no external service), `jsdom`,
  `prettier`, `typescript`,
  `angular-eslint` + `eslint` + `typescript-eslint` (`npm run lint`, run in CI —
  it is what mechanically defends the conventions of this section: OnPush,
  function-based `input()`/`output()`, `@for` with a `track`, no `any`).
  The rule is an intent, not that list: **no state-management library, no second
  UI kit, no CSS framework, no third-party i18n library** (`ngx-translate`,
  `transloco`, …) — and any other addition, runtime or dev, needs explicit
  sign-off. Keep this list correct when it changes: a rule that describes a
  false state stops being obeyed.
- Frontend tests are Vitest specs (`*.spec.ts` next to the code, `TestBed` for
  anything DI/rendering, `provideZonelessChangeDetection()` since the app is
  zoneless). They run via `npm test` in a Node/jsdom environment — no browser,
  not wired into the Maven `%test` phase (Quinoa stays disabled there), run by a
  dedicated CI job. Favour testing `core/` logic (services with a mocked
  `ApiService`, pure helpers) over heavy component-rendering tests.
- End-to-end tests are Playwright specs in `src/main/webui/e2e` (`npm run
  e2e`): the issue #165 security perimeter (auth wall, espace animateur
  boundary, full échange flow with refusal and cancellation), a smoke sweep of
  every admin route plus the language toggle, reference-data CRUD through the
  UI, the planning views over seeded data, locks, the help page, **real short
  solves** (ad hoc constraints respected and visible, a locked animateur
  unchanged by a re-solve, an accepted échange surviving regeneration) and
  **seeded invariant fuzzing** (random referentials solved for real, replayed
  with `E2E_FUZZ_SEED`). **Deliberately excluded from CI**: they need the full
  stack and write to the database — run them only against a disposable local
  stack (see `docs/developpement.md` § Tests de bout en bout).

## Domain invariants (never break these)

- `PosteAffectation` is generated **per seat** (respecting
  `Stand.effectifMin/Max`) at scenario/seed time; effectif min/max is enforced by
  counting non-null `animateur` assignments grouped by stand+créneau, not by a
  dedicated constraint class.
- Minor/adult status (`isMineurOn(LocalDate)` / `isMajeurOn(LocalDate)`) is
  always derived from `dateNaissance` at the créneau's date — never stored as a
  boolean flag.
- Availability is opt-out: an animateur is available unless the date is listed in
  `joursIndisponibles` (`isIndisponibleOn(LocalDate)`).
- `ContrainteAdHoc`'s prescriptive types (`INDISPONIBILITE_FORCEE`,
  `INCOMPATIBILITE`, `AFFECTATION_FORCEE`) are evaluated as `HardScore`, at the
  same priority as legal/minor hard constraints — never demote these to
  medium/soft. `AFFINITE` (issue #80) is the one deliberate exception: a soft
  *reward* for co-assigning a preferred pair on the same stand — never promote
  it to hard (that would be a forced assignment in disguise).
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

- Code and comments in English, domain names in French business vocabulary —
  the glossary and the test that enforces it are in *Language of the code*
  below.
- **Public links go through `ApplicationLinks`, never through concatenation.**
  URLs printed outside the app (notification mails, individual PDFs) target
  **Angular SPA routes** (`src/main/webui/src/app/app.routes.ts`), not JAX-RS
  paths: the API is mounted under `quarkus.rest.path=/api`, so
  `UriBuilder.fromResource(...)` would yield `/api/echanges` — the JSON
  endpoint instead of the screen — and two of the three links have no resource
  to derive from. One method per link there; renaming an Angular route means
  renaming it there too.
- **A refused request throws `BusinessError`; a resource never writes a
  `try/catch` for it.** The sealed hierarchy (`Invalid` → 400, `NotFound`
  → 404, `Conflict` → 409) carries the status, and `BusinessErrorMapper`
  switches over it exhaustively. It extends `IllegalArgumentException`, so a
  plain one — thrown by a library, or by nobody on purpose — still falls
  through to its 500 and its Sentry alert; that difference is the point.
  Choose the variant by where the id came from: a path segment that names
  nothing is `NotFound`, a body field that names nothing is `Invalid`.
- **Inject `ObjectMapper`, never `new ObjectMapper()`** — a bare one lacks the
  modules Quarkus registers (JSR-310), which forces records to flatten
  `LocalDate`/`Instant` fields to `String` and only fails at write time.
- Write/extend a test proving no hard constraint is violated before considering a
  step done.
- No additional frontend dependency (UI kit, state library, CSS framework, i18n
  library) without explicit sign-off — plain Angular served by Quinoa is a
  deliberate choice. The dependencies that *are* present, and the reason for
  each, are listed in the Frontend section above.

## Language of the code

Three rules, and a glossary that settles what "domain vocabulary" means. The
first is checked by `LanguagePolicyStructuralTest`, which reads the backend
sources and fails on a French comment block or a French declared name. The
other two are conventions this file owns — and the test only catches the most
visible half of the third, a French word that carries an accent.

1. **Prose is English, business names are French.** A verb is always English
   (`construire` → `build`, `verifier` → `check`), a common noun is always
   English (`cible` → `target`, `charte` → `theme`), word order is always
   English (`PlanningPdfGlobal` → `GlobalPlanningPdf`). Only the words of the
   glossary below stay French, because they name a row of the staffing workbook or
   a legal notion. `listCreneaux()`, `getAnimateur()` and `animateurId` are
   therefore already correct and must not be "fixed".
2. **Touch a test, rename its method to English.** The 600-odd French test
   method names are deliberately left alone — they are read in a surefire
   report and nowhere else — but a test whose body you change leaves with an
   English name. The debt shrinks where the work happens instead of being
   paid in one indigestible commit.
3. **The glossary below is the vocabulary of the English prose.** Writing
   "créneau" in a javadoc is as wrong as writing `verifierHoraire`: the prose
   says *timeslot* while the code says `Creneau`, and that pairing is what
   makes the two readable together.

| Java / SQL | English prose | Note |
| --- | --- | --- |
| `Animateur` | *animateur* | **not translated** — "volunteer", "staff" and "instructor" each drop something the French word carries, and the minor/adult regime hangs on it |
| `Stand` | *stand* | same word in both languages |
| `Creneau` | *timeslot* | |
| `PosteAffectation` | *seat* | one instance per seat to fill, never "assignment slot" |
| `Emplacement` | *location* | the physical place a stand sits on |
| `VerrouillagePlanning` | *lock* | |
| `DemandeEchange` | *swap request* | |
| `Typologie` | *game category* | a CRUD referential, not an enum |
| `Horaire` | *opening hours* | |
| `Decoupage` | *slicing* | |
| `Vacation` | ***shift*** | false friend: English *vacation* means holidays |
| `Amplitude` | ***opening span*** | false friend: English *amplitude* is about oscillations |

The last two matter more than they look. `Vacation` and `Amplitude` are the
words of the staffing workbook and stay in the code, but an English sentence that
uses them bare says something else entirely — "the animateur works two
vacations" reads as two holidays. Write *shift* and *opening span* in the
prose, keep `Vacation` and `Amplitude` in the identifiers.

**One deliberate mismatch, and it is documented where it shows.** The Java code
says `token`; the SQL column is still `jeton_acces` and the JSON key still
`jetonAcces`, because both are read outside this repository (the Angular
`models.ts`, every MCP client, the espace links already printed on PDFs).
The gap is flagged in the JDBC mapping and on the DTO that carry it; aligning
the whole chain needs a Flyway migration and a frontend release, which is its
own issue.
