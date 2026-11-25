# Régler les règles du planning

Vous ajustez ce qui décide du plan : les seuils légaux, l'importance de chaque préférence, la durée du calcul, et vous savez quand une règle est bloquée par une donnée absente.

![La page Règles du planning](/images/fr/regles.webp "Règles du planning")

Tout se règle dans *Règles du planning* (groupe *Planifier*), en trois onglets de réglage : *Légal*, *Qualité* et *Calcul*. Ce sont des réglages d'expert : ils n'apparaissent qu'après un clic sur « Réglages avancés », sous la rangée d'onglets. Rien ne s'écrit avant « Enregistrer », et « Relancer le calcul » est à un clic.

Ces seuils viennent du droit du travail : [ce que le Code du travail impose à un planning](/code-du-travail/) dit ce que le logiciel applique de lui-même et ce qu'il laisse à votre jugement.

## L'onglet Légal

Une ligne par règle obligatoire :

- son nom en clair et l'article qui la fonde ;
- son état ;
- les seuils qui la règlent (durées hebdomadaires, pause, fenêtre repas…), modifiables sur la ligne même ;
- les écarts de la dernière analyse.

Le texte complet de la règle et l'historique de ses réglages s'ouvrent dans un panneau.

Désactiver une règle légale (mineurs, temps de travail, sécurité des mineurs) demande une confirmation, qui rappelle que vous restez l'employeur et le responsable du planning diffusé. L'écran montre en permanence ce qui est désactivé. Rien n'est désactivé à votre place.

## L'onglet Qualité

Le même tableau pour les règles de qualité et les préférences. Chaque règle a une importance en trois positions : faible, normale, forte. Le poids exact est dans le panneau, pour qui le veut.

« Baisser l'importance » n'est jamais le premier geste proposé pour une règle de qualité : le Diagnostic le met toujours en dernier, et il disparaît quand l'importance est déjà au plus bas.

## L'onglet Calcul

- la durée du calcul et l'arrêt quand plus rien ne progresse (voir [Calculer le planning](calculer-planning.md#régler-la-durée-du-calcul)) ;
- l'e-mail de fin de résolution ;
- le début de la soirée, la seule soirée de l'application ;
- la typologie ninja.

## Quand une règle ne peut pas s'améliorer : le plancher

Une règle qui pénalise la quasi-totalité de ce qu'elle évalue mesure en réalité une donnée absente du référentiel : aucun souhait déclaré, aucun référent. Ses points sont un **plancher** qu'aucune résolution ne fera bouger.

La page Règles du planning le signale, nomme la donnée et mène à sa saisie. Le score hors plancher s'affiche à côté du score brut dans le récapitulatif du Solveur et dans les Versions du plan.

## Retrouver qui a changé un poids

Chaque changement de poids ou d'activation d'une règle est gardé avec ses valeurs avant et après, sa date et son origine (écran, assistant, scénario, duplication), sans rien de nominatif, aussi longtemps que l'édition. Le panneau d'une règle le pose à côté des résolutions qui ont suivi et de leurs écarts à cette règle.

## Pauses, jours d'affilée et trajets

Trois règles du calcul se règlent directement sur leur ligne.

### La pause : un trou ou un relais

Une pause légale due se prend de deux manières, et l'outil n'en connaît pas de troisième :

- un **trou** d'au moins la durée réglée dans la grille ;
- un **relais** : un collègue du même stand tient une place pendant toute la pause.

Sans l'un ni l'autre, le plan est refusé, comme il le serait pour un dépassement d'heures. Vous n'avez rien à déclarer : ouvrez la place, le calcul s'en sert.

En contrepartie, la pause est du repos, donc **déduite partout** : plafonds quotidien et hebdomadaires, heures et équité du planning par personne, Besoin, indicateurs. Il y a un seul nombre d'heures, le même sur tous les écrans.

### Les jours d'affilée

La règle limite les jours travaillés consécutifs : **huit par défaut, et le nombre se règle** sur la ligne de la règle. Le calcul pénalise les jours en trop sans bloquer, aucun texte n'imposant un décompte glissant. Une organisation qui veut un plafond bloquant l'allume pour son édition depuis la même page.

Le seuil vaut mieux que le dosage : sur une grille qui réclame presque tout le monde tous les jours, un jour de plus ou de moins décide si un planning sans écart existe, là où aucun poids ne le peut.

### Le temps de trajet entre deux postes

Deux postes d'une même personne sur deux emplacements éloignés doivent laisser le temps d'aller de l'un à l'autre à pied. Le temps de marche se calcule à partir de la distance, d'un facteur de détour et d'une vitesse de marche, **réglables sur la ligne de la règle**, avec une tolérance. Le calcul pénalise chaque minute qui manque au battement.

Le Rail et la timeline marquent d'un chevron rouge un enchaînement trop serré (« 20 min à pied, 10 min de battement »), ou un trajet pris sur la pause. La page Problèmes les liste, avec le lien vers le Rail du jour. La fiche d'un emplacement donne le temps de marche vers chacun des autres.

## Le planning déjà publié bouge le moins possible

Une fois le planning envoyé, chaque personne déplacée coûte au calcul : un recalcul après un changement tardif bouge le minimum de personnes déjà prévenues.

## Pourquoi chaque règle existe

Le nom, l'article de loi et la raison de chaque règle sont dans le catalogue de l'application (écran Règles du planning). La raison d'être de chacune, ce qu'elle coûte et ce qui a été écarté sont dans [Les règles du solveur](../comprendre/pourquoi-ces-regles.md).
