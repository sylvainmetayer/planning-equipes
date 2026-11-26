# Lancer les tests et lire un test structurel qui échoue

Vous saurez quelle famille de tests lancer selon ce que vous modifiez, comment boucler vite sans conteneur, ce que chaque test structurel garantit quand il échoue et comment rejouer les tests de bout en bout en local. Les scénarios de grande taille, le banc, le moteur direct et la mutation du solveur sont dans [les tests du solveur et des scénarios](tests-de-scenarios.md).

## Les familles de tests

| Famille | Outil | Ce qu'elle tient |
| --- | --- | --- |
| Contraintes (`*ConstraintsTest`) | `ConstraintVerifier`, sans Quarkus ni base | une règle isolée, en quelques millisecondes. Toute nouvelle contrainte ajoute au moins un cas pénalisé et un cas valide |
| Unitaires de service | JUnit, services construits à la main | une règle métier appelée directement |
| Intégration (`@QuarkusTest`) | PostgreSQL jetable par dev services | les migrations Flyway s'exécutent comme en production ; transport HTTP, câblage |
| Structurels | JUnit qui relit le code | une règle que rien d'autre ne vérifie, voir [plus bas](#les-tests-structurels) |
| Scénarios | JUnit, fichiers YAML de `src/main/resources/scenarios/` | une résolution de bout en bout, voir [les tests de scénarios](tests-de-scenarios.md) |
| Frontend | Vitest, jsdom | l'orchestration des pages ; job CI dédié, pas branché sur la phase Maven |
| Bout en bout | Playwright | ce qu'un navigateur fait vraiment, voir [plus bas](#les-tests-de-bout-en-bout) |

Un test de service neuf naît unitaire : les services se construisent sans conteneur (`new PlanningService(…)` avec `EmptyReferenceData`, comme dans `PlanningServicePlainTest`, `PlanningHardConstraintsTest` ou le harnais `ScenarioLadder`), et le lecteur de scénarios est pur et statique. Un `@QuarkusTest` reste la bonne réponse quand le test porte sur le transport (une route, un code HTTP, un en-tête), sur une migration ou sur le câblage. Pour une règle métier que l'on peut appeler directement, il ne l'est pas.

