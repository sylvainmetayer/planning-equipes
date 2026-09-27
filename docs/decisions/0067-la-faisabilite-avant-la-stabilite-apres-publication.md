# 0067 — La faisabilité avant la stabilité, après publication

- **Statut** : accepté, implémenté
- **Date** : septembre 2026
- **Portée** : solveur (déroulé d'une résolution quand un plan est publié)
- **Complète** : [0025](0025-stabilite-du-plan-publie.md)
- **Prolonge** : [0049](0049-la-regle-dure-des-jours-d-affilee-se-cherche-par-jours-entiers.md)

## Contexte

`stabiliteDuPlanPublie` ([0025](0025-stabilite-du-plan-publie.md)) donne un
prix medium à chaque personne déplacée d'un siège qu'elle tenait dans le
dernier plan publié. Elle est muette tant que rien n'a été publié, et c'est ce
qui a longtemps caché son effet sur la **faisabilité**.

Combler un trou demande souvent une chaîne : quelqu'un quitte le siège qu'on
lui a annoncé pour prendre le trou, puis ce siège est repourvu à son tour. Le
premier maillon est neutre en dur et plus cher en medium. *Late acceptance*
refuse un pas moins bon que le score d'il y a quatre cents pas : une fois la
recherche stabilisée sur un palier, ce maillon n'est plus jamais accepté, et
le score dur ne bouge plus. 0025 décrivait déjà ce piège et y répondait par le
*ruin and recreate* autour des trous.

Deux chemins ordinaires y mènent :

- **resserrer une règle après publication**, le cas que
  [0049](0049-la-regle-dure-des-jours-d-affilee-se-cherche-par-jours-entiers.md)
  n'avait pas mesuré (ses mesures ont été faites sur un import, sans
  publication). La forme dure des jours d'affilée allumée, ou son seuil
  baissé, sur un plan publié : le **regroupement** d'une journée, qui fabrique
  les jours-personnes libres dont les chaînes ont besoin, est neutre en dur et
  cesse d'être accepté ;
- **recalculer une édition publiée** dont le référentiel a bougé depuis : le
  plan de départ, publié, n'est plus faisable, et chaque siège à rendre coûte.

Une règle medium décide alors de la faisabilité dure, ce que la hiérarchie des
niveaux existe pour interdire. Le contournement manuel le confirme : éteindre
la stabilité, résoudre, la rallumer, résoudre encore. Le second calcul part
d'un plan faisable, et son polissage ramène les gens sur leurs sièges publiés
sans reperdre le zéro dur.

## Options envisagées

**(A) Remettre le *ruin and recreate* au poids 1 quand un plan est publié.**
Écarté : 0049 a mesuré qu'à ce poids la faisabilité n'est jamais atteinte dans
le budget sous la règle des jours d'affilée.

**(B) Assouplir l'acceptation sur le medium pendant la phase de faisabilité.**
Écarté : Timefold ne permet pas d'ignorer un niveau dans un *acceptor*, et un
réglage de température serait à calibrer à l'aveugle.

**(C) Documenter le contournement.** Écarté comme réponse : c'est demander à
l'administrateur de réparer à la main un défaut de recherche, en éteignant une
règle qu'il a peut-être dosée volontairement.

**(D) Faire le contournement côté serveur, dans le même job, seulement sous la
forme dure des jours d'affilée.** C'était la première forme de cette décision.
Écartée : le piège tient à la stabilité, pas à cette règle, et le second
chemin du contexte n'a rien à voir avec elle.

**(E) Faire le contournement côté serveur, dans le même job, dès qu'un plan
est publié.** Retenu.

## Décision

**(E)**, sous deux conditions réunies : un plan publié (`affectationsPubliees`
non vide) et `stabiliteDuPlanPublie` active pour l'édition. Toute autre
résolution garde son étape unique, configuration comprise. La résolution
incrémentale du jour J suit la même règle : elle passe par le même
déroulé.

1. **Étape 1, la faisabilité.** `stabiliteDuPlanPublie` pèse zéro, en mémoire
   seulement : un poids enregistré reste refusé en dessous de un. La
   résolution s'arrête dès le zéro dur (`bestScoreFeasible`) ou après **deux
   tiers** du budget.
2. **Étape 2, le polissage.** Elle repart du meilleur plan de l'étape 1, la
   stabilité rétablie à son poids, sur le reste du budget et avec l'arrêt sur
   plateau du budget ([0051](0051-budget-de-calcul-par-edition-sous-plafond-d-exploitant.md)).
   Timefold garde la meilleure solution : la faisabilité trouvée ne peut pas
   se reperdre, et les changements et échanges ramènent les gens sur leurs
   sièges publiés autant que les règles dures et le temps restant le
   permettent.
3. **L'étape 2 s'enchaîne même si l'étape 1 a échoué**, jamais après un
   arrêt. Elle part du meilleur plan trouvé, qui n'est pas un plus mauvais
   départ que celui d'une résolution unique. Une annulation ou un arrêt du
   serveur pendant l'étape 1, ou entre les deux, termine le job là, et le
   récapitulatif dit que le polissage n'a pas eu lieu. Timefold efface au
   démarrage d'un `solve()` l'arrêt demandé avant : c'est donc le serveur qui
   renonce à lancer l'étape 2, et le job relit à chaque nouveau meilleur plan
   son propre drapeau d'arrêt, que rien n'efface, pour rattraper un arrêt
   arrivé dans l'intervalle — ce qui vaut aussi pour une résolution en une
   seule étape.
