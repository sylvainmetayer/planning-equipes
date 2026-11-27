---
twin: installer/securite-navigateur
---

# Setting the browser security headers and the CSP

The application sends security headers by itself on every response, with nothing for you to set up. You will know which ones go out, which can be configured (`CSP`, `HSTS`) and why the content policy refuses every `on*` handler: enough to tighten the policy, add `preload` or understand a block seen in a real browser.

## Browser security headers

`SecurityHeadersFilter` adds the following headers to **every** response, SPA
static files included:

| Header | Value | Why here |
| --- | --- | --- |
| `Content-Security-Policy` | `planning.securite.csp` (see below) | The SPA loads no third-party script apart from the Cloudflare Web Analytics beacon, and only when its token is set: anything that would be injected into a page is refused at run time |
| `Referrer-Policy` | `no-referrer` | The staff espace token, the ICS subscription one **and** the wall display one travel **in the URL**; without this header they leave in the `Referer` of every outgoing navigation (OpenStreetMap attribution link, link to the repository). One exception for the tiles, below |
| `X-Frame-Options` | `DENY` | Nothing is meant to be framed, and hijacking a click in a session that can rewrite the whole schedule has no counterpart |
| `Cross-Origin-Opener-Policy` | `same-origin` | Isolates the window from any `window.opener` opened from another site |
| `X-Content-Type-Options` | `nosniff` | The exports (PDF, ICS, SQL, CSV) are served with their type; a browser guessing another one gains nothing |
| `Permissions-Policy` | `geolocation=(), camera=(), microphone=(), payment=()` | None of these APIs is used: the map picker places a point with the mouse |
| `Strict-Transport-Security` | `planning.securite.hsts`, **only on an HTTPS visit** | One year, subdomains included. Absent on the local stack, which is in clear |
| `X-Robots-Tag` | `noindex, nofollow` | An internal planning tool has no business in a search engine, and the only URL a robot can reach without an account (a staff member's espace) **is** its access token. Not configurable, unlike the CSP: there is no page to index here |

### Not being indexed, said three times

`robots.txt` (at the root, copied from `src/main/webui/public/`) forbids
everything to all robots; a `<meta name="robots">` tag in `index.html`
repeats the instruction in the page; the header above repeats it on **every
response**. The three are not redundant:

- the **file** is an instruction that a robot is free to ignore, and it says
  nothing about an already known URL;
- the **tag** exists only once the page is interpreted as HTML; it
  covers neither an exported PDF, nor an ICS feed, nor an API response;
- the **header** is the only one of the three that *deindexes* an address already
  reached. That is the case that matters: an espace link circulates by e-mail,
  and it is enough for it to be pasted once on a public page to be
  visited.

### `script-src` forbids every `on*=""`, and what that forces you to turn off

No `'unsafe-inline'`, no `'unsafe-hashes'`: the browser refuses **every
`on*=""` attribute**. This is the intended stance, but an Angular
optimisation then becomes silently destructive.

*Critical CSS inlining* serves the real stylesheet as
`media="print" onload="this.media='all'"`. When the CSP blocks that handler,
**the stylesheet stays on `print`**: nothing raises an error, and everything the
extraction did not keep disappears (an icon font, for example).

`inlineCritical` is therefore **turned off**, and `scripts/check-csp-index.js` fails
the build if the produced `index.html` contains any inline handler. Neither
the build (the HTML is valid) nor the tests (they never load
`index.html`) can see the problem: a CSP violation appears only in a
real browser. It is therefore checked on the artefact.

Three deliberate exceptions:

- **the CSP stops at the threshold of `/q/*`**: Swagger UI serves inline scripts
  there that the project does not control. The other headers apply there;
- **HSTS is sent only on an HTTPS visit**: a browser would ignore it
  in clear, and setting it locally would pin `localhost` to https;
- **the map tiles escape `no-referrer`**:
  `tile.openstreetmap.org` [blocks traffic with no `Referer`](https://osm.wiki/blocked),
  the map would stay grey. `MapPicker` therefore sets
  `referrerPolicy: 'strict-origin-when-cross-origin'` on its tile layer alone. The token does not leak: this policy sends only the origin, never
  the path or the query.

### A single origin

The CORS filter is on, and it names **no** third-party origin. A request carrying an `Origin` header other than the application's address is refused with a 403 before it reaches anything, on `/mcp` as on `/api` and on the login. The application's own pages go through as before, and a client that sends no `Origin` is not affected: an MCP client outside a browser, a subscribed calendar, `curl`.

This is the `Origin` validation the MCP specification asks of an HTTP server. It does not protect against *DNS rebinding*, where the trap's origin and the requested host are the same; that is not needed here, since `/mcp` requires a key or a token in a header, which a trapped page does not have.

"The same origin" is read from the scheme and the host of the request, so behind the reverse proxy from `X-Forwarded-Proto` and `Host`, the two headers the admin login already depends on. A proxy that rewrites `Host` needs `QUARKUS_HTTP_PROXY_ENABLE_FORWARDED_HOST=true`. An instance served over https on a port other than 443 also needs `X-Forwarded-Port`.

No application variable opens a third-party origin, on purpose: do not set `QUARKUS_HTTP_CORS_ORIGINS` either, which Quarkus reads by itself, since opening one for an MCP client in a browser would open the whole administration API to it as well. `QUARKUS_HTTP_CORS_ENABLED=false` switches the filter off and restores the 4.0.0 behaviour, while you fix a proxy that does not pass these headers on.

### Configuration

| Variable | Default | Use |
| --- | --- | --- |
| `CSP` | policy above | Full content security policy. **Empty = header turned off** |
| `HSTS` | `max-age=31536000; includeSubDomains` | **Empty = header turned off**. Add `preload` only after deciding that the domain will never be served in clear again: it is irreversible at browser scale |

The default CSP names only what the application contacts by itself:

| Directive | Third-party origins | When |
| --- | --- | --- |
| `script-src` | `https://static.cloudflareinsights.com` | only if `CLOUDFLARE_WEB_ANALYTICS_TOKEN` is set |
| `connect-src` | `https://cloudflareinsights.com` | only if `CLOUDFLARE_WEB_ANALYTICS_TOKEN` is set |
| `connect-src` | the origin of `SENTRY_DSN` (`https://bugsink.example.org`) | only if `SENTRY_DSN` is set |
| `connect-src` | `https://api.github.com` | always: the toolbar's "new version available" indication, asked once the administration session is confirmed (see the new-version check in [`versioning.md`](versioning.md)) |
| `img-src` | `https:` | always: the OpenStreetMap tiles of the map and the logo of `BRANDING_LOGO_URL`, specific to each deployment |

The application adds the Cloudflare and Sentry origins at startup, from those
two variables. It also adds them to a `CSP` you write yourself, in the
`script-src` and `connect-src` directives it carries: a policy without those
directives stays as it is. To turn the new-version check off, remove
`https://api.github.com` from `connect-src`.

`style-src` keeps `'unsafe-inline'` in all cases: Angular Material writes
its styles into the page.

## Page caching

The browser must ask for the entry page again on every visit: it is the page
that names the files of the version in place. `index.html`, at the root as on
every address of the interface, leaves with `Cache-Control: no-cache`, like the
files served under a fixed name (translation catalogues, fonts, favicon,
`robots.txt`). `no-cache` does not mean "do not keep": the browser keeps its
copy and revalidates it, which costs one request answered `304` as long as
nothing has changed. The files whose name carries a fingerprint (`main-*.js`,
`chunk-*.js`) keep their long cache: a new version gives them a new name.

Without this, an update, security fix included, can leave the old interface in
place for up to a day for a visitor who came before. If your reverse proxy adds
cache headers of its own, leave the application's on these addresses.
