---
twin: comprendre/api-horaires-des-stands
---
# Stand opening hours through the API

A stand's hours are read and written at three levels: the stand record (rules and exceptions), the openings report (what the solver would receive) and the entry grid. You will know what each one returns, which anomalies the report detects, and what saving a grid keeps or replaces. How the effective window is worked out (rules, exceptions, consignes) is described in [stand hours and windows](horaires-et-fenetres-des-stands.md); the timeslot grid and concurrent writes are in [writing the reference data](api-referentiels.md).

## Rules and exceptions

The stand record (`horaires`) returns two levels with no expansion, the view the UI edits: recurring rules, and dated exceptions that override the day they name. A null `heureFin` means "until closing". A write answers `400` if a rule is inconsistent or if two rules of the same scope on overlapping days contradict each other.

`POST /api/stands/compactage-horaires` rewrites repeated dated windows as equivalent rules; `appliquer=false` (the default) is a dry run. A stand is rewritten only if the proposed rules reproduce exactly its open segments. `gapMinutes` counts the opening minutes that disagree: `1` when an old `23:59` had become the day's real closing time.

## Openings view

`GET /api/ouvertures-stands` returns a stand-by-day grid built from the seats the solver would receive: the screen validates the real data, with no second interpretation of the entry. `source` (`DEFAUT`, `REGLE`, `EXCEPTION`) says which layer decided. None of the six anomalies blocks a solve. Three are defects: `STAND_JAMAIS_OUVERT`, `FENETRE_SANS_EFFET` (no timeslot of the day overlapped) and `SEGMENT_TROP_COURT`, the signature of the `23:59` workaround. Three are given for information:

| Code | Trigger |
| --- | --- |
| `REGLES_CHEVAUCHANTES` | two rules of the same scope and the same mode decide the same day with overlapping windows: union of the windows, highest headcount, which the message names |
| `REGLE_MASQUEE` | a rule covers days of the edition and decides none of them: a more specific rule or a dated exception wins everywhere |
| `FENETRES_CHEVAUCHANTES` | two windows of the same rule (or two dated openings of the same day) overlap with different headcounts |

They are judged on the edition's real days (the dates that carry a timeslot, and the day after a timeslot that runs past midnight). A `PLAGE` rule outside the edition is not masked, a consigne masks nothing, and different scopes in opposite modes (closed on weekends over a daily opening) are the mechanism, not an anomaly. `horaireId` designates the rule concerned. The edition status counts them (`ouvertures.informations`) without their being enough to push the line to "to check". The rule editor replays the detection live (`core/horaire-stand.ts`), kept in agreement with the server by a shared set of cases, `horaire-stand-anomalies.cas.json`.

A day carries its public holiday label (`jours[].ferie`) and its columns (`jours[].creneaux`): a whole timeslot, or its slices when a window starts or ends inside it (4 people from 14:00 to 19:00 then 2 until 20:00 split the 14-20 timeslot into 14-19 and 19-20, for all stands). Nothing is stored; the bounds are read back from the windows. Each cell carries one entry per column (`creneaux[]`: `creneauId`, `tranche`, `effectif`, `partiel`, `segments`), with `effectif` set to `null` if the stand is closed there. `partiel` remains only for a bound that no column follows; `effectif` is then the highest one and `segments` says what the cell holds.

## Layers: where an opening comes from

`GET /api/ouvertures-stands/couches?du=AAAA-MM-JJ&au=AAAA-MM-JJ` says where each opening comes from, where the report says what it becomes. Both bounds are optional; `400` on an unreadable date or a reversed range. For each stand and each day of the range that carries a timeslot:

| Field | Layer | Read from |
| --- | --- | --- |
| `source`, `horaireIds`, `motif`, `nominal` | recurring rule, dated exception or open by default | `HoraireStandResolver` alone |
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

A stand that is sent has its rules and exceptions replaced in full by its cells: consecutive timeslots with the same headcount become one dated window, the one that reaches the end of the day stays open, and a day with no cell is an explicit closure, since saying nothing would mean open. `effectifMin` is the smallest value entered, `effectifMax` the largest. The minutes of a timeslot that no cell covers keep what the stand had there; two cells that overlap, or fall outside their timeslot, give `400`, as do an unknown timeslot or stand or a zero headcount (leave the cell empty to close).

The grid never destroys what it cannot display. A partial cell (the report marks it `partiel`) sent back with the same value keeps its segments; retyped with another value, the value covers the whole timeslot. Flattening a stand's partial cells is an explicit request, `stands[].aplatir: true`, never a side effect of saving a neighbour. Compaction then folds repeated days back into rules when it proves the equivalence; the response says so stand by stand (`regles`, `exceptions`, `compacte`, `raison`). All stands are validated first, then written in one transaction, `409` during a solve. A grid carries a single variant of each shift ([ADR 0029](repo:docs/decisions/0029-retrait-des-familles-de-relais.md)).
