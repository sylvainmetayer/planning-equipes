# Calculer le planning

Vous lancez le calcul automatique, vous le suivez en direct, vous décidez quand l'arrêter, et vous savez quel bouton choisir selon la situation.

![Le planning calculé](/images/fr/planning.webp "Le planning d'une journée, calculé")

Le calcul affecte les animateurs aux places à pourvoir sous trois niveaux d'exigence, dans l'ordre :

1. le cadre légal et les incompatibilités, qui ne sont jamais franchis ;
2. la couverture des postes ;
3. l'équité, les souhaits et le confort.

Il se lance depuis *Solveur* (groupe *Planifier*).

## Avant de lancer : ce que le calcul recevra

L'écran dit ce que le calcul prendra en compte :

- les verrouillages, les ajustements manuels, les consignes et leurs dates ;
- les règles désactivées ;
- les modifications faites depuis la dernière résolution ;
- les déclarations de disponibilité encore en attente, que le calcul **ne verra pas** tant qu'elles ne sont pas appliquées.

Chaque compteur mène à l'écran qui le liste. La faisabilité et les alertes viennent avant ; la volumétrie du problème est repliée en bas. Un compteur d'écart indique les modifications faites depuis le calcul enregistré. Seules les actions qui changent ce que le calcul reçoit sont comptées : un envoi ou un export ne périme rien.

Si une exception posée à la main ne peut être tenue par aucune place (un mineur forcé sur une nuit, un jour férié ou un stand réservé aux majeurs, quelqu'un dont l'emploi du temps est verrouillé), « Calculer » demande confirmation avant de dépenser du temps de calcul sur un problème qui finira par enfreindre une règle impérative. Le manque de monde ne demande rien : le calcul l'atténue encore.

## Choisir comment lancer

Trois boutons, chacun avec sa phrase sur la page. La page annonce d'où partira le prochain calcul et d'où le dernier est parti.

| Bouton | Ce qu'il fait |
| --- | --- |
| « Calculer le planning » | Repart du plan enregistré s'il existe et cherche à l'améliorer. Rien n'est figé hormis les verrouillages |
| « Corriger après un changement » | Fige ce qui tient et ne recalcule que les postes rouverts : quelques dizaines de secondes au lieu de plusieurs minutes. Il montre d'abord ce qui a changé : le compte par famille (« 3 animateurs, 1 stand, 2 créneaux ») et les dernières lignes de l'historique, avec un lien vers celui-ci |
| « Recommencer de zéro » | Abandonne l'acquis, et le dit avant. Sa confirmation liste les relectures de journées qui survivront |

Après un changement tardif, « Corriger après un changement » bouge le minimum de gens déjà prévenus : une fois le planning envoyé, chaque personne déplacée coûte au calcul, et le récapitulatif dit combien la publication préviendrait.

## Suivre le calcul

- La courbe de score en direct. Les trois niveaux de la note (règles impératives, places à pourvoir, qualité) se tracent pendant la résolution, chacun à sa propre échelle. Vous voyez quand le calcul plafonne et vous pouvez l'arrêter à propos. Seule la résolution en cours est tracée.
- Plan faisable depuis… Dès que le calcul tient un planning faisable (toutes les places pourvues, toutes les règles impératives tenues), la page le dit et compte depuis combien de temps. La suite n'améliore plus que la qualité. « Arrêter et garder ce plan » enregistre ce planning sans attendre la fin du budget.
- La file d'attente. Vous pouvez planifier une résolution derrière celle qui tourne : elle démarre d'elle-même, ce qui permet de préparer l'édition suivante sans attendre devant l'écran. La file survit à un redémarrage du serveur ; la résolution qui était en cours est perdue et signalée comme interrompue.
- L'e-mail de fin. Activez-le par édition (Règles du planning, onglet *Calcul*) pour être prévenu dès qu'une résolution se termine, avec l'édition, le score et la faisabilité.

![Le suivi d'un calcul en cours](/images/fr/docs/solveur.webp "L'écran Solveur pendant un calcul")

## Régler la durée du calcul

Chaque édition règle combien de temps un calcul peut durer, et quand il s'arrête faute de progrès. L'arrêt faute de progrès ne joue qu'une fois le planning faisable, jamais tant que des places restent à pourvoir. Ces réglages sont plafonnés par une valeur que fixe l'exploitant de l'instance, affichée dans *Règles du planning*, onglet *Calcul* (derrière « Réglages avancés »). « Revenir au défaut » rend l'édition aux valeurs de l'instance.

## Lire le résultat

Après un calcul, le résultat se lit en phrases. Voir le planning, relire la première journée pas encore relue et publier sont à un clic.

Le score d'avant s'affiche à côté de celui d'après. Relancer un calcul sur un bon planning peut le dégrader sans qu'aucune règle impérative soit enfreinte ; l'écran le dit alors et propose de revenir au plan précédent.

Pour comprendre pourquoi une place reste vide ou une règle cède, voir [Comprendre un planning qui coince](comprendre-un-planning.md). Pour retoucher le résultat, voir [Relire et retoucher le planning](relire-et-retoucher.md).

## Garder des versions du plan

*Solveur*, onglet *Versions du plan*, réunit dans un seul tableau les résolutions terminées et les instantanés de l'édition, du plus récent au plus ancien, avec le résultat de chacun en phrases (rien de nominatif).

- Un **instantané** met un planning de côté et le remet en place plus tard. Une capture est prise automatiquement avant chaque résolution ; en remettre un périmé demande une confirmation qui nomme la cause.
- Deux versions cochées, ou une version et le plan en place, se comparent côte à côte : score, couverture, équité et écarts aux règles, toutes éditions confondues sur demande.
- Chaque résolution garde le dosage des règles sous lequel elle a été lancée, et le comparateur prévient quand deux plans ont été calculés sous des poids différents.
- Les mesures des résolutions survivent à la suppression de l'édition.

## Pour aller plus loin

- Ce que le calcul doit respecter : [Régler les règles du planning](regles-du-planning.md) et [Poser des exceptions et des verrous](ajustements-et-verrous.md).
- Les règles de pause, de jours d'affilée et de trajet : [Régler les règles du planning](regles-du-planning.md#pauses-jours-daffilée-et-trajets).
