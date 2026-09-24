# 0050 — La faisabilité avant la stabilité, après publication

- **Statut** : accepté, implémenté
- **Date** : septembre 2026
- **Portée** : solveur (déroulé d'une résolution quand un plan est publié et que la règle dure des jours d'affilée est allumée)
- **Prolonge** : [0049](0049-la-regle-dure-des-jours-d-affilee-se-cherche-par-jours-entiers.md)
- **Complète** : [0025](0025-stabilite-du-plan-publie.md)

## Contexte

Les mesures de [0049](0049-la-regle-dure-des-jours-d-affilee-se-cherche-par-jours-entiers.md)
ont été faites sur un import, **sans plan publié**. Le cas qu'elles ne
couvraient pas est pourtant le chemin ordinaire d'une organisation qui
resserre : l'édition est résolue avec le seuil par défaut, publiée, puis le
seuil descend à six et la règle dure s'allume. Le plan en place fait alors
dépasser le nouveau plafond à des dizaines de personnes.

Sur ce chemin, la recherche de 0049 cale. Le mouvement qui fabrique les
jours-personnes libres — le **regroupement** d'une journée sur moins de
personnes (0049, décision 3) — est neutre en dur. Une fois un plan publié,
`stabiliteDuPlanPublie` donne un prix medium à chaque siège qu'il fait
quitter, et *late acceptance* ne l'accepte plus une fois la recherche
stabilisée. Les chaînes qui comblent les trous ne trouvent plus de collègue
déjà présent pour reprendre le jour rendu, et le score dur ne bouge plus.
C'est le piège que 0025 décrivait déjà — une suite de mouvements neutres en
dur et plus chers en medium — et la réponse de 0025, le *ruin and recreate*,
est justement ce que 0049 a rendu rare.

Une règle medium décide ainsi de la faisabilité dure, ce que la hiérarchie
des niveaux existe pour interdire. Le contournement manuel le confirme :
éteindre la stabilité, résoudre, la rallumer, résoudre encore.

## Options envisagées

**(A) Remettre le *ruin and recreate* au poids 1 quand un plan est publié.**
Écarté : 0049 a mesuré qu'à ce poids la faisabilité n'est jamais atteinte dans
le budget sous la règle des jours d'affilée.

**(B) Assouplir l'acceptation sur le medium pendant la phase de faisabilité.**
Écarté : Timefold ne permet pas d'ignorer un niveau dans un *acceptor*, et un
réglage de température serait à calibrer à l'aveugle.

**(C) Documenter le contournement.** Écarté comme réponse : c'est demander à
l'administrateur de réparer à la main un défaut de recherche, en éteignant une
règle qu'il a peut-être dosée volontairement. L'aide dit toutefois que
resserrer après publication déplace du monde quoi qu'il arrive.

**(D) Faire le contournement côté serveur, dans le même job.** Retenu.

## Décision

**(D)**, sous trois conditions réunies : `maxJoursConsecutifsTravaillesDur`
allumée, un plan publié (`affectationsPubliees` non vide) et
`stabiliteDuPlanPublie` active pour l'édition. Toute autre résolution garde
son étape unique, configuration comprise.

1. **Étape 1, la faisabilité.** `stabiliteDuPlanPublie` pèse zéro — en mémoire
   seulement : un poids persisté reste refusé en dessous de un. La résolution
   s'arrête dès le zéro dur (`bestScoreFeasible`) ou après **deux tiers** du
   budget.
2. **Étape 2, le polissage.** Elle repart du meilleur plan de l'étape 1, la
   stabilité rétablie à son poids, sur le reste du budget. Timefold garde la
   meilleure solution : la faisabilité trouvée ne peut pas se reperdre, et les
   changements et échanges ramènent les gens sur leurs sièges publiés partout
   où les règles dures le permettent.
3. **L'étape 2 s'enchaîne même si l'étape 1 a échoué.** Elle part du meilleur
   plan trouvé, qui n'est pas un plus mauvais départ que celui d'une
   résolution unique.
4. **Un seul job, une seule courbe.** Le second solveur est suivi par la même
   trace de score, décalée du temps déjà écoulé : la courbe reste continue et
   rien ne laisse croire à un second job. Le journal du serveur annonce les
   deux étapes et pourquoi ; le récapitulatif de la page Solveur dit combien de
   temps chacune a pris et combien de sièges publiés la seconde a rendus à leur
   titulaire.

## Mesures

Protocole : même graine (celle de `solverConfig.xml`), problème construit comme
en production (horaires résolus). L'édition est résolue au seuil de huit,
publiée, puis le seuil passe à six avec la règle dure allumée. La résolution
mesurée repart du plan publié, budget 300 s. Les sièges publiés changés sont
comptés comme `stabiliteDuPlanPublie` les compte.

**`festival-hivernal`** (versionné, 153 animateurs, 3 438 sièges) :

| Déroulé | Score à 300 s |
|---|---|
| une étape (avant) | `-35hard` |
| **deux étapes** | **`-19hard`** (étape 1 arrêtée à 200 s, `-23hard`) |

Aucun des deux n'atteint zéro dur : 0049 note déjà que rien n'établit que
cette grille tienne à six jours, publication ou non.

**`festival-realiste-canicule`** (versionné, 153 animateurs, grille à l'aise) :

| Déroulé | Score à 300 s | Sièges publiés changés |
|---|---|---|
| une étape (avant) | `0hard/-4856medium` | — |
| **deux étapes** | `0hard/-4913medium` | 55 après l'étape 1 (1 s), **20** au final |

Sur une grille où la stabilité ne bloque rien, les deux étapes ne coûtent
presque rien : la faisabilité tombe en une seconde et le polissage dispose de
tout le reste du budget. Au seuil de cinq, même constat (`0hard/-4872medium`
contre `0hard/-4936medium`).

Aucun scénario versionné ne reproduit le blocage complet — un plan tenable à
six jours que la stabilité empêche d'atteindre. Il a été observé sur une
édition non versionnée, où la même grille, jamais publiée, atteignait zéro dur
dans le budget. `PlanningServiceScenarioPublishedRunCapTest` tient donc le contrat des
deux étapes sur `festival-realiste-canicule` : zéro dur à la fin, et moins de
sièges publiés changés qu'à la sortie de l'étape 1.

## Conséquences

- Resserrer la règle des jours d'affilée après publication ne laisse plus la
  recherche calée par la stabilité ; cela **déplace toujours beaucoup de
  monde**, que la publication suivante préviendra. L'aide le dit, et donne les
  deux façons d'en déplacer moins : resserrer avant la première publication,
  ou verrouiller les journées qui ne doivent plus bouger.
- La stabilité reste une règle medium ordinaire : elle ne décide plus de la
  faisabilité, mais elle garde tout son poids sur le choix entre deux plans
  faisables.
- Le même piège peut exister sans la règle dure, dès qu'un plan publié devient
  infaisable après un changement de référentiel. La condition n'a pas été
  élargie : 0025 mesure que le *ruin and recreate* au poids 1 y suffit, et rien
  n'a été mesuré qui dise le contraire.
- `FeasibilityFirstSolveTest` tient la condition, la part du budget et le
  compte des sièges publiés changés ; `SolverScoreTraceTest` la courbe
  continue.
