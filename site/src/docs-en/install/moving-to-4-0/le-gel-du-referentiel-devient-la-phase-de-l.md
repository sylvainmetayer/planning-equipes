---
ordre: 200
---

### The reference data freeze becomes the phase of the edition

- **Before (3.x)**: reference data was frozen family by family (stands, timeslots, game categories and locations, skills), each laid down and lifted on its own in Settings (table `gel_referentiel`). Nothing froze by itself, not even after a publication.
- **After (4.0)**: an edition has a **phase**, Preparation, Solving, Published or Finished ([ADR 0115](repo:docs/decisions/0115-la-phase-de-l-edition-remplace-le-gel.md)), and the phase is what freezes. In Solving and Finished, the four families are frozen. In Published, the structure (stands, timeslots, game categories and locations) is frozen but the staff fiches, skills included, stay editable; the structure can be **unblocked** for a last-minute change without leaving the phase, and the next publication freezes it again. **The first publication moves the edition to Published**; every other change of phase is an explicit gesture (`PUT /api/editions/courant/phase`). Consignes, availability, adjustments and locks remain free in every phase, and the frozen past (`PASSE_FIGE`) stays an instance setting, unrelated to the phase.
- **Migration `V155` derives the phase from the state of each edition, and this mapping loses information**:

| 3.x state | 4.0 phase |
| --- | --- |
| At least one publication, no structure family frozen | Published, structure unblocked: nothing closes at the upgrade, the next publication freezes it |
| At least one publication, a structure family frozen | Published, structure frozen **as a whole** |
| No publication, at least one family frozen | Solving: the **four** families frozen |
| No publication, no family frozen | Preparation |

  What is lost: the per-family detail. An edition that had frozen only its stands sees its timeslots, game categories and locations and skills frozen too; a published edition that had frozen its skills finds them editable again; the freeze date of each family becomes a single phase date. The `gel_referentiel` table stays in place, read by nothing and left out of the SQL export, for a way back to 3.x, and 5.0 drops it.
- **What you do**: after the upgrade, open each ongoing edition and check its phase on the home page ("Phase of the edition" line) or in Administer › Editions. If a family is frozen while you wanted it open, change the phase there: go back to Preparation to reopen the four families, or, in Published, click "Unblock the structure" (a banner says so as long as it is unblocked). The "Reference data freeze" card of Settings, *Edition* tab, no longer exists. The MCP tools `figer_referentiel` and `lever_gel` are replaced by `modifier_phase_edition` (`phase` argument, or `structure` to unblock and freeze again): an assistant or prompt that names them must change the name, with no alias. Until 5.0, the `PUT` and `DELETE /api/editions/courant/gel/{famille}` routes remain aliases of the phase (freezing one family in Preparation freezes them all); a script that calls them must move to `PUT /api/editions/courant/phase` and `/api/editions/courant/deblocage-structure` before 5.0. After a publication, a script or import that changed stands or timeslots is refused with `409 REFERENTIEL_FIGE` until the structure is unblocked.
