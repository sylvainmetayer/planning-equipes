---
twin: comprendre/horaires-et-fenetres-des-stands
---
# Hours, consignes and effective windows

You will know how a stand's hours become opening windows, how an edition's consigne (an official order, such as an arrêté, relayed to the whole event) closes them again without destroying anything, why the past is no longer rewritten, and what a seat carries when its timeslot is open only in pieces.

## Three states per day for a stand

Each day is decided independently. A day cannot carry both closures and openings: the write refuses it, which avoids arbitrating a conflict at solve time.

| What the stand declares that day | Reading |
| --- | --- |
| Neither closure nor opening | Open without restriction, the overwhelmingly common case |
| At least one closure | Open by default, closed only on the listed windows. The solve subtracts the union of the closures that overlap the timeslot: a closure in the middle leaves two open segments |
| At least one opening | **Closed by default**, open only on the listed windows. This is the case of a stand that opens on a few timeslots only: one opening instead of one closure per timeslot |

An empty `heureFin` means "until closing": the window runs to the end of the timeslot being evaluated. A window cannot straddle midnight, unlike a timeslot. A window dated on event day +1 is read only by a timeslot that really crosses midnight.

## Headcount is carried by the window

A stand whose load varies during the day (4 people in the morning, 5 in the evening) stays **a single stand**: each opening window carries its `effectif`. Empty, it inherits `effectifMin`, so that a window that names none generates what it generated before the field existed. The minimum is what a stand requires at its quietest hour: applying it to the peak would understaff it.

Saving a stand from the entry grid **derives its two bounds from its cells**: `effectifMin` is the smallest value entered, `effectifMax` the largest, in both directions (a stand declared at 5 whose cells never exceed 2 ends up at 2). The stand's profile remains the place to set a capacity that the grid does not show. A window's headcount cannot exceed `effectifMax`, which both the validator and the form refuse: otherwise generation would make mandatory more seats than the stand is declared able to hold.

