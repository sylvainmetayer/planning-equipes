---
twin: comprendre/mcp-outils
---
# What the MCP server tools do

You know which families of tools the MCP server exposes, what each one reads or writes, which prompts and resources it serves, and what stays deliberately out of the assistant's reach. The server itself announces the exact list of tools; the [`mcp`](repo:src/main/java/dev/sylvain/planning/mcp/) package is its source, one class per family. The rules that all follow (edition as argument, business refusal, hints, writing during a solve) are in [the tool rules](mcp-conventions-des-outils.md), and the connection in [connecting an assistant](mcp-connexion.md).

Everywhere, a tool that reads a plan reads the **saved plan**, and a person comes out by **id**, never by name, in a list sorted by id (an alphabetical order of names would still say something about them).

## Knowing where the edition stands

`consulter_edition` is the first useful call. It returns the cycle checklist that the home page displays (reference data, collection, openings, need, last solve, publication, acknowledgements of receipt, fair), each line with its status (`A_FAIRE`, `ATTENTION`, `INFO`, `FAIT`) and the figures that decide it, computed on the server side and therefore identical for the screen and the assistant. A solve in progress reads there as a status, without making the call fail. It contains only counts and dates.

- The `coherence` block counts the input anomalies; `lister_anomalies_referentiel` gives the detail (family, severity, code, record concerned by `objet` and `objetId`) without the sentence or the date that the screen displays. This is the rule of `WarningCodes` applied to the whole list: the warning about a minor dates their coming of age.
- The `aTraiter` block answers "what must I do this morning?": declarations and swaps pending, unreviewed days of the next seven days, silent staff to remind. "Today" is that of `JourJClock`, simulated date included.
- `consulter_planning` says when the solver last ran, `consulter_publication` when the staff were notified. Only the second describes what people have under their eyes: a plan computed this morning and published last week is, for them, last week's. `consulter_publication` also says who would be concerned by the next publication, without sending anything.
- `comparer_journee` says what moved on a date, since the last publication (`reference=publication`) or since the plan before the last solve (`reference=resolution`), by seat and by staff member. A seat kept by the same person at other hours is a `HORAIRES` line. `referenceDisponible` false means "nothing to compare", never "no change".
- `comparer_realise_planifie` returns the screen of the same name for each stand and each **elapsed** day: seats published, held, absences, replacements, empty, removed (consigne, deleted timeslot: never an absence) or added, minutes published, done and lost. Each day is compared with the publication in force at its first timeslot; a day begun before any publication (`lateReference`) enters no total. `nature` is `DECLARED`: what was recorded, not a clock-in.
- `consulter_planning`, view `synthese`, answers in a few dozen lines (seats filled in total, per stand and per day), where the `affectations` view would spend the assistant's context before it sees that a stand is understaffed on Saturday.

## Computing and correcting the plan

`lancer_calcul` starts the full solve from the plan in place: locks and the frozen past are respected, everything else may move; `deZero` starts from scratch and loses the quality already reached. `lancer_correction` reopens only the seats in default, those a change has invalidated, and freezes the rest for that solve; its `animateurIds`, `jours` and `standIds` also reopen everything they name, apply only to that solve and **do not touch locks**: what is frozen durably is set with `verrouiller`. `consulter_calcul` follows a solve (or the active one, `IDLE` when nothing runs) and returns the history with `historique`, `arreter_calcul` and `supprimer_calcul` stop and forget it.

`diagnostiquer_plan` solves nothing: it recomputes the score of the saved plan, the one `consulter_planning` talks about. It returns the **score reading** (`lecture`), the same sentences as the Calculate tab, written from templates: they name only rules, days and numbers, never a person. Each rule at fault carries its typical `actions` (code, gesture, explanation); with `ecartsDurs`, it also returns the anonymised message of each hard default.

