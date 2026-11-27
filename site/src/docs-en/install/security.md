---
twin: installer/securite
---

# Exposing the instance to the Internet without surprises

Before opening the instance to the public, go through the short list below, start the production stack and set up your reverse proxy: these three steps cover what the application cannot do for you. It applies the rest by itself (security headers, rate caps, login lockouts), and the neighbouring pages detail it so that you know what to set and why.

For an overview of what is expected of a host, before the details, see [self-hosting Planning Équipes](/heberger/).

## Before opening: the short list

- [ ] `ADMIN_PASSWORD` long, generated, specific to this deployment: the application
  **refuses to start** in production if it has been left on the example, `DB_PASSWORD`
  included;
- [ ] `SESSION_ENCRYPTION_KEY` set (≥ 16 characters) and kept;
- [ ] `DB_PASSWORD` changed;
- [ ] `PUBLIC_URL` in `https://`;
- [ ] `PLANNING_MCP_API_KEY` left empty as long as the MCP server serves
      nobody (empty, `/mcp` answers `401` to everything; set, it calls for
      `CONNEXION_PROXYS_FIABLES` along with it; see [the rate limits](securite-limites-de-debit.md#débit-du-serveur-mcp));
- [ ] `CONNEXION_PROXYS_FIABLES` set before creating a wall display
      link, behind a proxy (see [the administration login](securite-acces-administration.md#verrouiller-le-formulaire-de-connexion-de-ladmin));
- [ ] behind an OIDC provider: a named administrator account created and
      tried **before** leaving the fallback closed, `ADMIN_LOCAL_SECOURS` absent
      or `false` in normal operation, `OIDC_CLIENT_SECRET` of at least 32
      characters;
- [ ] no `REMOTE_USER_*` variable in your environment: the proxy header
      mode is removed in 4.0, startup flags them and
      ignores them;
- [ ] `LEGAL_MENTIONS` variable set: `/mentions-legales` is public, and an empty
      legal notice page amounts to no legal notice. Five
      of them (publisher, host, contact, legal basis, retention)
      **make startup fail on a public instance** (`PUBLIC_INSTANCE=true`) and
      warn otherwise: declare your instance public, and this box ticks itself;
      the other two are left to you (see the legal notice in [`exploitation.md`](exploitation.md));
- [ ] production dataset loaded, never the test fixtures.

## Starting the production stack

`docker-compose.yml` is a **development** stack: it publishes PostgreSQL
on the host, adds pgAdmin as `admin/admin` without server mode, an SMTP sink
that sends nothing, and `festival`/`admin` passwords. None of this
should exist on an exposed machine, and "we'll change the passwords"
removes neither pgAdmin nor port 5432.

`docker-compose.prod.yml` is the corresponding stack:

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d
```

What it changes:

- **a named version**: `APP_VERSION` is mandatory (a `vX.Y.Z`, never `:main` or `:latest`, see [`versioning.md`](versioning.md));
- **a single published port**, on the loopback (`127.0.0.1:8080`): the reverse
  proxy is the only way in;
- **PostgreSQL is no longer published at all**: only the internal network sees it;
- **neither pgAdmin nor Mailpit**;
- **no default value on the secrets**: `DB_PASSWORD`,
  `ADMIN_PASSWORD`, `SESSION_ENCRYPTION_KEY`, `PUBLIC_URL`, `MAIL_HOST` and
  `MAIL_FROM` make startup fail if the environment does not provide them,
  rather than letting a development credential through;
- **a backup directory** on the host (`./backup`) mounted on `BACKUP_DIR`, where the nightly task writes
  its `pg_dump`s. It is the only place where the application writes to disk, and
  these files are the complete dataset in clear: names, birth
  dates and addresses of staff members, minors included. They are treated like
  the database itself: restricted access on the host, copy off the machine
  encrypted, and never in a ticket or on a demonstration instance.
  The application exposes them through no endpoint, and they must not be served
  from the reverse proxy either.
- **two public probes**, `/q/health/live` and `/q/health/ready`, with no
  session: an external probe or an orchestrator cannot authenticate.
  They return only `UP`/`DOWN`, the name of their checks and two migration
  counters. Quarkus's datasource check is turned off for
  this reason: on failure, it copied the driver's message, so the database host
  and port, into the response. See "Knowing it is running" in [`exploitation.md`](exploitation.md).
- **port 9000 is not published**: it is the management interface, which
  serves the Prometheus metrics (`/q/metrics`) **without authentication**. Its
  only protection is being reachable only from the stack's internal network;
  the image does not declare it in `EXPOSE` so that a `docker run -P` does not
  publish it by mistake. The application port does not serve the metrics (`404`,
  `MetricsEndpointTest`), and no label carries a token, address or
  identifier; see the Metrics section of [`observabilite.md`](observabilite.md).

## What is left to the reverse proxy

The application cannot take care of these in its place, and these points are
**prerequisites** of what precedes:

| To do | Why |
| --- | --- |
| Terminate TLS and redirect all http traffic to https | HSTS and the `Secure` flag of the espace cookie only take effect on an HTTPS visit |
| Pass on the visitor's `Host` header and `X-Forwarded-Proto` | The application compares the origin of a browser request with this scheme and this host: without them, every write of the administration screen is refused with a 403 (see [a single origin](securite-navigateur.md#une-seule-origine)) |
| **Set `CONNEXION_PROXYS_FIABLES`** to the addresses of your reverse proxies (literal or CIDR blocks) | Without it, the two per-address caps ignore `X-Forwarded-For` and count all visitors behind the proxy on a single counter: it is safe, but the first attacker to come along locks everyone out. **Mandatory as soon as `/mcp` serves**: that cap counts every request, not only the failures. **Also mandatory as soon as a wall display link exists**: without it, a few random requests lock the proxy's address, and a newly connected screen, or one restarted after an application restart, shows nothing until the lock ends. `QUARKUS_HTTP_PROXY_TRUSTED_PROXIES` does not replace this setting: it decides whether the header is read, never which element is kept |
| **Make the origin unreachable other than through the proxy** (firewall, network) | Without that, `X-Forwarded-Proto` remains forgeable, and an attacker who reaches the origin directly is counted on their real address, which is correct but deprives them of the benefit of the list above |
| Rate-limit per IP address across the whole site | The application's caps are targeted (admin login, espace codes, MCP server, wall display); the rest (exports, API) has none. The solve has an optional instance quota (see the [solve quota](securite-limites-de-debit.md#quota-de-calcul)), which a per-address limit does not replace |
| Log without the URLs of the staff espace, **the ICS subscription or the wall display**, or purge those logs | The three tokens travel **in the path**: they land as is in the access logs, the subscription comes back there on every calendar sync and the wall display every minute |
| Never route port 9000 (metrics), nor publish it on the host | It has no authentication: it is protected by the network, not by a password. A scraper outside the stack goes through a tunnel or a private network, not through the public proxy |
| Reserve `/q/health/*` for the monitoring source, if known | Nothing sensitive is read there, but a probe does not have to be reachable by the whole world; the compose `healthcheck` goes through the loopback and does not depend on it |
| Do not put the site back into an index (proxy home page, sitemap, internal directory) | The application says three times that it does not want to be indexed (see [not being indexed, said three times](securite-navigateur.md#ne-pas-être-référencé-dit-trois-fois)); a link from a public page, on the other hand, gets noticed |

## HTTP server caps

| Variable | Default | What it bounds |
| --- | --- | --- |
| `MAX_BODY_SIZE` | `10M` | Maximum size of a request body. It used to size the SQL dump import, now reserved for tests: a deployment does well to lower it sharply, a YAML scenario import being the largest body expected |
| `MAX_CONNECTIONS` | `500` | Simultaneous connections accepted |
| `HTTP_IDLE_TIMEOUT` | `PT16M` | Delay after which a connection that has stayed silent is closed, which frees its place in `MAX_CONNECTIONS` |

The connection cap is what prevents exhausting the server by opening
connections without ever sending anything. `500` is wide compared with real use,
so it has no effect on legitimate traffic.

The two settings go together: an idle connection counts toward the cap
until the idle timeout closes it. Quarkus's (30 min) is
brought down to 16 min, just above the longest synchronous call of the API: a
solve launched by `POST /api/solve`, which writes nothing before it ends
(900 s by default). Whoever allows longer synchronous solves raises
`HTTP_IDLE_TIMEOUT` by as much; the reverse proxy remains the right place to cut
idle clients earlier.

The **staff CSV import** adds two bounds of its own, tighter
than `MAX_BODY_SIZE` and written in the code rather than in configuration:
1,000,000 characters and 5,000 data rows. The file is read into
memory in one piece, and a list of volunteers weighs a few tens of
kilobytes, and a cap shared with the SQL dumps would leave ten
megabytes of margin to an import that needs none of it. The file is
never written to disk: see [`rgpd.md`](rgpd.md).

These caps do not replace those of the reverse proxy, which must remain the
first line (per-IP rate limiting, per-client connection cap,
timeouts).

## Going further

| To… | Read |
| --- | --- |
| Set `CSP` and `HSTS`, understand a browser block | [Setting the browser security headers and the CSP](securite-navigateur.md) |
| Limit abuse (codes, declarations, MCP, solve quota) | [Limiting abuse: rate, lockouts and solve quota](securite-limites-de-debit.md) |
| Choose the administration door and keep the fallback closed | [Choose and protect the admin login](securite-acces-administration.md) |
| Connect your company directory | [Connect your corporate directory (OIDC)](oidc.md) |
| Know what each role opens | [Knowing what each role can call](securite-roles-et-droits.md) |
| Publish a calendar or a wall screen with no session | [Opening a calendar or a screen without opening the administration](securite-jetons-publics.md) |
| Control webhooks, weather and remote solve | [Control what leaves the instance](securite-appels-sortants.md) |
| Know what the release chain checks | [The security checks that run on every version](securite-analyses-automatiques.md) |
| Authentication and tokens on the API and MCP side | [`api-conventions.md`](../comprendre/api-conventions.md), [`mcp-connexion.md`](../comprendre/mcp-connexion.md) |
