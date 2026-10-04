# 0078 — Échanger des journées entières plutôt que des *pillar moves*

- **Statut** : accepté, implémenté
- **Date** : octobre 2026
- **Portée** : solveur (seconde recherche locale)
- **Complète** : [0049](0049-la-regle-dure-des-jours-d-affilee-se-cherche-par-jours-entiers.md)
  (la relocalisation à travers la semaine, autre mouvement construit par
  l'application)

## Contexte

Plusieurs règles medium comptent par personne et par jour : stands premium,
typologies, emplacements distincts. Une journée qui gagnerait à changer de
mains ne peut le faire qu'un siège à la fois avec le *change* et le *swap*, et
le premier siège déplacé dégrade le score avant que la journée entière ait
bougé : l'acceptation tardive le refuse le plus souvent.

Timefold propose pour cela les *pillar moves* : un bloc d'entités qui portent
la même valeur, déplacé ou échangé d'un coup. Ils ont été mesurés sur la
seconde phase, Timefold 2.7.0, sur les neuf scénarios versionnés utilisés
pour le réglage du solveur (`scenario-complet`, `scenario-continu`,
`festival-realiste-canicule`, `festival-hivernal`, `gamme-20` à `gamme-25`) :
phase 1 jouée une fois par scénario, puis la seconde phase seule depuis ce
même plan, 90 s, graines 0, 1 et 2.

- Le `pillarChangeMoveSelector`, sous-piliers de deux à trois postes d'une même
  date filtrés sur l'éligibilité, **perd du medium sur sept scénarios sur
  neuf**, à chaque graine. Un animateur tient le plus souvent un seul poste par
  jour : le filtre écarte 66 à 92 % des blocs tirés.
- Le `pillarSwapMoveSelector`, blocs d'une même date, n'accepte que 0,03 % des
  paires tirées sur canicule et **divise la vitesse par neuf** : deux blocs
  tirés au hasard ne sont presque jamais éligibles dans les deux sens.

## Décision

Les *pillar moves* ne sont pas retenus. La seconde phase tire à la place, à
poids 2 face au *change* et au *swap*, un **échange de journées** construit par
l'application (`DaySwapMoveIteratorFactory`) : les postes déplaçables d'un
animateur à une date passent à un collègue qui travaille ce jour-là, et les
siens à lui, en un seul mouvement (le *pillar swap* de Timefold, construit sur
un couple choisi plutôt que tiré).

Le couple est **choisi** : un bloc (animateur, date) tiré uniformément, puis un
collègue du même jour tiré uniformément parmi ceux qui peuvent échanger — chaque
poste éligible pour son receveur, aucun poste reçu sur l'heure d'un poste figé
que le receveur garde, la journée d'un mineur sous le plafond quotidien tel que
`dureeQuotidienneMaxMineur` le mesure. Ces vérifications n'écartent que des
échanges que le score refuserait de toute façon.

Deux journées faites des mêmes postes s'échangent **quand même**. Le score ne
voit pas la différence, et les refuser paraissait n'économiser qu'une
évaluation inutile ; mesuré, cela coûte du medium : -30 852 contre -30 293 sur
`festival-hivernal` (moyenne des graines 0, 1 et 2). L'acceptation tardive
prend ce mouvement neutre comme un pas, et ce pas fait avancer sa fenêtre.

## Conséquences

- Le medium progresse **à chaque graine de chaque scénario**, de +965
  (`gamme-25`) à +7 135 (`scenario-complet`) ; le détail par graine est dans
  le commentaire de la seconde phase de `solverConfig.xml`, la synthèse dans
  `docs/developpement.md`.
- Le prix est de 14 à 32 % d'évaluations par seconde en moins, et de la moitié
  des tirages retirée au *change* et au *swap*. Le soft, classé sous le medium,
  recule sur quatre scénarios.
- Chacun garde les dates qu'il travaille, donc son nombre de jours, sa semaine
  et sa série ; les heures de la journée, le total de la semaine et le repos
  autour changent, et le score en juge comme de tout mouvement.
- `DaySwapMoveIteratorFactory` dépend de `core.impl`, comme
  `WeekRelocationMoveIteratorFactory` : il entre à l'inventaire de
  `TimefoldInternalApiStructuralTest`, et une montée de Timefold qui cesserait
  de le tirer se verrait dans `-Pscenario-tests`.
- Rouvrir la question des *pillar moves* demande de refaire la mesure : une
  version de Timefold qui filtrerait les piliers à la construction changerait
  le second constat, pas le premier.