`consulter_planning` reads the saved plan under a `vue`: `etat` (when it was computed, and whether it is stale), `synthese` (a few dozen lines, per stand and per day) or `affectations` (the seats, filtered and capped). `analyser_affectation` explains the score of a seat. To retouch a seat without starting the solve again, `simuler_affectation` prices a change without writing anything (`mode` `affecter`: another holder; `deplacer`: towards another seat or another person; `candidats`: who could hold it), and `modifier_affectation` applies it (`affecter`, or `deplacer`, refused if it breaks a hard rule). Two tools and not one: an assistant that evaluates and applies in the same call leaves no step where a human can say no. `modifier_affectation` refuses a **locked** seat, the lock being what it is asked to respect, and a seat whose timeslot has begun. `modifier_demande_echange` (`decision` `accepter`) is, with it, one of the only two tools that write into the plan without a solver: it applies the swap then freezes it with `ANIMATEUR_CRENEAU` locks. Hence `analyser_impact_echange` alongside, because the prevalidation stored with the request describes the plan of the day it was sent.

`modifier_contrainte` sets a rule for the edition, one setting per call: `actif`, `poids` (or `poidsParDefaut`), or the `niveau` of an organisation rule: `OBLIGATOIRE`, `PREFERENCE_FORTE`, `PREFERENCE_NORMALE` or `PREFERENCE_LEGERE` (a preference writes the weight of its position). `lister_contraintes` gives `niveauReglable` and `niveauChoisi`; a legal, assignment, ad hoc or lock rule, or a soft preference, refuses the level. The tool is accepted during a computation and says so (`RESOLUTION_EN_COURS`), and returns the rule's line as `lister_contraintes` reads it after the change.

A setting made by `modifier_contrainte` is read in the application's action journal ("Rules" filter), under the `ASSISTANT` actor, with the rule's name and what changed (activation, weight or level), without the value. The former `consulter_historique_ponderation` tool is removed: the journal keeps no value, and no tool reads it.

## Reading the plan from different angles

