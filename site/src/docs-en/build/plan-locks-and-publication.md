---
twin: comprendre/plan-verrous-et-publication
---
# Locks, freeze, replanning, swaps and the published plan

You will know what the solver keeps or reopens when you rerun a solve on an existing plan, what distinguishes a lock from a reference-data freeze and from an ad hoc exception, how a swap of staff members is applied, and why the staff espace reads a different plan from the one the administration works on.

## A lock freezes seats

`@PlanningPin` marks a seat as **validated and frozen**: no move can change its staff member. The field is neither set by the solver nor persisted on the seat: it is recomputed every time the problem is built, from the lock table (`VerrouillagePlanning`).

A lock targets a staff member, a stand, a day, a timeslot, or a staff member × timeslot pair (`TypeVerrouillage`: `ANIMATEUR`, `STAND`, `JOUR`, `CRENEAU`, `ANIMATEUR_CRENEAU`). The last one is laid automatically when a swap is validated.

**A lock is not an ad hoc exception.** Both take freedom away from the solver, but not at the same moment nor on the same matter. An [ad hoc exception](exceptions-ad-hoc.md) is a tailor-made rule laid *before* the solve (place or exclude someone) that every solve honours, including one that starts from scratch. A lock is a gesture on a plan that is *already solved*: it keeps what a solve produced on one part of the schedule, says nothing about what should be there, and holds only for the saved plan. Imposing or forbidding an assignment is a job for the ad hoc exception; protecting a validated part of a plan you rerun is a job for the lock. On the organiser's side, see [Setting exceptions and locks](../regles/ajustements-et-verrous.md).

**One target, never two.** In the database, a lock is a flat row (five nullable target columns and a discriminator) that the `CHECK` constraint keeps consistent. In the code, the target is a sealed hierarchy: a variant carries only its own fields and cannot designate two. The `switch` that writes the columns is exhaustive with no `default`, so adding a way of locking does not compile until somebody says where it lands.

Two rules:

- **an unfilled seat is never frozen.** Filled seats are re-seeded with the staff member of the last solve; those that were empty stay movable, otherwise freezing a hole would make it permanently unfilled;
- **a frozen seat is scored normally.** A lock can therefore leave a visible deviation; it silently disables no rule.

**Pinning is not enough to lock a staff member**: it freezes the seats they hold, but the solver could assign them others elsewhere. Two hard constraints forbid that (`animateurVerrouilleFige` and `animateurVerrouilleCreneauFige`), based on the locks passed in as problem facts.

**A timeslot lock names its shift** (day, start time, end time), not its identifier. `creneau.id` is a `BIGINT` identity: deleting the timeslots of a day and then recreating them identically (grid regeneration, typical day reapplied) gives new ones. With an `ON DELETE CASCADE`, any deletion silently took the lock with it, whereas validating a swap lays two locks precisely so that the next regeneration does not undo the swap. The identifier remains stored as a cache, rewritten at every read by a join on the natural key, and is `NULL` as long as the grid does not carry that shift. A lock in that state **waits**: the Locks screen says so, and the grid check reports it, including on the preview of a derivation, that is, before "replace the grid" is written. The scope of an ad hoc exception follows the same rule, with one more consequence: a rule written for a timeslot that has disappeared is worth **nothing** instead of being worth the whole edition.

Reviewing a day ("reviewed and accepted") is a different state from the lock ([decision 0039](repo:docs/decisions/0039-validation-de-relecture-distincte-du-verrou.md)): validating never lays a lock unless asked.

## The reference-data freeze freezes records

The lock freezes **seats** of a solved plan; the freeze freezes the **records** from which that plan is solved, once their preparation is finished ([decision 0052](repo:docs/decisions/0052-gel-du-referentiel-distinct-du-verrou.md)). The organiser sets and lifts it by family (`ReferentialFamily`), in one edition:

| Family | What can no longer be written |
| --- | --- |
| Stands | Creation, deletion, min and max staffing, adults-only reserve, proposed game categories, hours (rules, dated openings and closures) |
| Timeslots | Creation, deletion, date, hours, break cover; typical days applied, derivation, series |
| Game categories and locations | Creation, deletion, cap per game category, all-rounder game category |
| Skills | Skill levels of staff members already registered (record, grid, import) |

