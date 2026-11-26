# 0118 — Une seule règle de jours d'affilée, à niveau réglable, sans ordre de construction par calendrier

- **Statut** : accepté, implémenté
- **Date** : novembre 2026
- **Portée** : `QualiteConstraints`, moteur direct (`Rule`, `IncrementalScore`), catalogue (`ANCIENS_NOMS`, règles à niveau réglable), heuristique de construction (`PosteAffectationDifficultyComparatorFactory`), mouvement de relocalisation (`WeekRelocationMoveIteratorFactory`), analyses (`FeasibilityAnalyzer`, `ConsecutiveDaysCapacity`, `DaySequenceFloor`), schéma (migration `V157`), scénario YAML, API et outils MCP
- **Révise** : [0045](0045-le-niveau-de-la-regle-des-jours-d-affilee.md) (les deux formes), [0049](0049-la-regle-dure-des-jours-d-affilee-se-cherche-par-jours-entiers.md) (ce qui déclenche la recherche par jours entiers), [0068](0068-sous-la-regle-dure-la-construction-suit-le-calendrier.md) (retiré)
- **Applique** : [0106](0106-obligatoire-est-un-niveau-reglable-par-edition.md) et [0111](0111-le-niveau-d-une-regle-est-le-niveau-de-son-poids.md) (le niveau d'une règle est le niveau de son poids)

## Contexte

L'[ADR 0045](0045-le-niveau-de-la-regle-des-jours-d-affilee.md) avait écrit le
plafond de jours d'affilée en deux règles, parce qu'un poids ne changeait jamais
le niveau d'une règle : `maxJoursConsecutifsTravailles` (medium, dosée, active)
et `maxJoursConsecutifsTravaillesDur` (dure, éteinte par défaut). Depuis
l'[ADR 0111](0111-le-niveau-d-une-regle-est-le-niveau-de-son-poids.md), une
règle d'organisation porte le niveau que l'édition choisit : la forme dure n'a
plus de raison d'être une seconde règle.

L'[ADR 0068](0068-sous-la-regle-dure-la-construction-suit-le-calendrier.md)
avait ajouté, sous la forme dure seule et sous Timefold seul, un ordre de
construction par date. Le banc des jours d'affilée (`ConsecutiveRunsBenchTest`,
mesures du 26 novembre 2026 sur les scénarios versionnés) en a donné le prix :
sous Timefold, cet ordre vaut 6 à 8 % de medium sur `festival-realiste-canicule`
et `scenario-complet`, 35 % sur `gamme-20` et `gamme-25` ; le moteur direct, par
défaut depuis l'[ADR 0088](0088-le-moteur-direct-devient-le-moteur-par-defaut.md),
construit par rareté et ne le connaît pas.

## Options envisagées

**A. Garder les deux règles.** Rien ne bouge, mais deux règles pour un seul
plafond, et la seule règle d'organisation dont le niveau ne se choisit pas
comme les autres.

**B. Une règle, l'ordre par calendrier gardé sous le niveau obligatoire.** Le
comparateur lirait le poids de la règle. Il garde le gain de Timefold, au prix
d'une couture entre l'heuristique et le niveau, pour un moteur qui n'est plus
celui par défaut.

**C. Une règle, l'ordre par calendrier retiré.** Un mécanisme de moins ; la
régression ne touche que `SOLVER_ENGINE=timefold` à l'obligatoire.

## Décision

**(C).**

1. **Une règle, `maxJoursConsecutifsTravailles`**, rangée en « Qualité
   d'organisation », dont le niveau se choisit comme celui des autres règles
   d'organisation : préférence (forte, normale, légère) ou obligatoire. **Le
   niveau livré est celui de la 3.x** : préférence, au poids configuré
   (`planning.constraint-weights.maxJoursConsecutifsTravailles`, 5 livré ; les
   scénarios versionnés épinglent 25) : aucun score ne bouge sur une édition
   qui n'avait pas allumé la forme dure. Le nom gardé est celui de l'ancienne
   forme dosée, si bien qu'aucun réglage de cette forme n'est à renommer.
2. **La forme dure devient un niveau.** `maxJoursConsecutifsTravaillesDur` est
   lue comme alias jusqu'en 5.0, mais comme un **niveau**, pas comme un nom de
   règle :
   - **base** (`V157`) : une édition qui l'avait allumée voit la règle fusionnée
     passer obligatoire, et rallumée si la forme dosée avait été éteinte ;
     sinon, la règle garde le niveau, le poids et l'interrupteur de la forme
     dosée. C'est la lecture la plus protectrice, comme `V152`
     ([0114](0114-une-regle-par-plafond-lue-selon-l-age.md)). Le poids stocké
     de la forme dosée est gardé : c'est celui qu'elle reprend si l'édition la
     repasse en préférence. Les lignes de la forme dure sont supprimées ;
     l'historique des réglages et le dosage gardé avec un plan disent toujours
     l'ancien nom ;
   - **scénario YAML** : `activees: [maxJoursConsecutifsTravaillesDur]` devient
     `niveaux: {maxJoursConsecutifsTravailles: OBLIGATOIRE}`, la règle retirée
     de `desactivees` ; ailleurs (`desactivees`, `poids`), la clé est ignorée ;
     un avertissement le dit au rapport d'import ;
   - **routes et outils MCP** : allumer la forme dure (`PUT
     /api/constraints/maxJoursConsecutifsTravaillesDur`, `activer_contrainte`)
     rend la règle obligatoire et active, l'éteindre la repasse en préférence
     sans toucher à son poids ;
   - **`planning.constraint-weights.maxJoursConsecutifsTravaillesDur`** n'est
     plus lu : un poids dur ne dose rien.
3. **L'ordre de construction par calendrier est retiré**, et sa couture de
   banc avec lui : la construction est par rareté pour toute édition, aux deux
   moteurs. La régression mesurée sous Timefold, 6 à 35 % de medium selon le
   scénario à l'obligatoire, est annoncée dans le guide de passage à la 4.0.
4. **La recherche par jours entiers de l'[ADR 0049](0049-la-regle-dure-des-jours-d-affilee-se-cherche-par-jours-entiers.md)
   reste**, déclenchée par le niveau : `WeekRelocationMoveIteratorFactory.hardRunCap`
   lit le poids que la solution porte pour la règle (un point dur ⇒ le plafond,
   sinon 0), et l'analyse de faisabilité, l'écran Besoin et le diagnostic lisent
   le niveau de l'édition ou du plan, là où ils lisaient l'interrupteur de la
   forme dure.

## Conséquences

- Le catalogue perd une règle ; le moteur direct, une constante de `Rule`.
- À la préférence, le score est celui de main règle par règle : même flux, même
  terme du moteur direct, même poids.
- À l'obligatoire, la règle compte ce que comptait la forme dure, au niveau dur,
  un point par jour au-delà du plafond. Un organisateur qui allumait **les deux**
  formes (« jamais plus de N, et de préférence moins ») n'a plus que la
  première moitié : la règle obligatoire ne paie plus aussi en medium.
- `SOLVER_ENGINE=timefold` perd l'ordre par calendrier ; le moteur direct n'est
  pas concerné.
