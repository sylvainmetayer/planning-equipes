---
twin: comprendre/api-avertissements-de-saisie
---

# Input warnings

Some inconsistencies do not justify a refusal: the row is written and a warning travels in the success response, next to the entity. You will learn which routes return them, what each `type` means, when it is emitted and why it never names a person. The principle comes from the repository's doctrine, which refuses only what is certainly unsatisfiable ([ADR 0020](repo:docs/decisions/0020-avertir-dans-la-reponse-d-ecriture.md)). The writes that do fail are described in [writing the reference data](api-referentiels.md).

## Where they appear

An organiser is allowed to enter an unavailability outside the edition's bounds, a minor staff member during the event, a timeslot that overflows the opening of all stands, or a stand schedule that overlaps no timeslot. The response then wraps the entity:

```json
POST /api/animateurs  →  200
{
  "animateur": { "id": "A12", "prenom": "…", … },
  "avertissements": [
    { "type": "MINEUR_PENDANT_EVENEMENT",
      "message": "L'animateur A12 est mineur du 2026-07-08 au 2026-07-08 et devient majeur le 2026-07-09 …" }
  ]
}
```

Five resources answer this way, with `avertissements` always present and empty when all is well:

| Routes | Response |
| --- | --- |
| `POST /api/animateurs`, `PUT /api/animateurs/{id}` | `{ animateur, avertissements }` |
| `POST /api/creneaux`, `PUT /api/creneaux/{id}` | `{ creneau, avertissements }` |
| `POST /api/stands`, `PUT /api/stands/{id}` | `{ stand, avertissements }` |
| `POST /api/contraintes-ad-hoc` | `{ contrainte, avertissements }` |
| `POST /api/verrouillages` | `{ verrouillage, avertissements }` |

