---
twin: comprendre/api-regles-et-diagnostic
---
# Reading the rules and diagnosing a plan through the API

You will be able to read the rule catalogue with its weights, breaches and floors, tune a rule, refresh the analysis without running the solver, explain an assignment, move or place someone by hand and use the repair assistant, with the refusals and preconditions of each write.

## The analysis catalogue

`GET /api/constraints` serves the catalogue of [`ConstraintCatalog`](repo:src/main/java/dev/sylvain/planning/solver/ConstraintCatalog.java) enriched with the result of the last analysis. Each entry carries:

| Field | Meaning |
| --- | --- |
| `poids` | the deployment's value, overridden by what the edition has saved |
| `protegee` | a public-order or minor-safety rule: the UI asks for confirmation before it is disabled |
| `dosable` | the MEDIUM rules of "Qualité d'organisation" (organisation quality), the only ones whose relative importance varies from one organiser to another |
| `remediation` | the lever the organiser really has on the rule (recruit, open a stand later, lower a weight). The answer to a legal rule is never "change a setting"; never empty |

The view also carries `contraintesAdHocEnCause`: the ad hoc exceptions entered by hand that the last analysis still finds in breach, the most breached first, with their id, their reason and their number of breaches. It answers "which of my exceptions" when an ad hoc rule shows twelve matches. The list is empty when the plan honours all of them, and when nothing has been analysed.

## Tuning a rule

Two routes, one body each: `PUT /api/constraints/{name}` switches on or off, `PUT /api/constraints/{name}/poids` sets the weight.

- The weight is an integer from **1 to 500** (`ConstraintCatalog.MAX_WEIGHT`). `0` is refused: a rule weighted zero would in fact be off while still showing as active, and for a legal rule without going through the confirmation. Switching off goes through the switch.
- The screen offers only three positions (low 1, normal 5, high 25) and the exact weight in the rule's panel; the API accepts any integer in the range ([ADR 0057](repo:docs/decisions/0057-importance-d-une-regle-en-trois-positions.md)).
- `poids: null` removes the override and gives the rule back the deployment's value.
- A deactivation inserts a row in `constraint_toggle`, a reactivation deletes it; the table holds nothing else.

What each setting changes for the plan is explained in [weight and disable rules](doser-et-desactiver-les-regles.md).

## History of weighting changes: the action journal

There is no weighting history any more. `GET /api/constraints/{name}/historique` and `GET /api/constraints/historique` are removed; changes of a rule's activation, weight and level are read in [the action journal](api-integrations.md#journal-des-actions), `GET /api/historique?nature=regles`.

- Each row says **which rule** (`entiteId`), **which action** (`CONTRAINTE_ACTIVEE`, `CONTRAINTE_DESACTIVEE`, `CONTRAINTE_PONDEREE`, `CONTRAINTE_NIVEAU`), **when** and **by whom**, and never the value: the journal keeps only names and expires.
- The weighting under which a solve ran is still stored with the KPI row (`kpi.dosage`) and with the plan (`planning_resolution.dosage`, copied by each snapshot): it is taken just before the solver starts, and a weight changed during the solve applies to the next one. It keeps only what departs from a default: `weights` (deviation from the deployment), `disabled` and `enabled` (deviation from the catalogue), plus `instanceWeights`, the deployment's weights. `null` on an old row means "weighting unknown", never "default".
- The old rows are not migrated: migration `V158` renames the table to `ponderation_contrainte_historique_avant_4_0`, which 5.0 deletes; the application neither reads nor exports it.

Each row of `GET /api/mesures-edition/indicateurs` carries `kpi.couvertureParJour`: by ISO date, `{ postes, pourvus }`, seats and never people, which stays compatible with the unlimited retention of this history. The sums of the days equal `postesTotal` and `postesPourvus`. The key is `null` on an old row and on a snapshot recomputed in degraded mode: the reader says "not measured", never "empty day". The `kpi` column is a `jsonb`, and `lister_mesures_edition` (MCP) returns it as is.

