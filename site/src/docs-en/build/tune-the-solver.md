---
twin: comprendre/regler-le-solveur
---
# Tune the solver

You will know which properties bound a solve, what each `solverConfig.xml` setting buys and what it costs, and how to write a rule without slowing down the search. What follows holds for the Timefold engine, the one of `solverConfig.xml` and the *constraint streams*, which defines the rules and re-scores any plan returned by the direct engine (see [how the schedule is calculated](comment-le-planning-est-calcule.md)). The measurements that settled each setting, and the leads set aside, are gathered in [the project's memory](repo:docs/memoire-du-projet.md).

## The properties that bound a solve

| Property | Default | Role |
| --- | --- | --- |
| `planning.solver.seconds-limit` | `900` (`SOLVER_SECONDS_LIMIT`; `3` in `%test`) | The instance's solve budget, the same for every edition ([ADR 0110](repo:docs/decisions/0110-budget-de-calcul-d-instance-file-en-memoire-quota-public.md)) |
| `planning.solver.unimproved-seconds-limit` | `180` (`SOLVER_UNIMPROVED_SECONDS_LIMIT`; `2` in `%test`), `0` disables | The instance's plateau window |
| `planning.solver.plateau-gain-medium-per-seat` | `0.25` (`0` in `%test`) | Medium gain, per seat of the problem, that the window must bring for the solve to continue |
| `planning.solver.engine` | `hand` (`SOLVER_ENGINE`) | The engine that searches: `hand` (direct engine, [ADR 0087](repo:docs/decisions/0087-un-second-moteur-de-recherche-arbitre-par-timefold.md), default since [ADR 0088](repo:docs/decisions/0088-le-moteur-direct-devient-le-moteur-par-defaut.md)) or `timefold` |
| `planning.staffing.verification-default` | `calcul` | What the staffing check answers when the request does not choose: `calcul` (a made-up team is solved) or `borne` (the exact minimum, with no solve; to adopt once the equivalence is total, [ADR 0098](repo:docs/decisions/0098-la-borne-exacte-s-affiche-d-abord-le-calcul-reste-le-defaut.md)) |
| `planning.solver.seconds-limit-max` | `3600` (`SOLVER_SECONDS_LIMIT_MAX`) | Ceiling on what a launch requests (`?seconds=`, MCP); startup is refused if it is below `seconds-limit` |
| `planning.public-instance` | `false` (`PUBLIC_INSTANCE`) | Public instance: only it applies the quota below and refuses to start without legal notices |
| `planning.solver.max-solves-per-hour` | `0`, no limit (`SOLVER_MAX_SOLVES_PER_HOUR`) | Public instance only: quota over a sliding 60 minutes, counted at launch by [`SolverQuota`](repo:src/main/java/dev/sylvain/planning/service/solve/SolverQuota.java) ([ADR 0075](repo:docs/decisions/0075-quota-de-calcul-de-l-instance-dans-l-application.md)) |
| `planning.solver.max-queued-jobs` | `0`, no limit (`SOLVER_MAX_QUEUED_JOBS`) | Public instance only: jobs queued behind the one that is running |
| `planning.constraint-weights.<contrainte>` | `1` for a hard rule, `5` for most of the others, `25` for `stabiliteDuPlanPublie` | Catalogue weight, see [weight and disable rules](doser-et-desactiver-les-regles.md) |
| `planning.jobs.reprise-au-demarrage` | `true` (`false` in `%test`) | Settles at startup what a previous run left unfinished: the queue is no longer kept, its jobs become `INTERROMPU`; a solve that went to a remote machine is followed again |
| `planning.diagnostic.mode` | `score-director` | Implementation of the per-constraint score breakdown; `solution-manager` is the oracle of the contract test, not a fallback mode ([ADR 0013](repo:docs/decisions/0013-diagnostic-par-le-score-director.md)) |

The `-max` ceiling and the quota cannot be bypassed: a service that launches a solve consumes the quota (which does nothing outside a public instance). To run the solve on another machine, see [solving on another machine](../installer/calcul-sur-une-autre-machine.md).

## Early stopping depends on feasibility

A solve stops when the budget is exhausted, or when the schedule is already feasible and has not gained, over the plateau window, `0.25 × seats` medium points. The two conditions are tied by an `AND` between `bestScoreFeasible` and Timefold's `unimprovedScoreDifferenceThreshold` threshold (the soft level stays free). Three choices follow.

- **Feasibility conditions stopping.** Without it, the solver would give up on a hard-score plateau, that is, when it most needs the rest of its budget. The downside: with too short a budget, it returns a plan with unfilled seats without raising an error, since stopping on a plateau does not trigger either. The default of 900 s leaves about 2.7 times the duration measured to reach feasibility on a real profile of 3,500 seats.
- **The plateau is judged on a minimum medium gain.** The second phase always finds a medium point from time to time: a strict plateau never triggered on a real edition. `0` in test gives the strict plateau back. Timefold's diminishing returns read only the soft level and are not suitable either ([ADR 0081](repo:docs/decisions/0081-le-plateau-se-juge-sur-son-gain-de-medium-a-l-echelle-du-plan.md)).
- **The window starts from feasibility.** An empty construction heuristic separates the two local searches: it takes no step, but Timefold resets the plateau window to zero at its end. Without it, the window went up during the feasibility phase and a slow host stopped as soon as it reached 0 hard, without optimising ([ADR 0082](repo:docs/decisions/0082-la-fenetre-de-plateau-part-de-la-faisabilite.md)).

## Sampled construction for very large problems

The first phase evaluates each seat against each staff member. Beyond 15 million seat × staff member pairs, [`LargeProblemConstruction`](repo:src/main/java/dev/sylvain/planning/service/solve/LargeProblemConstruction.java) replaces it at solve time with the same phase (hardest seats first, eligibility filter included) that evaluates only 50 eligible staff members drawn at random per seat. The limit counts what the filter keeps, not the draws: counted on the draws, a seat whose whole draw was ruled out had no move, and Timefold then ends the entire phase.

Measured on the versioned `extreme-` scenarios, with the same seed:

| Problem | Construction | 0 hard in |
| --- | --- | --- |
| `extreme-02` (6,480 seats × 1,000 staff members) | exact | 182 s |
| `extreme-02` | sampled | 12 s |
| `extreme-09` (41,370 seats × 1,000 staff members) | exact | 50 min 48 s |
| `extreme-09` | sampled | 3 min 30 s |

Below the threshold, exact construction pays for itself: it costs a few minutes more for a better first plan that local search does not catch up. Above it, it no longer fits in a production budget. The threshold sits where exact construction would exceed ten minutes, and every real edition is far below it ([ADR 0036](repo:docs/decisions/0036-construction-echantillonnee-des-tres-gros-problemes.md)).

The `pickEarlyType` stop criterion `FIRST_FEASIBLE_SCORE_OR_NON_DETERIORATING_HARD` is unusable here: a seat can stay empty, "leaving it empty" does not degrade the score, and construction would stop on an entirely empty plan.

## `acceptedCountLimit`: two phases, one setting per objective

The number of candidate moves sampled at each local-search step (`<forager>`) is the most sensitive setting in the file, and it depends on the size of the problem. A low count reaches feasibility fast but polishes poorly; a high count polishes well, but on a large problem a step costs enough that sampling many candidates starves the search and it never reaches feasibility. Local search is therefore split into two phases:

| Phase | `acceptedCountLimit` | End | Role |
| --- | --- | --- | --- |
| 1 | `1` | `bestScoreFeasible` | reach 0 hard as early as possible |
| 2 | `40` | the rest of the budget | polish the medium and soft levels |

What to retain from the measurement:

- A low count alone reaches feasibility, but by moving the medium in the wrong place: on the real profile, the number of seats held by a staff member with no rating of the stand's game category doubled. A high count alone never reaches 0 hard. The two phases give feasibility and the quality of wide sampling.
- Timefold always keeps the best solution met: phase 2 cannot lose again the feasibility already acquired. The two selectors targeted at unfilled seats are not repeated in phase 2, there are none left.
- `lateAcceptanceSize` at 400: below it, quality degrades; above it, it changes nothing.
- The phase 1 setting was re-measured when moving up to Timefold 2.5 (with an identical setting, a step evaluates about ten times more moves) and a fixture that is too small underestimates the real problem by a third: `festival-realiste` comes out of construction at -110 hard when the production profile comes out at -218. Settle a setting on the real problem, never on the versioned scenario alone.

To redo if the size of the problem changes significantly: replay the real problem (`GET /api/planning/persisted`, `score` field removed, staff members reset to `null`) outside the dev server, one configuration at a time. Two concurrent solvers share cache and memory bandwidth, and the comparison no longer means anything. The `SolverBenchTest` bench is described with [the tests](tests.md).

## The second phase swaps days

Several medium rules count per person and per day (premium stands, game categories, locations). For a day to change hands, a one-seat move must first degrade the score, which the search rarely accepts. The second phase therefore also draws, at weight 2 against *change* and *swap*, a **day swap** ([`DaySwapMoveIteratorFactory`](repo:src/main/java/dev/sylvain/planning/solver/DaySwapMoveIteratorFactory.java)): the seats of a staff member on a date pass to a colleague who works that day, and the colleague's to them, in a single move. Each keeps the dates they work, so their number of days, their week and their run of days; the hours of the day, the total of the week and the rest around it change, and the score judges them.

The pair is chosen, not drawn then filtered: a uniform (staff member, date) block among those that exist, then a uniform colleague of the same day among those who can swap. A swap is built only if the score would not refuse it outright:

| Check | Reason |
| --- | --- |
| Each seat goes to an eligible staff member (`EligibleAnimateurMoveFilter.isEligible`) | unavailability, minors' rules specific to the seat-staff member pair |
| No received seat overlaps a frozen seat that its receiver keeps that day | the past and the locks do not change hands, but they occupy their hour |
| A minor's day, received seats included, stays under the `dureeQuotidienneMax` cap for their age | measured as the rule measures it, and only when it is active |

Two days made of the same seats are swapped all the same: refusing them costs medium (-30,852 against -30,293 on `festival-hivernal`). This is what distinguishes this move from Timefold's *pillar moves*, set aside after measurement: the `pillarSwapMoveSelector` drew two blocks at random and its filter rejected 99.97% of them, which divided the speed by nine, and the `pillarChangeMoveSelector` lost medium on seven scenarios out of nine ([ADR 0078](repo:docs/decisions/0078-echange-de-journees-plutot-que-mouvements-pillar.md)).

Gain measured on versioned scenarios (90 s of second phase from the same feasible plan, mean of seeds 0, 1 and 2):

| Scenario | Medium without swap | Medium with swap |
| --- | --- | --- |
| `scenario-complet` | -30,737 | -23,822 |
| `festival-realiste-canicule` | -21,872 | -19,200 |
| `festival-hivernal` | -33,702 | -30,282 |
| `gamme-20` | -38,163 | -33,817 |
| `gamme-23` | -38,260 | -32,587 |

The gain has the same direction at each seed on the nine scenarios tried, at the price of 14 to 32% fewer evaluations per second. The soft level falls back on several of them and remains ranked below the medium. Beyond weight 2, the gain falls within the gap between seeds while *change* and *swap* lose draws. The per-seed figures are in the comment of the second phase of [`solverConfig.xml`](repo:src/main/resources/solver/solverConfig.xml).

## Write a rule without slowing the search

Speed is dominated by the number of tuples that the *constraint streams* build and maintain at each move, not by the number of rules. A `forEachUniquePair` followed by a `filter` builds all the pairs before discarding almost all of them; a `Joiners.equal` turns it into an indexed access.

### Indexed joiners rather than `filtering`

On `scenario-complet.yaml` (2,088 seats, 150 staff members), rewriting four constraints as indexed joiners removed about 202,000 useless pair tuples, with an identical search trajectory (`randomSeed=0`, the same 1,220 steps, the same final score):

| Constraint | Pairs built before | After |
| --- | --- | --- |
| `incompatibiliteAdHoc` | 59,508 (all pairs of seats of the same timeslot) | 0 as long as no incompatibility is entered |
| `eviterRoulementStandsPremium` | 115,596 (all pairs of the same stand) | 0 with no premium stand |
| `reposQuotidienMinimal` | about 13,500 | only the night timeslots held by a minor |
| `eviterChangementEmplacementEloigne` | about 13,500 | only the timeslots actually chained |

The time to feasibility went from 22.0 s to 7.3 s. The rule that follows for any new constraint:

1. express first what can be expressed with `Joiners.equal`, `lessThan` or `overlapping`;
2. restrict the input stream with a `filter` before joining;
3. keep `Joiners.filtering` only for what cannot be indexed (the haversine distance and the scope comparison of the ad hoc constraints).

The metric to watch is the *move evaluation speed* in the solver log, not the elapsed time.

### One node per aggregation, shared between rules

Bavet, the *constraint streams* engine, builds one node per stream object and shares it between the constraints that read that same object. It does not merge two streams that look alike: two `groupBy` with the same keys are two nodes, each maintaining its list at every move. Written rule by rule, the families maintained "the staff member's seats that day" eleven times.

[`SeatStreams`](repo:src/main/java/dev/sylvain/planning/solver/constraints/SeatStreams.java) builds these streams once per provider and lends them to each family: seats held, dated seats, seats per staff member and per day (with the legal settings or the meal windows alongside), per staff member, per stand × timeslot row. Two rules of similar shape plug into the same object. The activation guard (`ConstraintToggleSupport.actif`) goes at the tail of the stream, on the breaches, never at the head: at the head it would be one more node on every seat and would make the head unshareable.

Sharing changes only the cost: the two versions follow the same trajectory step by step and end on the same plan. A family's unit tests (`ConstraintVerifier`) keep calling `define(factory)`, each family then builds its own streams, and sharing between families happens only in `PlanningConstraintProvider`. A new rule that reads seats starts from a `SeatStreams` stream; an aggregation that no stream offers yet is added there rather than in the rule.

### The eligibility filter is not a wall

`EligibleAnimateurMoveFilter` rules out the (seat, staff member) pairs that the legal rule would refuse anyway and that can be decided on the pair alone: a minor at night, a minor on a stand restricted to adults, a timeslot longer than a minor's daily or continuous ceiling. By construction it cannot rule out any feasible solution, and it spares a score calculation to discover a certain breach. It is not a wall, though: the *ruin and recreate* rebuild does not read it. That is why these same rules weigh a flat penalty of 10,000 hard points per breach ([ADR 0034](repo:docs/decisions/0034-exclusions-eligibilite-plus-lourdes-que-tout.md)).

A stricter set of legal rules can also make a day split structurally infeasible (an evening that ends at midnight pushes back the next morning, the pool for a rare game category is no longer enough). If the solve no longer reaches feasibility after a legal tightening, look at the scenario before the solver's speed.

## Environment mode and parallelism

`solverConfig.xml` sets `<environmentMode>NO_ASSERT</environmentMode>`. The *assert* modes fully recompute the score at every phase boundary to detect corruption; removing them gained about 7% of evaluation speed. Determinism depends on `<randomSeed>`. If in doubt about the score, go back to `PHASE_ASSERT`, or to `FULL_ASSERT` to identify the faulty move, then return to `NO_ASSERT`. `REPRODUCIBLE`, the name of the same trade-off in Timefold 1.x, no longer exists in 2.x and makes the construction of the *solver factory* fail.

There is no `<moveThreadCount>`: multithreaded incremental solving is a feature of Timefold Solver Enterprise Edition. With `timefold-solver-core` alone, the solver refuses to start if the parameter is present ("Enterprise Edition could not be loaded"). The available cores remain unused, which is the main performance ceiling of the deployment, and no constraint rewrite works around it.

## Freezing an acquired block

`PosteAffectation.verrouille` carries `@PlanningPin`, and a `STAND`-type lock covers all the seats of a stand. Laying such a lock on a voluminous but easy block (setup and teardown, 20% of the seats on two single-game-category stands with no skill constraint) removes these entities from move generation without removing them from the schedule: they stay assigned, by name, and their hours continue to be evaluated.

The gain is on the search space; the cost of a score calculation stays the same. The lock freezes these people and consumes their hours: it is sound only if the frozen assignment is already good, hence the order solve, check, then lock. No code to write, two API calls (see [locks and publication](plan-verrous-et-publication.md)).
