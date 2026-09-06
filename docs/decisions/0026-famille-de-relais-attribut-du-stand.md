# 0026 — La famille de relais est un attribut du stand

- **Statut** : accepté, implémenté
- **Date** : septembre 2026
- **Portée** : référentiel des stands, génération des postes, IHM, import
- **Issue** : #390

## Contexte

Sur une grille décalée, chaque stand relaie avec les stands de sa famille :
la génération des postes ne l'apparie qu'aux créneaux de cette famille. La
famille se recalculait à chaque construction du problème par le **rang du
stand dans la liste triée des identifiants**, modulo le nombre de familles.
Équilibré, déterministe, et instable à l'ajout : un stand dont l'identifiant
se classe avant les autres fait glisser chacun des suivants d'une famille.

Le banc de la règle de stabilité ([0025](0025-stabilite-du-plan-publie.md)) l'a
mesuré sur la fixture anonymisée : un stand `BANC-NOUVEAU` ajouté après la
publication, et **plus aucune ligne stand × créneau commune** avec le plan
publié (2 050 lignes), 153 personnes sur 153 changent d'horaires, quel que
soit le poids de la règle. Le même stand nommé `ZZ-NOUVEAU` ne décale rien.

## Décision

1. **La famille est persistée sur le stand** (`stand.famille`, nullable).
   Un stand qui en a une la garde, quoi qu'il arrive aux autres.
2. **Un stand sans famille rejoint la moins peuplée**, la plus basse à
   égalité, dans l'ordre des identifiants. Sur une édition où rien n'est
   persisté, c'est exactement l'ancien tour de rôle : rien ne change au
   déploiement, et la migration fige la répartition en vigueur (même rang,
   même ordre binaire que Java).
3. **L'attribution s'écrit** à la création du stand et à chaque construction
   du problème pour ce qui n'en a pas, sans toucher `modifie_le` : ce n'est
   pas une modification de l'opérateur, une fiche ouverte ne doit pas devenir
   un conflit d'écriture.
4. **Une famille hors grille est réattribuée** comme une absence (valeur
   au-delà du nombre de familles), jamais traitée comme une famille de plus.
5. **Le remplacement de la grille oublie les familles** (découpage,
   dérivation « remplacer ») : le plan enregistré part avec, la prochaine
   construction répartit à nouveau.
6. **Visible et modifiable** dans la fiche du stand quand la grille a plus
   d'une famille (« Automatique » par défaut), portée par l'import et
   l'export de scénario (`famille:` facultatif) et par `creer_stand` /
   `modifier_stand`.

## Conséquences

- Ajouter ou supprimer un stand ne déplace plus les autres ; la règle de
  stabilité a de nouveau une prise après un stand ajouté.
- L'équilibre n'est plus garanti à un stand près qu'à répartition constante :
  supprimer plusieurs stands d'une même famille la vide sans réattribution.
  C'est le prix de la stabilité, et l'opérateur voit la famille pour corriger.