The other reference data answer the bare entity: a missing `avertissements` key means nothing to report. Scenario import emits none (a file stands for itself and its impact report says what it changes), and the MCP tools call the bare writes and answer the entity alone, apart from the `RESOLUTION_EN_COURS` code described in [writing during a solve](api-referentiels.md#écrire-pendant-un-calcul). Bulk edit is covered: it emits one `PUT` per row and groups the batch's warnings into a single message.

## The types

| `type` | What triggers it |
| --- | --- |
| `INDISPONIBILITE_HORS_EVENEMENT` | An unavailability day outside the interval `[first timeslot, last timeslot]`: it covers no timeslot and protects nobody. |
| `INDISPONIBILITE_JOUR_SANS_CRENEAU` | An unavailability day inside the interval but on a date with no timeslot (a Monday break between two weekends). The staff espace will not display it and the first availability declaration applied will erase it. CSV import refuses the row for this reason, manual entry warns. |
| `MINEUR_PENDANT_EVENEMENT` | The staff member is a minor on at least one day of the event; the message says from which date they become an adult, or that they are a minor from start to finish. Emitted only when the write sets or changes the birth date: being a minor is a legitimate state, and repeating it at every edit of another column teaches people to ignore the message. |
| `MINEUR_SANS_REPRESENTANT` | A minor's contacts are recorded without a legal representative. Optional, so never refused, but the compliance report lists these people and the sentence says so before it does. Emitted by the contacts write, not by the profile's. |
| `CRENEAU_HORS_OUVERTURE_STANDS` | No stand is open for a single minute of the timeslot: it will open no seat. |
| `CRENEAU_DEBORDE_OUVERTURE_STANDS` | The timeslot starts before all stands open, or ends after they have all closed, by at least a quarter of an hour. |
| `STAND_FENETRE_SANS_EFFET` | A stand window, from an extended rule as from a dated exception, overlaps no timeslot of its day: saved, it changes nothing. The message cites up to five days. |
| `STAND_EXCEPTION_HORS_EVENEMENT` | A dated stand exception names a day outside the interval `[first timeslot, last timeslot]`. The day after a timeslot that crosses midnight is not counted as outside the event: the domain really reads that date. |
| `STAND_JAMAIS_OUVERT` | After the write, the stand is open on no timeslot. |
| `STAND_SANS_EMPLACEMENT` | The stand is saved with no location while its neighbours share one: same name root ("Buvette 3" and "Buvette B" have the root "Buvette"), failing that the same game categories offered. It takes at least two placed neighbours and a location held by more than half of them. Emitted only when the write changes the location, the name or the game categories, and never on an edition with no locations. A suggestion, never a write: `GET /api/stands/suggestions-emplacement` gives the same reading for the whole edition. |
| `AFFECTATION_FORCEE_JOUR_INDISPONIBLE` | The forced assignment falls only on days when all the staff members it names have declared themselves unavailable: it cannot be held. Written anyway (the unavailability day can be removed), and taken up as a blocking cause by the feasibility analysis as long as it holds. |
| `AFFECTATION_FORCEE_MOTIF_LEGAL` | No seat in the forced assignment's scope accepts one of the named staff members, under a hard rule readable on the seat and staff member pair: minor at night, a public holiday, a stand reserved for adults, the daily ceiling exceeded. The message names the catalogue rules. |
| `AFFECTATION_FORCEE_SIEGE_VERROUILLE` | The schedule of each named staff member is locked over its whole scope without any of them already holding a seat there: the solver can neither move the lock nor ignore the exception. Only `ANIMATEUR` and `ANIMATEUR_CRENEAU` locks count; the others pin the held seats and leave the empty seats fillable. |
| `ARRIVEE_GROUPEE_JOURS_DIVERGENTS` | The members of a grouped arrival have not declared the same unavailability days: on those days, the group cannot arrive together. The message gives the number of days and the first one. |
| `VERROUILLAGE_SUR_VIOLATION_DURE` | The lock written freezes situations that already break a hard rule in the last analysis. Written anyway: a lock freezes without exempting ([ADR 0003](repo:docs/decisions/0003-verrouillage-par-pin-natif.md)) and the seats are still judged. The message names the rules and the number of situations. Nothing is said on an edition that no solve has analysed: there is nothing to read, and nothing is guessed. |

The three `AFFECTATION_FORCEE_*` types and `ARRIVEE_GROUPEE_JOURS_DIVERGENTS` concern an ad hoc exception; their business meaning is in [ad hoc exceptions](exceptions-ad-hoc.md).

## Only what the write changes is reported

On an edit, each rule is read against the profile as it stood before: an unavailability day already saved and a birth date not touched produce nothing. Bulk edit sends one `PUT` per row carrying the whole merged profile; without this comparison, ticking thirty volunteers to add a skill to them would end in a message citing every minor in the selection. On creation, everything is new and everything is examined. A warning that fires on correct data stops being read.

The three stand warnings about hours (`STAND_FENETRE_SANS_EFFET`, `STAND_EXCEPTION_HORS_EVENEMENT`, `STAND_JAMAIS_OUVERT`) are emitted only if the write touches the hours (rules, closures, openings): renaming a stand that has never opened is not the time to say so. The entry grid `PUT /api/ouvertures-stands/grille` emits none, its windows following the timeslots by construction.

## The event's bounds are derived

An `Edition` carries neither dates nor an "ongoing" flag: the event runs from the first to the last timeslot, the same derivation as the availability collection. An edition with no timeslot at all therefore has no bounds and emits no warning: inventing one would produce a false positive on the very screen where a new edition begins.

Stand hours are read with recurrences expanded, and expanded against the timeslot being written. Without that, a timeslot created on a new date would see every stand scheduled by rule as closed, and the warning would cry wolf on the most ordinary entry.

## What the messages do not say

A warning names a staff member by their id alone, never by last name and first name or birth date. The browser copies every message into a `localStorage` log that survives the sign-out, and a minor's identity has no business there (see [data protection](../installer/rgpd.md)). The UI goes further on a single type: the `MINEUR_PENDANT_EVENEMENT` sentence is displayed but not logged, because by naming the day of their 18th birthday it would let someone recompute the birth date.
