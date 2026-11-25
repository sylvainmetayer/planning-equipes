# Exposer l'instance sur Internet sans mauvaise surprise

Avant d'ouvrir l'instance au public, parcourez la liste courte ci-dessous, démarrez la pile de production et réglez votre reverse proxy : ces trois gestes couvrent ce que l'application ne peut pas faire à votre place. Le reste, elle l'applique d'elle-même (en-têtes de sécurité, plafonds de débit, verrous de connexion), et les pages voisines le détaillent pour que vous sachiez quoi régler et pourquoi.

Pour un aperçu de ce qu'on attend d'un hébergeur, avant les détails, voir [héberger Planning Équipes soi-même](/heberger/).

## Avant d'ouvrir : la liste courte

- [ ] `ADMIN_PASSWORD` long, généré, propre à ce déploiement : l'application
  **refuse de démarrer** en production s'il est resté sur l'exemple, `DB_PASSWORD`
  compris ;
- [ ] `SESSION_ENCRYPTION_KEY` définie (≥ 16 caractères) et gardée ;
- [ ] `DB_PASSWORD` changé ;
- [ ] `PUBLIC_URL` en `https://` ;
- [ ] `PLANNING_MCP_API_KEY` laissée vide tant que le serveur MCP ne sert à
      personne (vide, `/mcp` répond `401` à tout ; renseignée, elle appelle
      `CONNEXION_PROXYS_FIABLES` avec elle ; voir [les limites de débit](securite-limites-de-debit.md#débit-du-serveur-mcp)) ;
- [ ] `CONNEXION_PROXYS_FIABLES` renseignée avant de créer un lien
      d'affichage mural, derrière un proxy (voir [la connexion à l'administration](securite-acces-administration.md#verrouiller-le-formulaire-de-connexion-de-ladmin)) ;
- [ ] derrière un fournisseur OIDC : un compte administrateur nominatif créé et
      essayé **avant** de laisser le secours fermé, `ADMIN_LOCAL_SECOURS` absent
      ou `false` en exploitation courante, `OIDC_CLIENT_SECRET` d'au moins 32
      caractères ;
- [ ] aucune variable `REMOTE_USER_*` dans votre environnement : le mode
      en-tête de proxy est retiré en 4.0, le démarrage les signale et les
      ignore ;
- [ ] variable `LEGAL_MENTIONS` renseignée : `/mentions-legales` est public, et une
      page de mentions légales vide vaut absence de mentions légales. Cinq
      d'entre elles (éditeur, hébergeur, contact, base légale, conservation)
      **font échouer le démarrage**, donc cette case se coche toute seule ;
      les deux autres restent à votre main (voir les mentions légales dans [`exploitation.md`](exploitation.md)) ;
- [ ] jeu de données de production chargé, jamais les fixtures de test.

## Démarrer la pile de production

`docker-compose.yml` est une pile de **développement** : elle publie PostgreSQL
sur l'hôte, ajoute pgAdmin en `admin/admin` sans mode serveur, un puits SMTP
qui n'envoie rien, et des mots de passe `festival`/`admin`. Rien de tout cela
n'a à exister sur une machine exposée, et « on changera les mots de passe »
n'enlève ni pgAdmin ni le port 5432.

`docker-compose.prod.yml` est la pile correspondante :

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d
```

Ce qu'elle change :

- **une version nommée** : `APP_VERSION` est obligatoire (un `vX.Y.Z`, jamais `:main` ni `:latest`, voir [`versioning.md`](versioning.md)) ;
- **un seul port publié**, sur la boucle locale (`127.0.0.1:8080`) : le reverse
  proxy est le seul chemin d'entrée ;
- **PostgreSQL n'est plus publié du tout** : seul le réseau interne le voit ;
- **ni pgAdmin ni Mailpit** ;
- **aucune valeur par défaut sur les secrets** : `DB_PASSWORD`,
  `ADMIN_PASSWORD`, `SESSION_ENCRYPTION_KEY`, `PUBLIC_URL`, `MAIL_HOST` et
  `MAIL_FROM` font échouer le démarrage si l'environnement ne les fournit pas,
  plutôt que de laisser passer un identifiant de développement ;
- **un répertoire de sauvegarde** de l'hôte (`./backup`) monté sur `BACKUP_DIR`, où la tâche de nuit écrit
  ses `pg_dump`. C'est le seul endroit où l'application écrit sur le disque, et
  ces fichiers sont le jeu de données complet en clair : noms, dates de
  naissance et adresses des animateurs, mineurs compris. Ils se traitent comme
  la base elle-même : accès restreint sur l'hôte, recopie hors machine
  chiffrée, et jamais dans un ticket ni sur une instance de démonstration.
  L'application ne les expose par aucun endpoint, et il ne faut pas les servir
  depuis le reverse proxy non plus.
- **deux sondes publiques**, `/q/health/live` et `/q/health/ready`, sans
  session : une sonde externe ou un orchestrateur ne sait pas s'authentifier.
  Elles ne rendent que `UP`/`DOWN`, le nom de leurs contrôles et deux compteurs
  de migrations. Le contrôle de source de données de Quarkus est coupé pour
  cette raison : en échec, il recopiait le message du pilote, donc l'hôte et
  le port de la base, dans la réponse. Voir « Savoir que ça tourne » dans [`exploitation.md`](exploitation.md).
- **le port 9000 n'est pas publié** : c'est l'interface de management, qui
  sert les métriques Prometheus (`/q/metrics`) **sans authentification**. Sa
  seule protection est de n'être joignable que du réseau interne de la pile ;
  l'image ne le déclare pas en `EXPOSE` pour qu'un `docker run -P` ne le
  publie pas par mégarde. Le port applicatif ne sert pas les métriques (`404`,
  `MetricsEndpointTest`), et aucune étiquette ne porte de jeton, d'adresse ni
  d'identifiant ; voir la rubrique Métriques de [`observabilite.md`](observabilite.md).

## Ce qui reste à la charge du reverse proxy

L'application ne peut pas s'en occuper à sa place, et ces points sont des
**prérequis** de ce qui précède :

| À faire | Pourquoi |
| --- | --- |
| Terminer le TLS et rediriger tout le trafic http vers https | HSTS et le flag `Secure` du cookie de l'espace ne s'activent que sur une visite HTTPS |
| **Renseigner `CONNEXION_PROXYS_FIABLES`** avec les adresses de vos proxys inverses (littérales ou blocs CIDR) | Sans elle, les deux plafonds par adresse ignorent `X-Forwarded-For` et comptent tous les visiteurs derrière le proxy sur un seul compteur : c'est sûr, mais le premier attaquant venu verrouille tout le monde. **Obligatoire dès que `/mcp` sert** : ce plafond-là compte chaque requête, pas les seuls échecs. **Obligatoire aussi dès qu'un lien d'affichage mural existe** : sans elle, quelques requêtes au hasard verrouillent l'adresse du proxy, et un écran nouvellement branché, ou relancé après un redémarrage de l'application, n'affiche rien jusqu'à la fin du verrou. `QUARKUS_HTTP_PROXY_TRUSTED_PROXIES` ne remplace pas ce réglage : il décide si l'en-tête est lu, jamais quel élément est retenu |
| **Rendre l'origine injoignable autrement que par le proxy** (pare-feu, réseau) | Sans cela, `X-Forwarded-Proto` reste forgeable, et un attaquant qui joint l'origine directement est compté sur sa vraie adresse, ce qui est correct mais le prive du bénéfice de la liste ci-dessus |
| Limiter le débit par adresse IP sur tout le site | Les plafonds de l'application sont ciblés (connexion admin, codes de l'espace, serveur MCP, affichage mural) ; le reste (exports, API) n'en a pas. Le calcul, lui, a un quota d'instance facultatif (voir le [quota de calcul](securite-limites-de-debit.md#quota-de-calcul)), qu'une limite par adresse ne remplace pas |
| Journaliser sans les URL de l'espace animateur, **de l'abonnement ICS ni de l'affichage mural**, ou purger ces journaux | Les trois jetons voyagent **dans le chemin** : ils atterrissent tels quels dans les journaux d'accès, l'abonnement y revient à chaque synchronisation d'un agenda et l'affichage mural chaque minute |
| Ne jamais router le port 9000 (métriques), ni le publier sur l'hôte | Il n'a pas d'authentification : il est protégé par le réseau, pas par un mot de passe. Un scraper hors de la pile passe par un tunnel ou un réseau privé, pas par le proxy public |
| Réserver `/q/health/*` à la source de la supervision, si elle est connue | Rien de sensible n'y est lu, mais une sonde n'a pas à être joignable par le monde entier ; le `healthcheck` du compose passe par la boucle locale et n'en dépend pas |
| Ne pas réintroduire le site dans un index (page d'accueil du proxy, sitemap, annuaire interne) | L'application dit trois fois qu'elle ne veut pas être référencée (voir [ne pas être référencé, dit trois fois](securite-navigateur.md#ne-pas-être-référencé-dit-trois-fois)) ; un lien depuis une page publique, lui, se remarque |

## Plafonds du serveur HTTP

| Variable | Défaut | Ce qu'elle borne |
| --- | --- | --- |
| `MAX_BODY_SIZE` | `10M` | Taille maximale d'un corps de requête. Elle dimensionnait l'import de dump SQL, désormais réservé aux tests : un déploiement gagne à la descendre franchement, l'import d'un scénario YAML étant le plus gros corps attendu |
| `MAX_CONNECTIONS` | `500` | Connexions simultanées acceptées |
| `HTTP_IDLE_TIMEOUT` | `PT16M` | Délai au bout duquel une connexion restée muette est fermée, ce qui libère sa place dans `MAX_CONNECTIONS` |

Le plafond de connexions est ce qui empêche d'épuiser le serveur en ouvrant des
connexions sans jamais rien envoyer. `500` est large au regard de l'usage réel,
donc sans effet sur le trafic légitime.

Les deux réglages vont ensemble : une connexion inactive compte dans le plafond
jusqu'à ce que le délai d'inactivité la ferme. Celui de Quarkus (30 min) est
ramené à 16 min, juste au-dessus du plus long appel synchrone de l'API : un
calcul lancé par `POST /api/solve`, qui n'écrit rien avant sa fin
(900 s par défaut). Qui autorise des calculs synchrones plus longs relève
`HTTP_IDLE_TIMEOUT` d'autant ; le reverse proxy reste le bon endroit pour couper
plus tôt les clients inactifs.

L'**import CSV des animateurs** ajoute deux bornes qui lui sont propres, plus
serrées que `MAX_BODY_SIZE` et écrites dans le code plutôt qu'en configuration :
1 000 000 de caractères et 5 000 lignes de données. Le fichier est lu en
mémoire d'un seul tenant, et une liste de bénévoles pèse quelques dizaines de
kilo-octets, et un plafond commun avec celui des dumps SQL laisserait dix
mégaoctets de marge à un import qui n'en a aucun besoin. Le fichier n'est
jamais écrit sur disque : voir [`rgpd.md`](rgpd.md).

Ces plafonds ne remplacent pas ceux du reverse proxy, qui doit rester la
première ligne (limitation de débit par IP, plafond de connexions par client,
délais d'attente).

## Aller plus loin

| Pour… | Lisez |
| --- | --- |
| Régler `CSP` et `HSTS`, comprendre un blocage du navigateur | [Régler les en-têtes de sécurité du navigateur et la CSP](securite-navigateur.md) |
| Limiter les abus (codes, déclarations, MCP, quota de calcul) | [Limiter les abus : débit, verrous et quota de calcul](securite-limites-de-debit.md) |
| Choisir la porte de l'administration et garder le secours fermé | [Choisir et protéger la connexion à l'administration](securite-acces-administration.md) |
| Brancher votre annuaire d'entreprise | [Brancher votre annuaire d'entreprise (OIDC)](oidc.md) |
| Savoir ce que chaque rôle ouvre | [Savoir ce que chaque rôle peut appeler](securite-roles-et-droits.md) |
| Publier un agenda ou un écran mural sans session | [Ouvrir un agenda ou un écran sans ouvrir l'administration](securite-jetons-publics.md) |
| Maîtriser webhooks, météo et calcul déporté | [Maîtriser ce qui sort de l'instance](securite-appels-sortants.md) |
| Savoir ce que la chaîne de publication vérifie | [Les contrôles de sécurité qui tournent à chaque version](securite-analyses-automatiques.md) |
| Authentification et jetons côté API et MCP | [`api-conventions.md`](../comprendre/api-conventions.md), [`mcp-connexion.md`](../comprendre/mcp-connexion.md) |
