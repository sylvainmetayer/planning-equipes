---
twin: comprendre/frontend
---
# The Angular frontend

The interface is an Angular application served by the same Quarkus as the API. You will know how it is built and bootstrapped, which structural rules the screens follow, and a few pitfalls that cost a lot when rediscovered: the poll that doubles the event stream, the keyboard, the theme, Leaflet.

## A single deployment

The Quinoa extension runs `npm ci && npm run build` during `mvn package` and copies the bundle into the static resources ([`application.properties`](repo:src/main/resources/application.properties), `quarkus.quinoa.*`). In production there is no Node server. In `quarkus:dev`, Quinoa starts `ng serve` and proxies port 4200 for hot reload.

Structural rule: one question, one screen, its variants as tabs (`?onglet=`) or views (`?vue=`) in the URL, loaded on demand. You do not add a section to an existing page. The menu ([`shell/nav-groups.ts`](repo:src/main/webui/src/app/shell/nav-groups.ts)) is the same for everyone and follows an edition's cycle; a route can be served without a menu entry (Debug), the Ctrl+K palette finds it. The services in `core/` carry the shared state and the HTTP calls; components do not call `fetch`.

State flows in one direction only: the Solver page pushes the solved schedule, the calendars reread it read-only and never start a solve.

## Redeploying without breaking open browsers

Each frontend page is loaded on demand, under a name that changes with its content (`chunk-*.js`): a redeployment replaces the whole batch. A browser that keeps the old shell in cache would then request files that no longer exist, and every menu link would fail silently, on mobile as on desktop.

Two mechanisms avoid having to ask users to "clear their cache":

- the server serves everything that has a stable name with `Cache-Control: no-cache`: `index.html`, the `/i18n/*.json` catalogues, but also the favicon, `robots.txt`, the logos and the fonts, which the Angular build copies as is from `public/`. *Revalidate before reuse*: a conditional request answered `304` as long as nothing has changed. The hashed files, for their part, stay cached for a long time without risk (`SpaCacheControlFilter`);
- if a stale shell runs anyway (a tab left open during the redeployment), the first click whose chunk has disappeared triggers **a full reload to the requested page** instead of failing silently (`core/stale-chunk-reload.ts` on the frontend side). The reload *replaces* the history entry: the broken shell is not left one back-step away. With the browser offline, however, nothing triggers: a tunnel produces the same error as a deleted chunk, and reloading would trade a dead link for the "no connection" page and the whole session with it.

No setting: redeploying is enough, browsers recover on the next visit or the first click.

To diagnose, remember this detail. SPA routing being active (`quarkus.quinoa.enable-spa-routing`), a missing chunk does not give a `404` but a `200` with the current `index.html`. It is the wrong MIME type that makes the dynamic import fail. In the browser console, look for *failed to fetch dynamically imported module* (or its Firefox and WebKit equivalent), not an HTTP error code.

## Three reads before `bootstrapApplication()`

[`main.ts`](repo:src/main/webui/src/main.ts) loads in parallel the English translation catalogue (only when English is the chosen language), `/api/config` and `/api/branding`, before bootstrapping the application. The tab title and the logo decide the first frame: no screen should have to wait for a round trip to know what it is called. If the English catalogue fails to load, the application falls back to French rather than staying blank.

**Runtime translation.** French is written directly in the templates, `loadTranslations()` loads `messages.en.json`. There is only one build, with no bundle per language, hence no extra Quinoa configuration. The writing rules (the two halves of a string, `$localize` never at module level) are in [the repository conventions](conventions-du-depot.md).

**Branding.** An empty `logoUrl` means "show nothing", never "show the default logo": an instance serves one client, and none inherits another's brand. A limit worth knowing: `mat.theme()` compiles the whole tonal palette into the bundle, and the configurable accent colour (`BRANDING_ACCENT_COLOR`) only feeds `--app-accent`. Recolouring the Material components requires a recompilation, not an environment variable.

## Light or dark theme

`mat.theme()` compiles both palettes at once: each `--mat-sys-*` colour is a `light-dark(light, dark)` pair, and the CSS `color-scheme` property of the root element decides which one is painted. There is no second theme or parallel stylesheet, nothing to recompile. The application writes `light dark` ("follow the machine", the default), `light` or `dark` on `<html>`, and keeps the preference in `localStorage` with the other interface preferences. `main.ts` sets it before any network call, so as not to paint a frame in the wrong theme.

Two colours escape `mat.theme()` and carry their own `light-dark()` pairs:

- the categorical palette of the game categories, `styles/typologie-colors.css`: eight hues that no `--mat-sys-*` provides;
- `--app-accent`, when `BRANDING_ACCENT_COLOR` fills it in (otherwise it equals `--mat-sys-primary`, already a pair). It serves as a text colour on `--mat-sys-surface` and as a focus ring; [`core/branding.ts`](repo:src/main/webui/src/app/core/branding.ts) therefore sets it as a pair, each half bounded in OKLCH at constant hue: lightness capped in light, bounded from below in dark (with a chroma ceiling). Both bounds, because an operator picks their colour looking at one surface and the other is then the one nobody checked: a pastel accent fell below 4.5:1 in light. A browser that cannot parse the pair keeps the raw colour.

Three surfaces deliberately stay outside the theme:

- the brand toolbar (administration, staff espace) keeps its signage colour: an identity that changes with the time of day is no longer one. It carries text, so its `--app-toolbar-foreground` / `--app-toolbar-background` pair is measured like any colour written by the repository;
- the maps display OpenStreetMap tiles, content and not chrome; darkening them would require another tile provider. The badges, legend and list around them follow the theme;
- the PDFs are composed server-side to be printed: they follow the brand, never the browser's theme.

