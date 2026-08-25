# 0011 — Publier, plutôt qu'envoyer à tous

- **Statut** : accepté, implémenté
- **Date** : août 2026
- **Portée** : persistance, service, API, espace animateur, frontend
- **Voisines** : [0007](0007-instantanes-contenu-denormalise.md) (le support existait déjà)

## Contexte

Le planning persisté bougeait après sa communication, et rien ne le disait. Un
remplacement appliqué depuis « Pourquoi lui ? » réécrit un siège en un clic ; un
échange validé dans la foire, un forçage, une résolution incrémentale font de
même. L'administrateur qui avait envoyé les plannings la veille n'avait aucun
moyen de savoir qu'il devait recommuniquer, ni à qui.

Le réflexe aurait été de reprendre le bandeau « données périmées » du solveur.
Il répond mal à cette question. Pour le solveur, elle est binaire — ce planning
est-il postérieur à la dernière modification du référentiel ? Pour la
communication, le même bandeau dirait « quelque chose a changé » à quelqu'un qui
a déplacé une personne sur cent cinquante, sans dire laquelle. **Un
avertissement qu'on ne peut pas satisfaire précisément est un avertissement
qu'on apprend à ignorer.**

## Décision

**Distinguer le planning publié du planning de travail.** « Ce que les gens ont
reçu » est exactement un instantané, marqué publié : aucune façon de stocker un
planning n'a eu à être inventée, la 0007 la portait déjà.

Il en découle quatre choses.

**L'action cesse d'être un avertissement et devient un travail en attente.** Le
bouton porte son décompte — « Publier — 3 personnes concernées ». Le compteur
descend à zéro et y reste ; il n'y a rien à ignorer. Le décompte est **par
personne** : renommer un stand ne réveille personne, échanger deux vacations
réveille exactement deux personnes.

**L'espace animateur suit le planning publié.** Ce qu'un animateur voit est ce
qu'on lui a envoyé. Avant la première publication l'espace est vide, et il le
dit : un repli sur le planning de travail recréerait exactement l'incohérence
qu'on supprime, et publier d'office à la migration ferait affirmer à
l'application qu'elle a communiqué un planning qu'elle n'a peut-être jamais
envoyé.

**Une décision d'échange est annoncée par la publication qui la porte**, pas au
moment où elle est prise. Accepter un échange change le planning de travail, pas
celui que le demandeur a reçu : le prévenir tout de suite lui promettrait un
planning que son espace ne montre pas encore.

**Aucun garde-fou temporel.** Le seul refus est de concurrence : publier pendant
une résolution figerait un planning sur le point d'être réécrit.

## Le garde-fou temporel, et pourquoi il est écarté

C'était l'arbitrage le moins évident, donc celui qui mérite d'être écrit.

| Option | Ce qu'elle coûte |
| --- | --- |
| Fenêtre de stabilité (« ne rien proposer tant que ça bouge ») | Le décompte **ment** juste après une édition : « 0 personne » alors qu'il y a à publier. Pire que le bruit évité |
| Rappel « rien de publié depuis 2 h » | C'est précisément l'avertissement rejeté plus haut : il se déclenche quand on est peut-être délibérément en cours d'édition, exige un ordonnanceur, un état par édition et un mécanisme d'ignorer — donc une chose qu'on apprend à ignorer |
| Aucun minuteur | Rien |

Le raisonnement tient en une phrase : **le décompte n'est pas une alerte, c'est
un compteur de travail en attente**, comme des messages non lus. Le voir passer
de 1 à 3 pendant qu'on édite n'est pas du bruit, c'est l'état réel. Le mode de
défaillance que le garde-fou visait — « un avertissement qu'on apprend à
ignorer » — appartient aux avertissements, pas aux compteurs ; la refonte l'avait
déjà supprimé.

Le coût de calcul ne justifie pas davantage un minuteur : l'aperçu se lit **à la
demande** (ouverture de l'écran, dépliement de la liste, fin de résolution),
jamais en boucle. Il n'y a aucune sollicitation périodique à espacer.

Ce que le rappel « 2 h » voulait vraiment dire — *au fait, quand as-tu publié
pour la dernière fois ?* — est affiché comme un fait à côté du bouton :
« Dernière publication le … », ou « Jamais publié ». La même information, sans
le harcèlement.

## Conséquences

- Un instantané publié sort de la purge de rétention et refuse d'être supprimé
  (`409`) : c'est la référence que lit l'espace animateur.
- Une trace nominative (`publication_destinataire`) dit qui a été prévenu de
  quoi et quand, y compris les injoignables. Utile côté RGPD : on n'écrit
  qu'aux personnes concernées, et la trace disparaît avec l'édition qu'elle
  documente.
- Le renvoi individuel renvoie le planning **publié** : deux versions ne
  circulent jamais en même temps. Il est refusé tant que rien ne l'a été.
- Un envoi en échec ne revient pas dans le décompte suivant — le compteur mesure
  ce qui a changé, pas ce qui a été délivré. Le rapport nomme les manqués, et le
  renvoi individuel est le rattrapage.
- Les jeux d'essai qui lisent l'espace doivent publier : une base qui n'écrit
  que `poste_affectation` laisse, à juste titre, tous les espaces vides.

## Ce qui reste ouvert

La suppression d'un créneau retire le siège des deux côtés à la fois : elle ne
produit donc aucun écart et ne prévient personne. Dire « la journée que vous
aviez n'existe plus » est une autre fonctionnalité, avec sa propre source de
vérité — elle n'est pas traitée ici.
