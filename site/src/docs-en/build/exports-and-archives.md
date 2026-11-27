---
twin: comprendre/exports-et-archives
---
# Exports and the end-of-event archive

You will know what each export contains (CSV of hours and fairness, individual and overall PDF, ICS), why they open correctly in a spreadsheet, and how the end-of-event archive brings them together without copying anything.

Everything is generated server-side, in the edition designated by `X-Edition-Id`. The exports are on the Files screen (Export tab, `/fichiers?onglet=exporter`) and on the screens that produce them (Planning, Publication, the staff espace).

## CSVs: shared conventions

Every CSV the server returns goes through `CsvDownload.attachment` ([`CsvDownload`](repo:src/main/java/dev/sylvain/planning/api/CsvDownload.java)), which prefixes the content with a UTF-8 BOM. Excel ignores the response's `charset=utf-8` as soon as the file is on disk and falls back to the system code page: without the mark, an accented name shows up as `Métayer`. LibreOffice does not need it and is not bothered by it. `CsvParser` strips a leading mark before reading the header, so a template downloaded and sent back as is imports identically (two tests check this on the file actually served).

Decimal numbers take a comma (`4,00`) and the field separator stays `;`: the pair a French spreadsheet expects. With a point, the cell is read as text, the column aligns left and `=SUM()` answers zero, which is worse than a wrong total because it looks like an answer.

A text that a spreadsheet would execute as a formula (it starts with `=`, `+`, `-`, `@`, a tab or a carriage return) comes out preceded by an apostrophe, the mark Excel and LibreOffice read as "this is text" (`CsvFormulaGuard`). A name typed into a form must not become a formula in the organiser's spreadsheet. A number does not carry one: a negative longitude stays a number, and so does a time or a total of hours (`12:30`, `-1,50`). The rule holds for every CSV of the server, including `heures-planning.csv`, `equite-planning.csv`, `plan-formation.csv`, `intendance-repas.csv` and `diff-publication.csv`. Each CSV import removes this apostrophe on reading, so an exported file re-imports identically; "Export this list" on the reference data screens applies the same rule in the browser.

### The six reference data sets

`GET /api/reference-data/export-csv` rewrites the edition's six reference data sets (staff, timeslots, locations, templates, stands, game categories) into a ZIP, one file per set, each one tickable, for a spreadsheet. Only the staff file reads back in, through the [CSV import](imports-csv.md): it reuses the header of `exemple-animateurs.csv` and needs an edition that already has its timeslots, since without dates a day of unavailability would be refused. The other five are not read back: the edition's YAML is their way in.

`ReferentielCsvExportServiceTest` replays the staff file into another edition (zero rejected lines) and checks that the records come out identical, multi-value cells included.

A cell that contains a possible separator (`;`, comma, tab) comes out in quotation marks, and not only when it contains the file's own. The reader guesses the dialect by counting the three candidates outside quotation marks over the whole file: a column of shifts puts three commas per line against two semicolons in the header, and the file you had just written was read back as a comma file.

## Hours for payroll

`GET /api/planning/hours/export?source=publie|persiste` returns `heures-planning.csv` ("Hours for the payroll (CSV)" on the Planning page): one line per staff member, one column per ISO week (`2026-W33`), then `total`, `dimanche`, `jours feries`, `dont dimanches feries`, `nuit paie (apres 22h)`, `amplitude max (h)` and `jours au-dela du seuil d'amplitude`. The night column is the only trace of the fixed 22:00 boundary in the application: the evening is the adjustable hour of the legal settings, and payroll keeps its night apart ([ADR 0055](repo:docs/decisions/0055-une-seule-soiree-la-nuit-de-la-paie-a-part.md)). `GET /api/planning/hours` returns the same report as JSON, with the source read, its date and `publicationAvailable`.

The server reads the plan, the browser does not send it. The file must be guaranteeable in front of a payroll or an inspection, which a schedule posted by a tab did not allow: it said what the tab held at one instant, neither what the database contained nor what had been published. Two sources are possible:

| `source` | What is read |
| --- | --- |
| `publie` | the publication in force: the seats frozen at publication, reread as the staff espace rereads them, against today's reference data |
| `persiste` | the saved plan |

Without a parameter, it is the publication when there is one, the saved plan otherwise. `publie` before any publication is a `409` (a state of the edition): an empty file would wrongly say that nobody worked. For the published plan, a timeslot that still exists gives its current hours (a deleted timeslot keeps those the publication recorded), the deducted breaks and the evening follow the current legal settings, and a staff member or stand deleted since drops out of the file. The file therefore repeats identically from one call to the next only as long as neither the publication nor this reference data moves.

The first line names the source and its date (`Source : plan publié le 12/07/2026 à 18:30`, or `Source : plan enregistré, résolu le …`). The saved plan has no date other than its last solve or restoration: a seat touched up by hand leaves none, and the file says which one it is rather than passing it off as the date of the last modification. The column header line comes next.

