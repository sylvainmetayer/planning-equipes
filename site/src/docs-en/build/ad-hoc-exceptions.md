---
twin: comprendre/exceptions-ad-hoc
---
# Ad hoc exceptions and their contradictions

You will know what the three types of ad hoc exception are, what the 3.x forced assignment and forced unavailability became, how a carpool request becomes an exception, and which combinations the application refuses on entry.

## What an ad hoc exception is

`ContrainteAdHoc` in the domain and on the wire, "Manual adjustments" on screen (Adjustments tab of "Planning rules"). These are one-off exceptions set by the administrator, stored in the database and evaluated dynamically: **never a hard-coded rule for a particular case**. What an organiser enters there is an exception to the plan, not a catalogue rule. The user-side edge cases ("one of these staff members" semantics, empty scope, deleted timeslot) are in the online help, "Manual adjustments" section.

| Type (`TypeContrainteAdHoc`) | What it says | Level |
| --- | --- | --- |
| `INCOMPATIBILITE` | Two staff members never hold the same timeslot, whatever the stand; the scope can restrict it to one stand or one timeslot | Hard |
| `AFFINITE` | A pair to bring together: rewarded on the same stand and the same timeslot, never imposed | Soft |
| `ARRIVEE_GROUPEE` | Two to four staff members who arrive and leave together (a carpool), with no timeslot or stand | Soft |

The incompatibility is scored hard, at the same priority as the legal rules: never downgrade it to medium or soft. `AFFINITE` is the deliberate exception, a soft reward, and promoting it to hard would be a forced assignment in disguise.

