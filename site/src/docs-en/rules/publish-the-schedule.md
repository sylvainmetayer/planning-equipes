---
twin: regles/publier-planning
---
# Publish the schedule

You send each staff member their schedule, you see who has received and confirmed it, and you remind those who do not answer.

![The Share screen](/images/fr/diffuser.webp "The Share screen: who has received which version")

Prerequisites: the edition must be **the active edition** (see [Chain editions](enchainer-les-editions.md#activer-une-édition)), and the mail server must be configured by the host. Everything happens in *Share* (*Share* group), *Send* and *Documents* tabs.

## Publish and notify

Publication sends their schedule and the link to their espace only to the people whose timetable has changed, telling them what changes. When a shift disappears from the grid after the fact, it says "withdrawn" instead of vanishing silently from their espace.

### Review before sending

Before publishing, you see one line per person to notify: what changes for them, their last confirmation. You can:

- sort by size and collapse the minor changes;
- export the detail as CSV.

Everybody on the list receives their planning: a person can no longer be taken out of a publication. To call somebody first, use "Notify only a few people" below.

The publication panel also says how many unreviewed days would go out.

### Notify only a few people

After an accepted swap or a replacement on the event day, "Notify the N people" publishes for those people only; the others remain to be notified at the next publication.

## Follow who has received which version

On the Share screen, a permanent table covers the whole edition. It shows, for each person:

- the version received;
- the sending state: sent, failure with its cause, no e-mail, deferred;
- the day-before reminder, the reminder and the acknowledgement.

Each row offers "Resend their planning" and "Remind". Filters exist by state and by day ("what changes on 06/09", counted as in the day view). A failed sending is visible and prevents the home screen from saying "up to date". Each printable document says who it is for.

### When a sending fails

An e-mail that the mail server refused is not mistaken for a silence. The acknowledgement column says "failed to send", with the date and the reason: server unreachable, credentials refused, address refused, temporary refusal. The summary counts it separately, a filter of the Staff page gathers them and the staff member's fiche flags it until you edit it.

- A refused address is no longer reminded, neither at night nor by hand, until the fiche has been corrected.
- A night reminder that fails leaves an alert on the home screen and does not count as a reminder.
- It is a sending state, not read tracking. A server that really sends nothing says "simulated".

## The acknowledgements

On the published schedule, the staff member has an "I have read it and I will be there" button. On the organisation side, a column shows confirmed, reminded or silent, with the summary at the top of the page and the date of the last publication. Republishing only asks for a confirmation again from the people whose timetable really changed.

### Remind those who have not confirmed

1. Open *Staff* and apply one of the two filters (carried by the page address): "Never confirmed" or "Silent for N days". Each can be restricted to those never reminded.
2. Start the bulk action "Remind now": it sends the same reminder as the night one, without waiting for it.
3. Read the report: it names those who were not written to and why.

There is only one reminder per person and per publication, whether the night or you sent it.

### The automatic night reminders

- The evening before, everyone receives the list of their seats for the next day.
- The silent ones are reminded once, unless you already did it by hand.
- Swap requests that lie dormant are escalated to the organisation.

Nothing leaves an edition that has not explicitly asked for it, and nobody receives the same message twice.

## Send the documents

The *Documents* tab groups what is printed or downloaded. Each document says who it is for.

| Document | Content |
| --- | --- |
| Overall PDF | All the assignments in a single document: a clickable table of contents, the "who runs which stand each day" grid, then the same assignments day by day, stand by stand and staff member by staff member, empty seats flagged |
| Individual PDF | A staff member's schedule, or everyone's in one archive: an overview of their days on a timeline, the detail of each day, then their teammates and their locations. Days without assignment appear explicitly as rest days |
| Double-sided sheet | The same individual schedule folded onto a single landscape A4 sheet: calendar on the front, teammates and locations on the back. One print per person instead of five. The staff member chooses their format from their espace, you from the sharing screen |
| QR code | The two individual PDFs carry a QR code to the personal espace: you scan the paper instead of copying out an address |
| ICS file | The individual schedule, importable into Google Calendar, Apple Calendar or Outlook |
| Calendar subscription | A permanent subscription address, given once to one's calendar: it updates by itself on every republication. It is personal, and the staff member replaces it themselves in one click if it has leaked |

## Show the day's schedule on a television

The wall display is created and revoked in Settings: see [Follow the event day](suivre-le-jour-j.md#afficher-la-journée-sur-un-téléviseur).

## What others read of this schedule

- Staff read their schedule in their espace: [The staff espace](espace-animateur.md).
- A stand manager or a human resources person reads the published plan from a limited account: [Manage accounts and rights](comptes-et-droits.md).
