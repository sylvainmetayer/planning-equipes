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
| [`developpement.md`](developpement.md) | Build, tests, CI, Podman, réglage du solveur, mises à jour Renovate |
| [`migration-timefold-2.md`](migration-timefold-2.md) | Passage de Timefold 1.34 à 2.5 : ce que l'édition Community refuse, les correctifs à appliquer, et le réglage du solveur remesuré sur le profil de production |
| [`exploitation.md`](exploitation.md) | **Exploiter une instance chez un client** : prérequis, variables d'environnement, délivrabilité des e-mails, sauvegarde et restauration, conservation des données, surveillance |
| [`securite.md`](securite.md) | Durcissement pour une exposition sur Internet : en-têtes de sécurité navigateur, plafonds de taille des requêtes, limitation de débit, déploiement de production |
| [`observabilite.md`](observabilite.md) | Suivi d'erreurs (Bugsink) et analytics d'usage (Cloudflare Web Analytics) en production : choix, intégration, variables d'environnement |
| [`rgpd.md`](rgpd.md) | **Ce qu'un hébergeur d'instance doit écrire et tenir** : convention de sous-traitance (art. 28), registre des traitements (art. 30), journal des purges, limites connues |
| [`audit-conformite-rh.md`](audit-conformite-rh.md) | Audit daté du référentiel de contraintes contre le Code du travail et la convention ÉCLAT : ce qui est couvert, ce qui manque, et le texte légal derrière chaque écart |
| [`revue-contraintes.md`](revue-contraintes.md) | Revue datée des contraintes et du réglage du solveur : optimisations mesurées sur un solve réel, et ce que le référentiel mutualise, oublie ou n'utilise pas |
| [`product_owner_report.md`](product_owner_report.md) | Journal des tours de toilettage du backlog : les décisions prises sur les issues ouvertes, et leur motif |
| [`qa-navigateur-2026-08-18.md`](qa-navigateur-2026-08-18.md) | Compte rendu d'une QA manuelle en navigateur réel : ce que les tests Node/jsdom ne couvrent pas |

La mémoire destinée aux agents IA (Copilot, Claude Code, …) est centralisée dans
un fichier unique à la racine : [`../AGENTS.md`](../AGENTS.md).
