---
twin: comprendre/scenario-yaml
---

# The YAML scenario file

You will know what a scenario file describes, what its import replaces in the edition, what the export guarantees on re-import, and what to touch when the format evolves.

A scenario describes a whole event: reference data, grid, team, settings. It is the file you exchange, version and replay in tests. Its structure is the [JSON schema](repo:docs/schema/scenario-schema.json), generated from the DTOs of the `dev.sylvain.planning.scenario.dto` package. It therefore cannot diverge from what the code accepts. What follows covers the rules the schema does not express. Everything is read and written in the edition designated by `X-Edition-Id` (see [One edition, one partition of the data](cloisonnement-par-edition.md)).

## Where each action takes place

Everything goes through the Files screen (`/fichiers`), which brings together export, correction and re-import, as well as the end-of-event archive. Its three tabs are driven by `?onglet=importer|exporter|archive`, the open card of the Import tab by `?cible=`. The old addresses `/imports`, `/exports`, `/export-csv` and `/validateur-yaml` redirect to it.

| Action | Address |
| --- | --- |
| Import a scenario | `/fichiers?cible=scenario` |
| Check a file without writing anything | `/fichiers?cible=verifier` |
| Load a shipped example | `/fichiers?cible=exemples` |
| Export the edition as a scenario | `/fichiers?onglet=exporter` |

The examples are filed under "To discover" or "To test a case", with a readable name and a sentence. The `gamme-*` and `extreme-*` files are shipped and load by name, but neither the screen nor the MCP tool `lister_scenarios` lists them. On the API side: `POST /api/reference-data/import-scenario-fichier`, `POST /api/reference-data/valider-scenario-fichier` and `GET /api/planning/export-scenario`.

## What the import replaces

![Import of a YAML scenario](repo:docs/diagrammes/import-scenario.svg)

<sub>Source: [`diagrammes/import-scenario.puml`](repo:docs/diagrammes/import-scenario.puml).</sub>

- Nothing leaves the target edition.
- Staff members and stands are replaced in full: those the file designates are updated in place, the others are deleted.
- Timeslots receive new ids in the database. The ad hoc constraints that cited them by their file id are re-associated.
- The edition's assignments and ad hoc constraints are deleted. The latter are rewritten only if the file carries the `contraintesAdHoc` section.
- Consignes and consigne presets are replaced if the file carries the corresponding section, and left in place otherwise. This is the rule for all the file's reference data: a present section replaces, an absent section touches nothing.

Before writing, the import stores the state of the edition in a backup that [Restore an edition](restaurer-une-edition.md) lets you replay.

An `edition: { id?, nom? }` section routes the import to another edition: the one with this identifier if it exists, otherwise the only edition of that name, otherwise an edition created empty under that name, whose identifier the application assigns. The response always says where the data landed, because the operator may be looking at a different edition from the one just written.

## File identifiers are local references

The application assigns all identifiers ([ADR 0050](repo:docs/decisions/0050-identifiants-generes-par-edition.md)): `A1`, `A2`… for staff members, `S…` for stands, `T…` for game categories, `L…` for locations, `C…` for ad hoc constraints, numbered within each edition. In a file, a row's identifier serves only to cite it elsewhere (a seat, a constraint, a consigne, a skill). What it designates in the target edition is decided row by row:

| Order | Staff member | Stand, game category, location |
| --- | --- | --- |
| 1 | the record with the same identifier, if it has the same e-mail address (failing an address, the same last and first name) | the row with the same identifier, if its `code` does not contradict the file's |
| 2 | the only record with this address, then the only one with these last and first names | the row with this `code`, or with the identifier read as a code for a hand-written file (`STRATEGIE`) |
| 3 | a new record | a new row, which keeps the file's identifier as its code when it is readable |

Rule 1 means that a scenario exported then re-imported in the same edition updates instead of recreating: the espace tokens (the links printed on the schedules), the sessions, the swap requests and the confirmations survive. The identity check that goes with it is necessary, because each edition numbers from 1 and the `A3` of another edition is somebody else. The ad hoc constraints, replaced as a block, keep their identifier when the edition carries it and receive one otherwise.

## What the export guarantees

"Export current data as a scenario" writes all the sections the import can read back: the entities, but also `typologies`, `emplacements`, `parametresLegaux`, `parametresSolveur`, `journeesTypes`, `contraintes`, `contraintesAdHoc`, `prereglagesConsigne` and `consignes`. Re-importing the file reproduces exactly the same problem. That is the reason for the export to exist.

