---
ordre: 1000
---

### Data and migrations

On its first start, 4.0.0 applies migrations `V140` to `V170`. Each one is described in the section of its change; this table brings them together, with what it removes or renames and the archive it keeps. A `…_avant_4_0` archive is neither read nor exported by the application, and 5.0 will drop it: until then it allows a return to 3.x on a copy of the database, and it belongs in your record of processing ([retention periods](conservation-des-donnees.md)).

| Migration | What it changes in the schema | Archive kept until 5.0 | Section |
| --- | --- | --- | --- |
| `V140` | column `typologie.ninja` renamed `polyvalent` | none | The "ninja" game category becomes "all-rounder" |
| `V142` | rejected or cancelled carpool requests deleted, column `motif` removed | `declaration_coequipier_avant_4_0` | Carpooling is reduced to validated or not |
| `V143` | table `plan_snapshot` renamed `version_plan`, column `automatique` replaced by the version's origin | none | Snapshots become versions |
| `V145` | solve duration and plateau removed from the edition's settings and from solver jobs | `solve_budget_avant_4_0` | The solve budget, the queue and the public instance |
| `V147` | column `plan` of the copy before a wipe made optional, nothing is rewritten | none | The copy before a wipe becomes a YAML export |
| `V148` | `kpi_historique` and `kpi_realise` merged into `mesure_edition` | `kpi_historique_avant_4_0`, `kpi_realise_avant_4_0` | The measures of an edition have one table and one screen |
| `V149` | level chosen per edition for each organisation rule, no score changes | none | The level of an organisation rule is chosen per edition |
| `V150` | column `modifie_le` on the four settings tables | none | The edition's settings in four sections |
| `V151` | `ANIMATEUR`, `STAND` and `CRENEAU` locks converted into animateur + timeslot and stand + day locks | none | The lock has only three types |
| `V152`, `V154` | settings of the adult / minor rules merged on the most protective reading | none | Schedule rule names |
| `V155` | reference data freeze replaced by the edition's phase | `gel_referentiel`, kept under its name, neither read nor exported | The reference data freeze becomes the phase of the edition |
| `V158` | weighting history moved out of the tables the application reads | `ponderation_contrainte_historique_avant_4_0` | The weighting history joins the action journal |
| `V159` | the stands' recurring rules unfolded into dated plages | none (the rules stay the stand's template) | Stand opening hours |
| `V160` | pending publication exclusions ignored | `exclusion_publication_avant_4_0` | "Exclude from the publication" is removed |
| `V161` | forced assignments and forced unavailabilities converted into locks and days off, then deleted | `contrainte_ad_hoc_avant_4_0` | The forced assignment and the forced unavailability are converted |
| `V162` | hard form of the days in a row merged into the rule with an adjustable level | none | One days-in-a-row rule |
| `V165` | switch of the meal break placement taken over as an edition setting | none | The two meal rules have only one line |
| `V168` | consignes in place rewritten as dated closing plages | none | The consigne is written as dated plages |
| `V169` | `journee_type` and its tables renamed `gabarit` | `journee_type_avant_4_0` (what does not convert) | One timeslot generator |
| `V170` | column `couverture_pause` removed from timeslots and templates | none (the number of shifts affected is written to the migration log) | The reduced-staffing meal relay is removed |

**What you do**: a full backup before upgrading, and a YAML export of each edition; the schema does not go back down. After the upgrade, read the startup log: `V161` names each forced exception it could not convert or widened to the whole day, and `V170`, if there are any, the number of timeslots and template shifts that lost their meal relay.
