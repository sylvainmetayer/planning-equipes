---
twin: regles/comprendre-un-planning
---
# Understand a stuck schedule

A schedule leaves seats empty or a rule gives way: you know why, where, who is concerned, and which action fixes the problem first.

![The diagnostic of a schedule](/images/fr/diagnostic.webp "The Diagnostic: what blocks, what is missing, what is fragile")

Everything is read in *Diagnostic* (*Plan* group) and, for the summary, at the top of the *Calculate* tab of the Planning page.

Why a solve can find nothing, in plain terms: [when the solve finds no solution](/comment-marche-le-calcul/).

## Read the assessment in a few sentences

The score is told without technical vocabulary:

- the mandatory rules met or not;
- the seats left empty, and the day with the most;
- the two or three rules that weigh on the organisation, with their share;
- what comes from missing data (see the floor in [Tune the planning rules](regles-du-planning.md#quand-une-règle-ne-peut-pas-saméliorer--le-plancher));
- the manual adjustments involved;
- right after a solve, what changed compared with the previous plan.

Each rule and each day cited leads to its row; no person is named. The same text appears at the top of the *Calculate* tab and of the Diagnostic, and for each plan compared in the Plan versions.

## Find what blocks: the Problems tab

The first tab answers "what blocks, what is missing, what is fragile". It gathers in one view the blockers, sorted by severity: the causes of infeasibility detected without a solve and the rules still breached after the last analysis.

Each card says:

- **where**: the three stands or days most affected. Each opens the Planning page on that stand and timeslot, with the seat panel open;
- **who**: the people most concerned, each opening their profile.

When a rule ran into exceptions entered by hand, they are named one by one, along with the legal breaks nobody can relieve. Below the cards, "Where the breaches concentrate" crosses the breaches with days, stands or people, filtered on the chosen rule.

## Apply the action that fixes the problem: "What to do?"

Under each problem, the application lists the real actions that fix it, in the order the rule catalogue gives them, each with its reason.

- "Qui peut tenir ce siège ?" (who can hold this seat?) opens on the spot, and "Place" fills the seat without leaving the Diagnostic.
- The other actions open the screen already positioned: the Skills grid on the stand's game category, the stand's profile, the rule's row to set its ceiling, the Stand hours, the load per person, the locks, the adjustments involved shown alone, the missing data of a floor.
- "Baisser l'importance" (lower the importance) always comes last, and disappears when the importance is already at its lowest.
- A day already under way offers nothing that would change it.

Other links connect a symptom to the screen that handles it: an empty seat, a break without relay or a stand on the map open the seat panel without leaving the Planning page; a game category bottleneck or a rare skill lead to the staff who hold it; a warning on save reopens the profile; a cause of infeasibility opens the timeslot or stand concerned.

## Spot single points of failure: the Fragility section

The Diagnostic's *Risks* tab gathers three readings, from top to bottom: fragility, "To train" (see [Know how many people to recruit](dimensionner-l-equipe.md)) and tension. The *Fragility* section says who is a single point of failure. For each person, it lists the timeslots that would fall below the minimum headcount if they dropped out, and above all those nobody else could take over. It also lists the stands held by a single skilled person.

Per person, three actions:

- "Lock" keeps their schedule as it is at the next solve;
- "Who can replace" opens the panel of the seat that they would leave the hardest to hold;
- "Train" opens the skills grid on the game categories of their stands.

Their name leads to their profile.

## See the tension hour by hour: the Tension section

The *Tension* section, at the foot of the *Risks* tab, crosses the day with the time band, on the saved schedule. In each cell: the people actually free minus the seats left empty, crossed with the plan's fragility.

- One column per start hour of the grid: an evening that ends at 22:00 on weekdays and at midnight on Saturday stays a single column.
- The colour follows the sign of the margin: red below zero, green above.
- The frame says the severity: calm, watched, high or critical, by named rules (empty seats nobody can hold, a seat no one else could take over, a stand without a specialist, fragile seats beyond the margin).
- A cell opens the seat panel of its band on the Planning page. The worst band of each day gives its reasons, with the profile of the irreplaceable person and the stand's fragility. A band already started is greyed out.

The margin *before* the solve is a column of the Need tab: see [Know how many people to recruit](dimensionner-l-equipe.md).

## Know what changed since the last solve

The Solver counts the changes made since the saved solve. "Fix after a change" shows them before offering what to reopen, by family and with the last lines of the history. See [Solve the schedule](calculer-planning.md#choisir-comment-lancer).

## When a solve degrades the plan

The previous score is shown next to the new one. If the new plan is worse without any mandatory rule being broken, the screen says so and offers to go back to the previous plan. Versions and their comparison are described in [Solve the schedule](calculer-planning.md#garder-des-versions-du-plan).

## Find who did what

The **action history** keeps what was done in the edition and by whom: every write, every export, every send, whether it comes from a screen, an assistant or a night task, with the fields a change really altered. See [Manage accounts and rights](comptes-et-droits.md#lire-lhistorique-des-actions).
