# Lancer l'application en local et respecter les conventions des écrans

Vous saurez installer la chaîne d'outils, lancer le backend et le frontend avec rechargement à chaud, éviter les pièges de l'environnement de développement, puis ajouter ou modifier un texte d'écran sans casser la traduction ni l'accessibilité.

## Installer l'environnement

`mise install` lit `mise.toml` et installe Java 25 (Temurin), Maven et Node 26. Il faut en plus un runtime de conteneurs : PostgreSQL et les dev services de Quarkus en ont besoin. Node n'est requis que pour travailler directement sur le frontend, car Quinoa télécharge le sien pendant le build.

```bash
./mvnw quarkus:dev                     # backend + frontend, rechargement à chaud, :8080
./mvnw test                            # tests unitaires et d'intégration, sans les scénarios lents
./mvnw verify -DskipITs=false          # y compris les *IT (skipITs vaut true par défaut)
cd src/main/webui && npm test          # Vitest
```

`quarkus:dev` suffit : Quinoa démarre `ng serve` et le proxifie. Le frontend est désactivé sous le profil `%test`. Le compte local est `admin` / `admin`. Le profil `%test` ouvre l'API sans session pour que les tests l'appellent directement ; `AuthentificationAdminTest` rétablit la vraie politique par `@TestProfile` pour couvrir le flux réel.

Sans `mise` ni Docker (un conteneur neuf, Claude Code sur le web), la suite complète tourne quand même avec JDK 25 et PostgreSQL pris dans `apt` : la recette est dans le skill `sandbox-tests` du dépôt (`.claude/skills/sandbox-tests/SKILL.md`). Les commandes de test sont détaillées dans [la page des tests](tests.md).

### Le compilateur C2 reste actif

Par défaut, le mode dev de Quarkus arrête la JVM au compilateur C1 (`-XX:TieredStopAtLevel=1`) pour démarrer plus vite. Le solveur y tourne alors deux à quatre fois moins vite que l'application packagée, et un calcul chronométré en dev mesure le mode dev, pas le solveur. La propriété `forceC2` du `pom.xml` vaut donc `true`. Pour retrouver un démarrage rapide pendant une session qui ne lance aucun calcul : `./mvnw quarkus:dev -DforceC2=false`.

### Avertissements du JDK 25

Le JDK 25 restreint l'accès natif (JEP 472) et signale `sun.misc.Unsafe` (JEP 498). Les deux sources sont traitées : `.mvn/jvm.config` réduit au silence ce que le wrapper Maven embarque (jansi, guava), et `--enable-native-access=ALL-UNNAMED` figure dans `jvm.args` du `pom.xml` parce que la console interactive de `quarkus:dev` charge JNA. L'image de production ne porte aucun flag JVM.

## Variables d'environnement locales

