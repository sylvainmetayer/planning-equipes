---
twin: comprendre/mcp-outils
---
# What the MCP server tools do

You know which families of tools the MCP server exposes, what each one reads or writes, which prompts and resources it serves, and what stays deliberately out of the assistant's reach. The server itself announces the exact list of tools; the [`mcp`](repo:src/main/java/dev/sylvain/planning/mcp/) package is its source, one class per family. The rules that all follow (edition as argument, business refusal, hints, writing during a solve) are in [the tool rules](mcp-conventions-des-outils.md), and the connection in [connecting an assistant](mcp-connexion.md).

Everywhere, a tool that reads a plan reads the **saved plan**, and a person comes out by **id**, never by name, in a list sorted by id (an alphabetical order of names would still say something about them).

## Knowing where the edition stands

`etat_edition` is the first useful call. It returns the cycle checklist that the home page displays (reference data, collection, openings, need, last solve, publication, acknowledgements of receipt, fair), each line with its status (`A_FAIRE`, `ATTENTION`, `INFO`, `FAIT`) and the figures that decide it, computed on the server side and therefore identical for the screen and the assistant. A solve in progress reads there as a status, without making the call fail. It contains only counts and dates.

- The `coherence` block counts the input anomalies; `lister_anomalies_referentiel` gives the detail (family, severity, code, record concerned by `objet` and `objetId`) without the sentence or the date that the screen displays. This is the rule of `WarningCodes` applied to the whole list: the warning about a minor dates their coming of age.
- The `aTraiter` block answers "what must I do this morning?": declarations and swaps pending, unreviewed days of the next seven days, silent staff to remind. "Today" is that of `JourJClock`, simulated date included.
- `etat_planning` says when the solver last ran, `etat_publication` when the staff were notified. Only the second describes what people have under their eyes: a plan computed this morning and published last week is, for them, last week's. `etat_publication` also says who would be concerned by the next publication, without sending anything.
- `changements_journee` says what moved on a date, since the last publication (`reference=publication`) or since the plan before the last solve (`reference=resolution`), by seat and by staff member. A seat kept by the same person at other hours is a `HORAIRES` line. `referenceDisponible` false means "nothing to compare", never "no change".
- `realise_vs_planifie` returns the screen of the same name for each stand and each **elapsed** day: seats published, held, absences, replacements, empty, removed (consigne, deleted timeslot: never an absence) or added, minutes published, done and lost. Each day is compared with the publication in force at its first timeslot; a day begun before any publication (`lateReference`) enters no total. `nature` is `DECLARED`: what was recorded, not a clock-in.
- `synthese_affectations` answers in a few dozen lines (seats filled in total, per stand and per day), where `lister_affectations` would spend the assistant's context before it sees that a stand is understaffed on Saturday.

## Solving and correcting the plan

`lancer_solveur` and `resoudre_incremental` start a solve, `statut_solveur`, `lister_jobs`, `arreter_solveur` and `supprimer_job` follow it, `resultats_animateur` and `expliquer_echec_contraintes_dures` read its result. The scope of `resoudre_incremental` (staff, days, stands) applies only to that job and **does not touch locks**: what is frozen durably is set with `verrouiller`.

`diagnostiquer_plan` solves nothing: it recomputes the score of the saved plan, the one `etat_planning` and `lister_affectations` talk about. It returns the **score reading** (`lecture`), the same sentences as the Solver page, written from templates: they name only rules, days and numbers, never a person. Each rule at fault carries its typical `actions` (code, gesture, explanation), as in `expliquer_echec_contraintes_dures`.

To retouch a seat without starting the solve again, `suggerer_reparations` looks for who could hold it and prices each candidate, `affecter_poste` applies the choice. Two tools and not one: an assistant that evaluates and applies in the same call leaves no step where a human can say no. `affecter_poste` refuses a **locked** seat, the lock being what it is asked to respect, and a seat whose timeslot has begun. `accepter_demande_echange` is, with it, one of the only two tools that write into the plan without a solver: it applies the swap then freezes it with `ANIMATEUR_CRENEAU` locks. Hence `analyser_impact_echange` alongside, because the prevalidation stored with the request describes the plan of the day it was sent.

`consulter_historique_ponderation` returns what `GET /api/constraints/{name}/historique` returns, or the global view without `contrainte`: changes of weight and activation, values before and after, typed origin, then the edition's solves with the weighting of each. A setting made by `modifier_poids_contrainte`, `activer_contrainte` or `desactiver_contrainte` carries the origin `ASSISTANT` there.

## Reading the plan from different angles

