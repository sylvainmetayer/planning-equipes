---
twin: comprendre/imports-csv
---
# CSV imports

You know what each CSV import writes, how a row recognises an existing record, which checks run before anything is written, and why some choices (absent columns, two-digit years, a full replacement that is refused) protect an edition's data.

The YAML scenario describes a whole event and replaces the edition's reference data ([The YAML scenario file](scenario-yaml.md)). The only CSV import left does the opposite: it touches only the staff, row by row, and deletes nothing by default. It is what you use when an organiser turns up with a spreadsheet of volunteers. Everything is read and written in the edition named by `X-Edition-Id`.

A volunteer spreadsheet is the typical case; to decide whether to leave the spreadsheet behind, see [spreadsheet or scheduling software](/tableur-ou-logiciel-de-planning/).

The Import tab of the Files screen (`/fichiers`) gathers the YAML scenario, the staff CSV, the examples and the validator, one card each, the open card being carried by `?cible=`. The "Import" button of the Staff screen opens the CSV card in a dialog. The routes are `/api/animateurs/import-csv/analyse` (preview, nothing written), `/api/animateurs/import-csv` (write) and `/api/animateurs/import-csv/exemple`; the exact list is in the OpenAPI contract ([The reference data on the API side](api-referentiels.md)).

## What 4.0 removed

Six imports are gone: the CSV of game categories, locations, stands, timeslots and day templates, and the stand grid matrix ([decision 0128](repo:docs/decisions/0128-le-yaml-est-le-classeur-complet-le-csv-ne-sert-qu-aux-animateurs.md)). The YAML file describes the whole edition; writing it once writes every reference set, in the order the application applies it, with a coherence check that six separate formats could not offer. The staff CSV stays, because a volunteer spreadsheet is the one file organisers already keep that carries people.

Their eighteen routes (`/api/<référentiel>/import-csv`, `/analyse` and `/exemple`, for the five reference sets, and `/api/stands/import-grille` with its two siblings) answer `410 Gone`, whatever body is sent, with a message pointing at the YAML import: a script written for 3.x learns where to go instead of running into a `404`. They read and write nothing and no longer have a line in the action journal. On screen, a bookmark to one of their cards (`/fichiers?cible=stands`) lands on the Scenario card. The CSV exports are untouched: they serve a spreadsheet, and only the staff one reads back in.


## The staff CSV import

A partial import: it touches only staff, one row at a time, and deletes nothing unless asked to. Staff card of Files (`/fichiers?cible=animateurs`), "Import" button of the Staff page, routes `POST /api/animateurs/import-csv/analyse` (pure read-only preview) and `POST /api/animateurs/import-csv` (write).

A paste from a spreadsheet is accepted on the card: the cells, separated by tabs, are rewritten as CSV with `;` and each cell in quotes, then sent as a `collage.csv` file. The quotes are needed because the server recognises the separator by counting `,`, `;` and the tab outside quotes and must not take a comma inside a cell for it. Once the import is written, "See the N imported rows" opens the staff screen filtered by `?ids=`; "Show all" removes the filter.

![Staff CSV import: preview, then write](repo:docs/diagrammes/import-csv.svg)

<sub>Source: [`diagrammes/import-csv.puml`](repo:docs/diagrammes/import-csv.puml).</sub>

### The sample file

[`exemple-animateurs.csv`](repo:src/main/resources/scenarios/exemple-animateurs.csv), served by `GET /api/animateurs/import-csv/exemple`, holds a dozen made-up people over the nine columns the import reads, and every rule of the format has a row in it: three minors during the event (one of whom turns adult four days after the end), a manager, skills with and without a level (`JEUX:DEBUTANT|ENF`, `DIV|REF:REFERENT|TROPH`), wishes, multiple unavailability days. Its dates are written `AAAA-MM-JJ` (year-month-day): saved again in a spreadsheet, `12/09/2026` comes back as `12/09/26`, whereas the ISO form comes out intact from Excel and LibreOffice alike.

Its game categories and unavailability days come from `festival-realiste-canicule` (1 to 16 September 2026), because the import refuses a row that names anything else: dropped into an edition with different dates or game categories, it gets rows rejected. It is a model of the form, with invented people. `AnimateurCsvExempleTest` re-imports it through the real reader (zero rows rejected, header recognised on its own); a regenerated fixture that renames a game category makes it fail, and the CSV is then touched up by hand.

