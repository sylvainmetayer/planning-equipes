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

### `script-src` interdit tout `on*=""`, et ce que ça oblige à désactiver

Pas d'`'unsafe-inline'`, pas d'`'unsafe-hashes'` : le navigateur refuse **tout
attribut `on*=""`**. C'est la posture voulue, mais elle rend une optimisation
d'Angular silencieusement destructrice.

L'*inlining du CSS critique* sert la vraie feuille de style en
`media="print" onload="this.media='all'"`. Quand la CSP bloque ce gestionnaire,
**la feuille reste en `print`** : rien ne lève d'erreur, et tout ce que
l'extraction n'a pas retenu disparaît — une police d'icônes, par exemple.

`inlineCritical` est donc **désactivé**, et `scripts/check-csp-index.js` échoue
le build si l'`index.html` produit contient le moindre gestionnaire inline. Ni
le build (le HTML est valide) ni les tests (ils ne chargent jamais
`index.html`) ne peuvent voir le problème : une violation de CSP n'apparaît que
dans un vrai navigateur. Elle est donc vérifiée sur l'artefact.

Trois exceptions volontaires :

- **la CSP s'arrête au seuil de `/q/*`** : Swagger UI y sert des scripts inline
  que le projet ne contrôle pas. Les autres en-têtes s'y appliquent ;
- **HSTS n'est envoyé que sur une visite HTTPS** — un navigateur l'ignorerait
  en clair, et le poser en local épinglerait `localhost` en https ;
