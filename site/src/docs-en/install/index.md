---
twin: installer/index
titre: Install it yourself
pour: Install, secure, back up and update your instance.
docs:
  - installation
  - legal-notices
  - email-delivery
  - local-accounts
  - oidc
  - keycloak
  - security
  - security-browser
  - security-admin-access
  - security-roles-and-rights
  - security-public-tokens
  - security-rate-limits
  - security-outgoing-calls
  - security-automated-checks
  - backup-and-restore
  - automatic-sends
  - tune-the-solve
  - solve-on-another-machine
  - outgoing-calls
  - monitoring
  - observability
  - run-the-instance
  - upgrade
  - versioning
  - release-and-patch
  - moving-to-4-0
  - data-retention
  - gdpr
  - gdpr-processing-agreement
  - gdpr-record-limits
  - gdpr-data-breach
  - accessibility-statement
  - environment-variables
---
# Install it yourself

You host Planning Équipes for an organiser, or for yourself: an application and its PostgreSQL database, behind your reverse proxy. These guides take you from the first deployment to running the instance during the event, in the order the questions come up.

## I want to put the application online

1. [Install the application on your server](installation.md): the production stack, the seven mandatory variables, the end-of-deployment check.
2. [Fill in the legal notice](mentions-legales.md): the five items without which the application refuses to start.
3. [Get your e-mails delivered](courriels.md): SMTP relay, SPF, DKIM, DMARC, and tracking of sends.
4. [Expose the instance on the Internet](securite.md): what the reverse proxy must do, and hardening the stack.

## I want to open the administration to my team

- [Create the administration accounts](comptes-locaux.md): password, e-mail code, emergency account.
- [Connect your company directory](oidc.md): sign-in through an OIDC provider.
- [Connect Keycloak](keycloak.md): the realm, the client, the AI assistant over OAuth and the order of the switchover.
- [Know what each role can call](securite-roles-et-droits.md): administrator, stand manager, human resources, staff member.

## I never want to lose data

- [Back up and restore](sauvegarde-et-restauration.md): the nightly backup, the manual backup, the guided restore.
- [Monitor the instance](surveillance.md): probes, metrics, logs.
- [Connect error tracking and metrics](observabilite.md).

## I want to tune what the application does on its own

- [Tune the automatic sends](envois-automatiques.md): day-before reminder, reminders, alerts.
- [Tune the schedule solve](calcul-du-planning.md) and [Solve on another machine](calcul-sur-une-autre-machine.md).
- [Allow webhooks and the weather alert](appels-sortants.md).

## I want to update or release

- [Update to a new version](mettre-a-jour.md), and [what a version number promises](versioning.md).
- [Release a version and patch the previous one](publier-une-version.md), for whoever builds the image.
- [Move from 3.x to 4.0](passer-a-la-4-0.md): what 4.0 will require, in preparation.

## I must meet my obligations

- [Purge and keep data](conservation-des-donnees.md): the annual purge.
- [Keep the GDPR register of your instance](rgpd.md), with the [processing agreement](rgpd-sous-traitance.md).
- [Publish your accessibility statement](accessibilite.md).

## I am looking for a specific setting

- [Find an environment variable](variables-environnement.md): every variable, its default and its effect.
- [Run the instance during the event](exploitation.md): the check one week before, and where to look when a Saturday goes wrong.
- [Third-party licences](licences-tierces.md): what the image redistributes.
