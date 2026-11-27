---
ordre: 93
---

### Outils MCP du diagnostic et de ses rapports

- **Avant (3.x)** : 11 outils pour lire le besoin, les risques et la charge du planning, un par analyse : `analyser_effectifs`, `analyser_marge` (et son mode `tension`), `analyser_typologies`, `volumes`, `analyser_faisabilite`, `plan_formation`, `equite_planning`, `heures_travaillees`, `analyser_pauses`, `analyser_enchainements` et `changements_journee`. La fragilité du planning n'avait pas d'outil.
- **Après (4.0)** : un outil par onglet ou par rapport, la partie lue passée en argument ([ADR 0127](repo:docs/decisions/0127-un-outil-mcp-par-objet-et-par-verbe.md)) : `consulter_besoin` (`section` = `borne`, `marge`, `typologie`, `volumetrie` ou `faisabilite`), `consulter_risques` (`section` = `fragilite`, `former` ou `tension`), `consulter_charge` (`vue` = `equite` ou `heures`) et `consulter_repas_pauses` (`section` = `pauses` ou `enchainements`). `changements_journee` devient `comparer_journee`. Ces familles passent à 5 outils. La section `fragilite` est nouvelle : elle rend le rapport Fragilité par ids, sans le nom ni la phrase de l'écran. Un argument qui ne vaut que pour une partie (`mode` hors de `marge`, `source` hors de `heures`, `standId` hors de `pauses`) est refusé au lieu d'être ignoré.
- **Ce que vous faites** : rien tout de suite. Les 11 anciens noms restent publiés jusqu'en 5.0, avec leurs arguments et leurs réponses, et leur description commence par « Ancien nom ». Une invite, une instruction ou un script qui les nomme doit passer aux nouveaux noms avant la 5.0 : la correspondance est dans [les anciens noms d'outils](../comprendre/mcp-outils.md#anciens-noms-doutils).
