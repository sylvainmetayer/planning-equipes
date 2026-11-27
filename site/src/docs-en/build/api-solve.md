---
twin: comprendre/api-calcul
---

# Starting, following and stopping a solve through the API

You will learn how to start a solve (full, queued or incremental), predict what it will receive, follow its progress live, read what it leaves behind (previous plan, snapshots, A/B comparison) and avoid its traps: budget, server restart, starting point. The internal mechanics of the solver are in [how the schedule is computed](comment-le-planning-est-calcule.md).

## Four ways to start, one path

The synchronous solve, the asynchronous one from a submitted problem, the asynchronous one from the reference data and the incremental one all go through [`SolvePipeline`](repo:src/main/java/dev/sylvain/planning/service/solve/SolvePipeline.java) and therefore do the same thing: capture the plan about to be overwritten, build the problem, solve, persist, diagnose, feed the Planning rules screen, write the KPI row, announce the end if the edition asked for it. A path that stopped before the end would leave that screen on the analysis of the previous solve without raising any error. `SolveSynchronePipelineTest` holds both ends.

**The past is frozen** ([ADR 0044](repo:docs/decisions/0044-le-passe-est-fige.md)). During the event, each problem built from the reference data takes from the saved plan the seats of the timeslots that have already started (a past date, or today with a start already reached) and pins them: the holder is kept even if they became unavailable, an empty seat stays empty. "Today" is the one on the event-day clock ([event day and staff](api-jour-j-et-animateurs.md)). These seats count in the rules without ever being blamed, so the score reads on what is left to play. A problem sent in the request body also has its past seats pinned, without a warm start: the server does not know the caller's starting point. A solve whose seats are all past is refused rather than producing an empty plan: the job ends `FAILED` with "Rien à planifier : tous les créneaux sont déjà commencés…" (nothing to plan: all the timeslots have already started) in `error`, with no outage alert. `PASSE_FIGE=false` turns the rule off.

### One solve at a time

A single solve job runs for the whole application, with a lock held by the server. Other sessions see the active job through `GET /api/jobs/active` and receive `409` on a second start, the body carrying the job in progress. This is the only start `409` whose body is a job: a public instance's hourly quota and capped queue (`SOLVER_MAX_SOLVES_PER_HOUR`, `SOLVER_MAX_QUEUED_JOBS`, see [tuning the solve](../installer/calcul-du-planning.md)) and the frozen reference data answer `409` with a plain `{ message }`. A client tells them apart by the shape of the body (an `id` and a `status`), not by the status.

## The budget

The budget is the **instance's** (ADR 0110), the same for every edition: `planning.solver.seconds-limit` and `planning.solver.unimproved-seconds-limit`, which `GET /api/parametres/calcul` exposes under `instance` with the ceiling of a launch (`maxSecondsLimit`, `SOLVER_SECONDS_LIMIT_MAX`). No write changes it: `PUT /api/parametres/calcul` only sets the edition's end-of-solve mail. Screen, script and MCP therefore get the same budget. Three traps:

- **a ceiling refuses, it does not truncate.** A duration above the ceiling is refused with a `400` that cites the ceiling, on a `?seconds=` as on an MCP `secondes`;
- **the plateau stays conditional on feasibility**: the stop is "feasible AND N seconds without improvement", it never cuts a solve that still has seats to fill. An explicit `?seconds=` different from the instance's duration means "exactly this duration": no plateau;
- **a job keeps the budget of its start.** It is resolved at submission. Incremental replanning has its own duration (60 s without `?seconds=`) and no plateau, under the same ceiling.

## Predicting what the solve will receive

`GET /api/planning/volumetrie` (MCP tool `consulter_besoin`, section `volumetrie`) describes the problem the next solve would build, never a stands × timeslots product: `animateurCount` (the possible values of a seat), `posteCount` (one seat per seat to fill), `contrainteAdHocCount`, `hoursToFill` (effective durations of the seats, closures deducted) and `hoursAvailable`, the legal ceiling of what the staff can work on the event days (per staff member and per ISO week, at most six non-unavailable days, each at the daily ceiling for their age that day, all bounded by the weekly ceiling). Their ratio is a fill rate, but it is a ceiling and not a forecast: skills, rest and breaks still subtract from it, and a rate close to 1 already announces an infeasible schedule.

