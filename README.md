# Planning Équipes

Implémentation initiale Quarkus + Timefold pour le planning du festival de jeux (15 jours, ~150 animateurs), basée sur `CLAUDE.md`.

## Stack

- Quarkus (API REST + serveur statique frontend)
- Timefold Solver (`HardMediumSoftScore`)
- PostgreSQL + Flyway
- Frontend vanilla JS (`src/main/resources/META-INF/resources`)
- Docker Compose

## Endpoints MVP

- `GET /api/planning/sample` : retourne un jeu d'exemple
- `POST /api/solve` : résout un planning envoyé en JSON
- `POST /api/solve/async`, `POST /api/solve/analyze/async`, `GET /api/jobs[/{id}]` :
  résolution / analyse en tâche de fond (l'IHM notifie à la fin, la navigation
  reste libre pendant les 3-4 minutes de résolution)
- `GET /api/planning/persisted` : planning persisté (lecture seule, utilisé par
  les vues calendrier — elles ne déclenchent jamais de résolution)
- `POST /api/planning/reset` : recharge le scénario d'exemple en base **sans**
  résolution (bouton « Reset BDD »), pour repartir d'un jeu de données vierge
- `GET /api/constraints` : catalogue métier des contraintes + résultat de la
  dernière analyse (onglet « Constraints »)
- `GET /api/database/export` / `POST /api/database/import` : export / import SQL
  de la base
- `POST /api/import/csv/{animateurs|stands|creneaux}` : import CSV du référentiel

## Import / export de jeux de données

### Export / import SQL

`GET /api/database/export` produit un script SQL autonome (DELETE puis INSERT de
toutes les tables métier) téléchargeable depuis la section « Data transfer » de
la page Administration. `POST /api/database/import` rejoue un tel script dans une
seule transaction : seules les instructions `INSERT` / `DELETE` / `TRUNCATE` sur
les tables métier sont acceptées, tout le reste est rejeté (400).

### Import CSV

Un import remplace **l'intégralité** de la table concernée et supprime les
affectations existantes (elles référenceraient des lignes disparues). Ligne
d'en-tête obligatoire, séparateur `;` ou `,` (détecté automatiquement), valeurs
multiples séparées par `|`, dates ISO `yyyy-MM-dd`, heures ISO `HH:mm`.

| Entité | Colonnes |
| --- | --- |
| `animateurs` | `id;prenom;nom;dateNaissance;statut;competences;joursIndisponibles` |
| `stands` | `id;nom;typologies;effectifMin;effectifMax;reserveMajeurs` |
| `creneaux` | `id;jour;date;heureDebut;heureFin` |

- `statut` : `BENEVOLE` ou `SALARIE`
- `competences` : `STRATEGIE:REFERENT|ENFANT:AUTONOME`
  (typologies : `STRATEGIE, AMBIANCE, ENFANT, COOPERATIF, ADRESSE, ROLE, ENIGME` ;
  niveaux : `DEBUTANT, AUTONOME, REFERENT`)
- `joursIndisponibles` : `2026-07-02|2026-07-03` (colonne facultative, vide = toujours dispo)
- `typologies` : `STRATEGIE|ENFANT`
- `reserveMajeurs` : `true` / `false` (`1`, `oui`, `yes` acceptés)

Exemple :

```csv
id;prenom;nom;dateNaissance;statut;competences;joursIndisponibles
A-1;Ada;Lovelace;1990-05-04;BENEVOLE;STRATEGIE:REFERENT|ENFANT:AUTONOME;2026-07-02
A-2;Alan;Turing;2010-01-15;SALARIE;;
```

Une ligne invalide annule tout l'import et renvoie un message précisant le
numéro de ligne et la colonne fautive.

## Lancer en local

```bash
./mvnw quarkus:dev
```

## Lancer les tests

Les tests démarrent un PostgreSQL jetable via les *dev services* Quarkus : un
runtime de conteneurs (Docker ou Podman) doit être disponible.

```bash
./mvnw test                      # tests unitaires
./mvnw verify -DskipITs=false    # + tests d'intégration (*IT) sur l'app packagée
```

Avec Podman (rootless), exposer la socket compatible Docker :

```bash
systemctl --user start podman.socket
export DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock
```

La CI GitHub Actions (`.github/workflows/tests.yml`) exécute `verify -DskipITs=false`
sur chaque push `main` et chaque pull request.

## Lancer avec Docker Compose

```bash
docker compose up --build
```
