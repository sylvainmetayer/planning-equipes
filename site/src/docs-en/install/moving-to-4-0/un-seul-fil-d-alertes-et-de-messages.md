---
ordre: 250
---

### A single feed of alerts and messages on the home screen

- **Before (3.x)**: the home screen folded a "Recent messages" section under "To handle today" (the alerts of the nightly sends, then the history of the application's messages), and the "Referential coherence" line of the checklist unfolded its anomalies in place. The Notifications page had already been absorbed by the home screen.
- **After (4.0)**: a single feed, under "To handle today", gathers the plan's alerts (nightly sends, weather alert, people to notify after a removed exclusion, sleeping swaps), the anomalies of the referential coherence and the application's messages. It is sorted by gravity (blocking, then to check, then messages), then newest first, and filtered by type: `/?type=alertes`, `/?type=coherence` or `/?type=messages`. The toolbar bell still leads there; the "Referential coherence" line no longer unfolds anything and leads to the feed filtered on its type; the former `/notifications` address opens the feed on the messages. People are designated as before: by name where the screen looks it up when displaying, by identifier in the anomalies and in the alerts whose fiche is gone. The Diagnostic's Problems tab does not change.
- **What you do**: nothing on the server, no variable, no migration. A bookmark or link to `/#alertes-nuit` or `/notifications` lands at the top of the home screen, without scrolling; replace it with `/?type=alertes#a-traiter` to get only the alerts.
