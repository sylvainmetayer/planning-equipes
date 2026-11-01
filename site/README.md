# Page vitrine

Une page statique en deux langues, sans dépendance ni build :

| Fichier | Rôle |
| --- | --- |
| `index.html` | la page en français |
| `en/index.html` | la même page en anglais |
| `style.css` | la feuille de style, partagée par les deux |
| `favicon.svg` | l'icône d'onglet, reprise dans l'en-tête des deux pages |
| `404.html` | la page d'erreur, bilingue, servie par Cloudflare Pages avec le statut 404 |
| `_headers` | les en-têtes HTTP (CSP, HSTS…), lus par Cloudflare Pages |
| `wrangler.toml` | la configuration du projet Cloudflare Pages (`pages_build_output_dir = "."`) |
| `robots.txt`, `sitemap.xml` | les instructions aux robots et la liste des deux pages |
| `og-fr.png`, `og-en.png` | l'image d'aperçu des réseaux sociaux (1200×630), une par langue |

Pour la relire, ouvrez `index.html` dans un navigateur — il n'y a rien à
installer. Le sélecteur de langue pointe sur des dossiers (`en/`, `../`) :
servi par n'importe quel serveur, il ouvre l'autre page ; ouvert en `file://`,
il montre le contenu du dossier. Les liens internes des deux pages sont
relatifs ; seuls `canonical`, `hreflang`, `robots.txt` et `sitemap.xml`
nomment `https://planning.sylvain.dev`, et `404.html`, servie à toute
profondeur, appelle ses ressources par la racine (`/style.css`).

Pour relire comme en production, en-têtes et page 404 compris :

```bash
npx -y wrangler@4 pages dev site --port 8788
```

## Le parti pris

- **Dans le prolongement de l'application.** La palette est celle de l'appli :
  le bleu azur de son thème Material, et les huit teintes des typologies de jeu
  (`src/main/webui/src/styles/typologie-colors.css`), en aplat pastel avec
  leur encre lisible, redéfinies pour le thème sombre. Un prospect qui ouvre
  la démonstration reconnaît ce qu'il vient de voir. Les couleurs ne sont
  écrites qu'une fois, en variables sur `:root`.
- **Courte, et menée par les questions.** La grille d'un samedi à Combelune
  ouvre la page ; vient ensuite l'écran **Aujourd'hui**, dessiné et annoté de
  cinq renvois d'une ligne — c'est la pièce centrale ; puis les questions
  qu'un organisateur se pose (légal ? animateurs ? nos outils ?), chacune en
  une phrase et un visuel ; la façon de travailler ensemble, trois questions
  pliées, et la démonstration. Une section qui s'allonge est une section à
  couper.

## Ce que la page s'interdit

- **Aucune dépendance, une seule requête externe** : pas de police distante
  (les piles ne nomment que des polices système), pas de script à elle, pas de
  CDN. La seule exception est le beacon Cloudflare Web Analytics, sans cookie
  ni identifiant persistant, que la CSP de `_headers` est seule à autoriser ;
  le pied de page le dit. L'application, elle, n'en a pas et n'en aura pas.
- **Aucune capture d'écran** de l'application : une capture exposerait des
  données d'exploitation. Les écrans sont redessinés en HTML et en SVG inline,
  qui suivent le thème clair ou sombre du visiteur (`prefers-color-scheme`).
- **Aucun logo ni nom de client.** L'exemple qui court sur toute la page est
  le **Festival de Combelune**, une commune inventée, avec des lieux et des
  prénoms inventés. Ne jamais le remplacer par un festival réel, même pour
  illustrer.
- **Le Code du travail, et lui seul.** La page n'affirme aucune conformité à
  une convention collective. Les articles cités — puces de « Le planning
  est-il légal ? » et renvois des postes refusés dans la grille — doivent
  correspondre, numéro pour numéro, au catalogue des contraintes
  (`ConstraintCatalog`, servi par `GET /api/constraints`) : une règle qui
  change de fondement change ici aussi.
- **Accessible** : lien d'évitement, repères de page, focus visible, contraste
  d'au moins 4,5:1 sur tout texte dans les deux thèmes, grille en vrai tableau
  (légende, en-têtes), illustrations décoratives en `aria-hidden` doublées
  d'un texte qui dit la même chose, défilement doux coupé sous
  `prefers-reduced-motion`, et aucune barre de défilement horizontale de
  320 px à l'écran large — seule la grille défile dans son propre cadre.

## Deux langues, une seule page

