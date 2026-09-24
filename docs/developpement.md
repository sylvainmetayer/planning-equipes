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

**Variables d'environnement locales.** `mise.toml` porte celles que tout le
monde partage (`MAIL_ADMIN`). Celles qui sont propres à une machine vont dans
`mise.local.toml`, à la racine, que mise charge par-dessus et que `.gitignore`
exclut — c'est le seul endroit où écrire une valeur qu'on ne veut pas voir dans
un dépôt public. Exemple, pour voir la page `/mentions-legales` remplie plutôt
que vide (les sept `LEGAL_*` sont décrites dans le
[README](../README.md#variables-denvironnement) et dans
[`exploitation.md`](exploitation.md#3-mentions-légales--à-renseigner-pas-à-laisser-vides)) :

```toml
[env]
LEGAL_EDITEUR = "Association Les Bénévoles du Jeu, 12 rue des Pions, 79000 Niort — RNA W791234567"
LEGAL_DIRECTEUR_PUBLICATION = "Camille Exemple, présidente"
LEGAL_HEBERGEUR = "Hébergeur Fictif SAS, 1 avenue des Serveurs, 75000 Paris"
LEGAL_CONTACT = "planning@exemple.invalid"
LEGAL_RESPONSABLE_TRAITEMENT = "Association Les Bénévoles du Jeu, représentée par sa présidente"
LEGAL_BASE_LEGALE = "Exécution du contrat de bénévolat et intérêt légitime de l'organisateur (art. 6.1.b et 6.1.f RGPD)"
LEGAL_CONSERVATION = "Jusqu'au 31 décembre de l'année suivant l'édition, puis suppression"
```

Un `quarkus:dev` déjà lancé ne relit pas l'environnement : le relancer.

**Deux pièges de l'environnement local :**

- **F5 ou lien profond sur `:8080` renvoie 404 en mode dev.** Bug amont de
  Quinoa ([#666](https://github.com/quarkiverse/quarkus-quinoa/issues/666)) : il
  retire l'en-tête `Accept` de ses requêtes vers `ng serve`, ce qui désactive le
  fallback SPA d'Angular CLI. **N'affecte pas la production.** Contournement :
  ouvrir `:4200` directement, `proxy.conf.json` y redirige `/api/*`.
- **Les mails partent réellement en dev**, vers `localhost:1025` :
  `docker compose up mailpit` fournit le puits et son UI sur `:8025`. Seul le
  profil de test mocke inconditionnellement ; `MAIL_MOCK=true` restaure le mock
  ailleurs. Mailpit rend la partie HTML : c'est là qu'on relit un gabarit de
  `src/main/resources/templates/mail/` après l'avoir modifié — le bouton
  « mail de test » de la page Débogage suffit pour la mise en page commune,
  et les tests unitaires (`MailServiceTest`, `NotificationWriterTest`,
  `MailTemplatesTest`) verrouillent le texte.

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

### Fichiers générés avant chaque build

Deux fichiers du frontend ne sont pas dans le dépôt : ils sont écrits par
`npm run generate`, que `prestart`, `prebuild`, `prewatch`, `pretest` et
`i18n-check` déclenchent déjà — il n'y a donc rien à lancer à la main.

| Fichier | Script | Contenu |
| --- | --- | --- |
| `src/app/version.ts` | `scripts/generate-version.js` | `APP_VERSION` : le tag exact s'il y en a un, sinon le SHA court ([`versioning.md`](versioning.md)) |
| `src/app/pages/nouveautes/news-data.ts` | `scripts/generate-news.js` | L'historique git — un sujet de commit, sa date, son tag de version — que l'écran **Nouveautés** affiche |

Les deux sont dans `.gitignore`, et `news-data.ts` dans `.prettierignore` : il
est écrit par `JSON.stringify`, le reformater ne survivrait pas à la
régénération suivante. Ce que chaque commit *veut dire* — sous quel intertitre
il tombe, comment sa phrase se lit sans son préfixe — n'est pas dans le script
mais dans `pages/nouveautes/news.ts`, testé par `news.spec.ts` : ces règles
recopient celles de `cliff.toml`, et une règle que personne ne peut tester
dérive de son modèle.

Conséquence à connaître : **un clone superficiel raccourcit l'écran
Nouveautés**, et une arborescence sans `.git` le laisse vide en le disant. Le
`Dockerfile` copie `.git` pour cette raison et pour `git describe` — et c'est
aussi pourquoi `docker-ghcr.yml`, qui construit l'image publiée, se place en
`fetch-depth: 0` : au défaut de 1, l'écran de chaque image publiée ne
listerait qu'un commit.

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

`npm run i18n-check` échoue sur quatre écarts, chacun invisible autrement : un
id sans traduction (l'écran s'affiche **en français** pour un anglophone,
`$localize` retombe sur la source sans rien dire), une clé orpheline
(généralement la moitié oubliée d'un renommage), des placeholders divergents
(casse **à l'affichage**, en anglais seulement — ni le build ni les tests ne la
voient), et un libellé cité qui ne correspond à rien à l'écran.

Le troisième écart est le plus sournois : un placeholder renommé côté anglais
fait voir le jeton littéral au lieu de la valeur, sur ce seul écran, dans cette
seule langue.

Le quatrième garde l'aide en application honnête. Quand un message cite un
autre message mot pour mot — « Ouvrir la collecte », « Arrêter le solveur » —
sa traduction doit citer le libellé anglais de ce même id, pas une paraphrase :
nommer un bouton n'a d'intérêt que si le lecteur retrouve ces mots-là à
l'écran. La comparaison ne porte que sur les citations d'au moins deux mots,
pour ne pas confondre une tournure ordinaire avec un libellé.

Aucun de ces quatre écarts ne regarde le **texte** : réécrire une source
française sous le même id laisse l'anglais dire l'ancienne version, et le
contrôle reste vert. `npm run i18n-check-modifies -- origin/main` ferme ce
trou en cliquet : les sources de la base sont extraites dans un worktree
jetable (une quarantaine de secondes), et tout id dont le français a changé
— hors espaces, apostrophes, casse et ponctuation finale — sans que sa
traduction bouge est signalé. C'est le contrôle qui permet une passe de
simplification des textes sans livrer une IHM anglaise périmée. Il tourne en
CI sur chaque pull request, contre sa branche de base. Si la traduction était
déjà juste, la relire et la retoucher est précisément ce qu'il demande.

## Textes des écrans : la règle

Un écran répond à une question ; il ne l'explique pas. La passe de
simplification a retiré près de la moitié de la prose des écrans, et ces
règles sont ce qui l'empêche de revenir hint par hint :

- **Sous-titre d'écran** : une phrase, qui dit ce que l'écran répond — pas
  comment il le calcule, ni ses réserves.
- **`mat-hint`** : douze mots au plus. Au-delà, c'est une infobulle ou l'Aide.
- **Ne jamais répéter en texte** ce que le tableau, le formulaire ou les
  boutons montrent déjà.
- **Le « pourquoi » vit dans l'Aide** (`pages/aide/content/*`, et l'aide de
  l'espace animateur), ancrée depuis l'écran ; l'écran garde le « quoi ».
- **Plafond : aucun message de plus de 200 caractères** hors Aide, aide de
  l'espace, pages légales et avertissement de responsabilité du dialogue de
  désactivation d'une règle légale. `npm run i18n-check` le refuse. Une seule
  exception, reconnue au texte : un message qui nomme une règle avec son
  article (« art. L3132-1 », « L4153-3 ») est gardé entier — c'est lui qui
  rend l'outil défendable, et le raccourcir serait une régression.
- **Une confirmation** dit ce qui se passe et ce qui est perdu, en une ou
  deux phrases. Un message court qui se lit bien n'est pas réécrit.

Réécrire une source française sous son id impose de réécrire l'anglais du
même id : c'est le cliquet `i18n-check-modifies` décrit plus haut.

## Accessibilité

Ces conventions valent sur tous les écrans — ceux de l'administration **et**
les quatre de l'espace animateur, le seul périmètre lu hors de l'organisation,
sur un téléphone, par des personnes dont une partie est mineure. Les tenir
coûte peu à l'écriture, les rattraper coûte cher.

- **Chaque coque porte un lien d'évitement et rend le focus à la page.** La
  coque d'administration et celle de l'espace animateur commencent par
  `<a class="skip-link" href="#contenu">` (style partagé dans `pages.css`) et
  enveloppent l'écran dans `<main id="contenu" tabindex="-1">`, refocalisé à
  chaque changement de page — dans l'espace, à chaque changement d'onglet de la
  barre, pas à chaque jour choisi dans la bande, qui n'est qu'une vue de la même
  page.

- **Un `<h1>` par écran, et un seul.** `mat-card-title` rend une `<div>` :
  écrire `<h1 mat-card-title>` sur le titre principal, `<h2>` sur les cartes
  suivantes. Naviguer par titres est le premier réflexe au lecteur d'écran.
  `npm run headings-check` (joué en CI) refuse un écran routé qui n'a pas
  exactement un `<h1>`, et tout `<mat-card-title>` écrit comme élément. Il ne
  regarde que les composants que `app.routes.ts` charge : les quatre onglets de
  `/diagnostic` et les cinq rendus de `/journee` sont les composants d'une page
  qui porte déjà le sien.
- **Une `<mat-icon>` porteuse d'information écrit `aria-hidden="false"` en
  dur.** Sans lui, `MatIcon` la masque et son `aria-label` n'est jamais lu :
  l'attribut est relu par `inject(new HostAttributeToken('aria-hidden'))`, qui
  ne voit pas une liaison Angular, et un élément `aria-hidden="true"` quitte
  l'arbre d'accessibilité avec tout ce qu'il porte — son `aria-label`, et
  l'`aria-describedby` que `MatTooltip` lui ajoute. Une icône qui ne fait que
  répéter le texte voisin reste décorative : elle perd alors son `aria-label`
  *et* son `matTooltip`, faute de quoi elle redevient une information que
  personne n'entend.
  **Et une infobulle n'est pas un nom** : `MatIcon` impose `role="img"`, un
  rôle qui ne prend pas son nom de son contenu, donc une icône à seule
  infobulle est annoncée comme un graphique anonyme — le `matTooltip` en est
  la description, l'`aria-label` le nom, et les deux se posent ensemble.
  `npm run icon-labels-check` (joué en CI) refuse une icône portant
  `aria-label`, `aria-labelledby` ou `matTooltip` sans `aria-hidden="false"`
  statique, et une icône ainsi exposée sans `aria-label` ni
  `aria-labelledby`.
- **Le résultat d'une action s'annonce** par `app-status-message`, jamais par un
  `<p>` inerte : `tone="error"` produit un `role="alert"`, les autres un
  `role="status"`. Le composant ne rend rien sur un texte vide, donc pas de
  `@if` autour : c'est sa création avec le texte qui fait annoncer la région.
  Un verdict dont la mise en page tient à sa classe (`marge-message`,
  `pauses-message`…) garde son élément et porte `role="status"`.
  `npm run status-messages-check` (joué en CI) refuse un élément dont tout le
  contenu est l'interpolation d'une erreur, d'un message ou d'un verdict
  (`error()`, `erreur()`, `rapport()!.message`…) sans `role` ni `aria-live` ;
  ses exceptions — contenu d'un dialogue, lu à l'ouverture, ligne d'une liste,
  bandeau présent dès le rendu — sont écrites une par une avec leur raison.
- **Une erreur de formulaire est reliée à son champ** — `mat-error` dans le
  `mat-form-field` ; `role="alert"` quand elle porte sur plusieurs champs.
- **Le focus survit à la suppression d'une ligne** :
  `focusApresSuppression(...)` le rend au bouton « Ajouter ».
- **Un tableau porte une `<caption>` masquée** disant ce qu'il compte et sur
  quel périmètre, et ses en-têtes leur `scope` — y compris un
  `<th mat-header-cell>`, dont Material n'écrit jamais le `scope`.
  `npm run table-headers-check` (joué en CI) refuse un `<th>` qui n'en porte
  pas.
- **Un lien qui ouvre une nouvelle fenêtre le dit.** La directive
  `NewWindowLink` (`shared/new-window-link.ts`, sélecteur `a[target="_blank"]`)
  ajoute « (nouvelle fenêtre) » en texte masqué à la fin du lien, et `pages.css`
  dessine une flèche après un lien texte. Un lien nommé par un `aria-label`
  — qui masque son contenu — porte la mention dans ce libellé, par
  `newWindowLabel(…)`. `npm run new-window-check` (joué en CI) refuse un
  composant qui ouvre une nouvelle fenêtre sans importer la directive, et un
  `aria-label` qui ne passe pas par `newWindowLabel`. Ne pas toucher au `rel` :
  le `noreferrer` des liens de l'espace animateur empêche le jeton de son URL
  de partir dans le `Referer`. Et avant d'annoncer une nouvelle fenêtre, se
  demander si elle est utile : les pages légales s'ouvrent depuis l'espace dans
  le même onglet, « Retour » y ramène.
- **Tout geste de glisser-déposer a un jumeau atteignable au clavier, et c'est
  le jumeau qui appelle le service.** `@angular/cdk/drag-drop` n'écoute que le
  pointeur : déplacer une affectation passe aussi par `shared/deplacement-dialog.ts`
  (« Déplacer … vers »), ouvert par la poignée — un bouton nommé, qu'un clic
  simple suffit à activer (WCAG 2.5.7) — sur la vue calendrier, et par Entrée
  sur la ligne du rail. Le choix fait dans le dialogue appelle la même méthode
  que le dépôt, et `GLISSER_DEPOSER_ACTIF` coupe les deux ensemble. Une cellule qui ouvre un détail est un arrêt clavier (le
  tableau croisé des contraintes : tabindex itinérant, Entrée), jamais un
  `(click)` sur un `<td>` seul.
- **Les raccourcis à une touche se coupent** (WCAG 2.1.4) : une case dans le
  dialogue « ? » et dans Paramètres, onglet Globaux, réglage du navigateur
  (`core/single-key-shortcuts.ts`). Un nouveau raccourci à une touche passe par
  `KeyboardShortcutsService`, qui respecte ce réglage.
- **Une longue liste se filtre, elle ne se déroule pas** :
  `app-selection-recherche` au-delà de quelques dizaines d'entrées. 153
  animateurs au clavier, c'est 153 flèches.
- **Une grille se parcourt aux flèches** (roving tabindex). N'écrire
  `role="grid"` que si la structure lignes/cellules existe réellement **et**
  que les flèches la parcourent : l'attribut fait basculer le lecteur d'écran
  en mode application, et une grille qui ne bouge pas aux flèches est alors
  moins lisible qu'un tableau ordinaire. Une table en lecture seule reste un
  `<table>` natif.
- **La couleur n'est jamais seule** : doubler d'un texte, d'une icône ou d'une
  initiale. Et une infobulle n'existe pas au tactile — ni au clavier sur un
  élément qui ne prend pas le focus (`<span>`, `<td>`) : son texte s'y double
  d'un `<span class="visually-hidden">`, un `aria-label` sur un élément sans
  rôle n'étant pas lu de façon fiable.
- **Les sept contrôles qui tiennent ces conventions** sont recensés dans
  [`accessibilite.md`](accessibilite.md#ce-qui-empêche-les-écarts-de-revenir) :
  les douze règles a11y d'`angular-eslint` en `error` (les trois désactivations
  sont commentées à leur ligne), cinq scripts du job `frontend`, et
  `e2e/accessibilite.spec.ts`, qui passe axe-core sur les écrans représentatifs
  — les quatre de l'espace animateur rejoués dans le projet `mobile` — contre
  une ligne de base gelée qui ne peut que décroître. Un écran qui y entre avec
  une violation *serious* ou *critical* la corrige, ou l'inscrit dans
  `LIGNE_DE_BASE` avec sa raison.
- **Toute couleur écrite par ce dépôt est mesurée avant d'être commise** —
  hors jetons `--mat-sys-*`, qui viennent de `mat.theme()` et sont réputés
  conformes par construction. Le seuil est 4,5:1 pour du texte, 3:1 pour un
  composant ou un anneau de focus, et la mesure se fait sur les **deux**
  surfaces, claire et sombre, puisque le thème compile les deux. Le mesureur
  est `core/testing/contrast.ts` : un chiffre qu'un test peut rejouer vaut
  mieux qu'un chiffre relevé une fois. C'est ce qui a manqué deux fois
  (audit #36) — la barre de marque à 3,24:1 sur tous les écrans, et une
  cellule de grille à 2,2:1 parce qu'une `opacity: 0.5` divisait une encre
  déjà atténuée. Une opacité sur du texte est une mesure à refaire, pas un
  détail de style.
- **Un accent de marque est borné dans les deux schémas.**
  `BRANDING_ACCENT_COLOR` est choisi par un exploitant qui regarde une surface,
  souvent la claire — `core/branding.ts` plafonne donc sa clarté OKLCH en
  clair et la plancher en sombre, et `branding.spec.ts` le mesure sur des
  accents pastel volontairement mauvais. Relever l'une des deux bornes sans
  refaire la mesure fait tomber l'anneau de focus des grilles sous 3:1, où la
  navigation au clavier devient invisible.

## Tests

Six familles :

- **contraintes** (`*ConstraintsTest`) — `ConstraintVerifier`, sans Quarkus ni
  base, quelques millisecondes. **Toute nouvelle contrainte ajoute au moins un
  cas pénalisé et un cas valide** ;
- **intégration** (`@QuarkusTest`) — PostgreSQL jetable par dev services, donc
  les migrations Flyway s'exécutent comme en production. Ce sont elles que
  `-Punit` écarte, voir [plus bas](#la-boucle-sans-conteneur) ;
- **frontend** (Vitest, jsdom) — pas branchés sur la phase Maven, job CI dédié ;
- **structurels** — ils ne jouent aucun scénario, ils **relisent le code** et
  échouent sur une règle que rien d'autre ne vérifie ;
- **gamme de scénarios** (`ScenarioLadder*Test`) — trente fichiers résolus de
  bout en bout, du plus petit problème à un mois de 150 stands, voir
  [plus bas](#la-gamme-de-scénarios) ;
- **bout en bout** (Playwright) — **à chaque poussée et sur chaque pull
  request**, sauf les specs marquées `@lourd`, jouées sur les seuls changements
  qui peuvent les casser et chaque nuit. Ils coûtent plusieurs minutes là où
  la suite unitaire répond en six secondes, et ce coût est assumé : c'est la
  seule couche qui voit ce qu'un navigateur fait vraiment. Voir
  [plus bas](#tests-de-bout-en-bout-playwright) pour les lancer en local.

### La boucle sans conteneur

`./mvnw test -Punit` ne joue que ce qui ne demande qu'une JVM : **1 405 tests
en une minute et demie**, sans Docker, sans PostgreSQL et sans dev services.
La suite entière en demande douze — et un runner GitHub, vingt.

Le chiffre qui explique le profil, mesuré sur un run vert (issue #475, point D4
de l'audit #392) : **112 classes sur 259 démarrent l'application**, et elles
coûtent **479 des 561 secondes** que la suite passe à jouer des tests. Les 145
autres en coûtent 82. La pyramide est à l'envers, et tant qu'elle l'est, la
boucle locale l'est aussi.

Ce que le profil écarte est écrit dans `src/test/container-tests.txt`, un
chemin par ligne, que le profil passe à surefire comme `excludesFile`. C'est
une **exclusion à la sélection**, et non une étiquette JUnit : un `@Tag` est
filtré après la découverte, or la découverte est déjà ce qui démarre Quarkus —
charger une classe `@QuarkusTest` construit l'application, dev services
compris, avant que le moindre filtre ne soit consulté.

Le fichier ne décide de rien, il rend visible : une ligne s'ajoute quand un
test neuf démarre l'application, elle disparaît quand un test est converti, et
`ContainerTestsInventoryTest` refuse tout écart entre le fichier et les
annotations — en disant quelle ligne écrire. Il se lit donc comme la mesure de
D4 : sa longueur est la dette, et elle ne peut que se voir.

**Un test de service neuf naît unitaire.** A1, A4 et A11 ont rendu les services
constructibles sans conteneur — `new PlanningService(…)` avec
`EmptyReferenceData`, comme le font `PlanningServicePlainTest`,
`PlanningHardConstraintsTest` ou le harnais `ScenarioLadder` — et le lecteur de
scénarios est pur et statique. Un `@QuarkusTest` reste la bonne réponse quand
le test porte sur le transport (une route, un code HTTP, un en-tête), sur une
migration ou sur le câblage lui-même ; il n'en est pas une pour une règle
métier que l'on peut appeler directement.

`-Punit` est la boucle, jamais la preuve : la CI joue la suite entière, et
c'est elle qu'il faut avoir vue verte avant de pousser.

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

### Où vivent les scénarios

Tous les scénarios — les fixtures écrites à la main, les trente barreaux de la
gamme, les quinze cas extrêmes — sont **à plat** dans le seul dossier
`src/main/resources/scenarios/`. Pas de sous-dossier, et pas de second dossier
`scenarios/` ailleurs sur le classpath : `ScenarioYamlReader.scenarioPath`
refuse un nom qui porte un composant de chemin, et `getResource("scenarios")`
ne rend que la **première** occurrence du classpath, jamais leur union. Un
fichier rangé autrement est un fichier que personne ne peut charger depuis le
sélecteur de la page Débogage.

Côté tests, `ScenariosLivres` est la seule lecture de ce dossier :

- `all()` — tout, pour les contrôles qui valent pour chaque fichier (validateur,
  binder) ;
- `references()` — les fixtures écrites à la main seules, pour les tests
  différentiels : ils figent une forme canonique par fichier, et une référence
  engendrée pour un fichier engendré ne serait lue par personne (celle
  d'`extreme-09` pèserait à elle seule plus que tout le corpus actuel) ;
- `noms(prefixe)` — la gamme ou les extrêmes, pour leurs contrôles de catalogue.

### La gamme de scénarios

Les trente fichiers `gamme-…` de `src/main/resources/scenarios/` sont des
scénarios de test rangés par taille : un jour, deux stands et trois animateurs au barreau 1, un mois, 150
stands et 320 animateurs au barreau 25. Chaque barreau exerce une partie de ce
qu'un fichier sait dire — rotation du midi, stands premium, mineurs et jour
férié, contraintes ad hoc, horaires récurrents sous toutes leurs portées,
relais repas à effectif plein ou réduit, journées types, contraintes désactivées, effectifs
portés par la fenêtre, édition cible. Les barreaux 26 à 30 ne doivent **jamais**
se résoudre, chacun pour une raison nommée, et le test dit si l'analyse de
faisabilité la voit.

Le nom d'un fichier est son index : `gamme-18-14j-35stands-132animateurs-effectifs-par-fenetre`.
`ScenarioLadderCatalogTest` échoue si un nom ne dit plus les jours, stands et
animateurs du fichier, si un fichier n'est joué par aucun test, ou si la
numérotation a un trou.

Le problème est construit comme en production, sans base : `ScenarioLadder`
résout les horaires, découpe les amplitudes, numérote les vacations découpées
comme le ferait la base, et applique `contraintes.desactivees`.
`ScenarioLadderImportTest` rejoue quatre barreaux par l'import réel et vérifie
que les deux chemins produisent les mêmes sièges. Après le score dur,
`assertCoreRules` relit le plan lui-même — jours d'indisponibilité,
chevauchements, mineurs, six jours par semaine, contraintes ad hoc — pour
qu'une contrainte qui cesserait de pénaliser ne passe pas au vert avec lui.

| Classe | Barreaux | Où |
| --- | --- | --- |
| `ScenarioLadderSmallTest` | 1 à 7 | suite par défaut |
| `ScenarioLadderMediumTest` | 8 à 15 | suite par défaut |
| `ScenarioLadderInfeasibleTest` | 26 à 30 | suite par défaut |
| `ScenarioLadderImportTest` | 2, 7, 8, 10 | suite par défaut (`@QuarkusTest`) |
| `ScenarioLadderLargeTest` | 16 à 25 | `scenario-lent`, `./mvnw test -Pscenario-tests` |

Les barreaux sont dimensionnés avec de la marge — au plus trois quarts de
l'effectif sollicité le jour le plus chargé : tous atteignent zéro écart dur
dès l'heuristique de construction, si bien que ce qu'ils relisent ne dépend pas
de la vitesse de la machine. Ils gardent la couverture fonctionnelle ; la
convergence d'un problème tendu reste l'affaire des scénarios `festival-*`.

Quand une évolution fait bouger un barreau, c'est une décision : corriger le
fichier **ou** l'assertion, en disant pourquoi, jamais les deux en silence.

### Les scénarios extrêmes

Les quinze fichiers `extreme-…` du même dossier poussent chaque dimension
au-delà de ce qu'une édition réelle demande, pour savoir où l'application cède avant qu'une
édition ne le découvre. Même harnais que la gamme, mêmes invariants relus sur le
plan, même contrôle de catalogue (`ScenarioExtremeCatalogTest`, suite par
défaut : chaque fichier valide, joué par un test, et un nom qui dit sa taille).

| Fichier | Limite | Mesuré (machine de développement) |
| --- | --- | --- |
| `extreme-01` | 1 000 animateurs pour 240 sièges | 0 dur en 5 s |
| `extreme-02` | un mois, 150 stands, 1 000 animateurs | 0 dur en 185 s, presque tout en construction |
| `extreme-03` | 120 jours, six jours fériés, saisonniers | 0 dur en 30 s |
| `extreme-04` | 120 jours sans relâche, 15 % de marge | 0 dur en 11 s |
| `extreme-05` | 500 stands le même jour | 0 dur en 30 s |
| `extreme-06` | exploitation 24 h/24 pendant une semaine | 0 dur en 3 s |
| `extreme-07` | vacations d'une heure | 0 dur en 15 s |
| `extreme-08` | un stand de 200 places | 0 dur en 7 s |
| `extreme-09` | tout à la fois : 120 j, 400 stands, 1 000 animateurs | lecture 1 s, analyse 0,25 s, ~1 Go de tas ; 0 dur en 3 min 30 s par la construction échantillonnée (50 min 48 s avant) |
| `extreme-10` à `15` | sans animateur, un siège pour 1 000, uniquement des mineurs, 2 000 contraintes ad hoc, stands jamais ouverts, sans créneau | voir `ScenarioExtremeDegenerateTest` |

**Ce qui cédait en premier était l'heuristique de construction** : elle évalue
chaque siège contre chaque candidat, son coût suit sièges × animateurs — et un
peu plus, le score se renchérissant à mesure que le plan se remplit —, et elle
ne se parallélise pas sans l'édition Enterprise. Au-delà de 15 millions de
couples, elle est désormais échantillonnée (voir *Réglage du solveur*). Le
reste — lecture, construction du problème, analyse, recherche locale jusqu'au
zéro dur — tient l'échelle.

**Le contrôle des contradictions ad hoc** comparait les exceptions deux à deux
(3,9 s pour 2 000 sur `extreme-13`, payées à chaque analyse de faisabilité).
Toutes ses règles supposent un animateur en commun : il les lit maintenant par
index d'animateur, sous la seconde, et `ContrainteAdHocContradictionsIndexTest`
tient les deux lectures égales, ordre compris.

Les dégénérés (`ScenarioExtremeDegenerateTest`) tournent dans la suite par
défaut. Les axes et le cumul portent le tag `scenario-extreme`, exclu de la suite
par défaut **et** du profil `scenario-tests` : `./mvnw test -Pscenario-extreme
-DargLine=-Xmx3g -Dtest=ScenarioExtremeAxisTest`, une classe à la fois. Leurs
plafonds valent une dizaine de fois le temps mesuré : ils disent qu'une
régression a eu lieu, pas combien de temps prend la machine. Un tas plus grand
n'y gagne rien — 1 Go suffit au cumul — et c'est lui que la pression mémoire
d'une machine partagée tue en premier.

### Tests de bout en bout (Playwright)

Ils réamorcent la base par `/api/database/import` : ne jamais les pointer
ailleurs que sur une pile jetable.

**Chaque spec repart d'un état de référence.** Avant la première, la suite
photographie la base par `/api/database/export` — un script qui commence par un
`DELETE FROM` de chaque table, donc une restauration autonome — puis chaque
spec y revient dans son `beforeAll`. Les specs ne se contaminent donc plus, et
la suite se relance sans recréer la base : la référence est mise en cache dans
`node_modules/.cache/` et rejouée au démarrage. Coût : ~40 ms par spec.

La première capture exige une base **vierge de données de test**, sinon elle
figerait l'état d'une exécution précédente ; la suite le refuse en le disant.
Supprimez le cache et recréez le conteneur PostgreSQL si le schéma a changé.

```bash
docker compose up -d postgres mailpit
GLISSER_DEPOSER_ACTIF=true ./mvnw quarkus:dev   # mail sur :1025, mot de passe « admin »
cd src/main/webui && npm run e2e         # + --headed, --ui, --project=mobile, un chemin de spec
```

`GLISSER_DEPOSER_ACTIF=true` est la seule variable à poser : le glisser-déposer
des vues journalières est coupé par défaut, et `glisser-deposer.spec.ts`
l'exerce. La pile de la CI (`e2e-suite.yml`) le pose de la même façon.

Réglages par variable d'environnement, tous facultatifs : `E2E_BASE_URL`
(défaut `http://localhost:8080`), `E2E_ADMIN_PASSWORD`, `E2E_MAILPIT_URL`
(défaut `http://localhost:8025`), `E2E_CHROMIUM`.

**Une spec `@lourd` ne tourne pas sur chaque poussée.** La semaine canicule sur
`festival-hivernal` (`canicule-festival-hivernal.spec.ts`) importe la fixture
réelle, la résout trois fois pour de vrai et la publie trois fois avec un PDF
par personne : treize minutes sur un runner GitHub, les quarante-deux autres
specs — cent quatre-vingt-quinze tests — en prennent sept. `e2e.yml` la laisse
de côté (`--grep-invert @lourd`) et `e2e-lourd.yml` la joue (`--grep @lourd`)
sur les changements du solveur, des consignes, de la publication, du `pom.xml`
et de ses propres fichiers — sa liste `paths`, à tenir à jour comme celle des
scénarios — et chaque nuit sur `main`. Comme les tests de scénario, et pour la
même raison, une PR Renovate ne l'exerce que sous le label `timefold` ou
`quarkus`.
Les deux appellent la même pile, `e2e-suite.yml`. Marquer une spec `@lourd`,
c'est dire qu'elle résout une fixture réelle ; ce n'est pas l'endroit où
ranger un test lent. En local, `npm run e2e -- --grep @lourd` la joue seule.

Une suite mérite un mot : `e2e/icones.spec.ts` vérifie que la police des icônes
arrive et se dessine. Un `<mat-icon>delete</mat-icon>` dont la police manque
n'échoue nulle part — le navigateur écrit le mot, le bouton le rogne, et tous
les écrans affichent « ... ». Aucun test unitaire ne peut le voir : jsdom n'a
pas de polices. Le test constate trois faits indépendants — le fichier servi est
bien une police et non la page de repli du SPA, le navigateur déclare la fonte
utilisable, et les icônes rendues sont des glyphes carrés et non des mots rognés
— et joint une capture de l'icône au rapport, réussite comprise.

**La trace d'abord.** `trace: 'retain-on-failure'` est déjà en place : un échec
laisse une trace navigable — pellicule, DOM, réseau, console — que la vidéo ne
remplace pas.

```bash
npm run e2e -- --trace on
npx playwright show-trace test-results/<dossier-du-test>/trace.zip
```

**La vidéo ensuite**, par `E2E_VIDEO` — absente, rien n'est enregistré. Elle
sert à montrer un parcours à qui ne lancera pas Playwright, pas à diagnostiquer.

```bash
E2E_VIDEO=retain-on-failure npm run e2e   # ou 'on' ; .webm dans test-results/<test>/
```

## Réglage du solveur

| Propriété | Défaut | Rôle |
| --- | --- | --- |
| `planning.solver.seconds-limit` | `900` (`3` en `%test`) | Budget de résolution |
| `planning.solver.unimproved-seconds-limit` | `300` (`2` en `%test`), `0` = désactivé | Arrêt sur plateau, **conditionné à la faisabilité** |
| `planning.constraint-weights.<contrainte>` | `1` | Voir [`contraintes.md`](contraintes.md#pondérer-une-contrainte) |
| `planning.jobs.reprise-au-demarrage` | `true` (`false` en `%test`) | Rejoue la file persistée. En test, une tâche laissée en file déclencherait un vrai solve au démarrage suivant |
| `planning.diagnostic.mode` | `score-director` | Implémentation du diagnostic par contrainte. `solution-manager` est l'oracle du test de contrat, pas un mode dégradé de secours — voir [`0013`](decisions/0013-diagnostic-par-le-score-director.md) |

**L'arrêt anticipé est un `AND`** entre `bestScoreFeasible` et la limite de
plateau : une résolution s'arrête quand le budget est épuisé, **ou** quand le
planning est déjà faisable et n'a plus progressé. Sans la condition de
faisabilité, le solveur abandonnait sur un plateau de score **dur** — exactement
le cas où il a besoin du reste de son budget.

### La construction échantillonnée des très gros problèmes

La première phase de `solverConfig.xml` évalue chaque siège contre chaque
animateur. Au-delà de **15 millions de couples** sièges × animateurs,
`LargeProblemConstruction` la remplace à la résolution par la même phase — sièges
les plus difficiles d'abord, filtre d'éligibilité compris — qui n'évalue que
**50 animateurs éligibles tirés au hasard** par siège — la limite compte ce que
le filtre garde, pas les tirages : comptée sur les tirages, un siège dont tout le
tirage était écarté n'avait aucun mouvement, et Timefold termine alors la phase
entière. Mesures (même graine) :

| Problème | Construction | 0 dur en | medium à 300 s |
| --- | --- | --- | --- |
| `extreme-02` (6 480 sièges × 1 000) | exacte | 182 s | **−5 521** |
| `extreme-02` | échantillonnée | 12 s | −5 938 |
| `extreme-09` (41 370 sièges × 1 000) | exacte | 50 min 48 s | — |
| `extreme-09` | échantillonnée | **3 min 30 s** | — |

Sous le seuil, la construction exacte se rembourse : trois minutes de plus pour
un premier plan meilleur, que la recherche locale ne rattrape pas en 300 s.
Au-dessus, elle ne tient plus dans un budget de production. Le seuil est
l'endroit où la construction exacte dépasserait dix minutes ; toute édition
réelle est loin dessous. Voir [0036](decisions/0036-construction-echantillonnee-des-tres-gros-problemes.md).

Une première piste, `pickEarlyType` `FIRST_FEASIBLE_SCORE_OR_NON_DETERIORATING_HARD`,
est inutilisable ici : un siège peut rester vide, « le laisser vide » ne dégrade
pas le score, et la construction s'arrête en 0,5 s sur un plan entièrement vide.

### `acceptedCountLimit` : mesuré, pas hérité

> **Les tables de cette section ont été mesurées en Timefold 1.34.** Elles sont
> conservées pour le raisonnement — pourquoi le compromis dépend de la taille du
> problème, pourquoi deux phases plutôt qu'une — mais **pas pour leurs temps** :
> à réglage identique, la 2.5 évalue environ dix fois plus de mouvements par pas
> de recherche locale. La mesure qui fait foi aujourd'hui est celle du profil de
> production en 2.5, juste en dessous.

#### Ce qui fait foi en 2.5

Remesuré sur le profil de production (3 499 postes, 153 animateurs), même
graine, budget de 1800 s, runs séquentiels :

| | `acl=2` (réglage 1.x) | `acl=1` (retenu) |
| --- | --- | --- |
| score dur à 180 s | -10 | -2 |
| **0 hard atteint à** | 526 s | **333 s** |
| medium à 1800 s | -4 748 | **-4 697** |
| soft à 1800 s | **-336** | -368 |

Deux conséquences, et une mise en garde.

**Le budget par défaut est passé de 180 à 900 s.** À 180 s, aucun des deux
réglages n'atteint la faisabilité sur le profil réel : le solveur rendait un
plan avec des places que personne ne tient, sans lever d'erreur — l'arrêt sur
plateau étant conditionné à la faisabilité, il ne peut pas le signaler non plus.
900 s laisse 2,7× la durée mesurée, de quoi absorber une machine plus lente.

**`acceptedCountLimit` de phase 1 passe de 2 à 1.** L'argument de la 1.x — 1
trouve plus tôt mais polit moins bien — ne tient plus : un pas échantillonnant
déjà dix fois plus large, baisser le compteur n'affame plus rien, et 1 gagne à
la fois sur le temps et sur le medium. Il ne cède que sur le soft.

**Ce que la fixture versionnée ne montrait pas.** `festival-realiste` sous-estime
le problème d'un tiers : elle sort de l'heuristique de construction à -110 hard
quand le profil réel en sort à -218, et atteint 0 hard à 359 s contre 526 s. Un
réglage arbitré sur elle seule est optimiste.

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
faisable, et évitent au solveur de payer un calcul de score pour découvrir un
écart certain. Le filtre n'est pas un mur — la reconstruction du *ruin and
recreate* ne le lit pas —, c'est pourquoi ces mêmes règles pèsent un forfait de
10 000 points durs par écart ([0034](decisions/0034-exclusions-eligibilite-plus-lourdes-que-tout.md)).

Règle à suivre pour toute nouvelle contrainte : exprimer d'abord ce qui peut
l'être en `Joiners.equal` / `lessThan` / `overlapping`, restreindre le flux
d'entrée avec un `filter` **avant** de joindre, et ne garder `Joiners.filtering`
que pour ce qui n'est pas indexable (ici : le calcul de distance haversine et
la comparaison de périmètre des contraintes ad hoc). La métrique à regarder est
le *move evaluation speed* du log solveur, pas le temps écoulé.

### Mode d'environnement et parallélisme

`solverConfig.xml` fixe `<environmentMode>NO_ASSERT</environmentMode>`. Les
modes *assert* recalculent intégralement le score à chaque frontière de phase
pour détecter une corruption : mesuré à **+7,4 %** de *move evaluation speed*
une fois retiré (4074 → 4377/s sur le scénario de référence, en 1.x). Le
déterminisme reste assuré par `<randomSeed>`. En contrepartie la détection de
corruption de score est désactivée — en cas de doute, repasser à
`PHASE_ASSERT`, ou `FULL_ASSERT` pour identifier le move fautif, puis **revenir
à `NO_ASSERT`**.

Ce réglage s'appelait `REPRODUCIBLE` en Timefold 1.x. Ce mode **n'existe plus
en 2.x** : la reproductibilité n'y est plus un mode mais une conséquence du
`randomSeed`, et `NO_ASSERT` est le même compromis sous le nouveau nom. Écrire
`REPRODUCIBLE` dans le fichier fait désormais échouer la construction de la
*solver factory*.

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

Les workflows vivent sous `.github/workflows/`, lisibles tels quels. Quelques
points qui ne s'y voient pas :

- **une poussée coûte une quarantaine de minutes de runner**, quel que soit
  son contenu : Tests (≈ 13 + 3), E2E (≈ 10), scénarios (≈ 8 dès que la PR a
  touché le solveur), Sécurité (≈ 1). D'où trois filtres : `tests.yml` et
  `e2e.yml` ignorent une poussée qui ne touche que `docs/` et le Markdown
  (moins les fichiers qu'un test relit ou que le job compare à son build,
  réinclus nommément) ; les specs Playwright `@lourd` ont leur workflow, comme
  les scénarios ; et une PR Renovate ne joue ni les scénarios ni la spec
  `@lourd` — les deux seuls workflows qui ont `pom.xml` dans leurs chemins —
  hors du label `timefold` ou `quarkus`. Sur une PR, GitHub évalue un filtre
  `paths` sur **l'ensemble des fichiers de la PR**, pas sur la dernière
  poussée : une PR mixte code + documentation rejoue tout à chaque poussée, et
  c'est voulu — un check « skipped » sur la dernière poussée masquerait le
  rouge de la précédente ;
- **sur une PR Renovate, `licences-renovate.yml` régénère
  `docs/licences-tierces.md`** et le commite sur la branche, puisqu'un bump
  fait échouer le contrôle par construction (le job `test` le joue d'ailleurs
  en premier, avant les treize minutes de tests). Une poussée faite avec
  `GITHUB_TOKEN` ne déclenche aucun workflow : le job relance lui-même Tests,
  E2E et Sécurité par `workflow_dispatch` sur la branche — et les deux
  workflows lourds sous le même label qui les gouverne ailleurs —, après avoir
  annulé ce qui tournait encore sur le commit remplacé. Deux jobs séparés, et
  c'est délibéré : la régénération lance des plugins Maven dont la version
  sort du `pom.xml` non relu de la PR, elle n'a donc que `contents: read` ;
  le jeton qui écrit sur la branche vit dans le second job, qui ne voit aucun
  build. La poussée n'est jamais forcée : si Renovate a rebasé entre-temps,
  elle est refusée, le run s'arrête en le disant, et celui que la rebase vient
  de déclencher repose le commit. `gitIgnoredAuthors` dans `renovate.json`
  évite que Renovate tienne la branche pour « modifiée » et cesse de la
  rebaser ; une rebase la recrée sans ce commit, que le job repose aussitôt ;
- **sur `main`, une fusion annule les runs de la précédente**
  (`cancel-in-progress`) : seule la dernière fusion d'une rafale est vérifiée
  là, la PR l'ayant déjà été. Les minutes annulées sont perdues, un run
  complet par fusion en coûterait davantage. L'E2E lourd range son groupe par
  type d'événement : une fusion n'annule donc pas le rattrapage nocturne, qui
  serait parti sans bruit — un run annulé n'est pas rouge ;
- **l'image publiée porte un SBOM et une signature cosign en mode keyless**. Les
  attestations GitHub natives attendent l'ouverture du dépôt ;
- **tous les workflows tournent sur des runners GitHub** (`ubuntu-latest`).
  Le résultat des tests se suit alors depuis la page d'une PR, sans dépendre
  de la disponibilité de la machine d'un seul développeur ; le prix est que
  ces minutes sont facturées. Pour `docker-ghcr.yml`, c'est en outre ce qui
  rend la provenance de l'image vérifiable une fois le dépôt public.
  La garde qui empêche une PR de fork de déclencher ces workflows est
  **conservée** : elle tenait au socket Docker du runner auto-hébergé, et la
  lever relève de l'ouverture publique (#286), pas d'un changement de runner.
- **`docker-ghcr.yml` publie `:main` à chaque fusion et `1.2.0`/`1.2` sur un
  tag `vX.Y.Z`** ; `:latest` n'est jamais posé par `metadata-action`
  (`latest=false`) mais par une étape dédiée, uniquement quand le tag poussé
  est le plus récent du dépôt. Le pourquoi — et toute la politique de
  release — est dans [`versioning.md`](versioning.md).
- **`release.yml` écrit le corps d'une release publiée** (git-cliff sur les
  messages de commit du tag) et ne touche à rien d'autre : ni tag, ni branche,
  ni fichier du dépôt — il n'y a pas de `CHANGELOG.md` à tenir. Il part sur
  `release: published`, donc de front avec `docker-ghcr.yml` que le push du
  même tag déclenche, sans lien entre les deux. Ses deux gardes de cohérence
  — la montée que les sujets demandaient, la branche où vit le tag — tournent
  **après** l'écriture : la release est déjà publiée quand le workflow
  démarre, rien ne peut plus l'empêcher, et un job rouge est le seul canal qui
  prévienne. Le détail est dans [`versioning.md`](versioning.md) § 3.

## Contexte de construction de l'image

Le démon Docker reçoit le contexte **entier** avant que le moindre `COPY` du
`Dockerfile` ne soit évalué, et celui-ci ne copie que `pom.xml`, `src` et
`.git`. Tout le reste de la racine était donc transféré pour rien.

`.dockerignore` exclut en conséquence `explorbot/`, `assets/`, `site/`,
`.quinoa/`, `.claude/worktrees/` et `src/main/webui/coverage/`. Sur un arbre de
travail habité, le contexte passe de ~420 Mio à ~26. En CI le gain est
négligeable — `docker-ghcr.yml` construit depuis un `checkout` neuf où ces
dossiers pèsent environ 1 Mo — mais deux raisons demeurent : le
`docker compose --profile app up --build` local, et le fait qu'`assets/` porte
des **données réelles**, qui n'ont rien à faire dans un contexte d'image.

Ajouter un dossier volumineux à la racine, c'est donc penser à l'exclure ici.

## Renovate

Couvre Maven, le wrapper Maven, Docker, les actions GitHub, npm et
`mise.toml`. Quatre règles valent d'être connues :

- les mises à jour **mineures et correctives des dépendances de test** sont
  groupées dans une seule PR — rien n'est fusionné automatiquement, `automerge`
  n'est pas activé ;
- **Quarkus et Timefold sont groupés par écosystème**, et les paquets
  `@angular/*` dans une seule PR : une montée partielle casse le build ;
- ce qui est **épinglé deux fois est groupé** : Maven (`mise.toml` et le
  wrapper), Playwright (le paquet `@playwright/test` et l'image du conteneur
  e2e) — deux PR séparées laisseraient les deux dériver. Deux réglages tiennent
  ces groupes : le Maven de `mise.toml` est lu par un gestionnaire
  `custom.regex` (celui de `mise` n'y trouvait aucune version à proposer, et le
  wrapper montait seul), et l'image Playwright échappe au délai de sept jours
  (Renovate ne lit pas sa date de publication sur `mcr.microsoft.com`, le délai
  la retenait indéfiniment et le groupe partait sans elle). De même, la règle
  Quarkus vient après celle des plugins de build : `quarkus-maven-plugin` et le
  BOM partagent `quarkus.platform.version`, que deux PR montaient sinon chacune ;
- les **majeures** de Java, PostgreSQL, victools et TypeScript passent par le
  tableau de bord (`dependencyDashboardApproval`) — ce qui suppose que l'issue
  de tableau de bord existe. TypeScript attend qu'Angular accepte la majeure :
  `@angular/compiler-cli` en borne la plage.

Les PR Renovate passent par le même `check-commits.sh` que les autres, sujet
sous 72 caractères compris. Pour Maven, où `depName` vaut
`groupId:artifactId`, le sujet ne garde que l'artifactId
(`fix(deps): update timefold-solver-core to v2.6.0`) : avec le nom complet et
le mot « dependency » du modèle par défaut, un artefact un peu long dépassait
la limite et sa PR échouait avant d'avoir lancé un test.

Ce que Renovate ne lit pas — le `node-version` d'`application.properties`, les
`java-version:` des workflows, les `FROM` du `Dockerfile` — est tenu d'accord
avec `mise.toml` par `ToolchainPinsStructuralTest` : une montée de version qui
n'en fait qu'une partie casse le build en nommant le fichier en retard.

Une image lancée par un `run:` de workflow échappe au gestionnaire
`github-actions`, qui ne lit que `uses:`, `container:` et `services:`. Un
gestionnaire `custom.regex` la rattrape, à condition de sortir l'image dans une
variable d'environnement précédée de son annotation — c'est ce que fait le job
Semgrep de `securite.yml` :

```yaml
env:
  # renovate: datasource=docker depName=semgrep/semgrep
  SEMGREP_IMAGE: semgrep/semgrep:1.174.0
```

Toute image doit porter une balise explicite, y compris dans
`docker-compose.yml` : sans balise ou sous `latest`, Renovate n'a rien à
proposer et la version installée dépend du jour du `pull`.

## Licences des dépendances

L'image est distribuée sous AGPL-3.0-only, mais elle redistribue aussi ses
dépendances, qui restent sous les leurs — et les licences MIT, BSD ou Apache
demandent que leur notice voyage avec le binaire.
[`licences-tierces.md`](licences-tierces.md) est cet inventaire. Il est
**généré**, jamais écrit à la main :

```bash
./mvnw license:add-third-party          # depuis la racine : le côté Java
cd src/main/webui && npm run licences   # fusionne les deux côtés dans le document
```

Deux sources, un seul rédacteur. Côté Java, `license-maven-plugin` résout la
fermeture des scopes `compile` et `runtime` — ce que l'image embarque dans
`target/quarkus-app/` — et ramène les noms de licence à leur identifiant SPDX
(six orthographes d'Apache-2.0 sinon, parce que chaque POM écrit la sienne). Le
plugin n'est lié à aucune phase : résoudre tout l'arbre à chaque build coûterait
une minute que personne n'a demandée. Côté npm, le script lit `package-lock.json`
et rien d'autre : un verrou v3 porte le champ `license` de chaque paquet, donc
l'inventaire n'a besoin ni de `node_modules` ni d'un outil de plus.

Le job `test` du workflow *Tests* refait les deux et compare au fichier commité
(`npm run licences-check`) : une dépendance ajoutée, retirée ou relicenciée par
une montée de version fait échouer la branche tant que l'inventaire n'a pas
suivi. C'est le même cliquet que pour le contrat OpenAPI, et pour la même
raison — un fichier commité que le dépôt ne produit plus est un fichier qu'on
cesse de croire.

Deux limites à connaître : le côté npm liste la fermeture des dépendances de
production, donc un **surensemble** de ce que le bundle embarque réellement
(sous-estimer coûterait une notice manquante, surestimer coûte une ligne) ; et
les paquets du système de base de l'image — JRE, client PostgreSQL,
distribution — n'y sont pas, ils relèvent du SBOM attaché à chaque image
publiée.

## Feuilles de style

Deux sortes, et pas de troisième : `src/styles.css` importe les partials que
tout écran charge (échafaudage des cartes, formulaires et tables ; retours ;
actions groupées ; couleurs des typologies ; polices et marque), et chaque page
porte en `styleUrl` ce qu'elle seule dessine, avec
`encapsulation: ViewEncapsulation.None` — les sélecteurs restent globaux, le
fichier voyage avec le chunk de la route au lieu de peser sur le bundle
initial. Une classe que plusieurs pages utilisent va dans `pages.css` ;
`npm run css-scope-check` refuse une page qui utilise une classe que seule
une autre route charge (les tests unitaires n'ont pas de CSS, personne
d'autre ne le verrait).

## Formatage

Le formatage n'est pas un sujet de revue : un outil décide, on obéit.

- **Java** : `spotless-maven-plugin` applique `palantir-java-format`, retire
  les imports inutilisés et fixe leur ordre. `spotless:check` est lié à la
  phase `validate` : tout `./mvnw test`, `package` ou `verify` refuse un
  fichier non formaté et dit quoi faire — `./mvnw spotless:apply`.
  `quarkus:dev` n'est pas concerné.
- **Frontend** : `npm run format` (prettier, avec le `.prettierrc` que le dépôt
  portait déjà) sur le TypeScript, le CSS et les scripts ; `npm run
  format-check` en CI. Les gabarits HTML sont exclus à dessein : dans un
  gabarit Angular, un blanc entre deux éléments inline est du rendu, et le
  reflux en changeait un.
- Les deux commits qui ont reformaté l'existant sont listés dans
  `.git-blame-ignore-revs` ; `git config blame.ignoreRevsFile
  .git-blame-ignore-revs` pour que `git blame` les saute. Le dépôt merge en
  rebase : après le merge de la PR qui les a introduits, ce sont les SHA que
  `main` a reçus qui doivent y figurer.

## Conventions

Elles vivent dans [`AGENTS.md`](../AGENTS.md), section *Working conventions* :
langue du code, `ApplicationLinks` pour tout lien public, `BusinessError` plutôt
qu'un `try/catch` dans une ressource, `ObjectMapper` injecté, messages de commit
courts, et les règles frontend. Le découpage des modules est dans
[`architecture.md`](architecture.md).

Les mémoires d'agents (`.claude/agents/`, `.github/chatmodes/`) sont dérivées
d'`AGENTS.md` : on modifie la source, pas les copies.
