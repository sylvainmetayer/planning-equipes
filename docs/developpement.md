# Développement

## Prérequis

- Java 25, Maven 3.9.9 et Node 24, épinglés dans `mise.toml` (`mise install`).
- Un runtime de conteneurs (Docker ou Podman) pour PostgreSQL et pour les tests
  (Quarkus dev services).

Node n'est nécessaire que pour travailler directement sur le frontend : le build
Maven télécharge sa propre version de Node via Quinoa
(`quarkus.quinoa.package-manager-install=true`), donc la CI et l'image Docker ne
demandent aucune installation préalable.

## Commandes courantes

```bash
./mvnw quarkus:dev                     # serveur de dev (hot reload) sur http://localhost:8080
./mvnw test                            # tests unitaires
./mvnw test -Dtest=PlanningHardConstraintsTest                 # une classe
./mvnw test -Dtest=PlanningHardConstraintsTest#generatedPlanningDoesNotViolateAnyHardConstraintOnNominalCase
./mvnw verify -DskipITs=false          # + tests d'intégration (*IT) sur l'app packagée
docker compose up postgres             # base seule
docker compose --profile app up --build  # application complète + base
```

Frontend Angular (sources dans `src/main/webui`) :

```bash
cd src/main/webui
npm install                            # dépendances (une seule fois)
npm run build                          # build de production dans dist/planning-equipes-ui/browser
npm start                              # ng serve seul sur http://localhost:4200 (API non proxifiée)
npm test                               # tests unitaires (Vitest) : watch en terminal, une passe en CI
```

En pratique, `./mvnw quarkus:dev` suffit : Quinoa démarre `ng serve` et le
proxifie sur http://localhost:8080, backend et frontend rechargent à chaud
ensemble. Le build Maven (`package`, `verify`) reconstruit toujours le frontend ;
il est désactivé sur le profil `%test` pour ne pas ralentir les tests unitaires.

`skipITs` vaut `true` par défaut dans le `pom.xml` : les tests `*IT` (failsafe)
ne s'exécutent qu'avec `-DskipITs=false`.

## Internationalisation (i18n) du frontend

L'interface est bilingue français/anglais via `@angular/localize`, avec
traduction **à l'exécution** (un seul build, pas de bundle par langue) : le
français est la langue source directement écrite dans les templates et les
composants, et `src/main.ts` charge `public/i18n/messages.en.json` puis appelle
`loadTranslations()` avant `bootstrapApplication()` si l'anglais est
sélectionné. Le bouton en haut à droite de la barre d'outils (`app.html`)
bascule la préférence stockée dans `localStorage` et recharge la page — les
messages `$localize` ne sont résolus qu'une fois, au démarrage, donc changer de
langue sans recharger n'est pas possible.

Pour ajouter une chaîne traduisible :

1. Dans un template : `<span i18n="@@monId">Texte en français</span>` (ou
   `i18n-ariaLabel`, `i18n-matTooltip`, etc. pour un attribut). Dans du
   TypeScript : `` $localize`:@@monId:Texte ${valeur}:placeholder:` `` —
   **jamais** au niveau module (`const x = $localize\`...\`` hors d'une
   fonction/méthode) : le fichier serait évalué avant que `loadTranslations()`
   ait pu s'exécuter, et resterait figé en français ; utilisez un
   `computed()`, une méthode, ou une fonction appelée depuis le constructeur
   (voir `buildNavGroups()` dans `app.ts` ou `jobLabel()` dans
   `solver-job.service.ts`).
2. Choisissez un id stable et unique (`@@page.section.role`), en réutilisant un
   id existant (`@@common.*`, `@@stands.lockedHint`, …) quand le texte anglais
   attendu est identique.
3. Extrayez le catalogue : `npx ng extract-i18n --output-path src/locale`
   (depuis `src/main/webui`) — le fichier `src/locale/messages.xlf` généré
   n'est **pas** versionné, il ne sert qu'à lire le texte source exact et les
   noms des placeholders (`<x id="…"/>` → `{$…}`).
