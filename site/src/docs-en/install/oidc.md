---
twin: installer/oidc
---

# Connect your corporate directory (OIDC)

So that your organisers sign in with their corporate account and the second factor of your identity provider (Keycloak, Zitadel, ADFS 2016 or later, any OpenID Connect provider), you declare the application with the provider, fill in a few variables, then create the accounts. It is optional: with no configuration, the `admin` account from the configuration opens the administration, as before. The reasoning behind the choice is in [ADR 0093](repo:docs/decisions/0093-connexion-par-un-fournisseur-oidc-et-secours-ferme.md).

## What the provider does, what the application does

- The provider says who is signing in (a stable subject, an address) and carries the second factor.
- The application decides what the person can do: the `compte` table (the person) and the `habilitation` table (their rights: `ADMIN`, `ORGANISATEUR` or `RESPONSABLE_STAND`). Without an active account carrying a right in force, the session opens nothing, whatever roles the provider might put in its token. Signing in creates no account.
- Staff members have no account: their espace opens with the personal link and the code received by e-mail, with or without a provider.

## Declare the application with the provider

For Keycloak, the step-by-step is in [Connect Keycloak](keycloak.md).

Create a confidential client ("authorization code" flow with PKCE), then declare these two addresses, exact and without wildcards: the `redirect_uri` sent follows the request's `Host` header, and a wildcard would let an unexpected host receive the code.

| Address | Value |
| --- | --- |
| Redirect after sign-in | `<PUBLIC_URL>/api/auth/oidc/login` (the sign-in route also serves as the return of the authorization code) |
| Redirect after sign-out | `<PUBLIC_URL>/login` |

The provider must be able to announce the user's address: the scopes requested by default are `profile` and `email`.

## Fill in the variables

| Variable | Role | Default |
| --- | --- | --- |
| `OIDC_ENABLED` | Connects the provider | `false` |
| `OIDC_AUTH_SERVER_URL` | The issuer, **the same URL** seen from the browser and from the container | (empty) |
| `OIDC_CLIENT_ID` | Client identifier | `planning-app` |
| `OIDC_CLIENT_SECRET` | Secret of the confidential client, **at least 32 characters**: startup is refused otherwise, Quarkus encrypting the PKCE state with it | (empty) |
| `OIDC_REQUIRE_EMAIL_VERIFIED` | Links an account by address only if the provider declares it verified. `false` for a directory (ADFS) that never sends `email_verified`; never for a provider where anyone can register an address | `true` |
| `OIDC_SCOPES` | Scopes requested | `profile,email` |
| `OIDC_PRINCIPAL_CLAIM` | Claim that carries the person's address | `email` |
| `OIDC_FORCE_HTTPS` | `https` redirects behind a proxy that terminates TLS | `false` |
| `OIDC_POST_LOGOUT_PATH` | Page where the person lands after signing out | `/login` |
| `OIDC_SESSION_AGE_EXTENSION` | Session extension when the token is refreshed | `30M` |
| `OIDC_CONNECTION_DELAY` | How long the provider discovery is retried at startup, as the application may be ready before the provider | `30S` |
| `OIDC_MCP_AUDIENCE` | Audience required of an OAuth token presented to the MCP server (see [`mcp-connexion.md`](../comprendre/mcp-connexion.md)) | `planning-mcp` |
| `ADMIN_LOCAL_SECOURS` | Opens (`true`) or closes (`false`) the emergency account. Absent: closed behind a provider, open without one | (empty) |

Behind a proxy that terminates TLS, set `OIDC_FORCE_HTTPS=true`; if the proxy does not forward the scheme, add `QUARKUS_OIDC_AUTHENTICATION_COOKIE_FORCE_SECURE=true` so that the session cookie never travels in clear.

## Create the first account and sign in

Do this before leaving the emergency account closed: otherwise nobody gets in the day the provider forgets you.

1. Sign in as `admin` (password `ADMIN_PASSWORD`), open Settings › **Accounts**, create your account (your address) and grant it the `ADMIN` right.
2. Sign in **through the provider** with that address. If the provider declares it verified, the account is then bound to the provider's **issuer + subject** pair. From then on only that subject designates it: another person who took over the same address at the provider gets nothing, and a change of address does not lock out the holder.
3. Confirm that the administration opens, then leave `ADMIN_LOCAL_SECOURS` absent (or `false`).

For the following people, an administrator creates the **account** (address, name) and grants a right; the person links up at their first sign-in in the same way. **Deactivating** an account cuts its access at the next request, without touching the provider.

The Settings › **Accounts** screen, visible to administrators only, lists the accounts; it lets you add them, deactivate and reactivate them, and grant and withdraw a right by role and by edition. The refusal to deactivate the last administrator, or to deactivate yourself, is displayed as the server states it. An organiser sees neither this tab nor the instance ones, and the server refuses them anyway (403). The history, the validation of a day and the event-day mode name the author by name (or failing that their address), resolved at read time. What each role opens is detailed in [Knowing what each role can call](securite-roles-et-droits.md).

## Change the provider's URL

The account is bound to the **issuer + subject** pair: if the issuer's URL changes (provider migration, new domain), no subject matches any more and nobody can sign in, administrators included. The procedure:

1. Reopen the emergency account: `ADMIN_LOCAL_SECOURS=true`, then restart (see [the emergency account](securite-acces-administration.md#le-compte-de-secours--ne-jamais-senfermer-dehors)).
2. Unlink the accounts in the database, so that they link again at their next sign-in (verified address):

   ```sql
   UPDATE compte SET oidc_sujet = NULL;
   ```

3. Change `OIDC_AUTH_SERVER_URL`, restart, sign in again through the provider.
4. Close the emergency account again: remove the variable and restart.

## Keep a fallback door

The configuration's `admin` account and its password (`ADMIN_PASSWORD`) are the fallback door, closed by default behind a provider (`ADMIN_LOCAL_SECOURS` opens it). The threat, the preparation and what to do the day the provider stops answering are in [Choose and protect the administration sign-in](securite-acces-administration.md#le-compte-de-secours--ne-jamais-senfermer-dehors).

Local accounts with a password stay open even behind a provider; they are described in [local accounts](comptes-locaux.md) and in [the four doors of the administration](securite-acces-administration.md#les-quatre-portes-de-ladministration).

## What the provider does not cover

The MCP server also accepts an OAuth token from the provider, under the same rights (see [`mcp-connexion.md`](../comprendre/mcp-connexion.md)); its API key remains usable. The `responsable_stand` role opens only `/api/responsable/*`, and the `organisateur` role covers only the editions its rights name: the list of instance routes reserved for administrators is in [Know what each role can call](securite-roles-et-droits.md#ce-que-le-rôle-organisateur-nouvre-pas).