Seats depend only on the stands and timeslots: they are counted before any staff member is entered (`hoursAvailable` is then zero), and everything is zero with no stand or timeslot. `GET /api/staffing` does the same (`referentielsManquants` names what is missing). `GET /api/feasibility` and `GET /api/fragilite`, by contrast, say explicitly that nothing is feasible without staff, rather than detecting no problem, and `GET /api/feasibility` also reports an edition with no timeslot or whose stands never open: nothing to fill is not a green light.

### The staffing need

`GET /api/staffing` (MCP tool `consulter_besoin`, section `borne`) returns **proven minimums**: no smaller team can hold the seats. Three figures read differently from what they seem:

- `mineursMax` is a ceiling, computed for `effectifReference` (the minimum, or the staff entered if there are more): beyond it, recruiting minors makes it impossible to hold. It never says how many minors to recruit, and it ignores days in a row and the minors' 4 h 30 of continuous work;
- `absentsMax` (per day) and `budgetIndisponibilites` (per week) are read against the same headcount. The budget is understood beyond the rest day that everyone must already take, and `indisponibilitesAuDela` counts the days declared beyond that rest;
- `joursConsecutifsMax` is read only if `maxJoursConsecutifsTravailles` is mandatory, `null` otherwise: a preference can be broken, it must not drive recruitment.

`POST /api/staffing/verification` by default launches a seat solve with a **made-up team** (`majeurs`, sixteen-year-old `mineurs`, all available and skilled everywhere) and answers `202`. With `calcul: false`, it instead answers **with the exact bound**: it compares the requested headcount (without `majeurs`, the minimum minus the minors) with the exact minimum and with the adults that the most demanding day requires, and returns `202` with a check already `TERMINEE` (`methode: BORNE`, `minimum`: the figure compared), with no solve, no quota, no wait. This answer says "at least N", never "a plan exists". A request that says nothing gets the instance's default, `planning.staffing.verification-default` (`calcul`, or `borne` when the bound will equal the solve over the whole range: [ADR 0098](repo:docs/decisions/0098-la-borne-exacte-s-affiche-d-abord-le-calcul-reste-le-defaut.md)). What follows holds only for the solve. `GET /api/staffing/verification` returns the last check, or `204` if there has never been one. Without `dureeSecondes`, the duration is that of one of the edition's solves (minimum 10 s, operator ceiling): any shorter, the check would fail where the solve succeeds. The solve runs on the instance's engine and stops as soon as a plan is feasible ([ADR 0071](repo:docs/decisions/0071-le-besoin-en-animateurs-un-minimum-exact-verifie-par-une-equipe-fictive.md)). Worth knowing:

- neither the plan, nor the staff, nor the queue are written; the check is kept (`verification_besoin`) and the history traces its start and end;
- one check at a time, all editions together, and none during a solve: a second request receives `409`, and a solve that starts (`SolveStarting`) interrupts the check, which is then read as `ECHEC` with the reason, as after a server stop. It travels in the state stream (`verification` field);
- **a failure proves nothing**: `realisable: false` only says that no complete plan was found in the time allowed. `posteDoitEtrePourvu` is always held, even if the edition switched it off;
- ad hoc exceptions and the published plan are left aside, because they name people that the made-up team does not contain. The plan in place serves as the starting point: each real staff member is switched to a made-up member, adult to adult, minor to minor, the most loaded first.

`GET /api/solve/entrees` counts in one read what the Calculate tab announces under "This solve will take into account", without computing anything. `pendingDeclarations` counts the availability declarations **pending**: a solve reads the profiles, never the declarations, so this figure says what it will not see. `changesSinceSolve` counts the actions journalled since the persisted plan, with the filter of `GET /api/historique/changements` (0 with no plan). `disabledRules` counts only the rules shipped active that the edition switched off.

