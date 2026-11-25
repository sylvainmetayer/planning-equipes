---
twin: installer/securite-jetons-publics
---

# Opening a calendar or a screen without opening the administration

Two links open from a URL alone, with no session: a staff member's calendar feed and the wall display of the control room. You will know exactly what each one allows, how to revoke it, and which authentication exception to declare on an access proxy without opening more than necessary. Both carry their token in the path, and each one's scope is deliberately a single route: it must not widen.

## The calendar subscription (ICS): a second token, scoped to a single document

`GET /api/abonnements/{token}/planning.ics` is the **only** route of the
application that a URL alone opens. It exists because a subscribed calendar
client carries no cookie and cannot answer an authentication challenge: without
it, the subscription would be a `401` at the first resync.

![Reading an ICS subscription and revoking it](repo:docs/diagrammes/abonnement-ics.svg)

<sub>Source: [`diagrammes/abonnement-ics.puml`](repo:docs/diagrammes/abonnement-ics.puml).</sub>

### Why one more token rather than the espace token

On its own, the espace token is good for nothing today: it lets you *request*
a code by e-mail, and that is all. Reusing it here would have made it, on this
route, a complete and lasting proof of access to the named schedule with no
second factor, and a link printed on a PDF gets passed around.

The subscription token is therefore a separate key (`animateur.abonnement_token`),
generated the same way as the other (`gen_random_uuid()`, 122 bits of randomness,
globally unique) and with an independent life cycle. See
[decision 0019](repo:docs/decisions/0019-jeton-et-chemin-dedies-pour-l-abonnement-ics.md).

### What it allows, exactly

Whoever holds a subscription token can read, indefinitely and with nothing
else, **a single document**: the published schedule of its owner, that is, their shifts,
their stands and the teammates their individual PDF already prints. This is
named data, and it must be read as such: this token says *who works where and
when*, for one person.

It allows **nothing** else. It does not open the staff espace, does not read swap
requests, availabilities or the photo directory, does not download the PDF, and
writes nowhere. The guard that resolves it is bound to a single route
(`@AbonnementTokenRequired`), and the espace token does not work on that
route, nor the reverse. `AbonnementIcsTest` checks both directions.

### Revocation

