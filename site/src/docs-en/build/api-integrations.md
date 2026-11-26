---
twin: comprendre/api-integrations
---
# Integrating the application: journal, alerts, webhooks, exports and backup

You will know how to read the action journal and what it refuses to contain, how to set the overnight sends and the weather alert, how to plug an outside tool into the webhooks while checking their signature, and the API-side boundaries of branding, exports, backup and scenario import.

## Action journal

`GET /api/historique?limite=200` returns what was done in the current edition, from newest to oldest, in pages of 500 lines at most.

| Parameter | Effect |
| --- | --- |
| `nature=exports` | returns only the files that left the application |
| `nature=donnees` | returns only the changes a solve would receive |
| `depuis`, `jusqua` | bound a period (two ISO-8601 instants), **`depuis` excluded, `jusqua` included** |
| `avant` | cursor: the id of the last line displayed, for the next page |

The search by `nature` is **done by the database** and covers the whole retention, not just the last page of all actions mixed together: an export buried under two hundred more recent changes is still found. The period bounds are those of `GET /api/historique/changements`, so `nature=donnees&depuis=<fin de la résolution>` returns exactly the lines the Solver screen counted (successful ones only: a refused write changed nothing). A period is not capped. The screen knows a next page exists by asking for one more line than it shows. A cursor whose line has disappeared returns an empty page rather than a refusal: only the overnight purge removes a line, and it takes all the older ones with it.

`GET /api/historique/actions` returns the inventory of the actions the application can describe, so that the screen offers a filter it did not invent. Its `export` flag is the classification that `nature` applies, maintained by `CatalogueActions` and not guessed from the spelling of the code.

The journal is read-only, and that shape is final: a journal you can edit is not a journal. Nothing is deleted; the table is bounded by a **retention** (`JOURNAL_RETENTION`, 90 days by default) applied by the overnight task.

### What a line carries, and what it never will

A line says when, who, the action and its sentence in French, what it targeted, **the names of the fields a change actually changed** (for the referentials CSV archive, the names of the referentials taken) and whether it succeeded or was refused, with its HTTP code. The author is:

| Author | When |
| --- | --- |
| `ADMIN` | an action from the administration |
| `ANIMATEUR` | the call **proved** it was one: a session opened with the e-mail code, a correct code exchanged for it, an address attested by the proxy, or the dedicated subscription token |
| `ANONYME` | a route opened without that proof |
| `ASSISTANT` | an MCP call |
| `SYSTEME` | the overnight task |

The author is decided on that proof, never on the HTTP code: a wrong code is a 400, a throttled code request a 429. The espace link alone does not prove who holds it; the staff member designated by the token remains the target of the line.

Two things do not appear there, and that is the contract:

- **no value**: "nom, email" says what moved, never what it became;
- **no identity**: the table stores an identifier; `acteurNom` and `entiteNom` are resolved from the reference data **at read time**, so a deleted record leaves a line that no longer names anyone.

### What is traced

Writes and data outputs (PDF, ICS and CSV exports, the referentials archive, the YAML scenario, the database dump, e-mail sends) are traced, never plain reads. A `POST` that only computes (preview, simulation, prior analysis of a file) is declared **untraced, with its reason**, in `CatalogueActions`.

A download is a `GET` and is traced all the same: it carries people's data. The `EXPORT_*` actions (administration) and `TELECHARGEMENT_ESPACE_*` (a schedule downloaded by the staff member from their espace, attributed to that staff member) do **not** count in `/api/historique/changements`: taking out a copy makes no solve stale.

An espace download is traced **only once the identity is proven**. Refused before that (unknown token as a 404, missing session as a 401), it writes nothing: it is a free read anyone can repeat, and one line per attempt would hand the table over to the first comer. Its successes, and the refusals met by an authenticated staff member, remain traced. Two families of `GET` stay untraced, with their reason: the sample files, which carry nobody, and `/api/abonnements/{token}/planning.ics`, re-read on its own by the calendar every few hours.

## Scheduled notifications

`GET` and `PUT /api/parametres-notifications` set the delays of the overnight sends **for this edition**. Whether they go out at all is not set here: only the active edition sends ([ADR 0072](repo:docs/decisions/0072-une-seule-edition-active.md)).

| Field | Role |
| --- | --- |
| `heureRappelVeille` | local time from which the day-before reminder may go out. **23:00 at the latest**: the task runs once an hour and the window closes at midnight (after that, "tomorrow" would be wrong). A later time would fall between two runs and never go out, so it is refused (`400`) rather than silently accepted |
| `delaiRelanceHeures` | silence tolerated after publication before a reminder (1 to 720) |
| `ancienneteEchangeJours` | how long a swap request waits before an alert (1 to 60) |

