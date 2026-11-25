---
titre: Comprendre comment c'est fait
pour: Le modèle, les règles, l'API, l'assistant IA et la façon de construire le projet, pour qui développe ou audite.
docs: [architecture, domaine, contraintes, import-export, api, mcp, developpement]
---
# Comprendre comment c'est fait

Planning Équipes est une application Quarkus qui embarque le solveur Timefold, une interface Angular et une base PostgreSQL, le tout dans un seul service. Cette partie explique comment l'ensemble tient : ce que le code fait, pourquoi il le fait ainsi, et ce qu'il ne faut pas casser. Elle s'adresse à une personne qui développe, relit ou audite le projet.

Les pages se lisent dans l'ordre ci-dessous la première fois, puis servent de référence.

## Choisir son point d'entrée

| Vous voulez… | Commencez par |
| --- | --- |
| Découvrir le projet et ses grandes pièces | [Architecture](architecture.md), puis [le modèle de domaine](domaine.md) |
| Comprendre pourquoi une règle existe, ou en ajouter une | [Les contraintes](contraintes.md), jusqu'à la section [Ajouter une contrainte](contraintes.md#ajouter-une-contrainte) |
| Charger ou exporter des données (scénarios YAML, CSV, PDF, ICS) | [Importer et exporter](import-export.md) |
| Appeler l'API depuis un autre programme | [L'API REST](api.md) |
| Brancher un assistant IA | [Le serveur MCP](mcp.md) |
| Construire, tester, régler le solveur, livrer | [Développer et livrer](developpement.md) |

## Ce qui vit ailleurs que sur le site

Trois sortes de documents restent dans le dépôt, parce qu'ils servent aux contributeurs et aux outils :

- **Les décisions d'architecture** : une page par choix qui a fermé des alternatives, avec ses raisons. Elles sont indexées dans [`docs/decisions/`](repo:docs/decisions/README.md) ; les pages de cette partie y renvoient quand un choix y est expliqué.
- **Le contrat de l'API** : [`docs/schema/openapi.json`](repo:docs/schema/openapi.json) est la liste des routes ; la page sur l'API ne porte que ce qu'un schéma ne dit pas.
- **Les diagrammes** : séquences, états et modèle, sous forme de sources PlantUML dans [`docs/diagrammes/`](repo:docs/diagrammes) ; les pages montrent leur rendu.

[La mémoire du projet](repo:docs/memoire-du-projet.md) rassemble les mesures et les pistes abandonnées derrière certains réglages du solveur, à lire quand on se demande pourquoi un garde-fou est écrit ainsi.

Si vous hébergez l'application plutôt que de la modifier, la partie [Installer chez vous](../installer/index.md) vous concerne ; pour l'utiliser, voyez [les règles du jeu](../regles/index.md).
