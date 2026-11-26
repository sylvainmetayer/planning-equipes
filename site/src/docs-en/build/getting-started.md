---
twin: comprendre/demarrer
---
# Running the application locally and following the screen conventions

You will know how to install the toolchain, start the backend and the frontend with hot reload, avoid the traps of the development environment, then add or change a screen text without breaking the translation or accessibility.

## Setting up the environment

`mise install` reads `mise.toml` and installs Java 25 (Temurin), Maven and Node 26. You also need a container runtime: PostgreSQL and the Quarkus dev services require one. Node is needed only to work directly on the frontend, since Quinoa downloads its own during the build.

```bash
./mvnw quarkus:dev                     # backend + frontend, hot reload, :8080
./mvnw test                            # unit and integration tests, without the slow scenarios
./mvnw verify -DskipITs=false          # including the *IT (skipITs is true by default)
cd src/main/webui && npm test          # Vitest
```

`quarkus:dev` is enough: Quinoa starts `ng serve` and proxies it. The frontend is disabled under the `%test` profile. The local account is `admin` / `admin`. The `%test` profile opens the API without a session so that tests call it directly; `AuthentificationAdminTest` restores the real policy through `@TestProfile` to cover the real flow.

Without `mise` or Docker (a fresh container, Claude Code on the web), the full suite still runs with JDK 25 and PostgreSQL taken from `apt`: the recipe is in the repository's `sandbox-tests` skill (`.claude/skills/sandbox-tests/SKILL.md`). The test commands are detailed in [the tests page](tests.md).

### The C2 compiler stays on

By default, Quarkus dev mode stops the JVM at the C1 compiler (`-XX:TieredStopAtLevel=1`) to start faster. The solver then runs two to four times slower than in the packaged application, and a timed solve in dev measures dev mode, not the solver. The `forceC2` property in `pom.xml` is therefore `true`. To get a fast startup back during a session that launches no solve: `./mvnw quarkus:dev -DforceC2=false`.

### JDK 25 warnings

JDK 25 restricts native access (JEP 472) and flags `sun.misc.Unsafe` (JEP 498). Both sources are dealt with: `.mvn/jvm.config` silences what the Maven wrapper embeds (jansi, guava), and `--enable-native-access=ALL-UNNAMED` is in the `jvm.args` of `pom.xml` because the interactive console of `quarkus:dev` loads JNA. The production image carries no JVM flag.

## Local environment variables

`mise.toml` carries the variables everyone shares (`MAIL_ADMIN`). Those that depend on the machine go in `mise.local.toml`, at the root: mise loads it on top and `.gitignore` excludes it, so it is the only place to write a value that must not reach a public repository. Example to see the `/mentions-legales` page filled in (the notices are set by `LEGAL_MENTIONS`, described in [the environment variables](../installer/variables-environnement.md) and in [the legal notice](../installer/mentions-legales.md)):

```toml
[env]
LEGAL_MENTIONS = """\
editeur=Association Les Bénévoles du Jeu, 12 rue des Pions, 79000 Niort, RNA W791234567;\
directeur-publication=Camille Exemple, présidente;\
hebergeur=Hébergeur Fictif SAS, 1 avenue des Serveurs, 75000 Paris;\
contact=planning@exemple.invalid;\
responsable-traitement=Association Les Bénévoles du Jeu, représentée par sa présidente;\
base-legale=Exécution du contrat de bénévolat et intérêt légitime de l'organisateur (art. 6.1.b et 6.1.f RGPD);\
conservation=Jusqu'au 31 décembre de l'année suivant l'édition, puis suppression"""
```

A `quarkus:dev` that is already running does not reread the environment: restart it. The rest of the variables is in [running the instance](../installer/exploitation.md).

## Two traps of dev mode

