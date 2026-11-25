---
twin: installer/keycloak
---
# Connect Keycloak

You declare the application in Keycloak, the provider we recommend to start with: a dedicated realm, a confidential client, the audience that lets an AI assistant connect over OAuth, the users, then the variables and the switchover order that keep you from locking yourself out. The principles (what the provider does, what the application does) are in [sign-in through an identity provider](oidc.md); this guide is the step-by-step implementation for Keycloak.

## Create the realm

Create a realm reserved for the application (**Realm settings**), separate from Keycloak's `master` realm.

The **issuer** is the public URL of the realm, in the form `https://<host>/realms/<realm>`. It is the value of `OIDC_AUTH_SERVER_URL`, and it must be **the same address** seen from the browser and from the application container: never put an internal Docker network name there, the provider signs its tokens with the address by which it was reached. Two layouts work:

- Keycloak on a **subpath of the application's domain** (`<PUBLIC_URL>/auth`, the issuer is then `<PUBLIC_URL>/auth/realms/<realm>`);
- Keycloak on a **separate host** (`https://auth.exemple.org/realms/<realm>`).

> **Choose the issuer once and for all.** The application's account is bound to the issuer + subject pair: if the address changes, nobody can sign in any more, administrators included. The change goes through the procedure [Change the provider URL](oidc.md#changer-lurl-du-fournisseur).

In the realm settings:

- leave **open registration closed** (User registration disabled): accounts are created by an administrator;
- enable **brute force protection** (Realm settings › Security defenses › Brute force detection).

## Declare the `planning-app` client

In **Clients**, create an OpenID Connect client named `planning-app` (the default value of `OIDC_CLIENT_ID`):

| Setting | Value |
| --- | --- |
| Client authentication | enabled (**confidential** client) |
| Flow | Standard flow only |
| PKCE | method `S256` (Proof Key for Code Exchange Code Challenge Method) |
| Valid redirect URIs | `<PUBLIC_URL>/api/auth/oidc/login`, exact, no wildcard |
| Valid post logout redirect URIs | `<PUBLIC_URL>/login`, exact, no wildcard |

Both addresses are exact because the `redirect_uri` sent follows the request's `Host` header: a wildcard would let an unexpected host receive the authorisation code.

In the **Credentials** tab, set a secret of **at least 32 characters** (for example `openssl rand -base64 48`): this will be `OIDC_CLIENT_SECRET`, and startup is refused below that, since the application encrypts the PKCE state with it. The client must have the `profile` and `email` scopes (these are the ones the application requests by default, `OIDC_SCOPES`).

## Open the AI assistant (MCP) over OAuth

This step is only useful if you want an assistant (Claude, Le Chat) to connect to the instance over OAuth, without an API key. Without it, the assistant keeps the key; see [Connecting an AI assistant to the MCP server](../comprendre/mcp-connexion.md).

### The `planning-mcp` audience

The application accepts at `/mcp` only tokens that carry the `OIDC_MCP_AUDIENCE` audience (`planning-mcp` by default): without this check, a token obtained for any other client of the realm would open the tools. Make the tokens carry this audience:

1. In **Clients**, create a client named `planning-mcp`. Nobody uses it to sign in: it is the "resource" that the tokens designate.
2. In **Client scopes**, create a scope `planning-mcp` of type `Default`, then, in its **Mappers**, add a mapper of type **Audience**: *Included Client Audience* `planning-mcp`, added to the access token.
3. Add this scope to the **realm's default scopes** (Client scopes › type `Default`, or Realm settings), so that clients registered later receive it too.

### Dynamic client registration

Claude on the web and Le Chat register themselves with the provider (dynamic client registration, DCR). Keycloak closes it by default, through its **Trusted Hosts** policy. To open it without leaving it wide open, go through **Clients › Client registration › Anonymous access policies**:

- **Allowed Client Scopes**: keep only the scopes useful to an assistant, including `planning-mcp`;
- **Trusted Hosts**: limit the redirect URIs to the domains of the assistants you use (`claude.ai`, `claude.com`, `chat.mistral.ai`), and enable mandatory matching of the client's URIs ("Client URIs Must Match");
- **Max Clients**: set a maximum number of registered clients;
- **Consent Required**: keep consent, so that the person sees what they are authorising;
- **offline_access**: do not grant it if it is not useful, so that an assistant does not keep an authorisation with no time limit.