A timeslot straddling two windows of different headcounts produces **two groups of seats**, each carrying the [effective window](#fenêtre-effective) of its segment. Where two windows overlap, the **higher** headcount applies: satisfying the larger satisfies both. Contiguous slices of the same headcount are merged back. `segmentsOuverts` is the primary computation; `segmentsOuvertsMinutes` is its projection and remains the pure opening geometry, where two segments separated only by headcount become a continuous opening again.

### Two fields the solver does not read

`effectifMax` bounds the entry (stand validator, opening set by a consigne, derivation from the grid) and nothing else. The number of seats of a timeslot is its window's headcount, failing that `effectifMin`, and no rule caps the number of staff on a stand. Raising `effectifMax` adds no seat: it is the window headcount that has to change.

`manager` is a profile label (list filter, column of the CSV imports, comparison between editions). No rule reads it: a staff member marked manager is scheduled like the others and counts in the duration caps, legal ones included. Supervision by managers outside the schedule is an organisational fact, which is why `mineurNecessiteEncadrementMajeur` is switched off by default; switched on, it requires an adult scheduled on the same stand and the same timeslot, manager or not.

## Recurring hours: three layers, one mode per day

Dated windows are **exceptions**. The pattern that repeats is entered above them, as rules (`HoraireStand`) carrying a mode (`OUVERTURE` or `FERMETURE`) and a day selector (`TypeJoursHoraire`) of increasing specificity: `TOUS` (0), `JOURS_SEMAINE` (1), `PLAGE` (2), `DATES` (3).

Resolution is done calendar day by calendar day (`HoraireStandResolver`):

1. **a window dated that day** wins alone and entirely replaces what the rules said about the day;
2. **otherwise the rules that cover the day**, at maximum specificity, as a union of their windows. Two rules of the same specificity and opposite modes on days that intersect are refused on write. For data that arrived another way (a hand-written scenario), the resolver keeps a deterministic arbitration: `OUVERTURE` wins, the most restrictive reading;
3. **otherwise**, it depends on what the stand declares. If it declares openings anywhere in its rules, the day is **closed**: "open these twelve days" is a schedule, and an absent day is a day when the stand does not open. If its rules only close, it describes exceptions to an open stand and the day is **open all day**.

The result is always **a single mode per day**. This is what preserves the three-state invariant, and what spares the segment computation, the constraints and the exports from having to know the rules. If a single day of the event remains unstated, no rule can take `TOUS`: it would govern a day deliberately left open by default.

The third layer avoids a trap: "open these twelve dates and nothing else" used to require a second filler rule, a closure starting early enough to cover all the timeslots, which the slightest timeslot added earlier silently reopened. `HoraireElagage` removes these closures that have become mute, after checking segment by segment that no opening moves. It **never** removes an opening, even one with no effect on the day's grid: it is a statement waiting for a timeslot.

**Accepted limit**: an exception *replaces* the day instead of being subtracted from the rules. "Open 10:00-12:00 and 14:00-closing every day, except the afternoon of 14 July" requires re-entering the whole day. This is the price of the one-mode-per-day invariant; the alternative would mix two modes in one day, the ambiguity this invariant rules out.

### What resolution settles silently

The openings analysis flags it **without refusing it**: two rules of the same scope and the same mode that overlap on the same day (union of the windows, highest headcount: "@4" then "@2" on the same hours gives 4), a rule that no day applies because a more specific rule or a dated exception wins everywhere, two windows of the same rule that overlap at different headcounts. We warn because the result is deterministic, because some overlaps are intended (a base rule plus a peak rule) and because a refusal would break existing scenarios. Only the truly ambiguous case, same scope and opposite modes, stays refused. `HoraireStandResolver.resolveDayWithRules` returns the rules that decided each day, without changing the verdict.

### Raw view, effective view, compaction

The expansion of the rules is **never written** onto the dated lists: a resolved stand can go through a save again without freezing its expansion into hundreds of lines. The service exposes two views, a raw CRUD view and an effective view resolved over the days of the edition, which the solver, seat generation and the feasibility analysis use.

**Compaction** derives the rules on demand and rewrites a stand only if they reproduce its own open segments. The gap is measured in **opening minutes** in disagreement, never by matching segments: a "closed 10:00-23:59" rewritten as "closed until closing" takes the number of segments from 1 to 0, whereas the real disagreement is a single minute. Counting segments would make us refuse the stands the rewrite helps most.

## The consigne, a fourth layer

A prefectoral arrêté (heatwave, storm) closes an hours band for **all** stands on a few dates, with extensions and lifts. It is neither a stand rule nor a dated exception: the decision comes from outside, applies to all without anyone having declared it, and must be lifted without rewriting anything. It is an **edition consigne** ([decision 0043](repo:docs/decisions/0043-consigne-d-edition-fermer-une-bande-sans-rien-detruire.md)), read by `ConsigneResolver` on top of the three layers and through a single entry point, `StandService.resolve`. A caller that unrolled the rules itself would silently staff a closed band, and `ConsigneCoucheStructurelleTest` refuses it.

**The model**: one line per date, which carries:

- a **forbidden band**, with a mandatory start and an optional end meaning "until midnight" (`00:00` → empty for the whole day);
- a mandatory **reason**;
- the name of the originating **preset** if there is one (a "Heatwave plan": band, reason and default windows, stored on the edition and copied on duplication, unlike dated consignes);
- **default compensation windows**, before or after the band;
- **openings** (a stand, a window, an optional headcount, several windows per stand);
- the **timeslots added** to the grid, marked as such.

Three rules, in this order, for each stand of a date under a consigne:

1. **The band closes all stands**, above their rules and exceptions, and closes only that: a stand keeps its hours outside the band. A 14:00-20:00 becomes an 18:00-20:00.
2. **The timeslots the consigne added belong to nobody's hours**: a stand is closed there, unless one of its own consigne windows covers them. Without this, a stand open by default would be staffed on an evening nobody chose for it.
3. **An opening is an extension chosen stand by stand**: the stand opens on its windows in addition to its hours, never in the band, at the headcount entered on that window, otherwise at the highest headcount it loses in the band, otherwise at its minimum. Where a window overlaps hours it already had, the higher of the two headcounts applies.

The day comes out **restated in full**: as explicit openings (closed by default) when something remains, as a whole-day closure otherwise. One mode per day, as after the three layers, and nothing downstream has to know about the consigne.

**What is added to the grid, and nothing else.** A chosen window that no timeslot covers (20:00-22:00 after a day that ends at 20:00) causes the missing timeslot to be added, marked as added by the consigne. This is the only write on the grid, it is additive, and an opening must require it. Modifying a consigne keeps an added timeslot, with its seats, as long as a window requires it exactly, and removes the others.

**What is never destroyed.** The nominal timeslots keep id, seats and locks. The shifts of the band become shifts with no seat: a stand closed over a whole timeslot generates no seat there, closed over part of it generates the seats for the rest, at their effective window. Lifting the consigne means no longer applying it: the stands get their hours back, and only the added timeslots go, with their seats. The next publication announces their removal. The [stability of the published plan](plan-verrous-et-publication.md#le-plan-publié) does the rest: the holder of a 14:00-20:00 stays on the 18:00-20:00 during the alert and gets their afternoon back at the lift.

**The past stays intact.** Setting, modifying and lifting refuse a past or ongoing date: a date already worked keeps forever the consigne that governed it. Setting or lifting removes the review validation of the days touched, lock or not, because the seats of the band no longer exist.

**Effective hours, everywhere.** Totals, rest, hours, fairness, indicators: everything already reads the effective window, so a day under a consigne counts what is really worked without any computation changing. A snapshot carries the consignes in force at its capture (empty on an earlier snapshot: "unknown", never "none").

**Accepted limits**: the band is mandatory (a consigne without a band is a day template); there is one band per day; availability stays per day; the night and rest rules read the whole timeslot, not the effective window, which closes the late windows to minors; an evening window that extends the afternoon requires two people per seat as long as the evening meal break forbids chaining 18:00-20:00 and 20:00-22:00.

### Restating the meal break for a date

The meal break is the organiser's rule, not the Labour Code's ([meals and breaks](parametres-legaux-et-qualite.md#la-coupure-repas)). A consigne can therefore set, for its date alone, other midday and evening windows and another break length, with a mandatory justification in business terms. The solver then receives two kinds of `FenetreRepas`: those of the edition, which carry the dates on which they stand aside, and those of the consigne, dated. `FenetreRepas.appliesTo(date)` is what the constraint and every reader ask. Lifting the consigne removes its windows with it; the edition's legal settings were never written. The legal caps cannot be overridden.

## The past is frozen

Every solve, full or incremental, from the screen or through MCP, takes from the saved plan the seats of the timeslots that have already started and pins them, whatever their state ([decision 0044](repo:docs/decisions/0044-le-passe-est-fige.md)). The holder is kept even if they have since declared the day unavailable or a forced unavailability now covers them (they were there); a deleted holder leaves the seat empty; a past seat that is empty is pinned anyway, since nobody can hold yesterday.

A timeslot has *started* if its date is past, or if it is today and the seat's effective start has been reached. A timeslot straddling midnight belongs to its start date. "Today" is that of the event-day clock: the machine in production, or the date frozen from Settings › Instance under `quarkus:dev` or with `HORLOGE_SIMULEE_AUTORISEE=true`.

These seats **count** in the rules that link days (daily rest between yesterday and today, weekly durations, consecutive days, breaks, "close late then open early") but are **never blamed**: a hole from yesterday or a minor placed at night yesterday do not block today's hard zero. The scope of a re-plan never reopens a past seat.

**The past can no longer be modified by hand either.** The actions that rewrite a seat without the solver ("Move to…" in the seat panel, `deplacer_affectation`, the acceptance of a swap, a repair applied or suggested) refuse a seat whose timeslot has started, with the same sentence: "This timeslot has already started: the past can no longer be modified." It comes out as a `400` on the REST side and as a business refusal on the MCP side (`FrozenPast.PAST_SEAT_REFUSAL`).

### The past is frozen to the minute

A repair, a "Place" or a "Mark absent" on the seat of a timeslot **under way** are not refused ([decision 0066](repo:docs/decisions/0066-le-passe-est-fige-a-la-minute.md)). The write **splits the seat at "now"**, the current minute of the event-day clock, into two real seats:

- the original is cut short at that minute (`heureFinEffective`) and keeps its holder, so that the history says who held 9:00 - 9:20;
- the continuation covers the rest of the timeslot (`heureDebutEffective`), carries `suiteDe` (the original's identifier, persisted in `poste_affectation.suite_de`) and receives the write. Its identifier is derived: `<origine>~HHmm`.

An **empty** seat is not split: placing someone there **trims** it (same line, start moved forward to "now", identifier `<siège>~HHmm` without `suiteDe`), without leaving an empty fragment counted as "unfilled". Only a seat that has already ended stays refused. A split seat remains **one** seat:

| Where | What the split changes |
| --- | --- |
| Seat generation and crew of a premium stand (`crewByStand`) | Nothing: the continuation is not an extra seat |
| Reconstruction (re-seeding, incremental) | `SeatSplit.restore` replays the split or trimmed cells of the persisted plan onto the generated seats; the solver reopens only the continuation |
| Snapshots | The continuation and its `suiteDe` are captured and restored with the plan |
| Scenario export | The continuations are omitted: a scenario describes the seats to fill |
| "New since this morning" (Today, wall display) | The continuation is a cell the published plan did not have: its empty seat is new |

**Nothing to schedule.** A solve whose seats are all past (finished edition, simulated date after the event) is refused rather than producing an empty plan at hard zero. If some past seats stayed empty (first solve started during the event, deleted holder), the solve takes place and its result carries `postesPassesVides`, announced as a warning.

`PASSE_FIGE=false` switches off the whole rule (pinning, refusal of the actions, refusal of a solve with no future), for an acceptance test that replays an old edition. It is off by default under the test profile.

## Effective window

A seat coming from a partial segment carries a window narrower than its timeslot. It is **not a sub-timeslot**: the foreign key points to a real, persisted timeslot, the window lives on the seat, and the accessors fall back on the timeslot when the override is absent. The rules that read the window and those that read the whole timeslot are distinguished in [the solver rules](pourquoi-ces-regles.md).
