---
twin: comprendre/repos-et-jours-d-affilee
---

# Rest and consecutive days

You will know how the solver reads a rest that straddles two weeks, why two full weeks in a row are forbidden, why the cap on consecutive days exists in two forms and is set at eight days, and what a late closing followed by an early opening costs. The articles cited are those of the Labour Code, the only framework applied; breaks and the meal break are in [Legal rules, breaks and meals](regles-legales-et-pauses.md).

The rests of minors are also summarised, article by article, in [the questions on minor staff members](/animateurs-mineurs/).

## The week boundary

The two weekly rests are read on the **calendar week**, from Monday 0:00 to Sunday 24:00 ([L3121-35], a default definition that a company agreement may move, a fact the application does not hold), the same window as `Creneau.semaineIso()` and the weekly ceilings. What remains is the question of a rest that straddles Monday.

**Adults, `reposHebdomadaireMinimal` ([L3132-2]).** The text speaks of "twenty-four consecutive hours to which are added the consecutive hours of daily rest": the 24 h are within the week, only the daily rest added to them may overflow. `creditReposMinutes` computes `min(durée réelle, part dans la semaine + 11 h)`. A rest that straddles is credited to both weeks but reaches the 35 h floor only in the one where it spends at least 24 h. A rest from Saturday 20:00 to Monday 10:00 (38 h) satisfies Saturday's week and leaves the next one at 21 h, as the Court of Cassation reads it (Cass. soc. 13 Nov. 2025, no. 24-10.733, citing articles L3132-1 and L3132-2).

**Minors, `reposHebdomadaireMineur` ([L3164-2]).** "Two consecutive days of rest per week" is counted in calendar days inside the week. A Sunday and the following Monday are one free day in each of two weeks, not two consecutive days of either: neither week is satisfied. Crediting the pair to both weeks was rejected, because it brought a minor down to a single free day per week, which is the 36 h derogation of paragraph 2 without the agreement it requires. `mineurAvecDimancheEtLundiLibresAChevalSurDeuxSemainesEstPenaliseSurChacune` (`LegalConstraintsTest`) locks this choice. Days the event does not cover remain free days, but the two days must follow each other: a week opened in dotted form (Monday, Wednesday, Friday, Sunday) is still penalised if the minor holds them all.

## Two full weeks in a row

`dureeHebdomadaireMax` caps each ISO week independently: nothing prevented two full weeks back to back, a shape towards which a solver converges on an event straddling two weeks with a tight need. `dureeHebdomadaireMaxDeuxSemaines` (hard) forbids it for adults.

Its threshold is that of `ParametresLegaux.dureeHebdomadaireMaxMinutes`, never a second 48 h constant that could diverge. The penalty counts **pairs** of consecutive full weeks: three weeks in a row cost twice as much, so that the solver has a slope to descend rather than a wall. A week is full in **actual work**, not in opening span, and since timeslots are in whole hours, "strictly under 48 h" means "47 h at most".

This is the short form of art. [L3121-22] (44 h on average over twelve consecutive weeks), the only one that makes sense over fifteen days: the solver knows neither the weeks before nor those after. The rolling average is [out of scope](regles-legales-et-pauses.md). The rule does not catch 47 h 59 then 48 h, allowed by construction: this is the wording the organiser asked for.

## Consecutive days

`maxJoursTravaillesParSemaine` (L3132-1) and `reposHebdomadaireMinimal` (L3132-2, 35 h) guarantee one free day per calendar week. They still let through Tuesday-Sunday then Monday-Saturday: twelve days in a row at zero hard violations. A cap on **rolling** days is therefore a rule of its own, and an **organiser's policy**, not an obligation. L3132-1 is read on the calendar week (L3121-35), and the Court of Cassation holds that the weekly rest need not fall at the latest after six consecutive days (Cass. soc. 13 Nov. 2025, no. 24-10.733; same reading CJEU C-306/16).

