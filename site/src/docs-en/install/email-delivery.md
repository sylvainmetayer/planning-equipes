---
twin: installer/courriels
---
# Get your e-mails delivered

You set up the SMTP relay and the sending domain so that access codes, schedules and reminders reach the inbox, then you learn to read what the application keeps of each send. It is the easiest point to miss, because it fails silently: an espace link filed as spam produces neither an error nor an alert, only a staff member who "received nothing" the day before the event.

## 1. Connect the SMTP relay

In the `.env.prod`:

```properties
MAIL_HOST=smtp.example.org
MAIL_PORT=587
MAIL_USERNAME=...
MAIL_PASSWORD=...
MAIL_START_TLS=REQUIRED
MAIL_FROM=planning@<domaine>
```

`MAIL_HOST` and `MAIL_FROM` are mandatory. The production compose takes `587` and `REQUIRED` by default, where the application alone starts against a local Mailpit (`1025`, no TLS). Also fill in `MAIL_ADMIN`: it is the address that receives swap requests, the end of a solve if the edition asks for it, the weather alert and the failure of the nightly backup.

## 2. Set up the domain

On the domain side, the application can do nothing, yet it is what decides whether the message is accepted:

- SPF: authorise the relay used;
- DKIM: sign, and publish the key;
- DMARC: at minimum a monitoring policy, aligned with the domain of `MAIL_FROM`.

## 3. Test before the first send

Send a test schedule to **three different mailboxes**, including Gmail and Outlook, which are the strictest. Check that the message arrives in the inbox, not only that it leaves. The *Debug* page (Ctrl+K, or the `/debug` address, *Checks* tab) offers "Send a test mail".

Each mail goes out in **two parts**: plain text, and an HTML version dressed in the deployment's colours (`BRANDING_PDF_*`, `BRANDING_ORGANISATION` in the footer). The `BRANDING_PDF_LOGO` logo is embedded in the message itself: no image is loaded from a server on opening, which mail clients block and spam filters penalise. A client that refuses HTML displays the text, identical word for word.

With `MAIL_MOCK=true`, nothing leaves: the mails are logged, and the screen says "simulated" so that a trial run is not mistaken for a real send.

## 4. Read what is kept of each send

Every e-mail addressed to a staff member (schedule published or resent, access code, invitation to declare, reminders, day-before reminder, swaps, carpooling, absences) leaves **one line** in the `envoi_mail` table: the person's identifier, the e-mail template, "sent" or "failed", the failure category and the date. Neither the address, nor the content, nor the mail server's reply, which readily quotes the refused address.

This is what the *Acknowledgement* column of the Staff page reads to display "Failed to send" instead of "silent", the "Failed to send" filter (`?envoi=echec`) and the signal on the staff member's record.

| Category | What the relay answered | What you do |
| --- | --- | --- |
| Server unreachable | nothing: connection refused, unknown name, timeout, TLS failure | check `MAIL_HOST`, `MAIL_PORT`, the network |
| Authentication | 530, 535, 5.7.8, and a 5.7.x that speaks of authentication ("relaying denied, authentication required") | check `MAIL_USERNAME` and `MAIL_PASSWORD` |
| Address refused | 5.1.x; 550, 551, 553 when no extended code says otherwise | correct the address on the record |
| Temporary refusal | any 4xx, and a full mailbox (552, 5.2.2) | nothing: the next send is allowed |
| Other | the rest: sender or relay refused (550 or 553 5.7.1), spam rejection, error before sending… | read the server log, check `MAIL_FROM` |

A 550 or a 553 therefore does not always mean "address refused": when the relay gives an extended code, only a `5.1.x` targets the recipient. A `553 5.7.1 Sender address rejected` or a `550 5.7.1 Relaying denied` targets the application (a `MAIL_FROM` that the relay does not accept, a relay that does not allow sending). Filing the whole list under "address refused" would block everyone's reminders because of a configuration error.

**A refused address is no longer reminded**, neither by hand nor at night, as long as the record has not been modified since the failure: the same e-mail would meet the same refusal. Any modification of the record lifts the block, and the signal with it; no fingerprint of the address is kept to know whether it is the address that changed. Once the record is modified, the next run of the automatic sends reminds the person, and "Remind now" can do so at once.

### What "sent" means

"Sent" means "handed to the relay", not "received" or "read". An authenticated relay almost always accepts the recipient and returns the refusal **later**, in a non-delivery notice addressed to `MAIL_FROM`; nobody picks it up today. These deferred rejections await the choice of a production relay ([decision 0073](repo:docs/decisions/0073-rejets-differes-imap-ou-webhook-du-relais.md)). In practice, the "address refused" category will rarely be seen: what the screen mostly shows is relay outages.

### How long the lines stay

The lines live as long as the action history (`JOURNAL_RETENTION`, ninety days by default, see [data retention](conservation-des-donnees.md)): the nightly purge removes the oldest, all editions together, and they disappear with their edition. They travel in the SQL export, like `envoi_planning`, and are not copied when an edition is duplicated.

## What about the night sends?

Day-before reminders and follow-ups leave from a scheduled task that has its own rules: [the automatic sends](envois-automatiques.md).
