# 0127 — Un créneau ne porte plus de couverture de pause

- **Statut** : accepté, implémenté
- **Date** : novembre 2026
- **Portée** : `domain/Creneau`, `VacationType`, schéma (migration `V170`), API, outils MCP, import et export CSV, scénario YAML (version 7), écrans Créneaux, Ouvertures et Calendrier
- **Remplace en partie** : [0032](0032-journees-types-nommees-vacations-fixes.md) et [0037](0037-une-grille-est-toujours-des-vacations.md), qui faisaient du relais repas un attribut du créneau et de la vacation d'une journée type
- **Applique** : [0091](0091-refonte-fonctionnelle-4-0.md)

## Contexte

Un créneau pouvait porter `couverturePause`, le « relais repas » : la génération des sièges n'y ouvrait que la moitié de l'effectif du stand, arrondie au supérieur. Aucune règle du planning ne lisait ce marquage, dans l'un ou l'autre moteur : il ne servait qu'à compter les sièges, ensuite lus par la génération, l'analyse de faisabilité et celle de fragilité. `NoRuleReadsBreakCoverageStructuralTest` le garde : ni `PlanningConstraintProvider`, ni `solver/constraints/*`, ni `solver/handsearch/*` ne nomment la couverture de pause ni les méthodes de comptage de sièges qui en dépendaient.

Une même demande — « le stand tient à effectif réduit à midi » — pouvait donc s'écrire de deux façons : le marquage du créneau, ou une plage d'ouverture du stand qui nomme son propre effectif. Le marquage est la moins bonne : il divise toujours par deux, quel que soit le stand, alors que seul le stand sait combien de personnes lui faut à midi ; et il compliquait chaque outil de la grille (formulaire, CSV, ligne de vacations, YAML, journées types, générateur, quatre rapports).

## Décision

Le marquage est retiré : le champ du créneau et de la vacation, la colonne `couverture_pause` (migration `V170`), les clés des API, des outils MCP, du CSV et du YAML, l'anomalie `RELAIS_REPAS_HORS_FENETRE`, le décompte `misAJour` du générateur. Un créneau se pourvoit à l'effectif plein du stand, ou à celui de sa plage d'ouverture quand elle en nomme un.

Un fichier YAML qui porte encore la clé est lu : le maillon 6 → 7 l'ignore et le dit dans le rapport d'import, pendant toute la 4.x.

## Options écartées

- **Garder le champ, documenté « sans effet »** : une case qui ne fait rien est pire qu'une case absente.
- **Convertir chaque marquage en plage d'ouverture à effectif réduit** : la conversion devine un effectif par stand et réécrit des horaires que l'organisation n'a pas saisis ; elle changerait aussi les empreintes d'horaires des scénarios livrés. Le marquage ne servait pas dans les éditions réelles : la perte est nulle, et la migration en écrit le nombre au journal pour toute base qui l'aurait utilisé.

## Conséquences

- Les sièges d'un créneau ne dépendent plus que du stand et de ses plages : un seul compte, plus de cas particulier à relire.
- Les scénarios livrés qui marquaient un relais ne le marquent plus : leur nombre de sièges au midi remonte à l'effectif plein, et les empreintes de leur lecture sont régénérées pour ce seul champ.
