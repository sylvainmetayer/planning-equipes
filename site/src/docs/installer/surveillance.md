# Surveiller l'instance

Vous mettez en place de quoi apprendre qu'une instance est tombée, qu'une sauvegarde n'a pas tourné ou qu'une erreur est survenue avant que ce soit un animateur qui vous le dise. L'application ne peut pas surveiller sa propre absence : il faut au moins une sonde externe.

## Brancher le suivi d'erreurs

`SENTRY_DSN` vaut `""` par défaut : sans lui, aucune erreur ne remonte et les pannes s'apprennent par téléphone. Le câblage existe, il ne manque que la variable : [le suivi d'erreurs et les métriques](observabilite.md).

## Sonder l'extérieur

Une sonde externe sur **`/q/health/ready`** (sans authentification) suffit à savoir qu'une instance est tombée avant que le client ne le découvre. Deux sondes, qui ne répondent que `UP` ou `DOWN` et le nom de leurs contrôles, ni URL JDBC ni hôte :

| Sonde | `UP` quand | Usage |
| --- | --- | --- |
| `/q/health/live` | le processus répond | redémarrer un conteneur figé ; ne dépend **pas** de la base, qu'un redémarrage ne réparerait pas |
| `/q/health/ready` | base joignable **et** historique Flyway à jour (aucune migration en attente ni en échec) : un seul contrôle, qui lit l'historique et échoue donc d'abord quand la base ne répond pas | sonde externe, `healthcheck` du service `app` (`docker compose ps` l'affiche `healthy`) |

Ce que la disponibilité signale avant tout, c'est **la base injoignable**. Une migration interrompue, elle, ne produit pas d'instance `DOWN` : l'application migre au démarrage et **refuse de démarrer** sur une migration en échec ou en attente, `FLYWAY_REPAIR_AT_START` la débloquant ; elle fait de même sur une base en avance sur l'image, `ALLOW_SCHEMA_AHEAD` forçant le démarrage ([ces deux cas](mettre-a-jour.md#débloquer-une-migration-interrompue)). La lecture de l'historique est une défense en profondeur contre un historique qui change sous une instance en marche (une autre instance, ou vous, qui migrez la même base avec un binaire plus récent). Le verdict est gardé 5 s : une rafale d'appels anonymes ne relit pas l'historique à chaque fois.

L'image n'embarque pas `curl` : le `healthcheck` du compose écrit la requête en bash sur `/dev/tcp`.

## Être prévenu d'une sauvegarde en échec

Un échec de la sauvegarde de nuit part par e-mail à `MAIL_ADMIN` ([le détail](sauvegarde-et-restauration.md#suivre-létat-de-la-sauvegarde)). Laisser cette variable vide, c'est ne l'apprendre qu'en ouvrant l'écran *Paramètres*.

## Lire les métriques

`/q/metrics` au format Prometheus, sur le **port 9000** (interface de management), que `docker-compose.prod.yml` ne publie pas. Un Prometheus ajouté à la pile le lit sur `http://app:9000/q/metrics`. Sans authentification, donc **jamais** publié sur l'hôte ni routé par le reverse proxy ; le port applicatif, lui, répond `404` sur ce chemin.

On y lit la durée des résolutions et la file du solveur, la latence de l'API, les mails refusés par le SMTP et l'âge de la dernière sauvegarde réussie (`planning_backup_last_success_timestamp_seconds`), de quoi alerter sur une sauvegarde qui n'a pas tourné même quand le mail d'échec n'est pas parti. La liste complète et les règles de cardinalité sont dans [les métriques](observabilite.md#métriques).

## Lire et limiter les journaux

`docker compose logs` reste la première chose à lire :

```bash
docker compose -f docker-compose.prod.yml logs -f app
```

L'application écrit sur la sortie standard, la rotation relève donc du moteur de conteneurs, et `docker-compose.prod.yml` la règle lui-même pour `app` et `postgres` : pilote `local` (compressé, rotation native), **5 fichiers de 20 Mo, soit environ 100 Mo par service** au plus. Sans ce réglage, le pilote par défaut de Docker grossit sans limite, et un disque rempli par les journaux fait aussi échouer la sauvegarde de nuit, qui écrit sur le même disque.

| Point | Détail |
| --- | --- |
| Ajuster | `LOG_MAX_SIZE` (taille d'un fichier, `20m` par défaut) et `LOG_MAX_FILE` (nombre de fichiers gardés, `5`) dans le `.env.prod`, puis `docker compose … up -d` pour recréer les conteneurs : un réglage de journalisation ne s'applique qu'à la création. Ce sont deux variables du compose, pas de l'application |
| `daemon.json` | le bloc `logging` du compose **l'emporte** sur `/etc/docker/daemon.json` pour ces deux services ; un réglage global de l'hôte reste valable pour ses autres conteneurs |
| Fichiers sur l'hôte | avec le pilote `local`, les fichiers `/var/lib/docker/containers/*/…-json.log` n'existent plus : le format est binaire et compressé. Ce qui les lisait directement passe par `docker compose logs` ou `docker logs` |
| Événement bavard | résolutions en boucle, SMTP qui refuse : les journaux anciens partent vite. Les **erreurs** ne dépendent pas d'eux, elles remontent à Sentry (`SENTRY_DSN`), qui reste leur canal |
| Vérifier | `docker inspect <conteneur> --format '{{.HostConfig.LogConfig}}'` affiche le pilote et les deux plafonds |

### Exporter vers un collecteur

Exporter vers un collecteur (syslog, journald, GELF pour Graylog…) est un choix que la pile n'impose pas : un fichier `docker-compose.override.yml` à côté du compose, passé en second `-f`, remplace le bloc `logging` des deux services, par exemple :

```yaml
services:
  app:
    logging:
      driver: journald
  postgres:
    logging:
      driver: journald
```

Le fichier se donne à chaque commande :

```bash
docker compose -f docker-compose.prod.yml -f docker-compose.override.yml \
  --env-file .env.prod up -d
```

`docker compose logs` continue de fonctionner avec un pilote distant grâce au cache local du moteur (*dual logging*, Docker 20.10 et suivants), mais ce cache a ses propres plafonds (`cache-max-size`, `cache-max-file`) : `LOG_MAX_SIZE` et `LOG_MAX_FILE` ne s'appliquent plus.
