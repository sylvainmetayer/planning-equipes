---
twin: regles/ajustements-et-verrous
---
# Set exceptions and locks

You forbid two people from working together, you keep someone on a timeslot or rule them out of a day, and you freeze part of the plan so the next solve no longer touches it.

**Manual adjustments**, **locks** and **consignes** are the first three tabs of *Planning rules*: what the next solve must respect. After each entry, re-running the solve is offered on the spot:

- a *correction* after a consigne, which reopens only the seats it removed or added;
- a *full solve* starting from the current plan after an adjustment or a lock.

## Manual adjustments: forbid or bring together before the solve

An adjustment is a one-off exception, recorded with its reason. It is the opposite of a lock, which freezes after the fact. Only three types are left:

- incompatibility between two people (never the same timeslot);
- pair to favour;
- grouped arrival (carpool, two to four people).

1. Open *Planning rules*, *Adjustments* tab.
2. Start the entry with the person; the list can also be filtered on a person.
3. Enter the type, the reason and, where the type allows it, the timeslot or the stand.

**Contradictions are refused on save.** An adjustment that cannot hold at the same time as another one already entered (the same pair both incompatible and in affinity) is refused with a message naming both, rather than a schedule declared infeasible several minutes later with nothing pointing at the cause.

## How to do without a forced assignment

The forced assignment and the forced unavailability no longer exist since 4.0. Two gestures replace them, each undone in a click:

- **Keep someone on a timeslot**: place them on the seat from the *Planning* page ("Place", with "Keep them at the next solve" ticked), or lock their seat. The lock ties that person to that timeslot; the next solve respects it, and the rest of their day stays free.
- **Rule someone out of a day**: on their fiche, click the day, then "Unavailable that day". They are no longer offered that day, and their seats of the day are freed in the same gesture. The day is always whole: the fiche knows no timeslot. For an unavailability over part of the day, take the person off the seat and lock them elsewhere.

A lock freezes what the saved plan carries, nothing more: after a reset or an import, set it again. The forced exceptions of a 3.x edition were converted the same way at migration, and what could not be is reported ([Moving from 3.x to 4.0](../installer/passer-a-la-4-0.md)).

## Locks: freeze part of the plan

A lock freezes a person on a timeslot, a stand on a day, or a whole day. The next solve no longer touches it and optimises the rest. To freeze a person's whole schedule, use the button on their profile: it lays one lock per timeslot. A lock is an action on a plan that is already solved: it keeps what a solve produced, and says nothing about what should be there.

1. Open *Planning rules*, *Locks* tab (or lock from a person's profile, from a seat's panel, or from the *Fragility* section of the Diagnostic's *Risks* tab).
2. Before a lock is set, the screen counts the assignments it will freeze and leads to the day that shows them.
3. Confirm.

The staff member + timeslot lock is also the way to keep someone on one precise timeslot: place them on the seat (from the day view), then lock them on that timeslot; the solve keeps them there and gives them no other seat at that time.

## Act on several rows at once

Adjustments and locks can be ticked several at a time ("Select all" follows the filter) and deleted or unlocked in one action, after a single confirmation. The report says what went through and what was refused.

## Validated grouped arrivals

A validated grouped arrival (see [Collect availability](collecter-disponibilites.md#traiter-les-demandes-de-covoiturage)) becomes a manual adjustment that only the organisation can cancel, from *Staff*, *Availability* tab, then *Carpool*. The adjustments page neither edits nor deletes it.

## Consignes from the authority

The third tab, *Consignes*, carries the closures imposed from outside: see [Apply a consigne from the authority](appliquer-une-consigne.md).