## The queue

![Life cycle of a solve job](repo:docs/diagrammes/job-etats.svg)

<sub>Source: [`diagrammes/job-etats.puml`](repo:docs/diagrammes/job-etats.puml).</sub>

`enFile=true` queues the task instead of refusing it, in FIFO order. The statuses are `QUEUED`, `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED` and `INTERROMPU`. The problem is built at the **actual start**, not at the click, and a queued task does not hold the solver: entry stays open on the targeted edition. A duplicate (same edition, same type, already planned) is refused with `409`, but planning a solve of the edition currently being solved is still allowed: it is the way to say "do it again with what I just fixed".

What is persisted (`solver_job`) is the **intent** (type, budget, edition, scope), never the solver state, which is enough since a queued task builds its problem at start. A restart loses the solve in progress, whose job goes to `INTERROMPU`, a terminal state so as not to keep the global lock. A job's result is not stored: a restored job exposes `result: null`, what it described already being in the database. `planning.jobs.reprise-au-demarrage=false` turns the resumption off.

**A server stop does not overwrite the plan.** When the container stops under a solve (graceful stop, live reload), Timefold returns its best solution as if the budget were exhausted. It is not a completed solve: the job ends `INTERROMPU`, never `COMPLETED`, and the partial plan replaces the saved plan only if it **strictly beats** it (hard, then medium, then soft) or if there was none. `result.interruption: { partialPlanKept, partialScore, persistedScore }` tells a script; when the plan is discarded, neither a KPI row nor an end-of-solve e-mail is produced. The server gives the solver a few seconds to write this verdict; a solve that does not respect them is found `RUNNING` at the next start and goes through the previous case.

## The starting point

`POST /api/solve/async/reference-data?reamorcage=AUTO|PLAN_COURANT|AUCUN` chooses where a full solve starts from. `AUTO`, the default, starts from the saved plan if there is one and from scratch otherwise. `PLAN_COURANT` requires it and fails the job with no plan. `AUCUN` is the cold start. An unknown value answers `400`. The choice is persisted with the job (`solver_job.reamorcage`).

Starting from the plan does not pin it: each seat receives the staff member the plan gave it and stays movable, only explicit locks are frozen. This is what separates this warm start from incremental replanning, which freezes everything that remains valid. The solver scores the starting solution first and keeps the best one it has seen: on equal data, the result never falls below the starting plan. A seat whose holder has disappeared or declared themselves unavailable starts empty, because seeding a breach to undo is a worse start than a hole. The default is not the cold start, even for a script or an assistant, because the cold start can end below the plan already in the database; you ask for it. An infeasible starting plan buys no shortcut: termination on feasibility does not trigger.

The result gives back what was done:

- `reamorcage: { mode, postes, postesLiberes, postesPasses, postesPassesVides }`, where `mode` is `PLAN_COURANT` or `AUCUN`, never `AUTO`. It is absent when the problem came in the request body. `postesPasses` counts the seats already started, pinned whatever the mode, and `postesPassesVides` those that hold nobody: a warning, never a hard point;
- `previousPlan`, see below;
- `impactPublication: { personnes, publieLe }`: how many people would see their schedule change compared with the last published plan, counted as the publication counts it, `null` as long as nothing has been published. It is the count to read before publishing; the `stabiliteDuPlanPublie` rule keeps it low ([the rules](pourquoi-ces-regles.md)).

## Following a solve live

`GET /api/jobs/stream` pushes the `{ active, file }` aggregate as server-sent events. The first event arrives at connection, without waiting for a transition (an idle solver would produce none), and each event is a complete snapshot. A `heartbeat` every 20 s (`JOBS_STREAM_HEARTBEAT`) also carries the `:keep-alive` line, without which a proxy closes a silent connection; the comment alone would not be enough, `EventSource` not exposing it to JavaScript. The stream doubles the polling instead of replacing it: SSE fails silently (a buffering proxy, a cut that is not reconnected) and the interface would stay frozen on an outdated state. The browser keeps a slow poll and takes over after a silence of about two heartbeats.