Un test qui attend un traitement asynchrone (une résolution qui aboutit, un job qui quitte la file, le solveur qui se libère) passe par Awaitility (`await().atMost(…).until(…)`, dépendance de test gérée par le BOM Quarkus) sur la condition réellement attendue, jamais par `Thread.sleep` : l'attente s'arrête dès que la condition tient, et son échec dit ce qui manquait. Quand seul le temps doit passer (une fenêtre de limitation qui lit l'horloge murale), `await().pollDelay(…)` le dit explicitement.

## Lancer chaque ensemble

Toute commande qui dure une minute ou plus se lance en tâche de fond depuis une session d'agent, pour ne pas bloquer le tour ni boucler sur un `sleep`.

| Commande | Ce qu'elle joue |
| --- | --- |
| `./mvnw test -Punit` | tout ce qui ne demande qu'une JVM, sans Docker ni PostgreSQL |
| `./mvnw test` | la suite complète, hors tests étiquetés `scenario-lent` et `scenario-extreme` |
| `./mvnw verify -DskipITs=false` | la suite et les `*IT`, lancés contre l'application empaquetée (`skipITs` vaut `true` par défaut) |
| `./mvnw test -Pscenario-tests` | ajoute les `scenario-lent` ; détail dans [les tests de scénarios](tests-de-scenarios.md) |
| `./mvnw test -Pscenario-extreme` | seulement les `scenario-extreme`, une classe à la fois |
| `./mvnw -Pmutation test-compile pitest:mutationCoverage` | les tests de mutation sur les contraintes ([tests du solveur](tests-de-scenarios.md#les-tests-de-mutation)) |
| `cd src/main/webui && npm test` | Vitest |
| `cd src/main/webui && npm run e2e` | Playwright, sur une application déjà lancée |

`./mvnw spotless:apply` formate le Java, et `spotless:check`, lié à `validate`, fait refuser un fichier mal formaté par tout `test`, `package` ou `verify`. Côté frontend, `npm run format` applique prettier.

### Sans mise ni Docker

Un conteneur neuf joue la suite entière avec JDK 25 et PostgreSQL pris dans `apt` : le skill `sandbox-tests` (`.claude/skills/sandbox-tests/SKILL.md`) donne la recette. Deux pièges : le préfixe `%test.` sur `-D%test.quarkus.datasource.devservices.enabled=false` est ce qui fait tenir le réglage, car une valeur propre à un profil l'emporte sur une propriété système simple ; et `-DskipITs=false` demande un mot de passe PostgreSQL différent de celui d'exemple (`festival`), que `DefaultSecrets` refuse au démarrage de l'application empaquetée.

## La boucle sans conteneur

`./mvnw test -Punit` joue environ quatorze cents tests en une minute et demie, sans Docker, sans PostgreSQL et sans dev services. C'est la boucle de travail, jamais la preuve : la CI joue la suite entière et c'est elle qu'il faut avoir vue verte avant de pousser. Les classes qui démarrent l'application coûtent l'essentiel du temps de la suite, et le profil existe pour les mettre à part.

Ce que le profil écarte est écrit dans `src/test/container-tests.txt`, un chemin par ligne, que le profil passe à surefire comme `excludesFile`. C'est une exclusion à la sélection et non une étiquette JUnit : un `@Tag` est filtré après la découverte, or charger une classe `@QuarkusTest` construit l'application, dev services compris, avant que le moindre filtre ne soit consulté.

Le fichier ne décide de rien, il rend visible. Une ligne s'ajoute quand un test neuf démarre l'application, elle disparaît quand le test est converti, et `ContainerTestsInventoryTest` refuse tout écart entre le fichier et les annotations en disant quelle ligne écrire. Sa longueur est la dette. En CI, le job `test` joue `-Punit` et deux jobs `test-conteneur` jouent chacun une moitié des classes listées : chaque classe est dans exactement un des trois.

## Les specs du frontend

Une spec de page teste l'orchestration : ce que la page demande à ses services et ce qu'elle fait de la réponse. Elle le fait par des faux services typés. `core/testing/fake.ts` (`fakeOf`, `provideFake`) construit le faux d'une classe de `core/` (les `core/api/*`, les stores) avec les seules méthodes dont la spec a besoin, chacune typée sur la classe. On simule la méthode et non l'URL, et `faux.methode.mock.calls[0]` est le tuple de ses paramètres : une méthode renommée ou un argument changé ne compile plus, là où un `as unknown as [string, Item]` restait vert. Un membre protégé du composant se lit `page['membre']`, vérifié par le compilateur. Le rendu Material n'est testé que là où il est le sujet (un libellé accessible, une erreur NG01352).

`npm run unknown-casts-check` refuse toute spec qui porte un cast qui fait taire le compilateur : `as unknown as`, `as any` ou `as never`, cherchés dans tout le texte de la spec, commentaires compris. Aucune règle eslint ne les tient dans une spec, où `no-explicit-any` est coupé. Le compte est à zéro, sans baseline.

## Les tests structurels

Un test structurel ne joue aucun scénario : il relit le code (sources, annotations, SQL, JSON, fichiers d'inventaire) et échoue sur une règle qu'aucun autre contrôle ne tient. Chacun porte une liste d'exceptions justifiées une par une, et un contrôle que le scan trouve bien quelque chose, sans quoi il passerait au vert le jour où son expression rationnelle cesserait de reconnaître le code. Quand l'un échoue, son message dit le plus souvent la ligne à écrire ; la décision d'exception revient au relecteur.

| Ce qui est tenu | Tests |
| --- | --- |
| Le prédicat `edition_id` sur toute requête métier, y compris à travers variables et constantes | `IsolationEditionStructurelleTest` ([cloisonnement](cloisonnement-par-edition.md)) |
| Aucun nom d'animateur ne sort par MCP ; chaque outil nomme son édition, déclare ses quatre indices, traduit les refus métier, dit ce qui se passe pendant un calcul | `McpConfidentialiteStructurelleTest`, `McpEditionStructurelleTest`, `McpAnnotationsStructurelleTest`, `McpRefusMetierStructurelleTest`, `McpWarnsWhileSolvingStructuralTest` ([outils MCP](mcp-outils.md)) |
| Les trois écritures du nom d'une contrainte : `actif(stream, "X")`, `.asConstraint("X")` et le catalogue | `ConstraintToggleStructurelleTest` et `ConstraintCatalogTest` |
| Les deux moteurs scorent les mêmes règles | `HandSearchCoverageStructuralTest`, `HandSearchDifferentialTest` ([tests du solveur](tests-de-scenarios.md)) |
| Un référentiel figé par la phase de l'édition refuse toute écriture ; une écriture qu'un calcul annulerait est refusée | `GelReferentielStructuralTest`, `RefusedWhileSolvingStructuralTest` |
| Seule l'édition active publie et envoie des mails | `ActiveEditionEmissionStructuralTest` |
| Toute route d'écriture et tout outil d'écriture sont au catalogue des actions journalisées | `JournalCoverageStructurelleTest` |
| Chaque table de référentiel porte `modifie_le` et sa précondition d'écriture | `WriteStampStructurelleTest` |
| Une migration appliquée ne change pas ; une nouvelle a sa ligne d'empreinte | `FlywayMigrationsFrozenTest` (`migrations-empreintes.txt`) |
| Les clés JSON exposées ne bougent pas en silence | `JsonContractTest` (`json-contract.txt`) |
| Les couches (un outil MCP appelle un service, jamais une ressource) | `LayeringStructuralTest` |
| Le dépôt n'importe `ai.timefold.solver.core.impl` qu'aux endroits recensés | `TimefoldInternalApiStructuralTest` |
| Toute lecture des plages d'un stand passe par `StandService.resolve` (les plages d'une consigne y sont lues au-dessus des siennes) | `ConsigneCoucheStructurelleTest` |
| Le responsable de stand ne voit que des projections, jamais un type de domaine | `ResponsableProjectionStructurelleTest` |
| Les routes ouvertes à l'organisateur et au responsable, une à une | `InstanceRoutesStructuralTest`, `RoutesOuvertesStructuralTest` |
| La liste des tests qui démarrent l'application | `ContainerTestsInventoryTest` |
| Les diagrammes, les pages de la documentation | `DiagramsStructuralTest`, `DocumentationStructuralTest` |
| La politique de langue du code | `LanguagePolicyStructuralTest` |

### Les routes ouvertes, une à une

`RoutesOuvertesStructuralTest` complète `InstanceRoutesStructuralTest`, qui ne classe que des ressources entières : les méthodes ouvertes à un organisateur (`routes-organisateur.txt`) ou à un responsable de stand (`routes-responsable.txt`, sous `src/test/resources/`) sont des inventaires versionnés comme `json-contract.txt`. Une route non classée échoue en imprimant la ligne à ajouter ; la décision (ouverte, ou `@InstanceOnly`) est celle du relecteur.

### La politique de langue

`LanguagePolicyStructuralTest` applique le glossaire de langue du code : verbe anglais, nom commun anglais, ordre des mots anglais, vocabulaire métier en français. Une convention que personne ne vérifie ne tient pas : une seule branche suffit pour y ajouter vingt blocs de javadoc en français.

Il ne regarde ni les composants de record ni les accesseurs (ce sont des clés JSON), ni les méthodes de test (la règle est de renommer dès qu'on touche un test), ni les méthodes qui portent le nom d'une contrainte (clé primaire de `constraint_toggle`), ni les outils MCP, dont le nom est celui que choisit un assistant francophone. Sur un nom légitime qu'il refuse, renommez-le ou ajoutez-le à `EXCEPTIONS_ASSUMEES` avec sa raison ; un autre contrôle vérifie que chaque exception correspond encore à du code réel. Côté frontend, `npm run language-policy-modifies` applique la même règle aux fichiers modifiés par une pull request.

## Les tests de bout en bout

Les specs Playwright (`src/main/webui/e2e/`) coûtent plusieurs minutes là où la suite unitaire répond en secondes, et ce coût est assumé : c'est la seule couche qui voit ce qu'un navigateur fait vraiment. Elles tournent à chaque poussée et sur chaque pull request, sauf les specs marquées `@lourd` (voir plus bas).

Elles réamorcent la base par `/api/database/import` : ne les pointez jamais ailleurs que sur une pile jetable. Ces routes de dump SQL ne répondent que si `planning.outillage-test.dump-sql` vaut `true`, ce qui est le cas sous `quarkus:dev`, dans les tests et sur la pile de `e2e-suite.yml` (`OUTILLAGE_TEST_DUMP_SQL`). Un jar lancé à la main pour l'E2E doit poser cette variable.

```bash
docker compose up -d postgres mailpit
./mvnw quarkus:dev   # mail sur :1025, mot de passe « admin »
cd src/main/webui && npm run e2e         # + --headed, --ui, --project=mobile, un chemin de spec
```

Réglages par variable d'environnement, tous facultatifs : `E2E_BASE_URL` (`http://localhost:8080`), `E2E_ADMIN_PASSWORD`, `E2E_MAILPIT_URL` (`http://localhost:8025`), `E2E_CHROMIUM`, `E2E_VIDEO`.

Le PostgreSQL local est en version 19 (`postgres:19` dans `docker-compose.yml`). Les dev services de `quarkus:dev` et des tests ont leur propre base, jetable.

### Chaque spec repart d'un état de référence

Avant la première spec, la suite photographie la base par `/api/database/export`, un script qui commence par un `DELETE FROM` de chaque table et constitue donc une restauration autonome. Chaque spec y revient dans son `beforeAll` : elles ne se contaminent pas, et la suite se relance sans recréer la base. La référence est mise en cache dans `node_modules/.cache/` et rejouée au démarrage, pour environ 40 ms par spec. La première capture exige une base vierge de données de test, sinon elle figerait l'état d'une exécution précédente, et la suite le refuse en le disant. Si le schéma a changé, supprimez le cache et recréez le conteneur PostgreSQL.

### Les amorces sont datées depuis l'horloge réelle

La pile e2e laisse « le passé est figé » ([ADR 0044](repo:docs/decisions/0044-le-passe-est-fige.md)) allumé, comme la production, et l'application empaquetée ne fige aucune date : un créneau derrière l'horloge serait repris du plan enregistré par chaque résolution et refuserait tout geste. Une spec écrit donc ses dates comme si l'événement tombait à l'été 2026 et les passe par `shiftDate()` (`e2e/support.ts`), qui les avance du plus petit nombre de semaines entières plaçant le lundi 6 juillet 2026 au moins huit semaines après aujourd'hui. Les jours de semaine sont gardés. La date de naissance d'un mineur passe par la même fonction, pour que son âge au créneau reste celui que l'assertion suppose. Une date écrite en dur dans une amorce est une spec qui cessera de tester quoi que ce soit le jour où l'horloge la dépassera.

### Les specs lourdes

Une spec `@lourd` ne tourne pas à chaque poussée. La semaine canicule sur `festival-hivernal` (`canicule-festival-hivernal.spec.ts`) importe la fixture réelle, la résout et la publie trois fois avec un PDF par personne : elle pèse plus à elle seule que toutes les autres specs ensemble. Le job `e2e` de `tests.yml` la laisse de côté (`--grep-invert @lourd`) et `e2e-lourd.yml` la joue (`--grep @lourd`) sur la même pile, `e2e-suite.yml` :

- sur une pull request, seulement sous un label : `e2e-lourd` (à poser sur une PR qui touche le solveur, les consignes, la publication ou l'horloge ; le poser sur une PR ouverte lance le run), ou `timefold`, `quarkus`, `playwright`, que Renovate pose sur les montées qui changent ce que ces specs traversent ;
- sur `main`, à chaque fusion qui touche le solveur, les consignes, la publication, le `pom.xml` ou ses propres fichiers (sa liste `paths`, à tenir à jour comme celle des scénarios) ;
- chaque nuit, pour que ce qu'une PR non étiquetée a cassé se voie au plus tard le lendemain.

Marquer une spec `@lourd` dit qu'elle résout une fixture réelle ou qu'il lui faut la pile de `e2e-lourd.yml` ; ce n'est pas l'endroit où ranger un test lent. `npm run e2e -- --grep @lourd` la joue seule en local.

`passe-fige.spec.ts` est lourde pour une autre raison : elle rejoue `FrozenPastAcceptanceTest` depuis l'écran et exige une pile qui accepte l'horloge simulée. `e2e-lourd.yml` passe `horloge-simulee: true` à `e2e-suite.yml`, qui monte l'application avec `HORLOGE_SIMULEE_AUTORISEE=true` ; le job `e2e` ordinaire non, et `jour-j.spec.ts` y vérifie que figer la date est refusé. En local, jouez donc `passe-fige.spec.ts` seule sur une application lancée avec cette variable : sur cette pile, le refus que vérifie `jour-j.spec.ts` n'a plus lieu et la suite entière échouerait. La spec rend l'horloge réelle en partant, car la table qui la porte survit à la restauration de la référence.

### Le compte local et l'essai OIDC

`organisateur-compte-local.spec.ts` (suite ordinaire) fait démarrer l'organisateur sur un compte local : création par l'API d'administration, lien d'invitation lu dans Mailpit, mot de passe généré (jamais écrit dans la spec), code reçu par mail. Il doit atteindre l'application sans boucle vers `/login`, ne voir ni l'onglet Comptes ni l'onglet Instance et recevoir `403` sur une route d'instance. La pile monte l'application avec `PUBLIC_URL=http://e2e.example.org:8080` et non `localhost`, car un lien d'invitation vers la machine elle-même est refusé hors dev et test.

L'essai OIDC (`oidc-keycloak.yml`, `npm run e2e-oidc`) joue le code d'autorisation avec PKCE contre un vrai Keycloak : connexion, `/api/auth/me`, refus d'un compte inconnu (`403`), déconnexion qui clôt aussi la session du fournisseur. `OidcSignInTest` reste la preuve des règles ; l'essai prouve qu'elles tiennent face à un fournisseur qui redirige et signe vraiment. Il se déclenche comme `e2e-lourd.yml` et jamais sur chaque PR. Keycloak et PostgreSQL sont lancés par `docker run`. Le realm est `src/main/webui/e2e/keycloak/realm-planning.json`, dont le secret du client, le mot de passe des utilisateurs et l'adresse de l'application sont des `${…}` remplis par l'environnement : aucun secret dans le dépôt. Le compte de secours reste ouvert (`ADMIN_LOCAL_SECOURS=true`) pour créer le premier compte, une connexion OIDC n'en créant jamais. La spec porte le suffixe `.oidc.ts` et sa propre config (`playwright.oidc.config.ts`), que la suite ordinaire ne ramasse pas. En local, pointez `E2E_BASE_URL`, `E2E_KEYCLOAK_URL` et `E2E_OIDC_USER_PASSWORD` vers une pile jetable.

### Diagnostiquer un échec

`e2e/icones.spec.ts` vérifie que la police des icônes se dessine : sans elle, un `<mat-icon>delete</mat-icon>` n'échoue nulle part, le navigateur écrit le mot et tous les écrans affichent « ... ». jsdom n'a pas de polices, donc aucun test unitaire ne le voit.

La trace d'abord : `trace: 'retain-on-failure'` laisse après un échec une trace navigable (pellicule, DOM, réseau, console).

```bash
npm run e2e -- --trace on
npx playwright show-trace test-results/<dossier-du-test>/trace.zip
```

La vidéo ensuite, par `E2E_VIDEO` (absente, rien n'est enregistré). Elle sert à montrer un parcours plus qu'à diagnostiquer.

```bash
E2E_VIDEO=retain-on-failure npm run e2e   # ou 'on' ; .webm dans test-results/<test>/
```