| Tool | What it returns |
| --- | --- |
| `consulter_besoin` | the Need tab of Diagnosis, under a `section`: `borne` (default: the proven minimum team, what sets it and the bottleneck per game category), `marge` (per day and slice, `mode` `avant` by default or `apres`), `typologie` (hours and seats held, `heuresParJour`, and four lists of people reduced to `nombre` and `animateurIds`: `affectes`, `competents`, `competentsJamaisAffectes`, `affectesSansCompetence`), `volumetrie` (the size of the next solve's problem) or `faisabilite` (the blocking causes before solving). `mode` outside the `marge` section is refused |
| `consulter_risques` | the Risks tab, under a `section`: `fragilite` (default: per assigned staff member, the seats a withdrawal would collapse and those nobody takes over, then the stand × timeslot pairs at most one specialist can hold; without the name or the sentence of the screen, which names the most fragile person), `former` (per game category that is short or held by a single specialist, the candidates ranked by available tension days, wish then level, `DEBUTANT` or `AUTONOME`, never `REFERENT`; with no saved plan, `planPersiste` is `false`) or `tension` (the `apres` margin crossed with the fragility, each cell carrying a `gravite` `CRITIQUE`, `ELEVEE`, `SURVEILLEE` or `CALME` and its `motifs`) |
| `consulter_charge` | the Load report, under a `vue`: `equite` (default: one row per assigned staff member, total hours, per week, evening, weekend, public holiday, hard seats, runs, and per column median, minimum, maximum, standard deviation; `colonnesSolveur` says which columns a rule measures, a column the solver does not weigh being information, not a defect) or `heures` (hours per ISO week and total, maximum daily opening span, days beyond 12 h, a display with no rule; `source=persiste` by default, `source=publie` for the publication in force, refused as long as nothing is published, and the response says which plan it read). `source` outside the `heures` view is refused |
| `consulter_repas_pauses` | the Meals and breaks report, under a `section`: `pauses` (default: per staff member and per day, where the due break falls, on which stand, who can relay; a break nobody can relay is a hard breach, `sansRelaisSeulement` returns only those) or `enchainements` (two consecutive seats on two geolocated locations whose gap leaves no time to walk, or only by taking it from the break, `walkOnBreak`; `geolocated=false` says that no trip could be computed, rather than an empty list that would read as "all is well"; speed, detour and tolerance are set by `modifier_parametres`, section `organisation`) |
| `lister_typologies` | each game category with its counters (`competents`, `polyvalents`, `souhaits`, `stands`) and its `etat`: `ORPHELINE`, `FRAGILE`, `INUTILISEE`, `SANS_COMPETENT_INUTILISEE`, `NORMALE` |
| `analyser_arrivees_groupees` | for each group, day by day, who works, who is missing, the gap in arrivals and departures |
| `comparer_editions` | the delta of the Editions view of the Compare two versions screen, with no name at all: a staff row carries its ids in each edition and the names of the fields that differ, never their values. Nothing is matched by id; `matching` says on what two rows were matched (`CODE`, `NOM`, `EMAIL`, `IDENTITE`, `POSITION`) |

The break is set by `consulter_parametres` and `modifier_parametres` (section `legal`): a single duration, `dureePauseMinutes` (30 by default), refused below its public-policy floor of 20 minutes and capped by nothing. The 30 minutes due to a minor apply at reading time, not at setting time: an edition at 25 gives 25 to adults and 30 to minors. The ceilings, the hours report, fairness and the indicators deduct this same duration ([ADR 0048](repo:docs/decisions/0048-une-seule-regle-de-pause.md)). The same section carries `heureDebutSoiree` and `dureeVacationMaxMinutes`.

## Building the grid and the hours

A grid is made of shifts and is no longer asked for in a mode: a timeslot is a shift, and two timeslots that overlap on the same day are two staggered handovers, never an anomaly ([ADR 0037](repo:docs/decisions/0037-une-grille-est-toujours-des-vacations.md)). The check (`diagnostiquer_creneaux` in section `controle`, `previsualiser_generateur_creneaux`) always flags a shift beyond `dureeVacationMaxMinutes` (6 h by default), a shift that the daily rest makes untenable, a duplicate, a gap, an isolated date. `diagnostiquer_creneaux`, in section `grille` (the default), is the first call to discover an edition. `supprimer_creneaux` removes timeslots with their seats, named by `ids` or picked by filters (dates, start hour, or `all=true` for the whole grid), never both at once.

One generator produces the grid in bulk ([ADR 0126](repo:docs/decisions/0126-un-generateur-de-creneaux-unique.md)): `previsualiser_generateur_creneaux` shows what it would produce and checks the resulting grid, `appliquer_generateur_creneaux` writes it, with the same arguments and a `source`. `stands` cuts the grid the stands' hours imply (a cut at each hour where a stand opens or closes), as an addition or, with `remplacer=true`, in place of the grid and of the plan along with it; `recurrence` adds windows repeated over dates; `gabarits` applies the templates' calendar; `grille` reads the templates the grid implies.

A stand's opening hours are one object, in two forms: its **model** (its recurring rules, ADR 0104) and its dated **plages**. `lister_stands` returns them under `horaires` and `plages` (id, mode, day, hours, headcount, and `depliee`: unfolded from the model or typed by hand), for every stand or for one with `id`. `creer_horaire_stand` adds, by `forme`, a rule to the model (`modele`: windows and scope; a rule identical to an existing one changes nothing) or a one-day plage (`plage`: date, hours, headcount of an opening), an opening or a closure (`mode`). `supprimer_horaire_stand` removes a plage (`forme=plage`, by `plageId`) or all those of a day (`date`), or clears the model (`forme=modele`) **and** the plages it unfolded, keeping the hand-entered ones. An argument of one form is refused with the other, and these writes are refused during a solve and when the edition's phase freezes the stands.

`creer_stand` also creates, when needed, what the stand depends on: its location (with `emplacementNom`), its missing game categories (with `creerTypologiesManquantes=true`) and its opening windows (`horaires`); the answer lists what it created along the way.

An opening window (`creer_horaire_stand`, `creer_stand`) can name its headcount with the suffix `@N` (`10:00-12:00@2,14:00-@4`) or the `effectif` argument of a dated opening; without it, it takes the stand's minimum. Zero is refused (a stand with nobody is a closure) and the suffix is rejected on a timeslot's recurrence.

The templates (« gabarits ») are the day templates of 3.x. `modifier_gabarit` creates or replaces a template by its name and its shifts on one line ("09:00-12:00, 12:00-13:00, 13:00-14:00, 14:00-20:00"), and lays it over a range or dates; `supprimer_gabarit` removes a template, or only dates from the calendar. None of this writes a timeslot: `appliquer_generateur_creneaux` with the source `gabarits` does, after the preview. `lister_gabarits` returns the dates out of line or under a consigne, which applying does not touch ([ADR 0032](repo:docs/decisions/0032-journees-types-nommees-vacations-fixes.md)).

## Laying down a consigne

An arrêté (an official order) closes a time band for all stands, and the question is whether the plan can be turned around before nightfall ([ADR 0043](repo:docs/decisions/0043-consigne-d-edition-fermer-une-bande-sans-rien-detruire.md)). Eight tools mirror the Consignes tab of Rules: `lister_consignes`, `consulter_preselection_consigne`, `simuler_consigne`, `appliquer_consigne`, `simuler_levee_consigne`, `supprimer_consigne`, `modifier_prereglage_consigne`, `supprimer_prereglage_consigne`. None names a person.

**Windows** are written `HH:MM-HH:MM` separated by commas, the end omitted or at `00:00` meaning "until midnight" (`08:00-10:00,18:00-`). **Openings** name the stand, its window and, after `*`, the headcount of that window: `BOURSE=08:00-10:00*2,BOURSE=18:00-22:00*7`. Without `*N`, the window inherits the highest headcount that the stand loses in the band, otherwise its minimum. The reason is mandatory: it is printed everywhere the day is said to be modified. The meal windows (`repasMidi`, `repasSoir`, `coupureRepasMinutes`, `justificationRepas`) apply only on the consigne's dates.

1. `consulter_preselection_consigne` on each date: what the band takes from each stand, the stands proposed, those set aside for hours set by hand. Writes nothing.
2. `simuler_consigne`: per day, seats and minutes before and after, timeslots to add, locks and ad hoc rules touched. This is the full validation without writing: a request that the simulation accepts will be accepted as is.
3. `appliquer_consigne`, same arguments: lays down or replaces the consigne of a date and adds to the grid only the timeslots that the openings require. The dates must be in the future and are all written together or not at all.
4. `lancer_correction`, then `consulter_publication` and `publier_planning`.

Lifting follows the same pattern: `simuler_levee_consigne` names the added timeslots that would leave with their seats and warns when the frozen days exhaust the available people, `supprimer_consigne` removes them and refuses a past or current date, a worked day keeping the consigne that governed it. A preset ("Heatwave plan") memorises band, reason and windows and follows the edition's duplication. `consulter_alertes_meteo` returns the alerts raised in the morning with the preset suggested for the phenomenon; it lays nothing down, applying the suggestion remains an explicit `appliquer_consigne`.

## Deciding what staff have asked for

Availability declarations and swap requests arrive from the staff espace. `consulter_disponibilites` reads the Availability tab of the Staff page under a `section`: `collecte` (default: the window, its dates, the automatic reminder), `declarations` (filterable by `statut`) or `covoiturage`. `modifier_declaration_disponibilite` decides a declaration: `decision=appliquer` writes it onto the record through the same `write*` as the entry screen (the data is marked modified, `consulter_planning` flags the plan as stale, the journal carries the fields replaced, `joursIndisponibles` and `souhaits`, and the tool returns the warnings as codes); `decision=refuser` leaves the record untouched, with the `commentaire` the staff member will read. Opening the collection stays `modifier_collecte_disponibilites`, apart, because it may write to the staff.

`consulter_foire_echanges` returns the fair's window and its statistics (volumes, rates, delays, breakdowns by day and by stand) over a creation period (`du`, `au`, or `touteEdition`, refused together with the other two). No staff member appears there, not even by id.

`consulter_publication`, view `confirmations`, counts, among the staff who have a seat on the published plan, those confirmed, reminded, silent and the **send failures** (counted apart, with no address) and dates the publication they are answering; `jamaisPublie` true means the question has not yet been put to anyone.

The `covoiturage` section of `consulter_disponibilites` returns the "I'm coming with…" requests of the collection, apart from the declaration: members, status, `confirmedByAll`, days where unavailabilities diverge, adjustment created, all by id, with status `EN_ATTENTE` (not validated) or `VALIDEE`. Validation, set-aside and cancellation remain screen gestures, because validation notifies the staff by e-mail; an assistant creates an `ARRIVEE_GROUPEE` directly through `creer_contrainte_ad_hoc`. On the other hand, `supprimer_contrainte_ad_hoc` on a grouped arrival that a validated request supports returns a result in error carrying the sentence of the Adjustments tab of Rules (cancel it from Availability > Carpool).

## Editions, scenarios and publication

One tool per object and per verb, the gesture passed as an argument ([ADR 0127](repo:docs/decisions/0127-un-outil-mcp-par-objet-et-par-verbe.md)). `creer_edition` creates an empty edition, or the copy of another one with `source`, with or without its staff. `modifier_edition` changes one setting per call: `nom`, or `active` (true: it becomes the active edition and the former one stops being so; false: nothing leaves any more). The phase stays `modifier_phase_edition`, open to the organiser signed in through OAuth, whereas `creer_edition` and `modifier_edition` are reserved to the instance administrator.

`importer_scenario` takes a shipped scenario (`nom`) or YAML content (`yaml`), one of the two; `diagnostiquer_scenario` checks a YAML without importing anything, and `supprimer_donnees_edition` empties the edition. `consulter_publication` reads the publication under a `vue`: `etat` (default), `destinataires` (the trace of a publication) or `confirmations`. Three tools write to people, each with its own verb: `publier_planning` (everyone, same keys as in 3.x: `snapshotId`, `envoyes`…), `envoyer_planning_animateur` (the published schedule of one person) and `envoyer_relance_animateurs` (the designated silent ones). A send is never a mode of another tool: a forgotten argument must not write to the whole team. `consulter_sauvegardes` gives the state of the nightly backup, `modifier_sauvegardes` suspends it.

## Prompts and resources

The server also serves **twenty-two prompts**, one for each moment of an event's cycle, each following one of the nine menu entries (Home, Prepare, Files, Planning, Diagnostic, Rules, Share, Today, Administer) and citing current tool names only, never an old one (from `demarrer_une_nouvelle_edition` to `tirer_le_bilan_de_l_evenement`, by way of `construire_la_grille_de_creneaux`, `figer_le_referentiel`, `verifier_avant_resolution`, `publier_le_planning` or `tenir_le_jour_j`), each chaining the tools in the right order. They also say what the tools cannot do (importing the stand matrix, grid entry, marking an absence on the event day and validating a carpool remain on screen), without which the assistant would look for a tool that does not exist.

Each prompt takes an **optional** `edition` argument. Provided, the text names it; omitted, it asks the assistant to call `lister_editions` and retain the active edition, or to ask the question if there is none. In both cases it asks the assistant to pass the edition on every tool call. For `demarrer_une_nouvelle_edition`, it is the **source** edition of the duplication; for `tirer_le_bilan_de_l_evenement`, omitted, it has the assistant propose the edition whose event has just ended, rarely still the active one. `preparer_un_plan_de_repli` prepares the fallback as a dated consigne in the live edition, without duplicating it.

A prompt that names an outgoing tool requires explicit agreement and first has the assistant check that the edition is the active one; the assistant stops rather than activating one of its own accord. `tenir_le_jour_j` notifies through `publier_planning`: `envoyer_planning_animateur` would resend the plan already published, that is, the old one. The interface's MCP page reads them on `GET /api/mcp/prompts` instead of copying them: one text, one source. They are **not translated**. `McpPromptsWordingTest` re-reads their rendered text, with and without an edition: every snake_case word must name an announced tool or prompt (otherwise it appears, argued, in the test's exceptions), and any tool whose `openWorldHint` is true cited by a prompt that is silent on agreement makes the build fail. A renamed tool therefore cannot leave a prompt behind: the same test refuses any old name of `mcp-renommages.tsv` in a prompt or a resource, and wants every prompt in the vocabulary's table.

