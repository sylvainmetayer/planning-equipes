---
ordre: 90
---

### MCP tools

**Before**: 157 tools whose names almost all start with a verb (`lister_stands`), with about ten exceptions (`equite_planning`, `volumes`…). **After**: tools are named `<verb>_<object>`, the verb taken from a closed set ([ADR 0100](repo:docs/decisions/0100-convention-de-nommage-des-outils-mcp.md)), many are grouped: 4.0.0 publishes 103 tools under their current name. Of the 157 of 3.5, 52 keep their name, 82 old names stay published next to the new one until 5.0, with their arguments and answers, their description starting with « Ancien nom », and 23 are no longer published: 20 grouped under a new name and 3 removed (`analyser_ouvertures_stands`, `compacter_horaires_stands`, `consulter_historique_ponderation`). The sections below say which tools change and why. The full mapping, old name then new name or "removed", is in [the old tool names](../comprendre/mcp-outils.md#anciens-noms-doutils).

**What you do**: if you have written instructions, prompts or scripts that name tools, reread them against this table; an assistant that reconnects rereads the tool list and has nothing else to change.
