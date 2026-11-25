# Installer l'application

Cette page est le point d'entrée de la partie *Infra* : ce qu'il faut pour
héberger Planning Équipes, ce que contient la pile de production et dans quel
ordre la mettre en route. Chaque point renvoie au document qui le traite en
détail ; l'inventaire de tous les réglages est dans
[`variables-environnement.md`](variables-environnement.md).

## Ce qui est livré

| Élément | Contenu |
| --- | --- |
| L'image | `ghcr.io/sylvainmetayer/planning-equipes`, publiée sans authentification, une étiquette par version (`1.2.0`, `1.2`), plus `main` (la recette, reconstruite à chaque fusion) et `latest`. Elle embarque l'application, l'interface et le client `pg_dump` de la sauvegarde ; elle écoute sur le port 8080 et tourne sous l'utilisateur 1000. Le SBOM et la signature de chaque release sont décrits dans [`versioning.md`](versioning.md) |
| `docker-compose.prod.yml` | La pile de production : l'application et PostgreSQL 19, rien d'autre |
| `.env.prod.example` | Le fichier d'environnement à copier en `.env.prod`, chaque variable commentée |
| `docker-compose.yml` | Une pile de **développement**, à ne pas déployer : elle publie PostgreSQL sur l'hôte, ajoute pgAdmin et un puits SMTP |
| `scripts/verifier-deploiement.sh` | Le contrôle de fin de déploiement (disponibilité, version, marque, mentions légales) |

## Ce que fait la pile de production

- **L'application** n'est publiée que sur la boucle locale de l'hôte
  (`127.0.0.1:8080`) : c'est un reverse proxy qui termine le TLS et qui est le
  seul chemin d'entrée. Son interface de supervision (port 9000, métriques) n'est
  jamais publiée.
- **PostgreSQL** n'a aucun port publié : seul le réseau interne de la pile le
  voit. Ses données vivent dans un volume nommé ; l'application applique ses
  migrations seule, au démarrage.
- **Les ressources** : 2 Go pour l'application (la JVM en prend les trois
  quarts) et 512 Mo pour la base, soit 2,5 Go pour la pile — voir
  [la mémoire de l'application](exploitation.md#la-mémoire-de-lapplication).
- **La sauvegarde de nuit** écrit dans `./backup`, un répertoire de l'hôte qui
  doit appartenir à l'utilisateur 1000 ; voir
  [Sauvegarde et restauration](exploitation.md#5-sauvegarde-et-restauration).
- **Les journaux** des conteneurs tournent (5 fichiers de 20 Mo par service par
  défaut, `LOG_MAX_SIZE` et `LOG_MAX_FILE`) pour qu'un disque plein ne fasse pas
  échouer la sauvegarde.
- **Sept variables sont obligatoires** — `APP_VERSION`, `DB_PASSWORD`,
  `ADMIN_PASSWORD`, `SESSION_ENCRYPTION_KEY`, `PUBLIC_URL`, `MAIL_HOST`,
  `MAIL_FROM` — et le compose refuse de démarrer sans elles. L'application
  refuse de son côté les deux mots de passe d'exemple et cinq mentions légales
  vides ; la liste complète de ses refus est en tête de
  [`variables-environnement.md`](variables-environnement.md#obligatoires-en-production).

## Mise en route

1. **Préparer l'hôte** : un nom de domaine et un certificat, un relais SMTP, un
   reverse proxy (voir [`securite.md`](securite.md#déploiement)) et les
   informations légales de l'organisateur
   ([`exploitation.md`](exploitation.md#1-avant-dinstaller)).
2. **Écrire l'environnement** : `cp .env.prod.example .env.prod`, puis remplir
   au moins les sept variables obligatoires et les mentions légales. Ne
   jamais committer ce fichier.
3. **Préparer la sauvegarde** : `mkdir -p backup && sudo chown 1000:1000 backup`.
4. **Démarrer** :

   ```bash
   docker compose -f docker-compose.prod.yml --env-file .env.prod up -d
   ```

5. **Vérifier** : `scripts/verifier-deploiement.sh https://<domaine>`, lancé depuis
   le répertoire du `.env.prod` (détail dans
   [`exploitation.md`](exploitation.md#2-les-variables-denvironnement)).
6. **Ouvrir l'administration**, créer son compte nominatif si l'on branche un
   fournisseur d'identité ([`oidc.md`](oidc.md)), régler les paramètres de
   l'instance et charger ou saisir la première édition.

## Les sujets de la partie Infra

| Sujet | Document |
| --- | --- |
| Tous les réglages, un par un | [`variables-environnement.md`](variables-environnement.md) |
| Prérequis, courriels, sauvegarde et restauration, conservation, surveillance, redéploiement, calcul sur une autre machine | [`exploitation.md`](exploitation.md) |
| Durcissement : en-têtes, plafonds, limitation de débit, rôles, appels sortants, déploiement | [`securite.md`](securite.md) |
| Connexion par un fournisseur d'identité, comptes locaux, compte de secours | [`oidc.md`](oidc.md) |
| Suivi d'erreurs et métriques | [`observabilite.md`](observabilite.md) |
| Numéros de version, releases, déployer et patcher | [`versioning.md`](versioning.md) |
| Ce que l'hébergeur doit écrire et tenir (sous-traitance, registre, atteintes) | [`rgpd.md`](rgpd.md) |
| Déclaration d'accessibilité | [`accessibilite.md`](accessibilite.md) |
| Licences des composants redistribués | [`licences-tierces.md`](licences-tierces.md) |
