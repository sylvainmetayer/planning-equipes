---
twin: regles/enchainer-les-editions
---
# Chain editions

You create an edition, activate the one that is starting, start again from the previous year and compare two editions.

All the reference data (stands, staff, timeslots...) and the results are partitioned by edition ("Year 2025", "Year 2026"). Two browser tabs can work on two editions at once.

## Create an edition

1. Click "Manage" in the edition bar, above every page: the *Editions* page opens.
2. In "New edition", give a name. Avoid a name shaped like an edition identifier: the application refuses it.
3. Choose "Copy from" an existing edition, or "Empty edition". With a copy, you can decide whether or not to carry over the staff.
4. Click "Add".

![The list of editions](/images/fr/docs/editions.webp "The list of editions")

Each edition is born **inactive**.

## Activate an edition

Only one edition is **active**. Only it publishes, writes to staff, and opens their espace, their calendars and the wall display. You prepare the next one safely, then activate it in one gesture.

1. In the *Editions* page, click the "Activate" button (a flag icon, tooltip "Activate: only this edition will publish and write to staff members") on the row of the edition that is starting.
2. Read the preview of what the switch closes: the espace and calendar links and the wall displays of the old edition stop responding, its swap requests stay unanswered, and the solves queued on these editions are counted.
3. Confirm with "Activate".

Activation is refused while a solve is running on either of the two editions.

The "Action required" banner carries one line per situation: it names the edition and offers the action (Activate, Deactivate, Activate the next one), with the confirmation of the Editions screen; a reader only gets the link to that screen. When the simulated clock is on, the line says so. "Don't show again" hides one line, for that edition and that situation, in this browser. Deactivating an edition (same row, "Deactivate" button) closes everything: no edition is active any more, nothing is published or sent, and the espace links, calendars and wall display stop responding.

To work in another edition without activating it, use "Work in this edition" on its row: the edition bar shows the one you are viewing.

## Try a variant

Duplicate the edition to try a real variant (another grid, another team) and solve it alongside, sending nothing: a copy is born inactive. A same-day fallback plan (heatwave, storm) is set instead by a consigne in the active edition: see [Apply a consigne from the authority](appliquer-une-consigne.md).

## Compare two editions

The *Editions* view of the *Compare two versions* screen ("Compare two editions" button of the Editions page; the *Plan versions* view of the same screen compares two plans) shows what changed in the reference data from one edition to the other, for example last year and this year, or an edition and its variant.

- A summary in figures at the top ("+3 stands, -12 staff, +410 hours to fill").
- The detail: stands, staff arrived and left, game categories, locations, templates, timeslots aligned on the rank of the opening day, settings, adjustments counted by type, and the volumes of both side by side.
- Each row says what it was matched on (code, name to check, e-mail, identity), never on the identifier, and opens its profile in the edition that holds it.
- A duplication without the people is stated at the top rather than listing the whole team as departed.

The page is read-only, and the CSV export names no staff member.

## Start from scratch

"Empty this edition" starts again from an empty edition without touching the others or the instance settings. Before emptying it, as before importing a scenario into it, the application keeps a copy, schedule and locks included. No screen lists these copies: they are found and put back through the application's API, a technical operation. The procedure is in [Start from an edition, go back](../comprendre/restaurer-une-edition.md#les-sauvegardes-avant-vidage).

## Delete an edition

On the edition's row, "Delete" asks you to type the edition's name to confirm. An incorrect name erases nothing.

## After the event

- Compare planned and realised: [Follow the event day](suivre-le-jour-j.md#mesurer-le-réalisé-contre-le-prévu).
- Archive the edition: [Import and export files](importer-exporter.md#archiver-une-édition-terminée).
- Start from this edition for the next one, with "Copy from".
