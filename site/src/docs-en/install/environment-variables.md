---
twin: installer/variables-environnement
---

# Finding an environment variable

You are looking for the name, default and effect of an instance setting: every variable that the application, the production compose file or the image's entry script reads is listed here, by family. The application is configured **through the environment**, never through a screen, with one exception: what changes from one edition to the next (durations, reminders, rule weights) is set in *Settings*, and the variables below give only the instance's default or cap. The [`.env.prod.example`](repo:.env.prod.example) file gives the version to copy, commented, for the production compose file.

Three conventions:

- a duration is written in ISO-8601 format (`PT15M`: fifteen minutes, `P90D`: ninety days);
- an empty value is not an absent value: `BACKUP_DIR=` turns the backup off, as the *Default* column indicates;
- any Quarkus setting with no variable of its own can also be set through the environment, in capitals and underscores (`quarkus.http.port` becomes `QUARKUS_HTTP_PORT`). The production compose file uses two (`QUARKUS_HTTP_AUTH_SESSION_ENCRYPTION_KEY`, `QUARKUS_HTTP_PROXY_TRUSTED_PROXIES`), and [the administration access guide](securite-acces-administration.md) cites a third for the OIDC session cookie.

| Family | To put it into practice |
| --- | --- |
| Mandatory, legal notice | [Install the application on your server](installation.md), [Fill in the legal notice](mentions-legales.md) |
| Network, limits, headers | [hardening](securite.md) |
| Login and accounts | [login through an identity provider](oidc.md), [local accounts](comptes-locaux.md) |
| Schedule solve, solve machine | [Tune the schedule solve](calcul-du-planning.md), [Solve on another machine](calcul-sur-une-autre-machine.md) |
| E-mails, night sends | [Get your e-mails delivered](courriels.md), [Tune the automatic sends](envois-automatiques.md) |
| Backup, retention | [Back up and restore](sauvegarde-et-restauration.md), [Purge and keep data](conservation-des-donnees.md) |
| Webhooks, weather | [Allow webhooks and the weather alert](appels-sortants.md) |
| Error tracking, audience | [Connect error tracking and metrics](observabilite.md) |
| MCP server | [the MCP server](../comprendre/mcp-connexion.md) |

## Mandatory in production

`docker-compose.prod.yml` refuses to start without them (`?` in the compose file), and the application refuses to start on the two example passwords.

The application also refuses to start in production, whatever the
compose file, in the following cases, each detailed on its line further down:

- one of the five required keys of `LEGAL_MENTIONS` is empty (`editeur`,
  `hebergeur`, `contact`, `base-legale`, `conservation`) on a public instance
  (`PUBLIC_INSTANCE=true`), unless `LEGAL_DEMO_INSTANCE=true`; on any other
  it is a warning;
- `ADMIN_PASSWORD` or `DB_PASSWORD` equals the example value or is empty;
- no door opens the administration (`OIDC_ENABLED=false` and
  `ADMIN_LOCAL_SECOURS=false`), or `OIDC_CLIENT_SECRET` is shorter than 32
  characters when OIDC is on;
- a webhook exists and `WEBHOOKS_SECRET_KEY` is missing or invalid;
- `BACKUP_RETENTION` is outside 1 to 30, `SOLVER_SECONDS_LIMIT_MAX` is below
  `SOLVER_SECONDS_LIMIT` (900 by default);
- an entry of `CONNEXION_PROXYS_FIABLES` or of `WEBHOOKS_RESEAUX_AUTORISES`
  is malformed;
- the remote solve machine is badly described (see *Schedule solve*).

| Variable | Default | Role |
| --- | --- | --- |
| `APP_VERSION` | *(none: mandatory)* | Version of the image to deploy, a named version (`1.2.0`), never `main` or `latest`. Read by the compose file only, not by the application |
| `DB_PASSWORD` | *(none: mandatory)* | PostgreSQL password, shared by the application and the database container. The example password refuses startup. Default outside compose: `festival` (in dev: the one in `docker-compose.yml`) |
| `ADMIN_PASSWORD` | *(none: mandatory)* | Password of the `admin` account. Default outside compose: `admin`, which refuses startup in production, like an empty value |
| `SESSION_ENCRYPTION_KEY` | *(none: mandatory)* | Key of at least 16 characters that encrypts the session cookie. The compose file passes it to the application under the name `QUARKUS_HTTP_AUTH_SESSION_ENCRYPTION_KEY`; without a fixed key, sessions drop at every restart |
| `PUBLIC_URL` | `http://localhost:8080` | Public URL of the instance, printed as the "staff espace" link on PDFs and in mails. Mandatory in the production compose file |
| `MAIL_HOST` | `localhost` | SMTP server. Mandatory in the production compose file |
| `MAIL_FROM` | `planning-equipes@localhost` | Sender address. Mandatory in the production compose file |

