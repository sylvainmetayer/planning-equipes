---
url: ~(stands|animateurs|creneaux|emplacements|typologies)~
title: Reference-data CRUD screens
---

These are the CRUD screens, the best exploration targets.

- **/stands** — booths. A stand needs a name, an *emplacement*, a *typologie*,
  a minimum/maximum headcount (`effectifMin` / `effectifMax`, min ≤ max), and
  optional opening hours (*horaires*, recurring per day) and closures.
- **/animateurs** — staff. Name, birth date (minors get stricter legal rules),
  availability, skills. Birth date drives labour-law constraints, so an under-18
  date changes what the solver accepts.
- **/creneaux** — time slots: a day, a start time, an end time; end must be after
  start. There is also a bulk "créneaux récurrents" generator with a preview.
- **/emplacements** — locations, referenced by stands. Deleting one that is still
  used by a stand must be refused or must cascade explicitly.
- **/typologies** — a CRUD referential (not an enum) referenced by stands, same
  deletion-in-use question.

Interesting edge cases on these screens: empty name, duplicate name, negative or
inverted min/max headcount, an end time before the start time, very long strings,
accented and emoji characters, deleting a row referenced elsewhere.
