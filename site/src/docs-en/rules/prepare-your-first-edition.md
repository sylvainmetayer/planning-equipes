---
twin: regles/preparer-edition
---
# Prepare your first edition

You start from an empty edition and describe it: its stands, their game categories, their locations. The staff and the calendar each have their own guide.

An edition is prepared in the order it is built: the stands and what they offer, the calendar they open on, the people, then the files that fill everything in. The *Prepare* group of the menu follows this order (Stands, Calendar, Staff, Files).

The type of event changes the priorities: see [a festival schedule](/planning-festival/), [a trade show or fair schedule](/planning-salon-foire/) or [a sports event schedule](/planning-evenement-sportif/).

## Before you start

- Choose the working edition in the edition bar, above every page. Two browser tabs can work on two editions at once; they never mix. To create one, see [Chain editions](enchainer-les-editions.md).
- On an empty edition, the home screen offers a "Get started" block: import a file, start from a previous edition or load an example. Each empty reference data list says what it will contain, leads to the previous step when it is missing, and also offers an example.
- If you already have a spreadsheet, [Import and export files](importer-exporter.md) saves you typing by hand.

## Create your game categories

A **game category** is a kind of game or activity. It is the common vocabulary between the staff's skills and what a stand offers. They are managed in the *Categories* tab of the Stands page.

1. Open *Stands*, *Categories* tab.
2. Create each game category. You can set there the ceiling of timeslots a staff member may hold in it, and tick the "all-rounder" category, the one for all-rounders, directly in the table.
3. Read the figures on each row: how many people master it, how many wished for it and how many stands offer it. Each figure leads to the list it counts.

A badge flags a game category as **orphan** (offered by a stand, mastered by nobody outside the all-rounders), **fragile** (a single person) or **unused** (no stand). The edition status counts the orphans before any solve. Once a schedule is solved, three columns say what it made of it: the seats, the hours, and the people assigned without the skill.

## Place your stands on the map (the locations)

**Locations** are the geolocated places attached to stands. They serve to avoid long trips from one timeslot to the next.

1. Open *Stands*, *Locations* tab. A map shows all the locations with their stands.
2. Move a marker with the mouse: the new position is saved.
3. The table says which stands are attached to each location. A location can also be created from a stand's form.

## Add a stand

1. Open *Stands* and click "Add". The wizard guides the creation: identity, game categories, location, then the edition's timeslots, which you untick as needed, with the headcount of each. No rule to write.
2. Fill in at least one game category (always), the minimum and maximum headcounts, and whether the stand is reserved for adults. The *premium* and *effort* flags are also set in the fiche.
3. Set the hours: recurring rules completed by dated exceptions. Each opening window can name its own headcount, for a stand that does not have the same need in the morning, the afternoon and at night. The detail is in [Build the calendar and the hours](calendrier-et-horaires.md).

The stands table sorts on every column and says, for each stand, its open days, its seats and, after a solve, its coverage.

### A stand's fiche

Each stand has its fiche, which gathers:

- its day × timeslot grid, editable in place as in the Stand hours grid;
- its opening anomalies, including a window declared at a time that no shift covers;
- its seats after a solve;
- the comparison with another stand;
- the history of its changes;
- its rules in condensed form.

A stand's hours can be copied onto another from its fiche, or onto a whole selection from the bulk edit. The rules, dated exceptions and window headcounts are copied with a signal when a window exceeds the target stand's maximum headcount.

## Working in tables

Each reference data list works like a spreadsheet.

- Every column can be sorted, identifiers in their natural order (T2 before T10).
- A row's name opens it; a menu edits, duplicates or deletes it.
- "Export this list" downloads as CSV what the table shows.
- A block copied from a spreadsheet can be pasted onto the rows, after a preview of the modified cells.
- A save warning stays marked on its row.

With the keyboard: "/" enters the page filter, then Down arrow goes into the table; the up and down arrows move the current row, Enter opens it and Space ticks it for a bulk action. A click on a row focuses it the same way, and Tab enters the table and leaves it without getting stuck in it.

## What the application flags as you enter data

On creation as on modification, the entry is saved, then a message flags what deserves a second look:

- an unavailability outside the event dates, or set on a day with no timeslot;
- a birth date that makes the staff member a minor during the event (the message says from when they become an adult);
- a timeslot that no open stand covers.

A modification only flags what it changes: data you have not touched triggers nothing.

## When two people edit the same fiche

If two tabs or two people open the same fiche, the second to save is warned that the fiche has changed since they opened it. They choose to reload or to overwrite knowingly: nothing is overwritten silently.

## Freeze the reference data when preparation is finished

An edition moves through phases: Preparation, Solving, Published, Finished. While it is in Preparation, everything can be edited. As soon as it moves to Solving, the reference data is frozen: stands, timeslots, game categories and locations, staff skills. No path (form, bulk edit, import, wizard) then modifies these fiches, and the frozen fields are displayed read-only with a padlock.

To move to Solving, freeze a family in Settings, *Edition* tab: the four freeze together. Lift one to go back to Preparation, and the four reopen.

The first publication moves the edition to Published. The structure (stands, timeslots, game categories and locations) stays frozen there, but the staff fiches can be edited again. For a last-minute change, such as adding a stand the day before, lift the stand freeze: the structure is unblocked without leaving the Published phase, and the next publication freezes it again by itself.

In every phase, availability, adjustments, locks and consignes (official orders relayed to the whole event) remain free. When the freeze is lifted, the application recalls whether the schedule is already published.

## What next

Describe [the staff and their skills](animateurs-et-competences.md), then [the calendar and the opening hours](calendrier-et-horaires.md). When everything is in place, [solve the schedule](calculer-planning.md).