A frozen family refuses writes **by every path** (form, bulk edit, CSV import, grid import, MCP) with a `409` carrying the code `REFERENTIEL_FIGE`. Still free: a stand's name, place and effort level, a game category's label and description, a location's name and position, personal data other than skills (availability, wishes, declarations, e-mail), the **creation** of a staff member with their skills, adjustments, locks and **consignes**, and the tool provided to close a band late without destroying anything. Scenario import and reset, which replace everything, are refused as long as a family is frozen.

A freeze is not copied when an edition is duplicated: the new one starts again in preparation. It does not affect a solve in progress. In the code, the guard is an annotation (`@RefusedWhileFrozen`) on service methods, carried by `GelReferentielService`; the routes are `GET /api/editions/courant/gel` and `PUT`, `GET` and `DELETE /api/editions/courant/gel/{famille}`.

## Incremental replanning

It answers "it is 9:00, someone has dropped out, what do we do?". It **starts from the persisted schedule**, pins everything that is still valid and reopens only the rest, hence a default budget of 60 s (`SolveBudgetPolicy`). It is started by `POST /api/solve/incremental/async` or by the MCP tool `resoudre_incremental`.

Matching is **positional**, on stand × timeslot, like that of locks: seats of the same stand on the same timeslot are interchangeable, so no seat identifier needs to survive a change in the reference data.

