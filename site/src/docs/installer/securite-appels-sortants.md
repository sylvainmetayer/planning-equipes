# Maîtriser ce qui sort de l'instance

Tant que vous ne configurez rien, l'application ne joint que le relais SMTP et le suivi d'erreurs. Les webhooks, l'alerte météo et la machine de calcul déportée ajoutent des sorties : cette page dit ce qui part vers qui, ce que la garde anti-SSRF refuse et comment couper chaque sortie.

## Appels sortants

L'application ne parlait au monde extérieur que par SMTP et Sentry. Les
**webhooks** et l'**alerte météo** lui font émettre des requêtes HTTP. Les
premiers visent une adresse **tapée par un administrateur** : c'est le cas
d'école du SSRF — se servir du serveur pour joindre ce que lui seul peut joindre (le port de PostgreSQL, le service de
métadonnées d'un cloud en `169.254.169.254`, une interface d'administration
liée à la boucle locale). La décision et ses raisons :
[ADR 0074](repo:docs/decisions/0074-deux-jobs-planifies-de-plus-webhooks-et-meteo.md).

### Ce que la garde refuse

`OutboundGuard` décide, à l'**enregistrement** du webhook puis **à chaque
envoi** (un nom qui se résolvait vers l'extérieur hier peut viser l'intérieur
aujourd'hui) :

| Règle | Détail |
| --- | --- |
| `https://` seulement | `http://` n'est accepté que vers un réseau autorisé |
| Ports 443 et 8443 | tout port vers un réseau autorisé |
| Aucune adresse interne | bouclage (`127.0.0.0/8`, `::1`), privé RFC 1918, lien local (`169.254.0.0/16` — métadonnées cloud —, `fe80::/10`), ULA `fc00::/7`, CGNAT `100.64.0.0/10`, multicast, non spécifiée (`0.0.0.0`, `::`), réservée ; une adresse IPv6 qui embarque une IPv4 — `::ffff:a.b.c.d`, `::a.b.c.d`, NAT64 `64:ff9b::/96`, 6to4 `2002::/16`, Teredo `2001::/32` — est jugée sur l'IPv4 qu'elle porte, et le NAT64 local `64:ff9b:1::/48` est refusé |
| Pas d'identifiants dans l'URL | `utilisateur:motdepasse@` refusé |

Le nom est **résolu par la garde**, toutes ses adresses vérifiées, et la
connexion part vers **l'adresse vérifiée** : le client HTTP (Vert.x) ne
résout pas le nom une seconde fois. Un nom qui répondrait une adresse publique
à la vérification et une adresse interne à la connexion (*DNS rebinding*) n'a
donc plus de fenêtre. TLS vérifie toujours le certificat contre le **nom**
d'hôte, pas contre l'adresse.

**Réseaux autorisés.** Un n8n sur le même réseau Docker est légitime :
`WEBHOOKS_RESEAUX_AUTORISES` liste les adresses et blocs CIDR qui échappent à
la garde (mêmes règles d'écriture que `CONNEXION_PROXYS_FIABLES` ; une entrée
mal formée empêche le démarrage). Ouvrir un bloc, c'est ouvrir **toutes** ses
machines à un administrateur de l'application : déclarez le sous-réseau du
seul conteneur visé, pas `10.0.0.0/8`.

### Ce que le récepteur ne peut pas faire

- **Rediriger** : une redirection n'est jamais suivie (l'adresse qu'elle nomme
  n'a pas été vérifiée) ; la livraison est abandonnée.
- **Être lu** : le corps de la réponse est vidé et jeté, jamais stocké, jamais
  montré. Le journal des livraisons ne garde qu'un code, une durée et une
  phrase fixe — jamais le message d'une exception, qui peut recopier la ligne
  de requête et donc une adresse Slack secrète. Un webhook ne sert pas à
  *lire* un service interne.
- **Faire attendre** : résolution du nom 5 s, connexion 5 s, puis 10 s pour
  tout l'échange, de l'envoi à la fin de la réponse — passé ce délai la
  connexion est coupée, même si le récepteur envoie encore quelques octets.
  L'envoi part d'un fil à lui — jamais du fil de la publication ou de la
  résolution qui l'a causé.

### Les secrets au repos

Le secret HMAC d'un webhook générique, l'adresse d'un webhook Slack, Discord
ou Matrix (qui **est** le secret : la détenir suffit à écrire dans le salon) et
le jeton d'un bot Telegram sont **chiffrés** en base (AES-256-GCM, clé
`WEBHOOKS_SECRET_KEY`). L'export SQL ne les contient pas du tout ; la
sauvegarde nocturne, qui prend tout le cluster, n'en contient que le chiffré.
L'écran ne les réaffiche jamais : le secret HMAC est montré une fois, à sa
génération ; une adresse de salon apparaît réduite à son hôte. Le journal des
actions note les **noms** des champs modifiés, jamais leur valeur. Gestion de
la clé : [`exploitation.md`](exploitation.md), à la rubrique des variables d'environnement.

