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
| [`decisions/`](decisions/README.md) | **Décisions d'architecture** : ce qui a été choisi, contre quelles alternatives, et ce que ça engage. Commence par le cloisonnement par édition (modèle, en-tête `X-Edition-Id`, duplication, plan de migration) |
| [`developpement.md`](developpement.md) | Build, tests, CI, Podman, réglage du solveur, mises à jour Renovate |
| [`exploitation.md`](exploitation.md) | **Exploiter une instance chez un client** : prérequis, variables d'environnement, délivrabilité des e-mails, sauvegarde et restauration, conservation des données, surveillance |
| [`securite.md`](securite.md) | Durcissement pour une exposition sur Internet : en-têtes de sécurité navigateur, plafonds de taille des requêtes, limitation de débit, déploiement de production |
| [`observabilite.md`](observabilite.md) | Suivi d'erreurs (Bugsink) et analytics d'usage (Cloudflare Web Analytics) en production : choix, intégration, variables d'environnement |

La mémoire destinée aux agents IA (Copilot, Claude Code, …) est centralisée dans
un fichier unique à la racine : [`../AGENTS.md`](../AGENTS.md).
