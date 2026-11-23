# Brancher un annuaire (OIDC)

L'application peut confier la connexion de ses administrateurs — et des
responsables de stand — à l'annuaire de l'organisation, par OpenID Connect. Le
choix et ses raisons sont dans
[l'ADR 0077](decisions/0077-comptes-nominatifs-par-un-fournisseur-oidc.md).

Ce qui ne change pas : sans fournisseur, l'application se connecte comme
toujours par le compte local (`admin` / `ADMIN_PASSWORD`). Les **animateurs
n'ont jamais de compte** : leur espace s'ouvre par leur lien et le code reçu
par e-mail, fournisseur ou non.

## Ce que fait le fournisseur, ce que fait l'application

| Le fournisseur | L'application |
| --- | --- |
| Authentifie : mot de passe, second facteur, politique de l'organisation | Autorise : qui administre, qui est RH, qui est responsable de quel stand, dans quelle édition, jusqu'à quand |
| Envoie l'adresse e-mail de la personne dans le jeton | Crée un compte nominatif à la première connexion, le désactive quand on le lui demande |

Les droits se donnent dans l'écran **Comptes et droits** (`/comptes`). Un
fournisseur qui envoie déjà un rôle `admin` (ou `mcp`) dans ses jetons est
honoré aussi : c'est facultatif.

## Les variables

| Variable | Défaut | Rôle |
| --- | --- | --- |
| `OIDC_ENABLED` | `false` | Branche le fournisseur |
| `OIDC_AUTH_SERVER_URL` | — | L'émetteur (*issuer*), la même URL vue du navigateur et du conteneur |
| `OIDC_CLIENT_ID` | `planning-app` | Le client déclaré chez le fournisseur |
| `OIDC_CLIENT_SECRET` | — | Son secret, **32 caractères au moins** en production (il chiffre aussi l'état PKCE) |
| `OIDC_SCOPES` | `profile,email` | Les portées demandées en plus d'`openid` |
| `OIDC_REQUIRE_EMAIL_VERIFIED` | `true` | Refuse un jeton sans `email_verified=true`. `false` pour un annuaire dont les adresses font foi (ADFS) |
| `OIDC_ADMIN_MFA` | `provider` | `provider` : le second facteur est l'affaire du fournisseur. `token` : l'application refuse le rôle admin à une session dont le claim `amr` ne nomme aucune méthode de `OIDC_ADMIN_MFA_AMR` |
| `OIDC_ADMIN_MFA_AMR` | `mfa,otp,hwk,swk,fido,http://schemas.microsoft.com/claims/multipleauthn` | Les valeurs d'`amr` qui valent second facteur |
| `OIDC_ROLE_CLAIM_PATH` | `realm_access/roles` | Où lire les rôles facultatifs du jeton (Keycloak : `realm_access/roles` ; ADFS : `role`) |
| `OIDC_ROLES_SOURCE` | `accesstoken` | Le jeton qui porte ces rôles (`accesstoken` ou `idtoken`) |
| `OIDC_PRINCIPAL_CLAIM` | `email` | Le claim qui nomme la personne dans l'historique |
| `OIDC_MCP_AUDIENCE` | `planning-mcp` | L'audience qu'un jeton MCP doit porter |
| `OIDC_FORCE_HTTPS` | `false` | `true` derrière un proxy qui termine TLS |
| `ADMIN_SECOURS_ENABLED` | `true` | Le compte local. À fermer une fois le fournisseur éprouvé |

Chez le fournisseur, déclarez un client **confidentiel**, flux *authorization
code* avec PKCE (S256), et ces deux adresses :

- de redirection : `https://<hôte>/api/auth/oidc/login` ;
- de retour après déconnexion : `https://<hôte>/login`.

## Mise en service

1. Déclarer le client chez le fournisseur (sections suivantes), renseigner les
   variables, redémarrer. Le journal de démarrage dit l'émetteur et le mode du
   second facteur ; une configuration incohérente refuse le démarrage.
2. Se connecter avec le **compte local**, ouvrir **Comptes et droits**,
   « Nommer un administrateur » pour chaque adresse concernée.
3. Se déconnecter, se reconnecter par « Se connecter » (l'annuaire) et
   vérifier l'accès.
4. Fermer le compte local : `ADMIN_SECOURS_ENABLED=false`. Le rouvrir le jour
   où l'annuaire ne répond plus.

## Un ADFS en direct

ADFS 2016 et suivants parlent OIDC. Dans la console AD FS :

1. **Groupe d'applications** → modèle *Application serveur accédant à une API
   web* (ou *Application serveur* seule). Identifiant client = `OIDC_CLIENT_ID`,
   adresse de redirection ci-dessus, générer un **secret partagé** =
   `OIDC_CLIENT_SECRET`.
2. **Règles d'émission** : envoyer l'adresse e-mail sous le type de claim
   `email` (règle « Envoyer les attributs LDAP en tant que revendications » :
   *E-Mail-Addresses* → `email`). Sans elle, le jeton ne porte pas d'adresse et
   la connexion n'ouvre rien.
3. **Stratégie de contrôle d'accès** : celle qui exige l'authentification
   multifacteur, si le second facteur doit être imposé par l'ADFS
   (`OIDC_ADMIN_MFA=provider`).

Côté application :

```bash
OIDC_ENABLED=true
OIDC_AUTH_SERVER_URL=https://adfs.exemple.fr/adfs
OIDC_CLIENT_ID=<identifiant du client>
OIDC_CLIENT_SECRET=<secret partagé>
OIDC_SCOPES=profile,email,allatclaims
OIDC_REQUIRE_EMAIL_VERIFIED=false
OIDC_ROLES_SOURCE=idtoken
OIDC_ROLE_CLAIM_PATH=role
```

`allatclaims` fait porter les claims des règles d'émission par le jeton
d'identité. ADFS n'envoie jamais `email_verified` : ses adresses sont celles de
l'annuaire, d'où `OIDC_REQUIRE_EMAIL_VERIFIED=false` — **jamais** pour un
fournisseur où chacun peut enregistrer sa propre adresse. Pour que
l'application vérifie elle-même le second facteur, `OIDC_ADMIN_MFA=token` :
ADFS nomme la méthode multifacteur
`http://schemas.microsoft.com/claims/multipleauthn` dans `amr`.

## Un Keycloak ou un Zitadel en intermédiaire

Un intermédiaire sert quand l'annuaire ne parle que SAML, quand plusieurs
annuaires se rejoignent (deux collectivités, une association), ou quand il
faut des comptes locaux à côté de ceux d'un annuaire. Il fédère les annuaires
(*identity brokering*) et l'application ne voit que lui.

**Keycloak** : un realm, un client confidentiel `planning-app` (*Client
authentication* activé, *Standard flow*, PKCE S256 dans les réglages avancés),
les deux adresses ci-dessus. Les annuaires se branchent en *Identity
providers* (SAML, OIDC, Google, Microsoft). Pour exiger un second facteur des
administrateurs, un flux conditionnel ; ou `OIDC_ADMIN_MFA=token`.

```bash
OIDC_ENABLED=true
OIDC_AUTH_SERVER_URL=https://auth.exemple.fr/realms/planning
OIDC_CLIENT_ID=planning-app
OIDC_CLIENT_SECRET=<secret du client>
```

**Zitadel** : une application *Web*, méthode d'authentification *Basic* (client
confidentiel) avec PKCE, les deux adresses ci-dessus ; les annuaires en
*Identity Providers* de l'organisation. Zitadel porte ses rôles de projet dans
un claim structuré : laissez les rôles à l'application (Comptes et droits)
plutôt que de les lire dans le jeton.

```bash
OIDC_ENABLED=true
OIDC_AUTH_SERVER_URL=https://auth.exemple.fr
OIDC_CLIENT_ID=<identifiant client>
OIDC_CLIENT_SECRET=<secret>
```

## MCP

La clé API (`PLANNING_MCP_API_KEY`) reste la voie de toujours. Un fournisseur
branché ouvre en plus `/mcp` aux jetons OAuth 2 qui portent l'audience
`OIDC_MCP_AUDIENCE` et le rôle `mcp` (métadonnées RFC 9728 sur
`/.well-known/oauth-protected-resource`). Un client MCP qui s'enregistre
lui-même (*dynamic client registration*) a besoin d'un fournisseur qui le
permet : c'est à vérifier fournisseur par fournisseur, la clé restant le
repli. Voir [`mcp.md`](mcp.md).

## L'espace animateur

Inchangé : lien et code par e-mail. Une personne déjà connectée par
l'annuaire, dont l'adresse est **celle de sa fiche**, entre dans son espace
sans le code ; jamais dans celui d'une autre fiche.