Activation is explicit because an `Edition` carries **neither dates nor an "ongoing" flag**: no task can guess that the staff of the 2025 edition are not the ones to notify for tomorrow. A new, duplicated or imported edition is born inactive. The machine's rhythm is not in the API: `NOTIFICATIONS_CRON` and `NOTIFICATIONS_TIMEZONE`, see [operations](../installer/exploitation.md). How the tasks work is described in [background tasks and notifications](taches-de-fond-et-notifications.md).

`GET /api/alertes` returns what these tasks left on the desk, most recent first. `limite` applies **per type** (50 by default, 500 at most): a record with no address produces an alert every evening, and a global cap would let a handful of unreachable people bury the `ALERTE_ECHANGE` ones, the only ones that call for a **decision** and for which this screen is the sole exit point. Reading is all it does: an alert is closed by dealing with what it flags. The journal stores only an `animateurId`; `nomAffiche` is resolved at read time.

**Every send is reserved in the database before it goes out**, on a primary key `(edition_id, type, cle)`: the task inserts and sends only if the insert wrote a row. Running every hour, restarting midway or replaying by hand therefore never writes to anybody twice, and a swap request is flagged **only once**, whatever its age afterwards.

## Weather alert

`GET` and `PUT /api/parametres/meteo` carry the edition's settings: `actif`, `horizonJours` (1 to 14), the temperature and gust thresholds, thunderstorm, and a suggested consigne preset per phenomenon. To these are added the state of the last query (`state.outcome`: `READ`, `OUT_OF_FORECAST`, `NO_PLACE`, `UNREACHABLE`), `serviceEnabled` (`METEO_ENABLED`) and `editionMayEmit`. The `PUT` carries `modifieLe` as a precondition, like a reference data row: `409` if somebody saved in the meantime. Deleting a suggested preset leaves the suggestion empty.

`POST /api/parametres/meteo/test` queries **now**, with the edition's thresholds, whether the alert is active or not, and summarises the forecast by date (maxima, thresholds crossed), without raising an alert or writing anything. It is the only way to try the alert on a simulated date: the morning task takes "today" from the event-day clock but keeps only the dates Open-Meteo actually forecasts (the next sixteen days), so a test run frozen far from the real date answers `outOfForecast`.

An alert never lays down a consigne. It is a line of `GET /api/alertes` (`type` `METEO_ALERTE`, key `date|PHÉNOMÈNE|palier`) whose `lien` leads to `/regles?onglet=consignes&date=…&nouvelle=1&prereglage=…`: the form opens filled in, and the admin rereads the stands and saves it themselves.

- A consigne already in place on the date is named by the alert instead of a suggestion.
- An alert on **today** (by the event-day clock) suggests nothing and has no `lien`: a consigne can only be laid down on a future day.
- A stable forecast raises no alert; a forecast that worsens by one step (two degrees, ten km/h, hail with the thunderstorm) raises a new one.

The MCP tool `consulter_alertes_meteo` reads the same alerts. The choice of these two tasks: [ADR 0074](repo:docs/decisions/0074-deux-jobs-planifies-de-plus-webhooks-et-meteo.md).

## Outgoing webhooks

`/api/webhooks` (administration) configures what the application announces to an outside tool. The configuration is **per instance**, not per edition: the payload says which edition it speaks of, and only the active edition emits ([ADR 0072](repo:docs/decisions/0072-une-seule-edition-active.md)). The formats and the SSRF guard are in [security of outgoing calls](../installer/securite-appels-sortants.md).

| Event code | When | `donnees` |
| --- | --- | --- |
| `planning.publie` | a publication went out | `instantaneId` (the id of the published version: the key keeps its 3.x name, which outside integrations read), `destinataires`, `envoyes`, `sansEmail`, `echecs`, `differes`, `planningsChanges` |
| `echange.soumis` | a swap request reaches the desk | `nombre` |
| `echanges.en_attente` | the overnight task flags requests that are sleeping | `nombre`, `ancienneteMaxJours` |
| `disponibilites.declaree` | a declaration is waiting to be applied | `joursIndisponibles`, `souhaits` |
| `resolution.terminee` | a solve is finished (screen, MCP or queue) | `score`, `faisable` |
| `sauvegarde.echec` | the overnight backup failed (instance event, `edition` is `null`) | `tentativeLe`, `raison`, `echecsConsecutifs` |
| `meteo.alerte` | the morning query raised alerts | `nombre`, `alertes`: for each, `date`, `phenomene` (`chaleur`, `rafales`, `orage`), `valeur`, `seuil`, `palier`, `lieux` (a number), `prereglageSuggere` |
| `test` | "Send a test" | none |

