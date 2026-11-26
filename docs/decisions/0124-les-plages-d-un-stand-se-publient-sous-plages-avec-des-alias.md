# 0124 — Les plages d'un stand se publient sous `plages`, les anciennes listes restent des alias jusqu'en 5.0

- **Statut** : accepté, implémenté
- **Date** : novembre 2026
- **Portée** : fiche du stand de l'API, routes d'écriture d'une plage, outils MCP des plages
- **Prolonge** : [0104](0104-les-horaires-d-un-stand-sont-une-liste-de-plages-datees.md) (les horaires sont une liste de plages datées), [0100](0100-convention-de-nommage-des-outils-mcp.md) (noms des outils)

## Contexte

Le modèle de la 0104 porte une seule liste de plages, mais la fiche du stand ne
la montrait que découpée par mode, sous les clés `ouvertures` et
`indisponibilites` de la 3.x, et sans route pour poser ou retirer une plage :
tout passait par la réécriture de la fiche entière. L'origine d'une plage
(`depliee`) n'était lisible que dans le corps de chaque plage. Côté assistant,
trois outils (`ajouter_ouverture_stand`, `ajouter_fermeture_stand`,
`effacer_plages_stand`) écrivaient la fiche entière, hors convention de nom.

## Décision

1. **La fiche publie `plages`**, la liste unique, chaque plage avec son
   identifiant, son mode et `depliee`. Le modèle reste sous `horaires`.
2. **`ouvertures` et `indisponibilites` restent lues et rendues jusqu'en 5.0**,
   marquées dépréciées dans l'OpenAPI : un client de la 3.x peut les envoyer.
   Quand un corps porte `plages` et une liste dépréciée, **la liste dépréciée
   l'emporte pour son mode**, quel que soit l'ordre des clés : un éditeur de la 3.x
   renvoie les `plages` qu'il a lues à côté de la liste qu'il a changée, et c'est
   la liste qu'il a changée qui dit sa volonté. Un client nouveau n'envoie que `plages`.
3. **Trois routes écrivent une plage** : `POST /api/stands/{id}/plages`,
   `DELETE /api/stands/{id}/plages/{plageId}` et `DELETE /api/stands/{id}/plages?date=`.
   Chacune est refusée pendant un calcul et sous gel des stands **en entier**
   (`@RefusedWhileSolving`, `@RefusedWhileFrozen(STANDS)`) : une plage est le
   planning que le calcul reçoit, il n'y a pas de champ à comparer. La réécriture
   de la fiche compare désormais `plages` (et non plus les deux anciennes listes).
4. **Les outils MCP suivent la convention** : `creer_plage_stand` (argument `mode`),
   `supprimer_plage_stand` (par `plageId` ou par `date`), `creer_horaire_stand` et
   `supprimer_horaires_stand` pour le modèle. Les vues d'un stand rendent `plages`.
   La table des renommages dit où est passé chaque ancien nom.

## Alternatives écartées

- **Retirer `ouvertures` et `indisponibilites` dès la 4.0** : l'éditeur de la 3.x
  les lit encore et un script les envoie ; le retrait attend la 5.0 et l'éditeur de plages.
- **La clé `plages` l'emporte sur les listes dépréciées** : un éditeur de la 3.x
  renvoie toute la fiche, `plages` périmées comprises, et perdrait sa modification
  sans un mot.
- **Garder `ajouter_ouverture_stand` et `ajouter_fermeture_stand` en deux outils** :
  un seul outil à `mode` dit la même chose, comme `creer_horaire_stand`.
