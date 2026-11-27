---
twin: installer/securite-acces-administration
---

# Choose and protect the admin login

Before opening the instance to people other than yourself, choose which door your administrators come in by (OIDC directory, local account or the `admin` account from the configuration), keep the emergency account closed without locking yourself out, and set the login lock behind your reverse proxy or tunnel. This page describes those three choices, then what protects the instance when an access proxy sits in front.

## The four doors to administration

Four doors, from strongest to weakest:

1. **An OIDC identity provider** ([setup](oidc.md), [ADR 0093](repo:docs/decisions/0093-connexion-par-un-fournisseur-oidc-et-secours-ferme.md)): each
   organiser signs in under their own name, with the provider's second
   factor. An unknown or deactivated account of the application has no role,
   and `/api/*` answers `403` (the login page explains it); only an `ADMIN`
   right in force gives the `admin` role. The `/q/openapi` and
   `/q/swagger-ui` pages follow the same rule.
2. **A local account** ([ADR 0094](repo:docs/decisions/0094-comptes-locaux-par-mot-de-passe.md)): an address and a password chosen by its
   owner alone, through a single-use link received by e-mail (72 h at
   invitation, one hour for a reset or "Forgot my password"),
   then a **six-digit code received by e-mail**, on by default
   (`AUTH_LOCAL_SECONDE_ETAPE=false` turns it off). The password is a bcrypt
   hash of at least twelve characters; link, challenge and code are stored only
   hashed, in global tables (`compte_jeton`, `compte_defi`), outside the SQL
   export. The form is locked per address like that of `admin`
   (`CONNEXION_MAX_ECHECS`), requests for a code and a link are limited per
   account, and a response never says whether an address has an account. Open **even
   behind an OIDC provider**: only the emergency account is closed. Deactivating an
   account cuts its session at the next request, and **choosing a new
   password closes all sessions opened with the old one** (the cookie carries
   the address and the date of the last password); the owner is notified
   by e-mail. The local account's lock counts per **(IP address, account) pair** at
   the form's threshold (`CONNEXION_MAX_ECHECS`), per **address alone at ten times
   that threshold**, and per **account** across all addresses, **also at ten
   times that threshold**: behind a
   shared address (a festival's wifi), several people's typos
   do not block everyone, but one password tried on
   many accounts, or many passwords on one account, hit
   a wall. A successful login resets only the failures of its own
   pair. Five wrong passwords lock out only the address that typed them:
   the account's owner still signs in from their own. An address feeds the
   account's counter no more than its pair's failures, even in a burst (each
   attempt reserves its slot before it is judged), so locking an account takes
   ten addresses within one window, which is the distributed guessing this
   lock stops, held to fifty attempts per `CONNEXION_DUREE_BLOCAGE` with the
   default settings. A session whose password has changed, or whose
   account was deactivated then reactivated, is anonymous (`401`): the session
   epoch (`sessions_depuis`) moves forward at each of these events. `429` and `409` responses after a
   correct password (lock, code not sent) confirm it: low risk,
   accepted. Outside development, a link is never written to `localhost`
   (`PUBLIC_URL` must be set, warning at startup).
3. **The `admin` account of the configuration**, a shared password with no
   second factor and no name in the action log (the actor there is `admin`).
   With no provider, it is the ordinary door. Behind a provider, it is the
   **emergency account**, closed by default: see
   [The emergency account](#le-compte-de-secours--ne-jamais-senfermer-dehors).
4. The `/mcp` API key, which opens only MCP: renewable from the MCP screen
   (hashed, shown once, overlap chosen), or the provider's OAuth token
   for an active account ([ADR 0097](repo:docs/decisions/0097-mcp-par-oauth-et-rotation-de-la-cle.md), [`mcp-connexion.md`](../comprendre/mcp-connexion.md)).

**Run the application behind https.** The OIDC session cookie and that of the
form only make sense encrypted in transit. Since deployment is agnostic,
nothing is forced: behind a proxy that terminates TLS, set
`OIDC_FORCE_HTTPS=true` (redirects in https) and, if the proxy does not forward
the scheme, `QUARKUS_OIDC_AUTHENTICATION_COOKIE_FORCE_SECURE=true`
(`quarkus.oidc.authentication.cookie-force-secure`) so the cookie never
travels in clear. Password attempts on the closed emergency account are
logged as failures (address only), without counting towards the lock.

### The emergency account: never lock yourself out

The threat: the `admin` account is a shared password, with no second
factor and no name in the log. Behind a provider, leaving it open
would cancel what the provider brings; closing it exposes you, conversely, to
locking out all administrators the day the provider goes down, or its
URL changes (the account is bound to the issuer + subject pair,
[`oidc.md`](oidc.md#changer-lurl-du-fournisseur)).

What "closed" means: `409` on `/j_security_check` (including under
a prefix, which Quarkus accepts) and a pre-existing session stripped of its role.
`ADMIN_LOCAL_SECOURS=true` reopens it; the opening at startup
(`Compte de secours ouvert au démarrage`) and each login through it
(`Connexion par le compte de secours`) are in the admin login log
(History, Logins tab), and the application logs a
warning as long as it is open. `ADMIN_PASSWORD` is still checked even
when closed (example value refused at startup).

**Before plugging in the provider** (or with the emergency account open):

1. Log in as `admin`, create your account (your address) in the
   accounts reference data and grant it the `ADMIN` right. At the next
   startup, the application logs a warning if the emergency account is closed
   and no active administrator exists.
2. Log in **through the provider** with that account: it is that first
   login that binds it to your identity there. Check that
   administration opens.
3. Leave `ADMIN_LOCAL_SECOURS` unset (or `false`).

**The day the provider stops answering, or administrator access is
lost**: set `ADMIN_LOCAL_SECOURS=true`, restart, log in as
`admin` with `ADMIN_PASSWORD`, fix things (reactivate the account, give it its
right back), then **remove the variable and restart**, and reread the login
log after the incident. If it is the provider's URL that changed, unlink
the accounts too ([`oidc.md`](oidc.md#changer-lurl-du-fournisseur)). Without
access to the deployment environment, the only way out is the database
(`desactive_le` or an `habilitation` row): keep `ADMIN_PASSWORD` and this
paragraph within the operator's reach.

## Lock the admin login form

The application has only one account, `admin`, with no second factor: a single pair
of credentials opens the personal data of ~150 people, minors
included. Yet `/j_security_check` accepted attempts at network
speed, while revealing the MCP key was already locked after
five tries.

Beyond `CONNEXION_MAX_ECHECS` consecutive failures, the address gets `429`
+ `Retry-After` for `CONNEXION_DUREE_BLOCAGE`, **even with the right
password**: otherwise it would be enough to wait your turn. A successful login
clears the counter, and the window runs from the last real failure.

| Variable | Default | Use |
| --- | --- | --- |
| `CONNEXION_MAX_ECHECS` | `5` | Consecutive failures tolerated per address |
| `CONNEXION_DUREE_BLOCAGE` | `PT15M` | Duration of the lock, counted from the last failure |

Success and failure both answer a redirect to the **same page**, so
neither the status nor `Location` tells them apart. Both are read
elsewhere: the failure from Quarkus's `AuthenticationFailureEvent`, which carries the HTTP
context and so the address; the success on the request itself, because Quarkus's
corresponding event carries no context, so no address to credit.

**The address retained is the rightmost entry of `X-Forwarded-For` that is not
a declared proxy**, and the header is read only if the connection itself
comes from a proxy declared in `CONNEXION_PROXYS_FIABLES`. Without this list
(the default), the header is entirely ignored and the connection address
counts: safe by construction, and correct for a deployment with no proxy.

Reading the header **from the left** is what made the lock ineffective. A proxy
*appends* its entry rather than replacing the header, so the first element
is the one the client wrote: a forged `X-Forwarded-For` per attempt
bought a fresh counter.

`QUARKUS_HTTP_PROXY_TRUSTED_PROXIES` **does not close this hole**, and you must not
count on it for that: it decides whether Quarkus reads the header, never which
element it retains (`ForwardedParser` always takes the first). Going through
`remoteAddress()` fixed nothing either. That is why
the lock reads the header itself, from the right, with its own list of proxies.

**Set `CONNEXION_PROXYS_FIABLES`** to the addresses of your reverse proxies,
as they appear on the application side. Without it, all visitors
behind the proxy share one counter, and the first attacker along locks
everyone's login.

The setting accepts **literal addresses and CIDR blocks**, separated by
commas: `172.18.0.0/16`, or `127.0.0.1,10.0.0.0/8`. A malformed entry
prevents startup rather than being ignored, otherwise you would think you had
declared your proxy while the lock counted everyone together.

Declaring a block means trusting every machine in it.
That is the right granularity for a private network whose members are your
own services, and the wrong one for a public range.

> **Behind a tunnel (Pangolin, Cloudflare Tunnel, `newt`...)**, the address to
> declare is neither the client's nor the proxy's public IP: it is that of the
> tunnel client **as your application sees it**, that is, a
> container on a bridge network. That address is assigned on attachment and
> moves at restart; the subnet, for its part, is fixed when the network is created.
> Hence the block:
>
> ```bash
> docker network inspect <network> -f '{{(index .IPAM.Config 0).Subnet}}'
> # → 172.18.0.0/16, the value to pass in CONNEXION_PROXYS_FIABLES
> ```
>
> First check that the header arrives: if your tunnel client relays over
> TCP without anything having set `X-Forwarded-For` upstream, this setting will
> bring you nothing and you must first have it added on the proxy side.

This lock does not replace the proxy's per-IP rate limiting, which applies to
everything else: exports, solving, the whole API.

### The trace it leaves

Every outcome of the form login is recorded in the instance's **login log**
(`journal_connexion` table, "Logins" tab of the
*History* page, whatever the edition chosen;
[ADR 0076](repo:docs/decisions/0076-journal-des-connexions-admin-par-instance.md)):
the successful login, **each failure**, and **the lockout**, once, on the
failure that reaches `CONNEXION_MAX_ECHECS`. Each row carries its timestamp and
the address the lock counts, read the same way (`X-Forwarded-For` from the
right behind a declared proxy). **Neither the password nor the identifier
entered** go in: a wrong identifier is often a password typed
in the wrong field.

An attempt refused with `429` during the lock writes nothing: it tried
no identifier, and a row per refusal would hand the table to anyone
hammering the form. The write leaves the event loop for a
worker; if it fails, the attempt is decided anyway and the failure
goes to the application log, in one line, with no stack trace. The rows follow
the history retention (`JOURNAL_RETENTION`, nightly purge) and do not
travel in the SQL export.

The lock is per address: an attack run from many addresses is
never blocked for long, and each of its failures would be one more row. The
trace is therefore bounded, for the whole instance:

| Cap | Value | Beyond |
| --- | --- | --- |
| Failed attempts logged | 1,000 per hour | counted, not written (the failure and its possible lockout together) |
| Writes waiting for a worker | 64 | counted, not written (successful logins included) |

A successful login is never held back by the first cap. A saturated window says so
in **a single** warning, at the first attempt dropped;
their number follows in one line when the next window opens. Only the
trace is bounded: the lock still counts every failure. A single address
stays far from the cap (about twenty failures per hour with the default
settings): reaching it is already the sign of a distributed attack.

## Behind an access proxy

An access proxy (a service that authenticates its visitors before letting them
through, or a shared tunnel) remains a good first barrier, but since
4.0 it **no longer logs anyone in to the application**: the proxy-header mode
is removed ([ADR 0102](repo:docs/decisions/0102-retrait-du-mode-en-tete-de-proxy.md)).
No identity header it sets (`Remote-Email`, `Remote-User`,
`X-Forwarded-User`...) is read. What protects the instance behind it:

| Protection | What it does |
| --- | --- |
| The application's login | Every `/api` route outside the public exceptions requires a session opened by one of the [four doors](#les-quatre-portes-de-ladministration). Getting past the proxy is never enough |
| The staff espace | Its URL token **and** the session that only the six-digit code sent by e-mail opens |
| `/mcp` | The key or an OAuth token; `PLANNING_MCP_REQUIRED_HEADERS` can additionally require a header that only the proxy sets ([the MCP connection](../comprendre/mcp-connexion.md#passer-un-proxy-daccès)) |
| The per-address locks | They trust `X-Forwarded-For` only from declared proxies (`CONNEXION_PROXYS_FIABLES`, see [the form lock](#verrouiller-le-formulaire-de-connexion-de-ladmin)) |
| The closed origin | The application's port is reachable only from the proxy (private network, firewall): without this, the proxy is bypassed and only the protections above remain |

On the proxy side, except from its authentication only the prefixes with a
dedicated token, `/api/abonnements/*` and `/api/mural/*`, and nothing else ([the public
tokens](securite-jetons-publics.md)). A second factor imposed by the proxy
can justify `AUTH_LOCAL_SECONDE_ETAPE=false` ([local
accounts](comptes-locaux.md)); it does not replace the login.