## Fairness

`GET /api/planning/equite/export` returns `equite-planning.csv` ("Fairness (CSV)" on the Planning page): the fairness table read on the persisted plan, one line per assigned staff member, in the screen's order. The columns are `heuresTotal`, one per ISO week, then `heuresSoiree`, `heuresWeekEnd`, `heuresJourFerie`, `postes`, `postesPenibles`, `standsDistincts`, `typologiesDistinctes`, `emplacementsDistinctsParJourMax`, `tauxSouhaits`, `tauxAppreciation`, `joursTravailles`, `joursRepos` and `plusLongueSerie`. The rates are ratios (`0,75`), which a spreadsheet turns into a percentage in one click. The summary (median, min, max, standard deviation) is not there: it is recomputed in the spreadsheet, and the screen shows it.

## The individual schedule as a PDF

The document handed to a staff member is a booklet in three parts, in this order, each page numbered "n / N". It shows the seat's effective hours and not the wider ones of its timeslot: a seat shortened by a partial closure shows the hours actually covered. When the stand has a geocoded location, the PDF carries an OpenStreetMap link.

1. Overview. The header (name, edition, period, number of days including rest days), four figures (hours, timeslots, stands, days worked), the "Your N days at a glance" timeline (one line per day, rest days included, on a common time axis, one bar per seat, the day's total on the right), the colour legend, then the stands grouped by location with their game category.
2. Your days. One block per day, then one card per timeslot: effective hours, stand, game category, place (clickable when the location is geocoded), teammates, duration. Meals and breaks stay under the shift that owes them, in the same words as the staff espace. A rest day keeps its line, a day under a consigne carries its banner ("Horaires modifiés — …", hours changed), and a day is never split between two pages.
3. With whom, and where. Teammates counted (most frequent first), shifts by number (set-up, teardown) with their headcount, places with the dates spent there.

Two basic rules. A bar's colour carries the stand's game category, never the stand: twelve stands over six categories give a readable legend. The reference data stores no colour, and `TypologiePalette` assigns eight hues in the order of the identifiers, which makes the document reproducible from one run to the next; a stand with several game categories takes the first. And beyond eight teammates on the same line (`AnimateurPlanningView.SEUIL_NOMS`), the document writes the headcount ("With 104 people") and no name: set-up gathers a hundred people, and the list of names tells nothing.

A staff member with no assignment keeps the empty state ("Aucune affectation pour cet événement.", no assignment for this event) and the link to their espace. This link is the espace's authentication data: it is long and nobody copies it from a printed sheet, hence the QR code in the "Votre espace en ligne" (your espace online) box (`QrCodeEspace`, `zxing-core` encoding, modules drawn as vectors). The address stays written below, for a photocopy that is too pale and for whoever wants to see where the code leads. On the booklet, the box closes the overview when it leaves room for it and closes the document otherwise, because a band alone on its own page would cost a sheet for one sentence.

### The booklet or the sheet

The folded sheet is an option of the individual PDF: the `feuille=true` parameter. The former `format=livret|feuille` parameter is still accepted as an alias (links already printed, mails, MCP clients) and `feuille=true` wins. An unknown value means `livret`: a typo on a download does not deserve a 400.

| Option | Document | For whom |
| --- | --- | --- |
| (default) | A4 portrait, several pages, the three parts above | the staff espace, the publication mails, a staff member's export |
| `feuille=true` | one A4 landscape double-sided sheet: front a calendar with one week per line with mini-timeline and timeslots, back the teammates, the large teams and the places | bulk printing by the organisation, one print per person instead of five |

Both name the edition under the staff member's name (a person returning from one year to the next has two documents to tell apart) and compose the same internal view, `AnimateurPlanningView`, which keeps them from saying two different things. Both outputs are offered to the staff member ("Download the PDF booklet", "Download the double-sided sheet") as to the organisation (Publication screen). The `feuille` parameter is accepted on `GET /api/espace-animateur/{jeton}/planning.pdf`, `POST /api/planning/export/pdf/animateur/{animateurId}`, `POST /api/planning/export/pdf/all` and `POST /api/planning/export/bundle/all`.

## The overall schedule as a PDF

`GlobalPlanningPdf` produces the organisation's PDF in A4 landscape, a document with a table of contents rather than two tables (`GET /api/planning/export/pdf/global`). The day badges, stand names and letters of the alphabet are internal links, because a sixty-page schedule you have to scroll through is a schedule nobody reads to the end.

1. Table of contents. The period, five figures (days, stands, seats and the unfilled ones, staff, hours) and the section entries.
2. Overview. The stands × days grid: the number of different staff members present on the stand that day, on a four-step scale (1-2, 3-4, 5-8, 9 and more), stands grouped by location, with a last line "Animateurs présents" (staff present) per day. Beyond 24 days, the grid is split over several pages rather than shrunk to nothing.
3. By day. One day per page: stands in rows, opening windows in columns, names inside, "—" for a closed stand. Beyond eight distinct windows, the day is written one line per timeslot.
4. By stand. Each stand, day by day, in the same form.
5. Staff from A to Z. One line per person: total hours, number of timeslots, hours of each day, on the same scale.

Seats that nobody holds are written in the accent colour ("Aucun animateur affecté", no staff member assigned; "3 non pourvus", 3 unfilled): this is exactly what the organiser opens this document to find. White labelling applies to both PDFs: logo, banner and palette come from the `pdf-*` keys of `BRANDING_IMAGES` and `BRANDING_COLORS` (`PdfTheme`), the overall schedule's scale is the deployment's accent diluted in white, and the provenance footer (who generated the document, when, from which edition and which plan) is the same everywhere.

## The ICS calendar

The same ICS document is served in two ways, which differ by mode, the format staying the same: as a download (a snapshot at the moment of the click, `POST /api/planning/export/ics/animateur/{animateurId}` or `POST /api/planning/export/ics/all`) or as a subscription, on a permanent address that the calendar polls by itself, `GET /api/abonnements/{token}/planning.ics` ([Access and accounts](acces-et-comptes.md)). The events' `UID`s are stable from one call to the next, a condition for a calendar client to update its appointments instead of creating duplicates. Like the PDF, the ICS shows the seat's effective hours, and a geocoded stand gives `LOCATION` and `GEO`.

## The end-of-event archive

`GET /api/exports/archive-evenement` (Archive tab of Files, `/fichiers?onglet=archive`) writes the exports of a finished edition in a single ZIP. Each part is opt-in through a boolean parameter; asking for none is a `400`; a ZIP reduced to its manifest would be misleading.

| Parameter | ZIP entry | Generator | Drawn from the plan |
| --- | --- | --- | --- |
| (always) | `LISEZMOI.txt` | the manifest | no |
| `pdfGlobal` | `planning-global.pdf` | `GET /api/planning/export/pdf/global` | yes |
| `equite` | `equite.csv` | `GET /api/planning/equite/export` | yes |
| `heures` | `heures.csv` | `GET /api/planning/hours/export` without `source`: the publication in force if there is one, the saved plan otherwise | yes |
| `referentiels` | `referentiels/*.csv` | the six files of `GET /api/reference-data/export-csv` | no |
| `scenario` | `scenario.yaml` | `GET /api/planning/export-scenario` | no |
| `publication` | `publication.csv` | `GET /api/planning/publication/export` | no |
| `individuels` (+ `feuille=true`) | `plannings-individuels/` | each staff member's PDF and ICS, those of `POST /api/planning/export/bundle/all` | yes |

The generators are those of the screens, never a copy: a file of the archive is, byte for byte, the one its screen would have downloaded at the same instant (BOM included for the CSVs). `ArchiveEvenementResourceTest` checks this for `equite.csv`, `heures.csv`, `referentiels/*.csv` and `scenario.yaml`, and re-imports `scenario.yaml` into an empty edition: it is the light, partitioned and re-importable equivalent of the [SQL dump](restaurer-une-edition.md#le-dump-sql-doutillage-de-test), which does not enter the archive because it carries all the editions and all the tokens.

One exception is deliberate: the individual schedules do not print the espace link. The PDF a staff member receives carries their address in clear text and as a QR code, and that address is their access token; an archive kept for a year has no business containing a hundred and fifty of them. They are therefore rendered from a plan whose profiles carry no token, same layout without the box. No part contains `accessToken` or `abonnementToken`, which the test checks by searching for a profile's two tokens in each entry.

The manifest is a plain text (CRLF, UTF-8) readable without the application: the edition, the generation date, the version, the last solve and its score ("inconnu", unknown, without an analysis in memory), the mention of a solve in progress (the archive then carries the last saved plan), the last publication ("aucune publication", none), the number of days reviewed, the list of files and the GDPR notice (data controller and retention period, the `responsable-traitement` and `conservation` keys of `LEGAL_MENTIONS`). The file is named `archive-<édition>-<AAAA-MM-JJ>.zip`, the edition's name reduced to characters without accents.

The archive is written as a stream. Everything that can refuse (the empty choice, the scenario of an empty edition, the manifest's reads) happens before the first byte; the entries are then written one by one into the response, the individual schedules last, one document at a time. A failure mid-write can no longer become an error code: it cuts the download, and the truncated ZIP does not open. The action is journalled as an export (`EXPORT_ARCHIVE_EVENEMENT`, with the names of the parts taken, refusals included) by the response filter, hence at the moment the response leaves and before the stream: a download cut off on the way stays recorded as successful ([Background jobs, notifications and journal](taches-de-fond-et-notifications.md)).
