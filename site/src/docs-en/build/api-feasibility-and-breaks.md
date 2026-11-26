---
twin: comprendre/api-faisabilite-et-pauses
---

# Feasibility, fragility and breaks on the API side

You will learn what the plan's capacity reads say, without running the solver: feasibility and the minimum staffing need, fragility (who is irreplaceable), legal breaks and meal logistics. They also serve on the day itself, described in [event day on the API side](api-jour-j-et-animateurs.md).

## Feasibility and staffing need

Two capacity computations without running the solver, displayable before a solve of several minutes. They are deliberately optimistic and read as a floor.

- `GET /api/feasibility` does not know which precise stand each staff member could hold: `feasible: true` does not guarantee a zero hard score after solving. Skill does not enter into it: since it became a medium constraint, any available staff member can hold any stand, and a gap in appraisal is reported after solving.
- `GET /api/staffing` looks at skills only in its per-game-category part, and ignores the daily rest. Declared unavailabilities are reported separately.

### The causes of feasibility

A cause is not always a shortage of staff. The following causes are `CRITIQUE` and name the exceptions concerned in `contrainteIds`:

| Cause | Meaning |
| --- | --- |
| `CONTRAINTES_AD_HOC_CONTRADICTOIRES` | Exceptions that cannot all hold. They are refused at entry: these were saved before that check or imported in bulk. Ranked before the understaffing causes, because it is fixed by deleting an entered row |

The contradiction triggers a confirmation on the three buttons of the Solver screen, because they guarantee a negative hard score whatever the budget. Understaffing, even `CRITIQUE`, triggers none: the solver still mitigates it, and a confirmation that fires on every tight evening is no longer read.

`PLAFOND_JOURS_CONSECUTIFS` reads the ceiling of days worked in a row against the whole grid, as soon as the rule is on. It is a single cause, on the tightest window, whose `date` is the first day. `demande` and `capacite` count person-days, `manque` the deficit (0 on a mere thin margin). It is `CRITIQUE` only as a proof under the mandatory rule, `ELEVE` otherwise, and an `ELEVE` cause does not make the report infeasible. A `CRITIQUE` is ranked with the contradictions so as never to fall outside the ten causes returned. The saved plan is reread in only two cases: during the event, a past day counts the people who actually worked it; under the mandatory rule, when the grid passes, the same cause can come from the plan in place.

### The bounds of the minimum need

Four bounds, the largest being retained. Each follows from a rule that `LegalConstraints` really applies, so that floor and solver cannot contradict each other.

| Bound | Computation |
| --- | --- |
| Simultaneous peak | Seats open at the same instant: nobody holds two |
| Peak with break | The same peak on intervals extended by the legal break between two shifts: the exact number of distinct staff members that the busiest day requires |
| Hourly load | The hours of the busiest ISO week, related to what a staff member can work in it |
| Rotation over the days | The person-days of that same week, related to the six days a staff member can work in it (art. L3132-1) |

The last two are proven week by week, never on the total: the unused capacity of a week that the event barely touches endows no other week. A week's capacity is capped twice, by the weekly ceiling and by the days the event occupies in it (six at most, of ten hours at most). Rotation catches the case of a hundred-person day repeated seven days in a row, which cannot be held at a hundred since nobody works seven days. A day's need is the larger of the peak with break and its hours divided by the daily ceiling.

`minimumAvecIndisponibilites` corrects the floor by the declared unavailabilities: a day on which 70% of the known pool are free requires a pool of `need / 0.7`. It is a projection (it assumes the recruits to come are also unavailable), hence its distinct field; `indisponibilitesDeclarees` distinguishes "everyone is free" from "nobody has answered".

`parCompetence` breaks the four bounds down by game category, the bottleneck by skill ("4 people skilled in escape game are missing"), with two attribution rules:

- a seat counts for a game category only if its stand offers that one alone. The seats of a multi-category stand are claimed by none and go into `siegesNonAttribues`. A stand with no game category at all is the opposite case: `hasCompetenceFor` answers yes only to all-rounders, its seats are the tightest demand and join the all-rounder line (`siegesReservesAuxPolyvalents`). With no all-rounder game category in the reference data, nobody can hold them;
- an all-rounder (a game category marked `polyvalent`) counts as a specialist only in the skills they declare, never in all of them: counting them everywhere would add the same person to every pool and hide the bottleneck. They are reported once more as a shared reinforcement (`polyvalents`). `typologiePolyvalenteDefinie` false reads "no reinforcement here", not "shortage". The all-rounder line's shortfall is isolated in `manquePolyvalents`, since that pool being the reinforcements' own, it cannot be offered against its own shortfall.

The two remaining approximations go the same way (demand underestimated for multi-category stands, pool overestimated for a staff member skilled in several): a bottleneck reported is one, the absence of a bottleneck proves nothing.

With no staff member in the reference data there are no bounds: the response is that of an empty edition, all bounds at 0, and `animateursTotal` at 0 is what distinguishes "list not yet filled" from "no single-category stand". A simulation is built server side on in-memory copies, nothing is written: the JSON of a real schedule exceeds the admitted body size.

## Schedule fragility

`GET /api/fragilite` answers a distinct question: who is irreplaceable. `/api/feasibility` says why a schedule does not hold, `/api/staffing` how many to recruit, this one what a withdrawal would take away. It launches no solve: it reads the persisted plan and the skills. Two indicators:

- per staff member, the stand x timeslot pairs that would fall below the minimum headcount if they withdraw. Seats are generated at `max(1, headcount of the open segment)`, half of that on a break-cover shift: the number of seats of a group is its floor. The figure that ranks is `postesIrremplacables`, the groups where nobody else could take over the seat. A group already understaffed is attributed to nobody (`groupesDejaSousEffectif`);
- the stand x timeslot pairs held by at most one person skilled for the stand's game categories, with the corresponding severity.