| Tool | What it returns |
| --- | --- |
| `heures_travaillees` | per staff member designated by id, hours per ISO week and total, maximum daily opening span, days beyond 12 h (a display, no rule). `source=persiste` by default, `source=publie` for the publication in force, refused as long as nothing is published; the response says which plan it read (`source`, `planDate`) |
| `equite_planning` | one row per assigned staff member (total hours, per week, evening, weekend, public holiday, hard seats, runs) and, per column, median, minimum, maximum, standard deviation. `colonnesSolveur` says which columns a rule measures and whether it is active: a column that the solver does not weigh is information, not a defect |
| `analyser_typologies` | the "by game category" view of the Planning page: hours and seats held, `heuresParJour`, and four lists of people reduced to `nombre` and `animateurIds` (`affectes`, `competents`, `competentsJamaisAffectes`, `affectesSansCompetence`) |
| `lister_typologies` | each game category with its counters (`competents`, `polyvalents`, `souhaits`, `stands`) and its `etat`: `ORPHELINE`, `FRAGILE`, `INUTILISEE`, `SANS_COMPETENT_INUTILISEE`, `NORMALE` |
| `analyser_marge` | the margin per day and slice, in `avant` mode (default) or `apres`; the `tension` mode crosses it with the plan's fragility, each cell carrying a `gravite` (`CRITIQUE`, `ELEVEE`, `SURVEILLEE`, `CALME`) and its `motifs`, the irreplaceable seats all being counted there |
| `plan_formation` | per game category that is short or held by a single specialist, the figures of the analyses and the ranked candidates (available tension days, then wish, then level); a candidate is `DEBUTANT` or `AUTONOME`, never `REFERENT`. With no saved plan, `planPersiste` is `false` and only the need part speaks |
| `analyser_pauses` | per staff member and per day, where the due break falls, on which stand, who can relay (`relaisAnimateurIds`); a break that nobody can relay is a hard breach, the `sansRelais` filter returns only those |
| `analyser_enchainements` | two consecutive seats of a staff member on two geolocated locations whose gap leaves no time to walk, or only by taking it from the break (`walkOnBreak`); `geolocated=false` says that no trip could be computed, rather than an empty list that would read as "all is well". Speed, detour and tolerance are set by `modifier_parametres` (section `organisation`) |
| `analyser_arrivees_groupees` | for each group, day by day, who works, who is missing, the gap in arrivals and departures |
| `comparer_editions` | the delta of the Compare two editions screen, with no name at all: a staff row carries its ids in each edition and the names of the fields that differ, never their values. Nothing is matched by id; `matching` says on what two rows were matched (`CODE`, `NOM`, `EMAIL`, `IDENTITE`, `POSITION`) |

The break is set by `consulter_parametres` and `modifier_parametres` (section `legal`): a single duration, `dureePauseMinutes` (30 by default), refused below its public-policy floor of 20 minutes and capped by nothing. The 30 minutes due to a minor apply at reading time, not at setting time: an edition at 25 gives 25 to adults and 30 to minors. The ceilings, the hours report, fairness and the indicators deduct this same duration ([ADR 0048](repo:docs/decisions/0048-une-seule-regle-de-pause.md)). The same section carries `heureDebutSoiree` and `dureeVacationMaxMinutes`.

## Building the grid and the hours

A grid is made of shifts and is no longer asked for in a mode: a timeslot is a shift, and two timeslots that overlap on the same day are two staggered handovers, never an anomaly ([ADR 0037](repo:docs/decisions/0037-une-grille-est-toujours-des-vacations.md)). The check (`valider_creneaux`, `previsualiser_creneaux_recurrents`, `diagnostiquer_grille_creneaux`) always flags a shift beyond `dureeVacationMaxMinutes` (6 h by default), a shift that the daily rest makes untenable, a meal relay outside the window, a duplicate, a gap, an isolated date. `diagnostiquer_grille_creneaux` is the first call to discover an edition.

When the stands have their hours, `previsualiser_derivation_creneaux` shows the grid they imply (a cut at each hour where a stand opens or closes) and `generer_creneaux_depuis_stands` writes it, as an addition or, with `remplacer=true`, in place of the grid and of the plan along with it. `creer_creneau` and `modifier_creneau` take an optional `couverturePause`: the meal relay keeps the stand open at half its headcount, rounded up.

An opening window (`ajouter_horaire_stand`, `creer_stand_complet`, `ajouter_ouverture_stand`) can name its headcount with the suffix `@N` (`10:00-12:00@2,14:00-@4`) or the `effectif` argument of a dated opening; without it, it takes the stand's minimum. Zero is refused (a stand with nobody is a closure) and the suffix is rejected on a timeslot's recurrence.

