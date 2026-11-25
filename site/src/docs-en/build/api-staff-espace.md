---
twin: comprendre/api-espace-animateur
---

# The staff espace on the API side

You will learn how a staff member enters their espace, what the API shows them and what it lets them write: timeslot swaps, ICS subscription, availability declaration, carpooling, a reported impediment. Every bound that protects a route open without an admin session is given with the reason that justifies it.

## Entering the espace

The `/api/espace-animateur/{jeton}` routes are, with the ICS subscription, the only ones reachable without an admin session. The token, the link printed on the individual PDF, resolves the staff member and their edition by itself: `X-Edition-Id` is not read there. An unknown token answers `404`, never `401`, and the token of an edition that is not the active edition answers exactly the same, so as to say nothing about its validity ([decision 0072](repo:docs/decisions/0072-une-seule-edition-active.md)).

![Entering the staff espace and acknowledging receipt](repo:docs/diagrammes/espace-animateur.svg)

<sub>Source: [`diagrammes/espace-animateur.puml`](repo:docs/diagrammes/espace-animateur.puml).</sub>

The link is not enough: the espace serves the schedule as a download, so every route except `POST .../code` and `POST .../session` also requires the session of the `planning-espace` cookie, tied to the staff member the token resolves. The e-mail on the profile is the second factor: no address, no code, and `400`. Code requests are capped (`ESPACE_CODE_MAX_DEMANDES`, 3 by default, over `ESPACE_CODE_FENETRE`, 10 minutes) and beyond that answer `429`; opening the session clears the counter.

`POST /api/animateurs/{id}/token` rotates the token and immediately invalidates the distributed link: it is the action to take when an individual schedule has leaked. It does not touch the ICS subscription token, which is a separate key.

## What the espace shows

The espace shows the published plan, not the working plan: the staff member sees what they have been sent. A validated swap, an applied repair or a solve does not move their espace without a publication. A null `publieLe` means nothing has been communicated: the seats are empty and the interface says so. Swap suggestions are computed on that same plan, you swap only what you have been told.

A decision taken and not yet published must be nameable. `communiqueeLe` is null until the publication that announces it has gone out. The espace says it only for an acceptance, the one case where the status contradicts what the person sees; the administration screen says it of every decision, it is its remaining-to-publish. Without this state, "Accepted" sits above an unchanged schedule, and the staff member believes in an outage, asks again for a swap already granted or turns up at the wrong stand.

The place travels with the seat: `emplacementNom`, `emplacementLatitude` and `emplacementLongitude` accompany every seat and every break, read from the reference data at the time of reading (a hall that was renamed reads as renamed). They are null when the stand has no location, and the coordinates leave only complete (`Emplacement.isGeocoded()`, the question that the `GEO` line of the ICS and the PDF pin already ask): a latitude alone is not a position.

With the fair closed, submissions and cancellations are refused server side (`400`); `foireOuverte` serves only to display it, and a dated bound is applied the same way. `foireOuvreLe` distinguishes "not yet open" from "closed" (same boolean on the server side, opposite meaning for the reader); it is null when the fair is open and when the window has passed. The schedule stays visible and downloadable.

## Permanent ICS subscription

`GET /api/abonnements/{token}/planning.ics` serves the staff member's published schedule, rebuilt at every call, as `text/calendar`. It is the address you give once to your calendar: a republication appears at the next synchronisation. The direct download `GET /api/espace-animateur/{jeton}/planning.ics` remains a snapshot at one instant.

A calendar client has no cookie and cannot answer a challenge: requiring the session would make the subscription impossible. Rather than making it a full proof of access with no second factor for the espace token, the subscription has its own key, revocable separately ([decision 0019](repo:docs/decisions/0019-jeton-et-chemin-dedies-pour-l-abonnement-ics.md), [public tokens](../installer/securite-jetons-publics.md)).

