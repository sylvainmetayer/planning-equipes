---
twin: comprendre/api-jour-j-et-animateurs
---
# Event day, bench and wall display through the API

You will know what the routes that serve the day of the event itself answer: the Today screen and its absences, the bench, the simulated clock and the wall display. The capacity reads are in [feasibility, fragility and breaks on the API side](api-faisabilite-et-pauses.md), the staff espace in [the staff espace on the API side](api-espace-animateur.md).

## What is a day in progress

The boundary of a day is neither midnight nor a fixed operating hour: it is the first timeslot actually scheduled. The day in progress at a given instant is the most recent one whose first timeslot has started and whose last has not ended.

- A timeslot belongs to the day its start opens, and to that day alone. A 22:00-02:00 timeslot belongs to the evening that opens it.
- At 1:00 in the morning, while that timeslot is running, the day in progress is the previous day: `GET /api/jour-j` with no parameter answers `date` = the previous day.
- Before the first timeslot or after the last, no day is in progress and the calendar date answers.
- A date with no timeslot has no day: the screen answers `creneauxDuJour: 0` and empty lists, and `POST /api/jour-j/absences` is refused there (`400`).

`date` and `maintenant` default to the day in progress and the server clock (`JourJClock`, frozen date included). `maintenant` is a full instant (`AAAA-MM-JJTHH:MM`), because a day that runs past midnight puts the two on different dates: at `2026-07-09T01:00`, the day looked at is `2026-07-08`. The response returns `maintenant`, so that the screen states the server's time and not the phone's. Reading a day that is not the day in progress reads it in full.

## The Today screen and absences

`GET /api/jour-j` returns, read-only, the remaining timeslots, the staff on duty (with the phone number from the record), the empty seats, the day's absences and reports, the open stands, the wall display alerts, `aPrevenir` (the recipients of the next publication, deferred ones included) and the swaps to arbitrate. An empty seat is `nouveau` when the published plan had somebody on it, and `resteDuCreneau` carries the remainder of a split seat.

`POST /api/jour-j/absences` declares an absence (`{ "animateurId": "A1", "raison": "…" }`, `creneauId` optional): one `INDISPONIBILITE_FORCEE` per remaining timeslot, and the seats held on those timeslots are emptied. `DELETE /api/jour-j/absences/{animateurId}` cancels it, one timeslot if `creneauId` is given, the whole day otherwise. The replacement goes through `POST /api/jour-j/postes/{posteId}/suggestions`: the repair assistant answers there on the saved plan, prepared as the write that follows will read it (disabled constraints, weights, edition quotas), so it proposes no candidate the write would then refuse.

| Rule | Reason |
| --- | --- |
| The scope of an absence is the set of remaining timeslots whose **end** is later than the reference time (the timeslot in progress is one of them) | Finished timeslots were worked: turning them into unavailabilities would make the schedule lie about what happened |
| A staff member is "already absent" only if an unavailability covers a timeslot still ahead | A morning unavailability, entered weeks earlier, does not prevent marking them absent for the rest |
| All or nothing: contradictions and locks are checked over the whole scope before the first write | A refusal on the third timeslot must not leave the first two written |
| Cancelling does not give the seats back | Who holds a seat is a decision: assuming the previous holder gets it back would erase a replacement applied in the meantime |
| Cancelling deletes only what the screen wrote, recognised by the id derived from the (staff member, timeslot) pair, and only the exceptions that target that staff member alone | Otherwise it would take away a long-standing unavailability entered in Manual adjustments, and silently widen what the next solve can do |

Two refusals, both `400`: an `AFFECTATION_FORCEE` on a targeted timeslot (the contradiction of [`ContrainteAdHocContradictions`](exceptions-ad-hoc.md), whose message names the two exceptions), or a seat to free that is covered by a lock (the message names the stand and the time and invites you to lift it first). Each exception written carries `raison`, `creeParUtilisateurId` (the admin account of the session, `jour-j` by default) and `creeLe`, and appears on the Manual adjustments page. Nothing here launches a solve or sends an e-mail: the screen shows the count for the [publication](plan-verrous-et-publication.md) and points to its button.

## Bench

