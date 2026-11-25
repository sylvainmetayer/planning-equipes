# Suivre le jour J

Vous gérez les imprévus de la journée depuis un téléphone (absent à remplacer, place vide à combler), vous affichez la journée sur un téléviseur, puis vous mesurez après coup l'écart entre le prévu et le réalisé.

![L'affichage mural](/images/fr/mural.webp "L'affichage mural de la salle de contrôle")

## Gérer la journée sur l'écran Aujourd'hui

*Aujourd'hui* (groupe *Aujourd'hui*) est l'écran du jour même, pensé pour un téléphone. En tête, la journée en une ligne : jour, stands ouverts, vacations restantes. Des compteurs mènent là où l'on s'en occupe. Aucun calcul n'est relancé.

![L'écran Aujourd'hui](/images/fr/docs/aujourdhui.webp "L'écran Aujourd'hui, le jour de l'événement")

### Remplacer un absent

1. Cherchez la personne par son nom ou son numéro, parmi tout l'effectif.
2. Marquez-la absente pour la suite de la journée.
3. Lisez la liste de ceux qui peuvent reprendre ses postes, avec leur numéro de téléphone.
4. Appliquez le remplacement.

Si les premiers candidats évalués ne conviennent pas, « Chercher plus loin » poursuit jusqu'à trouver quelqu'un, ou dit que personne ne peut tenir la place sans enfreindre une règle impérative.

Le créneau en cours se répare aussi : le siège est coupé à l'heure qu'il est, ce qui a été tenu reste au nom de l'absent, le reste du créneau est remplacé.

Les places ouvertes depuis ce matin sont distinguées des trous déjà connus à la publication.

### Prévenir les personnes concernées

« Prévenir les N personnes » publie le plan, chaque espace montrant la nouvelle version, et n'envoie le courriel qu'aux personnes concernées. Les autres restent à prévenir à la publication suivante.

### Quand quelqu'un signale qu'il ne viendra pas

Un animateur peut signaler son absence depuis son espace (voir [L'espace des animateurs](espace-animateur.md#je-ne-pourrai-pas-venir)). Vous le voyez dans les compteurs, constatez l'absence et remplacez, ou classez le signalement.

## Afficher la journée sur un téléviseur

L'**affichage mural** est un écran pour la TV de la salle de contrôle, lisible à trois mètres. Il montre :

- les stands ouverts du jour regroupés par emplacement ;
- la vacation en cours et la suivante ;
- les places libres depuis ce matin en rouge, dites « nouvelles », et les trous déjà connus estompés ;
- les stands fermés sur une seule ligne ;
- en bas, ce qui est nouveau : la vacation qui commence dans la demi-heure avec une place vide, la pause sans relais dans l'heure et la consigne du jour. C'est le même calcul que l'écran Aujourd'hui.

Il se met à jour seul chaque minute, garde son dernier état s'il perd le réseau et tourne ses pages quand tout ne tient pas. Il ne dit « peut différer du planning envoyé » que lorsque c'est le cas, avec le nombre de personnes. Il s'imprime en tableau stands × vacations pour la journée entière.

Pour le brancher :

1. Ouvrez Paramètres, onglet *Affichage mural*, et créez un lien. Le lien s'affiche une seule fois. Vous pouvez le révoquer à tout moment.
2. Ouvrez-le sur le téléviseur. L'écran n'utilise jamais une session d'administration laissée ouverte.
3. Si besoin, limitez le lien à quelques emplacements : une TV par zone.

Par défaut l'écran montre le prénom et l'initiale ; une lettre de plus, puis le nom entier, là où deux personnes se liraient pareil. Depuis les pages Planning et Aujourd'hui, « Afficher sur la TV » mène à ce lien.

## Mesurer le réalisé contre le prévu

L'onglet *Réalisé vs planifié* de la page Aujourd'hui donne, après coup ou jour par jour, l'écart entre le planning publié et ce qui a été tenu, stand par stand et journée par journée :

- sièges publiés, tenus, absences marquées le jour J, remplacements, sièges restés vides ;
- heures publiées, réalisées et perdues ;
- les totaux par stand, par jour, par typologie et pour l'événement.

Quelques règles de lecture :

- Chaque journée écoulée (son dernier créneau terminé) est comparée au planning publié en vigueur à l'ouverture de son premier créneau. Une republication en cours d'événement ne réécrit pas l'écart des journées passées, et la publication qui a servi de référence à une journée écoulée ne peut plus être supprimée.
- Les journées à venir ne comptent pas. Une journée commencée avant toute publication est montrée sans entrer dans les totaux. Avant la première publication, l'écran dit qu'il n'y a rien à comparer.
- Un siège retiré par une consigne ou un créneau supprimé n'est pas une absence.
- Le réalisé est **déclaré** : c'est ce que l'outil a enregistré, pas un pointage de présence, et l'écran le dit.
- Une case ouvre ses vacations, noms compris, à l'écran seulement. L'export CSV ne contient que stands, jours et compteurs.

Une fois l'événement terminé, la mesure de l'édition est calculée puis figée une fois pour toutes (à la première lecture de l'écran, de celui des Versions de l'édition suivante, ou à la suppression de l'édition). Elle survit à l'édition : l'écran Versions de l'édition suivante la rappelle, et l'onglet Besoin affiche, pour information, le taux d'absence et les heures perdues de chaque typologie l'année précédente.

## Après l'événement

Archivez l'édition et préparez la suivante : [Importer et exporter des fichiers](importer-exporter.md#archiver-une-édition-terminée) et [Enchaîner les éditions](enchainer-les-editions.md).
