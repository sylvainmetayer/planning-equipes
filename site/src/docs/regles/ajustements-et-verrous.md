# Poser des exceptions et des verrous

Vous interdisez à deux personnes de travailler ensemble, vous gardez quelqu'un sur un créneau ou vous l'écartez d'un jour, et vous gelez une partie du plan pour que le prochain calcul n'y touche plus.

Les **ajustements manuels**, les **verrouillages** et les **consignes** sont les trois premiers onglets de *Règles du planning* : ce que le prochain calcul doit respecter. Après chaque saisie, relancer le calcul est proposé sur place :

- une *correction* après une consigne, qui ne rouvre que les places qu'elle a ôtées ou ajoutées ;
- un *calcul complet* repartant du plan après un ajustement ou un verrou.

## Ajustements manuels : interdire ou rapprocher avant le calcul

Un ajustement est une exception ponctuelle, tracée avec sa raison. C'est l'inverse d'un verrou, qui fige après coup. Il n'en reste que trois types :

- incompatibilité entre deux personnes (jamais le même créneau) ;
- paire à privilégier ;
- arrivée groupée (covoiturage, de deux à quatre personnes).

1. Ouvrez *Règles du planning*, onglet *Ajustements*.
2. Commencez la saisie par la personne ; la liste se filtre aussi sur une personne.
3. Indiquez le type, la raison et, si le type le permet, le créneau ou le stand.

**Les contradictions sont refusées à l'enregistrement.** Un ajustement qui ne peut pas tenir en même temps qu'un autre déjà saisi (la même paire à la fois incompatible et en affinité) est refusé avec un message qui nomme les deux, plutôt qu'un planning déclaré infaisable plusieurs minutes plus tard sans que rien n'en désigne la cause.

## Comment faire sans affectation forcée

L'affectation forcée et l'indisponibilité forcée n'existent plus depuis la 4.0. Deux gestes les remplacent, et se défont d'un clic :

- **Garder quelqu'un sur un créneau** : placez-le sur le siège depuis la page *Planning* (« Placer », avec « La garder au prochain calcul » cochée), ou verrouillez son siège. Le verrou lie cette personne à ce créneau ; le prochain calcul le respecte, et le reste de sa journée reste libre.
- **Écarter quelqu'un d'un jour** : sur sa fiche, un clic sur le jour, puis « Indisponible ce jour ». Elle ou il n'est plus proposé ce jour-là, et ses sièges du jour sont libérés dans le même geste. Le jour est toujours entier : la fiche ne connaît pas le créneau. Pour une indisponibilité sur une plage de la journée, retirez la personne du siège et verrouillez-la ailleurs.

Un verrou fige ce que porte le plan enregistré, rien de plus : après une réinitialisation ou un import, posez-le de nouveau. Les exceptions forcées d'une édition de la 3.x ont été converties à la migration de la même façon, et ce qui ne pouvait pas l'être est signalé ([Passer de la 3.x à la 4.0](../installer/passer-a-la-4-0.md)).

## Verrouillages : geler une partie du plan

Un verrou gèle une personne sur un créneau, un stand sur une journée, ou une journée entière. La prochaine résolution n'y touche plus et optimise le reste. Pour figer tout le planning d'une personne, utilisez le bouton de sa fiche : il pose un verrou par créneau. Un verrou est un geste sur un plan déjà calculé : il conserve ce qu'une résolution a produit, sans rien dire de ce qui devrait s'y trouver.

1. Ouvrez *Règles du planning*, onglet *Verrouillages* (ou verrouillez depuis la fiche d'une personne, le panneau d'un siège, ou la section *Fragilité* de l'onglet *Risques* du Diagnostic).
2. Avant de poser un verrou, l'écran compte les affectations qu'il figera et mène à la journée qui les montre.
3. Confirmez.

Le verrou animateur + créneau sert aussi à tenir quelqu'un sur un créneau précis : placez-le sur le siège (depuis la journée), puis verrouillez-le sur ce créneau ; le calcul le garde là et ne lui donne pas d'autre siège à ce moment.

## Agir sur plusieurs lignes d'un coup

Ajustements et verrouillages se cochent à plusieurs (« Tout sélectionner » suit le filtre) et se suppriment ou se déverrouillent d'un geste, après une seule confirmation. Le compte rendu dit ce qui est passé et ce qui a été refusé.

## Les arrivées groupées validées

Une arrivée groupée validée (voir [Collecter les disponibilités](collecter-disponibilites.md#traiter-les-demandes-de-covoiturage)) devient un ajustement manuel que seule l'organisation peut annuler, depuis *Animateurs*, onglet *Disponibilités*, puis *Covoiturage*. La page des ajustements ne le modifie ni ne le supprime.

## Les consignes de l'autorité

Le troisième onglet, *Consignes*, porte les fermetures imposées de l'extérieur : voir [Appliquer une consigne de l'autorité](appliquer-une-consigne.md).
