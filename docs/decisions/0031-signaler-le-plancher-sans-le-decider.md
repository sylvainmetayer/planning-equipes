# 0031 — Une règle qui pénalise tout faute de donnée est signalée, jamais décidée

- **Statut** : accepté, implémenté
- **Date** : septembre 2026
- **Portée** : analyse de contraintes, IHM, KPI

## Contexte

Sur l'édition 2026 réelle, avant que la grille d'appréciations existe, 68 %
du score medium était une constante : deux règles de qualité matchaient
l'intégralité de ce qu'elles évaluent parce que la donnée qu'elles mesurent
n'existait pas au référentiel — aucun souhait déclaré, aucun animateur au
niveau référent. Sur la fixture anonymisée `festival-realiste`, qui portait
des référents et des appréciations avant d'être retirée avec les familles de
relais ([0029](0029-retrait-des-familles-de-relais.md)), il restait une règle
signalée, `souhaitsIncompatibles`, pour 39 % du medium.
Les règles étaient correctes. Mais un organisateur qui lit
`0hard/-6675medium` ne peut pas savoir que `-5000` de ces points ne
bougeront jamais, quelle que soit la durée de calcul : il conclut à un
mauvais planning, ou à un solveur qui plafonne. L'aide le disait en prose ;
rien ne le détectait.

## Options envisagées

**(A) Désactiver automatiquement** une règle dont la donnée est absente.
Écarté : l'application prendrait à la place de l'organisateur une décision
qui engage le sens du score — et une règle éteinte en silence ne se rallume
pas quand la donnée arrive. La confirmation demandée avant d'éteindre une
règle légale ([0006](0006-obligation-legale-ou-politique-organisateur.md))
va déjà dans le sens inverse : moins d'automatisme, plus de lisibilité.

**(B) Un seuil sur le score** — signaler une règle qui pèse plus de N % du
medium. Écarté : une règle peut peser lourd parce qu'elle mesure un vrai
problème. C'est le *ratio* qui distingue une constante d'une mesure, pas le
poids.

**(C) Lire chaque règle non dure comme un ratio** `écarts ÷ éléments
évalués`, au grain de la règle, et **signaler** ce qui dépasse un seuil en
nommant la donnée absente — sans rien changer à l'état de la règle.

## Décision

(C). Cinq règles :

1. **Le dénominateur est celui de la règle**, pas un nombre de postes
   uniforme : sièges pourvus pour une règle par siège, groupes stand ×
   créneau pour une règle par groupe, paires consécutives, animateur × jour,
   pauses dues… La table (`ConstraintFloorRules`) vit à côté du catalogue et
   un test structurel la rend exhaustive : une règle medium ou soft sans
   ligne ne compile pas le sens, elle fait échouer la suite. Trois familles de
   règles n'ont pas de lecture par élément et ne sont jamais un plancher :
   correspondance agrégée (équité), récompense, et **pénalité portant une
   fonction de poids** — là, le nombre de correspondances est une quantité
   d'écart et non un booléen par élément, si bien qu'un ratio de 1,0 désigne
   exactement ce que le solveur sait réduire.
2. **Le seuil est 95 %**, pas 100 % : une poignée de sièges échappe toujours
   — un stand sans typologie, un siège vide — et une règle qui en pénalise
   98 % est tout aussi constante. Constante nommée, une seule. Avec un
   plancher d'échantillon pour le seul plancher *sans motif* : en dessous de
   dix éléments évalués le ratio ne prouve rien — une correspondance sur une
   fait 100 % — et une édition en cours de saisie signalerait presque toutes
   ses règles. Un plancher que le référentiel explique ne passe pas par là :
   « aucun souhait déclaré » est un fait, pas une inférence.
3. **La donnée absente est lue dans le problème analysé**, pas dans une
   dépendance de plus : souhaits, appréciations, référents, niveau
   au-dessus de débutant. Elle n'est nommée que si le référentiel n'en
   contient effectivement aucune ; un plancher que rien de tel n'explique
   est signalé quand même, sans lien — une règle inadaptée à l'édition est
   tout aussi constante. Les absences qui rendent une règle *inerte* (zéro
   stand premium, zéro emplacement, zéro mineur) ne sont pas des planchers :
   la règle ne coûte rien, « satisfaite » est exact, et les signaler
   brouillerait le signal.
4. **Le score hors plancher voyage avec le score brut** — sur Contraintes,
   sur le récapitulatif du Solveur, dans le KPI de chaque résolution, donc
   dans le Comparateur A/B et l'Autopsie. Un instantané pris avant la mesure
   le porte absent, jamais à zéro : non mesuré n'est pas nul, la règle de
   `violationsParContrainte`.
5. **Rien n'est désactivé.** La règle reste active, à son poids ; c'est
   l'organisateur qui saisit la donnée (le lien y mène), baisse le poids,
   ou éteint la règle en connaissance de cause. Signalé, pas décidé.

## Conséquences

- Deux champs de plus par contrainte et trois sur la vue (`postesEvalues`,
  `plancher` ; `scoreHorsPlancher`, `plancherMedium`, `plancherSoft`), deux
  sur le KPI persisté en `jsonb` — aucune migration.
- Le score medium reste ce qu'il est : la mesure ne le corrige pas, elle le
  double d'une lecture. Comparer deux résolutions du même jeu de données se
  fait sur le score hors plancher.
- Une nouvelle règle medium ou soft demande une ligne de plus, son grain,
  dans `ConstraintFloorRules` — le test le rappelle.
