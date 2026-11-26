# Construire le calendrier et les horaires

Vous posez les créneaux de l'édition (les vacations que le calcul remplira) et vous réglez quand chaque stand est ouvert.

Le calendrier et les horaires des stands se tiennent sur la même page, *Calendrier* (groupe *Préparer*). Une **vacation** est une tranche de travail réelle : il n'y a pas d'autre sorte de créneau.

## Poser les créneaux : le générateur et les créneaux à la main

![La grille des horaires des stands](/images/fr/horaires.webp "Les horaires des stands")

La grille se produit par un **générateur** unique, le menu *Générer des créneaux*. Il part de l'une de trois sources :

| Source | Ce qu'elle écrit |
| --- | --- |
| *Depuis les gabarits* | les vacations de chaque gabarit sur les dates qui lui sont affectées |
| *Depuis les horaires des stands* | une coupure à chaque heure où un stand ouvre ou ferme, un créneau sur chaque tranche ouverte ; ajouté à la grille ou à sa place |
| *Depuis un motif récurrent* | des fenêtres répétées sur des jours choisis, ajoutées à la grille |

Quelle que soit la source, le geste est le même : un **aperçu** chiffré qui n'écrit rien et dit ce que vaudrait la grille obtenue, puis l'**application**. Un **gabarit** est la journée type d'avant la 4.0 : les vacations d'une sorte de journée (un « R » marque un relais repas), posées sur un calendrier de dates, qui sont celles de l'édition.

1. Ouvrez *Calendrier* et définissez vos gabarits (« semaine », « samedi »…).
2. Posez-les sur les dates de l'édition.
3. Lisez l'aperçu chiffré : le gabarit s'applique par différence, sans rien détruire.
4. Appliquez.

Retirer une date du calendrier d'un gabarit ne supprime pas ses créneaux : ils restent, la date n'est plus gouvernée. Les gabarits peuvent aussi se reconnaître dans une grille déjà écrite (menu *Générer des créneaux*). Un créneau isolé s'ajoute ou se modifie à la main, dans la liste, comme avant.

La page décrit la grille en place et en affiche le contrôle de cohérence en bandeau, relu seul à chaque visite et après chaque écriture. Chaque créneau compte les stands ouverts et les postes qu'il porte, avec un lien vers les horaires des stands ce jour-là. Les jours fériés sont marqués à la saisie, et le contrôle rappelle qu'un mineur n'y travaille pas quand l'édition en compte un ce jour-là.

## Régler les horaires d'ouverture des stands

L'onglet *Horaires des stands* est une grille stand × créneau, saisie et relue au même endroit comme dans un tableur.

- Collez un bloc, recopiez un jour, reprenez la ligne du dessus, appliquez une case à toute sa colonne.
- Une case vide est fermée.
- Derrière chaque effectif, des barres fines montrent l'ouverture retenue, les horaires du stand et la bande de la consigne du jour.
- Un clic sur une case explique d'où vient son ouverture en une phrase, et mène à la fiche du stand, au gabarit ou à la consigne.
- Un jour férié y est marqué par son nom, sans rien interdire.
- La même grille se saisit par gabarit.

Au-dessus de la grille, la synthèse et les trois erreurs de saisie d'horaires habituelles sont à vérifier avant de lancer un calcul.

Une dernière vue compare deux à huit stands à un stand de référence, jour par jour et case par case, puis ouvre la copie des horaires de la référence vers les autres.

La grille du classeur d'un organisateur peut aussi arriver telle quelle par fichier : voir [Importer et exporter des fichiers](importer-exporter.md#importer-la-grille-des-stands).

## Quand une consigne de l'autorité change les horaires

Un arrêté ou une canicule qui ferme des stands se pose par une consigne, pas en retouchant la grille : voir [Appliquer une consigne de l'autorité](appliquer-une-consigne.md).

## Ensuite

Vérifiez [combien de personnes il vous faut](dimensionner-l-equipe.md), puis [calculez le planning](calculer-planning.md).
