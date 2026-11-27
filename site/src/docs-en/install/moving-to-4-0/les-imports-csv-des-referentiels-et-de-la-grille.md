---
ordre: 246
---

### The CSV imports of the reference sets and of the stand grid are removed

- **Before (3.x)**: the Import tab of *Files* offered seven imports: a CSV for each of the five short reference sets (game categories, locations, stands, timeslots, day templates), the **stand grid** (a stands × timeslots matrix, one headcount per cell), the staff CSV and the YAML scenario; each reference screen had its "Import" button.
- **After (4.0)**: **the YAML is the complete workbook of an edition, and the CSV is only for staff** ([decision 0128](repo:docs/decisions/0128-le-yaml-est-le-classeur-complet-le-csv-ne-sert-qu-aux-animateurs.md)). The Import tab has only four cards (Scenario, Staff, Examples, Check a file); a bookmark on a removed card (`/fichiers?cible=stands`) lands on the Scenario card. The eighteen removed routes answer **`410 Gone`**, whatever the body, with a message pointing at `POST /api/reference-data/import-scenario-fichier`: `…/typologies`, `…/emplacements`, `…/stands`, `…/creneaux` and `…/journees-types`, each as `/import-csv`, `/import-csv/analyse` and `/import-csv/exemple`, and `/api/stands/import-grille` with its siblings `/analyse` and `/exemple`. They disappear in 5.0. **The CSV exports do not change** (the archive of *Files › Export* keeps its six files), nor does the skills entry grid, which is not a file. No MCP tool carried these imports.
- **What you do**:
  - **A script that called one of these routes** moves to the YAML: it receives `410` and the message saying so. For the five reference sets, write the `typologies`, `emplacements`, `stands`, `creneaux` and `gabarits` sections of the file (the [scenario format](../comprendre/scenario-yaml.md) describes them, [the schema](repo:docs/schema/scenario-schema.json) validates them, and the "Check a file" card checks them without writing anything). A YAML import **replaces** the edition instead of completing it: start from an export of the edition (*Files › Export*, "Export current data as a scenario"), edit it, then import it again; a copy of the edition is kept before the write.
  - **Redoing a stand grid in YAML.** The matrix (stands in rows, timeslots in columns, one headcount per cell) is written, for each stand, as one opening plage per non-empty cell, in the stand's `plages` list: the `date` and the hours of the column, and the cell's `effectif`.

    ```yaml
    stands:
      - id: LUDOTHEQUE
        nom: Ludothèque
        typologiesProposees: [JEUX_DE_SOCIETE]
        effectifMin: 1
        effectifMax: 3
        plages:
          - { date: 2027-07-03, mode: OUVERTURE, heureDebut: '10:00', heureFin: '12:30', effectif: 2 }
          - { date: 2027-07-03, mode: OUVERTURE, heureDebut: '14:00', heureFin: '18:00', effectif: 3 }
    ```

    A workbook column that spans several timeslots is written as one plage per timeslot, or as a single plage as wide as the column (a window at its own bounds, as the grid import used to lay it). A spreadsheet can produce these lines: a column of formulas that assembles `{ date: …, mode: OUVERTURE, heureDebut: '…', heureFin: '…', effectif: … }` for each non-empty cell, pasted under `plages:`. For a stand open every day at the same hours, write its **model** under `horaires` instead (`mode: OUVERTURE`, `jours: TOUS`, `fenetres`): the application unfolds it into plages on the days of the timeslots.
  - **Without writing YAML**, the grid is redone on screen: *Prepare › Calendar*, the hours tab, "Entry" view (the same stand × timeslot table, one headcount per cell), or each stand's fiche.
  - **The five reference CSVs** are redone the same way: create the rows on screen, or write the YAML section. The CSVs exported by 3.x can be read again in a spreadsheet to copy their rows into the YAML, never into the application.
  - **The history**: the removed import actions (game categories, locations, stands, grid, timeslots, day templates) no longer have a label; the lines already written to the journal show under their code.
