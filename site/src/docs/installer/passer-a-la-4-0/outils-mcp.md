---
ordre: 90
---

### Outils MCP

**Avant** : 157 outils aux noms presque tous en verbe d'abord (`lister_stands`), avec une dizaine d'exceptions (`equite_planning`, `volumes`…). **Après** : les outils se nomment `<verbe>_<objet>`, le verbe pris dans un ensemble fermé ([ADR 0100](repo:docs/decisions/0100-convention-de-nommage-des-outils-mcp.md)), beaucoup sont regroupés : la 4.0.0 publie 103 outils sous leur nom courant. Sur les 157 de la 3.5, 52 gardent leur nom, 82 anciens noms restent publiés à côté du nouveau jusqu'en 5.0, avec leurs arguments et leurs réponses, leur description commençant par « Ancien nom », et 23 ne sont plus publiés : 20 regroupés sous un nouveau nom et 3 retirés (`analyser_ouvertures_stands`, `compacter_horaires_stands`, `consulter_historique_ponderation`). Les sections qui suivent disent quels outils changent et pourquoi. La correspondance complète, ancien nom puis nouveau nom ou « retiré », est dans [les anciens noms d'outils](../comprendre/mcp-outils.md#anciens-noms-doutils).

**Ce que vous faites** : si vous avez écrit des instructions, des invites ou des scripts qui nomment des outils, relisez-les avec cette table ; un assistant qui se reconnecte relit la liste des outils et n'a rien d'autre à changer.
