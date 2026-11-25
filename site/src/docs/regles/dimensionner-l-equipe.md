# Savoir combien de personnes recruter

Vous connaissez l'effectif minimum que vos stands exigent, avant même d'avoir saisi un animateur, et vous savez quelles compétences former en priorité.

Tout se lit dans *Diagnostic*, onglet *Besoin en animateurs*, calculé à partir des seuls stands, créneaux et horaires.

## Lire la fiche de recrutement

L'effectif minimum est **prouvé** : aucune équipe plus petite ne tient les sièges, y compris quand les jours d'affilée sont une règle impérative. La fiche donne :

- le minimum de chaque typologie de jeu, plafond de créneaux par personne compris, et ce qu'il faudrait si personne n'en cumulait deux ;
- combien de majeurs il faut au moins (tout l'effectif un jour férié, les soirées) et combien de mineurs au plus ;
- les indisponibilités que l'équipe absorbe chaque jour et chaque semaine.

Chaque journée porte sa **marge avant résolution** : les personnes disponibles moins les sièges, sur sa tranche la plus serrée, dépliable tranche par tranche. Elle ne dépend d'aucun calcul.

Chaque chiffre mène à l'écran qui le change : une borne mène aux horaires des stands de la journée la plus chargée, ou à la ligne de sa règle dans Règles du planning ; chaque journée mène à ses horaires des stands ; un goulot mène à la grille des compétences.

## Vérifier le minimum par un vrai calcul

1. Dans l'onglet *Besoin en animateurs*, lancez la vérification.
2. Choisissez le nombre de majeurs et de mineurs de l'équipe fictive, et la durée du calcul.
3. Lisez le résultat. Le planning de l'édition n'est pas touché.

Chaque essai est consigné dans l'historique avec son équipe, sa durée et son résultat, pour comparer les tailles essayées. Un filtre de l'historique les isole. Le calcul se fait en dehors de la file : il cède la place à toute résolution qui démarre.

## Savoir qui former

La section « À former » ferme l'onglet. Elle liste, typologie par typologie, celles que le besoin dit en manque ou que la fragilité montre tenues par un seul spécialiste. Pour chacune :

- les chiffres de ces deux lectures et les jours en tension ;
- un lien vers la grille des compétences filtrée sur elle ;
- les candidats, classés par jours en tension où ils sont disponibles, puis par souhait, puis par niveau. Ce sont les débutants et les autonomes de la typologie, jamais un référent ni un polyvalent.

Une typologie sans candidat relève du recrutement. Aucun calcul n'est lancé, rien n'est écrit, et la section s'exporte en CSV.

## Mesurer la taille du problème avant de calculer

Avant de lancer, le Solveur affiche la volumétrie : animateurs, postes à pourvoir, créneaux, ajustements manuels et, surtout, les **heures à pourvoir face au plafond légal** de ce que l'équipe peut travailler, jours d'indisponibilité déduits. Un taux de remplissage proche de 1 annonce un planning infaisable avant qu'une minute de calcul soit dépensée.

## L'an dernier, pour information

L'onglet Besoin affiche aussi, pour chaque typologie, le taux d'absence et les heures perdues de l'année précédente. Cette mesure vient de [Réalisé vs planifié](suivre-le-jour-j.md#mesurer-le-réalisé-contre-le-prévu).
