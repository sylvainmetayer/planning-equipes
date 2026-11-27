---
twin: installer/surveillance
---

# Monitoring the instance

You set up what it takes to learn that an instance is down, that a backup did not run or that an error occurred before a staff member tells you. The application cannot monitor its own absence: you need at least one external probe.

## Connecting error tracking

`SENTRY_DSN` is `""` by default: without it, no error is reported and outages are learned by phone. The wiring exists, only the variable is missing: [error tracking and metrics](observabilite.md).

## Probing from the outside

An external probe on **`/q/health/ready`** (no authentication) is enough to know that an instance is down before the customer finds out. Two probes, which answer only `UP` or `DOWN` and the name of their checks, no JDBC URL or host:

| Probe | `UP` when | Use |
| --- | --- | --- |
| `/q/health/live` | the process answers | restart a frozen container; does **not** depend on the database, which a restart would not repair |
| `/q/health/ready` | database reachable **and** Flyway history up to date (no migration pending or failed): a single check, which reads the history and therefore fails first when the database does not answer | external probe, `healthcheck` of the `app` service (`docker compose ps` shows it `healthy`) |

What availability signals above all is **an unreachable database**. An interrupted migration, for its part, does not produce a `DOWN` instance: the application migrates at startup and **refuses to start** on a failed or pending migration, `FLYWAY_REPAIR_AT_START` unblocking it; it does the same on a database ahead of the image, `ALLOW_SCHEMA_AHEAD` forcing the startup ([these two cases](mettre-a-jour.md#débloquer-une-migration-interrompue)). Reading the history is a defence in depth against a history that changes under a running instance (another instance, or you, migrating the same database with a more recent binary). Once the history is read as up to date, the verdict is kept until restart: the probe no longer opens Flyway, and a database that goes down afterwards no longer turns it `DOWN` (monitor it separately). While `DOWN`, it is re-read at most every 5 s.

The image does not ship `curl`: the compose `healthcheck` writes the request in bash on `/dev/tcp`.

## Being warned of a failed backup

A failure of the nightly backup goes out by e-mail to `MAIL_ADMIN` ([the detail](sauvegarde-et-restauration.md#suivre-létat-de-la-sauvegarde)). Leaving this variable empty means learning of it only by opening the *Settings* screen.

## Reading the metrics

`/q/metrics` in Prometheus format, on **port 9000** (management interface), which `docker-compose.prod.yml` does not publish. A Prometheus added to the stack reads it at `http://app:9000/q/metrics`. With no authentication, so **never** published on the host or routed by the reverse proxy; the application port, for its part, answers `404` on that path.

You read there the duration of the solves and the solver queue, the API latency, the mails refused by the SMTP server and the age of the last successful backup (`planning_backup_last_success_timestamp_seconds`), enough to alert on a backup that did not run even when the failure mail did not go out. The full list and the cardinality rules are in [the metrics](observabilite.md#métriques).

## Reading and limiting the logs

`docker compose logs` remains the first thing to read:

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod logs -f app
```

The application writes to standard output, so rotation falls to the container engine, and `docker-compose.prod.yml` sets it itself for `app` and `postgres`: `local` driver (compressed, native rotation), **5 files of 20 MB, so about 100 MB per service** at most. Without this setting, Docker's default driver grows without limit, and a disk filled by logs also makes the nightly backup fail, which writes to the same disk.

| Point | Detail |
| --- | --- |
| Adjust | `LOG_MAX_SIZE` (size of one file, `20m` by default) and `LOG_MAX_FILE` (number of files kept, `5`) in `.env.prod`, then `docker compose … up -d` to recreate the containers: a logging setting applies only at creation. These are two compose variables, not application ones |
| `daemon.json` | the compose `logging` block **overrides** `/etc/docker/daemon.json` for these two services; a global host setting remains valid for its other containers |
| Files on the host | with the `local` driver, the `/var/lib/docker/containers/*/…-json.log` files no longer exist: the format is binary and compressed. What read them directly now goes through `docker compose logs` or `docker logs` |
| Chatty event | solves in a loop, SMTP refusing: old logs go quickly. The **errors** do not depend on them, they are reported to Sentry (`SENTRY_DSN`), which remains their channel |
| Check | `docker inspect <container> --format '{{.HostConfig.LogConfig}}'` shows the driver and the two caps |

### Exporting to a collector

Exporting to a collector (syslog, journald, GELF for Graylog…) is a choice the stack does not impose: a `docker-compose.override.yml` file next to the compose file, passed as a second `-f`, replaces the `logging` block of the two services, for example:

```yaml
services:
  app:
    logging:
      driver: journald
  postgres:
    logging:
      driver: journald
```

The file is given on each command:

```bash
docker compose -f docker-compose.prod.yml -f docker-compose.override.yml \
  --env-file .env.prod up -d
```

`docker compose logs` keeps working with a remote driver thanks to the engine's local cache (*dual logging*, Docker 20.10 and later), but this cache has its own caps (`cache-max-size`, `cache-max-file`): `LOG_MAX_SIZE` and `LOG_MAX_FILE` no longer apply.
