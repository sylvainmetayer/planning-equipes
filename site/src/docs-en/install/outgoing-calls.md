---
twin: installer/appels-sortants
---
# Allow webhooks and the weather alert

You open to the application container the outbound HTTPS connections that two features need, webhooks (n8n, Slack, Discord, Matrix, Telegram) and the weather alert, and you set their variables. The container needs **outbound HTTPS** access to these services; `docker-compose.prod.yml` imposes nothing, and an egress firewall can be limited to the hosts of the configured webhooks (`hooks.slack.com`, `discord.com`, `api.telegram.org`, your n8n…). What the anti-SSRF guard refuses, and why, is in [controlling outbound calls](securite-appels-sortants.md).

## Webhooks

They are configured in *Settings › Instance* (behind "Advanced settings"), *Webhooks* card.

![Webhooks, in Settings, Instance tab](/images/fr/docs/webhooks.webp "The Webhooks card of the instance settings")

| Variable | Default | Role |
| --- | --- | --- |
| `WEBHOOKS_ENABLED` | `true` | `false` cuts all outbound traffic: nothing is queued or sent, the test is refused |
| `WEBHOOKS_SECRET_KEY` | — | AES-256 key (32 bytes in base64, `openssl rand -base64 32`) that encrypts webhook secrets at rest |
| `WEBHOOKS_RESEAUX_AUTORISES` | — | Internal addresses and CIDR blocks a webhook may target (an n8n on the Docker network); `http://` and any port are accepted there. Malformed: startup refused |
| `WEBHOOKS_CRON` | `0 * * * * ?` | The retry of deliveries waiting to be retried |

### Set and store the key

Without `WEBHOOKS_SECRET_KEY`, the screen refuses to create a webhook; and **as soon as a webhook exists, the application refuses to start without it**, or with a key other than the one that encrypted its secrets, because unreadable secrets would make you believe deliveries are going out.

Store it with `DB_PASSWORD` and `SESSION_ENCRYPTION_KEY`, **outside** the database backup: the nightly backup (`pg_dump` of the whole cluster) contains only the encrypted form of the secrets, and that is the whole point, since a stolen dump gives access to no channel. Losing the key means losing the secrets: you then have to recreate every webhook (or, on a database whose key was lost, delete the rows of the `webhook` table so the application can restart). Changing the key is not supported: there is no rotation, and a new key does not read the old secrets.

### How a delivery goes

A first attempt leaves at once, outside the thread of the operation that caused it. On a network error, a `408`, a `429` or a `5xx`, the task that runs every minute retries after 1 min, 5 min, 30 min, 2 h and 12 h; so does a name that does not resolve, so a DNS outage is survived. Any other response is final. Pending deliveries resume after a restart.

The log (*Delivery log* button) is purged beyond 30 days by the nightly task of the automatic sends. Only the **active** edition (Editions page) emits its events; the failure of the nightly backup, an instance event, always goes out.

## The weather alert

Each morning, the active edition queries Open-Meteo for the geolocated places of its open stands, and suggests a consigne preset (a consigne is an official order, such as an arrêté, relayed to the whole event) when a threshold is crossed (*Settings › Edition*, *Weather alert* card). The container must reach `api.open-meteo.com:443`, or the address in `METEO_URL`.

| Variable | Default | Role |
| --- | --- | --- |
| `METEO_ENABLED` | `true` | `false` switches the feature off for the instance: no queries, the test is refused |
| `METEO_URL` | `https://api.open-meteo.com/v1/forecast` | The service queried: the free API, the commercial offer or a self-hosted instance |
| `METEO_CRON` | `0 0 6 * * ?` | The time of the query, in the `NOTIFICATIONS_TIMEZONE` time zone |

- **The free Open-Meteo API is reserved for non-commercial use.** A host that bills for the instance uses Open-Meteo's paid offer, or a self-hosted instance (official Docker image) that it designates in `METEO_URL`.
- **Attribution**: Open-Meteo data is licensed under CC BY 4.0; the settings screen and the alert e-mail carry "Données météo : Open-Meteo.com (CC BY 4.0)" (weather data). It is not a dependency of the software: it has no place in [the inventory of third-party licences](licences-tierces.md).
- Only the active edition is queried, and only if its alert is enabled; between two events, none is. "Today" follows the simulated clock of a trial run, but Open-Meteo only forecasts the next sixteen real days: a simulated date outside that window queries nothing, and the screen says "Out of forecast". The *Test now* button queries on demand, without raising an alert.
- An unreachable service stops nothing: the "unreachable since…" status is displayed, the task retries the next day, and a single warning goes out on the second consecutive day of failure.

## Check

From the screen, the webhook test (button on the card) and the weather alert's *Test now* button tell you whether the outbound connection goes through. A refusal by the guard is explained in the message; a firewall block appears as a network error in the *Delivery log*.
