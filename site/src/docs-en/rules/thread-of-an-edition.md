---
twin: regles/fil-d-une-edition
---
# The thread of an edition

An edition goes through six moments, from preparation to the event day. The application shows them in that order, so you always know where to start and what comes next.

Planning Équipes organises the work of a team of volunteers or employees during an event: who runs which stand, which day, at what time, while respecting the legal rules (those for minors in particular), skills, availability and a fair workload across people. An **edition** is one occurrence of the event: a year, a festival, a trade show.

If you are new to the tool, [can you trust it with a team of volunteers?](/organiser-son-equipe/) answers without jargon, and [the frequently asked questions](/questions/) cover the rest.

## The six moments

The application opens on the **edition status**, which tells you where you stand. The menu follows the same thread in nine entries: *Home*, *Prepare* (stands, calendar, staff), *Files*, *Planning* (with its *Calculate* tab), *Diagnostic*, *Rules*, *Share* (with *Swaps*), *Today* and *Administer* (with *Editions*). The entries gathering several pages fold; the Ctrl+K palette finds every screen and every tab.

| Moment | What you do | Guides |
| --- | --- | --- |
| Prepare | Describe the event: the stands and their opening hours, the calendar of timeslots, the staff with their skills and availability. You can enter everything by hand, by file, or start from a previous edition | [Prepare your first edition](preparer-edition.md), [Enter staff and their skills](animateurs-et-competences.md), [Build the calendar and the hours](calendrier-et-horaires.md), [Import and export files](importer-exporter.md) |
| Plan | Start the solve: the application proposes a schedule that respects the rules, and you follow the progress live. You review it, adjust it by hand and lock what must no longer move | [Solve the schedule](calculer-planning.md), [Review and adjust the schedule](relire-et-retoucher.md) |
| Understand | When a schedule is not feasible or leaves seats empty, the application says why and what is best to fix first | [Understand a stuck schedule](comprendre-un-planning.md), [View the schedule](consulter-planning.md) |
| Share | Publish the schedule, send each staff member their own (PDF, calendar), then follow who has received and confirmed it | [Publish the schedule](publier-planning.md) |
| Event day | The *Today* screen shows the day under way, the gaps to fill and the unforeseen; the wall display shows the day on a television in the control room | [Follow the event day](suivre-le-jour-j.md) |
| After | Compare planned and realised (the *Realised vs planned* view of the Edition measures screen), archive the event, start from this edition for the next one | [Chain editions](enchainer-les-editions.md), [Import and export files](importer-exporter.md) |

## Reading the edition status

![The edition status: the list of steps with their state](/images/fr/accueil.webp "The edition status")

The home screen takes the shape of the moment.

**During preparation**, it is a list of steps: reference data, phase of the edition, referential coherence, availability collection, openings, staffing need, solve, problems, review, publication, acknowledgements, swap fair. Each line says whether the step is done, to check, for information or to do, with the figure that matters and the link to the screen concerned. A few lines in detail:

- the last solve is read as sentences, never as a raw score;
- the phase of the edition (Preparation, Solving, Published, Finished), which decides what the reference data lets you edit, is read and changed there, with its "Unblock the structure" and "Freeze again" buttons in Published;
- the acknowledgements say whether the automatic reminders are active and only alert beyond the reminder delay;
- the "Referential coherence" line gathers all the anomalies detected elsewhere. Identical anomalies are merged into one line with their count ("16 days: nothing between 12:00 and 13:00") and each line leads to the fiche that fixes it. The detail of these anomalies is read in the feed of alerts and messages, further down: "See in the feed" opens it on that type;
- two lines count without listing: the fiches with no phone number (including those assigned on an upcoming date) and, on an edition that enters locations, the stands with no location. For the latter, the application shows those for which neighbouring stands suggest a location, with its reason. Each line opens the filtered list; the suggestion also appears in the Location column of the stands and when a stand left without a location is saved.

**During the event**, the first line is the day's: its number (D5 for the fifth day), the open stands, the empty seats, the absentees, the swaps to rule on. It opens the Today screen, next to the link to the wall display, and the list of steps unfolds on demand.

**After the event**, the archive comes first.

**On an empty edition**, a "Get started" block offers to import a file, start from an edition or load an example.

