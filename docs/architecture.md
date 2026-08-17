# Architecture

Application **unique** conteneurisée : un service Quarkus qui embarque le solveur
Timefold et sert le frontend statique, plus une base PostgreSQL.

```
Navigateur (application Angular)
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
| Frontend | Angular 22 (standalone, signals, zoneless) dans `src/main/webui` | Construit et servi par l'extension Quarkus Quinoa |
| Exports | OpenPDF (PDF), génération ICS maison | Toujours côté serveur |
| Conteneurisation | Docker Compose (app, postgres, pgadmin) | Config par variables d'environnement |
| Outillage | `mise.toml` (`temurin-25`, Maven 3.9.9, Node 24) | Toolchain épinglée ; Quinoa télécharge Node au build si absent |
| MCP | Quarkiverse `quarkus-mcp-server-http` (streamable HTTP) | Assistant IA en langage naturel, voir [`mcp.md`](mcp.md) |

## Arborescence

```
AGENTS.md                       → mémoire unique pour les agents IA
README.md                       → démarrage local + fonctionnalités métier (2 sections)
docs/                           → toute la documentation technique
src/main/java/.../solver/
  ├── api/                      → ressources JAX-RS
  ├── domain/                   → modèle Timefold
  ├── mcp/                      → outils MCP (assistant IA), voir mcp.md
  ├── service/                  → services métier, persistance, exports, jobs
  └── solver/                   → configuration et contraintes du solveur
src/main/webui/                 → application Angular (sources, package.json)
src/main/resources/
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
| `PlanningService` | Construit la `SolverFactory` depuis `solver/solverConfig.xml`, charge `scenario.yml`, expose `construireExemple()` / `resoudre()` / `analyser()`, et l'explicabilité par affectation (`expliquerAffectation()`, `simulerSwap()`) |
| `SolverJobService` | Résolutions et analyses asynchrones ; porte le verrou « un seul solveur à la fois », partagé par tous les clients |
| `ConstraintAnalysisStore` | Mémorise le résultat de la dernière analyse pour l'onglet « Constraints » |
| `ReferenceDataService` / `ReferenceDataRepository` | CRUD référentiels (stands, créneaux, animateurs, typologies, contraintes ad hoc) ; point de passage unique du SQL référentiel, et donc du prédicat `edition_id` |
| `EditionService` / `EditionRepository` | Gestion des éditions elles-mêmes : création, duplication, suppression — voir [`editions.md`](editions.md) |
| `EditionContext` / `EditionRequestScope` | Résout l'édition que la requête courante lit et écrit (en-tête `X-Edition-Id`, repli sur l'édition par défaut), et permet de lier une édition à un thread sans requête (worker du solveur) |
| `PlanningPersistenceService` | Lecture / écriture du planning persisté, cloisonnée par édition |
| `DatabaseDumpService` | Export / import de dump SQL |
| `PlanningExportService` | Génération PDF (OpenPDF) et ICS, **côté serveur uniquement** |

### `api/`

Ressources JAX-RS : `PlanningResource`, `SolverJobResource`, `ReferenceDataResource`,
`EditionResource`, `ConstraintResource`, `AffectationExplanationResource`,
`CsvImportResource`, `DatabaseResource`, `PlanningExportResource`, plus le filtre
`EditionHeaderFilter` qui dépose l'en-tête `X-Edition-Id` dans le scope de requête.
Voir [`api.md`](api.md).

### `mcp/`

Outils MCP (`@Tool`) exposés à un assistant IA, en délégant aux services
métier ci-dessus sans dupliquer de logique. Couvre l'ensemble des endpoints
REST (CRUD des référentiels compris) : `AnimateurMcpTools` (filtré
confidentialité), `StandMcpTools` (stands/emplacements/typologies),
`CreneauMcpTools` (créneaux/groupes/découpage), `ParametresMcpTools`
(paramètres légaux, découpage, solveur, contraintes ad hoc),
`ScenarioMcpTools`, `PlanningMcpTools`, `ContrainteMcpTools`,
`SolveurMcpTools`. Confidentialité : `AnonymisationViolations` +
test structurel `McpConfidentialiteStructurelleTest`. Authentification par
clé API : `McpApiKeyAuthenticationMechanism` / `McpApiKeyIdentityProvider`.
Voir [`mcp.md`](mcp.md).

## Frontend

