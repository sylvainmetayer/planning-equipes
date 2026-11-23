# Page vitrine

Le site de présentation de Planning Équipes, en deux langues, construit avec
[Eleventy](https://www.11ty.dev/) et publié par Cloudflare Pages sur
`planning.sylvain.dev`. Les textes se modifient dans [Pages CMS](https://pagescms.org)
(un commit par enregistrement), le reste dans le code.

| Chemin | Rôle |
| --- | --- |
| `src/_data/pages/*.json` | **les textes**, un fichier par page : le français à la racine, l'anglais sous la clé `en`. C'est ce que Pages CMS édite |
| `src/_data/articles.js` | les dix articles du Code du travail cités, dans les deux langues. Pas dans le CMS : testés contre `ConstraintCatalog` |
| `src/_data/site.json` | nom, adresse, éditeur, jeton de la mesure d'audience. Pas dans le CMS |
| `src/_data/{copy,allPages,urls,build}.js`, `src/lib.js` | les textes lus par les gabarits, la liste des pages et de leurs adresses, la date de dernière modification (lue dans git) |
| `src/_includes/`, `src/pages.njk`, `src/404.njk` | les gabarits : l'accueil, les pages-réponses, le film, la mise en page commune |
| `src/schema.js`, `src/markdown.js` | les données structurées JSON-LD de chaque page, et son jumeau Markdown |
| `src/llms.njk`, `src/robots.njk`, `src/sitemap.njk` | `llms.txt`, `robots.txt`, `sitemap.xml`, tous trois générés |
| `src/assets/` | la feuille de style (`style.css`, `tokens.css`, `base.css`, minifiée et assemblée au build) et l'icône des liens sortants |
| `src/images/{fr,en}/`, `src/videos/{fr,en}/` | les captures et les vidéos de l'application, une série par langue |
| `src/_headers`, `wrangler.toml` | les en-têtes HTTP (CSP, HSTS…) et la configuration Cloudflare Pages (`pages_build_output_dir = "_site"`) |
| `scripts/tournage/` | **le tournage des captures et des vidéos**, en une commande (voir plus bas) |
| `scripts/og.html` | la source des images de partage `src/og-fr.png` et `src/og-en.png` |
| `.pages.yml` | la configuration de Pages CMS |
| `test/` | les tests : `npm test` construit le site puis les joue |

## Travailler dessus

```bash
cd site
mise run install   # ou npm install
mise run dev       # eleventy --serve, rechargement à chaud
mise run build     # génère _site/
mise run preview   # émulateur Cloudflare Pages sur :8788, en-têtes compris
npm test           # build + tests : articles, FR/EN, .pages.yml, liens, JSON-LD, hreflang
npm run check:spelling   # cspell, français et anglais
npm run validate         # html-validate sur _site/
PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium-browser npm run check:a11y   # pa11y-ci, WCAG 2 AA
```

La CI (`.github/workflows/site.yml`, filtrée sur `site/**` et sur
`ConstraintCatalog.java`) rejoue tout cela, plus la vérification des liens avec
lychee (hors ligne à chaque poussée, tous les liens le lundi).

## Le parti pris

- **Dans le prolongement de l'application, en plus sobre.** Le bleu azur de
  l'application est le seul accent ; la page est un papier froid, des filets
  d'un pixel, des touches de clavier en guise de boutons, et des captures réelles
  de l'application : ce qu'un organisateur voit est ce qu'il aura. Le thème
  s'appelle Cobalt (skill Hallmark, `modern-minimal`), il suit le thème clair ou
  sombre du visiteur. Les couleurs ne sont écrites qu'une fois, en variables
  (`tokens.css`).
- **Courte, et menée par les questions.** L'accueil répond dans l'ordre aux
  questions d'un organisateur : le planning est-il légal ? que se passe-t-il le
  jour J ? et pour les animateurs, les bénévoles ? comment travaille-t-on
  ensemble ? Chaque réponse longue a sa page (`/code-du-travail-mineurs/`,
  `/benevoles/`, `/comment-marche-le-calcul/`, `/questions/`), en français et en
  anglais. Une section qui s'allonge est une section à couper ou à déplacer.
- **Une phrase qui répond, puis le détail.** Chaque page commence par sa
  réponse en une ou deux phrases (c'est ce qu'un moteur de recherche ou un
  assistant cite), puis les sections, puis une invitation à écrire.

## Ce que la page s'interdit

- **Aucune dépendance côté visiteur, une seule requête externe.** Les polices
  (Familjen Grotesk et IBM Plex Sans, `@fontsource-variable`) sont servies par
  le site lui-même (`font-src 'self'`) ; pas de CDN, pas de script à elle. La
  seule exception est le beacon Cloudflare Web Analytics, sans cookie ni
  identifiant persistant, que la CSP de `src/_headers` est seule à autoriser ;
  le pied de page le dit. L'application, elle, n'en a pas et n'en aura pas.
  Il n'y a pas de JavaScript de la page : tout se lit sans.
- **Aucune capture qui ne soit pas fictive.** Les captures et les vidéos
  viennent de données inventées (le scénario `vitrine-fete-du-jeu-de-pierrefeuille.yaml`),
  et rien d'autre n'y entre : pas de données d'exploitation, pas de page de
  mentions légales d'une instance, pas de carte (son fond montre un lieu réel).
- **Aucun logo ni nom de client.** L'exemple qui court sur toute la page est la
  **Fête du jeu de Pierrefeuille**, une commune inventée, avec des lieux et des
  prénoms inventés. Ne jamais le remplacer par un événement réel, même pour
  illustrer.
- **Le Code du travail, et lui seul.** La page n'affirme aucune conformité à
  une convention collective. Les articles cités doivent correspondre, numéro
  pour numéro, au catalogue des contraintes (`ConstraintCatalog`, servi par
  `GET /api/constraints`) : `test/articles.test.mjs` lit `ConstraintCatalog.java`
  et refuse un article, dans `articles.js` comme dans un texte édité dans le CMS,
  que le catalogue ne cite pas. Une règle qui change de fondement change ici
  aussi.
- **Accessible** : lien d'évitement, repères de page, focus visible, contraste
  d'au moins 4,5:1 sur tout texte dans les deux thèmes, tableaux avec légende et
  en-têtes, vidéos transcrites, défilement doux coupé sous
  `prefers-reduced-motion`, et aucune barre de défilement horizontale de 320 px à
  l'écran large. `npm run check:a11y` (axe et HTML_CodeSniffer, WCAG 2 AA) et
  `html-validate` le gardent.

## Deux langues, une seule page

`src/_data/pages/<page>.json` porte le français à la racine et l'anglais sous la
clé `en`, **avec exactement les mêmes clés** : `test/copy.test.mjs` refuse une
clé qui manque d'un côté, et `test/pages-yml.test.mjs` refuse une clé que
`.pages.yml` ne déclare pas (Pages CMS peut perdre un champ non déclaré à
l'enregistrement). Un champ ajouté au JSON s'ajoute donc dans les trois endroits,
dans les deux langues.

L'anglais est une traduction, pas un mot-à-mot : il dit *staff* là où le français
dit « animateurs », reprend le vocabulaire de l'interface anglaise de
l'application (*Today*, *Send out*, *Schedule rules*), et cite les articles du
Code du travail sous la même forme (`L3131-1`). Les pages se déclarent l'une
l'autre (`hreflang`, `canonical` sur soi-même, `x-default`), et le sitemap les
répète. La typographie française (espace insécable avant `?` `!` `:` `;` et
autour de `«` `»`) est posée au build : on écrit avec des espaces ordinaires
dans le CMS.

Ce que Pages CMS ne permet pas de modifier, et c'est voulu : les articles
(`articles.js`), le jeton de mesure d'audience (`site.json`), les gabarits.

## Le référencement et les assistants

- **Titre et description** : le `<title>` commence par ce qu'on cherche
  (« logiciel de planning d'équipes pour festivals ») et finit par le nom ; le
  slogan reste dans le `<h1>` et dans `og:title`. 70 caractères au plus pour le
  titre, 160 pour la description (testé).
- **Aperçus** : Open Graph et `twitter:card`, avec une image par langue.
- **Données structurées** : un bloc JSON-LD par page, généré depuis les mêmes
  données que le HTML (`Person`, `WebSite`, `WebPage` ou `FAQPage`,
  `BreadcrumbList` ; sur l'accueil `SoftwareApplication` avec ses
  captures, `FAQPage` et un `VideoObject` par film). Les questions et réponses de
  `FAQPage` sont celles des `<details>` au mot près, puisque les deux viennent du
  même fichier. Pas d'`offers` : le tarif est sur devis.
- **Pour les assistants** : `/llms.txt` liste les pages, et chaque page a un
  jumeau Markdown (`<adresse>index.md`, annoncé par `<link rel="alternate"
  type="text/markdown">`). `robots.txt` autorise explicitement les robots d'IA
  (GPTBot, ClaudeBot, PerplexityBot…) : la page est faite pour être citée.
- **Dates** : `dateModified` et `lastmod` sont la date du dernier commit qui
  touche `site/`, lue dans git au build ; rien à écrire à la main.
- **Recherches visées** : festivals et événements, conformité au Code du travail
  et mineurs, bénévoles. Le calcul ne distingue pas un bénévole d'un autre
  animateur : la page ne dit rien de plus sur leur statut.

### Image de partage

Elle est faite à partir de `scripts/og.html` (même logo, même palette) et se
régénère depuis `site/` :

```bash
for l in fr en; do chromium-browser --headless=new --hide-scrollbars \
  --force-device-scale-factor=1 --window-size=1200,630 \
  --screenshot=src/og-$l.png "file://$PWD/scripts/og.html#$l"; done
```

Le slogan de l'image suit celui du `<h1>` de l'accueil ; changer l'un, c'est
régénérer l'autre.

## Les captures et les vidéos : le tournage

La section « Le logiciel en images » montre trois parcours filmés, sans le son,
et neuf écrans. Chaque langue a sa série, tournée avec l'interface dans cette
langue ; les noms de stands restent en français, ce sont des données. Une vidéo
n'a pas de lecture automatique, et sa légende dit ce qu'elle montre.

**Tout se retourne en une commande**, depuis la racine du dépôt :

```bash
site/scripts/tournage/tourner.sh              # image :main, résultat dans site/src
APP_IMAGE=ghcr.io/sylvainmetayer/planning-equipes:v2.1.0 site/scripts/tournage/tourner.sh
```

Elle demande Docker (avec `docker compose`), `ffmpeg`, ImageMagick avec WebP,
et rien d'autre (sur Debian 13 : `docker.io docker-cli docker-compose ffmpeg imagemagick webp`) : Playwright tourne dans son image officielle, il n'entre dans
aucune dépendance du dépôt. Le script :

1. monte la pile `app` du `docker-compose.yml` de la racine (PostgreSQL, Mailpit,
   l'application) avec `HORLOGE_SIMULEE_AUTORISEE=true`
   (`scripts/tournage/compose.yml`) ;
2. importe le scénario `src/main/resources/scenarios/vitrine-fete-du-jeu-de-pierrefeuille.yaml`
   (l'édition « Fête du jeu de Pierrefeuille 2027 », dérivée du barreau 16 de
   l'échelle), l'active, le résout et le publie, puis fige l'horloge au 10 août
   2027 à 15 h ;
3. joue les neuf écrans et les trois parcours dans les deux langues
   (`scripts/tournage/tourner.mjs`), le code d'accès de l'espace animateur étant lu
   dans Mailpit ;
4. encode : WebP pour les images (captures à 1440×900, 1600×900 pour la TV), vignettes
   de 800 px de large, MP4 H.264 sans piste son (1280×800, 412×800 pour le
   téléphone), le passage d'attente du calcul accéléré, et une affiche tirée de
   chaque film.

Le scénario est versionné avec l'application : il est dans la liste des scénarios
livrés, relu par les mêmes tests, et **ne change pas sans qu'on refasse les
captures**. Une vidéo qui change met à jour sa durée, sa transcription et sa
légende dans `src/_data/pages/accueil.json` (le poids du fichier, lui, se lit au
build). Une capture d'interface qui change ne se met pas à jour toute seule :
c'est le prix de cette section, à payer quand un écran montré change
franchement. Le tournage peut se faire sur une machine jetable (un serveur à
l'heure suffit) ; rien n'y reste.

## Marque

Un logotype et un favicon faits ici : trois barres empilées sur un carré azur,
la première en clair. Si une identité visuelle propre voit le jour, elle remplace
`src/favicon.svg` et le `<svg>` de l'en-tête (`src/_includes/base.njk`).

## Publication

La page est servie par **Cloudflare Pages** sur `planning.sylvain.dev`, comme
ref.sylvain.dev et www.sylvain.dev ; le projet, son domaine et le site Web
Analytics sont décrits dans le dépôt homelab. Le projet Pages est relié au dépôt
par l'application GitHub de Cloudflare avec le dossier racine `site`, la
**commande de build `npm run build`**, la sortie `_site` (relue dans
`wrangler.toml`) et une variable `NODE_VERSION` alignée sur `.node-version` ; ses
chemins surveillés se limitent à `site/**`, pour qu'un commit de l'application ne
redéploie rien. Chaque merge sur `main` qui touche `site/` publie ; une PR reçoit
un déploiement d'aperçu, que Pages sert en `noindex` et sans la mesure
d'audience (`ELEVENTY_ENV=preview`, posé par `eleventy.config.js` depuis
`CF_PAGES_BRANCH`).

Le jeton du beacon est dans `src/_data/site.json` : la valeur est celle de
`tofu output -raw web_analytics_token` côté homelab.

## Qualité (Opquast)

La page suit le référentiel Opquast « Qualité numérique » (v5). Ce qui en
découle et qu'il faut garder : un pied de page qui nomme l'éditeur, la licence,
l'hébergeur et la mesure d'audience, et qui mène aux mentions légales et à la
politique de confidentialité de sylvain.dev, aux nouveautés (les releases) et aux
issues ; les liens visités et les liens sortants (une icône) qui se distinguent ;
une abréviation expliquée à sa première occurrence ; des renvois d'au moins 24 px ;
une feuille d'impression, qui déplie la FAQ ; la durée des vidéos, le format et le
poids de leur lien de téléchargement ; des vignettes qui ne sont pas des images
redimensionnées par le navigateur ; les en-têtes de sécurité de `_headers` ;
`404.html`, `robots.txt` et `sitemap.xml`, et la feuille de style minifiée au
build.

Écarts assumés : la langue n'est pas choisie d'après `Accept-Language` (règle 135 :
il faudrait une Function, le sélecteur et `hreflang` suffisent) ; l'en-tête
`server: cloudflare` ne se retire pas (règle 213) ; les vidéos n'ont pas de
sous-titres, elles sont muettes et transcrites (règle 122) ;
`www.planning.sylvain.dev` n'existe pas, personne ne préfixe un sous-domaine
(règle 218).
