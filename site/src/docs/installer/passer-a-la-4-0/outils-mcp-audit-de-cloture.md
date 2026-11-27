---
ordre: 95
---

### Trois outils MCP remis dans la convention de nommage

- **Avant (3.x)** : trois outils portaient un verbe hors de la liste de la convention : `lever_consigne`, `definir_prereglage_consigne` et `configurer_collecte_disponibilites`.
- **Après (4.0)** : ils s'appellent `supprimer_consigne`, `modifier_prereglage_consigne` et `modifier_collecte_disponibilites`, avec les mêmes arguments et les mêmes réponses ([ADR 0127](repo:docs/decisions/0127-un-outil-mcp-par-objet-et-par-verbe.md)). `modifier_collecte_disponibilites` reste le seul outil qui ouvre la collecte et, sur demande, écrit aux animateurs. Aucun autre outil n'est regroupé : l'audit n'a trouvé aucun doublon certain parmi les outils restants.
- **Ce que vous faites** : rien tout de suite. Les trois anciens noms restent publiés jusqu'en 5.0, et leur description commence par « Ancien nom ». Une invite ou un script qui les nomme doit passer aux nouveaux noms avant la 5.0 : la correspondance est dans [les anciens noms d'outils](../comprendre/mcp-outils.md#anciens-noms-doutils).