A possible replacement is skilled, not declared unavailable that day, an adult if the stand is reserved for adults, and free of any overlapping seat. Daily rest, weekly ceiling, ad hoc and preferences are not checked: an "irreplaceable" one certainly is, a "replaceable" one may not be after solving. Only one withdrawal is simulated at a time.

All-rounders are counted differently depending on the indicator, on purpose (`Animateur.hasCompetenceFor` answers yes for them on all stands):

- skill scarcity counts only specialists and shows the available all-rounders in `renforts`. Including them would make almost all the rows disappear, a void that would be a measurement artefact;
- the replaceability of a seat counts them fully: the question is whether the seat can be held tomorrow, and the solver would send an all-rounder there.

The reasoning and the two options discarded are in [decision 0017](repo:docs/decisions/0017-fragilite-le-ninja-est-un-renfort-pas-un-specialiste.md). `polyvalentConfigure: false` signals that no game category is marked all-rounder: the two counts then coincide and a `renforts` at zero says nothing.

Three clarifications of the contract. A pair is really a triplet stand x timeslot x window: a stand closed in the middle of a timeslot produces several rows that differ only by `heureDebut`, and `groupesSansSpecialiste` as well as `groupesAnalyses` are counted in that unit. `effectifMin` is the headcount configured for the window, not the floor: on a break-cover shift (`couverturePause: true`) the seats are generated at the half rounded up, and `siegesRequis` carries the applicable floor. The lists are bounded (20 detailed seats per staff member, 100 pairs); `postesNonDetailles` and `totalCompetencesRares` say what is not shown, and staff members with no assignment are not listed.

## Legal breaks

`GET /api/pauses` says where breaks fall, for whom, from what time to what time, on which stand and who relays. The solver requires that a due break be takeable (a gap in the grid or a colleague on the same stand: art. L3121-16, six hours; art. L3162-3, 4h30 and thirty minutes at least for a minor) without saying at what minute it is taken. Laying down the rotation is what this report does. A break that nobody can relay is a hard breach; a persisted plan may carry one, reported with `relaisDisponible` false.

It is a read of the persisted plan, under the current legal parameters ("with what I declare today, what is left to organise"), never a solve. With no plan: empty report, `journeesAnalysees` at zero, no error.

- `journees[]`: one entry per staff member and per day that owes or lists a break, sorted by date then name, with `mineur` (the scale), `sequences[]` and `pausesPlanifiees[]`.
- `sequences[]`: the uninterrupted work sequences over the seats' effective windows, merged when the gap between two is shorter than the legal break. Each `pausesDues[]` carries `debut` and `fin`, `heureLimite` (at the latest; `debut` never exceeds it), `dureeMinutes` (the edition's, at least 30 for a minor), the stand held during the break, `relais[]`, `relaisDisponible` and `simultanee`. A twelve-and-a-half-hour sequence owes two, the second six hours twenty after the first.
- `pausesPlanifiees[]`: the gaps of at least the legal break between two sequences, typically the midday handover.
- `journeesAnalysees`, `pausesDues`, `relaisManquants`, `message`: the screen's counters and its sentence.

The rotation lays down, on each stand and each day, the breaks one after the other as late as possible: the first one handled is the one whose limit time is the latest, the next ends where the previous begins, so that a single person of the stand is out at a time. A break does not start too early either: what remains of the sequence must fit under the threshold. When the windows leave no room (three people over twelve hours in a row), the break is laid at the start of its window and `simultanee` says so; the plan is not modified. An unknown birth date is read on the adult scale, the adult break always being due. Only one break rule exists ([decision 0048](repo:docs/decisions/0048-une-seule-regle-de-pause.md)); the rules themselves are in [legal rules and breaks](regles-legales-et-pauses.md).

The same read serves the individual schedule: `pauses[]` of `GET /api/espace-animateur/{jeton}` (on the published plan), its PDF ("Break from 18:20 to 18:50 (30 min)" under the shift that owes it) and its calendar feed. The PDF also carries the meal break, which is not the legal break: it is the rule the organisation gives itself, one hour by default, in the midday and evening windows of the legal parameters. When the legal break falls within the meal break, a single card says so ("Meal from 13:00 to 14:00 (60 min), legal break included"): two cards would make people read two obligations for a single moment.

## Meal logistics

`GET /api/pauses/intendance` is the same read, counted instead of named: per day and per window, how many people are on break, hour by full hour and by location. The "Breaks and meals" rendering of the Planning page says who goes out and when; this one says how many meals to prepare and where to take them.

- `journees[].fenetres[]`: one table per declared window, with `tranches[]` (start of each band, aligned on the hour and not on the start of the window: 12:15-13:45 reads 12-13 then 13-14, the width being `pasMinutes`), `emplacements[]` and the totals.
- `emplacements[].personnes[]` and `mineurs[]`: one count per band. `total` counts the distinct people of the window, never the sum of `personnes[]`, which counts someone once per hour their break crosses.
- `message` says why the report is empty when it is (nothing solved, no window, no day crossing a window): "0" and "nothing is solved" are not the same screen.

The place is the location of the stand that the person leaves when the break begins, where the food has to be brought. The time is that at which `PauseAnalyzer` places the break, as early as possible in the free gap, and not the one `FenetreRepas.auPlusTard` would prefer, so that the two screens read the same day the same way. Nobody is named and minors are counted: neither name nor birth date leaves. `GET /api/pauses/intendance/export` is the same list flattened (`jour;fenetre;emplacement;tranche;personnes;dont mineurs`), because it leaves the tool to go to catering. The organisation of the meals themselves stays outside the tool: this point exposes only the figure that only the schedule knows.
