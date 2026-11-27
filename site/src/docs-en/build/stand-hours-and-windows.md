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

## Opening hours are a list of dated plages

Since 4.0, a stand carries only a **list of plages** (`PlageStand`): a day, hours, a mode (`OUVERTURE` or `FERMETURE`), a headcount for an opening, a reason ([decision 0104](repo:docs/decisions/0104-les-horaires-d-un-stand-sont-une-liste-de-plages-datees.md)). No recurring rule is stored any more: "every Monday from 10:00 to 12:00" is a **generator** that writes one plage per Monday, and the plage is what is read afterwards, never the pattern that produced it.

Resolution (`HoraireStandResolver`) therefore only lays the stand's plages down as its effective windows, on which the consigne is then applied. The three states per day at the top of this page read off them as they are: a day with no plage is open all day, a day that carries an opening is open only on those, a day that carries only closures is open outside them. The result is always **a single mode per day**, which preserves the three-state invariant and keeps the segment computation, the constraints and the exports from having to know anything but plages.

**A plage's origin is stored.** A plage unfolded from the model carries `depliee` (a column, a key of the YAML file); a hand-typed one does not. Unfolding and the migration mark what they lay down; the resolution does not use it, but whatever reads "where does this day come from" does: a day only the model explains is the stand's normal regime (`REGLE`), not an exception, so a consigne's preselection finds the open stands again. Changing the model withdraws exactly the marked plages; a hand-typed plage stays, even identical to an unfolded one.

**What it costs, and is accepted.** A day added to the event afterwards (a timeslot on a date nobody had counted) has no plage, but gets the stand's model if it keeps one: without it, the stand is open all day there, even if it "only opened at weekends". A daily schedule over a long edition makes one row per day and per window.

### The former rules

In 3.x, a stand stated its hours as rules (scope `TOUS`, `JOURS_SEMAINE`, `PLAGE` or `DATES`, of increasing specificity), which a resolver folded at every read under the dated exceptions. The 4.0 migration converts them **once**, day by day, over the days of the event, with the application's exact reading: a dated exception keeps its day, otherwise the rules of the most precise scope win and merge their windows, two rules of the same scope and opposite modes give the opening, and a stand whose rules declare openings is closed on a day none of them names. `HorairesRecurrents` keeps that reading, for the migration, for link 4 → 5 of the YAML format, for the MCP tool `creer_horaire_stand` and for the unfolding of the model. An edition with no timeslot has no day to lay its rules on: they stay the stand's **model** (`Stand.horaires`, `stand_horaire` tables), which the resolution never reads. Every timeslot that opens a new day in the edition unfolds the model on that day, with the same reading, and never twice: a day that already carries a plage keeps it, a day is new only once, and a plage deleted by hand does not come back ([decision 0104](repo:docs/decisions/0104-les-horaires-d-un-stand-sont-une-liste-de-plages-datees.md)). A rule sent to an edition that already has timeslots is kept **and** unfolded at once over its days (the plages the previous model had unfolded are withdrawn first, a plage entered by hand stays); sending the stored model back unchanged changes nothing ([moving to 4.0](../installer/passer-a-la-4-0.md)).

## The consigne, plages written on the stands

A prefectoral arrêté (heatwave, storm) closes an hours band for **all** stands on a few dates, with extensions and lifts. It is neither a stand rule nor a dated exception: the decision comes from outside, applies to all without anyone having declared it, and must be lifted without rewriting anything. It is an **edition consigne** ([decision 0043](repo:docs/decisions/0043-consigne-d-edition-fermer-une-bande-sans-rien-detruire.md)). Since 4.0 ([decision 0125](repo:docs/decisions/0125-la-consigne-s-ecrit-en-plages-datees.md)), laying a consigne down **writes dated plages** on every stand of its date, marked as its own (`consigne`), and lifting it removes exactly those. There is no fourth layer any more: the resolution reads plages only, through a single entry point, `StandService.resolve`. A caller that read the plages itself, one mode per day, would honour the stand's openings and silently staff the closed band; `ConsigneCoucheStructurelleTest` refuses it.

**The model**: one line per date, which carries:

- a **forbidden band**, with a mandatory start and an optional end meaning "until midnight" (`00:00` → empty for the whole day);
- a mandatory **reason**;
- the name of the originating **preset** if there is one (a "Heatwave plan": band, reason and default windows, stored on the edition and copied on duplication, unlike dated consignes);
- **default compensation windows**, before or after the band;
- **openings** (a stand, a window, an optional headcount, several windows per stand);
- the **timeslots added** to the grid, marked as such.

Three rules, written as plages on each stand of a date under a consigne:

1. **The band closes all stands**: a closure over the band, above their own plages, which closes only that: a stand keeps its hours outside the band. A 14:00-20:00 becomes an 18:00-20:00.
2. **The timeslots the consigne added belong to nobody's hours**: a closure over each, which takes nothing from the stand's consigne windows. Without this, a stand open by default would be staffed on an evening nobody chose for it.
3. **An opening is an extension chosen stand by stand**: one opening per window, outside the band; the stand opens on it in addition to its hours, even where its own plages close it (a stand closed every evening from 17:00 reopens 18:00-22:00 if the consigne says so), at the headcount entered on that window, otherwise at the highest headcount it loses in the band, otherwise at its minimum. Where a window overlaps hours it already had, the higher of the two headcounts applies.

