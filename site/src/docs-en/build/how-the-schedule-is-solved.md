---
twin: comprendre/comment-le-planning-est-calcule
---
# From problem to plan: the solver and its two engines

Every schedule solve follows the same path, however it is launched, and two engines can search for the plan there, one refereeing the other. You will know what a solve does from end to end, who searches for the plan and who checks it, where it can run, and why the diagnosis of a plan no longer goes through Timefold's reserved API.

The user's walkthrough is in [Solve the schedule](../regles/calculer-planning.md), the operator's settings in [the schedule solve](../installer/calcul-du-planning.md), the routes in [starting, following and stopping a solve](api-calcul.md).

For an organiser who does not read code, [how the solve works](/comment-marche-le-calcul/) gives the short version.

## A solve always does the same thing

There are four ways to launch a solve: synchronous from a problem sent in the request, asynchronous from a problem sent, asynchronous from the reference data, incremental. All go through [`SolvePipeline`](repo:src/main/java/dev/sylvain/planning/service/solve/SolvePipeline.java), and therefore always do the same eight things:

1. snapshot the plan about to be overwritten (`captureBeforeSolve`);
2. build the problem;
3. solve;
4. save the plan;
5. diagnose it;
6. feed the Planning rules screen (`ConstraintAnalysisStore`);
7. write the KPI history row;
8. announce the end, by e-mail if the edition asked for it.

The reason for this class is a defect it removed: a path that stopped after saving left the rules screen on the analysis of the previous solve, without any error, and did not write the KPI row. The only symptom was a screen that lies. `SolveSynchronePipelineTest` locks both ends.

Only two seams vary from one caller to another: how the problem is built, and who must hold the solver in order to be able to stop it. A queued job builds its problem at its actual start, not at the click: it solves the edition as it is when its turn comes.

![Sequence of a solve launched from the reference data](repo:docs/diagrammes/solve.svg)

<sub>Source: [`diagrammes/solve.puml`](repo:docs/diagrammes/solve.puml).</sub>

The second seam serves two uses: stopping the solver, and following it. `SolverScoreTrace` subscribes to the same `Solver`, at the same moment, to keep the score curve of the solve in progress; it is read by `GET /api/jobs/score` and pushed onto the jobs stream. A single consumer receives the solver, which keeps the two needs from drifting apart. The sampling of the curve is detailed in [the solve API](api-calcul.md).

### What the solve receives

`SolveRunner.prepareToSolve` completes the problem from the edition, pins the past (`FrozenPast.pin`) and announces the score of the starting plan. This last step exists because Timefold announces a best plan only when the best changes: a re-plan starting from an already feasible plan would be usable from its first second, and would say so only at its first improvement, or never.

**The past is frozen** ([ADR 0044](repo:docs/decisions/0044-le-passe-est-fige.md)). During the event, every construction from the reference data takes from the saved plan the seats of the timeslots that have already started and pins them: holder kept even if they became unavailable, empty seat left empty. These seats count in the rules (rest, weekly caps, consecutive days) without ever being blamed. "Today" is that of the event-day clock. A solve whose seats are all past fails with "Nothing to plan" without being reported as a breakdown. `PASSE_FIGE=false` turns the rule off.

A problem sent in the body of the request also has its past seats pinned, but the synchronous solve is refused as long as a family of the reference data is frozen (`@RefusedWhileFrozen`): landing rewrites the stands, timeslots and skills that the problem carries over those of the edition ([ADR 0052](repo:docs/decisions/0052-gel-du-referentiel-distinct-du-verrou.md)). It is also counted in the operator's hourly quota, like a job: it takes cores away from the queue.

### A server shutdown does not overwrite the plan

When the container stops under a solve, Timefold returns its best solution as if the budget were exhausted. This is not a completed solve. The job ends `INTERROMPU`, no KPI row or e-mail is produced, and the partial plan replaces the saved plan only if it strictly beats it (hard, then medium, then soft), or if there was no plan (`keepsPartialPlan`). Any read that fails, of the count or of the score, answers no: the plan the operator already has is worth more than the one a restart cut short.

## Two engines behind the same solver

`planning.solver.engine` (`SOLVER_ENGINE`) chooses who searches for the plan of a prepared problem, and `SolveRunner.solvePrepared` is the only place that reads it.

