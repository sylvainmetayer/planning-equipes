---
twin: comprendre/regles-legales-et-pauses
---

# Legal rules, breaks and meals

You will know why the break, the meal break and their deductions are written the way they are, what each one costs, what the solver deliberately does not do, and which legal framework applies. Weekly rests, consecutive days and the rest after a late closing are covered in [Rest and consecutive days](repos-et-jours-d-affilee.md).

The framework as explained to an organiser is in [the Labour Code and schedules](/code-du-travail/), and the case of under-18s is in [minor staff members](/animateurs-mineurs/).

## Break length

A single break length applies to the edition: `dureePauseMinutes`, set in Settings, Legal tab (the row of each rule that reads it, in Planning rules, points there) ([ADR 0048](repo:docs/decisions/0048-une-seule-regle-de-pause.md)). It is read by `travailContinuMax`, `PauseSurPoste`, the eligibility filter, all the hour counters and the break analysis, and so by the Breaks and meals screen and by the "Break from 18:20 to 18:40" card of the staff PDF. All of them go through `ParametresLegaux.dureePauseMinutes(mineur)`, the only way to guarantee that none is stricter than another.

It is a **floor**, unlike the two weekly durations, which are ceilings: 20 minutes (art. [L3121-16]), refused below that by the service, free above it. The default is 30 minutes. Giving more than the Code requires is an organisational choice (a 30-minute relief is easier to organise than a 20-minute one); the only direction an application must not allow is the other one. An assistant also sets it, with the same floor, through the MCP tool `modifier_parametres_legaux`.

**The application only asks for an adult's break beyond six hours of continuous work.** Art. [L3121-16] makes it due "as soon as the daily working time reaches six hours": this is a knowing departure from the text, `travailContinuMax` reading the threshold as an excess. It is deliberate: a timeslot of exactly six hours (14:00-20:00, for instance) would otherwise become a hard breach nobody can cover, the break falling at its very end, where nobody has anything left to relay. To be revisited if a contrary legal reading were adopted for the event.

**The minor's floor is not stored, it is applied on read.** The 30 minutes of art. [L3162-3] are a matter of public policy, but an edition that grants 25 minutes is not unlawful: it gives 25 to its adults and 30 to its minors. `dureePauseMinutes(true)` returns `max(valeur, 30)` instead of refusing the setting, which would have forced the edition to give 30 to everyone. The constants `PlafondsLegauxMajeurs.PAUSE_MINIMALE_MINUTES` and `PlafondsLegauxMineurs.PAUSE_MINIMALE_MINUTES` are the two floors.

### What the break deducts, what the opening span counts

The break is rest, not actual work (art. L3121-1): it is **deducted**, paid or not, with nothing to declare. In a plan with no hard breach, every deducted break was indeed taken: that is what `travailContinuMax*` guarantees. The deduction applies everywhere:

| Where | What is measured |
|---|---|
| `dureeQuotidienneMax` | actual work: opening span minus the breaks due |
| `dureeHebdomadaireMax`, `dureeHebdomadaireMaxDeuxSemaines` | same, summed over the ISO week |
| Planning by person page, "Hours for the payroll" CSV (`PlanningHoursService`), `heures_travaillees` tool | same: total and weekly columns |
| fairness table (`EquiteService`) | same: this is the quantity being shared out |
| KPIs (`PlanningKpiService`) | same, except old snapshots |
| volumes (`ProblemScaleService`) | same for held seats; an empty seat counts its opening span |

`EffectiveWork` is the only place where this calculation lives. A break is due **per day and per staff member** (a sequence does not cross midnight): every weekly reader groups by day before summing, never the reverse (`LegalConstraints.joursAvecParametres`). A real gap between two shifts deducts nothing: no sequence in it exceeds six hours, so no break is due there, the gap is the break. `HeuresCoherentesTest` locks the readings to the same value: summing the opening span on one screen and the actual work on another gave two versions of the same schedule, half an hour a day apart, small enough to pass for rounding.

**The daily opening span is shown, not capped.** From the first start to the last end of a day, gaps and breaks included, it is displayed (maximum, number of days beyond 12 h) in the per-person summary, the hours CSV, `heures_travaillees` and the timeline of the staff member's sheet. The threshold is a constant (`PlanningHoursService.SEUIL_AMPLITUDE_MINUTES`) that no rule reads: capping the opening span awaits a lawyer's opinion.

The opening span remains the quantity for three named cases:

- the three premium counters of the payroll (Sunday, public holiday, "Night (payroll)" after 22:00) and their equivalents in the fairness table. A premium is paid on presence, and a break cannot be placed on one side of midnight without inventing when it was taken. The three columns therefore do not add up to the total. The evening is not a premium: there is only one, the adjustable hour of the legal parameters ([ADR 0055](repo:docs/decisions/0055-une-seule-soiree-la-nuit-de-la-paie-a-part.md));
- an empty seat in the volumes (`hoursToFill`): what it will cost its holder in breaks is unknown as long as nobody holds it;
- the KPIs of an old snapshot, recomputed in degraded mode: its seats carry no hours, so no sequence can be deduced from them.

