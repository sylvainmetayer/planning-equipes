---
ordre: 180
---

### The lock has only three types

- **Before (3.x)**: a lock could target a staff member over the whole event (`ANIMATEUR`), a stand over the whole event (`STAND`), a day (`JOUR`), a timeslot for every stand (`CRENEAU`) or a staff member on a timeslot (`ANIMATEUR_CRENEAU`, also laid by an accepted swap).
- **After (4.0)**: three types ([ADR 0109](repo:docs/decisions/0109-trois-types-de-verrou.md)): staff member + timeslot (`ANIMATEUR_CRENEAU`), stand + day (`STAND_JOUR`), day (`JOUR`). Migration `V151` converts each old lock into what it froze on the saved plan, so that plan does not move at the next solve:

| Old lock | Becomes |
| --- | --- |
| `ANIMATEUR` | one staff member + timeslot lock per timeslot where the staff member holds a seat |
| `STAND` | one stand + day lock per day on which the stand has seats |
| `CRENEAU` | one staff member + timeslot lock per staff member holding a seat on that timeslot |
| `JOUR`, `ANIMATEUR_CRENEAU` | unchanged |

  A lock for which the plan has no matching seat froze nothing and disappears. The `animateurVerrouilleFige` rule is removed: a staff member is no longer locked "everywhere", so nothing forbids giving them a seat elsewhere, and empty seats are not frozen (as before). The number of rules goes from 47 to 46. The `verrouiller` MCP tool keeps its name; its `type` argument takes `ANIMATEUR_CRENEAU`, `STAND_JOUR` or `JOUR`, and the API refuses the old values with a `400`. The `plan:` section of a YAML file (the one of the copy before a wipe) carries only these three types; a file naming an old one is read with the same conversion, and each converted lock is reported by a warning.
- **What you do**: nothing for your locks. The locks of older editions are converted the same way. A script or prompt that laid an `ANIMATEUR`, `STAND` or `CRENEAU` lock must lay one lock per timeslot or per day. A copy before a wipe taken in 3.x that contains such locks stays restorable: its locks are converted on reading, the same way, without the stored copy being rewritten.
