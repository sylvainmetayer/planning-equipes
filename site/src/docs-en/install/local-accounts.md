---
twin: installer/comptes-locaux
---
# Create the administration accounts

You give each administrator a personal account, protected by a password and a code received by e-mail, and you keep a back door. Local accounts work with or without an identity provider; to connect a directory, see [sign-in through an identity provider](oidc.md).

## Add an account

1. Open *Settings › Accounts* and add the account, leaving the invitation ticked.
2. The person receives a single-use link, valid for 72 hours, to choose their password (at least twelve characters).

**The application must therefore be able to send mail** (`MAIL_*`, and `PUBLIC_URL` to write the link: without a reachable `PUBLIC_URL`, the link points to `localhost`). See [Get your e-mails delivered](courriels.md).

"Reset the password" (same tab) and "Forgot my password" (sign-in page) send a link valid for one hour. The administrator never sees the link or the password.

## The second sign-in step

At sign-in, a **six-digit code received by mail** follows the password (ten minutes, five attempts). It is on by default. `AUTH_LOCAL_SECONDE_ETAPE=false` switches it off: keep that for an instance without e-mail, or already behind an access proxy that imposes its own second factor.

Local accounts stay open behind an OIDC provider. Every sign-in, successful or not, is in *Settings › Logins* (IP address and time, never what was typed).

## Never lock yourself out

Behind an OIDC provider, the `admin` account from the configuration is **closed by default**. Before connecting the provider, create your own administrator account and check that it opens the administration: otherwise, the day the provider stops answering, only the database is left. The full procedure (preparation, reopening with `ADMIN_LOCAL_SECOURS=true`, rereading the login log) and its reasoning are in [the emergency account](securite-acces-administration.md#le-compte-de-secours--ne-jamais-senfermer-dehors).

Password lost by the last administrator: same procedure.
