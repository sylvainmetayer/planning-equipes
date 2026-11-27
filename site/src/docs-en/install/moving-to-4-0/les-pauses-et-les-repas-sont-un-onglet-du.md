---
ordre: 233.5
---

### Breaks, meal catering and walks are the Diagnostic's Meals and breaks tab

- **Before (3.x)**: the breaks ("Breaks and meals", with the meal catering under them) were a view of the Planning page, and the tight walks between two stands a reading of the Problems. 4.0 removed the view from the Planning page and sent the old address to the Problems in the meantime.
- **After (4.0)**: one report, the Diagnostic's **Meals and breaks** tab (`?onglet=repas-pauses`), read one day at a time (`jour`). It gathers the legal breaks (stand by stand, with the relay, and "Open the seat" to the Planning page), the meal break owed, the catering (who eats, when and where, with the CSV export of the whole event) and the tight walks. These are the same computations as before, read on the saved plan; nothing is recomputed. The preferred placement of the meal break is still set in *Settings*, *Organisation* tab, and the "Coupure repas" rule in *Planning rules*: the tab links to both.
- **What you do**: nothing for your links. Until 5.0, `/pauses`, `/intendance` and the Planning page's `?vue=pauses` lead to `?onglet=repas-pauses`, the day of `jour` or `date` kept. The other keys of those old views (`relais`, `repas`) are dropped. A bookmark that named one of these addresses should be moved before 5.0, which will remove the old routes.
