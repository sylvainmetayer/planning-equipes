---
ordre: 230
---

### Les vues Journée, Rail et Tableau deviennent la vue Jour

- **Avant (3.x)** : la page Planning offrait, pour une journée, cinq rendus côte à côte, dont « Tableau » (`?vue=calendrier`, ouvert par défaut) et « Rail » (`?vue=rail`).
- **Après (4.0)** : le tableau et le rail sont deux **modes** d'une même vue **Jour** : `?vue=jour&mode=rail` ou `?vue=jour&mode=tableau`. **Le rail est le mode par défaut** : une adresse sans `vue` ni `mode` ouvre le rail, là où elle ouvrait le tableau. La barre de relecture avec l'état de la journée (à relire, relue, figée) reste dans les deux modes.
- **Ce que vous faites** : rien pour vos liens. `?vue=calendrier` et `?vue=rail` sont lus comme avant (le premier ouvre le tableau) tant que la 4.x dure, et `/day-calendar` et `/rail-jour` mènent au bon mode ; la 5.0 retirera les anciennes valeurs. Un signet ou une page d'aide interne qui devait ouvrir le tableau doit porter `mode=tableau`.
