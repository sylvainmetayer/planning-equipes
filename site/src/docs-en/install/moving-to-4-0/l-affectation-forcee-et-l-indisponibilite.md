---
ordre: 220
---

### The forced assignment and the forced unavailability are converted

- **Before (3.x)**: an ad hoc exception had five types, among them the **forced unavailability** (a person ruled out of a timeslot, a stand or the whole event) and the **forced assignment** (at least one seat of the scope held by one of the named people).
- **After (4.0)**: three types ([ADR 0117](repo:docs/decisions/0117-deux-types-d-exception-les-forcees-sont-converties.md)): incompatibility, affinity, grouped arrival. The two rules `indisponibiliteForcee` and `affectationForcee` are removed (the number of rules goes from 42 to 40); their names are read with a warning in a 3.x file or copy. Migration `V161` converts each exception into what replaces it, without touching the saved plan:

| 3.x exception | Becomes |
| --- | --- |
| forced assignment on a timeslot | one lock staff member + timeslot for each named person who holds a seat of the scope on the saved plan |
| forced unavailability on a timeslot | the **whole day** added to the unavailable days of the fiche |
| forced unavailability with no timeslot | every day carrying a timeslot, added to the unavailable days of the fiche |
| forced unavailability on a stand; forced assignment with no timeslot, or that nobody holds | nothing: reported in the migration log, kept in the `contrainte_ad_hoc_avant_4_0` archive |

- **What is lost**: a forced unavailability on **a single timeslot** becomes a **whole day**: the fiche knows no timeslot. If the saved plan seats the person on another timeslot that day, it carries, after the migration, a hard availability breach: it shows on the Problems page, and the next solve moves the person. Each case is reported in the migration log (`RAISE WARNING`, one message per exception); read it before launching the first solve. The event-day mode also marks an absence by adding the **whole day** to the unavailable days of the fiche, whatever timeslot it started from.
- **What you do**: nothing for your data. The `contrainte_ad_hoc_avant_4_0` archive (`convertie` column: true or false) is neither read nor exported by the application and 5.0 drops it; once 4.0 is in place and a return to 3.x is ruled out, `DROP TABLE` removes it with no effect on the application. A script, prompt or MCP tool that created an `INDISPONIBILITE_FORCEE` or `AFFECTATION_FORCEE` exception is now refused: lock the person's timeslot (`verrouiller`, type `ANIMATEUR_CRENEAU`), or add the day to their `joursIndisponibles` (`modifier_animateur`). A 3.x YAML file carrying one of these types is read with the same conversion, a forced assignment being converted only if the file carries a plan (`plan:` section) where the person holds the seat; each case is a warning of the import report. The built-in help gives the gesture: "How to do without a forced assignment".
