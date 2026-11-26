---
twin: installer/rgpd-limites-a-consigner
---

# Record in the register what the instance keeps

Your register must describe the measures as they are, without claiming they are complete. This page goes through each processing in turn: what the application keeps, what bounds each retention and what does not. Carry each point over to your register in that form. The known limits are recorded first, then the broader processings (remote solve, compliance report, emergency contacts).

## No automatic purge

There is no automatic purge: the announced retention period is enforced by hand,
on a fixed date (see the purge procedure in [`exploitation.md`](exploitation.md)). This is the most exposed gap, because
it concerns a public commitment. The choice is deliberate and its deadline
is known (see
[decision 0016](repo:docs/decisions/0016-purge-manuelle-avant-automatisation.md)): the
scheduled task, its log and the cascade test come when several
instances run in parallel. What goes in the register is therefore not
"automatic purge: no", but "manual purge, on a fixed date, recorded in the
[purge log](rgpd.md#tenir-le-journal-des-purges-et-des-restaurations)".

## Access tokens in the proxy logs

Access tokens travel in the URL path: they land
as they are in the reverse proxy's access logs, which must therefore be
purged or written without those paths (see [what remains the reverse proxy's responsibility](securite.md#ce-qui-reste-à-la-charge-du-reverse-proxy)).

## The calendar subscription

The calendar subscription exposes a named schedule durably and
without a second factor, and it is the most exposed point of this document
after the manual purge. It goes in the register as it is, with what
bounds it and what does not.

What it is: a permanent address that, on its own, serves the published schedule
of a named person (their shifts, their stands, their teammates)
to whoever holds it. **It is knowingly handed to a third party**: the
provider of the staff member's calendar application (Google, Apple,
Microsoft, a CalDAV server...), which will fetch it several times a day and
keep its content under its own rules. That recipient is
**not** a processor of the host: it is chosen by the person
concerned, for their own purposes. This is why
the subscription must remain an explicit act on their part, never an
automatic send.

What bounds it: the token is distinct from the espace's token and opens
**one document, read-only** (no swaps, no availability, no
staff directory, no writing); it has 122 bits of randomness; it is **revocable
by the person themselves**, from their espace, without going through
the organisation; and it disappears with their record or their edition. The content
is only ever the **published** plan: it announces nothing the person has not
already received.

What does not bound it: nothing expires, nothing counts accesses, nothing
tells the owner from a third party who got hold of the address. A subscription
address that has leaked stays usable **until
someone replaces it**, and the only person in a position to notice
is the one it belongs to. The replace button,
in the espace, is therefore a security measure: it is mentioned
in the register as such, and inviting staff members to use it is part of what you
explain to them.

## The wall display

The wall display shows names to anyone walking past the screen: who
runs which stand, now and next, for the current day. That is its
purpose, and it is recorded as such.

What bounds it: by default, the **first name and the initial of the surname** (the
full name is an option the organisation chooses link by link); no
phone number, address, age or reason for absence; only the day
under way, and only the link's locations if it is restricted to some. The token
opens only that read, it is **hashed** in the database, **revocable** from
Settings, attached to an edition and purged with it; it travels neither in
the SQL export nor in the end-of-event archive, and no more through
observability tools: audience measurement is not loaded on the screen,
and error tracking receives it masked, like the espace token.

What does not bound it: the screen itself. An address photographed on the TV
or picked up from an access log shows the same thing elsewhere, until it is
revoked; the date of last access, in the list of links, is what
lets you notice. If the screen is visible to the public, the instance's
privacy policy must mention it.

## The phone number

The phone number is organisation data, not a channel:
optional, entered on the record or imported from a column of the staff
file, it serves one purpose: calling a replacement on the event day. It is
shown on the record and on the Today screen (admin session),
and **nowhere else**: not on the wall display, not in a PDF schedule,
an ICS feed or a CSV export of reference data, not through MCP: the MCP
confidentiality test refuses any view that would carry it. It does travel,
however, with the record wherever the record travels whole: the SQL export, the backups
and the edition's YAML scenario, which are already handled with the care due to a dump.
The application sends no SMS.

## Backups

Backups are a storage location in their own right: since they
became automatic, a full dump, minors and tokens included, exists
permanently on the volume. The application's SQL export is narrower (it
leaves out espace access and sessions, the log and the clock)
but it now carries the declarations, the confirmations and the publication
recipients, address included; it is handled with the same care. The register must say so, and the entry is
honest only if the encrypted off-site copy announced in the baseline really
exists.

## The end-of-event archive

The end-of-event archive takes a whole edition out in one gesture:
from the Files screen (Archive tab), a single ZIP can gather the global schedule, the
Equity and Hours CSVs, the six sets of reference data (staff included, with their dates
of birth and addresses), the edition's YAML scenario, the publication
review and the individual schedules, minors included. Once
downloaded, it **escapes all retention by the application**: neither
`JOURNAL_RETENTION` nor the [annual purge](rgpd.md#tenir-le-journal-des-purges-et-des-restaurations) reaches it, and keeping or
deleting it falls to whoever holds it. Three bounds are recorded
as they are: it covers **only the edition** of the tab (never the SQL dump,
which carries all editions and stays on the Settings screen); it
contains **no token**: neither the espace token, which the archive's individual
schedules do not print (no link, no QR code), nor the calendar
subscription token, which a test verifies; and its manifest
`LISEZMOI.txt` recalls what it contains, the data controller and the
retention period declared (`responsable-traitement` and
`conservation` keys of `LEGAL_MENTIONS`). It is the natural step before the annual purge of a finished
edition; the register mentions it as a **data output**,
logged like the others.

## Carpool requests

A carpool request lives in `declaration_coequipier`: the ids of the
teammates named and the status, pending or validated. When the organisation
rejects it, or cancels the validated group arrival, it **deletes** it: what
is left is the action log line, which keeps only the request id. The table
has a foreign key on the requester: deleting their record deletes their
requests.

**The 3.x archive.** In 3.x, a rejected or cancelled request stayed in the
database, with a **free-text reason written by the administrator for the
staff member**. The 4.0 migration (`V142`) does not erase it: it moves those
requests, reason included, into `declaration_coequipier_avant_4_0`. It is
text about a person, typed by another, that the application no longer reads,
shows or exports (neither the SQL export nor the MCP tools); it stays in the
nightly dump, like the whole database. **Retention: until 5.0, which drops
the table.** Until then, the archive goes with the edition (annual purge) or
with the requester's record, through the same foreign keys as the original
table. You can drop it earlier, once 4.0 is in place and going back to 3.x is
ruled out ([purge and keep data](conservation-des-donnees.md#larchive-du-covoiturage-3x)).
Record it in the register with that retention.

## Availability declarations

A processed availability declaration survives its decision: applied
or refused, it stays in the database so the organisation can say *why*
someone's record states what it states, and so the staff member
can read again in their espace what they were answered. It has **no
purge of its own**: it disappears with its edition, in cascade on `edition_id`,
that is, at the [annual purge](rgpd.md#tenir-le-journal-des-purges-et-des-restaurations). The proposed retention period is
therefore **that of the edition, no longer**, and it is recorded as such
in the register. Two things make it less exposed than the rest: the table
carries **no new data** (dates, game category ids and
a free word, the same categories as the staff record), and only one
pending proposal exists per person. The free word remains the sensitive point:
it is the only field where someone can write a health or family reason
nobody asked them for, and it goes as is into the nightly dump
**and, since the application's SQL export carries
`declaration_disponibilite`, into the file an administrator downloads
from the Settings screen**. It is the same data, for the same purpose,
but by a second route and in a form that leaves the machine by hand: the
register records it as such, and the rule in the backup section of [`exploitation.md`](exploitation.md) on how to
handle a dump applies word for word to this one. The choice was made to keep it rather than delete it or
bound its content: a staff member who explains their unavailability helps
the organisation decide, and a constrained field would push them to write the same
thing elsewhere. It is therefore recorded in the register **as free-text data**,
and handled as such: nothing in it is
requested, required or used beyond the decision it informs.

## Automatic night sends

The automatic night sends write without a human rereading: the
day-before reminder, the confirmation reminder and the reminder for the availability
collection leave from a scheduled task, to staff members'
addresses, minors included. Three bounds are
set and recorded as they are: nothing leaves except from the **active
edition**, which an administrator designates explicitly (every edition is born
inactive, and the espace, the ICS feed and the wall display of an inactive edition
are closed); the
reminder says only what has **already been published**, so it never announces anything
new; and the `notification_planifiee` log stores **neither name nor
address**, only a staff member id; the identity is joined at read time
from the reference data, so a deleted record leaves an
alert that names nobody. This log has no purge of its own: it
disappears with its edition, in cascade on `edition_id`, so at the
annual purge.

## Reported absences

An absence reported from the espace (`signalement_absence`) says that
a person will not be there on a given day, or on a seat. It carries
**no free text**: the reason, optional, is a closed list (personal
reason, transport, other), chosen precisely so that no health
or family reason is ever written in it. The e-mail that warns
the organisation names the person, the day, the seat and that reason, like the
notification of an availability declaration. The e-mail that answers the
staff member, when the organisation decides, carries only the day and the
seat (not the reason) and goes to their own address. The row follows the record,
the timeslot and the stand it names (deletion in cascade) and disappears with
the edition.

## The status of each schedule send

The status of each schedule send is kept (`envoi_planning`):
for each publication and each resend, one row per person saying whether
the e-mail left, failed, could not leave for lack of an address or was
deferred. **Dates and statuses only**: neither the content of the message
(which already lives in `publication_destinataire`), nor the address, nor the
mail server's error message (it readily quotes the refused address),
replaced by a short cause (address refused, mailbox full, server
unreachable, other). Like the publication trace, the row survives the
record it concerns, because it is proof of sending, and cascades with its snapshot
and its edition, so it disappears at the latest at the annual purge.

## The result of each e-mail

The result of each e-mail sent to a staff member is kept
(`envoi_mail`), not only the schedule: access code, invitation to
declare, reminders, day-before reminder, swaps, carpooling, absences.
This is what tells, on the Staff page, a send failure from
silence, and what stops reminders from writing again to an address that the
relay has refused. The row carries **a staff member id, the name of the
e-mail template, "sent" or "failed", a short failure category and
a date**, with no address, no content and no answer from the mail server. It is
**not read tracking**: no pixel, no tracked link, "sent"
means handed to the relay; confirmation remains the staff member's own act. What
lifts the ban on reminding a refused address is an edit
of the record, any edit: no fingerprint of the address is stored
to know whether it is the one that changed. Minors gain no more data
there than an adult. The row has **the purge of the action history**
(`JOURNAL_RETENTION`, applied every night) and cascades with its edition;
it survives the record until then, like `notification_planifiee`, and then
names nobody.

## The stand manager

The stand manager (the `RESPONSABLE_STAND` right) is one more category of
recipients of the published schedule, and it is its **minimisation** that
makes it tenable. By default they read **the hours and the seats to fill**
of their stands, **with no name at all**. An administrator can add the
**first and last name** of the staff members scheduled (a default setting of the edition,
overridable stand by stand in both directions, each with an optional
opening date before which only the hours are shown),
once the schedule is stable and the manager needs to know whom
to welcome. **Never** an address, phone number, date of birth, age or
minor status, whatever the setting. The scope is bounded three times:
the stands of their right, the **active edition** only (no read of
a past or future edition), the **published** plan. The printable sheet follows
exactly the same setting; once printed it leaves the application's scope,
like any paper. The settings (`reglage_noms_responsable`,
`reglage_noms_stand`) carry no personal data; they return
to the safe default (hours only) when the edition is wiped and in a
duplicated edition. The scope of a right (`habilitation_stand`) follows `habilitation`,
outside the edition, outside the SQL export; wiping or deleting the edition
removes the rights of its managers, and a deleted stand leaves only a
right that opens nothing. The plan read is the **whole publication** in force: a
deferred (or partial) publication is not split by stand, so the manager sees
what the team received, not an intermediate state of the working plan. A name
is never returned to a manager for a stand outside their right, including that of a person who also works there
elsewhere. To mention in the register: one more category of recipients,
internal to the organisation, designated by the organiser.

## Organisers' accounts

The organisers' accounts (`compte`, `habilitation`) keep an
**e-mail address**, an optional name, the creation, last login and deactivation dates,
the rights granted (role, edition,
expiry) and, for each deactivation or removal, the **id of the
account** that did it. A **local** account also keeps a **bcrypt hash**
of its password (never the password) and the date it was chosen; invitation
or reset links (`compte_jeton`, 72 h or 1 h, only one per
account, deleted on use) and login challenges (`compte_defi`, ten
minutes, with the hash of the code received by e-mail) are stored only hashed,
and expired rows are removed at the next write; none of these tables
is in the SQL export. The e-mail with the link and the one with the code go through the instance's
SMTP relay (a processor already declared). This is the data of the
**people who administer the instance**, not that of staff members. The application deletes nothing: an
account is deactivated, a right is withdrawn, so that the log keeps its meaning.
**Erasure**: at a person's request, or when the period below expires,
the operator **pseudonymises** the row rather than deleting
it: the address becomes an opaque identifier (`efface-<id>@invalide`),
the name is emptied, `mot_de_passe_hash` and `mot_de_passe_le` are set to `NULL` and
the account's `compte_jeton` and `compte_defi` rows are deleted. The row, its dated rights and the log
references stay, designating nobody; it is an SQL operation, never a
`DELETE`. **Retention**: an active account is kept as long as it is used; a
deactivated account is kept **two years** after its deactivation (long enough to
answer "who had access?" for a season), then pseudonymised. These are instance
tables, outside the edition, **absent from the SQL export** (a restore
does not carry accounts: restoring elsewhere would open the instance to
strangers, and would not give back the receiving instance's rights either).
The action history designates them by **account id**, never
by address or name.

## The admin login log

The admin login log (`journal_connexion`) keeps,
for each attempt on the login form (successful, failed, or
the lockout it triggers), **the timestamp and the IP address of the
client**, and nothing else: neither the password nor the identifier entered. It
serves the security of access to staff data ("did someone
brute-force the password?"). It is an instance table, outside the edition; it has **the
purge of the action history** (`JOURNAL_RETENTION`) and does not travel
in the SQL export.

## The action history

The action history (`journal_action`) records who did what, and
it is a processing to record as such. It follows the same rule as the log
above, and two more. It stores **no name, address or
date of birth**: a staff member id at most, the identity being
joined at read time. It stores **no field value**: an
edit leaves in it the *names* of the fields that moved ("nom, email"),
never what they became, which is enough to retrace an action without
copying the data. And it has **a purge of its own**:
`JOURNAL_RETENTION`, ninety days by default, applied every night,
because a log nobody rereads would otherwise become one more storage, kept without limit and included in every backup. It also cascades
with its edition, so it disappears at the latest at the annual purge.

It also records **data outputs**, and that is what makes it possible to answer,
throughout the retention period, "who took out the list of staff members, minors
included, and when?": every admin export (PDF, archives,
named CSVs, reference data, YAML scenario, database dump) and every
download of their schedule (PDF or ICS) by a staff member from their
espace leaves a row (refusals included on the admin side), under the same rule and **the same
retention** `JOURNAL_RETENTION` for the live table; the rows
also travel in the nightly backups (`BACKUP_RETENTION`) and in
any exported dump, which keep them at their own pace. Nothing more goes in:
the action, the date, the author, the HTTP code and, for an espace
download, the staff member id; for the reference data archive,
the names of the sets of reference data taken; for the end-of-event archive, the
names of the parts it carried; never the content of the file. An espace
download **refused before any proof of identity** (unknown link,
or e-mailed code never entered) writes **nothing**: holding the
link does not prove you are the staff member, and recording it under their name, often
a minor's, would blame them for someone else's attempt, and a read
anyone can repeat must not make it possible to fill the table.
More broadly, a staff member is the **author** of a row only if the call proved
it (session opened by the e-mailed code): a code request, a wrong code
or a write refused for lack of a session are recorded under
`ANONYME`, the staff member remaining named as the **target**. The author on the admin side
stays "Administration": the application has only one account, so it cannot
say which of the people sharing it downloaded. Only two things escape
the trace, with their reason in `CatalogueActions`: the sample files
(which carry no one) and **the calendar subscription**: the calendar
rereads it on its own every few hours, a row per read would be
noise, not a trace, and nothing counts those reads, as the section on the calendar above says.

## The CSV import of staff

The CSV import of staff brings personal data in through a
file the operator keeps themselves, and it is an input to record:
this spreadsheet carries names, dates of birth (so the information that
designates **minors**) and often e-mail addresses. What the application
guarantees on its side: the file is received in the request body, read in
memory and **never written to disk** (`service/backup/` remains the only
place where the application writes), nor kept between the two calls:
the preview and the write each receive it, and the memory is released
at the end of the request. What it cannot guarantee: the **source
file**, which stays on the organiser's computer, in their mailbox and
in their backups. It is that copy that goes in the register, with
the rule to delete it after import.

## The browser log

The organiser's browser keeps a log of what it was
shown, and it is one more storage location, outside the database and outside the
backups. Every info bubble is copied into a log of 200
entries kept in the profile's `localStorage`, partitioned by edition but
**not cleared at logout** and readable from the *Recent messages* of the home page
by anyone who reopens that profile, typically a shared control-room computer. Two
bounds are set and recorded as they are: input warning messages
**name a staff member by id only**,
never by first and last name or date of birth; and the sentence
saying a person is a **minor** (the only one that, by naming the day they turn
18, would let someone recompute their date of birth) is **displayed without being
logged**. What does not bound it: the log does not expire on its own,
it is emptied with the button from the *Recent messages* of the home page, and nothing stops
another message of the application from writing a name in it. The operating rule
that goes with it is that of a shared computer: empty the log, or close the
browser session, when leaving the computer.

## Form drafts

Long forms keep a draft of the entry under way in the
browser, and it is another storage location outside the database: the staff
record, the stand record and the consigne save, one second after the
last keystroke, what has not yet been saved, to offer it on
reopening after a reload, an expired session or a closed tab.
The bounds set are recorded as they are: the draft of a **staff
record** (identity, date of birth, hence minor status, e-mail
address) is written only to **`sessionStorage`**, which dies with
the tab, never to `localStorage`; those of the stand and the consigne,
which carry no personal data, go to `localStorage`. Each
draft is partitioned by edition and by record (one at most), **erased** on
save, on "Ignore", on confirmed abandonment of the form and on the
*Log out* button (all drafts, all editions), and purged
**after 24 h** when the interface opens; that of a record
deleted in the meantime is erased without being offered again, with a message that
designates it by its id. Since a tab's `sessionStorage` is
readable only by that tab, the logout is **announced to the other tabs** of the
browser (broadcast channel, or failing that a storage event on a
key that carries no data): each then erases its staff-record drafts.
The purge takes precedence: a form opened before it
writes no more drafts, not even the pending save that
navigating to the login page would trigger by closing it. An **expired**
session does not erase them, and that is intended: resuming an entry after
expiry is the very purpose of the draft, and that of a staff
record stays in the tab's `sessionStorage`, which no other tab reads.
No draft is sent to the server, logged, exported or exposed to
MCP. What does not bound it: a tab closed without logging out takes its
`sessionStorage` with it, but a tab left open, expired session included,
keeps the record in progress until the next logout or the 24 h purge; and a
browser that refuses both broadcast mechanisms purges only the tab
that logs out.

## Copies before a wipe

The edition is copied before each wipe, and these copies are not
purged (`sauvegarde_avant_vidage`,
[decision 0084](repo:docs/decisions/0084-sauvegarder-l-edition-avant-de-la-vider.md)):
a reset, a scenario import or the restore of a backup first stores
**one YAML export** of the edition (first name, last name,
date of birth, address and phone of each staff member included), its
schedule (seats and locks, by ids only) as the last section
([decision 0112](repo:docs/decisions/0112-la-copie-avant-vidage-est-un-export-yaml-avec-son-plan.md)).
This file is personal data under the GDPR, like the exported scenario, and is
restored through the normal import. **One copy per
wipe (except when the state is exactly that of the previous copy),
kept as long as the edition**: the only purge is the
deletion of the edition, in cascade on `edition_id`, so the annual purge
in the [purge log](rgpd.md#tenir-le-journal-des-purges-et-des-restaurations); the retention period of these copies is therefore **that of
the edition**, and it is recorded as such in the register. An administrator
can delete one (`DELETE /api/planning/sauvegardes-avant-vidage/{id}`,
logged). They go neither into the SQL export nor through MCP; only an
administrator lists, downloads, restores or deletes one, through
the API; the downloaded file is a full export and is kept like the
database; downloading, restoring and deleting are
logged, the plain list (dates and origins, no personal data)
is not. What does not bound them: a deleted
record stays in the backups taken before its deletion, and
restoring one brings it back, hence the next point.

## Erasure requested by a staff member

Erasure requested by a staff member on an edition that is still active has
no tooled procedure: it is a manual deletion of their record, **then
of each copy before a wipe of their edition that carries it**, by
`DELETE /api/planning/sauvegardes-avant-vidage/{id}` (the simplest: all
those taken before the record was deleted). An
erasure request is not refused on the grounds that the event is not
over, and automating the purge by expired edition will not settle it: that is
a separate operation.

## Duplicating an edition

Duplicating an edition copies its people, and that is intended for the use
that gave rise to duplication: the "heatwave plan" edition created mid-festival
must keep its team and its "Send to all".
The use as a *template from one year to the next* is the opposite: preparing 2027 from
2026 would copy names, dates of birth and e-mails of people who have not
registered again, that is, would start a new retention
period on data that nothing justifies holding any more. Duplication
therefore accepts `avecAnimateurs=false`: the structure
goes through, the people stay behind, and with them skills, unavailability,
wishes and manual adjustments. What does not bound it: nothing forces
choosing this mode, and a duplication done with the people out of habit produces
exactly the copy one wanted to avoid: it is a checkbox, not a
safeguard.

## A remote solve sends a pseudonymised problem

When the instance solves on a rented machine
([`exploitation.md`](exploitation.md), section "Solve on another machine"), the
problem leaves the application for the duration of the solve. **Never sent**: last
name, first name, e-mail address, phone, tokens, free texts. **Sent**:
each staff member's skills, wishes and unavailability days, under an
**identifier drawn at random for each solve**, and a **substitute** date of
birth in the same age band (under 16, 16-17,
adult), and the real date only for someone who turns 16 or 18 during
the event, because the rule then changes its answer midway.

It remains personal data: availability tied to an
age band, which the correspondence table (kept in the application's
database, for the duration of the solve) lets you tie to a person. To
record: the host of the solving machine as a **sub-processor**,
the region chosen, and the fact that the machine keeps nothing
on disk and is destroyed after use. A worker installed on the site itself
changes nothing in the register.

## The compliance report names the people scheduled

The labour inspectorate compliance report (*Compliance* tab of
Diagnostic) lists, by name, the hours, the applicable caps, the breaches from the
legal rules and the minor status of each person scheduled. It carries
no date of birth, address or contact details. It is readable only by
administrators and by accounts holding an `RH` right on the edition;
**every consultation and every export is written to the action log**
(`CONFORMITE_CONSULTEE`, `EXPORT_CONFORMITE_PDF`, `EXPORT_CONFORMITE_CSV`), which
follows the log's retention purge. The PDF and CSV downloaded leave the
application: their retention is that of the organisation that
produces them, and they are never exposed through MCP.

## The emergency contact and the legal representative: a third party, a separate table

A staff member (or their legal representative) can enter the name and phone number of the
person to notify in case of illness and, for a minor, of their legal
representative. These are **data of a third party who asked for nothing**: the
privacy policy announces it, with its purpose (people's safety during the
event).

| Question | Answer |
| --- | --- |
| Where it lives | `animateur_contact` table, separate from the record: the staff member is read by the scenario, the exports, MCP and the colleagues' espace, and none of that must carry a row of it |
| Who reads | Administrators and the edition's `RH` right; the staff member reads and edits their own in their espace (*My contacts*) |
| Never in | YAML scenario, export CSV and PDF, ICS, archive, e-mails, MCP, a colleague's espace, stand manager, list of records |
| Log | Every read and every edit by the organisation (`CONTACTS_CONSULTES`, `CONTACTS_MODIFIES`) and every edit by the staff member (`CONTACTS_MODIFIES_PAR_ANIMATEUR`): ids, never a value |
| Minor without a representative | Optional: a warning on entry (by id, never the identity; it does not go into the browser's notification log) and a line in the compliance report |
| Retention | A deleted record takes its contacts with it (foreign key in cascade); the edition's retention purge does the same. An **edition duplication does not copy them**: a new year asks again, without carrying a third party's number from one edition to the next. A scenario import keeps the contacts of the records it retains and removes those of the records it drops; a reset of the edition erases them all, and its restore does not bring them back (they are not in the saved scenario) |
| Backup | The nightly `pg_dump` backup contains them, like the rest of the database; it is never downloadable. The test tooling's SQL export does not carry them (`DELIBERATELY_NOT_DUMPED`) |
