# Intégration continue et livraison

Vous saurez ce que chaque workflow de `.github/workflows/` vérifie et pourquoi une poussée n'en déclenche qu'une partie, comment les actions sont verrouillées, comment l'image est construite, signée et publiée. Les mises à jour de dépendances et l'inventaire des licences ont [leur page](renovate-et-dependances.md).

Tous les workflows tournent sur des runners GitHub (`ubuntu-latest`). Sur un dépôt public ces runners standard sont gratuits, donc la CI se parallélise en jobs plutôt qu'en runners plus gros. C'est aussi ce qui rend la provenance de l'image vérifiable. La garde qui empêche une PR de fork de déclencher ces workflows reste en place : elle tenait au socket Docker de l'ancien runner auto-hébergé, et la lever relève de l'ouverture du dépôt aux contributions externes.

## Les workflows

Les PR vers une branche de maintenance `release/X.Y` rejouent la CI comme celles vers `main` (aucun workflow ne filtre les branches cibles). Après fusion, seul `securite.yml` joue aussi sur la poussée vers `release/**` : ajouter cette branche aux poussées de `tests.yml` ou `scenario-tests.yml` fait échouer zizmor (`cache-poisoning`).

| Workflow | Rôle |
| --- | --- |
| `tests.yml` | Suite backend, E2E, contrôles du frontend, Sonar |
| `scenario-tests.yml` | Scénarios lents, sur les chemins qui peuvent casser la convergence du solveur |
| `e2e-lourd.yml`, `e2e.yml`, `e2e-suite.yml` | Specs Playwright `@lourd` ; lancement manuel de l'E2E ; suite réutilisable |
| `securite.yml`, `scorecard.yml` | Analyses de sécurité, détaillées dans [les analyses automatiques](../installer/securite-analyses-automatiques.md) |
| `docker-ghcr.yml`, `image-pr.yml` | Publication de l'image ; construction à blanc de l'image d'une PR |
| `restauration.yml` | Rejoue `scripts/restaurer.sh` sur une vraie pile |
| `release.yml` | Corps d'une release publiée |
| `licences-renovate.yml`, `artefacts-renovate.yml`, `angular-renovate.yml` | Commits de rattrapage sur les PR Renovate, voir [Renovate](renovate-et-dependances.md#renovate) |
| `numeros-reserves.yml` | Collision de numéros de migration ou d'ADR entre PR ouvertes |
| `verify-lockfile.yml` | Vérification du verrou des actions |
| `mutation.yml`, `analyse-dynamique.yml` | Chaque nuit sur `main` : tests de mutation sur les contraintes (PIT) ; OWASP ZAP *baseline* sur le jar de production |
| `site.yml`, `oidc-keycloak.yml` | Vitrine ; essai OIDC contre un vrai Keycloak |

## Pourquoi une poussée ne joue pas tout

Une poussée consomme des dizaines de minutes de runner quel que soit son contenu : la suite, l'E2E en deux moitiés, les scénarios dès qu'une PR a touché le solveur, la sécurité. Quatre filtres limitent ce coût.

- **Les poussées de documentation sautent la suite.** `tests.yml` ignore `docs/`, `site/` et le Markdown, moins les fichiers qu'un test relit ou que le job compare à son build (le contrat OpenAPI, l'inventaire des licences, les ADR, les diagrammes, les pages que `DiagramsStructuralTest` ou un autre test structurel lit), réinclus nommément. Le job `e2e` a un filtre plus étroit, calculé par un petit job `e2e-scope` qui relit le diff comme GitHub lit `paths`. Une page qui montre un diagramme doit figurer dans cette liste, sans quoi une poussée qui ne toucherait qu'elle ne jouerait pas le contrôle du diagramme.
- **Les specs `@lourd` ont leur workflow.** Il ne part sur une PR que sous le label `e2e-lourd` (ou `timefold`, `quarkus`, `playwright`), et chaque nuit sur `main`.
- **Une PR Renovate ne joue pas les scénarios** hors des labels `timefold` ou `quarkus`.
- **La mutation et l'analyse dynamique ne tournent plus sur les PR.** Six PR ouvertes en même temps saturaient la limite de jobs simultanés de GitHub, et ces deux mesures bougent peu d'une PR à l'autre. `mutation.yml` (PIT, environ cinq minutes) et `analyse-dynamique.yml` (ZAP *baseline* sur le jar de production, l'image ZAP et le profil de production d'avant) tournent chaque nuit sur `main`, et à la demande par `workflow_dispatch`. Un échec nocturne ouvre une issue, ou commente celle qui est déjà ouverte, comme le contrôle hebdomadaire du site. Avant de pousser une contrainte, lancez PIT en local ([les tests de mutation](tests-de-scenarios.md#les-tests-de-mutation)).