`mise.toml` porte les variables que tout le monde partage (`MAIL_ADMIN`). Celles qui dépendent de la machine vont dans `mise.local.toml`, à la racine : mise le charge par-dessus et `.gitignore` l'exclut, c'est donc le seul endroit où écrire une valeur qui ne doit pas atteindre un dépôt public. Exemple pour voir la page `/mentions-legales` remplie (les mentions se posent par `LEGAL_MENTIONS`, décrite dans [les variables d'environnement](../installer/variables-environnement.md) et dans [les mentions légales](../installer/mentions-legales.md)) :

```toml
[env]
LEGAL_MENTIONS = """\
editeur=Association Les Bénévoles du Jeu, 12 rue des Pions, 79000 Niort, RNA W791234567;\
directeur-publication=Camille Exemple, présidente;\
hebergeur=Hébergeur Fictif SAS, 1 avenue des Serveurs, 75000 Paris;\
contact=planning@exemple.invalid;\
responsable-traitement=Association Les Bénévoles du Jeu, représentée par sa présidente;\
base-legale=Exécution du contrat de bénévolat et intérêt légitime de l'organisateur (art. 6.1.b et 6.1.f RGPD);\
conservation=Jusqu'au 31 décembre de l'année suivant l'édition, puis suppression"""
```

Un `quarkus:dev` déjà lancé ne relit pas l'environnement : relancez-le. Le reste des variables est dans [l'exploitation](../installer/exploitation.md).

## Deux pièges du mode dev

**Un F5 ou un lien profond sur `:8080` renvoie 404.** Quinoa retire l'en-tête `Accept` de ses requêtes vers `ng serve` ([bug amont #666](https://github.com/quarkiverse/quarkus-quinoa/issues/666)), ce qui désactive le fallback SPA d'Angular CLI. La production n'est pas touchée. Ouvrez `:4200` directement : `proxy.conf.json` y redirige `/api/*` vers `:8080`.

**Les mails partent réellement**, vers `localhost:1025`. `docker compose up mailpit` fournit le puits et son interface sur `:8025`. Seul le profil de test mocke sans condition ; `MAIL_MOCK=true` rétablit le mock ailleurs. Mailpit rend la partie HTML : c'est là qu'on relit un gabarit de `src/main/resources/templates/mail/` après modification. Le bouton « mail de test » de la page Débogage suffit pour la mise en page commune, et `MailServiceTest`, `NotificationWriterTest` et `MailTemplatesTest` verrouillent le texte.

## Les métriques en local

Les métriques Prometheus sont sur un second port, l'interface de management : `http://localhost:9000/q/metrics` en dev. Sous `%test`, ce port est tiré au hasard (`quarkus.management.test-port=0`) pour que deux suites lancées côte à côte ne se le disputent pas. Un test l'atteint par `@TestHTTPResource(value = "metrics", management = true)` (chemin relatif à `/q`) ou lit directement le `MeterRegistry`. Le détail de ce qui est mesuré est dans [l'observabilité](../installer/observabilite.md).

## Les fichiers générés avant chaque build

Deux fichiers du frontend ne sont pas dans le dépôt. `npm run generate` les écrit, et `prestart`, `prebuild`, `prewatch`, `pretest` et `i18n-check` l'appellent déjà : il n'y a rien à lancer à la main.

| Fichier | Script | Contenu |
| --- | --- | --- |
| `src/app/version.ts` | `scripts/generate-version.js` | `APP_VERSION` : le tag exact s'il y en a un, sinon le SHA court ([versions et releases](../installer/versioning.md)) |
| `src/app/pages/nouveautes/news-data.ts` | `scripts/generate-news.js` | l'historique git (sujet du commit, date, tag de version) que l'écran Nouveautés affiche |

Les deux sont dans `.gitignore`, et `news-data.ts` aussi dans `.prettierignore` : `JSON.stringify` l'écrit, un reformatage ne survivrait pas à la régénération suivante. Ce que chaque commit veut dire (sous quel intertitre il tombe, comment sa phrase se lit sans son préfixe) vit dans `pages/nouveautes/news.ts`, testé par `news.spec.ts`. Ces règles recopient celles de `cliff.toml`, et une règle que personne ne teste dérive de son modèle.

Un clone superficiel raccourcit l'écran Nouveautés, et une arborescence sans `.git` le laisse vide en le disant. Le `Dockerfile` copie `.git` pour cette raison et pour `git describe`. Pour la même raison, `docker-ghcr.yml`, qui construit l'image publiée, passe en `fetch-depth: 0` : avec la profondeur par défaut de 1, l'écran de chaque image ne listerait qu'un commit.

## Traduire : l'i18n à l'exécution

Le frontend est un seul build, sans bundle par langue. Le français est écrit directement dans les templates ; si l'anglais est choisi, `loadTranslations()` charge `public/i18n/messages.en.json` avant `bootstrapApplication()`. Changer de langue recharge la page, car les messages ne sont résolus qu'une fois, au démarrage.

Pour ajouter une chaîne :

1. posez `i18n="@@page.section.role"` dans un template, ou `` $localize`:@@id:…` `` en TypeScript ;
2. ajoutez la traduction dans `messages.en.json`, avec les mêmes placeholders dans le même ordre ;
3. lancez `npm run i18n-check`.

Le titre d'une route est une fonction qui renvoie une chaîne `$localize`, jamais un littéral : évalué après le démarrage, il est vu par `ng extract-i18n` et compté par `i18n-check`, et le nom de page que le lecteur d'écran annonce est dans sa langue. `app.routes.spec.ts` refuse un littéral.

> N'appelez jamais `$localize` au niveau d'un module. Le fichier serait évalué avant `loadTranslations()` et resterait figé en français. Passez par un `computed()`, une méthode ou une fonction appelée depuis le constructeur.

### Ce que `i18n-check` refuse

Chacun de ces écarts est invisible autrement :

| Écart | Ce qui se passerait |
| --- | --- |
| Un id sans traduction | l'écran s'affiche en français pour un anglophone : `$localize` retombe sur la source sans rien dire |
| Une clé orpheline | en général la moitié oubliée d'un renommage |
| Des placeholders divergents | le jeton littéral s'affiche à la place de la valeur, sur ce seul écran et dans cette seule langue ; ni le build ni les tests ne le voient |
| Un libellé cité qui ne correspond à rien | quand un message cite un autre message mot pour mot (« Ouvrir la collecte », « Arrêter le solveur »), sa traduction doit citer le libellé anglais de cet id et non une paraphrase ; la comparaison ne porte que sur les citations d'au moins deux mots |
| Un même id sur deux textes français différents | l'extracteur n'en garde qu'un et le catalogue anglais n'a qu'une chaîne : l'un des deux endroits affiche l'autre en anglais |

Aucun de ces contrôles ne regarde le texte lui-même. Réécrire une source française sous le même id laisse l'anglais dire l'ancienne version, et le contrôle reste vert : la revue humaine vérifie, à chaque pull request, que l'anglais d'un id a suivi son français.

## Écrire un texte d'écran

Un écran répond à une question, il ne l'explique pas. Ces règles empêchent la prose de revenir hint par hint :

- Sous-titre d'écran : une phrase qui dit ce que l'écran répond, sans dire comment il le calcule ni ses réserves.
- `mat-hint` : douze mots au plus. Au-delà, c'est une infobulle ou l'Aide.
- Aucune répétition en texte de ce que le tableau, le formulaire ou les boutons montrent déjà.
- Le pourquoi vit dans l'Aide (`pages/aide/content/*`, et l'aide de l'espace animateur), ancrée depuis l'écran. L'écran garde le quoi.
- Plafond de 200 caractères par message, hors Aide, aide de l'espace, pages légales et avertissement de responsabilité du dialogue de désactivation d'une règle légale. `npm run i18n-check` le refuse. Seule exception, reconnue au texte : un message qui nomme une règle avec son article (« art. L3132-1 », « L4153-3 ») reste entier, parce que c'est lui qui rend l'outil défendable.
- Une confirmation dit ce qui se passe et ce qui est perdu, en une ou deux phrases. Un message court qui se lit bien n'est pas réécrit.

## Accessibilité

Ces conventions valent pour tous les écrans : ceux de l'administration et les quatre de l'espace animateur, le seul périmètre lu hors de l'organisation, sur un téléphone, par des personnes dont une partie est mineure. Les tenir coûte peu à l'écriture et cher à rattraper.

### Structure et annonces

- Lien d'évitement et focus rendu à la page. La coque d'administration et celle de l'espace animateur commencent par `<a class="skip-link" href="#contenu">` (style partagé dans `pages.css`) et enveloppent l'écran dans `<main id="contenu" tabindex="-1">`, refocalisé à chaque changement de page. Dans l'espace, c'est à chaque changement d'onglet de la barre, pas à chaque jour choisi dans la bande, qui n'est qu'une vue de la même page.
- Un seul `<h1>` par écran. `mat-card-title` rend une `<div>` : écrivez `<h1 mat-card-title>` sur le titre principal et `<h2>` sur les cartes suivantes. `npm run headings-check` refuse un écran routé qui n'a pas exactement un `<h1>`, et tout `<mat-card-title>` écrit comme élément. Il ne lit que les composants que `app.routes.ts` charge : les quatre onglets de `/diagnostic` et les cinq rendus de `/journee` sont les composants d'une page qui porte déjà le sien.
- Le résultat d'une action s'annonce par `app-status-message`, jamais par un `<p>` inerte : `tone="error"` produit un `role="alert"`, les autres un `role="status"`. Le composant ne rend rien sur un texte vide, donc n'entourez pas d'un `@if` : c'est sa création avec le texte qui fait annoncer la région. Un verdict dont la mise en page tient à sa classe (`marge-message`, `pauses-message`) garde son élément et porte `role="status"`. `npm run status-messages-check` refuse un élément dont tout le contenu est l'interpolation d'une erreur, d'un message ou d'un verdict sans `role` ni `aria-live`. Ses exceptions (contenu d'un dialogue lu à l'ouverture, ligne de liste, bandeau présent dès le rendu) sont écrites une par une avec leur raison.
- Une erreur de formulaire est reliée à son champ : `mat-error` dans le `mat-form-field`, et `role="alert"` quand elle porte sur plusieurs champs.
- Le focus survit à la suppression d'une ligne : `focusApresSuppression(...)` le rend au bouton « Ajouter ».

### Icônes, infobulles, couleur

- Une `<mat-icon>` porteuse d'information écrit `aria-hidden="false"` en dur. `MatIcon` relit l'attribut par `inject(new HostAttributeToken('aria-hidden'))`, qui ne voit pas une liaison Angular, et un élément `aria-hidden="true"` quitte l'arbre d'accessibilité avec son `aria-label` et l'`aria-describedby` que `MatTooltip` lui ajoute. Une icône qui ne fait que répéter le texte voisin reste décorative, et perd alors son `aria-label` et son `matTooltip`.
- Une infobulle n'est pas un nom. `MatIcon` impose `role="img"`, un rôle qui ne prend pas son nom de son contenu : une icône à seule infobulle est annoncée comme un graphique anonyme. Le `matTooltip` est la description, l'`aria-label` le nom, et les deux se posent ensemble. `npm run icon-labels-check` refuse une icône portant `aria-label`, `aria-labelledby` ou `matTooltip` sans `aria-hidden="false"` statique, et une icône ainsi exposée sans `aria-label` ni `aria-labelledby`.
- La couleur n'est jamais seule : doublez-la d'un texte, d'une icône ou d'une initiale. Une infobulle n'existe pas au tactile, ni au clavier sur un élément qui ne prend pas le focus (`<span>`, `<td>`) : son texte s'y double d'un `<span class="visually-hidden">`, parce qu'un `aria-label` sur un élément sans rôle n'est pas lu de façon fiable.
- Toute couleur écrite par le dépôt est mesurée avant d'être commise, hors jetons `--mat-sys-*` que `mat.theme()` fournit conformes. Le seuil est 4,5:1 pour du texte et 3:1 pour un composant ou un anneau de focus, mesuré sur les deux surfaces, claire et sombre, puisque le thème compile les deux. Le mesureur est `core/testing/contrast.ts` : un chiffre qu'un test peut rejouer vaut mieux qu'un chiffre relevé une fois. Une opacité sur du texte est une mesure à refaire, pas un détail de style (une `opacity: 0.5` sur une encre déjà atténuée a déjà fait tomber une cellule de grille à 2,2:1).
- L'accent de marque est borné dans les deux schémas. La clé `accent` de `BRANDING_COLORS` est choisie par un exploitant qui regarde souvent la surface claire. `core/branding.ts` plafonne donc sa clarté OKLCH en clair et la plancher en sombre, et `branding.spec.ts` le mesure sur des accents pastel volontairement mauvais. Relever une des bornes sans refaire la mesure rend l'anneau de focus des grilles invisible sous 3:1.

### Tableaux, liens, gestes

- Un tableau porte une `<caption>` masquée qui dit ce qu'il compte et sur quel périmètre, et ses en-têtes portent leur `scope`, y compris un `<th mat-header-cell>`, dont Material n'écrit jamais le `scope`. `npm run table-headers-check` refuse un `<th>` qui n'en porte pas.
- Un lien qui ouvre une nouvelle fenêtre le dit. La directive `NewWindowLink` (`shared/new-window-link.ts`, sélecteur `a[target="_blank"]`) ajoute « (nouvelle fenêtre) » en texte masqué, et `pages.css` dessine une flèche après un lien texte. Un lien nommé par un `aria-label`, qui masque son contenu, porte la mention dans ce libellé par `newWindowLabel(…)`. `npm run new-window-check` refuse un composant qui ouvre une nouvelle fenêtre sans importer la directive, et un `aria-label` qui ne passe pas par `newWindowLabel`. Ne touchez pas au `rel` : le `noreferrer` des liens de l'espace animateur empêche le jeton de son URL de partir dans le `Referer`. Avant d'annoncer une nouvelle fenêtre, demandez-vous si elle est utile : les pages légales s'ouvrent depuis l'espace dans le même onglet, et « Retour » y ramène.
- Un saut de ligne entre un texte et un lien n'est pas un espace : Angular retire un nœud fait de blancs seuls. Terminez la première ligne par `&ngsp;`. `npm run inline-spacing-check` refuse un `<ng-container i18n>` ou un élément en ligne suivi, à la ligne, d'un lien ou d'un autre élément en ligne.
- Tout geste à la souris a un jumeau clavier (WCAG 2.5.7). Déplacer une affectation passe uniquement par « Vers un autre siège… » de « Simuler un changement », dans le panneau du siège, qui ouvre `shared/deplacement-dialog.ts` ; il n'y a pas de glisser-déposer. Une cellule qui ouvre un détail est un arrêt clavier (le tableau croisé des contraintes : tabindex itinérant, Entrée), jamais un `(click)` sur un `<td>` seul.
- Les raccourcis à une touche se coupent (WCAG 2.1.4) : une case dans le dialogue « ? » et dans Paramètres, onglet Instance, réglage du navigateur (`core/single-key-shortcuts.ts`). Un nouveau raccourci passe par `KeyboardShortcutsService`, qui respecte ce réglage.
- Une longue liste se filtre, elle ne se déroule pas : `app-selection-recherche` au-delà de quelques dizaines d'entrées. Avec 153 animateurs, un menu déroulant coûte 153 flèches au clavier.
- Une grille se parcourt aux flèches (roving tabindex). N'écrivez `role="grid"` que si la structure lignes/cellules existe et que les flèches la parcourent : l'attribut fait basculer le lecteur d'écran en mode application, et une grille qui ne bouge pas aux flèches se lit moins bien qu'un tableau ordinaire. Une table en lecture seule reste un `<table>` natif.

### Ce qui tient ces conventions

Les contrôles sont recensés dans [la déclaration d'accessibilité](../installer/accessibilite.md) : les douze règles a11y d'`angular-eslint` en `error` (les trois désactivations sont commentées à leur ligne), cinq scripts du job `frontend` (`headings-check`, `icon-labels-check`, `status-messages-check`, `table-headers-check`, `new-window-check`) et `e2e/accessibilite.spec.ts`, qui passe axe-core sur les écrans représentatifs, les quatre de l'espace animateur rejoués dans le projet `mobile`. Cette spec compare à une ligne de base gelée qui ne peut que décroître : un écran qui y entre avec une anomalie *serious* ou *critical* la corrige ou l'inscrit dans `LIGNE_DE_BASE` avec sa raison.
