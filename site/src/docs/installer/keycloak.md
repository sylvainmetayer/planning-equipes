# Brancher Keycloak

Vous déclarez l'application chez Keycloak, le fournisseur que nous recommandons pour commencer : un realm dédié, un client confidentiel, l'audience qui laisse un assistant IA se connecter en OAuth, les utilisateurs, puis les variables et l'ordre de bascule qui évitent de s'enfermer dehors. Les principes (ce que fait le fournisseur, ce que fait l'application) sont dans [la connexion par fournisseur d'identité](oidc.md) ; ce guide en est la mise en œuvre pas à pas pour Keycloak.

## Créer le realm

Créez un realm réservé à l'application (**Realm settings**), distinct du realm `master` de Keycloak.

L'**émetteur** est l'URL publique du realm, de la forme `https://<hôte>/realms/<realm>`. C'est la valeur de `OIDC_AUTH_SERVER_URL`, et ce doit être **la même adresse** vue du navigateur et du conteneur de l'application : n'y mettez jamais un nom de réseau Docker interne, le fournisseur signe ses jetons avec l'adresse par laquelle on l'a joint. Deux dispositions conviennent :

- Keycloak sur un **sous-chemin du domaine de l'application** (`<PUBLIC_URL>/auth`, l'émetteur est alors `<PUBLIC_URL>/auth/realms/<realm>`) ;
- Keycloak sur un **hôte à part** (`https://auth.exemple.org/realms/<realm>`).