`GET /api/banc-de-touche/{creneauId}` (parameters `standId` and `posteId`) says who is not on duty on a timeslot and why they could not be. `GET /api/banc-de-touche` with no timeslot answers on the first timeslot the plan fills and names it in `creneauId`. The response reads the last saved plan, with no solver. The screen sends `standId` and `posteId` on every call, empty when they do not apply: the server reads an empty value as absent.

Having nothing to say is not an error. The reference data legitimately holds more timeslots than the plan (no stand open, or a timeslot derived after the last solve): a `404` then opened the screen on "Unknown timeslot" for a perfectly real timeslot. The `statut` field distinguishes three cases, and `404` stays reserved for a timeslot absent from the edition.

| `statut` | Meaning |
| --- | --- |
| `NO_PLAN` | Nothing is saved (also the case when no timeslot is filled) |
| `NO_SEAT` | The plan exists, but with no seat on this timeslot or stand |
| `EVALUATED` | A seat was probed: the list and its reasons |

Outside `EVALUATED`, `posteCibleId`, `standCibleId` and `animateurCibleId` are null, the lists empty and `seatStarted` false. On `EVALUATED`, `seatStarted` says that the seat's timeslot has already started in the sense of the [frozen past](repo:docs/decisions/0044-le-passe-est-fige.md) read on the server clock (always false when `PASSE_FIGE=false`): the dialog then offers "Place" to nobody, as the write would refuse it. `creneauxAvecSieges` lists the timeslots the plan fills, so as to propose only answers that are non-empty in advance. The seat evaluated is `posteCibleId`: the one `posteId` designates, otherwise the first free seat of the timeslot (restricted to `standId` if provided), otherwise its first seat, in which case the question becomes "who could replace them?" and `animateurCibleId` names the holder.

The reasons are not rewritten here. Each carries the `contrainte` of a rule actually applied, with the `niveau`, the `categorie` and the `description` of `GET /api/constraints`. They come from two sources and no third:

- what the (seat, staff member) pair decides on its own (unavailability, stand reserved for adults, public holiday, night, minors' caps for an isolated timeslot) is read in the solver's eligibility filter, the one used by the repair assistant;
- what depends on the rest of the plan (daily and weekly caps, rest, breaks, overlaps, supervision of a minor, missing appraisal) is obtained by placing the seat on each candidate and asking the constraints what got worse. No threshold is copied.

**Worse than the empty seat, never than its holder.** Compared with the holder, a candidate who breaks the rule the holder already breaks leaves the total unchanged, and the screen answered "nothing against them": a staff member already on duty elsewhere at that hour came out "Available". The seat is therefore emptied before probing. Each line carries two verdicts:

| Field | Meaning |
| --- | --- |
| `disponible` | No hard rule opposes the assignment, empty seat as the reference. It is what the screen displays |
| `degradeLePlan` | The plan's hard score would really be worse than today |

`disponible` implies `!degradeLePlan`, never the reverse: taking over the seat of somebody who already breaks a rule can break another without the plan getting worse. `delta` quantifies the gap to the current plan, which means probing the holder too.

The guarantee with respect to the repair assistant comes down to one implication: every `disponible` staff member is a candidate that `suggererReparations` proposes on the same seat (`CreneauAvailabilityCoherenceTest`). The converse is deliberately false: the assistant's second filter reads the seat's gaps match by match, and reproducing it here would duplicate that analysis. All applicable reasons are listed, not only the most blocking one: knowing that lifting the unavailability would leave three others behind helps decide whether to negotiate.

"Missing skill" is not an exclusion: appraisal is a medium constraint (`appreciationIncompatible`), hence a `MEDIUM`-level reason on a line that can remain `disponible`. A reinforcement (all-rounder game category) never carries one, since `Animateur.hasCompetenceFor` judges them competent everywhere.

## Freezing today's date

`/api/horloge` replaces the date the server takes for "today", to test event day outside the event. The screen is the "Simulated date and time" card of Settings, Instance tab.

`GET /api/horloge` returns `{ "dateDuJour", "heureDuJour", "modifiable" }`, `PUT /api/horloge` freezes the date and, if given, the time. An empty or `null` date hands control back to the machine's clock, time included. A time without a date answers `400` (on the machine's date, it would slip by a day at midnight).

