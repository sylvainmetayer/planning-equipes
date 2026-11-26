# 0107 — Les paramètres de l'édition ont quatre sections, sans fusionner leurs tables

- **Statut** : accepté, implémenté — **la section `instance` devient `calcul`** ([0110](0110-budget-de-calcul-d-instance-file-en-memoire-quota-public.md)) : le budget de calcul n'est plus un réglage d'édition
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
   demandée est refusé (400) plutôt qu'ignoré, par l'outil comme par
   `PUT /api/parametres/<section>` ; les anciennes racines gardent la lecture
   tolérante de la 3.x. Le journal écrit une action par section, la même
   qu'il s'agisse de la route REST ou de l'outil ; `PARAMETRES_MODIFIES` ne
   nomme qu'un appel refusé sur une section inconnue.
4. **Le scénario YAML** range `parametresLegaux`, `parametresQualite` et
   `parametresSolveur` sous `parametres` (`legal`, `organisation`,
   `instance`) : maillon 2 → 3 de la chaîne (ADR 0101). Les notifications n'y
   étaient pas et n'y entrent pas.
5. **Les tables ne changent pas.** Chaque section garde sa table typée et
   s'écrit entière, comme en 3.x ; il n'y a donc pas de migration SQL. Ces
   tables n'ont pas de `modifie_le` : la dernière écriture d'une section
   l'emporte, comme avant. Une précondition d'écriture par section reste
   possible plus tard, une colonne par table, sans toucher aux autres.

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