Application **Angular 22** dont les sources vivent dans `src/main/webui`.
L'extension [Quinoa](https://docs.quarkiverse.io/quarkus-quinoa/dev/index.html)
lance `npm ci && npm run build` pendant `mvn package` et copie le bundle dans les
ressources statiques du service Quarkus : le déploiement reste unique, il n'y a
pas de serveur Node en production. En `quarkus:dev`, Quinoa démarre `ng serve` et
proxifie le port 4200, ce qui donne le rechargement à chaud du frontend.

Configuration dans `application.properties` (`quarkus.quinoa.*`) : répertoire de
build `dist/planning-equipes-ui/browser`, `package-manager-install=true` (Node est
téléchargé par le build, aucune installation locale requise en CI ou dans
Docker), `enable-spa-routing=true` (les routes Angular inconnues du serveur
renvoient `index.html`) et frontend désactivé sur le profil `%test`.

L'interface est **bilingue français/anglais** via `@angular/localize`, avec
traduction à l'exécution : le français est écrit en dur dans les templates et
composants (`i18n="@@id"` / `` $localize`:@@id:…` ``), et `src/main.ts` charge
`public/i18n/messages.en.json` puis appelle `loadTranslations()` avant
`bootstrapApplication()` quand l'anglais est sélectionné — un seul build, pas
de bundle par locale, donc aucune configuration Quinoa/Quarkus supplémentaire.
Le bouton en haut à droite de la barre d'outils bascule la préférence stockée
dans `localStorage` (`app/core/locale.ts`) et recharge la page ; le français
reste la langue par défaut. Voir [`developpement.md`](developpement.md) pour
la procédure d'ajout d'une chaîne traduisible. Seuls les commentaires de code
et les identifiants techniques restent en anglais, conformément à
`AGENTS.md`.

Le design s'appuie sur **Angular Material** (Material Design 3). Le thème est
défini dans `src/material-theme.scss` via `mat.theme()` (palettes azure / blue,
typographie Roboto) ; le CSS applicatif n'utilise que les variables système
`--mat-sys-*`. L'application ne dépend pas de `@angular/animations` : les
composants Material s'animent en CSS.

Chaque bloc fonctionnel a **sa propre route et sa propre page**, chargée en
*lazy loading* :

