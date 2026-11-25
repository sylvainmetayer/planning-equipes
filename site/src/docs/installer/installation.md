# Installer l'application sur votre serveur

En une heure environ, vous mettez en ligne Planning Équipes sur votre propre serveur : l'application et sa base PostgreSQL, derrière votre reverse proxy, avec un contrôle qui confirme que tout répond. Il vous faut un serveur Linux avec Docker Compose, un nom de domaine et un relais SMTP.

## Avant de commencer

| Il vous faut | Pourquoi |
| --- | --- |
| Un nom de domaine et un certificat TLS | `PUBLIC_URL` construit les liens d'espace **imprimés sur les PDF** : ils doivent rester joignables après l'événement, pas seulement pendant |
| Un relais SMTP authentifié | Codes d'accès, liens d'espace, plannings individuels, notifications d'échange : tout passe par l'e-mail ([les faire arriver](courriels.md)) |
| Un reverse proxy | Il termine le TLS et doit être le seul chemin vers l'application : [le durcissement du déploiement](securite.md) |
| Les informations légales de l'organisateur | Éditeur, hébergeur, contact, base légale, durée de conservation : ce sont les siennes, pas les vôtres ([les renseigner](mentions-legales.md)) |
| 2,5 Go de mémoire pour la pile | 2 Go pour l'application, 512 Mo pour PostgreSQL ([pourquoi autant](calcul-du-planning.md#la-mémoire-de-lapplication)) |

## Ce que vous recevez

| Élément | Contenu |
| --- | --- |
| L'image | `ghcr.io/sylvainmetayer/planning-equipes`, publiée sans authentification, avec une étiquette par version (`1.2.0`, `1.2`), plus `main` (la recette, reconstruite à chaque fusion) et `latest`. Elle embarque l'application, l'interface et le client `pg_dump` de la sauvegarde ; elle écoute sur le port 8080 et tourne sous l'utilisateur 1000. Le SBOM et la signature de chaque release sont décrits dans [les numéros de version](versioning.md) |
| `docker-compose.prod.yml` | La pile de production : l'application et PostgreSQL 19, rien d'autre |
| `.env.prod.example` | Le fichier d'environnement à copier en `.env.prod`, chaque variable commentée |
| `docker-compose.yml` | Une pile de **développement**, à ne pas déployer : elle publie PostgreSQL sur l'hôte, ajoute pgAdmin et un puits SMTP |
| `scripts/verifier-deploiement.sh` | Le contrôle de fin de déploiement (disponibilité, version, marque, mentions légales) |
| `scripts/restaurer.sh` | La restauration guidée d'une sauvegarde ([la procédure](sauvegarde-et-restauration.md)) |

Les fichiers de déploiement se prennent dans le [dépôt du projet](repo:docker-compose.prod.yml), à l'étiquette qui correspond à la version que vous allez déployer.

## Mettre la pile en route