Two **resources**, to attach once to inform all following calls:

| URI | Content |
| --- | --- |
| `planning://contraintes` | the catalogue of constraints, generated from `ConstraintCatalog`: it can neither describe an absent rule nor forget one added the day before |
| `planning://vocabulaire` | stand, timeslot, seat, shift, day template, game category, version, edition, the order in which an event is prepared and the table that files each prompt under its menu entry; the only hand-written text |

## Old tool names

4.0 renames, groups or removes tools of 3.x ([ADR 0100](repo:docs/decisions/0100-convention-de-nommage-des-outils-mcp.md)). A grouped tool does under one name, with an argument (`vue`, `section`, `mode`, `decision`), what several used to do ([ADR 0127](repo:docs/decisions/0127-un-outil-mcp-par-objet-et-par-verbe.md)); its old names stay published until 5.0 next to the new one, their description starting with « Ancien nom »: those of the day review, those of the day templates, the recurring timeslots and the derivation, which the timeslot generator grouped ([ADR 0126](repo:docs/decisions/0126-un-generateur-de-creneaux-unique.md)), those of the plan, the solve, the rules and the swaps, those of the diagnosis and its reports, those of the parameters, the editions, the scenarios, the publication and the backups, those of the stands, the staff, the availabilities and the timeslots, and three names the closing audit brought back into the convention (`lever_consigne`, `definir_prereglage_consigne`, `configurer_collecte_disponibilites`). The `tension` mode of `analyser_marge` became the `tension` section of `consulter_risques`. The former solver settings tool is no longer published: the compute budget it set moved to the instance, and only the end-of-solve mail stays in `modifier_parametres` (section `calcul`). The three stand plage and model tools born in 4.0 are not kept: no published version carried them, and the [4.0 guide](../installer/passer-a-la-4-0.md) names them. This table is the rendering of the changed rows of [`mcp-renommages.tsv`](repo:src/main/resources/mcp-renommages.tsv); a name that is absent has not changed. When the file changes, `McpRenommageTest` fails and prints the table to paste between the two markers.