### The score curve

The same stream carries the `score` event: the points (elapsed time, hard, medium, soft) that Timefold announces through `bestSolutionChanged`. `GET /api/jobs/score` returns the same curve in one block. What is plotted is the solve in progress, or the last one until the next; nothing is persisted.

Timefold announces a best solution only when the score strictly improves: a solve that plateaus stops producing points, which is exactly the state the screen must show. A curve normalised on its points alone would always touch the right edge. The **duration therefore travels separately** (`dureeMs`, by the clock): it is the right edge, the browser extends the last value flat to it and that segment is the plateau. `dureeMs` is frozen at the end of the run. A solve in progress therefore emits an event at every tick, even with no new point (`points: []`); a finished curve goes quiet.

Two sampling bounds, server side:

| Bound | Effect |
| --- | --- |
| at most one point per second | the most recent of the window is kept, never the first |
| at most 400 points | beyond that, the series is halved and the interval doubles |

A 15-minute solve and a 4-hour one therefore cost the same memory. Announcements carrying an uninitialised solution are ignored: their score describes a partial assignment, with almost no breach, and plotting them would draw a cliff at the moment the plan becomes complete. The rate is set by `JOBS_STREAM_SCORE` (1 s).

The `score` events are **deltas**: `depuis` is the index of the first point sent (`0` replaces everything, a new connection, a new solve or a halved series; otherwise you append at that index), and `generation` changes as soon as the series stops being an extension of itself. Three clarifications:

- `EventSource` sets no header, so the stream has no edition: each event carries the solve's `editionId` and the browser shows only the curve of its own edition. `GET /api/jobs/score` filters server side and answers `204` for another edition's curve;
- a `score` event **with no `jobId`** means "there is no curve any more", sent once per connection. It targets the restart: without it, a client whose last event said `termine: false` would leave a graph on screen as if it were alive;
- the curve closes at the end of the job (success, failure, cancellation): the last score is added, `dureeMs` is frozen, `termine` becomes `true`. A job cancelled before its solver started does not touch the curve, otherwise an empty trace would erase that of the previous run.

### The feasibility moment

The first feasible plan (zero hard) often arrives well before the end, and it is what the organiser is waiting for: the schedule is then usable, the rest is quality. The job carries it in `feasibleAtSeconds` (`JobView`, `consulter_calcul` in MCP), `null` as long as the plan is not feasible, and pushes a state event at that moment. "Stop and keep this plan" is only the ordinary stop, the solver always keeping the best plan reached. Timefold announcing a solution only when it changes, a solve that already starts from a zero-hard plan would say nothing: the complete starting plan is therefore scored once before the start (`SolveListener`).

## Incremental replanning

`POST /api/solve/incremental/async` starts from the persisted plan, pins what a late change has not invalidated and recomputes only the rest. The body is optional and **widens** the scope: `animateurIds`, `jours` and `standIds` form a union. With no body, the scope is what the changes invalidated plus the empty seats. The job fails if there is no plan to resume. Seats already started are settled before the scope: pinned, never reopened (`jours` naming a past day reopens nothing), counted in `statistiques.postesPasses` and `postesPassesVides`, and a replanning whose seats are all past is refused like the full solve. The mechanism is in [locks, freeze and replanning](plan-verrous-et-publication.md).

## Versions and A/B comparison

Only one plan is persisted per edition; **versions** are the only persistence able to keep several ([ADR 0108](repo:docs/decisions/0108-un-seul-type-version-a-trois-origines.md)). A version has three origins, given by `origine`: `MANUELLE` (asked for by a person), `AVANT_CALCUL` (taken automatically) and `PUBLIEE` (what the staff members were sent). The routes are `/api/planning/versions`; the 3.x roots `/api/planning/snapshots` are deprecated aliases of them, removed in 5.0, and `automatique` is still served (true for `AVANT_CALCUL`) for as long. A version is **taken automatically before each solve**: it is the anti-overwrite safety net for whoever did not think to save. The automatic ones are purged beyond the last N (`planning.snapshots.automatiques-conservees`, 5), those created by hand never.