## Database

| Variable | Default | Role |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/festival` | PostgreSQL JDBC URL. The production compose file sets it to the `postgres` container |
| `DB_USER` | `festival` | Database user |
| `FLYWAY_REPAIR_AT_START` | `false` | `true`: repair the migration history at startup (deletes the row of a half-done migration). For a single startup, knowingly |
| `ALLOW_SCHEMA_AHEAD` | `false` | `true`: start on a database more recent than the image (rollback), with a warning. For a single startup |

## Network and reverse proxy

| Variable | Default | Role |
| --- | --- | --- |
| `HTTP_PORT` | `8080` | HTTP port of the application |
| `PROXY_ADDRESS_FORWARDING` | `true` | Follow the `X-Forwarded-*` headers of a reverse proxy that terminates TLS (login redirect in `https`, `Secure` cookie) |
| `TRUSTED_PROXIES` | *(empty)* | Read by the production compose file and passed under the name `QUARKUS_HTTP_PROXY_TRUSTED_PROXIES`: the addresses whose `X-Forwarded-*` headers are accepted. Empty: those of any caller; to set as soon as the application is reachable without going through the proxy |
| `CONNEXION_PROXYS_FIABLES` | *(empty)* | Reverse proxies whose `X-Forwarded-For` header is believed, to count per address the administrator login failures, the refused reads of the wall display and, by default, the MCP calls. Empty: the connection's address is counted, so all visitors behind a proxy share a counter. A malformed entry prevents startup |
| `CSP` | (default policy) | Content security policy sent on every response; empty: header turned off |
| `HSTS` | `max-age=31536000; includeSubDomains` | HSTS header, sent on an HTTPS visit; empty: turned off |
| `MAX_BODY_SIZE` | `10M` | Maximum size of a request body |
| `MAX_CONNECTIONS` | `500` | Simultaneous HTTP connections accepted |
| `HTTP_IDLE_TIMEOUT` | `PT16M` | Delay before an idle connection is closed, to keep above the longest synchronous solve |
| `TZ` | `Europe/Paris` (production compose) | Time zone of the containers, read by the compose file |
| `LOG_MAX_SIZE` | `20m` (production compose) | Maximum size of a container log file, read by the compose file |
| `LOG_MAX_FILE` | `5` (production compose) | Number of log files kept per container, read by the compose file |

## Login and accounts

| Variable | Default | Role |
| --- | --- | --- |
| `OIDC_ENABLED` | `false` | Turn on login through an OpenID Connect identity provider |
| `OIDC_AUTH_SERVER_URL` | *(empty)* | URL of the provider's issuer, the same as seen by the browser and by the application |
| `OIDC_CLIENT_ID` | `planning-app` | Identifier of the client declared with the provider |
| `OIDC_CLIENT_SECRET` | *(empty)* | Client secret, at least 32 characters: shorter, startup is refused in production when OIDC is on |
| `OIDC_REQUIRE_EMAIL_VERIFIED` | `true` | Require the provider to assert that the address is verified; `false` for a directory that does not send this information |
| `OIDC_SCOPES` | `profile,email` | Scopes requested from the provider |
| `OIDC_SESSION_AGE_EXTENSION` | `30M` | Extension of the session after the identity token expires |
| `OIDC_POST_LOGOUT_PATH` | `/login` | Path to return to after logout |
| `OIDC_PRINCIPAL_CLAIM` | `email` | Claim that carries the person's identity |
| `OIDC_CONNECTION_DELAY` | `30S` | How long the provider's discovery is retried at startup |
| `OIDC_MCP_AUDIENCE` | `planning-mcp` | Audience required of the tokens presented by an AI assistant connected through OAuth: a token issued for another client of the provider is refused. Declare this audience with the provider |
| `AUTH_RECENT_WINDOW` | `PT10M` | Maximum age of an administrator's login to generate or display the AI assistant key |
| `OIDC_FORCE_HTTPS` | `false` | `true` behind a proxy that terminates TLS: redirects in `https` |
| `ADMIN_LOCAL_SECOURS` | *(empty)* | The `admin` account from the configuration. Empty: open without OIDC, closed behind an OIDC provider; `true` keeps it open as a fallback account. `false` without OIDC refuses startup (no door would open the administration) |
| `AUTH_LOCAL_SECONDE_ETAPE` | `true` | Local accounts receive a six-digit code by mail after their password; `false` turns this second step off |
| `CONNEXION_MAX_ECHECS` | `5` | Login failures tolerated before lockout |
| `CONNEXION_DUREE_BLOCAGE` | `PT15M` | Length of the lockout, from the last failure |
| `ESPACE_CODE_MAX_DEMANDES` | `3` | Unused access codes tolerated per staff member before refusal |
| `ESPACE_CODE_FENETRE` | `PT10M` | Window over which this cap is counted |
| `ESPACE_DECLARATION_MAX_ENVOIS` | `20` | Availability declarations that a staff member can send from their espace over the window |
| `ESPACE_DECLARATION_FENETRE` | `PT10M` | Window of this cap |
| `ESPACE_COLLEGUES_MAX` | `40` | Different colleagues whose seats a staff member can look up (choosing a swap) over the window |
| `ESPACE_COLLEGUES_FENETRE` | `PT1H` | Window of this cap |
| `AFFICHAGE_MURAL_MAX_REFUS` | `30` | Refused reads (unknown or revoked link) of the wall display tolerated per address over the window |
| `AFFICHAGE_MURAL_MAX_LECTURES_PAR_LIEN` | `120` | Reads allowed per wall display link over the window |
| `AFFICHAGE_MURAL_FENETRE` | `PT1M` | Window of these two caps |

## MCP server

| Variable | Default | Role |
| --- | --- | --- |
| `PLANNING_MCP_API_KEY` | *(empty)* | API key expected to authenticate the MCP server; empty: MCP unusable |
| `PLANNING_MCP_API_KEY_HEADER` | `X-MCP-Api-Key` | Header that carries the key (or `Authorization: Bearer <key>`) |
| `PLANNING_MCP_REQUIRED_HEADERS` | *(empty)* | Headers required in addition to the key, `Name=value` separated by commas |
| `PLANNING_MCP_PANGOLIN_ACCESS_TOKEN_ID` | *(empty)* | Identifier of the Pangolin access token, revealable from the MCP page |
| `PLANNING_MCP_PANGOLIN_ACCESS_TOKEN` | *(empty)* | Matching Pangolin access token |
| `PLANNING_MCP_MAX_REQUESTS` | `120` | Requests tolerated per address and per window; `0` or less turns the cap off |
| `PLANNING_MCP_RATE_WINDOW` | `PT1M` | Length of the window, in ISO-8601 format |
| `PLANNING_MCP_TRUSTED_PROXIES` | (value of `CONNEXION_PROXYS_FIABLES`) | Proxies whose `X-Forwarded-For` is believed to count the calls |
| `PLANNING_MCP_MAX_FAILURES` | `5` | Consecutive refused keys tolerated per address before blocking; `0` or less turns the lockout off |
| `PLANNING_MCP_LOCKOUT_DURATION` | `PT10M` | Length of this block |

## Schedule solve

| Variable | Default | Role |
| --- | --- | --- |
| `SOLVER_ENGINE` | `hand` | `hand`: direct engine, each plan scored again by the constraints; `timefold`: the search from before 3.0.0 |
| `SOLVER_SECONDS_LIMIT` | `900` | The instance's solve budget: maximum duration of a solve, in seconds. The same for every edition |
| `SOLVER_UNIMPROVED_SECONDS_LIMIT` | `180` | Stop if the already feasible plan has not improved for this long, in seconds; `0`: never |
| `SOLVER_SECONDS_LIMIT_MAX` | `3600` | Cap, in seconds, on the time a launch may request (`?seconds=`, MCP); below `SOLVER_SECONDS_LIMIT`, startup is refused |
| `PUBLIC_INSTANCE` | `false` | `true`: public instance. Applies the solve quota below and refuses to start without legal notices. Without it, the quota is ignored (with a warning) and missing notices are a warning. Not set at all, the warning also says that a 3.x public instance no longer is one; `false` silences it |
| `SOLVER_MAX_SOLVES_PER_HOUR` | `0` (no limit) | Public instance only: solves accepted over the last 60 sliding minutes, all editions and all callers combined; beyond that, a refusal that says when to come back |
| `SOLVER_MAX_QUEUED_JOBS` | `0` (no limit) | Public instance only: jobs that can wait behind the solve in progress |
| `PASSE_FIGE` | `true` | `false`: the solver can again rewrite the days already worked. To reserve for an acceptance test that replays an old edition |
| `JOBS_STREAM_HEARTBEAT` | `PT20S` | Heartbeat of the solve tracking stream, which keeps proxies open |
| `JOBS_STREAM_SCORE` | `PT1S` | Frequency at which the score curve of a solve in progress is sent |
| `SOLVER_WORKER_URL` | *(empty)* | Address of a permanent worker that receives full solves. In `https`, except on the loopback; requires `SOLVER_WORKER_SECRET`. Otherwise, startup is refused |
| `SOLVER_WORKER_SECRET` | *(empty)* | Secret shared with the worker, the same value on both sides: mandatory with `SOLVER_WORKER_URL`, and to start the image in worker mode |
| `SOLVER_WORKER_TLS_FINGERPRINT` | *(empty)* | Fingerprint of the worker's certificate, pinned |
| `SOLVER_WORKER_DEFAULT` | `local` | `remote`: a solve goes to the solve machine when its launch says nothing; `local` otherwise |
| `SOLVER_WORKER_MODE` | `false` | `true`: the image starts the solve worker instead of the application. Read by `docker/entrypoint.sh` |
| `SOLVER_WORKER_PORT` | `8443` | Worker mode: listening port |
| `SOLVER_WORKER_TLS_KEYSTORE` | (none) | Worker mode: PKCS#12 file of the TLS certificate. Without it, the worker listens in HTTP on the loopback only; with it, in HTTPS on all interfaces |
| `SOLVER_WORKER_TLS_KEYSTORE_PASSWORD` | *(empty)* | Worker mode: password of this file |
| `JDK_JAVA_OPTIONS` | `-XX:MaxRAMPercentage=75 -XX:+UseParallelGC` (set by the image) | JVM options; redefining them replaces the image's value entirely ([memory](calcul-du-planning.md#la-mémoire-de-lapplication)) |

## E-mails

| Variable | Default | Role |
| --- | --- | --- |
| `MAIL_HOST` | `localhost` | SMTP server (Mailpit locally) |
| `MAIL_PORT` | `1025` | SMTP port (587 in the production compose file) |
| `MAIL_USERNAME` | *(empty)* | SMTP user name |
| `MAIL_PASSWORD` | *(empty)* | SMTP password |
| `MAIL_START_TLS` | `DISABLED` | `DISABLED`, `OPTIONAL` or `REQUIRED` (`REQUIRED` in the production compose file) |
| `MAIL_MOCK` | `false` | `true`: mails are logged instead of being sent |
| `MAIL_FROM` | `planning-equipes@localhost` | Sender address |
| `MAIL_ADMIN` | *(empty)* | Administrator's address: swap requests, end of solve if the edition asks for it, weather alert, nightly backup failed then restored; empty: turned off, and a backup failure shows only on the settings screen |

## Backup, night tasks and retention

| Variable | Default | Role |
| --- | --- | --- |
| `BACKUP_DIR` | *(empty)* | Directory where the nightly backup writes its `pg_dump`s; empty: backup turned off (`/backup` in the production compose file) |
| `BACKUP_RETENTION` | `10` | Number of backups kept, from 1 to 30; out of bounds, startup is refused |
| `BACKUP_CRON` | `0 0 4 * * ?` | Backup schedule (cron expression) |
| `BACKUP_TIMEZONE` | `Europe/Paris` | Time zone in which this schedule is read |
| `PG_DUMP` | `pg_dump` | `pg_dump` binary to run |
| `BACKUP_TIMEOUT` | `PT30M` | Time allowed for a dump |
| `JOURNAL_RETENTION` | `P90D` | Retention period of the action history, the login journal and the result of the mails sent to staff members |
| `NOTIFICATIONS_CRON` | `0 5 * * * ?` | Wake-up rhythm of the night sends (day-before reminders, reminders of the unconfirmed) |
| `NOTIFICATIONS_TIMEZONE` | `Europe/Paris` | Time zone of the event, for these sends and for the weather alert |
| `EDITIONS_RAPPEL_JOURS` | `7` | Number of days before the first timeslot at which a reminder to activate the edition is sent |
| `HORLOGE_SIMULEE_AUTORISEE` | `false` | `true`: Settings can freeze the date and time read by "Today" and the staff espace. Demonstration or acceptance testing only |

## Outgoing calls: webhooks and weather

| Variable | Default | Role |
| --- | --- | --- |
| `WEBHOOKS_ENABLED` | `true` | `false` cuts all webhook output |
| `WEBHOOKS_CRON` | `0 * * * * ?` | Rhythm at which deliveries waiting for a retry are resumed |
| `WEBHOOKS_SECRET_KEY` | *(empty)* | AES-256 key (32 bytes in base64, `openssl rand -base64 32`) that encrypts the webhook secrets. Without it, creating a webhook is refused, and so is startup as soon as a webhook exists; a malformed key refuses startup |
| `WEBHOOKS_RESEAUX_AUTORISES` | *(empty)* | Networks (addresses or CIDR, comma-separated) that a webhook may target although the guard ordinarily refuses them (loopback, private networks); `http://` is accepted only toward them. A malformed entry prevents startup |
| `METEO_ENABLED` | `true` | `false` turns the weather alert off for the whole instance |
| `METEO_CRON` | `0 0 6 * * ?` | Time at which the weather service is queried |
| `METEO_URL` | `https://api.open-meteo.com/v1/forecast` | Forecast service queried (Open-Meteo's free tier is reserved for non-commercial use) |
| `SENTRY_DSN` | *(empty)* | Error tracking (Bugsink or any Sentry-compatible endpoint); empty: turned off |
| `SENTRY_ENVIRONMENT` | `local` | Environment label attached to errors (`production` in the production compose file) |
| `CLOUDFLARE_WEB_ANALYTICS_TOKEN` | *(empty)* | Cloudflare Web Analytics token, production profile only; empty: turned off |

## Branding and documents

Images and colours are set by two **grouped** variables of the form `key=value;key=value` (a literal semicolon in a value is written `\;`). An unknown key is reported at startup and ignored.

| Variable | Default | Role |
| --- | --- | --- |
| `BRANDING_PRODUCT_NAME` | `Planning Équipes` | Product name: tab, titles, mail subjects, PDFs |
| `BRANDING_ORGANISATION` | *(empty)* | Customer for whom the instance is deployed, printed at the foot of PDFs and mails |
| `BRANDING_SUPPORT_EMAIL` | `planning@sylvain.dev` | Support address named by the Help page; empty: the paragraph disappears |
| `BRANDING_IMAGES` | *(empty)* | The images, by key: `logo` (logo displayed in the application: URL, or file served at the root), `mascot` (mascot, large, shown by the Konami code), `mascot-icon` (the same, small), `pdf-logo` (logo of the header of PDFs and HTML mails, embedded, never loaded remotely), `pdf-strip` (decorative strip on the first page of the individual schedule) |
| `BRANDING_COLORS` | *(empty)* | The colours, by key: `accent` (accent colour of the interface, any CSS colour), `pdf-headline` (main ink of the PDFs, `#1f2933`), `pdf-muted` (secondary text, `#6b7280`), `pdf-accent` (accent of PDFs and HTML mails, `#3a6ea5`), `pdf-highlight` (background of statistics tiles and table headers, `#e4eaf1`), `pdf-pill` (background of time pills and table rules, `#f1f4f8`) |

Example, in quotes (a shell reads the `;` as the end of a command): `BRANDING_IMAGES="logo=logo.png;pdf-logo=classpath:/branding/logo.png"` and `BRANDING_COLORS="accent=#8b1e3f;pdf-accent=#8b1e3f"`.

The eleven old variables (`BRANDING_LOGO_URL`, `BRANDING_ACCENT_COLOR`, `BRANDING_MASCOT_URL`, `BRANDING_MASCOT_ICON_URL`, `BRANDING_PDF_LOGO`, `BRANDING_PDF_STRIP`, `BRANDING_PDF_HEADLINE`, `BRANDING_PDF_MUTED`, `BRANDING_PDF_ACCENT`, `BRANDING_PDF_HIGHLIGHT`, `BRANDING_PDF_PILL`) are **still read** throughout 4.x, with a warning at startup, and removed in 5.0; when both are set, the grouped key wins. See [Moving from 3.x to 4.0](passer-a-la-4-0.md).

## Legal notice

Five keys of `LEGAL_MENTIONS`, marked **required**, refuse startup in production of a public instance (`PUBLIC_INSTANCE=true`) when empty, unless `LEGAL_DEMO_INSTANCE=true`; on any other instance, a warning. They are displayed on the public `/mentions-legales` page. The two grouped variables have the form `key=value;key=value` (a literal semicolon in a value is written `\;`).

| Variable | Default | Role |
| --- | --- | --- |
| `LEGAL_DEMO_INSTANCE` | `false` | `true`: an instance that is not in service, exempt from the required notices (no refusal, no warning) |
| `LEGAL_MENTIONS` | *(empty)* | The notices, by key: `editeur` (**required**: name, legal form, address, registration), `hebergeur` (**required**: name and address), `contact` (**required**: contact address, including for exercising one's rights), `base-legale` (**required**: legal basis for the processing of personal data), `conservation` (**required**: retention period of personal data), `responsable-traitement` (data controller, when it differs from the publisher; empty: the publisher), `directeur-publication` |
| `LEGAL_ACCESSIBILITE` | *(empty)* | The accessibility statement, by key: `etat` (`totale`, `partielle` or `non`), `date-audit`, `contenus` (content that is not accessible), `signalement` (reporting channel; empty: the legal notice's contact address) |

The eleven old variables (`LEGAL_EDITEUR`, `LEGAL_DIRECTEUR_PUBLICATION`, `LEGAL_HEBERGEUR`, `LEGAL_CONTACT`, `LEGAL_RESPONSABLE_TRAITEMENT`, `LEGAL_BASE_LEGALE`, `LEGAL_CONSERVATION`, `LEGAL_ACCESSIBILITE_ETAT`, `LEGAL_ACCESSIBILITE_DATE_AUDIT`, `LEGAL_ACCESSIBILITE_CONTENUS`, `LEGAL_ACCESSIBILITE_SIGNALEMENT`) are **still read** throughout 4.x, with a warning at startup, and removed in 5.0.

## White labelling

The deployment model is **one instance per customer**: identity is therefore set
per deployment, with the `BRANDING_*` variables of the *Branding and documents* section, and nothing is
stored in the database: there is no branding administration screen, and a restart is
the only moment when a logo changes. The frontend reads them once, before it
starts, on the public endpoint `GET /api/branding`.

With none of these variables, the application starts, calls itself "Planning
Équipes", **displays no logo** rather than another's, and prints its
documents in a neutral grey-blue palette.

Two limits to know:

- the **Angular Material palette** (buttons, bars, fields) is compiled by
  `mat.theme()` in `src/main/webui/src/material-theme.scss`:
  the `accent` key of `BRANDING_COLORS` drives only `--app-accent`, read by the stylesheets
  specific to the application. Recolouring the Material components themselves
  requires a recompilation of the frontend;
- **images** cannot be uploaded from the application. A logo is
  supplied either by a URL (`logo` key of `BRANDING_IMAGES`; the default CSP accepts
  `https:` and `data:`), or by a file mounted next to the container
  (`pdf-logo` key), or by an image embedded in the jar
  (`classpath:/branding/…`). **No visual is shipped with the repository**: a
  deployment's images belong to its customer, they are mounted next to
  the container or served by URL.

## What is not an operating option

`OUTILLAGE_TEST_DUMP_SQL` opens two routes for exporting and importing the whole
database as SQL, by which the test suites bootstrap themselves. It stays `false` in
production and does not appear in `.env.prod.example`: it is a test tool,
never an operating setting.