| | Espace token (`access_token`) | Subscription token (`abonnement_token`) |
| --- | --- | --- |
| Path | `/api/espace-animateur/{jeton}/…` | `/api/abonnements/{token}/planning.ics` |
| Also required | the session opened by e-mail code | nothing |
| Opens | the whole espace: schedule, PDF, swaps, availability, writes | one document, read-only |
| Rotation | `POST /api/animateurs/{id}/token` (admin) | `POST /api/espace-animateur/{jeton}/abonnement` (the staff member) |

The `/api/abonnements/` prefix exists only for this route: it is the pattern that an access proxy excepts from its authentication, it must designate only that and no other route is tied to it. Two behaviours:

- unknown or revoked token, or finished edition: `404` with a fixed sentence ("Abonnement inconnu, révoqué, ou édition terminée"), the same in every case, which helps neither to guess a token nor to know whether it ever existed;
- nothing published: `200` and an empty calendar, never `404`. A client that meets a repeated `404` disables the feed, and the staff member subscribed before the first publication would have to subscribe again without being told.

The response carries `Cache-Control: private, no-cache`: the document is nominative, it must neither transit through a shared cache nor be replayed after a publication. `POST /api/espace-animateur/{jeton}/abonnement` (session required) revokes the current address and creates a new one (`{"abonnementToken": "…"}`); the link already printed on a PDF keeps working, and the espace's view carries the current token in `abonnementToken`.

## Swaps

`GET` and `PUT /api/echanges/configuration` set the window of the schedule swap fair.

| Field | Role |
| --- | --- |
| `foireOuverte` | The switch, and the master: a dated but closed window accepts nothing |
| `debut` / `fin` | Optional bounds, inclusive; empty = no bound |
| `ouverteAujourdhui` | Read-only: the switch and today's date within the bounds. Distinct from `foireOuverte` to say "open, but outside the period" |

An end before the start is refused (`400`) before the write: a refused configuration leaves the previous one in place.

![A swap request, from submission to decision](repo:docs/diagrammes/echange.svg)

<sub>Source: [`diagrammes/echange.puml`](repo:docs/diagrammes/echange.puml).</sub>

![Life cycle of a swap request](repo:docs/diagrammes/echange-etats.svg)

<sub>Source: [`diagrammes/echange-etats.puml`](repo:docs/diagrammes/echange-etats.puml).</sub>

A request is born `EN_ATTENTE_CIBLE` and enters the decidable queue (`PROPOSEE`) only once accepted by the targeted colleague; a refusal by the colleague is terminal, the admin never arbitrates. It is prevalidated against the hard constraints but saved whatever the verdict: the response carries `prevalidationOk` and the constraints not met. If the colleague also holds a seat on the timeslot, the two swap; otherwise they take over the seat. A directed swap gives up one timeslot and takes back another designated one.

Acceptance applies the swap as simulated, lays two `ANIMATEUR_CRENEAU` locks on the timeslot each one receives and writes to nobody: the working plan has changed, not the published plan, and the next publication announces it. The solver is not run again. A timeslot already started, given or received, refuses the swap at proposal as at acceptance (`400`, [decision 0044](repo:docs/decisions/0044-le-passe-est-fige.md)). A batch solicits each targeted colleague with a notification of its own, which counts only their own requests: nobody learns what was proposed to someone else.

### Who can replace me