`index.html` et `en/index.html` disent la même chose, section par section, et
**changent ensemble**, dans le même commit. L'anglais est une traduction, pas
un mot-à-mot : il dit *staff* là où le français dit « animateurs », reprend le
vocabulaire de l'interface anglaise de l'appli (*Today*, *Show on the TV*,
*Who can hold this seat?*), et cite les articles sous la forme « French Labour
Code, art. L3131-1 ». Les deux pages se déclarent l'une l'autre
(`<link rel="alternate" hreflang>`) et appellent `style.css` et `favicon.svg`,
la page anglaise par `../`.

## La démonstration

L'adresse de l'instance de démonstration et ses identifiants sont donnés en
clair, à trois endroits de chaque page : le bouton de la barre du haut (qui
mène au bloc d'accès), le bouton et la ligne d'identifiants du haut de page,
et le bloc d'accès complet en bas. Ce bloc dit tout : l'adresse, `admin` /
`demo`, les courriels de l'application lisibles sur `/mail`, des données
fictives remises à zéro chaque nuit, une horloge arrêtée sur un jour de
festival, un calcul plafonné à 60 secondes et à 12 par heure. Si l'un de ces
faits change, ce sont les deux pages qu'il faut mettre à jour.

## Marque

Un logotype et un favicon faits ici : trois barres empilées sur un carré azur,
la première dans la teinte jaune des typologies. Si une identité visuelle
propre voit le jour, elle remplace `favicon.svg` et le `<svg>` de l'en-tête
des deux pages.

## Publication

La page est servie par **Cloudflare Pages** sur `planning.sylvain.dev`, comme
ref.sylvain.dev et www.sylvain.dev ; le projet, son domaine et le site Web
Analytics sont décrits dans le dépôt homelab. Le projet Pages est relié au
dépôt par l'application GitHub de Cloudflare, avec le dossier racine `site`,
**aucune commande de build** et la sortie `.` (relue dans `wrangler.toml`) ; ses
chemins surveillés se limitent à `site/**`, pour qu'un commit de l'application
ne redéploie rien. Chaque merge sur `main` qui touche `site/` publie ; une PR
reçoit un déploiement d'aperçu, que Pages sert en `noindex`.

Le jeton du beacon est écrit en dur dans `index.html` et `en/index.html` (il
n'y a pas de build pour l'injecter) : la valeur est celle de
`tofu output -raw web_analytics_token` côté homelab, la même dans les deux pages.

## Référencement

- **Titre et description** : le `<title>` commence par ce qu'on cherche (« logiciel
  de planning d'équipes pour festivals ») et finit par le nom ; le slogan reste
  dans le `<h1>` et dans `og:title`. Moins de 65 caractères pour le titre, de
  160 pour la description, sinon le moteur les coupe.
- **Aperçus** : Open Graph et `twitter:card`, avec une image par langue.
  `og:description` reprend la `meta description` mot pour mot.
- **Données structurées** : un bloc JSON-LD par page (`Person`,
  `SoftwareApplication`, `FAQPage`), que la CSP n'a pas à autoriser puisqu'il
  ne s'exécute pas. Les questions et réponses de `FAQPage` sont celles des
  `<details>` **au mot près** : une réponse qui change dans la page change là
  aussi. Pas d'`offers` : le tarif est sur devis.
- **Langues** : `canonical` sur soi-même, `hreflang` croisés, et `sitemap.xml`
  qui les répète.

### Image de partage

Elle est faite à partir de `scripts/vitrine-og.html` (même logo, même palette)
et se régénère depuis la racine du dépôt :

```bash
for l in fr en; do chromium-browser --headless=new --hide-scrollbars \
  --force-device-scale-factor=1 --window-size=1200,630 \
  --screenshot=site/og-$l.png "file://$PWD/scripts/vitrine-og.html#$l"; done
```

Le slogan de l'image suit celui du `<h1>` ; changer l'un, c'est régénérer l'autre.

## Qualité (Opquast)

La page suit le référentiel Opquast « Qualité numérique » (v5). Ce qui en
découle et qu'il faut garder : un pied de page qui nomme l'éditeur, la licence,
l'hébergeur et la mesure d'audience, et qui mène aux mentions légales et à la
politique de confidentialité de sylvain.dev, aux nouveautés (les releases) et
aux issues ; les liens visités et les liens sortants (flèche ↗) qui se
distinguent ; une abréviation expliquée à sa première occurrence (`<abbr
title>`) ; des renvois d'au moins 24 px ; une feuille d'impression ; les
en-têtes de sécurité de `_headers` ; `404.html`, `robots.txt` et
`sitemap.xml`.

Écarts assumés : la feuille de style n'est pas minifiée (règle 229 : pas de
build, et la compression de Cloudflare fait l'essentiel) ; la langue n'est pas
choisie d'après `Accept-Language` (règle 135 : il faudrait une Function, le
sélecteur et `hreflang` suffisent) ; une page unique n'a pas de plan du site
(règle 171) ; l'en-tête `server: cloudflare` ne se retire pas (règle 213).
