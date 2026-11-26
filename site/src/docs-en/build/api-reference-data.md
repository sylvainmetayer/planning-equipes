---
twin: comprendre/api-referentiels
---
# Writing the reference data through the API

The reference data (stands, staff, timeslots, game categories, locations, adjustments) all follow the same CRUD pattern, partitioned by edition. You will know how identifiers are assigned, what a write refused for a concurrent modification or a solve in progress returns, how to check and derive the timeslot grid, and how to import a file. The [stand hours](api-horaires-des-stands.md) have their own page. The inconsistencies the API reports without refusing are in [entry warnings](api-avertissements-de-saisie.md).

## Identifiers and codes

An identifier is never chosen by the caller ([ADR 0050](repo:docs/decisions/0050-identifiants-generes-par-edition.md)). The creation `POST` assigns it and returns it: `A1`, `A2`… for a staff member, `S…` for a stand, `T…` for a game category, `L…` for a location, `C…` for an adjustment, numbered within each edition; `E1`, `E2`… for an edition. An `id` present in the body of a creation is ignored, and a number freed by a deletion is not reused. An adjustment is an exception: you modify it by sending its `POST` again with its identifier, which must exist (`400` otherwise).

Stands, game categories and locations carry an optional `code` (`STRATEGIE`, `JEU-LIBRE`), unique within the edition (`409` if taken) and never of the form of an identifier (`400`): it is the key that import files cite. A game category cited by a stand or a staff member (`typologiesProposees`, `competences`, `souhaits`) is written by identifier or by code, and it is the identifier that is stored. An edition name of the form `E` followed by a number is refused (`400`): the `edition` argument of the MCP tools would take it for an identifier.

Game categories form a reference data set referenced by foreign key: an unknown `id` answers `400`, and so does deleting a category still in use. `polyvalent` designates the category of all-rounder profiles, at most one at a time: setting it on another removes the flag from the previous one.

## What a write requires

- A stand carries at least one game category: `POST /api/stands` and `PUT /api/stands/{id}` answer `400` on an empty `typologiesProposees`, naming the stand. The check applies only at write time: an old row with no game category can still be read and solved, and is refused at its next save.
- A timeslot requires a date, a start and an end (`400` otherwise). An end earlier than or equal to the start is accepted: that is how a timeslot that crosses midnight is written (20:00 to 00:00 lasts four hours), and it alone reads a stand window dated the next day.
- Ad hoc constraints and locks are states: you delete them and recreate them. A pair of staff members cannot be both in incompatibility and in affinity (`400`); a target that is already locked returns `200` with no duplicate. See [ad hoc exceptions](exceptions-ad-hoc.md) and [locks](plan-verrous-et-publication.md).

## Concurrent modification

Without a guard, two tabs saving the same record one after the other let the second silently overwrite the first. Each row of the six reference data sets therefore carries `modifieLe`, the date of its last write, which the `GET` returns and which the form sends back as is ([ADR 0023](repo:docs/decisions/0023-modification-concurrente-par-horodatage.md)).

| `modifieLe` sent | Effect |
| --- | --- |
| equal to the row's | written |
| different | `409` with nothing written, body `{ message, code: "MODIFICATION_CONCURRENTE", modifieLe }` with the current value |
| absent or `null` | written without a check (an import, a script, a deliberate overwrite) |

The `code` distinguishes this `409` from the busy solver's. The UI offers Reload (nothing is written) or Overwrite anyway (the same payload goes out again without `modifieLe`); closing the dialog without choosing leaves the entry on screen.

The other writes that carry the precondition:

