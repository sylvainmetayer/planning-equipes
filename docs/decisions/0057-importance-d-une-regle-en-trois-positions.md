# 0057 — L'importance d'une règle en trois positions, et une échelle de poids multipliée par cinq

- **Statut** : accepté, implémenté
- **Date** : septembre 2026
- **Portée** : écran « Règles du planning », configuration (`planning.constraint-weights.*`), schéma (migration `V108`), scénarios livrés
- **Révise** : l'échelle des poids de [0025](0025-stabilite-du-plan-publie.md) (stabilité à 5) et de [0045](0045-le-niveau-de-la-regle-des-jours-d-affilee.md) (jours d'affilée au poids 1) — les rapports qu'elles ont mesurés sont conservés, seule l'unité change

## Contexte

L'écran des contraintes offrait à chaque règle un champ « Poids » de 1 à 100,
écrit au serveur à chaque changement. Deux défauts :

- **le défaut livré d'une règle de qualité était 1, le minimum.** Le geste
  « baisser son poids » que le Diagnostic propose sur une règle en écart menait
  à un champ qui valait déjà 1 — douze fois sur douze sur les données de test.
  Le seul sens offert était la hausse ;
- **un nombre de 1 à 100 ne dit rien à un organisateur.** Entre 7 et 12, ce
  qui change n'est lisible qu'à qui connaît le score ; ce que l'organisateur
  veut dire est « cette règle compte moins », « normalement », « plus ».

## Options envisagées

| Option | Ce qu'elle coûte |
| --- | --- |
| **A.** Garder le champ numérique, défaut 1 | Le geste « baisser » reste faux, et le nombre reste opaque |
| **B.** Trois positions sur l'échelle actuelle (1 / 2 / 5) | Aucune migration, mais « normale » vaudrait 2 : toutes les règles réglées à 1 par défaut deviendraient « faibles » sans que personne les ait baissées, et le classement des plans changerait à la première ouverture de l'écran |
| **C.** Trois positions, faible 1 / normale 5 / forte 25, et l'échelle ×5 | Une migration des poids stockés et de leur historique, les défauts de configuration et les scénarios livrés multipliés par cinq, les scores medium et soft des résolutions suivantes cinq fois plus grands |

## Décision

**Option C.**

- L'écran « Règles du planning », onglet Qualité, offre à chaque règle moyenne
  ou souple **trois positions : faible (1), normale (5), forte (25)**. Un
  facteur cinq entre deux positions voisines suffit à faire passer une règle
  forte avant une normale sans écraser les autres de son niveau. Le poids exact
  reste réglable dans le panneau de la règle (1 à 500) et s'affiche alors
  « personnalisée ».
- **Le défaut d'une règle moyenne ou souple passe de 1 à 5**
  (`application.properties`, et `ConstraintCatalog.defaultWeight` pour une
  règle que la configuration ne nomme pas) ; la stabilité du plan publié passe
  de 5 à 25, la position forte. **Une règle dure garde 1** : un écart dur se
  compte, il ne se dose pas, et l'écran ne lui offre que son interrupteur.
- **La migration `V108` multiplie par cinq les poids moyens et souples que
  chaque édition avait stockés**, et ceux de leur historique, pour que
  l'historique se lise dans l'unité de l'écran. Les scénarios livrés portent
  leurs poids multipliés par cinq. Le plafond de l'API passe de 100 à 500 : ce
  qu'une édition avait stocké reste stockable.
- **Rien d'autre n'est réécrit.** Le dosage mémorisé par une résolution passée
  (`planning_resolution.dosage`) et son score restent tels quels : ils disent
  sous quels poids et avec quel score ce plan a été calculé. Le Comparateur dit
  alors, à juste titre, qu'un plan d'avant et un plan d'après ne se comparent
  pas à poids égaux.

Le classement des plans ne change pas : toutes les règles d'un même niveau
sont multipliées par le même facteur, sauf celles qu'une édition avait laissées
au défaut et qui gagnent le même facteur par la configuration.

## Conséquences

- Les scores medium et soft sont cinq fois plus grands à plan égal ; un score
  noté avant la migration ne se compare pas à un score d'après.
- Les rapports mesurés par les ADR 0025 et 0045 tiennent : 5 contre 1 devient
  25 contre 5, 1 contre 1 devient 5 contre 5.
- **Un fichier scénario écrit avant ce changement** porte ses poids sur
  l'ancienne échelle ; réimporté tel quel, ses règles citées pèseraient cinq
  fois moins que les autres. Le fichier ne porte pas de numéro de version qui
  permettrait de le convertir à la lecture ; `docs/import-export.md` dit de le
  multiplier à la main.
- Le Diagnostic peut masquer « Baisser l'importance » sur une règle déjà au
  minimum : `core/importance.ts` expose `importanceAtMinimum`.
