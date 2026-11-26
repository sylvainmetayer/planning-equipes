---
twin: comprendre/api-conventions
---

# API conventions: edition, authentication, errors

You will learn how a client designates the edition it works in, how it authenticates (session, public tokens), and how to read the server's refusals: the HTTP status, then the `code` that tells apart two `409`s of the same status. What holds for every call is here; the list of routes and schemas is in the generated specification.

Everything is under `/api`, in JSON. The specification is authoritative for the shape: [`openapi.json`](repo:docs/schema/openapi.json) in the repository, `/q/openapi` on a running instance, `/q/swagger-ui` to explore it. The API pages of this part carry only what a schema cannot say: invariants, business trade-offs, traps. The MCP server has its own door, presented in [the MCP connection](mcp-connexion.md).

## The edition, designated on every request

The reference data is partitioned by edition ("Year 2025", "Year 2026"), and nothing flows from one to the other ([ADR 0001](repo:docs/decisions/0001-cloisonnement-par-edition.md), [partitioning](cloisonnement-par-edition.md)). The client names the edition with the `X-Edition-Id` header, on all endpoints except those that work in no edition (`/api/editions`, `/api/config`, `/api/auth/*`) and the SQL dump (`/api/database/*`, test tooling), which ignores the header and carries all editions. Two tabs can therefore work on two editions at once: the designation lives in the request, never in a global state.

| Situation | Response |
| --- | --- |
| Header absent | `400`, code `EDITION_REQUISE` |
| Header naming a deleted or unknown edition | `400`, code `EDITION_INCONNUE` |
| Action that leaves the instance (publish, send an e-mail, open the staff espace, the ICS feed or the wall display) on an inactive edition | `409`, code `EDITION_INACTIVE` |

There is no fallback to a "default edition": the frontend answers the refusal by making the user choose the edition before displaying anything. Only one edition is **active** (`active` field of `GET /api/editions`, [ADR 0072](repo:docs/decisions/0072-une-seule-edition-active.md)). `PUT /api/editions/{id}/active` and `DELETE /api/editions/{id}/active` activate or deactivate it, and `GET /api/editions/{id}/activation` first says what the switch closes.

The "one solver at a time" lock stays **global to the instance**, whatever the edition ([the solve](api-calcul.md)).

The SQL dump is not a database backup. It restores the tables that describe the dataset, and nine tables deliberately stay out: the clock, the backup settings, espace access and sessions, the job queue, the action journal, among others. They describe the machine, who is entitled to what at the moment or what it was asked to do next. The journal is excluded for a reason of its own: a dump deletes what it carries, and including it would wipe the local journal at every import. The copy that covers everything is the nightly `pg_dump` ([ADR 0015](repo:docs/decisions/0015-sauvegarde-par-pg-dump-restauration-hors-application.md)).

## Reading a refusal

A deliberate refusal is a `BusinessError` (a sealed hierarchy) that [`BusinessErrorMapper`](repo:src/main/java/dev/sylvain/planning/api/BusinessErrorMapper.java) turns into a response, once for all routes. The body is `{ "message": "…" }`, a sentence written for the person, which the frontend displays as is. An ordinary exception (an `IllegalArgumentException` thrown by a library, or by accident) does not go through there: it stays a `500` and triggers the alert. That is what distinguishes "refused on purpose" from "broken".

| Status | Case | Body |
| --- | --- | --- |
| `400` | malformed request, business rule broken, body field that names nothing | `message` |
| `404` | path segment that names nothing | `message` |
| `409` | state that refuses the request (e.g. a busy solver, a publication with no recipient) | `message` |
| `409` | write based on a stale read: `code: "MODIFICATION_CONCURRENTE"` | `message`, `code`, `modifieLe` (the real date of the last write, to send back to overwrite knowingly) |
| `409` | reference data family frozen by the phase of the edition: `code: "REFERENTIEL_FIGE"` | `message`, `code`, `familles` |
| `400` / `409` | edition refusal, see above | `message`, `code` |
| `409` | session too old for a sensitive action: `RECONNEXION_REQUISE_<mode>` | `code`, `mode`, `message` |
| `429` | too many attempts (sign-in, access codes) | `Retry-After` header |

Three consequences for a client:

- **the status is not enough for a `409`**. You must read `code` (`MODIFICATION_CONCURRENTE` calls for "reload or overwrite", `REFERENTIEL_FIGE` calls for "change the phase or unblock the structure"), and, for a refusal to start a solve, the shape of the body ([the solve](api-calcul.md#un-seul-calcul-à-la-fois));
- **the variant is chosen by where the identifier came from**: a path segment that designates nothing is a `404`, a body field that designates nothing is a `400`;
- **a write that a running solve would undo is refused with `409`** with the job at issue in the body, by the service itself: a REST route and an MCP tool meet the same refusal.

The concurrency and write-warning routes are detailed in [the reference data](api-referentiels.md).

## Authentication

Almost the whole API requires a session; an unauthenticated call answers **`401`, never an HTML redirect**. The scope depends on the session's role:

| Prefix | Who opens it |
| --- | --- |
| `/api/*` | the `admin` and `organisateur` roles (the organiser sees only their editions); routes that set something for the whole instance are reserved to `admin` (`@InstanceOnly` annotation) |
| `/api/responsable/*` | the `responsable_stand` role, and this prefix only |
| `/api/rh/*` | the `rh` and `admin` roles, the edition being named in the path |
| `/mcp` | the MCP key or an OAuth sign-in, see [the MCP connection](mcp-connexion.md) |

Accounts, roles and rights are presented in [accounts, roles and access](acces-et-comptes.md). The public exceptions are deliberate and listed one by one in `application.properties`:

- the staff espace, `/api/espace-animateur/*`: the URL token is the key, and it resolves the edition itself;
- the ICS subscription, `/api/abonnements/*`, and the wall display, `/api/mural/*`: each has its dedicated token and opens only a read ([the event day and staff page](api-jour-j-et-animateurs.md));
- the session routes (`/api/auth/me`, `/api/auth/connexion`, `/api/auth/mot-de-passe`, `/api/auth/logout`), `/api/config`, `/api/branding` and `/api/mentions-legales`, which the frontend reads before it starts, sign-in page included.

![Coordinator sign-in through the built-in form](repo:docs/diagrammes/authentification.svg)

<sub>Source: [`diagrammes/authentification.puml`](repo:docs/diagrammes/authentification.puml).</sub>

The `/j_security_check` form answers `429` after five failures from the same address, including with the right password ([security](../installer/securite.md)). Sign-in through an OIDC provider and local accounts are described in [OIDC sign-in](../installer/oidc.md) and [local accounts](../installer/comptes-locaux.md).

### The MCP key

`POST /api/mcp/cle` reveals the configured key and `POST /api/mcp/cle/rotation` generates a new one, shown only once, the old one remaining valid for `recouvrementMinutes` (zero: it stops at once). Both require a recent sign-in (`AUTH_RECENT_WINDOW`, ten minutes by default): a key gives full write access and outlives the session it was copied from, so revealing it must be tied to someone present at the keyboard. A session that is too old receives the `409 RECONNEXION_REQUISE_<mode>` of the table, where `mode` says by which route to sign in again. A generated key is never read back: only its fingerprint is kept, and `POST /api/mcp/cle` then answers `409`. The context is in [ADR 0097](repo:docs/decisions/0097-mcp-par-oauth-et-rotation-de-la-cle.md).

`GET /api/mcp/prompts` serves the prompts the MCP server announces (name, description, text), so that the MCP screen can offer them to clients that cannot fetch them. It reads them instead of copying them.

## Behind a reverse proxy that terminates TLS

Quarkus builds the sign-in redirect as an absolute URL, from the scheme of the request received. Behind a proxy that terminates TLS, that request arrives in clear: without precautions, the redirect goes out as `http://`, which the browser refuses from an `https` page, and signing in is impossible.

`PROXY_ADDRESS_FORWARDING` (enabled by default) makes the `X-Forwarded-*` headers apply: scheme, host and encryption match what the visitor asked for, and the session cookie is marked `Secure`. With no proxy upstream, nothing changes. Two additions:

- `QUARKUS_HTTP_PROXY_TRUSTED_PROXIES`, to be set as soon as the origin is reachable without going through the proxy: otherwise any client announces the scheme and address of its choice;
- `QUARKUS_HTTP_PROXY_ENABLE_FORWARDED_HOST` if the proxy rewrites `Host`.