> **Choisissez l'émetteur une fois pour toutes.** Le compte de l'application est lié au couple émetteur + sujet : si l'adresse change, plus personne ne se connecte, administrateurs compris. Le changement passe par la procédure [Changer l'URL du fournisseur](oidc.md#changer-lurl-du-fournisseur).

Dans les réglages du realm :

- laissez l'**inscription libre fermée** (User registration désactivée) : les comptes se créent par un administrateur ;
- activez la **protection contre la force brute** (Realm settings › Security defenses › Brute force detection).

## Déclarer le client `planning-app`

Dans **Clients**, créez un client OpenID Connect nommé `planning-app` (la valeur par défaut de `OIDC_CLIENT_ID`) :

| Réglage | Valeur |
| --- | --- |
| Client authentication | activée (client **confidentiel**) |
| Flux | flux standard (Standard flow) seul |
| PKCE | méthode `S256` (Proof Key for Code Exchange Code Challenge Method) |
| Valid redirect URIs | `<PUBLIC_URL>/api/auth/oidc/login`, exacte, sans joker |
| Valid post logout redirect URIs | `<PUBLIC_URL>/login`, exacte, sans joker |

Les deux adresses sont exactes parce que le `redirect_uri` envoyé suit l'en-tête `Host` de la requête : un joker laisserait un hôte inattendu recevoir le code d'autorisation.

Dans l'onglet **Credentials**, posez un secret d'**au moins 32 caractères** (par exemple `openssl rand -base64 48`) : ce sera `OIDC_CLIENT_SECRET`, et le démarrage est refusé en dessous, l'application chiffrant l'état PKCE avec lui. Le client doit disposer des scopes `profile` et `email` (ce sont ceux que l'application demande par défaut, `OIDC_SCOPES`).

## Ouvrir l'assistant IA (MCP) en OAuth

Cette étape n'est utile que si vous voulez qu'un assistant (Claude, Le Chat) se connecte à l'instance par OAuth, sans clé d'API. Sans elle, l'assistant garde la clé ; voir [Brancher un assistant IA sur le serveur MCP](../comprendre/mcp-connexion.md).

### L'audience `planning-mcp`

L'application n'accepte à `/mcp` que les jetons qui portent l'audience `OIDC_MCP_AUDIENCE` (`planning-mcp` par défaut) : sans ce contrôle, un jeton obtenu pour n'importe quel autre client du realm ouvrirait les outils. Faites porter cette audience par les jetons :

1. Dans **Clients**, créez un client nommé `planning-mcp`. Il ne sert à personne pour se connecter : c'est la « ressource » que les jetons désignent.
2. Dans **Client scopes**, créez un scope `planning-mcp` de type `Default`, puis, dans ses **Mappers**, ajoutez un mappeur par type **Audience** : *Included Client Audience* `planning-mcp`, ajouté à l'access token.
3. Ajoutez ce scope aux **scopes par défaut du realm** (Client scopes › type `Default`, ou Realm settings), pour que les clients enregistrés plus tard le reçoivent aussi.

### L'enregistrement dynamique des clients

Claude sur le web et Le Chat s'enregistrent eux-mêmes auprès du fournisseur (enregistrement dynamique, DCR). Keycloak le ferme par défaut, par sa politique **Trusted Hosts**. Pour l'ouvrir sans le laisser grand ouvert, passez par **Clients › Client registration › Anonymous access policies** :

- **Allowed Client Scopes** : ne gardez que les scopes utiles à un assistant, dont `planning-mcp` ;
- **Trusted Hosts** : limitez les URI de redirection aux domaines des assistants que vous utilisez (`claude.ai`, `claude.com`, `chat.mistral.ai`), et activez la correspondance obligatoire des URI du client (« Client URIs Must Match ») ;
- **Max Clients** : fixez un nombre maximal de clients enregistrés ;
- **Consent Required** : gardez le consentement, pour que la personne voie ce qu'elle autorise ;
- **offline_access** : ne l'accordez pas s'il n'est pas utile, afin qu'un assistant ne garde pas une autorisation sans limite de durée.

Vérifiez ensuite qu'un client enregistré reçoit bien l'audience : connectez l'assistant, puis décodez l'access token qu'il obtient (ou regardez l'évaluation d'un client dans **Client scopes › Evaluate**) et cherchez `planning-mcp` dans `aud`. Un jeton sans elle est refusé en `401` par l'application.

Le jeton ne vaut que pour un compte actif de l'application qui a un droit d'administrateur ou d'organisateur en vigueur ; les rôles Keycloak n'y changent rien.

## Créer les utilisateurs

Un utilisateur Keycloak par personne (**Users**) :

- cochez **Email verified** : l'application ne rattache un compte à une adresse que si le fournisseur la déclare vérifiée (`OIDC_REQUIRE_EMAIL_VERIFIED=true`, la valeur par défaut) ;
- saisissez la **même adresse** que celle du compte dans Paramètres › **Comptes** de l'application ;
- posez un mot de passe (ou laissez la personne le choisir) et, si vous le souhaitez, un second facteur.

La première connexion lie le compte au couple émetteur + sujet. **Les rôles Keycloak ne donnent aucun droit dans l'application** : les droits (`ADMIN`, `ORGANISATEUR`, `RESPONSABLE_STAND`) se donnent dans Paramètres › **Comptes**.

## Basculer sans s'enfermer dehors

Respectez cet ordre :

1. **Créez d'abord votre compte `ADMIN`** dans l'application et vérifiez qu'il se connecte en local (mot de passe et code, voir [les comptes locaux](comptes-locaux.md)).
2. **Renseignez les variables** de l'instance :

   | Variable | Valeur |
   | --- | --- |
   | `OIDC_ENABLED` | `true` |
   | `OIDC_AUTH_SERVER_URL` | l'émetteur, `https://<hôte>/realms/<realm>` |
   | `OIDC_CLIENT_ID` | `planning-app` |
   | `OIDC_CLIENT_SECRET` | le secret du client, 32 caractères au moins |
   | `OIDC_FORCE_HTTPS` | `true` derrière un proxy qui termine le TLS |
   | `OIDC_MCP_AUDIENCE` | `planning-mcp` (la valeur par défaut) |
   | `ADMIN_LOCAL_SECOURS` | absent, ou `false` |

3. **Redéployez**, puis testez une connexion par Keycloak avec votre adresse : l'administration doit s'ouvrir. Au démarrage, l'application retente la découverte du fournisseur pendant `OIDC_CONNECTION_DELAY` (30 secondes par défaut), de quoi laisser Keycloak démarrer après elle.

Les comptes locaux restent ouverts : vos autres personnes peuvent continuer à se connecter comme avant pendant la transition. Le détail du compte de secours est dans [Choisir et protéger la connexion à l'administration](securite-acces-administration.md#le-compte-de-secours--ne-jamais-senfermer-dehors).

## Prévoir les ressources

- **Mémoire.** Keycloak demande environ 0,5 Gio. Bornez son tas (par exemple `JAVA_OPTS_KC_HEAP`) et la limite mémoire de son conteneur, et faites-le céder avant l'application en cas de manque : sur un petit hôte, mieux vaut que le noyau tue Keycloak, qui redémarre, que l'application en plein calcul.
- **Base.** Donnez à Keycloak sa **propre base**, distincte de celle de l'application, et sauvegardez-la : sans elle, les utilisateurs, les secrets et les clients enregistrés disparaissent avec le serveur. La sauvegarde de l'application ne la couvre pas ([Sauvegarder et restaurer](sauvegarde-et-restauration.md)).
- **Exposition.** N'exposez pas la console d'administration sur Internet. Au proxy, seuls `/realms/<realm>/` et les ressources statiques (`/resources/`) doivent être joignables ; ni `/admin/`, ni le realm `master`.

## Un exemple complet

Voici un déploiement réel, anonymisé : Keycloak 26 sur le sous-chemin `/auth` du domaine de l'application, avec sa propre petite base PostgreSQL, derrière un reverse proxy qui termine le TLS. Le domaine est `planning.exemple.org`, les secrets sont des `<PLACEHOLDER>`. Reprenez la structure, pas les valeurs.

### Keycloak et sa base (Docker Compose)

Ce fichier compose lance Keycloak et sa base. Les commentaires disent pourquoi chaque réglage est là ; trois sont à retenir : l'adresse publique (`KC_HOSTNAME`, qui signe l'émetteur), le sous-chemin (`KC_HTTP_RELATIVE_PATH`) et les limites de mémoire, qui font perdre la page de connexion avant un calcul.

```yaml
services:
  keycloak:
    image: quay.io/keycloak/keycloak:26.8.2
    restart: unless-stopped
    # `start`, pas `start --optimized` : l'image officielle n'est construite ni
    # pour PostgreSQL ni pour /auth (options de BUILD). `start` reconstruit à
    # chaque démarrage (quelques secondes de CPU) ; --optimized exige une image
    # construite avec ces options.
    command: ["start"]
    mem_limit: 768m
    # Pas de mem_reservation, et premier tué en cas d'OOM : sur un hôte partagé
    # avec l'appli, mieux vaut perdre la page de connexion qu'un calcul.
    oom_score_adj: 500
    security_opt: ["no-new-privileges:true"]
    environment:
      KC_DB: postgres
      KC_DB_URL: jdbc:postgresql://keycloak-db:5432/keycloak
      KC_DB_USERNAME: keycloak
      KC_DB_PASSWORD: <KEYCLOAK_DB_PASSWORD>
      # Sous-chemin : sans ça, émetteur, endpoints et feuilles de style sont
      # construits depuis la racine, là où le proxy route l'appli.
      KC_HTTP_RELATIVE_PATH: /auth
      # URL PUBLIQUE avec le préfixe : c'est elle qui signe l'émetteur.
      KC_HOSTNAME: https://planning.exemple.org/auth
      # TLS terminé au reverse proxy : Keycloak parle http et lit le schéma
      # dans X-Forwarded-*.
      KC_HTTP_ENABLED: "true"
      KC_PROXY_HEADERS: xforwarded
      KC_HEALTH_ENABLED: "true"
      # Tas à 50 % du conteneur (défaut de l'image : 70 %). Attention :
      # MaxHeapFreeRatio sous 40 exige MinHeapFreeRatio ≤ lui, sinon la JVM
      # refuse de démarrer.
      JAVA_OPTS_KC_HEAP: "-XX:MaxRAMPercentage=50 -XX:InitialRAMPercentage=10 -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30"
      KC_BOOTSTRAP_ADMIN_USERNAME: admin
      KC_BOOTSTRAP_ADMIN_PASSWORD: <KEYCLOAK_ADMIN_PASSWORD>
      TZ: Europe/Paris
    depends_on:
      keycloak-db:
        condition: service_healthy
    healthcheck:
      # Port de gestion 9000, préfixé lui aussi par /auth. Pas de curl dans
      # l'image : redirection bash.
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
    networks: [keycloak-internal]   # jamais sur le réseau du proxy

networks:
  keycloak-internal:
    internal: true                  # base joignable de Keycloak seul
  proxy:
    external: true                  # celui du reverse proxy
volumes:
  keycloak_data:
```

- L'application ne partage aucun réseau avec Keycloak : elle le joint par son adresse publique, la même que celle du navigateur. Sinon l'émetteur diffère et les jetons sont refusés.
- La base de Keycloak est séparée de celle de l'application (environ 40 à 80 Mio). **Sauvegardez-la** : les mots de passe et les seconds facteurs n'existent que là.

### Les variables de l'application

Une fois le realm créé, l'application n'a besoin que de ces variables. L'émetteur est l'URL publique du realm, sous-chemin compris.

```dotenv
OIDC_ENABLED=true
OIDC_AUTH_SERVER_URL=https://planning.exemple.org/auth/realms/planning
OIDC_CLIENT_ID=planning-app
OIDC_CLIENT_SECRET=<SECRET_CLIENT_48_CARACTERES>
OIDC_MCP_AUDIENCE=planning-mcp
OIDC_FORCE_HTTPS=true
ADMIN_LOCAL_SECOURS=false
```

Le secret fait au moins 32 caractères (48 ici, générés par `openssl rand -hex 24`) : l'application refuse de démarrer en dessous. Elle ne contrôle pas `OIDC_AUTH_SERVER_URL` elle-même : avec `OIDC_ENABLED=true`, c'est la découverte du fournisseur, retentée pendant `OIDC_CONNECTION_DELAY`, qui échoue si l'adresse est vide ou fausse. OIDC éteint, n'écrivez que `OIDC_ENABLED=false` : les autres variables restent absentes (ou vides, comme dans `.env.prod.example`).

### Le realm, déclaré en code (OpenTofu ou Terraform)

Un realm se clique dans la console, mais le décrire en code le rend reproductible et relisible. Cet exemple utilise le fournisseur `keycloak/keycloak` (version `~> 5.9`). Le realm ferme l'inscription libre, active la protection contre la force brute et fixe des sessions courtes ; les commentaires expliquent chaque choix.

```hcl
provider "keycloak" {
  client_id = "admin-cli"
  url       = "https://planning.exemple.org/auth"
  username  = "admin"
  password  = var.keycloak_admin_password
  # Si l'API d'admin est derrière une authentification du proxy :
  # additional_headers = { "<En-tête>" = "<jeton>" }
}

resource "keycloak_realm" "planning" {
  realm                    = "planning"
  display_name             = "Planning"
  login_with_email_allowed = true
  duplicate_emails_allowed = false
  registration_allowed     = false   # comptes créés par un administrateur
  edit_username_allowed    = false
  reset_password_allowed   = false   # sans relais SMTP
  verify_email             = false   # l'admin coche « Email verified »
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

Le client `planning-app` est confidentiel, en flux standard seul, avec PKCE `S256`. Ses adresses de redirection sont exactes, sans joker, comme dans le tableau plus haut.

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
  # EXACTES, sans joker.
  valid_redirect_uris             = ["https://planning.exemple.org/api/auth/oidc/login"]
  valid_post_logout_redirect_uris = ["https://planning.exemple.org/login"]
  web_origins                     = ["https://planning.exemple.org"]
  pkce_code_challenge_method      = "S256"
}
```

L'audience que `/mcp` exige se pose avec trois ressources. D'abord un client `planning-mcp` qui n'émet rien, puis un scope qui porte le mappeur d'audience.

```hcl
# L'audience que /mcp exige : un client qui n'émet rien…
resource "keycloak_openid_client" "mcp_resource" {
  realm_id                     = keycloak_realm.planning.id
  client_id                    = "planning-mcp"
  access_type                  = "CONFIDENTIAL"
  standard_flow_enabled        = false
  direct_access_grants_enabled = false
  service_accounts_enabled     = false
}

# … un scope qui la pose dans le jeton d'accès…
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

Ensuite, ce scope rejoint les scopes par défaut du realm, pour que les clients enregistrés plus tard le reçoivent, et plus aucun scope n'est optionnel, `offline_access` compris.

```hcl
# … et ce scope parmi les défauts du realm : les clients enregistrés
# dynamiquement, créés plus tard, l'obtiennent ainsi.
resource "keycloak_realm_default_client_scopes" "planning" {
  realm_id       = keycloak_realm.planning.id
  default_scopes = ["profile", "email", "roles", "web-origins", "acr", "basic", "planning-mcp-audience"]
}

# Aucun scope optionnel, offline_access compris.
resource "keycloak_realm_optional_client_scopes" "planning" {
  realm_id        = keycloak_realm.planning.id
  optional_scopes = []
}
```

Enfin, l'enregistrement dynamique anonyme, pour les assistants hébergés (Claude sur le web, Le Chat). Seule la politique « Max Clients Limit » se déclare en code ; la liste des hôtes de confiance se pose à part, juste après.

```hcl
# Enregistrement dynamique anonyme (clients MCP hébergés). Pas de Trusted Hosts ici.
resource "keycloak_realm_client_registration_policy" "max_clients" {
  realm_id    = keycloak_realm.planning.id
  name        = "Max Clients Limit"
  provider_id = "max-clients"
  sub_type    = "anonymous"
  config      = { "max-clients" = "20" }
}
```

**Importez la politique avant de la déclarer.** Keycloak crée « Max Clients Limit » avec le realm, réglée à 200. Si vous déclarez la ressource sans l'importer, vous ajoutez une seconde politique à côté de la première. La procédure :

1. Faites un premier `apply` sans cette ressource, ou avec une variable qui l'éteint.
2. Lisez son identifiant dans la console : realm `planning` › Clients › Client registration › Anonymous access policies. L'identifiant est dans l'URL.
3. Importez-la : `tofu import 'keycloak_realm_client_registration_policy.max_clients' 'planning/<id-max-clients>'`
4. Vérifiez que le plan ne montre qu'une mise à jour en place, jamais de création, puis faites l'`apply`.

> **Écueil : ne déclarez pas « Trusted Hosts » dans le code.** Le fournisseur Terraform/OpenTofu écrit la liste des hôtes de confiance comme **une seule chaîne** (`claude.ai##claude.com##chat.mistral.ai`), et Keycloak la lit comme un hôte unique, qui ne correspond à rien : il refuse alors tout enregistrement. Constaté sur un déploiement réel. La politique « Trusted Hosts » créée avec le realm reste donc hors du code, avec sa liste vide qui refuse tout, jusqu'à ce que vous lui donniez **trois valeurs distinctes** par la ligne de commande ou l'API d'administration.

### Poser les hôtes de confiance

La politique est un composant du realm. Avec `kcadm.sh` (l'outil en ligne de commande de Keycloak, dans l'image officielle), cherchez-la, puis donnez-lui une liste de trois valeurs :

```bash
kcadm.sh config credentials --server https://planning.exemple.org/auth \
  --realm master --user admin
kcadm.sh get components -r planning -q providerId=trusted-hosts --fields id,name,subType
kcadm.sh update components/<id-trusted-hosts> -r planning \
  -s 'config."trusted-hosts"=["claude.ai","claude.com","chat.mistral.ai"]' \
  -s 'config."host-sending-registration-request-must-match"=["false"]' \
  -s 'config."client-uris-must-match"=["true"]'
```

Cette commande n'a pas été rejouée telle quelle : sa forme exacte (guillemets des clés, valeurs en tableau) suit la syntaxe de `kcadm.sh` et le format des composants décrits dans la documentation de Keycloak sur l'enregistrement dynamique des clients et ses politiques d'accès anonyme. Relisez le résultat avec `kcadm.sh get components/<id-trusted-hosts> -r planning` : `trusted-hosts` doit porter **trois éléments**, pas un seul. Si vous préférez, la même modification se fait dans la console (Clients › Client registration › Anonymous access policies › Trusted Hosts), un hôte par ligne, ou par l'API d'administration (`PUT /admin/realms/planning/components/<id>`), depuis le réseau d'administration.

Les politiques par défaut « Consent Required », « Allowed Client Scopes » et « Allowed Protocol Mapper Types » restent en place : elles imposent un écran de consentement, limitent aux scopes par défaut et bornent les types de mappeur acceptés.

### Le reverse proxy

Deux familles d'adresses. Les premières sont **publiques**, sans authentification du proxy ni géo-filtrage : les clients MCP hébergés appellent depuis l'étranger, et l'application elle-même interroge la découverte au démarrage.

- `/auth/realms/planning/*` : découverte, clés (JWKS), autorisation, jeton, déconnexion, enregistrement dynamique ;
- `/auth/resources/*` : le CSS et le JavaScript de la page de connexion.

Les secondes ne sont **jamais publiques** : derrière l'authentification du proxy, un VPN, ou fermées.

- `/auth/admin/*` : la console et l'API d'administration ;
- `/auth/realms/master/*` : la connexion de l'administrateur ;
- tout le reste de `/auth`.

Le port 9000 (santé, métriques) n'est jamais publié. Tout ce qui n'est pas sous `/auth` va à l'application, y compris `/.well-known/oauth-protected-resource`, par où un assistant découvre où ouvrir sa connexion.

Avec nginx, chaque `location` envoie vers Keycloak, et `keycloak_proxy.conf` transmet l'hôte et le schéma d'origine, que Keycloak lit dans `X-Forwarded-*` :

```nginx
location ^~ /auth/realms/planning/ { proxy_pass http://keycloak:8080; include keycloak_proxy.conf; }
location ^~ /auth/resources/       { proxy_pass http://keycloak:8080; include keycloak_proxy.conf; }
location ^~ /auth/admin/ {
    allow 192.0.2.0/24;   # réseau d'administration
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
proxy_buffer_size 16k;           # en-têtes de Keycloak parfois gros
proxy_buffers 8 16k;
```

Avec Caddy, le même découpage tient en un seul bloc de site :

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

### Les contrôles après déploiement

Vérifiez ces points avant d'annoncer la connexion. Les derniers ont été constatés sur le déploiement réel, une fois les hôtes de confiance posés :

- la découverte publique (`/auth/realms/planning/.well-known/openid-configuration`) répond 200, avec l'émetteur exact ;
- la console et le realm `master` répondent 401 hors du réseau d'administration ;
- Keycloak refuse en 400 une `redirect_uri` étrangère ;
- l'enregistrement anonyme répond 403 tant que la liste des hôtes de confiance est vide ;
- un enregistrement dont les redirections sont sur les trois domaines répond 201 ;
- le client ainsi créé porte `planning-mcp-audience` dans ses scopes par défaut, donc ses jetons ont `aud=planning-mcp`, et le consentement est exigé ;
- 403 pour une URI hors liste, pour une bonne URI accompagnée d'une URI hors liste, et pour `claude.ai.evil.example` ;
- 403 pour une demande d'`offline_access` (politique « Allowed Client Scopes ») ;
- 20 clients anonymes au plus.

### La mémoire mesurée

Mesures sur un hôte de 3,8 Gio et 2 vCPU, partagé avec l'application :

- Keycloak 26.8.2, limité à 768 Mio avec un tas à 50 % : environ 450 Mio en régime établi, jusqu'à 563 Mio au démarrage (reconstruction de `start` comprise) ;
- sa base PostgreSQL : environ 45 Mio au repos, 80 Mio après la création du realm ;
- mémoire disponible sur l'hôte au repos : environ 2,0 Gio avant Keycloak, 1,4 Gio après. Pendant un calcul, l'application monte d'environ 0,9 Gio.

La limite de 768 Mio ne vaut que 1,4 fois le pic de démarrage, sous la règle des deux fois : c'est un compromis assumé, compensé par `oom_score_adj: 500`. En cas de manque de mémoire, Keycloak part avant le calcul.

> **Variante : un hôte dédié.** Si vous préférez Keycloak sur `auth.exemple.org` plutôt que sous `/auth`, presque tout se simplifie, et cinq choses changent :
>
> - **L'émetteur** devient `https://auth.exemple.org/realms/planning`, c'est la valeur de `OIDC_AUTH_SERVER_URL`. Les adresses du client (`valid_redirect_uris`, `valid_post_logout_redirect_uris`) restent celles de l'application, `https://planning.exemple.org/...` ;
> - **`KC_HOSTNAME`** vaut `https://auth.exemple.org`, sans chemin ;
> - **`KC_HTTP_RELATIVE_PATH`** disparaît : Keycloak sert à la racine, et la sonde de santé du compose vise `/health/ready` au lieu de `/auth/health/ready` ;
> - **le certificat** : un second nom à couvrir, `auth.exemple.org`, par un certificat propre ou multi-noms ; le fournisseur doit être joignable en HTTPS de confiance, depuis l'application comme depuis le navigateur ;
> - **les règles du proxy** : un second site pour `auth.exemple.org`, avec les mêmes règles sans le préfixe `/auth` (`/realms/planning/*` et `/resources/*` publics, `/admin/*` et `/realms/master/*` restreints, le reste en 404). Le site de l'application ne change pas.
>
> Le reste (client, audience, politiques d'enregistrement, mémoire) ne change pas. Choisissez l'adresse de l'émetteur avant la première connexion : c'est celle que l'application retiendra.

## Plus tard : un annuaire, puis un ADFS

Le fournisseur est un point d'appui qui peut changer sans toucher aux comptes de l'application, tant que les adresses restent les mêmes :

- **Fédérer un annuaire AD ou LDAP** dans Keycloak (**User federation**) : Keycloak importe ou interroge l'annuaire et continue d'émettre les jetons, l'émetteur ne change pas. Vérifiez que l'adresse de chaque utilisateur est renseignée, et marquée vérifiée.
- **Basculer sur un ADFS du client** : l'émetteur change, donc la procédure [Changer l'URL du fournisseur](oidc.md#changer-lurl-du-fournisseur) s'applique. Un ADFS n'envoie jamais `email_verified` : posez `OIDC_REQUIRE_EMAIL_VERIFIED=false` pour lui, jamais pour un fournisseur où n'importe qui enregistre une adresse (voir [les variables](oidc.md#renseigner-les-variables)).