Then check that a registered client does receive the audience: connect the assistant, then decode the access token it obtains (or look at a client's evaluation in **Client scopes › Evaluate**) and look for `planning-mcp` in `aud`. A token without it is refused with a `401` by the application.

The token is valid only for an active application account that has a current administrator or organiser right; Keycloak roles change nothing about it.

## Create the users

One Keycloak user per person (**Users**):

- tick **Email verified**: the application links an account to an address only if the provider declares it verified (`OIDC_REQUIRE_EMAIL_VERIFIED=true`, the default value);
- enter the **same address** as the account's in the application's Settings › **Accounts**;
- set a password (or let the person choose it) and, if you wish, a second factor.

The first sign-in binds the account to the issuer + subject pair. **Keycloak roles grant no right in the application**: the rights (`ADMIN`, `ORGANISATEUR`, `RESPONSABLE_STAND`) are given in Settings › **Accounts**.

## Switch over without locking yourself out

Follow this order:

1. **Create your `ADMIN` account first** in the application and check that it signs in locally (password and code, see [local accounts](comptes-locaux.md)).
2. **Fill in the instance variables**:

   | Variable | Value |
   | --- | --- |
   | `OIDC_ENABLED` | `true` |
   | `OIDC_AUTH_SERVER_URL` | the issuer, `https://<host>/realms/<realm>` |
   | `OIDC_CLIENT_ID` | `planning-app` |
   | `OIDC_CLIENT_SECRET` | the client's secret, at least 32 characters |
   | `OIDC_FORCE_HTTPS` | `true` behind a proxy that terminates TLS |
   | `OIDC_MCP_AUDIENCE` | `planning-mcp` (the default value) |
   | `ADMIN_LOCAL_SECOURS` | absent, or `false` |

3. **Redeploy**, then test a sign-in through Keycloak with your address: the administration must open. At startup, the application retries the provider discovery for `OIDC_CONNECTION_DELAY` (30 seconds by default), enough to let Keycloak start after it.

Local accounts stay open: your other people can keep signing in as before during the transition. The details of the emergency account are in [Choose and protect the administration sign-in](securite-acces-administration.md#le-compte-de-secours--ne-jamais-senfermer-dehors).

## Plan the resources

- **Memory.** Keycloak needs about 0.5 GiB. Bound its heap (for example `JAVA_OPTS_KC_HEAP`) and the memory limit of its container, and make it give way before the application when memory runs short: on a small host, it is better for the kernel to kill Keycloak, which restarts, than the application in the middle of a solve.
- **Database.** Give Keycloak its **own database**, separate from the application's, and back it up: without it, the users, the secrets and the registered clients disappear with the server. The application's backup does not cover it ([Back up and restore](sauvegarde-et-restauration.md)).
- **Exposure.** Do not expose the administration console on the Internet. At the proxy, only `/realms/<realm>/` and the static resources (`/resources/`) must be reachable; neither `/admin/` nor the `master` realm.

## A complete example

Here is a real deployment, anonymised: Keycloak 26 on the `/auth` subpath of the application's domain, with its own small PostgreSQL database, behind a reverse proxy that terminates TLS. The domain is `planning.exemple.org`, the secrets are `<PLACEHOLDER>`s. Reuse the structure, not the values.

### Keycloak and its database (Docker Compose)

This compose file starts Keycloak and its database. The comments say why each setting is there; three are worth remembering: the public address (`KC_HOSTNAME`, which signs the issuer), the subpath (`KC_HTTP_RELATIVE_PATH`) and the memory limits, which lose the sign-in page before a solve.

```yaml
services:
  keycloak:
    image: quay.io/keycloak/keycloak:26.8.2
    restart: unless-stopped
    # `start`, not `start --optimized`: the official image is built neither for
    # PostgreSQL nor for /auth (BUILD options). `start` rebuilds at every
    # startup (a few seconds of CPU); --optimized requires an image built with
    # these options.
    command: ["start"]
    mem_limit: 768m
    # No mem_reservation, and first to be killed on OOM: on a host shared with
    # the app, better to lose the sign-in page than a solve.
    oom_score_adj: 500
    security_opt: ["no-new-privileges:true"]
    environment:
      KC_DB: postgres
      KC_DB_URL: jdbc:postgresql://keycloak-db:5432/keycloak
      KC_DB_USERNAME: keycloak
      KC_DB_PASSWORD: <KEYCLOAK_DB_PASSWORD>
      # Subpath: without it, issuer, endpoints and stylesheets are built from
      # the root, where the proxy routes the app.
      KC_HTTP_RELATIVE_PATH: /auth
      # PUBLIC URL with the prefix: it is what signs the issuer.
      KC_HOSTNAME: https://planning.exemple.org/auth
      # TLS terminated at the reverse proxy: Keycloak speaks http and reads the
      # scheme from X-Forwarded-*.
      KC_HTTP_ENABLED: "true"
      KC_PROXY_HEADERS: xforwarded
      KC_HEALTH_ENABLED: "true"
      # Heap at 50% of the container (image default: 70%). Beware:
      # MaxHeapFreeRatio under 40 requires MinHeapFreeRatio <= it, otherwise the
      # JVM refuses to start.
      JAVA_OPTS_KC_HEAP: "-XX:MaxRAMPercentage=50 -XX:InitialRAMPercentage=10 -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30"
      KC_BOOTSTRAP_ADMIN_USERNAME: admin
      KC_BOOTSTRAP_ADMIN_PASSWORD: <KEYCLOAK_ADMIN_PASSWORD>
      TZ: Europe/Paris
    depends_on:
      keycloak-db:
        condition: service_healthy
    healthcheck:
      # Management port 9000, also prefixed by /auth. No curl in the image:
      # bash redirection.
      test: ["CMD-SHELL", "exec 3<>/dev/tcp/127.0.0.1/9000 && echo -e 'GET /auth/health/ready HTTP/1.1\\r\\nHost: localhost\\r\\nConnection: close\\r\\n\\r\\n' >&3 && grep -q '\"UP\"' <&3"]
      interval: 15s
      timeout: 5s
      retries: 20
      start_period: 120s
    networks: [keycloak-internal, proxy]

  keycloak-db:
    image: postgres:19
    restart: unless-stopped
    mem_limit: 256m
    mem_reservation: 64m
    stop_grace_period: 60s
    security_opt: ["no-new-privileges:true"]
    environment:
      POSTGRES_DB: keycloak
      POSTGRES_USER: keycloak
      POSTGRES_PASSWORD: <KEYCLOAK_DB_PASSWORD>
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U keycloak -d keycloak"]
      interval: 5s
      timeout: 5s
      retries: 12
    volumes:
      - keycloak_data:/var/lib/postgresql
    networks: [keycloak-internal]   # never on the proxy network

networks:
  keycloak-internal:
    internal: true                  # database reachable by Keycloak alone
  proxy:
    external: true                  # the reverse proxy's network
volumes:
  keycloak_data:
```

- The application shares no network with Keycloak: it reaches it through its public address, the same as the browser's. Otherwise the issuer differs and the tokens are refused.
- Keycloak's database is separate from the application's (about 40 to 80 MiB). **Back it up**: passwords and second factors exist only there.

### The application's variables

Once the realm is created, the application needs only these variables. The issuer is the public URL of the realm, subpath included.

```dotenv
OIDC_ENABLED=true
OIDC_AUTH_SERVER_URL=https://planning.exemple.org/auth/realms/planning
OIDC_CLIENT_ID=planning-app
OIDC_CLIENT_SECRET=<SECRET_CLIENT_48_CARACTERES>
OIDC_MCP_AUDIENCE=planning-mcp
OIDC_FORCE_HTTPS=true
ADMIN_LOCAL_SECOURS=false
```

The secret is at least 32 characters (48 here, generated by `openssl rand -hex 24`): the application refuses to start below that. It does not check `OIDC_AUTH_SERVER_URL` itself: with `OIDC_ENABLED=true`, it is the provider discovery, retried for `OIDC_CONNECTION_DELAY`, that fails if the address is empty or wrong. With OIDC off, write only `OIDC_ENABLED=false`: the other variables stay absent (or empty, as in `.env.prod.example`).

### The realm, declared as code (OpenTofu or Terraform)

A realm can be clicked together in the console, but describing it as code makes it reproducible and reviewable. This example uses the `keycloak/keycloak` provider (version `~> 5.9`). The realm closes open registration, enables brute force protection and sets short sessions; the comments explain each choice.

```hcl
provider "keycloak" {
  client_id = "admin-cli"
  url       = "https://planning.exemple.org/auth"
  username  = "admin"
  password  = var.keycloak_admin_password
  # If the admin API is behind a proxy authentication:
  # additional_headers = { "<Header>" = "<token>" }
}

resource "keycloak_realm" "planning" {
  realm                    = "planning"
  display_name             = "Planning"
  login_with_email_allowed = true
  duplicate_emails_allowed = false
  registration_allowed     = false   # accounts created by an administrator
  edit_username_allowed    = false
  reset_password_allowed   = false   # without an SMTP relay
  verify_email             = false   # the admin ticks "Email verified"
  ssl_required             = "external"
  access_token_lifespan    = "5m"
  sso_session_idle_timeout = "8h"
  sso_session_max_lifespan = "12h"
  password_policy          = "length(12) and notUsername and notEmail and passwordHistory(3)"

  internationalization {
    supported_locales = ["fr", "en"]
    default_locale    = "fr"
  }

  security_defenses {
    brute_force_detection {
      permanent_lockout                = false
      max_login_failures               = 5
      wait_increment_seconds           = 60
      max_failure_wait_seconds         = 900
      failure_reset_time_seconds       = 43200
      quick_login_check_milli_seconds  = 1000
      minimum_quick_login_wait_seconds = 60
    }
  }
}
```

The `planning-app` client is confidential, with the standard flow only, with PKCE `S256`. Its redirect addresses are exact, with no wildcard, as in the table above.

```hcl
resource "keycloak_openid_client" "app" {
  realm_id                     = keycloak_realm.planning.id
  client_id                    = "planning-app"
  access_type                  = "CONFIDENTIAL"
  client_secret                = var.oidc_client_secret
  standard_flow_enabled        = true
  implicit_flow_enabled        = false
  direct_access_grants_enabled = false
  service_accounts_enabled     = false
  # EXACT, no wildcard.
  valid_redirect_uris             = ["https://planning.exemple.org/api/auth/oidc/login"]
  valid_post_logout_redirect_uris = ["https://planning.exemple.org/login"]
  web_origins                     = ["https://planning.exemple.org"]
  pkce_code_challenge_method      = "S256"
}
```

The audience that `/mcp` requires is set with three resources. First a `planning-mcp` client that issues nothing, then a scope that carries the audience mapper.

```hcl
# The audience that /mcp requires: a client that issues nothing…
resource "keycloak_openid_client" "mcp_resource" {
  realm_id                     = keycloak_realm.planning.id
  client_id                    = "planning-mcp"
  access_type                  = "CONFIDENTIAL"
  standard_flow_enabled        = false
  direct_access_grants_enabled = false
  service_accounts_enabled     = false
}

# … a scope that puts it in the access token…
resource "keycloak_openid_client_scope" "mcp_audience" {
  realm_id               = keycloak_realm.planning.id
  name                   = "planning-mcp-audience"
  include_in_token_scope = false
}

resource "keycloak_openid_audience_protocol_mapper" "mcp_audience" {
  realm_id                 = keycloak_realm.planning.id
  client_scope_id          = keycloak_openid_client_scope.mcp_audience.id
  name                     = "planning-mcp-audience"
  included_client_audience = keycloak_openid_client.mcp_resource.client_id
  add_to_access_token      = true
  add_to_id_token          = false
}
```

Next, this scope joins the realm's default scopes, so that clients registered later receive it, and no scope is optional any more, `offline_access` included.

```hcl
# … and this scope among the realm's defaults: dynamically registered
# clients, created later, get it that way.
resource "keycloak_realm_default_client_scopes" "planning" {
  realm_id       = keycloak_realm.planning.id
  default_scopes = ["profile", "email", "roles", "web-origins", "acr", "basic", "planning-mcp-audience"]
}

# No optional scope, offline_access included.
resource "keycloak_realm_optional_client_scopes" "planning" {
  realm_id        = keycloak_realm.planning.id
  optional_scopes = []
}
```

Finally, anonymous dynamic registration, for hosted assistants (Claude on the web, Le Chat). Only the "Max Clients Limit" policy is declared as code; the list of trusted hosts is set separately, right after.

```hcl
# Anonymous dynamic registration (hosted MCP clients). No Trusted Hosts here.
resource "keycloak_realm_client_registration_policy" "max_clients" {
  realm_id    = keycloak_realm.planning.id
  name        = "Max Clients Limit"
  provider_id = "max-clients"
  sub_type    = "anonymous"
  config      = { "max-clients" = "20" }
}
```

**Import the policy before declaring it.** Keycloak creates "Max Clients Limit" with the realm, set to 200. If you declare the resource without importing it, you add a second policy next to the first. The procedure:

1. Do a first `apply` without this resource, or with a variable that turns it off.
2. Read its identifier in the console: realm `planning` › Clients › Client registration › Anonymous access policies. The identifier is in the URL.
3. Import it: `tofu import 'keycloak_realm_client_registration_policy.max_clients' 'planning/<id-max-clients>'`
4. Check that the plan shows only an in-place update, never a creation, then do the `apply`.

> **Pitfall: do not declare "Trusted Hosts" in code.** The Terraform/OpenTofu provider writes the list of trusted hosts as **a single string** (`claude.ai##claude.com##chat.mistral.ai`), and Keycloak reads it as a single host, which matches nothing: it then refuses every registration. Observed on a real deployment. The "Trusted Hosts" policy created with the realm therefore stays outside the code, with its empty list that refuses everything, until you give it **three distinct values** through the command line or the administration API.

### Set the trusted hosts

The policy is a component of the realm. With `kcadm.sh` (Keycloak's command-line tool, in the official image), look it up, then give it a list of three values:

```bash
kcadm.sh config credentials --server https://planning.exemple.org/auth \
  --realm master --user admin
kcadm.sh get components -r planning -q providerId=trusted-hosts --fields id,name,subType
kcadm.sh update components/<id-trusted-hosts> -r planning \
  -s 'config."trusted-hosts"=["claude.ai","claude.com","chat.mistral.ai"]' \
  -s 'config."host-sending-registration-request-must-match"=["false"]' \
  -s 'config."client-uris-must-match"=["true"]'
```

This command was not replayed as is: its exact form (key quoting, array values) follows the syntax of `kcadm.sh` and the component format described in Keycloak's documentation on dynamic client registration and its anonymous access policies. Reread the result with `kcadm.sh get components/<id-trusted-hosts> -r planning`: `trusted-hosts` must carry **three elements**, not just one. If you prefer, the same change is made in the console (Clients › Client registration › Anonymous access policies › Trusted Hosts), one host per line, or through the administration API (`PUT /admin/realms/planning/components/<id>`), from the administration network.

The default policies "Consent Required", "Allowed Client Scopes" and "Allowed Protocol Mapper Types" stay in place: they impose a consent screen, limit to the default scopes and bound the accepted mapper types.

### The reverse proxy

Two families of addresses. The first are **public**, with no proxy authentication or geo-filtering: hosted MCP clients call from abroad, and the application itself queries the discovery at startup.

- `/auth/realms/planning/*`: discovery, keys (JWKS), authorisation, token, logout, dynamic registration;
- `/auth/resources/*`: the CSS and JavaScript of the sign-in page.

The second are **never public**: behind the proxy's authentication, a VPN, or closed.

- `/auth/admin/*`: the console and the administration API;
- `/auth/realms/master/*`: the administrator's sign-in;
- everything else under `/auth`.

Port 9000 (health, metrics) is never published. Everything not under `/auth` goes to the application, including `/.well-known/oauth-protected-resource`, by which an assistant discovers where to open its connection.

With nginx, each `location` sends to Keycloak, and `keycloak_proxy.conf` forwards the original host and scheme, which Keycloak reads in `X-Forwarded-*`:

```nginx
location ^~ /auth/realms/planning/ { proxy_pass http://keycloak:8080; include keycloak_proxy.conf; }
location ^~ /auth/resources/       { proxy_pass http://keycloak:8080; include keycloak_proxy.conf; }
location ^~ /auth/admin/ {
    allow 192.0.2.0/24;   # administration network
    deny all;
    proxy_pass http://keycloak:8080; include keycloak_proxy.conf;
}
location ^~ /auth/realms/master/ {
    allow 192.0.2.0/24;
    deny all;
    proxy_pass http://keycloak:8080; include keycloak_proxy.conf;
}
location ^~ /auth/ { return 404; }
location / { proxy_pass http://planning-app:8080; include keycloak_proxy.conf; }

# keycloak_proxy.conf
proxy_set_header Host              $host;
proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
proxy_set_header X-Forwarded-Proto $scheme;
proxy_set_header X-Forwarded-Host  $host;
proxy_set_header X-Forwarded-Port  $server_port;
proxy_buffer_size 16k;           # Keycloak headers are sometimes large
proxy_buffers 8 16k;
```

With Caddy, the same split fits in a single site block:

```caddy
planning.exemple.org {
    @kc_public path /auth/realms/planning/* /auth/resources/*
    handle @kc_public { reverse_proxy keycloak:8080 }
    @kc_admin {
        path /auth/admin/* /auth/realms/master/*
        remote_ip 192.0.2.0/24
    }
    handle @kc_admin { reverse_proxy keycloak:8080 }
    handle /auth/* { respond 404 }
    handle { reverse_proxy planning-app:8080 }
}
```

### Checks after deployment

Check these points before announcing the sign-in. The last ones were observed on the real deployment, once the trusted hosts were set:

- the public discovery (`/auth/realms/planning/.well-known/openid-configuration`) answers 200, with the exact issuer;
- the console and the `master` realm answer 401 outside the administration network;
- Keycloak refuses a foreign `redirect_uri` with a 400;
- anonymous registration answers 403 as long as the trusted hosts list is empty;
- a registration whose redirects are on the three domains answers 201;
- the client thus created carries `planning-mcp-audience` in its default scopes, so its tokens have `aud=planning-mcp`, and consent is required;
- 403 for a URI outside the list, for a good URI accompanied by a URI outside the list, and for `claude.ai.evil.example`;
- 403 for an `offline_access` request ("Allowed Client Scopes" policy);
- 20 anonymous clients at most.

### Measured memory

Measurements on a 3.8 GiB, 2 vCPU host, shared with the application:

- Keycloak 26.8.2, limited to 768 MiB with a 50% heap: about 450 MiB at steady state, up to 563 MiB at startup (the `start` rebuild included);
- its PostgreSQL database: about 45 MiB at rest, 80 MiB after the realm is created;
- memory available on the host at rest: about 2.0 GiB before Keycloak, 1.4 GiB after. During a solve, the application climbs by about 0.9 GiB.

The 768 MiB limit is only 1.4 times the startup peak, below the two-times rule: it is a deliberate compromise, offset by `oom_score_adj: 500`. When memory runs short, Keycloak goes before the solve.

> **Variant: a dedicated host.** If you prefer Keycloak on `auth.exemple.org` rather than under `/auth`, almost everything gets simpler, and five things change:
>
> - **The issuer** becomes `https://auth.exemple.org/realms/planning`, the value of `OIDC_AUTH_SERVER_URL`. The client's addresses (`valid_redirect_uris`, `valid_post_logout_redirect_uris`) stay those of the application, `https://planning.exemple.org/...`;
> - **`KC_HOSTNAME`** is `https://auth.exemple.org`, with no path;
> - **`KC_HTTP_RELATIVE_PATH`** goes away: Keycloak serves at the root, and the compose health probe targets `/health/ready` instead of `/auth/health/ready`;
> - **the certificate**: a second name to cover, `auth.exemple.org`, with its own or a multi-name certificate; the provider must be reachable over trusted HTTPS, from the application as from the browser;
> - **the proxy rules**: a second site for `auth.exemple.org`, with the same rules without the `/auth` prefix (`/realms/planning/*` and `/resources/*` public, `/admin/*` and `/realms/master/*` restricted, the rest as 404). The application's site does not change.
>
> The rest (client, audience, registration policies, memory) does not change. Choose the issuer's address before the first sign-in: it is the one the application will keep.

## Later: a directory, then an ADFS

The provider is a support point that can change without touching the application's accounts, as long as the addresses stay the same:

- **Federate an AD or LDAP directory** in Keycloak (**User federation**): Keycloak imports or queries the directory and keeps issuing the tokens, the issuer does not change. Check that each user's address is filled in, and marked verified.
- **Switch to a customer's ADFS**: the issuer changes, so the procedure [Change the provider URL](oidc.md#changer-lurl-du-fournisseur) applies. An ADFS never sends `email_verified`: set `OIDC_REQUIRE_EMAIL_VERIFIED=false` for it, never for a provider where anyone can register an address (see [the variables](oidc.md#renseigner-les-variables)).
