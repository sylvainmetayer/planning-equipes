---
twin: regles/importer-exporter
---
# Import and export files

You fill an edition from your spreadsheets, export what you want to correct or share, and archive the edition at the end of the event.

It all happens in *Files* (*Prepare* group), a screen with three tabs: *Import*, *Export*, *Archive*. Each reference data screen also has its own "Import" button, which opens the same import card without leaving the list. The full cycle comes down to three gestures: export, correct in the spreadsheet, re-import.

Is a spreadsheet your starting point? [Moving from a spreadsheet to scheduling software](/tableur-ou-logiciel-de-planning/) says what this change brings, and when it is not necessary.

## Import

The *Import* tab offers one card per type of reference data, in the order the data depends on each other:

1. game categories;
2. locations;
3. stands;
4. timeslots;
5. template days;
6. staff;
7. the stand grid;
8. finally the scenario file, **the only one that replaces the edition** instead of completing it.

![The file import screen](/images/fr/docs/fichiers.webp "The Files screen, Import tab")

The common rules:

- The first three files ask for the minimum: a code and a label (game categories), a code and a name (locations), a code, a name and game categories (stands). A stand's headcount and a location's coordinates remain optional.
- Timeslots are recognised by their date and their two times rather than by an identifier.
- Template days are recognised by their name. Their shifts fit on one line ("09:00-12:00, 12:00-13:00 R, 14:00-20:00"). Their dates complete the calendar without replacing it: the grid only moves when you apply the calendar.
- A missing column erases nothing, and a row whose code is known is updated.
- A game category that is cited without existing is created, after being announced.
- No identifier is ever entered or read in a file: the application assigns its own (A1, A2... for staff, S1... for stands), numbered within each edition. A row is designated by its code, otherwise by its name; a staff member by their e-mail address, otherwise by their last and first name.
- Each card also accepts cells copied from a spreadsheet, read like a file. Once the import is written, a link opens the screen of the imported rows.

### Import the stand grid

The matrix of your workbook arrives as it is: stands in rows, days and timeslots in columns, one headcount per cell.

1. Each column is placed on its timeslot; an unknown column is ignored and listed.
2. Review the preview row by row: each stand has its hours rewritten from its cells.
3. Confirm.

The current grid can be downloaded as a template.

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

- The ZIP archive: one CSV per type of reference data (game categories, locations, stands, timeslots, template days, staff), each one tickable, and each in the exact form the matching import tab reads back. This lets you copy one edition onto another, replay a year's calendar the next year, or correct in bulk in a spreadsheet and then re-import.
- The scenario file: the whole edition in a single file, the one the Scenario card of the imports reads back.

Each reference data list also offers "Export this list", which downloads as CSV what the table shows.

## Archive a finished edition

The *Archive* tab keeps a record of a finished edition in one click. The edition status offers it once the last day of the event has passed.

A single ZIP gathers, as you choose: the overall schedule, fairness, hours worked, the reference data, the scenario that can be re-imported, the publication review and the individual schedules. A manifest, readable without the application, says which plan these files come from, when it was solved and published, and how many days were reviewed.

The files are those each screen would have downloaded. No access token appears in them, and the download is recorded in the history.

## The formats in detail

The exact columns of the CSV files are described in [the CSV imports](../comprendre/imports-csv.md), the scenario schema in [the scenario file](../comprendre/scenario-yaml.md) and how to go back on an import in [restoring an edition](../comprendre/restaurer-une-edition.md).
