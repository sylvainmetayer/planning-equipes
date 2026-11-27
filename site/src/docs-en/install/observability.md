---
twin: installer/observabilite
---

# Connect error tracking and metrics

You connect the instance to error tracking ([Bugsink](https://www.bugsink.com/), Sentry protocol and SDK), to optional audience measurement ([Cloudflare Web Analytics](https://www.cloudflare.com/web-analytics/)) and to a Prometheus, knowing what each one sees and what it must never see. The first two components are **disabled** until you give them a key, and in development and test.

## What always runs

Two components have no switch.

The **health probes** `/q/health/live` and `/q/health/ready` (SmallRye Health) are public and say nothing about connection details. Readiness rests on a single check, the Flyway history (`FlywayMigrationsReadinessCheck`), whose first role is to report the database unreachable: the history cannot be read without it. A pending or failed migration also makes the instance not ready, but that is defence in depth (an interrupted migration already prevents startup) against a history modified under a running instance. Once the history is read as up to date, the verdict is kept: the probe answers `UP` without opening Flyway again (a database that goes down afterwards no longer turns it `DOWN`); while `DOWN`, it is re-read at most every 5 s. The data source check supplied by Quarkus is **turned off**: when failing, it copies the driver's message into the public response, and with it the database host. Their day-to-day use is in [Monitoring the instance](surveillance.md).

The **operating metrics**, in Prometheus format, are served on a separate port that the production stack does not publish: see [Metrics](#métriques).

The error-tracking wiring can be read in `SentryInitializer`, `GlobalExceptionMapper` and `app/core/observability.ts`. What follows covers what the code does not say.

## The non-negotiable point: the staff espace token

The espace URL carries the access token, **a unique identifier of a person,
often a minor**. The wall display's URL also carries its own, which opens the day's
named schedule to whoever holds it. Three safeguards, for both:

- the Cloudflare beacon is **not loaded at all** on `/animateur/*` or
  `/mural/*`;
- error reports are scrubbed **on both sides**, for pages
  (`/animateur/…`, `/mural/…`) as for API calls
  (`/api/espace-animateur/…`, `/api/mural/…`). In the browser,
  `maskTokensEverywhere` walks the **whole** report (`beforeSend`,
  `beforeBreadcrumb`) rather than a list of fields: masking only
  `request.url` and `data.url` let through the two likeliest cases:
  an internal page change (`data.from` / `data.to`) and an HTTP failure whose
  message contains the URL. On the server, `maskTokensInReport` does the same;
- `Referrer-Policy: same-origin` closes the outbound-links channel.

This is what makes true the sentence that the privacy policy addresses to
the staff. **Any new way of sending data out must preserve this property.**

## What a solve that dies used to tell nobody

A solve is launched with a `202`: the request is accepted at once, the
solve runs separately, and its failure arrives later **in the job**. It
therefore never goes through `GlobalExceptionMapper`, and with nothing more it
would never be reported. It is the only category of failure that the organiser sees
on their screen and nobody else ever sees.

`SolverJobService.reportFailure` reports it where the exception is still
in hand, with its stack, and covers at once the screen, the API and MCP. The event carries the job type, its
identifier, the edition and the scope. At worst (tracker absent or down),
the log line remains: a reported failure must not become a lost failure.

A **cancelled** job is not a failure and is not reported.

## Metrics

Sentry says what broke; metrics say how things are running: how long
a solve takes, how many wait behind it, whether mails
go out, whether the nightly backup ran. Micrometer, Prometheus registry
(`quarkus-micrometer-registry-prometheus`), always on.

### Where, and why not elsewhere

The endpoint is **`/q/metrics` on the management interface**, port **9000**:
a second HTTP server, separate from the application port. It has **no
authentication** (a Prometheus scraper does not open a session) and its
only protection is being reachable only from the internal network:
`docker-compose.prod.yml` publishes only `127.0.0.1:8080`, the reverse proxy
knows only that port, and the image does not declare 9000 with `EXPOSE` (a
`docker run -P` would publish it). On the application port, `/q/metrics` answers
`404`; `MetricsEndpointTest` holds both halves.

A Prometheus added to the production stack reads it at
`http://app:9000/q/metrics`. Outside a container (`quarkus:dev`), the interface
listens on `localhost:9000`.

The `/q/health/*` probes and the API documentation (`/q/openapi`,
`/q/swagger-ui`) **stay on the application port**: the management interface
would otherwise take them with it, and the compose `healthcheck`, the deployment
scripts and external probes look for them there. The opposite choice,
everything on 9000, would move as one block, probes included.

### What is measured

| Metric | Type | Labels | What it says |
| --- | --- | --- | --- |
| `planning_solver_duration_seconds` | timer | `type` (`full`, `incremental`), `outcome` (`completed`, `failed`, `refused`, `cancelled`, `interrupted`) | duration of a solve, from start to its final state; `refused` = business refusal (nothing to schedule), not a failure |
| `planning_solver_failures_total` | counter | `type` | solves dead on an unexpected error, exactly those `reportFailure` sends to Sentry |
| `planning_solver_queue_size` | gauge | — | jobs in the queue (in memory: empty after a restart) |
| `planning_solver_active` | gauge (0/1) | — | a job holds the solver |
| `planning_mail_sent_total`, `planning_mail_failures_total` | counters | `template` (`code-acces`, `planning-publie`, `rappel-veille`…) | mails handed to the SMTP server, or refused, notifications included (their failure is swallowed without being lost for all that) |
| `planning_backup_duration_seconds` | timer | `outcome` (`success`, `failure`) | duration of each `pg_dump` |
| `planning_backup_last_success_timestamp_seconds` | gauge | — | date of the last successful backup (epoch seconds) |
| `planning_backup_size_bytes` | gauge | — | size of that last dump |
| `http_server_requests_seconds` | timer | `uri` (route template), `method`, `status`, `outcome` | API latency, supplied by the extension |

On top come what the extension and Timefold publish by default: JVM, Vert.x,
the connection pool (`agroal_*`, switched on by
`quarkus.datasource.metrics.enabled`) and the solver counters
(`timefold_solver_*`, with no solve identifier).

Three behaviours to know when writing an alert:

- **on restart, counters start again from zero**, following the Prometheus
  rule: `increase()` and `rate()` account for it. The date of the last
  successful backup, on the other hand, is read back from the database at startup, and the size from the
  dump still on disk;
- **without `BACKUP_DIR`, the two backup gauges do not exist**, and an
  instance that has never yet had a successful backup does not have them either:
  an absent date rather than a zero that would say "last backup in
  1970";
- **`MAIL_MOCK=true` still counts**: in acceptance testing, "the mail would
  have gone out" is what you want to see.

### Bounded cardinality: the token, again

No label carries an edition, a staff member, a token, an address or
an identifier: every label is a closed set. An edition is not an
operating dimension: you monitor the instance's solver.

For `uri`, the requirement is the same as for error reports: **the
espace token and the ICS subscription token travel in the path**, and
a label that copied the path would send them to monitoring. Three
settings, in `application.properties`:

- a REST route is labelled by its **template**, never by the path
  called: `/espace-animateur/{jeton}`, `/abonnements/{token}/planning.ics`
  (the extension writes it without the `/api` prefix);
- a 4xx/5xx response with no recognised route (connection refused, rate
  limiter, a bot trying `/wp-admin`) is labelled `UNKNOWN`
  (`suppress4xx-errors`), a 404 `NOT_FOUND`;
- everything the Angular frontend serves, including the espace link
  `/animateur/<jeton>` and the hashed files, is folded into a single
  value, `/{frontend}` (`match-patterns`).

The number of `uri` values is therefore bounded by the number of routes (about
two hundred). The extension's ceiling, 100 by default, would silently drop
all routes beyond the hundredth: `max-uri-tags` sets it to 500.

`MetricsEndpointTest` calls the two token routes, the espace link and an
unknown path with dummy tokens, then checks that none appears in
the output, that every `uri` is made only of literal segments and
`{gabarits}`, and that no forbidden label exists. **Any new
metric must preserve this property.**

## Two configuration traps

**Audience measurement is off until you give it a token.** The `%prod`
profile used to supply a fallback (the publisher's account)
and any instance deployed without thinking of it therefore sent its audience to that
account, while declaring a transfer outside the EU in its own legal
notice. The default has been empty since; setting `CLOUDFLARE_WEB_ANALYTICS_TOKEN` is
now the only way to switch measurement on.

**The Sentry SDK is loaded only if a DSN is configured**, by a dynamic
`import()` inside the condition. It weighs 462 kB (130 kB transferred):
including it in the initial bundle made every visitor pay for it, including
on `/animateur/:jeton`, a public page often opened from a phone,
and including on a deployment with no DSN.

**The SDK's data collection is set explicitly** (`dataCollection`). Since
version 11, leaving the option empty attaches the user, cookies, headers
and HTTP bodies to errors; the configuration restores version 10's
restrictive baseline: no user, no cookie, no body, headers
and URL parameters filtered.

## Variables

| Variable | Default | Use |
| --- | --- | --- |
| `SENTRY_DSN` | empty | Bugsink DSN, or any Sentry-compatible endpoint. Empty = disabled on both sides |
| `SENTRY_ENVIRONMENT` | `local` | Label attached to every error |
| `CLOUDFLARE_WEB_ANALYTICS_TOKEN` | empty | Token of the Cloudflare account that receives the audience data. Empty = disabled |

The frontend is built **only once** and served as is: it cannot
receive these keys at build time without duplicating the bundle per environment. The
backend therefore exposes them through `/api/config`, read before `bootstrapApplication()`. A
DSN and a beacon token are by construction public identifiers, meant
to live in browser code: exposing them this way creates no leak.

The Debug page (*Checks* tab) throws a test exception on each side, to check a
freshly configured DSN without waiting for a real bug.

## GDPR

Bugsink is consumed as a **hosted offering**, not self-hosted: it is therefore a
**processor**, which the privacy policy must say.
EU hosting is announced by the vendor, so there is no transfer outside the EU to declare,
unlike Cloudflare.

The policy says so **only if the tool runs**: `/api/mentions-legales` returns
`mesureAudience` and `suiviErreurs`, computed on the **effective value** of these
two keys (the one the beacon and the SDK read), and the page shows only the matching
paragraphs. Setting a variable therefore switches the tool on and adds
its declaration; emptying it removes both. A deployment that sends nothing to Cloudflare must not announce a
transfer outside the EU. Adding a third component therefore means a third
flag, not one more hard-coded paragraph.

## Product analytics: removed

PostHog has been dropped. Nobody looked at the dashboards, and the component
cost a JavaScript dependency, two variables and a personal-data processing
to justify. Three prices paid for nothing.

If the need comes back, the candidates considered were PostHog (most generous free
tier, EU hosting), Umami and Plausible.
`app/core/observability.ts` is the only entry point to pick up: nothing
else in the code knew about PostHog.
