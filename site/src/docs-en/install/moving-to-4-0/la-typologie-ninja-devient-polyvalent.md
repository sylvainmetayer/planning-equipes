---
ordre: 110
---

### The "ninja" game category becomes "all-rounder"

- **Before**: the game category that makes its holders available on every stand was called "ninja": `ninja` flag of the game categories (REST, YAML, CSV, MCP tools) and `typologie.ninja` column.
- **After**: it is called "all-rounder" (`polyvalent`). Migration `V140` renames the column and its index, and fixes the field names already recorded in the action journal. The rule's name (`preserverBufferPolyvalents`), its weights and its switch do not change. API responses, YAML and CSV exports and the MCP tools' field say `polyvalent`; the derived keys follow (`polyvalentConfigure` of the fragility and tension reports, `typologiePolyvalenteDefinie` of the staffing-need report). The screens say "All-rounder category". The stands' `premium` flag keeps its name and is displayed as "Premium stand".
- **What you do**: nothing for your data or your files. The old names are still read throughout 4.x (`ninja` JSON and YAML key, `ninja` CSV header, the latter flagged line by line in the import report) and removed in 5.0. Only your scripts that *read* a `ninja` key from an API response or an export need to move to its new name.