A field forgotten in a section is not "not overridable": the import writes a fresh object and the field falls back to the default. `heureDebutSoiree`, absent from the DTO at the start, thus brought an edition set to 22:00 back to 20:00 and shifted the fairness evening hours without a word. A file without these sections likewise falls back on the settings of the instance that imports it and replays a different problem. For whoever touches the format: every field read must also be written.

Timeslots always come out as they are, with `couverturePause: true` when they cover a meal service (the stand opens only half its staffing there, rounded up; the flag is written only when it is true). The seat list `postes`, on the other hand, does not come out (see below).

## `schemaVersion`

A file written by the application starts with `schemaVersion: 2` (`ScenarioBinder.SCHEMA_VERSION`). On reading, the key is optional and is 1 when missing. A file older than the current version goes through a **chain of conversions** (`ScenarioMigrations`) before being bound: each link is a pure function from version *n* to *n + 1*, with its test, and each model change adds its own. What a link cannot convert is ignored **with a warning in the import report** (`avertissements`), never silently. The shipped scenarios, the end-of-event archives and the copies made before a wipe thus remain readable. The v1 reader is kept throughout 4.x and removed in 5.0 ([ADR 0101](repo:docs/decisions/0101-schema-yaml-2-et-chaine-de-conversions.md)). A number higher than the one the version reads is refused with a clear sentence ("produced by a more recent version") rather than with an "unknown field" on the first new key. A version of the application older than the key refuses any file that carries it, like any unknown key.

## `postes`: absent and empty do not say the same thing

When absent, the section is generated from the imported stands and timeslots, by the builder that a solve also uses: one seat per place on each timeslot and segment actually open. The number of places comes from the opening window when it carries an `effectif`, otherwise from `effectifMin`, never from `effectifMax` (see [The data model](modele-des-donnees.md)).

When present, it is taken as is, even if empty, which allows off-rule staffing; no shipped scenario uses it. A written list repeats what `effectifMin` already says and freezes the grid against any later change of a stand. The export therefore never writes it.

## `contraintes`: as a block, never merged

A rule the section does not cite becomes active again, at its default weight. A scenario that pins its setting describes the problem against which it was verified. A merge would leave in place the remains of the importing edition, and the same file would solve a different problem depending on where it lands. When absent, the section touches nothing.

A constraint name absent from the catalogue is refused with a 400 instead of being ignored: it is a typo or a file written against another version of the catalogue, and ignoring it would suggest that a rule is off when it is not.

The shipped scenarios all carry this section, with the only two weights that differ from the default, `appreciationIncompatible: 15` and `maxJoursConsecutifsTravailles: 25`. The deployment's default is 1 for a hard rule and 5 for a medium or soft rule: the weighting travels with the scenario.

Weights are read on the current scale (low 1, normal 5, high 25, see [ADR 0057](repo:docs/decisions/0057-importance-d-une-regle-en-trois-positions.md)). A file written on the old scale, where the default was 1, would make its cited rules weigh five times less than the others: multiply its medium and soft rule weights by five before importing it. The weighting is detailed in [Weight and disable rules](doser-et-desactiver-les-regles.md).

`contraintesAdHoc` replaces those of the edition, and its absence installs none. It designates staff members, stands and timeslots by the file's ids. The import never inherits the exceptions of the current edition: an exception copied into another edition would target a stand and a timeslot that do not exist there, and in its own edition it would target timeslots the import has just deleted. What preserves exceptions across a round trip is the export, which writes the section as soon as the edition carries one. An exception that cannot hold together with another in the same file makes the whole import be refused before any write, with a message that names them: this is the input check (see [Ad hoc exceptions](exceptions-ad-hoc.md)).

## Stand hours

The day selector is flat: `jours` names which of the neighbouring fields applies (`TOUS` by default, `JOURS_SEMAINE`, `PLAGE`, `DATES`), and only that one is read.

- An omitted `heureFin` means "until closing": the window runs to the end of the evaluated timeslot. A single rule thus covers a day that closes at 20:00 and a day that closes at midnight, without the `23:59` workaround that a concrete hour required (a window cannot straddle midnight).
- An omitted `effectif` means "inherit from `effectifMin`", the most common case. Filling it in on a window lets a stand whose load varies during the day (4 people in the morning, 5 in the evening) remain a single stand. The field exists on a recurring rule window and on a dated opening.

A more precise scope takes precedence over a wider one, and a dated exception takes precedence over all rules for the single day it names. The full arbitration is in [Hours, consignes and effective windows](horaires-et-fenetres-des-stands.md).

## Game categories, typical days and removed keys

