# Authentification par Keycloak (OIDC)

Toute connexion passe par un fournisseur d'identité — Keycloak : les
administrateurs, l'espace animateur et les clients MCP en OAuth2. Keycloak dit
**qui** se connecte (une adresse vérifiée, un second facteur pour les
administrateurs) et porte trois rôles globaux ; l'application garde pour elle
les autorisations fines, par édition et datées.

Keycloak est **obligatoire en production** : `OIDC_ENABLED` vaut `true` par
défaut, et l'ancien compte `admin` unique ne survit que comme **compte de
secours**, fermé tant qu'on ne l'ouvre pas explicitement. Le pourquoi de ce
choix — et des options écartées — est dans
[`decisions/0054-keycloak-obligatoire-comptes-nominatifs.md`](decisions/0054-keycloak-obligatoire-comptes-nominatifs.md).

| Surface | Comment on y entre |
| --- | --- |
| Administration (`/api/*`, `/q/openapi`) | Compte nominatif du realm portant le rôle `admin`, **second facteur obligatoire**, imposé par le realm |
| Espace animateur | Le lien (jeton de la fiche) **et** une session Keycloak dont l'adresse **vérifiée** est celle de la fiche, portant le rôle `animateur`. Plus de code à six chiffres envoyé par l'application |
| Serveur MCP (`/mcp`) | Clé API (inchangée) **ou** jeton OAuth2 d'audience `planning-mcp` portant le rôle `mcp` |
| Compte de secours | `admin` / `ADMIN_PASSWORD`, **fermé par défaut** (`ADMIN_SECOURS_ENABLED=false`) — voir plus bas |

## Un compte est une personne, pas une fiche

C'est la décision structurante, et elle n'était pas entièrement libre.

Keycloak refuse de combiner deux options : *autoriser les doublons d'adresse
e-mail* et *permettre la connexion par e-mail*. Comme se connecter avec son
adresse est l'exigence, les adresses sont uniques dans le realm — et « un compte
par (édition, fiche) » n'est pas réalisable sans renoncer à la connexion par
e-mail. Il en découle :

- un animateur présent sur deux éditions a **deux fiches et un seul compte** ;
  il garde son mot de passe, son passkey et son historique d'une année sur
  l'autre ;
- **dupliquer une édition ne crée aucun compte** — ils existent déjà ;
- le cloisonnement par édition reste **entièrement côté application**. Le jeton
  présent dans l'URL de l'espace désigne la fiche, donc l'édition ; le compte
  Keycloak dit seulement **qui frappe à la porte**.

Un animateur correctement connecté qui ouvre le lien d'un collègue n'obtient
donc rien : l'adresse attestée doit être celle de la fiche que le jeton désigne.
Sans session Keycloak correspondante, l'espace répond `401` avec « Authentification
requise : connectez-vous avec votre compte. ».

### L'adresse doit être vérifiée, et figurer dans le jeton

