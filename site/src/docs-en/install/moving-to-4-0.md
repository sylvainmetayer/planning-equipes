---
twin: installer/passer-a-la-4-0
---

# Moving from 3.x to 4.0

You run a 3.x instance and want to know what 4.0 will ask of you. This page is an **outline**: 4.0 is in preparation, every change that breaks something adds its paragraph here when it is merged, and no release date is promised. Do not upgrade based on this guide until 4.0.0 is published.

## Where things stand

- **3.5** is the latest version published from the development line. The `3.x` line now receives only fixes, on the `release/3.5` maintenance branch ([how it is run](publier-une-version.md#3-patcher-une-version-antérieure)).
- The 4.0 breaking changes are tracked in tickets [#271](https://github.com/sylvainmetayer/planning-equipes/issues/271) (model), [#272](https://github.com/sylvainmetayer/planning-equipes/issues/272) (freeze, versions, settings), [#273](https://github.com/sylvainmetayer/planning-equipes/issues/273) (rules), [#274](https://github.com/sylvainmetayer/planning-equipes/issues/274) (MCP and screens) and [#281](https://github.com/sylvainmetayer/planning-equipes/issues/281) (constraint batches).
- How long 3.x stays supported: see [the maintenance policy](versioning.md#combien-de-temps-une-ligne-est-corrigée).

## Before upgrading

1. Stay on the latest 3.x and read its release notes: a 3.x release may already warn about what 4.0 removes.
2. Make a [backup](sauvegarde-et-restauration.md#2-sauvegarder-à-la-main). The schema only moves forward: a database migrated to 4.0 does not go back under a 3.x image.
3. Read the "⚠️ Attention" entry of the 4.0 release notes, then the sections below.

## What changes

Each paragraph says *before*, *after* and *what you do*. The paragraphs are written as merges land; those not yet written say "Coming".

### Environment variables

#### White labelling and legal notices: twenty-six variables become eight

- **Before (3.x)**: fourteen `BRANDING_*` variables (name, organisation, logos, mascots, colours, PDF palette, support address) and twelve `LEGAL_*` (legal notices, accessibility statement, demonstration flag).
- **After (4.0)**: eight variables. `BRANDING_PRODUCT_NAME`, `BRANDING_ORGANISATION`, `BRANDING_SUPPORT_EMAIL` and `LEGAL_DEMO_INSTANCE` do not change. Four **grouped** variables, of the form `key=value;key=value` (a literal semicolon is written `\;`), replace the others: `BRANDING_IMAGES`, `BRANDING_COLORS`, `LEGAL_MENTIONS` and `LEGAL_ACCESSIBILITE`. The old names are **still read** throughout 4.x: startup warns for each one, the grouped key wins when both are set, and they will be removed in 5.0. The refusal to start without the five required notices stays in place.
- **What you do**: at your own pace before 5.0, replace each old variable with its key, then remove it from your environment (compose, `.env`, deployment CI).

| Old variable | Grouped variable, key |
| --- | --- |
| `BRANDING_LOGO_URL`, `BRANDING_MASCOT_URL`, `BRANDING_MASCOT_ICON_URL` | `BRANDING_IMAGES`: `logo`, `mascot`, `mascot-icon` |
| `BRANDING_PDF_LOGO`, `BRANDING_PDF_STRIP` | `BRANDING_IMAGES`: `pdf-logo`, `pdf-strip` |
| `BRANDING_ACCENT_COLOR` | `BRANDING_COLORS`: `accent` |
| `BRANDING_PDF_HEADLINE`, `BRANDING_PDF_MUTED`, `BRANDING_PDF_ACCENT`, `BRANDING_PDF_HIGHLIGHT`, `BRANDING_PDF_PILL` | `BRANDING_COLORS`: `pdf-headline`, `pdf-muted`, `pdf-accent`, `pdf-highlight`, `pdf-pill` |
| `LEGAL_EDITEUR`, `LEGAL_HEBERGEUR`, `LEGAL_CONTACT`, `LEGAL_BASE_LEGALE`, `LEGAL_CONSERVATION`, `LEGAL_RESPONSABLE_TRAITEMENT`, `LEGAL_DIRECTEUR_PUBLICATION` | `LEGAL_MENTIONS`: `editeur`, `hebergeur`, `contact`, `base-legale`, `conservation`, `responsable-traitement`, `directeur-publication` |
| `LEGAL_ACCESSIBILITE_ETAT`, `LEGAL_ACCESSIBILITE_DATE_AUDIT`, `LEGAL_ACCESSIBILITE_CONTENUS`, `LEGAL_ACCESSIBILITE_SIGNALEMENT` | `LEGAL_ACCESSIBILITE`: `etat`, `date-audit`, `contenus`, `signalement` |

The details and an example are in [the environment variables](variables-environnement.md#marque-et-documents) and [the legal notice](mentions-legales.md).

### Proxy header mode is removed

- **Before (3.x)**: `REMOTE_USER_ENABLED=true`, with `REMOTE_USER_SECRET`, let an access proxy authenticate the administrator (`REMOTE_USER_ADMIN_EMAIL`) and open the staff espace without a code, through a header. A 3.x release already warned that it was "deprecated".
- **After (4.0)**: the mechanism no longer exists. The five variables `REMOTE_USER_ENABLED`, `REMOTE_USER_HEADER`, `REMOTE_USER_SECRET_HEADER`, `REMOTE_USER_SECRET` and `REMOTE_USER_ADMIN_EMAIL` have no effect any more. If one is set, startup says so in a warning and carries on; the warning disappears in 5.0. A proxy's header no longer opens anything, neither the administration nor a staff member's espace.
- **What you do**: **before** upgrading, set up [sign-in through an OIDC provider](oidc.md) or create [local accounts](comptes-locaux.md), and check that a named administrator account opens. Staff members go back to the six-digit code sent by e-mail. Then remove the `REMOTE_USER_*` variables from your environment. Your proxy can stay in front of the instance as a network filter, but it no longer replaces sign-in.

### The on-demand machine launcher is removed

- **Before (3.x)**: `SOLVER_WORKER_HOOK_URL` and `SOLVER_WORKER_HOOK_SECRET` connected a launcher that created a rented machine for the duration of a solve, with `SOLVER_WORKER_INSTANCE`, `SOLVER_WORKER_START_TIMEOUT` and `SOLVER_WORKER_IDLE_TIMEOUT`. The Solver screen showed the state of that machine, with "Prepare" and "Stop", and the repository shipped `deploy/launcher/`.
- **After (4.0)**: the application no longer starts or stops any machine. The five variables above have no effect any more: if one is set, startup says so in a warning and carries on; the warning disappears in 5.0. The **permanent worker** (`SOLVER_WORKER_URL`, `SOLVER_WORKER_SECRET`, `SOLVER_WORKER_TLS_FINGERPRINT`, `SOLVER_WORKER_DEFAULT`) does not change, nor does the rule that a remote failure fails the job. The "On this server" or "On the compute machine" choice stays on the Solver screen.
- **What you do**: if you used the launcher, rent and switch on the machine with your host's tools, start the worker on it ([the procedure](calcul-sur-une-autre-machine.md)) and set `SOLVER_WORKER_URL` and `SOLVER_WORKER_SECRET`. Without that, your solves run on this server. Then remove the five variables from your environment.

### YAML scenario format

- **Before**: the application writes `schemaVersion: 1`.
- **After**: it writes `schemaVersion: 3`. It still reads v1 files (key absent or equal to 1): they go through a chain of conversions, link by link, as the model changes during 4.x. What the chain cannot convert is ignored, and every ignored item appears in the import report's warnings, never silently. The 1 → 2 link renames the game categories' `ninja` key to `polyvalent`, losing nothing; the 2 → 3 link converts the stands' opening hours into plages (see [Stand opening hours](#horaires-des-stands)).
- **What you do**: nothing to read your old files, end-of-event archives or copies taken before a wipe. A v2 scenario cannot be imported into a 3.x application ("newer format"): keep a v1 export before upgrading, it is your way back. The v1 reader is removed in 5.0.

### Schedule rule names

Coming: mapping of old names, rules merged or removed.

### MCP tools

**Before**: 157 tools whose names almost all start with a verb (`lister_stands`), with about ten exceptions (`equite_planning`, `volumes`…). **After**: tools are named `<verb>_<object>`, the verb taken from a closed set ([ADR 0100](repo:docs/decisions/0100-convention-de-nommage-des-outils-mcp.md)), some are grouped or removed, and there is **no alias**: a tool is published only under its current name. The mapping, old name then new name or "removed", is in [the old tool names](../comprendre/mcp-outils.md#anciens-noms-doutils), filled in as 4.0 changes land (for now no tool is renamed).

**What you do**: if you have written instructions, prompts or scripts that name tools, reread them against this table; an assistant that reconnects rereads the tool list and has nothing else to change.

### The "ninja" game category becomes "all-rounder"

- **Before**: the game category that makes its holders available on every stand was called "ninja": `ninja` flag of the game categories (REST, YAML, CSV, MCP tools) and `typologie.ninja` column.
- **After**: it is called "all-rounder" (`polyvalent`). Migration `V140` renames the column and its index, and fixes the field names already recorded in the action journal. The rule's name (`preserverBufferPolyvalents`), its weights and its switch do not change. API responses, YAML and CSV exports and the MCP tools' field say `polyvalent`; the derived keys follow (`polyvalentConfigure` of the fragility and tension reports, `typologiePolyvalenteDefinie` of the staffing-need report). The screens say "All-rounder category". The stands' `premium` flag keeps its name and is displayed as "Premium stand".
- **What you do**: nothing for your data or your files. The old names are still read throughout 4.x (`ninja` JSON and YAML key, `ninja` CSV header, the latter flagged line by line in the import report) and removed in 5.0. Only your scripts that *read* a `ninja` key from an API response or an export need to move to its new name.

### Stand opening hours

- **Before**: a stand stated its hours through **recurring rules** ("every day from 10:00 to 12:00 then from 14:00 to closing", or every Saturday, or a date range, or specific dates), plus dated exceptions. The application folded these rules at every read, and the screen offered to "compact" dated entries into rules.
- **After**: a stand carries a **list of dated plages**, an opening or a closure per day ([decision 0104](repo:docs/decisions/0104-les-horaires-d-un-stand-sont-une-liste-de-plages-datees.md)). A recurrence is no longer stored: it serves to write plages. Migration `V141` converts your rules over the days of each edition (from the first to the last timeslot, plus the day after a timeslot that runs past midnight), **with the exact reading the application had of them**: a test replays it on each shipped scenario and compares, day by day, the windows obtained with those of 3.x. The YAML moves to version 3 likewise, the `plages` key replacing `horaires`, `ouvertures` and `indisponibilites`.
- **What you do**:
  - **Export each edition to YAML before upgrading** (Files, "Export the current data as a scenario"). It is your way back, and the only way to recover the detail of your rules: the migration does not go back down.
  - **An edition with no timeslot at all has no day to lay its rules on.** They are neither converted nor lost: the migration keeps them aside, in the tables `stand_horaire_avant_4_0` and `stand_horaire_fenetre_avant_4_0` (dropped in 5.0), and writes one warning per edition concerned in the startup log (`Édition X : ses horaires récurrents de stand ne sont pas convertis…`). To recover them: add the timeslots to that edition's YAML export, then re-import it (the conversion link lays the rules on the days of the timeslots **of the file**; a file with no timeslot drops them, with a warning in the import report), or enter plages.
  - **A day added afterwards** to the event (a timeslot on a date the edition did not count) no longer has a rule to govern it: the stand is open all day there, even if it "only opened at weekends".
  - The recurring-hours entry screen, the "Compact the hours" action and the MCP tool `compacter_horaires_stands` have nothing left to do: the screen is replaced by a plages editor in a later change, and meanwhile a rule the current screen sends is converted at once into plages over the days of the event (refused with `400` in an edition with no timeslot, never lost in silence). The MCP tool `ajouter_horaire_stand` still exists: it writes plages on the days of the edition's timeslots, and refuses an edition with no timeslot. `effacer_horaires_stand` is removed (see [the old tool names](../comprendre/mcp-outils.md#anciens-noms-doutils)).

### Data and migrations

The migrations that convert data are described above, one per change, as they are merged: for now, [the stands' opening hours](#horaires-des-stands).
