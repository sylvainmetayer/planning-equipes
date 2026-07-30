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
```

En pratique, `./mvnw quarkus:dev` suffit : Quinoa démarre `ng serve` et le
proxifie sur http://localhost:8080, backend et frontend rechargent à chaud
ensemble. Le build Maven (`package`, `verify`) reconstruit toujours le frontend ;
il est désactivé sur le profil `%test` pour ne pas ralentir les tests unitaires.

`skipITs` vaut `true` par défaut dans le `pom.xml` : les tests `*IT` (failsafe)
ne s'exécutent qu'avec `-DskipITs=false`.

## Tests

On distingue deux familles de tests :

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
| `planning.solver.unimproved-seconds-limit` | `60` (`2` en profil `%test`) | Arrêt anticipé si le score n'a pas progressé |

La configuration Timefold elle-même est dans `src/main/resources/solver/solverConfig.xml`.
Le value range `animateurRange` couvre tous les animateurs (~150) car
l'éligibilité dépend du poste visé (compétence du stand, disponibilité à la
date), pas d'une propriété statique de l'animateur : `EligibleAnimateurMoveFilter`
rejette les change/swap moves manifestement invalides avant tout calcul de
score, ce qui multiplie par ~3 le débit de la recherche locale sur le scénario
complet (150 animateurs / 2088 postes) et est déterminant sur du matériel
contraint (Raspberry Pi).

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

- `.github/workflows/tests.yml` — `./mvnw verify -DskipITs=false` sur chaque push
  `main` et chaque pull request, avec upload des rapports surefire/failsafe.
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
