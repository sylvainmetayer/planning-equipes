# 0114 — Une règle par plafond, le plafond lu selon l'âge à la date du créneau

- **Statut** : accepté, implémenté
- **Date** : novembre 2026
- **Portée** : `LegalConstraints`, moteur direct (`Rule`, `IncrementalScore`), catalogue (`ANCIENS_NOMS`), schéma (migration `V152`), scénario YAML, API et outils MCP, propriétés `planning.constraint-weights.*`
- **Applique** : la table des anciens noms du catalogue, prévue pour les fusions de règles de la 4.0
- **Prolonge** : [0048](0048-une-seule-regle-de-pause.md) (ses deux règles de pause deviennent une)

## Contexte

Le plafond de travail effectif d'une journée et celui d'une période de travail
ininterrompue existaient chacun en deux règles, une pour les majeurs et une pour
les mineurs : quatre lignes à l'écran, quatre interrupteurs, quatre poids, pour
deux sujets. Les deux règles d'une paire avaient la même forme ; elles ne
différaient que par l'âge auquel elles s'appliquaient, et par le plafond qu'elles
lisaient dans `PlafondsLegauxMajeurs` ou `PlafondsLegauxMineurs`.

## Décision

1. **Une règle par plafond.** `dureeQuotidienneMax` remplace
   `dureeQuotidienneMaxMajeur` et `dureeQuotidienneMaxMineur` ;
   `travailContinuMax` remplace `travailContinuMaxMajeur` et
   `travailContinuMaxMineur`. Le plafond est lu selon l'âge de l'animateur **à la
   date du créneau**, jamais un drapeau stocké : 10 h, 8 h (16 à moins de 18 ans)
   ou 7 h avant 16 ans pour la journée ; 6 h (pause d'au moins 20 min) ou 4 h 30
   (pause d'au moins 30 min, d'ordre public) pour la période ininterrompue.
   `PlafondsLegauxMajeurs` et `PlafondsLegauxMineurs` restent les seuls endroits
   où ces chiffres sont écrits.
2. **L'équivalence est exacte, pas approchée.** Mêmes violations, mêmes minutes
   de dépassement, même forfait d'exclusion d'éligibilité pour un mineur, donc
   même score à niveau égal. Un animateur sans date de naissance n'est ni majeur
   ni mineur : il n'avait aucune des deux règles, il n'a pas davantage la
   règle fusionnée. Le test `AgeCapMergeEquivalenceTest` rejoue des plans fixes
   des scénarios livrés et compare le score de chaque niveau et les points de
   chaque règle (la paire sommée sous son nouveau nom) à une référence écrite par
   ce même test sur le code d'avant la fusion. Le test différentiel entre les deux
   moteurs et le test de mutation sur les contraintes légales tiennent le reste.
3. **Les moteurs.** Les deux écritures de la règle sont fusionnées dans le même
   changement : les flux de contraintes (`LegalConstraints`) et le moteur direct
   (`Rule`, `IncrementalScore`). `FlatProblem.staticTerms` ne change pas : ces
   règles lisent une journée, pas un siège et son titulaire.
4. **Les réglages déjà enregistrés sont fusionnés sur la lecture la plus
   protectrice**, par la migration `V152` (interrupteur, poids et niveau choisi
   par l'édition, ADR 0111) : la règle fusionnée est **allumée si l'une des deux
   l'était** (elle n'est éteinte que si les deux l'étaient ; une règle sans
   ligne vaut « active »), prend **le niveau le plus dur** (obligatoire si l'une
   l'était ou suivait le niveau du catalogue, dur pour une règle légale ;
   préférence seulement si les deux l'étaient) et **le poids le plus fort**. Une
   édition ne devient donc jamais moins protégée pour un âge qu'elle ne l'était.
   Le contraire, prendre le réglage le plus faible, aurait pu rendre légale en
   silence une journée de mineur que l'édition interdisait.
5. **Les anciens noms sont lus en alias, avec un avertissement, pendant toute la
   4.x, et retirés en 5.0** (`ConstraintCatalog.ANCIENS_NOMS`, deux entrées par
   règle fusionnée) : dans un fichier de scénario (`contraintes`, appliqué à tout
   document par `ScenarioMigrations`, le plus protecteur des deux réglages
   l'emporte, l'avertissement va au rapport d'import), dans
   `planning.constraint-weights.<ancien>` (le poids le plus fort, avertissement
   au démarrage), dans les routes et les outils MCP de réglage des règles (la
   réponse porte le nouveau nom).
6. **Ce que la migration ne réécrit pas** : l'historique des réglages
   (`ponderation_contrainte_historique`) et le dosage gardé avec chaque plan
   (`planning_resolution.dosage`) disent ce qui s'est fait sous le nom d'alors.
   La comparaison de deux plans dont l'un porte les anciens noms dit seulement
   que les dosages diffèrent, ce qui est exact.

## Alternatives écartées

- **Garder les deux règles et les présenter ensemble à l'écran.** Le problème est
  dans le modèle, pas dans l'affichage : quatre interrupteurs, quatre poids et
  quatre noms dans l'API, les exports et les journaux.
- **Une règle par tranche d'âge** (majeur, 16 à 18, moins de 16). Trois règles au
  lieu de deux, pour une distinction que le plafond lu à la date porte déjà.
- **Fusionner sur le réglage le plus faible**, ou sur « le dernier écrit ».
  Rejeté : la fusion ne doit rendre aucune édition moins protégée (point 4).

## Conséquences

- Le catalogue passe de 47 à 45 règles. L'écran, `GET /api/constraints`, les
  diagnostics et les outils MCP disent une règle avec son plafond par âge.
- Un réglage porte désormais sur les deux âges à la fois : on ne peut plus
  éteindre le plafond des majeurs en laissant celui des mineurs, ni l'inverse.
- Les lecteurs des anciens noms (propriétés, fichiers, appels) fonctionnent
  jusqu'à la 5.0, puis refusent : le guide de passage en 4.0 le dit, avec
  l'action de l'exploitant.
