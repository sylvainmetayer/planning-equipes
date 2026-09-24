# Documentation technique — Planning Équipes

Cette section regroupe toute la documentation technique du projet. Le `README.md`
à la racine reste volontairement limité à deux sections (démarrage local et
fonctionnalités métier) : **tout ajout de documentation technique se fait ici**.

| Document | Contenu |
| --- | --- |
| [`architecture.md`](architecture.md) | Stack, arborescence, découpage backend / frontend, base de données, conteneurisation |
| [`domaine.md`](domaine.md) | Modèle Timefold (`Animateur`, `Stand`, `Creneau`, `PosteAffectation`, `PlanningEvenement`) et mapping vers les contraintes |
| [`contraintes.md`](contraintes.md) | Catalogue des contraintes implémentées (dur / medium / soft) et règles d'ajout |
| [`api.md`](api.md) | Endpoints REST exposés par le service Quarkus |
| [`mcp.md`](mcp.md) | Serveur MCP (outils exposés à un assistant IA, authentification, confidentialité) |
| [`import-export.md`](import-export.md) | Formats d'import / export : dump SQL, PDF, ICS, schéma de validation des scénarios |
| [`memoire-du-projet.md`](memoire-du-projet.md) | **Ce que l'historique portait** : les mesures qui ont tranché un réglage du solveur, les pistes abandonnées, et l'incident précis derrière chaque garde-fou. Complément des décisions, à lire quand on se demande « pourquoi est-ce écrit comme ça ? »
| [`decisions/`](decisions/README.md) | **Décisions d'architecture** : ce qui a été choisi, contre quelles alternatives, et ce que ça engage. Commence par le cloisonnement par édition (modèle, en-tête `X-Edition-Id`, duplication, plan de migration) |
| [`diagrammes/`](diagrammes/) | **Diagrammes PlantUML** (séquences, états, modèle) : sources et SVG générés, chacun montré par le document qui possède son sujet. Régénération et contrôles dans [`developpement.md`](developpement.md#diagrammes) |
| [`developpement.md`](developpement.md) | Build, tests, CI, Podman, réglage du solveur, mises à jour Renovate |
| [`exploitation.md`](exploitation.md) | **Exploiter une instance chez un client** : prérequis, variables d'environnement, délivrabilité des e-mails, sauvegarde et restauration, conservation des données, surveillance |
| [`versioning.md`](versioning.md) | **Versions et releases** : ce que promettent MAJOR/MINOR/PATCH, comment une release se fabrique (un formulaire GitHub, des notes générées, une image Docker), et la procédure de patch d'une version antérieure |
| [`securite.md`](securite.md) | Durcissement pour une exposition sur Internet : en-têtes de sécurité navigateur, plafonds de taille des requêtes, limitation de débit, déploiement de production |
| [`observabilite.md`](observabilite.md) | Suivi d'erreurs (Bugsink) et analytics d'usage (Cloudflare Web Analytics) en production : choix, intégration, variables d'environnement |
| [`licences-tierces.md`](licences-tierces.md) | **Inventaire généré des licences tierces** : ce que l'image redistribue (dépendances Java, paquets npm) et sous quelle licence. Régénéré par `./mvnw license:add-third-party` puis `npm run licences` |
| [`accessibilite.md`](accessibilite.md) | **Accessibilité (RGAA 4.1)** : ce que l'audit a mesuré, les contrôles qui empêchent les écarts de revenir, ce qui reste ouvert, et la déclaration d'accessibilité qu'un exploitant renseigne |
| [`rgpd.md`](rgpd.md) | **Ce qu'un hébergeur d'instance doit écrire et tenir** : convention de sous-traitance (art. 28), registre des traitements (art. 30), journal des purges, limites connues |

La mémoire destinée aux agents IA (Copilot, Claude Code, …) est centralisée dans
un fichier à la racine, [`../AGENTS.md`](../AGENTS.md), complété d'un seul
fichier de répertoire, [`../src/main/webui/AGENTS.md`](../src/main/webui/AGENTS.md),
qui porte les conventions du frontend et n'est chargé que lorsqu'un agent y
travaille.