4. **Un seul job, une seule courbe.** Le second solveur est suivi par la même
   trace de score, décalée du temps déjà écoulé. Le dernier point de l'étape 1
   ferme sa partie de la courbe, mais n'est jamais repris comme score final :
   il a été compté sans la stabilité. Le medium chute d'un coup au passage
   d'une étape à l'autre : c'est la stabilité qui recommence à compter, pas
   un recul du plan. Le journal du serveur annonce les deux
   étapes ; le récapitulatif de la page Solveur dit combien de temps chacune a
   pris et combien de places publiées la seconde a rendues à leur titulaire.
5. **Le dosage enregistré avec le plan est celui de l'édition.** L'étape 1
   part des poids avec lesquels le problème a été préparé, stabilité mise à
   zéro, sans les relire ; le plan rendu par l'étape 2 porte à nouveau ces
   poids. C'est ce qui est lu pour l'historique des indicateurs et les
   instantanés, jamais le zéro de l'étape 1.
6. **Les tests de scénario cherchent comme la production.**
   `solveUntilFeasible`, que lancent les tests lents, fait exactement
   l'étape 1 quand un plan est publié : jusqu'à la faisabilité, stabilité
   suspendue.

## Mesures

Protocole : même graine (celle de `solverConfig.xml`), problème construit comme
en production (horaires résolus). L'édition est résolue au seuil de huit,
publiée, puis le seuil passe à six avec la règle dure allumée. La résolution
mesurée repart du plan publié, budget 300 s. Les sièges publiés changés sont
comptés comme `stabiliteDuPlanPublie` les compte. Ces mesures ont été prises
avant l'échelle de [0057](0057-importance-d-une-regle-en-trois-positions.md),
qui multiplie par cinq tous les poids medium et soft : les rapports entre
règles, donc les pas de la recherche, sont les mêmes.

**`festival-hivernal`** (versionné, 153 animateurs, 3 438 sièges) :

| Déroulé | Score à 300 s |
|---|---|
| une étape | `-35hard` |
| **deux étapes** | **`-19hard`** (étape 1 arrêtée à 200 s, `-23hard`) |

Aucun des deux n'atteint zéro dur : 0049 note déjà que rien n'établit que
cette grille tienne à six jours, publication ou non.

**`festival-realiste-canicule`** (versionné, 153 animateurs, grille à l'aise) :

| Déroulé | Score à 300 s | Sièges publiés changés |
|---|---|---|
| une étape | `0hard/-4856medium` | — |
| **deux étapes** | `0hard/-4913medium` | 55 après l'étape 1 (1 s), **20** au final |

Sur une grille où la stabilité ne bloque rien, les deux étapes ne coûtent
presque rien : la faisabilité tombe en une seconde et le polissage dispose de
tout le reste du budget. C'est ce qui rend l'élargissement sans risque pour
une édition publiée qui n'en a pas besoin : l'étape 1 s'arrête dès qu'elle
atteint zéro dur.

Aucun scénario versionné ne reproduit le blocage complet, un plan faisable que
la stabilité empêche d'atteindre dans le budget. Il a été observé sur des
éditions non versionnées, où la même grille, jamais publiée, atteignait zéro
dur. `PlanningServiceScenarioPublishedRunCapTest` tient donc le contrat des
deux étapes sur `festival-realiste-canicule` : zéro dur à la fin, et moins de
sièges publiés changés qu'à la sortie de l'étape 1.

## Conséquences

- La stabilité reste une règle medium ordinaire : elle ne décide plus de la
  faisabilité, mais elle garde tout son poids sur le choix entre deux plans
  faisables.
- Resserrer une règle après publication **déplace toujours du monde**, que la
  publication suivante préviendra. L'aide le dit, et donne les deux façons
  d'en déplacer moins : resserrer avant la première publication, ou
  verrouiller les journées qui ne doivent plus bouger.
- Le polissage dispose d'un peu moins de budget qu'une résolution unique,
  du temps que l'étape 1 a pris. Sur une édition dont le plan publié reste
  faisable, c'est quelques secondes.
- Deux cas n'ont pas été mesurés, et y coûtent davantage. Une résolution
  **de zéro** sur une édition publiée construit son plan sans préférer les
  titulaires publiés : c'est le polissage seul qui les ramène. Une édition
  **qui ne peut pas atteindre zéro dur** passe les deux tiers du budget sans
  la stabilité, et son plan final peut déplacer plus de monde qu'une
  résolution unique n'en aurait déplacé. Dans les deux cas, repartir du plan
  enregistré, ou verrouiller, garde les gens en place.
- Limiter les deux étapes aux résolutions qui repartent du plan enregistré a
  été envisagé, puis écarté. Une édition publiée a toujours un plan
  enregistré, et le mode par défaut en repart : partir de zéro n'arrive que
  par « Recommencer de zéro », un geste explicite dont la confirmation
  prévient déjà qu'il peut bousculer des personnes prévenues. Qui le choisit
  préfère un plan faisable à un plan proche du publié ; le récapitulatif dit
  combien de places le polissage a rendues.
- `FeasibilityFirstSolveTest` tient la condition, la part du budget et le
  compte des sièges publiés changés ; `SolverScoreTraceTest` la courbe
  continue.
