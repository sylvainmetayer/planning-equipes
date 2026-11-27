# Construire le calendrier et les horaires

Vous posez les créneaux de l'édition (les vacations que le calcul remplira) et vous réglez quand chaque stand est ouvert.

Le calendrier se tient sur la page *Calendrier* (groupe *Préparer*), les horaires d'un stand sur sa fiche. Une **vacation** est une tranche de travail réelle : il n'y a pas d'autre sorte de créneau.

## Poser les créneaux : le générateur et les créneaux à la main

La grille se produit par un **générateur** unique, le menu *Générer des créneaux*. Il part de l'une de trois sources :

| Source | Ce qu'elle écrit |
| --- | --- |
| *Depuis les gabarits* | les vacations de chaque gabarit sur les dates qui lui sont affectées |
| *Depuis les horaires des stands* | une coupure à chaque heure où un stand ouvre ou ferme, un créneau sur chaque tranche ouverte ; ajouté à la grille ou à sa place |
| *Depuis un motif récurrent* | des fenêtres répétées sur des jours choisis, ajoutées à la grille |

Quelle que soit la source, le geste est le même : un **aperçu** chiffré qui n'écrit rien et dit ce que vaudrait la grille obtenue, puis l'**application**. Un **gabarit** est la journée type d'avant la 4.0 : les vacations d'une sorte de journée, posées sur un calendrier de dates, qui sont celles de l'édition.

1. Ouvrez *Calendrier* et définissez vos gabarits (« semaine », « samedi »…).
2. Posez-les sur les dates de l'édition.
3. Lisez l'aperçu chiffré : le gabarit s'applique par différence, sans rien détruire.
4. Appliquez.

Retirer une date du calendrier d'un gabarit ne supprime pas ses créneaux : ils restent, la date n'est plus gouvernée. Les gabarits peuvent aussi se reconnaître dans une grille déjà écrite (menu *Générer des créneaux*). Un créneau isolé s'ajoute ou se modifie à la main, dans la liste, comme avant.

La page décrit la grille en place et en affiche le contrôle de cohérence en bandeau, relu seul à chaque visite et après chaque écriture. Chaque créneau compte les stands ouverts et les postes qu'il porte, avec un lien vers la liste des stands. Les jours fériés sont marqués à la saisie, et le contrôle rappelle qu'un mineur n'y travaille pas quand l'édition en compte un ce jour-là.

## Régler les horaires d'ouverture des stands

Les horaires d'un stand se règlent sur sa fiche (*Stands*, puis le nom du stand), et nulle part ailleurs : il n'y a plus de grille stand × créneau.

- Son **modèle récurrent**, en haut, dit son régime normal (« chaque samedi et dimanche de 10 h à 18 h, effectif 2 »).
- Ses **plages datées**, en dessous, sont ce que le calcul lit : une ligne par ouverture ou fermeture, marquée *modèle* ou *saisie*. Vous en ajoutez une à la main, vous retirez une plage ou tout un jour.
- Un jour sans plage, le stand est ouvert toute la journée.
- La fiche dit combien de jours ouverts et de postes le stand porte, avant tout calcul. Un stand jamais ouvert ou une fenêtre hors de tout créneau se compte sur l'accueil et dans le contrôle du calendrier.

La grille du classeur d'un organisateur ne s'importe plus en CSV : elle se réécrit dans le fichier YAML de l'édition, voir [Passer de la 3.x à la 4.0](../installer/passer-a-la-4-0.md#les-imports-csv-des-référentiels-et-de-la-grille-des-stands-sont-retirés).

## Quand une consigne de l'autorité change les horaires

Un arrêté ou une canicule qui ferme des stands se pose par une consigne, pas en retouchant la grille : voir [Appliquer une consigne de l'autorité](appliquer-une-consigne.md).

## Ensuite

Vérifiez [combien de personnes il vous faut](dimensionner-l-equipe.md), puis [calculez le planning](calculer-planning.md).
