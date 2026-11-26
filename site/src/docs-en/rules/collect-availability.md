---
twin: regles/collecter-disponibilites
---
# Collect staff availability

You open a period during which each staff member declares their own unavailability days and wishes, then you apply the declarations in bulk before the solve.

Prerequisites: the staff members have an e-mail address and the edition is **the active edition**. Each staff member declares from their espace (see [The staff espace](espace-animateur.md)), by finger on a phone, ticking days.

Why let everyone declare for themselves: see [collecting the team's availability](/organiser-son-equipe/), on the organiser's side.

## Open the collection

1. Open Settings, *Edition* tab, and set the availability collection period (it is opened and closed from this tab; the Availability page keeps a status line).
2. Tick the **invitation** box if you want: it sends everyone the link to their espace when the collection opens. Tick it on the first round, leave it out when you reopen the period after a correction.
3. If you want a reminder to go out automatically, switch on the **reminder**: it is off by default.

![The availability declarations received](/images/fr/docs/disponibilites.webp "The availability collection and the declarations received")

### Resend the links after opening

With the collection open, the "Send the links" button of the *Availability* page resends the links without touching the period. It writes to:

- staff members not yet invited (by default: profiles added or addresses completed after opening);
- or everyone who has declared nothing.

The report says who was reached, who has no address and which sends failed.

### The automatic end-of-period reminder

If you switched it on with the collection, each staff member who received the invitation and has not yet declared anything gets, three days before the end of the period, an e-mail recalling the deadline with the link to their espace.

- There is only one reminder per person and per end date.
- A missed night is caught up the next day.
- Nothing goes out from a closed collection or one without an end date, nor from an edition that is not the active one.

## Apply the declarations

A declaration remains a **proposal**: it is taken into account only once you apply it, in bulk, from the *Availability* page (a tab of the *Staff* page). While it is pending, the solve does not see it, and the Solver tells you so in "what the solve will receive".

## Handle carpool requests

During the collection, in the *Carpool* tab of their espace, a staff member names up to three teammates they are coming with ("I'm coming with..."). This tab is separate from their declaration. Outside the collection, it shows where their request stands.

On the organisation side, *Staff*, *Availability* tab, then *Carpool*, lists these requests. If everyone has named the others, it says by how many days their unavailabilities differ. You decide each request separately:

- **validate** the grouped arrival;
- or **dismiss** it, with an optional reason that the staff member reads.

Everyone is notified by e-mail.

A validated request becomes a manual adjustment that only the organisation can cancel, from this same tab, notifying each member. The solve tries to give them the same days and close arrivals and departures (adjustable tolerance, 30 minutes by default), without imposing the same stand. The Rail marks the members, the Problems page lists the misaligned days, and the espace tells each person, day by day, whether their carpool holds.

## Next

Start the solve: [Solve the schedule](calculer-planning.md).
