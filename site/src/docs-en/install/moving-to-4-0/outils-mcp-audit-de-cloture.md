---
ordre: 95
---

### Three MCP tools brought back into the naming convention

- **Before (3.x)**: three tools carried a verb outside the convention's list: `lever_consigne`, `definir_prereglage_consigne` and `configurer_collecte_disponibilites`.
- **After (4.0)**: they are called `supprimer_consigne`, `modifier_prereglage_consigne` and `modifier_collecte_disponibilites`, with the same arguments and the same answers ([ADR 0127](repo:docs/decisions/0127-un-outil-mcp-par-objet-et-par-verbe.md)). `modifier_collecte_disponibilites` remains the only tool that opens the collection and, on request, writes to the staff. No other tool is grouped: the audit found no certain duplicate among the remaining tools.
- **What you do**: nothing right away. The three old names stay published until 5.0, and their description starts with « Ancien nom ». A prompt or a script that names them must move to the new names before 5.0: the mapping is in [the old tool names](../comprendre/mcp-outils.md#anciens-noms-doutils).