The "workload" bound of the Staffing need screen likewise raises the two ceilings by the break taken under them (`maxDailyAmplitude`): a day with a 10 h 20 opening span is 10 hours of work, so one person can cover it. Dividing an opening span by an actual-work ceiling would demand people the plan does not need, which is the only error that matters for a bound announced as a proof.

## The legal break: a gap or a relay

A break that is due is taken in one of two ways, and the tool knows no third:

- **a gap** of at least `dureePauseMinutes` in the grid. It breaks the sequence, and nothing is due on either side;
- **a relay**: a colleague of the **same stand** holding a seat during the whole break.

With neither, it is a **hard breach**, one point per break that cannot be relayed, carried by `travailContinuMax`, plus the eligibility exclusion flat penalty for a minor ([the eligibility exclusions](pourquoi-ces-regles.md)). The breaks due come from `PauseSurPoste`, which the Breaks and meals screen, the staff PDF and the ICS feed also read: none of the four diverges on what is due or when.

**Why hard and not weighted.** A relay that does not exist is not a lost comfort. With nobody to hold the stand, the person cannot leave it, their "break" remains actual work (art. L3121-1 and L3121-2; paying the break does not change its legal characterisation, Cass. soc. 22 May 2019, no. 17-26.914), and the twenty minutes of art. L3121-16 are not given. That is also what justifies the deduction above: the two decisions only hold together. A "break taken on the post" checkbox that switched off `travailContinuMax*` once existed; it left two readings of the same schedule and switched off the minors' rule although the 4 h 30 of art. L3162-3 are a matter of public policy.

Where the rule bites: a **single-seat stand held for more than six hours in a row**. Nobody can relay, and no assignment reaches zero hard. The answer is to recut the grid or open one more seat; there is no weight to lower, the rule admits none.

**A break due in a sequence that has already elapsed is held against nobody** ([ADR 0044](repo:docs/decisions/0044-le-passe-est-fige.md)). Its seats are pinned: a hard breach placed there could not be repaired by any move, and a solve restarted in the middle of an event would never reach zero again. The rule groups by staff member and by day, but the day guard is not enough, because a day whose morning is over still has an evening seat. It is the seat the relay should have covered that decides.

## The meal break

`coupureRepasObligatoire` (hard) and `coupureRepasPlacementPrefere` (soft) carry the meal break. Without them, a ten-hour day with no break came out at zero hard with nothing to flag it.

**This is not the Labour Code.** The only break it imposes is the one in art. L3121-16, which `travailContinuMax` already carries. The meal break is the event's own organisational rule, the one for which the grid of the source workbook cuts its midday shifts. It is held hard by choice, under the protected category "Organisation (repas)" (organisation, meals).

### The rule, exactly

For each (staff member, date) pair and each declared meal window:

- it is **due** if the person works on both sides: a seat starts before the window opens and a seat ends after it closes. Someone who starts at 19:00 has eaten before, someone who finishes at 14:00 will eat after;
- it is **satisfied** if a free gap of at least the configured length fits entirely within the window. On a 12:00-14:00 window at 60 minutes, that is 12-13, 13-14 or anything placed between. A 13:45-14:45 break counts only for its first fifteen minutes: the window is the catering service;
- the **penalty** is the number of missing minutes, never all-or-nothing: a day short by a quarter of an hour and a day that never stops are not the same problem, and a step would leave no slope to descend;
- there is **one break per window crossed**: a 10:00-23:00 day owes two.

The break is **one hour** by default (`coupureRepasMinutes`). A two-hour window then splits into two whole timeslots (12-13 or 13-14 at midday, 19-20 or 20-21 in the evening), which is how the event is really organised; at 45 minutes, the break floated within the window without landing on a timeslot. A window shorter than the break it requires is discarded outright (`FenetreRepas.depuis`): nobody could satisfy it, and punishing a data entry is not the score's job.

**A meal break is a break within the meaning of art. L3121-16**: it interrupts the continuous sequence, and the six-hour counter restarts from zero. This is a consequence of the model, since `PauseSurPoste.sequences` breaks a sequence on any gap of at least the legal break, and `RepasConstraintsTest` locks it because nothing in the code writes it down.

### The rotation and what it costs

Offering the choice between 12-13 and 13-14 assumes two distinct timeslots at midday, and that somebody holds the stand while the others eat. `couverturePause` on a timeslot halves, rounded up, the seats a stand opens there; the flag is set on the timeslot or on the shift of a typical day. Without it, a rotation demands two full crews. A grid of contiguous blocks (11:00-12:00 then 12:00-13:00) needs no gap setting between shifts.

The 11-hour **daily rest** is still due between the end of an evening and the next morning. The floor on the Staffing need screen does not see it (its bounds ignore it) and may announce a comfortable margin while part of the workforce is unusable.

`coupureRepasPlacementPrefere` then decides between 12-13 and 13-14 by penalising the distance to the end of the window towards which it leans. **At midday, it is the later one**: the stands have just opened, nobody eats at noon sharp. **In the evening, it is the earlier one**: people eat early to reopen afterwards. The rule reads what the person *could* choose: a day that leaves the whole midday free is not "eats at noon sharp", it costs nothing. Since stand coverage is hard, it is its trade-off with this preference that distributes the rotation.

