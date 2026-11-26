---
twin: regles/comptes-et-droits
---
# Manage accounts and rights

You give other people access to the application (a stand manager, a human resources person), you control what they see, and you find who did what.

## Who signs in and how

Sign-in works through one of:

- an administrator **password**;
- **named accounts** invited by e-mail (single-use link, code received by e-mail as a second step);
- sign-in delegated to the host's access portal.

![Settings, Accounts tab: the list of accounts and their rights](/images/fr/docs/comptes.webp "The administration accounts and their rights")

Staff espaces remain reachable through their link: see [The staff espace](espace-animateur.md). Setting up delegated sign-in and break-glass accounts is the host's job: see [Install it yourself](../installer/index.md).

Accounts are managed in Settings, *Accounts* tab.

## Give access to a stand manager

The **stand manager** sees, read-only, the hours and the seats to fill of their stand: the **published** schedule, of the active edition only. They have a screen of their own ("My stand") and a sheet to print.

![The My stand screen of a stand manager](/images/fr/docs/responsable.webp "What a stand manager sees: their stand, their timeslots, their empty seats")

1. Open Settings, *Accounts* tab.
2. Grant the stand manager right to the account, for one edition and for the stands they must see (stand by stand).
3. Set the names: a default setting for the edition and an exception per stand, each with an optional opening date. Until you decide otherwise, first and last names are not shown; empty seats stay without a name.

They never see contact details or ages.

## Give access to a human resources person

The **HR** right gives access to an edition's **compliance report**, and nothing else. It is granted in Settings, *Accounts* tab. Administrators have access too. Every consultation and every export is written to the journal.

![The HR compliance page as seen by a human resources account](/images/fr/docs/rh.webp "What a human resources person sees")

An HR account opens directly on its *HR compliance* page, outside the administration; an administrator reads the same report in *Diagnostic*, *Compliance* tab. It can be exported as PDF or CSV. For the labour inspectorate, from the published schedule, it gives per person:

- the hours actually worked per week, against the ceiling that applies (adult or minor);
- Sunday, public holiday and night hours;
- the shortest rest observed;
- the breaches of legal rules;
- a section dedicated to minors.

![Diagnostic, Compliance tab: the report per person](/images/fr/docs/conformite.webp "The compliance report, Compliance tab of the Diagnostic")

It changes no schedule rule. Emergency contacts and legal representatives entered by the staff are readable by administrators and by the HR right (see [The staff espace](espace-animateur.md#contact-durgence-et-représentant-légal)).

## Read the action history

The **history** (Settings, *History* tab, under "Advanced settings") keeps what was done in the edition and by whom: every write, every export, every send, whether it comes from a screen, an assistant or a night task, with the fields a change really altered.

![The action history, filtered by author](/images/fr/docs/historique-auteur.webp "The history filtered on one author")

A few filters:

- "Exports": who exported which file and when, including a schedule downloaded by a staff member from their espace, over the whole retention period.
- "Data": the changes the next solve will see. A period is chosen down to the minute, and the Solver screen leads there on exactly the changes it counts since the last solve.
- "Rules": the changes of a rule's activation, weight or level, with its name, the date and the author, without the value. The Planning rules page links there from a rule's panel.
- The staffing-need checks appear there with their team, their duration and their result, and a filter isolates them.

No name is kept there: identities are looked up at display time, and rows that are too old drop out by themselves.

A *Logins* tab, the same whatever the edition, lists every sign-in to the administration, every failure and every address locked after too many failures, with the time and the address, never what was typed.
