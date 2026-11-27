---
ordre: 93
---

### MCP tools of the diagnosis and its reports

- **Before (3.x)**: 11 tools to read the need, the risks and the load of the plan, one per analysis: `analyser_effectifs`, `analyser_marge` (and its `tension` mode), `analyser_typologies`, `volumes`, `analyser_faisabilite`, `plan_formation`, `equite_planning`, `heures_travaillees`, `analyser_pauses`, `analyser_enchainements` and `changements_journee`. The plan's fragility had no tool.
- **After (4.0)**: one tool per tab or per report, the part read passed as an argument ([ADR 0127](repo:docs/decisions/0127-un-outil-mcp-par-objet-et-par-verbe.md)): `consulter_besoin` (`section` = `borne`, `marge`, `typologie`, `volumetrie` or `faisabilite`), `consulter_risques` (`section` = `fragilite`, `former` or `tension`), `consulter_charge` (`vue` = `equite` or `heures`) and `consulter_repas_pauses` (`section` = `pauses` or `enchainements`). `changements_journee` becomes `comparer_journee`. These families go down to 5 tools. The `fragilite` section is new: it returns the Fragility report by ids, without the name or the sentence of the screen. An argument that only applies to one part (`mode` outside `marge`, `source` outside `heures`, `standId` outside `pauses`) is refused instead of being ignored.
- **What you do**: nothing right away. The 11 old names stay published until 5.0, with their arguments and their answers, and their description starts with « Ancien nom ». A prompt, an instruction or a script that names them must move to the new names before 5.0: the mapping is in [the old tool names](../comprendre/mcp-outils.md#anciens-noms-doutils).