4. Ajoutez la traduction anglaise dans `public/i18n/messages.en.json`, avec
   les mêmes placeholders `{$nom}` que la source, dans le même ordre.
   `npx ng extract-i18n` réémet un avertissement si deux textes différents
   partagent le même id : renommez l'un des deux plutôt que d'ignorer
   l'avertissement.

## Tests

On distingue trois familles de tests :

- **Tests unitaires de contraintes** (`solver/constraints/*ConstraintsTest`) :
  chaque contrainte est vérifiée isolément avec le `ConstraintVerifier` de
  Timefold (dépendance `timefold-solver-test`). Ils ne démarrent ni Quarkus ni
  base de données et s'exécutent en quelques millisecondes ; ils s'appuient sur
  `ConstraintTestBase`, qui sélectionne la contrainte à tester par son nom et
  fournit les fabriques de fixtures (créneaux, stands, animateurs, postes).
  **Toute nouvelle contrainte doit y ajouter au moins un cas pénalisé et un cas
  valide.**
- **Tests d'intégration** (`@QuarkusTest`, ressources REST, persistance,
  `PlanningHardConstraintsTest`) : ils démarrent un PostgreSQL jetable via les
  *dev services* Quarkus (`postgres:17`), donc les migrations Flyway
  s'exécutent exactement comme en production.
