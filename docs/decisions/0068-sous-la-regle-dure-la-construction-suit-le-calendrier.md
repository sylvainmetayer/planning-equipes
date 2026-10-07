# 0068 — Sous la règle dure des jours d'affilée, la construction suit le calendrier

- **Statut** : accepté, implémenté
- **Date** : septembre 2026
- **Portée** : solveur (ordre de l'heuristique de construction)
- **Prolonge** : [0049](0049-la-regle-dure-des-jours-d-affilee-se-cherche-par-jours-entiers.md)

## Contexte

L'heuristique de construction (`FIRST_FIT_DECREASING`) place les sièges du plus
difficile au plus facile, la difficulté étant le nombre d'animateurs éligibles
(`PosteAffectationDifficultyComparatorFactory`). C'est le bon ordre quand ce
qui manque, ce sont des compétences : les sièges que peu de gens savent tenir
passent avant que ces gens soient pris ailleurs.

Sous `maxJoursConsecutifsTravaillesDur`, ce qui manque est autre chose :
des **jours-personnes d'affilée** ([0049](0049-la-regle-dure-des-jours-d-affilee-se-cherche-par-jours-entiers.md)).
Et l'ordre par rareté place en dernier les sièges que presque tout le monde
peut tenir — le montage, le démontage — une fois que la plupart des gens
travaillent déjà les jours qui suivent. Un jour de montage ajouté devant leur
série la ferait dépasser le seuil : la construction ne casse pas la règle,
elle laisse le siège vide. La recherche locale passe ensuite l'essentiel de
son temps à construire, par la relocalisation à travers la semaine, les
chaînes de jours qui remplissent ces sièges.

Sur une édition de seize jours tenue à six (non versionnée, chiffres hors du
dépôt), la construction ne cassait aucune règle dure mais laissait des
sièges vides, pour l'essentiel sur un jour de montage ; le temps jusqu'au zéro
dur variait fortement d'une graine à l'autre.

## Options envisagées

**(A) Chercher la faisabilité sans les règles medium et soft**, comme
[0067](0067-la-faisabilite-avant-la-stabilite-apres-publication.md) le fait
pour la seule stabilité : une première étape où elles pèsent zéro. Écarté,
mesuré plus lent : la construction va plus vite, mais finit avec plus de
sièges vides, et la recherche n'évalue pas plus de mouvements par seconde. Les
règles de qualité — l'équilibre de la charge en premier — orientent la
recherche vers la faisabilité ; ce ne sont pas elles qui coûtent.

**(B) Construire par échantillon** ([0036](0036-construction-echantillonnee-des-tres-gros-problemes.md),
cinquante ou vingt candidats tirés par siège). Écarté : plus lent jusqu'au
zéro dur et moins bon à la fin, à cette taille.

**(C) Partir d'un plan vide**, la recherche remplissant tout. Écarté : le zéro
dur arrive deux fois plus tôt, mais le plan final reste nettement moins bon en
medium, la construction complète donnant un niveau medium que la recherche ne
retrouve pas dans le budget.

**(D) Changer le poids des mouvements** de la phase de faisabilité
(relocalisation à travers la semaine, *ruin and recreate*). Écarté : le temps
jusqu'au zéro dur varie sans tendance d'un poids à l'autre ; aucun réglage ne
gagne de façon stable.

**(E) Construire jour par jour, sous la règle dure seulement.** Retenu.

## Décision

**(E).** Quand `maxJoursConsecutifsTravaillesDur` est allumée pour l'édition
(la condition lue par `WeekRelocationMoveIteratorFactory.hardRunCap`), la
construction place les
sièges **par date croissante**, puis par rareté à l'intérieur d'un même jour.
Les séries se construisent dans l'ordre où la règle les compte : un jour déjà
long bloque le suivant, pas le précédent. Les sièges que la construction
laisse vides sont alors répartis sur les jours, là où un changement ou un
échange les remplit, au lieu d'être rassemblés sur un montage qu'aucune
chaîne courte n'atteint.

Toute autre édition garde l'ordre par rareté, inchangé.

## Mesures

Protocole : même graine (celle de `solverConfig.xml`) sauf mention, problème
construit comme en production (horaires résolus), seuil à six,
`maxJoursConsecutifsTravaillesDur` allumée ; le calcul du repos hebdomadaire
en minutes, qui ne change aucune trajectoire, est déjà en place des deux côtés.

**`festival-realiste-canicule`** (versionné, grille à l'aise) :

| Construction | Zéro dur atteint | Medium à ce moment |
|---|---|---|
| par rareté | 16,0 s | `-21415medium` |
| **par date** | **14,3 s** | **`-17805medium`** |

Les deux atteignent le zéro dur dès la construction ; celle par date y arrive
avec un meilleur niveau medium.

**`festival-hivernal`** (versionné, grille qui n'atteint zéro dur à six jours
dans aucune mesure), score dur à 300 s, graines 0, 1 et 2 :

| Construction | Graine 0 | Graine 1 | Graine 2 |
|---|---|---|---|
| par rareté | `-11hard` | `-10hard` | `-13hard` |
| par date | `-15hard` | `-17hard` | `-15hard` |

À 600 s, graine 0 : `-10hard/-31355medium` par rareté, `-12hard/-28185medium`
par date.

**L'édition de seize jours** (non versionnée, chiffres hors du dépôt) : sur
plusieurs graines, le zéro dur arrive nettement plus tôt, et d'un temps
presque constant d'une graine à l'autre. Le medium au moment de la
faisabilité est moins bon, et le polissage ne rattrape pas tout l'écart quand
le budget lui laisse plusieurs minutes.

## Conséquences

- Une édition tenue à six jours dont la grille est tenable atteint le zéro dur
  plus tôt et plus régulièrement ; c'est le cas pour lequel la règle dure est
  allumée, et celui où le budget se joue.
- Une édition dont la grille **ne tient pas** à six jours finit avec quelques
  sièges vides de plus : sur `festival-hivernal`, quatre à sept à 300 s. Le
  plan reste infaisable dans les deux cas ; l'écran Problèmes nomme les jours
  où ils manquent, et c'est la grille ou le seuil qui se discute, pas l'ordre
  de construction.
- Le niveau medium d'une édition tenable peut être moins bon à budget égal
  quand ce budget laisse beaucoup de temps au polissage ; il est meilleur
  quand le budget s'arrête peu après la faisabilité. Le choix est fait pour le
  second cas : un plan faisable avant un plan poli.
- `PosteAffectationDifficultyComparatorFactoryTest` tient les deux ordres : la
  rareté seule sans la règle, la date puis la rareté avec elle.
