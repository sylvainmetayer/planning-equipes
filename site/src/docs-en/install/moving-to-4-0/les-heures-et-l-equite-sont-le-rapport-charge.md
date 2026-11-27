---
ordre: 233
---

### Hours and fairness are the Diagnostic's Workload report

- **Before (3.x)**: the hours worked (with the payroll export) and fairness were two screens, `/hours` and `/equite`. 4.0 first gathered them in the *Summary* view of the Planning page, by person.
- **After (4.0)**: one report, the Diagnostic's **Workload** tab (`?onglet=charge`), which keeps the summary as it was: same columns, same choice of published or saved plan for the hours, same two CSV exports (hours for payroll, fairness). The Planning page keeps only the grid and the timeline by person. The Diagnostic's "Voir la charge par personne" (see the workload per person) action opens this report.
- **What you do**: nothing for your links. Until 5.0, `/hours`, `/equite` and `?vue=personne&rendu=synthese` (or the old `?axe=personne&vue=synthese`, including links that carry only a sort or `colonnes=toutes`) lead to `?onglet=charge`, with the sort (`sort`, `dir`), the always-empty columns (`colonnes`), "Only those with no rest day" (`sansRepos`), the plan the hours are read on (`source`) and the name filter (`q`); the other parameters of the Planning page (day, span, density, and the `animateur`, `stand`, `emplacement`, `typologie` filters) are dropped. A bookmark that named one of these addresses should be moved before 5.0, which will remove the old routes.