`GET /api/espace-animateur/{jeton}/suggestions-echange?creneauId=X&standId=Y&plafond=N` serves the staff member who does not want a timeslot and has nobody in mind: they designate only their seat, and the assistant looks for swaps that hold. Same mechanics as the repair assistant (the solver's eligibility filter on both sides of the trade, one simulation per lead, verdict on the whole plan), a ceiling of 20 by default and 100 at most. Three families, carried by `nature`:

| `nature` | Effect for the requester |
| --- | --- |
| `LIBERE` | The colleague is free at that time: the timeslot leaves their hands |
| `CROISE` | The colleague works that same timeslot: the seats swap, the requester changes stand |
| `DIRIGE` | A seat of another timeslot comes back; the suggestion carries `creneauCibleId`, its date, its hours and its stand, enough to build the directed request as is |

The families are evaluated in alternation: with 150 free colleagues, the ceiling would go entirely to "we free you" without ever asking whether a Tuesday swaps for a Monday. At most two seats per colleague are proposed, so that the choice is about days and not about a person. `listeTronquee` says the search stopped at the ceiling. The response carries no score: a `HardMediumSoftScore` says nothing to a staff member, and the overall health of the plan must not leave through a token. The route is read-only, searches only for the caller's own seats (`400` otherwise) and only with the fair open.

## Availability declaration

The staff member declares their unavailability days and their game category wishes. Nothing is written to the reference data at submission: the declaration waits for an explicit decision from the admin.

![Availability collection](repo:docs/diagrammes/declaration-disponibilites.svg)

<sub>Source: [`diagrammes/declaration-disponibilites.puml`](repo:docs/diagrammes/declaration-disponibilites.puml).</sub>

It is the first write route opened from the espace, and three bounds hold it.

| Bound | What it prevents |
| --- | --- |
| Collection window closed by default | The fair is open as long as nobody has closed it, for compatibility; a route that writes is not opened by omission |
| One pending proposal per staff member (partial unique index) | Volume: sending a thousand times leaves one row |
| `ESPACE_DECLARATION_MAX_ENVOIS` per staff member and per window (20 by default) | Rate: beyond it, `429` and `Retry-After` |

A day outside the event dates and an unknown game category are refused (`400`); as long as no timeslot exists, no day is imposed, since collecting before the grid is the use case. Skills are not declared here: a self-declared skill feeds hard constraints, the validation stakes are not the same.

`GET` and `POST /api/espace-animateur/{jeton}/disponibilites`: the `GET` returns the state of the window, the days, the game categories, what the reference data says about the person, their pending proposal and their history, including with the window closed (you must be able to reread what you declared). The `POST` replaces the pending proposal and answers the same view. The form opens on the pending proposal, otherwise on the reference data: a blank page would mean "available every day". `collecteOuverte` is false in two opposite situations, not yet begun and finished, which `collecteDebut` separates: "come back from the 15th" is not "the collection is closed".

On the administration side, `GET /api/disponibilites` lists the edition's declarations, most recent first. `GET` and `PUT /api/disponibilites/configuration` read and write the window; `prevenirAnimateurs` also triggers the sending of the invitations. `POST /api/disponibilites/invitations` resends the links of an open collection (`409` otherwise, or inactive edition) without touching the window; `destinataires` is `NOT_YET_INVITED` (default) or `NOT_YET_DECLARED`. `POST /api/disponibilites/{id}/application` applies the whole proposal to the profile through `AnimateurService` (so `dataStale` triggers), `POST /api/disponibilites/{id}/refus` touches nothing and its comment is read by the staff member.

- All or nothing: the admin accepts or refuses the whole proposal, never line by line; the staff member sends back a corrected version as long as the window is open.
- Application claims the decision before writing the profile. In the other direction, a simultaneous refusal and application left the second one overwriting the staff member's days and then receiving a `409`, the reference data having moved for a refused declaration that was recorded. Claiming first turns the race into a clean `409`, profile intact.
- `prevenirAnimateurs` is an action, not a setting: it is never sent back. The invitation is essential in the first round and tiresome at reopening, so it is ticked each time. The response carries `invitation` (sent, `sansEmail`, failures), and the link points to the declaration tab, built by `ApplicationLinks`.
- The `PUT` is atomic: without `PUBLIC_URL`, an opening ticked "notify" answers `400` without saving anything, the collection stays closed. What fails afterwards fails per staff member and is read in `invitation` without being thrown: a missing address goes into `sansEmail`, a missing espace token into `echecs` with its cause.
- "This window" has no identity: `parametres_collecte` is one row per edition with no opening date. "Already invited" is therefore read in `envoi_mail` (template `invitation-declaration`, sends that went out only), and closing then reopening does not reset the count; "everyone who has declared nothing" is the way to resend to all.

## Carpooling

"I'm coming with…" (one to three teammates) does not travel with the declaration: the espace sends it through `POST /api/espace-animateur/{jeton}/covoiturage`, and the admin decides it under `/api/disponibilites/coequipiers`. Applying or refusing a declaration never touches it, and neither does the reverse: two independent decisions on two tables (`declaration_coequipier`).

| Bound | Reason |
| --- | --- |
| The collection window, the same as the declarations | A single period when the staff member writes before the build; closed, the tab can be read but sending answers `400` |
| One pending request per staff member | Sending again replaces; an empty list withdraws the request |
| A separate rate counter, at the same ceiling | Correcting oneself on one side does not use up the other's sends |
| `409` as long as a validated grouped arrival names the staff member | The validated group belongs to the organisation: it cancels it, never the espace |

`POST /api/disponibilites/coequipiers/{id}/validation` and `.../{id}/ecart` decide; the set-aside accepts an optional `reason` (500 characters at most), read by the staff member and absent from the action journal, which keeps only the id. Each decision goes out as a notification, so best effort: validation writes to each member, set-aside to the requester alone, with a link to the Carpool tab. A SMTP outage never cancels the decision.

`POST /api/disponibilites/coequipiers/{id}/annulation` targets the group, not just the request `{id}`: it deletes the adjustment and sets all the requests validated with it to `ANNULEE`, in one transaction. `404` for an unknown `{id}`, `409` for a request that is no longer validated, or whose adjustment has disappeared, and while a solve holds the edition (the solve in progress still scores the grouped arrival received). The trap is on the other side: `DELETE /api/contraintes-ad-hoc/{id}` and the `POST` that rewrites an adjustment answer `409` on a grouped arrival that a validated request supports (`issueDeCovoiturage` in the view), otherwise the group would believe in a car that no longer exists.

## Reported impediment

"I won't be able to be there": the staff member reports that they will be absent for a whole day or for a seat, through `POST /api/espace-animateur/{jeton}/signalements`; `DELETE /api/espace-animateur/{jeton}/signalements/{signalementId}` cancels their own. It is the espace's second write route, open for the whole edition, fair and collection closed included. Nothing is written to the schedule: the organisation notes or files it ([decision 0065](repo:docs/decisions/0065-empechement-signale-declaration-constatee.md)).

| Bound | What it prevents |
| --- | --- |
| A day on which the staff member holds a seat in the schedule communicated, not yet finished; for a seat, their own and not finished (`400`) | A report on what they do not see, or on the past |
| A single open report per person and per object (partial unique index); a day reported in full covers its seats (`409`) | Volume |
| `ESPACE_DECLARATION_MAX_ENVOIS`, counted apart from the declaration and before any validation | Rate: `429` and `Retry-After` |

The seat is designated by its natural key (`creneauId` and `standId`), never by the seat id that a solve renumbers. The reason is optional and in a closed list (`PERSONNEL`, `TRANSPORT`, `AUTRE`): no free field, since that is where a health reason that nobody asked for would be written. The organisation is warned by e-mail on a best-effort basis, a lost e-mail does not cancel the report, which the Today screen lists anyway. The staff member cancels theirs as long as it is open, never after (`409`).

The decision goes through `POST /api/jour-j/signalements/{signalementId}/traitement` ("Mark absent and replace") and `.../classement` ("File"). Handling reserves the report in the transaction that writes the absence: two decisions a second apart, or a cancellation that crosses the decision, leave a single winner and the other receives `409`, with no absence laid twice. If the absence is refused (lock, contradiction, solve in progress), the reservation is cancelled with it and the report stays open. The staff member learns the decision by e-mail, best effort: "taken into account" once the absence is written, "filed" without touching the schedule. The e-mail names the day and the seat, never the reason.

`GET` and `PUT /api/jour-j/signalements/configuration` disable the action per edition (table `parametres_signalement`, enabled by default). Switched off, it disappears everywhere: the espace no longer offers it (`signalementsActifs`) and refuses a submission, the reports received are no longer displayed, and cancelling, filing or handling them answers `400`. They stay in the database, and switching back on restores them as they were.