<!-- mcp-renommages:debut -->
| Name in 3.x | Name in 4.0 |
| --- | --- |
| `accepter_demande_echange` | `modifier_demande_echange` |
| `activer_contrainte` | `modifier_contrainte` |
| `activer_edition` | `modifier_edition` |
| `affecter_journee_type` | `modifier_gabarit` |
| `affecter_poste` | `modifier_affectation` |
| `ajouter_fermeture_stand` | `creer_horaire_stand` |
| `ajouter_horaire_stand` | `creer_horaire_stand` |
| `ajouter_ouverture_stand` | `creer_horaire_stand` |
| `ajouter_validation_journee` | `modifier_etat_journee` |
| `analyser_effectifs` | `consulter_besoin` |
| `analyser_enchainements` | `consulter_repas_pauses` |
| `analyser_faisabilite` | `consulter_besoin` |
| `analyser_marge` | `consulter_besoin` |
| `analyser_ouvertures_stands` | removed |
| `analyser_pauses` | `consulter_repas_pauses` |
| `analyser_typologies` | `consulter_besoin` |
| `appliquer_declaration_disponibilite` | `modifier_declaration_disponibilite` |
| `arreter_solveur` | `arreter_calcul` |
| `capturer_instantane` | `creer_version` |
| `changements_journee` | `comparer_journee` |
| `compacter_horaires_stands` | removed |
| `comparer_instantanes` | `comparer_versions` |
| `configurer_collecte_disponibilites` | `modifier_collecte_disponibilites` |
| `configurer_foire_echanges` | `modifier_foire_echanges` |
| `consulter_animateur` | `lister_animateurs` |
| `consulter_collecte_disponibilites` | `consulter_disponibilites` |
| `consulter_historique_ponderation` | removed |
| `consulter_instantane` | `consulter_version` |
| `consulter_parametres_legaux` | `consulter_parametres` |
| `consulter_parametres_notifications` | `consulter_parametres` |
| `consulter_parametres_qualite` | `consulter_parametres` |
| `consulter_parametres_solveur` | `consulter_parametres` |
| `consulter_stand` | `lister_stands` |
| `creer_creneaux_recurrents` | `appliquer_generateur_creneaux` |
| `creer_stand_complet` | `creer_stand` |
| `definir_journee_type` | `modifier_gabarit` |
| `definir_prereglage_consigne` | `modifier_prereglage_consigne` |
| `deplacer_affectation` | `modifier_affectation` |
| `desactiver_contrainte` | `modifier_contrainte` |
| `desactiver_edition` | `modifier_edition` |
| `diagnostiquer_grille_creneaux` | `diagnostiquer_creneaux` |
| `dupliquer_edition` | `creer_edition` |
| `edition_courante` | `lister_editions` |
| `effacer_horaires_stand` | `supprimer_horaire_stand` |
| `effacer_plages_stand` | `supprimer_horaire_stand` |
| `equite_planning` | `consulter_charge` |
| `etat_edition` | `consulter_edition` |
| `etat_planning` | `consulter_planning` |
| `etat_publication` | `consulter_publication` |
| `etat_sauvegardes` | `consulter_sauvegardes` |
| `expliquer_affectation` | `analyser_affectation` |
| `expliquer_echec_contraintes_dures` | `diagnostiquer_plan` |
| `figer_referentiel` | `modifier_phase_edition` |
| `generer_creneaux_depuis_stands` | `appliquer_generateur_creneaux` |
| `heures_travaillees` | `consulter_charge` |
| `importer_scenario_yaml` | `importer_scenario` |
| `lancer_solveur` | `lancer_calcul` |
| `lever_consigne` | `supprimer_consigne` |
| `lever_gel` | `modifier_phase_edition` |
| `lister_affectations` | `consulter_planning` |
| `lister_declarations_disponibilite` | `consulter_disponibilites` |
| `lister_demandes_covoiturage` | `consulter_disponibilites` |
| `lister_destinataires_publication` | `consulter_publication` |
| `lister_instantanes` | `lister_versions` |
| `lister_jobs` | `consulter_calcul` |
| `lister_journees_types` | `lister_gabarits` |
| `lister_kpi_historique` | `lister_mesures_edition` |
| `lister_validations_journee` | `consulter_etats_journee` |
| `materialiser_journees_types` | `appliquer_generateur_creneaux` |
| `modifier_parametres_legaux` | `modifier_parametres` |
| `modifier_parametres_notifications` | `modifier_parametres` |
| `modifier_parametres_qualite` | `modifier_parametres` |
| `modifier_parametres_solveur` | `modifier_parametres` |
| `modifier_poids_contrainte` | `modifier_contrainte` |
| `plan_formation` | `consulter_risques` |
| `previsualiser_application_journees_types` | `previsualiser_generateur_creneaux` |
| `previsualiser_creneaux_recurrents` | `previsualiser_generateur_creneaux` |
| `previsualiser_derivation_creneaux` | `previsualiser_generateur_creneaux` |
| `previsualiser_reconnaissance_journees_types` | `previsualiser_generateur_creneaux` |
| `realise_vs_planifie` | `comparer_realise_planifie` |
| `reconnaitre_journees_types` | `appliquer_generateur_creneaux` |
| `refuser_declaration_disponibilite` | `modifier_declaration_disponibilite` |
| `refuser_demande_echange` | `modifier_demande_echange` |
| `reinitialiser_donnees` | `supprimer_donnees_edition` |
| `relancer_animateurs` | `envoyer_relance_animateurs` |
| `renommer_edition` | `modifier_edition` |
| `resoudre_incremental` | `lancer_correction` |
| `restaurer_instantane` | `restaurer_version` |
| `resultats_animateur` | `consulter_planning` |
| `retirer_dates_journee_type` | `supprimer_gabarit` |
| `retirer_validation_journee` | `modifier_etat_journee` |
| `simuler_deplacement` | `simuler_affectation` |
| `simuler_swap` | `simuler_affectation` |
| `statut_solveur` | `consulter_calcul` |
| `suggerer_reparations` | `simuler_affectation` |
| `supprimer_creneau` | `supprimer_creneaux` |
| `supprimer_instantane` | `supprimer_version` |
| `supprimer_job` | `supprimer_calcul` |
| `supprimer_journee_type` | `supprimer_gabarit` |
| `supprimer_kpi_historique` | `supprimer_mesure_edition` |
| `synthese_affectations` | `consulter_planning` |
| `synthese_confirmations` | `consulter_publication` |
| `valider_creneaux` | `diagnostiquer_creneaux` |
| `valider_scenario_yaml` | `diagnostiquer_scenario` |
| `volumes` | `consulter_besoin` |
<!-- mcp-renommages:fin -->

