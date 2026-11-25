---
twin: installer/securite-appels-sortants
---

# Control what leaves the instance

As long as you configure nothing, the application reaches only the SMTP relay and error tracking. Webhooks, the weather alert and the remote solve machine add outputs: this page says what goes to whom, what the anti-SSRF guard refuses and how to cut each output.

## Outgoing calls

The application used to talk to the outside world only through SMTP and Sentry. **Webhooks**
and the **weather alert** make it issue HTTP requests. The
former target an address **typed by an administrator**: the textbook
case of SSRF, using the server to reach what only it can reach (PostgreSQL's port, a cloud
metadata service at `169.254.169.254`, an administration interface
bound to the loopback). The decision and its reasons:
[ADR 0074](repo:docs/decisions/0074-deux-jobs-planifies-de-plus-webhooks-et-meteo.md).

### What the guard refuses

`OutboundGuard` decides, when the webhook is **registered** and then **at each
send** (a name that resolved to the outside yesterday may point inside
today):

| Rule | Detail |
| --- | --- |
| `https://` only | `http://` is accepted only towards an authorised network |
| Ports 443 and 8443 | any port towards an authorised network |
| No internal address | loopback (`127.0.0.0/8`, `::1`), private RFC 1918, link-local (`169.254.0.0/16`, cloud metadata; `fe80::/10`), ULA `fc00::/7`, CGNAT `100.64.0.0/10`, multicast, unspecified (`0.0.0.0`, `::`), reserved; an IPv6 address that embeds an IPv4 (`::ffff:a.b.c.d`, `::a.b.c.d`, NAT64 `64:ff9b::/96`, 6to4 `2002::/16`, Teredo `2001::/32`) is judged on the IPv4 it carries, and the local NAT64 `64:ff9b:1::/48` is refused |
| No credentials in the URL | `utilisateur:motdepasse@` refused |

The name is **resolved by the guard**, all its addresses checked, and the
connection goes to **the checked address**: the HTTP client (Vert.x) does not
resolve the name a second time. A name that answered a public address
at the check and an internal address at connection time (*DNS rebinding*) thus has
no window left. TLS still checks the certificate against the host **name**,
not against the address.

**Authorised networks.** An n8n on the same Docker network is legitimate:
`WEBHOOKS_RESEAUX_AUTORISES` lists the addresses and CIDR blocks that escape
the guard (same writing rules as `CONNEXION_PROXYS_FIABLES`; a malformed
entry prevents startup). Opening a block means opening **all** its
machines to an administrator of the application: declare the subnet of the
single target container, not `10.0.0.0/8`.

### What the receiver cannot do

- **Redirect**: a redirect is never followed (the address it names
  has not been checked); the delivery is abandoned.
- **Be read**: the response body is drained and discarded, never stored, never
  shown. The delivery log keeps only a code, a duration and a fixed
  sentence, never an exception's message, which may copy the request line
  and so a secret Slack address. A webhook is not for
  *reading* an internal service.
- **Make you wait**: name resolution 5 s, connection 5 s, then 10 s for
  the whole exchange, from send to the end of the response; past that delay the
  connection is cut, even if the receiver is still sending a few bytes.
  The send leaves from a thread of its own, never from the thread of the publication or
  solve that caused it.

### Secrets at rest

The HMAC secret of a generic webhook, the address of a Slack, Discord
or Matrix webhook (which **is** the secret: holding it is enough to write in the room) and
the token of a Telegram bot are **encrypted** in the database (AES-256-GCM, key
`WEBHOOKS_SECRET_KEY`). The SQL export does not contain them at all; the
nightly backup, which takes the whole cluster, contains only the encrypted form.
The screen never shows them again: the HMAC secret is shown once, when it is
generated; a room address appears reduced to its host. The action
log notes the **names** of the fields changed, never their value. Key
management: [`exploitation.md`](exploitation.md), in the section on environment variables.

### Cutting webhooks

`WEBHOOKS_ENABLED=false` cuts every output: nothing is queued, nothing
is sent, the test is refused; deliveries already waiting resume
when the variable goes back to `true` (within their 30 days).

No MCP tool configures a webhook: a room address is a secret,
and opening a network output from a conversation is a route for
exfiltration; see [`mcp-connexion.md`](../comprendre/mcp-connexion.md).

### The weather alert

Each morning, the active edition queries Open-Meteo (`METEO_URL`) about the
places of its open stands. What leaves: **place coordinates**,
rounded to 0.01° (about one kilometre), dates and the server's IP
address. No personal data leaves. The browser never calls the
service: the CSP (`connect-src`) has nothing to open, and the organiser's
address goes to no third party.

`METEO_URL` is set by the operator, not typed by a user: the webhook
guard **does not apply to it**, otherwise a self-hosted Open-Meteo instance
on the Docker network would be refused. Redirects
are not followed, the exchange is capped at 10 s and the response at 2 MB. An
unreadable response or a silent service is never propagated: it is noted
("unreachable since...") and said once to the administrator on the second day.
`METEO_ENABLED=false` turns the feature off for the instance.

## Remote solve: what leaves, and to whom

An instance can entrust its solves to a *worker* on another machine
([ADR 0086](repo:docs/decisions/0086-le-calcul-deporte-un-worker-sans-conteneur-et-un-crochet.md),
set up in [`exploitation.md`](exploitation.md), section "Solve on another machine"). With no configuration, nothing leaves.

| Subject | What is done |
| --- | --- |
| Direction of connections | All start from the application. The worker calls nobody |
| Worker authentication | A shared secret on each call, compared in constant time |
| Encryption | HTTPS, certificate **pinned** by its SHA-256 fingerprint. A worker without a certificate listens only on the loopback, and the application refuses to start on an `http://` address that is not one |
| What the worker receives | The problem under pseudonyms: no name, address, phone, token or free text (see [what the instance keeps](rgpd-limites-a-consigner.md#le-calcul-déporté-envoie-un-problème-pseudonymisé)) |
| What the worker keeps | Nothing on disk; one problem and its plan in memory, replaced by the next |
| The plan that comes back | Scored again by the application on its own problem; a score different from the one announced, an unknown seat or a pinned one moved, and the plan is refused |
| Worker address | The operator's, in an instance variable: the webhooks' anti-SSRF guard does not apply to it, as for `METEO_URL` |

One limit to know: the worker does not rate-limit refused calls.
Its protection is a 256-bit secret and a firewall that opens its port
only to the application.
