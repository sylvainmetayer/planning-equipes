---
ordre: 235
---

### The consigne is written as dated plages

- **Before (3.x)**: an edition consigne (a band closed for every stand, the compensation openings chosen stand by stand) was laid on top of the stands' hours at every read, writing nothing on them.
- **After (4.0)**: laying a consigne down **writes dated plages** on every stand of its date, marked as its own: a closure over the band, one over each timeslot it added, an opening per compensation window ([decision 0125](repo:docs/decisions/0125-la-consigne-s-ecrit-en-plages-datees.md)). These plages are read above the stand's own, and the resulting day is the same. Lifting the consigne removes exactly its plages; a consigne plage edited by hand in the meantime loses its mark and stays. The gestures (propose, preview, lay down, change, lift), the Consignes screen and the MCP tools `appliquer_consigne`, `lever_consigne`, `simuler_consigne` and `simuler_levee_consigne` do not change. Migration `V168` writes the plages of the consignes already laid, past ones included; one test compares, on every shipped scenario put under two consignes, the windows and seats obtained with those of 3.x, and another replays the migration against the application's writer.
- **What you do**: nothing for your data. A stand form opened before a consigne is laid or lifted is refused on save (reload it). A consigne's plages now appear among the stand's plages (`consigne: true` in the API); a script reading a stand's hours must read them along. A YAML export does not carry them: it carries the consigne, and the import writes them again.
