---
twin: regles/regles-du-planning
---
# Tune the schedule rules

You adjust what decides the plan: the legal thresholds, the importance of each preference, the length of the solve, and you know when a rule is blocked by missing data.

![The Planning rules page](/images/fr/regles.webp "Planning rules")

Everything is set in *Planning rules* (*Plan* group), in three settings tabs: *Legal*, *Quality* and *Solve*. These are expert settings: they only appear after a click on "Advanced settings", under the row of tabs. Nothing is written before "Save", and "Run the solve again" is one click away.

These thresholds come from labour law: [what the French Labour Code requires of a schedule](/code-du-travail/) says what the software applies by itself and what it leaves to your judgement.

## The Legal tab

One row per mandatory rule:

- its plain name and the article that grounds it;
- its state;
- the thresholds that set it (weekly durations, break, meal window...), editable on the row itself;
- the breaches of the last analysis.

The full text of the rule and the history of its settings open in a panel.

Disabling a legal rule (minors, working time, safety of minors) asks for a confirmation, which recalls that you remain the employer and the person responsible for the schedule that goes out. The screen permanently shows what is disabled. Nothing is disabled for you.

## The Quality tab

The same table for the quality rules and the preferences. Each rule has an importance in three positions: low, normal, high. The exact weight is in the panel, for whoever wants it.

"Baisser l'importance" (lower the importance) is never the first gesture offered for a quality rule: the Diagnostic always puts it last, and it disappears when the importance is already at its lowest.

## The Solve tab

- the length of the solve and the stop when nothing progresses any more (see [Solve the schedule](calculer-planning.md#régler-la-durée-du-calcul));
- the end-of-solve e-mail;
- the start of the evening, the application's only evening;
- the all-rounder game category.

## When a rule cannot improve: the floor

A rule that penalises nearly everything it evaluates is in fact measuring data missing from the reference data: no wish declared, no referent. Its points are a **floor** that no solve will move.

The Planning rules page flags it, names the data and leads to where it is entered. The score without the floor is displayed next to the raw score in the Solver summary and in the Plan versions.

## Find who changed a weight

Each change of a rule's weight or activation is kept with its before and after values, its date and its origin (screen, assistant, scenario, duplication), with nothing nominative, for as long as the edition. A rule's panel sets it next to the solves that followed and their breaches from that rule.

## Breaks, consecutive days and travel

Three rules of the solve are set directly on their row.

### The break: a gap or a relay

A legally due break can be taken in two ways, and the tool knows no third:

- a **gap** of at least the duration set in the grid;
- a **relay**: a colleague of the same stand holds a seat during the whole break.

With neither, the plan is refused, as it would be for an overrun of hours. You have nothing to declare: open the seat, the solve uses it.

In return, the break is rest, so it is **deducted everywhere**: daily and weekly ceilings, hours and fairness in the per-person schedule, Staffing need, indicators. There is a single number of hours, the same on all screens.

### Consecutive days

The rule limits consecutive days worked: **eight by default, and the number can be set** on the rule's row. The solve penalises the excess days without blocking, since no text imposes a sliding count. An organisation that wants a blocking ceiling turns it on for its edition from the same page.

The threshold beats weighting: on a grid that calls for nearly everyone every day, one day more or less decides whether a schedule with no breach exists, where no weight can.

### Travel time between two seats

Two seats of the same person on two distant locations must leave the time to walk from one to the other. The walking time is computed from the distance, a detour factor and a walking speed, **adjustable on the rule's row**, with a tolerance. The solve penalises each minute missing from the buffer.

The Rail and the timeline mark with a red chevron a sequence that is too tight ("20 min on foot, 10 min buffer"), or a trip taken out of the break. The Problems page lists them, with the link to the day's Rail. A location's fiche gives the walking time to each of the others.

## The already published schedule moves as little as possible

Once the schedule is sent, each person moved costs the solve: a re-solve after a late change moves the minimum of people already notified.

## Why each rule exists

The name, the article of law and the reason of each rule are in the application's catalogue (Planning rules screen). The rationale of each, what it costs and what was ruled out are in [The solver's rules](../comprendre/pourquoi-ces-regles.md).
