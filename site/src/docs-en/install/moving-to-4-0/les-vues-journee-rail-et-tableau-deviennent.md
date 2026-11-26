---
ordre: 230
---

### The Day, Rail and Table views become the Day view

- **Before (3.x)**: for one day, the Planning page offered five renderings side by side, including "Table" (`?vue=calendrier`, open by default) and "Rail" (`?vue=rail`).
- **After (4.0)**: the table and the rail are two **modes** of a single **Day** view: `?vue=jour&mode=rail` or `?vue=jour&mode=tableau`. **The rail is the default mode**: an address with neither `vue` nor `mode` opens the rail, where it used to open the table. The review bar with the state of the day (to review, reviewed, frozen) stays in both modes.
- **What you do**: nothing for your links. `?vue=calendrier` and `?vue=rail` are read as before (the first opens the table) for the whole 4.x, and `/day-calendar` and `/rail-jour` lead to the right mode; 5.0 will remove the old values. A bookmark or an internal help page that should open the table must carry `mode=tableau`.