A solve's result names this snapshot and its score: `previousPlan : { snapshotId, score, degraded }`. `degraded` compares the two scores in Timefold's order (hard, medium, soft) and is `false` as soon as one of them is missing or unreadable, because it is better to claim nothing than to raise a false alarm. `previousPlan` is `null` at an edition's first solve. A snapshot's score is copied at capture from the last analysis, which lives in memory: after a restart, the first read recomputes it from the saved plan, and the next solve restores this score before replacing the plan if it is still missing.

### Freshness and restoring

A snapshot says whether it is still up to date (`perime`, `referenceModifieLe`). The reference data carries the date of its last mutation (`edition.reference_modifie_le`, written by [`ReferenceDataChangeTracker`](repo:src/main/java/dev/sylvain/planning/service/ReferenceDataChangeTracker.java)); a snapshot captured before it is stale. This date is persisted: after a restart, an in-memory value would read as "never modified", hence "up to date", and it is in front of the *Restore* button that a false "up to date" costs the most ([ADR 0038](repo:docs/decisions/0038-fraicheur-du-referentiel-persistee.md)).

`POST /api/planning/versions/{id}/restore` answers `409` **without writing anything** in three cases:

- a **reference has disappeared**: the body lists which. This refusal is checked first, a snapshot being most often stale because ids have disappeared;
- the snapshot is **stale**: the body carries `perime: true` and the date. The action is replayed with `?forcer=true`, since the plan remains restorable, it just no longer describes today's reference data. `forcer` lifts only this refusal;
- a solve holds **this edition's** solver: it would overwrite the plan put back in place. The body is that of the reference data writes, with the job named. The guard is in the service, which the API and the MCP tool `restaurer_version` share; a solve of another edition refuses nothing.

A restoration puts the plan back with the weighting under which it had been computed.

### Publication and deletion

A snapshot can carry the "published" state (`publieLe`): it is the plan the staff received, and it is exempt from the purge. Two `409` refusals on deletion:

- **the last publication**: the espace reads it, and deleting it would silently take back what was announced. Replaced publications are deleted like any snapshot;
- **a publication that measures an elapsed day**: Realised vs planned compares each finished day with the published plan in force at its first timeslot, and deleting that publication would rewrite the gap after the fact. The refusal names the day's date, never a person, and applies to the MCP tool `supprimer_version`. It is evaluated before the deletion, because the answer depends on the clock and the timeslots.

The comparison reference of a deferred person (`animateur.plan_notifie_id`) can designate one of these publications. Deleting it makes them read as "never notified": their next publication will announce their whole schedule rather than the differences.

### Comparing two plans

`GET /api/planning/versions/compare` triggers **no solve**: it reads KPIs measured at capture. `GET /api/planning/versions/comparables` crosses editions, hence `editionsDifferentes`, which is not an anomaly (a variant is another edition) but which the interface must state. In `diffViolations`, `null` means **not measured**, never zero, and `kpiRecalcule` marks the degraded mode of a snapshot with no stored KPIs (coverage and volumetry exact, breaches not measured).

**Two plans computed under different weightings cannot be compared at equal weights**: `dosagesDifferents` says so when both `kpi.dosage` are known and differ. A snapshot also carries the consignes (official orders, such as an arrêté, relayed to the whole event) in force at its capture (`consignes`, by date): `null` on an earlier snapshot means **unknown**, an empty array "there were none". `consignesDifferentes` stays `false` as soon as one side does not know, and signals when a difference in seats or hours comes from a band that an arrêté closed rather than from a solver setting ([consignes](api-planning-et-publication.md#consignes)).
