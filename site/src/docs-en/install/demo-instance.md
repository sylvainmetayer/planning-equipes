---
twin: installer/instance-de-demo
---
# Host a demo instance

You open a trial instance to everyone, with fictitious data and published credentials, reset every thirty minutes. A single variable puts it in this mode; the data come from a reference dump you build once, and your host restores it at fixed times. The decision is in [ADR 0130](repo:docs/decisions/0130-un-drapeau-pour-l-instance-de-demonstration.md).

Never use this mode for an instance in service: it accepts the example passwords and keeps nothing for more than half an hour.

## What the mode shuts

`DEMO_INSTANCE=true` forces the following settings, whatever the variables set beside it:

| Forced setting | Effect |
| --- | --- |
| `PUBLIC_INSTANCE=true` | the solve quota applies (see below) |
| `LEGAL_DEMO_INSTANCE=true` | the instance starts without legal notices; fill them in anyway if you are the publisher of a public site |
| `MAIL_MOCK=true` | no e-mail leaves: they are simulated |
| `AUTH_LOCAL_SECONDE_ETAPE=false` | signing in to a local account asks for no e-mailed code |
| `WEBHOOKS_ENABLED=false` | creating or testing a webhook is refused, nothing is sent |
| `METEO_ENABLED=false` | the weather alert queries no service |
| `OIDC_ENABLED=false` | no sign-in provider: the `admin` account's form stays open |
| `PLANNING_MCP_API_KEY` ignored, MCP OAuth closed | no key opens `/mcp`, and the configured one is not revealed |

The mode also refuses, with a message starting with `Instance de démonstration :`:

- creating, deactivating or reactivating an account, granting or withdrawing a right;
- sending a password link, choosing a password, the « forgot my password » request included;
- generating an MCP key.

Since no e-mail leaves, the espace animateur shows on screen the access code it would have sent: the espace's link, its token, stays the one key.

It accepts the example passwords (`ADMIN_PASSWORD=admin`, `DB_PASSWORD=festival`) that an ordinary instance refuses at startup. Every page, the login page included, carries a banner saying the data are fictitious and reset at :00 and :30.

## The variables

| Variable | Value for the demo | Role |
| --- | --- | --- |
| `DEMO_INSTANCE` | `true` | the mode itself |
| `ADMIN_PASSWORD` | the one you publish | the `admin` account, the only one that does not come from the dump |
| `SOLVER_SECONDS_LIMIT` | `40` | duration of a solve |
| `SOLVER_SECONDS_LIMIT_MAX` | `40` | ceiling on what a launch may ask for |
| `SOLVER_MAX_QUEUED_JOBS` | `3` | solves that may wait behind the running one |
| `SOLVER_MAX_SOLVES_PER_HOUR` | `30` | solves accepted per sliding hour, for the whole instance |

Only one solve runs at a time on an instance: that is how the queue works, with no setting. The other variables are those of an ordinary [installation](installation.md) (database, `PUBLIC_URL`).

## Build the reference dump

The repository's `scripts/demo/generer-dump.sh` starts a throw-away stack (PostgreSQL, Mailpit, the application), imports the showcase scenario, runs a solve and waits for it, publishes the plan, creates the three local accounts, then writes the dump. It needs `docker` (with `docker compose`), `curl` and `jq`, and a few minutes.

```bash
DEMO_PASSWORD='demo-planning' \
APP_IMAGE=ghcr.io/sylvainmetayer/planning-equipes:4.2.0 \
  scripts/demo/generer-dump.sh reference.dump
```

| Script variable | Default | Role |
| --- | --- | --- |
| `DEMO_PASSWORD` | required, at least 12 characters | password of the three local accounts |
| `APP_IMAGE` | `ghcr.io/sylvainmetayer/planning-equipes:main` | the version that builds the dump; take the one the demo deploys |
| `POSTGRES_IMAGE` | `postgres:19` | same major version as the server that will restore the dump |
| `DEMO_ESPACE_TOKEN` | a fixed UUID, written in the script | token of the demo espace (see below) |
| `APP_PORT`, `MAILPIT_PORT` | `18080`, `18025` | ports of the throw-away stack, on the loopback |

The accounts of the dump:

| Account | Right |
| --- | --- |
| `rh@example.org` | human resources of the edition |
| `responsable@example.org` | stand lead of the first two stands |
| `lecteur@example.org` | reader of the edition |

The animateur of the plan who has an address and the most seats receives the token `DEMO_ESPACE_TOKEN`: their espace opens at `/animateur/<token>`, the same address from one dump to the next, and the script prints it at the end.

The dump is in `pg_dump -Fc` format, **without owner or privileges** (`--no-owner --no-acl`): its objects belong to the role that restores it. It is neither in the repository nor in the image: keep it on the demo's host.

A dump built by a version older than the one you deploy stays valid: at startup, the application migrates the restored database. Build it again when the showcase scenario changes.

## The reset

A timer on the host, every thirty minutes (at :00 and :30), stops the application, replaces the database with the dump, then starts the application again. The database is **dropped and created again** rather than cleaned by `pg_restore --clean`: a table that a newer version added, absent from the dump, would survive the cleaning and make the next migration fail.

```bash
docker compose stop app
docker compose exec -T postgres dropdb -U festival --if-exists --force festival
docker compose exec -T postgres createdb -U festival -O festival festival
docker compose exec -T postgres pg_restore -U festival -d festival --no-owner --role=festival < reference.dump
docker compose start app
```

With systemd, a `oneshot` service running these commands and a timer `OnCalendar=*:00,30`. The nightly backup has no purpose on the demo: leave `BACKUP_DIR` empty, and it does not run.