Sur une PR, GitHub évalue le filtre `paths` sur l'ensemble des fichiers de la PR, pas sur la dernière poussée : une PR mixte code et documentation rejoue tout à chaque poussée. C'est voulu, car un check « skipped » sur la dernière poussée masquerait le rouge de la précédente.

Sur une PR, une poussée annule les runs de la précédente : chaque workflow déclenché par `pull_request` porte un groupe `concurrency` par référence, avec `cancel-in-progress: ${{ github.event_name == 'pull_request' }}`. Sur `main` et les tags, rien n'est annulé : un run commencé va au bout, et dans une rafale de fusions seul le dernier run en attente part après lui (GitHub n'en garde qu'un par groupe). L'E2E lourd range en plus son groupe par type d'événement, pour qu'une fusion ne fasse pas attendre ni sauter le rattrapage nocturne. Les workflows de rattrapage Renovate (`artefacts-renovate.yml`, `angular-renovate.yml`) n'annulent jamais : ils poussent un commit, et l'interrompre en plein travail laisserait la branche à moitié régénérée.

## La suite de tests en parallèle

- **La suite backend est jouée en trois jobs.** `test` lance `verify -DskipITs=false -Pcoverage,unit` : les classes qui n'ont besoin que d'une JVM, les tests d'intégration, le packaging. Les deux moitiés de `test-conteneur` jouent les classes de `src/test/container-tests.txt`, une ligne sur deux chacune (`-Dsurefire.includesFile`). Chaque classe est dans un seul des trois, et `ContainerTestsInventoryTest` garde le fichier égal aux annotations. Les trois rapports JaCoCo vont à Sonar, qui les fusionne.
- **L'E2E est joué en deux moitiés**, chacune sur sa pile (`--shard=1/2` et `--shard=2/2`, par fichier de spec puisque `fullyParallel` est faux). Elles sont indépendantes par construction : chaque spec appelle `repartirDeLaReference` dans son `beforeAll` (la base revient au dump de référence, Mailpit est vidé), `globalSetup` capture la référence sur la pile de sa moitié, et la seule spec sans ce retour (`perimetre-authentification`) ne lit aucune donnée. Les checks s'appellent « e2e (1) / e2e » et « e2e (2) / e2e ».
- **Le jar est construit une fois.** Le job `test` téléverse `target/quarkus-app` (artefact `quarkus-app`, gardé sept jours pour qu'un « Re-run failed jobs » de l'E2E le retrouve) et `e2e-suite.yml` le reprend quand on lui passe `app-artifact`. Sans cet argument, la pile package elle-même, ce que font `e2e-lourd.yml` et `e2e.yml`. L'E2E n'attend que `test` : il tourne pendant les tests conteneur, et une classe conteneur rouge garde la PR rouge sans l'empêcher de partir. Le bundle Angular n'est construit que deux fois par poussée : par Quinoa dans le `verify` de `test`, et par `npm run build` dans `frontend`, qui en a besoin pour les budgets d'`angular.json`.
- **La couverture d'un `@QuarkusTest` demande l'extension `quarkus-jacoco`.** L'agent JaCoCo seul ne mesure rien : Quarkus réécrit le bytecode des beans qu'il charge, qui ne correspondent plus à `target/classes`, et chaque bean passait pour non couvert. Le profil `coverage` ajoute donc l'extension `quarkus-jacoco` (en `test`, elle n'entre pas dans le jar), l'agent laisse de côté le `QuarkusClassLoader`, et les deux écrivent dans `target/jacoco-quarkus.exec`.
- **SonarCloud est lancé par le job `sonar`**, pas par l'analyse automatique de SonarCloud, qui est désactivée : celle-ci ne lisait ni la couverture ni les exclusions. Le job attend `test` et `frontend`, reprend les classes compilées et les rapports JaCoCo (artefact `sonar-backend`) et le `lcov` de Vitest (`coverage-frontend`), puis lance le scanner avec le secret `SONAR_TOKEN`. Il tourne même quand une suite a échoué : la couverture est alors partielle, les constats restent lisibles. Sa configuration est `sonar-project.properties` : périmètre, chemins de couverture et chaque exclusion de règle avec sa raison.
- **`.mvn/maven.config` coupe la découverte des fichiers de préfixes** de Maven 3.10 (`aether.remoteRepositoryFilter.prefixes.resolvePrefixFiles=false`). Sinon chaque session Maven, y compris celle que Quarkus ouvre dans `generate-code-tests`, demande `/.meta/prefixes.txt` à chaque dépôt déclaré par un POM de dépendance, avec trois reprises : un dépôt qui répond 503 fait perdre jusqu'à dix minutes au job `test`. Le `Dockerfile` copie `.mvn/`, la construction de l'image lit donc le même réglage.

