---
twin: installer/sauvegarde-et-restauration
---

# Back up and restore

You make sure a recent copy of the database exists every night, you know how to make one by hand, and you know how to restore without making things worse. All the application's state lives in PostgreSQL: reference data, solved schedule, snapshots, staff espace tokens, sessions. There is **nothing else to back up**, and nothing that can be rebuilt without the database.

For a host who is still deciding, [the hosting page](/heberger/) sums up the nightly backup in a few lines.

![Automatic backup and restore outside the application](repo:docs/diagrammes/sauvegarde.svg)

<sub>Source: [`diagrammes/sauvegarde.puml`](repo:docs/diagrammes/sauvegarde.puml).</sub>

## 1. Let the nightly backup run

The application dumps the database **on its own, every night at 4:00**, as soon as `BACKUP_DIR` points to a directory. It is a full `pg_dump --format=custom` (schema and data, all editions) written to that directory. Only the `BACKUP_RETENTION` most recent copies are kept: the oldest goes when a new one arrives.

| Variable | Default | Effect |
| --- | --- | --- |
| `BACKUP_DIR` | *(empty)* | Backup directory. Empty: **nothing is scheduled**, and the *Settings* screen, *Instance* tab (behind "Advanced settings"), says so. The production compose file sets it to `/backup` |
| `BACKUP_RETENTION` | `10` | Number of copies kept, between 1 and 30. Out of bounds, **startup is refused**: a retention silently trimmed is only discovered the day you look for the missing dump |
| `BACKUP_TIMEZONE` | `Europe/Paris` | Time zone in which "4:00" is read, and in which the file is named; set separately, independently of `TZ` |
| `BACKUP_CRON` | `0 0 4 * * ?` | The schedule itself, if 4:00 does not suit |

`docker-compose.prod.yml` also sets **`TZ`** (default `Europe/Paris`) on both containers. That time zone does not enter the two nightly schedules, which each have their own, but decides **the day boundary** everywhere else: which timeslots are "already started" and therefore frozen during the event, the day the availability collection and the swap fair open and close, the time printed on PDFs, the date of exported files. It must be the event's: in UTC, all of that would flip one to two hours after midnight, Paris time.

Files are named `planning-AAAAMMJJ-HHMMSS.dump`. A dump placed there by hand under another name is never touched by the rotation.

### Prepare the directory

The directory must belong to **uid 1000**, the container's user, which is also the first account of a Linux machine. `docker-compose.prod.yml` mounts the host's `./backup` directory there, next to the compose file: the mount of the reference deployment, and the one an external backup tool (borgmatic, restic...) copies off the machine. **Create it before the first start**, otherwise Docker creates it as `root` and the first backup fails on a write denial:

```bash
mkdir -p backup && sudo chown 1000:1000 backup
```

> The directory survives the container, **not the machine**. A backup that never leaves the host protects against a wrong move, but not against losing the server: copy `./backup` elsewhere, encrypted.

### Follow the backup status

The *Settings* screen, *Instance* tab, shows the location, the retention, the next backup, the files present and the report of the last attempt, success or failure. That is where "the backup has not run for three weeks" shows up before it matters. A switch **suspends** it; it does not turn it on, `BACKUP_DIR` does.

**A failed night warns the administration by e-mail**, without waiting for someone to open the screen: the `MAIL_ADMIN` address receives the date of the attempt, the reason (the one the screen shows), the date of the last successful backup and the number of consecutive failed nights. A second message goes out at the next successful backup, to close the loop.

