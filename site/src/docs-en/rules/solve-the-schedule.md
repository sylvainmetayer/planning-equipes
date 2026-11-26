---
twin: regles/calculer-planning
---
# Solve the schedule

You start the automatic solve, follow it live, decide when to stop it, and know which button to pick depending on the situation.

![The solved schedule](/images/fr/planning.webp "The schedule of a day, solved")

The solve assigns staff to the seats to fill under three levels of requirement, in this order:

1. the legal framework and incompatibilities, which are never crossed;
2. seat coverage;
3. fairness, wishes and comfort.

It is started from *Solver* (*Plan* group).

To know what this solve guarantees before starting it, read [how the schedule solve works](/comment-marche-le-calcul/), written for an organiser.

## Before starting: what the solve will receive

The screen says what the solve will take into account:

- the locks, manual adjustments, consignes and their dates;
- the disabled rules;
- the changes made since the last solve;
- the availability declarations still pending, which the solve **will not see** until they are applied.

Each counter leads to the screen that lists it. Feasibility and alerts come first; the size of the problem is folded at the bottom. A gap counter shows the changes made since the saved solve. Only actions that change what the solve receives are counted: a send or an export makes nothing stale.

If an exception set by hand cannot be held by any seat (a minor forced onto a night, a public holiday or an adults-only stand, someone whose schedule is locked), "Calculate the schedule" asks for confirmation before spending solve time on a problem that will end up breaking a mandatory rule. Lack of people asks for nothing: the solve still mitigates it.

## Choose how to start

Three buttons, each with its sentence on the page. The page announces where the next solve will start from and where the last one started from.

| Button | What it does |
| --- | --- |
| "Calculate the schedule" | Starts from the saved plan if there is one and tries to improve it. Nothing is frozen except the locks |
| "Fix after a change" | Freezes what holds and recomputes only the reopened seats: a few dozen seconds instead of several minutes. It first shows what changed: the count by family ("3 staff members, 1 stand, 2 timeslots") and the last lines of the history, with a link to it |
| "Start over from scratch" | Drops what was gained, and says so beforehand. Its confirmation lists the day reviews that will survive |

After a late change, "Fix after a change" moves as few already-notified people as possible: once the schedule has been sent, each person moved costs the solve, and the summary says how many people the publication would notify.

## Follow the solve

- The live score curve. The three levels of the score (mandatory rules, seats to fill, quality) are plotted during the solve, each on its own scale. You see when the solve plateaus and can stop it at the right moment. Only the solve under way is plotted.
- Feasible plan since... As soon as the solve holds a feasible schedule (all seats filled, all mandatory rules met), the page says so and counts how long ago. From then on the solve only improves quality. "Stop and keep this plan" saves that schedule without waiting for the end of the budget.
- The queue. You can schedule a solve behind the one that is running: it starts by itself, so you can prepare the next edition without waiting in front of the screen. The queue is **in memory**: a server restart loses the planned solves as it does the one that was running, flagged as interrupted; launch them again.
- The end e-mail. Enable it per edition (Settings, *Solve* tab) to be notified as soon as a solve ends, with the edition, the score and the feasibility.

![Following a solve in progress](/images/fr/docs/solveur.webp "The Solver screen during a solve")

## Set the solve duration

The maximum duration of a solve and the stop for lack of progress are **the instance's**, the same for every edition: the operator sets them on the server, and *Settings*, *Solve* tab, shows them without changing them. Stopping for lack of progress only applies once the schedule is feasible, never while seats remain to fill.

## Read the result

After a solve, the result is read in sentences. Viewing the schedule, reviewing the first day not yet reviewed and publishing are one click away.

The previous score is shown next to the new one. Re-running a solve on a good schedule can degrade it without any mandatory rule being broken; the screen then says so and offers to go back to the previous plan.

To understand why a seat stays empty or a rule gives way, see [Understand a stuck schedule](comprendre-un-planning.md). To touch up the result, see [Review and adjust the schedule](relire-et-retoucher.md).

## Keep versions of the plan

*Solver*, *Plan versions* tab, gathers in a single table the finished solves and the versions of the edition, newest first, with the result of each in sentences (nothing nominative).

- A **version** sets a schedule aside and puts it back later. It has an origin, written on its row: *manual* ("Save this state"), *before a solve* (taken automatically before each solve) or *published* (the plan the staff received). Putting back an outdated one asks for a confirmation that names the cause.
- Two ticked versions, or one version and the plan in place, open in *Compare two versions*: score, coverage, fairness and rule breaches, across all editions on request. The address keeps the pair, to share it.
- Each solve keeps the weighting of the rules under which it was started, and the comparator warns when two plans were solved under different weights.
- The solve measurements survive the deletion of the edition.

## Going further

- What the solve must respect: [Tune the schedule rules](regles-du-planning.md) and [Set exceptions and locks](ajustements-et-verrous.md).
- The rules on breaks, consecutive days and travel: [Tune the planning rules](regles-du-planning.md#pauses-jours-daffilée-et-trajets).
