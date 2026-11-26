---
twin: installer/calcul-du-planning
---
# Tune the schedule solve

You decide how far an edition may push a solve, you protect a shared instance against back-to-back solves, you size the memory and you choose the search engine. The solver is **one for the whole instance**: one solve at a time, all editions together, queue included.

## Cap an edition's solve time

What the solve does with that time is explained in [how long it takes](/comment-marche-le-calcul/).

Each edition sets its own budget on the *Solver* screen (maximum duration, stop without improvement), but you decide how far it can go.

| Variable | Default | Role |
| --- | --- | --- |
| `SOLVER_SECONDS_LIMIT_MAX` | `3600` | Maximum duration an edition may set, or a launch may request (`?seconds=`, MCP), in seconds |
| `SOLVER_UNIMPROVED_SECONDS_LIMIT_MAX` | that of `SOLVER_SECONDS_LIMIT_MAX` | Ceiling of the stop without improvement, in seconds |

Above the cap, the value is **refused**, with a message that names the cap. It is not silently trimmed: an organiser who sets three hours would get two while believing the budget was used up. Lowering a cap below a value an edition has already saved breaks nothing: its solves run at the cap, and the solver monitor says so.

**Startup is refused** if a cap is below the corresponding default (`planning.solver.seconds-limit`, 900 s; `planning.solver.unimproved-seconds-limit`, 180 s): every edition left on the default would otherwise have its own solves refused.

## Limit the number of solves on a shared instance

The caps above limit **one** solve. An instance that shares its machine with another, typically a public demo with a shared password next to a production, must also limit how many can be chained. Two guards, off by default, apply to the whole instance, all editions and all callers together:

| Variable | Default | Role |
| --- | --- | --- |
| `SOLVER_MAX_SOLVES_PER_HOUR` | `0` (no limit) | Solves accepted over the last 60 rolling minutes |
| `SOLVER_MAX_QUEUED_JOBS` | `0` (no limit) | Jobs that may wait behind the one running ("Plan the calculation"), the running one not included |

The quota counts every solve **accepted at launch**, whatever the path: full or incremental solve, from the *Solver* screen, the API (including the synchronous `POST /api/solve`) or an MCP assistant, and the staffing check by a solve (*Diagnostic › Staffing need*, the default), which is a real solve; the bound alone does not consume any. A launch refused for another reason does not count; a job that was planned and then removed from the queue does. Beyond the limit, the launch is refused (`409`) with a sentence that the screen displays as is and that the MCP tool returns as an error:

> "Cette instance limite le calcul à 12 résolutions par heure. Prochaine résolution possible à 14:32." (This instance limits solving to 12 solves per hour. Next solve possible at 14:32.)

The time is given in the `NOTIFICATIONS_TIMEZONE` time zone, that of the event. A full queue is refused the same way ("Cette instance limite la file d'attente du solveur à 2 résolutions planifiées…", this instance limits the solver queue to 2 planned solves).

The counter lives **in memory**: a restart resets it to zero, which is enough against the abuse in view. The queue replayed at startup is neither refused nor counted a second time. For a demo, an order of magnitude: a quota of a dozen per hour and a queue of two, with `SOLVER_SECONDS_LIMIT_MAX` lowered to what a visitor is patient enough to wait for. The choice to limit here rather than at the reverse proxy is [ADR 0075](repo:docs/decisions/0075-quota-de-calcul-de-l-instance-dans-l-application.md).

## The application's memory

The application container needs **2 GB** (`mem_limit: 2g` in `docker-compose.prod.yml`), PostgreSQL 512 MB: that is the memory the host must be able to give them. Solving a schedule is the only big consumer, its local search allocating relentlessly, and it decides the size.

The image sets up the JVM accordingly, through `JDK_JAVA_OPTIONS`:

| Option | JVM default | Why |
| --- | --- | --- |
| `-XX:MaxRAMPercentage=75` | 25% of the container limit | With 25%, the heap tops out at 512 MB under 2 GB (256 MB under 1 GB) and the solver spends its time collecting. Three quarters leave the rest (metaspace, compiled code, stacks, buffers, about 200 MB measured during a solve) outside the heap |
| `-XX:+UseParallelGC` | G1, or Serial in a container with fewer than two cores or about 1.8 GB | The collector built for throughput, where G1 aims for short pauses: a solve that allocates a lot gains from it, for pauses of the order of a tenth of a second, with no effect on the screens |

