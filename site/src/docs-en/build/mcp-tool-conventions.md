---
twin: comprendre/mcp-conventions-des-outils
---
# The rules every MCP tool follows

You know what an MCP tool, existing or to be added, must respect: designate the edition by argument, bring back a readable business refusal, declare its hints and its published name, say what happens to a write during a solve, respect the reference data freeze, and signal that it sends an e-mail. Each rule is held by a structural test that fails the build: a tool added later cannot escape them by omission. The tools themselves, family by family, are in [the MCP server tools](mcp-outils.md); authentication and confidentiality are in [connecting an assistant](mcp-connexion.md).

MCP and REST are two callers of the same rules: a tool delegates to the services and never to a JAX-RS resource (`LayeringStructuralTest`).

## Designating the edition by argument

An MCP request is not a JAX-RS request: `EditionHeaderFilter` never sees it, and **`X-Edition-Id` has no effect on `/mcp`**. Each tool that works within an edition carries an `edition` argument, which accepts the id or the name. It is **mandatory** ([ADR 0072](repo:docs/decisions/0072-une-seule-edition-active.md)): omitted, the call is refused (`EDITION_REQUISE`) rather than acting in an edition nobody named. An unknown edition fails the same way (`EDITION_INCONNUE`, with the list of existing editions), like the HTTP header that names a deleted one: a silent fallback would send the write to the wrong one. The id is tried before the name, which is why an edition name shaped like an id (`E` followed by a number) is refused at creation and at renaming.

