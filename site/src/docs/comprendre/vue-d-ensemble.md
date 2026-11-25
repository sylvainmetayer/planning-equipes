# La pile et le découpage du code

Planning Équipes tient dans un seul service : un Quarkus (Java 25) qui embarque le solveur Timefold dans la même JVM, sert l'interface Angular déjà compilée et parle à une base PostgreSQL. Vous saurez ici de quoi la pile est faite, comment le code est découpé, où se cache chaque capacité, et comment l'image et la base se comportent au démarrage.

Aucun micro-service, aucun serveur Node en production. Le modèle (postes, créneaux, animateurs) est supposé connu ; sinon, commencez par [le modèle des données](modele-des-donnees.md).

## La pile

| Couche | Choix |
| --- | --- |
| Backend | Quarkus : REST et ressources statiques dans le même déploiement |
| Optimisation | Timefold Solver Community, score `HardMediumSoftScore` ; un second moteur écrit à la main, arbitré par Timefold ([le calcul](comment-le-planning-est-calcule.md)) |
| Persistance | PostgreSQL et Flyway |
| Frontend | Angular 22, composants autonomes, signals, sans zone, servi par Quinoa ([le frontend](frontend.md)) |
| Exports | OpenPDF pour les PDF, `zxing-core` pour le QR code, ICS écrit dans le dépôt, toujours côté serveur |
| MCP | `quarkus-mcp-server-http` ([connexion MCP](mcp-connexion.md)) |
| Outils | `mise.toml` : Temurin 25, Maven 3.10, Node 26 |

## Le découpage du code

Le paquet racine est `dev.sylvain.planning` ([`src/main/java/dev/sylvain/planning`](repo:src/main/java/dev/sylvain/planning)). Chaque sous-paquet répond à une question.

| Paquet | Ce qu'il porte |
| --- | --- |
| `domain/` | Le modèle Timefold : `Animateur`, `Stand`, `Creneau`, `PosteAffectation` (une instance par place à pourvoir), `ContrainteAdHoc`, `VerrouillagePlanning`, `DemandeEchange`, `PlanningEvenement` (la `@PlanningSolution`) |
| `solver/` | Les règles : `PlanningConstraintProvider` les agrège, `solver/constraints/` les range par famille, `ConstraintCatalog` les décrit, `solver/handsearch/` est le moteur direct |
| `service/` | Le métier, un sous-paquet par capacité (voir plus bas) |
| `api/` | Les ressources JAX-RS : le transport seul (statuts, formes de corps). Ce qui décide quelque chose vit dans un service |
| `mcp/` | Les outils MCP, qui appellent les mêmes services que l'API |
| `scenario/` | Le DTO du fichier de scénario |
| `config/`, `observability/` | Configuration typée, métriques et suivi d'erreurs |

Deux règles de couche tiennent le découpage. Un outil MCP délègue aux services, jamais à une ressource : MCP et REST sont deux appelants des mêmes règles, et un outil qui injecterait une ressource ne lirait qu'une `Response` HTTP qu'il n'a jamais reçue (`LayeringStructuralTest`). Un refus métier est une `BusinessError`, que l'API et le MCP traduisent chacun à leur façon ; une ressource n'écrit pas de `try/catch` pour cela.

### Les sous-paquets de `service/`

| Sous-paquet | Question à laquelle il répond |
| --- | --- |
| `solve/` | Comment un problème est construit, résolu, enregistré, mis en file ou envoyé à un worker |
| `analyse/` | Ce qu'on lit d'un plan sans le recalculer (équité, heures, réalisé contre planifié) |
| `diagnostic/` | La décomposition du score règle par règle |
| `referentiel/` | Les référentiels (stands, animateurs, créneaux, typologies…), leurs validations, leurs imports, le gel |
| `scenario/` | Lecture et écriture du fichier de scénario, import |
| `edition/` | Les éditions, leur activation, leur comparaison |
| `publication/` | Publier, envoyer, accuser réception ; `MailService` |
| `espace/` | L'espace animateur, les échanges, l'horloge du jour J |
| `compte/`, `responsable/`, `rh/` | Les comptes et leurs droits ([accès et comptes](acces-et-comptes.md)) |
| `consigne/`, `validation/`, `journee/`, `mural/` | Les consignes d'édition, la relecture d'une journée, ce qui a bougé un jour, l'affichage mural |
| `notification/`, `mail/`, `webhook/`, `weather/`, `backup/`, `journal/` | Tout ce qui sort ou se trace ([tâches de fond](taches-de-fond-et-notifications.md)) |
| `export/`, `profile/`, `schema/` | Les PDF et l'ICS, la fiche d'un animateur, la garde de schéma |

