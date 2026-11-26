---
twin: comprendre/doser-et-desactiver-les-regles
---
# Weight and disable rules

You will know how a rule is switched off or weighted, what the confirmation dialog guarantees and what it does not, how the screen says what a rule is set to and which action to offer when it is in breach, and how to read a floor, the kind of rule that penalises everything for lack of data.

## The screen says what the rule is set to

A description answers *why*, never *how much*. "No more consecutive days than the cap set on the Settings page" left the reader hunting for the right tab and betting that the field they found was the one the sentence meant. So each rule carries, **on its own line**, the settings it reads: the field label word for word, and the field itself at the edition's value. A setting read by several rules (the break, the meal window) appears on each of their lines and can be changed on any of them: it is a single value. The server sends the setting key (`cle`) along with its label, so the screen does not have to recognise a label to know which field it is editing. Nothing is written before "Save".

A rule lists **all** the settings it reads, not only its headline threshold. `dureeHebdomadaireMax` shows why: its cap is weekly, but the week is measured in effective work, which depends on the length of the break. Three things do not appear there, none of them by oversight:

- the **weight**, which has its own column (the importance) and arbitrates between rules of the same level rather than saying what a rule measures;
- a **Labour Code constant** (the daily 10 h, the 11 h of rest, the six days before weekly rest): no form changes it, the description is where it is named;
- a **piece of reference data** (a game category's cap, a stand's premium flag, a staff member's appraisals): these are rows, not settings, and the screen points to them through the floor when their absence flattens a rule.

The inventory is [`ConstraintParameters`](repo:src/main/java/dev/sylvain/planning/solver/ConstraintParameters.java), next to `ConstraintCatalog` and for the same reason: a hand-written list silently rots as soon as a rule reads one more parameter. `ConstraintParametersStructuralTest` reads the rules' sources, resolves what each one reads through its helper methods and fails on any undeclared read. Two forms escape it and are named with their reason: a parameter that actually arrives from the problem (the meal windows, built by `ReferenceDataService`) and an accessor with an argument read outside the scanned folder (`dureePauseMinutes(mineur)` and `PauseSurPoste.dues`, which the five duration rules all declare).

## When a rule is in breach: the action, not the weight

"554 matches" is a measurement, not a next step. The Problems screen used to offer the same button on twelve rules in breach, lower the weight, while the explanations named the real action without a link: raise a staff member's level, remove the premium flag, raise the cap. A screen that only offers to care less does not help to fix anything.

**The catalogue says the action, the screen renders it** ([ADR 0064](repo:docs/decisions/0064-le-geste-d-une-regle-en-defaut-vient-du-catalogue.md)). For each medium and soft rule (and for hard rules whose lever is specific), `ConstraintCatalog` carries the ordered list of its **levers** (`Lever`, a type, never a sentence), and `BlockerPlaybook` translates each lever into a navigation positioned on the first breach. The rule-by-rule list is in the catalogue and in `GET /api/constraints` (`actions` field): it is checked by a test there, it would rot here.

| Lever | Button | What it opens |
|---|---|---|
| `SEAT` | "Qui peut tenir ce siège ?" (who can hold this seat?) | the seat's bench, in Diagnostic, and its "Place" |
| `SKILL` | "Saisir la compétence" (enter the competence) | the skills grid filtered on the stand's game category |
| `STAND_PROFILE` | "Ouvrir la fiche du stand" (open the stand's profile) | headcount, premium or exhausting flag, referent |
| `STAFFING` | "Baisser l'effectif demandé" (lower the required headcount) | the stand's hours, on that day |
| `CAP` | "Régler le plafond" (set the cap) | the rule's line on Planning rules |
| `THRESHOLD` | "Régler les seuils" (set the thresholds) | the same line, for a rule that measures against thresholds |
| `REPAIR` | "Proposer une réparation" (suggest a repair) | the Planning page, on the day and stand of the breach |
| `GAME_CATEGORY_CAP` | "Régler le plafond de la typologie" (set the game category's cap) | the Categories screen |
| `ANIMATEUR_PROFILES` | "Revoir les fiches" (review the profiles) | the person's profile: unavailability, wishes |
| `WORKLOAD` | "Voir la charge par personne" (see the workload per person) | the Planning page, by person |
| `LOCK` | "Verrouiller ce qui doit tenir" (lock what must hold) | the locks |
| `ADJUSTMENTS` | "Review the adjustment" | the manual adjustments involved |
| `MEAL_WINDOW`, `SHIFT` | "Régler la fenêtre repas" (set the meal window), "Shorten the shift" | the line of the meal break, the timeslots |
| `RULE` | "Voir la règle" (view the rule) | the rule's line |

Four invariants hold the whole together, each tested by `BlockerPlaybookTest`:

- no medium or soft rule has the weight as its only action: each names at least one lever that changes the plan or the reference data, and that lever comes first;
- "Baisser l'importance" (lower the importance) always comes last, added by the playbook to every weighted rule, never to a hard rule, and no longer at all when the weight is already at its lowest: a button that cannot lower anything lies;
- only that button talks about the weight: the first action repeats the rule's advice word for word, except when that advice is to lower the importance;
- "Régler le plafond" (set the cap) or "Régler les seuils" (set the thresholds) exists only on a rule that reads a setting (`ConstraintParameters`): with no field to fill in, the line has nothing to set.

The "where" comes from the same analysis. The pivot says on which days, stands and people the breaches concentrate, but not which timeslot of which stand, the only thing you open to fix them. `BreachHotspots` therefore crosses stand and timeslot for the three places that gather the most breaches of each rule (`hotspots`, identifiers only), and each one opens the Planning page on that seat. The frozen past is excluded: a seat that has already started can no longer be corrected. An aggregate rule that names no place (load balancing) falls back on the most affected stands, then days. When the breach falls on a seat held by a single person, "Qui peut tenir ce siège ?" (who can hold this seat?) becomes "Qui peut remplacer ?" (who can replace?) and opens on that person's seat.

## Enable and disable

Disabling applies to the next solve: it stops the stream from producing matches, and touches neither the weight nor the logic.

A `constraint_toggle` row carries a rule's **explicit state** (`nom`, `actif`); its absence means what the catalogue says. Almost all rules are active by default, so the table then carries only the deactivations. Those the catalogue ships **switched off** are in `ConstraintCatalog.DESACTIVEES_PAR_DEFAUT`: `mineurNecessiteEncadrementMajeur` ([ADR 0041](repo:docs/decisions/0041-encadrement-des-mineurs-eteint-par-defaut.md)) and `maxJoursConsecutifsTravaillesDur`. For those, it is switching on that writes a row. Asking for the state the catalogue already gives **deletes the row**: the table carries only decisions somebody made, like a weighting put back to its default.

A scenario says both directions: `contraintes.desactivees` for what it switches off, `contraintes.activees` for what it switches on when the catalogue ships it off. A rule it does not mention goes back to the catalogue default.

The state is injected as a planning fact and consulted by `ConstraintToggleSupport.actif(...)` **at the tail of the stream, on the breaches**, just before the penalty. A rule's breaches number in the dozens where its seats number in the thousands: a guard placed at the head of the stream would make the head impossible to share between rules (`SeatStreams`). A rule driven by `ContrainteAdHoc` keeps its guard on the few ad hoc facts. This is where, and not only in the service, the catalogue default is read: a Java harness that builds its own problem gets exactly what an edition nobody touched gets.

> **A rule whose stream is not wrapped in `ConstraintToggleSupport.actif` shows a switch with no effect.** `ConstraintToggleStructurelleTest` fails on any catalogue rule in that case, and `ConstraintToggleTest` checks the mechanism on one representative rule per family: the same dataset must be penalised without the toggle and be exactly zero with it.

### Who is present on a stand × timeslot line

Three rules read who holds a stand at a given time: a referent present (`standComplexeAvecReferent`), no more minors than adults (`repartitionMineursParCreneau`), a beginner next to the referent (`favoriserMixiteDesNiveaux`). All three read the same line (`SeatStreams`): the seats **still held** over the rest of the timeslot. The original seat of a seat split during the day ([ADR 0066](repo:docs/decisions/0066-le-passe-est-fige-a-la-minute.md)) stops where its continuation begins: someone who left at 9:20 is neither referent, nor adult, nor beginner for the afternoon. Without a split (any plan before the event day), nothing changes.

### Disabling a legal rule

The solver can then produce a schedule that **breaches the Labour Code while showing a hard score of zero**, with nothing in the score to flag it. Four categories are protected (`CATEGORIES_PROTEGEES`, `protegee` field of the API): "Légal (mineurs)" (legal, minors), "Légal (temps de travail)" (legal, working time), "Sécurité (mineurs)" (safety, minors) and "Organisation (repas)" (organisation, meals). The others are unticked without friction: they arbitrate comfort.

**Only two are grounded in law** (`CATEGORIES_LEGALES`, `legale` field): the two "Légal (…)" (legal). "Sécurité (mineurs)" (safety, minors) and "Organisation (repas)" (organisation, meals) are rules the organiser gave itself: lifting them commits the organiser just as much, but the schedule does not become illegal, and the dialog must not claim it does. A warning that says too much is a warning people learn to skip: the dialog chooses its wording on `legale`, the server decides, never a test on the category label on the client side.

The screen asks for a confirmation that names the rule, recalls what grounds it and repeats the wording of the terms of use: **the organiser remains the employer and the party responsible** for the schedule produced. Re-enabling asks nothing. **The API, for its part, asks nothing**: an MCP client or a `curl` disables a rule without going through the dialog, the guard is ergonomic, not contractual.

**Confirmation only, a decision taken on one's own account**: no reason, no journal, no author column. Without a notion of user, a trace could not be attributed. What remains is the state: the Planning rules screen permanently shows what is disabled, and the shared banner repeats it everywhere a solve is started or judged. A real audit trail first requires deciding who has the right to lift a legal rule.

## Weight a rule

Each rule penalises with a literal weight in its code; that default can be overridden by `ConstraintWeightOverrides` without touching the Java. Three layers, the closest one winning: the deployment (`planning.constraint-weights.*`, **1 for a hard rule, 5 for a medium or soft rule**, 25 for the stability of the published plan), the edition (`ponderation_contrainte`), and the scenario when the file pins its weighting. A rule's weight ranges from 1 to 500; `0` is refused. It is set through `PUT /api/constraints/{name}/poids` or the `modifier_poids_contrainte` tool ([rules API](api-regles-et-diagnostic.md)).

### Importance in three positions

The screen does not ask for a number: the Quality tab offers each medium or soft rule **three positions, low, normal, high**, which write a weight of 1, 5 and 25 (`core/importance.ts`, `ConstraintCatalog.POIDS_FAIBLE/NORMAL/FORT`). The shipped default is the middle position, so that "lower the importance" always means something: a default of 1 was already the minimum. A factor of five between two neighbouring positions is enough for a high rule to come before a normal one, without crushing the others of its level ([ADR 0057](repo:docs/decisions/0057-importance-d-une-regle-en-trois-positions.md)).

A weight that is not one of the three values is still allowed, the rule's panel carries the numeric field, and it is displayed as "custom". A weighting stored by a past solve and its score say under which weights that plan was solved: two plans solved under different weightings cannot be compared at equal weights, and the Plan versions comparison says so.

### The level: mandatory or preference

An organisation rule has a **level chosen per edition** ([ADR 0111](repo:docs/decisions/0111-le-niveau-d-une-regle-est-le-niveau-de-son-poids.md), which applies [0106](repo:docs/decisions/0106-obligatoire-est-un-niveau-reglable-par-edition.md)). **Mandatory** (`OBLIGATOIRE`): the rule counts at the hard level of the score, on a par with the legal rules; a plan that breaks it is infeasible and the diagnostic names it. **Preference**: the rule counts at the medium level, at the weight of its position (light 1, normal 5, strong 25). It is the **same rule** in both cases, written once: only the place in the score its breach goes to changes, and the numeric weight stays adjustable in the advanced settings.

The level can be chosen for the medium rules of the organisation quality category, the meal break and the adult beside a minor (`niveauReglable` in `GET /api/constraints`). **Legal, assignment, ad hoc and lock rules stay mandatory, and soft preferences stay preferences**: a level asked for them is refused. A mandatory rule counts one hard point per breach; the weight stored for it is kept, not applied, for the day it becomes a preference again. Making mandatory a rule an edition cannot hold gives an infeasible plan: the feasibility analysis says so before the computation. The dosage stored by each solve keeps the moved levels, and a plan computed under other levels is not comparable at equal weights.

### What is weighted, what is not

The rules that really vary from one organiser to another are the **medium rules of "Qualité d'organisation"** (organisation quality) (`dosable()`): they arbitrate comfort against comfort, continuity on a premium stand against load balancing, wishes against experience. The rule you do not care about goes to low importance and gets beaten, the one that matters goes to high.

A hard rule is respected or the schedule is invalid: its weight only changes the speed of convergence, and **reweighting a legal rule makes it neither more nor less mandatory**. Hard rules therefore have only their switch; offering them in three positions would suggest otherwise. A weight never changes a rule's level: this is why consecutive days exist in two forms ([Rest and consecutive days](repos-et-jours-d-affilee.md)).

### Keeping a trace of a weighting

A weighting is tuned by trial and error, and the trial and error is only worth it if you can reread what it produced. Every effective change of weight or activation is recorded per edition, values included (`GET /api/constraints/historique`, `GET /api/constraints/{name}/historique`, `consulter_historique_ponderation` tool), and each solve stores the weighting under which it was **started**. A rule's panel lays out the changes and the scores that followed, and the Plan versions comparison flags two plans that different weights make incomparable. It is a juxtaposition, not a measurement of effect.

### The fairness rules

`equilibrerCharge` puts `loadBalance().unfairness()`, a `BigDecimal` typically below 1, on a ×10 scale before truncating to `int`. Without that, any moderate imbalance truncated to 0 and the solver had no gradient to follow. This factor is itself a weighting choice: for "more or less importance", go through `planning.constraint-weights.equilibrerCharge`; touch `UNFAIRNESS_SCALE` only for "more or less granularity". `equilibrerCreneauxPenibles` applies the same technique, on the subset of exhausting or premium seats only.

## The floor: a rule that penalises everything for lack of data

On a real edition measured before the appraisals grid existed, most of the medium score was a constant: two rules matched everything they evaluate because the measured data did not exist in the reference data (no wish declared, no staff member at referent level). The rules were correct; but an organiser reading a large medium score cannot know that a large part of it will never move. The flaw recurs on any edition where some data has not been entered, which is what any edition is on its first day.

Each analysis therefore reads every non-hard rule as a **ratio**: `breaches ÷ elements evaluated`, where the denominator is the rule's grain (filled seats for `souhaitsIncompatibles`, stand × timeslot groups for `standComplexeAvecReferent`, consecutive pairs for `eviterChangementEmplacementEloigne`, published seats for `stabiliteDuPlanPublie`). The table is [`ConstraintFloorRules`](repo:src/main/java/dev/sylvain/planning/solver/ConstraintFloorRules.java), exhaustive by test: a medium or soft rule added without a line makes `ConstraintFloorRulesTest` fail.

Eleven rules have no per-element reading and are never a floor: those that have only an aggregate match (`equilibrerCharge`, `equilibrerCreneauxPenibles`), a reward (`affiniteAdHoc`), and the eight that penalise through a **weight function** (`repartitionMineursParCreneau`, `eviterRoulementStandsPremium`, `limiterEmplacementsParJour`, `limiterTypologiesDistinctesParAnimateur`, `maxJoursConsecutifsTravailles`, `eviterFermeturePuisOuverture`, `coupureRepasPlacementPrefere`, `preserverBufferPolyvalents`). For those, the number of matches is an amount of breach and not a boolean per element: a ratio of 1.0 means "everyone is in breach by an amount the solver can reduce", the opposite of a floor.

**From 95%** (`FLOOR_THRESHOLD`, not 100%: a handful of seats always escape), the rule is flagged: a mark in the Breaches column of Planning rules, and in its panel the ratio and the data named, with a link to the entry screen when the reference data holds none (wishes, appraisals, referents). A floor that nothing of the kind explains is flagged anyway, without a link: a premium stand that nobody can legally hold alone is just as constant. That one asks for a sample: under ten elements evaluated (`FLOOR_MIN_SAMPLE`), it is not reported, because one match out of one makes 100% of nothing. A floor that the reference data explains is flagged whatever the size: "no wish declared" is as true on three seats as on three thousand.

The following are **not** floors: the cases where the missing data makes the rule *inert* (zero premium stands, zero locations, zero minors, zero exhausting stands). The rule matches nothing, costs nothing, and "satisfied" is accurate.

The **score outside the floor**, the raw score minus, level by level, what the flagged rules cost, is the part a solve can move. It is shown next to the raw score on the Solver summary and in the Plan versions comparison (`kpi` of a snapshot; absent, never zero, on a snapshot taken before the measurement). **Nothing is disabled**: a rule at the floor stays active, and it is up to the organiser to enter the data, lower the weight or switch the rule off knowingly ([ADR 0031](repo:docs/decisions/0031-signaler-le-plancher-sans-le-decider.md)).

What these rules measure can be read on the Planning page, by person (`GET /api/planning/equite`), which says column by column whether a solver rule weighs it and whether it is active: evening, weekend or public-holiday hours are measured by no rule there, and the screen says so. Weekend and public-holiday hours are attributed in full to the timeslot's date, as the ISO week is by `Creneau.semaineIso()`; only evening hours are prorated, because the evening is an hour of the day and not a day.