The `PUT` answers `400` on any instance launched neither with `quarkus:dev` nor with `HORLOGE_SIMULEE_AUTORISEE=true`, a variable that is `false` by default and should stay so in production: a mock that can be switched on during a real event would make the event-day screen lie. The safeguard is on the server, on the write; `modifiable` only serves to hide a control that cannot be used. It also applies to reads: the value lives in an ordinary row that paths outside the application can bring in (`pg_dump` restore, copied volume, `psql` session, see [decision 0015](repo:docs/decisions/0015-sauvegarde-par-pg-dump-restauration-hors-application.md)). Where the simulated clock is not allowed, the row is therefore ignored. The application dump does not include it: `horloge_jour_j` is outside the tables of `/api/database/export`, and the import refuses it (`400`).

What the frozen date replaces:

- the day looked at by the event-day screen, its remaining timeslots, the scope of an absence;
- the boundary of consignes;
- the past the solver freezes ([decision 0044](repo:docs/decisions/0044-le-passe-est-fige.md)): the seats of timeslots started at that instant are taken from the saved plan and pinned, which makes it possible to rehearse a solve "during the event" out of season;
- the day marker of the staff espace, computed in the browser: `GET /api/espace-animateur/{jeton}` carries `dateDuJourFigee` and `heureDuJourFigee` (null where the simulated clock is not allowed), which the page substitutes for the phone's date.

A fixed time gives the same 14:30 on every reload; without a time, the timeslots pass behind as the afternoon goes on. Deliberately out of reach: minor or adult status (derived from the birth date against the timeslot's date, a mock could make a minor work at night), the timestamp of what is written (`creeLe`, publication and snapshot dates), the overnight backup, the solver jobs and the espace codes. The setting is persisted in the `horloge_jour_j` table, not partitioned by edition (it is the server's clock), to survive a hot reload.

## Wall display

The control room screen (`/mural/:jeton`) reads `GET /api/mural/{token}` every minute, with no session: the token is the key and opens only that read ([decision 0053](repo:docs/decisions/0053-affichage-mural-par-jeton-dedie.md), [security](../installer/securite.md)). The links are managed under `/api/affichage-mural`, behind the admin session.

- The token is returned only once, in the `201` response of `POST /api/affichage-mural`: the database keeps only its SHA-256 fingerprint. The QR code is requested at creation (`POST /api/affichage-mural/qr-code`, a grid of modules the screen draws as SVG); later there is no address left to encode. `DELETE /api/affichage-mural/{id}` revokes.
- An unknown token and a revoked token answer the same `404`, word for word.
- The plan read is the persisted plan, not the published plan: a replacement made on event day appears on the next read, without republishing.
- "Now" is the server time, and the day follows the rule above: at 1:00, the 22:00-02:00 shift is still the previous day's, and in progress.
- The whole day travels, not only the shift in progress and the next one: the screen chooses them against `now`, advanced by the time elapsed since the read, so a shift ends at its minute without waiting for the next read. `?impression=1` shows the whole day.
- A shift groups the seats of a stand that share the same effective window; an end that is not after the start falls on the next day.
- The alerts are the free seats of a shift in progress or starting within two hours, and the breaks without relay for the day not yet finished, read in the same report as the Breaks screen.
- A consigne "until midnight" has no end: `closedUntil` is `null` and the screen writes "midnight".
- A link restricted to certain locations stays so: if those locations are deleted, the screen shows nothing, never the whole edition without warning.
- `lastAccessAt` is stamped at most once a minute: it says that a screen is reading, not how many times.
- Importing a SQL dump deletes all the links: they do not travel in the export and the import empties the `edition` table, whose cascading deletion takes them away. After an import, each screen displays "unknown or revoked link"; recreate the links from Settings.

Printing on the admin side goes through no token: `GET /api/affichage-mural/apercu?date=AAAA-MM-JJ` returns the same view, under the admin session, with all the stands and the full names that the Planning page already shows (the day in progress without `date`, `400` on an invalid date). It lives under `/api/affichage-mural` and never under `/api/mural/`: that prefix is what a proxy lets through without authentication and it must name only one route.
