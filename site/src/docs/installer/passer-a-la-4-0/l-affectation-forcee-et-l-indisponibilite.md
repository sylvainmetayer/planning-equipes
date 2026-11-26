---
ordre: 220
---

### L'affectation forcée et l'indisponibilité forcée sont converties

- **Avant (3.x)** : une exception ad hoc avait cinq types, dont l'**indisponibilité forcée** (une personne écartée d'un créneau, d'un stand ou de tout l'événement) et l'**affectation forcée** (au moins une place du périmètre tenue par l'une des personnes nommées).
- **Après (4.0)** : trois types ([ADR 0117](repo:docs/decisions/0117-deux-types-d-exception-les-forcees-sont-converties.md)) : incompatibilité, affinité, arrivée groupée. Les deux règles `indisponibiliteForcee` et `affectationForcee` sont retirées (le nombre de règles passe de 42 à 40) ; leurs noms sont lus avec un avertissement dans un fichier ou une copie de la 3.x. La migration `V161` convertit chaque exception en ce qui la remplace, sans toucher au plan enregistré :

| Exception de la 3.x | Devient |
| --- | --- |
| affectation forcée sur un créneau | un verrou animateur + créneau pour chaque personne nommée qui tient une place du périmètre sur le plan enregistré |
| indisponibilité forcée sur un créneau | le **jour entier** ajouté aux jours indisponibles de la fiche |
| indisponibilité forcée sans créneau | tous les jours qui portent un créneau, ajoutés aux jours indisponibles de la fiche |
| indisponibilité forcée sur un stand ; affectation forcée sans créneau, ou que personne ne tient | rien : signalée dans le journal de migration, gardée dans l'archive `contrainte_ad_hoc_avant_4_0` |

- **Ce qui se perd** : une indisponibilité forcée sur **un seul créneau** devient un **jour entier** : la fiche ne connaît pas le créneau. Si le plan enregistré place la personne sur un autre créneau ce jour-là, il porte, après la migration, un écart dur de disponibilité : il apparaît sur la page Problèmes, et le prochain calcul déplace la personne. Chaque cas est signalé dans le journal de migration (`RAISE WARNING`, un message par exception) ; lisez-le avant de lancer le premier calcul. Le mode jour J marque lui aussi une absence en ajoutant le **jour entier** aux jours indisponibles de la fiche, quel que soit le créneau d'où elle part.
- **Ce que vous faites** : rien pour vos données. L'archive `contrainte_ad_hoc_avant_4_0` (colonne `convertie` : vrai ou faux) n'est lue ni exportée par l'application et la 5.0 la supprime ; une fois la 4.0 en place et le retour à la 3.x exclu, `DROP TABLE` la supprime sans effet. Un script, une invite ou un outil MCP qui créait une exception `INDISPONIBILITE_FORCEE` ou `AFFECTATION_FORCEE` reçoit un refus : verrouillez le créneau de la personne (`verrouiller`, type `ANIMATEUR_CRENEAU`), ou ajoutez le jour à ses `joursIndisponibles` (`modifier_animateur`). Un fichier YAML de la 3.x qui porte l'un de ces types est lu avec la même conversion, une affectation forcée n'étant convertie que si le fichier porte un plan (section `plan:`) où la personne tient la place ; chaque cas est un avertissement du rapport d'import. L'aide intégrée donne le geste : « Comment faire sans affectation forcée ».