- Bulk editing sends one `PUT` per row: a row modified elsewhere is refused on its own, and the report asks you to redo the selection, the dialog still holding the stale timestamp.
- `PUT /api/ouvertures-stands/grille` (see [the hours](api-horaires-des-stands.md)) carries one per stand (`stands[].modifieLe`).
- `PUT /api/animateurs/competences/grille` carries one per record (`animateurs[].modifieLe`) but answers `200` with a report per row: `WRITTEN`, `STALE` (the record's `409`, with its current `modifieLe`) or `REJECTED` with its reason. A stale row does not cancel the others.
- A webhook (`PUT /api/webhooks/{id}`) reads it in `modifiedAt`, a consigne preset (`PUT /api/consignes/prereglages/{id}`) in `modifieLe`. A consigne laid down on several dates carries a `date → horodatage` table: a date with no entry is not checked, an entry for a date the request does not lay down is refused (`400`), and the refusal of one date cancels the whole laying down.
- The `PUT` of each of the four sections of the edition's settings (`/api/parametres/legal`, `organisation`, `notifications`, `calcul`) reads `modifieLe` in the body, the one the section's `GET` returned: a section written since answers the same `409`. The former roots (`/api/parametres-legaux`, `-qualite`, `-solveur`, `-notifications`) also return `modifieLe` but ignore it on write: the last writer wins, as in 3.x.
- The MCP `modifier_*` tools accept the timestamp as an optional argument; when omitted, they reread the row just before writing.

Imports do not carry it: they replace or merge a whole reference data set. The check is part of the write: a single SQL statement whose `ON CONFLICT DO UPDATE` carries the precondition and returns no row when it fails, because reading then writing would let two saves a millisecond apart through. Creation is checked the same way: an identifier drawn from the counter that is already taken (database edited by hand) answers `409` instead of replacing the row. The mechanism warns the second session without locking anybody. Persisting a solve does not touch `modifieLe`, otherwise every open record would conflict after each solve.

## Deleting, and what the deletion takes with it

`GET /api/stands/usages`, `GET /api/animateurs/usages` and `GET /api/creneaux/usages` quantify what references a selection, so that the confirmation says so before deleting: filled seats of the saved plan, manual adjustments, locks, consignes (always zero for a staff member). Repeating `id` counts several entities and returns an aggregated total, which leaves a bulk deletion to a single call; the client splits again on the encoded length of the request, identifiers being free text.

- The count informs without blocking: no threshold.
- An unknown `id` counts as zero (no `404`: the dialog is about rows already displayed and the deletion itself will say a row has disappeared). A request with no `id`, or with a non-numeric timeslot `id`, answers `400`; `/api/creneaux/usages` reads its `id`s as text so that the conversion fails in the resource and not before, as a `404`.
- An empty seat does not count.
- The four counters are not an inventory: zero everywhere is said by naming them, never "nothing references it", because other tables follow in cascade without being counted (swaps, a stand's hours and openings, skills and wishes).

Deleting a timeslot or a stand takes away the seats that were on it, and only those (`stand_id` and `creneau_id` are `NOT NULL`). Deleting a staff member empties their seats without deleting them: `animateur_id` is nullable, and a seat with no staff member is an unfilled seat, visible in `postesNonPourvus` and the coverage screens instead of making the plan look better covered than it is. These holes appear in a plan that nobody asked to solve again: they are real. In bulk, each deletion is its own transaction.

## Writing during a solve

As long as a solve holds the edition's solver, writing is refused (`409`, the body carrying the job and a `message` readable as is) for: deleting and modifying a stand, a staff member or a timeslot, resetting the edition (`POST /api/planning/reset`), importing a scenario, reassigning a seat (repair assistant, event-day mode, `affecter_poste`) and accepting a swap.

The landing of the solve deletes and reinserts all the seats from the starting plan, rewrites the date and hours of the timeslots, and saves the stands and staff again from the objects captured at the start. A seat reassigned during the solve would revert to its previous holder, an accepted swap would disappear leaving the request `ACCEPTEE`, a deleted staff member would be resurrected, personal data included. The reset and the import would in addition leave locations, hours and ad hoc constraints erased, and the old timeslots would reappear next to the new ones. Creations go through: an entity absent at the start is named by no seat of the result.

- The refusal is confined to the job's edition, compared with the current edition: a solve on a fallback variant blocks nothing elsewhere, and a solve launched on A blocks A even if the tab has moved to B. A solve that is merely queued blocks nothing.
- It comes before everything else in the method: a `404`, a `400` or a `409 REFERENTIEL_FIGE` give way behind it.
- It is placed on the service method by `@RefusedWhileSolving` (a CDI interceptor), never in the resource: the REST route and the MCP tool meet the same guard. `RefusedWhileSolvingStructuralTest` fails the build on a write method neither annotated nor classed open with its reason, and on an annotated method called through `this`, which would bypass the interceptor. Where no interceptor can run (a class that is not a bean), an explicit call, argued in that test, takes its place.

The other writes go through (game categories, locations, ad hoc constraints, legal parameters, locks, constraint weights, creations): the landing does not undo them, but the solve in progress will not reflect them. The UI locks these entries for the duration of the solve (`editingLocked`). An MCP assistant has no screen: the tool's response carries `RESOLUTION_EN_COURS` in `avertissements` ([ADR 0020](repo:docs/decisions/0020-avertir-dans-la-reponse-d-ecriture.md)), with the same scope as the refusal, and a refused write keeps its `409`. See [the MCP tools](mcp-outils.md) and [the solve](api-calcul.md).

## The timeslot grid

A timeslot is created alone (`POST /api/creneaux`) or in bulk by **the timeslot generator** ([ADR 0126](repo:docs/decisions/0126-un-generateur-de-creneaux-unique.md)), which produces dozens of timeslots at once from one of three sources: the templates (« gabarits », the day templates of 3.x) applied to their dates, the stands' hours, or a recurring pattern. Three gestures hold for all of them: `POST /api/generateur-creneaux/apercu` writes nothing and returns the verdict on the grid that would result, `POST /api/generateur-creneaux/application` writes, `POST /api/generateur-creneaux/retrait` withdraws dates from the templates' calendar (their timeslots stay).

```json
POST /api/generateur-creneaux/apercu
{ "source": "RECURRENCE",
  "recurrence": { "jours": "JOURS_SEMAINE", "dateDebut": "2026-07-06", "dateFin": "2026-07-19",
    "joursSemaine": ["MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY"],
    "exclusions": ["2026-07-14"],
    "fenetres": [ { "heureDebut": "09:00", "heureFin": "12:00" },
                  { "heureDebut": "14:00", "heureFin": "18:00" } ] } }
→ { "source": "RECURRENCE", "nombreGeneres": 18, "creneaux": [ … ], "controle": { … } }
```

`jours` reuses the stand hours selector (`TOUS`, `JOURS_SEMAINE`, `PLAGE`, `DATES`); a recurrence adds and never replaces. A generation that repeats an existing timeslot comes out as `DOUBLON` (`ERREUR`); `400` on a malformed request. A timeslot is a shift: an overlap on the same day is the normal shape of two staggered handovers, never an anomaly ([ADR 0037](repo:docs/decisions/0037-une-grille-est-toujours-des-vacations.md)). The 3.x routes (`/api/creneaux/recurrence`, `/api/creneaux/derivation`, `/api/journees-types`) remain aliases marked deprecated until 5.0, with their old shapes.

`GET /api/creneaux/controle` returns the grid's anomalies (`severite` `ERREUR` or `AVERTISSEMENT`, `type`, `date`, `message`), those of the stands' openings and the feasibility report (`null` with no stand or no timeslot). Two duration caps can be read there: beyond `parametresLegaux.dureeVacationMaxMinutes` (6 h by default, threshold of art. L3121-16) a `VACATION_TROP_LONGUE` warning, beyond what daily rest allows a `REPOS_QUOTIDIEN_IMPOSSIBLE` error. `GET /api/creneaux/diagnostic` describes the grid in place: number of shifts, dates, `joursFeries`.

**Public holidays.** The check adds `VACATION_JOUR_FERIE` (one line per date) when shifts fall on a public holiday and the edition has at least one minor available that day, because only adults sit there (art. L3164-6). With no minor available, it stays silent: a festival legitimately opens on 14 July. Nothing is blocked, the hard constraint already exists; the message counts the minors without naming them. `GET /api/jours-feries?debut=AAAA-MM-JJ&fin=AAAA-MM-JJ` returns the public holidays of a range (`[{ date, label }]`) for the entry screens. It is a calendar fact, no edition is read but the admin login is still required; two years at most, `400` on a reversed range, a range that is too long or an unreadable date. The scope is that of `JoursFeries` (the eleven days of art. L3133-1, mainland France outside Alsace-Moselle), and the client never computes Easter: the label travels with the date.

**The `STANDS` source: deriving the grid from the stand hours.** When the hours already exist, the grid follows from them: each hour at which a stand opens or closes is a cut, and each slice between two cuts where at least one stand is open becomes a timeslot. The request carries `derivation: { dateDebut, dateFin, heureFermeture, dureeMinimaleMinutes, remplacer }`:

- `heureFermeture` gives an end to the "until closing" windows (`00:00` for midnight: the last timeslot crosses midnight).
- A slice shorter than `dureeMinimaleMinutes` (15 by default) joins the next one, or the previous one failing that; a gap shorter than that is closed rather than splitting the timeslot in two.
- `remplacer` judges and, on writing, replaces the whole grid, the solved plan going with it; otherwise the timeslots are added.

Only the days a stand declares "open on these windows" take part: a stand that is silent on a day is open when the others are. The response carries the timeslots, the `coupures`, the `joursSansFenetre` and the `controle` verdict; `400` with no window at all.

**The `GABARITS` source.** The templates are managed under `/api/generateur-creneaux/gabarits` and their calendar under `PUT /api/generateur-creneaux/calendrier`; `GET /api/generateur-creneaux` returns both and the dates out of line. Applying materialises the calendar by difference (`application`: timeslots kept, updated, created, removed with their seats); a date without a template is never touched. `POST /api/generateur-creneaux/reconnaissance` goes the other way and replaces the templates with those the grid implies. A timeslot created on a day the edition did not count unfolds the stands' model, like one typed by hand ([ADR 0104](repo:docs/decisions/0104-les-horaires-d-un-stand-sont-une-liste-de-plages-datees.md)).

## File imports

Imports are partial and previewed: two `POST`s with the same body, `…/analyse` which returns a report without opening a transaction, then the write route which rereads the same file, replays all the checks and writes the accepted rows in one transaction. The write receives the file and never the displayed report, so that a replay or a fabricated body goes back through the same checks; the file is never written to disk ([ADR 0021](repo:docs/decisions/0021-import-tabulaire-partiel-et-previsualise.md), [ADR 0022](repo:docs/decisions/0022-import-de-la-grille-des-stands.md)).

| Import | Routes | Body |
| --- | --- | --- |
| Staff | `POST /api/animateurs/import-csv/analyse`, `POST /api/animateurs/import-csv`, `GET /api/animateurs/import-csv/exemple` | `{ fileName, content, mapping, replaceAnimateurs, replaceJoursIndisponibles }` |
| Stand grid | `POST /api/stands/import-grille/analyse`, `POST /api/stands/import-grille`, `GET /api/stands/import-grille/exemple` | `{ fileName, content }`; the sample is the edition's current grid, re-importable as is |

For staff, `mapping` associates a field with a column index (`null`: field absent, never overwritten); an absent `mapping` asks for the correspondence proposed from the headers, a mapping that is supplied but empty is taken as is. The report is the same for the preview and the write (`applied` tells them apart): `rows[]` carries per row its physical number, its verdict (`CREATED`, `UPDATED`, `REJECTED`), its refusal reasons and its warnings.

Refusals that concern the whole file, before any write: `400` for an empty, `.xlsx`, `.xls`, `.ods` or binary file, one of more than 1,000,000 characters or 5,000 lines, a non-UTF-8 file (the message quotes an excerpt), a mapping designating none of identifier, first name or last name, and a full replacement requested while a row is rejected; `409` with no timeslot in the edition or during a solve. The grid import neither creates nor deletes a stand and also answers `400` when no column matches a timeslot. The file formats (columns, dates and times, two-digit years, attaching a column to a timeslot) are described in [CSV imports](imports-csv.md).
