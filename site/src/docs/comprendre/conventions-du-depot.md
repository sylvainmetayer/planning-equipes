# Conventions du dépôt

Vous saurez qui décide du formatage, quelle langue s'écrit où, comment un diagramme PlantUML entre dans une page et reste fidèle au code, comment se tiennent les feuilles de style, comment plusieurs branches modifient les mêmes fichiers sans conflit, et où vivent la vitrine et les sources de cette documentation.

## Le formatage est décidé par un outil

Aucune relecture ne discute du formatage : un outil décide, on obéit.

| Côté | Outil | Commande | Contrôle |
| --- | --- | --- | --- |
| Java | `spotless-maven-plugin`, `palantir-java-format`, imports inutiles retirés et ordonnés | `./mvnw spotless:apply` | `spotless:check`, lié à `validate` : tout `./mvnw test`, `package` ou `verify` refuse un fichier non formaté et dit quoi faire (`quarkus:dev` n'est pas concerné) |
| Frontend | `prettier`, avec le `.prettierrc` du dépôt, sur le TypeScript, le CSS et les scripts | `npm run format` | `npm run format-check` en CI |

Les gabarits HTML Angular sont exclus à dessein : dans un gabarit, un blanc entre deux éléments inline est du rendu, et le reflux de prettier en changeait un. Les gabarits `template:` écrits en littéral dans un composant sont protégés de la même façon par `embeddedLanguageFormatting: off`.

Les deux commits qui ont reformaté l'existant sont listés dans `.git-blame-ignore-revs` ; `git config blame.ignoreRevsFile .git-blame-ignore-revs` fait que `git blame` les saute. Le dépôt fusionne en rebase : ce sont les SHA que `main` a reçus qui doivent y figurer, pas ceux de la branche. `.github/scripts/check-blame-ignore-revs.sh` vérifie que chaque révision listée est un ancêtre de `HEAD`.

## La langue du code et les commits

Les conventions de code vivent dans [`AGENTS.md`](repo:AGENTS.md), le fichier que lisent les assistants de code et, avant eux, les contributeurs. Les mémoires d'agents (`.claude/agents/`, `.github/chatmodes/`) en dérivent : on modifie la source, jamais les copies.

- **Les commentaires et les identifiants non métier sont en anglais**, les noms du domaine en français (`Animateur`, `Creneau`, `PosteAffectation`). Un verbe ou un nom commun est toujours anglais, l'ordre des mots aussi. `LanguagePolicyStructuralTest` lit les sources du backend et refuse un bloc de commentaire en français ou un nom déclaré français. Le glossaire d'`AGENTS.md` fixe le vocabulaire de la prose anglaise : *timeslot* pour `Creneau`, *seat* pour `PosteAffectation`, *shift* pour `Vacation`, *opening span* pour `Amplitude` (deux faux amis : *vacation* et *amplitude* veulent dire autre chose en anglais).
- **Une clé JSON est un contrat.** `JsonContractTest` fige les clés de chaque type exposé dans `src/test/resources/json-contract.txt` : renommer un accesseur ou une composante de record déplace une clé publique, et ce test le rend bruyant.
- **Un test touché prend un nom de méthode anglais.** Les anciens noms français sont laissés tels quels, la dette se paie là où le travail se fait.
- **Les liens publics passent par `ApplicationLinks`**, jamais par concaténation : une URL imprimée dans un mail ou un PDF vise une route de la SPA Angular, pas un chemin JAX-RS.
- **Un refus lève `BusinessError`**, et une ressource n'écrit jamais de `try/catch` pour lui : la hiérarchie scellée porte le statut HTTP et `BusinessErrorMapper` la traduit.
- **L'`ObjectMapper` s'injecte**, jamais `new ObjectMapper()` : un mapper nu n'a pas les modules que Quarkus enregistre (JSR-310).
- **Un message de commit est court.** Sujet sous 72 caractères, préfixe conventionnel, point final absent ; un corps seulement s'il porte ce que le diff ne dit pas. Les sujets deviennent le corps de la release (git-cliff, `cliff.toml`) et le préfixe décide de la version, voir [les versions et releases](../installer/versioning.md). Deux trailers sont admis, `Signed-off-by` et `Co-Authored-By`, aucun autre : `.github/scripts/check-commits.sh` tient ces règles sur chaque PR.

## Les diagrammes PlantUML

Les diagrammes de séquence, d'états et de classes vivent dans [`docs/diagrammes/`](repo:docs/diagrammes/) : un `.puml` écrit à la main, et à côté le `.svg` qu'il produit, commité lui aussi. La page qui possède le sujet le montre sous le paragraphe qu'il illustre, avec un lien vers sa source (le site publie les SVG sous `/docs/diagrammes/`) :

```markdown
![Séquence d'un solve lancé depuis le référentiel](repo:docs/diagrammes/solve.svg)

<sub>Source : [`diagrammes/solve.puml`](repo:docs/diagrammes/solve.puml).</sub>
```

Après avoir modifié ou ajouté un `.puml`, régénérez les SVG et commitez les deux :

```bash
./mvnw validate -Pgenerate-diagrams
```

Le profil n'appelle que PlantUML (build MIT, dépendance de test : ni l'image ni l'inventaire des licences ne le voient). Les diagrammes d'états et de classes déclarent `!pragma layout smetana`, le portage Java de Graphviz, si bien qu'aucun outil n'est à installer à côté de la JVM.

[`DiagramsStructuralTest`](repo:src/test/java/dev/sylvain/planning/DiagramsStructuralTest.java) tient les diagrammes au code, dans le même sens que `DocumentationStructuralTest` pour la prose : ce qu'un diagramme nomme doit exister.

| Règle | Ce qui la fait échouer |
| --- | --- |
| Le SVG est le rendu de son `.puml` | Un `.puml` modifié sans régénération. PlantUML embarque sa source dans le SVG : le test la décode et la compare sans rien redessiner, donc sans dépendre des polices de la machine |
| Chaque diagramme est montré par une page de `site/src/docs/` (une image Markdown, hors bloc de code) et chaque image montrée existe | Un diagramme orphelin, un lien vers un SVG absent |
| Chaque page qui montre un diagramme figure dans les chemins du workflow Tests | Une page ajoutée sans sa ligne : une poussée qui n'y toucherait que lui ne jouerait pas le contrôle ci-dessus |
| Un `participant`, ou une boîte `class`, `abstract class`, `interface` ou `enum`, nommé comme un type Java en est un | Une classe renommée. Les rôles qui ne sont pas du code s'écrivent `actor`, `boundary`, `control` ou `database`, ou portent un espace ou un point dans leur nom : ils ne sont pas vérifiés |
| Un message `méthode(…)` envoyé à un tel participant est déclaré par son type ou l'un de ses supertypes du projet | Une méthode renommée. Un libellé qui n'est pas un appel ne commence pas par `identifiant(` ; une flèche à deux pointes n'est pas vérifiée |
| Une route `VERBE /api/…` citée existe | Une route renommée ou retirée |
| Un diagramme d'états qui porte `' enum: Nom` dessine exactement les constantes de cette enum | Une constante ajoutée et oubliée, un état qui n'existe plus |
| Un champ `nom : Type` dessiné dans une boîte est un champ déclaré par la classe (ou une composante de son record) ; une constante dessinée dans une boîte `enum` en est une | Un champ renommé du modèle, une constante retirée |

Le test ne voit ni l'ordre des appels ni le sens d'un libellé : un diagramme reste de la prose, à relire quand le flux qu'il dessine change.

## Les feuilles de style

Il y en a deux sortes. `src/styles.css` importe les partials que tout écran charge : échafaudage des cartes, formulaires et tables, retours, actions groupées, couleurs des typologies, polices et marque. Chaque page porte en `styleUrl` ce qu'elle seule dessine, avec `encapsulation: ViewEncapsulation.None` : les sélecteurs restent globaux, et le fichier voyage avec le chunk de la route au lieu de peser sur le bundle initial.

Une classe que plusieurs pages utilisent va dans `pages.css`. `npm run css-scope-check` (depuis `src/main/webui`) refuse une page qui emploie une classe que seule une autre route charge, que personne d'autre ne verrait puisque les tests unitaires n'ont pas de CSS, et une classe définie qu'aucun gabarit ni littéral TypeScript n'emploie : une règle morte se retire avec le gabarit qui la portait.

## La vitrine

La page de présentation, qui porte aussi cette documentation, est un site Eleventy à part, dans `site/`. Elle a ses dépendances (`site/package.json`, aucune n'entre dans l'application), son workflow `.github/workflows/site.yml` et sa publication par Cloudflare Pages. Le workflow est filtré sur `site/**`, sur `docs/**` (le site publie les diagrammes et l'inventaire des licences) et sur `ConstraintCatalog.java`, dont les articles du Code du travail sont relus par `site/test/articles.test.mjs`. Les textes s'éditent dans Pages CMS, configuré par `.pages.yml` à la racine du dépôt.

`mise run site`, depuis la racine, installe les dépendances et lance le serveur Eleventy. Les commandes, les tests, le tournage des captures et les réglages Cloudflare sont dans [`site/README.md`](repo:site/README.md), et la charte (jetons, polices, règles, la partie « Documentation ») dans [`site/design.md`](repo:site/design.md), à lire avant tout changement de `site/`. Le scénario `vitrine-fete-du-jeu-de-pierrefeuille.yaml`, livré avec les autres, est le décor des captures : il ne change pas sans qu'on les refasse.

## Les fichiers que plusieurs branches modifient

Plusieurs PR ouvertes en même temps touchent les mêmes fichiers, et chaque conflit coûte une fusion de plus et un passage de CI. Ces fichiers sont découpés pour que deux branches n'écrivent pas les mêmes lignes.

| Fichier | Forme | Ce qui la tient |
| --- | --- | --- |
| Guide « Passer de la 3.x à la 4.0 » | une section par fichier, dans `site/src/docs/installer/passer-a-la-4-0/` et `site/src/docs-en/install/moving-to-4-0/` | le build du site |
| Nombre de règles du catalogue | jamais écrit en chiffres : `{{ regles.total }}` dans une page, « chaque règle du catalogue » dans le code et `AGENTS.md` | `DocumentationStructuralTest` |
| `src/test/resources/migrations-empreintes.txt` | une ligne par migration, triée par version | `FlywayMigrationsFrozenTest` |
| `docs/decisions/README.md` | une ligne par ADR, triée par numéro, la table en fin de fichier | `DocumentationStructuralTest` |
| `src/test/resources/json-contract.txt` | une clé par ligne, `Type.clé`, triée | `JsonContractTest` |
| `src/main/resources/mcp-renommages.tsv` | une ligne par outil, triée par ancien nom | `McpRenommageTest` |
| `src/main/webui/public/i18n/messages.en.json` | trié par id | `npm run i18n-check` |

Le site compte `{{ regles.total }}` sur la table du catalogue de [Pourquoi ces règles](pourquoi-ces-regles.md), que `DocumentationStructuralTest` tient égale à `ConstraintCatalog`.

Les quatre listes à une ligne par entrée sont fusionnées en union de lignes (`merge=union` dans `.gitattributes`) : deux branches qui ajoutent chacune une ligne ne se gênent plus. Une union peut laisser des lignes hors d'ordre, ou deux lignes pour une même entrée quand les deux côtés l'ont modifiée : le test de chaque fichier le refuse, et la correction se fait à la main. Le catalogue anglais reste hors de l'union, à cause des virgules du JSON ; un conflit s'y résout en gardant les deux côtés, puis `npm run i18n-check` signale les orphelins :

```bash
jq -S -s '.[0] * .[1]' <(git show MERGE_HEAD:src/main/webui/public/i18n/messages.en.json) <(git show HEAD:src/main/webui/public/i18n/messages.en.json) > src/main/webui/public/i18n/messages.en.json
```

### Ajouter une section au guide de passage à la 4.0

La page `passer-a-la-4-0.md` ne garde que l'introduction ; chaque changement est un fichier du dossier `passer-a-la-4-0/`, que le site ajoute à la suite de la page. La page publiée, son adresse et ses ancres restent les mêmes.

1. Créez `site/src/docs/installer/passer-a-la-4-0/<sujet>.md`. Il ouvre sur un titre de niveau 3 et porte un `ordre:` numérique dans son front matter :

   ```markdown
   ---
   ordre: 245
   ---

   ### Le titre du changement
   ```

2. Créez sa jumelle anglaise dans `site/src/docs-en/install/moving-to-4-0/`, sous le **même nom de fichier** et avec le même `ordre:`.
3. Les sections se rangent par `ordre:` croissant, puis par nom de fichier. Les sections déjà là vont de 10 en 10, et « Données et migrations » porte 1000 pour rester la dernière : prenez une valeur entre deux dizaines, là où la section doit paraître, pour ne pas croiser une autre branche.
4. Écrivez les liens comme dans la page elle-même, relatifs au dossier `installer/` et non à celui de la section.

Le build refuse une section sans `ordre:` numérique et une page anglaise dont les sections ne sont pas celles de la française dans le même ordre ; le contrôle des jumelles compare les pages assemblées.

### Les numéros de migration

Personne ne réserve de numéro de migration : une branche prend le suivant de `main` au moment de pousser. Si une autre PR le prend avant la fusion, le coordinateur lance le script de renumérotation sur la branche propre, juste avant de la fusionner :

```bash
git fetch origin
python3 .github/scripts/renumeroter-migration.py origin/main
```

Quand la plus basse migration ajoutée par la branche ne dépasse plus la plus haute de `main`, le script les renomme toutes, dans leur ordre, aux numéros qui suivent. Il reporte le changement **dans les seules lignes que la branche a ajoutées** : le nom du fichier et chaque `Vnnn`, les cibles Flyway des tests (`target("…")`, `fromVersion("…")`), l'empreinte, recalculée, et le tri du fichier d'empreintes. Une ligne de `main` qui parle de sa propre migration du même numéro n'est pas touchée. Il affiche chaque changement et laisse le tout à committer. Il refuse, sans rien écrire, un arbre modifié, deux migrations ajoutées sous un même numéro et une ligne ajoutée qui nomme le fichier de `main` de ce numéro. Son test, `.github/scripts/test_renumeroter_migration.py`, le joue sur un dépôt temporaire dans le job `frontend`.

## Où vivent les sources de la documentation

Les pages du site sont des fichiers Markdown rangés par partie dans `site/src/docs/`, que l'Eleventy de `site/` lit au build et publie sous `/docs/`. Trois parties, un dossier chacune :

| Dossier | Pour qui | Contenu |
| --- | --- | --- |
| `regles/` | l'organisateur, le responsable de stand, qui découvre le produit | des guides de tâche, sans nom de classe, de route ni de variable, qui nomment les écrans comme l'interface |
| `installer/` | qui héberge l'application | installer, configurer, sécuriser, sauvegarder, mettre à jour ; `variables-environnement.md` est l'inventaire complet, vérifié contre `application.properties` |
| `comprendre/` | qui développe ou audite | architecture, modèle, règles du solveur, API, MCP, import et export, construction |

Le `index.md` de chaque dossier est l'accueil de la partie. Son front matter (`titre`, `pour`, `docs:`) la nomme, dit à qui elle s'adresse et liste ses pages dans l'ordre de lecture : une page absente de la liste fait échouer le build du site et `DocumentationStructuralTest`. Ajouter un sujet, c'est ajouter une page à la bonne partie et son slug à cet index, pas un fichier à la racine ni une section de README.

Quelques règles d'écriture, tenues par le site et ses tests :

- **Une page parle à son lecteur** (« vous »), porte le nom de ce qu'il veut faire et ne dit rien de la documentation elle-même.
- **Un lien entre pages est relatif** (`../installer/oidc.md`). Un lien vers tout ce qui est hors du site (code, ADR, contrat OpenAPI, `AGENTS.md`) s'écrit avec le préfixe `repo:` suivi du chemin depuis la racine (`repo:AGENTS.md`) et devient un lien GitHub. Un lien vers une page inexistante fait échouer le build, une ancre introuvable aussi.
- **Une capture** est `![alt](/images/fr/x.webp "légende")`, dans `site/src/images/`, et **un diagramme** s'écrit de la même façon, avec `repo:docs/diagrammes/` devant le nom du fichier `.svg`.
- **Le contrat de l'API est l'OpenAPI publié**, rafraîchi par `npm run api-schema`, et `npm run api-types-check` échoue quand les modèles du frontend dérivent. Une nouvelle contrainte s'écrit dans `ConstraintCatalog`, que `GET /api/constraints` sert : les pages expliquent pourquoi une règle existe et ce qu'elle coûte, jamais la liste.
- **Les décisions d'architecture** sont une par fichier dans [`docs/decisions/`](repo:docs/decisions/README.md), nommées `NNNN-titre-court.md` et listées dans son index. Une décision répond au pourquoi, jamais ne se réécrit : révisée, elle reçoit un statut et un lien vers celle qui la remplace. Deux garde-fous la rendent publiable : aucune référence d'issue, et aucune donnée d'exploitation d'un client (des chiffres seulement s'ils se reproduisent sur un scénario versionné de `src/main/resources/scenarios/`).

Le dossier `docs/` à la racine ne garde que ce qui sert aux contributeurs et aux outils : `decisions/` (jamais publiées sur le site), `schema/` (le contrat OpenAPI et le schéma de scénario, générés), `diagrammes/` (sources PlantUML et SVG), `licences-tierces.md` (généré, [publié dans la partie d'installation](../installer/licences-tierces.md)) et [`memoire-du-projet.md`](repo:docs/memoire-du-projet.md), qui garde ce que l'historique portait : les mesures qui ont tranché un réglage du solveur, les pistes abandonnées et l'incident derrière chaque garde-fou.
