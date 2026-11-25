# Documentation — Planning Équipes

La documentation est publiée sur le site du produit, sous
[planning.sylvain.dev/docs](https://planning.sylvain.dev/docs/), en **trois parties** selon la personne qui
lit. Le `README.md` à la racine se limite à une introduction et à un démarrage
local ; **tout ajout de documentation se fait ici**, dans la partie à laquelle il
appartient.

| Partie | Pour qui | Ce qu'on y trouve |
| --- | --- | --- |
| **Fonctionnel** | L'organisateur, le bénévole responsable, la personne qui découvre | Ce que fait l'application, par grand usage, sans nom de classe, de route ni de variable |
| **Infra** | La personne qui héberge l'application | Installer, configurer, sécuriser, sauvegarder, mettre à jour |
| **Tech** | La personne qui développe ou audite | Architecture, modèle, contraintes, API, build et tests, décisions |

Les pages du premier groupe vivent dans [`fonctionnel/`](fonctionnel/parcours.md) ; celles
des deux autres sont directement dans `docs/`. La liste des pages de chaque partie
et leur ordre sont dans `site/src/docs-parties.json` : une page qui n'est dans
aucune partie fait échouer la construction du site.

## Fonctionnel

| Page | Contenu |
| --- | --- |
| [`fonctionnel/parcours.md`](fonctionnel/parcours.md) | Une édition, de la préparation au jour J : le fil de l'application et qui s'en sert (organisateur, animateur, responsable de stand, ressources humaines) |
| [`fonctionnel/planifier.md`](fonctionnel/planifier.md) | Planifier : l'état de l'édition, le calcul du planning, la relecture et les retouches |
| [`fonctionnel/decider-diagnostiquer.md`](fonctionnel/decider-diagnostiquer.md) | Décider et diagnostiquer : comprendre un planning, corriger, suivre le jour J |
| [`fonctionnel/organiser-annee.md`](fonctionnel/organiser-annee.md) | Organiser l'année : créer, dupliquer et comparer les éditions, consignes de l'autorité |
| [`fonctionnel/referentiels.md`](fonctionnel/referentiels.md) | Référentiels : stands, animateurs, créneaux, compétences |
| [`fonctionnel/consulter-planning.md`](fonctionnel/consulter-planning.md) | Consulter le planning : par jour, par stand, par personne, par typologie |
| [`fonctionnel/diffuser-echanger.md`](fonctionnel/diffuser-echanger.md) | Diffuser et échanger : publication, espace animateur, foire au planning, collecte des disponibilités |
| [`fonctionnel/outils.md`](fonctionnel/outils.md) | Outils : fichiers, exports, sauvegarde, alertes, assistant IA, pages légales |

## Infra

| Document | Contenu |
| --- | --- |
| [`installation.md`](installation.md) | **Point d'entrée de l'hébergeur** : ce qui est livré (image, compose de production), ce que fait la pile, la mise en route pas à pas |
| [`variables-environnement.md`](variables-environnement.md) | **Inventaire complet des variables d'environnement** : nom, défaut et rôle de chacune, relevés dans la configuration de l'application |
| [`exploitation.md`](exploitation.md) | **Exploiter une instance chez un client** : prérequis, variables d'environnement, délivrabilité des e-mails, sauvegarde et restauration, conservation des données, surveillance |
| [`securite.md`](securite.md) | Durcissement pour une exposition sur Internet : en-têtes de sécurité navigateur, plafonds de taille des requêtes, limitation de débit, déploiement de production |
| [`oidc.md`](oidc.md) | **Connexion par un fournisseur d'identité OIDC** : variables, rattachement d'un compte à une personne, compte de secours ; les comptes locaux (mot de passe) sont dans [`exploitation.md`](exploitation.md#les-comptes-locaux-mot-de-passe) |
| [`observabilite.md`](observabilite.md) | Suivi d'erreurs (Bugsink) et analytics d'usage (Cloudflare Web Analytics) en production : choix, intégration, variables d'environnement |
| [`versioning.md`](versioning.md) | **Versions et releases** : ce que promettent MAJOR/MINOR/PATCH, comment une release se fabrique (un formulaire GitHub, des notes générées, une image Docker), et la procédure de patch d'une version antérieure |
| [`rgpd.md`](rgpd.md) | **Ce qu'un hébergeur d'instance doit écrire et tenir** : convention de sous-traitance (art. 28), registre des traitements (art. 30), journal des purges, limites connues |
| [`accessibilite.md`](accessibilite.md) | **Accessibilité (RGAA 4.1)** : ce que l'audit a mesuré, les contrôles qui empêchent les écarts de revenir, ce qui reste ouvert, et la déclaration d'accessibilité qu'un exploitant renseigne |
| [`licences-tierces.md`](licences-tierces.md) | **Inventaire généré des licences tierces** : ce que l'image redistribue (dépendances Java, paquets npm) et sous quelle licence. Régénéré par `./mvnw license:add-third-party` puis `npm run licences` |

## Tech

| Document | Contenu |
| --- | --- |
| [`architecture.md`](architecture.md) | Stack, arborescence, découpage backend / frontend, base de données, conteneurisation |
| [`domaine.md`](domaine.md) | Modèle Timefold (`Animateur`, `Stand`, `Creneau`, `PosteAffectation`, `PlanningEvenement`) et mapping vers les contraintes |
| [`contraintes.md`](contraintes.md) | Pourquoi chaque règle existe et ce qu'elle coûte (la liste elle-même est `ConstraintCatalog`, servie par `GET /api/constraints`) |
| [`api.md`](api.md) | API REST : ce que le contrat OpenAPI ne peut pas dire (invariants transverses, arbitrages métier, pièges) ; la liste des endpoints est le contrat publié, généré dans [`schema/`](schema/) |
| [`mcp.md`](mcp.md) | Serveur MCP (outils exposés à un assistant IA, authentification, confidentialité) |
| [`import-export.md`](import-export.md) | Imports / exports : scénario YAML (et son schéma), import CSV des animateurs et des référentiels, exports CSV, PDF et ICS ; le dump SQL n'est plus qu'un outillage de test |
| [`developpement.md`](developpement.md) | Build, tests, CI, Podman, réglage du solveur, mises à jour Renovate |
| [`memoire-du-projet.md`](memoire-du-projet.md) | **Ce que l'historique portait** : les mesures qui ont tranché un réglage du solveur, les pistes abandonnées, et l'incident précis derrière chaque garde-fou. Complément des décisions, à lire quand on se demande « pourquoi est-ce écrit comme ça ? » |
| [`decisions/`](decisions/README.md) | **Décisions d'architecture** : ce qui a été choisi, contre quelles alternatives, et ce que ça engage. Commence par le cloisonnement par édition (modèle, en-tête `X-Edition-Id`, duplication, plan de migration) |
| [`diagrammes/`](diagrammes/) | **Diagrammes PlantUML** (séquences, états, modèle) : sources et SVG générés, chacun montré par le document qui possède son sujet. Régénération et contrôles dans [`developpement.md`](developpement.md#diagrammes) |

La mémoire destinée aux agents IA (Copilot, Claude Code, …) est centralisée dans
un fichier à la racine, [`../AGENTS.md`](../AGENTS.md), complété d'un seul
fichier de répertoire, [`../src/main/webui/AGENTS.md`](../src/main/webui/AGENTS.md),
qui porte les conventions du frontend et n'est chargé que lorsqu'un agent y
travaille.
