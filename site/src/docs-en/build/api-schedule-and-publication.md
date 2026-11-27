---
twin: comprendre/api-planning-et-publication
---
# Consignes, publication and freeze: what the API guarantees

You will know how to lay down, simulate and lift a consigne (an official order, such as an arrêté, relayed to the whole event) through the API, preview then publish a plan without notifying everyone, read who received which version and who confirmed, freeze a family of the reference data, and read what moved on a day. The schema gives the shape of the calls; what follows is what it does not say.

## Consignes

A consigne closes a time band for **all** the stands on a date and reopens the ones you name on compensation windows. The model is described in [hours and consignes](horaires-et-fenetres-des-stands.md), the reasoning in [ADR 0043](repo:docs/decisions/0043-consigne-d-edition-fermer-une-bande-sans-rien-detruire.md).

![Prefill, simulate, lay down and lift a consigne](repo:docs/diagrammes/consigne.svg)

<sub>Source: [`diagrammes/consigne.puml`](repo:docs/diagrammes/consigne.puml).</sub>

**Future days only.** Laying down, changing and lifting refuse (`400`) a date that is not strictly after "today", that of the event-day clock (so of the date frozen by `/api/horloge` in development, demonstration and acceptance testing). `GET /api/consignes` returns `aujourdhui` so that the screen computes the same boundary as the server. A date already worked keeps for ever the consigne that governed it, and a date with no timeslot is refused ("rien à fermer ce jour-là", nothing to close that day).

**One request lays down, replaces or extends.** `POST /api/consignes` takes dates: a free date receives the consigne, a date already under a consigne sees it **replaced in place** (`dejaSousConsigne` in the preview), and extending an alert is the same call with more dates. A timeslot that the previous consigne had added is kept, seats included, when a window still requires it exactly, and removed otherwise (`creneauxARetirer`). The dates of a request are written in **a single transaction**: an arrêté over five days whose third date fails leaves nothing of the first two. Times are accepted as `HH:mm`; a missing end or one at `00:00` reads as "until midnight" and **is saved as an open end** (the band, a default window or an opening: `00:00` written, `null` read back, on the screen, in MCP and in scenario import alike).

