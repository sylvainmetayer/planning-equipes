---
twin: installer/mentions-legales
---

# Fill in the legal notice

Before you open the instance to its staff, you set the organiser's legal information in the environment: without five of them (the five required keys of `LEGAL_MENTIONS`), the application refuses to start in production. This page lists them, explains why, and shows how to run a demonstration instance that has none.

## Why they cannot stay empty

The notices default to `""`, and the public `/mentions-legales` page says so itself: "No legal information has been entered for this deployment". That is acceptable on a demonstration instance, not on a live one, where the LCEN (the French law on trust in the digital economy) requires an identified publisher and host, and the GDPR requires a data controller, a legal basis and a retention period.

This information is **the organiser's**: the organiser is the employer of the people scheduled and the data controller. You, as host, are a **processor** within the meaning of article 28 of the GDPR, which calls for a written agreement between the two parties from the first hosting, even a free one. Its mandatory content and the register that goes with it are in [what the host must write and keep](rgpd.md).

## The variables to fill in

The notices are set by **one variable**, `LEGAL_MENTIONS`, of the form `key=value;key=value`: a literal semicolon in a value is written `\;`, and an unknown key is reported at startup and then ignored.

| Key of `LEGAL_MENTIONS` | Status | What it holds |
| --- | --- | --- |
| `editeur` | **required** | Publisher: name, legal form, address, registration |
| `hebergeur` | **required** | Host: name and address |
| `contact` | **required** | Contact address, including for exercising one's rights |
| `base-legale` | **required** | Legal basis for processing personal data |
| `conservation` | **required** | Retention period for personal data |
| `responsable-traitement` | optional | Data controller, if different from the publisher; empty means the publisher |
| `directeur-publication` | optional | Publication director |

Example, in the compose `.env`:

```
LEGAL_MENTIONS="editeur=Association Exemple, 1 rue des Pions, 79000 Niort;hebergeur=Hébergeur Fictif SAS, Paris;contact=planning@exemple.invalid;base-legale=Intérêt légitime de l'organisateur;conservation=Jusqu'au 31 décembre de l'année suivant l'édition"
```

The five required ones make startup fail in production when they are empty, like an `ADMIN_PASSWORD` left on its sample value. The message names **all of them at once**: fixing them one by one would cost five restarts. This refusal stays in place in 4.0.

The other two are deliberately optional. An empty `responsable-traitement` falls back to the publisher, and requiring it would contradict that fallback. Not every publisher is required to have a publication director.

Take care with `conservation`: this period is shown in the privacy policy, and **nothing in the code enforces it**. Announce only what you will actually do, through the [annual purge](conservation-des-donnees.md#la-purge-annuelle).

The seven old variables (`LEGAL_EDITEUR`, `LEGAL_HEBERGEUR`, `LEGAL_CONTACT`, `LEGAL_BASE_LEGALE`, `LEGAL_CONSERVATION`, `LEGAL_RESPONSABLE_TRAITEMENT`, `LEGAL_DIRECTEUR_PUBLICATION`) are still read throughout 4.x, with a warning at startup, and removed in 5.0: see [Moving from 3.x to 4.0](passer-a-la-4-0.md).

## The accessibility statement

A second variable, `LEGAL_ACCESSIBILITE`, fills in the `/declaration-accessibilite` page through four keys, `etat`, `date-audit`, `contenus` and `signalement`: the RGAA compliance status (the French accessibility standard), the audit date, the inaccessible content, and the address for reporting problems. They do not block startup: the obligation in article 47 of law no. 2005-102 applies only to certain organisations, and the empty page itself says that no audit has been declared rather than claiming compliance. The details are in [accessibility](accessibilite.md).

## An instance that is not live

A demonstration or acceptance-test instance sets `LEGAL_DEMO_INSTANCE=true` and starts with an empty page. The flag is explicit rather than inferred from `PUBLIC_URL` or from the launch mode: what an instance is *meant for* appears nowhere in its configuration ([decision 0040](repo:docs/decisions/0040-mentions-legales-exigees-au-demarrage.md)). Do not set it on an instance open to its staff.

A demonstration or acceptance-test server can also show the event-day mode and the staff espace out of season. Started with `HORLOGE_SIMULEE_AUTORISEE=true`, it shows in *Settings*, *Instance* tab (behind the "Advanced settings" button), the "Simulated date and time" card: a date, an optional time, and "Back to the machine's clock". As long as a date is set, a red hourglass in the top bar reminds you and takes you back to this card. Without the variable, the card does not appear and the API refuses the write (`400`). Never enable it in production: it would make the event-day screen lie during a real event. The details are in [the API note](../comprendre/api-jour-j-et-animateurs.md#figer-la-date-du-jour).

## Upgrading an instance that is already live

If your live instance has never set these variables, it **will not restart** until they are set. This is intended: the obligation already existed, and nothing enforced it. Prepare them before the upgrade window, not during it.

## Check

After the restart, `scripts/verifier-deploiement.sh` checks that the five required notices are not empty ([the script in detail](installation.md#mettre-la-pile-en-route)), and the instance's `/mentions-legales` page shows what you entered.
