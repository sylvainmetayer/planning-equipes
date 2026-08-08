# Documentation technique — Planning Équipes

Cette section regroupe toute la documentation technique du projet. Le `README.md`
à la racine reste volontairement limité à deux sections (démarrage local et
fonctionnalités métier) : **tout ajout de documentation technique se fait ici**.

| Document | Contenu |
| --- | --- |
| [`architecture.md`](architecture.md) | Stack, arborescence, découpage backend / frontend, base de données, conteneurisation |
| [`domaine.md`](domaine.md) | Modèle Timefold (`Animateur`, `Stand`, `Creneau`, `PosteAffectation`, `PlanningFestival`) et mapping vers les contraintes |
| [`contraintes.md`](contraintes.md) | Catalogue des contraintes implémentées (dur / medium / soft) et règles d'ajout |
| [`api.md`](api.md) | Endpoints REST exposés par le service Quarkus |
| [`import-export.md`](import-export.md) | Formats d'import / export : dump SQL, PDF, ICS |
| [`developpement.md`](developpement.md) | Build, tests, CI, Podman, réglage du solveur, mises à jour Renovate |
| [`observabilite.md`](observabilite.md) | Suivi d'erreurs (Bugsink) et analytics d'usage (PostHog) en production : choix, intégration, variables d'environnement |
| [`audit-conformite-rh.md`](audit-conformite-rh.md) | Audit de conformité RH du référentiel de contraintes (Code du travail, CCN ÉCLAT) — constats, articles et suites données |
| [`product_owner_report.md`](product_owner_report.md) | Rapport de toilettage du backlog GitHub Issues — issues fermées, priorisation, points d'attention |

La mémoire destinée aux agents IA (Copilot, Claude Code, …) est centralisée dans
un fichier unique à la racine : [`../AGENTS.md`](../AGENTS.md).
