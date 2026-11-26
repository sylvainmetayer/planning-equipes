---
ordre: 50
---

### YAML scenario format

- **Before**: the application writes `schemaVersion: 1`.
- **After**: it writes `schemaVersion: 5`. It still reads v1 files (key absent or equal to 1): they go through a chain of conversions, link by link, as the model changes during 4.x. What the chain cannot convert is ignored, and every ignored item appears in the import report's warnings, never silently. The 1 → 2 link already renames the game categories' `ninja` key to `polyvalent`, losing nothing; the 4 → 5 link converts the stands' opening hours into plages (see [Stand opening hours](#horaires-des-stands)).
- **What you do**: nothing to read your old files, end-of-event archives or copies taken before a wipe. A v2 to v5 scenario cannot be imported into a 3.x application ("newer format"): keep a v1 export before upgrading, it is your way back. The v1 reader is removed in 5.0.
