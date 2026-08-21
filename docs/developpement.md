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
5. Vérifiez : `npm run i18n-check` (depuis `src/main/webui`).

### Le garde-fou `i18n-check`

`npm run i18n-check` extrait les messages sources et les confronte à
`messages.en.json`. Il échoue sur trois écarts, chacun invisible autrement :

| Écart | Conséquence sans le contrôle |
| --- | --- |
| Id présent dans le code, absent du catalogue | L'écran s'affiche **en français** pour un lecteur anglophone. `$localize` retombe sur la source sans rien signaler. |
| Clé présente dans le catalogue, absente du code | Poids mort, et généralement la moitié oubliée d'un renommage. |
| Placeholders divergents entre source et traduction | Casse **à l'affichage**, sur ce seul écran, en anglais uniquement : ni le build ni les tests ne la voient. |

Ce contrôle tourne dans le job `frontend` du workflow Tests. Il a été ajouté
après avoir trouvé 56 identifiants sans traduction et 6 messages aux
placeholders divergents — dont trois où le catalogue anglais avait *renommé* le
placeholder (`{$adresse}` pour `{$INTERPOLATION}`), si bien que l'utilisateur
anglophone voyait le jeton littéral au lieu de la valeur, et deux où le
français avait été réécrit sans que l'anglais suive. Rien n'avait signalé ces
six-là pendant des mois.

Les avertissements « Duplicate messages with id » de l'extraction sont
affichés mais ne font **pas** échouer le contrôle : ils signalent un id partagé
par deux textes sources différents, ce qui est un problème réel mais distinct,
et à traiter en renommant l'un des deux.

## Accessibilité du frontend

Ces conventions sont appliquées sur les 26 écrans ; les tenir coûte peu à
l'écriture, les rattraper après coup coûte cher.

**Un `<h1>` par écran, et un seul.** `mat-card-title` rend une `<div>` : écrivez
`<h1 mat-card-title>` sur le titre principal de la page et `<h2 mat-card-title>`
sur les cartes suivantes. Six écrans dont la première carte n'est pas leur
identité (Solveur, Contraintes, Créneaux, Données, Verrouillages, Calendrier)
portent un `<h1 class="page-title">` en tête de template. Naviguer par titres
est le premier réflexe au lecteur d'écran.

