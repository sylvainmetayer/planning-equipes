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

## Ce qui restait ouvert : la vacation supprimée

Cette section a longtemps dit ceci : *la suppression d'un créneau retire le
siège des deux côtés à la fois, elle ne produit donc aucun écart et ne prévient
personne.* C'était vrai, et resté théorique tant que la grille ne bougeait plus
après publication. Modifier un jour déjà publié — plan dégradé, fermeture
imposée, réapplication d'une journée type — l'a rendu concret, et la personne
que la publication sautait était exactement celle qui venait de perdre son
mardi après-midi.

La cause n'était pas le calcul de l'écart, elle était dans ce que le côté
publié savait dire. Le contenu de l'instantané portait `posteId`, `standId`,
`creneauId` et les heures effectives : que des identifiants, donc rien qui
décrive une vacation dont le créneau n'existe plus. Le plan publié se
ré-assemblait contre le référentiel du jour, et un siège nommant un créneau
disparu était écarté — au même instant que dans le plan de travail.

**L'instantané date désormais chaque affectation**, et porte la fenêtre du
créneau avec elle. Le côté publié cesse de dépendre du référentiel du jour : un
créneau supprimé laisse un siège du seul côté publié, ce qui est précisément la
définition d'un retrait. La personne entre dans le décompte, reçoit « mardi
14/07 : Cirque 14h-18h (retiré) », et son espace rejoue la phrase.

Deux conséquences, assumées :

- **La vacation reste visible dans l'espace tant que la publication n'a pas
  annoncé son retrait** — y compris dans le PDF, l'abonnement ICS et le rappel
  de la veille. C'est la règle de cette décision, pas une exception : l'espace
  suit le planning publié, et une promesse ne cesse pas d'avoir été faite parce
  que la grille a bougé. Ce qui la retire, c'est la publication qui le dit.
- **Un instantané capturé avant ce changement ne porte pas de date** et retombe
  sur la résolution d'avant : ses sièges sont résolus contre le référentiel du
  jour, et écartés quand leur créneau a disparu. Rétrocompatible sans
  migration — au prix d'une publication dont les retraits restent muets, le
  temps d'une capture.

Ce que cette décision ne prétend toujours pas faire : un siège dont le **stand**
a disparu reste écarté. Le trou est de même nature, sa source de vérité est
autre — l'instantané ne porte pas le nom du stand — et il n'est pas traité ici.
