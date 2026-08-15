# Développement

## Prérequis

- Java 25, Maven 3.9.9 et Node 24, épinglés dans `mise.toml` (`mise install`).
- Un runtime de conteneurs (Docker ou Podman) pour PostgreSQL et pour les tests
  (Quarkus dev services).

Node n'est nécessaire que pour travailler directement sur le frontend : le build
Maven télécharge sa propre version de Node via Quinoa
(`quarkus.quinoa.package-manager-install=true`), donc la CI et l'image Docker ne
demandent aucune installation préalable.

### Avertissements JDK 25 au démarrage

Le JDK 25 restreint l'accès natif (JEP 472) et signale les usages dépréciés de
`sun.misc.Unsafe` (JEP 498). Deux sources de bruit résiduelles, indépendantes
l'une de l'autre :

- **Le Maven wrapper** embarque ses propres jansi/guava (`.mvn/wrapper`), qui
  déclenchent ces avertissements dès `./mvnw -v`. `.mvn/jvm.config` les
  silence pour le processus Maven lui-même ; ce fichier ne dépend pas de la
  version de Quarkus.
- **La console interactive de `quarkus:dev`/`quarkus:test`** charge JNA, qui
  déclenche le même avertissement d'accès natif dans la JVM forkée par ces
  goals. La propriété `jvm.args` du `pom.xml` (reprise par les `argLine`
  surefire/failsafe) porte `--enable-native-access=ALL-UNNAMED` pour ça.

