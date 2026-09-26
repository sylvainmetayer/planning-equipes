# AGENTS.md — Frontend (`src/main/webui`)

Directory-scoped memory for the Angular frontend, loaded when a session works
under `src/main/webui`. The root `AGENTS.md` holds everything else — the i18n
rules, the language policy, the domain invariants, the documentation rules —
and this file does not repeat them. When one of the three frontend rules the
root file keeps changes (no new dependency, one question = one screen, both i18n
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
  hard-coded colour in `src/styles/` is now a dark-mode bug, not a nit. Three
  colours are not `mat.theme()`'s to switch and carry their own `light-dark()`
  pairs: `styles/typologie-colors.css` (a categorical palette has no
  `--mat-sys-*` equivalent), `--app-positive` in `styles/branding.css` (the
  "better" green of an écart or an affinity: Material 3 has no success role,
  so a page needing it reads that variable instead of writing its own pair),
  and `--app-accent` once `BRANDING_ACCENT_COLOR`
  fills it — `core/branding.ts` writes it as a pair, the configured colour on
  light and an OKLCH-lightened twin on dark, because it is a *text* colour on
  `--mat-sys-surface` in a dozen partials. Deliberately outside the switch: the
  brand toolbar, the OpenStreetMap tiles of the maps, and the
  server-side PDFs — see `docs/architecture.md`.
- Shell: `app/app.ts` is a bare `<router-outlet/>`; the admin chrome
  (`mat-toolbar` + `mat-sidenav`, the solver `app-job-monitor`, the logout
  button) lives in `app/shell/admin-shell.ts`, a layout route wrapping every
  admin page. The drawer is **one menu for everybody**, grouped by moment of
  the edition's cycle — Accueil / Planning / Préparer / Construire / Diffuser /
  Aujourd'hui / Administrer — with no simple or advanced mode: a rare screen
  goes down its group, it is never hidden. Its foot lists the news and the
  four legal pages in text (the accessibility statement must be one click
  away from every page). While this browser has not opened the news of the
  running version (`core/news-seen`, a chrome preference), a small
  « nouveautés » link to them sits next to the Aide entry, and the foot's
  news link carries a dot, still in sight when the group is folded.
  `shell/nav-groups.ts` is the one
  table behind the drawer, the command palette and the `g`+letter shortcuts:
  `buildNavGroups()` the menu, each entry with its icon (never shared by two
  entries), its letter and the tabs or views the palette indexes;
  `buildOffMenuLinks()` the **routes served but listed in no group** —
  `/debug` (raw technical information, reached by Ctrl+K or its address),
  `/nouveautes` (the foot of the menu) and the Quarkus Dev UI in development; `buildLegalLinks()` the legal pages.
  `nav-groups.spec.ts` fails on a route of `app.routes.ts` that none of the
  three knows. The standalone routes `/login`, `/animateur/:jeton` (espace animateur, issue
  #165) and `/mural/:jeton` (« Affichage mural » — the control room's
  television, opened by a dedicated token and no session, ADR 0053: the stands
  of the day with the shift under way and the next one, over the pure
  `pages/mural/mural.ts`; it reads the server every minute and keeps its last
  state offline, turns its pages every fifteen seconds, and `?impression=1`
  lays the whole day out for print) render outside it — no admin navigation,
  no admin polling. So does `/impression/:date` (« Impression de la journée »
  — « Imprimer cette journée » of the Planning page: the same print layout of
  `pages/mural`, route data `apercu`, read under the admin session from
  `GET /api/affichage-mural/apercu`, never through a token; a 401 sends to
  `/login` like any admin call). The espace has
  five child routes of its own: `/animateur/:jeton` (« Mon planning » — three
  tabs chosen by `?onglet=jour|apercu|coequipiers`, the day on screen carried by
  `?jour=`: the day with its strip, its state band and its seat cards; the
  whole planning as four figures and a frieze coloured by typologie; and « suis-je
  avec quelqu'un ? », the teammates ranked by shared shifts and searched by
  name), `/animateur/:jeton/echanges`, `/animateur/:jeton/disponibilites`,
  `/animateur/:jeton/covoiturage` (« Covoiturage » — « Je viens avec… », asked
  for apart from the declaration on the same collection window, read-only
  outside it) and `/animateur/:jeton/aide`.
- **One question = one screen, its variants as tabs or views in the URL.**
  A functional block is one route and one `app/pages/<block>/` folder; a
  variant of the same question (`?onglet=`, `?vue=`) is a tab of that page,
  never a second route, and a route may be served without a menu entry. The
  palette indexes the tabs and views declared in `shell/nav-groups.ts`, not
  only the routes. Admin routes (children of the shell):
  `/` (default, « État de l'édition » — read in one call from
  `GET /api/editions/courant/etat`, its form decided by the server's phase:
  while preparing, the checklist of the cycle, one line per step with its
  state and a link to the screen that moves it — the solve read in sentences,
  identical coherence anomalies merged with their count, the freeze a state
  linking to Paramètres; during the event, the day under way first, opening
  `/jour-j`, and the checklist folded; after it, the archive first; on an
  empty edition, a « Démarrer » block. « À traiter aujourd'hui »
  (`#a-traiter`, where the toolbar's bell lands) is always drawn, and folds
  under it `pages/accueil/messages-recents` — the night's alerts and the
  local history of the application's messages, what `/notifications`, now a
  redirect to `/#a-traiter`, used to show), `/solveur` (the solver page,
  the former home: feasibility first, then « Ce calcul tiendra compte de »
  read from `GET /api/solve/entrees`, each counter a link and a zero muted,
  the three ways to launch each with its sentence on the page, the result in
  sentences with « Voir le planning », « Relire » and « Publier » under it,
  the volumetry folded),
  `/debug` (« Débogage » — the raw and the technical only, served everywhere
  and reached by its address or Ctrl+K: two tabs chosen by
  `?onglet=resolution|verifications`, the raw analysis with the version and
  the API docs, and the checks — test notification, test exception, test
  mail, Mailpit, pgAdmin; a guard on the route sends its former
  `?onglet=donnees` and `?onglet=yaml` to Fichiers' examples and validator),
  `/mcp-client`,
  `/parametres` (« Paramètres » — three tabs chosen by
  `?onglet=edition|mural|instance`: the edition's own settings that no rule
  reads — its name, the freeze of its referential (the only place it is
  edited), its guichets (the collection of availabilities, the foire au
  planning, the covoiturage that follows the collection — each opened and
  closed here with its dates, Échanges and Disponibilités keeping one line of
  their state, `shared/guichet-etat.ts`), the e-mails it sends of itself
  (`#emails`) and the organisation's contact shown in the espace —, the wall
  display links — created, shown once with their QR code, revoked —, and
  « Instance », what the operator configured and what holds for the whole
  database — nightly backup, SQL dump, the single-key shortcuts of this
  browser, and « Date et heure simulées »
  (`pages/parametres/horloge-simulee-card`, over `PUT /api/horloge`), shown
  only where the server allows a simulated clock, which the toolbar's
  hourglass links to; `?onglet=globaux`, its former name, reads as
  `instance`, and a guard sends `?onglet=legaux` to `/regles?onglet=legal`
  and `?onglet=emails` to `#emails`), `/regles`
  (« Règles du planning » — three tabs chosen by `?onglet=legal|qualite|calcul`:
  one dense row per hard rule — short label, article, on/off, the thresholds
  it reads edited on the row —, the same table for the medium and soft rules
  with their importance in three positions (faible 1 / normale 5 / forte 25,
  `core/importance.ts`, ADR 0057), and the solve budget, the start of the
  evening and the ninja typologie; nothing is written before the tab's
  « Enregistrer », and `?regle=<name>` opens that rule's panel — long text,
  exact weight, history of its settings — on its own tab, the stable address
  the Diagnostic and the reading of the score link to),
  `/stands` (« Stands » — two tabs chosen by `?onglet=`: the table, filtered
  by `q` and by the `?typologie=` and `?emplacement=` chips, every column
  sorted by `?sort=&dir=`, a name — or Entrée — leading to the fiche with
  that sort, a game category to `/typologies`, a location to the other tab,
  « Ouvert » the open days and seats read from the openings report and, once
  a plan holds somebody, « Couverture »; « Ajouter » the guided creation
  `stand-creation-dialog.ts` over the pure `stand-creation.ts`, whose last step
  writes the stand's row through the Horaires des stands grid's save — a
  failure of that second call keeping the stand, a retry sending the hours
  alone —, « Édition groupée » the bulk edit of the ticked stands or else of
  the rows shown, « Saisir en grille », « Importer la grille » and « Compacter
  les horaires » in a « Plus » menu, and « Dupliquer » the stand form on a
  copy; and `?onglet=lieux`, « Lieux », the
  former `/emplacements` — the `pages/emplacements` component drawn in a
  `@defer`, a map of every located place drawn by `lieux-map.ts`, whose
  dragged marker saves the position with no dialog, a sorted table with the
  stands attached. The two tables write the same `q`, `sort` and `dir`, each
  only while it is on screen (`viewParams` of `core/reference-table-page.ts`);
  the page follows `queryParamMap`, a link to the Lieux tab from the table
  reusing it. `?edit=<id>` is sent by the route's `standEditToFiche` guard —
  run again on a query-only change (`runGuardsAndResolvers`) — to
  `/stands/<id>?modifier=1`, the Lieux tab keeping its own `edit`),
  `/stands/:id` (« Fiche stand » — the
  head of one stand, « Modifier l'identité » the stand form cut down to its
  identity, `?modifier=1` opening it on arrival; its grid day × timeslot edited
  in place in `stand-grid-editor.ts` over the pure `stand-grid.ts`, built on
  `pages/ouvertures/grille-horaires.ts` and saved by the same body as the
  Horaires des stands grid — the route's `canDeactivate` asks before unsaved
  cells are dropped —; its opening anomalies; its seats after a solve, an
  empty one opened in the Siège panel of the Planning page; « Comparer avec… »; its
  history; « Règles », folded, the condensed form, and the whole stand form
  behind « Modifier les règles ». « précédent / suivant » walk the table in
  the order of its `?sort=`, over the pure `pages/stands/stand-order.ts` the
  table reads too),
  `/animateurs` (every name a link to the fiche, Entrée on a row too,
  carrying the list's view — `q`, `sort`/`dir`, the acknowledgement,
  category, wish, minor and manager filters — in the fiche's URL; the
  consultation dialog is gone), `/animateurs/:id` (« Fiche animateur »
  — one person on one page and every gesture on them: a head of actions — the
  espace link copied, the planning mailed through the service the MCP tool
  `envoyer_planning_animateur` uses, the reminder, the PDF and the ICS, the
  whole planning locked or released, the adjustment form pre-filled on the
  person, the fiche's form — and « précédent / suivant » through the list's
  view, rebuilt by the pure `pages/animateurs/animateur-roster.ts` the list
  itself reads; seven foldable sections read in one call from
  `GET /api/animateurs/{id}/fiche`, which narrows the Équité and Fragilité
  reports to that person rather than recomputing them, the first three open
  and `?section=` — or a `#fragment` — opening and scrolling to another:
  identity, the availability strip whose day, clicked, is made unavailable by
  `PUT /api/animateurs/{id}/jours-indisponibles/{date}` — its seats of that
  day freed in the same call and each offered to the bench dialog of the Siège
  panel, a locked one kept in place and named with a link to the locks, ADR
  0056 —, the planning read on time (`animateur-timeline.ts`, the former
  `/timeline` screen, drawn only while its section is open and read again
  after every gesture of the fiche that moves the plan, every shift a link to
  `/journee?date=&siege=`), load and fairness with the radar
  (`equite-radar.ts` over the pure `radar.ts`, `?axes=` and `?comparer=`),
  the competences edited in place — one person's, the grid staying the bulk
  entry —, the fragility with « Verrouiller » and « Former », the follow-up.
  Every animateur name of the application links here), `/competences`
  (« Compétences » — the animateur × typologie
  grid of appreciations and wishes — the heart of a cell, or S —, saved row by
  row; a spreadsheet block pastes from the current cell after a preview,
  `?animateur=` narrows it to the person the Animateurs form or the fiche sent
  — the only bulk entry of appreciations, the form shows them read-only, with
  no CSV export or import),
  `/fichiers` (« Fichiers » — the two halves of one gesture, export, correct
  in the spreadsheet, import back, and what closes an edition: three tabs
  chosen by `?onglet=importer|exporter|archive`. « Importer » is
  `pages/imports/importer-panel`, one card chosen by `?cible=` — the referential
  imports in the order data is entered, the stand matrix, the scenario file,
  « Exemples » (`pages/imports/exemples-card`, the bundled scenarios under a
  readable name and one sentence from the pure `exemples.ts`, never a file
  name) and « Vérifier un fichier » (the YAML validator); every referential
  card also takes cells pasted from a spreadsheet (`collage-tableur.ts` over
  the pure `collage.ts`, which reads the quoting of Excel and LibreOffice) and,
  once written, links to the screen of its rows narrowed to them — `?ids=`
  (`core/imported-rows.ts`), read by the Stands, Typologies, Emplacements,
  Créneaux and Animateurs lists and shown there as a filter to drop.
  « Exporter » is `pages/exports/exporter-panel`, the CSV archive and the
  scenario file; « Archive » the end-of-event ZIP the server streams,
  `pages/exports/archive-evenement-card`, which the home screen links to once
  the event's last day is past. The referential screens open the same import
  card in a dialog through `shared/import-button.ts`, without leaving the
  list),
  `/publication` (« Publication » — what reaches real people: the planning
  documents to print or archive, and the mailing to every animateur whose
  schedule changed; a screen of its own under Solveur since issue #320, so the
  solver page only solves. Before sending, the review table — one row per
  person, ordered by `?tri=nom|ampleur`, minor changes folded by
  `?mineurs=masques`, each row a checkbox that **defers** that person's message
  rather than dropping it, ADR 0047), `/export-csv`, `/creneaux` (one path
  first — day templates laid on the calendar, or applied without being kept;
  a single timeslot, recognition and derivation from the stands' hours sit in
  one « Autres façons de créer la grille » menu; the grid check is a band read
  by itself on every visit and after every write), `/typologies` (every count
  links to the list it counts, the ninja category is ticked in the table, and
  three columns read the computed plan: seats, hours, assigned without the
  skill),
  `/consignes-solveur` (« Consignes au solveur » — what the next solve must
  respect, three tabs chosen by `?onglet=ajustements|verrouillages|consignes`:
  the manual adjustments (the API path and the domain type keep the
  `ContrainteAdHoc` name; `?personne=` narrows the table to the rows naming
  someone, by name or id, and `?ids=` to the rows a problem named), the locks
  (`?animateur=`) and the consignes (a band an arrêté closes for every stand on
  a date, the compensation chosen, the presets; issue #4 / ADR 0043;
  `?date=…&nouvelle=1` opens the form on that date, `date=demain` on the day
  after the server's today). Each tab emits `changed` after a write, and the
  page then offers « Relancer le calcul » in place; `/ad-hoc-constraints`,
  `/verrouillages` and `/consignes` redirect to their tab, their params kept —
  the network of pairs (`?vue=reseau`, `?paires=`) is gone),
  `/journee` (« Planning », the former Journée, issue #712 — the plan at the
  top: the title on one line with the renderings beside it; the day chosen on
  a foldable mini-month, `pages/journee/mini-mois.ts` over the pure `mois.ts`,
  each day marked with its empty seats, its reading, its lock and its
  consigne, « aujourd'hui » being the server's date from `ConsignesStore`;
  the stand and animateur filters as `shared/selection-recherche` autocompletes;
  the relecture as one line of four chips (`relecture-barre.ts`) — empty
  seats, breaks without relay, locked seats, changes — each **narrowing** the
  rendering on what it counts (`?sieges=vides|verrous` on the table,
  `?relais=sans` on the breaks, the Changements rendering), the comment and
  « Relu et accepté » in its menu (`relecture-dialog.ts` over the former
  panel); the consigne in one line (`consigne-ligne.ts`); « Imprimer cette
  journée » (`/impression/:date`) and « Afficher sur la TV » (Paramètres ›
  Affichage mural). One day under five renderings chosen by
  `?vue=calendrier|rail|carte|pauses|changements`: the table stands ×
  timeslots across the whole width (`calendar-day-vue`, one name per chip,
  empty seats in red, a closed timeslot in a neutral « fermé », a partial
  opening as its hours), the rail animateur by animateur, the day replayed on
  the emplacement map with one time cursor — and under it the load per
  emplacement, place × span or `?charge=evenement` place × day, over the pure
  `carte-jour/charge-emplacement.ts` —, the breaks and meals (the intendance of
  the day under them, `pages/intendance/intendance-jour`), and what changed since
  the last publication or the last solve (`?reference=publication|resolution`,
  absent = the publication when one exists; `?lecture=animateurs` for the
  per-person reading); `?comparer=<day>` puts a second day beside the first
  on the calendar and the rail — aligned lines, a banner of écarts, read-only,
  `?ecarts=1` for the differences only — in `pages/journee/comparaison-vue`;
  one day selector (`date`, and `jour` read as a date or as the number of the
  former screens) and the
  same `stand`, `animateur` and `q` filters in the URL — with `emplacement`
  and `typologie`, the four kept across the axes: `?axe=stand|personne|typologie`
  turns the page from one day to the whole event (absent = the day). « Par
  stand » and « Par personne » are two readings of the shared
  `pages/planning-grille` (a frozen first column, one tab stop moved by the
  arrows, templates for the header and the cell, a sort asked of the caller):
  per stand, a density in `?densite=noms|compteurs|couverture`, closed told
  from empty, a total per day, and the treemap of the former Répartition des
  heures as `?vue=treemap` (`pages/repartition-heures`, its day in
  `?jourTreemap=`); per person, the Équité and payroll columns, all sortable,
  the always-empty ones hidden until `?colonnes=toutes`, the frise as
  `?vue=frise`, and the two CSV files. « Par typologie » is a table whose
  figures are links. The one evening is `EquiteService`'s settable start; the
  fixed 22:00 is the payroll's night alone (ADR 0055). The plan and the breaks
  read once by the page and handed to the rendering on screen — the views under
  `pages/calendar-day`, `pages/rail-jour`, `pages/carte-jour` and
  `pages/pauses`, and `pages/journee/changements-vue`, are its components, not
  routes; a click on any cell of them — a name or a free seat, a shift, a
  stand of the map, a break without relay, a line of the changes — opens beside
  the rendering the **Siège panel** (`shared/siege-panel/`, an `aside` labelled
  by its title, focus moved to it and given back on close — to the cell drawn
  in the opener's place, found by its `data-siege-cle`, when a gesture re-read
  the plan meanwhile —, Escape handled on the panel itself; on the rail, whose
  shift labels are out of the tab order, Space on a line opens it): the seat
  explained, then Remplacer, Déplacer vers (the move dialog, whatever
  `GLISSER_DEPOSER_ACTIF` says — the flag governs the pointer gesture only),
  Libérer (ticked by default, it also locks the person off that timeslot, as
  an accepted échange does), Verrouiller, the bench read-only, the fiche;
  Libérer and Remplacer send the holder shown as `occupant`, a precondition
  the write holds; on an empty seat « Qui peut tenir ce siège ? », the bench
  of that seat in `bench-dialog.ts`, whose « Placer » — offered to nobody a
  lock or a started timeslot would refuse — seats and, ticked by default,
  locks, and « Poser un ajustement ».
  It reads the plan the page loaded, never one per cell, and asks for the
  candidates on demand; the seat is `?siege=<poste id>`, and `?creneau=` (with
  `stand=`), the bench's old keys, is resolved to a seat once the plan is read),
  `/diagnostic` (« Diagnostic » — what blocks, what is missing, where it is
  tight, what is fragile, as tabs chosen by
  `?onglet=problemes|besoin|tension|fragilite`. Problèmes: each card says
  « Où » (its hotspots, each opening the Planning page's Siège panel on that
  stand and timeslot), « Qui » (each name opening `/animateurs/:id`) and the
  gestures `ConstraintCatalog` gives the rule, in its order, « Baisser
  l'importance » last and hidden at the lowest importance
  (`core/importance.ts`); « Qui peut tenir ce siège ? » opens the bench dialog
  in place (`shared/siege-panel/seat-placement.ts`) and its « Placer » fills
  the seat without leaving; « Où se concentrent les écarts »
  (`pages/problemes/ecarts-pivot*`) sits under the cards, open, narrowed by
  `?regle=` and read along `?axe=`. Besoin: every bound and every day links to
  the screen that changes it, the « avant » margin is a column and a grid
  (`pages/marge/margin-before-grid.ts`), « À former » (`pages/formation`) its
  last section. Tension (`pages/marge/tension-tab.ts`): the « après » margin
  crossed with the fragility, one column per start hour of the grid, the fill
  the margin's sign and the grade a frame, each cell opening the Siège panel
  of its timeslot. Fragilité: « Verrouiller », « Qui peut remplacer »,
  « Former » per person. The tab components keep their own view state in the
  URL next to the page's key; `?onglet=banc` is sent to the Planning page by
  `benchTabToJournee`, `?onglet=former` to `?onglet=besoin&section=former` by
  `trainingTabToNeed`), `/echanges`, `/jour-j` (« Mode jour J » — the day-of screen:
  mark somebody absent, repair the seats they held), `/versions` (« Versions du
  plan » — the finished solves and the snapshots of the edition in one
  chronology over the pure `versions.ts`, `?editions=toutes` for every
  edition's; two ticked rows, or one and « Plan en place », open the A/B
  comparator as a panel beside the table, `?comparer=a,b` keeping the pair;
  `/kpi`, `/instantanes` and `/comparateur` redirect there, the replay of the
  former Autopsie is gone; the Solveur shows its five latest rows),
  `/ouvertures` (« Horaires des stands » — three
  views chosen by `?vue=`: the grid, the default, read and typed in one place —
  one field per stand and timeslot, an empty cell is closed, and behind each
  field the bars of the layers ticked in `?couches=` (the result, the stand's
  own hours, the consigne) drawn as one `background-image` over the pure
  `rendu-grille.ts`, a focused cell explained in a sentence by
  `calendrier-couches.ts` with its links, `?du=`/`?au=` narrowing the days;
  `?vue=journees-types`, the same cells said once per template;
  `?vue=comparer&stands=a,b&ref=a`, two to eight stands against a reference,
  read-only, over the pure `comparaison-ouvertures.ts`, its copy opening the
  stands' bulk edit preset. The former `?vue=journee` and `?vue=calendrier`
  are redirected by the route's guard, `vues-retirees.ts`, to `/journee` once
  a plan is computed or to the grid narrowed to the day, and to the grid with
  its layers),
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
  `/solveur`; `/imports` (its `onglet` becoming the Importer tab's `cible`),
  `/exports` (its `archive-evenement` fragment landing on the Archive tab),
  `/export-csv` and `/validateur-yaml` redirect to `/fichiers` with their
  params, `/constraints` to `/regles` — its `?regle=` kept, a `#rule` anchor
  turned into it; `/data-transfer` and `/data-setup` are
  legacy redirects too, `/decoupage` now landing on `/creneaux` since the
  slicing was removed, kept for old bookmarks/links; so are `/emplacements`,
  landing on `/stands?onglet=lieux` with its params, and `/timeline`, the
  former « Timeline animateur » — `?animateur=X` lands on
  `/animateurs/X?section=timeline`, no person on `/animateurs` —, and so
  are the eight former screens `/day-calendar`, `/rail-jour`, `/carte-jour`,
  `/pauses`, `/problemes`, `/staffing`, `/fragilite` and `/banc-de-touche`,
  whose redirects carry their query params along, renamed where the page now
  owns the key — `/banc-de-touche` landing on `/journee` with its `creneau`
  and `stand` —, `/marge`, landing on `/diagnostic?onglet=besoin`, or on
  `?onglet=tension` for its `mode=apres|tension`, and the three the Planning page absorbed in issue #712:
  `/calendar` (its `date`, `stand` and `animateur`; the `month` it showed is
  the day's own), `/intendance` (`?vue=pauses`) and `/graphe` (`?vue=carte`),
  through `redirectToPlanning` of `app.routes.ts`, and the six its axes
  absorbed in issue #713: `/heatmap`, `/repartition-heures` (`?vue=treemap`,
  its `date` becoming `jourTreemap`) and `/typologies-planning`, then `/hours`,
  `/repos` and `/equite` on `?axe=personne` — `/equite?vue=fiche&animateur=`
  landing on the fiche's « Charge et équité » section,
  `/animateurs/:id?section=equite`, its `axes` and `comparer` kept).
  Adding a functional block means adding a route, a `app/pages/<block>/`
  folder and its line in `shell/nav-groups.ts` (a group, or the off-menu
  list), never a new section inside an existing page.
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
  sort kept in the URL (`?sort=&dir=`, over `table-sort.ts`: every column, ids
  in natural order, empty cells last), selection keyed on the sorted rows,
  roving tabindex, reload, open by the row's name, duplicate (`duplicate.ts`
  names the copy), « Exporter cette liste » (`csv-export.ts`, the server's CSV
  dialect, what the table shows), paste of a spreadsheet block
  (`paste-rows.ts`, then `shared/paste-preview-dialog.ts`, then one bulk save),
  the two deletes. `typologies`, `emplacements` and `stands`
  extend it and keep only a `ReferenceTableConfig` plus what they do
  differently; TypeScript only, the templates stay per page since that is what
  genuinely differs. `animateurs` stays out — its acknowledgement filters,
  chips and plan column go beyond a config — and `creneaux` because it
  generates and groups its own; both still use `table-sort.ts`, `csv-export.ts`,
  `paste-rows.ts` and the shared row menu, so the five tables behave alike;
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
  apply one value down a column) and the paste of a spreadsheet block, planned
  then previewed then applied (`planCollage`, `appliquerCollage`), generic over the cell so a grid only says how
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
  field, `row-menu.ts` — the « ⋯ » of a row (modify, duplicate, delete, plus
  the page's own items), `filter-chips.ts` — the active filters as removable
  chips, `empty-state.ts` — an empty referential: what it holds, the previous
  step when it is missing, « Charger un exemple » on an empty edition,
  `row-warning.ts` — a save's warning kept on its row
  (`ReferenceCrudService.warningsOf`), `paste-preview-dialog.ts`,
  `detail-dialog.ts` — the read-only "consultation" view left to emplacements
  and stands (their « Détail » menu item), whose content each page builds in a
  plain `<entity>-detail.ts` next to it — animateurs have their fiche instead,
  `command-palette-dialog.ts` and `keyboard-shortcuts-dialog.ts` — Ctrl+K and
  `?`, `sort-header-name.ts` — the one directive: a `mat-sort-header` holding a
  control must name itself, or the generated sort button borrows that control's
  `aria-label`), and `app/pages/<page>/` holds one folder per route.
- **One global keyboard listener, and it already exists.** Ctrl+K (command
  palette), `g`+letter (navigation — the initial of the destination wherever
  it was free: `g s` the solver, `g a` the animateurs, `g g` the home), `/`
  (the page's filter, marked by `data-page-filter`), `?` (the shortcut list,
  the `g`+letter table included) and Ctrl+Enter (submit the active form) all
  go through `core/keyboard-shortcuts.service.ts`; its destinations are read
  from `shell/nav-groups.ts` — the menu in its order on an empty query, the
  tabs, the off-menu routes and the legal pages on a query only. Single-key shortcuts are
  suppressed while the focus is in a text entry — modifier combinations are
  not, since Ctrl+Enter is meant to be pressed from inside a field — and can
  be turned off on a browser (WCAG 2.1.4: dictation fires one per word said
  outside a field), a chrome preference in `core/single-key-shortcuts.ts`
  offered by the `?` dialog and by Paramètres. Escape is
  deliberately not implemented: `MatDialog` already closes the topmost dialog.
  The three forms that keep an auto-saved draft (fiche animateur, stand,
  consigne) are the one exception, and it stays inside the dialog: they set
  `disableClose` and route Escape and the backdrop through
  `shared/brouillon-dialog.ts`, which asks before throwing a modified form
  away — through the dialog's own `keydownEvents()`, never a `document`
  listener. Do not add a second `document`-level
  `keydown`; the Konami easter egg of the shell is the one accepted exception.
  Arrow navigation inside a widget — the calendars, the planning grid, the reference
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
  drawer's folded groups), `core/news-seen` (the news read) or
  `core/panel-collapse` (a page panel, e.g. the
  solver's score curve). An unsaved entry is neither, and has its own module:
  `core/brouillon-formulaire.ts` keeps the draft of the three long forms,
  scoped by edition, 24 h at most, sessionStorage for the fiche animateur and
  never localStorage — its bounds are `docs/rgpd.md` §7. What is typed and not
  saved is asked of one place, `core/formulaires-modifies.ts`, which those
  forms register with and the Solveur page reads before « Calculer ». The default of a control is
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
  pages draw the same thing — the two calendars, the imports that Fichiers and
  the import dialog of the referential screens both host), declared
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
  (the maps, reached only by the `@defer` of the Lieux tab of `/stands`, by
  the location form a stand form loads with `import()` and by the `@defer`
  block of `/journee` around its map rendering — it is a
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
  does. Tag a spec that way when it solves a real-world fixture, or when it
  needs what that stack alone offers — the simulated clock
  `passe-fige.spec.ts` sets (`e2e-suite.yml`'s `horloge-simulee` input,
  refused on the ordinary stack, where `jour-j.spec.ts` checks the refusal) —
  never to hide a slow test. `tests.yml` and `e2e.yml` also skip a push that touches
  only `docs/` and Markdown — minus the files a backend test reads or the
  `test` job compares to its build, which are re-included by name. Locally
  they **erase the database
  they target**: run them only against a disposable stack (see
  `docs/developpement.md` § Tests de bout en bout).
