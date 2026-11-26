---
twin: installer/rgpd-atteinte-aux-donnees
---

# Prepare the response to a data breach

Decide now who you call and within what time: it costs ten minutes, and improvising it on a festival Sunday costs the relationship. This is the procedure to follow when you host a client's instance: what counts as a breach, the deadline to meet, the person to call, the content of the alert and the log to keep, including for breaches that are not notified (art. 33).

The processor **never notifies the CNIL** (the French data protection authority): it alerts the data
controller, **without undue delay**, and it is the controller who decides whether to
notify the authority. The controller has 72 hours **from the moment
the host informed them**: any delay incurred here is taken out of their deadline, which
means the commitment must be quantified.

## What counts as a breach

A breach covers more than an intrusion: it is anything that affects the
**confidentiality**, **integrity** or **availability** of the data,
accidental or not. All three are notified, even though they are not handled
the same way.

| Case | What to know before calling |
| --- | --- |
| Lost dump: a backup left in clear, sent through an uncontrolled channel, forgotten on a machine that was handed over | What it contained; a dump is **complete**: names, minors' dates of birth, addresses, espace tokens (see the backup in [`exploitation.md`](exploitation.md)) |
| Unauthorised access to the administration | Since when, what was viewed or modified, and whether the administration password was used elsewhere |
| Leak of staff espace, calendar subscription or wall display tokens | The tokens travel **in the URL path**: shared, indexed or forwarded reverse-proxy access logs are a breach, not a negligence without consequence (see [what remains the reverse proxy's responsibility](securite.md#ce-qui-reste-à-la-charge-du-reverse-proxy)). A leaked subscription token is **immediately exploitable without a second factor**, and it appears in those logs at every calendar synchronisation, so far more often than the other |
| Database or backup lost with no copy | This is a breach of **availability**: it is notified, even with no third-party access at all |
| Individual schedule sent to the wrong address | A breach too, on its own scale: the wrong-recipient error is the most frequent case in practice |

Two cases are **not** breaches: an instance outage with no data loss
(that is an operating incident, the "Know it's running" section of [`exploitation.md`](exploitation.md)), and a staff member who
sees a colleague's schedule through a feature designed for that
([viewing a colleague's seats](securite-limites-de-debit.md#consultation-des-postes-dun-collègue)).

## The deadline we commit to

| Step | Commitment |
| --- | --- |
| Alert the client contact | **Within 24 hours** of becoming aware, even without a full diagnosis; the first call says what we know, not what we have understood |
| First written statement qualifying the facts | Within 48 hours |
| Final report | Once the investigation is closed, with no deadline set in advance |

Notifying early and incompletely is explicitly provided for by the regulation: art. 33.4
allows information **in phases**. Waiting until everything is understood is the
only really costly mistake.

## Who we call

Call a named person and a deputy, never a generic address: a
`contact@` is read by nobody on a Sunday. They are named in the
processing agreement (see [the agreement](rgpd-sous-traitance.md)) and carried over to the register (see [the entry template](rgpd.md#remplir-une-entrée-du-registre)), with a
phone number, because the incident may be precisely the one that prevents e-mail
from arriving.

## What the alert contains

Art. 33.3 sets the content; the controller will reuse these elements as they are
for the CNIL:

- the nature of the breach, and the **categories and approximate number** of
  people and records concerned;
- the likely consequences;
- the measures taken or proposed, including to mitigate their effects;
- the contact point on the host's side.

The number of people concerned can be read in the application: it is the headcount
of the editions affected. Recalling it here avoids having to look for it under
pressure.

## The breach log

Art. 33.5 requires **documenting every breach, including one that is not
notified**: this register makes it possible to show that the facts were properly
qualified. It lives with the register of processing activities and the purge log
(see [the purge log](rgpd.md#tenir-le-journal-des-purges-et-des-restaurations)):

| Column | Content |
| --- | --- |
| Discovery | Date and time of **becoming aware**, not of occurrence: that is what starts the deadlines |
| Facts | What happened, and through which channel it was learnt (probe, error tracking, report from a staff member) |
| Scope | Editions and categories of data affected, approximate volume |
| Qualification | Breach or not, **and why**, including when the answer is no |
| Notification | Who was informed, when, by what means |
| Follow-up | Corrective measures, and what they change in the security measures declared in the register |

## What is missing to keep this commitment

A 24-hour deadline assumes that you **know** something happened.
The "Know it's running" section of [`exploitation.md`](exploitation.md) already says so for outages, and the
dependency is the same here: with neither `SENTRY_DSN` set nor an external probe, an
instance can have gone down, or worse, without anyone learning of it before
Monday. Committing to a notification deadline without these two components amounts to
signing an obligation that will not be met.
