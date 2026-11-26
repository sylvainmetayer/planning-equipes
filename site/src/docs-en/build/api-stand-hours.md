---
twin: comprendre/api-horaires-des-stands
---
# Stand opening hours through the API

A stand's hours are read and written at three levels: the stand record (its plages), the openings report (what the solver would receive) and the entry grid. You will know what each one returns, which anomalies the report detects, and what saving a grid keeps or replaces. How the effective window is worked out (rules, exceptions, consignes) is described in [stand hours and windows](horaires-et-fenetres-des-stands.md); the timeslot grid and concurrent writes are in [writing the reference data](api-referentiels.md).

## Plages

The stand record returns its dated plages with no expansion, the view the UI edits: openings under `ouvertures` and closures under `indisponibilites` (each plage also carries its `mode`). A null `heureFin` means "until closing". A write answers `400` if a plage is inconsistent or if one day carries both an opening and a closure.

The recurring rules of 3.x no longer exist ([decision 0104](repo:docs/decisions/0104-les-horaires-d-un-stand-sont-une-liste-de-plages-datees.md)): the record's `horaires` key stays in the response, always empty, until the API speaks plages. A write that still sends a rule there (the current schedule editor does) has it **converted at once into plages** over the days of the event, with the 3.x reading: a day that already carries a plage keeps it. Without any timeslot there is no day to lay it on, and the write answers `400` rather than losing it in silence. This bridge goes away with the plages editor. `POST /api/stands/compactage-horaires` has nothing left to compact and always answers an empty report.

## Openings view

`GET /api/ouvertures-stands` returns a stand-by-day grid built from the seats the solver would receive: the screen validates the real data, with no second interpretation of the entry. `source` (`DEFAUT` or `EXCEPTION`, that is: the stand carries a plage that day) says what decided. No anomaly blocks a solve. Three are defects: `STAND_JAMAIS_OUVERT`, `FENETRE_SANS_EFFET` (no timeslot of the day overlapped) and `SEGMENT_TROP_COURT`, the signature of the `23:59` workaround. Three codes of 3.x, born of the recurring rules, are never raised any more:

| Code | What it said |
| --- | --- |
| `REGLES_CHEVAUCHANTES` | two rules of the same scope and the same mode decided the same day with overlapping windows |
| `REGLE_MASQUEE` | a rule covered days of the edition and decided none of them |
| `FENETRES_CHEVAUCHANTES` | two windows of the same rule overlapped with different headcounts |

`horaireId` designated the rule concerned; it stays on the response, always empty.

A day carries its public holiday label (`jours[].ferie`) and its columns (`jours[].creneaux`): a whole timeslot, or its slices when a window starts or ends inside it (4 people from 14:00 to 19:00 then 2 until 20:00 split the 14-20 timeslot into 14-19 and 19-20, for all stands). Nothing is stored; the bounds are read back from the windows. Each cell carries one entry per column (`creneaux[]`: `creneauId`, `tranche`, `effectif`, `partiel`, `segments`), with `effectif` set to `null` if the stand is closed there. `partiel` remains only for a bound that no column follows; `effectif` is then the highest one and `segments` says what the cell holds.

## Layers: where an opening comes from

`GET /api/ouvertures-stands/couches?du=AAAA-MM-JJ&au=AAAA-MM-JJ` says where each opening comes from, where the report says what it becomes. Both bounds are optional; `400` on an unreadable date or a reversed range. For each stand and each day of the range that carries a timeslot:

| Field | Layer | Read from |
| --- | --- | --- |
| `source`, `horaireIds`, `motif`, `nominal` | dated plage or open by default (`horaireIds` is always empty) | `HoraireStandResolver` alone |
| `jours[].consigne`, `reopenings` | the band the consigne closes and the windows where it reopens this stand | the consigne in place |
| `effective` | what remains once the consigne has been applied, with its seats split | `StandService.resolve`, the same entry point as the solver |
| `jours[].vacations` | the timeslots, `addedByConsigne` for those a consigne created | the grid |

Windows are in minutes since midnight of the day: a timeslot that runs past midnight ends beyond 1440 (the convention of `journee-stands.ts`). A missing `effectif` means the stand's minimum; on a reopening, `null` says the consigne inherits the headcount of what the band removed. The endpoint is separate because the report is read on every visit, whereas the layers only serve the cell bars and the sentence that explains a cell. It reads neither the seats nor the plan: the screen answers on an edition that was never solved.

## Grid entry

`PUT /api/ouvertures-stands/grille` writes the stands' schedule in the form of the organiser's workbook: one integer per column, empty for closed. The body carries only the modified stands, each with all its cells; a cell names its timeslot and, for a slice, its bounds within the timeslot:

```json
{ "stands": [ { "standId": "BOURSE",
                "cellules": [ { "creneauId": 1, "effectif": 2 },
                              { "creneauId": 2, "heureDebut": "14:00", "heureFin": "19:00", "effectif": 4 },
                              { "creneauId": 2, "heureDebut": "19:00", "heureFin": "20:00", "effectif": 2 } ] } ] }
```

A stand that is sent has its plages replaced in full by its cells: consecutive timeslots with the same headcount become one dated window, the one that reaches the end of the day stays open, and a day with no cell is an explicit closure, since saying nothing would mean open. `effectifMin` is the smallest value entered, `effectifMax` the largest. The minutes of a timeslot that no cell covers keep what the stand had there; two cells that overlap, or fall outside their timeslot, give `400`, as do an unknown timeslot or stand or a zero headcount (leave the cell empty to close).

The grid never destroys what it cannot display. A partial cell (the report marks it `partiel`) sent back with the same value keeps its segments; retyped with another value, the value covers the whole timeslot. Flattening a stand's partial cells is an explicit request, `stands[].aplatir: true`, never a side effect of saving a neighbour. The response says so stand by stand (`exceptions` counts the plages written; `regles` is always 0 and `compacte` always false, leftovers of 3.x). All stands are validated first, then written in one transaction, `409` during a solve. A grid carries a single variant of each shift ([ADR 0029](repo:docs/decisions/0029-retrait-des-familles-de-relais.md)).
