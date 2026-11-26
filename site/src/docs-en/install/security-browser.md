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
| `Content-Security-Policy` | `planning.securite.csp` (see below) | The SPA loads no third-party script apart from the Cloudflare Web Analytics beacon: anything that would be injected into a page is refused at run time |
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

### Configuration

| Variable | Default | Use |
| --- | --- | --- |
| `CSP` | policy above | Full content security policy. **Empty = header turned off** |
| `HSTS` | `max-age=31536000; includeSubDomains` | **Empty = header turned off**. Add `preload` only after deciding that the domain will never be served in clear again: it is irreversible at browser scale |

The default CSP deliberately leaves `connect-src https:` and `img-src https:`
open: the Bugsink endpoint and the tile server are specific to each
deployment. A deployment that knows its own does better to name them in `CSP`
than to keep `https:`. Add `https://api.github.com` to it if you want
the toolbar's "new version available" indication, the only
other destination a browser contacts by itself, and only once
the administration session is confirmed
(see the new-version check in [`versioning.md`](versioning.md)).

`style-src` keeps `'unsafe-inline'` in all cases: Angular Material writes
its styles into the page.
