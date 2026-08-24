# Développement

## Démarrer

`mise install` (Java 25, Maven 3.9.9, Node 24) et un runtime de conteneurs pour
PostgreSQL et les dev services. Node n'est requis que pour travailler
directement sur le frontend : Quinoa télécharge le sien au build.

```bash
./mvnw quarkus:dev                     # backend + frontend, hot reload, :8080
./mvnw test                            # unitaires
./mvnw verify -DskipITs=false          # + tests *IT (skipITs=true par défaut)
cd src/main/webui && npm test          # Vitest
```

`quarkus:dev` suffit : Quinoa démarre `ng serve` et le proxifie. Le frontend est
désactivé sur le profil `%test`.

**Deux pièges de l'environnement local :**

- **F5 ou lien profond sur `:8080` renvoie 404 en mode dev.** Bug amont de
  Quinoa ([#666](https://github.com/quarkiverse/quarkus-quinoa/issues/666)) : il
  retire l'en-tête `Accept` de ses requêtes vers `ng serve`, ce qui désactive le
  fallback SPA d'Angular CLI. **N'affecte pas la production.** Contournement :
  ouvrir `:4200` directement, `proxy.conf.json` y redirige `/api/*`.
- **Les mails partent réellement en dev**, vers `localhost:1025` :
  `docker compose up mailpit` fournit le puits et son UI sur `:8025`. Seul le
  profil de test mocke inconditionnellement ; `MAIL_MOCK=true` restaure le mock
  ailleurs.

Compte local : `admin` / `admin`. Le profil `%test` désactive la policy pour que
les tests appellent l'API sans session — `AuthentificationAdminTest` la restaure
via `@TestProfile` pour couvrir le flux réel.

Les variables d'environnement sont documentées dans le
[`README.md`](../README.md#configuration) et
[`exploitation.md`](exploitation.md).

### Avertissements JDK 25

Le JDK 25 restreint l'accès natif (JEP 472) et signale `sun.misc.Unsafe`
(JEP 498). Deux sources, déjà traitées : le Maven wrapper embarque ses propres
jansi/guava, silencés par `.mvn/jvm.config` ; la console interactive de
`quarkus:dev` charge JNA, d'où `--enable-native-access=ALL-UNNAMED` dans
`jvm.args`. Netty ne les déclenche plus depuis Quarkus 3.28, donc l'image de
production ne porte aucun flag JVM.

## i18n : traduction à l'exécution

Un seul build, pas de bundle par langue. Le français est écrit dans les
templates, `loadTranslations()` charge `messages.en.json` avant
`bootstrapApplication()` si l'anglais est choisi. Changer de langue recharge la
page : les messages ne sont résolus qu'une fois, au démarrage.

Pour ajouter une chaîne : `i18n="@@page.section.role"` dans un template ou
`` $localize`:@@id:…` `` en TypeScript, puis la traduction dans
`public/i18n/messages.en.json` avec **les mêmes placeholders, dans le même
ordre**, puis `npm run i18n-check`.

> **Jamais `$localize` au niveau module.** Le fichier serait évalué avant
> `loadTranslations()` et resterait figé en français. Utiliser un `computed()`,
> une méthode, ou une fonction appelée depuis le constructeur.

`npm run i18n-check` échoue sur trois écarts, chacun invisible autrement : un id
sans traduction (l'écran s'affiche **en français** pour un anglophone,
`$localize` retombe sur la source sans rien dire), une clé orpheline
(généralement la moitié oubliée d'un renommage), et des placeholders divergents
(casse **à l'affichage**, en anglais seulement — ni le build ni les tests ne la
voient).

Le troisième écart est le plus sournois : un placeholder renommé côté anglais
fait voir le jeton littéral au lieu de la valeur, sur ce seul écran, dans cette
seule langue.

## Accessibilité

Ces conventions valent sur les 26 écrans : les tenir coûte peu à l'écriture,
les rattraper coûte cher.

- **Un `<h1>` par écran, et un seul.** `mat-card-title` rend une `<div>` :
  écrire `<h1 mat-card-title>` sur le titre principal, `<h2>` sur les cartes
  suivantes. Naviguer par titres est le premier réflexe au lecteur d'écran.
- **Le résultat d'une action s'annonce** par `app-status-message`, jamais par un
  `<p>` inerte : `tone="error"` produit un `role="alert"`, les autres un
  `role="status"`.
- **Une erreur de formulaire est reliée à son champ** — `mat-error` dans le
  `mat-form-field` ; `role="alert"` quand elle porte sur plusieurs champs.
- **Le focus survit à la suppression d'une ligne** :
  `focusApresSuppression(...)` le rend au bouton « Ajouter ».
- **Un tableau porte une `<caption>` masquée** disant ce qu'il compte et sur
  quel périmètre, et ses en-têtes leur `scope`.
- **Une longue liste se filtre, elle ne se déroule pas** :
  `app-selection-recherche` au-delà de quelques dizaines d'entrées. 153
  animateurs au clavier, c'est 153 flèches.
- **Une grille se parcourt aux flèches** (roving tabindex). N'écrire
  `role="grid"` que si la structure lignes/cellules existe réellement.
- **La couleur n'est jamais seule** : doubler d'un texte, d'une icône ou d'une
  initiale. Et une infobulle n'existe pas au tactile.

## Tests

Cinq familles :

- **contraintes** (`*ConstraintsTest`) — `ConstraintVerifier`, sans Quarkus ni
  base, quelques millisecondes. **Toute nouvelle contrainte ajoute au moins un
  cas pénalisé et un cas valide** ;
- **intégration** (`@QuarkusTest`) — PostgreSQL jetable par dev services, donc
  les migrations Flyway s'exécutent comme en production ;
- **frontend** (Vitest, jsdom) — pas branchés sur la phase Maven, job CI dédié ;
- **structurels** — ils ne jouent aucun scénario, ils **relisent le code** et
  échouent sur une règle que rien d'autre ne vérifie ;
- **bout en bout** (Playwright) — jamais sur une PR, rejoués **chaque nuit**.

### Les tests structurels

Cinq : le prédicat `edition_id` sur toute requête métier, l'alignement des trois
écritures du nom d'une contrainte, l'absence de fuite de nom d'animateur par
MCP, l'obligation d'y nommer son édition, et la politique de langue.

Chacun porte une liste d'exceptions justifiées **une par une**, et un test qui
vérifie que le scan trouve bien quelque chose — sans quoi il passerait au vert
le jour où son expression rationnelle cesserait de reconnaître le code.

`LanguagePolicyStructuralTest` applique le glossaire d'`AGENTS.md` : verbe
anglais, nom commun anglais, ordre des mots anglais, vocabulaire métier en
français. Une règle écrite dans `AGENTS.md` mais non vérifiée ne tient pas : une
seule branche suffit à y ajouter vingt blocs de javadoc française.

Il ne regarde pas les composants de record ni les accesseurs (ce sont des clés
JSON), les méthodes de test (la règle est « on renomme dès qu'on touche un
test »), les méthodes portant le nom d'une contrainte (clé primaire de
`constraint_toggle`) ni les outils MCP (leur nom est celui que choisit un
assistant francophone).

Quand il échoue sur un nom légitime : le renommer, ou l'ajouter à
`EXCEPTIONS_ASSUMEES` **avec sa raison**. Un troisième test vérifie que chaque
exception correspond encore à du code réel.

## Réglage du solveur

| Propriété | Défaut | Rôle |
| --- | --- | --- |
| `planning.solver.seconds-limit` | `180` (`3` en `%test`) | Budget de résolution |
| `planning.solver.unimproved-seconds-limit` | `300` (`2` en `%test`), `0` = désactivé | Arrêt sur plateau, **conditionné à la faisabilité** |
| `planning.constraint-weights.<contrainte>` | `1` | Voir [`contraintes.md`](contraintes.md#pondérer-une-contrainte) |
| `planning.jobs.reprise-au-demarrage` | `true` (`false` en `%test`) | Rejoue la file persistée. En test, une tâche laissée en file déclencherait un vrai solve au démarrage suivant |

**L'arrêt anticipé est un `AND`** entre `bestScoreFeasible` et la limite de
plateau : une résolution s'arrête quand le budget est épuisé, **ou** quand le
planning est déjà faisable et n'a plus progressé. Sans la condition de
faisabilité, le solveur abandonnait sur un plateau de score **dur** — exactement
le cas où il a besoin du reste de son budget.

### `acceptedCountLimit` : mesuré, pas hérité

Le nombre de mouvements candidats échantillonnés par pas de recherche locale
(`solverConfig.xml`, `<forager>`) est le réglage le plus sensible du fichier, et
il dépend de la **taille du problème**. Mesures sur le scénario réel 2026
(3 502 postes, 153 animateurs), même graine, budget de 180 s, runs séquentiels :

| `acceptedCountLimit` | 1 | 2 | 3 | 4 | 10 | 20 | 40 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| score dur à 180 s | 0 | 0 | -1 | -3 | -6 | -14 | -27 |
| 0 hard atteint à | 95 s | 137 s | jamais dans le budget | | | | |

**Le compromis s'inverse avec la taille du problème.** À 2 088 postes, 20
donnait la meilleure qualité par pas ; à 3 502, un pas coûte assez cher pour
qu'échantillonner 20 candidats affame la recherche — ce réglage « prudent »
n'atteint jamais la faisabilité, là où 2 y arrive en moins de trois minutes.

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
faisabilité **et** la qualité de l'échantillonnage large. Timefold conserve toujours la
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
| `reposQuotidienMinimal` | ~13 500 (toutes les paires de postes d'un animateur) | les seuls créneaux de nuit tenus par un mineur |
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

Mesures faites pendant l'implémentation des correctifs de conformité RH, sur
la même machine et le même `randomSeed=0`, avec `solveUntilFeasible` :

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
contraintes.

### Geler un bloc acquis

`PosteAffectation.verrouille` porte `@PlanningPin`, et un verrou de type `STAND`
couvre toutes les places d'un stand. Poser un tel verrou sur un bloc volumineux
mais facile — le montage et le démontage pèsent 20 % des postes, pour deux
stands mono-typologie sans contrainte de compétence — retire ces entités de la
génération de mouvements **sans les retirer du planning** : elles restent
affectées, nominatives, et leurs heures continuent d'être évaluées.

Le gain porte donc sur l'espace de recherche, pas sur le coût d'un calcul de
score. Contrepartie : le verrou fige ces personnes-là et consomme leurs heures,
il n'est sain que si l'affectation gelée est déjà bonne — d'où l'ordre
« résoudre, vérifier, puis verrouiller ». Aucun code : deux appels d'API.

## Intégration continue

Quatre workflows sous `.github/workflows/`, lisibles tels quels. Deux points qui
ne s'y voient pas :

- **les tests de bout en bout ne tournent pas sur une PR**, seulement chaque
  nuit : la pile complète coûte trop pour une boucle de relecture ;
- **l'image publiée porte un SBOM et une signature cosign en mode keyless**. Les
  attestations GitHub natives attendent l'ouverture du dépôt.

## Renovate

Couvre Maven, Docker, les actions GitHub, npm et `mise.toml`. Trois règles
valent d'être connues :

- les mises à jour **mineures et correctives des dépendances de test** sont
  fusionnées automatiquement ;
- **Quarkus et Timefold sont groupés par écosystème**, et les paquets
  `@angular/*` dans une seule PR : une montée partielle casse le build ;
- les **majeures** de Java, PostgreSQL et victools passent par le tableau de
  bord (`dependencyDashboardApproval`) — ce qui suppose que l'issue de tableau
  de bord existe.

## Conventions

Elles vivent dans [`AGENTS.md`](../AGENTS.md), section *Working conventions* :
langue du code, `ApplicationLinks` pour tout lien public, `BusinessError` plutôt
qu'un `try/catch` dans une ressource, `ObjectMapper` injecté, messages de commit
courts, et les règles frontend. Le découpage des modules est dans
[`architecture.md`](architecture.md).

Les mémoires d'agents (`.claude/agents/`, `.github/chatmodes/`) sont dérivées
d'`AGENTS.md` : on modifie la source, pas les copies.
