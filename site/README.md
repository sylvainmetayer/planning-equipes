# Page vitrine

Une page statique en deux langues, sans dépendance ni build :

| Fichier | Rôle |
| --- | --- |
| `index.html` | la page en français |
| `en/index.html` | la même page en anglais |
| `style.css` | la feuille de style, partagée par les deux |
| `favicon.svg` | l'icône d'onglet, reprise dans l'en-tête des deux pages |

Pour la relire, ouvrez `index.html` dans un navigateur — il n'y a rien à
installer. Le sélecteur de langue pointe sur des dossiers (`en/`, `../`) :
servi par GitHub Pages ou par n'importe quel serveur, même sous un
sous-chemin, il ouvre l'autre page ; ouvert en `file://`, il montre le contenu
du dossier. Tous les liens internes sont relatifs.

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

- **Aucune dépendance, aucune requête externe** : pas de police distante (les
  piles ne nomment que des polices système), pas de script — la page n'en a
  aucun et n'en a pas besoin —, pas de CDN, pas de traqueur.
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

`.github/workflows/pages.yml` publie ce dossier sur GitHub Pages, en
déclenchement **manuel** : on publie quand on le décide, pas à chaque commit.
Pages se règle avec « Source: GitHub Actions » dans les réglages du dépôt.
