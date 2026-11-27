---
twin: regles/calendrier-et-horaires
---
# Build the calendar and the hours

You lay down the timeslots of the edition (the shifts the solve will fill) and set when each stand is open.

The calendar is kept on the *Calendar* page (*Prepare* group), a stand's hours on its fiche. A **shift** is a real slice of work: there is no other kind of timeslot.

## Lay down timeslots: the generator and timeslots by hand

The grid is produced by one **generator**, the *Generate timeslots* menu. It starts from one of three sources:

| Source | What it writes |
| --- | --- |
| *From the templates* | the shifts of each template on the dates assigned to it |
| *From the stands' opening hours* | a cut at every hour a stand opens or closes, a timeslot on every open stretch; added to the grid or in its place |
| *From a recurring pattern* | windows repeated on chosen days, added to the grid |

Whatever the source, the gesture is the same: a figures **preview** that writes nothing and says what the resulting grid would be worth, then the **application**. A **template** (« gabarit ») is what 3.x called a day template: the shifts of a kind of day, laid onto a calendar of dates, which are those of the edition.

1. Open *Calendar* and define your templates ("weekday", "Saturday"...).
2. Lay them onto the edition's dates.
3. Read the figures preview: the template is applied as a difference, destroying nothing.
4. Apply.

Removing a date from a template's calendar does not delete its timeslots: they stay, the date is simply no longer governed. Templates can also be recognised in a grid that is already written (*Generate timeslots* menu). A single timeslot is added or edited by hand, in the list, as before.

The page describes the grid in place and shows its consistency check as a banner, re-read on its own at each visit and after each write. Each timeslot counts the open stands and the seats it carries, with a link to the list of stands. Public holidays are marked on entry, and the check reminds you that a minor does not work on one when the edition has one that day.

## Set the stand opening hours

A stand's hours are set on its fiche (*Stands*, then the stand's name), and nowhere else: there is no stand × timeslot grid any more.

- Its **recurring model**, at the top, says its normal regime ("every Saturday and Sunday from 10:00 to 18:00, headcount 2").
- Its **dated windows**, below, are what the solve reads: one line per opening or closure, marked *model* or *typed*. You add one by hand, and remove a window or a whole day.
- A day with no window, the stand is open all day.
- The fiche says how many open days and seats the stand carries, before any solve. A stand never open or a window outside every timeslot is counted on the home page and in the calendar check.

An organiser's workbook grid is no longer imported as CSV: it is rewritten in the edition's YAML file, see [Moving from 3.x to 4.0](../installer/passer-a-la-4-0.md#les-imports-csv-des-référentiels-et-de-la-grille-des-stands-sont-retirés).

## When a consigne from the authority changes the hours

An arrêté or a heatwave that closes stands is set by a consigne, not by editing the grid: see [Apply a consigne from the authority](appliquer-une-consigne.md).

## Next

Check [how many people you need](dimensionner-l-equipe.md), then [solve the schedule](calculer-planning.md).
