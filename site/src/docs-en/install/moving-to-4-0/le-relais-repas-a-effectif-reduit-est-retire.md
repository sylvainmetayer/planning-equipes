---
ordre: 246
---

### The reduced-staffing meal relay is removed

- **Before (3.x)**: a shift could carry `couverturePause` (the "Meal relay" box of the timeslot form, an `R` in a template's shift line, the `couverturePause` CSV column, an argument of the `creer_creneau` and `modifier_creneau` tools). The stand then received only half its seats, rounded up. No planning rule read that flag: it only shaped seat generation.
- **After (4.0)**: the flag no longer exists. A shift is staffed at the stand's full headcount, or at the headcount of its opening window when that names one. Migration `V170` drops the column from timeslots and template shifts; the `couverturePause` and `contientCouverturePause` keys and the `RELAIS_REPAS_HORS_FENETRE` anomaly type disappear from the API, the MCP tools and the CSV export. The generator report's `misAJour` count disappears too: it only counted these flips.
- **What you do**: nothing if none of your shifts was flagged (the shipped editions have none). Otherwise the migration writes to the startup log how many shifts carried the flag, and those shifts now ask for the full headcount: to keep a reduced headcount at noon, give it an opening window with its own headcount (*Stands › Horaires*). A YAML file or a copy that still carries `couverturePause` imports: the key is ignored and an import-report warning says so. A script that sends it to `POST /api/creneaux` or to the MCP tools must stop doing so; the `R` of a shifts line (`12:00-13:00 R`) is now refused.
