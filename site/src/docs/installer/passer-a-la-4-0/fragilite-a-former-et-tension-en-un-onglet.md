---
ordre: 231.5
---

### Fragilité, « À former » et Tension sont un seul onglet : Risques

- **Avant (3.x)** : la fragilité du plan, la tension heure par heure (ex-Marge disponible) et « À former » étaient trois onglets du Diagnostic (la tension sous le nom de Marge disponible, un écran à part), qui lisent le même manque sous trois angles.
- **Après (4.0)** : le Diagnostic n'a plus que cinq onglets en dehors de la Conformité (Problèmes, Besoin en animateurs, **Risques**, Charge, Repas et pauses). Risques empile, de haut en bas, la *Fragilité*, « À former » et la *Tension*, chacune avec ses propres filtres dans l'adresse. « À former » quitte donc le Besoin.
- **Ce que vous faites** : rien pour vos liens. `?onglet=fragilite`, `?onglet=former`, `?onglet=tension`, `?onglet=besoin&section=former`, `/fragilite` et `/marge?mode=apres|tension` sont lus comme avant et mènent à `?onglet=risques&section=fragilite|former|tension` (les autres paramètres, filtres de la fragilité ou jour, sont gardés). La 5.0 retirera les anciennes adresses.
