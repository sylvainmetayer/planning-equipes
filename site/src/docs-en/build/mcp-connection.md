---
twin: comprendre/mcp-connexion
---
# Connecting an AI assistant to the MCP server

You know how an MCP client (Claude, Le Chat, an in-house tool) authenticates to the instance, how to renew the key without touching the server, what bounds the rate and the attempts, how to get past an access proxy, and what never leaves towards the assistant. What the assistant can do once connected is described in [the MCP server tools](mcp-outils.md); an organiser who just wants to know what can be asked of it will find the essentials in [the organiser's toolkit](../regles/outils-de-l-organisateur.md).

The server answers on `POST /mcp` (streamable HTTP) and `GET /mcp/sse`, provided by `quarkus-mcp-server-http`. Almost everything the administration does is exposed; the only substantive restriction is the confidentiality of staff data.

![An MCP tool call, from the key to the service](repo:docs/diagrammes/mcp.svg)

<sub>Source: [`diagrammes/mcp.puml`](repo:docs/diagrammes/mcp.puml).</sub>

## Choosing the key or OAuth

Two doors exist, at the client's choice ([ADR 0097](repo:docs/decisions/0097-mcp-par-oauth-et-rotation-de-la-cle.md)). The **key** is presented in a configurable header (`X-MCP-Api-Key` by default, `PLANNING_MCP_API_KEY_HEADER`) or as `Authorization: Bearer`. It remains the choice of clients that cannot do OAuth and of instances behind an access proxy that consumes `Authorization`. **OAuth** is added as soon as the identity provider is configured (`OIDC_ENABLED`, see [the OIDC connection](../installer/oidc.md)).

As long as `PLANNING_MCP_API_KEY` is empty and no key has been generated, `/mcp` answers `401` to everyone: the server can empty the database and start a solve, and refusing to serve is better than an unauthenticated exposure.

The policy of `/mcp` requires the `mcp` role, which only the identity built from the key carries (`McpApiKeyAuthenticationMechanism`, `McpApiKeyIdentityProvider`). A browser admin session does not have this role and receives `403`: with an `authenticated` policy, form authentication, which reads its cookie on every path, would have let a logged-in browser reach `/mcp` without a key.

When a client sends its key in `Authorization: Bearer`, a value of three dot-separated segments is taken for an OAuth token: it does not count as a key attempt in the lockout described below, otherwise an expired token would block the address. A key is a random string, never a JWT.

## Connecting through OAuth

With the provider configured, `/mcp` is a protected resource in the sense of the MCP authorization specification: the `401` carries `WWW-Authenticate: Bearer resource_metadata=…` and `/.well-known/oauth-protected-resource` names the provider, enough for a client to discover on its own where to open the connection.

| Variable | Role | Default |
| --- | --- | --- |
| `OIDC_MCP_AUDIENCE` | Audience that the token must carry. To be declared at the provider (Keycloak audience mapper, ADFS resource identifier) | `planning-mcp` |

The token is valid only for **an active account** of the application that holds an administrator or organiser right currently in force; its roles at the provider do not count. An unknown or disabled account, or one that is only a stand manager, receives `403`.

- An **administrator** has the same tools as with the key.
- An **organiser** can designate only the editions of their rights: any other is refused as unknown and `lister_editions` does not show it. They do not have the instance tools (creating, renaming, duplicating, activating, deactivating, deleting and comparing editions, backups). The job tools (`consulter_calcul`, `arreter_calcul`, `supprimer_calcul`) are limited to their editions, and a tool with no `edition` argument never falls back to an active edition outside their rights. `McpEditionStructurelleTest` requires every tool without an edition to be `@InstanceOnlyTool` or justified.
- A **reader** (read-only right on an edition) has only the tools that declare `readOnlyHint = true` without computing (`@Computes`: simulations, previews, repairs…), on their editions; the others answer with a business refusal ([ADR 0099](repo:docs/decisions/0099-le-lecteur-voit-sans-rien-ecrire.md)).

The action journal keeps the assistant as author, as with the key.

## Renewing the key without touching the server

The MCP screen offers **Generate a new key**. The action requires an administrator **logged in within the last 10 minutes** (`AUTH_RECENT_WINDOW`, `PT10M` by default), a requirement shared with revealing the configuration key. It replaces re-entering a password, which neither an OIDC account nor a local account needs to provide. Otherwise the server answers `409` with the code `RECONNEXION_REQUISE_<mode>` and the screen offers to log in again:

| Mode | Re-login |
| --- | --- |
| `OIDC` | at the provider, called again with `prompt=login` and `max_age=0`, then back to the screen |
| `LOCAL` | login page: password and code |
| `BREAK_GLASS` | the emergency form |

The login instant is the `auth_time` of the identity token: a provider that does not send it, or that ignores `max_age` and `prompt`, does not allow re-login. For a form, it is the instant that the login noted in memory, by fingerprint of the session cookie, and a restart asks everyone to log in again.

The key is shown **only once**: only its fingerprint (SHA-256) is kept. You choose how long the old one stays valid: none (it is cut at once), 15 minutes, 1 hour or 24 hours on the screen, 7 days at most through the API (`POST /api/mcp/cle/rotation`). The screen shows the end date while it runs, and the journal keeps the action (`CLE_MCP_ROTEE`), never the key.

The `PLANNING_MCP_API_KEY` variable works as long as no key has been generated. At the first generation it becomes "the old one", so valid during the overlap, then opens nothing any more, even if kept or modified: you can remove it from the deployment. A generated key can no longer be revealed (`409` on `POST /api/mcp/cle`): if it is lost, you generate another.

## Bounding the rate

`/mcp` is the only authenticated surface whose credential is a long-lived shared key, with no session or second factor. `McpRateLimiter` counts **all** requests under `/mcp`, authenticated or not, per source address, in a fixed window opened at the first request. Past the ceiling, the answer is `429` with a `Retry-After`, **even with the right key**: a ceiling that counted only failures would let a stolen key call at network speed. The window closes on the hour, a refused request does not push it back.

| Variable | Default | Use |
| --- | --- | --- |
| `PLANNING_MCP_MAX_REQUESTS` | `120` | Requests tolerated per address and per window; `0` or less disables the ceiling |
| `PLANNING_MCP_RATE_WINDOW` | `PT1M` | Window duration, in ISO-8601 format |
| `PLANNING_MCP_TRUSTED_PROXIES` | value of `CONNEXION_PROXYS_FIABLES` | Reverse proxies whose `X-Forwarded-For` is believed: addresses or CIDR blocks separated by commas |

A hundred and twenty requests per minute are enough for an assistant driven by a person; a script that walks through the reference data exceeds them. Raise the ceiling then, and disable it only if `/mcp` is reachable from the machine itself alone. `PLANNING_MCP_TRUSTED_PROXIES` takes the login lockout's list by default, proxies being a fact of the deployment; an empty value counts as absent, which is what compose files pass on. Why the header is read from the right is explained in [the rate limits](../installer/securite-limites-de-debit.md#débit-du-serveur-mcp).

> **Behind a proxy, fill in `CONNEXION_PROXYS_FIABLES` at the same time as the key.** Without it, all calls that arrive through the proxy fall on a single counter, and an anonymous caller (the filter runs before authentication) keeps your MCP client at `429` there with two requests per second.

This ceiling does not replace the reverse proxy's per-IP limit, the first line for the rest of the application.

## Locking out after refused keys

The ceiling does not limit guessing: 120 requests per minute make more than seven thousand attempts per hour. The key's entropy remains its protection, and this second guard merely makes a series of attempts cost time. Beyond `PLANNING_MCP_MAX_FAILURES` consecutive refused keys, the address receives `429` for `PLANNING_MCP_LOCKOUT_DURATION`, even with the right key, otherwise it would be enough to wait one's turn. The duration runs from the **last** failure: retrying during the block gains nothing.

| Variable | Default | Use |
| --- | --- | --- |
| `PLANNING_MCP_MAX_FAILURES` | `5` | Consecutive refused keys tolerated per address; `0` or less disables the lockout |
| `PLANNING_MCP_LOCKOUT_DURATION` | `PT10M` | Duration of the block |

- **Configuring a client does not lock.** A request with no key at all receives its `401` without counting anything, and a request that authenticates clears the series. Only a key presented and refused counts, which includes the right key of a header required by `PLANNING_MCP_REQUIRED_HEADERS`.
- **An attempt is counted before it is made.** A request that presents a key reserves its failure slot before authentication and gives it back as soon as the status is written. A burst of keys in parallel therefore never exceeds `PLANNING_MCP_MAX_FAILURES` attempts, whereas counting the `401` afterwards let through as many as the burst contained. As long as these slots are taken, the next request receives `429` with a `Retry-After` of one second.

## Getting past an access proxy

`PLANNING_MCP_REQUIRED_HEADERS` requires `Name=value` pairs **in addition** to the key, by exact constant-time comparison. It is defence in depth that protects the origin if it stays reachable without going through the proxy; it is optional and empty by default. Two traps:

- list only headers that the proxy **forwards**: a proxy that consumes them and removes them would make every request fail;
- an entry with no `=`, or with an empty value, is **never** satisfied (fail closed, as for the key): it does not let you require the mere presence of a header.

On the client side, prefer the dedicated header to `Authorization: Bearer`: an access proxy often consumes `Authorization` for its own account, and the key would never reach the application.

The interface's MCP page detects the proxy through the `X-Pangolin` response header. Pangolin does not add it itself, its custom headers setting touching only the request to the backend. `PangolinHeaderFilter` therefore returns on the response any `X-Pangolin` received on the request: declaring it on the proxy side is enough.

## Connecting from a free plan

The MCP connector is paid at most consumer assistants. What sets free plans apart is **what the client can present as authentication**: a client that sends neither a free header nor `Authorization: Bearer` cannot carry the key. These plans change fast, so re-read this table before relying on it (state as of September 2026).

| Client | MCP connector on the free plan | What it presents | Usable with the key |
| --- | --- | --- | --- |
| Le Chat (Mistral) | yes | free header, Bearer, Basic, OAuth 2.1 | yes, in the dedicated header |
| Claude on the web | yes, a single connector | OAuth or nothing, no free header | no, without OAuth configured |
| Claude Desktop | yes | free header, through the `mcp-remote` bridge | yes, with the configuration the MCP page displays |
| VS Code with Copilot Free | yes | free header | yes |
| ChatGPT | no | | no |

A **hosted client** (Le Chat, Claude on the web) opens the connection from its servers: the instance must be reachable from the public Internet, the situation that `PLANNING_MCP_REQUIRED_HEADERS` hardens. It has no stable address, so a proxy that filters by country, by range or by SSO blocks it: open `/mcp` with a rule on the path, not on the caller, and let the key stand guard. A **local client** (Claude Desktop with `mcp-remote`, VS Code) calls from the workstation: an instance open only on the organisation's network remains reachable, at the price of one installation per person and a Node for the bridge. A usage-billed API key, put in a free client, is a third route that is often dismissed by confusing it with a subscription: an organisation key serves everyone.

## What never leaves

**A staff member's last name, first name and date of birth never leave over MCP.** What leaves is the id, the adult or minor status and under-16 status, derived from the date but never the date, and the non-identifying scheduling attributes.

The id says nothing about the person, structurally: the application assigns it (`A1`, `A2`… within each edition, [ADR 0050](repo:docs/decisions/0050-identifiants-generes-par-edition.md)), never a caller or an import from the name, otherwise `marie.dupont` would make the person stand out in every response. The creation tools (`creer_animateur`, `creer_stand`, `creer_emplacement`, `creer_typologie`, `creer_contrainte_ad_hoc`, `creer_edition`, with or without `source`) therefore take no id and return the one they assigned. A stand, a game category or a location is created with an optional readable `code`, and the tools that cite a game category or a location accept the id as well as the code.

Four mechanisms hold the rule:

1. **Views, never the domain objects.** Each tool returns a dedicated `record` with no accessor to a personal field.
2. **Anonymised breach messages.** `ViolationFormatter` writes "First name Last name (id)" for the web interface, `AnonymisationViolations` rewrites it as "animateur id" and removes the reason of an ad hoc constraint before any MCP output. It starts from the edition's records: the exact label of each known staff member is replaced, whatever the shape of the name (one word, an initial, a digit). Recognition by shape comes only afterwards, for what the reference data no longer knows, because a saved row can outlive the record it names. Its worst case is a damaged label, never a name that leaks; a known stand whose name has this shape is spared.
3. **Free texts leave as "filled in or not".** The reason for a swap request, the comment of a declaration, the organisation's answer, the reason for a lock or an ad hoc constraint: this is where a name or a medical appointment comes back ("I'm covering for Marie D."), and no filter tells it apart from a harmless sentence. The views carry `motifRenseigne`, `commentaireRenseigne`, `raisonRenseignee`. Writing stays open: refusing a request or a declaration always takes the comment that the staff member will read. The closing reason of a stand leaves as is, it describes a stand.
4. **A structural test.** `McpConfidentialiteStructurelleTest` walks through all the `@Tool` by reflection and fails if one exposes `Animateur` or a field `prenom`, `dateNaissance`, `email`, `accessToken`, `commentaire`, `urgenceNom`, `urgenceTelephone`, `representantNom`, `representantTelephone`, `commentaireAdmin` or `raison`, including through generics.

Two consequences on the write side. `modifier_animateur` **merges** instead of replacing, unlike the REST `PUT`: an assistant that cannot read last name, first name and date of birth cannot send them back, and a full replacement would erase them. And all the tools that create or modify a staff member, a stand or a timeslot go through the same `write*` of `ReferenceDataService` as the REST resources: it re-reads the record before writing, notes the fields that moved for the journal and returns the consistency warnings. These warnings cross as **codes** (`INDISPONIBILITE_HORS_EVENEMENT`, `MINEUR_PENDANT_EVENEMENT`…), never as sentences, because the message of a warning about a minor dates their coming of age, hence their date of birth shifted by eighteen years. `LayeringStructuralTest` forbids a bare `create*` or `update*` outside the facade.