### A CSV reader, and only CSV

[`CsvParser`](repo:src/main/java/dev/sylvain/planning/service/referentiel/CsvParser.java) is written by hand, with no dependency. It follows RFC 4180 and recognises what a spreadsheet produces: separator `,`, `;` or tab (the most frequent outside quotes wins), doubled quotes, CRLF or LF, byte order mark, line breaks inside a cell. An `.xlsx` or `.ods` is refused with the way forward. Each line of the report carries its physical line number in the file, the only landmark the organiser can act on.

The file must be UTF-8. Excel in French still saves in Windows-1252: the browser then reads each accent as an unreadable character long before the server receives a byte. The import is refused by naming the encoding and quoting an excerpt, rather than answering "this is not CSV" to someone who has just saved one. A truly binary file (a NUL, or more than a tenth unreadable) remains a format refusal.

### The column mapping

The screen proposes a mapping from the headers, in French as in English, then lets you change everything, including clearing everything (an empty mapping sent explicitly is respected). It designates columns by their position, never by their name: a file can carry the same header twice, or none. A column already taken by another field is moved, never shared. An unmapped column is not an empty column: the field is not touched on existing records, which makes a three-column catch-up file harmless.

`competences`, `souhaits` and `joursIndisponibles` accept several values per cell, separated by `|`, `;`, `,` or a line break, never `/`, which dates such as `18/07/2030` use. A skill is written `typologie` or `typologie:REFERENT`.

### Dates and times

Dates are read in the dialects a spreadsheet writes (`2026-07-10`, `10/07/2026`, `10-07-2026`, `10.07.2026`), and so are times (`09:00`, `9:00`, `9h`, `9h30`, `9`, `09:00:00`). A two-digit year is resolved towards the future (the last ten years and the next eighty-nine): on an edition date, `27` is next season, not 1927. This is the opposite of a date of birth, detailed below.

### The two-digit year is read, and reported

A spreadsheet retypes every cell that looks like a date and saves it again in short form: `2000-01-01` comes out as `01/01/00`. Refusing the row would send the organiser back to a file that the same spreadsheet would rewrite at the next save. The import therefore reads `J/M/AA`, `J-M-AA` and `J.M.AA` (day before month) with a pivot: the year is the most recent possible among those ending in these two digits, up to a ceiling that depends on what the date designates.

| Date (today 2026-09-11) | Ceiling | Reading |
| --- | --- | --- |
| Birth `01-01-00` | today | 2000-01-01 |
| Birth `12/09/27` | today | 1927-09-12 |
| Birth `12.09.26` | today | 2026-09-12, then refused as implausible, quoting this reading |
| Unavailability `5/9/26` | last day of the event | 2026-09-05 |

The ceiling of an unavailability is the last day of the event, not today: an event is prepared the year before, and read against today `18/07/27` would land in 1927 and then be refused as "hors des dates de l'événement" (outside the event dates) for a reason nobody wrote. Resolution stays strict (`31/02/26` is not a date) and the message advises opening the CSV through the spreadsheet's import wizard, forcing the date column to the "Text" type. Each row read with two digits carries a warning with the date understood: the preview is where this interpretation passes under human eyes before the write. The plausibility check (future date, more than 120 years) applies to the date as read.

### What a row uses to recognise a record

Two keys, in this order: the e-mail address, then "first name + last name" compared without case or accents. A row that recognises no record creates one, under the edition's next identifier. The name is the last resort and it can be ambiguous: a row whose name designates two records is rejected, naming the identifiers, rather than picking one. Two rows of the same file that designate the same person: the second is rejected, quoting the number of the first one actually retained. A row rejected for another reason does not occupy the identity, otherwise the right row would be refused in favour of a wrong one.

### Probable duplicates: flagged, never refused

Recognition never reads the date of birth. Two rows carrying two addresses for the same person therefore create two records, and a row with no address attaches by its name alone to a namesake, whose date of birth (and so minor or adult regime) it rewrites, along with the address where the access codes go. A duplicate weighs on the solver: it sees two people, counts their legal ceilings separately and may assign the same person twice at the same moment.

The preview therefore compares each accepted row on the key (first name, last name, date of birth), first and last names normalised as for recognition, over the headcount the write would leave. A row cites at most three other rows and three records and counts the rest ("Probable duplicate of rows 3, 4, 5 and of 2,996 others"): without this cap, three thousand identical rows would each cite the 2,999 others.