## The floor: flagged per rule, never enforced

Each non-hard entry carries `postesEvalues`, the number of elements the rule evaluated at the last analysis, **at the grain of the rule** (filled seats, stand-by-timeslot groups, consecutive pairs), despite the name.

When the rule matched at least 95% of those elements (and at least ten evaluated), the entry carries a `plancher` object:

| Field | Meaning |
| --- | --- |
| `ratio` | 0.95 and above |
| `motif` | code of the missing data, `null` when none explains it |
| `libelle` | the sentence to display, in French |
| `lien` | Angular route of the entry screen, `null` when there is no data to enter |

The object is `null` for a hard rule, for a rule with no per-element reading and as long as nothing has been analysed.

The view also carries `scoreHorsPlancher`, `plancherMedium` and `plancherSoft`: the raw score minus, level by level, what the flagged rules cost, and the two constant parts, **signed like the score** (`-5000` for five thousand points that nothing will make up). On a `PlanningKpi`, `scoreMediumHorsPlancher` and `plancherMedium` follow the rule of `violationsParContrainte`: `null` when the measure did not exist, never zero. A floor changes neither `actif` nor `poids`: it is flagged, not decided. The reasoning is in [why these rules](pourquoi-ces-regles.md).

## Where the breaches concentrate

`pivotEcarts` says **where** the breaches fall, where the rest of the view says how many there are: one cell per constraint and per axis key (`JOUR` as an ISO date, `STAND`, `ANIMATEUR`), with the number of breaches the rule counts there. Only cells carrying at least one breach exist; the table of zeros is drawn by the screen. The keys are **identifiers**, never names.

Two points to know:

- All the rules appear there, not only the hard ones. `violations` stops at the hard rules because *listing* thousands of rows is costly; *counting* them is not. A medium rule's cell therefore opens on a count and a sentence, not on a list.
- A breach that names three stands counts for all three. The cell answers "how many breaches touch this stand", and discarding the ambiguous cases would answer nothing. This is the deliberate opposite of `references`, which opens a record and must not open the wrong one.

## Refreshing the analysis without the solver

`POST /api/constraints/diagnostic` recomputes the score, rule by rule, of the **persisted plan** and returns the same view as `GET /api/constraints`. It is a single score computation, with no solver held: it therefore stays available while a solve is running. With nothing persisted, the response is the empty view (`analysedAt` null).

The analysis lives in memory and the plan in the database. A restart is therefore not "no analysis": the first read that finds nothing in memory re-derives the analysis from the persisted plan and keeps it (one score computation per edition and per restart; with no persisted seat, a `COUNT` is enough). A computation that fails returns the empty view and leaves a trace in the logs: a diagnostic screen does not turn into an error page because the analysis could not be redone. No analysis goes through a plan that is solved and then thrown away ([ADR 0013](repo:docs/decisions/0013-diagnostic-par-le-score-director.md)).

## Contradictory ad hoc exceptions

`POST /api/contraintes-ad-hoc` answers **400**, with a message naming the two exceptions, when the submitted constraint cannot be satisfied at the same time as an exception already saved. The scenario import applies the same check to the whole set. What is refused, what passes deliberately and why no numeric cap exists: [ad hoc exceptions](exceptions-ad-hoc.md).

Saving a constraint again **under its own id** is never refused: the submitted version replaces the previous one. This is the only way to modify an exception, since the API exposes only `POST` and `DELETE`. Everything is replaced except the **author**: `creeParUtilisateurId` is set by the server at creation (the account of the request, `admin` or `mcp`, or the server gesture `jour-j` or `collecte`: a role, never a person) and no write changes it. The value a client sends is ignored. A scenario import creates its adjustments in the name of the importing account; an edition duplication and an SQL dump copy the author as is.

## Explaining an assignment

