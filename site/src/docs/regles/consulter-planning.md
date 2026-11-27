# Consulter le planning

Vous lisez le planning calculé par jour, par stand, par personne ou sur la carte, et vous trouvez l'information dont vous avez besoin : qui est où, ce qui manque, ce que chacun a reçu.

Tout passe par une seule page, *Planning* (entrée du menu). Tout est porté par l'adresse : une vue, un filtre se partagent par le lien (`?vue=jour|stand|personne|carte`).

## Se repérer dans la page

- Le titre tient sur une ligne. Le jour se choisit sur un mini-mois dépliable dont chaque case dit ses sièges vides, sa relecture, son verrou et sa consigne. « Aujourd'hui » est la date du serveur.
- Quatre vues : **Jour**, **Stand**, **Personne**, **Carte**.
- Les mêmes filtres (stand, animateur, emplacement, typologie) restent d'une vue à l'autre. Saisissez deux lettres pour chercher.
- Un compteur de relecture (sièges vides et pauses sans relais) mène aux problèmes du Diagnostic.
- La consigne du jour tient en une ligne.
- « Imprimer cette journée » met la journée entière sur papier. « Afficher sur la TV » mène au lien de l'affichage mural.

![Le rail d'une journée](/images/fr/rail.webp "Le rail : une ligne par animateur")

## Jour

La vue **Jour** se lit en deux modes, que l'on choisit à côté du bouton « Jour » : le **rail**, ouvert par défaut, et le **tableau**. Le tableau s'écrit dans l'adresse (`vue=jour&mode=tableau`) ; le rail, mode par défaut, n'y écrit rien. La barre de relecture et l'état de la journée (à relire, relue, figée) restent là dans les deux modes.

### Mode tableau

La journée en tableau sur toute la largeur de l'écran : un stand par ligne avec son emplacement, un créneau par colonne, un nom par étiquette. Les sièges vides sont en rouge, un stand fermé en gris. Chaque nom et chaque siège vide ouvre le panneau du siège pour corriger à la main (voir [Relire et retoucher le planning](relire-et-retoucher.md)).

### Mode rail

La même journée vue par personne : une ligne par animateur, ses vacations placées dans le temps, les pauses légales posées dessus (en rouge sans relais), et des lignes vides pour qui reste mobilisable. Chaque vacation ouvre le panneau du siège, d'où « Simuler un changement », puis « Vers un autre siège… », la confie à une autre personne ou l'échange avec la sienne.

## Stand

Une ligne par stand, une colonne par journée : celles de la semaine du jour choisi, ou ce jour seul, ou tout l'événement en couleur seule. Dans chaque case, les personnes qui tiennent les sièges, ou leurs compteurs (2/2), ou la couleur seule. La case est colorée selon sa couverture (pourvue, partielle, vide), et un stand fermé ce jour-là se distingue d'un stand vide.

À droite : les sièges à pourvoir, les pourvus, la part des heures-sièges tenue et les heures. En pied : les totaux de chaque journée. Une case ouvre le panneau du siège sur ce stand ce jour-là.

La vue Stand a un second rendu, la **répartition des heures** : un rectangle par stand, d'autant plus grand que le stand demande d'heures-sièges, coloré selon la part tenue (rayé sous 80 %). Il se regroupe par emplacement ou par typologie, et se restreint à une semaine ou à un jour. Un clic agrandit un groupe, un second ouvre la journée du stand.

## Personne

![Le planning par personne](/images/fr/personne.webp "La semaine de chaque animateur, heures et soirées comprises")

Une ligne par animateur, une colonne par journée (la semaine du jour choisi, ce jour seul ou tout l'événement en couleur seule, comme dans la vue Stand). Dans chaque case, chaque stand tenu sous ses heures. La case est colorée selon que la personne travaille, se repose ou était indisponible ; une soirée et une affectation posée sur une journée indisponible y sont marquées. Une case ouvre le panneau du siège ; le nom mène à la fiche, où se lit le radar d'une personne.

Une **frise** dessine une barre par personne, ce qui tient un événement d'un mois sur un écran.

Ce que chacun a reçu sur tout l'événement (heures, équité, paie) se lit dans *Diagnostic*, onglet *Charge* : voir [Comprendre un planning qui coince](comprendre-un-planning.md).

## Carte

La journée sur la carte des emplacements, avec un curseur temporel. Chaque lieu est coloré selon que ses stands y sont ouverts et pourvus, ouverts avec des places vides, ou ouverts sans personne. Il porte le nombre de personnes présentes à cette heure-là, et sa taille suit l'effectif. Une grille lieu × tranche horaire (ou lieu × jour, au pic de chaque journée) dit combien de monde se trouve où, pour dimensionner l'encadrement et la logistique d'un lieu.

## Ce qui n'est plus sur cette page

- **Par typologie** : la lecture par typologie de jeu (postes, heures, compétents, affectés sans la compétence) est la section *Typologie* de l'onglet *Besoin en animateurs* du Diagnostic.
- **Synthèse par personne** : les heures, l'équité et les deux exports CSV sont l'onglet *Charge* du Diagnostic.
- **Pauses et repas** : les pauses, l'intendance des repas et les enchaînements serrés sont l'onglet *Repas et pauses* du Diagnostic.
- **Changements** : ce qui a bougé depuis la dernière publication se lit dans *Diffuser*, onglet *Envoyer*, une ligne par personne, et ce que le dernier calcul a réécrit dans le résultat de l'onglet *Calculer*.
- **Comparer deux jours** : retirée.

Les anciennes adresses de ces vues mènent à l'écran qui les remplace.
