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
| Conteneurisation | Docker Compose (app, postgres, pgadmin) | Config par variables d'environnement ; l'image applicative tourne sous un utilisateur non privilégié (`uid 1001`) |
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
| `ResolutionPipeline` | Ce qu'un solve fait **toujours** : capturer le plan qu'il va écraser, construire son problème, résoudre, persister, diagnostiquer, alimenter l'écran Contraintes, écrire la ligne de KPI, annoncer la fin. Deux coutures seulement — comment le problème est construit, et qui doit tenir le solveur pour pouvoir l'arrêter |
| `SolverJobService` | Résolutions et analyses asynchrones ; porte le verrou « un seul solveur à la fois », partagé par tous les clients, et la **file d'attente** des tâches planifiées derrière celle qui tourne ; la rejoue au démarrage |
| `SolverJobRepository` | Persistance de cette file et du journal des jobs (table `solver_job`) : l'**intention** d'un job, jamais un état de solveur ni le résultat — voir [`api.md`](api.md#la-file-survit-au-redémarrage) |
| `ConstraintAnalysisStore` | Mémorise le résultat de la dernière analyse pour l'onglet « Constraints » |
| `ReferenceDataService` | Façade de lecture/écriture sur l'ensemble du référentiel, sans logique propre : une porte unique pour la vingtaine de classes qui lisent plusieurs familles à la fois (ressources JAX-RS, outils MCP, construction du problème) |
| `StandService`, `AnimateurService`, `CreneauService`, `EmplacementService`, `TypologieService`, `ContrainteAdHocService`, `VerrouillageService`, `ParametresService` | Une famille de référentiel chacun : sa validation et ses écritures. Un appelant qui ne touche qu'une famille injecte ce service-là, pas la façade |
| `Referentiel` | Ce que la construction d'un problème lit du référentiel, et rien d'autre. Implémenté par `ReferenceDataService` ; les tests hors CDI en fournissent une version vide, qui vit dans `src/test` |
| `StandRepository`, `AnimateurRepository`, `CreneauRepository`, `EmplacementRepository`, `TypologieRepository`, `ContrainteAdHocRepository`, `VerrouillageRepository`, `ParametresRepository` | Le SQL d'une famille chacun. Le prédicat `edition_id` reste auditable d'un `grep` sur le paquet, et `IsolationEditionStructurelleTest` le vérifie mécaniquement |
| `ImportReferentielRepository` | La seule écriture qui traverse toutes les familles : remplacer le référentiel entier par celui d'un scénario, en une transaction. Il emprunte une connexion et la passe à chaque dépôt de famille |
| `JdbcEditionScope` | Le seul endroit qui emprunte une connexion, lie l'édition courante au **premier** paramètre d'une requête (`prepareScoped`) et porte la transaction (`lire` / `ecrire` / `ecrireEtRendre`). C'est ce qui rend le prédicat `edition_id` mécanique — voir [`editions.md`](editions.md) |
| `EditionService` / `EditionRepository` | Gestion des éditions elles-mêmes : création, duplication, suppression — voir [`editions.md`](editions.md) |
| `EditionContext` / `EditionRequestScope` | Résout l'édition que la requête courante lit et écrit (en-tête `X-Edition-Id`, repli sur l'édition par défaut), et permet de lier une édition à un thread sans requête (worker du solveur) |
| `PlanningPersistenceService` | Lecture / écriture du planning persisté, cloisonnée par édition |
| `PlanningKpiService` | KPI agrégés et non nominatifs d'un plan (score par niveau, couverture, dispersion des heures, taux de modifications manuelles) — issue #89 |
| `KpiHistoriqueService` | Une ligne de KPI par solve terminé, toutes éditions, sans clé étrangère (l'historique survit à la suppression d'une édition) — issue #89 |
| `ReplanificationDiff` / `PerimetreReplanification` | Périmètre volatil d'une replanification incrémentale et diff des équipes qu'elle a fait bouger — issue #86 |
| `SnapshotComparaisonService` | Comparateur A/B : confronte deux plans (instantanés ou plan courant), **toutes éditions confondues**, en lecture seule — aucune résolution, aucun score recalculé — issue #70 |
| `EnvoiPlanningService` | Envoi des plannings individuels par mail : qui est concerné, le PDF, le compte rendu. Action d'administration explicite, donc un compte rendu nominatif — à l'opposé des notifications d'échange, best-effort par nature |
| `DatabaseDumpService` | Export / import de dump SQL |
| `PlanningExportService` | Façade des exports d'un planning, **côté serveur uniquement** : qui est concerné, comment on le nomme, ses jours de repos, le lien de son espace, et les ZIP qui distribuent le tout |
| `PlanningPdfAnimateur` / `PlanningPdfGlobal` / `PlanningIcs` | Un document chacun : le PDF en cartes qu'on lit sur un téléphone, le récapitulatif paysage de l'organisateur, le calendrier iCalendar |
| `ChartePdf` | L'identité visuelle des PDF (palette, fontes, logo, icônes, coins arrondis). Séparée parce qu'elle change quand la charte change, jamais quand la façon de planifier change |
| `StatistiquesPostes` | Ce qu'un ensemble de sièges représente — jours, stands, créneaux, heures — et la tuile qui l'affiche. Partagée par les deux PDF, pour que « 3 JOURS » veuille dire la même chose sur les deux |
| `LiensApplication` | Toute URL publique imprimée hors de l'application (mail, PDF) : seul endroit qui connaît `planning.public-url` et les routes du SPA visées — voir [`developpement.md`](developpement.md#conventions-de-code) |
| `AdresseAdministrateur` | Seul lecteur de `planning.mail.admin` ; vide = notifications administrateur désactivées |
| `MailService` | Les mails qu'un administrateur **demande** (planning individuel, code d'accès, mail de test) — un échec **remonte** |
| `service/notification/` | Les mails best-effort : le métier émet une `Notification` (interface scellée), `RedacteurNotifications` la rédige par `switch` exhaustif, `ExpediteurNotifications` l'observe et porte l'unique `catch` — voir ci-dessous |