## What has no tool

| What stays outside | Why |
| --- | --- |
| YAML scenario export | it contains first name, last name and date of birth, since it must be re-importable |
| Backups before emptying | each is a scenario export. `supprimer_donnees_edition` and the imports write one, but only an administrator reads, restores or deletes it, through the API ([ADR 0084](repo:docs/decisions/0084-sauvegarder-l-edition-avant-de-la-vider.md)) |
| SQL dump, PDF, ICS and CSV exports | all the personal data, or binaries meant for download from the interface |
| CSV import of volunteers | the spreadsheet carries last name, first name, date of birth and address: a tool that accepted it would let in through MCP what does not leave through it |
| `/api/planning/sample`, `/persisted` | a whole `PlanningEvenement`, several dozen MB with the personal data; `consulter_planning` (view `affectations`) covers the need while staying filtered |
| Staff espace and ICS subscription | the staff side, behind a personal token: MCP sees the requests and declarations on the admin side, never someone's espace |
| Rotating a staff member's espace token | it invalidates the link already printed on a distributed PDF, a consequence that cannot be undone from a conversation |
| Outgoing webhooks | a Slack, Discord or Matrix address is a secret, and a webhook opens a network exit from the server: configuring it from a conversation would be a ready-made exfiltration route. They are set in Settings › Instance |
| `/api/horloge` | the simulated clock moves the event-day mode, the frozen past and the staff espaces for everyone: an instance setting, not an organisation question |
| `/api/config`, `/api/auth`, `/api/branding`, `/api/mentions-legales`, `/api/debug` | public browser keys, admin session, branding and development sandboxes: nothing an assistant could do with them |