- **les tuiles de la carte échappent au `no-referrer`** :
  `tile.openstreetmap.org` [bloque le trafic sans `Referer`](https://osm.wiki/blocked),
  la carte resterait grise. `MapPicker` pose donc
  `referrerPolicy: 'strict-origin-when-cross-origin'` sur sa seule couche de
  tuiles. Le jeton ne fuit pas : cette politique n'envoie que l'origine, jamais
  le chemin ni la requête.

### Réglage

| Variable | Défaut | Usage |
| --- | --- | --- |
| `CSP` | politique ci-dessus | Politique de sécurité du contenu complète. **Vide = en-tête désactivé** |
| `HSTS` | `max-age=31536000; includeSubDomains` | **Vide = en-tête désactivé**. N'ajoutez `preload` qu'après avoir décidé que le domaine ne resservira jamais en clair : c'est irréversible à l'échelle du navigateur |

La CSP par défaut laisse volontairement `connect-src https:` et `img-src https:`
ouverts : l'endpoint Bugsink et le serveur de tuiles sont propres à chaque
déploiement. Un déploiement qui connaît les siens gagne à les nommer dans `CSP`
plutôt qu'à garder `https:`.

`style-src` garde `'unsafe-inline'` dans tous les cas : Angular Material écrit
ses styles dans la page.

## Plafonds du serveur HTTP

| Variable | Défaut | Ce qu'elle borne |
| --- | --- | --- |
| `MAX_BODY_SIZE` | `10M` | Taille maximale d'un corps de requête. C'est l'**import de dump SQL** (`POST /api/database/import`, un script rejoué en entier) qui la dimensionne : un déploiement qui n'utilise pas l'import gagne à la descendre franchement |
| `MAX_CONNECTIONS` | `500` | Connexions simultanées acceptées |

Le plafond de connexions est ce qui empêche d'épuiser le serveur en ouvrant des
connexions sans jamais rien envoyer. `500` est large au regard de l'usage réel,
donc sans effet sur le trafic légitime.

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

### Débit des déclarations de disponibilités

`POST /api/espace-animateur/{jeton}/disponibilites` (issue #291) est la
**première route qui écrit** depuis l'espace, et l'espace est ouvert sur
Internet avec une URL pour tout justificatif. Deux bornes, qui ne se remplacent
pas :

- le **volume** est borné par le métier — une seule proposition en attente par
  animateur, garantie par un index unique partiel (`V58`) : renvoyer mille fois
  laisse une ligne, et l'admin n'a jamais deux versions contradictoires de la
  même personne à arbitrer ;
- le **rythme** est borné ici. Sans plafond, une session ouverte écrit et
  notifie à la vitesse du réseau. Personne ne déclare ses disponibilités vingt
  fois en dix minutes ; celui qui se corrige n'atteint jamais le plafond.

| Variable | Défaut | Usage |
| --- | --- | --- |
| `ESPACE_DECLARATION_MAX_ENVOIS` | `20` | Déclarations tolérées par animateur et par fenêtre |
| `ESPACE_DECLARATION_FENETRE` | `PT10M` | Durée de la fenêtre |

Au-delà, `429` avec un `Retry-After`, comme pour les codes. Le compte est tenu
**par animateur** et non par adresse IP : la session nomme déjà l'animateur, et
une IP est ce qu'un téléphone change entre deux cellules. Les deux compteurs
partagent la même arithmétique (`SlidingWindowCounter`) pour qu'ils ne dérivent
pas l'un de l'autre.

Deux garde-fous de plus, côté contenu : un jour hors des dates de l'événement
et une typologie inconnue sont refusés, et la déclaration **n'écrit rien dans
le référentiel** — elle attend une décision explicite de l'admin. Un porteur de
jeton ne peut donc pas modifier les données d'entrée du solveur, seulement
proposer.

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

Succès et échec répondent tous deux une redirection vers la **même page**, donc
ni le statut ni `Location` ne les distinguent. Les deux se lisent ailleurs :
l'échec par l'`AuthenticationFailureEvent` de Quarkus, qui porte le contexte
HTTP donc l'adresse ; le succès sur la requête elle-même — l'événement Quarkus
correspondant ne transporte aucun contexte, donc aucune adresse à qui rendre
son crédit.

**L'adresse retenue est celle annoncée par `X-Forwarded-For`**, la connexion
réelle ne servant que faute de mieux : derrière un proxy, toutes les requêtes
arrivent de la même adresse, et compter là-dessus laisserait le premier
attaquant venu verrouiller la connexion de tout le monde. Cet en-tête n'est
digne de confiance qu'à une condition : **l'origine ne doit pas être joignable
sans passer par le proxy**.

Ce verrou ne remplace pas la limitation de débit par IP du proxy, qui vaut pour
tout le reste — exports, résolution, API entière.

### Lecture des créneaux d'un collègue

La route qui sert le sélecteur « son créneau que je veux en échange » est
**large par conception** : l'espace distribue déjà le trombinoscope complet, et
les créneaux d'un collègue sont ce que le planning imprimé fait circuler. Les
écritures de la foire, elles, portent leur autorisation dans le `WHERE` de leur
requête — le motif le plus sûr.

Ce qui reste inconfortable est l'**agrégat** : une seule session pourrait
reconstituer tout le planning nominatif de l'événement, mineurs compris. Deux
resserrements :

- **la foire doit être ouverte.** La règle est déclarée **sur la route**
  (`@FoireOpenRequired`), dans la forme des deux gardes voisines : on lit les
  trois exigences d'un coup d'œil au lieu de chercher un contrôle au fond d'une
  méthode. Le filtre tourne **après** l'authentification, donc un appelant
  anonyme reçoit son `401` sans apprendre si la foire est ouverte. Les
  écritures gardent leur contrôle dans le service — c'est là qu'est
  l'application réelle de la règle, et elle ne doit pas dépendre d'une route
  annotée ;
- **le collègue doit exister dans l'édition**, sinon `404` nu. Un `200 []`
  laisserait sonder les identifiants existants, et un corps d'erreur ne
  donnerait à lire qu'à celui qui sonde.

**Reste ouvert** : plafonner le nombre de collègues distincts consultés par
fenêtre — le seul contrôle visant réellement le balayage — et, plus en
profondeur, remplacer le trombinoscope entier par une recherche à la frappe.

## Analyse statique : les suppressions et leur justification

Le job `code` de `securite.yml` (Semgrep OSS) fait échouer la CI sur toute
règle qui trouve, quelle que soit sa sévérité. Une règle atteint aujourd'hui
neuf endroits du code, tous pour la même raison, et tous annotés `nosemgrep`
**site par site** plutôt que désactivés en bloc : une nouvelle concaténation
SQL non annotée continue donc de faire rougir la CI.

`java.lang.security.audit.formatted-sql-string` signale toute requête
construite par concaténation. Dans ce dépôt, ce qui est concaténé est
**toujours un identifiant** — un nom de table ou de colonne —, et SQL ne
permet pas de lier un identifiant en paramètre : `SELECT * FROM ?` n'existe
pas. Ces identifiants viennent sans exception de littéraux écrits dans le code
appelant ou de listes constantes (`DatabaseDumpService.TABLES`), jamais d'une
entrée utilisateur ; l'import de dump vérifie même la sienne contre
`ALLOWED_TABLES`. Les **valeurs**, elles, voyagent toutes en paramètres liés
(`ps.setString`, `ps.setLong`, `ps.setObject`).

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
  plutôt que de laisser passer un identifiant de développement ;
- **un volume de sauvegarde** monté sur `BACKUP_DIR`, où la tâche de nuit écrit
  ses `pg_dump`. C'est le seul endroit où l'application écrit sur le disque, et
  ces fichiers sont le jeu de données complet en clair — noms, dates de
  naissance et adresses des animateurs, mineurs compris. Ils se traitent comme
  la base elle-même : accès restreint sur l'hôte, recopie hors machine
  chiffrée, et jamais dans un ticket ni sur une instance de démonstration.
  L'application ne les expose par aucun endpoint, et il ne faut pas les servir
  depuis le reverse proxy non plus.

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