## Numéros de migration et d'ADR

Deux PR ouvertes en même temps prennent volontiers le même « suivant », et `FlywayMigrationsFrozenTest` ne le voit qu'une fois les deux sur `main`. Le job `frontend` lance donc `.github/scripts/check-reserved-numbers.sh` contre la pointe de la base, pas contre le point de départ de la branche : une migration ajoutée doit dépasser la plus haute version de la base (Flyway refuse une version plus basse qu'une version déjà appliquée), un ADR ajouté doit porter un numéro libre, et le message nomme le fichier déjà là. La seconde PR échoue à sa prochaine exécution après la fusion de la première, avant sa propre fusion.

`numeros-reserves.yml` regarde plus tôt, parmi les PR ouvertes : une collision y est un avertissement posé sur le fichier, jamais un échec, puisque aucune des deux n'a tort tant que rien n'est fusionné. Il échoue seulement quand l'API ne répond pas, pour ne pas conclure « aucune collision » sans avoir lu. `DocumentationStructuralTest` refuse enfin deux ADR du même numéro dans l'arbre.

## Le verrou des actions

Les actions sont épinglées par SHA dans les workflows et verrouillées dans `.github/workflows/actions.lock` ([`gh-actions-lock`](https://github.com/github/gh-actions-lock), extension `gh` en préversion dont le format bouge encore). Le verrou fige chaque action, directe ou transitive, à un commit, avec les identifiants numériques du propriétaire et du dépôt. Le runner le revérifie au démarrage de chaque job, et `verify-lockfile.yml` lance `gh actions-lock --verify` sur chaque PR et sur `main`. Les SHA restent en ligne (`@<sha> # vX`) : le verrou s'ajoute aux épingles, il ne les remplace pas.

Après tout ajout ou changement d'un `uses:` :

```bash
gh extension install github/gh-actions-lock --pin v0.5.1   # la version de verify-lockfile.yml
gh actions-lock --no-narrow   # SANS --no-narrow, l'outil réécrit les SHA en tags
gh actions-lock --verify      # lecture seule, comme la CI
```

Le verrou ne s'édite pas à la main. Renovate le régénère à chaque montée d'une action (il appelle l'outil avec `--no-narrow`), et un gestionnaire `custom.regex` suit la version de l'extension (`--pin vX.Y.Z` de `verify-lockfile.yml`), en PR à part et sans automerge.

Les trois `uses: ./.github/workflows/e2e-suite.yml` restent en `./` : la règle `self-repository` de zizmor est coupée dans `.github/zizmor.yml`, l'outil les accepte et le workflow appelé a sa propre entrée dans le verrou. Les images de conteneur sans `@sha256` (`postgres`, `mailpit`, l'image Playwright, celle de `scorecard-action`) ne sont pas couvertes : l'outil avertit sans bloquer.

Un workflow ajouté est lu par actionlint et zizmor (`securite.yml`, job `workflows`) : pas de `${{ … }}` dans le corps d'un `run:` (la valeur passe par `env:`), `persist-credentials: false` sur chaque `actions/checkout` qui ne pousse rien, aucun cache lu par un build qui publie une version. Les résultats de Trivy, Semgrep, zizmor, CodeQL et Scorecard sont publiés dans l'onglet *Security* (SARIF) en plus d'échouer dans le log.

## L'image

### Publication

`docker-ghcr.yml` pousse l'image sous le seul tag `sha-…`, la démarre contre un vrai PostgreSQL (smoke test), la scanne avec Trivy, la signe, puis publie `:main` à chaque fusion et `X.Y.Z` et `X.Y` sur un tag `vX.Y.Z`. Trois garde-fous :

- **Trivy passe avant la signature.** Une CVE corrigeable de gravité HIGH ou CRITICAL dans les paquets système arrête le job, l'image n'existe alors que sous `sha-…`.
- **Signature et attestations.** L'image porte un SBOM CycloneDX et une signature cosign en mode keyless, plus les attestations GitHub natives (provenance, SBOM).
- **Pas de cache pour une version.** Un tag `v*` est construit sans cache, couches et dépôt Maven compris. `:main` est aussi reconstruite sans cache chaque dimanche et sur lancement manuel, pour les correctifs Ubuntu.

`:latest` n'est jamais posé par `metadata-action` (`latest=false`) : une étape dédiée le pose, uniquement quand le tag poussé est le plus récent du dépôt, sinon la publication d'un correctif sur une ligne ancienne le ferait reculer. Le dépôt Maven de l'image est conservé d'un build à l'autre : le `RUN --mount=type=cache` du `Dockerfile` vit dans un builder BuildKit jetable, et `cache-to: type=gha` n'exporte que les couches, si bien que `actions/cache` (clé sur `pom.xml`) et `buildkit-cache-dance` versent `.m2` dans le builder avant la construction et l'en retirent à la fin.

`release.yml` écrit le corps d'une release publiée (git-cliff sur les messages de commit du tag) et ne touche à rien d'autre : ni tag, ni branche, ni fichier. Il n'y a pas de `CHANGELOG.md` à tenir. Il part sur `release: published`, de front avec `docker-ghcr.yml` que le push du même tag déclenche. Ses deux gardes de cohérence, la montée que les sujets demandaient et la branche où vit le tag, tournent après l'écriture : la release est déjà publiée, et un job rouge est le seul canal qui prévienne. La politique de version est dans [les versions et releases](../installer/versioning.md).

### Construire l'image d'une PR

Les jobs de `tests.yml` désactivent Quinoa et ne passent jamais par son installation npm avec le Node qu'il télécharge : une montée de Node pouvait passer verte en PR puis casser l'image de `main`. `image-pr.yml` construit donc l'image sans la publier. Il ne part que sur le `Dockerfile`, `docker/entrypoint.sh`, `.dockerignore`, `pom.xml`, `.mvn/`, `mise.toml`, les manifestes npm, la configuration de build Angular et `application.properties` (qui porte la version de Node de Quinoa). Les trois workflows Renovate le relancent après leur propre commit, sans filtre de chemins puisque `workflow_dispatch` n'en a pas. L'étape `package` du `Dockerfile` n'est pas en `-q`, pour que la sortie d'npm reste dans le log.

`restauration.yml` joue `scripts/restaurer.sh` pour de vrai : pile de production montée avec l'image du commit, sauvegarde déposée dans le volume, base modifiée, restauration, vérification. Il couvre aussi le mode `--essai`, le refus d'un fichier qui n'est pas un dump ou d'un dump tronqué, et le retour à l'état d'avant d'une restauration qui échoue en cours de route. Il ne part que sur `scripts/`, `docker-compose.prod.yml`, le `Dockerfile` et lui-même.

### Le contexte de construction

Le démon Docker reçoit le contexte entier avant que le moindre `COPY` ne soit évalué, et le `Dockerfile` ne copie que `pom.xml`, `.mvn`, `src` et `.git`. `.dockerignore` exclut donc `explorbot/`, `assets/`, `site/`, `.quinoa/`, `.claude/worktrees/` et `src/main/webui/coverage/`. En CI le gain est négligeable (le dépôt y est un `checkout` neuf), mais il compte pour le `docker compose --profile app up --build` local, et `assets/` porte des données réelles qui n'ont rien à faire dans un contexte d'image. Ajouter un dossier volumineux à la racine, c'est penser à l'exclure ici.

