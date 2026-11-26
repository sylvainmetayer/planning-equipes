# 0090 — Les réglages d'expert sont repliés écran par écran, ouvrables par l'adresse

- **Statut** : accepté
- **Date** : novembre 2026
- **Portée** : l'interface d'administration (`src/main/webui`) ; rien du serveur
- **Voisine de** : [0012](0012-etat-de-vue-dans-l-url.md) (l'état de vue dans l'URL), [0018](0018-ecrire-l-url-de-vue-sans-naviguer.md) (écrire l'URL sans naviguer)

## Contexte

Le menu était « un seul menu pour tout le monde », sans mode simple ni
avancé : un écran rare descendait dans son groupe, il n'était jamais caché.
La règle protégeait la découverte — rien d'inaccessible — mais elle mettait
au même niveau ce qu'un organisateur fait chaque jour et ce qu'un expert
règle une fois : les seuils légaux et les poids des règles à côté des
ajustements, le choix de la machine de calcul et la volumétrie à côté des
trois boutons de calcul, la sauvegarde, les webhooks et l'horloge simulée à
côté du nom de l'édition, les couches et la légende de la grille des horaires
avant la grille elle-même. Le menu, lui, est passé à treize entrées en
rangeant les écrans voisins en onglets ; restait la densité des écrans.

## Décision

Chaque écran replie ce qui relève de l'expert derrière un bouton
« Réglages avancés », **fermé par défaut**, dont l'état vit dans l'adresse
(`?avance=1`) comme tout état de vue : un lien peut l'ouvrir, un
rechargement le garde, et le bouton le referme.

- **Un repli par écran, pas un mode global.** Un mode « expert » de
  l'utilisateur aurait caché en bloc et partout, et aurait été une
  préférence de plus à retrouver ; le repli dit sur place qu'il y a
  davantage, là où on le cherche.
- **Rien n'est supprimé ni rendu injoignable.** Un onglet replié s'affiche
  quand l'adresse le nomme (`/regles?onglet=legal`, `?regle=…`,
  `/parametres?onglet=instance`) ; un filtre replié reste affiché tant qu'il
  filtre ; la palette Ctrl+K trouve toujours chaque onglet.
- **Le contenu replié n'est créé qu'une fois ouvert** (`shared/reglages-avances.ts`
  prend un `<ng-template>`), pour qu'un panneau technique ne lise rien tant
  que personne ne le regarde.

Repliés au moment de cette décision : les onglets Légal, Qualité et Calcul
de Règles du planning ; le lieu du calcul, la machine de calcul, la courbe du
score hors calcul et la volumétrie du Solveur ; les onglets Instance,
Historique et MCP de Paramètres ; les couches et la légende des Horaires des
stands ; les filtres Emplacement et Typologie du Planning.

## Complément : un bloc replié dans un onglet (Règles du planning)

Les onglets Légal et Qualité de Règles du planning ont fusionné en un onglet
« Règles » à trois blocs : les obligations légales (lecture seule, leurs seuils
se règlent dans Paramètres), la politique de l'organisation (les règles dont
l'édition choisit le niveau) et « Avancé », le reste, **replié** derrière le même
bouton. Le repli n'est plus un onglet mais un bloc du premier onglet de règles ;
le principe est inchangé : fermé par défaut, ouvert par `?avance=1`, et une règle
que l'adresse nomme (`?regle=`) ouvre le bloc qui la tient — une règle demandée
n'est jamais cachée. Le bloc d'une règle vient du catalogue (`legale`,
`niveauReglable`), jamais d'une liste de noms : une fusion de règles ne déplace
rien à l'écran.

## Conséquences

- La règle « un écran rare n'est jamais caché » est abandonnée au profit de
  « un réglage d'expert est replié, jamais injoignable ».
- Un nouvel écran qui expose un réglage d'expert le met derrière ce repli
  plutôt qu'au premier niveau ; le repli ne sert pas à cacher l'usage
  courant d'un écran trop chargé — un tel écran se découpe.
- Un test qui atteint un réglage replié ouvre d'abord le repli, ou passe par
  l'adresse.
