---
twin: comprendre/exceptions-ad-hoc
---
# Ad hoc exceptions and their contradictions

You will know what the five types of ad hoc exception are, how a carpool request becomes one, which combinations the application refuses on entry, which it deliberately lets through, and how it flags an exception that no seat can honour.

## What an ad hoc exception is

`ContrainteAdHoc` in the domain and on the wire, "Manual adjustments" on screen (Adjustments tab of "Planning rules"). These are one-off exceptions set by the administrator, stored in the database and evaluated dynamically: **never a hard-coded rule for a particular case**. What an organiser enters there is an exception to the plan, not a catalogue rule. The user-side edge cases ("one of these staff members" semantics, empty scope, deleted timeslot) are in the online help, "Manual adjustments" section.

| Type (`TypeContrainteAdHoc`) | What it says | Level |
| --- | --- | --- |
| `INDISPONIBILITE_FORCEE` | A staff member does not work within a scope | Hard |
| `INCOMPATIBILITE` | Two staff members never hold the same timeslot, whatever the stand; the scope can restrict it to one stand or one timeslot | Hard |
| `AFFECTATION_FORCEE` | At least one seat of the scope is held by **one** of the named staff members, never by all | Hard |
| `AFFINITE` | A pair to bring together: rewarded on the same stand and the same timeslot, never imposed | Soft |
| `ARRIVEE_GROUPEE` | Two to four staff members who arrive and leave together (a carpool), with no timeslot or stand | Soft |

The prescriptive types are scored hard, at the same priority as the legal rules: never downgrade them to medium or soft. `AFFINITE` is the deliberate exception, a soft reward, and promoting it to hard would be a forced assignment in disguise.