`definir_journee_type` creates or replaces a day template by its name and its shifts on one line ("09:00-12:00, 12:00-13:00 R, 13:00-14:00 R, 14:00-20:00", `R` marking a meal relay with seats halved), `affecter_journee_type` lays it over a range or dates. None of this writes a timeslot: `previsualiser_application_journees_types` says what applying would change and `materialiser_journees_types` writes it. `reconnaitre_journees_types` goes the other way from the timeslots, and `lister_journees_types` returns the dates out of line or under a consigne, which materialisation does not touch ([ADR 0032](repo:docs/decisions/0032-journees-types-nommees-vacations-fixes.md)).

## Laying down a consigne

An arrêté (an official order) closes a time band for all stands, and the question is whether the plan can be turned around before nightfall ([ADR 0043](repo:docs/decisions/0043-consigne-d-edition-fermer-une-bande-sans-rien-detruire.md)). Eight tools mirror the Consignes screen: `lister_consignes`, `consulter_preselection_consigne`, `simuler_consigne`, `appliquer_consigne`, `simuler_levee_consigne`, `lever_consigne`, `definir_prereglage_consigne`, `supprimer_prereglage_consigne`. None names a person.

**Windows** are written `HH:MM-HH:MM` separated by commas, the end omitted or at `00:00` meaning "until midnight" (`08:00-10:00,18:00-`). **Openings** name the stand, its window and, after `*`, the headcount of that window: `BOURSE=08:00-10:00*2,BOURSE=18:00-22:00*7`. Without `*N`, the window inherits the highest headcount that the stand loses in the band, otherwise its minimum. The reason is mandatory: it is printed everywhere the day is said to be modified. The meal windows (`repasMidi`, `repasSoir`, `coupureRepasMinutes`, `justificationRepas`) apply only on the consigne's dates.

1. `consulter_preselection_consigne` on each date: what the band takes from each stand, the stands proposed, those set aside for hours set by hand. Writes nothing.
2. `simuler_consigne`: per day, seats and minutes before and after, timeslots to add, locks and ad hoc rules touched. This is the full validation without writing: a request that the simulation accepts will be accepted as is.
3. `appliquer_consigne`, same arguments: lays down or replaces the consigne of a date and adds to the grid only the timeslots that the openings require. The dates must be in the future and are all written together or not at all.
4. `resoudre_incremental`, then `etat_publication` and `publier_planning`.

Lifting follows the same pattern: `simuler_levee_consigne` names the added timeslots that would leave with their seats and warns when the frozen days exhaust the available people, `lever_consigne` removes them and refuses a past or current date, a worked day keeping the consigne that governed it. A preset ("Heatwave plan") memorises band, reason and windows and follows the edition's duplication. `consulter_alertes_meteo` returns the alerts raised in the morning with the preset suggested for the phenomenon; it lays nothing down, applying the suggestion remains an explicit `appliquer_consigne`.

## Deciding what staff have asked for

Availability declarations and swap requests arrive from the staff espace. `appliquer_declaration_disponibilite` writes the declaration onto the record through the same `write*` as the entry screen: the data is marked modified, `etat_planning` flags the plan as stale, the journal carries the fields replaced (`joursIndisponibles`, `souhaits`) and the tool returns the warnings as codes.

`consulter_foire_echanges` returns the fair's window and its statistics (volumes, rates, delays, breakdowns by day and by stand) over a creation period (`du`, `au`, or `touteEdition`, refused together with the other two). No staff member appears there, not even by id.

`synthese_confirmations` counts, among the staff who have a seat on the published plan, those confirmed, reminded, silent and the **send failures** (counted apart, with no address) and dates the publication they are answering; `jamaisPublie` true means the question has not yet been put to anyone.

`lister_demandes_covoiturage` returns the "I'm coming with…" requests of the collection, apart from the declaration: members, status, `confirmedByAll`, days where unavailabilities diverge, adjustment created, all by id, with status `EN_ATTENTE` (not validated) or `VALIDEE`. Validation, set-aside and cancellation remain screen gestures, because validation notifies the staff by e-mail; an assistant creates an `ARRIVEE_GROUPEE` directly through `creer_contrainte_ad_hoc`. On the other hand, `supprimer_contrainte_ad_hoc` on a grouped arrival that a validated request supports returns a result in error carrying the sentence of the Manual adjustments screen (cancel it from Availability > Carpool).

## Prompts and resources

The server also serves **twenty-two prompts**, one for each moment of an event's cycle (from `demarrer_une_nouvelle_edition` to `tirer_le_bilan_de_l_evenement`, by way of `construire_la_grille_de_creneaux`, `figer_le_referentiel`, `verifier_avant_resolution`, `publier_le_planning` or `tenir_le_jour_j`), each chaining the tools in the right order. They also say what the tools cannot do (importing the stand matrix, grid entry, marking an absence on the event day and validating a carpool remain on screen), without which the assistant would look for a tool that does not exist.

