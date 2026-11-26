---
ordre: 170
---

### The weighting history joins the action journal

- **Before (3.x)**: a rule's panel (*Planning rules*) showed every change of weight or activation, values included, and the solves that followed (`GET /api/constraints/historique`, `GET /api/constraints/{name}/historique`, MCP tool `consulter_historique_ponderation`); the rows lived in `ponderation_contrainte_historique`, for the whole life of the edition.
- **After (4.0)**: there is only one journal. *Settings › History*, "Rules" filter (`GET /api/historique?nature=regles`), says which rule changed activation, weight or level, when and by whom, **without the value**: the journal keeps none, and it expires like the rest. The two routes and the tool are removed, with no alias (the tool appears as "retiré" in [the former tool names](../comprendre/mcp-outils.md#anciens-noms-doutils)). A scenario import and an edition duplication no longer leave one row per rule: the journal says the import or the duplication, not each rule they set.
- **What you do**: nothing for your data. Migration `V158` **does not migrate** the old rows, which carry values the journal refuses: it renames the table to `ponderation_contrainte_historique_avant_4_0`, which the application neither reads nor exports and 5.0 deletes. To keep those values, export the table before upgrading; once 4.0 is in place and going back to 3.x is ruled out, `DROP TABLE ponderation_contrainte_historique_avant_4_0` removes it with no effect on the application. A script that read the two routes or the tool must read the journal, or do without the values.