Each stand in the file proposes at least one game category: `typologiesProposees: []` is refused by the validator as by the import, which names the entry and its id. A game category that is cited without being declared is created with a label and a code equal to the cited value; the `typologies` section, applied after the import so as not to be overwritten by this creation, sets a real label and a `code`. At most one game category carries `polyvalent: true` (declaring one removes the flag from the previous one). Each entry may carry `maxCreneauxParAnimateur`, the ceiling of timeslots over the whole edition, and `description`, the note displayed on the Categories screen.

`journeesTypes` is optional: name, shifts (with `couverturePause` for a meal relay) and the dates that each day governs. Applied after the timeslots, it replaces typical days and calendar but writes no timeslot: a consistent file lists under `creneaux` what its typical days say. When absent, the import recognises the typical days that the timeslots imply ([ADR 0032](repo:docs/decisions/0032-journees-types-nommees-vacations-fixes.md)).

Some keys are refused by name, with a message that says what to write instead. Accepting them by ignoring them would be worse: an opening-span grid would be imported as fourteen-hour shifts without a word, and a file verified under one break mode would be judged under a rule that is no longer its own.

| Refused key | Replacement |
| --- | --- |
| `parametresDecoupage`, `decoupageAuto` | automatic splitting no longer exists ([ADR 0037](repo:docs/decisions/0037-une-grille-est-toujours-des-vacations.md)); `dureeVacationMaxMinutes` joined `parametresLegaux` |
| `pauseSurPoste`, `pauseMinimaleEntreVacationsMinutes`, `dureePauseMajeurMinutes`, `dureePauseMineurMinutes` (in `parametresLegaux`) | a single duration, `dureePauseMinutes`, and a single break rule ([ADR 0048](repo:docs/decisions/0048-une-seule-regle-de-pause.md)) |

## Consignes and presets

Two optional sections carry what an arrêté (an official order) has imposed on the edition ([ADR 0043](repo:docs/decisions/0043-consigne-d-edition-fermer-une-bande-sans-rien-detruire.md)). `prereglagesConsigne` lists the named presets. `consignes` has one entry per date: forbidden band, reason, name of the originating preset, default windows, stand-by-stand openings with their staffing, added timeslots, and a `repas` block when the date runs under different meal windows from the edition's.

```yaml
prereglagesConsigne:
  - id: 4f1c…
    nom: Plan canicule
    fermetureDebut: '12:00'
    fermetureFin: '16:00'
    motif: Arrêté préfectoral canicule
    fenetres:
      - debut: '18:00'
        fin: '20:00'
consignes:
  - date: '2033-07-08'
    fermetureDebut: '12:00'
    fermetureFin: '16:00'
    motif: Arrêté préfectoral canicule
    prereglage: Plan canicule
    fenetres:
      - debut: '18:00'
        fin: '20:00'
    ouvertures:
      - standId: STAND-A
        debut: '18:00'
        fin: '20:00'
        effectif: 2
    creneauxAjoutes:
      - date: '2033-07-08'
        heureDebut: '18:00'
        heureFin: '20:00'
    repas:
      soirDebut: '18:00'
      soirFin: '22:00'
      justification: Repas pris pendant la bande fermée
```

The file is held to the rules of the screen: the `repas` block is written only when it overrides something and its `justification` is then mandatory, a meal window carries both its bounds or neither. An end written `'00:00'` means "until midnight" and is read back as an absent end. The `effectif` of an opening is that of its window: a stand reopened on two windows at different staffing levels is written as two entries. A hand-written preset may omit its `id`.

The added timeslots travel by natural key, because the ids of `creneaux` do not survive an export (the import renumbers the section). The timeslot comes out as is under `creneaux`, and `creneauxAjoutes` designates it by date, start time and end time. A key that designates no timeslot in the file is refused, like an opening on a stand that the file does not declare. A timeslot that no stand nominally opens (an evening that only the consigne opens) would have no seat: the consigne recreates it.

The import writes directly into the reference data, after the stands and timeslots, and never through the action of the Consignes screen. The latter refuses a past or current date and itself adds the timeslots that its windows require: two things a file has already settled. A consigne on a past date can therefore be imported, and the statistics of that day are those of what was done.

A scenario solved directly from `src/main/resources/scenarios/` (`PlanningService.buildExample`) does not apply them: the consigne layer is laid down by `StandService.resolve` on the reference data, and this scenario is solved on its nominal grid. They take effect once the file is imported.

## The shipped files

Each scenario lives flat in `src/main/resources/scenarios/`: the examples of the Files screen list this folder and `ScenarioYamlReader.scenarioPath` refuses any path component, so a scenario filed in a subfolder, or in a second `scenarios/` folder that `getResource` would hide, is chosen by nobody. The tests read them through `ScenariosLivres`.

