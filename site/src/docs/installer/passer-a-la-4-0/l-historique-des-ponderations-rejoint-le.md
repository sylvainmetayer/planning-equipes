---
ordre: 170
---

### L'historique des pondérations rejoint le journal des actions

- **Avant (3.x)** : le panneau d'une règle (*Règles du planning*) montrait chaque changement de poids ou d'activation, valeurs comprises, et les résolutions qui avaient suivi (`GET /api/constraints/historique`, `GET /api/constraints/{name}/historique`, outil MCP `consulter_historique_ponderation`) ; les lignes vivaient dans `ponderation_contrainte_historique`, pour toute la durée de l'édition.
- **Après (4.0)** : il n'y a plus qu'un journal. *Paramètres › Historique*, filtre « Règles » (`GET /api/historique?nature=regles`), dit quelle règle a changé d'activation, de poids ou de niveau, quand et par qui, **sans la valeur** : le journal n'en garde aucune, et il expire comme le reste. Les deux routes et l'outil sont retirés, sans alias (l'outil figure « retiré » dans [les anciens noms d'outils](../comprendre/mcp-outils.md#anciens-noms-doutils)). Un import de scénario et une duplication d'édition ne laissent plus une ligne par règle : le journal dit l'import ou la duplication, pas chaque règle qu'ils ont posée.
- **Ce que vous faites** : rien pour vos données. La migration `V158` **ne migre pas** les anciennes lignes, qui portent des valeurs que le journal refuse : elle renomme la table en `ponderation_contrainte_historique_avant_4_0`, que l'application ne lit ni n'exporte et que la 5.0 supprime. Pour garder ces valeurs, exportez la table avant la montée ; une fois la 4.0 en place et le retour à la 3.x exclu, `DROP TABLE ponderation_contrainte_historique_avant_4_0` la supprime sans effet sur l'application. Un script qui lisait les deux routes ou l'outil doit lire le journal, ou se passer des valeurs.
