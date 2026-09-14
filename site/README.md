# Page vitrine

Une page statique, sans dépendance ni build : `index.html`, `style.css` et
`favicon.svg`. Pour la relire, ouvrez `index.html` dans un navigateur — il n'y
a rien à installer.

Le choix du zéro-outillage est délibéré. Un générateur de site (Astro, Hugo…)
se justifie à partir de plusieurs pages ou d'un contenu structuré ; pour une
page, il ajoute un `node_modules`, une configuration et une dépendance à tenir
à jour, sans rien apporter. Le contenu reste portable si le besoin change.

Aucune requête externe non plus : pas de police distante, pas de script, pas de
traqueur. Les illustrations sont des SVG inline qui prennent leurs couleurs
dans les mêmes variables CSS que le reste, et suivent donc le thème clair ou
sombre du visiteur. Ce sont des schémas dessinés, jamais des captures : une
capture d'écran de l'application exposerait des données d'exploitation, et
aucune ne doit figurer ici — ni volumétrie, ni nom de partenaire, ni chiffre
identifiable.

## Contenus provisoires

Les blocs marqués `data-todo` attendent un contenu réel. Ils portent un liseré
ocre pour être repérables en relecture ; retirer l'attribut une fois le bloc
validé.

Il n'en reste qu'un : **l'URL de la démonstration publique**, dans la section
« Voir avant de décider ». Tant qu'elle pointe sur `#contact`, le bouton ment
poliment. Le reste de la page est factuel et vérifiable dans le code de
l'application — les règles légales citées correspondent aux contraintes dures
listées dans [`docs/contraintes.md`](../docs/contraintes.md).

## Marque

La page porte un logotype typographique et un favicon géométrique faits ici :
trois barres empilées, qui valent pour un planning. C'est volontairement
minimal, et c'est surtout à nous. Le logo d'un client n'a rien à faire sur la
page commerciale du produit, et un onglet de navigateur sans favicon n'inspire
rien à personne. Si une identité visuelle propre voit le jour, elle remplace
`favicon.svg` et le `<svg class="mark__glyph">` de l'en-tête.

## Publication

`.github/workflows/pages.yml` publie ce dossier sur GitHub Pages, en
déclenchement **manuel** pour l'instant : Pages n'est pas disponible sur un
dépôt privé sans compte payant. À l'ouverture du dépôt public, ajouter le
déclencheur `push` documenté en tête du workflow et activer Pages avec
« Source: GitHub Actions ».
