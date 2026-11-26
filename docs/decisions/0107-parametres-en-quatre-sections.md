# 0107 — Les paramètres de l'édition ont quatre sections, sans fusionner leurs tables

- **Statut** : accepté, implémenté
- **Date** : novembre 2026
- **Portée** : API des paramètres, outils MCP, section `parametres` du scénario YAML

## Contexte

Les paramètres d'une édition étaient quatre objets sans lien visible entre eux :
`ParametresLegaux`, `ParametresQualite`, `ParametresSolveur`,
`ParametresNotifications`, chacun avec sa racine d'API, ses deux outils MCP et,
pour trois d'entre eux, sa clé au premier niveau du scénario YAML. L'écran des
paramètres ne peut pas se construire sur quatre racines sans que chaque
nouveau réglage hésite sur la sienne.

## Décision

1. **Quatre sections** : `legal`, `organisation` (les anciens seuils de
   qualité), `notifications`, `instance` (l'ancien budget de calcul, qui
   devient une propriété d'instance dans une PR suivante). La météo et les
   coordonnées de l'organisation restent à part : ce ne sont pas des sections.
2. **L'API** sert `GET /api/parametres` (les quatre) et `GET`/`PUT
   /api/parametres/<section>`. Les quatre anciennes racines restent pendant
   toute la 4.x comme alias déclarés obsolètes, qui appellent le même code ;
   elles sont retirées en 5.0.
3. **Deux outils MCP**, `consulter_parametres` et `modifier_parametres`, avec
   un argument `section` (ADR 0100). Un champ qui n'est pas de la section
   demandée est refusé plutôt qu'ignoré. Le journal écrit une action unique,
   `PARAMETRES_MODIFIES` ; les routes REST gardent une action par section.
4. **Le scénario YAML** range `parametresLegaux`, `parametresQualite` et
   `parametresSolveur` sous `parametres` (`legal`, `organisation`,
   `instance`) : maillon 2 → 3 de la chaîne (ADR 0101). Les notifications n'y
   étaient pas et n'y entrent pas.
5. **Les tables ne changent pas.** Chaque section garde sa table typée, son
   `modifie_le` et sa précondition d'écriture ; il n'y a donc pas de migration
   SQL.

## Alternatives écartées

- **Une seule table `parametres`** : elle ferait d'une édition d'un seul champ
  un conflit d'écriture avec les trois autres sections, perdrait les
  contraintes de colonne typées et obligerait à réécrire la duplication
  d'édition, l'export SQL et les tests d'isolation pour un gain nul côté
  utilisateur. Rien ne l'interdit plus tard : la section est déjà
  l'unité que l'API manipule.
- **Un seul `PUT /api/parametres` pour les quatre** : quatre tables, pas de
  transaction commune dans le dépôt, donc une écriture qui échouerait à moitié.
- **Un seul outil MCP** : un outil qui lit et écrit ne peut pas se déclarer en
  lecture seule (ADR 0100).

## Conséquences

- Les pondérations et l'état des règles ne font pas partie des paramètres : ils
  suivent la refonte des niveaux de règle.
- Les clients de la 3.x continuent de lire et d'écrire par les anciennes
  racines pendant la 4.x ; les outils MCP, eux, n'ont pas d'alias.
