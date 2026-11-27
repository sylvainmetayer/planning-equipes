---
twin: regles/consulter-planning
---
# View the schedule

You read the solved schedule by day, by stand, by person or on the map, and find the information you need: who is where, what is missing, what each person received.

Everything goes through a single page, *Planning* (*Plan* group). Everything is carried by the address: a view, a filter are shared through the link (`?vue=jour|stand|personne|carte`).

## Find your way around the page

- The title fits on one line. The day is chosen on a collapsible mini-month where each cell shows its empty seats, its review, its lock and its consigne. "Today" is the server's date.
- Four views: **Day**, **Stand**, **Person**, **Map**.
- The same filters (stand, staff member, location, game category) stay from one view to the next. Type two letters to search.
- A review counter (empty seats and breaks without relay) leads to the Diagnostic problems.
- The day's consigne fits on one line.
- "Print this day" puts the whole day on paper. "Show on the TV" leads to the wall display link.

![The rail of a day](/images/fr/rail.webp "The rail: one row per staff member")

## Day

The **Day** view has two modes, chosen next to the "Day" button: the **rail**, open by default, and the **table**. The table is written in the address (`vue=jour&mode=tableau`); the rail, the default mode, writes nothing there. The review bar and the state of the day (to review, reviewed, frozen) stay there in both modes.

### Table mode

The day as a table across the full width of the screen: one stand per row with its location, one timeslot per column, one name per label. Empty seats are in red, a closed stand in grey. Each name and each empty seat opens the seat panel for manual correction (see [Review and adjust the schedule](relire-et-retoucher.md)).

### Rail mode

The same day seen by person: one row per staff member, their shifts placed in time, the legal breaks laid on top (in red without relay), and empty rows for those who remain available. Each shift opens the seat panel, from which "Move to..." hands it to another person or swaps it with theirs.

## Stand

One row per stand, one column per day: those of the week of the chosen day, or that day alone, or the whole event in colour only. In each cell, the people who hold the seats, or their counters (2/2), or the colour alone. The cell is coloured by its coverage (filled, partial, empty), and a stand closed that day is distinguished from an empty stand.

On the right: the seats to fill, the filled ones, the share of seat-hours held and the hours. At the bottom: the totals of each day. A cell opens the seat panel on that stand that day.

The Stand view has a second rendering, the **Hours breakdown**: one rectangle per stand, the larger the more seat-hours the stand requires, coloured by the share held (striped below 80%). It can be grouped by location or by game category, and restricted to a week or a day. One click enlarges a group, a second opens the stand's day.

## Person

![The schedule by person](/images/fr/personne.webp "Each staff member's week, hours and evenings included")

One row per staff member, one column per day (the week of the chosen day, that day alone or the whole event in colour only, as in the Stand view). In each cell, each stand held with its hours underneath. The cell is coloured according to whether the person works, rests or was unavailable; an evening and an assignment set on an unavailable day are marked there. A cell opens the seat panel; the name leads to the profile, where a person's radar is read.

A **timeline** draws one bar per person, which fits a month-long event on one screen.

What each person received over the whole event (hours, fairness, payroll) is read in the Diagnostic, *Workload* tab: see [Understand a stuck schedule](understand-a-stuck-schedule.md#read-everyones-workload-the-workload-tab).

## Map

The day on the map of locations, with a time slider. Each place is coloured according to whether its stands there are open and filled, open with empty seats, or open with nobody. It shows the number of people present at that hour, and its size follows the headcount. A location × time band grid (or location × day, at the peak of each day) says how many people are where, to size the supervision and the logistics of a location.

## What is no longer on this page

- **By game category**: the reading by game category (seats, hours, skilled, assigned without the skill) is the *Typology* section of the *Staffing need* tab of the Diagnostic.
- **Summary by person**: the hours, fairness and the two CSV exports are the Diagnostic's *Workload* tab.
- **Breaks and meals**: the breaks without relay are read in *Diagnostic*, *Problems* tab.
- **Changes**: what moved since the last publication is read in *Publish*, *Send* tab, one row per person, and what the last solve rewrote in the result of the *Solver* page.
- **Compare two days**: removed.

The old addresses of these views lead to the screen that replaces them.