**Le résultat d'une action s'annonce.** `app-status-message` est le seul endroit
où une page dit « enregistré », « supprimé » ou une erreur :
`tone="error"` produit un `role="alert"` (interrompt, l'action a échoué), les
autres un `role="status"` (poli). N'écrivez pas un `<p>` inerte pour ça.

**Une erreur de formulaire est reliée à son champ.** `mat-error` dans le
`mat-form-field` quand l'erreur porte sur un champ ; `role="alert"` sur le
message quand elle porte sur plusieurs (`stand-form-dialog` en donne les deux
formes).

**Le focus survit à la suppression d'une ligne.** Un formulaire répétable
détruit le bouton focalisé : appelez `focusApresSuppression(...)`
(`core/focus-apres-suppression.ts`) pour le rendre au bouton « Ajouter » de la
section, via un attribut `data-focus`.

**Un tableau de données porte une `<caption>`** masquée (`.visually-hidden`)
disant ce qu'il compte et sur quel périmètre, et ses en-têtes leur `scope`.

**Une longue liste se filtre, elle ne se déroule pas.**
`app-selection-recherche` (autocomplétion, jetons en mode multiple) remplace un
`mat-select` dès que la liste dépasse quelques dizaines d'entrées : 153
animateurs au clavier, c'est 153 flèches.

**Une grille se parcourt aux flèches.** Heatmap et calendrier mensuel utilisent
un *roving tabindex* (un seul arrêt de tabulation, les flèches déplacent la
sélection). N'écrivez `role="grid"` que si la structure lignes/cellules existe
réellement — sinon `role="group"` et des boutons.

**La couleur n'est jamais seule** : doublez-la d'un texte, d'une icône ou d'une
initiale (pastilles de typologie, dépassements d'heures). Et une infobulle
n'existe pas au tactile : ce qu'elle dit doit exister ailleurs.

## Tests

On distingue quatre familles de tests :

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
- **Tests de bout en bout** (`src/main/webui/e2e`, **Playwright**) : voir
  ci-dessous — exécutés à la main, jamais en CI.

### Tests de bout en bout (Playwright)

`src/main/webui/e2e` exerce le frontend dans un vrai navigateur :

- **périmètre de sécurité** (issue #165) : mur d'authentification (401 nus,
  redirection `/login`, connexion/déconnexion), frontière de l'espace
  animateur (jeton inconnu, absence de chrome admin, pas de session offerte
  par le jeton) ;
- **foire au planning de bout en bout** : soumission → accord du collègue
  ciblé (depuis son propre espace) → acceptation admin → échange appliqué
  côté animateur, refus avec motif transmis, annulation depuis l'espace ;
- **balayage de l'admin** : chaque route du menu se charge et affiche son
  contenu, bascule de langue FR/EN, catalogue des contraintes ;
- **CRUD référentiels** via l'interface (animateur avec e-mail et lien
  d'espace copiable/régénérable, typologie), filtre rapide, consultation,
  suppression confirmée ;
- **vues du planning** sur les données ensemencées (heures, besoin en
  effectif, ouvertures, timeline animateur — y compris l'envoi des plannings
  par e-mail : individuel depuis la timeline, groupé depuis la page Solveur,
  avec son compte rendu), **verrouillages** (pose d'un
  verrou de journée avec aperçu d'impact, retrait) et **page d'aide** (recherche,
  section foire au planning) ;
- **résolutions réelles** (`e2e/solveur.spec.ts`, solves courts de ~6 s via
  `?seconds=`) : un solve lancé depuis la page Solveur pourvoit tous les
  postes, les trois types de contrainte ad hoc sont respectés par le résultat
  **et** visibles dans le frontend (écran Contraintes ad hoc, timeline de
  l'animateur imposé), un verrouillage `ANIMATEUR` fige un planning à
  l'identique après re-résolution, et un échange accepté **survit à la
  régénération** grâce à ses verrous `ANIMATEUR_CRENEAU` ;
- **cas limites du périmètre** (`e2e/cas-limites.spec.ts`) : la régénération
  du jeton vue du navigateur (l'ancien lien meurt en impasse propre, le
  nouveau reprend l'espace), une demande vers un collègue indisponible
  signalée infaisable **des deux côtés** (alerte métier chez l'animateur,
  bandeau « Signalée infaisable » chez l'admin), la **fermeture de la foire**
  (l'espace passe en consultation seule : formulaire absent, refus serveur,
  planning toujours téléchargeable en PDF/ICS, réouverture par le même
  interrupteur), et un lien profond admin sans session qui repasse par
  `/login` ;
- **verrou d'édition pendant un solve** (`e2e/verrou-edition.spec.ts`) : le
  job est lié à l'édition depuis laquelle il a été soumis ; pendant qu'il
  tourne, la saisie est verrouillée sur cette édition-là (et le moniteur de la
  barre d'outils la nomme), tandis qu'une autre édition reste éditable et
  ouverte à l'import de scénario ;
- **fuzzing à invariants** (`e2e/solveur-fuzz.spec.ts`) : des référentiels
  aléatoires mais **reproductibles** (PRNG semé, graine affichée dans la
  sortie et rejouable avec `E2E_FUZZ_SEED=<graine>`) sont résolus pour de
  vrai, puis le planning persisté est vérifié structurellement — tous les
  postes pourvus, aucun chevauchement, indisponibilités personnelles et
  forcées respectées, incompatibles jamais réunis, affectation forcée
  honorée, stand réservé aux majeurs sans mineur, score dur nul.

Les créneaux de test vivent dans la plage réservée `987000–987999` et toutes
les lignes ensemencées portent un préfixe `E2E-`/`SOLV-`/`FUZZ-` : la suite ne
touche jamais à des données hors de ce périmètre, mais elle réécrit le
planning persisté de l'édition courante — d'où la pile jetable. La suite
épingle l'édition `DEFAUT` (en-tête `X-Edition-Id` côté API, `localStorage`
côté navigateur, voir `e2e/support.ts`) : elle ne dépend pas de l'édition que
l'instance visée flague par défaut.

Attention, le profil `%dev` pointe sur le Postgres du docker-compose
(`localhost:5432/festival`) : deux `quarkus:dev` (même sur des ports
différents) partagent **la même base**. Pour une pile réellement jetable :

```bash
podman run -d --name planning-e2e-pg -p 5433:5432 \
  -e POSTGRES_USER=festival -e POSTGRES_PASSWORD=festival \
  -e POSTGRES_DB=festival docker.io/library/postgres:17
DB_URL="jdbc:postgresql://localhost:5433/festival" \
  ./mvnw quarkus:dev -Dquarkus.http.port=8081
E2E_BASE_URL=http://localhost:8081 npm run e2e   # depuis src/main/webui
podman rm -f planning-e2e-pg                          # à la fin
```

Ils sont **volontairement exclus de la CI** : ils exigent la pile complète et
écrivent en base (ensemencement idempotent d'identifiants `E2E-*` via
`/api/database/import`, décisions réelles) — à ne lancer que contre une pile
locale jetable.

```bash
# 1. La pile : Postgres + Mailpit + l'application packagée (ou `quarkus:dev`).
#    Mailpit est OBLIGATOIRE : l'espace animateur s'authentifie par code
#    envoyé par e-mail, et la suite lit ces codes dans l'API Mailpit — ne pas
#    mocker les mails (laisser MAIL_MOCK à sa valeur par défaut, false).
docker compose up -d postgres mailpit
./mvnw package -DskipTests
java -jar target/quarkus-app/quarkus-run.jar

# 2. La suite (depuis src/main/webui ; navigateur : npx playwright install chromium)
npm run e2e
```

Deux projets Playwright : `chromium` (bureau, toute la suite) et **`mobile`**,
qui rejoue la seule suite de l'espace animateur sur un viewport de téléphone —
c'est de là que la plupart des animateurs ouvrent leur lien, l'interface
d'administration assumant d'être une interface de bureau :

```bash
E2E_BASE_URL=http://localhost:8081 npx playwright test --project=mobile
```

Variables : `E2E_BASE_URL` (défaut `http://localhost:8080`),
`E2E_ADMIN_PASSWORD` (défaut `admin`, doit refléter l'`ADMIN_PASSWORD` de
l'application), `E2E_MAILPIT_URL` (défaut `http://localhost:8025`),
`E2E_CHROMIUM` (chemin d'un Chromium déjà installé, pour un
environnement qui interdit le téléchargement du navigateur). Les specs
s'exécutent en série (`workers: 1`) : elles partagent la base et le jeu de
données ensemencé.

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
| `planning.solver.unimproved-seconds-limit` | `300` (`2` en profil `%test`), `0` = désactivé | Arrêt anticipé sur plateau, **conditionné à la faisabilité** : voir ci-dessous |
| `planning.constraint-weights.<nomDeLaContrainte>` | `1` pour chaque contrainte | Poids de la contrainte (multiplie le hard/medium/soft qu'elle produit) ; voir [`contraintes.md`](contraintes.md#pondérer-une-contrainte) |
| `planning.jobs.reprise-au-demarrage` | `true` (`false` en profil `%test`) | Rejoue la file de résolution persistée au démarrage — voir [`api.md`](api.md#la-file-survit-au-redémarrage). En test, une tâche laissée en file par une exécution précédente déclencherait un vrai solve au démarrage de la suivante |

### `acceptedCountLimit` : mesuré, pas hérité

Le nombre de mouvements candidats échantillonnés par pas de recherche locale
(`solverConfig.xml`, `<forager>`) est le réglage le plus sensible du fichier, et
il dépend de la **taille du problème**. Mesures sur le scénario réel 2026
(3 502 postes, 153 animateurs), même graine, budget de 180 s, runs séquentiels :

| `acceptedCountLimit` | 1 | 2 | 3 | 4 | 10 | 20 | 40 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| score dur à 180 s | 0 | 0 | -1 | -3 | -6 | -14 | -27 |
| 0 hard atteint à | 95 s | 137 s | jamais dans le budget | | | | |

La valeur était à 20, réglée pour la qualité de chaque pas sur le scénario de
référence de 2 088 postes. À 3 502 postes le compromis s'inverse : un pas coûte
assez cher pour qu'échantillonner 20 candidats affame la recherche, et ce
réglage « prudent » n'atteint jamais la faisabilité — un run de production de
1800 s plafonnait à -2 hard là où 2 y arrive en moins de trois minutes.

2 plutôt que 1 : les deux atteignent la faisabilité sur toutes les graines
essayées, 1 y arrive ~45 s plus tôt mais avec un medium nettement moins bon
(-9 211 contre -8 502 à temps égal) — or c'est précisément le polissage
medium/soft qui occupe le budget une fois la faisabilité atteinte.

#### Deux phases : trouver, puis polir

Un `acceptedCountLimit` bas trouve la faisabilité mais polit mal, un haut fait
l'inverse. Les deux objectifs n'appellent donc pas le même réglage, et la
recherche locale est découpée en **deux phases** : la première à 2, terminée
par `bestScoreFeasible`, la seconde à 40 pour le reste du budget. Mesuré à
600 s :

| Configuration | 0 hard atteint | medium | soft |
| --- | --- | --- | --- |
| `acl 2` seul | 146 s | -7 987 | -539 |
| `acl 20` seul | jamais (-2 hard) | -6 878 | -720 |
| `2 puis 20` | 145 s | -6 876 | -715 |
| **`2 puis 40`** | **145 s** | **-6 704** | -716 |

L'avantage de 40 sur 20 en phase 2 est vérifié sur trois graines : -6 704 /
-6 703 / -6 762 contre -6 876 / -6 867 / -6 879.

Confirmé en conditions réelles sur le scénario 2026 (3 502 postes, 153
animateurs, 45 stands premium), budget de production de 1800 s :

| Configuration | dur | medium | dont `appreciationIncompatible` |
| --- | --- | --- | --- |
| `acl 20`, une phase | **-2** | -7 217 | -1 125 |
| `acl 2`, une phase | 0 | -9 055 | **-2 322** |
| **deux phases 2 → 40** | **0** | **-7 266** | -1 236 |

C'est bien le compromis visé, et il faut lire la troisième colonne pour le
comprendre : `acl 2` seul atteignait la faisabilité, mais en doublant le nombre
de postes tenus par un animateur sans appréciation sur la typologie du stand
(deux tiers du planning contre un tiers). Les deux phases donnent la
faisabilité **et** gardent la qualité du réglage historique. Timefold conserve toujours la
meilleure solution rencontrée, donc la phase 2 ne peut pas reperdre la
faisabilité acquise par la phase 1. Les deux sélecteurs ciblés sur les postes
non pourvus ne sont pas repris en phase 2 : à ce stade il n'y en a plus.

`lateAcceptanceSize` a été remesuré au passage : 100 / 400 / 1500 donnent
-14 / -14 / -12 à `acceptedCountLimit` 20, et -1 / 0 / 0 à 2. En dessous de 400
il dégrade, au-dessus il ne change rien — laissé tel quel.

**À refaire si la taille du problème change nettement.** Le protocole : rejouer
le vrai problème (`GET /api/planning/persisted`, champ `score` retiré,
animateurs remis à `null`) hors du serveur de dev, une configuration à la fois
— deux solveurs concurrents se partagent cache et bande passante mémoire et la
comparaison ne veut plus rien dire.

### Geler un bloc acquis pour rétrécir l'espace de recherche

`PosteAffectation.verrouille` porte l'annotation `@PlanningPin` de Timefold, et
un `VerrouillagePlanning` de type `STAND` couvre toutes les places d'un stand
sur tous ses créneaux. Poser un tel verrou sur un bloc volumineux mais facile —
le montage et le démontage de `edition-1708` pèsent 712 des 3 502 postes, soit
20 %, pour deux stands mono-typologie sans contrainte de compétence — retire
ces entités de la génération de mouvements **sans les retirer du planning** :
elles restent affectées, nominatives et visibles partout, et leurs heures
continuent d'être évaluées par les contraintes légales (un poste épinglé
participe toujours au calcul du score).

Le gain porte donc sur l'espace de recherche, pas sur le coût d'un calcul de
score. Contrepartie : le verrou fige ces personnes-là sur ces places et
consomme leurs heures, il n'est donc sain que si l'affectation gelée est déjà
bonne — d'où l'ordre « résoudre, vérifier, puis verrouiller », et la réversion
par `DELETE /api/verrouillages/{id}`. Aucun code n'est nécessaire : c'est deux
appels d'API ou deux clics sur l'écran Verrouillages.

### Arrêt anticipé : plateau **et** planning faisable

Une résolution s'arrête de deux façons, à la première des deux : le budget
`seconds-limit` est épuisé, **ou** le planning est déjà faisable (score dur à
zéro) et n'a plus progressé depuis `unimproved-seconds-limit`. C'est un `AND`
entre `bestScoreFeasible` et la limite de plateau, monté dans
`PlanningService#applyTermination`.

Le conditionnement à la faisabilité n'est pas cosmétique : c'est ce qui a permis
de réactiver cette limite. Une limite de plateau nue — ce que la propriété
configurait avant d'être mise à `0` — coupe aussi la recherche locale sur un
plateau de score **dur** : le solveur abandonnait plusieurs minutes trop tôt sur
un planning qui avait encore des postes non pourvus, exactement le cas où il a
besoin de tout son budget. Ainsi conditionné, l'arrêt ne peut rogner que du
temps passé à polir le medium/soft d'un planning déjà exploitable.

Mesures sur `edition-1708` (3 502 postes, 153 animateurs), budget par budget :
600 s → -11 hard, 1200 s → -2 hard, 1800 s → 0 hard. Sur ce scénario, le budget
de 30 min n'a donc pas de gras à couper avant d'atteindre la faisabilité — c'est
après, sur le medium/soft, que l'arrêt anticipé peut jouer.

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
| `ADMIN_PASSWORD` | `admin` | Mot de passe du compte `admin` (form login, issue #165) |
| `PROXY_ADDRESS_FORWARDING` | `true` | Suivre les en-têtes `X-Forwarded-*` derrière un reverse proxy TLS — voir [`api.md`](api.md#derrière-un-reverse-proxy-qui-termine-le-tls) |
| `SESSION_ENCRYPTION_KEY` | *(vide)* | Clé (≥ 16 caractères) du cookie de session ; générée au démarrage si absente (les sessions ne survivent alors pas à un redémarrage) |
| `MAIL_HOST` / `MAIL_PORT` / `MAIL_FROM` / `MAIL_MOCK` / `MAIL_ADMIN` | voir `application.properties` | Notifications d'échange par mail |
| `PUBLIC_URL` | `http://localhost:8080` | Base des liens « espace animateur » imprimés sur les PDF |

### Authentification admin en développement

Toute l'API `/api/*` est derrière le form login Quarkus (compte unique
`admin`/`admin` en local), **sauf** l'espace animateur, `/api/auth/*` et
`/api/config` — voir [`api.md`](api.md#authentification). Le profil `%test`
désactive la policy (`permit`) pour que les tests fonctionnels appellent l'API
sans session ; le flux d'authentification lui-même est couvert par
`AuthentificationAdminTest`, dont le `@TestProfile` restaure la policy réelle.

### Mails en local (Mailpit)

Les mails (notifications d'échange et envoi des plannings, issue #165)
partent **réellement** en dev et en prod, vers `localhost:1025` par défaut :
`docker compose up mailpit` fournit ce puits SMTP avec une UI sur
<http://localhost:8025>, et un `quarkus:dev` sans configuration
supplémentaire y dépose donc ses mails. Seul le profil de **test** mocke
inconditionnellement ; `MAIL_MOCK=true` restaure le mock partout ailleurs
(les mails partent dans les logs — c'est ce que fait la pile jetable des
tests Playwright). Le profil `--profile app` du compose est déjà branché sur
Mailpit. `MAIL_ADMIN` vide désactive la notification admin ; un animateur
sans adresse e-mail sur sa fiche ne reçoit simplement rien.

## Base de données

Migrations Flyway dans `src/main/resources/db/migration/`, appliquées au
démarrage. **Un changement de schéma = un nouveau fichier versionné** ; ne jamais
éditer une migration déjà appliquée.

## Intégration continue

Tous les jobs tournent sur le runner **self-hosted** (`runs-on: self-hosted`),
minutes GitHub-hébergées comprises dans aucun budget. Un choix de runner
dynamique selon les crédits restants n'est pas praticable : le job qui
choisirait le runner aurait lui-même besoin d'un runner (et l'API de
facturation exige un PAT dédié) — la bascule éventuelle vers `ubuntu-latest`
en début de mois se fait donc à la main, en éditant les `runs-on`.

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
- `.github/workflows/scenario-tests.yml` — les deux tests de scénario grande
  échelle (`PlanningServiceScenarioCompletTest`,
  `PlanningServiceScenarioContinuTest`, ~8 min), qui résolvent des scénarios de
  plusieurs milliers de postes jusqu'à la faisabilité et affirment un score dur
  nul. Trop lents pour tourner sur chaque push, ils se déclenchent **uniquement**
  sur les chemins qui peuvent casser la convergence : les packages `solver/` et
  `domain/`, `solverConfig.xml`, `application.properties`, les scénarios de
  `src/main/resources/scenarios/`, les deux classes de test elles-mêmes et
  `pom.xml` — une montée de version Timefold étant précisément le moment où
  l'on veut savoir si les scénarios connus convergent toujours. Lançable aussi
  à la main (`workflow_dispatch`). Sans ce filet, un réglage du solveur pouvait
  partir en production sans qu'aucun test ne vérifie qu'un scénario connu
  converge encore.
- `.github/workflows/securite.yml` — deux jobs sur chaque push `main`, chaque
  pull request **et chaque lundi matin**. `dependances` est un Trivy en mode
  système de fichiers qui lit `pom.xml` et
  `src/main/webui/package-lock.json` d'une seule passe — pas de clé d'API à
  gérer, contrairement à OWASP dependency-check que la limitation de débit du
  NVD rend pénible en CI. Le job **est** la barrière : `exit-code: 1` le fait
  échouer sur la moindre vulnérabilité corrigeable et son tableau part dans le
  log, sans dépendre d'un onglet qu'il faudrait penser à consulter. Le
  rendez-vous hebdomadaire compte autant que le déclenchement sur PR : une CVE
  publiée demain touche un `pom.xml` qui n'a pas bougé. Trivy ignore les
  vulnérabilités **sans correctif disponible** (`ignore-unfixed`) : elles
  feraient échouer chaque PR sans que personne ne puisse rien y faire.
  Le second job, `code`, est un **Semgrep OSS** sur quatre jeux de règles du
  registre (`p/java`, `p/typescript`, `p/owasp-top-ten`, `p/dockerfile`),
  limité à la sévérité `ERROR` : à l'introduction d'un SAST, tout ouvrir d'un
  coup noie les vrais signaux sous les avertissements de style, et un garde-fou
  qu'on prend l'habitude d'ignorer ne garde plus rien — monter à
  `WARNING` est un cran à passer plus tard, une fois la base propre. Semgrep
  saute de lui-même ce que `.gitignore` couvre. Il tourne dans son **image
  Docker officielle**, le dépôt monté en lecture seule : `actions/setup-python`
  ne trouve aucun binaire pour la distribution du runner (« not found for
  debian 13 ») et un `pip install` dans le Python système se heurterait à
  PEP 668 ; la lecture seule évite qu'un fichier appartenant à root ne fasse
  échouer le `git clean` du job suivant. Sa version est **épinglée** : une
  montée peut ajouter des règles, donc faire rougir une PR qui n'a rien changé ;
  Renovate ne suivant pas une image citée dans un `run:`, c'est une mise à jour
  à faire à la main, en lisant ce que la nouvelle version trouve.

  **CodeQL a été écarté** : il ne sait publier que dans l'onglet **Security**,
  ce qui suppose le *code scanning* — gratuit sur un dépôt public, payant sur
  un dépôt privé, ce qu'est celui-ci. Un job qui ne peut structurellement pas
  passer n'a pas sa place dans une CI ; le jour où le dépôt deviendrait public,
  il redeviendrait une option, en plus de Semgrep et non à sa place (les deux
  ne trouvent pas les mêmes choses). GitGuardian (secrets,
  `.gitguardian.yaml`) et Renovate (versions) restent les deux autres pans du
  dispositif.
- `.github/workflows/docker-ghcr.yml` — publication de l'image sur GHCR, en
  multi-arch (`linux/amd64`, `linux/arm64` via QEMU) pour un déploiement natif
  sur Raspberry Pi. Ne se déclenche **que** sur un push `main` ou un tag de
  release (`v*`) — jamais sur une branche ou une pull request, pour ne pas
  payer le coût (temps + minutes CI) d'un build multi-arch complet à chaque
  push d'une branche de travail. Chaque image publiée est également
  **inventoriée et signée** : voir ci-dessous.

### SBOM et signature de l'image

À chaque commit sur `main` (et à chaque tag `v*`), `docker-ghcr.yml` produit,
après le push de l'image :

1. un **SBOM** CycloneDX de l'image elle-même (Syft, `anchore/sbom-action`) —
   l'inventaire de ce qui est réellement livré (couche JVM, application Quarkus
   et jars embarqués), pas de ce que l'arbre source aurait pu produire. Il est
   aussi déposé en artefact de run (`sbom.cyclonedx.json`) ;
2. une **signature cosign** de l'image et une **attestation CycloneDX** portant
   ce SBOM, toutes deux attachées à l'image dans GHCR ;
3. une **attestation de provenance** et une **attestation de SBOM** côté GitHub
   (`actions/attest-*`) — **uniquement si le dépôt est public**, voir plus bas.

D'où les permissions `id-token: write`, `packages: write` et
`attestations: write` du workflow.

#### cosign en mode keyless

Le certificat de signature est délivré à la volée par Fulcio à partir de
l'identité OIDC du workflow : aucune clé à stocker ni à faire tourner. La
contrepartie est que la signature est enregistrée dans **Rekor, le journal de
transparence public** — le nom du dépôt, le chemin du workflow et l'empreinte
de l'image y deviennent visibles publiquement, alors même que le dépôt est
privé. Rien du contenu de l'image ne fuit, seulement ces identifiants.

Vérification côté consommateur :

```bash
IMAGE=ghcr.io/sylvainmetayer/planning-equipes:main
IDENTITE='^https://github\.com/sylvainmetayer/planning-equipes/\.github/workflows/docker-ghcr\.yml@refs/'

cosign verify \
  --certificate-identity-regexp "$IDENTITE" \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  "$IMAGE"

# Le SBOM lui-même, tel qu'attaché à l'image :
cosign verify-attestation --type cyclonedx \
  --certificate-identity-regexp "$IDENTITE" \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  "$IMAGE" | jq -r .payload | base64 -d | jq .predicate
```

#### Attestations GitHub : en attente d'un dépôt public

Les deux étapes `actions/attest-build-provenance` et `actions/attest-sbom`
restent dans le workflow, gardées par `if: ${{ !github.event.repository.private }}`.
Le magasin d'attestations de GitHub refuse en effet les dépôts **privés
appartenant à un utilisateur** (« Feature not available for user-owned private
repositories »), ce qui est le cas ici. Le garde les réactivera de lui-même le
jour où le dépôt passera public ou rejoindra une organisation — d'où le choix
de les garder actives et gardées plutôt que commentées. Vérification, ce
jour-là :

```bash
gh attestation verify oci://ghcr.io/sylvainmetayer/planning-equipes:main \
  --repo sylvainmetayer/planning-equipes
```

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
