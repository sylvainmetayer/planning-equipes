# 0116 — Une règle pour le plafond hebdomadaire et une pour le repos hebdomadaire, lus selon l'âge

- **Statut** : accepté, implémenté
- **Date** : novembre 2026
- **Portée** : `LegalConstraints`, moteur direct (`Rule`, `IncrementalScore`), catalogue (`ANCIENS_NOMS`, `NOMS_CONSERVES`), schéma (migration `V154`), scénario YAML, API et outils MCP, propriétés `planning.constraint-weights.*`
- **Complète** : [0114](0114-une-regle-par-plafond-lue-selon-l-age.md), dont elle reprend le procédé pour les deux dernières paires majeur / mineur

## Contexte

Après la fusion des plafonds quotidien et de travail continu (0114), il restait
deux sujets en deux règles chacun : la durée hebdomadaire de travail effectif
(`dureeHebdomadaireMax` pour les majeurs, `dureeHebdomadaireMaxMineur`) et le
repos hebdomadaire (`reposHebdomadaireMinimal`, 35 heures consécutives, qui
s'applique à **tous** les animateurs, et `reposHebdomadaireMineur`, deux jours
consécutifs par semaine civile, art. L3164-2).

## Décision

1. **Deux règles de moins, qui gardent leur nom.** `dureeHebdomadaireMax` lit son
   plafond selon l'âge à la date du créneau : 48 h (majeur) ou 35 h (mineur), les
   deux durées des paramètres légaux. `reposHebdomadaireMinimal` demande les 35 h
   consécutives à tous, et à un mineur en plus les deux jours consécutifs.
   `dureeHebdomadaireMaxMineur` et `reposHebdomadaireMineur` sont retirées. Le
   catalogue passe de 44 à 42 règles. Contrairement à 0114, le nom nouveau n'est
   pas un nom neuf : c'est celui de la règle des majeurs (ou de tous).
2. **Équivalence exacte.** Mêmes violations, mêmes minutes ou jours manquants,
   donc même score à niveau égal. Pour la durée, une semaine porte deux sommes,
   les jours travaillés comme majeur et ceux travaillés comme mineur, chacune
   contre son plafond : la semaine d'un dix-huitième anniversaire se juge comme
   avant, âge par âge, sans mélanger les jours. Pour le repos, les deux déficits
   (minutes de repos manquantes, jours de repos manquants) se somment dans un même
   tuple par animateur ; seuls comptent pour les deux jours les jours où la
   personne est mineure. `AgeCapMergeEquivalenceTest` rejoue les plans fixes des
   scénarios livrés et compare le score de chaque niveau et les points de chaque
   règle (la paire sommée sous son nom) à la référence écrite avant la fusion ; le
   test différentiel et le test de mutation sur les règles légales tiennent le
   reste. Le moteur direct n'a qu'à écrire les deux coûts sous la même règle :
   `FlatProblem.staticTerms` ne change pas.
3. **Les réglages enregistrés** sont fusionnés par `V154` comme par `V152` (la
   règle fusionnée est allumée si l'une l'était, au niveau le plus dur, au poids
   le plus fort, une préférence sans poids valant 5) ; la ligne enregistrée sous le
   nom conservé compte pour la règle des majeurs, ou pour tous.
4. **Les anciens noms** (`dureeHebdomadaireMaxMineur`, `reposHebdomadaireMineur`)
   sont lus en alias avec un avertissement pendant toute la 4.x et retirés en 5.0 :
   scénario YAML, propriétés (le poids le plus fort de ceux écrits, nom conservé
   compris), routes et outils MCP.
5. **Le piège du nom conservé.** Un fichier YAML de 3.x qui n'écrit que
   `dureeHebdomadaireMax` ou `reposHebdomadaireMinimal` ne se distingue pas d'un
   fichier de 4.0 qui l'écrit pour les deux âges : il est lu comme tel. Un
   `desactivees: [dureeHebdomadaireMax]` de 3.x (le plafond des majeurs seul)
   éteint donc désormais les deux âges, sans avertissement. Seule la présence de
   l'ancien nom déclenche la fusion sur la lecture la plus protectrice, et alors
   le nom conservé non écrit vaut « allumé » (`ConstraintCatalog.NOMS_CONSERVES`).
   C'est le coût d'avoir gardé les noms ; le guide de passage en 4.0 le dit.

## Alternatives écartées

- **Deux noms neufs** (`dureeHebdomadaire…`, `reposHebdomadaire`), comme en 0114 :
  aucune ambiguïté dans les fichiers, mais un changement de nom de plus pour des
  règles que tout le monde connaît, dans des tests, des docs et des exports. À
  reprendre si le piège du point 5 se révèle plus coûteux que prévu.
- **Ne pas fusionner le repos** : `reposHebdomadaireMinimal` n'est pas une règle de
  majeurs, c'est le plancher de tous. Mais les deux moitiés lisent la même chose (la
  semaine civile d'un animateur, ses places), et deux interrupteurs pour un seul
  sujet sont ce que 0114 voulait retirer.
- **Un tuple par âge** pour la durée hebdomadaire : plus proche des deux règles
  d'avant, mais un compte de violations qui double pour la semaine d'un
  anniversaire et un score identique. Un tuple par semaine, deux sommes, suffit.

## Conséquences

- Un réglage porte désormais sur les deux âges à la fois, pour la durée comme pour
  le repos : on ne peut plus éteindre le repos des majeurs en laissant celui des
  mineurs.
- Le détail des deux jours consécutifs d'un mineur apparaît dans les écarts de
  `reposHebdomadaireMinimal` avec les places de la personne, comme le déficit de 35 h.
- Les poids d'un mineur et d'un majeur ne peuvent plus différer pour ces deux sujets.
