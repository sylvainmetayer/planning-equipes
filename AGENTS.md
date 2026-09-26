# AGENTS.md — Planning Équipes

Single source of repository memory for every AI agent (Copilot CLI, Copilot
coding agent, Claude Code, …), with one directory-scoped companion:
`src/main/webui/AGENTS.md`, the frontend's conventions, loaded when a session
works under that directory. **Do not recreate `CLAUDE.md` or
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

- Full test suite (fast — excludes `scenario-lent`, see below): `./mvnw test`
- Inner loop, without a container: `./mvnw test -Punit` — the 1405 tests that
  need nothing but a JVM, in about a minute and a half, with neither Docker nor
  PostgreSQL. The classes it leaves out are the ones that boot the application,
  listed in `src/test/container-tests.txt`; see *La boucle sans conteneur* in
  `docs/developpement.md`. It is the loop while writing, never the proof: CI
  plays the whole suite, and so should you before pushing.
- Integration tests (`*IT.java`, failsafe, `skipITs=true` by default):
  `./mvnw verify -DskipITs=false`

### Costly test jobs

Four scenario tests solve large fixtures to hard-feasibility and are tagged
`@Tag("scenario-lent")`, excluded from the default `./mvnw test`/`verify` and
from the main CI workflow; the scenario ladder (the `gamme-…` files) and the
extreme scenarios (the `extreme-…` files, `@Tag("scenario-extreme")`) sit
beside them. How to run each set, which profile clears which exclusion, what
the anonymised real-world fixtures need, and what to check on a Timefold bump
are the `heavy-tests` skill. Four rules hold without it:

- **Tag any future test in this weight class** `scenario-lent` (or
  `scenario-extreme`) instead of letting it slow down the default loop.
- `.github/workflows/scenario-tests.yml` runs them only on the paths that can
  break convergence (`solver/`, `domain/`, `solverConfig.xml`,
  `application.properties`, the scenario files, `pom.xml`): keep that path
  list in step with any move of those files — a solver tuning that ships
  without these tests having run is the hole this workflow exists to close.
- Every scenario file lives **flat** in the single classpath folder
  `src/main/resources/scenarios/`: the examples of Fichiers › Importer list that folder and
  `ScenarioYamlReader.scenarioPath` refuses a path component, so a scenario in
  a subfolder — or in a second `scenarios/` directory, which `getResource`
  would hide entirely — is one nobody can pick. `ScenariosLivres` in the tests
  is what reads it.
- A job on the order of a minute or more (this profile, a `docker build`, a
  long solve) runs as a background command; let the harness notify on
  completion instead of blocking the turn or sleep-polling.

