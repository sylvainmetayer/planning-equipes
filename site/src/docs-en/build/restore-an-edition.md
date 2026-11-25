---
twin: comprendre/restaurer-une-edition
---

# Starting again from an edition, going back

You will know how to choose between scenario, duplication and year template to start an edition, how to find and restore the state of an edition from before an import or a wipe, and what the SQL dump reserved for tests does (and does not do).

## Three ways to start again from an edition

| Action | What it does | The people |
| --- | --- | --- |
| YAML scenario | describes a whole event and replaces the reference data of the target edition ([the file](scenario-yaml.md)) | yes, `animateurs` section |
| Edition duplication (`POST /api/editions/{id}/dupliquer`) | creates a new edition and copies into it all the reference data of the source: stands, locations, game categories, recurring hours, timeslots, constraint weightings, consigne parameters and presets. Neither the solved schedule nor the dated consignes: "2026 = 2025 minus the assignments" | yes, by default |
| Year template (`…/dupliquer?avecAnimateurs=false`) | the same duplication, without the people | no |

There is no separate template library: the list of editions takes its place, and applying a template means duplicating. A parallel table of templates would be a second, redundant reference data set for the same service.

The year template mode leaves behind `animateur` and everything that hangs off a person (`animateur_competence`, `animateur_jour_indispo`, `animateur_souhait`), as well as the manual adjustments, since each type of `contrainte_ad_hoc` names at least one staff member: a copy pointing at absent people would be worse than no copy. `stand_typologie` stays, because it carries a stand and a game category, never somebody.

The default does not change. Duplicating with the people is the action for a "heatwave plan" edition created in the middle of a festival, which must keep its team and its mailing to everyone. Unticking the box is the opposite case, preparing 2027 from 2026, and there copying names, birth dates and e-mails of people who have not re-registered is a problem of minimisation and retention period, not a convenience ([ADR 0009](repo:docs/decisions/0009-edition-unique-porteur-de-variantes.md) for the role of the edition, [One edition, one data partition](cloisonnement-par-edition.md) for the partitioning).

## Backups before a wipe

Before writing anything, a scenario import, "Empty this edition", `reinitialiser_donnees` and the restore itself store the scenario export of the target edition and its schedule (seats and locks) in `sauvegarde_avant_vidage` ([ADR 0084](repo:docs/decisions/0084-sauvegarder-l-edition-avant-de-la-vider.md)). If this backup fails, nothing is emptied.

- Nothing purges them: they stay until the edition is deleted, or until an administrator deletes one (`DELETE /api/planning/sauvegardes-avant-vidage/{id}`).
- A half-built edition (a team with no grid, a grid with no stands) is backed up like the others. Only an edition that contains nothing (no reference data, no schedule, no parameters, weights or typical days) receives none, and a state already backed up last is not backed up twice.
- An import refused during a solve writes nothing, an import refused further along may leave its backup.
- No screen lists them: they can only be reached through the API.

### Finding and restoring them

With the `X-Edition-Id` header:

1. `GET /api/planning/sauvegardes-avant-vidage` lists the edition's backups, the most recent first. Each row gives its origin (`RESET`, `IMPORT`, `RESTORE`) and its date (`creeLe`). A `RESTORE` row also says which backup the restore targeted (`restaureId`, empty if that one was deleted) and its outcome (`restaurationAboutie`: `true` completed, `false` not completed or unknown); its row is the state just before that restore. The file of a backup is read through `GET /api/planning/sauvegardes-avant-vidage/{id}`.
2. `POST /api/planning/sauvegardes-avant-vidage/{id}/restauration` restores it.

The restore proceeds in order:

