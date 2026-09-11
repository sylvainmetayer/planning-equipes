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
renamed between source and translation — and `npm run i18n-check-modifies --
origin/main` (same job, a ratchet against the base branch) fails on a French
source rewritten under its id while its English string stayed put, which none
of the three catches. Never call `$localize` at module scope
(only from a method, a `computed()`, or a constructor): it must run after
`main.ts` has loaded translations, not at import time. See
`docs/developpement.md` for the full workflow. Code comments and non-domain identifiers stay in English.

A route `title` is a **function** returning a `$localize` string, never a
literal: evaluated after bootstrap, it is seen by `ng extract-i18n` and counted
by `i18n-check`, and the page name `admin-shell` announces to screen readers is
then in the reader's language. `app.routes.spec.ts` refuses a literal.

## Project overview

Quarkus + Timefold Solver application that schedules ~150 `Animateur`s (staff)
onto `Stand`s over the days of an event, under hard/medium/soft constraints.

Read before working on constraints or the domain model:

- `README.md` (section *Fonctionnalités métier*) — functional scope and business capabilities
- `docs/domaine.md` — Timefold model and its invariants
- `docs/contraintes.md` — why each rule exists and what it costs (the list itself is `ConstraintCatalog`)
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

`PlanningServiceScenarioCompletTest`, `PlanningServiceScenarioContinuTest`,
`PlanningServiceScenarioFestivalRealisteTest` and
`PlanningServiceScenarioFestivalHivernalTest` solve large scenarios to
hard-feasibility — the first two take ~25s/~75s on hand-built problems, the
last two run the **anonymised real-world fixtures**
(`festival-realiste-canicule.yaml`, sliced by the découpage, and
`festival-hivernal.yaml`, the same event on the organiser's own grid: 153
animateurs, 65 stands, 45 premium, per-stand recurring schedules) — the only ones whose
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
- Formatting is decided by tools, not in review (issue #392, C10). Java:
  `spotless-maven-plugin` runs palantir-java-format, removes unused imports and
  orders the rest; `spotless:check` is bound to `validate`, so every
  `./mvnw test|package|verify` refuses an unformatted file — run
  `./mvnw spotless:apply`. Frontend: `npm run format` (prettier, the
  repository's `.prettierrc`) on the TypeScript, CSS and scripts, checked by
  `npm run format-check` in CI; the HTML templates are excluded — the
  `.html` files by `.prettierignore`, the inline `template:` literals by
  `embeddedLanguageFormatting: off` — since a blank between two inline
  elements is rendering there. The two initial reformatting commits are
  listed in `.git-blame-ignore-revs`.
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
  swap proposals, issue #165), `PlanningEvenement` (`@PlanningSolution`,
  `HardMediumSoftScore`).
- `solver/PlanningConstraintProvider.java` — aggregates constraints; the actual
  rules live in `solver/constraints/` split by family (`AffectationConstraints`,
  `LegalConstraints`, `AdHocConstraints`, `QualiteConstraints`,
  `PreferenceConstraints`), one private method per constraint.
- `solver/ConstraintCatalog.java` — business description of every constraint,
  served by `GET /api/constraints`; **every new constraint must be registered
  there too**. It also carries the two indexes the rest of the application
  reads it through — `PAR_NOM` and `NOMS_DURS` — so nothing outside `solver/`
  rebuilds them.
- `service/` — one subpackage per capability (issue #392, A4), and a root that
  only holds what they all share: `BusinessError`, `Ids`, `NaturalOrder`,
  `WriteStamp`, `ProductName`, `JdbcEditionScope`, `EditionContext`,
  `EditionRequestScope`, `TokenOwner`, `ConcurrentModificationGuard`,
  `ReferenceDataChangeTracker`. A class lives with the question it answers; a
  member another package needs is public, and its name is then English (the
  language policy reads public names only). The packages:
  - `service/solve/` — `PlanningService` (a façade over the classes next to
    it: its constructor builds `SolverConfiguration`, `SolveRunner`,
    `ProblemBuilder`, `PlanningWhatIf` and `PlanningDiagnosticService` from the
    beans it receives there — no field injection, no lazy supplier — and
    delegates; the scenario entry points it keeps are the only ones with a
    body of their own), `SolverConfiguration` (the `SolverFactory` from
    `solver/solverConfig.xml`, termination, constraint weights), `SolveRunner`
    (the solve, and the server-side preparation that always overwrites what a
    caller sent), `PlanningWhatIf` (everything the application answers about a
    plan without solving it again — explanation, repair, échange,
    availability; still four responsibilities in one class), `ProblemBuilder`
    (the edition's reference data turned into a problem to solve — from
    scratch, réamorcé (#174) or incremental (#86); the pieces needing no
    database stay static, which is what lets the PosteGeneration / Reamorcage /
    Incremental / Verrouillage tests run without a container),
    `SolverJobService` (async solves, plus the solver queue — persisted through
    `SolverJobRepository` and replayed at startup, so a restart no longer loses
    the planned runs; `SolverJobTasks` and `SolverJobPersistence` carry what
    does not hold the lock), `ConstraintAnalysisStore` (the score breakdown the
    Contraintes screen shows, written by every solve and re-derivable from the
    persisted plan alone — **nothing analyses by solving a plan it then throws
    away**), `PlanningPersistenceService`, `PlanSnapshotService`,
    `SnapshotComparisonService`, `DeplacementService`.
  - `service/analyse/` — what is read from a plan without solving it:
    `PlanningDiagnosticService` (score, unfilled seats, per-constraint
    breakdown), the analyzers (feasibility, fragilité, ouvertures, pauses,
    staffing), `PlanningKpiService` / `KpiHistoriqueService`,
    `PlanningHoursService`, `AlerteService`.
  - `service/referentiel/` — `ReferenceDataService` (facade over one service
    per referential family — `StandService`, `AnimateurService`,
    `CreneauService`, … — over one repository per family, plus
    `ReferenceDataImportRepository` for the one write that spans all of them),
    the validators, the grids (`CreneauGridService`, `GrilleHorairesStands`,
    `GrilleDepuisFenetres`), coherence, reference usage, and the CSV and grid
    imports.
  - `service/scenario/` — `ScenarioYamlReader` / `ScenarioYamlWriter` (the
    scenario file format, both pure and static, so their tests exercise reading
    and writing without a database; `PlanningServiceScenarioAllerRetourTest`
    keeps them in step), `ScenarioDomainMapper`, `ScenarioDtoAssembler`,
    `ScenarioImportService`. The DTO itself is `scenario/` at the package root.
  - `service/edition/`; `service/publication/` (publishing, delivery,
    confirmations, `MailService`); `service/export/` (`PlanningExportService`
    over `AnimateurPlanningPdf`, `GlobalPlanningPdf` and `PlanningIcs`, sharing
    `PdfTheme` — server-side only; `DatabaseDumpService`); `service/espace/`
    (`DemandeEchangeService` / `EspaceAnimateurService`, the foire au planning
    of issue #165; `ApplicationLinks`, every public URL printed in a mail or a
    PDF); and the ones that predate the split — `service/diagnostic/`,
    `service/journal/`, `service/mail/`, `service/notification/`,
    `service/backup/` (nightly `pg_dump` — see below).
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
- **A mail's wording lives in a Qute template, never in Java.** One pair per
  mail under `src/main/resources/templates/mail/` — `<name>.txt` and
  `<name>.html`, the latter built on the shared `layout.html` (deployment logo,
  palette, product name). `service/mail/MailTemplates` renders both parts and
  assembles the `Mail`: the text part is **always** sent, the HTML one is an
  alternative, and the logo travels as an inline `cid:` attachment — never a
  remote `<img src>`, which mail clients block and spam filters penalise. It
  comes from `BRANDING_PDF_LOGO` (bytes the application holds), not
  `BRANDING_LOGO_URL` (a URL only a browser can resolve). Java names the
  template, decides the subject and passes values; adding a mail means adding
  the two files. Unit tests keep asserting on the wording without a container
  through `MailTemplates.standalone()`, which builds the same engine over the
  same classpath templates.
- **Every action is journalled, and a new entry point cannot opt out by
  omission** (issue #406). `service/journal/CatalogueActions` is the business
  inventory of what the application does — the same role `ConstraintCatalog`
  plays for the solver's rules — and it is read by three seams, because there
  is no single one: `api/JournalActionFilter` (a response filter, so a refusal
  is recorded too), `mcp/JournalOutilInterceptor` — bound to `@Journalise`,
  **its own** binding, because the cross-edition tool classes carry no
  `@EditionCiblee` and reusing it left six write tools uncovered — and
  `recordSystemAction` from the nightly job.
  `JournalCoverageStructurelleTest` fails on any write route or write tool that
  is in neither the catalogue nor its **argued** exclusion list, on a tool class
  missing `@Journalise`, and on a catalogue entry nothing can reach — a `POST`
  that only computes is not an action, and saying so with a reason is what keeps
  the list honest. Two things never enter the table: a **value** (an edit records
  the field *names* it changed, from the before-image
  `ReferenceDataService` already reads) and an **identity** (an id only; the
  name is joined at read time, so a deleted fiche leaves a line naming nobody).
  Adding a route means adding its line to the catalogue.
- **Never call `SolutionManager.analyze()` again.** Breaking a score down per
  constraint goes through `service/diagnostic/`
  (`ConstraintDiagnosticService` → `PlanningAnalysis`), and nothing else. That
  method is Enterprise-only from Timefold 2.x, and it used to be called from a
  dozen places — including after every solve, which turned a missing licence
  into "no solve ever completes" rather than "one screen is empty". The default
  implementation reaches one layer lower, into the score director, which needs
  no licence; the `analyze()` one is kept as the oracle
  `ConstraintDiagnosticServiceContractTest` compares against. That test is the
  only thing making a dependency on `ai.timefold.solver.core.impl` tenable —
  **run it on every Timefold bump**, and read its result rather than the build's:
  on a Community 2.x build the oracle is gated, so the test reports itself
  **skipped** instead of failing, and a green build then proves nothing about
  the diagnostic. A licence restores the comparison with no configuration
  change. See `docs/decisions/0013-diagnostic-par-le-score-director.md` and
  `docs/migration-timefold-2.md`. The three move filters
  (`EligibleAnimateurMoveFilter`, `HoleNeighbourPosteFilter`,
  `UnassignedPosteFilter`) and the move factory
  (`WeekRelocationMoveIteratorFactory`) depend on `core.impl` too — the
  filter and factory SPIs only exist there, the moves themselves are the
  public preview API — with a different net: a bump that reshapes the types
  does not compile, and one that silently stops asking them shows up in
  `-Pscenario-tests` as a solve that no longer converges.
  `TimefoldInternalApiStructuralTest` keeps the inventory: a new file
  importing `core.impl` fails the build until it names what will catch its
  next break.
- `api/` — JAX-RS resources: `PlanningResource`, `SolverJobResource`,
  `EditionResource`, `ConstraintResource`, `DatabaseResource`,
  `PlanningExportResource`, `EspaceAnimateurResource` and
  `AbonnementIcsResource` (token-authenticated, the only public parts of the
  API), `DemandeEchangeResource`, `AuthResource`, plus
  one resource per referential family (`StandResource`, `AnimateurResource`, …)
  and `ReferenceDataResource` for scenario import. A resource holds transport
  only — status codes and payload shapes; anything that decides something
  belongs to a service. The scenario import is the worked example: the order
  its sections are applied in lives in `ScenarioImportService`, and
  `ReferenceDataResource` is left turning the outcome into a body. The endpoint
  list is the published OpenAPI.
- HTTP security (issue #165): everything under `/api` requires the admin form
  login (single `admin` account from config) **except**
  `/api/espace-animateur/*` (its URL token is the credential and resolves the
  edition by itself), `/api/abonnements/*`, `/api/auth/*` and `/api/config`;
  `/mcp` keeps its own API-key mechanism.
  **`/api/abonnements/{token}/planning.ics` is the one route a URL alone
  opens** (issue #324): a calendar client subscribed to a feed carries no
  cookie and cannot answer a challenge, so the espace's e-mail-code session is
  out of reach there. It therefore uses a **second, separate token**
  (`animateur.abonnement_token`, rotated from the espace itself) and lives
  under a prefix of its own — that prefix is what an access proxy excepts from
  its authentication, and it must keep naming nothing else. Do not widen it,
  and do not bind `@AbonnementTokenRequired` to a second route: the perimeter
  of that token is "one document, read-only", and it is readable only as long
  as one route carries it. See
  `docs/decisions/0019-jeton-et-chemin-dedies-pour-l-abonnement-ics.md`. An **opt-in** header mode (`planning.auth.remote-user.*`,
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
  assistant, delegating to the services above — **to the services, never to a
  resource**: MCP and REST are two callers of the same rules, and a tool that
  injects a resource can only read a business outcome through a JAX-RS
  `Response` it never actually received over HTTP
  (`LayeringStructuralTest`). Three hard rules, all enforced over every tool so
  a new one cannot opt out by omission:
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
  3. every tool declares the four MCP hints, and only the tools that **send
     mail** (`publier_planning`, `envoyer_planning_animateur`,
     `configurer_collecte_disponibilites`, `relancer_animateurs`) may declare
     `openWorldHint = true` — they are enumerated in
     `McpAnnotationsStructurelleTest`, which also derives the other hints from
     the tool's own name.

  See `docs/mcp.md`.
- **`service/backup/` is the only place the application writes to disk**: a
  nightly `pg_dump` of the whole cluster into
  `BACKUP_DIR`, keeping the `BACKUP_RETENTION` most recent copies. Three rules
  it is built on, and none of them is an oversight to fix later —
  **restoring is out of scope** (an infrastructure operation with
  `pg_restore`; a "restore everything" button would be the very gesture the
  backup exists to undo), the dumps are **never downloadable** (they carry
  every animateur's name, birth date and e-mail, minors included), and the
  destination and retention are **environment variables**, not screen
  settings — the one thing the Paramètres screen writes is the boolean that
  *suspends* the run. A failure is recorded and shown rather than propagated.
  `PgDump` is a bean of its own solely so the suite can replace it: nothing in
  a test may shell out to that binary, whose presence and version belong to the
  machine. The client is pinned to `postgresql-client-18` in the runtime image
  and must move with the `postgres:` image of the production stack. See
  `docs/decisions/0015-sauvegarde-par-pg-dump-restauration-hors-application.md`.
- **`service/notification/NotificationsPlanifieesService` is the application's
  second (and last) `@Scheduled`**, and the single entry point of the three
  nightly sends — day-before reminder, reminder of the unconfirmed, alert on
  stale swap requests. It follows the `backup` conventions (configurable cron
  and zone, `SKIP` on overlap, failure logged rather than propagated) and adds
  two rules of its own. It **loops over editions**, entering each through
  `EditionContext.executeIn` — there is no `X-Edition-Id` on a scheduler
  thread — and nothing leaves an edition that has not been armed explicitly in
  `parametres_notifications`: an `Edition` carries neither dates nor an
  "ongoing" flag, so that boolean is the only thing between the job and last
  year's volunteers. And **idempotence is claimed, not checked**:
  `JournalNotificationsRepository` takes a `(edition_id, type, cle)` key with
  `INSERT … ON CONFLICT DO NOTHING` and the message goes out only for the call
  that won the insert, so an hourly cron writes to nobody twice. Reading then
  acting would let two runs both read "not yet".
- Persistence: PostgreSQL + Flyway migrations in
  `src/main/resources/db/migration/`. Schema change = **new versioned file**;
  never edit an applied migration. `FlywayMigrationsFrozenTest` holds both
  halves: a new file needs its line in
  `src/test/resources/migrations-empreintes.txt` (the failure prints it), and
  an applied file whose fingerprint moved is refused.
- **Every referential row carries `modifie_le`** (issue #362), and every write
  of one carries its own precondition: the `ON CONFLICT DO UPDATE` clause
  compares the caller's `modifieLe` with the stored one and returns no row when
  they differ, which is the `409`. A new referential table must add the column,
  write the same clause, and bind it through `WriteStamp.bindPrecondition`;
  `WriteStampStructurelleTest` fails on a table that has neither. A read before
  the write would not do: two saves a millisecond apart would both pass.
- **Everything is partitioned by `edition`** ("Année 2025", "Année
  2026"). Every business table carries an `edition_id`, business ids have a
  composite `(edition_id, id)` primary key, and the edition a request works in
  comes from its `X-Edition-Id` header via `EditionContext`. **Off a request,
  `EditionContext` throws rather than guess**: work that outlives its request —
  a solver job, the notification scheduler — must name its edition through
  `executeIn`, because answering "the default one" to a write on a thread that
  designated nothing is how one edition's data ends up in another. Inside a
  request the default fallback stays: an absent or stale `X-Edition-Id` is the
  ordinary case. An MCP call is *not* one of those: it is served inside a
  request context activated by the extension, and a tool called without an
  `edition` argument resolves to the default edition on purpose — see
  `docs/mcp.md`. A new reference
  table must follow the same convention, and its SQL must go through
  `JdbcEditionScope` — the single helper that binds the edition to the
  statement's first placeholder and owns the transaction dance.
  **The invariant is the helper, not one class.** It used to say "all SQL goes
  through `ReferenceDataRepository`", a 1 600-line class holding eight
  referentials; the referential SQL now lives in one repository per family in
  the `service` package, which keeps the predicate auditable by a `grep` over
  the package instead of over a file. What makes that safe is not the reader's
  diligence: `IsolationEditionStructurelleTest` reads the backend's SQL and
  fails on any business-table statement without an `edition_id` predicate. It
  **resolves the statement through indirection** — a `static final String`
  constant, a local variable, a concatenation of those, the SQL parameter of a
  private helper — so moving the SQL into a variable first hides nothing from
  it. A form it cannot follow (`StringBuilder`, `String.join`, a constant of
  another class, a literal followed by a method call, a variable reassigned
  after its declaration) **fails the test** unless it is named in
  `INDIRECTIONS_ASSUMEES` with a written reason, and every table **named by a
  prepared statement the scan resolves** must be declared either partitioned
  (`TABLES_METIER`) or global with its motive (`TABLES_HORS_EDITION`). That net
  is narrower than "every table": SQL run through a plain `Statement`
  (`DatabaseDumpService`, the temporary table of `EditionRepository`) is not an
  anchor, and a statement whose table name is itself a variable has no name to
  classify. See
  `docs/decisions/0001-cloisonnement-par-edition.md`.
- Solver tuning: `planning.solver.seconds-limit` /
  `planning.solver.unimproved-seconds-limit` in `application.properties`.

### Frontend

Angular 22 application in `src/main/webui`, built during `mvn package` and served
as Quarkus static resources by the **Quinoa** extension (`quarkus.quinoa.*` in
`application.properties`). Single deployment, no Node server in production; in
`quarkus:dev` Quinoa runs `ng serve` and proxies it on port 8080.

- Standalone components only (no `NgModule`), `signal()` / `computed()` for
  state, new control flow (`@if` / `@for`) in templates, lazy-loaded routes in
  `app/app.routes.ts`. A read-only screen loads through `resource()` — the
  loading and error states are the resource's, not hand-written signals, and a
  superseded request is discarded by it; `core/resource-state.ts` keeps the
  last good value on screen across a failed reload and words the failure.
  The stores and services of `core/` expose **read-only signals** only
  (`private readonly _x = signal(…)` and `readonly x = this._x.asReadonly()`):
  a page reads, the store writes, and a write from outside goes through a
  method of the store. `scripts/check-readonly-stores.js` (`npm run
  stores-check`, run in CI) refuses a public writable signal in `core/`; a
  spec that needs a store holding fixtures seeds it through
  `core/testing/seed-store.ts`, never by writing the signal.
- UI built with **Angular Material** (Material Design 3). The theme lives in
  `src/material-theme.scss` (`mat.theme()`, azure/blue palettes, Roboto); use the
  `--mat-sys-*` tokens in custom CSS instead of hard-coded colours. The app has
  no `@angular/animations` dependency: Material components animate through CSS,
  so don't add `provideAnimations*()` back.
- **Light and dark come from the same `mat.theme()` call** (issue #317): with no
  explicit `theme-type` the mixin emits every `--mat-sys-*` colour as a
  `light-dark(light, dark)` pair, and the CSS `color-scheme` of `<html>` picks
  the half. `core/theme-preference.ts` writes it (`light dark` = follow the
  machine, the default; `light`; `dark`), `core/theme.service.ts` exposes it as
  signals to the toolbar's three-state button, and `main.ts` applies it
  synchronously before bootstrap so no frame paints the wrong scheme. There is
  **no second theme block and no parallel stylesheet** — which is exactly why a
  hard-coded colour in `src/styles/` is now a dark-mode bug, not a nit. Two
  colours are not `mat.theme()`'s to switch and carry their own `light-dark()`
  pairs: `styles/typologie-colors.css` (a categorical palette has no
  `--mat-sys-*` equivalent) and `--app-accent` once `BRANDING_ACCENT_COLOR`
  fills it — `core/branding.ts` writes it as a pair, the configured colour on
  light and an OKLCH-lightened twin on dark, because it is a *text* colour on
  `--mat-sys-surface` in a dozen partials. Deliberately outside the switch: the
  brand toolbar, the OpenStreetMap tiles of `/emplacements`, and the
  server-side PDFs — see `docs/architecture.md`.
- Shell: `app/app.ts` is a bare `<router-outlet/>`; the admin chrome
  (`mat-toolbar` + `mat-sidenav`, navigation grouped in Planning / Pendant
  l'événement / Aide à la décision / Reference data / Views / Tools, the
  solver `app-job-monitor`, the logout button) lives in
  `app/shell/admin-shell.ts`, a layout route wrapping every admin page. The
  drawer has two modes, `simple` (the default) and `avance`: an entry flagged
  `avance` in `shell/nav-groups.ts` is listed only in the second, or while its
  route is the one on screen — the list of expert screens is that flag, never
  a second list, and the mode is a chrome preference (`core/nav-mode`), so it
  changes nothing about what a URL, the palette or a help link can reach. The
  standalone routes `/login` and `/animateur/:jeton` (espace animateur, issue
  #165) render outside it — no admin navigation, no polling. The espace has
  four child routes of its own: `/animateur/:jeton` (« Mon planning »),
  `/animateur/:jeton/echanges`, `/animateur/:jeton/disponibilites` and
  `/animateur/:jeton/aide`.
- **One route = one page = one block.** Admin routes (children of the shell):
  `/` (default, the solver page),
  `/debug`, `/mcp-client`, `/notifications`, `/parametres`, `/stands`, `/emplacements`,
  `/animateurs`, `/competences` (« Compétences » — the animateur × typologie
  grid of appreciations, saved row by row, exported and imported as a CSV),
  `/import-animateurs`, `/creneaux`, `/typologies`,
  `/ad-hoc-constraints` (« Ajustements manuels » on screen — the route, the API
  path and the domain type keep the `ContrainteAdHoc` name, only the label was
  renamed), `/calendar`, `/day-calendar`, `/constraints`,
  `/problemes`, `/echanges`, `/hours`, `/equite` (« Équité » — one line per
  assigned animateur: evening, week-end and holiday hours, demanding seats,
  variety, honoured wishes, rest days, each with its distance to the median),
  `/repos` (« Jours de repos » — the
  animateur x day grid: who works, who rests, who was unavailable),
  `/staffing`, `/jour-j` (« Mode jour J » —
  the day-of screen: mark somebody absent, repair the seats they held),
  `/banc-de-touche`, `/carte-jour` (« Carte de la journée » — the day replayed
  on the emplacement map, one time cursor), `/rail-jour` (« Rail de la
  journée »), `/timeline` (« Timeline animateur »), `/heatmap` (« Heatmap de
  charge »), `/fragilite` (« Fragilité du planning »), `/kpi`, `/comparateur`
  (« Comparateur A/B » of two snapshots), `/instantanes` (« Instantanés »),
  `/verrouillages`, `/ouvertures` (« Ouvertures des stands »),
  `/import-grille-stands`, `/pauses`, `/disponibilites` (what the animateurs
  declared), `/editions`, `/historique` (« Historique des actions »), the
  three public legal pages `/mentions-legales`, `/conditions-utilisation`,
  `/politique-confidentialite`, and `/aide` (`/solver`,
  `/exports`, `/data-transfer`, `/data-setup`, `/decoupage` and
  `/validateur-yaml` are legacy redirects, kept for old bookmarks/links).
  Adding a functional block means adding a route and a `app/pages/<block>/`
  folder, never a new section inside an existing page.
- Layout: `app/core/` holds shared services (`api.service.ts` — the only place
  touching `HttpClient`: the verbs, the error mapping, `downloadFile` returning
  a status string and never touching the DOM; `core/api/` — one service per
  resource, owning the `/api/…` paths and the return types, so a page asks for
  the thing and never writes an address — `scripts/check-api-paths.js` fails
  on any literal outside `core/api/`, in `pages/`, `shell/`, `shared/` and
  `core/` alike; its exception list names the `core/` files still carrying
  one, and can only shrink;
  `scripts/check-api-contract.js` (`npm run api-contract-check`, run in CI)
  confronts every address of `core/api/` — path, verb, query parameters — to
  the OpenAPI contract, and refuses a URL it cannot resolve statically;
  `models.ts`; `date-utils.ts`, week starts Monday; `planning-state.service.ts`;
  `reference-data.store.ts`; `reference-crud.service.ts` — save/delete, single
  or in bulk, plus snack-bar feedback shared by the five reference pages;
  `reference-table-page.ts` — the shared half of a referential page: filter,
  selection keyed on the filtered rows, roving tabindex, reload,
  detail-then-edit, the two deletes. `typologies`, `emplacements` and `stands`
  extend it and keep only a `ReferenceTableConfig` plus what they do
  differently; TypeScript only, the templates stay per page since that is what
  genuinely differs. `animateurs` stays out because it keys its selection on the
  **sorted** rows rather than the filtered ones, `creneaux` because it generates
  and groups its own;
  `table-selection.ts` — multi-row selection of those pages, always intersected
  with the displayed rows; `table-navigation.ts` — their keyboard navigation
  (roving tabindex over the rows: arrows, Home/End, Enter to open, Space to
  tick), local to a table and never a `document` listener. **The way in is the
  quick filter**: arrow down from `table-filter.ts` calls `focusCurrent()` on
  the page's navigation. A roving tabindex is invisible — the row carrying
  `tabindex="0"` sits behind the "select all" checkbox and one stop per
  sortable header, so the Tab count changes whenever a column gains a sort, and
  a feature reached by guessing that count does not exist. Keep that entry
  working, and keep the E2E assertion on the count;
  `text-filter.ts` — accent/case-insensitive
  "contains every term" matching behind those pages' quick filter; `bulk-edit.ts` — the "leave unchanged / add / remove
  / replace" modes a bulk edit applies to one row; `entity-labels.ts` — plural
  entity labels of the bulk actions;
  `solver-job.service.ts` — the poll and the job state, over
  `solver-stream.ts` (the SSE connection, its silence watchdog and its
  backoff) and `score-trace.ts` (the pure splice of score deltas);
  `notification.service.ts`, backed by `MatSnackBar`;
  `keyboard-shortcuts.ts` + `keyboard-shortcuts.service.ts` — the application's
  **only** global `keydown`, armed with the admin shell, see below),
  `app/shared/` holds cross-page components (`job-monitor.ts`,
  `confirm-dialog.ts` — replaces `window.confirm`, `output-panel.ts`,
  `help-blocks.ts` — the one block model and renderer of the two user guides
  (`pages/aide/content/*` and `espace-aide-content.ts` only choose their sections),
  `bulk-actions-bar.ts`, `table-filter.ts` — the reference pages' quick-filter
  field, `detail-dialog.ts` — their read-only "consultation" view, whose
  content each page builds in a plain `<entity>-detail.ts` next to it,
  `command-palette-dialog.ts` and `keyboard-shortcuts-dialog.ts` — Ctrl+K and
  `?`, `sort-header-name.ts` — the one directive: a `mat-sort-header` holding a
  control must name itself, or the generated sort button borrows that control's
  `aria-label`), and `app/pages/<page>/` holds one folder per route.
- **One global keyboard listener, and it already exists.** Ctrl+K (command
  palette), `g`+letter (navigation), `/` (the page's filter, marked by
  `data-page-filter`), `?` (the shortcut list) and Ctrl+Enter (submit the
  active form) all go through `core/keyboard-shortcuts.service.ts`; its
  destinations are derived from `app.routes.ts`, so a new route is reachable
  by keyboard without being registered anywhere. Single-key shortcuts are
  suppressed while the focus is in a text entry — modifier combinations are
  not, since Ctrl+Enter is meant to be pressed from inside a field. Escape is
  deliberately not implemented: no dialog sets `disableClose`, so `MatDialog`
  already closes the topmost one. Do not add a second `document`-level
  `keydown`; the Konami easter egg of the shell is the one accepted exception.
  Arrow navigation inside a widget — the calendars, the heatmap, the reference
  tables through `core/table-navigation.ts` — is *not* an exception: it is
  bound to that widget's own element, consumes only the keys it uses, and
  leaves everything else travelling up to the global listener, which stops at a
  `defaultPrevented` event. Add a keyboard behaviour that way, never with a
  second `document` listener.
- Bulk edits go through one dialog per entity (`<entity>-bulk-edit-dialog.ts`),
  whose rules live in a plain `<entity>-bulk-edit.ts` next to it so they are
  unit-tested without rendering. Every field defaults to "ne pas modifier": a
  bulk edit only writes what the user explicitly filled in.
- State flows one way: the solver page pushes the solved planning into
  `PlanningStateService`, calendars read it back read-only and never start a
  solve. Components don't call `fetch` directly.
- **View state — a sort, a quick filter, a chosen view — goes in the URL**, via
  `core/view-query-params.ts`: a refresh restores the screen and the link is
  shareable, with no schema and no browser storage. A **chrome preference** is
  not view state and does not go there — how much room a panel is allowed on
  *this* person's screen is neither shareable nor worth a param, and a param
  would be gone on the next plain navigation, which is the visit the preference
  has to survive. Those go to localStorage, through `core/nav-collapse` (the
  drawer's folded groups), `core/nav-mode` (simple / avancé) or
  `core/panel-collapse` (a page panel, e.g. the
  solver's score curve). The default of a control is
  the *absence* of its param, reading is tolerant (an unknown value falls back
  to the default rather than failing the page), writing replaces the history
  entry through `Location.replaceState` and **never navigates** — a router
  navigation per keystroke costs the filter field its focus — and
  every such screen carries a one-action reset. See
  `docs/decisions/0012-etat-de-vue-dans-l-url.md`.
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
  queued, every 30 s otherwise. That same stream also carries the running
  solve's **score curve** (issue #304) as `score` events — deltas, sampled and
  capped server-side by `SolverScoreTrace`, drawn on the Solveur page by
  hand-written SVG polylines, one per score level and each on its own scale.
  **There is no charting dependency**, and adding one needs the same explicit
  sign-off as any other. Each event also carries `dureeMs`, and that is not a
  detail: Timefold announces a new best score **only when it strictly
  improves**, so a solve that plateaus records no point at all — and the
  plateau is what the screen is for. The elapsed time is what draws it, so a
  running solve beats every tick even with nothing new to send. That curve is
  not polled: the stream is already reopened on error and after 45 s of
  silence, and a new connection is sent the whole series. See `docs/api.md`.
  Both loops are stopped with the shell that started
  them (the service is `providedIn: 'root'` and would outlive it).
- CSS is **unscoped, and loaded with the route** (issue #392, B9). Two
  kinds of stylesheet, and nothing else: `src/styles.css` aggregates the
  partials every screen needs (`pages.css` for the shared card/form/table
  scaffolding, `feedback.css` for the job monitor, snack-bar and summary-banner
  variants, `bulk-actions.css`, `detail.css`, `typologie-colors.css`, fonts and
  branding); everything a single route draws is that page's `styleUrl`
  (`pages/<block>/<block>-page.css`, or a shared file in `src/styles/` when two
  pages draw the same thing — the two calendars, the two imports), declared
  with `encapsulation: ViewEncapsulation.None` so its selectors mean exactly
  what they did as a global partial, and shipped in the route's lazy chunk
  rather than in the initial bundle. Each file holds its own `@media` rules.
  A class used by several pages goes to `pages.css`, never to one page's file:
  `scripts/check-css-scope.js` (`npm run css-scope-check`, run in CI) refuses
  a page that uses a class only another route loads — the unit tests render
  without CSS and would never see it. Don't restyle what a Material component
  already themes.
- **A screen answers, it does not explain** (issue #417): a subtitle is one
  sentence, a `mat-hint` twelve words at most, nothing repeats what the table
  or the form shows, and the *why* lives in the help page. `npm run i18n-check`
  refuses a message over 200 characters outside the help and legal pages — a
  warning that names a legal rule with its article is the one exception, kept
  whole. The full rule is in `docs/developpement.md` § *Textes des écrans*.
- Keep the frontend dependency-light. What is actually there, and why: Angular
  + its CLI + Angular Material + `@angular/localize` (the stack proper);
  `@sentry/angular` (error reporting, loaded by a dynamic `import()` only when a
  DSN is configured — see `core/observability.ts`); `leaflet` + `@types/leaflet`
  (the maps, reached only by the lazy `/emplacements`, `/graphe` and
  `/carte-jour` routes — it is a 150 kB chunk of its own and **must stay out of
  the initial bundle**, so nothing eagerly loaded may import it; the tile
  layer, the attribution and the bundled icon paths are shared by
  `shared/leaflet-base.ts`).
  Dev-only: `@playwright/test`, `vitest`, `@vitest/coverage-v8`
  (`npm run test:coverage`, run in CI — the report is published as an artifact
  and nothing consumes it: no threshold, no external service), `jsdom`,
  `prettier` (`npm run format` / `format-check`, the latter run in CI — the
  formatting of the TypeScript, CSS and scripts is its decision, not a review
  topic; templates excluded), `typescript`,
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
  every admin route plus the language and colour-scheme toggles, reference-data
  CRUD through the
  UI, the planning views over seeded data, locks, the help page, **real short
  solves** (ad hoc constraints respected and visible, a locked animateur
  unchanged by a re-solve, an accepted échange surviving regeneration),
  **contradictory ad hoc exceptions** (refused at entry, and — seeded straight
  through `/api/database/import`, the only way in left — reported by the
  pre-solve diagnostic) and
  **seeded invariant fuzzing** (random referentials solved for real, replayed
  with `E2E_FUZZ_SEED`). CI runs them on every pull request (`e2e.yml`, on a
  stack the job starts and throws away). Locally they **erase the database
  they target**: run them only against a disposable stack (see
  `docs/developpement.md` § Tests de bout en bout).

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
- **An edition's dates are derived, never stored.** An `Edition` carries neither
  dates nor an "ongoing" flag, so the event runs from the first to the last
  créneau of the edition (`service/referentiel/JoursEvenement`, read by the availability
  collection and by the write-time warnings). An edition with no créneau has no
  bounds at all, and anything reasoning on them must stay silent rather than
  invent one. Being *inside* those bounds is not the same as being usable: the
  availability circuit keeps only the exact dates carrying a créneau
  (`JoursEvenement.hasCreneauOn`), so an off day on a gap day is written and
  then erased by the first applied declaration — the warnings say so instead of
  staying silent.
- **A write-time warning names an animateur by their id, never by their
  identity.** No nom, no prénom, no date de naissance in an `Avertissement`
  message: the browser copies every notification into a `localStorage` log that
  outlives the logout (`docs/rgpd.md` §7). The sentence saying somebody is a
  minor goes one step further — shown, never logged
  (`models.ts`/`AVERTISSEMENTS_HORS_JOURNAL`).
- **A warning is only raised on what the write changed.** An edit compares
  against the fiche as it stood before; the bulk edit sends the whole merged
  fiche one row at a time, so anything else re-shouts on data nobody touched,
  and a warning that fires on correct data stops being read.
- `ContrainteAdHoc`'s prescriptive types (`INDISPONIBILITE_FORCEE`,
  `INCOMPATIBILITE`, `AFFECTATION_FORCEE`) are evaluated as `HardScore`, at the
  same priority as legal/minor hard constraints — never demote these to
  medium/soft. `AFFINITE` (issue #80) is the one deliberate exception: a soft
  *reward* for co-assigning a preferred pair on the same stand — never promote
  it to hard (that would be a forced assignment in disguise).
- Two ad hoc exceptions that **cannot both hold** are refused when written —
  `ContrainteAdHocContradictions`, applied by `ContrainteAdHocService` to a
  single entry and by `ReferenceDataService.importFromPlanning` to a whole
  scenario. It refuses only what is *certainly* unsatisfiable, read against the
  semantics each type has in `AdHocConstraints`: widening it to "suspicious"
  combinations would cost a user a legitimate exception with no way around it.
  A numeric budget on how many exceptions may exist was explicitly rejected —
  `docs/decisions/0010-contraintes-ad-hoc-contradiction-plutot-que-budget.md`.
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
5. When behaviour changes, update the doc that owns it. Two owners are **not**
   Markdown files, and writing prose instead of touching them is the mistake
   this rule exists to prevent:
   - **new endpoint → the OpenAPI the application publishes**, which is the
     contract (audit #392, question 12). `docs/api.md` does not list endpoints
     and must not start: it carries what a schema cannot express — the
     cross-cutting invariants, the business trade-offs, and the traps that cost
     half a day. Add to it only when there is such a thing to say. The
     committed `docs/schema/openapi.json` is refreshed by `npm run api-schema`
     (from `src/main/webui`, like every npm command here), and `npm run
     api-types-check` fails when the frontend's hand-written models drift from
     it;
   - **new constraint → `ConstraintCatalog`**, which `GET /api/constraints`
     serves and which is therefore the documentation the application itself
     hands out. `docs/contraintes.md` no longer enumerates the constraints and
     must not go back to it: it answers *why* a rule exists and what it costs,
     which the catalogue's one-line description cannot carry;
   - new business capability → README section 2; new command/CI/tooling →
     `docs/developpement.md`; anything touching the version contract, the
     release flow or the Docker image tags → `docs/versioning.md`.
6. **Architecture decisions go to `docs/decisions/`**, one file per decision,
   named `NNNN-short-title.md` and listed in `docs/decisions/README.md`. A
   decision record answers *why*, where the rest of `docs/` answers *what*.
   Write one when a choice closes off alternatives that a future reader would
   otherwise reopen — a schema shape, a solver mechanism, a classification
   rule. Never rewrite a past decision: when it is revised, set its status and
   link forward to the one that replaces it. The chain of revisions is the
   value; the latest state alone is not.
   Two rules keep these records publishable: **no issue references** (they
   point at a backlog that stays private) and **no client operating data** —
   figures are allowed only when they are reproducible on a scenario versioned
   under `src/main/resources/scenarios/`.
7. Don't create planning/notes/tracking Markdown files in the repository.

## Dependency updates

Renovate (`renovate.json` at the repo root) tracks Maven dependencies (including
the `quarkus.platform.version` / `timefold.solver.version` properties), the npm
dependencies of `src/main/webui`, Docker images, GitHub Actions and the
`mise.toml` toolchain. Keep the config in that
single file; document behaviour changes in `docs/developpement.md`. Quarkus and
Timefold bumps must be validated with `./mvnw verify -DskipITs=false` **and**
`./mvnw test -Pscenario-tests`: the five files that depend on
`ai.timefold.solver.core.impl` (listed by `TimefoldInternalApiStructuralTest`)
are covered by a compile error for the filters and the move factory and by the
contract test for the diagnostic, but a filter that still compiles and is no
longer asked only shows in the scenarios.

## Working conventions

- Code and comments in English, domain names in French business vocabulary —
  the glossary and the test that enforces it are in *Language of the code*
  below.
- **Commit messages are short.** Subject under 72 characters, conventional
  prefix, no trailing period. Subjects become CHANGELOG lines (git-cliff,
  `cliff.toml`), so write them for the operator who will read the release
  notes; two things file an entry under « ⚠️ Attention » — a `!` after the
  prefix, which means a MAJOR rupture, and the reserved
  `contraintes-legales` scope, which warns without bumping the major. See
  `docs/versioning.md`. A body only when it carries what the diff
  cannot — the why, an option rejected on the way, a consequence at
  deployment — and then three to five lines, not thirty. No body at all is a
  perfectly good outcome. Never restate the diff, never narrate the work that
  produced it, never list the files touched: the reader has `git show`. Two
  trailers and no others: `Signed-off-by` (`git commit -s`) and
  `Co-Authored-By: Claude <noreply@anthropic.com>` where an
  assistant helped — without a model version, which ages badly and teaches
  nobody anything. `.github/scripts/check-commits.sh` holds these rules on
  every pull request.
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
  → 404, `Conflict` → 409, `Stale` → 409 with a `code` the frontend switches
  on — a write based on an out-of-date read, issue #362) carries the status,
  and `BusinessErrorMapper` switches over it exhaustively. It extends `IllegalArgumentException`, so a
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
| `PlanningEvenement` | *the planning solution* | `Evenement` because the product schedules any event, not one festival ; the YAML section and `dateDebutFestival` keep their name, they are on the wire |
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

**Both public tokens say `token` all the way down.** Java, the SQL column
(`animateur.access_token`, since `V52`), the JSON key (`accessToken`) and the
HTTP path (`POST /api/animateurs/{id}/token`) were aligned together in issue
#186 — before that, one thing carried three names and each one had to be
explained where it showed. What stays French is the **path parameter** of the
espace itself (`@Path("/{jeton}")`, route `animateur/:jeton`): it names a
variable, never a published segment, so the links already printed on the PDFs
are unaffected. `jeton` is in the glossary for that reason, and for that scope:
the espace's path parameter and the identifiers that carry it. The credential
itself is `token` everywhere it is stored or serialised — a `jetonAcces` would
be as wrong after this as before. The subscription token of issue #324 carries no such history,
so it says `token` everywhere the credential appears — column
`animateur.abonnement_token`, JSON key `abonnementToken`, path parameter
`@Path("/{token}")` — while the *concept* is named `abonnement` in the
identifiers and the URL (`/api/abonnements`), as `Alerte`, `Fragilite` and
`Decoupage` already are, and *subscription* in the English prose.

A JSON key is a contract, not an identifier: `JsonContractTest` freezes the
keys of every exposed type in `src/test/resources/json-contract.txt`. Renaming
an accessor or a record component moves a key on the wire, and that test is
what makes it loud instead of silent.
