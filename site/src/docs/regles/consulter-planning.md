# Consulter le planning

Vous lisez le planning calculé par jour, par stand, par personne ou par typologie, et vous trouvez l'information dont vous avez besoin : qui est où, ce qui manque, ce que chacun a reçu.

Tout passe par une seule page, *Planning* (groupe *Planifier*). Tout est porté par l'adresse : une vue, un filtre, une comparaison se partagent par le lien.

## Se repérer dans la page

- Le titre tient sur une ligne. Le jour se choisit sur un mini-mois dépliable dont chaque case dit ses sièges vides, sa relecture, son verrou et sa consigne. « Aujourd'hui » est la date du serveur.
- Quatre axes : **par jour**, **par stand**, **par personne**, **par typologie**.
- Les mêmes filtres (stand, animateur, emplacement, typologie) restent d'un axe à l'autre. Saisissez deux lettres pour chercher.
- Un compteur de relecture (sièges vides et pauses sans relais) mène aux problèmes du Diagnostic.
- La consigne du jour tient en une ligne.
- « Imprimer cette journée » met la journée entière sur papier. « Afficher sur la TV » mène au lien de l'affichage mural.

![Le rail d'une journée](/images/fr/rail.webp "Le rail : une ligne par animateur")

## Par jour

La même journée se lit de cinq façons.

### Tableau

La journée en tableau sur toute la largeur de l'écran : un stand par ligne avec son emplacement, un créneau par colonne, un nom par étiquette. Les sièges vides sont en rouge, un stand fermé en gris. Chaque nom et chaque siège vide ouvre le panneau du siège pour corriger à la main (voir [Relire et retoucher le planning](relire-et-retoucher.md)).

### Rail

La même journée vue par personne : une ligne par animateur, ses vacations placées dans le temps, les pauses légales posées dessus (en rouge sans relais), et des lignes vides pour qui reste mobilisable. Chaque vacation ouvre le panneau du siège, d'où « Déplacer vers… » la confie à une autre personne ou l'échange avec la sienne.

### Carte

La journée sur la carte des emplacements, avec un curseur temporel. Chaque lieu est coloré selon que ses stands y sont ouverts et pourvus, ouverts avec des places vides, ou ouverts sans personne. Il porte le nombre de personnes présentes à cette heure-là, et sa taille suit l'effectif. Une grille lieu × tranche horaire (ou lieu × jour, au pic de chaque journée) dit combien de monde se trouve où, pour dimensionner l'encadrement et la logistique d'un lieu.

### Pauses et repas

Où tombent les pauses légales, jour par jour et stand par stand : qui sort au plus tard à quelle heure, pour combien de temps, et qui est là pour relayer. À côté, la coupure repas due par chaque journée à cheval sur une fenêtre repas, avec ce qui manque quand la grille ne lui laisse pas de place.

Dessous, l'**intendance** du jour : combien de personnes mangent, à quelle heure et à quel emplacement, midi et soir, sans nommer personne. Tout l'événement s'exporte en CSV. Chaque animateur retrouve ses pauses sur son espace, son PDF et son calendrier.

### Changements

Ce qui a bougé sur la date choisie, siège par siège, par rapport au dernier plan publié ou à la capture automatique d'avant le calcul.

### Comparer deux jours

Deux journées côte à côte, deux samedis par exemple, sur le tableau ou le rail. Un bandeau chiffre pour chacune les sièges, pourvus, vides, pauses sans relais, animateurs et stands, avec l'écart de l'une sur l'autre. Dessous, une ligne par stand ou par personne, alignée des deux côtés, avec les différences signalées et filtrables. La comparaison est en lecture seule et l'adresse la garde pour la partager.

## Par stand

Une ligne par stand, une colonne par journée : celles de la semaine du jour choisi, ou ce jour seul, ou tout l'événement en couleur seule. Dans chaque case, les personnes qui tiennent les sièges, ou leurs compteurs (2/2), ou la couleur seule. La case est colorée selon sa couverture (pourvue, partielle, vide), et un stand fermé ce jour-là se distingue d'un stand vide.

À droite : les sièges à pourvoir, les pourvus, la part des heures-sièges tenue et les heures. En pied : les totaux de chaque journée. Une case ouvre le panneau du siège sur ce stand ce jour-là.

Un second rendu montre la **répartition des heures** : un rectangle par stand, d'autant plus grand que le stand demande d'heures-sièges, coloré selon la part tenue (rayé sous 80 %). Il se regroupe par emplacement ou par typologie, et se restreint à une semaine ou à un jour. Un clic agrandit un groupe, un second ouvre la journée du stand.

## Par personne

![Le planning par personne](/images/fr/personne.webp "La semaine de chaque animateur, heures et soirées comprises")

Une ligne par animateur, une colonne par journée (la semaine du jour choisi, ce jour seul ou tout l'événement en couleur seule, comme par stand). Dans chaque case, chaque stand tenu sous ses heures. La case est colorée selon que la personne travaille, se repose ou était indisponible ; une soirée et une affectation posée sur une journée indisponible y sont marquées. Une case ouvre le panneau du siège ; le nom mène à la fiche, où se lit le radar d'une personne.

Une **frise** dessine une barre par personne, ce qui tient un événement d'un mois sur un écran.

### La vue Synthèse

Ce que chacun a reçu sur tout l'événement, chaque valeur triable :

- heures et écart à la médiane, heures par semaine ;
- soirée (à partir de l'heure réglée dans *Paramètres*, onglet *Calcul*, la seule soirée de l'application), week-end, jours fériés, dimanche ;
- « Nuit (paie) » après 22 h ;
- amplitude maximale d'une journée (du premier début à la dernière fin, trous compris) et nombre de journées au-delà de 12 h, signalées sans aucune règle derrière ;
- postes et postes exigeants ; stands, typologies et emplacements distincts ;
- souhaits et appréciations satisfaits ;
- jours travaillés, de repos et plus longue série.

Les colonnes où tout le monde est à zéro sont masquées tant que vous ne les demandez pas.

### Quel plan sert aux heures

Les heures (celles de la synthèse comme l'export pour la paie) se lisent au choix sur le **plan publié**, ce que les animateurs ont reçu, proposé d'office dès qu'il existe, ou sur le **plan enregistré**. Le fichier dit lequel et de quand. Sur le plan publié, l'écran rappelle que la grille des jours et les autres compteurs restent ceux du plan enregistré.

Deux exports CSV : les heures pour la paie, et l'équité.

## Par typologie

Le planning lu par typologie de jeu, en un tableau : postes, heures, plafond, personnes affectées, compétents, compétents jamais affectés, affectés sans la compétence. Chaque chiffre mène à l'écran où l'on agit : Par stand restreint à la typologie, la page Animateurs filtrée, la grille Compétences sur sa colonne.
