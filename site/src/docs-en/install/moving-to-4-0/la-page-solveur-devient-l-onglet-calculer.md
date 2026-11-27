---
ordre: 233
---

### The Solver page becomes the "Calculate" tab of the Planning page

- **Before (3.x)**: solves were started from the *Solver* page (`/solveur`), which had its own menu entry ("g" then "s" shortcut), three buttons ("Calculate the schedule", "Fix after a change", "Start over from scratch") and a second tab, *Plan versions* (`/solveur?onglet=versions`). How far the review had got only showed there after a solve started from this browser.
- **After (4.0)**: the *Planning* page (`/journee`) carries two tabs next to its four views: **Calculate** (`?onglet=calculer`), the former *Solver* page, and **Plan versions** (`?onglet=versions`). The *Solver* entry leaves the menu, and its shortcut with it; the palette finds both tabs. **Two buttons** remain: "Calculate the schedule", the full solve from the plan in place (locks and frozen past kept), which asks for confirmation as soon as a plan exists, and "Fix after a change", which only repairs the seats in breach. **"Start over from scratch" becomes a box** of the confirmation of "Calculate the schedule", unticked by default; ticked, a second confirmation says what will be lost. How far the review has got (days reviewed or frozen) shows as soon as a plan exists, solved here or not. The API and the MCP tools do not change.
- **What you do**: nothing for your links. `/solveur` and `/solver` lead to `/journee?onglet=calculer`, `/solveur?onglet=versions` and `/versions` to `/journee?onglet=versions`, their other parameters kept. A bookmark or an internal help page that named `/solveur` should be moved to the new address before 5.0. Whoever started a solve from scratch with the button now ticks the box of the confirmation.
