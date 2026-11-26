---
twin: comprendre/taches-de-fond-et-notifications
---

# Scheduled jobs, mails, webhooks, action journal

What leaves the application without anyone clicking, and what records what everyone does: the four scheduled jobs, the two failure policies of mails, the templates, the webhooks, the weather alert, the frozen realised figures, the backup and the action journal. For each one you will know what guarantees that it does not write twice, does not write to the wrong edition, or is not forgotten when a route is added.

## The four `@Scheduled` jobs

| Job | When | What it does |
| --- | --- | --- |
| `BackupService` | `BACKUP_CRON`, 4:00 by default | `pg_dump` of the database ([the backup](#la-sauvegarde-seul-endroit-qui-écrit-sur-le-disque)) |
| `NotificationsPlanifieesService` | `NOTIFICATIONS_CRON`, every hour at minute 5 | The four night sends, the history purges |
| `WebhookDeliverer` | `WEBHOOKS_CRON`, every minute | Picks up the webhook deliveries whose retry is due |
| `WeatherAlertJob` | `METEO_CRON`, 6:00 by default | Queries Open-Meteo for the active edition |

Time zones follow `NOTIFICATIONS_TIMEZONE` (`Europe/Paris` by default). The `%test` profile switches the scheduler off: tests call the services directly. The frozen realised figure is no longer a job ([further down](#le-réalisé-figé-à-la-demande)).

## The night sends

`NotificationsPlanifieesService` is the single entry point of four sends: the day-before reminder (`RappelVeilleJob`), the reminder of the unconfirmed (`RelanceConfirmationJob`), the alert on swap requests that are sleeping (`AlerteEchangeJob`) and the reminder of the availability collection to invitees who have declared nothing (`RelanceCollecteJob`). Its cron is hourly because the send time is set per edition and a single job cannot leave at four different hours. Waking up often is safe only thanks to the idempotence below.

Like `backup`, it has a configurable cron and time zone, skips its turn on overlap (`SKIP`) and logs a failure without propagating it. It has two rules of its own:

- It serves only the active edition ([partitioning](cloisonnement-par-edition.md#une-seule-édition-parle-à-lextérieur)). A scheduler thread has no `X-Edition-Id`: the job enters the active edition through `EditionContext.executeIn`, and without an active edition nothing leaves. An edition that carried timeslots on the same day as another can therefore no longer remind the same people with two different schedules.
- Idempotence is reserved, not checked. `JournalNotificationsRepository` lays down a `(edition_id, type, cle)` key by `INSERT … ON CONFLICT DO NOTHING` and the message leaves only if the insert wrote a row. Asking "have I already sent?" before acting would let two runs both read "no"; here the database decides once. A failure between the reservation and the send costs a missing message rather than a duplicate, which suits a reminder that can also be read in the staff espace.

The same pass purges, with the duration of `JOURNAL_RETENTION` (90 days by default), the action history, the mail send results and the login journal, and, with its own thirty-day deadline, the webhook deliveries. A purge that fails lets a table grow, it does not fail the night.

## Two failure policies for mails

A mail that accompanies an operation already done (request submitted, decision taken, solve finished) must never make it fail: an SMTP outage does not cancel what took place. A mail that is the operation (the access code without which the staff member cannot get in, the schedule believed to be broadcast) must fail loudly so that the caller says who was not reached. The separation is structural:

- `MailService` carries only what an administrator explicitly asks for: the failure propagates.
- Everything else goes through `service/notification/`. The business code emits a fact (`Notification`, a sealed interface), `NotificationWriter` writes it by an exhaustive `switch`, and `NotificationDispatcher` observes it and carries the one `catch`.

So do not add a `notifierXxx` to `MailService`: that would put two opposite policies back behind methods of the same shape.

### The outcome comes back without changing the policy

Every e-mail leaves the application through `MailMetrics`, which counts it per template. When it is addressed to a staff member, the same point records the outcome in `envoi_mail` (`MailDeliveryRepository`): sent, or failed with a `MailFailureCategory` read from the exception (relay unreachable, authentication, address refused, temporary, other). Never the address. The identifier comes from the caller, through a parameter of `MailService` or through the `Notification.ToAnimateur` subtype. The failure is recorded then rethrown: `MailService` always propagates it and the dispatcher always swallows it.

Two callers needed the outcome without changing policy, the night jobs. They go through `NotificationDispatcher.deliver`, which sends immediately and **returns** the outcome instead of propagating it. On a failure, the reminder does not move the person to `RELANCE` and leaves an `ALERTE` alert on the home page, like the manual reminder.

The keys of `JournalNotificationsRepository` encode what follows:

- the key that the night shares with the manual action (`animateurId|publication`) is released on failure, so that "Remind now" finds it free;
- the night keeps a key of its own (suffix `|nuit`), one attempt per person and per publication, so as not to write again every hour to a relay that is down. Only a refused address sets the person aside until their record has been modified;
- the alert of a failure has its own key (suffix `|echec`), so that the "no address" warning raised earlier does not swallow it.

## The text of mails lives in templates

Each mail is a pair of Qute templates under `src/main/resources/templates/mail/`: `<nom>.txt` and `<nom>.html`, the latter built on `layout.html` (logo, palette, deployment name). Java names the template, decides the subject and passes its values; adding a mail means adding the two files. `MailTemplates` renders both parts and assembles the `Mail`:

- the text part is always sent, the HTML is an alternative;
- the logo travels as an inline `cid:` attachment, never as a remote image, which clients block and spam filters penalise. It comes from the `pdf-logo` key of `BRANDING_IMAGES` (bytes the application holds), not from the `logo` key (a URL that only a browser can resolve);
- the exhaustive `switch` of `NotificationWriter` guarantees that a new notification has its template.

Under Quarkus, it is the `Engine` validated at startup; in unit tests, `MailTemplates.standalone()` builds the same engine from the classpath, with the same settings. Wording assertions thus run without a container.

## Outgoing webhooks

`WebhookDeliverer` ([ADR 0074](repo:docs/decisions/0074-deux-jobs-planifies-de-plus-webhooks-et-meteo.md)) picks up every minute the deliveries whose retry is due, after 1 min, 5 min, 30 min, 2 h then 12 h, including after a restart. The first attempt leaves immediately, on a small thread pool of its own, never on the thread of the operation that caused the event. A lease in the database, taken row by row just before the attempt and required to record its outcome, prevents the first attempt and the retry from sending the same row.

The path of an event:

- `WebhookEmitter` observes the `Notification`s through an exhaustive `switch` (each case gives an event, or explicitly none) and the `PlanningPublished` event that publication fires. It is a plain `@Observes`, not `AFTER_SUCCESS`: without a JTA transaction, the annotation would defer nothing. It inserts one delivery per active subscribed webhook, in a `try/catch` that never propagates;
- an edition emits only if `OutboundEditionPolicy` allows it, and the question is asked again at each attempt: a retry or a "Resend" on an edition that has become inactive is abandoned. The failure of the nightly backup, an instance event, always leaves;
- `WebhookDeliverer` renders the body in the webhook's format (`WebhookFormats`, one pure method per format, HMAC signature for the generic format), passes the address to `OutboundGuard`, which resolves and checks it (https, ports 443 and 8443, no internal address except `WEBHOOKS_RESEAUX_AUTORISES`), then sends through the Vert.x client to the verified address. The response body is discarded.

Bodies carry counts and identifiers, never a person (`WebhookFormatsTest`). The configuration is the instance's: `webhook` and `webhook_livraison` have no `edition_id` and stay out of the SQL export. Secrets are encrypted there in AES-GCM with `WEBHOOKS_SECRET_KEY`, without which startup is refused as soon as a webhook exists. No MCP tool configures them. The guard is detailed in [outgoing calls](../installer/securite-appels-sortants.md).

## The weather alert

`WeatherAlertJob` enters the active edition each morning, if its alert is enabled, and lets `WeatherAlertService` do the rest. It suggests a consigne preset; it never lays one down.

- Dates. Those of the event in `[aujourd'hui ; + horizon]`, where "today" is that of `JourJClock`, intersected with what Open-Meteo really forecasts: from the real current day to that day plus 15. If the intersection is empty, nothing is queried and the screen says "Out of forecast".
- Places. The geolocated locations of the stands open on those days, read through `listSolvedStands()` (consignes included), rounded to 0.01° and deduplicated. A single request (`OpenMeteoClient`, the JDK's HTTP client) carries them all.
- Alert. Per date and per phenomenon (heat, gusts, storm), a `notification_planifiee` row is reserved under the key `date|PHÉNOMÈNE|palier`. Lower levels are reserved as plain locks: only a worsening forecast speaks again. An e-mail to the administrator (`Notification.WeatherAlert`) and the `meteo.alerte` webhook event follow. For today, a consigne can no longer be laid down: the alert gives the forecast with no suggestion or link.
- Silent service. It is never propagated: `meteo_etat` keeps "injoignable depuis…" (unreachable since) and a single warning leaves on the second day.

The settings (`parametres_meteo`) follow the edition: exported, copied by a duplication with the alert switched off. The state (`meteo_etat`) remains the machine's. `METEO_URL` is the operator's choice, the SSRF guard of webhooks does not apply to it.

## The frozen realised figure, on demand

No job measures the realised figure ([ADR 0092](repo:docs/decisions/0092-le-realise-se-calcule-a-la-demande.md), which replaces [0070](repo:docs/decisions/0070-un-troisieme-job-planifie-fige-le-realise.md)). `RealisedVsPlannedService` answers on request; at the first read made once the last timeslot of the edition has ended, and something is published, it writes into `kpi_realise` the measure per game category and for the event: counts, no person.

This write is a cache that writes only once: an edition that has its rows is left as is (`ON CONFLICT DO NOTHING` behind the check), so that `fige_le` is the first read after the event. A frozen past that is disabled is not cached and says so in the log; a write that fails never fails the read; re-freezing is an operator's SQL `DELETE` of the edition's rows, never a route. The table, with no foreign key, keeps the names of the edition and of the game categories: the measure survives the deletion of the edition.

The measure comes from `RealisedVsPlannedAnalyzer`, on top of `ChangementsJourneeService.compareSeats` (never a comparison of its own). Each elapsed day, whose last timeslot has ended, is compared with the publication in force at the start of its first timeslot. A publication that serves as the reference of an elapsed day refuses deletion (`PlanSnapshotService.delete`). The realised figure is read behind `RealisedSource`: the declared source (`DeclaredRealisedSource`) today, an attendance check-in tomorrow, without touching the calculation.

## The backup, the only place that writes to disk

`BackupService` backs up the database each night with a real `pg_dump` into `BACKUP_DIR` and keeps only the `BACKUP_RETENTION` most recent copies (10 by default). Three choices structure the package ([ADR 0015](repo:docs/decisions/0015-sauvegarde-par-pg-dump-restauration-hors-application.md)), and none is an oversight:

- restoring stays outside the application, an infrastructure operation with `pg_restore`: a "restore everything" button would be the very action the backup exists to undo;
- backups are never downloadable, because they carry the name, birth date and address of every staff member, minors included;
- the location and the retention are environment variables. The screen writes only a boolean, the one that suspends the job.

A failure is recorded and displayed, not propagated. `PgDump` is a bean of its own for a single reason: it is the part the tests replace, because nothing in the suite runs this binary, whose presence and version belong to the machine. Rotation, the half that deletes, is proved on a temporary directory (`BackupStoreTest`). The `pg_dump` client embedded in the image follows the PostgreSQL version ([overview](vue-d-ensemble.md#la-conteneurisation)).

## The action journal has three seams

Recording every action runs into the fact that there is no single point of passage. A REST request goes through the JAX-RS chain; an MCP tool call never sees it, because `quarkus-mcp-server-http` answers before it; a night job has neither a request nor a current edition. Three surfaces, three seams:

- `api/JournalActionFilter`, an unbound response filter (like `EditionHeaderFilter`). It sees all routes and writes the status on which the action ended: a refusal is a fact that is looked for more often than a success;
- `mcp/JournalOutilInterceptor`, bound to `@Journalise`, an annotation of its own. The classes that act across editions (`EditionMcpTools`, `SauvegardeMcpTools`) legitimately do not carry `@EditionCiblee`: reusing it had left six write tools without interception;
- `JournalActionService.recordSystemAction`, which the system jobs call when a fact has really occurred: night send gone out, weather alert raised, staffing check finished, realised figure frozen. Backups and the purge, which cut across editions, do not go through it: a row belongs to an edition, and filing them under whichever one happened to be current would be an invented fact.

What keeps the promise is therefore not discipline at the call site, but `service/journal/CatalogueActions`, the business inventory of actions (like `ConstraintCatalog` for rules), and the test that reads it. A stand created from a screen and a stand created by an assistant write the same row. `JournalCoverageStructurelleTest` fails on:

- any route or tool that writes without appearing either in the catalogue or in its list of reasoned exclusions;
- any `GET` that returns a file (a file type in its `@Produces`, the method's otherwise the class's, read by reflection; or a `Content-Disposition` written by the method body, `CsvDownload` included) and is neither catalogued nor excluded with its reason. A catalogued download is declared through `export(…)`, whose flag feeds the history's "Exports" filter; the espace's one is recorded only once identity is proven (`routeWhenProven`);
- any route of the catalogue or of the exclusions that no longer designates any method (this is how `EXPORT_BASE` was found silent after a rename);
- a class of journalled tools without `@Journalise`, and a described action that nothing reaches, because a dead inventory would promise the screen a filter that will return nothing.

Two things never enter the table: a value (a modification records the names of the fields changed, never their content) and an identity (an identifier alone; the name is joined at read time, so that a deleted record leaves a row that names nobody).

The author of a row is the account identifier (never its address), and `GET /api/historique?auteur=` selects it in the database, over the whole retention: an account or staff member identifier, `mcp`, or `systeme` for the night jobs. A value that is neither an author of the edition nor a known account is refused (400), never ignored. Before named accounts (migration V130), all administrators shared the configuration account and their rows say `admin` whoever was acting: they are not rewritten. The screen therefore displays "Author reliable since…", a date read from `flyway_schema_history` (installation of V130, `ReliableAuthorSince`), exact for the instance and earlier than any row of a new installation, rather than "the first row carrying an account", which would not exist as long as nobody has opened a named account.

The changed fields come from the service, the only one to know what changed: `ReferenceDataService` already reads the record as it was before the write, for the entry warnings, and deposits the comparison in `CurrentAction`, which the entry point picks up at the end of the call. It is an enrichment, not an obligation: a row for which nobody supplied the fields is written all the same.