Each prompt takes an **optional** `edition` argument. Provided, the text names it; omitted, it asks the assistant to call `lister_editions` and retain the active edition, or to ask the question if there is none. In both cases it asks the assistant to pass the edition on every tool call. For `demarrer_une_nouvelle_edition`, it is the **source** edition of the duplication; for `tirer_le_bilan_de_l_evenement`, omitted, it has the assistant propose the edition whose event has just ended, rarely still the active one. `preparer_un_plan_de_repli` prepares the fallback as a dated consigne in the live edition, without duplicating it.

A prompt that names an outgoing tool requires explicit agreement and first has the assistant check that the edition is the active one; the assistant stops rather than activating one of its own accord. `tenir_le_jour_j` notifies through `publier_planning`: `envoyer_planning_animateur` would resend the plan already published, that is, the old one. The interface's MCP page reads them on `GET /api/mcp/prompts` instead of copying them: one text, one source. They are **not translated**. `McpPromptsWordingTest` re-reads their rendered text, with and without an edition: every snake_case word must name an announced tool or prompt (otherwise it appears, argued, in the test's exceptions), and any tool whose `openWorldHint` is true cited by a prompt that is silent on agreement makes the build fail. A renamed tool therefore cannot leave a prompt behind.

Two **resources**, to attach once to inform all following calls:

| URI | Content |
| --- | --- |
| `planning://contraintes` | the catalogue of constraints, generated from `ConstraintCatalog`: it can neither describe an absent rule nor forget one added the day before |
| `planning://vocabulaire` | stand, timeslot, seat, shift, day template, game category, edition, and the order in which an event is prepared; the only hand-written text |

## Old tool names

4.0 renames, groups or removes tools of 3.x, with no alias: a tool is published only under its current name ([ADR 0100](repo:docs/decisions/0100-convention-de-nommage-des-outils-mcp.md)). This table is the rendering of the changed rows of [`mcp-renommages.tsv`](repo:src/main/resources/mcp-renommages.tsv); a name that is absent has not changed. When the file changes, `McpRenommageTest` fails and prints the table to paste between the two markers.

<!-- mcp-renommages:debut -->
| Name in 3.x | Name in 4.0 |
| --- | --- |
| `consulter_parametres_legaux` | `consulter_parametres` |
| `consulter_parametres_notifications` | `consulter_parametres` |
| `consulter_parametres_qualite` | `consulter_parametres` |
| `consulter_parametres_solveur` | `consulter_parametres` |
| `modifier_parametres_legaux` | `modifier_parametres` |
| `modifier_parametres_notifications` | `modifier_parametres` |
| `modifier_parametres_qualite` | `modifier_parametres` |
| `modifier_parametres_solveur` | `modifier_parametres` |
<!-- mcp-renommages:fin -->

## What has no tool

| What stays outside | Why |
| --- | --- |
| YAML scenario export | it contains first name, last name and date of birth, since it must be re-importable |
| Backups before emptying | each is a scenario export. `reinitialiser_donnees` and the imports write one, but only an administrator reads, restores or deletes it, through the API ([ADR 0084](repo:docs/decisions/0084-sauvegarder-l-edition-avant-de-la-vider.md)) |
| SQL dump, PDF, ICS and CSV exports | all the personal data, or binaries meant for download from the interface |
| CSV import of volunteers | the spreadsheet carries last name, first name, date of birth and address: a tool that accepted it would let in through MCP what does not leave through it |
| `/api/planning/sample`, `/persisted` | a whole `PlanningEvenement`, several dozen MB with the personal data; `lister_affectations` covers the need while staying filtered |
| Staff espace and ICS subscription | the staff side, behind a personal token: MCP sees the requests and declarations on the admin side, never someone's espace |
| Rotating a staff member's espace token | it invalidates the link already printed on a distributed PDF, a consequence that cannot be undone from a conversation |
| Outgoing webhooks | a Slack, Discord or Matrix address is a secret, and a webhook opens a network exit from the server: configuring it from a conversation would be a ready-made exfiltration route. They are set in Settings › Instance |
| `/api/horloge` | the simulated clock moves the event-day mode, the frozen past and the staff espaces for everyone: an instance setting, not an organisation question |
| `/api/config`, `/api/auth`, `/api/branding`, `/api/mentions-legales`, `/api/debug` | public browser keys, admin session, branding and development sandboxes: nothing an assistant could do with them |