1. **Écrivez l'environnement.** Copiez le modèle, puis remplissez au moins les sept variables obligatoires et les mentions légales. Ne commitez jamais ce fichier : il porte des secrets.

   ```bash
   cp .env.prod.example .env.prod
   ```

   Les sept variables obligatoires sont `APP_VERSION`, `DB_PASSWORD`, `ADMIN_PASSWORD`, `SESSION_ENCRYPTION_KEY`, `PUBLIC_URL`, `MAIL_HOST` et `MAIL_FROM`. Le compose refuse de démarrer si l'une manque, et l'application refuse de son côté les deux mots de passe d'exemple et cinq mentions légales vides. La liste complète de ses refus ouvre [l'inventaire des variables](variables-environnement.md#obligatoires-en-production).

2. **Choisissez une version nommée.** `APP_VERSION=1.2.0` dans le `.env.prod`, jamais `main` ni `latest`. `main` est l'image de recette, reconstruite à chaque fusion et chaque dimanche ; `latest` désigne ce que le registre veut bien. Un déploiement doit être nommable dans un rapport d'incident : « la 1.2.0 » en est un, « l'image de mardi » non. Ce que promet un numéro est dans [les numéros de version](versioning.md).

3. **Préparez le répertoire des sauvegardes.** La sauvegarde de nuit écrit dans `./backup`, à côté du fichier compose. Il doit appartenir à l'utilisateur 1000, sinon Docker le crée en `root` et la première sauvegarde échoue.

   ```bash
   mkdir -p backup && sudo chown 1000:1000 backup
   ```

4. **Démarrez.**

   ```bash
   docker compose -f docker-compose.prod.yml --env-file .env.prod up -d
   ```

   Au premier démarrage, l'application applique ses migrations seule avant de répondre ; le contrôle de l'étape suivante patiente pour cela.

5. **Vérifiez.** Le script livré dans le dépôt s'appelle depuis le répertoire du `.env.prod` (bash, `curl` et `jq`) :

   ```bash
   scripts/verifier-deploiement.sh https://<domaine>          # version lue dans APP_VERSION
   scripts/verifier-deploiement.sh https://<domaine> 1.2.0    # ou donnée explicitement
   ```

   Il enchaîne les contrôles ci-dessous et s'arrête au premier échec avec un code de sortie non nul. Il ne demande ni accès à l'hôte ni identifiant : une CI de déploiement peut l'appeler telle quelle.

   | Contrôle | Ce qui est vérifié |
   | --- | --- |
   | Disponibilité | `/q/health/ready` répond `UP` : base joignable, aucune migration en attente ni en échec. L'attente est bornée (120 s, `--attente`) pour laisser passer les migrations d'un premier démarrage |
   | Version | la version exposée par `/api/config` est celle attendue (`--version-libre` tant que `APP_VERSION=main`) |
   | Marque et mentions | `/api/branding` et `/api/mentions-legales` répondent |
   | Mentions obligatoires | aucune des cinq mentions exigées n'est vide ; sauté si l'instance se déclare de démonstration (`demoInstance` de `/api/mentions-legales`, soit `LEGAL_DEMO_INSTANCE=true` tel que l'application l'a lu), ou avec `--instance-demo` |

   Le pied de page de l'application (administration comme espace animateur) et la page *Débogage* (hors menu : Ctrl+K, ou l'adresse `/debug`, onglet *Résolution*) affichent la version déployée : le tag `vX.Y.Z` pour une image de release, l'identifiant du commit pour une image de recette. C'est ce qu'un rapport de faille doit citer ([signaler une faille](repo:.github/SECURITY.md)).

6. **Ouvrez l'administration.** Connectez-vous avec le compte `admin` et le mot de passe `ADMIN_PASSWORD`. Si vous branchez un fournisseur d'identité, créez d'abord votre compte nominatif ([OIDC](oidc.md), [comptes locaux](comptes-locaux.md)). Réglez ensuite les paramètres de l'instance, puis chargez ou saisissez la première édition : le guide [Préparer votre première édition](../regles/preparer-edition.md) prend la suite.

## Ce que fait la pile de production

- **L'application** n'est publiée que sur la boucle locale de l'hôte (`127.0.0.1:8080`) : c'est votre reverse proxy qui termine le TLS et qui est le seul chemin d'entrée. Son interface de supervision (port 9000, métriques) n'est jamais publiée.
- **PostgreSQL** n'a aucun port publié : seul le réseau interne de la pile le voit. Ses données vivent dans un volume nommé, et l'application applique ses migrations seule, au démarrage.
- **Les ressources** : 2 Go pour l'application (la JVM en prend les trois quarts) et 512 Mo pour la base, soit 2,5 Go pour la pile.
- **La sauvegarde de nuit** écrit dans `./backup`, répertoire de l'hôte qui doit appartenir à l'utilisateur 1000 ([sauvegarder et restaurer](sauvegarde-et-restauration.md)).
- **Les journaux** des conteneurs tournent (5 fichiers de 20 Mo par service par défaut, `LOG_MAX_SIZE` et `LOG_MAX_FILE`) pour qu'un disque plein ne fasse pas échouer la sauvegarde ([les journaux](surveillance.md#lire-et-limiter-les-journaux)).
- **Le contrôle de santé** du service `app` interroge `/q/health/ready` : `docker compose ps` l'affiche `healthy` une fois les migrations passées (jusqu'à 180 s de grâce au démarrage).

## Et ensuite

| Je veux… | Guide |
| --- | --- |
| Faire arriver les e-mails jusqu'aux boîtes de réception | [Faire arriver vos e-mails](courriels.md) |
| Renseigner les mentions légales de l'organisateur | [Renseigner les mentions légales](mentions-legales.md) |
| Ne jamais perdre les données | [Sauvegarder et restaurer](sauvegarde-et-restauration.md) |
| Savoir que l'instance tourne | [Surveiller l'instance](surveillance.md) |
