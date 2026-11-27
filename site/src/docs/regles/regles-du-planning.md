# Régler les règles du planning

Vous ajustez ce qui décide du plan : les seuils, le niveau de chaque règle d'organisation, et vous savez quand une règle est bloquée par une donnée absente.

![La page Règles du planning](/images/fr/regles.webp "Règles du planning")

Tout se règle dans *Règles du planning* (groupe *Planifier*), dans l'onglet *Règles*, en trois blocs. Rien ne s'écrit avant « Enregistrer », et « Relancer le calcul » est à un clic.

Ces règles viennent du droit du travail : [ce que le Code du travail impose à un planning](/code-du-travail/) dit ce que le logiciel applique de lui-même et ce qu'il laisse à votre jugement.

## Obligations légales

Une ligne par règle imposée par la loi, en lecture seule :

- son nom en clair et l'article qui la fonde ;
- les seuils qu'elle lit, tels qu'ils sont enregistrés (durées hebdomadaires, pause, fenêtre repas…) ;
- les écarts de la dernière analyse ;
- un lien « Régler dans Paramètres », qui mène à *Paramètres › Légal*.

Les seuils ne se modifient qu'à cet endroit : une seule place pour les écrire, aucune double saisie. Le texte complet de la règle et l'historique de ses réglages s'ouvrent dans un panneau, au clic sur son nom.

## Politique de l'organisation

Les règles que votre organisation se donne : l'équilibre de la charge, la continuité sur un stand, la coupure repas, l'encadrement des mineurs par un majeur. Chacune a un **niveau**, que vous choisissez pour l'édition :

- **Obligatoire** : un plan qui l'enfreint n'est pas livrable, comme pour une règle légale. Une règle obligatoire est comptée, pas pesée : l'écran n'offre alors aucun poids ;
- **Forte**, **Normale** ou **Légère** : une préférence, que le calcul fait peser en conséquence. Le poids exact (de 1 à 500) est dans le panneau de la règle, pour qui le veut ; « Normale » est le niveau livré.

Rendre obligatoire une règle que l'édition ne peut pas tenir donne un plan impossible : le diagnostic des échecs durs la nomme. Désactiver une règle protégée (la coupure repas, l'encadrement des mineurs) demande une confirmation, qui rappelle que vous restez l'employeur et le responsable du planning diffusé. L'écran montre en permanence ce qui est désactivé. Rien n'est désactivé à votre place.

Deux règles sont rangées sous un même titre, *Dispersion sur la journée* (les changements d'emplacement éloignés et le nombre d'emplacements visités dans la journée), chacune gardant sa ligne, son niveau et son interrupteur. Ce n'est qu'un regroupement à l'écran : aucune règle n'est fusionnée, et « Trajets trop courts entre deux postes » reste à part, sous son propre titre. L'équilibre de la charge et celui des créneaux pénibles ne sont pas regroupés : l'un est une règle d'organisation, l'autre une préférence du bloc Avancé, et un titre ne traverse pas deux blocs.

« Baisser l'importance » n'est jamais le premier geste proposé pour une règle d'organisation : le Diagnostic le met toujours en dernier, et il disparaît quand le poids est déjà au plus bas.

## Avancé

Le reste, replié par défaut : l'affectation, les ajustements, les verrouillages, les préférences d'agrément. Ouvrez-le par « Réglages avancés ». Une règle y est dosée en trois positions (faible, normale, forte) et peut être éteinte. Un lien vers une règle de ce bloc l'ouvre tout seul.

## Les seuils, dans Paramètres

*Paramètres* range les réglages de l'édition en quatre sections, chacune un onglet : *Notifications* (les rappels et relances automatiques), *Légal*, *Organisation* et *Calcul*. Les trois dernières sont des réglages d'expert, derrière « Réglages avancés ».