These exceptions are set **before** the solve: every solve honours them, including when starting from scratch. Not to be confused with the [lock](plan-verrous-et-publication.md#le-verrou-fige-des-sièges), which freezes *after the fact* what a solve produced without saying anything about what should be there. The help carries a box, "Manual adjustment or lock?".

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

Hand-entered exceptions apply as hard. Nothing would therefore prevent entering two that cannot be satisfied together, and the only symptom would be a negative hard score at the next solve, with nothing pointing at the cause. `ContrainteAdHocContradictions` refuses these combinations on write, with a message that **names the exceptions involved**. Five families are detected:

| Combination | Why it is impossible |
| --- | --- |
| The same pair declared `INCOMPATIBILITE` **and** `AFFINITE` | The hard rule would always win over the soft reward, without anyone being warned |
| An `AFFECTATION_FORCEE` whose whole scope is covered by an `INDISPONIBILITE_FORCEE` targeting each of the named staff members | Any seat that would satisfy the first is penalised by the second |
| Two `AFFECTATION_FORCEE` fixing **the same staff member** on scopes that overlap in time | `pasDeChevauchementHoraire` forbids holding both seats, and no single seat satisfies both |
| Two `AFFECTATION_FORCEE` placing an `INCOMPATIBILITE` pair on **the same timeslot** | `incompatibiliteAdHoc` joins on the timeslot alone: two seats on two stands of that timeslot are just as infeasible |
| An `ARRIVEE_GROUPEE` where **two members** form an `INCOMPATIBILITE` pair **with no stand or timeslot** | Aligning their days makes the incompatibility nearly impossible to hold: the hard rule would win and the carpool would be penalised every day without anyone knowing why |

The fourth line is read **at the timeslot, not at the stand**, which is what the rule actually applies: restricting detection to the same stand would let through combinations that the solver would declare infeasible.

The fifth is the only one that is not *certainly* unsatisfiable: a grouped arrival is soft, and two incompatible members could arrive together and never hold the same timeslot. It is refused anyway, in both directions (the grouped arrival after the incompatibility, or the reverse), because the combination describes nothing an organiser wants: you do not put in the same car two people you refuse to have work together. Only the **general** incompatibility is concerned. An incompatibility limited to one stand or one timeslot leaves the pair free to hold the same hours elsewhere, which is enough for the car: it passes.

### What deliberately passes

The validation refuses only what is **certainly** unsatisfiable: an exception refused wrongly costs the user a legitimate entry, with no workaround, where a doubtful exception let through costs only a solve. So the following pass:

- an `AFFECTATION_FORCEE` naming two staff members, which only one of them is enough to satisfy: it contradicts only an unavailability that covers both of them;
- an unavailability **narrower** than the forced scope: the seat can be placed elsewhere in that scope;
- two forced assignments of the same staff member on the same timeslot when at most one of the two specifies a stand: a single seat satisfies both.

Overlaps are computed on the timeslot's **nominal** window. A stand closure can narrow the window a seat really covers: two forced assignments on timeslots that overlap, but on two stands closed at complementary hours, are therefore refused although the solver could have placed them. An exception's scope is what the user entered, and refusing this combination while naming both is the honest answer.

### Where the check applies

It holds for **every** write: single entry (`POST /api/contraintes-ad-hoc`, MCP tool `creer_contrainte_ad_hoc`) as well as the import of a scenario, which writes the whole set at once. A file cannot install a combination that the form refuses. Saving an exception again **under its own id** remains possible: the saved version replaces the previous one instead of coexisting with it.

A budget of exceptions ("no more than N") was explicitly set aside in favour of this check: see [decision 0010](repo:docs/decisions/0010-contraintes-ad-hoc-contradiction-plutot-que-budget.md).

### What remains, and how to see it

A contradiction entered **before** this check stays in the database and would appear nowhere. `FeasibilityAnalyzer` therefore raises it as the blocking cause `CONTRAINTES_AD_HOC_CONTRADICTOIRES`, before any solve: it shows on the Problems page and badges the affected lines of the adjustments page.

When a solve ends **anyway** with a negative hard score, the diagnosis answers the next question, "which of my exceptions?", with `contraintesAdHocEnCause`: one line per exception still in breach, its id, its reason and the number of breaches it carries. The breach lines also name the exception by its id.

## Forced assignments that cannot be held

An `AFFECTATION_FORCEE` can be untenable without contradicting any other exception: it pits an exception against data that, for its part, cannot be refused. Three readings, three pure classes, one doctrine: **warn on write, report as a blocking cause as long as it holds, never refuse**. The data that breaks the exception almost always arrives after it, and a refusal would hold only until then ([decision 0046](repo:docs/decisions/0046-un-placement-intenable-est-dit-avant-le-calcul.md)).

| Reading | What makes the exception untenable | Warning and cause |
| --- | --- | --- |
| `ForcedAssignmentOnDayOff` | **All** the named staff members declared themselves unavailable on **every** date of the scope | `AFFECTATION_FORCEE_JOUR_INDISPONIBLE` |
| `ForcedAssignmentOnExcludedSeats` | No seat of the scope accepts one of the named staff members under a hard rule of the seat × staff member pair, the reasons of `EligibleAnimateurMoveFilter`: minor at night, public holiday, stand reserved for adults, daily cap exceeded | `AFFECTATION_FORCEE_MOTIF_LEGAL`; the message names the catalogue rules |
| `ForcedAssignmentOnLockedSchedule` | The schedule of each of the named staff members is locked (`ANIMATEUR` or `ANIMATEUR_CRENEAU`) over the whole scope, and none holds a seat there already: the solver can neither move the lock nor ignore the exception | `AFFECTATION_FORCEE_SIEGE_VERROUILLE` |

The first two are never doubled: the day declared unavailable is *also* a hard exclusion reason, so the second stays silent on a case the first reports, because the reading in terms of declaration is the one the organiser knows how to handle, by talking to the person. The third can add to the other two, and that is intended: a lock and a legal rule are two independent blockages, lifted by two different actions.

Two precautions avoid inventing a conflict. The seats of the scope are built as `ProblemBuilder` generates them, one per (stand open on the timeslot, timeslot): a scope that opens nowhere, or that names a stand unknown to the reference data, carries no seat and says nothing. And the duration caps are read with the most permissive reading of the break, so that a reported conflict is a conflict the solver would meet whatever the edition says.

Only the `ANIMATEUR` and `ANIMATEUR_CRENEAU` locks enter the third reading. A stand, timeslot or day lock pins the seats that **hold someone** and leaves empty seats fillable: it never opposes the assignment the exception demands.

Finally, the solve keeps the day of unavailability, the lock and the legal rule: it is the exception that is not honoured. It is then counted as a breach and named by `contraintesAdHocEnCause`.
