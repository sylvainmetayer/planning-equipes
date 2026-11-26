---
twin: regles/regles-du-planning
---
# Tune the schedule rules

You adjust what decides the plan: the thresholds, the level of each organisation rule, and you know when a rule is blocked by missing data.

![The Planning rules page](/images/fr/regles.webp "Planning rules")

Everything is set in *Planning rules* (*Plan* group), in the *Rules* tab, in three blocks. Nothing is written before "Save", and "Run the solve again" is one click away.

These rules come from labour law: [what the French Labour Code requires of a schedule](/code-du-travail/) says what the software applies by itself and what it leaves to your judgement.

## Legal obligations

One row per rule the law imposes, read only:

- its plain name and the article that grounds it;
- the thresholds it reads, as stored (weekly durations, break, meal window...);
- the breaches of the last analysis;
- a "Set in Settings" link, leading to *Settings › Legal*.

Thresholds are edited there and nowhere else: one place to write them, no double entry. The full text of the rule and the history of its settings open in a panel, on a click on its name.

## Organisation policy

The rules your organisation gives itself: load balance, continuity on a stand, the meal break, the adult beside a minor. Each has a **level**, which you choose for the edition:

- **Mandatory**: a plan that breaks it cannot be delivered, as for a legal rule. A mandatory rule is counted, not weighed: the screen then offers no weight;
- **High**, **Normal** or **Light**: a preference, which the solve weighs accordingly. The exact weight (1 to 500) is in the rule's panel, for whoever wants it; "Normal" is the shipped level.

Making a rule mandatory that the edition cannot hold gives an impossible plan: the diagnosis of hard failures names it. Disabling a protected rule (the meal break, the adult beside a minor) asks for a confirmation, which recalls that you remain the employer and the person responsible for the schedule that goes out. The screen permanently shows what is disabled. Nothing is disabled for you.

Two rules are listed under one shared title, *Spread over the day* (far location changes and the number of locations visited in a day), each keeping its own line, level and switch. It is only a grouping on screen: no rule is merged, and "Trajets trop courts entre deux postes" (journeys too short between two shifts) stays apart under its own title. Load balance and the balance of hard timeslots are not grouped: one is an organisation rule, the other a preference of the Advanced block, and a title never spans two blocks.

"Baisser l'importance" (lower the importance) is never the first gesture offered for an organisation rule: the Diagnostic always puts it last, and it disappears when the weight is already at its lowest.

## Advanced

The rest, folded by default: assignment, adjustments, locks, comfort preferences. Open it with "Advanced settings". A rule there is dosed in three positions (low, normal, high) and can be switched off. A link to a rule of this block opens it by itself.

## The thresholds, in Settings

*Settings* groups the edition's settings into four sections, each a tab: *Notifications* (the automatic reminders and follow-ups), *Legal*, *Organisation* and *Solve*. The last three are expert settings, behind "Advanced settings".

- *Legal* and *Organisation* list, on a single form, every threshold the rules read: weekly ceilings, break, meal break, consecutive days, travel, late services. They are the only places where these are written; each row of *Planning rules* points to them. Nothing is written before "Save".
- *Solve* shows the length of the solve and the stop when nothing progresses any more, without being editable: they are set for the whole instance (see [Solve the schedule](calculer-planning.md#régler-la-durée-du-calcul)). It also holds the end-of-solve e-mail, the start of the evening, the application's only evening, and the all-rounder game category.

The former *Legal* and *Quality* tabs of *Planning rules* lead to the *Rules* tab, and the former *Solve* tab (`/regles?onglet=calcul`) to *Settings › Solve*.

## When a rule cannot improve: the floor

A rule that penalises nearly everything it evaluates is in fact measuring data missing from the reference data: no wish declared, no referent. Its points are a **floor** that no solve will move.

The Planning rules page flags it, names the data and leads to where it is entered. The score without the floor is displayed next to the raw score in the Solver summary and in the Plan versions.

## Find who changed a weight

Each change of a rule's weight or activation is kept with its before and after values, its date and its origin (screen, assistant, scenario, duplication), with nothing nominative, for as long as the edition. A rule's panel sets it next to the solves that followed and their breaches from that rule.

## Breaks, consecutive days and travel

Three rules of the solve have their thresholds in *Settings › Organisation*.

### The break: a gap or a relay

A legally due break can be taken in two ways, and the tool knows no third:

- a **gap** of at least the duration set in the grid;
- a **relay**: a colleague of the same stand holds a seat during the whole break.

With neither, the plan is refused, as it would be for an overrun of hours. You have nothing to declare: open the seat, the solve uses it.

In return, the break is rest, so it is **deducted everywhere**: daily and weekly ceilings, hours and fairness in the per-person schedule, Staffing need, indicators. There is a single number of hours, the same on all screens.

### Consecutive days

The rule limits consecutive days worked: **eight by default, and the number can be set** in *Settings › Organisation*. The solve penalises the excess days without blocking, since no text imposes a sliding count. An organisation that wants a blocking ceiling turns it on for its edition from the same page.

The threshold beats weighting: on a grid that calls for nearly everyone every day, one day more or less decides whether a schedule with no breach exists, where no weight can.

### Travel time between two seats

Two seats of the same person on two distant locations must leave the time to walk from one to the other. The walking time is computed from the distance, a detour factor and a walking speed, **adjustable in *Settings › Organisation***, with a tolerance. The solve penalises each minute missing from the buffer.

The Rail and the timeline mark with a red chevron a sequence that is too tight ("20 min on foot, 10 min buffer"), or a trip taken out of the break. The Problems page lists them, with the link to the day's Rail. A location's fiche gives the walking time to each of the others.

## The already published schedule moves as little as possible

Once the schedule is sent, each person moved costs the solve: a re-solve after a late change moves the minimum of people already notified.

## Why each rule exists

The name, the article of law and the reason of each rule are in the application's catalogue (Planning rules screen). The rationale of each, what it costs and what was ruled out are in [The solver's rules](../comprendre/pourquoi-ces-regles.md).
