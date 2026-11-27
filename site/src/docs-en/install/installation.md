---
twin: installer/installation
---
# Install the application on your server

In about an hour, you put Planning Équipes online on your own server: the application and its PostgreSQL database, behind your reverse proxy, with a check that confirms everything responds. You need a Linux server with Docker Compose, a domain name and an SMTP relay.

Still unsure about the principle? [Self-host or have it hosted](/heberger/) summarises what is needed, and what we can take over for you.

## Before you start

| You need | Why |
| --- | --- |
| A domain name and a TLS certificate | `PUBLIC_URL` builds the espace links printed on the PDFs: they must stay reachable after the event as well as during it |
| An authenticated SMTP relay | Access codes, espace links, individual schedules, swap notifications: everything goes by e-mail ([get them delivered](courriels.md)) |
| A reverse proxy | It terminates TLS and must be the only path to the application: [hardening the deployment](securite.md) |
| The organiser's legal information | Publisher, host, contact, legal basis, retention period: these are the organiser's ([fill them in](mentions-legales.md)) |
| 2.5 GB of memory for the stack | 2 GB for the application, 512 MB for PostgreSQL ([why so much](calcul-du-planning.md#la-mémoire-de-lapplication)) |

## What you receive

| Item | Content |
| --- | --- |
| The image | `ghcr.io/sylvainmetayer/planning-equipes`, published without authentication, with one tag per version (`1.2.0`, `1.2`), plus `main` (the trial build, rebuilt at every merge) and `latest`. It bundles the application, the interface and the `pg_dump` client for the backup; it listens on port 8080 and runs as user 1000. The SBOM and the signature of each release are described in [version numbers](versioning.md) |
| `docker-compose.prod.yml` | The production stack: the application and PostgreSQL 19, nothing else |
| `.env.prod.example` | The environment file to copy to `.env.prod`, every variable commented |
| `docker-compose.yml` | A **development** stack, not to be deployed: it publishes PostgreSQL on the host, adds pgAdmin and an SMTP sink |
| `scripts/verifier-deploiement.sh` | The end-of-deployment check (availability, version, branding, legal notice) |
| `scripts/restaurer.sh` | The guided restore of a backup ([the procedure](sauvegarde-et-restauration.md)) |

The deployment files are taken from the [project repository](repo:docker-compose.prod.yml), at the tag that matches the version you are about to deploy.

## Start the stack

1. Write the environment. Copy the template, then fill in at least the seven mandatory variables and the legal notice. Never commit this file: it carries secrets.

   ```bash
   cp .env.prod.example .env.prod
   ```

   The seven mandatory variables are `APP_VERSION`, `DB_PASSWORD`, `ADMIN_PASSWORD`, `SESSION_ENCRYPTION_KEY`, `PUBLIC_URL`, `MAIL_HOST` and `MAIL_FROM`. The compose file refuses to start if one is missing, and the application for its part refuses the two sample passwords and, on a public instance (`PUBLIC_INSTANCE=true`), five empty legal fields. The full list of its refusals opens [the variable inventory](variables-environnement.md#obligatoires-en-production).

2. Choose a named version. `APP_VERSION=1.2.0` in the `.env.prod`, never `main` or `latest`. `main` is the trial image, rebuilt at every merge and every Sunday; `latest` designates whatever the registry decides. An incident report must be able to name the deployment: "1.2.0" does, "Tuesday's image" does not. What a number promises is in [version numbers](versioning.md).

3. Prepare the backup directory. The nightly backup writes to `./backup`, next to the compose file. It must belong to user 1000, otherwise Docker creates it as `root` and the first backup fails.

   ```bash
   mkdir -p backup && sudo chown 1000:1000 backup
   ```

4. Start.

   ```bash
   docker compose -f docker-compose.prod.yml --env-file .env.prod up -d
   ```

   On the first start, the application applies its migrations by itself before answering; the check in the next step waits for that.

5. Check. The script shipped in the repository is called from the directory of the `.env.prod` (bash, `curl` and `jq`):

   ```bash
   scripts/verifier-deploiement.sh https://<domaine>          # version read from APP_VERSION
   scripts/verifier-deploiement.sh https://<domaine> 1.2.0    # or given explicitly
   ```

   It runs the checks below in sequence and stops at the first failure with a non-zero exit code. It needs no host access, only the password of the `admin` account to read the version, taken from `ADMIN_PASSWORD` (the environment, or the `.env.prod`): a deployment CI can call it as is.

   | Check | What is verified |
   | --- | --- |
   | Availability | `/q/health/ready` answers `UP`: database reachable, no migration pending or failed. The wait is bounded (120 s, `--attente`) to let the migrations of a first start go through |
   | Version | the version `/api/config` gives a session is the expected one: the script opens the `admin` account's session and closes it. Behind an OIDC whose break-glass door is closed, or while `APP_VERSION=main`, pass `--version-libre` |
   | Branding and legal notice | `/api/branding` and `/api/mentions-legales` answer |
   | Mandatory notices | none of the five required keys of `LEGAL_MENTIONS` is empty; skipped if the instance declares itself a demo (`demoInstance` of `/api/mentions-legales`, that is `LEGAL_DEMO_INSTANCE=true` as the application read it), or with `--instance-demo` |

   The application footer (administration as well as staff espace) and the *Debug* page (*Administer* menu, Ctrl+K or the `/debug` address, *Solve* tab) display the deployed version: the `vX.Y.Z` tag for a release image, the commit identifier for a trial image. That is what a vulnerability report must cite ([report a vulnerability](repo:.github/SECURITY.md)).

6. Open the administration. Sign in with the `admin` account and the `ADMIN_PASSWORD` password. If you connect an identity provider, first create your personal account ([OIDC](oidc.md), [local accounts](comptes-locaux.md)). Then set the instance settings, then load or enter the first edition: the guide [Prepare your first edition](../regles/preparer-edition.md) takes over.

## What the production stack does

- The application is published only on the host's loopback (`127.0.0.1:8080`): your reverse proxy terminates TLS and is the only entry path. Its supervision interface (port 9000, metrics) is never published.
- PostgreSQL has no published port: only the stack's internal network sees it. Its data lives in a named volume, and the application applies its migrations by itself, at startup.
- Resources: 2 GB for the application (the JVM takes three quarters of it) and 512 MB for the database, that is 2.5 GB for the stack.
- The nightly backup writes to `./backup`, a host directory that must belong to user 1000 ([back up and restore](sauvegarde-et-restauration.md)).
- Container logs rotate (5 files of 20 MB per service by default, `LOG_MAX_SIZE` and `LOG_MAX_FILE`) so that a full disk does not make the backup fail ([the logs](surveillance.md#lire-et-limiter-les-journaux)).
- The `app` service's health check queries `/q/health/ready`: `docker compose ps` shows it as `healthy` once the migrations have gone through (up to 180 s of grace at startup).

## What next

| I want to… | Guide |
| --- | --- |
| Get e-mails to the inboxes | [Get your e-mails delivered](courriels.md) |
| Fill in the organiser's legal notice | [Fill in the legal notice](mentions-legales.md) |
| Never lose data | [Back up and restore](sauvegarde-et-restauration.md) |
| Know that the instance is running | [Monitoring the instance](surveillance.md) |
