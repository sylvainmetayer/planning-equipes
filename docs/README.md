# Le dossier `docs/`

La documentation rédigée (guides pour les organisateurs, pour les hébergeurs,
pour les développeurs) est une partie du site du produit : ses sources sont dans
[`site/src/docs/`](../site/src/docs/index.md) et elle est publiée sous
[planning.sylvain.dev/docs](https://planning.sylvain.dev/docs/). Ce dossier-ci ne
garde que ce qui sert aux contributeurs et aux outils.

| Chemin | Contenu |
| --- | --- |
| [`decisions/`](decisions/README.md) | Les décisions d'architecture : ce qui a été choisi, contre quelles alternatives, et ce que ça engage. Jamais publiées sur le site ; les pages y renvoient par un lien GitHub |
| [`schema/`](schema/) | Le contrat OpenAPI publié par l'application (`openapi.json`, rafraîchi par `npm run api-schema`) et le schéma des fichiers de scénario |
| [`diagrammes/`](diagrammes/) | Les diagrammes PlantUML (sources `.puml`, SVG générés), montrés par les pages du site. Régénération et contrôles : voir la page de développement du site |
| [`licences-tierces.md`](licences-tierces.md) | L'inventaire des licences des composants que l'image redistribue. Généré, jamais modifié à la main ; le site l'affiche dans la partie « Installer chez vous » |
| [`memoire-du-projet.md`](memoire-du-projet.md) | Ce que l'historique portait : les mesures qui ont tranché un réglage du solveur, les pistes abandonnées, l'incident derrière chaque garde-fou. À lire quand on se demande « pourquoi est-ce écrit comme ça ? » |

La mémoire destinée aux agents IA est dans [`../AGENTS.md`](../AGENTS.md) et, pour
le frontend, [`../src/main/webui/AGENTS.md`](../src/main/webui/AGENTS.md).
