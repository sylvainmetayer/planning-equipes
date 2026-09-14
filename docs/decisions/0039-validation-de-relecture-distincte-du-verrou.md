# 0039 — Une validation de relecture, distincte du verrou

- **Statut** : accepté, implémenté
- **Date** : septembre 2026
- **Portée** : relecture du plan, résolution, publication, MCP, IHM

## Contexte

Le verrouillage est un **mécanisme** destiné au solveur
([0003](0003-verrouillage-par-pin-natif.md)) : il fige des sièges, il ne dit
pas qu'un humain a relu quoi que ce soit. Entre le premier planning faisable et
la publication il y a une semaine de relecture que rien n'outillait. Un
organisateur qui relit douze journées n'avait aucun moyen de marquer où il en
était, ni de savoir ce qui avait bougé depuis son passage : la stabilité du plan
publié ([0025](0025-stabilite-du-plan-publie.md)) ne compte qu'**après**
publication, et avant elle chaque résolution réécrit tout en silence.

Le contournement observé est de verrouiller pour marquer la relecture. Il coûte
cher : figer une journée relue empêche le solveur d'améliorer le reste du plan
autour d'elle, et une équipe qui relit au fil de l'eau se retrouve à ne plus
pouvoir résoudre du tout.

## Options envisagées

**(A) Un drapeau « relu » porté par le verrou.** Écarté : c'est exactement la
confusion à défaire. Un verrou sans relecture (figer une contrainte terrain) et
une relecture sans verrou (accepter en continuant d'optimiser) sont tous deux
des cas courants ; un seul objet à deux sens n'aurait su exprimer ni l'un ni
l'autre.

**(B) Un circuit d'approbation à plusieurs rôles.** Écarté ici : il suppose des
comptes nommés, qui n'existent pas encore, et il répond à une autre question —
*qui a le droit d'accepter* — là où celle-ci est *où en suis-je de ma
relecture*.

**(C) Un état de revue autonome, avec le verrou offert à côté.** Retenu.

**(D) Le même état, avec un stand facultatif en second axe**, pour déléguer la
relecture aux responsables de stand. Livré un temps (V80), puis retiré (V81)
avant la fusion : les deux grains ne s'entendaient avec rien de ce qui entoure
la validation. La case « Verrouiller aussi » figeait la journée entière alors
que l'écran était filtré sur un stand — il n'existe pas de verrou « ce stand ce
jour-là ». Le compteur, qui compte des journées, devait dire les stands à part
dans une seconde phrase. Et une résolution retirait la validation d'un stand
sur lequel rien n'avait bougé, puisque le retrait se décide par journée. Relire
stand par stand reste possible avec les filtres ; seule l'acceptation est
d'une journée entière.

## Décision

Une **validation** porte sur une **journée entière** de l'édition, quels que
soient les filtres affichés quand on la pose. Elle porte la date
de lecture, son auteur et un commentaire libre. Relire une journée déjà acceptée
**remplace** la lecture précédente : ce qui vaut est la lecture qui tient.

Elle **ne fige rien**. L'écran propose de poser un verrou de journée en même
temps ; il ne le pose jamais tout seul, et retirer la validation laisse le
verrou en place. Les deux répondent à deux questions différentes et restent
deux objets.

Avant d'accepter, l'écran affiche quatre **prérequis** de cette seule journée —
zéro écart dur, aucun siège vide, toutes les pauses relayées, aucun poste
irremplaçable — **repris des analyses existantes** (Problèmes, Pauses,
Fragilité) filtrées sur la date, jamais recalculés autrement : cet écran et ces
trois-là ne peuvent pas raconter deux histoires sur la même journée. Un prérequis
que le serveur ne sait pas mesurer se dit « non vérifié », jamais « satisfait ».
**Aucun prérequis ne bloque** : l'organisateur qui sait pourquoi un siège reste
vide accepte la journée et l'écrit dans le commentaire. Ce que l'écran lui doit,
c'est de ne pas pouvoir accepter *sans savoir*.

Une **résolution qui déplace un siège d'une journée validée retire la
validation** : personne n'a relu ce que le solveur vient d'écrire, et le
récapitulatif le dit (« 2 journées validées ont bougé »). L'exception est
étroite et délibérée : une journée portant **aussi** un verrou de type `JOUR`
garde sa validation, puisque le solveur n'a rien pu y déplacer. Un verrou plus
petit qu'une journée — un stand, un créneau, un animateur — n'en fige qu'une
partie, et le reste de la journée est précisément ce que personne n'a relu.

## Conséquences

- Le panneau de publication dit combien de journées non validées partiraient.
- La bannière de progression apparaît là où l'on regarde une journée du plan :
  Journée, Calendrier, Solveur ; l'« État de l'édition » gagne une ligne entre
  Problèmes et Publication.
- Un seul grain partout : la validation, le verrou proposé à côté, le retrait
  après résolution et la progression comptent tous des journées. Le panneau le
  rappelle quand la page Journée est filtrée.
- L'auteur est le compte d'administration unique tant qu'il n'y a pas de table
  d'utilisateurs ; la colonne existe pour que des comptes nommés n'aient pas à
  migrer les lignes déjà écrites.
- Une validation laissée par une journée dont la grille ne porte plus de créneau
  n'est pas comptée : la bannière dit « N sur 12 » et ne doit jamais pouvoir
  dire « 13 sur 12 ».
