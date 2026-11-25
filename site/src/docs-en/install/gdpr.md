---
twin: installer/rgpd
---

# Keep your instance's GDPR register

If you host an instance for an organisation other than your own, three obligations arise at the first hosting, even a free one: a processing agreement (art. 28), a record of processing activities (art. 30) and a breach notification procedure (art. 33). This page has you keep the register; the [agreement](rgpd-sous-traitance.md) and the [breach procedure](rgpd-atteinte-aux-donnees.md) each have their own guide, and [what the instance keeps and does not erase](rgpd-limites-a-consigner.md) goes in the register as it is. None is a document you produce once: the agreement is signed before going into production, the register is kept up and the procedure is decided **before** you need it.

> **This is not legal advice.** The exact classification of the data controller in an association and the scope of the assistance commitment deserve a lawyer's look.

Hardening is in [`securite.md`](securite.md), operations in [`exploitation.md`](exploitation.md); here is what you must have written before hosting someone else's instance, and what is kept up afterwards.

## Who is what

| Role | Who | Why |
| --- | --- | --- |
| Data controller | The organiser | They are the employer or the principal of the people scheduled: they decide the purposes and means. It is **their** information that fills the `LEGAL_*` variables (see the legal notice in [`exploitation.md`](exploitation.md)) |
| Processor | Whoever hosts the instance | They process personal data **on behalf of** the controller, without deciding the purpose |
| Sub-processors | Infrastructure host, SMTP relay, Bugsink, Cloudflare | Each exists only if the corresponding building block runs (see [the entry template](rgpd.md#remplir-une-entrée-du-registre)) |

The services the application uses (SMTP relay, error tracking) have their own analysis in the GDPR section of [`observabilite.md`](observabilite.md#rgpd); what follows covers the other direction: the host's role towards their client.

## Keep the record of processing activities (art. 30)

The exemption for organisations under 250 employees **does not apply** to a
regular processing, and hosting a staff schedule is one.

The register to keep is the **processor's** (art. 30.2), which has only five
mandatory headings (the purposes, which belong to the controller, are not among them):

1. the identity and contact details of the host, and those of **each**
   controller on whose behalf they act;
2. the categories of processing carried out for each;
3. transfers outside the EU, where applicable;
4. the sub-processors;
5. a general description of the security measures.

Categories of data and retention periods are not required of a processor.
The [entry template](rgpd.md#remplir-une-entrée-du-registre) keeps them anyway: it is the CNIL (the French data protection authority) model, and it serves
as a handover sheet when someone else takes over the instance.

The register does not live in the repository, because it names clients. Keep it in a spreadsheet or
a versioned document outside the repository, with **one entry per hosted instance**.

## Fill in a register entry

The baseline is **identical for all instances**: it is the same
application, the same data model, the same measures. Only the
"variable" block is filled in per client, and it is filled in **when you
fill in `.env.prod`**: the two lists overlap.

### The common baseline

| Heading | Content |
| --- | --- |
| Categories of people | Staff members, **including minors**; supervisors and managers |
| Categories of data | Last name, first name, **date of birth**, e-mail address (optional), **phone number (optional, to call a replacement on the event day)**, skills, assignment wishes, unavailability days, **two access tokens** (the staff espace's and the calendar subscription's), **the organisation's wall display links (hashed token, label, date of last access)**, assignments and swaps, **self-service availability declarations, including a free-text comment**, **carpool requests (teammates named, and the free-text reason the organisation can attach when rejecting them)**, schedule snapshots, sessions and access logs, **action history (identifiers and field names, no values)**, **the result of each e-mail sent to a staff member (sent or failed, and why, with no address or content)**, **a staff member's emergency contact and legal representative (name and phone, optional: third-party data)**, **the phone number and contact address the organisation publishes to its staff, per edition, often a coordinator's personal number, hence personal data of the supervisory staff, shown to all staff members of the edition** |
| Processing carried out | Hosting, scheduling and solving, **display of the day's work schedule on a control-room screen (first name and initial by default)**, **import of a spreadsheet of staff supplied by the organisation (processed in memory, never kept)**, sending e-mails (access codes, individual schedules, swap notifications, **automatic night reminders and follow-ups**) **and recording their result**, backup, **logging of admin actions**, purge |
| Recipients | The organiser through the admin interface; the staff member through their espace **and through the calendar application to which they give their subscription address**; the other staff members for the visible part of the schedule (see [`securite.md`](securite.md)); **anyone standing in front of a wall display screen**; the SMTP relay |
| Security measures | TLS and HSTS; CSP and `Referrer-Policy` headers (**tokens travel in the URL**); session encryption; rate limiting on espace codes and lockout of the login form; origin reachable only through the reverse proxy; automatic nightly backup by `pg_dump`, rotated in a dedicated volume, whose **encrypted off-machine copy remains the operator's responsibility** (see the backup in [`exploitation.md`](exploitation.md)) |

### The variable block, per instance

| Heading | Where to read it |
| --- | --- |
| Data controller and contact | `LEGAL_RESPONSABLE_TRAITEMENT`, `LEGAL_CONTACT` |
| Declared legal basis | `LEGAL_BASE_LEGALE` |
| Announced retention period | `LEGAL_CONSERVATION`; see [the retention limit](rgpd-limites-a-consigner.md#aucune-purge-automatique) |
| Infrastructure host | `LEGAL_HEBERGEUR` |
| SMTP relay | `MAIL_HOST` |
| Error tracking | `SENTRY_DSN` set ⇒ Bugsink, EU hosting, **no transfer to declare** |
| Audience measurement | `CLOUDFLARE_WEB_ANALYTICS_TOKEN` set ⇒ **transfer outside the EU to declare**. The default is empty: setting nothing is enough to turn it off. This was not always so: the `%prod` profile used to carry the publisher's token ([`observabilite.md`](observabilite.md)) |
| Update check | On a tagged version, the **logged-in administrator's** browser asks `api.github.com` whether a more recent release exists (see [`versioning.md`](versioning.md)). No staff data leaves: the call is made only once the administrator session is confirmed, the staff espace never makes it, and only the administrator's IP address reaches GitHub Inc. (United States). It is therefore not a processing of staff data to declare in the register, but the privacy policy says so all the same, since nothing turns it off through an empty variable, unlike the two tools above. An operator who wants to turn it off removes `https://api.github.com` from `connect-src` in `CSP` (see [the browser headers](securite-navigateur.md#réglage)) |
| Outgoing webhooks | Configured in Settings › Instance ⇒ Slack, Discord, Telegram, a Matrix bridge or an automation tool receive the application's events. **No personal data leaves**: counts and technical identifiers (edition, snapshot, delivery), never a name, first name, address, date of birth or token, which `WebhookFormatsTest` checks on every event and every format. No recipient of staff data to declare; deliveries are kept 30 days, outside the SQL export |
| Weather service (`METEO_URL`) | Technical recipient, **with no personal data**: each morning, the rounded coordinates (to 0.01°) of the event's places and the dates go to Open-Meteo, with the server's IP address, never a browser's. Nothing to add to the categories of data; `METEO_ENABLED=false` turns it off |
| Notification contact and their deputy | Named in the agreement (see [whom to call](rgpd-atteinte-aux-donnees.md#qui-on-appelle)): **a person, not a generic address** |
| Dates | Start and end of the hosting |

## Keep the log of purges and restores

The purge procedure in [`exploitation.md`](exploitation.md) ends with "note the date and scope in the
record of processing". Here is the expected form: one row per operation,
in the same document as the register:

| Column | Content |
| --- | --- |
| Date | Date of the operation |
| Nature | Retention purge, deletion on request, or restore |
| Scope | Edition deleted, or staff record; deletion cascades over the tables carrying `edition_id` |
| Archived dump | Reference of the encrypted backup taken before the operation |
| Check | Staff espace **and subscription address** of a deleted token indeed answering `404` |

A manual purge with nothing in writing proves nothing. Only this log makes
the period announced by `LEGAL_CONSERVATION` tenable.

## Maintenance rhythm

| When | What |
| --- | --- |
| Before each first production release | Processing agreement signed on both sides (see [the agreement](rgpd-sous-traitance.md)), then creation of the register entry (see [the entry template](rgpd.md#remplir-une-entrée-du-registre)) |
| At each change of third-party block | Update the sub-processors and transfers, because a change of environment variable is a change to the register |
| At each purge or restore | One row in the log (see [the purge log](rgpd.md#tenir-le-journal-des-purges-et-des-restaurations)) |
| At each breach, notified or not | One row in the breach log (see [whom to call](rgpd-atteinte-aux-donnees.md#qui-on-appelle)); art. 33.5 makes no distinction |
| Once a year, after the event | Review of all entries, at the same time as the purge |
| At the end of hosting | Fate of the data settled and carried out, date entered in the register |

This register costs little if it starts with the first client, a lot if it
starts with the fifth.
