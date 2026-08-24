# Page vitrine

Une page statique, sans dépendance ni build : `index.html` et `style.css`.
Pour la relire, ouvrez `index.html` dans un navigateur — il n'y a rien à
installer.

Le choix du zéro-outillage est délibéré. Un générateur de site (Astro, Hugo…)
se justifie à partir de plusieurs pages ou d'un contenu structuré ; pour une
page, il ajoute un `node_modules`, une configuration et une dépendance à tenir
à jour, sans rien apporter. Le contenu reste portable si le besoin change.

## Contenus provisoires

Les blocs marqués `data-todo` attendent une décision de positionnement ou un
contenu réel — lien de la démonstration, captures d'écran, grille tarifaire.
Ils portent un liseré ocre pour être repérables en relecture. Retirer
l'attribut une fois le bloc validé.

La page n'a ni marque ni favicon, et c'est délibéré : le produit n'a pas
d'identité visuelle propre, et le logo d'un client n'en tient pas lieu sur la
page commerciale du produit. Le balisage indique où les rétablir.

Le reste de la page est factuel et vérifiable dans le code de l'application.
Aucune donnée d'exploitation d'un client ne doit y figurer : ni volumétrie, ni
nom de partenaire, ni chiffre identifiable.

## Publication

`.github/workflows/pages.yml` publie ce dossier sur GitHub Pages, en
déclenchement **manuel** pour l'instant : Pages n'est pas disponible sur un
dépôt privé sans compte payant. À l'ouverture du dépôt public, ajouter le
déclencheur `push` documenté en tête du workflow et activer Pages avec
« Source: GitHub Actions ».
