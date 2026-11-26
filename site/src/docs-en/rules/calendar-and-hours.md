---
twin: regles/calendrier-et-horaires
---
# Build the calendar and the hours

You lay down the timeslots of the edition (the shifts the solve will fill) and set when each stand is open.

The calendar and the stand hours are kept on the same page, *Calendar* (*Prepare* group). A **shift** is a real slice of work: there is no other kind of timeslot.

## Lay down timeslots: the generator and timeslots by hand

![The stand hours grid](/images/fr/horaires.webp "The stand hours")

The grid is produced by one **generator**, the *Generate timeslots* menu. It starts from one of three sources:

| Source | What it writes |
| --- | --- |
| *From the templates* | the shifts of each template on the dates assigned to it |
| *From the stands' opening hours* | a cut at every hour a stand opens or closes, a timeslot on every open stretch; added to the grid or in its place |
| *From a recurring pattern* | windows repeated on chosen days, added to the grid |

Whatever the source, the gesture is the same: a figures **preview** that writes nothing and says what the resulting grid would be worth, then the **application**. A **template** (« gabarit ») is what 3.x called a day template: the shifts of a kind of day (an "R" marks a meal relay), laid onto a calendar of dates, which are those of the edition.

1. Open *Calendar* and define your templates ("weekday", "Saturday"...).
2. Lay them onto the edition's dates.
3. Read the figures preview: the template is applied as a difference, destroying nothing.
4. Apply.

Removing a date from a template's calendar does not delete its timeslots: they stay, the date is simply no longer governed. Templates can also be recognised in a grid that is already written (*Generate timeslots* menu). A single timeslot is added or edited by hand, in the list, as before.

The page describes the grid in place and shows its consistency check as a banner, re-read on its own at each visit and after each write. Each timeslot counts the open stands and the seats it carries, with a link to the stand hours that day. Public holidays are marked on entry, and the check reminds you that a minor does not work on one when the edition has one that day.

## Set the stand opening hours

The *Stand hours* tab is a stand × timeslot grid, entered and reviewed in the same place as in a spreadsheet.

- Paste a block, copy a day over, take the row above, apply a cell to its whole column.
- An empty cell is closed.
- Behind each headcount, thin bars show the opening retained, the stand's hours and the band of the day's consigne.
- A click on a cell explains in one sentence where its opening comes from, and leads to the stand's profile, the template or the consigne.
- A public holiday is marked there by its name, without forbidding anything.
- The same grid can be entered by template.

Above the grid, the summary and the three usual hour-entry mistakes are worth checking before starting a solve.

A last view compares two to eight stands with a reference stand, day by day and cell by cell, then opens the copy of the reference's hours to the others.

An organiser's workbook grid can also arrive as is by file: see [Import and export files](importer-exporter.md#importer-la-grille-des-stands).

## When a consigne from the authority changes the hours

An arrêté or a heatwave that closes stands is set by a consigne, not by editing the grid: see [Apply a consigne from the authority](appliquer-une-consigne.md).

## Next

Check [how many people you need](dimensionner-l-equipe.md), then [solve the schedule](calculer-planning.md).