An n8n flow filters on these codes: they are never renamed. **None carries a person** (no name, no address, no token), only counts and identifiers. The `raison` of a failed backup is a **category** ("pg_dump a échoué", pg_dump failed; "pg_dump n'a pas terminé dans le délai imparti", pg_dump did not finish in time), never the message of `pg_dump`, which names the host, the user and server paths; the detail stays on the Settings screen.

### Generic format and signature

```http
POST /webhook/planning HTTP/1.1
Content-Type: application/json
X-Planning-Evenement: planning.publie
X-Planning-Livraison: 7d1e…-…
X-Planning-Horodatage: 1752343380
X-Planning-Signature: sha256=2c6d5dd0…

{"id":"7d1e…","evenement":"planning.publie","survenuLe":"2026-07-12T18:03:00Z",
 "edition":{"id":"2026","nom":"Année 2026"},"donnees":{…},"lien":"https://…/publication"}
```

- `X-Planning-Signature` is `sha256=` followed by the hexadecimal HMAC-SHA256 of `horodatage + "." + corps`, with the secret shown **once** when the webhook is created or its secret regenerated. The signed body is, byte for byte, the one received: verify on the raw body, never on re-serialised JSON.
- `X-Planning-Horodatage` is in Unix seconds and is part of what is signed. **Refuse anything more than five minutes off**: a body replayed later no longer passes.
- `X-Planning-Livraison` (and `id` in the body) identifies the delivery, **not the attempt**: a retry and a "Resend" keep the same one. It is the receiver's idempotency key.
- `survenuLe` is the **real** time of the event, even when the instance simulates another date (demonstration event-day mode).

Verification in Python:

```python
import hashlib, hmac, time

def verifier(secret: str, horodatage: str, signature: str, corps: bytes) -> bool:
    if abs(time.time() - int(horodatage)) > 300:
        return False
    attendu = hmac.new(secret.encode(), horodatage.encode() + b"." + corps,
                       hashlib.sha256).hexdigest()
    return hmac.compare_digest("sha256=" + attendu, signature)
```

In n8n, the *Webhook* node must keep the raw body (the *Raw Body* option), then a *Code* node; the `crypto` module needs `NODE_FUNCTION_ALLOW_BUILTIN=crypto` in n8n's environment:

```javascript
const crypto = require('crypto');
const horodatage = $input.first().json.headers['x-planning-horodatage'];
const signature = $input.first().json.headers['x-planning-signature'];
const corps = Buffer.from($input.first().binary.data.data, 'base64');
const attendu = 'sha256=' + crypto.createHmac('sha256', $env.PLANNING_SECRET)
  .update(horodatage + '.').update(corps).digest('hex');
const frais = Math.abs(Date.now() / 1000 - Number(horodatage)) <= 300;
if (!frais || !crypto.timingSafeEqual(Buffer.from(attendu), Buffer.from(signature))) {
  throw new Error('Signature invalide');
}
return [{ json: JSON.parse(corps.toString('utf8')) }];
```

### Delivery and journal

Each event creates one delivery per active subscribed webhook. A first attempt goes out immediately, off the operation's thread. After that, a network error, a `408`, a `429` (whose `Retry-After` is honoured, capped at 12 h) or a `5xx` are retried after 1 min, 5 min, 30 min, 2 h and 12 h: six attempts at most, then `FAILED`. A name that does not resolve (DNS) is retried the same way.

Any other response, a redirect included, is final: `ABANDONED`. The same goes for an address the guard refuses, a secret the key no longer opens, and a delivery on an edition that is no longer active: every attempt, retry and "Resend" included, asks the question again.

| Route | Role |
| --- | --- |
| `GET /api/webhooks/{id}/livraisons` | the journal: the last 100, kept 30 days (status, attempt, code, duration, a fixed sentence on error, never the response body) |
| `POST /api/webhooks/livraisons/{id}/renvoi` | puts a delivery back at the start of its schedule, under the same identifier |
| `POST /api/webhooks/{id}/test` | sends the `test` event and waits for the response, with no retry: the only synchronous call (`maxAttempts` is `1`) |
| `POST /api/webhooks/{id}/secret` | regenerates the HMAC secret |

Secrets are never read back. The HMAC secret is in the response of the call that generated it (`POST /api/webhooks`, `POST /api/webhooks/{id}/secret`, or a `PUT` that switches a webhook to the generic format), and the address of a Slack, Discord or Matrix webhook comes back reduced to its host. On a `PUT`, an address or token left empty **keeps** the previous one.

## Branding and legal notice

`GET /api/branding` and `GET /api/mentions-legales` are **public**, like `/api/config`: an application that cannot say its name before login greets its visitors with an empty bar, and a legal notice reserved for logged-in users misses its readers (the person deciding whether to trust the site, the staff member whose link has expired). The frontend reads the branding **once, before it starts**.

