# 0035 — Un mineur seul coûte le forfait des exclusions d'éligibilité

- **Statut** : accepté, implémenté
- **Date** : septembre 2026
- **Portée** : solveur (score)
- **Complète** : [0034](0034-exclusions-eligibilite-plus-lourdes-que-tout.md)

## Contexte

[0034](0034-exclusions-eligibilite-plus-lourdes-que-tout.md) a donné un forfait
de 10 000 points durs aux six règles qui disent qui ne peut jamais tenir un
siège, pour qu'un plan infaisable laisse un siège vide plutôt qu'une affectation
illégale. Elle les a prises dans les motifs du filtre d'éligibilité — les
exclusions qui se décident sur le seul couple (siège, animateur).

`mineurNecessiteEncadrementMajeur` n'en fait pas partie : elle dépend de qui
d'autre tient le stand, aucun filtre ne peut la décider. Elle est pourtant de la
même nature — un mineur ne doit jamais se retrouver seul — et elle gardait le
défaut que 0034 corrigeait ailleurs. Sur une équipe composée uniquement de
mineurs (`extreme-12`), laisser un mineur seul coûtait exactement un siège vide :
seul l'ordre de la recherche départageait.

## Décision

Un mineur sans majeur à ses côtés, sur le même stand et le même créneau, coûte
le même forfait `ExclusionEligibilite.FORFAIT`. `ExclusionEligibilite.CONTRAINTES`
la compte avec les motifs du filtre.

## Conséquences

- Un plan qui manque de majeurs laisse le siège vide ; il ne place plus un mineur
  seul pour le pourvoir.
- Le score dur de cette règle change d'échelle, comme en 0034.
