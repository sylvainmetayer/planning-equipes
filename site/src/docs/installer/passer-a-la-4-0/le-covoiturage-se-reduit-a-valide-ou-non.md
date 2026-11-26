---
ordre: 130
---

### Le covoiturage se réduit à validé ou non

- **Avant (3.x)** : une demande « Je viens avec… » passait par quatre états (`EN_ATTENTE`, `VALIDEE`, `ECARTEE`, `ANNULEE`), l'organisation pouvait écrire un motif, et trois courriels partaient : validation, demande écartée, arrivée groupée annulée.
- **Après (4.0)** : une demande est non validée (`EN_ATTENTE`) ou validée (`VALIDEE`). Écarter une demande ou annuler une arrivée groupée supprime la demande, sans motif et sans courriel. Un seul courriel subsiste : la validation, envoyée à chaque membre. La règle `arriveeGroupee` ne change pas, ni l'ajustement qu'une validation crée. La migration `V142` retire de la table les demandes déjà écartées ou annulées, et la colonne du motif : elles sont gardées, motif compris, dans l'archive `declaration_coequipier_avant_4_0`, que l'application ne lit plus et que la 5.0 supprimera ([durée de conservation](conservation-des-donnees.md#larchive-du-covoiturage-3x)) ; l'API ne rend plus `reason`, et les routes `.../ecart` et `.../annulation` répondent `204` sans corps. Le statut rendu par `lister_demandes_covoiturage` n'a plus que deux valeurs.
- **Ce que vous faites** : rien pour vos données. Après une annulation, prévenez vous-même le groupe, puisque plus aucun courriel ne part. Un script qui lisait `ECARTEE`, `ANNULEE` ou `reason` doit s'en passer. Ajoutez l'archive à votre registre des traitements, avec sa durée ([les limites à consigner](rgpd-limites-a-consigner.md#les-demandes-de-covoiturage)).
