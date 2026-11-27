---
ordre: 233
---

### Le panneau Siège n'a plus que deux gestes « et si »

- **Avant (3.x)** : le panneau Siège de la page Planning offrait des gestes voisins, chacun à part : « Remplacer » (les remplaçants viables, appliqués d'un clic), « Déplacer vers… » (écrit sans aperçu), « Qui peut tenir ce siège ? » et « Pourquoi lui ? », lu à chaque ouverture d'un siège tenu. Côté API, `POST /api/postes/{posteId}/deplacement/simulation` simulait un déplacement, et `POST /api/postes/{posteId}/simulation-swap` simulait une passation sur un plan envoyé par l'appelant. Un lecteur ne pouvait appeler ni l'un ni l'autre.
- **Après (4.0)** : deux gestes. « Simuler un changement » réunit les suggestions de réparation, le déplacement vers un autre siège et le banc ; il montre l'avant et l'après du changement choisi, et « Appliquer » l'écrit avec les mêmes contrôles qu'avant (calcul en cours, verrou, passé figé, personne affichée). « Expliquer » dit pourquoi cette personne est sur ce siège. Libérer, Marquer absent et Verrouiller restent à côté. Côté API, la simulation et les suggestions se lisent en `GET /api/postes/{posteId}/simulation?cible=|animateur=` et `GET /api/postes/{posteId}/suggestions` : ce sont des lectures, ouvertes au lecteur, qui ne peut toujours pas appliquer.
- **Ce que vous faites** : rien dans l'application. Un script qui appelait `POST …/deplacement/simulation` ou `POST …/simulation-swap` continue de fonctionner jusqu'en 5.0 ; passez-le sur `GET …/simulation`, qui lit le plan enregistré avec les réglages de l'édition, avant la 5.0, qui retirera les deux anciennes routes.