**An F5 or a deep link on `:8080` returns 404.** Quinoa removes the `Accept` header from its requests to `ng serve` ([upstream bug #666](https://github.com/quarkiverse/quarkus-quinoa/issues/666)), which disables Angular CLI's SPA fallback. Production is not affected. Open `:4200` directly: `proxy.conf.json` redirects `/api/*` there to `:8080`.

**Mails really are sent**, to `localhost:1025`. `docker compose up mailpit` provides the sink and its interface on `:8025`. Only the test profile mocks unconditionally; `MAIL_MOCK=true` restores the mock elsewhere. Mailpit renders the HTML part: that is where you reread a template of `src/main/resources/templates/mail/` after changing it. The "Send a test mail" button of the Debug page is enough for the common layout, and `MailServiceTest`, `NotificationWriterTest` and `MailTemplatesTest` lock the text.

## Metrics locally

Prometheus metrics are on a second port, the management interface: `http://localhost:9000/q/metrics` in dev. Under `%test`, this port is drawn at random (`quarkus.management.test-port=0`) so that two suites launched side by side do not fight over it. A test reaches it through `@TestHTTPResource(value = "metrics", management = true)` (path relative to `/q`) or reads the `MeterRegistry` directly. The detail of what is measured is in [observability](../installer/observabilite.md).

## Files generated before each build

Two frontend files are not in the repository. `npm run generate` writes them, and `prestart`, `prebuild`, `prewatch`, `pretest` and `i18n-check` already call it: there is nothing to run by hand.

| File | Script | Content |
| --- | --- | --- |
| `src/app/version.ts` | `scripts/generate-version.js` | `APP_VERSION`: the exact tag if there is one, otherwise the short SHA ([versions and releases](../installer/versioning.md)) |
| `src/app/pages/nouveautes/news-data.ts` | `scripts/generate-news.js` | the git history (commit subject, date, version tag) that the What's new screen displays |

Both are in `.gitignore`, and `news-data.ts` also in `.prettierignore`: `JSON.stringify` writes it, a reformatting would not survive the next regeneration. What each commit means (under which heading it falls, how its sentence reads without its prefix) lives in `pages/nouveautes/news.ts`, tested by `news.spec.ts`. These rules copy those of `cliff.toml`, and a rule nobody tests drifts from its model.

A shallow clone shortens the What's new screen, and a tree without `.git` leaves it empty and says so. The `Dockerfile` copies `.git` for this reason and for `git describe`. For the same reason, `docker-ghcr.yml`, which builds the published image, uses `fetch-depth: 0`: with the default depth of 1, the screen of each image would list only one commit.

## Translating: i18n at runtime

The frontend is a single build, with no bundle per language. French is written directly in the templates; if English is chosen, `loadTranslations()` loads `public/i18n/messages.en.json` before `bootstrapApplication()`. Changing language reloads the page, because messages are resolved only once, at startup.

To add a string:

1. put `i18n="@@page.section.role"` in a template, or `` $localize`:@@id:…` `` in TypeScript;
2. add the translation in `messages.en.json`, with the same placeholders in the same order;
3. run `npm run i18n-check`.

A route's title is a function that returns a `$localize` string, never a literal: evaluated after startup, it is seen by `ng extract-i18n` and counted by `i18n-check`, and the page name that the screen reader announces is in its language. `app.routes.spec.ts` refuses a literal.

> Never call `$localize` at module level. The file would be evaluated before `loadTranslations()` and would stay frozen in French. Go through a `computed()`, a method or a function called from the constructor.

### What `i18n-check` refuses

Each of these discrepancies is invisible otherwise:

| Discrepancy | What would happen |
| --- | --- |
| An id without translation | the screen is displayed in French for an English speaker: `$localize` falls back to the source without saying anything |
| An orphan key | usually the forgotten half of a rename |
| Diverging placeholders | the literal token is displayed instead of the value, on that screen only and in that language only; neither the build nor the tests see it |
| A quoted label that matches nothing | when a message quotes another message word for word ("Opening collection", "Stop solver"), its translation must quote the English label of that id and not a paraphrase; the comparison covers only quotations of at least two words |
| The same id on two different French texts | the extractor keeps only one and the English catalogue has only one string: one of the two places shows the other in English |

None of these checks looks at the text itself. Rewriting a French source under the same id leaves the English saying the old version, and the check stays green: human review checks, on every pull request, that the English of an id has followed its French.

## Writing a screen text

A screen answers a question, it does not explain it. These rules keep prose from coming back hint by hint:

- Screen subtitle: one sentence that says what the screen answers, without saying how it computes it or its caveats.
- `mat-hint`: twelve words at most. Beyond that, it is a tooltip or the Help.
- No repetition in text of what the table, the form or the buttons already show.
- The why lives in the Help (`pages/aide/content/*`, and the staff espace help), anchored from the screen. The screen keeps the what.
- Ceiling of 200 characters per message, except the Help, the espace help, the legal pages and the liability warning of the dialog for disabling a legal rule. `npm run i18n-check` refuses it. The one exception, recognised from the text: a message that names a rule with its article ("art. L3132-1", "L4153-3") stays whole, because that is what makes the tool defensible.
- A confirmation says what happens and what is lost, in one or two sentences. A short message that reads well is not rewritten.

## Accessibility

These conventions apply to all screens: those of the administration and the four of the staff espace, the only scope read outside the organisation, on a phone, by people some of whom are minors. Following them costs little when writing and a lot to catch up on.

### Structure and announcements

- Skip link and focus returned to the page. The administration shell and the staff espace shell start with `<a class="skip-link" href="#contenu">` (style shared in `pages.css`) and wrap the screen in `<main id="contenu" tabindex="-1">`, refocused at each page change. In the espace, this is at each change of tab in the bar, not at each day chosen in the strip, which is only a view of the same page.
- A single `<h1>` per screen. `mat-card-title` renders a `<div>`: write `<h1 mat-card-title>` on the main title and `<h2>` on the following cards. `npm run headings-check` refuses a routed screen that does not have exactly one `<h1>`, and any `<mat-card-title>` written as an element. It reads only the components that `app.routes.ts` loads: the four tabs of `/diagnostic` and the five renderings of `/journee` are components of a page that already carries its own.
- The result of an action is announced through `app-status-message`, never through an inert `<p>`: `tone="error"` produces a `role="alert"`, the others a `role="status"`. The component renders nothing on an empty text, so do not wrap it in an `@if`: it is its creation with the text that makes the region announce. A verdict whose layout depends on its class (`marge-message`, `pauses-message`) keeps its element and carries `role="status"`. `npm run status-messages-check` refuses an element whose entire content is the interpolation of an error, a message or a verdict without `role` or `aria-live`. Its exceptions (content of a dialog read on opening, list row, banner present from the first render) are written one by one with their reason.
- A form error is tied to its field: `mat-error` in the `mat-form-field`, and `role="alert"` when it covers several fields.
- Focus survives the deletion of a row: `focusApresSuppression(...)` gives it back to the "Add" button.

### Icons, tooltips, colour

- A `<mat-icon>` that carries information writes `aria-hidden="false"` literally. `MatIcon` rereads the attribute through `inject(new HostAttributeToken('aria-hidden'))`, which does not see an Angular binding, and an element with `aria-hidden="true"` leaves the accessibility tree with its `aria-label` and the `aria-describedby` that `MatTooltip` adds to it. An icon that merely repeats the neighbouring text stays decorative, and then loses its `aria-label` and its `matTooltip`.
- A tooltip is not a name. `MatIcon` imposes `role="img"`, a role that does not take its name from its content: an icon with only a tooltip is announced as an anonymous graphic. The `matTooltip` is the description, the `aria-label` the name, and the two go together. `npm run icon-labels-check` refuses an icon carrying `aria-label`, `aria-labelledby` or `matTooltip` without a static `aria-hidden="false"`, and an icon exposed that way without `aria-label` or `aria-labelledby`.
- Colour is never alone: pair it with text, an icon or an initial. A tooltip does not exist on touch, nor on keyboard for an element that does not take focus (`<span>`, `<td>`): its text is doubled there by a `<span class="visually-hidden">`, because an `aria-label` on an element without a role is not reliably read.
- Every colour written by the repository is measured before being committed, except the `--mat-sys-*` tokens that `mat.theme()` supplies already compliant. The threshold is 4.5:1 for text and 3:1 for a component or a focus ring, measured on both surfaces, light and dark, since the theme compiles both. The measuring tool is `core/testing/contrast.ts`: a figure a test can replay is worth more than a figure taken once. An opacity on text is a measurement to redo, not a styling detail (an `opacity: 0.5` on an already muted ink once dropped a grid cell to 2.2:1).
- The brand accent is bounded in both schemes. The `accent` key of `BRANDING_COLORS` is chosen by an operator who often looks at the light surface. `core/branding.ts` therefore caps its OKLCH lightness in light mode and floors it in dark mode, and `branding.spec.ts` measures it on pastel accents that are deliberately bad. Raising one of the bounds without redoing the measurement makes the focus ring of the grids invisible below 3:1.

### Tables, links, gestures

- A table carries a hidden `<caption>` that says what it counts and over what scope, and its headers carry their `scope`, including a `<th mat-header-cell>`, whose `scope` Material never writes. `npm run table-headers-check` refuses a `<th>` that has none.
- A link that opens a new window says so. The `NewWindowLink` directive (`shared/new-window-link.ts`, selector `a[target="_blank"]`) adds "(new window)" as hidden text, and `pages.css` draws an arrow after a text link. A link named by an `aria-label`, which hides its content, carries the mention in that label through `newWindowLabel(…)`. `npm run new-window-check` refuses a component that opens a new window without importing the directive, and an `aria-label` that does not go through `newWindowLabel`. Do not touch the `rel`: the `noreferrer` of the staff espace links keeps the token in its URL from leaving in the `Referer`. Before announcing a new window, ask yourself whether it is useful: the legal pages open from the espace in the same tab, and "Back" returns there.
- Every mouse gesture has a keyboard twin (WCAG 2.5.7). Moving an assignment goes only through "Move to…" in the seat panel, which opens `shared/deplacement-dialog.ts`; there is no drag and drop. A cell that opens a detail is a keyboard stop (the constraint cross table: roving tabindex, Enter), never a `(click)` on a lone `<td>`.
- Single-key shortcuts can be turned off (WCAG 2.1.4): a checkbox in the "?" dialog and in Settings, Instance tab, a browser setting (`core/single-key-shortcuts.ts`). A new shortcut goes through `KeyboardShortcutsService`, which respects this setting.
- A long list is filtered, not scrolled: `app-selection-recherche` beyond a few dozen entries. With 153 staff members, a dropdown costs 153 keyboard arrows.
- A grid is traversed with the arrows (roving tabindex). Write `role="grid"` only if the row/cell structure exists and the arrows traverse it: the attribute switches the screen reader to application mode, and a grid that does not move with the arrows reads worse than an ordinary table. A read-only table stays a native `<table>`.

### What holds these conventions

The checks are listed in [the accessibility statement](../installer/accessibilite.md): the twelve a11y rules of `angular-eslint` at `error` (the three deactivations are commented at their line), five scripts of the `frontend` job (`headings-check`, `icon-labels-check`, `status-messages-check`, `table-headers-check`, `new-window-check`) and `e2e/accessibilite.spec.ts`, which runs axe-core on the representative screens, the four of the staff espace replayed in the `mobile` project. This spec compares against a frozen baseline that can only shrink: a screen that enters it with a *serious* or *critical* anomaly fixes it or lists it in `LIGNE_DE_BASE` with its reason.