| Files | Role |
| --- | --- |
| `scenario-*.yaml`, `scenario.yml` | textbook cases of the format |
| `gamme-01-…` to `gamme-30-…` | increasing difficulty, including five `infaisable-…` cases |
| `extreme-01-…` to `extreme-15-…` | edge cases of volume or shape |
| `festival-realiste-canicule.yaml`, `festival-hivernal.yaml` | anonymised realistic fixtures |
| `scenario-avec-erreur-planning.yaml` | deliberately unsolvable fixture |
| `vitrine-fete-du-jeu-de-pierrefeuille.yaml` | demonstration |
| `exemple-animateurs.csv` | template for the [staff CSV](imports-csv.md#limport-csv-des-animateurs) |

### Anonymised realistic fixtures

`festival-realiste-canicule.yaml` is derived from a real edition. It serves a full-scale demonstration and the convergence regression of `PlanningServiceScenarioFestivalRealisteTest`. The anonymisation rewrites first names, last names, ids, names of locations and stands, and the edition name. Hours, staffing, skills, opening hours, game categories and parameters do not move: a fixture that does not converge like its source does not test what it claims to test.

The calendar is the only exception. `--date-debut` shifts the event as a block to detach it from the real dates (these fixtures open on 01/09/2026). Birth dates never move, because shifting them would change who is a minor and so the problem. A shift that is a multiple of 7 would preserve the days of the week and the ISO week anchor; this one is not, so it moves the week boundaries and the public holidays crossed. Convergence must be rechecked after any change of this option.

- Coordinates are translated in longitude at constant latitude, not removed: a quality constraint penalises two distant locations.
- Anonymous ids are numbered in order of appearance and the order of sections is preserved, because a sort or a hash relying on ids would make the search trajectory diverge (`randomSeed=0`).
- The calendar shift is remembered only by the header the script writes: regenerating without passing `--date-debut` again brings back the source's dates.

```bash
python3 src/main/resources/anonymiser-scenario.py \
    ~/…/reel-2026-canicule.yaml src/main/resources/scenarios/festival-realiste-canicule.yaml \
    --edition-id festival-realiste-canicule --edition-nom "Festival réaliste — canicule" \
    --date-debut 2026-09-01
```

The sources stay out of the repository: `docs/reel-*.yaml` and `scenarios/reel-*.yaml` are in `.gitignore`, because they carry real personal data. The `reel-` prefix is what makes them invisible to git, hence the name `festival-realiste-…` for the versioned fixture.

`festival-hivernal.yaml` is derived from an edition entered in shifts (midday rotation, evening reliefs carried by the opening windows) and played by `PlanningServiceScenarioFestivalHivernalTest`. It pins no constraint weight, deliberately. Its anonymisation goes further: staff members renumbered at random ("Animateur NNN", on `example.org`, adults with randomly drawn birth dates, the source having no minors), places translated to a randomly drawn municipality with longitudes rescaled by the cosine of the latitude, calendar advanced by thirty weeks (days of the week and ISO anchor preserved), stands and game categories renamed consistently. Convergence is read from the test, not from the source, whose search trajectory is no longer that one.

### The unsolvable fixture

`scenario-avec-erreur-planning.yaml` is the only shipped fixture that must not converge: six stands, five staff members, ten timeslots, and one opening defect per stand (window outside the timeslot, minimum staffing above total staffing, recurring rule outside the opening span, badly entered end time, game category that nobody masters…). It shows what the Problems, Staffing need, Openings pages and the feasibility analysis say when the input is wrong. `PlanningServiceUnsolvableScenarioTest` freezes the intent (accepted at import, hard score never zero), no score.

## For whoever touches the format

After modifying a DTO, regenerate the schema:

```bash
./mvnw process-classes -Pgenerate-schema
```

The schema cannot express the conditional rules of an opening-hours selector, checked by `StandValidator`, nor the cross-references, checked at the real import.

The file is read in a single pass, by `ScenarioYamlReader.buildFromScenarioText` ([`ScenarioYamlReader`](repo:src/main/java/dev/sylvain/planning/service/scenario/ScenarioYamlReader.java)), which returns the schedule and all the optional sections together (`ScenarioImporte`, `ScenarioSections`). Adding an optional section means a field in `ScenarioSections` and its filling in `ScenarioDomainMapper.sections`, not one more public method: one method per section would reread the whole file at each call, that is as many full parses as there are sections for a single click.
