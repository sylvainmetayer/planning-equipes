---
ordre: 233.5
---

### Les pauses, l'intendance des repas et les enchaînements sont l'onglet Repas et pauses du Diagnostic

- **Avant (3.x)** : les pauses (« Pauses et repas », avec l'intendance des repas dessous) étaient une vue de la page Planning, et les enchaînements serrés entre deux stands une lecture des Problèmes. La 4.0 avait retiré la vue de la page Planning et envoyé l'ancienne adresse vers les Problèmes, en attendant.
- **Après (4.0)** : un seul rapport, l'onglet **Repas et pauses** du Diagnostic (`?onglet=repas-pauses`), lu un jour à la fois (`jour`). Il réunit les pauses légales (stand par stand, avec le relais, et « Ouvrir le siège » vers la page Planning), la coupure repas due, l'intendance (qui mange, quand et où, avec l'export CSV de tout l'événement) et les enchaînements serrés. Ce sont les mêmes calculs qu'avant, lus sur le planning enregistré ; rien n'est recalculé. Le placement préféré de la coupure repas se règle toujours dans *Paramètres*, onglet *Organisation*, et la règle « Coupure repas » dans *Règles du planning* : l'onglet renvoie vers les deux.
- **Ce que vous faites** : rien pour vos liens. Jusqu'en 5.0, `/pauses`, `/intendance` et `?vue=pauses` de la page Planning mènent à `?onglet=repas-pauses`, le jour de `jour` ou de `date` gardé. Les autres clés de ces anciennes vues (`relais`, `repas`) sont abandonnées. Un signet qui nommait l'une de ces adresses est à reprendre avant la 5.0, qui retirera les anciennes routes.