- Formatting is decided by tools, not in review (issue #392, C10). Java:
  `spotless:check` is bound to `validate`, so every
  `./mvnw test|package|verify` refuses an unformatted file — run
  `./mvnw spotless:apply`. Frontend: `npm run format` (prettier, the
  repository's `.prettierrc`) on the TypeScript, CSS and scripts, checked by
  `npm run format-check` in CI; the HTML templates are excluded — the
  `.html` files by `.prettierignore`, the inline `template:` literals by
  `embeddedLanguageFormatting: off` — since a blank between two inline
  elements is rendering there.
- Frontend commands run from `src/main/webui` (the `package.json` scripts;
  `proxy.conf.json` forwards `/api/*` to `:8080`). `quarkus:dev` already
  starts and proxies the dev server — but a direct navigation (curl, F5, deep
  link) to any route but `/` on `:8080` 404s in dev mode (upstream Quinoa bug,
  doesn't affect production); test deep links against `:4200` directly
  instead — see `docs/developpement.md`.
- `docs/licences-tierces.md` (the third-party licence inventory the AGPL image
  owes whoever deploys it) is **generated, never hand-edited**: the
  `dependency-update` skill regenerates it, and the `test` job of the Tests
  workflow fails on a stale file, the same ratchet as the committed OpenAPI
  contract.
- **A sandbox without `mise` and without Docker still runs the whole suite**
  (Claude Code on the web, a fresh container): JDK 25 from apt, PostgreSQL from
  apt in place of the dev-services container. The recipe, the `%test.` prefix
  that makes the override stick, and the `-DskipITs=false` trap under the
  shipped password are the `sandbox-tests` skill. **Do not report the suite
  as unrunnable** — a claim that the backend could not be tested is worth a
  lot less than the run itself.

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
  only holds what they all share. A class lives with the question it answers; a
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
    away**; in memory, so the first read after a restart re-derives it from that
    plan instead of answering "never analysed"), `PlanningPersistenceService`,
    `PlanSnapshotService`, `SnapshotComparisonService`, `DeplacementService`.
  - `service/analyse/` — what is read from a plan without solving it.
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
  - `service/validation/` — « relu et accepté » on a day: the review state that
    the lock mechanism never carried (ADR 0039). `ValidationJourneeService`
    owns the two rules that keep the two apart — validating never lays a lock
    down unasked, and a solve that moves a seat of a validated day withdraws
    the reading unless that day also carries a `JOUR` lock;
    `ValidationPrerequisService` narrows the Problèmes, Pauses and Fragilité
    reports to one date rather than recomputing them, so the panel and those
    three screens cannot tell two stories about the same day.
  - `service/journee/` — what the Journée page asks about one day and nothing
    else answers: `ChangementsJourneeService` reads what moved on a date
    against the last published plan or the pre-solve automatique snapshot,
    seat by seat on the natural key (stand, day, hours — ADR 0025) and person
    by person through `PublicationDiffService`, never a comparison of its own.
  - `service/mural/` — the wall display of the control room (ADR 0053):
    `AffichageMuralService` manages the links (a token handed out once,
    stored as its SHA-256) and builds the view a token opens, on the
    persisted plan and `JourJClock`, through the pure
    `AffichageMuralViewBuilder` — the day under way, the shifts of each open
    stand, the alerts.
  - `service/profile/` — the fiche 360° of one animateur
    (`AnimateurProfileService`): an assembler over `EquiteService`,
    `FragiliteAnalyzer`, `ConfirmationPlanningService` and the referential,
    each report computed over the whole plan and only then narrowed to the
    person, so the fiche and the specialised screens cannot disagree.
  - `service/consigne/` — what an authority imposes on the whole event for
    one date (ADR 0043): a band every stand is shut on, and the compensation
    chosen stand by stand. `ConsigneResolver` is the **fourth layer** of a
    stand's schedule, pure and static, applied by `StandService.resolve` on
    top of `HoraireStandResolver` (rules, then dated exceptions) and on the
    effective windows only; `ConsigneService` proposes, previews, lays down,
    changes and lifts consignes and their presets — the only grid write it
    makes is adding the timeslots an opening needs, and lifting removes those
    alone; `ConsigneRepository` owns the six tables. Every reader of
    effective windows goes through `StandService.resolve` /
    `ReferenceData.resolveHoraires`: `ConsigneCoucheStructurelleTest` refuses
    any other production caller of `HoraireStandResolver.apply` that is not
    argued in its list, the same net as the edition-scoping tests.
  - `service/edition/`; `service/publication/` (publishing, delivery,
    confirmations, `MailService`); `service/export/` (`PlanningExportService`
    over `AnimateurPlanningPdf`, `AnimateurFeuillePdf`, `GlobalPlanningPdf`
    and `PlanningIcs`, sharing `PdfTheme` — server-side only; the two
    individual layouts render one `AnimateurPlanningView`, which is what keeps
    them saying the same thing, colour their bars from `TypologiePalette` and
    carry the espace's link as a QR code (`QrCodeEspace` over `zxing-core`,
    the one thing OpenPDF cannot draw); `ArchiveEvenementService`, the
    end-of-event ZIP, which writes each part through the generator of its own
    export and never a copy of it; `DatabaseDumpService`); `service/espace/`
    (`DemandeEchangeService` / `EspaceAnimateurService`, the foire au planning
    of issue #165; `ApplicationLinks`, every public URL printed in a mail or a
    PDF); and the ones that predate the split — `service/diagnostic/`,
    `service/journal/`, `service/mail/`, `service/notification/`,
    `service/backup/` (nightly `pg_dump` — see below).
- **A frozen family of the referential refuses every write, by annotation.**
  `service/referentiel/GelReferentielService` holds the freeze (ADR 0052); a
  service method writing stands, timeslots, game categories/locations or
  competences carries `@RefusedWhileFrozen(ReferentialFamily.X)` (interceptor,
  `409 REFERENTIEL_FIGE`), or compares with its before-image and calls
  `gel.refuseIfFrozen` itself when only some fields are frozen.
  `GelReferentielStructuralTest` fails on a method of those services in neither
  case nor argued as open — and on an annotated method called on `this`, which
  bypasses the interceptor. Consignes stay open: their grid write goes through
  the in-transaction timeslot methods, which only `ConsigneService` calls.
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
  change. See `docs/decisions/0013-diagnostic-par-le-score-director.md`.
  The three move filters
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
- `api/` — JAX-RS resources, one per capability and one per referential
  family, plus `ReferenceDataResource` for scenario import;
  `EspaceAnimateurResource` and `AbonnementIcsResource` are
  token-authenticated, the only public parts of the API. A resource holds
  transport only — status codes and payload shapes; anything that decides
  something belongs to a service. The scenario import is the worked example:
  the order its sections are applied in lives in `ScenarioImportService`, and
  `ReferenceDataResource` is left turning the outcome into a body. The endpoint
  list is the published OpenAPI.
- HTTP security (issue #165): everything under `/api` requires the admin form
  login (single `admin` account from config) **except**
  `/api/espace-animateur/*` (its URL token is the credential and resolves the
  edition by itself), `/api/abonnements/*`, `/api/mural/*`, `/api/auth/*` and
  `/api/config`; `/mcp` keeps its own API-key mechanism.
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
  `docs/decisions/0019-jeton-et-chemin-dedies-pour-l-abonnement-ics.md`.
  **`/api/mural/{token}` is the second one**, on the same reasoning (ADR 0053):
  the control room's television reads it every minute with a third token,
  stored hashed in `lien_affichage_mural`, created and revoked by the admin,
  good for that one read of one edition. The same two rules hold — the prefix
  names that route alone, and `AffichageMuralSecurityTest` asserts the token
  opens nothing else. An **opt-in** header mode (`planning.auth.remote-user.*`,
  off by default) lets an access proxy assert an already-authenticated
  address: `admin-email` gets the admin role, any other recognised address is
  an animateur whose espace opens without the e-mail code. It refuses to boot
  without a shared secret — a header is a claim, not a proof. Hardening for an
  Internet-facing deployment — browser security headers
  (`SecurityHeadersFilter`), HTTP limits, the four rate limiters
  (`AdminLoginLimiter` on `/j_security_check`,
  `CodeRequestLimiter` on the espace access codes, `McpRateLimiter` on the
  `/mcp` transport, which carries two guards of its own — a rate ceiling on
  every request and a lockout on a run of refused keys —, and
  `AffichageMuralRateLimiter` on `/api/mural/`; the three address-keyed
  classes share `ClientAddress`, which reads `X-Forwarded-For` from the right
  and is the half that makes any of them count anything), the production compose
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
  (`LayeringStructuralTest`). Five hard rules, all enforced over every tool so
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
  4. a tool class carries `@RefusMetier`, so a refusal from the domain comes
     back as a tool result in error carrying its sentence instead of
     « Internal error » (issue #529) — `RefusMetierInterceptor` is the MCP
     counterpart of `api/BusinessErrorMapper` and translates `BusinessError`
     and nothing else, a bug keeping its generic answer as it keeps its 500.
     The message travels as-is, so **no `BusinessError` may name a person**:
     designate an animateur by id, never by nom/prénom
     (`McpRefusMetierStructurelleTest` holds both halves).
  5. a write tool says what happens while a solve holds its edition: refused
     in 409 by `refuseIfSolving` — every write the landing would undo: the
     stand, animateur and créneau edits and deletes, the seat writes
     (`reaffecterPoste`, `applyEchange`, moves, restores), consignes, imports
     and reset; the test reads the guard back in the service methods it
     names — or accepted and annotated
     `@WarnsWhileSolving` — its answer, a `WarningCarrier`, then carries
     `RESOLUTION_EN_COURS` (`mcp/WarningCodes`) — or excluded with its reason
     (`McpWarnsWhileSolvingStructuralTest`). Never turn a refused write into
     a warned one: the refusal protects against the landing resurrecting data.

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

The frontend's conventions (components and state, Material theme, the shell
and every route, the `core/` layout, keyboard handling, URL view state, the
solver stream, CSS loading, tests) are `src/main/webui/AGENTS.md`, loaded when
a session works under that directory. Two rules hold everywhere, next to the
i18n rule at the top of this file:

- **No additional frontend dependency** — UI kit, state library, CSS
  framework, i18n library, charting library, runtime or dev — without explicit
  sign-off. Plain Angular served by Quinoa is a deliberate choice, and the
  inventory of what is there and why is in that file.
- **One question = one screen, its variants as tabs or views in the URL**:
  adding a functional block means adding a route, an `app/pages/<block>/`
  folder and its line in `shell/nav-groups.ts`, never a new section inside an
  existing page; a variant of the same question is a tab (`?onglet=`) or a
  view (`?vue=`) of that page. A route may be served without a menu entry.
  Every route is listed in that file, and `DocumentationStructuralTest` fails
  on one that is not.

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

1. **One root memory file**: this `AGENTS.md`, plus the directory-scoped
   `src/main/webui/AGENTS.md` for the frontend and nothing else. Never split
   repository memory across `CLAUDE.md`, `.github/copilot-instructions.md`,
   or similar files.
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
   - a flow, a lifecycle or the model drawn in `docs/diagrammes/` → its
     `.puml` (PlantUML), then `./mvnw validate -Pgenerate-diagrams` and commit
     the regenerated `.svg` with it. `DiagramsStructuralTest` fails on a
     stale SVG, a participant or method that no longer exists, a cited route
     that does not, and a state diagram that drifted from its enum — the
     conventions it relies on are in `docs/developpement.md` (« Diagrammes »).
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

Renovate manages them (`renovate.json`, one file — keep the config there, and
document behaviour changes in `docs/developpement.md`). What a Quarkus or
Timefold bump must be validated with, how a Renovate PR regenerates the licence
inventory and re-dispatches its own checks, and which labels trigger the
scenario workflow are the `dependency-update` skill.

## Working conventions

- Code and comments in English, domain names in French business vocabulary —
  the glossary and the test that enforces it are in *Language of the code*
  below.
- **Commit messages are short.** Subject under 72 characters, conventional
  prefix, no trailing period. Subjects become the body of the GitHub release
  (git-cliff, `cliff.toml`, written by `release.yml`), so write them for the
  operator who will read those notes; two things file an entry under « ⚠️ Attention » — a `!` after the
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
| `ConsigneEdition` | *consigne* | **not translated** — "directive" and "instruction" both lose what the French word carries: an order received from outside (an arrêté) and relayed as such to the whole event. Its *bande* is *the band* in prose; `PrereglageConsigne` is *a preset* |
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
identifiers and the URL (`/api/abonnements`), as `Alerte` and `Fragilite`
already are, and *subscription* in the English prose.

A JSON key is a contract, not an identifier: `JsonContractTest` freezes the
keys of every exposed type in `src/test/resources/json-contract.txt`. Renaming
an accessor or a record component moves a key on the wire, and that test is
what makes it loud instead of silent.
