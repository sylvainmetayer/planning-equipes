---
twin: comprendre/pourquoi-ces-regles
---
# The solver's rules and their catalogue

You will know where to read the list of the 47 rules, how they are arranged by level, why the screen and the code do not use the same word for a breach, and what two fields of the record do not drive. The table at the end of the page is the complete list, kept up to date by a test.

A schedule is valid when no hard rule is breached, and good when the quality rules are barely penalised. The rules are arranged on the three levels of Timefold's `HardMediumSoftScore`: **hard** (blocking), **medium** (heavily penalised, flagged to the organiser) and **soft** (a preference that separates two valid schedules).

The three levels are also told for an organiser in [three levels of requirement](/comment-marche-le-calcul/), and the legal framework in [the Labour Code](/code-du-travail/).

> **Never reclassify a hard rule as medium or soft without explicit sign-off**, in particular anything touching the legal framework for minors.

Each rule is written twice: in Timefold's constraint streams, which define it, and in the direct engine (`solver/handsearch`), which must score it identically. [How the schedule is calculated](comment-le-planning-est-calcule.md) describes the two engines and [Adding a rule](ajouter-une-regle.md) the procedure to follow.

## Where the list is

The level, category, business description and legal article of each rule live in [`ConstraintCatalog`](repo:src/main/java/dev/sylvain/planning/solver/ConstraintCatalog.java). The catalogue is served by `GET /api/constraints` and displayed by the Planning rules screen (`/regles`). This part of the documentation carries the mechanisms and trade-offs that a one-line description cannot contain:

| Question | Page |
|---|---|
| Why a legal, break, rest or week rule is written the way it is | [Legal rules and breaks](regles-legales-et-pauses.md) |
| All-rounder game category, quotas, travel, carpooling, quality thresholds, published-plan stability | [Coverage and comfort rules](regles-de-couverture-et-de-confort.md) |
| Switching off, weighting, reading a floor, weighting history | [Weight and disable rules](doser-et-desactiver-les-regles.md) |
| Hand-placed exceptions and their contradictions | [Ad hoc exceptions](exceptions-ad-hoc.md) |

