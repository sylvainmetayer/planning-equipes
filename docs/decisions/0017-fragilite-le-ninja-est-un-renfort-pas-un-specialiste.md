# 0017 — Fragilité : le ninja est un renfort, jamais un spécialiste

- **Statut** : accepté, implémenté
- **Date** : août 2026
- **Portée** : analyse hors solveur (`FragiliteAnalyzer`), écran « Fragilité du
  planning »

## Contexte

L'analyse de fragilité répond à deux questions sur le plan déjà persisté, sans
lancer de résolution : qui laisserait des postes sous l'effectif minimum en se
désistant, et quels couples stand × créneau ne reposent que sur une seule
personne compétente.

La seconde question bute sur une règle du modèle. Un animateur détenant la
typologie marquée « ninja » est réputé compétent pour **tous** les stands : c'est
le sens de la polyvalence, et le solveur l'exploite pour le dispatcher partout.
Le prédicat de compétence du domaine répond donc « oui » pour un ninja quel que
soit le stand.

Le compter tel quel ferait disparaître presque toutes les lignes de la seconde
liste dès qu'une poignée de ninjas existent dans le référentiel. Or c'est
exactement le signal recherché : un vide obtenu ainsi serait un artefact de
mesure, pas une bonne nouvelle. À l'inverse, l'ignorer partout mentirait sur la
première question : le siège d'une personne qu'un ninja peut reprendre demain
n'est pas un siège irremplaçable.

## Options envisagées

**(A) Compter le ninja comme n'importe quel compétent, dans les deux
indicateurs.** Fidèle au prédicat du domaine, et faux comme signal : sur un
référentiel de quelques ninjas, la rareté de compétence retombe à presque zéro
et l'écran cesse de désigner quoi que ce soit.

**(B) Ignorer le ninja dans les deux indicateurs.** Restaure la rareté, mais
déclare irremplaçables des sièges qu'un polyvalent reprendrait sans difficulté —
l'écran crie alors partout, ce qui revient à ne plus rien dire.

**(C) Le compter différemment selon la question posée.** La rareté de compétence
ne compte que les *spécialistes* — ceux qui détiennent une des typologies
proposées par le stand — et affiche les ninjas disponibles à côté, comme
renforts. La remplaçabilité d'un siège, elle, les compte pleinement.

## Décision

**(C).** Les deux indicateurs ne posent pas la même question, donc ils ne
comptent pas la même population :

- « ce stand repose-t-il sur une seule personne ? » est une question de
  **recrutement et de formation**. Elle porte sur les spécialistes, et le nombre
  de ninjas disponibles la nuance sans la changer : un stand à un spécialiste et
  trois ninjas reste un stand à un spécialiste, simplement moins grave qu'un
  stand sans aucun ;
- « ce siège peut-il être tenu demain ? » est une question d'**exploitation**.
  Elle porte sur qui le solveur accepterait d'y envoyer, ninjas compris.

La sévérité d'une ligne de rareté distingue les trois cas : aucun spécialiste et
aucun ninja (critique), un spécialiste et aucun ninja (élevée), un cas mitigé par
au moins un ninja (modérée). Quand le référentiel ne marque aucune typologie
ninja, la réponse le dit explicitement, pour qu'un compteur de renforts à zéro
partout ne se lise pas comme une pénurie.

## Conséquences

- La liste de rareté reste lisible sur un référentiel qui compte des ninjas :
  c'est bien le nombre de spécialistes qui la classe.
- Le classement des animateurs par criticité ne repose pas sur le nombre de
  postes qu'un désistement laisserait sous l'effectif — les sièges étant générés
  à l'effectif minimum, tout groupe complet y descend dès qu'un siège se libère,
  ce qui rend le chiffre peu discriminant. C'est le sous-ensemble **sans
  remplaçant possible** qui classe.
- Cette asymétrie doit être dite à l'écran, pas seulement dans le code : deux
  colonnes voisines comptent deux populations différentes, et un lecteur qui
  l'ignore lira l'une pour l'autre.
- Prolonge [0005](0005-polyvalence-reserve-plutot-que-reservation.md), qui fait
  de la polyvalence une réserve garantie plutôt qu'une réservation de personnes :
  ici aussi le ninja vaut comme **capacité de secours**, jamais comme preuve
  qu'une compétence est couverte.
- Ne relève pas de [0014](0014-analyser-le-plan-persiste.md) par hasard : comme
  toute analyse de cette famille, celle-ci se dérive du plan persisté et ne
  résout rien.
