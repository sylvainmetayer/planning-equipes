# 0082 — La fenêtre de plateau part de la faisabilité ; la phase de faisabilité vise les doubles réservations

- **Statut** : accepté, implémenté
- **Date** : octobre 2026
- **Portée** : solveur (terminaison, première recherche locale)
- **Complète** : [0081](0081-le-plateau-se-juge-sur-son-gain-de-medium-a-l-echelle-du-plan.md) (où se pose la terminaison qu'elle décide), [0079](0079-la-phase-de-faisabilite-tire-sur-une-liste-par-pas-et-reconstruit-rarement.md) (les sélecteurs de la phase de faisabilité)

## Contexte

Sur un hôte à deux cœurs, une grille réelle de seize jours partie de zéro
n'atteignait pas la faisabilité en 900 s, et une fois relancée depuis son plan
s'arrêtait **dans la seconde** où elle devenait faisable, sans optimisation.
Deux causes, indépendantes.

**Le plateau.** 0081 a posé la règle de gain au niveau du solveur, gardée par
`bestScoreFeasible`. Timefold juge alors la fenêtre sur l'historique des
meilleurs scores **de toute la résolution** : une amélioration ne repousse le
plateau qu'en comparaison d'entrées de l'historique, et le parcours s'arrête à
la première entrée plus vieille que la fenêtre. Quand la phase de faisabilité
a mis plus de 180 s entre ses deux derniers gains de hard, l'arrêt est déjà
dû au moment où le plan devient faisable — la garde de faisabilité lève le
dernier obstacle, et la seconde phase ne tourne pas. Le plateau strict d'avant
0081 n'avait pas ce défaut : toute amélioration, la faisabilité comprise,
repoussait la fenêtre. Le rejeu de 0081 (`TerminationReplayTest`) faisait
déjà partir la fenêtre de la faisabilité : c'est la production qui s'en
écartait.

**La phase de faisabilité.** Une fois tous les sièges pourvus, les derniers
points de hard sont le plus souvent un animateur tenu sur deux sièges à la
même heure. Le change et le swap uniformes tirent l'un de ces deux sièges sur
quelques milliers environ une fois sur deux mille ; les sélecteurs dirigés de
0079 ne visent que les sièges vides, et le *ruin and recreate* ne ruine
qu'autour d'un trou. La durée de la phase dépendait alors beaucoup de la
trajectoire : sur la même grille, le seul ordre des sièges la faisait varier
du simple au double.

## Décision

1. **Une heuristique de construction vide sépare les deux recherches
   locales.** Le plan étant faisable, tous les sièges sont pourvus : elle ne
   fait aucun pas. Mais c'est une phase qui n'annonce aucun meilleur score, et
   Timefold remet la fenêtre de plateau à zéro à la fin d'une telle phase : la
   fenêtre part donc de la faisabilité. La terminaison reste sur le solveur ;
   le seuil, la fenêtre, la garde de faisabilité et le budget sont ceux de
   0081.
2. **La phase de faisabilité tire aussi sur les doubles réservations** : un
   change et un swap de poids 1, comme ceux des sièges vides, dont le
   sélecteur d'entités (liste par pas, 0079) ne garde que les sièges d'un
   animateur qui en tient un autre au même moment (`DoubleBookedPosteFilter`,
   mémoïsé une fois par pas comme `HoleNeighbourPosteFilter`).

Mesuré sur `festival-hivernal`, deux cœurs, jusqu'à la faisabilité seule
(`-Dbench.stopWhenFeasible=true`) :

| graine | avant | après |
| --- | --- | --- |
| 0 | 36,5 s (1 277 pas) | 30,2 s (401 pas) |
| 1 | 39,2 s (1 742 pas) | 36,4 s (1 020 pas) |

`festival-realiste-canicule` est faisable dès la construction : la phase n'y
tourne pas. Sur une grille réelle, la variante resserre surtout l'écart entre
graines.

## Alternatives écartées

- **Remonter le poids du *ruin and recreate*.** Il ne ruine qu'autour d'un
  trou (`HoleNeighbourPosteFilter`) : sans siège vide, il ne propose rien, et
  0079 a mesuré ce qu'il coûte quand on le tire souvent.
- **Revenir au plateau strict.** Corrige l'arrêt immédiat, mais rend à chaque
  résolution ses quinze minutes, ce que 0081 a mesuré et refusé.
- **Poser la terminaison sur la phase d'optimisation.** C'était la première
  version de ce correctif : sa fenêtre partait bien de la phase. Mais Timefold
  2.7 n'appelle pas `solvingStarted` sur une terminaison de phase, et celle à
  seuil de gain y initialise son historique : elle lève une
  `NullPointerException` à la première amélioration. Les tests de bout en bout
  l'ont trouvée ; `gamme-13` la reproduit en quelques secondes.
- **Une terminaison maison** qui remettrait l'historique à zéro à la
  faisabilité. Elle reposerait sur l'API interne de Timefold, que
  `TimefoldInternalApiStructuralTest` tient à l'inventaire près.

## Conséquences

- `solverConfig.xml` compte une phase de plus. `SolverConfigurationBudgetTest`
  exige qu'une heuristique de construction précède la dernière recherche
  locale, et résout `gamme-13` sous un seuil de gain : rouge si le plateau
  revient sur la phase.
- Le mécanisme dépend d'un comportement de Timefold
  (`UnimprovedTimeMillisSpentScoreDifferenceThresholdTermination.phaseEnded`),
  commenté dans son code mais pas documenté : à revérifier à chaque montée de
  version, comme le reste de l'inventaire.
- Les deux étapes de 0067 n'en changent pas : la première s'arrête à la
  faisabilité, avant la phase vide ; la seconde la traverse comme une
  résolution ordinaire.
- `DoubleBookedPosteFilter` dépend de `core.impl` (l'interface
  `SelectionFilter`) : il entre à l'inventaire de
  `TimefoldInternalApiStructuralTest`, avec `DoubleBookedPosteFilterTest`.
