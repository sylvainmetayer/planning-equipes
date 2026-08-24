# 0003 — Figer les affectations validées par le mécanisme natif du solveur

- **Statut** : accepté, implémenté
- **Date** : août 2026
- **Portée** : domaine, solveur, persistance, API, frontend

## Contexte

Une partie du planning est validée — une journée, une zone, une personne — et ne
doit plus bouger quand on relance une résolution sur le reste.

Une contrainte ad hoc d'affectation forcée existait déjà, et paraissait
convenir. Elle ne convient pas : elle force la **présence** d'un animateur dans
un périmètre (un stand, un créneau), pas une affectation précise. Le solveur
reste libre de la satisfaire en déplaçant la personne d'un poste à l'autre à
l'intérieur du périmètre. Ce n'est donc pas un verrouillage, et l'utilisateur qui
la prendrait pour tel verrait son planning « validé » se réorganiser sous ses
yeux.

## Options envisagées

- **Réutiliser l'affectation forcée** — aucun développement, mais une sémantique
  fausse, et une confiance mal placée est pire qu'une fonctionnalité absente.
- **Utiliser le mécanisme de gel natif du solveur** — une entité épinglée est
  exclue de tout mouvement, c'est exactement la garantie recherchée.

## Décision

Le gel natif, porté par un drapeau sur l'affectation, alimenté à la construction
du problème depuis une table de verrous persistés. Les verrous s'expriment au
niveau où l'utilisateur raisonne : un animateur, un stand, une journée, ou un
créneau isolé.

**Un poste non pourvu n'est pas verrouillable.** Geler un trou le rendrait
définitivement non pourvu : le solveur ne pourrait plus jamais le combler, et
l'utilisateur aurait figé une erreur en croyant figer un acquis.

## Conséquences

- Un poste épinglé est exclu de tout mouvement **mais reste évalué**. Un verrou
  peut donc laisser une violation visible à l'écran — volontairement. C'est
  préférable à des règles silencieusement désactivées sur la zone gelée : on
  voit ce que le verrou coûte.
- Le mécanisme est le prérequis de la replanification incrémentale : replanifier
  sur un changement tardif, c'est geler tout ce qui ne bouge pas et ne rouvrir
  que le reste.
