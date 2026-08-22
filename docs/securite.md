# Durcissement pour une exposition sur Internet

L'application a été écrite pour un usage en réseau de confiance : une seule
personne administre, les animateurs consultent leur planning par un lien. Ce
document rassemble ce que le service applique **de lui-même** quand on
l'ouvre sur Internet, et ce qui reste à la charge du déploiement.

L'authentification (session admin, jeton + code de l'espace animateur, clé
MCP) est décrite dans [`api.md`](api.md) et [`mcp.md`](mcp.md) ; on ne la
répète pas ici.

## En-têtes de sécurité navigateur

`SecurityHeadersFilter` ajoute à **toute** réponse — fichiers statiques du SPA
compris — les en-têtes suivants :

| En-tête | Valeur | Pourquoi ici |
| --- | --- | --- |
| `Content-Security-Policy` | `planning.securite.csp` (voir ci-dessous) | Le SPA ne charge aucun script tiers hormis la balise Cloudflare Web Analytics : tout ce qui serait injecté dans une page est refusé à l'exécution |
| `Referrer-Policy` | `no-referrer` | Le jeton de l'espace animateur voyage **dans l'URL** ; sans cet en-tête il part dans le `Referer` de chaque navigation sortante (lien d'attribution OpenStreetMap, lien vers le dépôt). Une exception pour les tuiles, ci-dessous |
| `X-Frame-Options` | `DENY` | Rien n'est prévu pour être encadré, et détourner un clic dans une session qui peut réécrire tout le planning n'a pas de contrepartie |
| `Cross-Origin-Opener-Policy` | `same-origin` | Isole la fenêtre de tout `window.opener` ouvert depuis un autre site |
| `X-Content-Type-Options` | `nosniff` | Les exports (PDF, ICS, SQL, CSV) sont servis avec leur type ; qu'un navigateur en devine un autre n'apporte rien |
| `Permissions-Policy` | `geolocation=(), camera=(), microphone=(), payment=()` | Aucune de ces API n'est utilisée — le sélecteur de carte place un point à la souris |
| `Strict-Transport-Security` | `planning.securite.hsts`, **seulement sur une visite HTTPS** | Un an, sous-domaines compris. Absent sur la pile locale, qui est en clair |

Trois exceptions volontaires :

- la CSP s'arrête au seuil de `/q/*` : Swagger UI y sert des scripts *inline*
  que le projet ne contrôle pas. Les autres en-têtes, eux, s'y appliquent ;
- HSTS n'est envoyé que si la visite est en HTTPS (`X-Forwarded-Proto`
  compris, comme pour la redirection de connexion et le cookie de l'espace) :
  un navigateur l'ignorerait de toute façon en clair, et le poser en local
  épinglerait `localhost` en https ;
- les tuiles de la carte échappent au `no-referrer`. `tile.openstreetmap.org`
  [bloque le trafic sans `Referer`](https://osm.wiki/blocked) : la carte
  resterait grise. `MapPicker` pose donc
  `referrerPolicy: 'strict-origin-when-cross-origin'` sur sa couche de tuiles
  ([option Leaflet](https://leafletjs.com/reference.html#tilelayer-referrerpolicy)),
  qui l'emporte sur la politique du document pour ces images seules. Le jeton
  ne fuit pas pour autant : cette politique n'envoie que l'origine, jamais le
  chemin ni la requête — et rien du tout si la tuile était servie en clair.

### Réglage

| Variable | Défaut | Usage |
| --- | --- | --- |
| `CSP` | politique ci-dessus | Politique de sécurité du contenu complète. **Vide = en-tête désactivé** |
| `HSTS` | `max-age=31536000; includeSubDomains` | **Vide = en-tête désactivé**. N'ajoutez `preload` qu'après avoir décidé que le domaine ne resservira jamais en clair : c'est irréversible à l'échelle du navigateur |

La CSP par défaut laisse volontairement `connect-src https:` et `img-src
https:` ouverts : l'endpoint Sentry/Bugsink (`SENTRY_DSN`) et le serveur de
tuiles cartographiques sont propres à chaque déploiement. Un déploiement qui
connaît les siens gagne à les nommer :

```bash
CSP="default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'; \
form-action 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; font-src 'self'; \
img-src 'self' data: https://*.tile.openstreetmap.org; \
connect-src 'self' https://bugsink.example.org"
```

`style-src` garde `'unsafe-inline'` dans tous les cas : Angular Material écrit
ses styles dans la page.

## Plafonds du serveur HTTP

| Variable | Défaut | Ce qu'elle borne |
| --- | --- | --- |
| `MAX_BODY_SIZE` | `10M` | Taille maximale d'un corps de requête. C'est l'**import de dump SQL** (`POST /api/database/import`, un script rejoué en entier) qui la dimensionne : un déploiement qui n'utilise pas l'import gagne à la descendre franchement |
| `MAX_CONNECTIONS` | `500` | Connexions simultanées acceptées |

La taille de corps était déjà bornée par le défaut de Quarkus ; elle est
désormais explicite et réglable, pour que le bouton soit visible. Le plafond
de connexions, lui, n'existait pas : sans lui, ouvrir des connexions sans
jamais rien envoyer suffit à épuiser le serveur. `500` est large au regard de
l'usage réel — une poignée d'administrateurs et ~150 animateurs qui consultent
leur planning — donc sans effet sur le trafic légitime.

Ces plafonds ne remplacent pas ceux du reverse proxy, qui doit rester la
première ligne (limitation de débit par IP, plafond de connexions par client,
délais d'attente).

## Limitation de débit

`POST /api/espace-animateur/{jeton}/code` est le seul endpoint public qui
**déclenche un envoi de mail**. Sans plafond, quiconque tient le lien d'un
animateur noie sa boîte sous les codes, épuise le quota du serveur SMTP, et
remet à cinq le compteur d'essais du code à chaque demande.

Ce qui est compté, ce sont les codes **jamais utilisés** : ouvrir la session
avec le code reçu efface le compteur. Un animateur qui se connecte
normalement, même souvent, n'atteint donc jamais le plafond — seule
l'accumulation de demandes sans suite, qui est exactement l'abus, y mène. Au
delà, la réponse est `429` avec un `Retry-After`.

| Variable | Défaut | Usage |
| --- | --- | --- |
| `ESPACE_CODE_MAX_DEMANDES` | `3` | Codes non utilisés tolérés par animateur et par fenêtre |
| `ESPACE_CODE_FENETRE` | `PT10M` | Durée de la fenêtre, calée sur la validité d'un code |

Le compteur vit en mémoire (application mono-instance), comme le verrouillage
de la révélation de clé MCP.

### Verrouillage du form login admin

L'application n'a qu'un compte, `admin`, sans second facteur : une seule paire
d'identifiants ouvre les données personnelles de ~150 personnes, mineurs
compris. `/j_security_check` acceptait pourtant les tentatives au rythme du
réseau — alors que la révélation de la clé MCP se verrouillait déjà au bout de
cinq essais.

Au-delà de `CONNEXION_MAX_ECHECS` échecs consécutifs, l'adresse reçoit `429`
+ `Retry-After` pendant `CONNEXION_DUREE_BLOCAGE`, **y compris avec le bon mot
de passe** : sans quoi il suffirait d'attendre son tour. Une connexion réussie
efface le compteur, et la fenêtre court depuis le dernier échec réel.

| Variable | Défaut | Usage |
| --- | --- | --- |
| `CONNEXION_MAX_ECHECS` | `5` | Échecs consécutifs tolérés par adresse |
| `CONNEXION_DUREE_BLOCAGE` | `PT15M` | Durée du verrouillage, comptée depuis le dernier échec |

Succès et échec du form login répondent tous deux une redirection vers la
**même page** (`landing-page` et `error-page` pointent au même endroit), donc
ni le statut ni `Location` ne les distinguent. Les deux côtés se lisent
ailleurs, et pas au même endroit :

- **l'échec** est l'`AuthenticationFailureEvent` de Quarkus
  (`quarkus.security.events.enabled`), qui porte le contexte HTTP de la
  tentative — donc son adresse ;
- **le succès** se lit sur la requête elle-même. Quarkus a bien un événement
  de connexion réussie (`FormAuthenticationEvent`), mais il ne transporte que
  son propre type : aucun contexte HTTP, donc aucune adresse à qui rendre son
  crédit. Une connexion réussie se reconnaît alors à ce qu'elle produit — une
  identité établie sur la requête, un cookie de session dans la réponse.

**L'adresse retenue est celle annoncée par `X-Forwarded-For`**, la connexion
réelle ne servant que faute de mieux : derrière un reverse proxy, toutes les
requêtes arrivent de la même adresse, et compter là-dessus laisserait le
premier attaquant venu verrouiller la connexion de tout le monde. Cet en-tête
n'est digne de confiance qu'à une condition, la même que pour
`PROXY_ADDRESS_FORWARDING` : **l'origine ne doit pas être joignable sans
passer par le proxy** (voir la section suivante).

Ce verrou ne remplace pas la limitation de débit par IP du reverse proxy, qui
vaut pour tout le reste : les exports, la résolution, l'API entière.

### Lecture des créneaux d'un collègue

`GET /api/espace-animateur/{jeton}/collegues/{collegueId}/postes` sert le
sélecteur « son créneau que je veux en échange ». Cet accès est **large par
conception** : la vue de l'espace distribue déjà le trombinoscope complet, et
les créneaux d'un collègue sont l'information que le planning imprimé fait
circuler. Il n'y a donc pas là d'accès non autorisé à corriger — les écritures
de la foire, elles, portent leur autorisation dans le `WHERE` de leur requête
(`AND cible_id = ?`, `AND demandeur_id = ?`), ce qui est le motif le plus sûr.

Ce qui restait inconfortable, c'est l'**agrégat** : une seule session d'espace
reconstituait tout le planning nominatif du festival, mineurs compris, en
autant de requêtes qu'il y a d'animateurs. Deux resserrements :

- **la foire doit être ouverte.** Cette lecture n'existe que pour alimenter le
  sélecteur, que l'interface masque quand la foire est fermée. La règle est
  déclarée **sur la route** par `@FoireOpenRequired`, dans la forme des deux
  gardes voisines (`@TokenRequired`, `@EspaceSessionRequired`) : on lit les trois
  exigences d'une route d'un coup d'œil, au lieu de chercher un contrôle au
  fond d'un corps de méthode. Le filtre tourne en priorité `AUTHORIZATION`,
  donc **après** l'authentification — un appelant anonyme reçoit son `401` sans
  jamais apprendre si la foire est ouverte. Les écritures, elles, gardent leur
  contrôle dans le service : c'est là qu'est l'application réelle de la règle,
  elle vaut quel que soit l'appelant et ne doit pas dépendre d'une route
  annotée. Une constante partagée fait que les deux disent la même phrase ;
- **le collègue doit exister dans l'édition.** Un identifiant inconnu répondait
  `200 []`, ce qui laissait sonder les identifiants existants. Il répond `404`
  nu — `NotFoundException`, comme toute entité inconnue de ce dépôt : un corps
  d'erreur ne donnerait à lire qu'à celui qui sonde.

Ce qui **n'est pas** traité ici et reste ouvert : plafonner le nombre de
collègues distincts consultés par fenêtre (sur le modèle du plafond des codes
d'accès), qui est le seul contrôle visant réellement le balayage ; et, plus en
profondeur, cesser de distribuer le trombinoscope entier au profit d'une
recherche à la frappe.

## Analyse statique : les suppressions et leur justification

Le job `code` de `securite.yml` (Semgrep OSS) fait échouer la CI sur toute
règle de sévérité `ERROR`. Une règle atteint aujourd'hui neuf endroits du
code, tous pour la même raison, et tous annotés `nosemgrep` **site par site**
plutôt que désactivés en bloc : une nouvelle concaténation SQL non annotée
continue donc de faire rougir la CI.

`java.lang.security.audit.formatted-sql-string` signale toute requête
construite par concaténation. Dans ce dépôt, ce qui est concaténé est
**toujours un identifiant** — un nom de table ou de colonne —, et SQL ne
permet pas de lier un identifiant en paramètre : `SELECT * FROM ?` n'existe
pas. Ces identifiants viennent sans exception de littéraux écrits dans le code
appelant ou de listes constantes (`DatabaseDumpService.TABLES`), jamais d'une
entrée utilisateur ; l'import de dump vérifie même la sienne contre
`ALLOWED_TABLES`. Les **valeurs**, elles, voyagent toutes en paramètres liés
(`ps.setString`, `ps.setLong`, `ps.setObject`).

| Fichier | Méthodes concernées |
| --- | --- |
| `DatabaseDumpService` | `appendTable` (nom de table issu de `TABLES`) |
| `DemandeEchangeService` | `lister` (liste de colonnes et prédicat, littéraux des appelants) |
| `PlanSnapshotService` | `absents` (table et colonne, littéraux des appelants) |
| `ReferenceDataImportRepository` | `compter`, `deleteMissingTx` (noms de tables issus de listes littérales) |
| `JdbcEditionScope` | `existe` (nom de table issu des sites d'appel des dépôts) |

**Avant d'ajouter un `nosemgrep`**, vérifier les appelants : si un identifiant
peut venir d'une requête HTTP, c'est une injection SQL et non un faux positif.
C'est ce qui a été fait pour ces neuf-là — chaque site d'appel passe une chaîne
littérale.

## Déploiement

`docker-compose.yml` est une pile de **développement** : elle publie PostgreSQL
sur l'hôte, ajoute pgAdmin en `admin/admin` sans mode serveur, un puits SMTP
qui n'envoie rien, et des mots de passe `festival`/`admin`. Rien de tout cela
n'a à exister sur une machine exposée — et « on changera les mots de passe »
n'enlève ni pgAdmin ni le port 5432.

`docker-compose.prod.yml` est la pile correspondante :

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d
```

Ce qu'elle change :

- **un seul port publié**, sur la boucle locale (`127.0.0.1:8080`) : le reverse
  proxy est le seul chemin d'entrée ;
- **PostgreSQL n'est plus publié du tout** — seul le réseau interne le voit ;
- **ni pgAdmin ni Mailpit** ;
- **aucune valeur par défaut sur les secrets** : `DB_PASSWORD`,
  `ADMIN_PASSWORD`, `SESSION_ENCRYPTION_KEY`, `PUBLIC_URL`, `MAIL_HOST` et
  `MAIL_FROM` font échouer le démarrage si l'environnement ne les fournit pas,
  plutôt que de laisser passer un identifiant de développement.

### Ce qui reste à la charge du reverse proxy

L'application ne peut pas s'en occuper à sa place, et ces points sont des
**prérequis** de ce qui précède :

| À faire | Pourquoi |
| --- | --- |
| Terminer le TLS et rediriger tout le trafic http vers https | HSTS et le flag `Secure` du cookie de l'espace ne s'activent que sur une visite HTTPS |
| **Rendre l'origine injoignable autrement que par le proxy** (pare-feu, réseau) | Sans cela, `X-Forwarded-For` et `X-Forwarded-Proto` sont forgeables : le verrouillage de connexion se contourne en changeant d'adresse annoncée. À défaut, renseigner `TRUSTED_PROXIES` (`QUARKUS_HTTP_PROXY_TRUSTED_PROXIES`) |
| Limiter le débit par adresse IP sur tout le site | Les plafonds de l'application sont ciblés (connexion admin, codes de l'espace) ; le reste — exports, résolution, API — n'en a pas |
| Journaliser sans les URL de l'espace animateur, ou purger ces journaux | Le jeton d'accès voyage **dans le chemin** : il atterrit tel quel dans les journaux d'accès |

### Avant d'ouvrir : la liste courte

- [ ] `ADMIN_PASSWORD` long, généré, propre à ce déploiement ;
- [ ] `SESSION_ENCRYPTION_KEY` définie (≥ 16 caractères) et gardée ;
- [ ] `DB_PASSWORD` changé ;
- [ ] `PUBLIC_URL` en `https://` ;
- [ ] `PLANNING_MCP_API_KEY` laissée vide tant que le serveur MCP ne sert à
      personne — vide, `/mcp` répond `401` à tout ;
- [ ] `REMOTE_USER_ENABLED` laissé à `false` sauf déploiement derrière un
      proxy d'accès, auquel cas `REMOTE_USER_SECRET` est obligatoire (le
      démarrage échoue sans lui) ;
- [ ] variables `LEGAL_*` renseignées : `/mentions-legales` est public,
      et une page de mentions légales vide vaut absence de mentions légales ;
- [ ] jeu de données de production chargé — jamais les fixtures de test.
