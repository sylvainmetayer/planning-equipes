---
ordre: 130
---

### Carpooling is reduced to validated or not

- **Before (3.x)**: an "I'm coming with…" request went through four states (`EN_ATTENTE`, `VALIDEE`, `ECARTEE`, `ANNULEE`), the organisation could write a reason, and three e-mails went out: validation, request set aside, grouped arrival cancelled.
- **After (4.0)**: a request is not validated (`EN_ATTENTE`) or validated (`VALIDEE`). Setting a request aside or cancelling a grouped arrival deletes the request, with no reason and no e-mail. One e-mail remains: the validation, sent to each member. The `arriveeGroupee` rule does not change, nor does the adjustment a validation creates. Migration `V142` takes the requests already set aside or cancelled out of the table, and the reason column: they are kept, reason included, in the `declaration_coequipier_avant_4_0` archive, which the application no longer reads and which 5.0 will drop ([retention](conservation-des-donnees.md#larchive-du-covoiturage-3x)); the API no longer returns `reason`, and the `.../ecart` and `.../annulation` routes answer `204` with no body. The status returned by `lister_demandes_covoiturage` has only two values.
- **What you do**: nothing for your data. After a cancellation, tell the group yourself, since no e-mail goes out any more. A script that read `ECARTEE`, `ANNULEE` or `reason` must do without. Add the archive to your record of processing, with its retention ([the limits to record](rgpd-limites-a-consigner.md#les-demandes-de-covoiturage)).