`GET /api/postes/{posteId}/explication` targets a single seat of the saved plan, **already solved and never solved again**. The plan is prepared on the server side as a solve prepares it (disabled constraints, the edition's weights and quotas); the MCP tool `expliquer_affectation` reads the same thing. A plan sent by the screen would not carry these rules: explained bare, it would blame the holder for a constraint the organiser has switched off.

"Respected" only means that no breach was found for this precise seat, not that the constraint applies to it: the UI does not present it as a mark of satisfaction.

`POST /api/postes/{posteId}/simulation-swap` scores a candidate it is pointed to, without persisting anything. No screen calls it (the UI exposes only the assistant's viable replacements, so as not to let anyone choose a replacement that breaks a hard rule); it remains open to the API and to the MCP tool `simuler_swap`.

## Moving an assignment by hand

Two routes: `POST /api/postes/{posteId}/deplacement/simulation` computes, `POST /api/postes/{posteId}/deplacement` writes. The gesture is designated by `?cible=P` (a seat) or `?animateur=A` (a person). The MCP tools are `simuler_deplacement` and `deplacer_affectation`.

The simulation persists nothing. With no body, it reads the verdict on the edition's rules, those the organiser has disabled included: the caller does not have to supply them. The write applies to the saved plan; `&occupant=X` says who the caller believes to be on the origin seat, and if someone else holds it, the answer is `409` with no write.

One rule, three gestures. After the gesture, the seat `{posteId}` holds `animateurCibleId` (nobody when it is null) and `posteCibleId`, if there is one, holds `animateurSourceId`:

| Dropped on | Effect |
| --- | --- |
| an **empty** seat (`cible`) | the person goes there, their seat stays empty: the hole moves, the hard score does not change |
| a **held** seat | the two people swap their seats |
| a **person** (`animateur`) | they take the seat; if they already hold one on the same timeslot, the two seats are swapped rather than putting them in two places |

The refusals:

- **The verdict is that of the whole schedule**, as for a swap: moving a seat can break a hard rule elsewhere (weekly hours, rest). With `casseContrainteDure` true, the write answers `400` naming the rules (`nouvellesViolationsDures`) and writes nothing. The simulation is redone **on the server when writing**, whatever the browser has checked.
- A locked seat refuses (`400`); so does a solve in progress (`409`).
- The two rows are written in a single transaction, and the stored analysis is re-derived from the plan afterwards, as after a restore.
- **The past is frozen** ([ADR 0044](repo:docs/decisions/0044-le-passe-est-fige.md)): a seat whose timeslot has already started, at either end of the gesture, refuses (`400`, "Ce créneau est déjà commencé : le passé ne se modifie plus"), simulation included, as long as `PASSE_FIGE` is on.

## Placing someone on an empty seat

`POST /api/postes/{posteId}/placement?animateur=A` sits `A` on the empty seat of the saved plan, writes and answers like a move (`animateurSourceId` null, scores before and after). It carries the same checks as the direct write of the repair assistant and of the MCP tool `affecter_poste`, of which it is the service:

- solve in progress (`409`), timeslot started ([ADR 0044](repo:docs/decisions/0044-le-passe-est-fige.md)), seat locked;
- **hard rule read twice**, on the whole schedule and on the seat. Filling an empty seat gives back its `posteDoitEtrePourvu` point; the global verdict alone would let a minor placed on a night shift through (`400`, rule named);
- the seat must be **still empty** (`409` otherwise: the panel shows a plan loaded earlier, and without this guard we would silently dislodge whoever took it in the meantime);
- the person must not be under a lock that forbids them a new seat on this timeslot.

The `delta` in the response lets the screen warn when the placement costs quality (medium level).

Keeping the person at the next solve goes through an `ANIMATEUR_CRENEAU` lock that the screen then lays down with `POST /api/verrouillages`, box ticked by default. The placement writes only the seat: the journal keeps two lines (the seat assigned and the lock laid), and a refused lock leaves the placement in place, reported as such. The dialog does not offer "Place" to a person under an `ANIMATEUR` or `ANIMATEUR_CRENEAU` lock on that timeslot, nor on a seat whose timeslot has started (`seatStarted` of the bench): these are the two refusals the bench rules do not carry.

"Free" lays down the symmetric lock, box ticked by default: an `ANIMATEUR_CRENEAU` on the freed person and this timeslot, the mechanism of an accepted swap to keep away the person it frees (`animateurVerrouilleCreneauFige`). Without it, "Fix the rest" would fill the hole with the person just removed from it. Locks are described in [locks, freeze and publication](plan-verrous-et-publication.md).

## Repair assistant

`POST /api/jour-j/postes/{posteId}/suggestions?plafond=N&depuis=M` looks for viable replacements on the saved plan and persists nothing. One of them is applied through `POST /api/postes/{posteId}/affectation?animateurId=X&occupant=Y`; without `animateurId`, the seat is emptied.

Where `simulation-swap` scores the candidate it is given, the assistant **enumerates**: it keeps only the candidates the solver's eligibility filter accepts, simulates each, discards those that would degrade the hard score of the whole plan (and not of the seat alone), then ranks the rest by decreasing impact.

**The cost is bounded.** Each candidate costs a full analysis, so only the first `plafond` eligible candidates are simulated (20 by default, 100 at most; a null or negative value falls back to the default). The response carries `candidatsEligibles` and `candidatsEvalues`: when they differ, the list is the best of what was seen, **not an exhaustive answer**, and the UI must say so. The order of passage keeps the most promising first: free at the time of the seat, then with no seat at all that day, then the fewest minutes worked, then the natural order of identifiers.

This ranking is identical on every call: `depuis` (0 by default) makes it simulate from that rank and the response returns it. A caller that found nobody calls again with `depuis = depuis + candidatsEvalues` (this is "Search further" on the Today screen) until it finds someone or exhausts the pool. `candidatsEvalues` counts what **this call** simulated; a `depuis` beyond the pool answers an empty list.

Applying a suggestion reassigns **that seat only** in `poste_affectation`, with no solver and no rewrite of the rest: the result is exactly the simulated plan.

- A seat covered by a lock is refused (`400`); unlock it first.
- A seat whose timeslot has **ended** is refused too, at the suggestion as at the application (`400`, [ADR 0044](repo:docs/decisions/0044-le-passe-est-fige.md)).
- **A seat whose timeslot is under way is split, not refused** ([ADR 0066](repo:docs/decisions/0066-le-passe-est-fige-a-la-minute.md)). The write cuts the seat at the current minute of the event-day clock: the origin keeps its holder until that minute and the **continuation**, a new seat with identifier `<id>~HHmm` that carries `suiteDe`, receives the write. The identifier written is therefore not the one that was sent. To find it, read the plan again (`GET /api/planning/persisted`) or the response of `POST /api/jour-j/absences`, whose `postesLiberes` names the continuations. The suggestions of such a seat are computed on its continuation, as the write will do.
- **No candidate that would break a hard rule on this seat is offered**, even when the global hard score does not move: on an empty seat, filling the place settles one hard point (`posteDoitEtrePourvu`) and may spend it a second time elsewhere, which would put at the top of the list, at zero delta, the staff member just declared unavailable. `violationsIntroduites` therefore never carries a `HARD`.

**`occupant` is a precondition carried by the write itself.** The seat panel shows a plan read earlier: "Free" and "Replace then Apply" send the person displayed, and the server answers `409` without writing anything if someone else holds the seat in the meantime (an accepted swap, an event-day repair, another tab). The check on the plan read gives the message; the `animateur_id IS NOT DISTINCT FROM ?` clause of the `UPDATE` closes the window between that read and the write, same principle as `placement`, which writes only on a seat that is still empty. Without `occupant`, no precondition: the MCP tool `affecter_poste` and the event-day mode write this way.

The `UPDATE` rechecks nothing else, locks apart. A suggestion list computed **before** another write is stale, and applying it can create an overlap that nobody flags. A UI that keeps several lists on screen invalidates them after each application, as the event-day mode does ([event-day API](api-jour-j-et-animateurs.md)).