Le principal d'une session OIDC **est l'adresse e-mail**
(`quarkus.oidc.token.principal-claim=email`). Les jetons doivent donc porter
`email` **et** `email_verified` — ce que fait le scope `email` que Keycloak
crée lui-même et attache par défaut à tout client. Une session dont le jeton
porte `email_verified: false` n'ouvre aucun espace : Keycloak accepte volontiers
un compte dont personne n'a confirmé l'adresse (import en masse, faute de frappe
d'un administrateur), et l'accepter ici donnerait l'espace de quelqu'un à qui
saurait créer un tel compte.

Un compte fraîchement provisionné porte `email_verified: false` jusqu'à ce que
son invitation soit allée au bout. La connexion réussit, puis l'espace refuse :
ce n'est pas une panne, c'est l'invitation qui attend.

## Les rôles, et ce qu'ils ouvrent

Ce sont des **rôles de realm**. L'application les lit dans
`realm_access.roles` du **jeton d'accès** et les fait correspondre à ses
politiques HTTP.

| Rôle | Ouvre | Second facteur |
| --- | --- | --- |
| `admin` | `/api/*`, `/q/openapi` | **Obligatoire**, quelle que soit la méthode de connexion |
| `animateur` | L'espace de sa propre fiche, et rien d'autre | Au choix de la personne (un passkey vaut déjà deux facteurs) |
| `mcp` | `/mcp` uniquement, jamais `/api` | Sans objet pour le client automatisé |
| `user` | **Rien** | — |

`/mcp` n'accepte que `mcp`, jamais `admin` : porter le rôle d'administration
n'est pas porter celui du serveur MCP. Un `403` sur `/api/*` veut dire
« connecté, mais pas administrateur ».

### `user` n'ouvre rien, et c'est le point

Keycloak distribue `user` comme rôle ordinaire de « cette personne existe ».
Bâtir l'accès animateur dessus aurait été commode et faux : le jour où un rôle
nouveau est ajouté, ses porteurs arriveraient avec un espace que personne ne
leur a accordé — parce que la condition n'était pas « être animateur » mais
« être connecté ». L'espace exige donc un rôle **dédié**, `animateur`
(`OIDC_ANIMATEUR_ROLE`), et un compte qui ne porte que `user` se connecte sans
erreur et n'atteint qu'une page qui le lui dit.

Trois gardes tiennent la règle plutôt que la bonne volonté :
`RealmPlanningStructuralTest` refuse `user` dans toute politique
`roles-allowed` et comme rôle animateur ; la variable Terraform
`role_animateur` refuse `user`, `admin` et `mcp` ; le playbook Ansible refuse
`user` comme privilège.

### Ce que le realm ne porte pas

Keycloak dit qui ; il ne dit pas **sur quelle édition, pour quel stand, jusqu'à
quand**. Ces autorisations-là vivent dans la base de l'application :

| Table | Rôle |
| --- | --- |
| `compte` | Une ligne par personne, créée à sa **première connexion**. Désactiver un compte **dans l'application** ferme toutes ses portes, quel que soit l'état de son compte Keycloak |
| `habilitation` | Un rôle métier (`RH`, `RESPONSABLE_STAND`) sur une édition ou sur toutes, avec une date d'expiration |
| `habilitation_stand` | Les stands d'un responsable de stand |

Elles se gèrent par `/api/comptes` (écran d'administration). Les poser dans le
realm aurait demandé un rôle Keycloak par édition et par stand, et une
expiration que Keycloak ne sait pas porter sur un rôle.

## Le flow de connexion

L'adresse d'abord, la méthode ensuite, et le second facteur des
administrateurs après **n'importe quelle** méthode :

```
browser-planning
├── auth-cookie                                 ALTERNATIVE   session déjà ouverte
├── identity-provider-redirector                ALTERNATIVE   Google, un annuaire…
└── browser-planning-forms                      ALTERNATIVE
    ├── auth-username-form                      REQUIRED      l'adresse, seule
    ├── browser-planning-methodes               REQUIRED
    │   ├── webauthn-authenticator-passwordless ALTERNATIVE   passkey
    │   ├── browser-planning-code-email         ALTERNATIVE
    │   │   └── planning-code-email             REQUIRED      code à six chiffres par e-mail
    │   └── auth-password-form                  ALTERNATIVE   mot de passe
    └── browser-planning-otp-admin              CONDITIONAL
        ├── conditional-user-role (admin)       REQUIRED
        └── auth-otp-form                       REQUIRED      TOTP
```

C'est l'arbre du realm de développement
([`docker/keycloak/realm-planning.json`](../docker/keycloak/realm-planning.json))
**et** celui de la production ([`terraform/keycloak/main.tf`](../terraform/keycloak/main.tf)),
nœud pour nœud : `TerraformKeycloakStructuralTest` reconstruit l'arbre des deux
côtés et les compare ligne à ligne.

Demander l'adresse **seule** est ce qui rend le reste possible : tant que
Keycloak réclame adresse et mot de passe d'un bloc, il ne peut rien offrir
d'autre, faute de savoir à qui il parle. Keycloak propose ensuite la méthode
dont ce compte dispose, les autres restant derrière « Essayer une autre
méthode » ; un compte sans mot de passe ni passkey reçoit d'emblée le code par
e-mail.

> ⚠️ **Le sous-flux du second facteur pend de `browser-planning-forms`, après
> les méthodes — nulle part ailleurs.** Au premier niveau, il se trouve parmi
> les alternatives du flux **avant que quiconque se soit identifié** : sa
> condition porte sur le rôle de la personne, elle ne s'évalue jamais, et la
> connexion réussit sans le facteur qu'on croyait imposer. Sous le seul mot de
> passe (la structure d'une première version), il laisse un administrateur
> entrer par le **code e-mail** — un seul facteur, la boîte aux lettres — sans
> TOTP. Dans les deux cas rien n'échoue, rien n'est journalisé, et le realm a
> l'air juste dans la console.

Conséquence assumée : un administrateur qui se connecte par passkey se voit
**aussi** demander son TOTP. Le passkey vaut déjà deux facteurs ; le redemander
coûte six chiffres à une poignée de personnes, et c'est le prix d'une règle
sans exception à auditer.

**Le flux est écrit en entier, jamais copié.** `copyFrom: browser` (Ansible) ou
`copy_from` (Terraform) semblent plus sûrs — ils reprennent la chaîne native
sans risquer d'en perdre un maillon — mais ne donnent aucun moyen de viser
l'intérieur du sous-flux `forms` de la copie : c'est exactement ainsi que le
second facteur s'est retrouvé au premier niveau, dans une version antérieure du
playbook, jouée contre un vrai serveur.

### Le code par e-mail : la seule méthode qui demande une image

Keycloak 26 n'a pas d'authentificateur qui envoie un code à la connexion — le
mot de passe, le TOTP, les passkeys et les fournisseurs externes sont natifs,
celui-là seul ne l'est pas. Le module
[`keycloak/code-email/`](../keycloak/code-email) produit un JAR que
l'image du dépôt dépose dans `/opt/keycloak/providers` ; le flow le nomme
`planning-code-email`. Un Keycloak **qui ne porte pas ce JAR** fait échouer le
`terraform apply` sur un fournisseur inconnu — le bon échec : une méthode
affichée et morte à l'usage serait pire.

Un code à six chiffres plutôt que le « lien magique » des exemples : un lien dans
une boîte aux lettres est une créance au porteur, qui survit à un transfert. Le
code est lié à la session qui l'a demandé, valable dix minutes, brûlé au
cinquième essai faux (`code_email_validity_seconds`,
`code_email_max_attempts` en Terraform). L'authentificateur n'accepte que
`REQUIRED` : le choix entre méthodes se fait un niveau plus haut, d'où le
sous-flux à une seule étape.

Le module est **hors du réacteur Maven** de l'application : c'est un greffon
chargé par un autre programme, compilé pour son JDK (21) et contre ses SPI.
Sa `keycloak.version` et l'image de base du `Dockerfile` bougent **ensemble** ;
`KeycloakThemeStructuralTest` refuse qu'elles divergent.

> ⚠️ **Limites connues de l'extension.** Le code vit dans la session
> d'authentification, pas dans un stockage partagé, et le compteur d'essais est
> par session : c'est juste pour un serveur, pas pour plusieurs répliques
> derrière un répartiteur.

## Compte de secours

Le formulaire `admin` / `ADMIN_PASSWORD` d'avant Keycloak reste dans le code,
comme **porte de secours** quand le fournisseur d'identité ne répond plus.

| `OIDC_ENABLED` | `ADMIN_SECOURS_ENABLED` | Effet |
| --- | --- | --- |
| `true` | `false` | **Production normale.** `POST /j_security_check` répond `409` |
| `true` | `true` | Les deux portes : le temps d'une bascule ou d'un incident |
| `false` | `true` | Secours seul : l'administration par `admin`, **l'espace animateur fermé** (`401`) et `/mcp` à la seule clé API |
| `false` | `false` | **Démarrage refusé** : aucune porte |

Ouvrir le secours : `ADMIN_SECOURS_ENABLED=true` dans le `.env.prod`, redémarrer,
se connecter sur `/login`. **Le refermer** dès que Keycloak répond, de la même
façon. Le compte de secours n'a pas de second facteur : c'est précisément pour
cela qu'il est fermé par défaut, et que `ADMIN_PASSWORD` reste **obligatoire**
même fermé — la porte doit exister le jour où il faut l'ouvrir, avec un mot de
passe long, propre au déploiement et connu d'une personne joignable, plutôt
qu'inventé dans l'urgence.

En développement, le profil `%dev` inverse les défauts : OIDC éteint, secours
ouvert — `./mvnw quarkus:dev` marche sans Keycloak, avec `admin` et le mot de
passe de développement.

## Amorçage local

Le `docker-compose.yml` embarque Keycloak et sa base. Le realm `planning` est
**importé depuis un fichier versionné**, ce qui rend l'état du fournisseur
d'identité reproductible au lieu d'être le résidu des clics de la veille.

### La ligne `/etc/hosts`, et pourquoi elle n'est pas négociable

Un jeton porte l'émetteur qui l'a signé, et Quarkus refuse un jeton dont
l'émetteur ne correspond pas à l'autorité qu'il a découverte. Il faut donc **une
seule URL**, valable depuis le navigateur *et* depuis le conteneur applicatif.
D'où le port 8081 à l'intérieur comme à l'extérieur du conteneur Keycloak, le
nom de service `keycloak` — et, côté machine hôte :

```bash
echo '127.0.0.1 keycloak' | sudo tee -a /etc/hosts
```

Sans elle, `http://keycloak:8081` ne se résout pas dans le navigateur, et la
connexion échoue au tout premier saut. Publier Keycloak sur un port différent
de celui qu'il écoute donnerait deux URL pour un seul serveur, donc des jetons
systématiquement rejetés.

### Démarrer

```bash
# La pile complète : l'application en mode production, Keycloak allumé
docker compose --profile app up --build

# Ou : quarkus:dev sur la machine, Keycloak, sa base et Mailpit en conteneurs
docker compose up keycloak mailpit
OIDC_ENABLED=true ./mvnw quarkus:dev
```

Le profil `app` lance l'image comme en production : OIDC allumé, secours
**fermé** (`ADMIN_SECOURS_ENABLED=true` pour l'essayer). `quarkus:dev`, lui,
démarre sans Keycloak tant qu'on ne pose pas `OIDC_ENABLED=true`. Dans les deux
cas, les secrets des clients prennent pour défauts ceux du realm versionné :
aucune variable de plus, et `RealmPlanningStructuralTest` tient les trois
fichiers ensemble — un secret changé dans le realm sans l'être dans
`application.properties` ou `docker-compose.yml` casse le build plutôt que la
connexion.

- Application : <http://localhost:8080>
- Keycloak : <http://keycloak:8081> (console `admin` / `dev-keycloak-Wq2fT7xM`)
- Mailpit : <http://localhost:8025> — invitations, réinitialisations et codes
  de connexion envoyés par Keycloak y atterrissent

Le conteneur Keycloak n'est pas l'image officielle : c'est celle du dépôt,
[`docker/keycloak/Dockerfile`](../docker/keycloak/Dockerfile). Le premier `up`
la construit — quelques minutes, le temps d'un module Maven — et les suivants la
réutilisent ; `--build` la refait après une retouche de `keycloak/code-email/`
ou des thèmes. Le dossier `themes/planning/brand/` est monté depuis le dépôt et
se recharge sans reconstruction.

Le realm n'est importé que sur une base **vide** : pour rejouer le fichier après
l'avoir modifié, `docker compose down -v` puis `up`. C'est aussi le remède quand
Keycloak répond `invalid_client_credentials` à l'échange du code : le volume
garde un realm plus ancien, dont le secret n'est plus celui que l'application
présente.

### Comptes de démonstration

| Compte | Mot de passe | Rôles | Note |
| --- | --- | --- | --- |
| `admin@planning.local` | `dev-admin-Zc6qL1yV` | `admin` | TOTP pré-configuré, secret `planningequipesotp01` |
| `marie.dupont@example.org` | `dev-animateur-Pn3vK9tR` | `user`, `animateur` | |
| `paul.martin@example.org` | `dev-animateur-Pn3vK9tR` | `user`, `animateur` | |
| `sans.role@example.org` | `dev-animateur-Pn3vK9tR` | `user` | N'ouvre **rien** : le compte sur lequel se vérifie le moindre privilège |

Le secret TOTP fixe permet à une suite de navigateur de calculer un code valide
au lieu de demander un téléphone. Il n'a de sens que là : le realm de production
est décrit par Terraform, qui ne pose **aucun** identifiant. Pour obtenir un code
à la main :

```bash
node -e "
const {createHmac}=require('crypto');
const c=Buffer.alloc(8); c.writeBigInt64BE(BigInt(Math.floor(Date.now()/30000)));
const h=createHmac('sha1',Buffer.from('planningequipesotp01')).update(c).digest();
const o=h[h.length-1]&15;
console.log(String(((h[o]&127)<<24|h[o+1]<<16|h[o+2]<<8|h[o+3])%1e6).padStart(6,'0'));
"
```

Le code par e-mail se voit dans Mailpit : « Essayer une autre méthode » après
l'adresse, puis « Code par e-mail ».

Une **passkey** ne s'essaie pas sur cette pile : WebAuthn n'accepte `http` que
sur `localhost`, et le navigateur refuse d'enrôler une passkey pour
`http://keycloak:8081`. Pour la tester, depuis un téléphone surtout, il faut
Keycloak derrière du HTTPS — un tunnel (Cloudflare Tunnel, ngrok) ou une recette
sur un vrai domaine.

## Provisioning des comptes animateurs

Avec `OIDC_PROVISIONING_ENABLED=true`, créer une fiche animateur crée le compte
qui ouvre son espace : l'organisateur tient une liste, pas deux. Le compte de
service `planning-provisioning` porte `manage-users` sur le realm, et rien
d'autre.

| Événement sur la fiche | Effet dans le realm |
| --- | --- |
| Création | Compte créé s'il n'existe pas (clé : l'adresse), rôle `animateur`, invitation envoyée par Keycloak : vérifier l'adresse, puis créer une passkey — aucun mot de passe |
| Modification | Compte créé s'il manque (une fiche qui gagne une adresse, une fiche d'avant le provisioning), prénom et nom alignés. Un compte désactivé à la console le reste |
| Adresse changée | Le compte de la **nouvelle** adresse est retrouvé ou créé ; l'ancien n'est pas renommé — il appartient peut-être à quelqu'un d'autre |
| Suppression | Compte **désactivé**, et seulement si plus aucune fiche, dans aucune édition, ne porte l'adresse. Jamais supprimé |

Créer ou modifier une fiche **échoue** si le compte ne peut pas être écrit : dans
ce mode, le compte *est* l'accès. Supprimer une fiche **n'échoue jamais**
là-dessus : la fiche est partie de toute façon, et un compte resté actif n'ouvre
rien par lui-même. `OIDC_PROVISIONING_SEND_INVITATION=false` crée une saison
entière de comptes sans envoyer 150 invitations d'un coup.

### Un animateur n'a pas de mot de passe

L'invitation porte les actions de `OIDC_PROVISIONING_INVITATION_ACTIONS`, par
défaut `VERIFY_EMAIL,webauthn-register-passwordless` : la personne confirme son
adresse, puis enregistre une passkey sur son téléphone (visage, empreinte ou
code de l'appareil). Elle ne choisit jamais de mot de passe.

- **Se connecter** : le téléphone propose la passkey dès l'écran de l'adresse
  (`webAuthnPolicyPasswordlessPasskeysEnabled`, `passwordless_passkeys_enabled`
  en Terraform) ; taper l'adresse puis choisir la passkey marche aussi.
- **Passkey perdue, nouveau téléphone** : après l'adresse, « Essayer une autre
  méthode » envoie un code à six chiffres par e-mail — la méthode de repli, que
  Keycloak propose d'emblée à un compte sans mot de passe ni passkey. Une fois
  dans l'espace, le menu « Ma passkey et mes moyens de connexion » mène à la
  page de la console de compte Keycloak (`GET /api/auth/oidc/compte`) où
  enregistrer la nouvelle passkey et retirer l'ancienne.
- **Un appareil sans passkey** : l'invitation échoue à la seconde étape, l'adresse
  est pourtant vérifiée, et le code par e-mail suffit à se connecter.

Le repli par e-mail fait de la boîte aux lettres la clé du compte : c'est le
niveau de l'ancien code de l'espace, et il ne vaut que pour un rôle qui n'ouvre
que son propre planning. Les administrateurs gardent le TOTP après **toute**
méthode. `OIDC_PROVISIONING_INVITATION_ACTIONS=UPDATE_PASSWORD,VERIFY_EMAIL`
rétablit l'invitation « choisissez votre mot de passe ».

Le compte de service n'a que `manage-users` et il faut y tenir en écrivant le
code qui s'en sert : lire la définition d'un rôle du realm demanderait
`view-realm`, que ce compte n'a pas — Keycloak répondrait `403` et le compte
serait créé **sans son rôle**. Le rôle se cherche parmi les rôles *assignables
à cet utilisateur*.

## Le serveur MCP en OAuth2

`/mcp` est un *resource server* OAuth 2.1 au sens de la spécification MCP, **en
plus** de sa clé API : un `401` portant `WWW-Authenticate: Bearer
resource_metadata="…"`, la route publique RFC 9728
`/.well-known/oauth-protected-resource` qui nomme le realm, et la **validation
d'audience** — seuls les jetons émis pour `planning-mcp` sont acceptés. Le
détail côté application est dans [`mcp.md`](mcp.md).

| Client | Pour qui | Flux |
| --- | --- | --- |
| `planning-mcp` | Personne : c'est la **ressource**, l'audience que `/mcp` exige | Aucun |
| `planning-mcp-client` | Un assistant qui tourne sans humain ; rôle `mcp` sur son compte de service | `client_credentials` |
| `planning-mcp-public` | Un client MCP de bureau qui ouvre un navigateur ; la personne doit porter `mcp` | Code + PKCE, boucle locale |

```bash
# Client automatisé, realm de développement
curl -s -X POST http://keycloak:8081/realms/planning/protocol/openid-connect/token \
  -d grant_type=client_credentials \
  -d client_id=planning-mcp-client \
  -d client_secret=dev-planning-mcp-rB7nX3wD | jq -r .access_token
```

Révéler la clé API MCP depuis l'écran MCP demande, pour une session Keycloak,
une connexion de **moins de cinq minutes** (`auth_time` du jeton) plutôt que la
ressaisie d'un mot de passe que l'application ne connaît plus : au-delà,
l'écran renvoie se reconnecter.

## Ce que chaque côté doit déclarer — les pièges déjà payés

Chacun de ces points a coûté une exécution de CI ou une session de débogage, et
chacun est tenu par un test structurel.

- **PKCE des deux côtés à la fois.** Le realm pose
  `pkce.code.challenge.method: S256` sur `planning-app`, ce que Keycloak lit
  comme « refuser toute demande sans challenge » ; l'application répond par
  `quarkus.oidc.authentication.pkce-required=true`. Le realm seul renvoie
  `Missing parameter: code_challenge_method` **avant** son écran de connexion.
- **Le secret de `planning-app` fait au moins 32 caractères** : c'est lui qui
  chiffre le vérificateur PKCE dans le cookie d'état. En deçà, Quarkus tire une
  clé au hasard au démarrage, et une connexion entamée ne survit pas à un
  redémarrage. La variable Terraform le refuse.
- **Les rôles se lisent dans le jeton d'accès** :
  `quarkus.oidc.roles.source=accesstoken` avec
  `quarkus.oidc.roles.role-claim-path=realm_access/roles`. Quarkus lit le jeton
  d'identité par défaut, où Keycloak ne met pas `realm_access` : l'administrateur
  franchirait son second facteur pour atterrir sur « Access denied ».
- **Le realm JSON ne déclare jamais `clientScopes`.** À l'import, Keycloak ne
  crée `email`, `profile`, `roles`, `acr`, `basic` et `web-origins` que si la
  clé est **absente** ; en déclarer un seul les remplace tous, et les jetons
  perdent `email` et `realm_access.roles` derrière un import qui annonce avoir
  réussi. D'où le mappeur d'audience posé **sur chaque client MCP** dans le
  fichier, là où Terraform — qui parle à un realm déjà peuplé — crée un client
  scope `planning-mcp-audience`. Conséquence : dans le realm de développement,
  un client enregistré dynamiquement n'obtient pas l'audience.
- **Ni `requiredActions`**, pour la même raison : les déclarer remplacerait les
  actions intégrées, `VERIFY_EMAIL` et `UPDATE_PASSWORD` compris — les deux
  que demande toute invitation.
- **Aucune description au-delà de 255 caractères.** Keycloak les stocke en
  `VARCHAR(255)` et ne tronque pas : l'import meurt, l'API répond `500
  unknown_error`, et la cause n'est que dans le journal du serveur.

## Mise en production

Deux outils, deux objets, **dans cet ordre** :

| | Décrit | Pourquoi là |
| --- | --- | --- |
| [`terraform/keycloak/`](../terraform/keycloak/) | **Le realm** : rôles, clients et leurs droits, scope d'audience, flow de connexion, actions d'enrôlement, thème, relais SMTP, Google et DCR facultatifs | Un état à converger : Terraform détecte la dérive et la corrige. C'est la **seule** description du realm de production |
| [`ansible/keycloak-planning.yml`](../ansible/keycloak-planning.yml) | **Les personnes** du personnel : comptes nominatifs, rôles `admin` / `mcp`, désactivation, invitations | L'invitation est une action, pas un état ; et Terraform **supprime** ce qui sort de sa description, ce qui pour une personne détruirait son compte et son second facteur |

Les **animateurs** ne sont dans aucun des deux : l'application les crée avec
leur fiche (provisioning).

### Où tourne Keycloak

Une instance déjà en place chez l'hébergeur fait l'affaire, **à condition de
porter l'extension « code par e-mail »** que le flow nomme. Sinon,
`docker-compose.prod.yml` l'héberge sous le profil `keycloak` :

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod \
  --profile keycloak up -d
```

C'est alors l'image du dépôt, `ghcr.io/sylvainmetayer/planning-equipes-keycloak`,
sous **la même `APP_VERSION`** que l'application — les deux images sont publiées
sur les mêmes tags par `docker-ghcr.yml` et `docker-keycloak-ghcr.yml`, et un
incident se rapporte avec un seul numéro. Elle démarre en `start --optimized`
(construite pour PostgreSQL), sur sa **propre base** `keycloak-db`, écoute sur
`127.0.0.1:8081` et attend le reverse proxy devant `KEYCLOAK_HOSTNAME`. Le
compte d'amorçage (`KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD`) n'est créé
qu'au premier démarrage : le remplacer par un compte permanent depuis la
console, puis le supprimer.

`OIDC_AUTH_SERVER_URL` est l'URL **publique** du realm, et le conteneur de
l'application doit la joindre **par le reverse proxy** : c'est elle qui signe
l'émetteur, une URL interne ne marcherait que pour la moitié du trajet.

### 1. Le realm, par Terraform

```bash
cd terraform/keycloak
cp terraform.tfvars.example terraform.tfvars   # puis remplir

# Rien de secret ne s'écrit dans ce fichier : ni les identifiants
# d'administration, ni les secrets de client, ni ceux du relais SMTP.
export KEYCLOAK_USER=... KEYCLOAK_PASSWORD=...
export TF_VAR_client_app_secret=... TF_VAR_client_mcp_secret=... \
       TF_VAR_client_provisioning_secret=...
export TF_VAR_smtp_username=... TF_VAR_smtp_password=...   # les deux ou aucun

terraform init && terraform apply
terraform output variables_application        # à recopier dans .env.prod
terraform output -raw oidc_client_secret
terraform output -raw oidc_provisioning_client_secret
```

Le relais SMTP est **obligatoire** : sans lui, pas d'invitation, pas de
réinitialisation, et pas de code par e-mail. L'état Terraform porte les secrets
en clair : il n'a rien à faire dans le dépôt (`terraform/keycloak/.gitignore`),
un backend distant chiffré est la place d'un vrai déploiement. Le
`.terraform.lock.hcl`, lui, se versionne.

Trois pièges d'exploitation :

- **Un flux lié ne se remplace pas directement.** Changer sa structure demande à
  Terraform de le détruire, et Keycloak refuse (`Cannot remove authentication
  flow, it is currently in use`). Rendre d'abord `browser_flow` au flux natif
  (console, ou un `apply` qui retire `keycloak_authentication_bindings`), puis
  appliquer la nouvelle structure.
- **Toucher au realm peut reprendre `manage-users` au provisioning le temps
  d'un `apply`** : les trois `keycloak_openid_client_service_account_role`
  lisent l'identifiant de `realm-management` par un `data` attaché au realm, et
  un changement du realm les fait *remplacer*. Une seconde sans droit de créer
  un compte : ne pas appliquer pendant qu'on saisit des animateurs.
- **Une description de plus de 255 caractères** fait répondre `500` sans autre
  trace que le journal de Keycloak — `TerraformKeycloakStructuralTest` la refuse
  avant.

### 2. Les personnes, par Ansible

```bash
ansible-galaxy collection install -r ansible/requirements.yml
cp ansible/inventory.example.yml ansible/inventory.yml
cp ansible/group_vars/all.example.yml ansible/group_vars/all.yml   # puis remplir
ansible-playbook -i ansible/inventory.yml ansible/keycloak-planning.yml
```

Le playbook vérifie d'abord que le realm répond, et s'arrête sinon. Chaque
entrée de `keycloak_personnes` porte une adresse, des `roles` pris parmi `admin`
et `mcp`, éventuellement des `roles_retires`, et `actif: false` pour une
personne qui part. Il est idempotent et ne fait **jamais** trois choses :

- **supprimer un compte** — une personne qui part est désactivée, son compte
  reste attribuable dans les journaux ; retirer sa ligne ne fait rien ;
- **poser un mot de passe** — chaque personne reçoit une invitation et choisit
  le sien ; le second facteur est imposé par le flow dès sa première connexion ;
- **renvoyer une invitation** — elle ne part que vers un compte que l'exécution
  vient de créer (`-e keycloak_reinviter=<adresse>` pour la renvoyer à la main).

Il refuse `user` comme privilège, et le rôle `animateur`, qui appartient à
l'application.

### Variables de l'application

| Variable | Rôle |
| --- | --- |
| `OIDC_ENABLED` | `true` par défaut. `false` seulement pour une instance dépannée par le compte de secours |
| `OIDC_AUTH_SERVER_URL` | `https://sso.exemple.org/realms/planning` — l'URL **publique** |
| `OIDC_CLIENT_ID` / `OIDC_CLIENT_SECRET` | Le client confidentiel `planning-app` ; secret ≥ 32 caractères |
| `OIDC_FORCE_HTTPS` | `true` derrière un proxy qui termine TLS : sans elle, les URL de redirection et les métadonnées MCP sont annoncées en `http` |
| `OIDC_POST_LOGOUT_PATH` | Où Keycloak renvoie après déconnexion (défaut `/login`) |
| `OIDC_ANIMATEUR_ROLE` | Rôle qui ouvre l'espace (défaut `animateur`). Jamais `user` |
| `OIDC_MCP_AUDIENCE` | Audience attendue sur `/mcp` (défaut `planning-mcp`) |
| `OIDC_PROVISIONING_ENABLED` | Miroir des fiches vers les comptes (`true` dans `docker-compose.prod.yml`) |
| `OIDC_PROVISIONING_SERVER_URL` | Racine de Keycloak, **sans** `/realms/…` |
| `OIDC_PROVISIONING_REALM` | `planning` |
| `OIDC_PROVISIONING_CLIENT_ID` / `_SECRET` | Le compte de service `planning-provisioning` |
| `OIDC_PROVISIONING_SEND_INVITATION` | `false` pour créer en masse sans envoyer d'invitation |
| `OIDC_PROVISIONING_INVITATION_ACTIONS` | `VERIFY_EMAIL,webauthn-register-passwordless` par défaut : adresse vérifiée, puis passkey, sans mot de passe |
| `ADMIN_SECOURS_ENABLED` | `false` par défaut : le compte de secours est fermé |
| `ADMIN_PASSWORD` | Le mot de passe du compte de secours, obligatoire même fermé |

L'application **refuse de démarrer** sur une configuration à moitié posée : OIDC
et secours tous deux éteints, un client confidentiel sans secret, un
provisioning sans identifiants de compte de service.

### Recette après déploiement

1. `GET /api/config` renvoie `"authOidc":true` et `"authSecours":false`.
2. `POST /j_security_check` répond `409`.
3. `/login` propose la connexion par Keycloak, sans champ mot de passe.
4. Un administrateur se connecte : Keycloak **exige un second facteur** et, s'il
   n'en a pas, lui en fait configurer un — par mot de passe **comme** par code
   e-mail. `GET /api/auth/me` renvoie `"authentifie":true` et `"roles":["admin"]`.
5. Un animateur se connecte : son espace s'ouvre ; `GET /api/animateurs` lui
   répond **403** (l'identité est reconnue, c'est le rôle qui manque).
6. Créer une fiche avec une adresse neuve fait apparaître le compte dans la
   console, et l'invitation dans la boîte correspondante.
7. `POST /mcp` sans jeton répond `401` avec un `WWW-Authenticate` portant
   `resource_metadata` ; l'URL qu'il désigne répond `200` et nomme le realm.
8. Se déconnecter, puis relancer une connexion : Keycloak **redemande**
   l'adresse (la déconnexion a fermé sa session, pas seulement la nôtre).

### Rotation et révocation

- **Une personne du personnel part** : `actif: false` dans le playbook (ou la
  console). Ses sessions ne se renouvellent plus. Désactiver son `compte` dans
  l'application ferme aussi toutes ses portes, immédiatement.
- **Le secret d'un client fuit** : nouvelle valeur dans `TF_VAR_*`, `terraform
  apply`, nouvelle valeur dans `.env.prod`, redémarrage.
- **La clé API MCP fuit** : elle reste indépendante d'OAuth2
  ([`mcp.md`](mcp.md)). Passer les clients à OAuth2 puis vider
  `PLANNING_MCP_API_KEY` supprime le problème plutôt que de le déplacer.
- **Keycloak est indisponible** : ouvrir le compte de secours (plus haut), le
  refermer ensuite. Les animateurs attendent : leur espace ne s'ouvre que par
  Keycloak.

## Migration d'une instance existante

Une instance d'avant Keycloak se connectait par `admin` / `ADMIN_PASSWORD`, et
ses animateurs par un code envoyé par l'application. Dans l'ordre :

1. **Déployer Keycloak** (instance existante portant l'extension, ou profil
   `keycloak` de la pile) et **appliquer `terraform/keycloak/`**.
2. **Poser les variables `OIDC_*`** dans `.env.prod`, à partir de `terraform
   output`, **et `ADMIN_SECOURS_ENABLED=true`** : le temps de la bascule, les
   deux portes restent ouvertes.
3. **Créer les administrateurs** avec le playbook Ansible ; chacun reçoit son
   invitation, choisit son mot de passe et enrôle son TOTP.
4. **Mettre à jour l'application.** La migration `V108` **supprime les tables du
   code d'accès de l'espace** (`espace_acces`, `espace_session`) : à partir de
   là, un animateur n'entre plus que par Keycloak. Allumer le provisioning
   (`OIDC_PROVISIONING_ENABLED=true`) : les fiches **existantes** n'ont pas
   encore de compte, et chaque enregistrement d'une fiche crée le sien. Prévenir
   les animateurs : l'invitation est le nouveau chemin.
5. **Vérifier la recette** ci-dessus avec un vrai compte administrateur.
6. **Refermer le secours** : `ADMIN_SECOURS_ENABLED=false`, redémarrer.
   `POST /j_security_check` répond alors `409`.

## Le thème, et les visuels d'un événement

La page de connexion et les e-mails de Keycloak sont ce qu'un animateur voit en
premier. Ils portent donc la marque du déploiement, comme l'application — et de
la même façon : **un thème standard dans l'image, une couleur par variable, des
images par dossier monté**. Rien à reconstruire pour un client, rien à cliquer
dans la console.

| | Ce que c'est |
| --- | --- |
| `login/theme.properties` | `parent=keycloak.v2` : chaque page (mot de passe, OTP, passkey, erreurs) vient du thème natif et en suit les mises à jour. Rien n'est recopié |
| `login/resources/css/planning.css` | La couche commune : typographie, carte, et **une seule variable**, `--planning-accent`, vers laquelle les couleurs globales de PatternFly sont redirigées |
| `login/footer.ftl` | Le seul gabarit surchargé. Il pose la couleur d'accent lue dans l'environnement, et n'accepte qu'une couleur hexadécimale |
| `email/html/template.ftl` | Le cadre de tous les e-mails (invitation, réinitialisation, vérification) : styles en ligne et tableaux, ce que lisent les clients de messagerie |
| `brand/` | **Ce qui appartient au client** : `logo.svg`, `background.svg`, `brand.css`, `logo-email.png`. Le dépôt y met les visuels neutres du produit |

Un client change deux choses, et pas une de plus :

- **`KEYCLOAK_BRAND_ACCENT`** — la couleur d'accent, pour la connexion **et**
  les e-mails d'un coup. Un e-mail ne sait pas lire un fichier CSS monté ; une
  variable est la seule chose que les deux peuvent partager.
- **`KEYCLOAK_BRAND_DIR`** — un dossier avec les quatre fichiers de `brand/`,
  monté à la place de celui de l'image. Le logo est un SVG pour la page, un PNG
  pour les e-mails (Gmail retire les SVG).

```bash
# .env.prod
KEYCLOAK_BRAND_ACCENT=#b23a48
KEYCLOAK_BRAND_DIR=/srv/planning/brand-2027
```

Sans `KEYCLOAK_BRAND_DIR`, c'est un volume nommé qui est monté, que Docker
remplit depuis l'image à sa création : le thème a toujours un logo sous les
pieds. Le dossier est **un seul point de montage** pour les deux types de
thème : l'image le rend visible à `login/` et à `email/` par deux liens
symboliques, si bien qu'un client ne peut pas donner aux e-mails un logo que la
page n'a pas.

Ce que le thème ne fait pas : **la console de compte** (où l'on enrôle un
passkey) est une application React qui ne prend ni la couleur ni le logo ; les
**textes** restent ceux de Keycloak, dans sa traduction française ; et les
visuels **ne se téléversent pas**, ils se montent.

## Tests

| Suite | Ce qu'elle couvre | Ce qu'elle ne peut pas couvrir |
| --- | --- | --- |
| Tests Java de l'application (`./mvnw test`) | Routage des locataires, rôles, audience, défi RFC 9728, règle `email_verified`, compte de secours — contre le serveur OIDC **en mémoire** de `quarkus-test-oidc-server` (profil `%test` en mode `hybrid`, OIDC allumé) | Le realm lui-même |
| `RealmPlanningStructuralTest`, `TerraformKeycloakStructuralTest`, `PlaybookKeycloakStructuralTest`, `KeycloakThemeStructuralTest` (`./mvnw test -Punit`) | Le realm JSON face à `application.properties` et à `docker-compose.yml` ; l'arbre du flow identique en JSON et en Terraform, le second facteur au bon endroit ; PKCE des deux côtés ; descriptions ≤ 255 ; le playbook réduit aux personnes, sans suppression ni `user` ; le thème, l'image, l'extension et leur version de Keycloak | Ce que seul un vrai serveur dit : un import, un `apply`, une connexion |
| Suite Playwright contre la pile Keycloak | Le code flow dans un vrai navigateur, le second facteur exigé et refusé, le provisioning contre un vrai serveur, la déconnexion RP-initiated | — |

Les quatre tests structurels ne démarrent pas l'application et ne demandent ni
Docker ni base. Ce que l'environnement de rédaction n'a pas pu exercer — un
`terraform validate` (le fournisseur `keycloak/keycloak` n'y était pas
téléchargeable), l'image construite et démarrée, le playbook contre un serveur —
reste à la charge de la CI et de la première mise en production.

## Limites connues

- **Pas de synchronisation inverse.** Un compte créé à la console ne crée pas de
  fiche ; c'est la fiche qui fait foi.
- **Les journaux d'action** enregistrent l'adresse du compte. Les entrées
  écrites avant la bascule portent encore `admin` : elles ne sont pas réécrites.
- **L'extension « code par e-mail » est mono-serveur** (voir plus haut).
- **Le realm de développement ouvre l'enregistrement dynamique de clients**
  (pour les clients MCP qui l'exigent) ; la production le garde fermé tant que
  `dynamic_client_registration` n'est pas allumé en Terraform, avec une liste
  d'hôtes de confiance.