| Engine | Who searches | Particularities |
| --- | --- | --- |
| `hand` (default since 3.0.0) | `HandSearchSolver`: a hand-written local search, `solver/handsearch`, with a score kept up to date move by move | A single phase, even on a published plan. The final plan is scored again by Timefold |
| `timefold` | Timefold's local search on the constraint streams | On a published plan, two phases: stability is weighted at zero until feasibility (at most two thirds of the budget), then regains its weight on what remains (`FeasibilityFirstSolve`) |

Both are `Solver`s: the queue, the stop, the score curve and the worker of the remote solve do not know which one is running. The setting is the instance's and travels with the problem to the worker, whose own variable does not count. A log line says afterwards which one ran ("Solve with the … engine"). `SOLVER_ENGINE=timefold` and a restart give back the original search, with no migration: a plan saved under one is readable, re-scorable and re-seedable under the other ([ADR 0088](repo:docs/decisions/0088-le-moteur-direct-devient-le-moteur-par-defaut.md)).

### Timefold is the referee, never the direct engine

The direct engine keeps its own score, where the 42 rules are written a second time: a move changes the score only of the person leaving the seat and of the one taking it, and rereading these two people costs a few microseconds, where each move through the stream engine crosses the whole rule network. This score is never believed. Once the best plan is laid on the problem, `HandSearchSolver` has it scored again by the constraint streams (the `SolutionManager`, the licence-free path of the diagnosis). What the application saves and explains comes from this second computation. Two moments are refereed, those the application commits to: the first plan with no hard breach, before announcing "feasible", and the final plan.

**Two different scores make the solve fail.** The job is in error with both scores in its message, and the saved plan stays intact. There is never a fallback on the other engine: an engine that has drifted from the rules is a failure to be seen, and a plan computed otherwise without saying so would hide it. The engine also refuses by itself, in plain words, what it cannot score (a seat without complete hours, a seat held by someone the problem does not know, a fact carried twice, a problem of more than 15 million seat × staff member pairs, beyond the sampled construction). The package imports nothing from `ai.timefold.solver.core.impl`, injects no bean and reads no configuration: it runs as is in a worker.

What stays on Timefold: the staffing check and the "until feasibility" solves, the diagnosis, and the definitions of the rules, which the constraint streams continue to carry.

### A rule exists twice

A new or modified constraint is written in the streams, which define it, and in the direct engine: its constant in `Rule`, its term in `IncrementalScore` (or in `FlatProblem.staticTerms` when it reads only one seat and its holder). Two tests hold this second copy:

- `HandSearchCoverageStructuralTest` fails by name on a constraint that only one engine knows;
- `HandSearchDifferentialTest` compares the two scores constraint by constraint, not on the total where two errors cancel out, on empty plans, random plans, plans found then perturbed, and on the versioned scenarios completed with the branches none of them carries (locks, frozen past, published plan, caps per game category, the five types of exception). Each rule must have been compared there at least once on a non-zero score: an equality at zero proves nothing.

This is a permanent cost, accepted. See [ADR 0087](repo:docs/decisions/0087-un-second-moteur-de-recherche-arbitre-par-timefold.md) for the reasoning and the alternatives ruled out, and [adding a rule](ajouter-une-regle.md) for the procedure.

## Where the solve runs

By default on the application's machine. A full solve built from the reference data can go to a worker: a Java program with no injection container, in the same image (`SOLVER_WORKER_MODE=true`), which the application reaches at a permanent address or through a machine requested from an operator's HTTP launcher ([ADR 0086](repo:docs/decisions/0086-le-calcul-deporte-un-worker-sans-conteneur-et-un-crochet.md)).

- `SolveExchange` is the form a prepared problem takes to leave, and that of its plan on return. What the public JSON hides from a caller travels in a controlled form: a staff member leaves as an allow-list of fields, under a pseudonym and a stand-in birth date, and free text stays home.
- `RemoteSolver` is a worker behind the `Solver` interface: the queue does not need to know where the search runs. It lays the plan received on the application's problem, recomputes the score and refuses the plan at the slightest difference.
- `WorkerClient` carries all the calls, with the worker's certificate pinned.
- The worker (`SolveWorker` behind `SolveWorkerServer`) reads no solver setting: duration, plateau and engine arrive with the problem (`SolverConfiguration.SearchSettings`), and it refuses a problem of another version. Nothing in these two classes may inject a bean, read the instance's configuration or open the database.

