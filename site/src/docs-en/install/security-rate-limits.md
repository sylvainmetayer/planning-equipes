---
twin: installer/securite-limites-de-debit
---

# Limiting abuse: rate, lockouts and solve quota

Every route open to the Internet carries a cap set by the application: access codes, declarations, looking up colleagues, the MCP server, plus a solve quota that you set if the machine is shared. This page says what each one counts, what can be configured and why each one counts in its own way. The counters live in memory (a single instance of the application); behind a reverse proxy, those that count per address assume you have set `CONNEXION_PROXYS_FIABLES`, which [the administration login](securite-acces-administration.md#verrouiller-le-formulaire-de-connexion-de-ladmin) describes, where the form lockout is also covered. The wall display's is in [the public tokens](securite-jetons-publics.md#débit-cache-et-journaux).

## Access code requests in the staff espace

`POST /api/espace-animateur/{jeton}/code` is the only public endpoint that
**triggers a mail send**. Without a cap, anyone holding a staff member's link
floods their mailbox with codes, exhausts the SMTP server's quota, and
resets the code's attempt counter to five on each request.

What is counted is the codes **never used**: opening the session
with the code received clears the counter. A staff member who logs in
normally, even often, therefore never reaches the cap; only
the accumulation of requests with no follow-up, which is exactly the abuse, leads there.
Beyond it, the response is `429` with a `Retry-After`.

| Variable | Default | Use |
| --- | --- | --- |
| `ESPACE_CODE_MAX_DEMANDES` | `3` | Unused codes tolerated per staff member and per window |
| `ESPACE_CODE_FENETRE` | `PT10M` | Length of the window, set to a code's validity |

The counter lives in memory (single-instance application), like the lockout
on revealing the MCP key.

## Rate of availability declarations

`POST /api/espace-animateur/{jeton}/disponibilites` is the
**first route that writes** from the espace, and the espace is open on the
Internet with a URL as its only credential. Two bounds, which do not replace each
other:

- the **volume** is bounded by the business rule: a single pending proposal per
  staff member, guaranteed by a partial unique index (`V59`): sending a thousand times
  leaves one row, and the admin never has two contradictory versions of the
  same person to arbitrate;
- the **rate** is bounded here. Without a cap, an open session writes and
  notifies at network speed. Nobody declares their availabilities twenty
  times in ten minutes; someone correcting themselves never reaches the cap.

| Variable | Default | Use |
| --- | --- | --- |
| `ESPACE_DECLARATION_MAX_ENVOIS` | `20` | Declarations tolerated per staff member and per window |
| `ESPACE_DECLARATION_FENETRE` | `PT10M` | Length of the window |

The carpool request (`POST /api/espace-animateur/{jeton}/covoiturage`)
is the second write of the collection: same cap, same time window,
but a **separate counter** (correcting yourself on one side does not exhaust the other). Its
volume is bounded the same way, by a single pending request per
staff member.

Reporting an impediment ("I won't be able to be there",
`POST /api/espace-animateur/{jeton}/signalements`) is open **the whole
edition**, collection and swap fair closed included, and each send writes to the
organisation: same cap, same window, **a third counter**, counted
before any validation so that a loop of refused reports stops
too. Its volume is bounded by a single open report per person and per
subject (partial unique index, `V111`). It writes nothing to the schedule: cancelling it,
acknowledging it or filing it are the only actions that follow.

Beyond that, `429` with a `Retry-After`, as for the codes. The count is kept
**per staff member** and not per IP address: the session already names the staff member, and
an IP is what a phone changes between two cells. The two counters
share the same arithmetic (`SlidingWindowCounter`) so that they do not drift
from each other.

Two more safeguards, on the content side: a day outside the event's dates
and an unknown game category are refused, and the declaration **writes nothing to the
reference data**: it waits for an explicit decision by the admin. A token holder
therefore cannot change the solver's input data, only
propose.

## Looking up a colleague's seats

`GET /api/espace-animateur/{jeton}/collegues/{id}/postes` feeds the choice
"their timeslot that I want in exchange". Access is **wide by design**:
the espace already gives each staff member the named list of their colleagues, and
a response says only timeslots and stands, which the printed global
schedule circulates. It is not restricted to "relevant colleagues":
a directed swap can target anyone, and a filter would empty the list
just when the staff member needs it.

What is bounded is **enumeration**: without a cap, a session chains
as many requests as there are staff members and rebuilds the whole grid, minors
included. The cap counts **different colleagues**, not requests:
looking at the same one again, reloading the page or hesitating between three costs nothing, and
someone looking for a swap never looks at forty in an hour.

| Variable | Default | Use |
| --- | --- | --- |
| `ESPACE_COLLEGUES_MAX` | `40` | Different colleagues that can be looked up per staff member and per window |
| `ESPACE_COLLEGUES_FENETRE` | `PT1H` | Length of the window |

Beyond that, `429` + `Retry-After`, counted per staff member like the declarations. A
nonexistent id counts as a real one: probing ids costs as much as reading them.
The refusal **never blocks the swap request**: the list stays empty with the
delay displayed, and the request goes out with no timeslot wanted in return.

Two more guards, which count nothing but close the route:

- **the swap fair must be open.** The rule is declared **on the route**
  (`@FoireOpenRequired`), in the form of the two neighbouring guards: you read the
  three requirements at a glance instead of looking for a check at the bottom of a
  method. The filter runs **after** authentication, so an anonymous caller
  gets their `401` without learning whether the fair is open. The
  fair's writes keep their check in the service, where the rule
  really applies, without depending on an annotated route;
  they also carry their authorisation in the `WHERE` of their
  query, the safest approach;
- **the colleague must exist in the edition**, otherwise a bare `404`. A `200 []`
  would let someone probe the existing identifiers, and an error body would
  only be readable by the one probing.

**Still open**: replacing the whole photo directory with a search as you
type. It is the only tightening that would attack the **aggregate** itself:
a session being able, within the cap, to rebuild part of the event's
named schedule, minors included.

## Rate of the MCP server

`/mcp` is the only authenticated surface whose credential is a **long-lived
shared key**, in a header, with no session or second
factor. Nothing bounded the rate at which it could be tried, nor the one at which
its holder could call, and the MCP tools launch a solve,
rewrite the reference data and read everyone's schedule.

`McpRateLimiter` counts **all** requests under `/mcp`, authenticated or
not, per address. Beyond the cap: `429` + `Retry-After`, **even with
the right key**. Counting only failures would let a stolen key call at
network speed, and a key pasted into a hosted assistant travels further
than a password.

| Variable | Default | Use |
| --- | --- | --- |
| `PLANNING_MCP_MAX_REQUESTS` | `120` | Requests tolerated per address and per window; `0` or less turns the cap off |
| `PLANNING_MCP_RATE_WINDOW` | `PT1M` | Length of the window |
| `PLANNING_MCP_TRUSTED_PROXIES` | *(the value of `CONNEXION_PROXYS_FIABLES`)* | Reverse proxies whose `X-Forwarded-For` is believed |

The window is **fixed, opened at the first request**: past the cap, it
still closes on time: a refused request does not push it back. This is the
opposite of the two lockouts (the one on refused keys, below, and the login one), whose
window runs from the last failure, and the difference comes from what each one
protects: a lockout wants one more attempt to gain nothing, a rate
cap wants a legitimate client that has hit it to know when to come back.

### The lockout on refused keys

The cap above **does not make the key hard to guess**: at 120 requests
a minute, an address has more than seven thousand attempts an hour. What
makes a key impracticable to guess is its entropy; this second guard makes
a **series of attempts cost time** rather than nothing.

`McpRateLimiter` therefore also counts **refused keys**, separately from the rate:
beyond `PLANNING_MCP_MAX_FAILURES` consecutive failures, the address gets
`429` for `PLANNING_MCP_LOCKOUT_DURATION`, otherwise it would be enough to
wait one's turn.

One exception: **a key generated from the MCP screen, and in force, passes the
lockout.** The address is not the caller: behind a NAT or an undeclared proxy,
a stranger's wrong keys used to lock the legitimate assistant out too. A
generated key carries 256 random bits, which the lockout protects from
nothing. It passes without taking a slot or clearing the series, so whoever
is guessing stays locked out. The configuration's key
(`PLANNING_MCP_API_KEY`), whose strength nobody checked, stays behind the
lockout, **even when it is right**: generate a key from the screen to get out
of it.

| Variable | Default | Use |
| --- | --- | --- |
| `PLANNING_MCP_MAX_FAILURES` | `5` | Consecutive refused keys tolerated per address; `0` or less turns the lockout off |
| `PLANNING_MCP_LOCKOUT_DURATION` | `PT10M` | Length of the block, counted from the **last** failure |

Two safeguards keep it from turning against a legitimate client:

- **a request with no key does not count.** It gets its `401` and nothing else:
  a client not yet configured probes before being set up, and locking it
  out for that would punish the one case that is not an attack;
- **a request that authenticates clears the series.** A header corrected on the
  next try therefore costs nothing.

A `403` (the admin session of a browser, which does not carry the `mcp` role) neither
counts nor clears: no key was tried there.

> **Success is read from the status, never from `context.user()`.** Quarkus
> sets an identity on the routing context even for a request it is about
> to refuse, so `user() != null` is true on `401`s too. The first
> version of this lockout used it as the success test: it cleared the series
> on every attempt and counted nothing at all.

The address used is the one that [the administration login lockout](securite-acces-administration.md#verrouiller-le-formulaire-de-connexion-de-ladmin) describes, word for word, and
the same code (`ClientAddress`) computes it: reading
`X-Forwarded-For` **from the right**, and only if the connection itself comes
from a declared proxy. `PLANNING_MCP_TRUSTED_PROXIES` takes by default the value
of `CONNEXION_PROXYS_FIABLES` (proxies are a fact of the deployment, not of an
endpoint), and an **empty** value counts as absent: a compose file that
passes the variable through unset sets it to the empty string, which the configuration's
`${A:${B:}}` fallback does not consider missing. Without this fallback in
Java, every call to `/mcp` arriving through the proxy landed on a single counter. See [`mcp-connexion.md`](../comprendre/mcp-connexion.md#borner-le-rythme) for setting the
cap according to use.

> **Behind a proxy, this list is not optional here.** The login lockout
> counts only failures, and a deployment that declared nothing
> stays usable: it takes five wrong passwords to bother someone.
> This cap counts **every request**, so without the list all calls
> arriving through the proxy land on a single counter, and an anonymous caller
> keeps the legitimate MCP client at `429` with two requests per second, with no key,
> since the filter runs before authentication. Set
> `CONNEXION_PROXYS_FIABLES` at the same time as `PLANNING_MCP_API_KEY`.

## Solve quota

A public instance (`PUBLIC_INSTANCE=true`), for example exposed with a
shared password for a demonstration, lets anyone launch solves, and a solve occupies the machine
for minutes. If that machine also carries a production, two operator guards
bound what a visitor can cost it:
`SOLVER_MAX_SOLVES_PER_HOUR` (solves accepted over a sliding 60 minutes) and
`SOLVER_MAX_QUEUED_JOBS` (queued jobs), with `SOLVER_SECONDS_LIMIT_MAX` for
the duration of each. Unlike the previous limiters, they count for
the **whole instance**, not per address: it is the machine being protected, and a
visitor who changes address does not start again from zero. The refusal is a `409` that
says when to come back, not a `429`. Off by default, and ignored on an
instance not declared public; setting and scope in
[tuning the solve](calcul-du-planning.md#borner-le-nombre-de-calculs-dune-instance-publique), the choice
in [ADR 0075](repo:docs/decisions/0075-quota-de-calcul-de-l-instance-dans-l-application.md)
and [ADR 0110](repo:docs/decisions/0110-budget-de-calcul-d-instance-file-en-memoire-quota-public.md).