The screen lays the rules out as a table, one per row. The **Legal** tab carries the hard rules (the law, minors' safety, the meal, assignment), the **Quality** tab the medium and soft rules. A row names the rule by its **short label** (`libelleCourt`, for example "Places pourvues" (seats filled) or "Coupure repas" (meal break)) and never by its technical name: `ConstraintCatalogShortLabelTest` keeps the latter out of anything an organiser reads. The detail (long text, advice when there is a breach, exact weight, tuning history) opens in a panel.

**A rule's name is its address**: `/regles?regle=coupureRepasObligatoire` opens that rule's panel, on its tab. Renaming a rule therefore breaks shared links, like any break in an API contract.

The descriptions remain plain text on the server side: the same string goes out to the MCP tools and is stored in a swap request. The frontend recognises at display time the article numbers they cite and links them to Légifrance (`core/legifrance.ts`, rendered by `app-legal-text`): writing `art. L3162-1` in a description is enough to get the link.

## "Breach" on screen, `violation` in the code

A case where a rule is not satisfied is called a **breach** everywhere the organiser reads it: labels, online help, documentation. The word holds for the three levels, which "offence" would not without accusing a comfort preference of being a wrongdoing.

The code keeps `violation`: Java and TypeScript identifiers, JSON keys (`violations`, `violationsParContrainte`, `violationsIntroduites`), `ViolationFormatter`. Renaming it would break the JSON contract and therefore every MCP client, for a readability gain only. The gap is deliberate: do not "fix" a label to `violation` nor an identifier to `écart`.

## `effectifMax` and `manager`: two fields the solver does not read

These two fields look like rule levers and are not.

- A stand's `effectifMax` bounds data entry ([data model](modele-des-donnees.md)). It sets neither the number of seats, which comes from the window's staffing or failing that from `effectifMin`, nor a ceiling that the solver would check.
- A staff member's `manager` is a tag. No rule reads it: a scheduled manager is subject to the same ceilings, `dureeHebdomadaireMax` included, and `mineurNecessiteEncadrementMajeur` counts any adult present, manager or not.

If a rule ever has to depend on it, it is added to the catalogue with its test. It is not deduced from the field.

## The effective window: who reads it, who ignores it

A stand closed for part of a timeslot, or normally closed and open on specific windows, generates one seat per segment that is still open. Each carries an *effective* time window narrower than the timeslot, and `posteDoitEtrePourvu` applies to these reduced seats: nobody is required on the closed range. The mechanism never creates an additional timeslot: the foreign key remains the real timeslot, the window lives on the seat.

An edition consigne (a consigne is an official order, such as an arrêté, relayed to the whole event: here the band an order closes for all stands, and the windows reopened as compensation, see [stand hours and windows](horaires-et-fenetres-des-stands.md)) is read through these same windows. No rule needs to know about it: what follows holds as is for a day under a consigne, minors' night rule included.

| Rule or calculation that reads the effective window | Why |
|---|---|
| `pasDeChevauchementHoraire` | two seats whose segments do not actually overlap are no longer flagged wrongly |
| hour totals and rests | a reduced seat counts only the time actually covered, and the rest that follows starts at the end of that time |

`travailDeNuitInterditPourMineur` deliberately stays pinned to the whole timeslot: a minors' safety rule must never become more permissive as a side effect of a stand-availability feature.

## Eligibility exclusions weigh more than anything

Six rules say who can **never** hold a seat: `animateurDisponible`, `standReserveAuxMajeurs`, `travailDeNuitInterditPourMineur`, `travailInterditJourFerieMineur`, `dureeQuotidienneMaxMineur`, `travailContinuMaxMineur`. These are the reasons behind `EligibleAnimateurMoveFilter`. A seventh carries the same flat penalty without being a reason of the filter, because it depends on who else holds the stand: `mineurNecessiteEncadrementMajeur`. A lone minor otherwise cost exactly one empty seat, and on a team of minors only the order of the search told them apart ([ADR 0035](repo:docs/decisions/0035-mineur-seul-au-forfait.md)). This seventh rule is off by default ([ADR 0041](repo:docs/decisions/0041-encadrement-des-mineurs-eteint-par-defaut.md)): the flat penalty is due only if the organisation has switched it on.

The filter keeps these staff members out of the search, but the *ruin and recreate* rebuild does not read it, and these rules weighed only one hard point: as much as an empty seat, less than a few minutes of rest. A plan that could not hold everything would then place a 15-year-old at night rather than leave a seat empty, or a female staff member on her day of unavailability rather than leave a forced assignment unmet.

Each breach therefore costs a flat penalty of **10,000 hard points** (`ExclusionEligibilite.FORFAIT`), plus the minutes of excess for the two ceilings counted by the minute. An empty seat or an unmet exception is read on the Problems page and dealt with; an illegal seat must not come out of a solve when an alternative exists. The flat penalty is in the matching weights: an edition weight multiplies it instead of replacing it. The options set aside are in [ADR 0034](repo:docs/decisions/0034-exclusions-eligibilite-plus-lourdes-que-tout.md).

## The constraints, as the catalogue declares them

This table is the rendering of `ConstraintCatalog`. When the catalogue changes, `DocumentationStructuralTest` fails and prints the table to paste between the two markers.

<!-- catalogue:debut -->
| Constraint | Level | Category | What it says |
|---|---|---|---|
| `posteDoitEtrePourvu` | HARD | Assignment | Every open seat on a stand must be filled by a staff member. |
| `animateurDisponible` | HARD | Assignment | A staff member cannot be assigned on a day they have declared unavailable. |
| `pasDeChevauchementHoraire` | HARD | Assignment | A staff member cannot hold two seats whose timeslots overlap in time (including two distinct timeslots that overlap, not only two seats on the same timeslot). |
| `plafondCreneauxParTypologie` | HARD | Assignment | On a game category that carries a cap, a staff member holds no more than that number of timeslots across the whole edition. A seat counts for every game category its stand offers. A game category with no cap imposes nothing. |
| `standReserveAuxMajeurs` | HARD | Legal (minors) | Stands restricted to adults cannot host any minor. |
| `mineurNecessiteEncadrementMajeur` | HARD | Safety (minors) | Off by default. A minor must always be accompanied by at least one adult on the same stand and the same timeslot. A safety rule set by the organiser, not an obligation of the Labour Code: the event's organiser fills it with its managers, who are not scheduled, and therefore does not ask the solver for it. An organisation with no supervisor outside the schedule switches it on from the Planning rules screen. |
| `travailDeNuitInterditPourMineur` | HARD | Legal (minors) | A minor cannot be assigned to a timeslot that encroaches on their legal night: 20:00-06:00 under 16, 22:00-06:00 from 16 to 18 (Labour Code art. L3163-1). |
| `dureeQuotidienneMaxMineur` | HARD | Legal (minors) | A minor cannot exceed 8 hours of effective work on a single day (Labour Code art. L3162-1), reduced to 7 hours under 16 (art. D4153-3). Breaks due are deducted: a break, relayed or taken as a gap, is rest and not effective work. |
| `travailInterditJourFerieMineur` | HARD | Legal (minors) | A minor cannot work on a legal public holiday (Labour Code art. L3164-6, list in art. L3133-1). No sectoral derogation is applied: the one in art. R3164-2 remains to be examined. |
| `reposHebdomadaireMineur` | HARD | Legal (minors) | A minor is entitled to two consecutive rest days within each calendar week, from Monday 00:00 to Sunday 24:00 (Labour Code art. L3164-2 and L3121-35): a Sunday and the Monday that follows it are each a rest day of their own week, but form the pair of neither. Collective-agreement derogations assume an extended agreement or an authorisation from the labour inspectorate: they are not presumed. |
| `travailContinuMaxMineur` | HARD | Legal (minors) | No uninterrupted work period of more than 4 h 30 for a minor: beyond that, every break due must be taken, either as a gap in the grid, or relayed by a colleague at the same stand holding a seat for the whole break. A break that nobody can take is a hard breach (Labour Code art. L3162-3). The duration is the configured one, raised to at least 30 minutes for a minor: this floor is public policy. |
| `dureeHebdomadaireMax` | HARD | Legal (working time) | No adult staff member (all paid, manager or not) can exceed the configured maximum weekly duration of effective work (48 h by default, Labour Code art. L3121-20, public policy). Breaks due are deducted, as for the daily ceiling. |
| `dureeHebdomadaireMaxDeuxSemaines` | HARD | Legal (working time) | An adult staff member cannot reach the maximum weekly duration in two consecutive ISO weeks: 48 h one week then 48 h the next is refused, 47 h then 48 h remains allowed. A short, operational form of the 44 h average over twelve weeks (Labour Code art. L3121-22) — the only one that makes sense for a fortnight-long event. The threshold is that of the maximum weekly duration setting, never a second constant. A week is judged full in effective work: breaks due are deducted from it. |
| `dureeHebdomadaireMaxMineur` | HARD | Legal (minors) | A minor cannot exceed 35 hours of effective work per week (Labour Code art. L3162-1; art. D4153-3 for ages 14 to under 16 employed during school holidays). Breaks due are deducted, as for the daily ceiling. |
| `dureeQuotidienneMaxMajeur` | HARD | Legal (working time) | An adult staff member cannot exceed 10 hours of effective work on a single day (Labour Code art. L3121-18). Breaks due are deducted: a relayed break is rest, not effective work (art. L3121-1). |
| `reposQuotidienMinimal` | HARD | Legal (working time) | Between two worked days, every staff member is entitled to a minimum daily rest: 11 h for an adult (art. L3131-1), 12 h for a minor and 14 h under 16 (art. L3164-1). |
| `maxJoursTravaillesParSemaine` | HARD | Legal (working time) | No staff member can work more than six days in the same week (Labour Code art. L3132-1). |
| `reposHebdomadaireMinimal` | HARD | Legal (working time) | Each staff member is entitled, in each week, to a weekly rest of 35 consecutive hours: 24 hours (art. L3132-2) to which the 11 hours of daily rest are added (art. L3131-1). A rest straddling Monday counts in full for the week in which it falls. |
| `travailContinuMaxMajeur` | HARD | Legal (working time) | No uninterrupted work period of more than 6 hours for an adult: beyond that, every break due must be taken, either as a gap in the grid of at least the configured duration, or relayed by a colleague at the same stand holding a seat for the whole break. A break that nobody can take is a hard breach (Labour Code art. L3121-16, at least 20 minutes). It is also what allows the break to be deducted from the daily and weekly ceilings: in a plan with no hard breach, every deducted break was indeed taken. |
| `coupureRepasObligatoire` | HARD | Organisation (meals) | Anyone working on both sides of a meal window must have, entirely within that window, a free break of the configured duration (60 min by default, lunch 12:00-14:00 and evening 19:00-21:00). Starting one's day at the opening of the window, or ending it at its closing, owes nothing: one ate before, or will eat after. A day straddling both windows owes two breaks. This is not an obligation of the Labour Code — the only break it imposes is the 20 minutes at the sixth hour (art. L3121-16), carried by travailContinuMaxMajeur — but the event's organisation rule, held hard by choice. The legal break and the meal break remain two distinct things: the one-hour break does not stand in for a relay, and a relay does not stand in for a meal — even if a one-hour gap does cut the six-hour sequence in passing. |
| `coupureRepasPlacementPrefere` | SOFT | Preferences | Between two possible meal breaks in the same window, prefer the one the window leans towards: at lunch the later one — 13:00-14:00 rather than 12:00-13:00, the stands have just opened — and in the evening the earlier one, to reopen afterwards. Stand coverage, for its part, is hard: it is its trade-off with this preference that spreads the rotation. |
| `indisponibiliteForcee` | HARD | Ad hoc constraints | Unavailability laid manually by the administrator: the staff member must never be assigned on the targeted scope. |
| `incompatibiliteAdHoc` | HARD | Ad hoc constraints | Two staff members declared incompatible must never work on the same timeslot. |
| `affectationForcee` | HARD | Ad hoc constraints | Assignment imposed by the administrator: the staff member must be present on the targeted timeslot or stand. |
| `affiniteAdHoc` | SOFT | Ad hoc constraints | Pair of staff members to favour: every timeslot where both are assigned to the same stand is rewarded. A soft constraint: it favours co-assignment when possible, without ever forcing it. |
| `arriveeGroupee` | SOFT | Ad hoc constraints | Arrival group (carpooling, 2 to 4 staff members): the members work the same days, arrive and leave together, within the tolerance (30 min by default, adjustable on the rule's row). Each day and each pair of the group costs the minutes of gap beyond the tolerance, at arrival as at departure, and a flat two hours when one works and the other does not. No stand constraint: members may hold different stands. A soft constraint: it yields to the legal rules and to load balance. |
| `animateurVerrouilleFige` | HARD | Schedule lock | The schedule of a locked staff member no longer moves: their validated seats are frozen and the solver can no longer assign them new ones. |
| `animateurVerrouilleCreneauFige` | HARD | Schedule lock | A validated swap is frozen on its timeslot: what each of the two staff members holds there after the swap no longer moves, without freezing the rest of their schedule. |
| `standComplexeAvecReferent` | MEDIUM | Organisation quality | Every stand should have at least one lead on each timeslot. |
| `equilibrerCharge` | MEDIUM | Organisation quality | The workload should be spread fairly among staff members. |
| `stabiliteDuPlanPublie` | MEDIUM | Organisation quality | Once a schedule is published, each person moved off a seat they held in the published plan costs: the solver disturbs people already notified only if the gain is worth the disturbance. Silent as long as nothing has been published; a shift the published plan did not have — another day, other hours or another stand — remains free. |
| `repartitionMineursParCreneau` | MEDIUM | Organisation quality | On a timeslot, a stand should not have more minors than adults. |
| `experienceRequisePourStandsPremium` | MEDIUM | Organisation quality | A premium stand should not be held by a beginner in its game category. |
| `eviterRoulementStandsPremium` | MEDIUM | Organisation quality | On a premium stand, limit the number of different staff members who take turns there beyond one crew: continuity is favoured. |
| `eviterChangementEmplacementEloigne` | MEDIUM | Organisation quality | Between two consecutive timeslots, avoid switching a staff member to a stand whose location is far (> 300 m as the crow flies) from that of the previous timeslot. |
| `trajetInsuffisantEntrePostes` | MEDIUM | Organisation quality | Between two seats of the same staff member on the same day, separated by a gap, at two different locations: the gap must leave time to walk from one to the other (crow-fly distance × detour factor ÷ walking speed). Each missing minute beyond the tolerance is penalised. Two seats that touch remain the business of eviterChangementEmplacementEloigne: a pair is never judged by both rules. |
| `limiterEmplacementsParJour` | MEDIUM | Organisation quality | On a single day, limit the number of distinct locations visited by a staff member (adjustable ceiling, 3 by default): beyond it, the day is scattered whatever the distances. |
| `eviterEnchainementStandsEpuisants` | MEDIUM | Organisation quality | Between two consecutive timeslots, avoid chaining a staff member onto two physically exhausting stands with no rest or easier stand in between. |
| `eviterFermeturePuisOuverture` | MEDIUM | Organisation quality | After a shift that ends late (22:00 by default), avoid an early start the next day (10:00 by default): 12 h of rest is then wanted rather than the legal minimum. An organisation preference, not an obligation of the Labour Code: only the minutes above the legal daily rest are counted here, those below remain the business of reposQuotidienMinimal, which holds them hard. The rule is therefore silent when the law already demands as much (a minor, 12 h; under 16, 14 h). |
| `appreciationIncompatible` | MEDIUM | Organisation quality | The administrator's appraisal covers no game category offered by the stand. |
| `souhaitsIncompatibles` | MEDIUM | Organisation quality | None of the game categories offered by the stand appears in the staff member's declared wishes. |
| `limiterTypologiesDistinctesParAnimateur` | MEDIUM | Organisation quality | A staff member should work on a small number of game categories (adjustable ceiling, 2 by default) across the whole edition, and not only on one day: two categories the same afternoon and two a week apart count the same. |
| `maxJoursConsecutifsTravailles` | MEDIUM | Organisation quality | A staff member should not work more consecutive days than the ceiling set on the rule's row (eight by default) without at least one rest day: less is possible, more should not be. An organisation rule, weightable: no article of the Labour Code imposes a rolling count (L3132-1 is read over the calendar week, Cass. soc. 13 Nov. 2025, no. 24-10.733). |
| `maxJoursConsecutifsTravaillesDur` | HARD | Organisation quality | Off by default. The same consecutive-days ceiling, held hard: beyond it, the plan is refused instead of being penalised. The threshold is the edition's, adjustable on the rule's row: both forms read it in the same place. A weight never changes a rule's level, hence a separate constraint, which an edition switches on from the Planning rules screen, through activer_contrainte or through contraintes.activees of a scenario. It stays filed under "Organisation quality" and not "Legal": it is an organiser policy, not an obligation of the Labour Code. |
| `favoriserMixiteDesNiveaux` | SOFT | Preferences | When a lead is present on a timeslot, pair a beginner with them to encourage skill growth. |
| `equilibrerCreneauxPenibles` | SOFT | Preferences | Spread demanding timeslots (exhausting or premium stands) fairly among staff members. |
| `preserverBufferPolyvalents` | SOFT | Preferences | Keep at least one all-rounder staff member (all-rounder game category) free on each timeslot, so the schedule can be repaired in case of a last-minute absence. |
<!-- catalogue:fin -->