| Route | Page | Contenu |
| --- | --- | --- |
| `/` (défaut) | `app/pages/solver/` | Scénario d'exemple, réinitialisation, résolution, analyse |
| `/debug` | `app/pages/debug/` | Diagnostics du solveur et état interne |
| `/problemes` | `app/pages/problemes/` | Vue centralisée des problèmes, triés par gravité : causes d'infaisabilité (`GET /api/feasibility`, sans résolution) + règles en défaut de la dernière analyse |
| `/data-setup` | `app/pages/data-setup/` | Scénarios d'exemple, réinitialisation, export scénario, export/import de dump SQL, durée de résolution du solveur |
| `/editions` | `app/pages/editions/` | Gestion des éditions : création (vide ou par duplication), renommage, édition par défaut, suppression |
| `/stands` | `app/pages/stands/` | CRUD des stands |
| `/emplacements` | `app/pages/emplacements/` | CRUD des emplacements (avec sélection sur carte) |
| `/animateurs` | `app/pages/animateurs/` | CRUD des animateurs (compétences, jours d'indisponibilité) |
| `/creneaux` | `app/pages/creneaux/` | CRUD des créneaux |
| `/typologies` | `app/pages/typologies/` | CRUD des typologies de jeux |
| `/ad-hoc-constraints` | `app/pages/ad-hoc-constraints/` | CRUD des contraintes ad hoc |
| `/calendar` | `app/pages/calendar-month/` | Vue mensuelle + filtres animateur / stand |
| `/day-calendar` | `app/pages/calendar-day/` | Vue par jour du festival |
| `/constraints` | `app/pages/constraints/` | Catalogue des contraintes + dernière analyse |
| `/hours` | `app/pages/hours/` | Heures planifiées par animateur et par semaine |
| `/staffing` | `app/pages/staffing/` | Besoin minimum en effectif par créneau |
| `/heatmap` | `app/pages/heatmap/` | Heatmap de charge par jour, croisée avec le stand (trous de couverture) ou l'animateur (surcharge) |
| `/timeline` | `app/pages/animateur-timeline/` | Timeline individuelle d'un animateur : amplitude, vacations et pauses/déplacements jour par jour |

`/exports` et `/solver` (son ancienne route) redirigent vers `/` (les exports
PDF / ICS sont déclenchés depuis la page de résolution) ; toute route inconnue
redirige également vers `/`.

Le code est organisé par responsabilité, sans module `NgModule` (composants
standalone) :

| Fichier | Rôle |
| --- | --- |
| `app/app.ts`, `app/app.html` | Coquille applicative : `mat-toolbar`, `mat-sidenav` (navigation groupée Planning / Référentiels / Vues), moniteur de job |
| `app/app.routes.ts` | Table des routes ci-dessus, toutes en *lazy loading* |
| `app/core/api.service.ts` | Helpers `HttpClient` ; `downloadFile` renvoie un message, ne touche jamais au DOM |
| `app/core/models.ts` | Types TypeScript des payloads de l'API |
| `app/core/date-utils.ts` | Calculs de dates (semaine commençant lundi) |
| `app/core/horaire-stand.ts` | Résolution des horaires d'un stand (règles récurrentes + exceptions datées) en fenêtres jour par jour, et résumés affichés. Miroir de `HoraireStandResolver` côté serveur — le seul endroit où le frontend réimplémente de la logique de domaine, pour prévisualiser une règle sans aller-retour ; à faire évoluer dans le même commit que son pendant Java |
| `app/core/locale.ts` | Langue choisie (`fr` / `en`), persistée dans `localStorage` |
| `app/core/edition-courante.ts` | Édition consultée par cet onglet, persistée dans `localStorage` ; module et non service, pour que l'intercepteur ne dépende pas du `HttpClient` qu'il intercepte |
| `app/core/edition.interceptor.ts` | Ajoute l'en-tête `X-Edition-Id` à chaque appel `/api/` |
| `app/core/edition.store.ts` | Liste des éditions et édition réellement résolue par le serveur (`GET /api/editions/courant`) |
| `app/core/slug.ts` | Dérive un identifiant stable à partir d'un nom saisi librement |
| `app/core/planning-state.service.ts` | État planning partagé (signal) + chargement lecture seule pour les vues |
| `app/core/planning-resolution.store.ts` | Groupe de créneaux de la dernière résolution vs groupe actif : alimente les avertissements « planning obsolète » |
| `app/core/problemes.ts` | Fusion pure des deux sources de problèmes (causes d'infaisabilité + contraintes en défaut) en une liste triée par gravité |
| `app/core/problemes.store.ts` | État partagé du diagnostic : `GET /api/feasibility` (avant toute résolution) et `GET /api/constraints`, plus les index utilisés par les badges des tableaux |
| `app/core/reference-data.store.ts` | Référentiels partagés (signals) et opérations CRUD, unitaires ou par lot (un seul rechargement par lot) |
| `app/core/reference-crud.service.ts` | Enregistrement / suppression mutualisés des pages référentiels, unitaires ou par lot (retour utilisateur, confirmation unique) |
| `app/core/table-selection.ts` | Sélection multiple d'un tableau : lignes cochées, états de la case d'en-tête, intersection avec les lignes affichées |
| `app/core/bulk-edit.ts` | Briques des modifications en masse : modes « ne pas modifier / ajouter / retirer / remplacer » appliqués à une ligne |
| `app/core/entity-labels.ts` | Libellés au pluriel des référentiels, utilisés par les actions de masse |
| `app/core/typologie-colors.ts` | Couleur stable d'une typologie de jeu (dérivée de son id, pas d'un rang) et libellés associés, partagés par la heatmap et la timeline |
| `app/core/solver-job.service.ts` | Suivi des jobs asynchrones : lit `/api/jobs/active` toutes les 2 s, aucun stockage navigateur |
| `app/core/notification.service.ts` | Notifications via `MatSnackBar` (+ notifications système) |
| `app/core/affectation-explanation.service.ts` | Appelle `/api/postes/{id}/explication` et `/api/postes/{id}/simulation-swap` (« Pourquoi lui ? ») |
| `app/shared/job-monitor.ts` | Indicateur « une résolution est en cours » dans la barre d'outils, temps écoulé calculé par le serveur |
| `app/shared/confirm-dialog.ts` | Dialogue Material de confirmation (remplace `window.confirm`) |
| `app/shared/output-panel.ts` | Panneau de résultat monospace partagé par les pages d'action |
| `app/shared/data-stale-indicator.ts` | Signale que les données de référence ont changé depuis la dernière résolution |
| `app/shared/edition-actuelle-bar.ts` | Bandeau permanent nommant l'édition consultée, avec bascule et lien vers la page Éditions |
| `app/shared/groupe-mismatch-banner.ts` | Signale que la grille de créneaux active diffère de celle de la dernière résolution |
| `app/shared/feasibility-banner.ts` | Alerte si le plan n'est pas fiable : soit la capacité pré-résolution (`FeasibilityReport`, avec ses causes les plus graves), soit le score dur réellement atteint (`hardScore` < 0 après résolution, y compris quand la capacité pré-résolution disait « réalisable ») |
| `app/shared/problem-summary-banner.ts` | Bannière de synthèse (nombre de problèmes par gravité) sur la page Solveur, avec un lien vers la page Problèmes |
| `app/shared/violation-details-dialog.ts` | Modale « qui/quoi/quand » listant chaque violation d'une contrainte dure (page Contraintes), à partir de `ConstraintView.violations` |
| `app/shared/affectation-explanation-dialog.ts` | Modale « Pourquoi lui ? » : contraintes violées/respectées pour un poste, et simulation de remplacement (calendriers journalier et des affectations) |
| `app/shared/map-picker.ts` | Sélection de coordonnées sur une carte, utilisée par le formulaire d'emplacement |
| `app/shared/bulk-actions-bar.ts` | Barre d'actions d'une sélection multiple (nombre sélectionné, modifier, supprimer, tout désélectionner) |

Conventions :

- composants **standalone**, état local en `signal()` / `computed()`, nouveau
  flot de contrôle (`@if` / `@for`) dans les templates ; pas de `NgModule` ;
- un bloc fonctionnel = une route = une page ; on n'ajoute pas une section dans
  une page existante ;
- les services de `core/` portent l'état partagé et les appels HTTP, les
  composants ne font pas de `fetch` direct ;
- l'état circule dans un seul sens : la page « Solver » pousse le planning
  résolu dans `PlanningStateService`, les calendriers le relisent en lecture
  seule (ils ne lancent jamais de résolution) ;
- l'état « un solveur tourne » n'est jamais stocké dans le navigateur
  (`localStorage` / `sessionStorage`) : `SolverJobService` interroge
  `/api/jobs/active` toutes les 2 secondes, de sorte qu'une résolution lancée
  depuis un autre navigateur ou une fenêtre privée verrouille aussi les boutons
  ici, affiche le temps écoulé calculé par le serveur, et pousse son résultat à
  la fin.

Le CSS global se limite à ce que Material ne couvre pas : `src/styles.css` n'est
qu'un agrégateur de règles `@import` et chaque partial vit sous `src/styles/`
(`pages.css` cartes / formulaires / tableaux, `feedback.css` moniteur de job et
variantes de snack bar, `calendar-month.css`, `calendar-day.css`,
`constraints.css`, `problemes.css`, `staffing.css`, `horaires-stand.css`
— éditeur de règles d'horaire d'un stand et bande d'aperçu jour par jour —,
`typologie-colors.css`
— la palette catégorielle des typologies de jeu, partagée par la heatmap et la
timeline —, `heatmap.css`, `animateur-timeline.css`), avec ses propres `@media`.

## Base de données

PostgreSQL, migrations Flyway (`quarkus.flyway.migrate-at-start=true`) dans
`src/main/resources/db/migration/` (`V1__init.sql`, `V2__reference_details.sql`,
`V3__reference_crud.sql`). **Un changement de schéma = un nouveau fichier
versionné** ; ne jamais modifier une migration déjà appliquée.

Toutes les tables métier sont cloisonnées par `edition_id` depuis `V32`–`V36`,
et les identifiants métier (`stand.id`, `animateur.id`, …) ont une clé primaire
composite `(edition_id, id)` : une nouvelle table du référentiel doit suivre la
même convention, et sa requête doit passer par `ReferenceDataRepository`. Voir
[`editions.md`](editions.md).

## Conteneurisation

`docker-compose.yml` orchestre :

- `app` (profil `app`) — build multi-stage `Dockerfile` (Maven + Temurin 25 puis
  image JRE), config via `DB_URL`, `DB_USER`, `DB_PASSWORD`, `HTTP_PORT` ;
- `postgres` — image `postgres:17`, volume `postgres_data`, healthcheck ;
- `pgadmin` — IHM d'administration sur le port 5050.