**The preview counts as full validation, without writing.** `POST /api/consignes/apercu` replays the whole validation (band, reason, windows outside the band, known stands, headcounts under the stand's maximum, one stand per window) and returns one line per date: seats and minutes before and after, timeslots to add and to remove, shifts that lose their seats, stands entering and leaving, exceptions ticked, minors and adults available, validation withdrawn, locks and ad hoc rules that name a shift of the band, people of the saved plan seated in the band. `POST /api/consignes` returns that same preview, computed **before** the write, since once the timeslots are added nothing distinguishes them from the nominal grid any more. `creneauxDuJour` removes an ambiguity: without it, a date on which the edition has no grid would read as a date the consigne had emptied. The headcount is that of **the opening**, not of the stand: the same stand reopened in the morning at 2 and in the evening at 7 carries two openings, each with its own; when absent, it inherits the highest headcount the stand loses in the band, otherwise its minimum. Both writes are refused (`409`) during a solve of the edition, like those of the reference data.

**The preselection sets aside without forbidding.** `POST /api/consignes/preselection` proposes, ticked, the stands that lose minutes in the band. A stand that set its hours by hand for that day (`exceptionDatee`, with its `motif`) is set aside but can still be ticked: somebody decided something for that date, and the consigne does not silently contradict it. `ouvertures` says what the date's existing consigne already opens on that stand.

**Lifting deletes the added timeslots, seats included.** It is the only deletion of the feature, and it is announced: `POST /api/consignes/levee/apercu` names the shifts that go and counts the people seated on them, and the next publication says them "removed" since the published snapshot, like any shift that has disappeared from the grid. The nominal shifts of the band never moved: they get their seats back on lifting, and the stability of the published plan gives the afternoon back to its holder at the next solve.

**The lift preview warns when frozen days empty the week** ([ADR 0089](repo:docs/decisions/0089-la-levee-avertit-quand-les-journees-figees-vident-la-semaine.md)). A lift gives the day its nominal grid back, but a full solve freezes every seat a lock covers and every past day. When the lift adds seat-minutes and at least one staff member available that day already holds a frozen seat on each of the six other days of their ISO week, the preview counts them (`animateursSansJourLibre`), names the days locked `JOUR` to reopen (`joursADeverrouiller`) and says how many of them that frees (`animateursLiberes`). It is a warning, never a refusal: the day may still hold without them. Nobody is named, only counted, and no solve is launched.

**Laying down or lifting withdraws the review validation** of the days touched, locked or not, where a solve keeps it on a locked day ([ADR 0039](repo:docs/decisions/0039-validation-de-relecture-distincte-du-verrou.md)). A lock freezes seats, and those of the band no longer exist. The preview says so (`validationRetiree`).

### What the consigne takes with it

- **`POST /api/planning/reset` also erases the consignes and presets** of the edition, like everything a solve receives. The database export and import carry them.
- **Presets are copied on edition duplication, consignes are not.** A preset ("Heatwave plan") describes the shape of the event; a consigne belongs to the days of an edition, like the plan. The name of the preset carried by a consigne is a name, not a key: deleting the preset does not rewrite the history of the dates it governed. Its `id` is optional at creation (`POST /api/consignes/prereglages`, as in a scenario file): the server mints one.
- **The reason reaches the person whose day the band empties.** The "days with modified hours" lines of the publication e-mail are read from the dates on which the person holds a seat in the published plan **or in the one it replaces**; the staff espace and the individual PDF carry the reason on their rest days too. Without that, somebody whose arrêté closed the whole day would read a bare "shift removed".
- **`GET /api/consignes` also carries the indicators**: one line per day under a consigne (nominal seats and seats under consigne, minutes closed, minutes reopened, staff concerned), read from the seats and not from the entry. `animateursConcernes` counts the people of the saved plan who hold a seat that day. The plan's KPI derives `journeesSousConsigne` and `heuresFermeesParConsigne` from it. `GET /api/journees-types` returns `datesSousConsigne`: those dates are neither "out of line" nor touched by an application, since the timeslots added by a consigne belong to the consigne.

### Meal windows specific to the consigne

A consigne, the request and the preset can carry their `repas`: `midiDebut`/`midiFin`, `soirDebut`/`soirFin`, `coupureMinutes`, `justification`. Each missing field keeps the edition's value. A window is given with both its bounds or neither, and **must contain the break** (the consigne's, otherwise the edition's): an evening 19:00-19:30 under a 60 min break is refused (`400`), because the solver discards a window too short for its break as untenable, and the date would end up with no rule at all instead of a stricter one. The `justification`, in business terms, is mandatory as soon as a field is filled in (`400` otherwise) and is shown next to the day.

The override is **dated by construction**: it governs only the consigne's date. The solver, the Breaks, Staffing need and Catering screens and the grid check read the consigne's window on that day, the edition's on other days, and it leaves with the consigne when it is lifted. `GET /api/parametres-legaux` never moves: nothing to put back, nothing to forget. Only the meal windows can be overridden, because the meal break is the organiser's rule; the legal caps (daily rest, durations, days per week) stay out of a consigne's reach. The use case is the heatwave evening: an 18:00-22:00 compensation would ask for two people per seat under the edition's 19:00-21:00 window, whereas "evening 18:00-22:00, the teams eat during the closure" brings the need back to one.

## Publication

`GET /api/planning/publication` says who would be notified and what they would read, without sending anything; `POST /api/planning/publication` publishes.

![Preview then publication of the schedule](repo:docs/diagrammes/publication.svg)

<sub>Source: [`diagrammes/publication.puml`](repo:docs/diagrammes/publication.puml).</sub>

Publishing marks the working plan as **the communicated plan** and writes only to the people whose timetable has changed since the last publication. The count is **per person**: renaming a stand wakes nobody, swapping two shifts wakes exactly two people. Each recipient comes with the exact sentences of their e-mail (`changements`), which the administrator rereads before anything is sent ([ADR 0011](repo:docs/decisions/0011-publier-plutot-qu-envoyer-a-tous.md)).

The preview is read on demand: nothing is pushed or polled. The only safeguard is about concurrency: publishing during a solve would freeze a plan about to be rewritten, hence `409`. The same goes when nobody is concerned: that is the point, not an error to work around.

`premiereDiffusion` distinguishes somebody to whom nothing has ever been sent: their whole schedule is the news, not a list of corrections. As long as `jamaisPublie` is `true`, **the staff espace is empty**: the application cannot pretend to have communicated a plan it has not sent.

A send that fails **does not come back** in the next count, because the counter measures what changed and not what was delivered. The report names the ones missed, and `POST /api/planning/envoi/animateur/{animateurId}` is the catch-up: it sends the **published** plan, and answers `400` as long as nothing has been.

### Two send registers

Each send, from a publication or a resend, leaves its state in `envoi_planning`, dates and states only (`ENVOYE`, `ECHEC`, `SANS_EMAIL`, `EXCLU`), never content. On a failure a short cause is added (`ADRESSE_REFUSEE`, `BOITE_PLEINE`, `SERVEUR_INJOIGNABLE`, `AUTRE`) rather than the mail server's message, which readily quotes the address. The preview derives `envoisEnEchec` from it (the people whose **last** send failed), and `GET /api/editions/courant/etat` no longer says "done" on the publication line while any remain: nothing changed for them, they simply received nothing.

| Register | Question | Where it is read |
| --- | --- | --- |
| `envoi_planning` | which version did they receive? | the Share screen |
| `envoi_mail` | could they be reached? | the Staff page |

`envoi_mail` keeps the outcome of **every** e-mail addressed to a staff member (access code, invitation, reminders, day-before reminder, swaps, carpooling, absences, schedules), written at the single exit point of e-mails, with a category read from the SMTP code: `RELAIS_INJOIGNABLE`, `AUTHENTIFICATION`, `ADRESSE_REFUSEE`, `TEMPORAIRE`, `AUTRE`. An extended code takes precedence over the reply code, and only a `5.1.x` designates the recipient: a `553 5.7.1` is the sender being refused. It is read through `dernierEnvoi` in `GET /api/animateurs/confirmations`, next to the confirmation status without ever mixing with it, and through `echecsEnvoi` in the summary. `ENVOYE` means "handed to the relay", nothing more, and nothing at all under `MAIL_MOCK=true`, which `GET /api/config` reports (`mailMock`) so that the screen writes "simulated".

`GET /api/planning/publication/etat` returns **who received which version**, one line per staff member of the edition, whether or not anybody is left to notify: the version announced (`version.numero`, the rank of the publication in the edition), the last send (`envoi`: nature, state, cause, date), the day-before reminder and the reminder read from `notification_planifiee`, the acknowledgement, `aPrevenir`, `differe` and `joursAPrevenir`. Nothing is recomputed there: each column reads the store that already answers its question.

### Publishing for a few people

`{"cibles": ["id", …]}` restricts a publication to the named people ("Tell the 2 people" after an accepted swap, or a replacement on event day). All the other people concerned are **deferred** ([ADR 0047](repo:docs/decisions/0047-differer-le-message-d-une-personne-sans-la-perdre-de-vue.md)): the capture stays global, their marker does not move, they come back in the next count. Targets that name nobody concerned leave the publication with no recipient: `409`.

### The POST body

The `POST` takes a JSON body `{"cibles": ["id", …]}` and requires `Content-Type: application/json`: the list is of free length and a staff member's id comes from the client, so neither a separator nor a URL is a safe place to carry it. An empty body, or one without `cibles`, publishes to everybody concerned. The 3.x `exclusions` field is gone; a client still sending it gets a `200`: the field is **ignored** (nobody is left out of a general publication) and the answer carries `avertissements`, a list of sentences saying the exclusion no longer exists, until 5.0. The home alert `EXCLUSION_PUBLICATION_RETIREE` closes person by person, once a publication has really notified that person (successful send).

A deferred person keeps their comparison marker (`animateur.plan_notifie_id`): the next publication names them again, with the gap accumulated since what they actually received, and `reporte` is `true` on their preview line.

Each preview line carries what is needed to sort and filter without reading French: `ajouts`, `retraits`, `deplacements`, and `mineur`. The latter flags the same shift, on the same stand, slid by a quarter of an hour at most, with no swap decision pending. It is a reading criterion, never an automatic exclusion: deciding not to notify somebody is an act. Each line also carries `jours`, the dates of its changes (`ChangementVacation.touches`), which are the same rule `ChangementsJourneeService` applies to keep a person on a day: the day filter of Share and the changes of a day count the same people, which the test `ChangementsJourneeServiceTest#theDiffuserDayFilterAndTheChangesOfTheDayCountTheSamePeople` holds.

### The recipients trace

`GET /api/planning/publication/export` returns the preview list as CSV, one line per person, the sentences in one cell. `GET /api/planning/publication/destinataires` returns the trace: who was notified of what and when, including those who could not be reached (`SANS_EMAIL`, `ECHEC`). With no parameter, that of the last publication, or an empty list if there has never been one.

The trace stores the two halves of the message separately: `changements` for the timetable, `demandes` for swaps. They do not age the same way: a timetable sentence says what was announced and always will, whereas "your request is awaiting a decision" stops being true as soon as the organisation decides, without any publication going out.

The staff espace is a second reader of this trace. `GET /api/espace-animateur/{jeton}` returns, in `changements`, the sentences of the last line that concerns the token's holder, those of their timetable and nothing else, with `changementsLe`, the instant of that send. They are never recomputed: it is what was read, not a diff redone on today's reference data, so a stand renamed since does not rewrite what was announced. The line is served whatever the fate of the send: with no address, or after a failure, the espace is the only place where those sentences still exist. Swap sentences are not replayed there: the Swaps tab carries the live status of each request, which a copied waiting sentence would contradict. A first broadcast (`premiere_diffusion` on the trace) returns an empty list.

**A swap decision is not announced when it is taken** but by the publication that carries it: accepting a swap changes the working plan, not the published plan, and notifying straight away would promise a schedule the espace does not show yet.

## Changes of a day

`GET /api/journees/{jour}/changements?reference=publication|resolution` says what moved on a day, read twice from the same facts: by seat (stand, hours, holder before and after: `nouveau`, `retiré`, `remplacé`, `horaires modifiés`) and by staff member, in the very sentences the publication puts in the e-mail. The second reading **is** the publication diff filtered on the date, never a comparison redone: the Day tab and the Publication screen cannot tell two stories about the same day. This diff is computed without the first-broadcast framing: the tab reads changes, never a schedule announced in full. The reading by seat matches on the natural key (stand, day, hours); the seats of one cell are interchangeable.

A person **kept on their stand at other hours** (a timeslot trimmed by a consigne) is an `HORAIRES` line (`heureDebutAvant`/`heureFinAvant` carry the reference's hours, `heureDebut`/`heureFin` those of the plan), counted in `horairesModifies`, and not a seat removed plus a new seat. The rule is that of the reading by staff member: same day, same stand, exactly one seat on each side for that person. Two trimmed seats on one stand remain two removals and two new ones, on both sides, so that the counters of the two readings say the same thing.

Two references, and **neither is invented**:

- `publication` compares with the last published snapshot;
- `resolution` compares with the automatic snapshot taken just before the last **effective** solve, the one that really replaced the saved plan (`snapshot_avant_solve_id`, kept by `planning_resolution`). A solve interrupted by the server or refused on its problem captures the plan then writes nothing: it does not move the reference, otherwise the tab would compare the plan with itself. A restore or an import between two solves does not touch it either: what they changed reads as changed since the solve.

The purge of automatic snapshots spares the one the reference designates; a manual deletion brings you back to "nothing to compare". With no parameter, the publication when there is one, the solve otherwise, and the response says which (`reference`). When the reference does not exist, the response carries `referenceDisponible: false` and counters at zero that mean nothing: "nothing to compare" is not "no change". A day the grid does not carry answers empty, not `404`. It is a read, with no journal trace; the MCP tool `comparer_journee` returns the same thing by ids.

## Acknowledgement

`POST /api/espace-animateur/{jeton}/confirmation` ("I have read it and I will be there") is the only thing the espace writes about the schedule itself. Clicking twice answers `200` with the **first** date, never an error: it is a normal gesture on a refreshed page. Confirming before any publication answers `409`, there is nothing to confirm. `GET /api/animateurs/confirmations` returns one line per staff member, `NON_VU` included, for the column of the Staff page, and `GET /api/animateurs/confirmations/synthese` the same answers in three numbers (confirmed, reminded, silent), counted among the people who have a seat on the published plan, with the date of that publication.

| Status | What it says |
| --- | --- |
| `NON_VU` | nothing came back: nobody has been asked yet, or the plan has moved since |
| `CONFIRME` | the button was clicked, with the date |
| `RELANCE` | a reminder went out, overnight or by hand, and remains unanswered |

The absence of a row in the database **is** `NON_VU`: the reset is a deletion, so there are never two ways of writing "this person has not answered". `affecte` distinguishes somebody who has no seat in the published plan: they are not silent, they were asked nothing, and the column does not count them among the people to remind.

**Republishing resets to `NON_VU` only the people whose timetable really changed** (`PublicationDiffService`). Somebody who is only notified of a swap decision reads the same days as before: asking them again to confirm would make the button a reflex rather than an answer.

### A reminder never goes out twice

`POST /api/animateurs/relances` (body `{ "animateurIds": […] }`) sends the confirmation reminder without waiting for the night, with the same text and the same template, and **reserves the same key** as the overnight job (`animateurId|date de publication`) in the scheduled-sends journal, before the e-mail goes out. Somebody reminded by hand is therefore not reminded the next night, and somebody the night has already written to is returned in `dejaRelancesPourCettePublication` instead of being written to a second time. The key carries the date of the edition's last publication: it changes for everyone at each republication, including those to whom nothing was resent. It is the status that closes that door: a person already in `RELANCE` is refused, and only a republication that really moves their timetable puts them back to `NON_VU`, which reopens the reminder.

The report carries only ids, one list each: `envoyes`, `dejaConfirmes`, `sansEmail`, `dejaRelancesPourCettePublication`, `echecs`, `sansPoste`. A failed send is **counted**, not swallowed (the gesture is explicit, unlike the overnight notification). It returns the key and leaves an alert in the feed of alerts and messages of the home page: the person remains silent and trying again is possible, by hand or overnight. A record with no address leaves the same trace. The route answers `400` as long as nothing has ever been published, or if an id designates nobody, in which case nothing goes out, not even to the valid ids that came before it.

## Phase of the edition and reference data freeze

The phase of the edition decides which reference data families are frozen ([ADR 0115](repo:docs/decisions/0115-la-phase-de-l-edition-remplace-le-gel.md)): it is read in `GET /api/editions/courant` (`phase`, `phaseDepuis`, `structureDebloquee`) and declared by `PUT /api/editions/courant/phase` (body `{ "phase": "CALCUL" }`), going back included. Declaring the phase the edition is already in does not change its date. Publishing moves the edition to `PUBLIEE` by itself. In `PUBLIEE` only, `PUT /api/editions/courant/deblocage-structure` reopens the structure and `DELETE` freezes it again; elsewhere, `409`. Entering a phase and publishing both refreeze an unblocked structure.

`GET /api/editions/courant/gel` lists the state of the four families as the phase decides it. The 3.x `PUT` and `DELETE /api/editions/courant/gel/{famille}` routes remain until 5.0, marked deprecated: they translate the gesture into a phase, so freezing **one** family in Preparation freezes **all** of them (move to `CALCUL`).

A write that a frozen family covers is refused with **`409`**, like a stale write. They are told apart by the `code` of the body: `MODIFICATION_CONCURRENTE` calls for "reload or overwrite", `REFERENTIEL_FIGE` calls for "change the phase or unblock the structure" and carries `familles`, the families involved ([the conventions](api-conventions.md#lire-un-refus)). Three consequences cannot be read in the schema:

- **the refusal applies to the field, not to the route.** A `PUT /api/stands/{id}` that changes only the name passes under a stand freeze; the same `PUT` that changes a headcount is refused. The comparison is made against the stored record, so bulk editing (one `PUT` per row with the whole record) refuses exactly the rows it really modifies;
- **a file is refused entirely**, before the first row: the staff import as soon as it changes the skills of somebody already registered (a newcomer keeps theirs);
- **whatever replaces everything is refused as soon as any family is frozen**: scenario import, restoring a copy taken before a wipe, the reset, and a **solve whose problem the client supplies** (`POST /api/solve`, `POST /api/solve/async`), whose landing rewrites the stands, timeslots and skills that problem carries. A solve built by the server from the edition is never refused.

Two cases remain open. The import of a SQL dump is not refused: it replaces the whole database and restores the phase each edition carried. Consignes remain open too, as their grid write does not go through the methods the freeze guards.
