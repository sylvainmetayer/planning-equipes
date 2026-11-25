---
twin: regles/outils-de-l-organisateur
---
# The organiser's toolkit

You connect the application to your other tools, you know what is backed up, you find the help and the legal pages, and you work faster with the keyboard.

## Connect an AI assistant

An AI assistant can consult and drive the application in natural language, without ever seeing the staff's personal data. The connection (keys, exposed tools, confidentiality) is described in [The MCP server](../comprendre/mcp-connexion.md). Keys are managed in Settings, *MCP* tab (behind "Advanced settings").

## Notify another team tool (webhooks)

The application can announce what is happening to the tool where the team works: Slack, Discord, Matrix, Telegram, or an automation tool such as n8n, by a signed message. The events:

- schedule published;
- swap request to rule on;
- swaps lying dormant;
- availability declared;
- solve finished;
- backup failed;
- weather alert.

The messages carry counts, never a name.

1. Open Settings, click "Advanced settings", then the *Instance* tab, *Webhooks* card.
2. Create the webhook (address and format).
3. Test it with one button.
4. Follow it in its delivery log.

A receiver that is down is retried several times and never delays the operation it announces. The application refuses to call an internal address.

## Know whether the night backup succeeded

Every night, the whole database is copied to the host's disk, and only the latest copies are kept. Settings, *Instance* tab (behind "Advanced settings"), says where they go, which ones exist and whether the last night went well.

- A failed night warns the administrator by e-mail: the reason, the last successful copy, the number of nights in a row.
- A second message announces the first night that succeeds again.
- Restoring is an operation the operator performs on the database: see [Back up and restore](../installer/sauvegarde-et-restauration.md).

## Find the user guide in the application

- Help: the application's complete user guide, with a search, available while a solve is running or on an empty edition ("?" icon in the toolbar).
- What's new: what the installed version brought, and since which version. Changes are grouped by version; what is not yet released appears under "Coming up", and the list is sorted like the release notes, "Worth watching" first. Nothing has to be kept up to date by hand.

## Work faster

- Command palette: Ctrl+K opens a single input area that leads to any screen (or directly to one of its tabs or views) and finds a staff member, a stand or a timeslot. "g" followed by a letter goes straight to a screen, "/" enters the page filter and "?" lists the shortcuts. Single-key shortcuts can be turned off.
- Tables with the keyboard: see [Working in tables](preparer-edition.md#travailler-dans-les-tableaux).
- Light or dark theme: a toolbar button cycles the display between "automatic", "light" and "dark". "Automatic" follows the operating system's setting, live. An explicit choice is remembered by the browser and survives a reload.

## The public pages and what they contain

Four pages can be read without being logged in, and without a valid link.

| Page | What it says |
| --- | --- |
| Legal notice | Publisher, host, contact, intellectual property |
| Privacy policy | Which data, why, for how long, who accesses it (including audience measurement and error tracking tools) and how to exercise one's rights. It explicitly addresses minor staff members |
| Terms of use | The dividing line, written in black and white: the application computes proposals, the organisation decides. The organisation remains the employer and is responsible for the data and for compliance with the regulations; the software is provided as is |
| Accessibility statement | Linked from every screen: the RGAA (the French government's accessibility standard) compliance status the organisation declares, the inaccessible content, where to report a defect. When empty, it says so rather than announcing compliance |

The screens can be navigated with the keyboard, including moving an assignment.

These pages are to be filled in by the instance's host: see [Install it yourself](../installer/index.md).