| Situation of a seat | What is done |
| --- | --- |
| Timeslot already started | **Pinned as worked**, before any other rule: holder kept even if invalidated since, empty seat left empty, never reopened by the scope. Counted separately (`postesPasses`). See [the frozen past](horaires-et-fenetres-des-stands.md#le-passé-est-figé) |
| Designated by the requested scope | Released no matter what: the user says "redo this" |
| Holder still valid | **Pinned** |
| Holder invalidated by a late change (deleted, unavailable, covered by a forced unavailability) | Released: pinning would freeze a hard deviation that nobody could correct any more. The validity test reuses the solver's predicate, so the two cannot diverge |
| Never filled, or new | Left free |

Two choices to know about:

- **the freeze is volatile**: it lives for the duration of the job and writes nothing. A lock remains what the user lays deliberately, never an artefact left by a replanning;
- **everything valid is pinned**, including outside any explicit lock. A replanning exists to *stabilise* the plan, not to re-optimise it. Reopening an area is an explicit act: name it in the scope, or run a full solve.

The result carries the statistics and the **crew diff** per stand × timeslot, because partial replanning is only useful if you can say who is affected. A permutation between interchangeable seats of the same stand does not appear in it: nobody's schedule changed.

## Re-seeding a full solve

A full solve **starts from the persisted plan** when there is one ([decision 0024](repo:docs/decisions/0024-repartir-du-plan-enregistre-par-defaut.md)). Each seat receives the staff member the plan gave it, by the same positional matching as locks and replanning, and **stays movable**. Nothing is pinned apart from explicit locks. On this single point it is the opposite of incremental replanning: replanning stabilises, re-seeding re-optimises without throwing away what has been gained.

The `reamorcage` parameter takes three values (`Reamorcage`): `AUTO` (the default, start from the plan if there is one, from scratch otherwise), `PLAN_COURANT` (fail rather than cold-start when there is no plan), `AUCUN` (cold start, the only gesture that abandons what has been gained and, for that reason, is confirmed on screen).

| Seat in the persisted plan | On re-seeding |
| --- | --- |
| Holder still valid | Re-seeded with them, **movable** |
| Covered by a lock | Left as the locks have put it (pinned) |
| Holder gone, unavailable that day, or covered by a forced unavailability | Starts empty, counted as "released" |
| Never filled, or new | Free |

The solver scores the starting solution first and keeps the best one it meets: with equal data, the result never falls below the starting plan. It can fall when the reference data has changed in the meantime (the starting plan is no longer a solution of the new problem) or on a cold start.

## Swap requests

A staff member proposes a swap from their espace; **nothing is applied without explicit validation by the administration**. The espace routes are under `/api/espace-animateur/{jeton}/demandes` and the administration's under `/api/echanges`.

The seat given up is referenced by its **(timeslot, stand)** pair, never by the id of the `poste_affectation`: those ids are renumbered at every solve, the pair survives.

**The colleague's agreement.** A request is born `EN_ATTENTE_CIBLE` (`StatutDemandeEchange`). The colleague accepts it, and it becomes `PROPOSEE` and the administration is notified, or declines it (`REFUSEE_CIBLE`), which is terminal. The administration can refuse a request that is still waiting, but can accept it only **after** the colleague's agreement (`ACCEPTEE`, `REFUSEE`).

**The simulation** judges on the **overall hard score**, not on the touched seats alone: a swap can break a constraint elsewhere, weekly hours or rest (`GET /api/echanges/{id}/impact`). The swap is crossed if the target also holds a seat on the timeslot, a simple takeover otherwise. A **directed** swap trades two distinct timeslots ("I leave you my Monday, I take your Tuesday").

**Application** surgically updates the seats concerned, **without re-solving**, then lays two `ANIMATEUR_CRENEAU` locks on the timeslot each person **receives**: the next regeneration does not undo the swap, without freezing the rest of both staff members' schedule.

**The announcement waits for publication.** Deciding a request mails nobody on the spot: the swap changed the working schedule, not the one the requester received, and their espace still shows the previous one. The decision, accepted or refused, travels with the publication that carries it, alongside the requests still pending.

**Schedule fair closed**: submissions and cancellations are refused server-side, and the espace switches to read-only (schedule visible and downloadable, history kept).

The espace link and its authentication (opaque token, code sent by mail) are described in [accounts and roles](acces-et-comptes.md).

## The published plan

Two schedules coexist per edition, and they need names. The **working schedule** is the one the solve writes and the administration works on: `poste_affectation`, only one per edition. The **published schedule** is the one staff members have received: the latest snapshot marked as published (`plan_snapshot.publie_le`). It is a snapshot and nothing else, because its denormalised content ([decision 0007](repo:docs/decisions/0007-instantanes-contenu-denormalise.md)) is exactly "a complete schedule, independent of what moves afterwards".

**The staff espace reads the published schedule.** What a person sees is what they were sent: a validated swap, an applied replacement or a new solve moves their espace only once published. As long as nothing has been published on the edition, the espace is **empty** and says so: falling back to the working schedule would recreate the inconsistency that the distinction removes.

**The espace names its edition.** The edition's name and bounds go down to the page (`editionNom`, `editionDebut`, `editionFin` of `GET /api/espace-animateur/{jeton}`), to the header of the individual PDF and to the name of the subscription calendar (`X-WR-CALNAME`). Nothing is stored for this: the name comes from the `Edition`, the bounds are derived from the timeslots, and an edition with no timeslot carries its name without dates. The case that motivated it: a staff member returning from one year to the next has **two** valid espace links in their mailbox and two strictly identical pages, and picking the wrong link gave a coherent schedule, but not this year's.

**Publishing writes only to the people concerned** ([decision 0025](repo:docs/decisions/0025-stabilite-du-plan-publie.md), [decision 0011](repo:docs/decisions/0011-publier-plutot-qu-envoyer-a-tous.md)). The unit of comparison is the **shift** (day, effective hours, stand) and the identity compared is the person's, not the seat's: a `poste_affectation` renumbered by a solve moves nobody. Renaming a stand therefore wakes nobody; swapping two shifts wakes exactly two people. A difference is expressed as an addition, a removal, or a **move** when an addition and a removal answer each other on the same day: "Ninja 14:00-18:00 replaces Cirque 14:00-18:00" is one change, not two (`PublicationDiffService`).

**A deleted shift is announced.** The published side carries the day and window of each assignment, copied at capture: deleting a timeslot from a day already published removes the seat only from the working schedule, and the remaining difference is a removal, with a name on it. The shift stays visible in the espace, the PDF, the ICS subscription and the day-before reminder until the publication that announces its removal: what promised it is also what takes it back. A snapshot captured before the date was stored does not carry this information and is resolved against the current reference data.

This calculation is deliberately **outside the snapshot comparison**, which is non-nominative by choice (fairness is a dispersion of hours, never a ranking of named people). It answers "has your timetable changed since what we sent you?", one person at a time, and only for those it is going to write to. The publication routes are described in [api-planning-et-publication](api-planning-et-publication.md).
