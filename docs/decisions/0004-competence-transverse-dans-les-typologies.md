# 0004 — Une compétence transverse réutilise l'énumération des typologies

- **Statut** : accepté, implémenté
- **Date** : août 2026
- **Portée** : domaine

## Contexte

Un stand mobile — un animateur qui déambule en ville plutôt que de tenir une
table — exige une aptitude particulière. Cette aptitude n'est pas un genre de
jeu, alors que l'énumération des typologies désigne précisément un genre montré
aux visiteurs (stratégie, ambiance, etc.).

La page « Typologies » et son CRUD laissent croire qu'on peut ajouter une
typologie par simple saisie. C'est faux : la table n'est qu'un miroir de
libellés posé sur l'énumération Java. Une ligne créée hors des littéraux
existants ne serait sélectionnable nulle part, ni sur un stand ni sur un
animateur, puisque la désérialisation est stricte.

## Options envisagées

| Option | Pour | Contre |
| --- | --- | --- |
| Nouveau littéral dans l'énumération existante | Se branche gratuitement sur le mécanisme d'éligibilité déjà en place | Mélange deux notions dans une même énumération |
| Second axe de compétences transverses | Conceptuellement juste | N'existe pas ; à construire de bout en bout, du domaine à l'IHM |

## Décision

Le nouveau littéral, **simplification assumée et documentée comme telle**.

L'axe séparé serait plus propre, mais il faudrait le créer entièrement alors que
le mécanisme d'éligibilité existant fait déjà exactement le travail attendu. Le
coût conceptuel est réel ; le coût de construction de l'alternative l'est
davantage, pour un besoin qui compte une seule occurrence.

## Conséquences

- L'énumération porte désormais deux notions : un genre de jeu et une aptitude
  d'animation. Quiconque la lit doit le savoir — d'où cet ADR.
- **Seuil de réouverture explicite** : à la troisième notion transverse, la
  simplification ne tient plus et l'axe séparé devient le bon investissement.
  Une deuxième occurrence, à elle seule, ne justifie pas encore la refonte.
