---
twin: regles/relire-et-retoucher
---
# Review and adjust the schedule

You review the schedule day by day, correct a seat by hand without breaking a rule, and mark what has been reviewed.

The solve is a proposal: you review, adjust and decide. All adjustments go through the *Planning* page.

## Correct a seat with the Seat panel

A click on any cell of the Planning page opens the **panel of that seat** beside it: a name or a free seat of the table, a shift of the rail, a stand of the map. The panel shows the stand, the timeslot, the person and the locks, then its two gestures.

### Simulate a change

"Simulate a change" tries a change without writing anything.

1. The panel offers the best replacements (or, on an empty seat, the best people to place), with their effect on the score. On a held seat, "To another seat..." picks instead a seat to move the person to: a free seat, or the seat of a person to swap with.
2. "Simulate" on one of them shows **the before and the after**: the score before and after, what the change settles on this seat and what it damages there. A change that would break a hard rule is announced "Refused", naming it, and cannot be applied.
3. "Apply" writes exactly that change. On an empty seat, the box "Keep them there at the next solve" (ticked by default) also protects it from the next solve.

"See the whole bench" shows everybody: who is on duty nowhere, and which rule would prevent each from holding the seat. On an empty seat, "Place" assigns a person directly.

### Explain

On a held seat, "Explain" says why this person is there: the rules in default on this seat, in sentences, read with the edition's settings.

### The other gestures of a held seat

- *Free*, keeping the person away from this timeslot at the next solve (box ticked by default);
- *Mark absent* on this timeslot;
- lock or unlock, open the person's fiche.

Apply and free only touch the seat if it is still held by the person displayed.

### What the application checks for you

A change applied (from the panel, the Today screen or an assistant) is **simulated again before writing**. It is refused if the gesture breaks a hard rule, naming it, on a timeslot that has already started, on a locked seat and during a solve; if it costs quality, the application says so. *Free* always goes through: marking an absence on the event day must never be blocked by the plan being repaired.

A read-only user simulates and asks for explanations like everybody else, but has no button to apply.

### After the gesture

The panel offers to notify the people concerned (the change is not published) and, if a seat has been left empty, to fix the rest with a partial re-solve.

## Move or swap from the table

In the *Table* view, each name and each empty seat opens the seat panel. "Simulate a change", then "To another seat...", moves to a free seat or swaps with a person, after showing the before and the after; the action is refused if a hard rule would be broken. The *Rail* offers the same gesture from a shift, to give it to another person or swap it with theirs.

## Change the state of a day

1. On the Planning page, find the counter of what is left to look at (empty seats and breaks with no relay): it leads to the problems of the Diagnostic.
2. Click the day's state button ("To review", "Reviewed on..." or "Frozen"). A three-valued control opens, with the prerequisites of that day: hard breaches, empty seats, breaks with no relay, irreplaceable seats.
3. Choose "Reviewed", possibly anyway, with a comment, then "Save the state". Choosing "Frozen" asks for confirmation: the solver will no longer touch that day, and the review comment is kept.

A read-only user sees the state of the day, with no button to change it.

Progress is read in the Solver ("3 days out of 12") and by a tick on each reviewed day of the Planning page's mini-month. The state always covers the whole day, whatever the filter displayed.

Reviewing freezes nothing: only the "Frozen" value lays the whole-day lock (see [Set exceptions and locks](ajustements-et-verrous.md)).

### The three states of a day

Each day is **to review**, **reviewed** or **frozen**.

| State | What it means | What a new solve does with it |
| --- | --- | --- |
| To review | Nobody has reviewed it | It recomputes it freely |
| Reviewed | Someone reviewed it, nothing is frozen | It may recompute it, and the day then goes back to review |
| Frozen | It carries a whole-day lock | It no longer touches it |

Only your gesture freezes a day: reviewing it never does. Removing the whole-day lock makes it reviewed if it had been reviewed, to review otherwise. A frozen day counts as reviewed in the progress, even when locked from the locks screen without being accepted. The edition's phase does not change the state of the days: you can review or freeze a day in preparation, while computing as well as after publication.

### When a new solve removes a review

A review survives a solve only if its day was not recomputed.

- A day already entirely worked, or that carries a whole-day lock, keeps its review.
- A solve started from the plan in place removes the review of the days where it moved a seat, or removed a reviewed seat.
- "Start over from scratch" removes that of every recomputed day, even if it landed on the same crew.

The summary says how many reviews are removed and how many are kept, with their reason (already worked, locked, unchanged). The confirmation of "Start over from scratch" lists in advance those that will survive, and the publication panel says how many unreviewed days would go out.

## Go back

A version of the plan is taken automatically before each solve, and you can save others: see [Solve the schedule](calculer-planning.md#garder-des-versions-du-plan).