**The staff member revokes it themselves**, from their espace ("this address has
leaked, replace it"): the address is never displayed anywhere else, so its holder
is the only person in a position to know it has leaked, and reaching that button
already costs the espace token **and** the e-mail code: stronger proof than the
subscription will ever ask for. The rotation kills the old address immediately and
does not affect the espace token: the link already printed on a PDF survives.

A deleted record or a deleted edition takes its token with it: the address simply
ceases to exist (`404`). There is no "subscribed / not subscribed" state: the
token always exists, and is only useful if someone has used it.

What is missing, knowingly, is an admin-side action to revoke somebody else's
subscription. An operator who must cut off a person who has left deletes their
record, which cuts everything. Adding the button to the Staff screen is a screen
addition, not a model change.

### The token in the path, and the logs

Same flaw as the espace token, and the same consequence: it lands as is in the
reverse proxy's access logs, **and it lands there much more often**: a calendar
client resyncs on its own, several times a day, from each device. These logs
must be purged or written without those paths (see [what is left to the reverse proxy](securite.md#ce-qui-reste-à-la-charge-du-reverse-proxy)). `Referrer-Policy: no-referrer` covers the
rest: the token leaves in the `Referer` of no outgoing navigation.

### No rate limiter here, and why

`AdminLoginLimiter`, `CodeRequestLimiter` and `McpRateLimiter` bound specific
things: authentication attempts, the sending of a mail and, for the
last one, the use of a shared key that opens everything, plus the attempts to guess
it. This route does none of that: it
reads, with no side effect, on a token that opens only one document.

A cap per staff member would be **counter-productive** here: a subscription
resyncs by itself, from a phone, a computer and a tablet at
once, at a pace the application does not choose. The cap would not
tell abuse from use, and a feed cut at the wrong moment shows up as a calendar
silently frozen, exactly the symptom this feature removes.

That leaves enumeration: it is not practicable on 122 bits of randomness, an
unknown token gets only a `404` carrying a fixed sentence ("Unknown or revoked
subscription"), the same for a revoked token as for an invented one (so it
reveals nothing), and **the reverse proxy's per-IP limiting remains the
first line**, as for the exports and the rest of the API.

What **stays open**: nothing bounds the cost of a misconfigured client that
would call every minute. Each call rebuilds the published plan, as
the espace's download already does, so this is not a new class of
load, but it is the first one triggered by a machine
rather than by a human.

### The rule on the access proxy

The `/api/abonnements/` prefix **exists for this route only**, and that is its
reason for being: an access proxy that authenticates its visitors (Pangolin
and the like) needs a URL pattern that designates only the feed to except.

| What to configure | Value |
| --- | --- |
| Authentication exception | `/api/abonnements/*`, and **this prefix alone** |
| What not to do | widen the exception to `/api/espace-animateur/*`: that would open at the same stroke every route of the espace, including its only public write |

On the application side, the same exception is declared in `application.properties`
(`quarkus.http.auth.permission.abonnement-ics`), and `AuthentificationAdminTest`
checks both that it applies and that it **does not spill over** the prefix.

## The wall display: a third token, for an unattended screen

`GET /api/mural/{jeton}` is the route the control room TV reads, and
the **only** one under the `/api/mural/` prefix, exempt from admin authentication
as `/api/abonnements/` is. The reasoning is that of the ICS subscription,
applied to a screen: a TV stays on for days in a room where volunteers
pass through, and an admin session left on it, extended by the screen's own
refresh, would give write access to the whole schedule. See
[decision 0053](repo:docs/decisions/0053-affichage-mural-par-jeton-dedie.md).

### What it allows, exactly

A read, for **one** edition: the stands open today, who staffs them
(first name and initial of the surname, or full name if the link was created that way), the free
seats, the breaks with no relay and the day's consigne (an official order relayed to the whole event). Nothing else: not
the reference data, not the exports, not the staff espace, no write. No
phone number, no e-mail, no age, no reason for absence. The phone number that
the record now carries goes out only to an administration session (record,
Today screen): the wall view has no field that could receive it, and
the number of "unpublished changes" it displays is a count, never
a list of names. Creating a link from Today ("Show on the
TV"): the same link as from Settings, whole edition, initials. The token works on
no other route, and an espace or subscription token does not work on
this one: `AffichageMuralSecurityTest` checks it under the real
authentication policy.

### Creation, storage, revocation

The administrator creates the links from **Settings → Wall display**, with a
label, the full-names option and, if needed, the locations displayed
(one TV per zone). The token (256 bits of randomness) is shown **only once**, at
creation: the database keeps only its SHA-256 fingerprint, like the espace
sessions. A copy of the database therefore reopens no screen, and a lost address
is replaced with a new link. The list shows the date of the last access, to the
minute. Revoking cuts the screen at its next read; deleting the edition
takes its links with it. An unknown token and a revoked token get the same `404`,
with the same sentence. Creation and revocation are journalled; reads are
not: one per minute and per screen is not a trace.

### Printing a day as the administrator

"Print this day", on the Planning page, opens the same layout
(`/impression/:date`) with no token at all: it reads
`GET /api/affichage-mural/apercu`, **under the admin session**, like the
link management. Nothing is therefore added under `/api/mural/`, and the token's scope
remains a single route; `AffichageMuralSecurityTest` checks that the preview refuses
a request with no session, token in a header or in a parameter included.

The links travel neither in the SQL export nor in an edition's duplication:
they are accesses of this instance, not data of the dataset. Expected downside:
**importing a SQL dump deletes them all**, since the import empties the editions and
the links go with them in cascade, even for an edition that the dump recreates identically.
The links are recreated from Settings after each
import, and each TV reopens on its new address.

### Rate, cache and logs

`AffichageMuralRateLimiter` carries two guards, and neither counts
the valid reads of an address:

- **refused reads, per address.** Only a `404` (unknown or
  revoked token) counts. Above the cap, the address gets `429` with a
  `Retry-After` for any token it has not already read successfully, until one
  window after its last refusal: trying tokens costs time, and
  no longer touches the database;
- **reads, per valid link**: a wide cap, which bounds what a leaked
  address can pull, since one link may be open on several screens.

A token already served (remembered in memory by its fingerprint, never in clear)
therefore gets through even when its address is locked: noise from the same
address never cuts a screen. This is what matters **behind an undeclared
proxy** (`CONNEXION_PROXYS_FIABLES` empty, the default), where all requests
carry the proxy's address: a cap on all requests let thirty
random requests a minute, from anywhere, switch off every TV of the
event. What the proxy declaration keeps necessary: without it, such noise
locks the shared address, and a **new** screen, or the first display after a
restart that empties the memory, waits for the end of the
window. The address is read as for the other caps.

| Variable | Default | Use |
| --- | --- | --- |
| `AFFICHAGE_MURAL_MAX_REFUS` | `30` | Refused reads tolerated per address before the lock; `0` turns it off |
| `AFFICHAGE_MURAL_MAX_LECTURES_PAR_LIEN` | `120` | Reads served per link and per window; `0` turns the cap off |
| `AFFICHAGE_MURAL_FENETRE` | `PT1M` | Duration of the lock (from the last refusal) and of the per-link window |

The response carries `Cache-Control: no-store` (it names people and changes
every minute) and, like all responses, `X-Robots-Tag: noindex`. The token
travels in the path and comes back **every minute** in the reverse proxy's
access logs: same precautions as for the subscription (see [what is left to the reverse proxy](securite.md#ce-qui-reste-à-la-charge-du-reverse-proxy)). On the application side, it goes to no third party: the
`/mural/*` page does not load the Cloudflare beacon, and the error reports on both
sides replace the token of `/mural/…` and `/api/mural/…` with `<jeton>`,
like the espace's ([`observabilite.md`](observabilite.md)).

### The rule on the access proxy

| What to configure | Value |
| --- | --- |
| Authentication exception | `/api/mural/*`, and **this prefix alone**, in addition to the SPA route `/mural/*` which carries no data |
| What not to do | widen the exception to `/api/affichage-mural/*`: that is link management, reserved to the admin |

On the application side: `quarkus.http.auth.permission.affichage-mural`.
