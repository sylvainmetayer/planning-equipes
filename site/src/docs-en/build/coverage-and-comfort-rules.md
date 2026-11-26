---
twin: comprendre/regles-de-couverture-et-de-confort
---
# Coverage and comfort rules

You will know what the rules that do not come from the Labour Code read and what they cost: all-rounders, quotas per game category, travel time, carpooling, quality thresholds, stability of an already published plan, and how all of them treat a past that has already been lived.

## All-rounder game category and all-rounder reserve

At most one game category carries the `polyvalent` flag (partial unique index). A staff member who has it among their skills is an **all-rounder**:

- `hasCompetenceFor(stand)` returns true for any stand: they are never penalised by `appreciationIncompatible`;
- they are excluded from `limiterTypologiesDistinctesParAnimateur`, because spreading them around is their reason for being;
- `preserverBufferPolyvalents` penalises every timeslot where no all-rounder is left free.

This last rule is **deliberately in tension** with `equilibrerCharge`: an all-rounder kept in reserve mechanically unbalances the load. The trade-off is carried by the levels: soft against medium, balancing wins except on a tie. As long as no game category is an all-rounder one, nobody is an all-rounder and the rule costs nothing. The all-rounder is reinforcement, not a specialist ([ADR 0017](repo:docs/decisions/0017-fragilite-le-ninja-est-un-renfort-pas-un-specialiste.md)).

No rule currently expresses a preference for variety of stands: the one that penalised two seats of the same staff member on the same stand conflicted with `eviterRoulementStandsPremium` and `limiterTypologiesDistinctesParAnimateur`, which push towards stability. Restoring that need first means arbitrating it against those two rules.

## A quota per game category

A game category can carry a cap, `maxCreneauxParAnimateur`, entered on its record. Empty, it means "no cap" and an edition that does not touch it does not change behaviour. The hard rule `plafondCreneauxParTypologie` enforces it ([ADR 0042](repo:docs/decisions/0042-quota-par-typologie-sur-la-typologie.md)). Two choices to know before using it:

- **The unit is the timeslot**, not the hour: four one-hour timeslots and four six-hour timeslots weigh the same.
- **A seat counts for every game category its stand offers**, not for those its staff member masters. You hold the game you sit at, whether the record mentions it or not. This is the opposite choice from `limiterTypologiesDistinctesParAnimateur`, which reads the intersection: that one is about what a person must learn, this one about what a game consumes.

The scope is the **whole edition**, never the day or the week. Since the rule is hard, a cap that is too low makes the edition infeasible rather than exceeded: the diagnosis then names who, which game category, how many timeslots held against which cap. The Categories page carries the matching reading (staff members actually assigned, seats, hours, gap with those the reference data rates highly): a cap that is visible nowhere is a cap nobody can tune.

## Travel time between two seats

`eviterChangementEmplacementEloigne` looks only at two seats that touch (the end of one equals the start of the other) and judges 300 m as the crow flies with no notion of time. Someone who finishes at 14:00 at stand A and resumes at 14:10 at stand B, one kilometre away, was never examined; `limiterEmplacementsParJour` counts the zones of a day without looking at either order or distance.

`trajetInsuffisantEntrePostes` fills that gap. The walking time between two geolocated locations is **crow-fly distance × detour factor ÷ walking speed**, rounded up to the minute: 1,000 m at 4 km/h with a factor of 1.3 makes 20 min. For two consecutive seats of the same staff member on the same day, at two different locations, separated by a **strictly positive** gap, each minute the gap is short by beyond the tolerance costs one medium point. A 10 min gap for a 20 min trip and 5 min of tolerance costs 5: the solver has a slope to descend, widen the gap or keep the person in the same place.

Three choices frame it:

- **No double counting.** A pair with a zero gap remains the business of `eviterChangementEmplacementEloigne`, on the same key (the timeslot hours): a pair is judged by one or the other. Only two consecutive shifts form a pair, an intervening seat carries the trip that counts.
- **No routing service.** Neither OSRM nor Google: no network dependency, no data sent to a third party, a deterministic calculation. The detour factor is the lever for a site where you have to go around.
- **Medium and weightable, not hard.** A tight trip is a discomfort, the speed an estimate, and there is no legal basis.

A stand with no location, or a location with no coordinates, makes the rule inert: an unknown trip is neither short nor long.