1. the refusals, before any write: solve in progress, frozen family, file that the import would refuse (reading, invalid stands, contradictory ad hoc exceptions, then named by the backup's identifiers);
2. the backup of the current state;
3. the scenario import through the usual import, which also recreates the stands and timeslots that no seat links to;
4. the schedule;
5. the locks.

The response gives `sauvegardeAvant`, the identifier of the backup of the replaced state: restoring that one undoes the restore.

### An operation that is not atomic

The repository has no transaction spanning the import, which is made of several services (see the ADR). A restore that fails after the backup of the current state leaves the edition half restored. The error says at which step it stopped and names the backup of the state just before this attempt: `409` for a business error, `500` for a failure, with the same message in the log. Other, rarer refusals of the import (a consigne, a typical day that the file describes badly) may occur after the parameters have been written: the recovery then applies too.

To recover, restore the backup named by the error, which puts the edition back as it was, or run the restore again once the cause is removed. Each attempt backs up the edition it replaces (except for a state identical to the last backup), so the work done between two attempts is never lost. The error names only the last one: to go back further (before a series of attempts, for example), choose from the list according to the dates, origins, targeted backups and outcomes. Retries after a halfway failure write one row per attempt, because each failure leaves a different state (the recreated timeslots change identifier); they accumulate and are cleaned up by manual deletion. A successful restore whose outcome could not be recorded is reported successful with a warning, its row remaining "not completed".

### How the schedule finds its seats again

The schedule is found again on what does not change on re-import: a timeslot by its date and hours, a staff member by their address then by their first and last name, a stand by its code then by its name, all normalised as the import compares them. A key designates only if it names a single row on each side, otherwise the next one is tried (an address shared by a family lets the name decide). An ambiguous name designates nothing, nor does a namesake whose address is different; a row that two entries designate goes to neither. Better a flagged empty seat than a seat given to somebody else.

The response counts the seats filled, the locks written and those already in place, gives the score of the restored plan (read after the locks) and the warnings, and lists by identifiers what could not come back. A seat whose staff member cannot be found comes back empty, a seat whose stand or timeslot cannot be found does not come back, a stand or timeslot that creation refuses is flagged (`referentielNonRestaure`). The reference data and the schedule come from the same backup, so these lists are normally empty: what appears in them is an anomaly. If no seat of a non-empty schedule comes back, the restore fails and the recovery above applies.

### What does not come back

- Day validations and swap requests are not backed up.
- Technical identifiers change (stands recreated, timeslots, staff members of an emptied edition): a snapshot that named them can no longer be restored, and the action journal keeps the old ones.

Re-importing a backup's file yourself (Files, Import) remains possible but is not the procedure: this import discards stands and timeslots with no seat and does not put the schedule back.

## The SQL dump for test tooling

No screen offers the SQL export to the operator any more. Backing up the database is the nightly `pg_dump` ([ADR 0015](repo:docs/decisions/0015-sauvegarde-par-pg-dump-restauration-hors-application.md), [Backup and restore](../installer/sauvegarde-et-restauration.md)), and exchanging a dataset is the [YAML scenario](scenario-yaml.md). The routes `GET /api/database/export` and `POST /api/database/import` remain for the test suites, which seed and restore the database through them. They answer `404` unless `planning.outillage-test.dump-sql` is `true` (variable `OUTILLAGE_TEST_DUMP_SQL`, set by the `%test` and `%dev` profiles and by the E2E stack), and do not appear in the production configuration. The service is [`DatabaseDumpService`](repo:src/main/java/dev/sylvain/planning/service/export/DatabaseDumpService.java).

The operation is global to the instance: the whole database, all editions included. The import replays the script in one transaction and accepts only `INSERT`, `DELETE` and `TRUNCATE` on the business tables. The edition consignes (`consigne_edition`, `consigne_edition_fenetre`, `consigne_edition_ouverture`, `consigne_edition_creneau`) and their presets (`prereglage_consigne`, `prereglage_consigne_fenetre`) are part of it: they change the seats a solve receives, and a restore without them would rebuild a nominal day that the organiser had closed. Replaying a full SQL export erases all the backups before a wipe, of all editions.

### A dump is replayed on the schema it was taken from

A dump is a list of rows shaped by the migrations applied at the time of the export: the script names the columns and writes the values as the schema stored them, with no conversion. A migration that renames a column or changes its type makes it unreadable for later versions. Its header therefore names the schema of the database that produced it, that is the last migration applied:

```sql
-- Planning Équipes database dump
-- Generated at 2026-10-02T08:00:00Z
-- Schema version: 122
-- Replay with the "Import SQL" admin action.
```

The import refuses with a `400`, before writing anything, a dump whose header names a different schema from the database's, and the message gives both versions. To compare, read `head -5 mon-dump.sql` on one side, and on the other the Debug page, which lists the last migration applied by each version, or:

```sql
SELECT version FROM flyway_schema_history
WHERE success AND version IS NOT NULL
ORDER BY installed_rank DESC LIMIT 1
```

A dump that does not target the same schema is replayed on an instance with the dump's schema, which is then upgraded before exporting a new one. A script without the line (exported before it existed, or a few hand-written statements) is replayed without verification: it is up to the operator to make sure it dates from the same schema. To keep a dataset beyond a version upgrade, it is the YAML scenario export that travels.