- **Tests frontend** (`src/main/webui`, fichiers `*.spec.ts`) : lancés par
  `npm test` (builder `@angular/build:unit-test`, runner **Vitest**) dans un
  environnement Node/**jsdom**, sans navigateur. Ils couvrent surtout la logique
  des services et utilitaires de `app/core/` (mock d'`ApiService` via `TestBed`,
  fonctions pures de dates/formatage) et le rendu des composants légers de
  `app/shared/`. Ils ne sont **pas** branchés sur la phase de test Maven (Quinoa
  reste désactivé sur `%test`) ; ils tournent dans un job CI dédié.

Avec Podman (rootless), exposer la socket compatible Docker :

```bash
systemctl --user start podman.socket
export DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock
```

Règle non négociable : **aucune contrainte dure ne doit être violée** dans un
planning résolu valide. `PlanningHardConstraintsTest` assert que
`solved.getScore().hardScore()` vaut zéro sur le scénario nominal ; toute
nouvelle contrainte dure doit être couverte dans le même esprit.

## Réglage du solveur

Dans `application.properties` :

| Propriété | Défaut | Rôle |
| --- | --- | --- |
| `planning.solver.seconds-limit` | `180` (`3` en profil `%test`) | Durée maximale de résolution |
| `planning.solver.unimproved-seconds-limit` | `0` = désactivé (`2` en profil `%test`) | Arrêt anticipé si le score n'a pas progressé ; désactivé par défaut pour laisser la recherche locale utiliser tout le budget `seconds-limit` plutôt que d'abandonner sur un optimum local à hard > 0 |

La configuration Timefold elle-même est dans `src/main/resources/solver/solverConfig.xml`.
Le value range `animateurRange` couvre tous les animateurs (~150) car
l'éligibilité dépend du poste visé (compétence du stand, disponibilité à la
date), pas d'une propriété statique de l'animateur : `EligibleAnimateurMoveFilter`
rejette les change/swap moves manifestement invalides avant tout calcul de
score, ce qui multiplie par ~3 le débit de la recherche locale sur le scénario
complet (150 animateurs / 2088 postes) et est déterminant sur du matériel
contraint (Raspberry Pi).

Le `unionMoveSelector` de la recherche locale combine deux paires de
sélecteurs change/swap à poids égal (`fixedProbabilityWeight`) : une paire
générale (tous postes) et une paire dont un des deux côtés est restreint aux
postes non pourvus (`UnassignedPosteFilter`). Sans ce second groupe, une
sélection uniforme sur ~2000+ postes ne retombe qu'exceptionnellement sur les
quelques postes encore vides, et le solveur plafonnait avec 1 à plusieurs
dizaines de violations `posteDoitEtrePourvu` même après tout le budget de
180 s, alors que `FeasibilityAnalyzer` confirmait un scénario réalisable
(assez d'animateurs compétents et disponibles). Ce second groupe force une
part constante des mouvements à cibler directement ces postes vides — soit en
les pourvant avec un animateur encore libre à ce créneau, soit en délogeant
quelqu'un déjà affecté ailleurs à un autre créneau — ce qui suffit à ramener
le hard score à zéro sur `scenario-complet.yaml` dans le budget existant.

## Configuration

| Variable | Défaut | Usage |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/festival` | Connexion PostgreSQL |
| `DB_USER` | `festival` | Utilisateur base |
| `DB_PASSWORD` | `festival` | Mot de passe base |
| `HTTP_PORT` | `8080` | Port HTTP exposé |

## Base de données

Migrations Flyway dans `src/main/resources/db/migration/`, appliquées au
démarrage. **Un changement de schéma = un nouveau fichier versionné** ; ne jamais
éditer une migration déjà appliquée.

## Intégration continue

- `.github/workflows/tests.yml` — deux jobs sur chaque push `main` et chaque
  pull request : `test` (`./mvnw verify -DskipITs=false`, avec upload des
  rapports surefire/failsafe) et `frontend` (`npm ci` puis `npm test` sur
  `src/main/webui`, Node 24).
- `.github/workflows/docker-ghcr.yml` — publication de l'image sur GHCR, en
  multi-arch (`linux/amd64`, `linux/arm64` via QEMU) pour un déploiement natif
  sur Raspberry Pi.

## Mises à jour de dépendances (Renovate)

La configuration vit dans `renovate.json` à la racine. Renovate surveille :

- les dépendances Maven (`pom.xml`), y compris les propriétés de version
  (`quarkus.platform.version`, `timefold.solver.version`) ;
- les images Docker (`Dockerfile`, `src/main/docker/*`, `docker-compose.yml`) ;
- les actions GitHub (`.github/workflows/*`) ;
- les dépendances npm du frontend Angular (`src/main/webui/package.json`) ;
- la toolchain `mise.toml` (Java, Maven, Node).

Points de vigilance :

- les mises à jour **mineures et correctives** des dépendances de test sont
  regroupées pour limiter le bruit ;
- Quarkus et Timefold sont regroupés par écosystème : leurs montées de version
  doivent être validées par un `./mvnw verify -DskipITs=false` complet ;
- les paquets `@angular/*` sont regroupés dans une seule PR : une montée de
  version d'Angular doit être validée par un `npm run build` puis un
  `./mvnw verify -DskipITs=false` ;
- les montées de version majeures de Java (image de base, `mise.toml`,
  `maven.compiler.release`, workflows) restent des PR séparées, à traiter
  manuellement — elles touchent plusieurs fichiers à la fois.

Pour activer Renovate sur le dépôt : installer l'application GitHub
[Renovate](https://github.com/apps/renovate) et la laisser ouvrir sa PR
d'onboarding ; la configuration présente ici sera reprise telle quelle.

## Conventions de code

- Code et commentaires en anglais ; noms de domaine en français métier.
- Frontend Angular : composants standalone, `signal()` / `computed()` pour
  l'état, nouveau flot de contrôle `@if` / `@for` ; les appels HTTP passent par
  les services de `app/core/`.
- IHM en **Angular Material** : un bloc fonctionnel = une route = une page sous
  `app/pages/`. Le CSS global (`src/styles/`) ne couvre que ce que Material ne
  fournit pas ; les couleurs viennent des variables `--mat-sys-*` du thème
  (`src/material-theme.scss`).
- Voir [`architecture.md`](architecture.md) pour le découpage des modules et
  [`domaine.md`](domaine.md) pour les invariants du modèle.