It exists in two forms, because a weight never changes the level of a rule (`SolverConfiguration.constraintWeightOverrides` maps onto the catalogue's level):

| Rule | Level | Default |
|---|---|---|
| `maxJoursConsecutifsTravailles` | medium, weighted | on, normal importance (weight 5) |
| `maxJoursConsecutifsTravaillesDur` | hard | off |

The two share their body and their threshold, so they never diverge on what they count or on the number of days they let through. They can be on together, which says "never more than N, and preferably fewer". The hard form is switched on from the Planning rules screen, through `activer_contrainte` or through the `contraintes.activees` of a scenario, with no migration, like [ADR 0041](repo:docs/decisions/0041-encadrement-des-mineurs-eteint-par-defaut.md).

### Why the threshold is eight days

The threshold (`joursConsecutifsMax`, **eight by default**) is set per edition, on the rule's row. It leaves the code constant because it decides **feasibility**, not comfort: the rule demands a rest day in every window of (threshold + 1) days, so one day more or less changes the number of rest days to find. On the versioned winter grid (`festival-hivernal`), six days is a cap that nothing satisfies with the hard form on, whereas eight reaches zero hard violations. An unreachable cap is worth less than a wider cap that holds, and the weight cannot help: it does not make reachable what arithmetic forbids. Tightening towards six is solved by the grid or by the workforce, not by the solver. The comparison protocol is in [ADR 0045](repo:docs/decisions/0045-le-niveau-de-la-regle-des-jours-d-affilee.md).

At the shipped weighting, the medium form leaves some people beyond the threshold: that is what the event demands with a constant workforce. The Fairness screen shows it, in the "longest streak" column, before publication.

### Checking that a threshold holds, then getting it found

These are two questions. The first is arithmetic, and the feasibility analysis asks it before any solve as soon as one of the two forms is on. Each day demands at least its **floor** (its peak of simultaneous seats, read in the same segments from which the seats arise). Each staff member offers, over a window of (threshold + 1) days, only the smaller of two numbers: the threshold, or their available days in the window. On the tightest window, the Problems screen says what the situation is, with the dates named and never a person:

| Margin (supply minus demand) | What the analysis says |
|---|---|
| negative, hard form on | **blocking**: no plan holds the rule, N person-days are missing, that is at least ⌈N / threshold⌉ staff members |
| negative, medium form alone | warning: the plan will necessarily exceed the cap for some |
| positive but under 10% of the demand | warning: the rule will hold only if each day employs almost the minimum number of people |
| wider | nothing |

The condition is *necessary*, not sufficient: skills, hours, legal rules and a day held by more than its floor consume the margin. Inflating the floor to catch these cases would cry impossible on grids that the solver fills without difficulty. Nor does it couple by skill. Under the hard form, when the grid leaves room, the analysis rereads the plan in place with the same arithmetic and warns if even there the count does not work out. A window entirely in the past is never judged; in a window that straddles the past, a day already worked counts whoever actually held it, not its floor.

The Staffing need screen counts the hard form exactly: it looks for the smallest workforce whose rest calendars together hold the six days per week and the consecutive-days threshold, by a maximum flow over the rest days ([ADR 0071](repo:docs/decisions/0071-le-besoin-en-animateurs-un-minimum-exact-verifie-par-une-equipe-fictive.md)). The medium form alone never raises this figure: a rule the solver may break must not make anyone recruit.

What decides remains a **plan**: built separately and imported, the Problems screen judges it, and zero hard violations proves that the need is achievable. The second question is the solver's. Under the hard form, its feasibility phase searches by **whole days**: the chain that fills a hole frees a day of the *streak* and not only of the ISO week, a day given back goes only to colleagues already present that day, and a day held in pieces is regrouped on fewer people ([ADR 0049](repo:docs/decisions/0049-la-regle-dure-des-jours-d-affilee-se-cherche-par-jours-entiers.md)). Construction also places seats by date rather than by scarcity, because in scarcity order the setup everybody knows how to hold came last, when the streaks that follow it were already long ([ADR 0068](repo:docs/decisions/0068-sous-la-regle-dure-la-construction-suit-le-calendrier.md)).

## Closing late then opening early

`reposQuotidienMinimal` holds the daily rest as a hard rule: 11 h for an adult ([L3131-1]), 12 h for a minor and 14 h before age 16 ([L3164-1]). Someone who closes at 23:00 and resumes at 10:00 respects the 11 h to the minute, which is legal but debatable over fifteen days. `eviterFermeturePuisOuverture` (medium, "Organisation quality") expresses this preference and nothing else: it is not an obligation of the Code, hence the absence of a "Legal" category.

### What it counts

Three thresholds of `ParametresQualite`: late service (the day **ends** at 22:00 or after), early service (the next day **starts** at 10:00 or before) and desired rest (12 h). The settings are `planning.contraintes.heure-service-tardif`, `planning.contraintes.heure-service-matinal` and `planning.contraintes.repos-souhaite-apres-service-tardif-minutes`.

The penalty is the number of minutes missing from the desired rest, **counted from the legal floor**: `souhaité − max(repos réel, plancher légal)`. This formula keeps the rule from becoming a second floor in disguise:

- a schedule that is already unlawful is not billed twice for the same minutes: those under the floor remain the business of `reposQuotidienMinimal`;
- where the law already requires as much as the wish (a minor, 12 h; before age 16, 14 h), the rule is entirely silent;
- a desired rest set at the level of the floor, or below it, makes it inert: a preference that does not exceed the law says nothing. Leaving both service hours empty is a legitimate answer ([ADR 0031](repo:docs/decisions/0031-signaler-le-plancher-sans-le-decider.md)).

The bounds are compared **as instants**, not as clock times: a 20:00-0:00 shift ends the next day at 0:00 and would pass for early if read as a time, whereas it is the closing the rule is looking for.

### One night, one penalty

The rule reasons on **days**, not on pairs of shifts. The rest of a night is a single quantity (last end of D, first start of D+1), whereas a join by pairs bills it once per pair: someone who resumes at 8:00 then again at 10:00 paid twice for the same night, and removing the 10:00 seat halved the penalty without giving back a minute of sleep. The gradient the solver descends must be the deficit itself: the day is aggregated first (`max(fin)`, `min(début)`), then the two days are joined on the adjacency key. An evening cut in two is covered by the same move. `reposQuotidienMinimal` can afford the pairwise form: it is a binary floor, not a magnitude.

### Why 22:00 and 10:00

10:00 and not 9:00: with an early threshold at 9:00, a shift ending at 22:00 or after and a resumption at 9:00 or before are at most 11 h apart, never beyond the legal floor of an adult that the rule refuses to bill. The only pair caught would be the exact point 22:00 → 9:00: an inert rule. At 10:00 the useful band is `[11 h, 12 h[`, exactly the targeted pattern.

### The tension with continuity on a premium stand

`eviterRoulementStandsPremium` pushes to keep the same faces on a premium stand; on a stand that opens and closes every day, avoiding closing then opening amounts to rotating them. Both rules are medium and weighted: the trade-off is set **per edition**, not in the code. On the anonymised real grid `festival-realiste-canicule`, the rule removes most of the clopening for a price of a few dozen points on the other rules. `scenario-complet` cannot answer the question: no timeslot there starts before 11:00, the rule cannot trigger, and a zero figure there is a grid artefact.

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
