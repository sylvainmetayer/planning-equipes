---
ordre: 233
---

### Les heures et l'équité sont le rapport Charge du Diagnostic

- **Avant (3.x)** : les heures travaillées (avec l'export pour la paie) et l'équité étaient deux écrans, `/hours` et `/equite`. La 4.0 les avait d'abord réunis dans la vue *Synthèse* de la page Planning, par personne.
- **Après (4.0)** : un seul rapport, l'onglet **Charge** du Diagnostic (`?onglet=charge`), qui garde la synthèse telle quelle : mêmes colonnes, même choix du plan publié ou enregistré pour les heures, mêmes deux exports CSV (heures pour la paie, équité). La page Planning n'a plus que la grille et la frise par personne. Le geste « Voir la charge par personne » du Diagnostic ouvre ce rapport.
- **Ce que vous faites** : rien pour vos liens. Jusqu'en 5.0, `/hours`, `/equite` et `?vue=personne&rendu=synthese` (ou l'ancien `?axe=personne&vue=synthese`, y compris les liens qui ne portent qu'un tri ou `colonnes=toutes`) mènent à `?onglet=charge`, avec le tri (`sort`, `dir`), les colonnes toujours vides (`colonnes`), « Seulement sans jour de repos » (`sansRepos`), le plan des heures (`source`) et le filtre par nom (`q`, écrit `nom` dans l'adresse du rapport : `q` est celui de la Fragilité) ; les autres paramètres de la page Planning (jour, portée, densité, et les filtres `animateur`, `stand`, `emplacement`, `typologie`) sont abandonnés. Un signet qui nommait l'une de ces adresses est à reprendre avant la 5.0, qui retirera les anciennes routes.
