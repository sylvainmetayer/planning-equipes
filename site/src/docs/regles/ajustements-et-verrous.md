# Poser des exceptions et des verrous

Vous imposez ou interdisez une affectation avant le calcul, et vous gelez une partie du plan pour que le prochain calcul n'y touche plus.

Les **ajustements manuels**, les **verrouillages** et les **consignes** sont les trois premiers onglets de *Règles du planning* : ce que le prochain calcul doit respecter. Après chaque saisie, relancer le calcul est proposé sur place :

- une *correction* après une consigne, qui ne rouvre que les places qu'elle a ôtées ou ajoutées ;
- un *calcul complet* repartant du plan après un ajustement ou un verrou.

## Ajustements manuels : placer ou écarter quelqu'un avant le calcul

Un ajustement est une exception ponctuelle, tracée avec sa raison. C'est l'inverse d'un verrou, qui fige après coup. Les types :

- indisponibilité forcée ;
- incompatibilité entre deux personnes ;
- affectation imposée ;
- paire à privilégier.

1. Ouvrez *Règles du planning*, onglet *Ajustements*.
2. Commencez la saisie par la personne ; la liste se filtre aussi sur une personne.
3. Indiquez le type, la raison et les dates.

**Les contradictions sont refusées à l'enregistrement.** Un ajustement qui ne peut pas tenir en même temps qu'un autre déjà saisi est refusé avec un message qui nomme les deux, plutôt qu'un planning déclaré infaisable plusieurs minutes plus tard sans que rien n'en désigne la cause.

Si l'ajustement place quelqu'un là où aucune place ne peut le tenir (un mineur forcé sur une nuit, un jour férié ou un stand réservé aux majeurs, quelqu'un dont l'emploi du temps est verrouillé), il est signalé à la saisie et nommé dans les causes bloquantes.

## Verrouillages : geler une partie du plan

Un verrou gèle une personne, un stand, une journée ou un créneau. La prochaine résolution n'y touche plus et optimise le reste. Un verrou est un geste sur un plan déjà calculé : il conserve ce qu'une résolution a produit, sans rien dire de ce qui devrait s'y trouver.

1. Ouvrez *Règles du planning*, onglet *Verrouillages* (ou verrouillez depuis la fiche d'une personne, le panneau d'un siège, ou la page Fragilité).
2. Avant de poser un verrou, l'écran compte les affectations qu'il figera et mène à la journée qui les montre.
3. Confirmez.

## Agir sur plusieurs lignes d'un coup

Ajustements et verrouillages se cochent à plusieurs (« Tout sélectionner » suit le filtre) et se suppriment ou se déverrouillent d'un geste, après une seule confirmation. Le compte rendu dit ce qui est passé et ce qui a été refusé.

## Les arrivées groupées validées

Une arrivée groupée validée (voir [Collecter les disponibilités](collecter-disponibilites.md#traiter-les-demandes-de-covoiturage)) devient un ajustement manuel que seule l'organisation peut annuler, depuis l'onglet Covoiturage de la page Disponibilités. La page des ajustements ne le modifie ni ne le supprime.

## Les consignes de l'autorité

Le troisième onglet, *Consignes*, porte les fermetures imposées de l'extérieur : voir [Appliquer une consigne de l'autorité](appliquer-une-consigne.md).