These exceptions are set **before** the solve: every solve honours them, including when starting from scratch. Not to be confused with the [lock](plan-verrous-et-publication.md#le-verrou-fige-des-sièges), which freezes *after the fact* what a solve produced without saying anything about what should be there. The help carries a box, "Manual adjustment or lock?".

## What the forced assignment and the forced unavailability became

They no longer exist since 4.0 ([decision 0117](repo:docs/decisions/0117-deux-types-d-exception-les-forcees-sont-converties.md)): a lock and a day off on the fiche say the same thing with a gesture the organiser already knows, and the solver has one rule fewer to write twice. What to do instead is in the help, "How to do without a forced assignment", and in [the guide](../regles/ajustements-et-verrous.md).

| Before (3.x) | After (4.0) |
| --- | --- |
| `AFFECTATION_FORCEE` on a timeslot | One `ANIMATEUR_CRENEAU` lock for each named staff member who holds a seat of the scope on the saved plan |
| `INDISPONIBILITE_FORCEE` on a timeslot | The **whole day** added to the unavailable days of the fiche: the fiche knows no timeslot |
| `INDISPONIBILITE_FORCEE` with no timeslot | Every day carrying a timeslot, added to the unavailable days of the fiche |
| `INDISPONIBILITE_FORCEE` on a stand | Nothing: "not on this stand" is not an unavailable day |

The migration (V161) and the reading of a 3.x file or copy (`ScenarioMigrations.withRetiredAdHocTypesConverted`) make the same conversion and say so. The saved plan does not change. What cannot be converted (an unavailability aimed at a stand, an assignment nobody holds, an assignment with no timeslot) is reported and kept in the `contrainte_ad_hoc_avant_4_0` archive, which 5.0 drops. The event-day mode marks an absence the same way: the whole day on the fiche (see [the event-day API](api-jour-j-et-animateurs.md)).

## Grouped arrival and carpool requests

Grouped arrival most often comes from a request. In the Carpool tab of their espace, during the availability collection window, a staff member names up to three teammates ("I'm coming with…"). The request is **independent of the availability declaration**: its own submission, its own decision. It is stored in `declaration_coequipier`, a generic table (`nature` = `COVOITURAGE`, `BINOME` reserved), and applying or refusing a declaration does not touch it. Only one request stays pending per staff member.

The administrator **validates** it (Carpool tab of the Availability page), which creates the adjustment, or **sets it aside**, which deletes the request: the staff member may send another. Only the validation goes out by e-mail, on a best-effort basis, to each member. A validated group can no longer be changed from the espace: the adjustment belongs to the organisation, which can **cancel** it from the same tab. Cancelling deletes the adjustment and, in the same transaction, all the requests validated with it (`contrainte_id`); nobody is notified by e-mail, and a new request follows the ordinary rule (during the collection window only).

| `statut` (`StatutDemandeCoequipier`) | Meaning |
| --- | --- |
| `EN_ATTENTE` | Sent from the espace, waiting for the administrator |
| `VALIDEE` | Turned into an `ARRIVEE_GROUPEE` adjustment (`contrainte_id`) |

A request set aside or cancelled has no state: it is deleted.

As long as a `VALIDEE` request points to a grouped arrival, **no other write modifies or deletes it**: the Manual adjustments page, MCP tools, any call to `ContrainteAdHocService` answers `409` with a single sentence that points to the Carpool tab. A grouped arrival entered by hand stays editable. A scenario import and the reset, which replace the adjustments of the whole edition, are not affected. A grouped arrival has 2 to 4 distinct members, with no timeslot or stand: a badly filled shape is refused on entry as on import.

## Contradictions refused on entry

The incompatibility applies as hard. Nothing would therefore prevent entering one that contradicts another exception, and the only symptom would be a negative hard score at the next solve, with nothing pointing at the cause. `ContrainteAdHocContradictions` refuses these combinations on write, with a message that **names the exceptions involved**. Two families are detected:

| Combination | Why it is impossible |
| --- | --- |
| The same pair declared `INCOMPATIBILITE` **and** `AFFINITE` | The hard rule would always win over the soft reward, without anyone being warned |
| An `ARRIVEE_GROUPEE` where **two members** form an `INCOMPATIBILITE` pair **with no stand or timeslot** | Aligning their days makes the incompatibility nearly impossible to hold: the hard rule would win and the carpool would be penalised every day without anyone knowing why |

The second is the only one that is not *certainly* unsatisfiable: a grouped arrival is soft, and two incompatible members could arrive together and never hold the same timeslot. It is refused anyway, in both directions (the grouped arrival after the incompatibility, or the reverse), because the combination describes nothing an organiser wants: you do not put in the same car two people you refuse to have work together. Only the **general** incompatibility is concerned. An incompatibility limited to one stand or one timeslot leaves the pair free to hold the same hours elsewhere, which is enough for the car: it passes.

### What deliberately passes

The check refuses only what is **certainly** contradictory: an exception refused wrongly costs the user a legitimate entry, with no way round it, where a questionable exception let through costs only a solve. An incompatibility limited to a stand or a timeslot, for instance, passes next to a grouped arrival of the same pair.

### Where the check applies

It holds for **every** write: single entry (`POST /api/contraintes-ad-hoc`, MCP tool `creer_contrainte_ad_hoc`) as well as the import of a scenario, which writes the whole set at once. A file cannot install a combination that the form refuses. Saving an exception again **under its own id** remains possible: the saved version replaces the previous one instead of coexisting with it.

A budget of exceptions ("no more than N") was explicitly set aside in favour of this check: see [decision 0010](repo:docs/decisions/0010-contraintes-ad-hoc-contradiction-plutot-que-budget.md).

### What remains, and how to see it

A contradiction entered **before** this check stays in the database and would appear nowhere. `FeasibilityAnalyzer` therefore raises it as the blocking cause `CONTRAINTES_AD_HOC_CONTRADICTOIRES`, before any solve: it shows on the Problems page and badges the affected lines of the adjustments page.

When a solve ends **anyway** with a negative hard score, the diagnosis answers the next question, "which of my exceptions?", with `contraintesAdHocEnCause`: one line per exception still in breach, its id, its reason and the number of breaches it carries. The breach lines also name the exception by its id.
