# 0005 — Polyvalence : garantir une réserve, plutôt que réserver les personnes

- **Statut** : accepté, implémenté
- **Date** : août 2026
- **Portée** : solveur

## Contexte

Un planning saturé est un planning fragile : la première absence oblige à tout
reprendre. On veut donc des plannings **réparables**, en gardant disponibles des
profils capables de tenir plusieurs types de postes.

Rien dans le modèle ne portait cette notion. Le seul matériau disponible est
l'ensemble des compétences de chaque animateur — personne ne comptait combien de
types de postes une personne couvre, et rien ne marquait un type comme rare.

## Options envisagées

**(A) Réserver les polyvalents.** Sur un poste à typologie rare, préférer un
spécialiste à un polyvalent quand les deux sont éligibles, pour garder le
polyvalent disponible ailleurs. Fidèle à l'intention — mais suppose de connaître,
pour chaque poste, l'ensemble des animateurs alternatifs éligibles *au moment de
l'évaluation*. Aucune contrainte existante ne raisonne ainsi, et le coût
d'évaluation croît avec l'effectif.

**(B) Garantir une charge résiduelle.** Exiger qu'un nombre minimal de profils
polyvalents restent sous un plafond de charge chaque jour, sans se soucier de
qui occupe quel poste précis. Réutilise le patron de regroupement déjà en place
pour l'équilibrage de charge.

## Décision

**(B)**, pour une première version exploitable. (A) reste ouverte si l'usage
montre que la réserve garantie ne suffit pas.

Le raisonnement n'est pas que (A) soit fausse — elle est plus fidèle — mais
qu'elle demande un mécanisme d'évaluation sans précédent dans le code pour un
gain que (B) approche déjà.

## Conséquences

- **Tension assumée avec l'équilibrage de charge** : un polyvalent volontairement
  sous-chargé déséquilibre mécaniquement la répartition globale. Les deux règles
  tirent en sens inverse, c'est voulu, et cet arbitrage doit rester écrit — sans
  quoi la prochaine relecture prendra la tension pour un bug.
- La notion de « polyvalent » repose sur un seuil. Un seuil arbitraire non
  documenté est une dette : il doit être paramétrable ou justifié.
