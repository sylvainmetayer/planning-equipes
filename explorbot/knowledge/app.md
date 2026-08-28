---
url: "*"
title: Planning Équipes — general knowledge
---

Single-page Angular 22 + Angular Material app served by Quarkus. The UI is in
French by default (an FR/EN switch exists in the top bar). Domain vocabulary is
French: *animateur* = staff member, *stand* = booth, *créneau* = time slot,
*emplacement* = location, *affectation* = assignment, *édition* = the event
edition (a tenant-like scope: almost every screen works on the **current
edition**).

Navigation: a left sidenav lists every screen. Main routes:
`/` (Solveur), `/stands`, `/emplacements`, `/animateurs`, `/creneaux`,
`/typologies`, `/ad-hoc-constraints`, `/verrouillages`, `/editions`,
`/calendar`, `/day-calendar`, `/constraints`, `/hours`, `/staffing`,
`/ouvertures`, `/heatmap`, `/timeline`, `/kpi`, `/graphe`, `/comparateur`,
`/what-if`, `/instantanes`, `/problemes`, `/echanges`, `/notifications`,
`/parametres`, `/debug`, `/aide`.

Behaviour to expect:
- Angular Material dialogs are used for every create/edit form; they close on a
  "Enregistrer" / "Valider" button and show a snackbar toast afterwards.
- Tables are `mat-table`, often with a filter field and paginator.
- Loading uses `mat-progress-bar` / `mat-spinner`; wait for them to disappear.
- Deletions ask for confirmation in a dialog whose confirm button is red.
- Data changes are pushed live over SSE, so a table may refresh on its own.