### Two independences

**Independent of the legal break.** A relay says that the twenty minutes of art. L3121-16 take place, it says nothing about lunch: satisfying one never exempts from the other. A one-hour gap cuts the six-hour sequence on the way, which is the only thing the two rules have in common.

**Independent of `modeGrille`.** The meal windows travel as problem facts (`FenetreRepas`, projected from `ParametresLegaux`) on any grid, whether entered as opening spans or as shifts. The break is set with the legal parameters.

### What it can make infeasible

On a grid where **a single timeslot covers the whole window** (a 10:00-20:00 shift in one block), its holder cannot leave and the seat must be filled: no assignment reaches zero hard. The answer is to recut the grid, or to switch the rule off from Planning rules.

A heavy weight on a medium rule is paid for in the feasibility phase, which accepts its moves on the whole score. The usage rule holds for all medium rules: reach zero hard at the default weighting, then weight and re-solve warm, never a heavy weight in a cold start.

### Where the other screens read it

The Breaks and meals screen lists the break due, the largest free gap and what is missing, through the **same `CoupureRepas`** as the rule: there is only one, so they cannot diverge. The Staffing need screen counts the break in its floor (fifth bound of `StaffingAnalyzer`), but `StaffingResource` passes it the windows only if the rule is active: a rule the solver does not have to honour must not raise the number of staff members to recruit. This bound counts a **seat that covers the whole window** as leaving no gap to eat for its holder: an evening seat 20:00-midnight against a 20:00-21:00 window forbids them the afternoon. A seat that overflows on both sides, on the other hand, raises nothing: it is the infeasible grid above, which no workforce can save. The Problems screen has nothing specific: the rule being hard, its breaches come up through `ConstraintCatalog.NOMS_DURS`.

## The Animation collective agreement (ÉCLAT, IDCC 1518)

It **does not apply**: the organisation confirmed that it does not fall under it. Everything the code cites is the Labour Code alone. The mention "CCN Animation ÉCLAT art. 5.2" that accompanied the 48 h ceiling was removed, because it was wrong twice: art. 5.2 deals with rest days and the high week appears in art. 5.7.2.3. It survives in the comment of `V7__parametres_legaux.sql`: an applied migration is not modified, its fingerprint is frozen (`FlywayMigrationsFrozenTest`).

This is a **declared fact, not a legal verification**. If the agreement applied, art. 5.3 would impose a 45-minute break on every day (the 30 minutes retained are below that floor) and would cap the opening span at 12 h, and art. 5.2 would give two consecutive rest days to every employee, which would mechanically forbid the twelve days in a row. One reservation remains to be lifted: a Légifrance page showed these articles as "not in force" whereas the 2024 consolidated texts carry them as "in force, extended".

## Deliberately out of scope

These obligations are real and deliberately not implemented, so that their absence is a written choice.

**Average weekly duration of 44 h over 12 weeks ([L3121-22]).** An event covers a few ISO weeks at most: the solver knows neither the previous weeks nor the following ones. This is a structural limit. The check belongs to the HR department, from the weekly total that Planning by person exposes.

**Night work of adults ([L3122-1] and following).** `chevaucheNuit()` is consulted only by the minors' rules, although the shipped scenarios include a nightly timeslot every day. Qualifying as a "night worker" depends on a threshold and a regularity, but also on the contract, a collective agreement or an authorisation from the labour inspectorate, facts the application does not hold. The exact numbering of the articles remains unverified: manual check until then, with a lawyer.

| Other subject | Why out of scope |
|---|---|
| Overtime, annual quota ([L3121-30]) | The schedule produced is the basis of the count, not the count |
| Nature of unavailabilities | `joursIndisponibles` is an untyped `Set<LocalDate>`: it is impossible to tell a statutory rest, paid leave and a convenience apart |
| Inspectorate authorisation for under-16s ([L4153-3], [D4153-2]) | Administrative data absent from the model, to be checked manually |
| Sector derogation for public holidays ([R3164-2]) | Not investigated; the most protective default applies |
| Sunday rest of minors ([L3132-3], [L3164-3] to [L3164-5], [R3164-1]) | The derogations of L3132-4 and L3132-8 do not apply to under-18s, and only apprentices in twelve listed sectors may work on Sunday. The organiser's sector and each staff member's status are facts the repository does not contain: a minor placed on a Sunday is accepted, to be checked manually |

[D4153-2]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=D4153-2
[L3121-16]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3121-16
[L3121-22]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3121-22
[L3121-30]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3121-30
[L3121-35]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3121-35
[L3122-1]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3122-1
[L3131-1]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3131-1
[L3132-2]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3132-2
[L3132-3]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3132-3
[L3162-3]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3162-3
[L3164-1]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3164-1
[L3164-2]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3164-2
[L3164-3]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3164-3
[L3164-5]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3164-5
[L4153-3]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L4153-3
[R3164-1]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=R3164-1
[R3164-2]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=R3164-2