| Case | Signal | `doublonDe` |
| --- | --- | --- |
| Two accepted rows, same key | "Probable duplicate of row 12", on both | `ROW` |
| The row gives its record the key of a record that is kept | "Probable duplicate of record A12" | `FICHE` |
| Same in full replacement, against a deleted record | deletion of a person immediately described again | `REPLACED` |
| Attachment by name alone to a record born on another day | "Namesake?", both dates | `NAMESAKE` |
| Attachment by name alone, the address designating nobody | "The e-mail address of record A12 will be replaced" | `NEW_ADDRESS` (absent if `NAMESAKE` already designates the record) |

These are warnings, the row stays accepted: twins share a name and date of birth, two namesakes are born on the same day, and the operator decides, informed. Not flagged are two records that already carried the same key before the file (repeating it at every re-import would teach people not to read the warning) nor a rejected row. The report counts the flagged rows (`doublonsProbables`), the screen marks them with a badge and the "Import" confirmation repeats their number. Messages designate a record by its identifier, never by name or address; only "Namesake?" quotes dates of birth, without which one cannot decide, in a report that only the import screen displays (not notified, not journalled, not exposed over MCP).

### What a row must carry

A first name, a last name and a date of birth: the three that the record form and the `creer_animateur` MCP tool refuse to omit, the CSV not being a back door to a record the screen would refuse. The date is indispensable: the whole minor or adult regime is deduced from it at the date of each timeslot, and the column is `NOT NULL`. A row that updates a record recognised by its address may leave any of the three empty.

Cells are bounded by the column widths: 128 characters for first and last name, 255 for the address. A longer value rejects the row, giving its length, the signature of a column mapped to the wrong field; without this check the preview would be all green before the write fails on the whole file. The game categories cited go row by row through the check of a record's input (`TypologieService.validateIds`): an unknown game category costs one row, not the file.

### Unavailability days

| Situation | What the import does |
| --- | --- |
| Day outside the timeslot dates | Row rejected, reason in the report. The staff espace draws only the days of the event: such a day would be stored, invisible, then erased by the first accepted declaration |
| No timeslot in the edition | Import refused as a whole (`409`) before anything is read: without reference dates, nothing can be checked |
| The staff member has a declaration pending | Row accepted with a named warning: the administrator's decision will replace the imported days |
| The staff member already exists | Addition by default: the file's days complete those of the record. A checkbox on the screen switches to replacement |

Addition is the default because a catch-up import must not erase what staff declared themselves during the collection. Skills and wishes are always added: removing a skill remains a gesture of the reference screen.

### Addition or full replacement

YAML replaces everything, CSV does not, unless explicitly asked. Unchecked (the default), the import adds and updates. Checked, the staff members absent from the file are deleted and their seats in the persisted schedule are released, not destroyed. Full replacement is refused as long as one row is rejected, otherwise it would delete precisely the people those rows failed to describe (the button is disabled, with the reason).

The preview counts what the deletion carries away (seats released, locks removed, ad hoc exceptions concerned) and names what is cascaded away with no way back: availability declaration, acknowledgement of receipt of the published schedule, swaps of the schedule swap fair, access code to the staff espace, skills and wishes.

### Two calls, one transaction

Analysis and write are two endpoints, and the second receives the file rather than the report: it re-reads everything and replays every check, so a page reload, a replay or a hand-forged body lets no row past a check. The write is atomic (a single transaction), and the report never announces 148 rows written after a rollback. The file is processed in memory and never written to disk, because it carries names, dates of birth and sometimes minors. Two ceilings bound it: 1,000,000 characters and 5,000 data rows.

## The skills grid no longer goes through a file

The staff × game categories matrix of appreciations is no longer exchanged by file, neither in export nor in import: it is entered exclusively on the `/animateurs?onglet=competences` screen ([ADR 0050](repo:docs/decisions/0050-identifiants-generes-par-edition.md)). It is saved by `PUT /api/animateurs/competences/grille`, one row per modified record, each with its own concurrent-modification precondition ([ADR 0023](repo:docs/decisions/0023-modification-concurrente-par-horodatage.md)). The response is a report per row (written, stale, refused) and a refused record does not cancel the others. Two things refuse the whole call: a level that is not one of the three names (deserialisation rejects it before the service) and a body of more than 2,000 rows.
