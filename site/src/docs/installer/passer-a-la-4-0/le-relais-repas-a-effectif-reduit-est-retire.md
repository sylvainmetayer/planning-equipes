---
ordre: 246
---

### Le relais repas à effectif réduit est retiré

- **Avant (3.x)** : une vacation pouvait porter `couverturePause` (case « Relais repas » du formulaire de créneau, `R` dans la ligne de vacations d'un gabarit, colonne `couverturePause` du CSV, argument des outils `creer_creneau` et `modifier_creneau`). Le stand n'y recevait que la moitié de ses sièges, arrondie au supérieur. Aucune règle du planning ne lisait ce marquage : il ne servait qu'à la génération des sièges.
- **Après (4.0)** : le marquage n'existe plus. Une vacation se pourvoit à l'effectif plein du stand, ou à l'effectif de sa plage horaire quand celle-ci en nomme un. La migration `V170` supprime la colonne des créneaux et des vacations de gabarit ; les clés `couverturePause`, `contientCouverturePause` et le type d'anomalie `RELAIS_REPAS_HORS_FENETRE` disparaissent de l'API, des outils MCP et de l'export CSV. Le décompte `misAJour` du rapport d'application du générateur disparaît aussi : il ne comptait que ces bascules.
- **Ce que vous faites** : rien, si aucune de vos vacations n'était marquée (c'est le cas des éditions livrées). Sinon la migration écrit au journal de démarrage combien de vacations portaient le marquage, et ces vacations demandent désormais l'effectif plein : pour garder un effectif réduit à midi, donnez-lui une plage horaire avec son propre effectif (*Stands › Horaires*). Un fichier YAML ou une copie qui porte encore `couverturePause` s'importe : la clé est ignorée et un avertissement du rapport d'import le dit. Un script qui l'envoie à `POST /api/creneaux` ou aux outils MCP doit cesser de le faire ; le `R` d'une ligne de vacations (`12:00-13:00 R`) est maintenant refusé.
