# Héberger une instance de démonstration

Vous ouvrez à tous une instance d'essai, avec des données fictives et des identifiants affichés, remise à zéro toutes les trente minutes. Une seule variable la met dans ce mode ; les données viennent d'un dump de référence que vous fabriquez une fois, et votre hôte le restaure à heure fixe. La décision est dans l'[ADR 0130](repo:docs/decisions/0130-un-drapeau-pour-l-instance-de-demonstration.md).

N'utilisez jamais ce mode pour une instance en service : il accepte les mots de passe d'exemple et ne garde rien plus d'une demi-heure.

## Ce que le mode coupe

`DEMO_INSTANCE=true` impose les réglages suivants, quelles que soient les variables posées à côté :

| Réglage imposé | Effet |
| --- | --- |
| `PUBLIC_INSTANCE=true` | le quota de calcul s'applique (voir plus bas) |
| `LEGAL_DEMO_INSTANCE=true` | l'instance démarre sans mentions légales ; renseignez-les quand même si vous êtes l'éditeur d'un site public |
| `MAIL_MOCK=true` | aucun e-mail ne part : ils sont simulés |
| `AUTH_LOCAL_SECONDE_ETAPE=false` | la connexion d'un compte local ne demande pas de code par e-mail |
| `WEBHOOKS_ENABLED=false` | créer ou tester un webhook est refusé, rien n'est envoyé |
| `METEO_ENABLED=false` | l'alerte météo n'interroge aucun service |
| `OIDC_ENABLED=false` | aucun fournisseur de connexion : le formulaire du compte `admin` reste ouvert |
| `PLANNING_MCP_API_KEY` ignorée, OAuth MCP fermé | aucune clé n'ouvre `/mcp`, et la clé configurée n'est pas révélée |

Le mode refuse aussi, avec un message qui commence par « Instance de démonstration : » :

- de créer, désactiver ou réactiver un compte, d'accorder ou de retirer un droit ;
- d'envoyer un lien de mot de passe, de choisir un mot de passe, la demande « mot de passe oublié » comprise ;
- de générer une clé MCP.

Comme aucun e-mail ne part, l'espace animateur affiche à l'écran le code d'accès qu'il aurait envoyé : le lien de l'espace, son jeton, reste la seule clé.

Il accepte les mots de passe d'exemple (`ADMIN_PASSWORD=admin`, `DB_PASSWORD=festival`) qu'une instance ordinaire refuse au démarrage. Chaque page, la connexion comprise, porte un bandeau qui dit que les données sont fictives et remises à zéro à :00 et :30.

## Les variables

| Variable | Valeur pour la démo | Rôle |
| --- | --- | --- |
| `DEMO_INSTANCE` | `true` | le mode lui-même |
| `ADMIN_PASSWORD` | celui que vous affichez | le compte `admin`, le seul qui ne vient pas du dump |
| `SOLVER_SECONDS_LIMIT` | `40` | durée d'un calcul |
| `SOLVER_SECONDS_LIMIT_MAX` | `40` | plafond de ce qu'un lancement peut demander |
| `SOLVER_MAX_QUEUED_JOBS` | `3` | calculs qui peuvent attendre derrière celui qui tourne |
| `SOLVER_MAX_SOLVES_PER_HOUR` | `30` | calculs acceptés par heure glissante, pour toute l'instance |

Un seul calcul tourne à la fois sur une instance : c'est le fonctionnement de la file, sans réglage. Les autres variables sont celles d'une [installation](installation.md) ordinaire (base, `PUBLIC_URL`).

## Fabriquer le dump de référence

Le script `scripts/demo/generer-dump.sh` du dépôt démarre une pile jetable (PostgreSQL, Mailpit, l'application), importe le scénario de la vitrine, lance un calcul et l'attend, publie le plan, crée les trois comptes locaux puis écrit le dump. Il demande `docker` (avec `docker compose`), `curl` et `jq`, et quelques minutes.

```bash
DEMO_PASSWORD='demo-planning' \
APP_IMAGE=ghcr.io/sylvainmetayer/planning-equipes:4.2.0 \
  scripts/demo/generer-dump.sh reference.dump
```

| Variable du script | Défaut | Rôle |
| --- | --- | --- |
| `DEMO_PASSWORD` | exigée, 12 caractères au moins | mot de passe des trois comptes locaux |
| `APP_IMAGE` | `ghcr.io/sylvainmetayer/planning-equipes:main` | la version qui fabrique le dump ; prenez celle que la démo déploie |
| `POSTGRES_IMAGE` | `postgres:19` | même version majeure que le serveur qui restaurera le dump |
| `DEMO_ESPACE_TOKEN` | un UUID fixe, écrit dans le script | jeton de l'espace de démonstration (voir plus bas) |
| `APP_PORT`, `MAILPIT_PORT` | `18080`, `18025` | ports de la pile jetable, sur la boucle locale |

Les comptes du dump :

| Compte | Droit |
| --- | --- |
| `rh@example.org` | ressources humaines de l'édition |
| `responsable@example.org` | responsable des deux premiers stands |
| `lecteur@example.org` | lecteur de l'édition |

L'animateur du plan qui a une adresse et le plus de postes reçoit le jeton `DEMO_ESPACE_TOKEN` : son espace s'ouvre à `/animateur/<jeton>`, la même adresse d'un dump à l'autre, et le script l'affiche à la fin.

Le dump est au format `pg_dump -Fc`, **sans propriétaire ni droits** (`--no-owner --no-acl`) : ses objets appartiennent au rôle qui le restaure. Il n'est pas dans le dépôt ni dans l'image : gardez-le sur l'hôte de la démo.

Un dump fabriqué par une version plus ancienne que celle que vous déployez reste valable : au démarrage, l'application migre la base restaurée. Refaites-le quand le scénario de la vitrine change.

## La remise à zéro

Un timer de l'hôte, toutes les trente minutes (à :00 et :30), arrête l'application, remplace la base par le dump, puis relance l'application. La base est **supprimée puis recréée** plutôt que nettoyée par `pg_restore --clean` : une table qu'une version plus récente a ajoutée, absente du dump, survivrait au nettoyage et ferait échouer la migration suivante.

```bash
docker compose stop app
docker compose exec -T postgres dropdb -U festival --if-exists --force festival
docker compose exec -T postgres createdb -U festival -O festival festival
docker compose exec -T postgres pg_restore -U festival -d festival --no-owner --role=festival < reference.dump
docker compose start app
```

Avec systemd, un service `oneshot` qui lance ces commandes et un timer `OnCalendar=*:00,30`. La sauvegarde de nuit n'a pas d'objet sur la démo : laissez `BACKUP_DIR` vide, et elle ne tourne pas.