La racine de `service/` ne garde que ce que tous partagent : `EditionContext`, `JdbcEditionScope`, `BusinessError`, les identifiants. Un membre dont un autre paquet a besoin est public, et son nom est alors en anglais.

## Où chercher quoi

| Vous cherchez… | Page |
| --- | --- |
| Pourquoi une requête ne peut pas lire l'édition d'un autre | [Une édition, une partition des données](cloisonnement-par-edition.md) |
| Ce que fait un calcul de bout en bout, et pourquoi deux moteurs | [Du problème au plan](comment-le-planning-est-calcule.md) |
| Qui peut appeler quoi | [Comptes, rôles et accès](acces-et-comptes.md) |
| Les tâches de nuit, les mails, les webhooks, la météo, le journal des actions, la sauvegarde | [Tâches planifiées et notifications](taches-de-fond-et-notifications.md) |
| Les conventions de l'interface Angular | [Le frontend Angular](frontend.md) |

## La base de données

Flyway migre au démarrage (`quarkus.flyway.migrate-at-start=true`). Un changement de schéma est un nouveau fichier versionné dans `src/main/resources/db/migration/` ; une migration déjà appliquée ne se modifie jamais. `FlywayMigrationsFrozenTest` tient les deux moitiés : un nouveau fichier doit avoir sa ligne dans `src/test/resources/migrations-empreintes.txt` (l'échec l'imprime), et un fichier appliqué dont l'empreinte a bougé est refusé.

`FLYWAY_REPAIR_AT_START` (faux par défaut) active la réparation de la table d'historique. Elle n'a de sens qu'en exploitation, le temps de débloquer une migration interrompue ([l'exploitation](../installer/exploitation.md)).

### Une base en avance sur le binaire

Une image qu'on ramène en arrière peut se retrouver devant un schéma qu'une version plus récente a migré. Flyway ne doit pas trancher seul, car il proposerait une réparation qui supprimerait ces lignes d'historique. Trois pièces se partagent la décision :

- `ignore-migration-patterns=*:future` laisse passer la validation sur les migrations futures ;
- `SchemaCompatibilityGuard` (`service/schema/`) lit `Flyway.info()` une fois la migration faite et refuse le démarrage avec un message qui nomme les versions et la marche à suivre, sauf `ALLOW_SCHEMA_AHEAD=true` ;
- `SchemaCompatibilityRepairGuard`, un rappel Flyway, prend la même décision juste avant toute réparation. Le motif `*:future` n'épargne que les migrations futures réussies : la réparation efface toute ligne en échec, celle d'un binaire plus récent comprise, et `FLYWAY_REPAIR_AT_START` la joue avant la garde.

La décision elle-même est la classe pure `SchemaCompatibility`. Un second observateur, joué après les autres contrôles de démarrage, note dans `version_applicative` chaque changement de version de l'application ; l'écran *Débogage* la liste ([versions](../installer/versioning.md)).

## La conteneurisation

Le [`Dockerfile`](repo:Dockerfile) est multi-étapes : Maven et Temurin 25 compilent (Quinoa construit le bundle Angular pendant `package`), une image JRE ne garde que l'application. Elle tourne sous l'utilisateur non privilégié d'uid 1000 et embarque `postgresql-client-19` pour la sauvegarde de nuit : un `pg_dump` plus ancien que le serveur refuse de tourner, cette version suit donc celle de l'image `postgres:` de la pile.

La même image porte un second programme. Sous `SOLVER_WORKER_MODE=true`, `docker/entrypoint.sh` lance le worker de calcul déporté au lieu de l'application ([calcul sur une autre machine](../installer/calcul-sur-une-autre-machine.md)).

Deux piles Compose, à ne pas confondre :

| Fichier | Rôle |
| --- | --- |
| [`docker-compose.yml`](repo:docker-compose.yml) | Développement : PostgreSQL, un puits SMTP (Mailpit) et pgAdmin ; l'application n'y démarre que sous le profil `app` |
| [`docker-compose.prod.yml`](repo:docker-compose.prod.yml) | Production : l'application et PostgreSQL, un seul port publié, sur la boucle locale, derrière le reverse proxy qui termine le TLS |

La configuration de production est dans [l'exploitation](../installer/exploitation.md), le durcissement dans [la sécurité](../installer/securite.md).