The check of the sequences (`GET /api/planning/enchainements`, MCP tool `analyser_enchainements`) reads the persisted plan with the same calculation but a wider net: all consecutive pairs, zero gap included, past included. It also flags a gap that is also the legal break when the trip eats part of it ("trip taken from the break"), without the break rule changing.

## Grouped arrival (carpooling)

Several staff members come in the same car and leave together. `affiniteAdHoc` cannot say that: it rewards two people on the same stand at the same timeslot, too strict for a car (which does not need the same stand) and too weak (nothing in it aligns the hours).

`arriveeGroupee` reads, for each `ARRIVEE_GROUPEE` adjustment (2 to 4 members), every pair of the group and every day:

- if one member works and the other does not: **flat penalty of 120 minutes**, because "the same days" weighs as much as "the same hours";
- if both work: the minutes of gap between their **first starts** beyond the tolerance (30 min by default, adjustable on the rule's row), plus those between their **last ends**. These are the effective windows, read on the same (staff member, day) folding as `eviterFermeturePuisOuverture`: a break or a meal break in the middle of the day changes nothing.

A member who arrives 45 min after the other costs 15. Per pair and per day, in minutes: a slope, not a wall. The rule is **soft and not medium**: it is a comfort that must yield to `equilibrerCharge`, to wishes and to all the legal rules (a minor who must leave before 22:00 misaligns the group, not the other way round). A day that is entirely past is no longer charged.

Different declared unavailabilities make some days inevitably misaligned: the Carpool tab of the Availability screen counts them before validation, writing the adjustment warns (without naming the people other than by their id) and the reference data's coherence list recalls it. The Problems page lists the misaligned days per group, the Rail marks the members, and the espace tells each person, day by day, whether their carpool holds, without ever detailing other people's seats.

## Quality thresholds

The "Qualité d'organisation" (organisation quality) rules read their thresholds in `ParametresQualite`, a problem fact like `ParametresLegaux`. They are **persisted per edition** and set in Settings, Organisation tab; the row of the rule that reads them, in Planning rules, points there. The `planning.contraintes.*` block of the configuration gives the values of an edition that has never set them, and a scenario overrides them in its `parametres.organisation:` section.

| Threshold | Default | Rule that reads it |
|---|---|---|
| Distinct locations per day and per staff member | 3 | `limiterEmplacementsParJour` |
| Distinct game categories per staff member, over the whole edition | 2 | `limiterTypologiesDistinctesParAnimateur` |
| Hour of a late shift | 22:00 | `eviterFermeturePuisOuverture` |
| Hour of an early shift | 10:00 | `eviterFermeturePuisOuverture` |
| Desired rest after a late shift | 12 h | `eviterFermeturePuisOuverture` |
| Days worked in a row | 8 | `maxJoursConsecutifsTravailles` and its hard form |
| Walking speed | 4 km/h | `trajetInsuffisantEntrePostes`, check of the sequences |
| Detour factor | 1.3 | same |
| Trip tolerance | 5 min | same |
| Grouped-arrival tolerance | 30 min | `arriveeGroupee`, reading of the grouped arrivals |

None has a public-policy floor: they are comforts that the organiser arbitrates. Leaving both shift hours empty is a legitimate answer, which is how `eviterFermeturePuisOuverture` goes quiet ([ADR 0031](repo:docs/decisions/0031-signaler-le-plancher-sans-le-decider.md)). The scope of the game-category cap is the edition: the rule's `groupBy` carries only the staff member, with no date key, and a test locks it so that a refactor does not let it slide silently. The reasoning about 22:00 and 10:00 is in [Rest and consecutive days](repos-et-jours-d-affilee.md).

## Stability of the published plan

`stabiliteDuPlanPublie` (medium, weightable, in the "Qualité d'organisation" (organisation quality) category) answers an observation: a solve rerun after publication moved dozens of people who had already been notified for a marginal fairness gain, because nothing in the score said that moving an informed person has a cost ([ADR 0025](repo:docs/decisions/0025-stabilite-du-plan-publie.md)).

**What it counts.** One medium point per seat whose holder is not the one the published plan had on that stand and timeslot: one point per person to whom the publication would say "your timetable has changed". The facts are the seats of the last published snapshot (`AffectationPubliee`), loaded by `SolveRunner.prepareProblem` before every solve and every diagnosis, never sent by a client. A published seat left empty costs as much as a replacement: its holder was notified, the hole is theirs, and that is what stops the solver from freely drawing a holder onto a new row while leaving behind a hole that nobody fills without paying.

**What it deliberately does not count:**

- nothing as long as nothing has been published: the rule is silent, the solver stays free to rework everything upstream;
- a stand or a timeslot created after publication: nobody was told anything about it;
- a seat added on a published row (the staffing grew) counts its new occupant, who is indeed a person to notify.

**What it cannot do.** It is medium: a hard rule or an unavailability always wins (`PlanningHardConstraintsTest.publishedSeatsNeverOutweighAHardRule`). Nor does it freeze anything: to freeze, there are locks and incremental replanning. It arbitrates, to the weight, between the disturbance and the gain.

**Its default weight is strong importance (25)** (`planning.constraint-weights.stabiliteDuPlanPublie`): `equilibrerCharge` produces thousands of points on a real edition, so at equal weight stability would weigh little. It is weighted per edition like the other quality rules and switched off with the toggle.

### Keeping feasibility despite stability

When a change adds seats (a new stand open all day), filling a new seat at peak hours requires a chain of moves that every stability point makes impassable step by step. Three mechanisms answer this:

- the feasibility phase carries a *ruin and recreate* selector restricted to the seats of the hour of a hole (`HoleNeighbourPosteFilter`, `solverConfig.xml`), which evaluates the chain as a single move;
- as soon as a plan is published and the rule is active, the solve runs **in two stages within the same job**: feasibility first, stability weighted at zero in memory, at most two thirds of the budget; then polishing, stability restored, which gives their published seats back to as many people as the hard rules and the remaining time allow. The summary on the Solver page says so, with the number of seats given back ([ADR 0067](repo:docs/decisions/0067-la-faisabilite-avant-la-stabilite-apres-publication.md));
- another hole resists *ruin and recreate* because its chain goes through another day of the week: taking a seat on Tuesday means giving up another on Monday, whose two half-days must go to two colleagues who held only one. `WeekRelocationMoveIteratorFactory` plays the whole chain as a single move, with colleagues eligible in the sense of `EligibleAnimateurMoveFilter`. When the edition holds consecutive days hard, the day given back may also be in the other week.

**A grid that changes removes the rule's hold.** It holds stand × timeslot rows: a derivation or a typical-day calendar that replaces the grid (the saved plan goes with it) reshuffles all the rows. An added stand, for its part, does not move the others.

**The count next to the score.** Every solve also returns `impactPublication.personnes`, the number of people the publication would notify, calculated as the publication calculates it, on shifts per person and not through the rule. The two do not coincide exactly (a person who changes two seats counts two points and one person): the screen shows the count of people.

## Counted, not blamed: the past

During the event, a solve receives the seats of timeslots already started **taken from the saved plan and pinned**: the past is frozen ([ADR 0044](repo:docs/decisions/0044-le-passe-est-fige.md), mechanics in [plan, locks and publication](plan-verrous-et-publication.md)). These seats carry a `passe` flag that every rule reads by a single logic:

- a past seat **counts**: it stays in every stream, group and join. What someone worked yesterday conditions the rest they are owed tonight, the hours they have left in the week, the consecutive days they reach;
- a past seat is **never blamed**: a match whose seats are all past (a hole from yesterday, a minor at night yesterday, a meal break missed yesterday) is history the solver cannot repair, and charging it would stop a feasible future from reaching zero hard. A match is charged only if at least one of the seats it involves is not past.

Concretely: a per-seat rule ignores the past seat; a per-pair rule does not charge a pair whose two seats are past; a rule grouped by staff member and day or week counts everything but charges the group only if it still holds an upcoming seat; the affinity reward follows the same reading. The diagnostics that replay the score on the saved plan (Planning rules, `expliquer_echec_contraintes_dures`, floors, simulations) go through the same constraint provider and therefore read the same thing. The analyses that count outside the solver (Breaks, Staffing need, grid check, Hours) always **describe** the past, including what went wrong in it: they say what was done without blaming it either.

**A medium floor during the event, accepted.** `equilibrerCharge` and `equilibrerCreneauxPenibles` measure the whole edition, past seats included (what each person has already worked makes fairness readable), and are charged only as long as a seat is upcoming. But the solve can now move only the future: a gap already dug before today cannot be made up, and the medium score keeps a floor that no rerun brings back to zero. It is neither flagged like the [per-rule floors](doser-et-desactiver-les-regles.md) nor subtracted: the score of a solve during the event is compared with the previous one, not with zero. Lifting it would require measuring balance only on the future, which would invert the rule: the staff member already loaded yesterday would become "to be loaded" again tomorrow.