**How the day is read.** The consigne's plages are read *above* the stand's own: its closures take their stretch out of the stand's own hours, never out of the consigne's openings, which are added to them. The day comes out **restated in full** by `HoraireStandResolver`: as explicit openings (closed by default) when something remains, as a whole-day closure otherwise. One mode per day, and nothing downstream has to know about the consigne. The headcount a window inherits is computed when the consigne is laid and written on the plage: laying it again recomputes it.

**Lifting removes exactly the consigne's plages.** A consigne plage edited by hand in the meantime loses its mark: it becomes the stand's own, and stays after the lift. A plage added by hand that day stays too. Laying or lifting a consigne moves the stamp of every stand of the edition: a stand form opened before is refused on save (409) rather than writing the day back without the band, or bringing it back after the lift. A scenario file never carries these plages: it carries the consigne, and the import writes them again; an edition copy does not take them, as it does not take the consignes.

**What is added to the grid, and nothing else.** A chosen window that no timeslot covers (20:00-22:00 after a day that ends at 20:00) causes the missing timeslot to be added, marked as added by the consigne. This is the only write on the grid, it is additive, and an opening must require it. Modifying a consigne keeps an added timeslot, with its seats, as long as a window requires it exactly, and removes the others.

**What is never destroyed.** The nominal timeslots keep id, seats and locks. The shifts of the band become shifts with no seat: a stand closed over a whole timeslot generates no seat there, closed over part of it generates the seats for the rest, at their effective window. Lifting the consigne removes its plages: the stands get their hours back, and only the added timeslots go, with their seats. The next publication announces their removal. The [stability of the published plan](plan-verrous-et-publication.md#le-plan-publié) does the rest: the holder of a 14:00-20:00 stays on the 18:00-20:00 during the alert and gets their afternoon back at the lift.

**The past stays intact.** Setting, modifying and lifting refuse a past or ongoing date: a date already worked keeps forever the consigne that governed it. Setting or lifting removes the review validation of the days touched, lock or not, because the seats of the band no longer exist.

**Effective hours, everywhere.** Totals, rest, hours, fairness, indicators: everything already reads the effective window, so a day under a consigne counts what is really worked without any computation changing. A snapshot carries the consignes in force at its capture (empty on an earlier snapshot: "unknown", never "none").

**Accepted limits**: the band is mandatory (a consigne without a band is a template); there is one band per day; availability stays per day; the night and rest rules read the whole timeslot, not the effective window, which closes the late windows to minors; an evening window that extends the afternoon requires two people per seat as long as the evening meal break forbids chaining 18:00-20:00 and 20:00-22:00.

### Restating the meal break for a date

The meal break is the organiser's rule, not the Labour Code's ([meals and breaks](parametres-legaux-et-qualite.md#la-coupure-repas)). A consigne can therefore set, for its date alone, other midday and evening windows and another break length, with a mandatory justification in business terms. The solver then receives two kinds of `FenetreRepas`: those of the edition, which carry the dates on which they stand aside, and those of the consigne, dated. `FenetreRepas.appliesTo(date)` is what the constraint and every reader ask. Lifting the consigne removes its windows with it; the edition's legal settings were never written. The legal caps cannot be overridden.

## The past is frozen

Every solve, full or incremental, from the screen or through MCP, takes from the saved plan the seats of the timeslots that have already started and pins them, whatever their state ([decision 0044](repo:docs/decisions/0044-le-passe-est-fige.md)). The holder is kept even if they have since declared the day unavailable (they were there); a deleted holder leaves the seat empty; a past seat that is empty is pinned anyway, since nobody can hold yesterday.

A timeslot has *started* if its date is past, or if it is today and the seat's effective start has been reached. A timeslot straddling midnight belongs to its start date. "Today" is that of the event-day clock: the machine in production, or the date frozen from Settings › Instance under `quarkus:dev` or with `HORLOGE_SIMULEE_AUTORISEE=true`.

These seats **count** in the rules that link days (daily rest between yesterday and today, weekly durations, consecutive days, breaks, "close late then open early") but are **never blamed**: a hole from yesterday or a minor placed at night yesterday do not block today's hard zero. The scope of a re-plan never reopens a past seat.

**The past can no longer be modified by hand either.** The actions that rewrite a seat without the solver ("Apply" of "Simulate a change" in the seat panel, `modifier_affectation`, the acceptance of a swap, a repair applied or suggested) refuse a seat whose timeslot has started, with the same sentence: "Ce créneau est déjà commencé : le passé ne se modifie plus." (this timeslot has already started: the past can no longer be modified) It comes out as a `400` on the REST side and as a business refusal on the MCP side (`FrozenPast.PAST_SEAT_REFUSAL`).

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

**Nothing to plan.** A solve whose seats are all past (finished edition, simulated date after the event) is refused rather than producing an empty plan at hard zero. If some past seats stayed empty (first solve started during the event, deleted holder), the solve takes place and its result carries `postesPassesVides`, announced as a warning.

`PASSE_FIGE=false` switches off the whole rule (pinning, refusal of the actions, refusal of a solve with no future), for an acceptance test that replays an old edition. It is off by default under the test profile.

## Effective window

A seat coming from a partial segment carries a window narrower than its timeslot. It is **not a sub-timeslot**: the foreign key points to a real, persisted timeslot, the window lives on the seat, and the accessors fall back on the timeslot when the override is absent. The rules that read the window and those that read the whole timeslot are distinguished in [the solver rules](pourquoi-ces-regles.md).
