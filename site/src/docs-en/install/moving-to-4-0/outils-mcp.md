---
ordre: 90
---

### MCP tools

**Before**: 157 tools whose names almost all start with a verb (`lister_stands`), with about ten exceptions (`equite_planning`, `volumes`…). **After**: tools are named `<verb>_<object>`, the verb taken from a closed set ([ADR 0100](repo:docs/decisions/0100-convention-de-nommage-des-outils-mcp.md)), some are grouped or removed. A tool is published only under its current name, except for the old names kept until 5.0 next to the new one, whose description starts with « Ancien nom » (those of the day review and of the timeslot generator). The mapping, old name then new name or "removed", is in [the old tool names](../comprendre/mcp-outils.md#anciens-noms-doutils), filled in as 4.0 changes land (for now no tool is renamed).

**What you do**: if you have written instructions, prompts or scripts that name tools, reread them against this table; an assistant that reconnects rereads the tool list and has nothing else to change.