#### Deux politiques d'échec, séparées structurellement

Un mail qui **accompagne** une opération déjà faite (une demande soumise, une
décision prise, un solve terminé) ne doit jamais la faire échouer : un SMTP en
panne ne peut pas annuler ce qui a eu lieu. Un mail qui **est** l'opération
(le code d'accès sans lequel l'animateur ne peut pas entrer, le planning qu'on
croit diffusé) doit au contraire échouer bruyamment pour que l'appelant dise
qui n'a pas été joint.

Ces deux politiques étaient auparavant portées par des méthodes de forme
identique sur la même classe, la première réécrite à la main autour de chaque
appel dans cinq services. Elles sont désormais dans deux endroits distincts —
`MailService` pour la seconde, `service/notification/` pour la première, où le
`catch` n'est écrit qu'une fois. Le métier n'envoie plus rien : il émet un
fait.


### `api/`

Ressources JAX-RS : `PlanningResource`, `SolverJobResource`, `EditionResource`,
`ConstraintResource`, `AffectationExplanationResource`, `CsvImportResource`,
`DatabaseResource`, `PlanningExportResource`, `KpiResource`, plus le filtre
`EditionHeaderFilter` qui dépose l'en-tête `X-Edition-Id` dans le scope de requête.

Le CRUD du référentiel a une ressource par famille — `StandResource`,
`AnimateurResource`, `CreneauResource`, `DecoupageResource`,
`EmplacementResource`, `TypologieResource`, `ContrainteAdHocResource`,
`ParametresResource` —, chacune montée sur sa propre racine d'URL.
`ReferenceDataResource` ne garde que l'import de référentiel, qui traverse
toutes les familles. **Aucun chemin HTTP n'a changé** : c'est le même découpage
côté transport que côté service.
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
| `/mcp-client` | `app/pages/mcp/` | Instructions de connexion au serveur MCP (clé API, jetons Pangolin détectés via l'en-tête `X-Pangolin`), et prompt d'exemple pour déboguer une solution |
| `/debug` | `app/pages/debug/` | Diagnostics du solveur et état interne, outils base de données, validateur YAML de scénario |
| `/problemes` | `app/pages/problemes/` | Vue centralisée des problèmes, triés par gravité : causes d'infaisabilité (`GET /api/feasibility`, sans résolution) + règles en défaut de la dernière analyse |
| `/parametres` | `app/pages/parametres/` | Paramètres de l'édition (import/export de scénarios, paramètres de découpage, typologie ninja, renvois vers les réglages restés sur leur écran) et paramètres globaux (export/import de dump SQL) |
| `/aide` | `app/pages/aide/` | Guide d'utilisation intégré : rôle de chaque écran, configuration du solveur, lecture des scores et réglages à faire selon le symptôme. Contenu statique (`aide-content.ts`), aucune dépendance à un service ni au planning résolu |
| `/editions` | `app/pages/editions/` | Gestion des éditions : création (vide ou par duplication), renommage, édition par défaut, suppression |
| `/stands` | `app/pages/stands/` | CRUD des stands |
| `/emplacements` | `app/pages/emplacements/` | CRUD des emplacements (avec sélection sur carte) |
| `/animateurs` | `app/pages/animateurs/` | CRUD des animateurs (compétences, jours d'indisponibilité) |
| `/creneaux` | `app/pages/creneaux/` | CRUD des créneaux + prévisualisation et génération du découpage en vacations |
| `/typologies` | `app/pages/typologies/` | CRUD des typologies de jeux |
| `/ad-hoc-constraints` | `app/pages/ad-hoc-constraints/` | CRUD des contraintes ad hoc |
| `/calendar` | `app/pages/calendar-month/` | Vue mensuelle + filtres animateur / stand |
| `/day-calendar` | `app/pages/calendar-day/` | Vue par jour du festival |
| `/constraints` | `app/pages/constraints/` | Catalogue des contraintes + dernière analyse |
| `/hours` | `app/pages/hours/` | Heures planifiées par animateur et par semaine |
| `/ouvertures` | `app/pages/ouvertures/` | Grille stand × jour des ouvertures réellement en vigueur (règles étendues, exceptions appliquées, fenêtres bornées aux créneaux) et anomalies de saisie, pour validation avant résolution |
| `/staffing` | `app/pages/staffing/` | Besoin minimum en effectif par créneau |
| `/heatmap` | `app/pages/heatmap/` | Heatmap de charge par jour, croisée avec le stand (trous de couverture) ou l'animateur (surcharge) |
| `/timeline` | `app/pages/animateur-timeline/` | Timeline individuelle d'un animateur : amplitude, vacations et pauses/déplacements jour par jour |
| `/instantanes` | `app/pages/snapshots/` | Instantanés de plan : enregistrer, restaurer, supprimer un plan mis de côté (un instantané est aussi pris automatiquement avant chaque résolution) |
| `/mentions-legales` | `app/pages/mentions-legales/` | Mentions légales, **hors des deux coques** : lisible sans session ni jeton d'accès. Faits propres au déploiement lus depuis `/api/mentions-legales`, le reste (traitement des données) écrit dans la page |
| `/conditions-utilisation` | `app/pages/mentions-legales/` | Conditions d'utilisation : aide à la décision, responsabilités de l'organisateur (employeur, données, réglementation), limitation de responsabilité |
| `/politique-confidentialite` | `app/pages/mentions-legales/` | Politique de confidentialité, publique et hors des deux coques comme les mentions légales : traitement des données, mineurs, sous-traitants, mesure d'audience et suivi d'erreurs, droits |
| `/comparateur` | `app/pages/comparateur/` | Comparateur A/B : une référence contre une variante (deux instantanés, ou un instantané et le plan courant), toutes éditions confondues, avec le sens de chaque écart |
| `/what-if` | `app/pages/what-if/` | Simulation « et si ? » : impact d'un désistement, d'un recrutement ou de la fermeture d'un stand, sans rien écrire |

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
`constraints.css`, `problemes.css`, `staffing.css`, `ouvertures.css`
— grille des ouvertures de stands —, `horaires-stand.css`
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
même convention, et sa requête doit passer par `JdbcEditionScope` — l'invariant
porte sur le helper, pas sur une classe unique.
`IsolationEditionStructurelleTest` le vérifie mécaniquement : il lit le SQL de
tout le backend et échoue sur toute requête visant une table métier sans
prédicat `edition_id`. Voir [`editions.md`](editions.md).

## Conteneurisation

`docker-compose.yml` orchestre :

- `app` (profil `app`) — build multi-stage `Dockerfile` (Maven + Temurin 25 puis
  image JRE), config via `DB_URL`, `DB_USER`, `DB_PASSWORD`, `HTTP_PORT` ;
- `postgres` — image `postgres:17`, volume `postgres_data`, healthcheck ;
- `pgadmin` — IHM d'administration sur le port 5050.
