---
twin: installer/exploitation
---
# Run the instance during the event

You prepare the week before the event, and you know where to look on a Saturday when something goes wrong. An event lasts a few days during which all staff members consult their schedule: what follows lets someone other than the person who installed the instance take over.

If you would rather not run the instance yourself, [write to us](/contact/): we can host it for you.

## One week before

| Check | How | Guide |
| --- | --- | --- |
| E-mails reach the inbox | a test schedule to three mailboxes, including Gmail and Outlook | [Get your e-mails delivered](courriels.md) |
| The event's edition is **active** | *Editions* page: without it, no reminder goes out | [Tune the automatic sends](envois-automatiques.md) |
| The plan is **published** | the day-before reminder reads the published plan, never the working plan | [Tune the automatic sends](envois-automatiques.md) |
| A recent nightly backup exists and you are warned if it fails | *Settings*, *Instance* tab; `MAIL_ADMIN` filled in | [Back up and restore](sauvegarde-et-restauration.md) |
| You have already done a trial restore | `scripts/restaurer.sh --essai` | [Back up and restore](sauvegarde-et-restauration.md#3-restaurer) |
| An external probe watches `/q/health/ready` | the application cannot report its own absence | [Monitoring the instance](surveillance.md) |
| Error tracking is connected | `SENTRY_DSN` filled in | [Connect error tracking](observabilite.md) |
| The legal notice is complete | `scripts/verifier-deploiement.sh` | [Fill in the legal notice](mentions-legales.md) |
| The deployed version is named | application footer, `APP_VERSION` | [Update](mettre-a-jour.md) |

## During: a Saturday when something goes wrong

| What you hear | Where to look first |
| --- | --- |
| "I received nothing" | the four causes in order, [check when nothing arrives](envois-automatiques.md#3-vérifier-quand-rien-narrive), then the [classification of send failures](courriels.md#4-lire-ce-qui-est-gardé-de-chaque-envoi) |
| "The site no longer responds" | `docker compose -f docker-compose.prod.yml --env-file .env.prod ps`, then the [logs](surveillance.md#lire-et-limiter-les-journaux); the `/q/health/ready` probe says whether the database responds |
| "The solve won't start" | the solve cap or the instance quota ([the solve](calcul-du-planning.md)), and a failing remote compute machine ([the remote solve](calcul-sur-une-autre-machine.md#4-savoir-ce-qui-se-passe-en-cas-de-panne)) |
| "I can't sign in any more" | the emergency account ([local accounts](comptes-locaux.md#ne-jamais-senfermer-dehors)), the login log and [the form lockout](securite-acces-administration.md) |
| "Data is missing" | restore a dump, knowing that everything more recent is lost: [Back up and restore](sauvegarde-et-restauration.md#3-restaurer) |
| A blank page or a dead menu link after a redeployment | [Redeploy without breaking open browsers](mettre-a-jour.md#3-redéployer-sans-casser-les-navigateurs-ouverts) |

## After: close cleanly

Once the event is over, the measure of the realised freezes at the first read, and you carry out the annual purge: [Purge and keep data](conservation-des-donnees.md).

## All the guides in this part

- [Install the application on your server](installation.md)
- [Fill in the legal notice](mentions-legales.md)
- [Get your e-mails delivered](courriels.md) and [Tune the automatic sends](envois-automatiques.md)
- [Tune the schedule solve](calcul-du-planning.md) and [Solve on another machine](calcul-sur-une-autre-machine.md)
- [Allow webhooks and the weather alert](appels-sortants.md)
- [Back up and restore](sauvegarde-et-restauration.md) and [Purge and keep data](conservation-des-donnees.md)
- [Monitoring the instance](surveillance.md) and [Connect error tracking and metrics](observabilite.md)
- [Upgrade to a new version](mettre-a-jour.md)
- [Create the administration accounts](comptes-locaux.md)
