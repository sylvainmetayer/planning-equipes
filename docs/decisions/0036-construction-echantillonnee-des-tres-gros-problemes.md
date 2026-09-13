# 0036 — La construction d'un très gros problème est échantillonnée

- **Statut** : accepté, implémenté
- **Date** : septembre 2026
- **Portée** : solveur (heuristique de construction)

## Contexte

La première phase du solveur construit un plan complet en évaluant, pour chaque
siège, chaque animateur (`FIRST_FIT_DECREASING`). Son coût suit sièges ×
animateurs, et un peu plus : le score se renchérit à mesure que le plan se
remplit. Sur les scénarios extrêmes de test, à même graine :

- `extreme-02` — 6 480 sièges, 1 000 animateurs : construction en 182 s ;
- `extreme-09` — 41 370 sièges, 1 000 animateurs : construction en 50 min 48 s,
  plus que n'importe quel budget de production.

Le multithread qui la répartirait est réservé à l'édition Enterprise de
Timefold.

## Options envisagées

**(A) Arrêter la construction au premier candidat qui ne dégrade pas le dur**
(`pickEarlyType` `FIRST_FEASIBLE_SCORE_OR_NON_DETERIORATING_HARD`). Écarté :
un siège peut rester vide, et le laisser vide ne dégrade pas le score. La
construction se termine en 0,5 s sur un plan entièrement vide.

**(B) Échantillonner partout.** Écarté : sous une certaine taille, la
construction exacte se rembourse. Sur `extreme-02`, 300 s de solve finissent à
−5 521 medium avec elle, −5 938 échantillonnée — trois minutes de construction
pour un premier plan que la recherche locale ne rattrape pas.

**(C) Échantillonner au-delà d'un seuil.** Retenu.

## Décision

Au-delà de **15 millions de couples** sièges × (animateurs + la valeur vide),
`LargeProblemConstruction` remplace à la résolution la première phase par la même
phase — sièges les plus difficiles d'abord, filtre d'éligibilité compris — qui
n'évalue que **50 animateurs tirés au hasard** par siège. Mesuré sur
`extreme-09` : 0 dur en 2 min 37 s au lieu de 50 min 48 s.

Le seuil est l'endroit où la construction exacte dépasserait dix minutes, en
prolongeant les deux mesures ci-dessus. Toute édition réelle est loin dessous :
3 500 sièges et 153 animateurs font un demi-million de couples.

`solverConfig.xml` reste la seule description du solveur : la phase échantillonnée
est construite à partir de lui, pas dupliquée dans un second fichier.

## Conséquences

- Sous le seuil, rien ne change.
- Au-dessus, le premier plan est plus pauvre en medium (−53 938 contre −36 301
  en fin de construction sur `extreme-09`), et le temps gagné revient à la
  recherche locale.
- Le seuil est une extrapolation entre deux mesures, pas une mesure au seuil :
  un problème réel qui s'en approcherait mériterait d'être mesuré des deux côtés.