**A remote failure fails the job, it never falls back to running locally**: machine unavailable, different version, plan refused. Only the partial re-plan, the solve of a problem sent in a request and the staffing check stay local. When the application stops during a remote solve, it lets the worker solve; at the next start it rebuilds the problem, finds the solve again by its identifier and follows it anew. The operator's side is in [solving on another machine](../installer/calcul-sur-une-autre-machine.md).

## The queue and the staffing check

`SolverJobService` carries the asynchronous solves and their queue, **in memory** (ADR 0110): a restart loses the jobs that were waiting. The history is written to `solver_job` as soon as a job holds the solver; a queued job is never written. At startup, a row left queued (a 3.x one) or that held the solver comes back `INTERROMPU`, a terminal state, with the reason on the job and in the log, so as not to hold the lock indefinitely; a solve left on a remote machine is followed again. `SolverJobTasks` and `SolverJobPersistence` carry what does not hold the lock. A public instance's hourly quota and capped queue are checked at submission (`SolverQuota`, with no effect outside a public instance), never in a resource: a new way of launching a solve calls `consume()` (and `checkQueue` if it can wait). The "one solve at a time" lock is global to the instance, never per edition; only the `409` of a launch refused because a solve is running carries a job in its body, which lets a client tell it apart from a quota refusal.

`StaffingVerificationService` checks the staffing need: by default a made-up team is solved until feasibility on its own thread, outside the queue, on the instance's engine (`SolveRunner.solveCheck`); with `calcul: false`, the exact bound answers alone ([ADR 0098](repo:docs/decisions/0098-la-borne-exacte-s-affiche-d-abord-le-calcul-reste-le-defaut.md)). It gives way to any solve that starts (`SolveStarting`) and keeps each check in `verification_besoin`.

## The diagnosis does not go through `analyze()`

Breaking a score down rule by rule feeds a lot of consumers: the rules and problems pages, the heatmap, the explanation of an assignment, the swap simulations, the repair assistant and their MCP equivalents. All of this went through `SolutionManager.analyze()`, reserved to Timefold's Enterprise edition from 2.x. The lock went beyond the screens: the end of every solve called it, so without a licence a successful solve was reported as a failure.

`service/diagnostic/` therefore offers a single seam, `ConstraintDiagnosticService`, which returns a `PlanningAnalysis`, a type of the project. Two implementations satisfy it.

| Implementation | Path | Licence |
| --- | --- | --- |
| `ScoreDirectorConstraintDiagnosticService` | the solver's score director | none |
| `SolutionManagerConstraintDiagnosticService` | `SolutionManager.analyze()` | Enterprise in 2.x |

They are not a full mode and a degraded mode. Only the facade is locked (`analyze()`, `diff()`, `recommendAssignment()`); the computation of the matches, justifications included, is ordinary Community code. The two implementations return the same analysis (score, constraints, matches, facts), which `ConstraintDiagnosticServiceContractTest` checks by running them side by side. There is nothing to detect at startup and nothing to report to the user ([ADR 0013](repo:docs/decisions/0013-diagnostic-par-le-score-director.md)).

The choice is made through `planning.diagnostic.mode` (`score-director` by default), and `ConstraintDiagnosticService.of` is the only place that knows there are two implementations. The one that goes through `analyze()` is the contract test's oracle, not a fallback. This is what makes it tenable to rely on `ai.timefold.solver.core.impl`, outside semver: a version bump that changes the behaviour makes the comparison fail and names the difference, instead of silently distorting five screens.

**At every Timefold bump, run this test and read its result rather than the build's.** On a Community 2.x edition the oracle is locked, and the test then declares itself skipped instead of failing: a green build then proves nothing about the diagnosis. A licence restores the comparison with no configuration.

### The other dependencies on internals

Four move filters (`EligibleAnimateurMoveFilter`, `HoleNeighbourPosteFilter`, `UnassignedPosteFilter`, `DoubleBookedPosteFilter`) and two move factories (`WeekRelocationMoveIteratorFactory`, `DaySwapMoveIteratorFactory`) also depend on `core.impl`, because the filter and factory interfaces exist only there. The moves they produce are those of the public *preview* API, and `MoveFactorySupport` depends only on those. Their net is of a different nature from the oracle: a version bump that changes the types no longer compiles, and one that stops querying them without saying so shows up in the scenario tests, as a solve that no longer converges.

`TimefoldInternalApiStructuralTest` keeps the inventory, seven files with the diagnostic service: a new file that imports `core.impl` fails the build until it has named its net. The heavy scenarios that serve as a net are run according to [the tests](tests.md).
