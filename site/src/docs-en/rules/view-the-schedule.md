---
twin: regles/consulter-planning
---
# View the schedule

You read the solved schedule by day, by stand, by person or by game category, and find the information you need: who is where, what is missing, what each person received.

Everything goes through a single page, *Planning* (*Plan* group). Everything is carried by the address: a view, a filter, a comparison are shared through the link.

## Find your way around the page

- The title fits on one line. The day is chosen on a collapsible mini-month where each cell shows its empty seats, its review, its lock and its consigne. "Today" is the server's date.
- Four axes: **by day**, **by stand**, **by person**, **by game category**.
- The same filters (stand, staff member, location, game category) stay from one axis to the next. Type two letters to search.
- A review counter (empty seats and breaks without relay) leads to the Diagnostic problems.
- The day's consigne fits on one line.
- "Print this day" puts the whole day on paper. "Show on the TV" leads to the wall display link.

![The rail of a day](/images/fr/rail.webp "The rail: one row per staff member")

## By day

The **Day** view has two modes, chosen next to the "Day" button: the **rail**, open by default, and the **table**. The table is written in the address (`vue=jour&mode=tableau`); the rail, the default mode, writes nothing there. The map, the breaks and meals and the changes are the day's other views. The review bar and the state of the day (to review, reviewed, frozen) stay there in both modes.

### Table mode

The day as a table across the full width of the screen: one stand per row with its location, one timeslot per column, one name per label. Empty seats are in red, a closed stand in grey. Each name and each empty seat opens the seat panel for manual correction (see [Review and adjust the schedule](relire-et-retoucher.md)).

### Rail mode

The same day seen by person: one row per staff member, their shifts placed in time, the legal breaks laid on top (in red without relay), and empty rows for those who remain available. Each shift opens the seat panel, from which "Move to..." hands it to another person or swaps it with theirs.

### Map

The day on the map of locations, with a time slider. Each place is coloured according to whether its stands there are open and filled, open with empty seats, or open with nobody. It shows the number of people present at that hour, and its size follows the headcount. A location × time band grid (or location × day, at the peak of each day) says how many people are where, to size the supervision and the logistics of a location.

### Breaks and meals

Where the legal breaks fall, day by day and stand by stand: who leaves at what time at the latest, for how long, and who is there to relieve. Next to it, the meal break due for each day that straddles a meal window, with what is missing when the grid leaves no room for it.

Below, the day's **catering**: how many people eat, at what time and at which location, noon and evening, without naming anyone. The whole event can be exported as CSV. Each staff member finds their breaks on their espace, their PDF and their calendar.

### Changes

What moved on the chosen date, seat by seat, compared with the last published plan or the automatic capture taken before the solve.

### Compare two days

Two days side by side, two Saturdays for instance, on the table or the rail. A banner gives for each the seats, filled, empty, breaks without relay, staff and stands, with the difference of one against the other. Below, one row per stand or per person, aligned on both sides, with the differences flagged and filterable. The comparison is read-only and the address keeps it so it can be shared.

## By stand

One row per stand, one column per day: those of the week of the chosen day, or that day alone, or the whole event in colour only. In each cell, the people who hold the seats, or their counters (2/2), or the colour alone. The cell is coloured by its coverage (filled, partial, empty), and a stand closed that day is distinguished from an empty stand.

On the right: the seats to fill, the filled ones, the share of seat-hours held and the hours. At the bottom: the totals of each day. A cell opens the seat panel on that stand that day.

A second rendering shows the **Hours breakdown**: one rectangle per stand, the larger the more seat-hours the stand requires, coloured by the share held (striped below 80%). It can be grouped by location or by game category, and restricted to a week or a day. One click enlarges a group, a second opens the stand's day.

## By person

![The schedule by person](/images/fr/personne.webp "Each staff member's week, hours and evenings included")

One row per staff member, one column per day (the week of the chosen day, that day alone or the whole event in colour only, as by stand). In each cell, each stand held with its hours underneath. The cell is coloured according to whether the person works, rests or was unavailable; an evening and an assignment set on an unavailable day are marked there. A cell opens the seat panel; the name leads to the profile, where a person's radar is read.

A **timeline** draws one bar per person, which fits a month-long event on one screen.

### The Summary view

What each person received over the whole event, each value sortable:

- hours and gap to the median, hours per week;
- evening (from the time set in *Settings*, *Solve* tab, the only evening in the application), weekend, public holidays, Sunday;
- "Night (payroll)" after 22:00;
- maximum opening span of a day (from the first start to the last end, gaps included) and number of days beyond 12 hours, flagged with no rule behind them;
- seats and demanding seats; distinct stands, game categories and locations;
- wishes and ratings satisfied;
- days worked, rest days and longest streak.

Columns where everyone is at zero are hidden until you ask for them.

### Which plan the hours come from

The hours (those of the summary and the payroll export) are read either on the **published plan**, what the staff received, proposed by default as soon as one exists, or on the **saved plan**. The file says which one and from when. On the published plan, the screen reminds you that the day grid and the other counters remain those of the saved plan.

Two CSV exports: the hours for payroll, and fairness.

## By game category

The schedule read by game category, in one table: seats, hours, ceiling, people assigned, skilled, skilled never assigned, assigned without the skill. Each figure leads to the screen where you act: By stand restricted to the game category, the Staff page filtered, the Skills grid on its column.
