# AGENTS.md — Frontend (`src/main/webui`)

Directory-scoped memory for the Angular frontend, loaded when a session works
under `src/main/webui`. The root `AGENTS.md` holds everything else — the i18n
rules, the language policy, the domain invariants, the documentation rules —
and this file does not repeat them. When one of the three frontend rules the
root file keeps changes (no new dependency, one route = one page, both i18n
halves), change it there.

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
  four child routes of its own: `/animateur/:jeton` (« Mon planning » — three
  tabs chosen by `?onglet=jour|apercu|coequipiers`, the day on screen carried by
  `?jour=`: the day with its strip, its state band and its seat cards; the
  whole planning as four figures and a frieze coloured by typologie; and « suis-je
  avec quelqu'un ? », the teammates ranked by shared shifts and searched by
  name), `/animateur/:jeton/echanges`, `/animateur/:jeton/disponibilites` and
  `/animateur/:jeton/aide`.
- **One route = one page = one block.** Admin routes (children of the shell):
  `/` (default, « État de l'édition » — the checklist of the cycle, one line
  per step with its state and a link to the screen that moves it, read in one
  call from `GET /api/editions/courant/etat`), `/solveur` (the solver page,
  the former home),
  `/debug` (« Débogage » — four tabs chosen by
  `?onglet=resolution|verifications|donnees|yaml`: the raw analysis with the
  version and the API docs, the checks — test notification, test exception,
  test mail, Mailpit, and the frozen date where the server allows it —, the
  database and the bundled scenarios, and the YAML validator),
  `/mcp-client`, `/notifications`,
  `/parametres` (« Paramètres » — four tabs chosen by
  `?onglet=legaux|edition|emails|globaux`: the legal parameters and the meal
  break, the edition's own settings — ninja typologie, organisational-quality
  thresholds, the pointers to what lives on its own screen —, the e-mails the
  edition sends of itself, and the whole-database settings — nightly backup and
  SQL dump), `/stands`, `/emplacements`,
  `/animateurs`, `/competences` (« Compétences » — the animateur × typologie
  grid of appreciations, saved row by row, exported and imported as a CSV),
  `/imports`, `/exports` (« Export » — the data the edition writes of itself:
  the CSV archive and the scenario file the import screen reads back),
  `/publication` (« Publication » — what reaches real people: the planning
  documents to print or archive, and the mailing to every animateur whose
  schedule changed; a screen of its own under Solveur since issue #320, so the
  solver page only solves. Before sending, the review table — one row per
  person, ordered by `?tri=nom|ampleur`, minor changes folded by
  `?mineurs=masques`, each row a checkbox that **defers** that person's message
  rather than dropping it, ADR 0047), `/export-csv`, `/creneaux`, `/typologies`,
  `/ad-hoc-constraints` (« Ajustements manuels » on screen — the route, the API
  path and the domain type keep the `ContrainteAdHoc` name, only the label was
  renamed), `/calendar`, `/journee` (« Journée » — one day under five
  renderings chosen by `?vue=calendrier|rail|carte|pauses|changements`: the
  calendar stand by stand, the rail animateur by animateur, the day replayed on
  the emplacement map with one time cursor, the breaks, and what changed since
  the last publication or the last solve (`?reference=publication|resolution`,
  absent = the publication when one exists; `?lecture=animateurs` for the
  per-person reading); one day selector and the
  same `stand`, `animateur` and `q` filters in the URL, the plan and the breaks
  read once by the page and handed to the rendering on screen — the views under
  `pages/calendar-day`, `pages/rail-jour`, `pages/carte-jour` and
  `pages/pauses`, and `pages/journee/changements-vue`, are its components, not
  routes), `/constraints`,
  `/diagnostic` (« Diagnostic » — the four analyses as tabs chosen by
  `?onglet=problemes|besoin|fragilite|banc`: the problems, the staffing need,
  the fragility, the bench; the tab components under `pages/problemes`,
  `pages/staffing`, `pages/fragilite` and `pages/banc-de-touche` keep their own
  view state in the URL next to the page's key), `/echanges`, `/hours`, `/intendance` (« Intendance des repas » — the meal
  breaks counted rather than named: how many people are out, hour by hour and
  per emplacement, « combien de sandwichs et où les porter »), `/typologies-planning` (« Planning par typologie » — the persisted plan read
  by typologie of jeu, under four renderings chosen on the page: the table, the
  compared bars, the typologie × jour heatmap and the cards; four filters narrow
  the rows and every animateur is a link to their timeline), `/equite` (« Équité » — one line per assigned animateur: evening, week-end and holiday hours, demanding seats, variety, honoured wishes, rest days, each with its distance to the median),
  `/repos` (« Jours de repos » — who works, who
  rests, who was unavailable, under two renderings chosen by `?vue=grille|frise`:
  the animateur × day grid, whose cells print their hours only under
  `?densite=confort`, and the frise, one proportional bar per animateur that
  fits a month-long edition on a screen), `/jour-j` (« Mode jour J » — the day-of screen:
  mark somebody absent, repair the seats they held), `/timeline` (« Timeline
  animateur »), `/heatmap` (« Heatmap de charge »), `/marge` (« Marge
  disponible » — the day × timeslot grid of what is left: the animateurs
  available then minus the seats to staff, read either on the seats a solve
  would have to fill or on the plan persisted), `/kpi`, `/comparateur`
  (« Comparateur A/B » of two snapshots), `/instantanes` (« Instantanés »),
  `/verrouillages`, `/consignes` (« Consignes » — a band an arrêté closes
  for every stand on a date, the compensation chosen, the presets; issue #4
  / ADR 0043), `/ouvertures` (« Ouvertures des stands »),
  `/disponibilites` (what the animateurs
  declared), `/editions`, `/historique` (« Historique des actions »),
  `/nouveautes` (« Nouveautés » — what the running version brought, read from
  the repository's commit subjects collected at build time by
  `scripts/generate-news.js` into a gitignored `news-data.ts`, and sorted
  under the headings of `cliff.toml`: no endpoint, no stored page, and
  nothing to keep up to date by hand), the
  four public legal pages `/mentions-legales`, `/conditions-utilisation`,
  `/politique-confidentialite` and `/declaration-accessibilite` (the
  accessibility statement, filled from `planning.legal.accessibilite.*` by the
  deployment it describes), and `/aide` (`/solver` redirects to
  `/solveur` and `/export-csv` to `/exports`;
  `/data-transfer`, `/data-setup` and `/validateur-yaml` are
  legacy redirects too, `/decoupage` now landing on `/creneaux` since the
  slicing was removed, kept for old bookmarks/links, and so
  are the eight former screens `/day-calendar`, `/rail-jour`, `/carte-jour`,
  `/pauses`, `/problemes`, `/staffing`, `/fragilite` and `/banc-de-touche`,
  whose redirects carry their query params along, renamed where the page now
  owns the key).
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
  / replace" modes a bulk edit applies to one row; `grille-saisie.ts` — the two
  moves of repetitive entry the two entry grids share (take the row above,
  apply one value down a column), generic over the cell so a grid only says how
  it reads and writes one, and pure so the moves are tested once instead of
  twice; `entity-labels.ts` — plural
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
  palette), `g`+letter (navigation — `g g` the home, `g l` the solver), `/` (the page's filter, marked by
  `data-page-filter`), `?` (the shortcut list) and Ctrl+Enter (submit the
  active form) all go through `core/keyboard-shortcuts.service.ts`; its
  destinations are derived from `app.routes.ts`, so a new route is reachable
  by keyboard without being registered anywhere. Single-key shortcuts are
  suppressed while the focus is in a text entry — modifier combinations are
  not, since Ctrl+Enter is meant to be pressed from inside a field — and can
  be turned off on a browser (WCAG 2.1.4: dictation fires one per word said
  outside a field), a chrome preference in `core/single-key-shortcuts.ts`
  offered by the `?` dialog and by Paramètres. Escape is
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
  bulk edit only writes what the user explicitly filled in. The **entry grids**
  (`/ouvertures`, by date and by journée type, and `/competences`) are the other
  way in and follow the same law from the other end: a move writes only the
  cells it names — the row it fills, or the one column it runs down — and
  writes them locally, so « Enregistrer » stays the only thing that reaches the
  server and « Annuler les modifications » undoes a move like any keystroke.
  Their shared moves live in `core/grille-saisie.ts`; each grid contributes an
  `AccesGrille` saying how it reads and writes one cell, and the per-template
  grid is what makes "nothing to copy" a real case — a column whose dates
  disagree is a report, not a value.
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
  every such screen carries a one-action reset. A page made of several
  components — the Journée and its renderings, the Diagnostic and its tabs —
  has several writers on one URL, and **a writer owns only the keys it
  names**: `keepViewInQueryParams` starts from the query string as it stands
  and replaces its own keys, so the page's `vue` and a view's `lignes` never
  erase each other. See `docs/decisions/0012-etat-de-vue-dans-l-url.md`.
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
  (the maps, reached only by the lazy `/emplacements` and `/graphe` routes and
  by the `@defer` block of `/journee` around its map rendering — it is a
  150 kB chunk of its own and **must stay out of the initial bundle** and out
  of the chunk of the three other renderings of the day, so nothing eagerly
  loaded may import it and the map view is never referenced outside that
  `@defer`; the tile
  layer, the attribution and the bundled icon paths are shared by
  `shared/leaflet-base.ts`).
  Dev-only: `@playwright/test`, `@axe-core/playwright` (the axe-core sweep of
  `e2e/accessibilite.spec.ts`, against a frozen per-screen baseline — see
  `docs/accessibilite.md`), `vitest`, `@vitest/coverage-v8`
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
  pre-solve diagnostic), **the placements nobody can hold** (a forced
  assignment warned about and written anyway, reported by the feasibility
  endpoint and confirmed for before the solve; a seat written by hand refused,
  and freeing one never refused — on a fixture built there, since no shipped
  scenario carries a minor, a night and an adults-only stand) and
  **seeded invariant fuzzing** (random referentials solved for real, replayed
  with `E2E_FUZZ_SEED`). CI runs them on every pull request (`e2e.yml`, on a
  stack `e2e-suite.yml` starts and throws away) — **except the specs tagged
  `@lourd`**: the organiser's heatwave week on `festival-hivernal` solves three
  times for real and weighed 13 of the suite's 20 minutes, so `e2e-lourd.yml`
  plays those on the changes that can break them (its `paths` list, kept in
  step like the scenario one) and every night, the way `scenario-tests.yml`
  does. Tag a spec that way when it solves a real-world fixture, never to
  hide a slow test. `tests.yml` and `e2e.yml` also skip a push that touches
  only `docs/` and Markdown — minus the files a backend test reads or the
  `test` job compares to its build, which are re-included by name. Locally
  they **erase the database
  they target**: run them only against a disposable stack (see
  `docs/developpement.md` § Tests de bout en bout).