- `productName` is never empty.
- **An empty `logoUrl` means "no logo"**, never "the default logo": one instance per client, and no client inherits another's branding.
- The legal fields are the empty string when the corresponding key of `LEGAL_MENTIONS` is missing, and the page then shows **what is missing** rather than an invented publisher. They live in configuration and not in the code: the repository is public, and for a publisher who is a natural person the address and registration number are personal data.
- `mesureAudience` and `suiviErreurs` are true when the Cloudflare token and the Sentry DSN have a value, the one the beacon and the SDK actually read, not the mere fact of having set the environment variable. The privacy policy hangs the paragraphs that name Cloudflare and Bugsink on them: without that, a deployment that leaves both empty would announce two processings that do not take place. These flags go through this endpoint and not through `/api/config`, which carries the same keys: its read fails silently to a disabled configuration, which would **hide** those paragraphs where the tools really run. Here, a failure shows an error instead of a silently wrong page.
- `demoInstance` says whether the instance declared itself a demonstration (`LEGAL_DEMO_INSTANCE`). The page does not display it: `scripts/verifier-deploiement.sh` reads it to know whether empty mandatory notices are a defect. It asks the instance rather than the `.env.prod`, which sees neither a value set elsewhere nor the spellings (`True`, quotes) that the application accepts.

## Exports

Generation happens on the server. The schedule is sent in the request body (`POST /api/planning/export/pdf/animateur/{animateurId}`, `POST /api/planning/export/ics/animateur/{animateurId}`, `POST /api/planning/export/pdf/all`, `POST /api/planning/export/ics/all`, `POST /api/planning/export/bundle/all`), **except the global export** `GET /api/planning/export/pdf/global`: a schedule the size of the event weighs several megabytes as JSON, which the caller should not have to upload to get a document back. That export therefore reads the same source as the calendars. The formats and the end-of-event archive: [exports and archives](exports-et-archives.md).

Sending by e-mail is the individual resend `POST /api/planning/envoi/animateur/{animateurId}`, which answers with a report naming those with no address and the failures. The collective send is the publication ([publication API](api-planning-et-publication.md)).

## Automatic backup

The overnight backup is set by the host, not by the API; two routes, mostly read-only.

`GET /api/backups` returns the full state: location and retention (environment variables), switch, next run, report of the last attempt and the list of dumps present (name, size, date). `PUT /api/backups/active` with `{ "active": false }` **suspends** the overnight backup, `true` resumes it. It is the only value the screen writes.

Three absences are deliberate and lasting:

- **No download of a dump.** It carries the names, birth dates and addresses of all staff, minors included: the browser is not a distribution channel for that.
- **No restore.** It is an operation on the PostgreSQL database, done by the operator with `pg_restore`; see [backup and restore](../installer/sauvegarde-et-restauration.md).
- **No writing of the location or the retention.** A disk path and the number of copies a volume holds are decided with that volume (`BACKUP_DIR`, `BACKUP_RETENTION`), not from a screen.

Like the SQL dump, these routes ignore `X-Edition-Id`: a backup takes the whole instance.

## Scenario import

The import is a diff, not a blind replacement. The stands and staff of the file are updated **in place**: a staff member who is kept keeps their `access_token` (printed links survive), their sessions, their requests outside the replaced timeslots and their e-mail if the file carries none. Only those absent from the file are deleted, in cascade. By contrast, the solved schedule, the locks, the ad hoc constraints and the solve trace are **always** erased; the timeslots are replaced by those of the file.

`GET /api/reference-data/impact-import` quantifies this scope **before** importing: it is what the confirmation dialog displays, which also records a snapshot when a solved schedule exists. The optional sections of the file (`parametresLegaux`, `parametresSolveur`, `typologies`, `contraintes`, `edition`) are applied if present and left as they are otherwise. `edition:` routes the import to the designated edition, created empty if need be. The import routes are `POST /api/reference-data/import-scenario` and `POST /api/reference-data/import-scenario-fichier`; the format is described in [the YAML scenario](scenario-yaml.md).

The validator, `POST /api/reference-data/valider-scenario-fichier`, checks the structure without importing anything and **always answers 200**, including for an empty or malformed file: the verdict is in the body. It does not replace the cross-reference checks of a real import. The other import formats are in [CSV imports](imports-csv.md) and the recovery of an edition in [restoring an edition](restaurer-une-edition.md).

## Two traps

**A dump taken before migration `V52` can no longer be re-imported.** The dump names its columns one by one, and `V52` renamed `animateur.jeton_acces` to `access_token`: the import refuses with `column "jeton_acces" ... does not exist`. Keeping both names alive would make permanent the gap that `V52` removes. To recover such a file, replace that string before loading it: it is its only occurrence.

**`POST /api/planning/reset` empties the current edition**, not the database. The other editions are not touched.
