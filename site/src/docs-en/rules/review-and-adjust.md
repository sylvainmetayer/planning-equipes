---
twin: regles/relire-et-retoucher
---
# Review and adjust the schedule

You review the schedule day by day, correct a seat by hand without breaking a rule, and mark what has been reviewed.

The solve is a proposal: you review, adjust and decide. All adjustments go through the *Planning* page.

## Correct a seat with the Seat panel

A click on any cell of the Planning page opens the **panel of that seat** beside it: a name or a free seat of the table, a shift of the rail, a stand of the map, a break with no relay, a line of the changes. The panel shows the stand, the timeslot, the person, the locks and, in sentences, the rules in default.

### On a held seat

- *Replace* with a viable replacement;
- *Move to...* another seat;
- *Free*, keeping the person away from this timeslot at the next solve (box ticked by default);
- lock or unlock, see who could hold it, open the person's fiche.

Replace and free only touch the seat if it is still held by the person displayed.

### On an empty seat

1. "Who can hold this seat?" says who is on duty nowhere, and which rule would prevent each from holding the seat.
2. "Place" assigns the person and keeps them at the next solve. The action is refused for a person a lock holds out of this timeslot, and on a timeslot that has already started.
3. "Add an adjustment" opens the prefilled form.

### What the application checks for you

A placement made directly (from the panel, the repair screen or an assistant) is **simulated before writing**. It is refused if the gesture breaks a hard rule, naming it; if it costs quality, the application says so. *Free* always goes through: marking an absence on the event day must never be blocked by the plan being repaired.

### After the gesture

The panel offers to notify the people concerned (the change is not published) and, if a seat has been left empty, to fix the rest with a partial re-solve.

## Move or swap from the table

In the *Table* view, each name and each empty seat opens the seat panel. "Move to..." moves to a free seat, or swaps with a person; the action is refused if a hard rule would be broken. The *Rail* offers the same gesture from a shift, to give it to another person or swap it with theirs.

## Mark a day "reviewed and accepted"

1. On the Planning page, find the counter of what is left to look at (empty seats and breaks with no relay): it leads to the problems of the Diagnostic.
2. In the day's menu, choose "Reviewed and accepted...". The prerequisites of that day are displayed: hard deviations, empty seats, breaks with no relay, irreplaceable seats.
3. Accept, possibly anyway: the application then says so.

Progress is read in the Solver ("3 days out of 12") and by a tick on each reviewed day of the Planning page's mini-month. The validation always covers the whole day, whatever the filter displayed.

Accepting freezes nothing: locking is offered alongside, never imposed (see [Set exceptions and locks](ajustements-et-verrous.md)).

### When a new solve removes a review

A review survives a solve only if its day was not recomputed.

- A day already entirely worked, or that carries a whole-day lock, keeps its review.
- A solve started from the plan in place removes the review of the days where it moved a seat, or removed a reviewed seat.
- "Start over from scratch" removes that of every recomputed day, even if it landed on the same crew.

The summary says how many reviews are removed and how many are kept, with their reason (already worked, locked, unchanged). The confirmation of "Start over from scratch" lists in advance those that will survive, and the publication panel says how many unreviewed days would go out.

## Go back

A snapshot of the plan is taken automatically before each solve, and you can create others: see [Solve the schedule](calculer-planning.md#garder-des-versions-du-plan).
