# 0131 — À l'import, le code d'un stand, d'une typologie ou d'un emplacement passe avant son identifiant

- **Statut** : accepté, implémenté
- **Date** : novembre 2026
- **Portée** : import de scénario et restauration d'une sauvegarde avant vidage
- **Révise** : [0050](0050-identifiants-generes-par-edition.md) § D3 (l'ordre des règles 1 et 2 pour les référentiels à code)

## Contexte

La décision 0050 fait désigner une ligne du fichier, dans l'édition cible,
d'abord par son identifiant (« si aucun code ne la contredit »), puis par son
code. Une ligne de l'édition **sans code** ne contredit rien : la ligne du
fichier qui porte son identifiant la garde, et lui écrit son code, même quand
une autre ligne de l'édition porte encore ce code. L'import s'arrête alors sur
l'index unique `(edition_id, code)` des stands, typologies et emplacements.

Le cas se produit dès qu'un code change de ligne entre une sauvegarde et sa
restauration (un emplacement vidé de son code, un autre créé avec ce code) :
la restauration échouait sans recours, et un import ordinaire aussi. L'ordre
de lecture du fichier comptait en plus : une ligne gardée par son identifiant
pouvait prendre la ligne qu'un code lu plus loin désignait.

## Décision

Pour les stands, les typologies et les emplacements, l'import décide en deux
passes :

1. **chaque code du fichier** (ou l'identifiant lu comme un code, pour un
   fichier écrit à la main) prend la ligne de l'édition qui le porte, pour
   toutes les lignes du fichier ;
2. ensuite seulement, une ligne encore libre est retrouvée par son
   identifiant, si aucun code ne la contredit, ou créée.

Le code est l'identité naturelle de ces objets : c'est par lui que les
fichiers les citent et que la restauration retrouve le planning. Aucune ligne
ne reçoit alors un code qu'une autre ligne de l'édition porte encore. Un
scénario exporté puis réimporté dans la même édition se retrouve en place
comme avant, chaque ligne portant son propre code. Les animateurs et les
contraintes ad hoc ne changent pas.

## Conséquences

- Une ligne de l'édition restée sans code, dont le fichier portait le code
  sous le même identifiant, n'est plus réécrite : la ligne qui porte le code
  l'est, et la ligne sans code reste telle quelle (pour un stand absent du
  fichier, supprimée comme avant).
- Un fichier qui donne le même code à deux lignes reste hors du champ de
  cette décision.