### To handle today

At the top of the home screen, "To handle today" gathers what awaits a decision that day:

- availability declarations;
- swaps to rule on (flagged once past the configured age);
- unreviewed days of the coming week;
- the silent ones to remind;
- data modified since the solve;
- the people to notify;
- what could not go out, as long as the cause remains: a day-before reminder to a fiche still without an address, a reminder (from the night or by hand) to someone still silent, a failed night backup, a published or resent schedule whose sending failed.

Each line opens the screen with its filter already set. The bell in the toolbar opens this area, which ends with a single feed of alerts and messages. It gathers the plan's alerts (the night sends', by name, the weather alert, the people to notify after a removed exclusion), the anomalies of the referential coherence and the history of the application's messages (solves finished, rules in default, input errors). It is sorted most serious first, then newest: blockers, then what is to check, then messages. A filter by type (plan alerts, referential coherence, messages) narrows it and is kept in the page's address. An alert or an anomaly closes by dealing with what it reports; only the messages are marked as read or cleared, and they are kept by this browser alone.

## Who uses it

| Who | What they see and do |
| --- | --- |
| The **organiser** (administrator) | The whole cycle above, edition by edition. They prepare, solve, publish and configure the application |
| The **staff member** | They have no account: they receive a personal link to their **espace** and identify themselves there with a code received by e-mail. There they read their schedule, keep it on their calendar, acknowledge receipt, report a conflict, propose or accept a swap of seats (the swap fair) and declare their availability when the organiser collects it. See [The staff espace](espace-animateur.md) |
| The **stand manager** | A read-only account, limited to one or more stands of an edition: the hours, the filled seats and the empty seats of the published plan, and, depending on your setting, the names of the people. They can print their stand's sheet |
| The **human resources** person | An account limited to an edition's compliance report: the check, on the published plan, that the legal rules are respected, exportable as PDF or CSV |

The last two accounts are granted in [Manage accounts and rights](comptes-et-droits.md).

The application publishes nothing outside, sends no message and opens no espace for an edition that is not **the active edition**: you can prepare and re-solve an edition in safety, and make it visible only at the moment you choose. Activation is described in [Chain editions](enchainer-les-editions.md).

## The words of the application

| Word | What it means |
| --- | --- |
| Edition | One occurrence of the event ("Year 2026"): its stands, its team, its schedule. Editions never mix |
| Timeslot, shift | A time slice of a day; a shift is an actual slice of work on a stand |
| Seat | A person to find for a stand on a timeslot: a stand that needs three staff members has three seats |
| Solver | The name of the screen that starts the **automatic solve** of the schedule; "resolution" means a solve |
| Hard rule | A rule the schedule must never break (the legal rules, those for minors, an unavailability). The other rules are preferences: the solve does its best |
| Score | A schedule's balance sheet on three levels: the hard rules, the seats left empty, then quality (fairness, wishes, continuity). The application always turns it into sentences |
| Floor | The part of a rule that no solve can improve, because data is missing (nobody has declared a wish, for example) |
| Partial re-solve | A solve that keeps the schedule in place and redoes only what a change broke |
| Lock | The schedule of a person, a stand or a day, which no solve will touch any more |
| Manual adjustment | An exception set by hand: two people not to be put together, a pair to favour, a carpool |
| Consigne | A closure imposed from outside (a prefectoral order, a heatwave) applied to all stands for a few dates |
| Version | A copy of the schedule set aside (by hand, before a solve or at publication), which you can put back in place later |
| CSV, ICS, PDF | The file formats: CSV opens in a spreadsheet, ICS is added to a calendar (phone, Outlook, Google), PDF is printed |
| Scenario file | A file that holds a whole edition, to copy or share it |
| Webhook | A message the application sends by itself to another tool of the team (Slack, Discord...) |
| MCP | The door through which an AI assistant can consult the application, without ever seeing personal data |

## What it does not do

- The solved schedule is a proposal: you review, adjust and decide. A reviewed day can be marked as such.
- A staff member is never assigned against a legal rule. At worst, these rules give way by seats left empty and flagged, never by a minor placed outside the framework.

The reasons for each rule and its cost are explained in [The solver's rules](../comprendre/pourquoi-ces-regles.md).
