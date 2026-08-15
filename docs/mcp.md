# Serveur MCP

Le service Quarkus expose un serveur [MCP](https://modelcontextprotocol.io/)
(Model Context Protocol) qui permet à un assistant IA de consulter et piloter
l'application en langage naturel. Issue d'origine :
[#107](https://github.com/sylvainmetayer/planning-equipes/issues/107), dont le
commentaire de suivi fixe le périmètre retenu ici : **tous les endpoints
peuvent être implémentés via MCP, la seule contrainte conservée est la
confidentialité des données animateurs**.

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
| `planning.mcp.required-headers` (`PLANNING_MCP_REQUIRED_HEADERS`) | vide | En-têtes supplémentaires exigés **en plus** de la clé (voir « Derrière un proxy »). |

La clé peut aussi être présentée via `Authorization: Bearer <clé>`.

**Sécurisé par défaut** : tant que `planning.mcp.api-key` n'est pas positionnée,
aucune requête vers `/mcp` ne peut s'authentifier (401 systématique) — le
serveur MCP peut vider la base, modifier les référentiels et déclencher une
résolution, une exposition non authentifiée serait pire qu'un refus de servir.

Implémentation : `McpApiKeyAuthenticationMechanism` +
`McpApiKeyIdentityProvider` (package
`dev.sylvain.planning.mcp`), branchés sur la politique
`quarkus.http.auth.permission.mcp` (`application.properties`). Un simple bean
`io.quarkus.vertx.http.runtime.filters.Filter` ne suffit pas : les routes de
`quarkus-mcp-server-http` sont enregistrées en amont de la chaîne de filtres
Vert.x standard, donc un tel filtre ne voit jamais ces requêtes. Le moteur de
politiques `quarkus.http.auth.permission.*`, lui, s'applique uniformément à
tout chemin quelle que soit l'extension qui l'a monté.

## Derrière un proxy d'accès (Pangolin, en-têtes personnalisés)

Quand l'application est déployée derrière un proxy d'accès type
[Pangolin](https://docs.pangolin.net/manage/access-control/links#use-the-access-token),
le proxy s'intercale avant le serveur MCP et exige son propre jeton. Le trajet
d'une requête est donc : client MCP → proxy (jeton d'accès) → application (clé
API). Deux réglages, indépendants l'un de l'autre.

### Côté client : envoyer les en-têtes du proxy

Le client MCP doit joindre le jeton du proxy à *chaque* requête, en plus de la
clé API. Pangolin attend par défaut deux en-têtes (`P-Access-Token-Id` et
`P-Access-Token`) ; certains déploiements les renomment, se référer à la
configuration de l'instance.

```json
{
  "mcpServers": {
    "planning-equipes": {
      "type": "http",
      "url": "https://planning.exemple.fr/mcp",
      "headers": {
        "X-MCP-Api-Key": "<clé PLANNING_MCP_API_KEY>",
        "P-Access-Token-Id": "<id du jeton Pangolin>",
        "P-Access-Token": "<jeton Pangolin>"
      }
    }
  }
}
```

Utiliser l'en-tête dédié `X-MCP-Api-Key` plutôt que `Authorization: Bearer`
dans ce cas de figure : un proxy d'accès consomme fréquemment `Authorization`
pour son propre compte, et la clé n'arriverait alors jamais à l'application.

Pangolin accepte aussi le jeton en query string
(`?p_token=<id>.<jeton>`), utile pour un client qui ne sait pas ajouter
d'en-têtes ; le chemin `/mcp` et la clé API restent inchangés.

### Côté serveur : exiger ces en-têtes en plus de la clé

`planning.mcp.required-headers` liste des paires `Nom-Header=valeur` séparées par
des virgules (virgule littérale à échapper en `\,`), exigées en plus de la clé
API — la comparaison est exacte et à temps constant, et une paire absente ou
différente donne 401 comme une clé invalide.

```bash
PLANNING_MCP_REQUIRED_HEADERS='P-Access-Token-Id=<id>,P-Access-Token=<jeton>'
```

C'est de la défense en profondeur : elle protège l'origine si celle-ci reste
joignable sans passer par le proxy. Elle est **facultative et vide par
défaut** — un déploiement sans proxy n'a rien à changer.

Deux pièges :

- ne lister que des en-têtes que le proxy **retransmet** ; un proxy qui les
  consomme et les retire ferait échouer toutes les requêtes ;
- une entrée sans `=`, ou avec une valeur vide, n'est jamais satisfaite (échec
  fermé, cohérent avec `planning.mcp.api-key`) : ce n'est pas un moyen d'exiger la
  simple présence d'un en-tête.

## Confidentialité des données animateur

**Seule restriction conservée par l'issue #107** : les nom, prénom et date de
naissance des animateurs ne sortent jamais par MCP. Seuls sortent l'id, le
statut majeur/mineur et moins-de-16-ans (dérivés de la date de naissance,
jamais la date elle-même) et les attributs de planification non identifiants
(compétences, souhaits, jours indisponibles, statut manager).

Trois mécanismes la garantissent :

1. **Des vues, jamais les objets de domaine.** Chaque outil renvoie un `record`
   dédié (`AnimateurView`, `AffectationView`, `HeuresAnimateurView`…) qui ne
   possède structurellement aucun accesseur vers un champ personnel.
2. **Anonymisation des messages de violation.** Les lignes lisibles produites
   par `ViolationFormatter` pour l'interface web désignent un animateur par
   « Prénom Nom (id) » ; `AnonymisationViolations` les réécrit en
   « animateur id » avant toute sortie MCP.
3. **Un test structurel de non-régression.**
   `McpConfidentialiteStructurelleTest` parcourt par réflexion *tous* les
   `@Tool` du package et échoue si l'un d'eux expose la classe `Animateur` ou
   un champ `prenom`/`dateNaissance`, y compris à travers les génériques — un
   nouvel outil mal filtré casse le build sans avoir à y penser.

Corollaire côté écriture : `modifier_animateur` fusionne au lieu de remplacer
(contrairement à `PUT /api/animateurs/{id}`). Un assistant qui ne peut pas lire
nom/prénom/date de naissance ne peut pas les renvoyer non plus : un remplacement
complet les effacerait à chaque modification.

## Outils exposés

### Animateurs (filtrés confidentialité)

| Outil | Description |
| --- | --- |
| `lister_animateurs` / `consulter_animateur` | Id, statut majeur/mineur, compétences, souhaits, indisponibilités |
| `creer_animateur` | Création ; nom/prénom/date de naissance facultatifs et jamais relus |
| `modifier_animateur` | Fusion : les champs omis conservent leur valeur en base |
| `supprimer_animateur` | Suppression |

### Stands, emplacements, typologies

| Outil | Description |
| --- | --- |
| `lister_stands` / `consulter_stand` | Typologies, effectifs, réserve majeurs/premium, effort, emplacement, plages |
| `creer_stand` / `modifier_stand` / `supprimer_stand` | CRUD, modification par fusion |
| `ajouter_fermeture_stand` / `ajouter_ouverture_stand` / `effacer_plages_stand` | Plages de fermeture et d'ouverture d'un stand |
| `lister_emplacements` / `creer_emplacement` / `modifier_emplacement` / `supprimer_emplacement` | Emplacements géographiques |
| `lister_typologies` / `creer_typologie` / `modifier_typologie` / `supprimer_typologie` | Référentiel des typologies de jeu |

### Créneaux, groupes et découpage

| Outil | Description |
| --- | --- |
| `lister_creneaux` / `lister_tous_les_creneaux` | Créneaux du groupe actif, ou tous groupes confondus |
| `creer_creneau` / `modifier_creneau` / `supprimer_creneau` | CRUD des créneaux |
| `lister_groupes_creneaux` / `creer_groupe_creneaux` / `modifier_groupe_creneaux` / `supprimer_groupe_creneaux` | Groupes de créneaux |
| `activer_groupe_creneaux` | Choisit le groupe sur lequel portera la prochaine résolution |
| `previsualiser_decoupage` / `generer_decoupage` | Découpage des amplitudes en vacations |

### Paramètres et contraintes

| Outil | Description |
| --- | --- |
| `lister_contraintes` | Catalogue métier + résultat de la dernière analyse |
| `activer_contrainte` / `desactiver_contrainte` | Active/désactive une contrainte pour le prochain solve |
| `consulter_parametres_legaux` / `modifier_parametres_legaux` | Durées maximales, pauses, repos |
| `consulter_parametres_decoupage` / `modifier_parametres_decoupage` | Paramètres de génération des vacations |
| `consulter_parametres_solveur` / `modifier_parametres_solveur` | Durée de résolution par défaut |
| `lister_contraintes_ad_hoc` / `creer_contrainte_ad_hoc` / `supprimer_contrainte_ad_hoc` | Contraintes au cas par cas (animateurs désignés par id) |

### Scénarios et données

| Outil | Description |
| --- | --- |
| `lister_scenarios` | Scénarios livrés avec l'application |
| `importer_scenario` / `importer_scenario_yaml` | Import d'un scénario livré ou d'un contenu YAML (destructif) |
| `valider_scenario_yaml` | Validation structurelle d'un YAML sans rien importer |
| `reinitialiser_donnees` | Vide entièrement la base (destructif) |

### Solveur et résultats

| Outil | Description |
| --- | --- |
| `lancer_solveur` / `lancer_analyse` | Résolution/analyse en tâche de fond depuis les données de référence |
| `arreter_solveur` / `statut_solveur` / `lister_jobs` / `supprimer_job` | Pilotage et historique des jobs |
| `volumetrie` | Taille réelle du problème que construirait la prochaine résolution |
| `analyser_faisabilite` | Diagnostic de capacité avant résolution, sans lancer de solve |
| `etat_planning` | Groupe résolu, date de résolution, affectations persistées, fraîcheur des données |
| `lister_affectations` | Affectations persistées, filtrables par stand, créneau, animateur ou non-pourvus |
| `resultats_animateur` | Postes affectés à un animateur donné |
| `heures_travaillees` | Heures par animateur (id seul) et par semaine ISO |
| `expliquer_affectation` / `simuler_swap` | Explication du score d'un poste, simulation d'un échange |
| `expliquer_echec_contraintes_dures` | Contraintes HARD encore violées lors de la dernière analyse, messages anonymisés |

Chaque outil délègue au service métier existant (`ReferenceDataService`,
`SolverJobService`, `PlanningService`, `PlanningPersistenceService`,
`HeuresPlanningService`, `FeasibilityAnalyzer`, `ConstraintAnalysisStore`) —
aucune logique n'est dupliquée, voir le package
`dev.sylvain.planning.mcp`. Les outils d'import de scénario
délèguent à `ReferenceDataResource`, qui possède l'orchestration
(paramètres épinglés, puis planning, puis `decoupageAuto:` et `typologies:`)
dont l'ordre est significatif.

Les endpoints qui exigent un `PlanningFestival` complet dans leur corps
(`/api/solve`, `/api/planning/hours`, `/api/postes/{id}/explication`…) sont
exposés ici en travaillant sur le **dernier planning persisté** : un assistant
n'a aucun moyen réaliste d'envoyer une charge utile de plusieurs dizaines de
Mo.

## Hors périmètre (volontairement)

| Endpoint REST | Pourquoi pas d'outil MCP |
| --- | --- |
| `GET /api/planning/export-scenario` | Le YAML exporté contient prénom, nom et date de naissance de chaque animateur (il doit être ré-importable) — exactement ce que l'issue #107 interdit de faire sortir. |
| `GET /api/database/export`, `POST /api/database/import` | Le dump SQL contient toutes les données personnelles ; c'est de plus un fichier binaire/volumineux destiné à une sauvegarde, pas à une conversation. |
| `POST /api/planning/export/pdf/*`, `/ics/*`, `/api/planning/hours/export` | Exports binaires ou CSV nominatifs, destinés au téléchargement depuis l'interface. |
| `GET /api/config` | Clés publiques destinées au navigateur (Sentry, PostHog) : aucun intérêt pour un assistant. |
| `POST /api/debug/test-exception` | Endpoint de test de la remontée d'erreurs. |
| `GET /api/planning/sample`, `GET /api/planning/persisted` | Renvoient un `PlanningFestival` entier (plusieurs dizaines de Mo, avec les données personnelles) ; `lister_affectations` et `lister_scenarios` couvrent le besoin en restant filtrés. |
