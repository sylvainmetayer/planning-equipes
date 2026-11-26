---
twin: comprendre/tests
---
# Running the tests and reading a failing structural test

You will know which test family to run depending on what you change, how to loop quickly without a container, what each structural test guarantees when it fails, and how to replay the end-to-end tests locally. Large scenarios, the bench, the direct engine and solver mutation are in [solver and scenario tests](tests-de-scenarios.md).

## The test families

| Family | Tool | What it holds |
| --- | --- | --- |
| Constraints (`*ConstraintsTest`) | `ConstraintVerifier`, no Quarkus, no database | one isolated rule, in a few milliseconds. Every new constraint adds at least one penalised case and one valid case |
| Service unit tests | JUnit, hand-built services | one business rule called directly |
| Integration (`@QuarkusTest`) | throwaway PostgreSQL from dev services | Flyway migrations run as in production; HTTP transport, wiring |
| Structural | JUnit that reads the code | a rule nothing else checks, see [below](#les-tests-structurels) |
| Scenarios | JUnit, YAML files in `src/main/resources/scenarios/` | an end-to-end solve, see [scenario tests](tests-de-scenarios.md) |
| Frontend | Vitest, jsdom | the orchestration of pages; dedicated CI job, not wired to the Maven phase |
| End to end | Playwright | what a browser really does, see [below](#les-tests-de-bout-en-bout) |

A new service test starts as a unit test: services are built without a container (`new PlanningService(…)` with `EmptyReferenceData`, as in `PlanningServicePlainTest`, `PlanningHardConstraintsTest` or the `ScenarioLadder` harness), and the scenario reader is pure and static. A `@QuarkusTest` remains the right answer when the test is about transport (a route, an HTTP code, a header), a migration or wiring. For a business rule that can be called directly, it is not.

A test that waits for asynchronous processing (a solve that completes, a job leaving the queue, the solver becoming free) goes through Awaitility (`await().atMost(…).until(…)`, a test dependency managed by the Quarkus BOM) on the condition actually awaited, never `Thread.sleep`: the wait stops as soon as the condition holds, and its failure says what was missing. When only time must pass (a throttling window that reads the wall clock), `await().pollDelay(…)` says so explicitly.

## Running each set

Any command that takes a minute or more runs as a background task from an agent session, so as not to block the turn or loop on a `sleep`.

| Command | What it runs |
| --- | --- |
| `./mvnw test -Punit` | everything that needs only a JVM, without Docker or PostgreSQL |
| `./mvnw test` | the full suite, except tests tagged `scenario-lent` and `scenario-extreme` |
| `./mvnw verify -DskipITs=false` | the suite and the `*IT`, run against the packaged application (`skipITs` is `true` by default) |
| `./mvnw test -Pscenario-tests` | adds the `scenario-lent`; details in [scenario tests](tests-de-scenarios.md) |
| `./mvnw test -Pscenario-extreme` | only the `scenario-extreme`, one class at a time |
| `./mvnw -Pmutation test-compile pitest:mutationCoverage` | the mutation tests on the constraints ([solver tests](tests-de-scenarios.md#les-tests-de-mutation)) |
| `cd src/main/webui && npm test` | Vitest |
| `cd src/main/webui && npm run e2e` | Playwright, against an application that is already running |

`./mvnw spotless:apply` formats the Java, and `spotless:check`, bound to `validate`, makes every `test`, `package` or `verify` refuse a badly formatted file. On the frontend, `npm run format` applies prettier.

### Without mise or Docker

A fresh container runs the whole suite with JDK 25 and PostgreSQL taken from `apt`: the `sandbox-tests` skill (`.claude/skills/sandbox-tests/SKILL.md`) gives the recipe. Two traps: the `%test.` prefix on `-D%test.quarkus.datasource.devservices.enabled=false` is what makes the setting stick, because a profile-specific value wins over a plain system property; and `-DskipITs=false` needs a PostgreSQL password different from the sample one (`festival`), which `DefaultSecrets` refuses at startup of the packaged application.

## The loop without a container

`./mvnw test -Punit` runs about fourteen hundred tests in a minute and a half, with no Docker, no PostgreSQL and no dev services. It is the working loop, never the proof: CI runs the whole suite and that is the one you must have seen green before pushing. The classes that start the application account for most of the suite's time, and the profile exists to set them apart.

What the profile leaves out is written in `src/test/container-tests.txt`, one path per line, which the profile passes to surefire as `excludesFile`. It is an exclusion at selection time and not a JUnit tag: a `@Tag` is filtered after discovery, yet loading a `@QuarkusTest` class builds the application, dev services included, before any filter is consulted.

The file decides nothing, it makes things visible. A line is added when a new test starts the application, it disappears when the test is converted, and `ContainerTestsInventoryTest` refuses any gap between the file and the annotations, saying which line to write. Its length is the debt. In CI, the `test` job runs `-Punit` and two `test-conteneur` jobs each run half of the listed classes: every class is in exactly one of the three.

## Frontend specs

A page spec tests orchestration: what the page asks of its services and what it does with the answer. It does so through typed fake services. `core/testing/fake.ts` (`fakeOf`, `provideFake`) builds the fake of a `core/` class (the `core/api/*`, the stores) with only the methods the spec needs, each typed on the class. You mock the method and not the URL, and `fake.method.mock.calls[0]` is the tuple of its parameters: a renamed method or a changed argument no longer compiles, where an `as unknown as [string, Item]` stayed green. A protected member of the component is read as `page['member']`, checked by the compiler. Material rendering is tested only where it is the subject (an accessible label, an NG01352 error).

`npm run unknown-casts-check` refuses any spec that carries a cast that silences the compiler: `as unknown as`, `as any` or `as never`, searched in the whole text of the spec, comments included. No eslint rule holds them in a spec, where `no-explicit-any` is off. The count is zero, with no baseline.

## Structural tests

A structural test plays no scenario: it reads the code (sources, annotations, SQL, JSON, inventory files) and fails on a rule that no other check holds. Each carries a list of exceptions justified one by one, and a check that the scan does find something, without which it would go green the day its regular expression stopped recognising the code. When one fails, its message most often says the line to write; the decision to make an exception is the reviewer's.

| What is held | Tests |
| --- | --- |
| The `edition_id` predicate on every business query, including through variables and constants | `IsolationEditionStructurelleTest` ([partitioning](cloisonnement-par-edition.md)) |
| No staff name leaves over MCP; every tool names its edition, declares its four hints, translates business refusals, says what happens during a solve | `McpConfidentialiteStructurelleTest`, `McpEditionStructurelleTest`, `McpAnnotationsStructurelleTest`, `McpRefusMetierStructurelleTest`, `McpWarnsWhileSolvingStructuralTest` ([MCP tools](mcp-outils.md)) |
| The three writings of a constraint's name: `actif(stream, "X")`, `.asConstraint("X")` and the catalogue | `ConstraintToggleStructurelleTest` and `ConstraintCatalogTest` |
| The two engines score the same rules | `HandSearchCoverageStructuralTest`, `HandSearchDifferentialTest` ([solver tests](tests-de-scenarios.md)) |
| A referential frozen by the phase of the edition refuses every write; a write that a solve would undo is refused | `GelReferentielStructuralTest`, `RefusedWhileSolvingStructuralTest` |
| Only the active edition publishes and sends mails | `ActiveEditionEmissionStructuralTest` |
| Every write route and write tool is in the catalogue of journalled actions | `JournalCoverageStructurelleTest` |
| Every referential table carries `modifie_le` and its write precondition | `WriteStampStructurelleTest` |
| An applied migration does not change; a new one has its fingerprint line | `FlywayMigrationsFrozenTest` (`migrations-empreintes.txt`) |
| Exposed JSON keys do not move silently | `JsonContractTest` (`json-contract.txt`) |
| Layers (an MCP tool calls a service, never a resource) | `LayeringStructuralTest` |
| The repository imports `ai.timefold.solver.core.impl` only in the listed places | `TimefoldInternalApiStructuralTest` |
| Every reading of a stand's plages goes through `StandService.resolve` (a consigne's plages are read above the stand's own there) | `ConsigneCoucheStructurelleTest` |
| The stand manager sees only projections, never a domain type | `ResponsableProjectionStructurelleTest` |
| The routes open to the organiser and to the stand manager, one by one | `InstanceRoutesStructuralTest`, `RoutesOuvertesStructuralTest` |
| The list of tests that start the application | `ContainerTestsInventoryTest` |
| The diagrams, the documentation pages | `DiagramsStructuralTest`, `DocumentationStructuralTest` |
| The language policy of the code | `LanguagePolicyStructuralTest` |

### Open routes, one by one

`RoutesOuvertesStructuralTest` complements `InstanceRoutesStructuralTest`, which classifies only whole resources: the methods open to an organiser (`routes-organisateur.txt`) or to a stand manager (`routes-responsable.txt`, under `src/test/resources/`) are inventories versioned like `json-contract.txt`. An unclassified route fails by printing the line to add; the decision (open, or `@InstanceOnly`) is the reviewer's.

### The language policy

`LanguagePolicyStructuralTest` applies the code's language glossary: English verb, English common noun, English word order, business vocabulary in French. A convention that nobody checks does not hold: a single branch is enough to add twenty blocks of French javadoc.

It looks at neither record components nor accessors (they are JSON keys), nor test methods (the rule is to rename as soon as you touch a test), nor methods that carry a constraint's name (primary key of `constraint_toggle`), nor MCP tools, whose name is the one a French-speaking assistant chooses. On a legitimate name that it refuses, rename it or add it to `EXCEPTIONS_ASSUMEES` with its reason; another check verifies that each exception still matches real code. On the frontend, `npm run language-policy-modifies` applies the same rule to the files modified by a pull request.

## End-to-end tests

The Playwright specs (`src/main/webui/e2e/`) cost several minutes where the unit suite answers in seconds, and that cost is accepted: it is the only layer that sees what a browser really does. They run on every push and on every pull request, except the specs marked `@lourd` (see below).

They reseed the database through `/api/database/import`: never point them anywhere but a throwaway stack. These SQL dump routes answer only if `planning.outillage-test.dump-sql` is `true`, which is the case under `quarkus:dev`, in the tests and on the `e2e-suite.yml` stack (`OUTILLAGE_TEST_DUMP_SQL`). A jar launched by hand for E2E must set this variable.

```bash
docker compose up -d postgres mailpit
./mvnw quarkus:dev   # mail on :1025, password "admin"
cd src/main/webui && npm run e2e         # + --headed, --ui, --project=mobile, a spec path
```

Settings through environment variables, all optional: `E2E_BASE_URL` (`http://localhost:8080`), `E2E_ADMIN_PASSWORD`, `E2E_MAILPIT_URL` (`http://localhost:8025`), `E2E_CHROMIUM`, `E2E_VIDEO`.

The local PostgreSQL is version 19 (`postgres:19` in `docker-compose.yml`). The dev services of `quarkus:dev` and of the tests have their own throwaway database.

### Each spec starts from a reference state

Before the first spec, the suite snapshots the database through `/api/database/export`, a script that starts with a `DELETE FROM` for every table and is therefore a self-contained restore. Each spec goes back to it in its `beforeAll`: they do not contaminate each other, and the suite can be rerun without recreating the database. The reference is cached in `node_modules/.cache/` and replayed at startup, for about 40 ms per spec. The first capture requires a database free of test data, otherwise it would freeze the state of a previous run, and the suite refuses it, saying so. If the schema has changed, delete the cache and recreate the PostgreSQL container.

### Seeds are dated from the real clock

The e2e stack leaves "the past is frozen" ([ADR 0044](repo:docs/decisions/0044-le-passe-est-fige.md)) on, like production, and the packaged application freezes no date: a timeslot behind the clock would be taken back from the stored plan by every solve and would refuse any action. A spec therefore writes its dates as if the event fell in summer 2026 and passes them through `shiftDate()` (`e2e/support.ts`), which advances them by the smallest whole number of weeks that places Monday 6 July 2026 at least eight weeks after today. Weekdays are kept. A minor's date of birth goes through the same function, so that their age at the timeslot stays the one the assertion assumes. A date hard-coded in a seed is a spec that will stop testing anything the day the clock passes it.

### Heavy specs

A `@lourd` spec does not run on every push. The heatwave week on `festival-hivernal` (`canicule-festival-hivernal.spec.ts`) imports the real fixture, solves it and publishes it three times with one PDF per person: on its own it weighs more than all the other specs together. The `e2e` job of `tests.yml` leaves it out (`--grep-invert @lourd`) and `e2e-lourd.yml` runs it (`--grep @lourd`) on the same stack, `e2e-suite.yml`:

- on a pull request, only under a label: `e2e-lourd` (to put on a PR that touches the solver, consignes, publication or the clock; putting it on an open PR starts the run), or `timefold`, `quarkus`, `playwright`, which Renovate puts on upgrades that change what these specs go through;
- on `main`, at every merge that touches the solver, consignes, publication, the `pom.xml` or its own files (its `paths` list, to be kept up to date like the scenarios one);
- every night, so that what an unlabelled PR broke shows up the next day at the latest.

Marking a spec `@lourd` says that it solves a real fixture or needs the `e2e-lourd.yml` stack; it is not the place to store a slow test. `npm run e2e -- --grep @lourd` runs it alone locally.

`passe-fige.spec.ts` is heavy for a different reason: it replays `FrozenPastAcceptanceTest` from the screen and requires a stack that accepts the simulated clock. `e2e-lourd.yml` passes `horloge-simulee: true` to `e2e-suite.yml`, which starts the application with `HORLOGE_SIMULEE_AUTORISEE=true`; the ordinary `e2e` job does not, and `jour-j.spec.ts` checks there that freezing the date is refused. Locally, therefore, run `passe-fige.spec.ts` alone on an application started with this variable: on that stack, the refusal that `jour-j.spec.ts` checks no longer happens and the whole suite would fail. The spec gives the real clock back when it leaves, because the table that carries it survives the restoration of the reference.

### The local account and the OIDC test

`organisateur-compte-local.spec.ts` (ordinary suite) has the organiser start on a local account: creation through the administration API, invitation link read in Mailpit, generated password (never written in the spec), code received by mail. It must reach the application without a loop back to `/login`, see neither the Accounts tab nor the Instance tab, and receive `403` on an instance route. The stack starts the application with `PUBLIC_URL=http://e2e.example.org:8080` and not `localhost`, because an invitation link to the machine itself is refused outside dev and test.

The OIDC test (`oidc-keycloak.yml`, `npm run e2e-oidc`) plays the authorization code flow with PKCE against a real Keycloak: sign-in, `/api/auth/me`, refusal of an unknown account (`403`), sign-out that also ends the provider's session. `OidcSignInTest` remains the proof of the rules; the test proves they hold against a provider that really redirects and signs. It triggers like `e2e-lourd.yml` and never on every PR. Keycloak and PostgreSQL are started by `docker run`. The realm is `src/main/webui/e2e/keycloak/realm-planning.json`, whose client secret, user password and application address are `${…}` filled in by the environment: no secret in the repository. The emergency account stays open (`ADMIN_LOCAL_SECOURS=true`) to create the first account, since an OIDC sign-in never creates one. The spec carries the `.oidc.ts` suffix and its own config (`playwright.oidc.config.ts`), which the ordinary suite does not pick up. Locally, point `E2E_BASE_URL`, `E2E_KEYCLOAK_URL` and `E2E_OIDC_USER_PASSWORD` at a throwaway stack.

### Diagnosing a failure

`e2e/icones.spec.ts` checks that the icon font is drawn: without it, a `<mat-icon>delete</mat-icon>` fails nowhere, the browser writes the word and every screen shows "...". jsdom has no fonts, so no unit test sees it.

The trace first: `trace: 'retain-on-failure'` leaves a browsable trace after a failure (filmstrip, DOM, network, console).

```bash
npm run e2e -- --trace on
npx playwright show-trace test-results/<dossier-du-test>/trace.zip
```

Then the video, through `E2E_VIDEO` (when absent, nothing is recorded). It is for showing a path more than for diagnosing.

```bash
E2E_VIDEO=retain-on-failure npm run e2e   # or 'on'; .webm in test-results/<test>/
```