- Nothing is sent when the backup is suspended or `BACKUP_DIR` is empty (that is a choice, not a failure), nor when `MAIL_ADMIN` is empty: the application then says so once at startup, and the screen reminds you.
- If the SMTP server itself is down, the sending failure is logged and nothing more.
- If it is the database that no longer answers, the message goes out anyway: the date of the last success is then read from the most recent dump in the directory ("unknown" if there is none), and the nights that could not be recorded are counted in the series, and in the recovery message, as soon as the database answers again, as long as the application has not restarted in the meantime.
- The backup's exception also goes to error tracking when `SENTRY_DSN` is set.
- An instance that no longer runs at all at night cannot alert itself: that is the role of [the external probe](surveillance.md#sonder-lextérieur).

### If the backup fails on a write denial

Two older installations may be concerned.

**Container moved from uid 1001 to 1000.** The existing backup volume still belongs to the old user. After the image update, the nightly backup fails on a write denial, visible in the report of *Settings*, *Instance* tab. Take ownership back once, with the stack stopped:

```bash
sudo chown -R 1000:1000 backup
```

An installation that still used the named volume `planning_backups` (mounted on `/backups`) keeps its old dumps in it: copy them once into `./backup`:

```bash
docker run --rm -v planning_backups:/old -v "$PWD/backup":/new \
  alpine sh -c 'cp -a /old/. /new/ && chown -R 1000:1000 /new'
```

**Directory `/backups` became `/backup`.** A `.env.prod` that still says `BACKUP_DIR=/backups` **keeps working**: the image makes `/backups` a link to `/backup`, so the dumps arrive in `./backup`. Two steps remain to do once, without urgency: copy the old dumps as above, and write `BACKUP_DIR=/backup` in `.env.prod`. The link is a compatibility measure, not a path to use.

## 2. Back up by hand

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod exec -T postgres \
  pg_dump -U festival -d festival --format=custom \
  > "planning-$(date +%Y%m%d-%H%M).dump"
```

The service is called `postgres`, the database and its owner `festival`: they are fixed by `docker-compose.prod.yml`, not by `.env.prod`.

Recommended rhythm: **daily outside the event, hourly during it**. The volume is small (a few megabytes); what costs a lot is the day of entry lost, not the storage.

> A dump contains **personal data of minors**: names, dates of birth, e-mail addresses, availability. Encrypt it at rest, store it off the machine, and never put it on a demo instance or in a ticket.

## 3. Restore

**Restoring is not a feature of the application, and that is deliberate**: it is done on the PostgreSQL database, by you, on the host. A "restore" button in a web interface would be precisely the gesture the automatic backup exists to make up for ([decision 0015](repo:docs/decisions/0015-sauvegarde-par-pg-dump-restauration-hors-application.md)). The former SQL dump export and import of the Settings screen is no longer offered: backing up is the nightly `pg_dump`; exchanging a dataset is the YAML scenario.

The repository ships a **guided script**, `scripts/restaurer.sh`, to run from the directory of `docker-compose.prod.yml` (bash and `docker compose`, nothing else):

```bash
tmux new -s restauration                              # first: see the note below
scripts/restaurer.sh                                  # lists the volume's dumps, asks which one
scripts/restaurer.sh planning-20260308-040000.dump    # a dump from the volume, by name
scripts/restaurer.sh --fichier ./copie.dump           # a file on the host
scripts/restaurer.sh --essai planning-20260308-040000.dump   # the rehearsal, without touching production
```

Do the rehearsal once, cold, before you need it: **`--essai`** restores into a temporary container of the image of the stack's `postgres` service (read from `docker-compose.prod.yml`, so the same major version as production), deleted at the end of the run, and displays the duration, the number of editions and the schema version.

The script chains:

| Step | What it does |
| --- | --- |
| Check | the file is **read in full** by `pg_restore` (to `/dev/null`): a file that is not a `--format=custom` dump, or a truncated dump (even after its table of contents, which `pg_restore --list` would read without complaint) is refused **before** any stop. The dump's schema version is compared with that of the image's migrations: a dump **newer** than the image is flagged, and a confirmation asked (`PLUS-RECENT`, deemed given under `--oui`). The stack must be running, and the volume must have room for a safety dump |
| Safety | dump of the current state, `planning-avant-restauration-AAAAMMJJ-HHMMSS.dump`, in the volume. Its name is ignored by the rotation, so it **is never erased on its own**: delete it by hand once the restore is validated. Its name is displayed: it is the way back |
| Confirmation | the script says what will be overwritten (database `festival`, instance `PUBLIC_URL`) and asks you to type `RESTAURER` (`--oui` for a written procedure) |
| Restore | `app` is stopped, the database dropped then recreated, `pg_restore` runs in **a single transaction** that stops at the first error, `app` is restarted. Failure or interruption (Ctrl-C, dropped SSH session): the safety dump is restored instead before the restart, so the application never restarts on an empty or half-restored database |
| Verification | wait for `app` to become `healthy` again (`/q/health/ready`), duration of the interruption displayed |

> **Run the script in `tmux`** (or `screen`, or under `nohup … > restauration.log 2>&1 &`) when you are connected over SSH. A session that drops during the restore does not leave the database half-restored: the script puts the safety dump back and restarts the application even without a terminal. But that rollback then happens unwitnessed, and you reconnect without knowing whether it succeeded. Under `tmux`, nothing is interrupted, and `tmux attach -t restauration` brings back the screen where it was.

The database is **recreated** rather than cleaned (`--clean`): `--clean` only drops the objects present in the dump, and restoring a dump older than the schema (the case of a rollback) would leave the tables of the later migrations, which Flyway would replay at startup and fail.

`pg_restore` and `pg_dump` come from the `postgres` container, so from the same major version as the server. The backup volume is read by a throwaway container of the application image, entry point replaced: the script also works the day the application no longer starts, and with a host directory mount as with the named volume. The working copy of the dump is erased on exit; nothing leaves the host.

### Restore a dump newer than the image

A dump newer than the deployed image (image rollback, dump from another instance) carries a schema ahead of the migrations the image knows, and the application will refuse to start on it ([rollbacks](mettre-a-jour.md#une-base-en-avance-sur-limage)). The restore flags it **before** any stop (version read from the dump's Flyway history, compared with the migrations embedded in the image) and asks for confirmation. The right move is almost always to deploy the image of the dump's version first (`APP_VERSION`).

### What a restore loses

Restoring during the event loses all writes after the dump: that is what justifies the hourly rhythm recommended above.

An edition's **pre-wipe backups** (`sauvegarde_avant_vidage`, [decision 0084](repo:docs/decisions/0084-sauvegarder-l-edition-avant-de-la-vider.md)) follow the gesture chosen. Restoring a `pg_dump` dump brings them back as they were at its time. Replaying an **SQL export of the application** (test tooling, no longer offered to the operator) **erases them all**, for all editions: the export does not carry them, and the import deletes each edition before rewriting it. It is a global operating gesture, not an edition wipe; nothing is backed up before it.
