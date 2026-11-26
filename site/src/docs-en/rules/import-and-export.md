---
twin: regles/importer-exporter
---
# Import and export files

You fill an edition from your spreadsheets, export what you want to correct or share, and archive the edition at the end of the event.

It all happens in *Files* (*Prepare* group), a screen with three tabs: *Import*, *Export*, *Archive*. The staff screen also has its own "Import" button, which opens the same CSV card without leaving the list. The full cycle comes down to three gestures: export, correct, re-import.

Is a spreadsheet your starting point? [Moving from a spreadsheet to scheduling software](/tableur-ou-logiciel-de-planning/) says what this change brings, and when it is not necessary.

## Import

The *Import* tab offers four cards:

1. the **YAML scenario**, the complete workbook of an edition: game categories, locations, stands and their hours, timeslots, template days, staff, adjustments, settings. It is **the only one that replaces the edition** instead of completing it;
2. the **staff**, from a CSV spreadsheet;
3. the **examples** shipped with the application;
4. the **check of a scenario file**, which writes nothing.

![The file import screen](/images/fr/docs/fichiers.webp "The Files screen, Import tab")

The CSV imports of game categories, locations, stands, timeslots and template days, and the stand grid one, no longer exist since 4.0: the YAML file replaces them. To take your stand grid back, export the edition as YAML (*Export* tab), rewrite the stands' hours in that file, then import it again: [Moving from 3.x to 4.0](../installer/passer-a-la-4-0.md#les-imports-csv-des-référentiels-et-de-la-grille-des-stands-sont-retirés) gives the exact notation.

The common rules:

- No identifier is ever entered or read in a CSV file: the application assigns its own (A1, A2... for staff), numbered within each edition. A staff member is designated by their e-mail address, otherwise by their last and first name.
- A missing column erases nothing, and a row that is already known is updated.
- The staff CSV also accepts cells copied from a spreadsheet, read like a file. Once the import is written, a link opens the staff list on the imported rows.

### Import your staff from a spreadsheet

Your volunteers' spreadsheet arrives as it is.

1. Say which column is which field.
2. Read, row by row, what the import would do: accepted, or rejected with the reason and the row number in the file. Nothing is written until you confirm.
3. Confirm.

By default the import adds and updates without deleting anyone, and completes the unavailability days already declared instead of erasing them. An example CSV can be downloaded from the screen.

### Load an example or a scenario

A **scenario file** describes a complete event configuration; the import validates the file and explains what is wrong. A file can also be checked without importing anything.

The examples shipped with the application are loaded from the **Examples** card of the Import tab. Each has a readable name, its size and what it shows; they are sorted "to discover" and "to test a case". The solve trial ladder and the extreme cases, shipped but not listed, are loaded by name.

Before replacing an edition with a scenario, the application keeps a copy of it: see [Chain editions](enchainer-les-editions.md#repartir-de-zéro).

## Export

The *Export* tab offers two forms.

- The ZIP archive: one CSV per type of reference data (game categories, locations, stands, timeslots, template days, staff), each one tickable, to open in a spreadsheet. Only the staff file reads back in.
- The scenario file: the whole edition in a single file, the one the Scenario card of the imports reads back. It is what lets you copy one edition onto another, replay a year's calendar the next year, or correct in bulk and then re-import.

Each reference data list also offers "Export this list", which downloads as CSV what the table shows.

## Archive a finished edition

The *Archive* tab keeps a record of a finished edition in one click. The edition status offers it once the last day of the event has passed.

A single ZIP gathers, as you choose: the overall schedule, fairness, hours worked, the reference data, the scenario that can be re-imported, the publication review and the individual schedules. A manifest, readable without the application, says which plan these files come from, when it was solved and published, and how many days were reviewed.

The files are those each screen would have downloaded. No access token appears in them, and the download is recorded in the history.

## The formats in detail

The exact columns of the staff CSV file are described in [cSV imports](../comprendre/imports-csv.md), the scenario schema in [the scenario file](../comprendre/scenario-yaml.md) and how to go back on an import in [restoring an edition](../comprendre/restaurer-une-edition.md).
