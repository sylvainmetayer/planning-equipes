---
twin: regles/suivre-le-jour-j
---
# Follow the event day

You handle the day's unforeseen events from a phone (an absentee to replace, an empty seat to fill), you show the day on a television, then you measure afterwards the gap between planned and realised.

![The wall display](/images/fr/mural.webp "The control room's wall display")

What these screens change during the event is told in [the event day of a festival](/planning-festival/) and in [the control room of a trade show or fair](/planning-salon-foire/).

## Handle the day on the Today screen

*Today* (*Today* group) is the screen for the day itself, designed for a phone. At the top, the day in one line: day, open stands, remaining shifts. Counters lead to where you deal with things. No solve is started again.

![The Today screen](/images/fr/docs/aujourdhui.webp "The Today screen, on the day of the event")

### Replace an absentee

1. Look for the person by name or number, among the whole staff.
2. Mark them absent for the rest of the day.
3. Read the list of those who can take over their seats, with their phone numbers.
4. Apply the replacement.

If the first candidates evaluated do not fit, "Search further" continues until it finds someone, or says that nobody can hold the seat without breaking a hard rule.

The current timeslot can also be repaired: the seat is cut at the current time, what has been held stays in the absentee's name, the rest of the timeslot is replaced.

Seats opened since this morning are distinguished from the gaps already known at publication.

### Notify the people concerned

"Notify the N people" publishes the plan, each espace showing the new version, and sends the e-mail only to the people concerned. The others remain to be notified at the next publication.

### When someone reports they will not come

A staff member can report their absence from their espace (see [The staff espace](espace-animateur.md#je-ne-pourrai-pas-venir)). You see it in the counters, record the absence and replace, or dismiss the report.

## Show the day on a television

The **wall display** is a screen for the control room TV, readable from three metres. It shows:

- the day's open stands grouped by location;
- the current shift and the next one;
- the seats empty since this morning in red, called "new", and the gaps already known faded;
- the closed stands on a single line;
- at the bottom, what is new: the shift starting within half an hour with an empty seat, the break with no relay within the hour and the day's consigne (an official order relayed to the whole event). It is the same computation as the Today screen.

It updates itself every minute, keeps its last state if it loses the network and rotates its pages when everything does not fit. It only says "may differ from the schedule sent" when that is the case, with the number of people. It prints as a stands × shifts table for the whole day.

To connect it:

1. Open Settings, *Wall display* tab, and create a link. The link is displayed only once. You can revoke it at any time.
2. Open it on the television. The screen never uses an administration session left open.
3. If needed, limit the link to a few locations: one TV per zone.

By default the screen shows the first name and the initial; one more letter, then the full name, where two people would read the same. From the Planning and Today pages, "Show on the TV" leads to this link.

## Measure the realised against the planned

The *Realised vs planned* view of the *Edition measures* screen (a link at the top of *Today*) gives, afterwards or day by day, the gap between the published schedule and what was actually held, stand by stand and day by day:

- seats published, held, absences marked on the event day, replacements, seats left empty;
- hours published, realised and lost;
- the totals by stand, by day, by game category and for the event.

A few rules for reading it:

- Each elapsed day (its last timeslot over) is compared with the published schedule in force when its first timeslot opened. A republication during the event does not rewrite the gap of past days, and the publication that served as the reference for an elapsed day can no longer be deleted.
- Upcoming days do not count. A day started before any publication is shown without entering the totals. Before the first publication, the screen says there is nothing to compare.
- A seat removed by a consigne or a deleted timeslot is not an absence.
- The realised is **declared**: it is what the tool recorded, not an attendance check-in, and the screen says so.
- A cell opens its shifts, names included, on screen only. The CSV export only contains stands, days and counters.

Once the event is over, the edition's measure is computed then frozen once and for all (at the first read of the screen, of the Versions screen of the next edition, or when the edition is deleted). It outlives the edition: the Versions screen of the next edition recalls it, and the Staffing need tab displays, for information, the absence rate and hours lost of each game category the previous year.

The *Solve indicators* view, on the same screen (`?vue=indicateurs`), lists the result of every finished solve, every edition together, so that an edition can be compared with the previous one; a row can be deleted there, and otherwise outlives its edition.

## After the event

Archive the edition and prepare the next one: [Import and export files](importer-exporter.md#archiver-une-édition-terminée) and [Chain editions](enchainer-les-editions.md).
