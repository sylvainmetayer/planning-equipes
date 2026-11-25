---
twin: installer/mentions-legales
---

# Fill in the legal notice

Before you open the instance to its staff, you set the organiser's legal information in the environment: without five of the settings below, the application refuses to start in production. This page lists them, explains why, and shows how to run a demonstration instance that has none.

## Why they cannot stay empty

The seven `LEGAL_*` variables default to `""`, and the public `/mentions-legales` page says so itself: "No legal information has been entered for this deployment". That is acceptable on a demonstration instance, not on a live one, where the LCEN (the French law on trust in the digital economy) requires an identified publisher and host, and the GDPR requires a data controller, a legal basis and a retention period.

This information is **the organiser's**: the organiser is the employer of the people scheduled and the data controller. You, as host, are a **processor** within the meaning of article 28 of the GDPR, which calls for a written agreement between the two parties from the first hosting, even a free one. Its mandatory content and the register that goes with it are in [what the host must write and keep](rgpd.md).

## The variables to fill in

| Variable | Status | What it holds |
| --- | --- | --- |
| `LEGAL_EDITEUR` | **required** | Publisher: name, legal form, address, registration |
| `LEGAL_HEBERGEUR` | **required** | Host: name and address |
| `LEGAL_CONTACT` | **required** | Contact address, including for exercising one's rights |
| `LEGAL_BASE_LEGALE` | **required** | Legal basis for processing personal data |
| `LEGAL_CONSERVATION` | **required** | Retention period for personal data |
| `LEGAL_RESPONSABLE_TRAITEMENT` | optional | Data controller, if different from the publisher; empty means the publisher |
| `LEGAL_DIRECTEUR_PUBLICATION` | optional | Publication director |

The five required ones make startup fail in production when they are empty, like an `ADMIN_PASSWORD` left on its sample value. The message names **all of them at once**: fixing them one by one would cost five restarts.

The other two are deliberately optional. An empty `LEGAL_RESPONSABLE_TRAITEMENT` falls back to the publisher, and requiring it would contradict that fallback. Not every publisher is required to have a publication director.

Take care with `LEGAL_CONSERVATION`: this period is shown in the privacy policy, and **nothing in the code enforces it**. Announce only what you will actually do, through the [annual purge](conservation-des-donnees.md#la-purge-annuelle).

## The accessibility statement

Four more variables, `LEGAL_ACCESSIBILITE_ETAT`, `LEGAL_ACCESSIBILITE_DATE_AUDIT`, `LEGAL_ACCESSIBILITE_CONTENUS` and `LEGAL_ACCESSIBILITE_SIGNALEMENT`, fill in the `/declaration-accessibilite` page: the RGAA compliance status (the French accessibility standard), the audit date, the inaccessible content, and the address for reporting problems. They do not block startup: the obligation in article 47 of law no. 2005-102 applies only to certain organisations, and the empty page itself says that no audit has been declared rather than claiming compliance. The details are in [accessibility](accessibilite.md).

## An instance that is not live

A demonstration or acceptance-test instance sets `LEGAL_DEMO_INSTANCE=true` and starts with an empty page. The flag is explicit rather than inferred from `PUBLIC_URL` or from the launch mode: what an instance is *meant for* appears nowhere in its configuration ([decision 0040](repo:docs/decisions/0040-mentions-legales-exigees-au-demarrage.md)). Do not set it on an instance open to its staff.

A demonstration or acceptance-test server can also show the event-day mode and the staff espace out of season. Started with `HORLOGE_SIMULEE_AUTORISEE=true`, it shows in *Settings*, *Instance* tab (behind the "Advanced settings" button), the "Simulated date and time" card: a date, an optional time, and "Back to the machine's clock". As long as a date is set, a red hourglass in the top bar reminds you and takes you back to this card. Without the variable, the card does not appear and the API refuses the write (`400`). Never enable it in production: it would make the event-day screen lie during a real event. The details are in [the API note](../comprendre/api-jour-j-et-animateurs.md#figer-la-date-du-jour).

## Upgrading an instance that is already live

If your live instance has never set these variables, it **will not restart** until they are set. This is intended: the obligation already existed, and nothing enforced it. Prepare them before the upgrade window, not during it.

## Check

After the restart, `scripts/verifier-deploiement.sh` checks that the five required notices are not empty ([the script in detail](installation.md#mettre-la-pile-en-route)), and the instance's `/mentions-legales` page shows what you entered.