### Couper les webhooks

`WEBHOOKS_ENABLED=false` coupe toute sortie : rien n'est mis en file, rien
n'est envoyé, le test est refusé ; les livraisons déjà en attente reprennent
quand la variable revient à `true` (dans la limite de leurs 30 jours).

Aucun outil MCP ne configure un webhook : une adresse de salon est un secret,
et ouvrir une sortie réseau depuis une conversation est une voie
d'exfiltration — voir [`mcp.md`](../comprendre/mcp.md).

### L'alerte météo

Chaque matin, l'édition active interroge Open-Meteo (`METEO_URL`) sur les
lieux de ses stands ouverts. Ce qui part : des **coordonnées de lieux**,
arrondies à 0,01° (environ un kilomètre), des dates et l'adresse IP du
serveur — aucune donnée personnelle. Le navigateur n'appelle jamais le
service : la CSP (`connect-src`) n'a rien à ouvrir, et l'adresse de
l'organisateur ne part chez aucun tiers.

`METEO_URL` est réglée par l'exploitant, pas tapée par un utilisateur : la
garde des webhooks **ne s'y applique pas**, faute de quoi une instance
Open-Meteo auto-hébergée sur le réseau Docker serait refusée. Les redirections
ne sont pas suivies, l'échange est borné à 10 s et la réponse à 2 Mo. Une
réponse illisible ou un service muet n'est jamais propagé : il est noté
(« injoignable depuis… ») et dit une fois à l'administrateur au deuxième jour.
`METEO_ENABLED=false` coupe la fonctionnalité pour l'instance.

## Calcul déporté : ce qui sort, et vers qui

Une instance peut confier ses résolutions à un *worker* sur une autre machine
([ADR 0086](repo:docs/decisions/0086-le-calcul-deporte-un-worker-sans-conteneur-et-un-crochet.md),
mise en place dans [`exploitation.md`](exploitation.md), rubrique « Résoudre sur une
autre machine »). Sans configuration, rien ne sort.

| Sujet | Ce qui est fait |
| --- | --- |
| Sens des connexions | Toutes partent de l'application. Le worker et le lanceur n'appellent personne |
| Authentification du worker | Un secret partagé sur chaque appel, comparé en temps constant. Pour une machine à la demande il est **dérivé** (HMAC du secret du crochet et d'un aléa par machine) : jamais stocké, et sans valeur pour une autre machine |
| Chiffrement | HTTPS, certificat **épinglé** par son empreinte SHA-256. Un worker sans certificat n'écoute que la boucle locale, et l'application refuse de démarrer sur une adresse `http://` qui n'y est pas. Le lanceur, lui, est accepté en `http://` sur la boucle locale ou un réseau privé seulement : son secret voyage dans chaque appel |
| Ce que le worker reçoit | Le problème sous pseudonymes : ni nom, ni adresse, ni téléphone, ni jeton, ni texte libre (voir [ce que l'instance conserve](rgpd-limites-a-consigner.md#le-calcul-déporté-envoie-un-problème-pseudonymisé)) |
| Ce que le worker garde | Rien sur disque ; un problème et son plan en mémoire, remplacés par le suivant |
| Le plan qui revient | Rescoré par l'application sur son propre problème ; un score différent de celui annoncé, un siège inconnu ou épinglé déplacé, et le plan est refusé |
| Création de machines | Hors de l'application : elle ne détient aucun jeton d'hébergeur. Aucun outil MCP ne prépare ni n'arrête une machine |
| Adresses du worker et du lanceur | Celles de l'exploitant, en variables d'instance : la garde anti-SSRF des webhooks ne s'y applique pas, comme pour `METEO_URL` |

Trois limites à connaître :

- les secrets d'une machine à la demande (secret du worker, clé de son
  certificat) transitent par le cloud-init : l'hébergeur les voit, et le
  service de métadonnées de la machine les rend à tout processus local. Ils ne
  valent que pour cette machine et pour sa durée de vie ;
- le worker ne limite pas le débit des appels refusés : sa protection est un
  secret de 256 bits et un pare-feu qui n'ouvre son port qu'à l'application ;
- le lanceur d'exemple est un exemple : il n'est pas exposé, pas durci, et son
  jeton doit être celui d'un projet qui ne contient que des workers.
