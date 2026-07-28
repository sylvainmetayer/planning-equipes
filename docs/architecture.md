# Architecture

Application **unique** conteneurisée : un service Quarkus qui embarque le solveur
Timefold et sert le frontend statique, plus une base PostgreSQL.

```
Navigateur (vanilla JS, modules ES)
        │  fetch /api/...
        ▼
Service Quarkus  ──►  Timefold Solver (même JVM, pas de micro-service)
        │
        ▼
PostgreSQL (+ migrations Flyway)
```

## Stack

| Couche | Choix | Remarques |
| --- | --- | --- |
| Backend / API | Quarkus (Java 25) | REST + ressources statiques dans le même déploiement |
| Moteur d'optimisation | Timefold Solver Community, `HardMediumSoftScore` | Encapsulé dans la même JVM |
| Persistance | PostgreSQL + Flyway | Migrations versionnées dans `src/main/resources/db/migration` |
| Frontend | HTML/CSS/JS vanilla, modules ES | Aucun bundler, aucun framework, aucune dépendance Node |
| Exports | OpenPDF (PDF), génération ICS maison | Toujours côté serveur |
| Conteneurisation | Docker Compose (app, postgres, pgadmin) | Config par variables d'environnement |
| Outillage | `mise.toml` (`temurin-25`, Maven 3.9.9) | Toolchain épinglée |

## Arborescence

```
AGENTS.md                       → mémoire unique pour les agents IA
README.md                       → démarrage local + fonctionnalités métier (2 sections)
docs/                           → toute la documentation technique
src/main/java/.../solver/
  ├── api/                      → ressources JAX-RS
  ├── domain/                   → modèle Timefold
  ├── service/                  → services métier, persistance, exports, jobs
  └── solver/                   → configuration et contraintes du solveur
src/main/resources/
  ├── META-INF/resources/       → frontend statique (index.html, css/, js/)
  ├── db/migration/             → migrations Flyway
  ├── solver/solverConfig.xml   → configuration Timefold
  ├── scenario.yml              → scénario de démonstration (SnakeYAML)
  └── application.properties
Dockerfile, docker-compose.yml, renovate.json
```

## Backend

Package racine : `dev.sylvain.planning`.

### `domain/`

Modèle Timefold : `Animateur`, `Stand`, `Creneau`, `PosteAffectation` (entité de
planification, **une instance par place à pourvoir**), `ContrainteAdHoc`, et
`PlanningFestival` (`@PlanningSolution`, porte le `HardMediumSoftScore`). Détail
dans [`domaine.md`](domaine.md).

### `solver/`

- `PlanningConstraintProvider` — point d'entrée unique : agrège les contraintes
  des classes de `solver/constraints/`.
- `solver/constraints/` — un fichier par famille :
  - `AffectationConstraints` — postes pourvus, disponibilité, compétences, non-cumul
  - `LegalConstraints` — cadre légal mineurs (nuit, durée, repos, encadrement)
  - `AdHocConstraints` — exceptions administrateur (`ContrainteAdHoc`)
  - `QualiteConstraints` — contraintes medium (référent, équité, mixité)
  - `PreferenceConstraints` — contraintes soft (rotation, mixité des niveaux)
- `ConstraintCatalog` — description métier de chaque contrainte, exposée par
  `GET /api/constraints`. **Toute nouvelle contrainte doit y être déclarée.**

### `service/`

| Service | Rôle |
| --- | --- |
| `PlanningService` | Construit la `SolverFactory` depuis `solver/solverConfig.xml`, charge `scenario.yml`, expose `construireExemple()` / `resoudre()` |
| `SolverJobService` | Résolutions et analyses asynchrones (jobs suivis par l'IHM) |
| `ConstraintAnalysisStore` | Mémorise le résultat de la dernière analyse pour l'onglet « Constraints » |
| `ReferenceDataService` / `ReferenceDataRepository` | CRUD référentiels (stands, créneaux, animateurs, typologies, contraintes ad hoc) |
| `PlanningPersistenceService` | Lecture / écriture du planning persisté |
| `CsvImportService`, `DatabaseDumpService` | Imports CSV et export / import de dump SQL |
| `PlanningExportService` | Génération PDF (OpenPDF) et ICS, **côté serveur uniquement** |

### `api/`

Ressources JAX-RS : `PlanningResource`, `SolverJobResource`, `ReferenceDataResource`,
`ConstraintResource`, `CsvImportResource`, `DatabaseResource`,
`PlanningExportResource`. Voir [`api.md`](api.md).

## Frontend

Servi tel quel depuis `src/main/resources/META-INF/resources` — les modules ES
exigent un service HTTP (Quarkus), ils ne fonctionnent pas en `file://`.

Une responsabilité par fichier, chargés via `<script type="module" src="/js/app.js">` :

| Module | Rôle |
| --- | --- |
| `js/app.js` | Point d'entrée : navigation entre pages + init des modules |
| `js/api.js` | Helpers `fetch` ; `downloadFile` renvoie un message, ne touche jamais au DOM |
| `js/utils.js`, `js/date-utils.js` | Utilitaires, calculs de dates (semaine commençant lundi) |
| `js/planning-state.js` | État planning partagé derrière `get/setLastSolvedPlanning` + `ensurePlanning()` — pas de global libre |
| `js/calendar-month.js` | Vue mensuelle + filtres animateur / stand (état de vue interne) |
| `js/calendar-day.js` | Vue par jour |
| `js/constraints.js` | Onglet « Constraints » (catalogue + dernière analyse) |
| `js/jobs.js`, `js/notifications.js` | Suivi des jobs asynchrones et notifications IHM |
| `js/reference-data.js` | CRUD des référentiels |
| `js/data-transfer.js` | Imports CSV, export / import de dump SQL |
| `js/admin.js` | Actions planning et exports |

Conventions :

- chaque module de vue exporte `initX()` (câblage des events une seule fois) et
  `renderX()` utilisé par la navigation ;
- les lookups DOM restent dans le module propriétaire ;
- l'état circule dans un seul sens : `admin` (solve/analyze) → `setLastSolvedPlanning`,
  les calendriers relisent via `ensurePlanning()` ;
- pas de globals mutables partagés, pas de retour à un `planning.js` monolithique.

Le CSS suit le même découpage : `style.css` n'est qu'un agrégateur de règles
`@import` (police Google d'abord, puis les partials) et chaque composant a son
partial sous `css/` (`base.css` tokens/reset/typo/contrôles, `layout.css`,
`calendar-month.css`, `calendar-day.css`, `constraints.css`, `notifications.css`,
`reference-data.css`), avec ses propres `@media`.

## Base de données

PostgreSQL, migrations Flyway (`quarkus.flyway.migrate-at-start=true`) dans
`src/main/resources/db/migration/` (`V1__init.sql`, `V2__reference_details.sql`,
`V3__reference_crud.sql`). **Un changement de schéma = un nouveau fichier
versionné** ; ne jamais modifier une migration déjà appliquée.

## Conteneurisation

`docker-compose.yml` orchestre :

- `app` (profil `app`) — build multi-stage `Dockerfile` (Maven + Temurin 25 puis
  image JRE), config via `DB_URL`, `DB_USER`, `DB_PASSWORD`, `HTTP_PORT` ;
- `postgres` — image `postgres:17`, volume `postgres_data`, healthcheck ;
- `pgadmin` — IHM d'administration sur le port 5050.