Netty (utilisé par Quarkus/Vert.x) déclenchait aussi ces deux avertissements
jusqu'à Quarkus 3.28 ; depuis cette version, Quarkus les évite nativement
([quarkusio/quarkus#49905](https://github.com/quarkusio/quarkus/issues/49905)),
donc l'image Docker de production ne nécessite plus aucun flag JVM.

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
npm start                              # ng serve seul sur http://localhost:4200 (API proxifiée vers :8080, voir proxy.conf.json)
npm test                               # tests unitaires (Vitest) : watch en terminal, une passe en CI
```

En pratique, `./mvnw quarkus:dev` suffit : Quinoa démarre `ng serve` et le
proxifie sur http://localhost:8080, backend et frontend rechargent à chaud
ensemble. Le build Maven (`package`, `verify`) reconstruit toujours le frontend ;
il est désactivé sur le profil `%test` pour ne pas ralentir les tests unitaires.

`skipITs` vaut `true` par défaut dans le `pom.xml` : les tests `*IT` (failsafe)
ne s'exécutent qu'avec `-DskipITs=false`.

### Limitation connue : F5 / lien profond en mode dev sur :8080

Naviguer directement (F5, lien profond, favori) vers une route Angular autre
que `/` via **http://localhost:8080** (le proxy Quinoa) renvoie un 404 en mode
`quarkus:dev` — bug amont non résolu de Quinoa
([#666](https://github.com/quarkiverse/quarkus-quinoa/issues/666),
[#91](https://github.com/quarkiverse/quarkus-quinoa/issues/91)) : pour
distinguer un fichier statique manquant d'une route SPA, Quinoa retire l'en-tête
`Accept` de ses requêtes internes vers `ng serve`, ce qui désactive au passage
le fallback historique (`historyApiFallback`) qu'Angular CLI utilise pour
servir `index.html`. **N'affecte pas la production** (Quarkus sert alors les
fichiers statiques directement, sans ce proxy).

Contournement pour tester un lien profond ou un F5 en dev : ouvrir
**http://localhost:4200** directement (`ng serve`, lancé automatiquement par
`quarkus:dev`) plutôt que `:8080` — son propre serveur de dev gère le fallback
SPA correctement, et `proxy.conf.json` redirige `/api/*` vers le backend
(`:8080`) donc les appels API fonctionnent aussi. Le hot-reload frontend reste
actif dans les deux cas.

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

### Mesurer la couverture

```bash
./mvnw test -Pcoverage     # puis ouvrir target/site/jacoco/index.html
```

Profil **opt-in**, comme `scenario-tests` et `generate-schema` : ni le build par
défaut ni la CI ne l'activent, l'instrumentation JaCoCo n'ayant d'intérêt que
lorsqu'on cherche activement des trous. Référence actuelle : **82,8 %**
d'instructions, avec `solver/constraints` à 99,3 % et `solver` à 98,6 %, contre
68 % pour `api` et 80 % pour `service` — ce dernier dominé par
`ReferenceDataRepository`, du code d'accès aux données dont le test coûte cher
pour ce qu'il protège.

Le pourcentage n'est pas un objectif en soi : les tests d'accesseurs le font
monter sans rien protéger. Deux garde-fous valent mieux qu'un point de
couverture, et sont d'ailleurs nés de bugs réels de ce dépôt :

- `ScenarioSchemaGeneratorTest` échoue dès que `docs/schema/scenario-schema.json`
  diverge des DTO. Le schéma se régénère par un profil opt-in que rien
  n'obligeait à lancer : deux champs ajoutés à `ParametresDecoupageDto` sont
  restés absents du schéma publié, et un éditeur validant un scénario contre
  lui signalait deux clés parfaitement valides comme inconnues.
- `ReferenceDataResourceDecoupageAutoFamillesTest` verrouille l'ordre
  d'application `parametresDecoupage` **puis** `decoupageAuto` à l'import.
  Inversé, un scénario qui épingle son propre découpage verrait ses vacations
  générées avec la configuration du serveur — sans erreur visible.

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
| `planning.constraint-weights.<nomDeLaContrainte>` | `1` pour chaque contrainte | Poids de la contrainte (multiplie le hard/medium/soft qu'elle produit) ; voir [`contraintes.md`](contraintes.md#pondérer-une-contrainte) |

La configuration Timefold elle-même est dans `src/main/resources/solver/solverConfig.xml`.
Le value range `animateurRange` couvre tous les animateurs (~150) car
l'éligibilité dépend du poste visé (disponibilité à la date, règles légales
mineurs), pas d'une propriété statique de l'animateur : `EligibleAnimateurMoveFilter`
rejette les change/swap moves manifestement invalides avant tout calcul de
score, ce qui multiplie par ~3 le débit de la recherche locale sur le scénario
complet (150 animateurs / 2088 postes) et est déterminant sur du matériel
contraint (Raspberry Pi). La compétence (appréciation) n'en fait plus partie
depuis sa bascule en contrainte medium (`appreciationIncompatible`) : un
animateur sans appréciation reste un mouvement structurellement valide, juste
pénalisé, donc le filtre ne peut plus l'exclure sans rendre certaines
solutions optimales inatteignables.

Le `unionMoveSelector` de la recherche locale combine deux paires de
sélecteurs change/swap à poids égal (`fixedProbabilityWeight`) : une paire
générale (tous postes) et une paire dont un des deux côtés est restreint aux
postes non pourvus (`UnassignedPosteFilter`). Sans ce second groupe, une
sélection uniforme sur ~2000+ postes ne retombe qu'exceptionnellement sur les
quelques postes encore vides, et le solveur plafonnait avec 1 à plusieurs
dizaines de violations `posteDoitEtrePourvu` même après tout le budget de
180 s, alors que `FeasibilityAnalyzer` confirmait un scénario réalisable
(assez d'animateurs disponibles). Ce second groupe force une
part constante des mouvements à cibler directement ces postes vides — soit en
les pourvant avec un animateur encore libre à ce créneau, soit en délogeant
quelqu'un déjà affecté ailleurs à un autre créneau — ce qui suffit à ramener
le hard score à zéro sur `scenario-complet.yaml` dans le budget existant.

### Coût des contraintes : joiners indexés plutôt que `filtering`

La vitesse de résolution est dominée par le nombre de tuples que les
*constraint streams* construisent et maintiennent à chaque mouvement, pas par
le nombre de contraintes. Un `forEachUniquePair` suivi d'un `filter` construit
**toutes** les paires avant d'en écarter la quasi-totalité ; un `Joiners.equal`
en fait un accès indexé.

Mesuré sur `scenario-complet.yaml` (2088 postes, 150 animateurs, 36 créneaux,
`randomSeed=0` donc trajectoire de recherche identique d'un run à l'autre), la
reformulation de quatre contraintes en joiners indexés a supprimé ~202 000
tuples de paires inutiles :

| Contrainte | Paires construites avant | Après |
| --- | --- | --- |
| `incompatibiliteAdHoc` | 59 508 (toutes les paires de postes d'un même créneau) | 0 tant qu'aucune incompatibilité n'est saisie |
| `eviterRoulementStandsPremium` | 115 596 (toutes les paires de postes d'un même stand) | 0 (le scénario n'a aucun stand premium) |
| `reposQuotidienMineur` *(depuis remplacée par `reposQuotidienMinimal`, cf. audit RH)* | ~13 500 (toutes les paires de postes d'un animateur) | les seuls créneaux de nuit tenus par un mineur |
| `eviterChangementEmplacementEloigne` | ~13 500 (idem) | les seuls créneaux réellement enchaînés |

Effet mesuré, à trajectoire de recherche inchangée (mêmes 1220 pas de
recherche locale, mêmes scores intermédiaires et final
`0hard/-341medium/-2742soft`) :

| Phase | Avant | Après |
| --- | --- | --- |
| Heuristique de construction | 4 848 ms — 17 122 mouvements/s | 2 086 ms — 40 396 mouvements/s |
| Recherche locale | 22 041 ms — 4 907 mouvements/s | 7 313 ms — 16 236 mouvements/s |
| **Total jusqu'à faisabilité** | **22,0 s** | **7,3 s** |

#### Effet des contraintes légales issues de l'audit RH

Mesures faites pendant l'implémentation des correctifs de
[`audit-conformite-rh.md`](audit-conformite-rh.md), sur la même machine et le
même `randomSeed=0`, avec `resoudreJusquaFaisabilite` :

| État | Vitesse d'évaluation | Temps jusqu'à `0hard` |
| --- | --- | --- |
| Avant B1/B2/B6/B7 (créneaux 4 h / 6 h / 4 h) | 6 673 mouvements/s | 31 s |
| Après, **sans** retiming du scénario | 5 515 mouvements/s | jamais atteint (−5 hard après 400 s) |
| Après, avec le scénario retimé en 4 h / 4 h / 4 h espacées | — | 21 s |

Deux enseignements. (1) Le coût *par mouvement* des sept contraintes légales
ajoutées est modeste (−17 %) : ce ne sont pas elles qui empêchaient la
convergence. (2) Ce qui l'empêchait, c'est que l'ancien découpage de journée
(matin 08 h-12 h, après-midi 14 h-20 h, soirée 20 h-00 h) devient
**structurellement infaisable** sous les nouvelles règles : la soirée se termine
à minuit, donc un animateur n'a droit au matin suivant qu'à partir de 11 h, et
le vivier d'une typologie rare (ROLE : 19 sièges par créneau pour 32 animateurs
compétents) ne suffit plus. Le scénario a donc été retimé — voir le commit
correspondant.

`EligibleAnimateurMoveFilter` a par ailleurs été étendu aux exclusions légales
décidables sur le seul couple (poste, animateur) — mineur la nuit, mineur sur un
stand réservé aux majeurs, créneau plus long que le plafond quotidien ou continu
d'un mineur. Elles ne peuvent, par construction, écarter aucune solution
faisable, et évitent au solveur de payer un calcul de score pour découvrir une
violation certaine.

Règle à suivre pour toute nouvelle contrainte : exprimer d'abord ce qui peut
l'être en `Joiners.equal` / `lessThan` / `overlapping`, restreindre le flux
d'entrée avec un `filter` **avant** de joindre, et ne garder `Joiners.filtering`
que pour ce qui n'est pas indexable (ici : le calcul de distance haversine et
la comparaison de périmètre des contraintes ad hoc). La métrique à regarder est
le *move evaluation speed* du log solveur, pas le temps écoulé.

### Mode d'environnement et parallélisme

`solverConfig.xml` fixe `<environmentMode>REPRODUCIBLE</environmentMode>`.
Timefold 1.x démarre sinon en `PHASE_ASSERT`, qui recalcule intégralement le
score à chaque frontière de phase pour détecter une corruption : mesuré à
**+7,4 %** de *move evaluation speed* une fois retiré (4074 → 4377/s sur le
scénario de référence). Le déterminisme reste assuré par `<randomSeed>`. En
contrepartie la détection de corruption de score est désactivée — en cas de
doute, repasser à `PHASE_ASSERT`, ou `FULL_ASSERT` pour identifier le move
fautif.

Il n'y a **pas** de `<moveThreadCount>` : la résolution incrémentale
multi-thread appartient à Timefold Solver *Enterprise Edition*, produit
commercial. Avec `timefold-solver-core` seul, le solveur refuse de démarrer si
le paramètre est présent (« Enterprise Edition could not be loaded »). Les
cœurs disponibles restent donc inexploités, et c'est le plafond de performance
principal du déploiement — inutile de chercher à le contourner côté
contraintes. Détail des mesures dans
[`revue-contraintes.md`](revue-contraintes.md).

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
  `src/main/webui`, Node 24). Le job `test` n'exécute **pas** les tests de
  scénario grande échelle (`@Tag("scenario-lent")`,
  `PlanningServiceScenarioCompletTest`/`PlanningServiceScenarioContinuTest`,
  ~25-75 s chacun) : ils sont exclus par défaut via la propriété
  `test.excludedGroups` du `pom.xml` et ne se lancent qu'en local avec
  `./mvnw test -Pscenario-tests` (profil `scenario-tests`). Voir
  `AGENTS.md` (« Costly test jobs ») pour la marche à suivre côté agent. La
  phase `package` de `mvn verify` active Quinoa (désactivé seulement sur le
  profil `%test`), qui télécharge son propre binaire Node et lance
  `npm ci` à chaque run sans cache : le job `test` met donc en cache
  `.quinoa` (binaire Node, clé sur `application.properties` où la version est
  épinglée) et `src/main/webui/node_modules` (clé sur `package-lock.json`)
  séparément de la mise en cache npm du job `frontend` (celle-ci passe par
  `actions/setup-node`, qui ne s'applique pas ici — ce job n'installe pas de
  Node système, Quinoa utilise le sien).
- `.github/workflows/docker-ghcr.yml` — publication de l'image sur GHCR, en
  multi-arch (`linux/amd64`, `linux/arm64` via QEMU) pour un déploiement natif
  sur Raspberry Pi. Ne se déclenche **que** sur un push `main` ou un tag de
  release (`v*`) — jamais sur une branche ou une pull request, pour ne pas
  payer le coût (temps + minutes CI) d'un build multi-arch complet à chaque
  push d'une branche de travail.

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

## Synchronisation des agents IA

Les profils d'agents personnalisés sont maintenus dans deux emplacements :

- `/.claude/agents/*.md` pour Claude Code ;
- `/.github/chatmodes/*.chatmode.md` pour Copilot.

Toute modification d'un profil doit être reportée dans son équivalent de
l'autre écosystème dans le **même commit**, afin de garder une synchronisation
stricte des deux versions.

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