The toggle button lives only in the administration bar. The login page and the staff espace follow the preference saved by this browser, failing that the system's.

## The poll is not a leftover

The "a solver is running" state is never stored in the browser: it is read from the server, so that a solve started from another window also locks the buttons here.

It is read in two ways, on purpose. The SSE stream `/api/jobs/stream` gives the latency: a solve started elsewhere locks in one second instead of thirty. The polling loop underneath is the safety net, because SSE fails silently and the interface would stay frozen on a stale state without signalling anything, which is worse than a poll, which repairs itself at the next tick. The poll keeps control until the stream has proved it is alive, drops to 30 s while it is, and takes control back after 45 s of silence.

The two halves are two files. [`core/solver-stream.ts`](repo:src/main/webui/src/app/core/solver-stream.ts) holds the `EventSource` connection, its watchdog and its backoff, with no business state. [`core/solver-job.service.ts`](repo:src/main/webui/src/app/core/solver-job.service.ts) holds the poll and the jobs' state, and routes what the stream pushes through the same body as a poll tick: this is what makes the fallback honest. The joining of the score curve's deltas is a pure function of `core/score-trace.ts`. The server side is in [start, follow and stop a solve](api-calcul.md).

## A single global keyboard listener

[`core/keyboard-shortcuts.service.ts`](repo:src/main/webui/src/app/core/keyboard-shortcuts.service.ts) carries the only `keydown` set on the document for shortcuts (Ctrl+K, `g` then a letter, `/`, `?`, Ctrl+Enter). It is armed and disarmed with the administration shell: `/login` and the staff espace have neither the palette nor these destinations. Do not add a second one: two global listeners fight over the same keystroke without anything signalling it. The only deliberate exception is the shell's Konami code, a sequence with its own state.

The arrows of the calendars, of the Planning grid and of the data tables are not global listeners: they are set on the component's element, consume only the keys they use and let the rest bubble up to the global listener, which stops on an already consumed event. This is how you add a keyboard behaviour. Escape is written nowhere: no dialog uses `disableClose`, `MatDialog` already closes the topmost dialog and returns focus.

The table of `g`+letter and the palette labels live in `core/keyboard-shortcuts.ts`, and the destinations are derived from `app.routes.ts`: a page added tomorrow is reachable by keyboard without anyone having thought of registering it.

### The data tables

The roving `tabindex` of the five reference tables, and of the manual adjustments and locks tables (a single row reachable by Tab, arrows, Home/End, Enter to open, Space to tick), is shared in [`core/table-navigation.ts`](repo:src/main/webui/src/app/core/table-navigation.ts), next to `core/table-selection.ts` which it drives.

- You enter the table through the filter. The row that carries `tabindex="0"` sits behind the "select all" checkbox and behind one stop per sortable header: the number of Tab presses would change each time a column becomes sortable, and a gesture you have to guess does not exist. The documented gesture is therefore: "/" focuses the page's filter, Down arrow enters it. This `keydown` is set on the field of `shared/table-filter.ts`, which emits `enterTable`; the page calls `TableNavigation.focusCurrent()`. Nothing global is added. The timeslots page, with no quick filter, keeps entry by Tab alone.
- Focus is anchored on the row, not on its rank. A sort does not make it jump elsewhere, and a row removed by the filter puts it back on the place it last occupied, without which no row would carry `tabindex="0"` any more and the table would leave the tab order. That last rank is the signal's own previous value (`linkedSignal`), not a rank frozen when focus was taken: a sort applied in the meantime would send focus back to a position the row has left.
- Enter and Space are consumed only if something opens or gets ticked. A table with no selection gives Space back to the page; the timeslots refuse to open while a solve locks the edition, and the key goes on its way, the lock banner already saying why.
- No `aria-selected` on the ticked row. The attribute is supported on `role=row` only in a `grid`, whereas a `mat-table` exposes `role="table"`. The accessible state is the checkbox's, the change is announced by the CDK's `LiveAnnouncer`, and the highlighting goes through a class.
- A sortable header does not name itself. Material renders the header cell in a `role="button"` that it never names, and the accessible-name computation descends into the `aria-label`s of nested controls: making a column sortable was enough for the six explanation lines of its help button to become the sort button's name. `shared/sort-header-name.ts` cuts this coupling with an `aria-labelledby` to the column's title, and is needed only on a header containing something other than its title. `sortActionDescription` feeds `aria-describedby`, which describes the action without touching the name; hiding the help button would make it invisible to the screen reader while it is focusable.

The two-axis Planning grid (by stand, by person) keeps its own navigation: its geometry is not that of a list.

## Leaflet stays out of the initial bundle

Two places display a map: the "Locations" tab of `/stands` (all the locations, a marker dragged with the mouse that saves its position, entering a point in a location's form) and the "Map" view of `/journee`. The first is in a `@defer` block, the location form is loaded by an `import()` when a stand offers "New location…", the second in a page `@defer`. Leaflet alone weighs a 150 kB chunk: nothing loaded at startup must import it, otherwise this weight goes into everyone's bundle, the login page included. What the maps share (OpenStreetMap tiles, attribution, the `Referrer` policy they require, icon paths that survive the bundle) lives in `shared/leaflet-base.ts`, never copied.

## The only place where the frontend reimplements domain logic

`app/core/horaire-stand.ts` resolves a stand's hours rules into day-by-day windows, mirroring `HoraireStandResolver` on the server side, to preview a rule without a round trip. It evolves in the same commit as its Java counterpart ([stand hours and windows](horaires-et-fenetres-des-stands.md)).

## CSS

[`src/styles.css`](repo:src/main/webui/src/styles.css) is only an aggregator of `@import`s, with one partial file per screen under `src/styles/`. `branding.css` is imported first: all the others read `--app-accent`.
