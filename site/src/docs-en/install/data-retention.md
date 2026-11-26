---
twin: installer/conservation-des-donnees
---
# Purge and keep data

You know what the application deletes by itself, what it keeps until you delete it, and you carry out the annual purge that upholds the retention period announced to staff members.

## What deletes itself

Three things delete themselves: the action history, the administrator login log and the result of the e-mails sent to staff members ([what is kept of them](courriels.md#4-lire-ce-qui-est-gardé-de-chaque-envoi)).

`JOURNAL_RETENTION` (90 days by default, in ISO 8601 format: `P30D`, `P1Y`) bounds the `journal_action`, `journal_connexion` and `envoi_mail` tables, which the nightly task purges along the way. Outside the allowed bounds, one day at minimum and ten years at maximum, **the application refuses to start** rather than silently trimming the value, as for `BACKUP_RETENTION`: at `P0D`, every night would empty the whole history with no trace other than an application log line.

The *History* page reads it; nobody writes it by hand and nothing erases it at the push of a button. Lengthening this period is a choice to own: the table goes into every backup, and it is a processing operation recorded in the register ([what a host must keep](rgpd.md)).

## What stays until you delete it

For everything else, the period announced by the `conservation` key of `LEGAL_MENTIONS` is displayed in the privacy policy and **nothing in the code enforces it**: no automatic purge exists today. A written commitment that is not kept exposes you more than no commitment, so the purge is done by hand, on a fixed date. This is deliberate, and what automation will have to carry when the day comes is written in [decision 0016](repo:docs/decisions/0016-purge-manuelle-avant-automatisation.md).

### The annual purge

To be done once a year, after the event:

1. export a dump ([back up by hand](sauvegarde-et-restauration.md#2-sauvegarder-à-la-main)) and archive it **encrypted**: it is the trace;
2. from the *Editions* screen, delete the **editions** whose retention period has expired. Deletion cascades over the tables carrying `edition_id`: staff members, schedules, snapshots and tokens go with it;
3. check that the staff espace of a deleted token does answer `404`;
4. record the date and scope in the register of processing activities, in the form expected by [the purge log](rgpd.md).

### Erasure requested during an edition

The procedure above does not cover the data of an edition that is **still active** when a staff member has asked for erasure. Today, it is a manual deletion of their record, **then of the backups taken before an edition is emptied that still carry it**, that is, all those taken before the record was deleted. You delete them one by one with `DELETE /api/planning/sauvegardes-avant-vidage/{id}` (logged), after listing them with `GET /api/planning/sauvegardes-avant-vidage` (the GDPR framework is in [the limits to record](rgpd.md)).

These backups are **never purged automatically** ([decision 0084](repo:docs/decisions/0084-sauvegarder-l-edition-avant-de-la-vider.md)): one per emptying (reset, import, restore), unless the state is identical to the previous one, kept until the edition is deleted, which carries them away in cascade. The annual purge therefore covers them. Each weighs from a few hundred kilobytes to a megabyte; deleting those that are no longer needed is an administrator's action, through the same route.

Automating the purge would not settle this case: it reasons by expired edition.

### The 3.x carpool archive

The 4.0 migration moves the carpool requests that 3.x had rejected or cancelled, with the free-text reason written for the staff member, into the `declaration_coequipier_avant_4_0` table ([what it holds](rgpd-limites-a-consigner.md#les-demandes-de-covoiturage)). The application no longer reads it and does not export it. It is **kept until 5.0**, which drops it; until then, it goes with its edition (the annual purge covers it) or with the requester's record.

Once 4.0 is in place and going back to 3.x is ruled out, nothing forces you to wait: `DROP TABLE declaration_coequipier_avant_4_0;` drops it, with no effect on the application. Record it in the register as a purge.

### The 3.x publication exclusions archive

The 4.0 migration stores, in the `exclusion_publication_avant_4_0` table, the people whose last publication line was a deferral: the identifier of the person, of the edition and of the published version, and the date. No name, no free text. The application no longer reads it and does not export it. It is **kept until 5.0**, which drops it; until then it leaves with its edition (the annual purge covers it) or with the person's record. Once 4.0 is in place and going back to 3.x is ruled out, `DROP TABLE exclusion_publication_avant_4_0;` removes it, with no effect on the application. Note it in the register as a purge.

## Measuring the realised

No scheduled task computes it: the "Edition measures" screen computes it on demand ([ADR 0092](repo:docs/decisions/0092-le-realise-se-calcule-a-la-demande.md)). The first read made once the edition's last timeslot has ended, when something has been published, writes the measure into `mesure_edition` (nature `REALISE`): absences, replacements, empty seats and lost hours, by game category, with no person at all.

This is what the next edition reads on its Versions screen and on the Need tab: that read first computes the measure of every finished and published edition that has none, and deleting an edition writes its own before erasing it. There is therefore nothing to open by hand. A simulated clock writes nothing.

Removed variables: `REALISE_CRON` and `REALISE_TIMEZONE` are no longer read (the old nightly tasks are deleted). Leaving them in your environment has no effect, but you can remove them.

The measure **is written only once** per edition: a correction made afterwards (an absence entered the next day) does not change it. It is not written, and the application log says so, as long as the frozen past is disabled (`PASSE_FIGE=false`): the realised is no longer guaranteed there. The measure **survives the deletion of the edition**, which is its purpose.

To **re-freeze** an edition after a correction that must count, there is no button: delete its rows, and the next read rewrites them from the plan of the moment.

```sql
DELETE FROM mesure_edition WHERE nature = 'REALISE' AND edition_id = '<id de l''édition>';
```

The identifier is read on the Editions screen. The deleted measure also disappears from the next edition's Versions screen until the read that rewrites it; an edition deleted in the meantime will never be re-frozen.
