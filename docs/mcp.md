# Serveur MCP

Le service Quarkus expose un serveur [MCP](https://modelcontextprotocol.io/)
(Model Context Protocol) qui permet à un assistant IA de consulter et piloter
l'application en langage naturel : consulter les animateurs, créneaux,
stands et contraintes, activer/désactiver une contrainte, lancer ou arrêter
le solveur, et récupérer le résultat d'une résolution. Issue d'origine :
[#107](https://github.com/sylvainmetayer/planning-equipes/issues/107) — une
intégration volontairement bornée à un jeu d'outils curé plutôt qu'un accès
CRUD complet sur toutes les données de référence.

## Transport et endpoints

Fourni par l'extension Quarkiverse `quarkus-mcp-server-http`
(streamable HTTP, transport MCP recommandé) :

| Endpoint | Usage |
| --- | --- |
| `POST /mcp` | Transport MCP streamable HTTP (recommandé) |
| `GET /mcp/sse` | Transport SSE (rétrocompatibilité) |

## Authentification

Il n'existe pas de modèle utilisateur/session dans cette application (voir la
javadoc de `ConstraintResource.ConstraintToggleUpdate`), donc l'authentification
MCP est une **clé API partagée**, comparée à un en-tête HTTP configurable —
l'option la plus simple des deux évoquées par l'issue #107 (en-tête façon Basic
Auth vs OAuth2 ; OAuth2 supposerait un modèle utilisateur/consentement que
l'application n'a pas).

| Propriété | Défaut | Rôle |
| --- | --- | --- |
| `planning.mcp.api-key` (`PLANNING_MCP_API_KEY`) | vide | Clé attendue. Vide = MCP inutilisable (voir ci-dessous). |
| `planning.mcp.api-key-header` (`PLANNING_MCP_API_KEY_HEADER`) | `X-MCP-Api-Key` | Nom de l'en-tête HTTP porteur de la clé. |

La clé peut aussi être présentée via `Authorization: Bearer <clé>`.

**Sécurisé par défaut** : tant que `planning.mcp.api-key` n'est pas positionnée,
aucune requête vers `/mcp` ne peut s'authentifier (401 systématique) — le
serveur MCP peut déclencher une résolution et lire les données animateurs, une
exposition non authentifiée serait pire qu'un refus de servir.

Implémentation : `McpApiKeyAuthenticationMechanism` +
`McpApiKeyIdentityProvider` (package
`dev.sylvain.planning.mcp`), branchés sur la politique
`quarkus.http.auth.permission.mcp` (`application.properties`). Un simple bean
`io.quarkus.vertx.http.runtime.filters.Filter` ne suffit pas : les routes de
`quarkus-mcp-server-http` sont enregistrées en amont de la chaîne de filtres
Vert.x standard, donc un tel filtre ne voit jamais ces requêtes. Le moteur de
politiques `quarkus.http.auth.permission.*`, lui, s'applique uniformément à
tout chemin quelle que soit l'extension qui l'a monté.

## Confidentialité des données animateur

**Exigence de l'issue #107** : les nom, prénom et date de naissance des
animateurs ne doivent jamais sortir par MCP. Seuls sortent l'id et le statut
majeur/mineur (dérivé de la date de naissance, jamais la date elle-même), plus
les attributs de planification non identifiants (compétences, souhaits,
jours indisponibles, statut manager). Voir `AnimateurMcpTools.AnimateurView`.

## Outils exposés

| Outil | Description |
| --- | --- |
| `lister_animateurs` | Animateurs (id, statut majeur/mineur, compétences, souhaits, indisponibilités) — sans données personnelles |
| `consulter_animateur` | Un animateur par id — même filtrage |
| `lister_creneaux` | Créneaux du groupe de créneaux actif |
| `lister_stands` | Stands (typologies, effectifs, réserve majeurs/premium, niveau d'effort) |
| `lister_contraintes` | Catalogue des contraintes + résultat de la dernière analyse |
| `activer_contrainte` / `desactiver_contrainte` | Active/désactive une contrainte pour le prochain solve |
| `lancer_solveur` / `lancer_analyse` | Lance une résolution/analyse en tâche de fond depuis les données de référence persistées |
| `arreter_solveur` | Arrête le job en cours |
| `statut_solveur` / `lister_jobs` | État d'un job ou de tous les jobs |
| `resultats_animateur` | Postes affectés à un animateur, d'après le dernier planning persisté |
| `expliquer_echec_contraintes_dures` | Détail des contraintes HARD encore violées lors de la dernière analyse, avec message de chaque violation — répond au besoin de l'issue #107 de connaître les erreurs exactes d'un run avec contraintes dures |

Chaque outil délègue au service métier existant (`ReferenceDataService`,
`SolverJobService`, `PlanningService`, `ConstraintAnalysisStore`) — aucune
logique n'est dupliquée, voir le package
`dev.sylvain.planning.mcp`.

## Hors périmètre (volontairement)

L'issue évoque aussi l'ajout/édition de personnes, créneaux et stands en
langage naturel. Cette première intégration se limite à la consultation et
aux actions déjà exposées par l'API REST (toggle contrainte, pilotage
solveur) ; le CRUD complet des référentiels via MCP n'est pas implémenté ici
et reste un prolongement possible.
