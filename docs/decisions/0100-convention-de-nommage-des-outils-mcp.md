# 0100 — Les outils MCP se nomment `<verbe>_<objet>`, et une table dit où est passé chaque ancien nom

- **Statut** : accepté, implémenté
- **Date** : novembre 2026
- **Portée** : outils MCP, guide de migration 4.0
- **Prolonge** : [0097](0097-mcp-par-oauth-et-rotation-de-la-cle.md) (le MCP par OAuth)

## Contexte

Les 157 outils publiés en 3.1.0 commencent presque tous par un verbe
(`lister_stands`, `creer_stand_complet`, `lancer_solveur`), mais une dizaine
commencent par un nom (`equite_planning`, `etat_edition`, `volumes`), et les
verbes eux-mêmes sont une cinquantaine, dont plusieurs disent la même chose
(`consulter_`, `etat_`, `statut_`, `synthese_`). La 4.0 regroupe les outils par
intention : chaque PR de modèle touche les siens, et ils ne doivent changer de
nom qu'une fois.

## Décision

1. **Un nom d'outil est `<verbe>_<objet>`**, en minuscules séparées par `_` :
   le verbe d'intention d'abord, pris dans un ensemble fermé, puis l'objet
   (`stand`, `planning`, `animateur`, `edition`, `consigne`, `verrou`…). Les
   verbes : `lister`, `consulter`, `analyser`, `diagnostiquer`, `comparer`,
   `simuler`, `previsualiser` (lire sans rien écrire) ; `creer`, `modifier`,
   `activer`, `desactiver`, `verrouiller`, `appliquer`, `arreter`,
   `publier`, `envoyer` (écrire) ; `supprimer`, `deverrouiller`, `lancer`,
   `importer` (écraser ou effacer) ; `exporter`, dont la classe se décidera
   avec le premier outil qui le prendra. Ajouter un verbe est une décision :
   une ADR qui prolonge celle-ci, et le motif `CONVENTION` du test.
2. **Le verbe porte les hints.** `McpAnnotationsStructurelleTest` dérive déjà
   `readOnlyHint` et `destructiveHint` du préfixe du nom : avec le verbe en
   tête, cette dérivation reste ce qu'elle est, et un verbe de la liste
   ci-dessus a une seule classe de hints. Un outil de réglages à sections
   (« paramètres ») se scinde donc en `consulter_parametres` et
   `modifier_parametres`, chacun avec un argument `section` : un seul outil
   qui lit et écrit ne pourrait pas se déclarer en lecture seule.
3. **Le fichier `src/main/resources/mcp-renommages.tsv` est la table de
   correspondance** : une ligne par nom publié sur `v3.1.0`, ni plus ni moins,
   `ancien` puis `nouveau`, ou `retiré` quand l'outil disparaît. Tant qu'une PR
   de modèle ne l'a pas renommé, le nouveau nom d'un outil est son ancien. Les
   lignes qui changent quelque chose sont recopiées dans la page « Ce que font
   les outils du serveur MCP » du site, entre deux repères.
4. **Chaque PR de modèle renomme ses outils directement dans la convention**
   et met à jour sa ligne de la table ; la PR de clôture des outils traite
   ceux qu'aucun modèle ne touche et exige alors la convention de tous.
5. **Pas d'alias.** Un outil n'est publié que sous son nom courant : le
   client est un assistant qui relit la liste des outils à chaque connexion,
   la table et le guide de migration suffisent, et des `@Tool` en double
   doubleraient les quatre tests structurels MCP.
6. **`McpRenommageTest` tient la table.** Il échoue sur un nom de `v3.1.0`
   sans ligne ou une ligne hors de `v3.1.0` (la liste est figée dans
   `src/test/resources/mcp-outils-v3.1.0.txt`, copiée du tag une fois : une
   extraction de CI n'a pas les tags), sur un nouveau nom que le serveur ne
   publie pas, sur un outil `retiré` encore publié, sur un nom changé qui ne
   suit pas la convention, et sur une page du site qui ne montre plus la table.
   Les noms publiés sont lus par réflexion sur les `@Tool`, comme dans les
   autres tests structurels, et non dans le texte des sources.

## Options écartées

- **`<objet>_<verbe>`** (`stand_lister`), pour que les outils d'un même objet
  se suivent dans la liste : il aurait fallu renommer les 157 outils, au lieu
  des seuls que la 4.0 regroupe, et lire les hints sur le suffixe. Un
  assistant ne lit pas la liste dans l'ordre alphabétique ; il cherche
  l'intention, que le verbe en tête donne d'abord.

## Conséquences

- Aucun outil n'est renommé par cette décision. Les noms hors convention de
  3.1.0 (`volumes`, `etat_edition`…) restent tolérés jusqu'à ce que leur PR
  les reprenne.
