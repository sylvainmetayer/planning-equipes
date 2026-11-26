---
ordre: 150
---

### The copy before a wipe becomes a YAML export

- **Before (3.x)**: before "Empty this edition", a scenario import or a restore, the application stored the edition's scenario (YAML text) **and**, in a separate JSON column (`sauvegarde_avant_vidage.plan`), its schedule; the restore ran the import, then dedicated code put the seats and locks back.
- **After (4.0)**: the copy is **one YAML export**, whose last section, `plan:`, carries the seats and locks; the restore is the normal import of that file, which puts the schedule back ([ADR 0112](repo:docs/decisions/0112-la-copie-avant-vidage-est-un-export-yaml-avec-son-plan.md)). Migration `V147` only makes the `plan` column optional: **no row is copied, rewritten or deleted**. The copies already stored by 3.x remain restorable and downloadable: their column is read as the `plan:` section of the same file. The downloaded file of a recent copy carries that section; it remains a valid v3 scenario; imported by hand, it brings back only the scenario (the `plan:` section is ignored with a warning), only the restore puts the schedule back. The rights (administrator only) and the retention (that of the edition, no purge) do not change.
- **What you do**: nothing for your copies. If your processing register describes "scenario and schedule by identifiers", it stays accurate; add that the file is a full YAML export, to be kept like the database ([copies before a wipe](rgpd-limites-a-consigner.md#les-copies-avant-vidage)). The read path of the old column is removed in 5.0, like the v1 reader: before 5.0, restore or delete the 3.x copies you still need.
