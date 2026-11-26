---
twin: regles/ajustements-et-verrous
---
# Set exceptions and locks

You impose or forbid an assignment before the solve, and you freeze part of the plan so the next solve no longer touches it.

**Manual adjustments**, **locks** and **consignes** are the first three tabs of *Planning rules*: what the next solve must respect. After each entry, re-running the solve is offered on the spot:

- a *correction* after a consigne, which reopens only the seats it removed or added;
- a *full solve* starting from the current plan after an adjustment or a lock.

## Manual adjustments: place or rule out someone before the solve

An adjustment is a one-off exception, recorded with its reason. It is the opposite of a lock, which freezes after the fact. The types:

- forced unavailability;
- incompatibility between two people;
- imposed assignment;
- pair to favour.

1. Open *Planning rules*, *Adjustments* tab.
2. Start the entry with the person; the list can also be filtered on a person.
3. Enter the type, the reason and the dates.

**Contradictions are refused on save.** An adjustment that cannot hold at the same time as another one already entered is refused with a message naming both, rather than a schedule declared infeasible several minutes later with nothing pointing at the cause.

If the adjustment places someone where no seat can hold them (a minor forced onto a night, a public holiday or an adults-only stand, someone whose schedule is locked), it is flagged on entry and named in the blocking causes.

## Locks: freeze part of the plan

A lock freezes a person, a stand, a day or a timeslot. The next solve no longer touches it and optimises the rest. A lock is an action on a plan that is already solved: it keeps what a solve produced, and says nothing about what should be there.

1. Open *Planning rules*, *Locks* tab (or lock from a person's profile, from a seat's panel, or from the *Fragility* tab of the Diagnostic).
2. Before a lock is set, the screen counts the assignments it will freeze and leads to the day that shows them.
3. Confirm.

## Act on several rows at once

Adjustments and locks can be ticked several at a time ("Select all" follows the filter) and deleted or unlocked in one action, after a single confirmation. The report says what went through and what was refused.

## Validated grouped arrivals

A validated grouped arrival (see [Collect availability](collecter-disponibilites.md#traiter-les-demandes-de-covoiturage)) becomes a manual adjustment that only the organisation can cancel, from *Staff*, *Availability* tab, then *Carpool*. The adjustments page neither edits nor deletes it.

## Consignes from the authority

The third tab, *Consignes*, carries the closures imposed from outside: see [Apply a consigne from the authority](appliquer-une-consigne.md).
