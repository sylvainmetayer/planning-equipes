# 0079 — La phase de faisabilité tire ses sièges sur une liste par pas, et reconstruit rarement pour toute édition

- **Statut** : accepté, implémenté
- **Date** : octobre 2026
- **Portée** : solveur (première recherche locale, celle qui atteint la faisabilité)
- **Révise** : [0049](0049-la-regle-dure-des-jours-d-affilee-se-cherche-par-jours-entiers.md) (le poids du *ruin and recreate*, réservé aux éditions tenues à six jours, devient celui de toutes)
- **Complète** : [0025](0025-stabilite-du-plan-publie.md), [0067](0067-la-faisabilite-avant-la-stabilite-apres-publication.md)

## Contexte

Un profil d'une résolution réelle (édition de seize jours, 4 057 sièges,
153 animateurs, jouée sous les règles par défaut) a montré que la phase de
faisabilité passait un cinquième de son temps **à tirer des entités**, pas à
les évaluer : `RandomSupport.boundedNextInt` seul pesait 9 % des échantillons,
les filtres d'entités 7 %, l'itérateur de filtrage *just in time* 3 %.

La cause est la façon dont Timefold applique un filtre à un sélecteur
d'entités tiré au hasard : il tire des entités jusqu'à en trouver une que le
filtre accepte, et quand aucune ne passe il tire `entityCount × 10` fois
(40 570 ici) avant d'abandonner. Or la phase de faisabilité porte trois
sélecteurs de ce type — deux restreints aux sièges vides
(`UnassignedPosteFilter`), un restreint aux voisins d'un trou
(`HoleNeighbourPosteFilter`) — et son état ordinaire est « tous les sièges
pourvus, plan encore infaisable sur une sur-affectation » : à chaque pas, les
trois sélecteurs repaient les 40 000 tirages pour rien. Le mémo que 0025
avait ajouté au filtre des trous rendait le *refus* bon marché, pas le
*tirage*.

Le second poste était connu depuis 0049 : le *ruin and recreate* reconstruit
ses sièges par une heuristique de construction qui évalue chaque animateur,
soit ~150 ms l'évaluation contre 0,3 ms pour tout autre mouvement. Tiré une
fois sur six, il occupait presque toute la phase. 0049 l'avait ramené à 0,02
pour les seules éditions tenues à la règle dure des jours d'affilée
(`HardRunCapSearch`), en écartant l'option « le baisser partout » parce que
les chaînes à l'heure du trou de 0025 — une résolution après publication —
en dépendaient. Depuis, 0067 a changé cette donnée : une résolution après
publication cherche d'abord la faisabilité **sans** la stabilité du plan
publié, et les maillons de ces chaînes, neutres en dur, ne coûtent plus de
medium pendant cette phase ; le *change* et le *swap* les parcourent eux-mêmes.

## Décision

1. **Les trois sélecteurs filtrés sont mis en cache par pas**
   (`cacheType STEP`, `selectionOrder RANDOM`) : le filtre passe une fois sur
   les sièges au début de chaque pas, le tirage se fait sur la courte liste
   obtenue — et une liste vide dit tout de suite qu'il n'y a rien à tirer.
2. **Le *ruin and recreate* de la phase de faisabilité pèse 0,02 pour toute
   édition**, tenue ou non à la règle dure. `HardRunCapSearch` disparaît :
   la configuration livrée est la configuration de toutes les éditions.

## Mesures

Même graine, phase de faisabilité seule (`solveUntilFeasible`), cette machine
(8 cœurs), runs séquentiels, les deux grilles jouées sous les règles par
défaut — donc le *ruin and recreate* à poids 1 avant cette décision.

| Scénario | Avant | Sélecteurs en cache | + reconstruction rare |
| --- | --- | --- | --- |
| édition réelle de seize jours (4 057 sièges ; non versionnée) | 0 dur à 138 s, 1 177 évaluations/s | **76 s**, 3 092 évaluations/s | 64 s |
| `festival-hivernal` (3 438 sièges) | 0 dur à 142 s, 956 évaluations/s | 77 s, 1 069 évaluations/s | **42 s** |
| `scenario-complet`, `gamme-22`, `gamme-25` | faisables dès la construction | inchangés | inchangés |

Le premier plan faisable est un peu moins bon en medium sur `festival-hivernal`
(−37 220 contre −36 485) : la seconde phase part de là et le rattrape ; c'est
le temps jusqu'à l'utilisable qui compte ici.

## Alternatives écartées

- **Retirer le *ruin and recreate*.** Plus rapide encore sur l'édition
  mesurée (0049 l'avait noté), mais la chaîne à l'heure du trou n'a alors
  aucun mouvement qui la joue d'un coup ; à 0,02 elle reste tirée une fois
  sur 250 environ.
- **Rendre la reconstruction moins chère** par une plage de valeurs portée
  par l'entité (les seuls animateurs éligibles au siège). Sur les éditions
  réelles presque tout le monde est éligible — pas de mineur, une
  indisponibilité — et le gain serait nul ; c'est un changement de modèle que
  rien ne justifie aujourd'hui.

## Conséquences

- `HardRunCapSearch` et son test sont retirés ; `SolverConfiguration` n'a
  plus de fabrique propre aux éditions tenues à la règle dure.
- 0068 garde sa condition (« la règle dure est allumée »), lue directement sur
  `WeekRelocationMoveIteratorFactory.hardRunCap`.
- La phase de faisabilité se mesure avec `SolverBenchTest`
  (`-Dbench.stopWhenFeasible=true`), qui a produit les chiffres ci-dessus.