- *Légal* et *Organisation* listent, sur un seul formulaire, tous les seuils que les règles lisent : plafonds hebdomadaires, pause, coupure repas, jours d'affilée, trajets, services tardifs. Ce sont les seuls endroits où ils s'écrivent ; chaque ligne de *Règles du planning* y renvoie. Rien ne s'écrit avant « Enregistrer ».
- *Calcul* affiche la durée du calcul et l'arrêt quand plus rien ne progresse, sans pouvoir les modifier : ils sont réglés pour toute l'instance (voir [Calculer le planning](calculer-planning.md#régler-la-durée-du-calcul)). Il porte aussi l'e-mail de fin de résolution, le début de la soirée, la seule soirée de l'application, et la typologie polyvalente.

Les anciens onglets *Légal* et *Qualité* de *Règles du planning* mènent à l'onglet *Règles*, l'ancien onglet *Calcul* (`/regles?onglet=calcul`) à *Paramètres › Calcul*.

## Quand une règle ne peut pas s'améliorer : le plancher

Une règle qui pénalise la quasi-totalité de ce qu'elle évalue mesure en réalité une donnée absente du référentiel : aucun souhait déclaré, aucun référent. Ses points sont un **plancher** qu'aucune résolution ne fera bouger.

La page Règles du planning le signale, nomme la donnée et mène à sa saisie. Le score hors plancher s'affiche à côté du score brut dans le récapitulatif de l'onglet *Calculer* et dans les Versions du plan.

## Retrouver qui a changé un poids

Chaque changement de poids ou d'activation d'une règle est gardé avec ses valeurs avant et après, sa date et son origine (écran, assistant, scénario, duplication), sans rien de nominatif, aussi longtemps que l'édition. Le panneau d'une règle le pose à côté des résolutions qui ont suivi et de leurs écarts à cette règle.

## Pauses, jours d'affilée et trajets

Trois règles du calcul ont leurs seuils dans *Paramètres › Organisation*.

### La pause : un trou ou un relais

Une pause légale due se prend de deux manières, et l'outil n'en connaît pas de troisième :

- un **trou** d'au moins la durée réglée dans la grille ;
- un **relais** : un collègue du même stand tient une place pendant toute la pause.

Sans l'un ni l'autre, le plan est refusé, comme il le serait pour un dépassement d'heures. Vous n'avez rien à déclarer : ouvrez la place, le calcul s'en sert.

En contrepartie, la pause est du repos, donc **déduite partout** : plafonds quotidien et hebdomadaires, heures et équité du planning par personne, Besoin, indicateurs. Il y a un seul nombre d'heures, le même sur tous les écrans.

### Les jours d'affilée

La règle limite les jours travaillés consécutifs : **huit par défaut, et le nombre se règle** dans *Paramètres › Organisation*. Le calcul pénalise les jours en trop sans bloquer, aucun texte n'imposant un décompte glissant. Une organisation qui veut un plafond bloquant l'allume pour son édition depuis la même page.

Le seuil vaut mieux que le dosage : sur une grille qui réclame presque tout le monde tous les jours, un jour de plus ou de moins décide si un planning sans écart existe, là où aucun poids ne le peut.

### Le temps de trajet entre deux postes

Deux postes d'une même personne sur deux emplacements éloignés doivent laisser le temps d'aller de l'un à l'autre à pied. Le temps de marche se calcule à partir de la distance, d'un facteur de détour et d'une vitesse de marche, **réglables dans *Paramètres › Organisation***, avec une tolérance. Le calcul pénalise chaque minute qui manque au battement.

Le Rail et la timeline marquent d'un chevron rouge un enchaînement trop serré (« 20 min à pied, 10 min de battement »), ou un trajet pris sur la pause. La page Problèmes les liste, avec le lien vers le Rail du jour. La fiche d'un emplacement donne le temps de marche vers chacun des autres.

## Le planning déjà publié bouge le moins possible

Une fois le planning envoyé, chaque personne déplacée coûte au calcul : un recalcul après un changement tardif bouge le minimum de personnes déjà prévenues.

## Pourquoi chaque règle existe

Le nom, l'article de loi et la raison de chaque règle sont dans le catalogue de l'application (écran Règles du planning). La raison d'être de chacune, ce qu'elle coûte et ce qui a été écarté sont dans [Les règles du solveur](../comprendre/pourquoi-ces-regles.md).