Three pieces take care of it. `@EditionArg` marks the carrying argument (the annotation makes the link, the argument's name plays no part). `@EditionCiblee` is placed on the tool **class**, so a tool added later inherits it. `EditionCibleeInterceptor` binds the edition around the call.

> **Self-invocation trap.** A tool that calls another on `this` bypasses the interceptor. Go through a private helper, never through the neighbouring tool.

`McpEditionStructurelleTest` fails on any `@Tool` that works within an edition without allowing it to be designated. Ten exemptions, which neither read nor write anything within an edition: `lister_scenarios` and `valider_scenario_yaml` (shipped files, pure validation), the four job-control tools (`statut_solveur`, `arreter_solveur`, `lister_jobs`, `supprimer_job`: the registry is global, each job carrying the edition it was started for), `lister_mesures_edition` and `supprimer_mesure_edition` (not partitioned so that an edition can be compared with the previous one) and the two backup tools. Any other tool with no edition is `@InstanceOnlyTool` (reserved to the administrator) or justified in the test.

Two clarifications. Solver launches capture the edition **at submission**: the job stays attached to it even if the active edition changes afterwards. And an `edition:` section in an imported YAML takes precedence over the argument, since the file explicitly designates its target. `comparer_editions` takes `reference` and `cible` as two ordinary arguments.

Only one edition is **active**; it alone publishes, sends e-mails and opens the staff espace, the ICS feed and the wall display. `lister_editions` flags it, `edition_courante` names it (in error between two events, when none is), `activer_edition` and `desactiver_edition` change it.

## Bringing back a business refusal

Without handling, a `BusinessError` leaves as a generic JSON-RPC error (`-32603`, "Internal error"): the sentence that says what is refused is lost, and the assistant retries identically for lack of telling a faulty input from a broken server. `RefusMetierInterceptor`, the MCP counterpart of `api/BusinessErrorMapper`, brings a `BusinessError` back as a tool result in error (`isError`) carrying its sentence. **Only a deliberate refusal goes through here**: an unintended `IllegalArgumentException` or a database outage keep their "Internal error" and their alert, as REST keeps its 500.

It binds to the tool classes through `@RefusMetier`, the third annotation next to `@EditionCiblee` and `@Journalise`: these are three different questions, and `SauvegardeMcpTools` does not carry `@EditionCiblee`. It applies **outside** `EditionCibleeInterceptor`, so the refusal of an unknown edition, raised before the tool body, arrives too. `McpRefusMetierStructurelleTest` fails on any tool class that forgets the binding.

**The sentence crosses as is, which is safe only because no refusal names anyone.** Running it through `AnonymisationViolations` again would accidentally trigger its recognition of the "First name Last name (id)" shape on sentences such as "Deux horaires de même portée (MONDAY,TUESDAY)…". The rule is therefore held at the source: `McpRefusMetierStructurelleTest` fails on a `BusinessError` built from a staff member's name, first name, date of birth or address. Always designate a staff member by their id in a refusal message.

## Declaring the hints and the name

The four *hints* of the MCP specification are declared on **all** tools. Their defaults are `destructiveHint = true` and `openWorldHint = true`: a mute tool such as `lister_stands` would announce itself as a destructive call to the outside, and the signal would no longer mean anything.

| Hint | Value |
| --- | --- |
| `readOnlyHint` | true for tools that only read |
| `destructiveHint` | true for deletions, imports, a snapshot restore and solves, which replace the saved plan |
| `idempotentHint` | true when calling the tool again with the same arguments changes nothing more |
| `openWorldHint` | true only for the tools that send e-mail, false everywhere else |

`McpAnnotationsStructurelleTest` holds both ends. `openWorldHint = false` serves as a marker (a forgotten block keeps the default and fails), and the expected hints are derived from the **prefix of the tool's name** (`lister_`, `consulter_`, `analyser_`… read; `supprimer_`, `importer_`, `lancer_solveur`… destructive; `creer_`, `modifier_`… non-destructive): a `supprimer_stand` declared read-only fails, and a name that the test cannot classify fails too. The outgoing tools are **enumerated** in the test, never inferred: a send is a reviewed decision, and listing it is that decision.

Each tool and each prompt carries its published name in full (`@Tool(name = "lister_stands", …)` on the Java method `listStands`). The method follows the rule of the rest of the code (English, camelCase); the published name stays French, it is the one the assistant picks, that the journal records (`CatalogueActions`) and that the prompts cite. Renaming the method must never rename the tool for clients already connected. `McpToolNamesTest` fails on a `@Tool` or a `@Prompt` with no explicit `name`, and the other structural tests read this published name, never the Java name.

## Naming a tool

A tool's name is `<verb>_<object>`: the verb of intent first, taken from a closed set (`lister`, `consulter`, `analyser`, `diagnostiquer`, `comparer`, `simuler`, `previsualiser` to read; `creer`, `modifier`, `activer`, `desactiver`, `verrouiller`, `appliquer`, `arreter`, `publier`, `envoyer` to write; `supprimer`, `deverrouiller`, `lancer`, `importer`, `restaurer` to overwrite or erase; `exporter`), then the object. It is the verb from which `McpAnnotationsStructurelleTest` derives the hints: a settings tool with sections therefore splits into a read tool and a write tool (verbs `consulter` and `modifier`, object `parametres`), each with a `section` argument. The names published in 3.1.0 keep theirs until the model they serve is rebuilt in 4.0: `src/main/resources/mcp-renommages.tsv` says, for each one, its current name or `retiré`, and `McpRenommageTest` refuses a name with no row, a rename outside the convention and a [table of old names](mcp-outils.md#anciens-noms-doutils) that no longer follows the file. There is no alias ([ADR 0100](repo:docs/decisions/0100-convention-de-nommage-des-outils-mcp.md)).

## Writing while a solve holds the edition

The landing of a solve rewrites the seats and takes back the stands, timeslots and staff of its problem: a write made in the meantime would be undone. Each write tool therefore has an answer to "what if a solve holds the edition?", and `McpWarnsWhileSolvingStructuralTest` fails the build on a tool that has none. It also re-reads, for each refused tool, that the named service method carries `@RefusedWhileSolving` (or the explicit call of a conditional refusal that `RefusedWhileSolvingStructuralTest` argues). The three lists are disjoint and exhaustive.

| During a solve of the edition | Tools | What the assistant receives |
| --- | --- | --- |
| **Refused** | modifying and deleting staff and stands, a stand's hours and ranges, modifying and deleting timeslots, recurring and derived timeslots, materialising day templates, consignes, `affecter_poste`, `deplacer_affectation`, `accepter_demande_echange`, snapshot restore, imports, reset | the refusal (`409` on the REST side) with the job at fault named |
| **Accepted, warned** (`@WarnsWhileSolving`) | game categories, locations, ad hoc constraints, legal settings and solver duration, locks, constraint activation and weights, creating a stand, a staff member or a timeslot, `ajouter_validation_journee` | the usual response, with **`RESOLUTION_EN_COURS`** in `avertissements` |
| **Unrelated** | the solver itself, editions, reference data freeze, publications and sends, consigne presets, `retirer_validation_journee`, day templates not materialised, snapshots, notification and backup settings | the usual response; each exclusion carries its reason in the test |

`RESOLUTION_EN_COURS` means: **saved**, but the solve in progress read the reference data and the plan at its start and will not see this write; start a solve again once it has finished. `statut_solveur` names the job and carries `feasibleAtSeconds` (the seconds elapsed when its plan became feasible, `null` as long as it is not): an assistant can offer to stop there, `arreter_solveur` keeping the best plan reached. On `ajouter_validation_journee`, it also says that the validation may not survive: the landing removes the review of the days that the solve recomputed, except those entirely past or locked at `JOUR`. It comes neither from a solve of another edition (it is the `edition` argument that counts) nor from a solve only queued, which will read the write in its turn.

Never turn a refusal into a warning: it protects against the resurrection of data by the landing. The screens lock these inputs for the duration of the solve; an assistant with no screen has only the warning.

## Respecting the phase of the edition

The phase of the edition decides which reference data families are frozen (`STANDS`, `CRENEAUX`, `TYPOLOGIES_EMPLACEMENTS`, `COMPETENCES`, [ADR 0115](repo:docs/decisions/0115-la-phase-de-l-edition-remplace-le-gel.md)), and `etat_edition` says which ones are frozen in its `gel` block. `modifier_phase_edition` changes it: its `phase` argument declares `PREPARATION`, `CALCUL`, `PUBLIEE` or `TERMINEE`, backwards included, and its `structure` argument (`DEBLOQUER` or `REFIGER`, in `PUBLIEE` only) reopens or closes the structure without leaving the phase; one or the other, never both. It replaces `figer_referentiel` and `lever_gel`, which no longer exist (the table of [old names](mcp-outils.md) sends them to it): the 3.x REST freeze routes, for their part, remain aliases until 5.0. Each gesture is journalled under the action of its route (phase changed, structure unblocked, structure refrozen) and the tool is classed "unrelated" to a solve: a phase refuses future writes, it changes no data that a solve reads.

The refusal of a frozen write is placed on the services, which the tools call like the screens: `modifier_stand` that changes a headcount, `supprimer_creneau`, `creer_typologie` or `modifier_animateur` that changes skills come back as a business refusal, with the sentence that names the frozen families and says how to reopen them; `modifier_stand` that touches only the name, or `appliquer_consigne`, go through. Reopen the reference data only on explicit request: after a publication, any retouch moves schedules that were already sent.

## Flagging an outgoing e-mail

Four tools make an e-mail leave and are the only ones to announce `openWorldHint = true`: `publier_planning`, `envoyer_planning_animateur`, `relancer_animateurs` and `configurer_collecte_disponibilites` when it notifies the staff. They are refused on an inactive edition (`409 EDITION_INACTIVE`), and `ActiveEditionEmissionStructuralTest` fails on a send or an outgoing tool that is neither guarded nor argued.

`publier_planning` first captures the published snapshot and then writes to people: a send that fails halfway leaves a coherent published plan and a trace of who was missed, which `lister_destinataires_publication` re-reads, by id. `relancer_animateurs` follows the rule of the night reminder: **one reminder only per person and per publication**, whichever hand sends it. The tool reserves the same key as the nightly job, so someone reminded by the assistant is not reminded a second time the next night, unless the night send failed and gave back the key. The report carries only ids: `envoyes`, `dejaConfirmes`, `sansEmail`, `dejaRelancesPourCettePublication`, `echecs`, `sansPoste` and `adresseRefusee`. It is refused if nothing has ever been published or if an id is unknown, and nothing is then sent, not even to the valid ids.

## Where a tool differs from REST

The assistant names ids it may have invented, sees no greyed-out button and reads an empty result as a success. Hence deliberate differences.

| Tool | Difference from REST | Reason |
| --- | --- | --- |
| `modifier_animateur` | merges instead of replacing | the assistant does not read the identity it would have to send back |
| `modifier_stand`, `modifier_animateur`, `modifier_creneau`, `modifier_emplacement`, `modifier_typologie` | optional `modifieLe` | the merge re-reads the record just before writing: without the argument, the concurrent-modification check says nothing. An assistant that read the record earlier passes the value it read in order to be refused if it moved |
| `affecter_poste` | refuses a staff member that the seat cannot accommodate: the gesture is scored on the saved plan and rejected if it degrades the hard score or creates a hard breach on the seat ([ADR 0046](repo:docs/decisions/0046-un-placement-intenable-est-dit-avant-le-calcul.md)) | the screen offers only a candidate already scored, an assistant names whoever it likes |
| `verrouiller` | returns `{ verrouillage, avertissements }`; the code `VERROUILLAGE_SUR_VIOLATION_DURE` says the frozen seats already break a hard rule | the lock is written (it freezes, it does not exempt), but whoever goes on to `lancer_solveur` must know where the negative hard score will come from |
| `deverrouiller`, `retirer_validation_journee` | fail on an unknown id, where the `DELETE` answers `204` or `404` | "removed" on a row that did not exist would make people believe the schedule is free to move |
| `creer_version`, `restaurer_version` | raise an error where REST returns a `409` with a body | a result that the assistant reads as a success must not say that nothing was written |
| `simuler_deplacement`, `deplacer_affectation` | a single tool for the three gestures of "Move to…" (move, swap, assign), the target being a seat or a person | the server chooses the gesture then refuses what would break a hard rule |
| `lancer_solveur` | starts from the saved plan by default (`reamorcage=AUTO`); `PLAN_COURANT` fails with no plan; `AUCUN` asks for a cold start | re-running "just to see" would otherwise silently destroy the quality reached |
| `lancer_solveur` | argument `distant`, and `calculDistant` in `statut_solveur` | with a compute machine ([ADR 0086](repo:docs/decisions/0086-le-calcul-deporte-un-worker-sans-conteneur-et-un-crochet.md)), `true` sends the search there, `false` keeps it on the spot, absent follows the instance setting. `calculDistant` is `PREPARING` until the worker has received the problem, then `SOLVING`. A remote solve that fails ends `FAILED` with its reason: start again with `distant=false` |
| `lancer_solveur`, `resoudre_incremental` | during the event, the seats of timeslots already begun are taken from the saved plan and pinned; a job whose seats are all past ends `FAILED` with "Rien à planifier…" ("Nothing to plan…"), and `statut_solveur` carries `avertissement` when the past holds gaps ([ADR 0044](repo:docs/decisions/0044-le-passe-est-fige.md)) | an assistant does not rewrite yesterday any more than the screen does, and an empty plan at zero hard would read as a success |
| `deplacer_affectation`, `simuler_deplacement`, `accepter_demande_echange`, `analyser_impact_echange`, `suggerer_reparations`, `affecter_poste` | refuse a seat whose timeslot has begun: "Ce créneau est déjà commencé : le passé ne se modifie plus" ("This timeslot has already begun: the past can no longer be modified") | the simulation tool already refuses, so as not to build a plan on a gesture that the write will refuse |
| `statut_solveur` | with no `jobId` and no active job, answers `IDLE` with "Aucun solveur ne tourne." ("No solver is running.") in `avertissement`; an unknown `jobId` is refused | a tool that returns nothing cannot be encoded, the assistant received "Internal error" |
| `lister_affectations`, `consulter_version` | cap the list (200 by default) and announce the total | a real plan carries several thousand seats; the total makes the truncation readable |
| `heures_travaillees` | reads the saved plan by default, where the screen opens on the publication in force | an assistant generally reads what it has just retouched |
| `diagnostiquer_plan` | error with no saved plan, where `POST /api/constraints/diagnostic` returns the empty view | an empty structure would read as "no constraint at fault" |
| `publier_planning`, `configurer_collecte_disponibilites` | count the people with no address and the send failures, REST names them | `lister_destinataires_publication` gives the detail again by id |

The solves started by `lancer_solveur` and `resoudre_incremental` go through the **replayable** submissions of `SolverJobService`, like the screens: the problem is built when the job starts, not at the call. This gives queueing (`enFile`, without which a busy solver refuses the request) and freshness, since a write made during the wait is taken into account; the queue itself is in memory, a restart loses it. The budget follows the screen's rule ([the solve](api-calcul.md)): without `secondes`, the job runs for the instance's duration and plateau stop, which `consulter_parametres` (section `calcul`) shows and no tool sets. Above the instance ceiling, a `secondes` is refused with a sentence that cites this ceiling.