On a small machine (a Raspberry Pi 4, three cores allocated, with an image built locally for arm64: the registry's one targets only `linux/amd64`), these two options together make the local search evaluate **more than twice as many moves per second** as the defaults under 1 GB (a 256 MB heap and Serial). On a workstation, the difference is barely visible. The same data follows the same trajectory, only faster: in the same solve time, the solver therefore goes further, and the plan obtained may differ, for the better, from the previous one.

Three consequences for you:

- 75% assumes a memory limit on the container: that is where the JVM takes the percentage from. Started without a limit (a bare `docker run`, a manifest without `resources.limits`), it takes three quarters of the host's memory at the expense of PostgreSQL and the system, and the parallel collector rarely gives back what it has taken. Always set a limit, or redefine `JDK_JAVA_OPTIONS` with a lower percentage.
- Do not keep a 1 GB limit with this image: 75% of a gigabyte leaves too little outside the heap, and the container risks being killed in the middle of a solve. A host that truly cannot give 2 GB redefines `JDK_JAVA_OPTIONS` with a lower percentage, and accepts a slower solve.
- To change these options, redefine `JDK_JAVA_OPTIONS` in `environment:`. The value replaces the image's one entirely: carry over what you want to keep. `JAVA_TOOL_OPTIONS` is not suitable, because the JVM reads it before the command line, where the launcher puts `JDK_JAVA_OPTIONS`: the image's value then wins on the heap size, and another collector chosen there (`-XX:+UseG1GC`, which some monitoring agents inject) **prevents the JVM from starting** ("Multiple garbage collectors selected"), and the container then restarts in a loop.

## Choose the search engine

| Variable | Default | Role |
| --- | --- | --- |
| `SOLVER_ENGINE` | `hand` | `hand`: solves look for their plan with the direct engine; `timefold`: with Timefold's search, as before 3.0.0 ([decision 0088](repo:docs/decisions/0088-le-moteur-direct-devient-le-moteur-par-defaut.md)) |

This is an **instance** setting. A compute machine follows that of the application that sends it the problem, not its own variable. The staffing check by a solve follows the instance's engine; the solves "until feasibility" stay on Timefold.

The direct engine ([decision 0087](repo:docs/decisions/0087-un-second-moteur-de-recherche-arbitre-par-timefold.md)) reaches a plan with no hard breach three to four times sooner on the `festival-hivernal` scenario, and in one minute gets under the score that Timefold's search reaches in five (measurement and command in the decision). It is not taken on trust: every plan it returns is scored again by the application's constraints before being saved.

- A solve that starts from the plan in place prefers to stay there: at equal score, it keeps the holder it found instead of swapping staff members that the rules do not distinguish. This is a preference of the search, not a seat-by-seat guarantee. A solve started from scratch is not concerned, and a published plan stays protected by its own rule, which weighs in the score.
- A discrepancy between the two scores makes the solve fail, with a message that gives both; the saved plan is not touched and nothing switches to the other engine by itself. It is a defect to report; meanwhile, `SOLVER_ENGINE=timefold` and a restart hand control back to the original search.
- The engine refuses by itself, with a message on the job, what it cannot score. The remedy is the same: `SOLVER_ENGINE=timefold`.

  | Refusal | When |
  | --- | --- |
  | "the staff member … is rated on … game categories offered by a stand, for 64 at most" | A record carries more than 64 ratings on game categories that a stand offers; the edition itself may hold as many game categories as it likes |
  | "the seat … has no stand or no complete schedule" | A timeslot without an end time |
  | "the seat … is held by a staff member absent from the problem" | A starting plan that names someone the edition no longer knows |
  | "… carries two states", "… carries two ceilings", "not exactly one set of parameters" | A duplicated fact, which no table allows |
  | "… seat × staff member pairs" | More than 15 million pairs (15,000 seats for 1,000 staff members, for example): the engine's table no longer fits in a small machine. A current edition has fewer than a million; `extreme-09-120j-400stands-1000animateurs-cumul` (41,370 seats, 1,000 staff members) has 41 million. Under the `hand` default, the job **fails**, with no fallback to Timefold: an instance of that size sets `SOLVER_ENGINE=timefold` before launching a solve |
  | "the score … is outside what the engine can order" | More than 500 million soft points, or a medium of the same order: excessive weights |

- "Le moteur direct s'est contredit dans son propre score" (the direct engine contradicted itself in its own score) and "a échoué sur une erreur inattendue" (failed on an unexpected error) are not refusals but engine defects: report them, with the application log.
- The log says which engine searched: every solve starts with "Solve with the HAND engine" (or `TIMEFOLD`), and a direct-engine plan accepted by the referee leaves "The direct engine's plan is refereed by Timefold at the same score", followed by the score.

On a small machine, the direct engine asks for no more than Timefold's search. Measurement on `festival-hivernal` (3,438 seats, 153 staff members), two shared cores, the JVM bounded like the production container (1.5 GB heap, `ParallelGC`), 300 s, seed 0, one solve at a time:

| Engine | Zero hard breaches | Score at 300 s (medium / soft) | Resident memory at peak |
| --- | --- | --- | --- |
| `timefold` | 95 s | −30,775 / −89,360 | 548 MB |
| `hand` | 10 s | −23,820 / −92,970 | 434 MB |

The command is that of the [solver bench](../comprendre/tests-de-scenarios.md#le-banc-du-solveur), with `-DargLine` to bound the JVM.

## Solve on another machine

The solver uses only one core, and a faster machine saves time: see [Solve on another machine](calcul-sur-une-autre-machine.md).
