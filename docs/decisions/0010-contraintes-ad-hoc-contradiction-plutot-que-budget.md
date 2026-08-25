# 0010 — Contraintes ad hoc : détecter la contradiction, pas plafonner le nombre

- **Statut** : accepté, implémenté
- **Date** : août 2026
- **Portée** : référentiel, diagnostic

## Contexte

Les contraintes ad hoc sont des exceptions saisies à la main
(`INDISPONIBILITE_FORCEE`, `INCOMPATIBILITE`, `AFFECTATION_FORCEE`), toutes
appliquées en contraintes **dures**, à la même priorité que les règles légales.
Rien ne limitait leur accumulation, rien ne détectait qu'elles se
contredisaient.

Le mode d'échec est silencieux et coûteux : quelques exceptions saisies au fil
de l'eau finissent par rendre le planning infaisable, le solveur rend un score
dur négatif, et rien ne désigne la cause. L'utilisateur conclut « le solveur
n'y arrive pas » alors que c'est la combinaison de ses propres saisies qui est
insatisfiable.

## Options envisagées

**(A) Un plafond numérique.** Refuser — ou signaler — au-delà de N exceptions
par édition. Simple à implémenter, immédiat à expliquer.

**(B) Une bascule progressive vers un poids soft.** Au-delà d'un seuil, les
exceptions les plus récentes cessent d'être dures et deviennent des
préférences, ce qui garantit une solution réalisable.

**(C) La détection de contradiction.** Refuser à la saisie les combinaisons
d'exceptions qui ne peuvent pas être satisfaites ensemble, et attribuer
l'infaisabilité restante aux exceptions qui y contribuent.

## Décision

**(C)**, et **(A)** comme **(B)** sont écartées.

**(A) n'a pas de sens métier.** Vingt exceptions cohérentes se résolvent sans
peine ; deux exceptions contradictoires suffisent à rendre le planning
infaisable. Le nombre n'est pas la grandeur qui prédit l'échec, donc aucun
seuil ne peut être justifié — et un refus arbitraire au vingt-et-unième
empêcherait une saisie légitime en donnant une raison fausse.

**(B) trahit le contrat de la contrainte dure.** Une exception qui bascule en
soft est une exception que le solveur peut ignorer sans le dire : l'organisateur
croit avoir forcé une affectation et découvre après coup qu'elle n'a pas été
tenue. C'est exactement l'échec silencieux que ce chantier cherche à supprimer,
déplacé d'un cran. Le modèle du domaine le pose déjà comme invariant : les
types prescriptifs ne se démotent jamais en medium ou soft.

**(C) attaque la cause.** La contradiction est mécaniquement décidable à partir
de la sémantique que le solveur donne à chaque type, elle se refuse à la saisie
avec un message qui nomme les deux exceptions en cause, et ce qui subsiste dans
le référentiel est remonté comme cause bloquante avant tout solve.

## Conséquences

- La validation ne signale que ce qui est **certainement** insatisfiable. Une
  exception refusée à tort coûte à l'utilisateur une saisie à laquelle il avait
  droit, sans contournement possible ; une exception litigieuse laissée passer
  coûte un solve. Les combinaisons ambiguës — une affectation forcée que deux
  animateurs peuvent satisfaire, une indisponibilité plus étroite que le
  périmètre forcé — passent donc délibérément.
- Le contrôle vaut pour **toute** écriture : la saisie unitaire comme l'import
  d'un scénario. Un fichier ne peut pas installer une combinaison que le
  formulaire refuse.
- Il reste possible de saturer une édition d'exceptions cohérentes mais
  collectivement infaisables. C'est le rôle du diagnostic, pas de la
  validation : le solveur rend alors un score dur négatif, et les exceptions
  qui portent les violations sont nommées dans le diagnostic.
- Si l'usage montrait un jour qu'un volume d'exceptions dégrade la convergence
  indépendamment de leur cohérence, la mesure — pas l'intuition — rouvrirait
  (A).
