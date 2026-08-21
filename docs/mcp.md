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

La page **MCP** de l'interface (menu Outils) reprend ces instructions pour
l'opérateur : elle affiche la configuration client prête à copier pour
l'instance en cours, et détecte le proxy via l'en-tête de réponse
`X-Pangolin: true` — quand il est présent, la page rappelle qu'il faut créer
un jeton d'accès et le présenter dans `P-Access-Token-Id`/`P-Access-Token`.

Cet en-tête n'est pas ajouté par Pangolin lui-même : son réglage « en-têtes
personnalisés » (onglet *Paramètres HTTP* d'une ressource) ne modifie que la
requête envoyée au backend, pas la réponse renvoyée au navigateur. C'est donc
`PangolinHeaderFilter` (package `api`) qui fait le lien : il renvoie tel quel,
sur la réponse, tout en-tête `X-Pangolin` reçu sur la requête. Il suffit donc
de déclarer `X-Pangolin: true` dans les en-têtes personnalisés de la ressource
Pangolin pour que la page MCP le détecte.

### La clé, vue depuis l'interface

La page interroge `GET /api/mcp/statut` au chargement. Clé absente, elle
**avertit** au lieu de laisser l'opérateur configurer un client qui
n'obtiendra que des 401 — le mode « sécurisé par défaut » ci-dessus est un
refus de servir silencieux, et un refus silencieux se diagnostique mal depuis
un client MCP.

Clé présente, un bouton « Révéler la clé d'API » demande **à nouveau le mot de
passe administrateur** (`POST /api/mcp/cle`). La session prouve déjà « un
admin » ; ce second contrôle existe parce que la clé est le seul secret de
l'interface qui **survit à la session** d'où il a été copié, ce qui fait d'un
onglet resté ouvert un risque d'une autre nature que le reste des écrans. Le
modèle de menace visé est donc quelqu'un devant un poste laissé sans
surveillance, pas un attaquant anonyme — celui-là n'arrive jamais jusque-là,
`/api/*` exigeant déjà la session.

Une fois révélée, la clé ne vit que dans un signal du composant : ni store, ni
`sessionStorage`, ni URL. Quitter la page détruit le composant, donc y revenir
redemande le mot de passe ; un minuteur de deux minutes l'efface aussi sur
place, relancé à chaque copie — copier est la seule interaction qui prouve que
quelqu'un est encore devant l'écran. La configuration client affichée juste en
dessous se met à jour avec la vraie clé tant qu'elle est visible, ce qui évite
le copier-coller manuel dans le JSON.

Le même bouton, la même demande de mot de passe et le même minuteur révèlent
aussi le jeton d'accès Pangolin, quand `planning.mcp.pangolin.access-token-id`
(`PLANNING_MCP_PANGOLIN_ACCESS_TOKEN_ID`) et `planning.mcp.pangolin.access-token`
(`PLANNING_MCP_PANGOLIN_ACCESS_TOKEN`) sont positionnées côté serveur : la
configuration client affichée contient alors la vraie valeur de
`P-Access-Token-Id`/`P-Access-Token` plutôt qu'un espace réservé. Les deux
propriétés sont facultatives et vides par défaut — sans elles la page continue
de rappeler qu'il faut coller le jeton à la main, comme avant.

Côté serveur, cinq échecs bloquent l'endpoint cinq minutes, et le bon mot de
passe ne lève pas le blocage — sinon il suffirait de le deviner une fois pour
annuler la limitation. Aucune trace n'est écrite : une ligne par révélation
n'apprendrait rien qui ne soit déjà dans la session, et ce journal deviendrait
lui-même un inventaire de qui détient la clé.

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
   un champ `prenom`/`dateNaissance` — ainsi que `email` et `jetonAcces`
   (issue #165 : l'adresse de contact est une donnée personnelle, et le jeton
   est la clé d'accès de l'espace animateur), y compris à travers les
   génériques — un nouvel outil mal filtré casse le build sans avoir à y
   penser.

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
| `lister_stands` / `consulter_stand` | Typologies, effectifs, réserve majeurs/premium, effort, emplacement, horaires récurrents et plages datées |
| `creer_stand` / `modifier_stand` / `supprimer_stand` | CRUD, modification par fusion |
| `creer_stand_complet` | Stand + emplacement + typologies + horaires d'ouverture en un seul appel |
| `ajouter_horaire_stand` / `effacer_horaires_stand` | Règles d'horaire récurrentes : une règle au lieu d'une plage datée par jour de festival |
| `ajouter_fermeture_stand` / `ajouter_ouverture_stand` / `effacer_plages_stand` | Plages datées, qui priment sur les règles pour le jour qu'elles nomment |
| `lister_emplacements` / `creer_emplacement` / `modifier_emplacement` / `supprimer_emplacement` | Emplacements géographiques |
| `lister_typologies` / `creer_typologie` / `modifier_typologie` / `supprimer_typologie` | Référentiel des typologies de jeu |

`creer_stand_complet` existe pour la mise en place d'une édition, où créer un
stand demande sinon quatre allers-retours — et où `creer_stand` échoue tant que
les typologies citées n'existent pas. Il crée l'emplacement absent quand
`emplacementNom` est fourni, et les typologies absentes **seulement** si
`creerTypologiesManquantes` vaut `true` : le refus d'une typologie inconnue est
une fonctionnalité, c'est lui qui transforme « NIJNA » en erreur au lieu d'une
seconde entrée quasi identique que personne ne remarque avant que le solveur ne
trouve aucun animateur compétent. La réponse énumère ce qui a été créé au
passage, pour que rien ne le soit à l'insu de l'utilisateur.

`ajouter_horaire_stand` prend ses fenêtres en une chaîne compacte,
`« 10:00-12:00,14:00- »` : une règle en porte régulièrement deux (la coupure
méridienne), ce que des arguments nommés borneraient à un maximum arbitraire.
Un tiret final laisse la fenêtre courir jusqu'à la fermeture du jour — même
notion que l'`heureFin` omise ailleurs. Omettre `heureFin` sur
`ajouter_fermeture_stand`/`ajouter_ouverture_stand` a le même effet. Voir
[`domaine.md`](domaine.md#horaires-récurrents).

### Créneaux et découpage

| Outil | Description |
| --- | --- |
| `lister_creneaux` | Créneaux de l'édition |
| `creer_creneau` / `modifier_creneau` / `supprimer_creneau` | CRUD unitaire |
| `diagnostiquer_grille_creneaux` | Ce que contient la grille en place, et si ce sont des amplitudes ou des vacations |
| `valider_creneaux` | Contrôle de cohérence de la grille (voir ci-dessous) |
| `previsualiser_creneaux_recurrents` / `creer_creneaux_recurrents` | Une règle récurrente au lieu de N créations unitaires |
| `supprimer_creneaux` | Suppression en lot, filtrée par dates ou heure de début |
| `previsualiser_decoupage` / `generer_decoupage` | Découpage des créneaux courants (lus comme amplitudes) en vacations, en place |

#### Amplitudes ou vacations : un choix qui ne se devine pas

Les outils qui écrivent ou contrôlent une grille exigent un argument `mode`
**sans valeur par défaut** (`ModeGrilleCreneaux`) :

- `AMPLITUDES` — des journées d'ouverture, destinées à être découpées en
  vacations par `generer_decoupage` avant résolution ;
- `VACATIONS` — des vacations finales, solvables telles quelles, pour
  l'utilisateur qui préfère poser lui-même toute sa grille.

Le mode change le verdict, il n'est donc pas cosmétique : deux créneaux qui se
chevauchent le même jour sont une double saisie entre amplitudes, et la
situation normale entre vacations décalées en familles. Un validateur qui
supposerait se tromperait une fois sur deux — d'où le refus d'un `mode` absent,
avec un message qui demande de trancher. `diagnostiquer_grille_creneaux`
renseigne l'assistant sans décider à sa place : son champ `modeCertain`
distingue ce que les données **prouvent** (une famille de décalage ou un
créneau de couverture de pause ne peut venir que du découpage) de ce qu'elles
suggèrent seulement — une grille de vacations saisie à la main est
indiscernable d'une grille d'amplitudes.

#### Récurrence

`creer_creneaux_recurrents` reprend le vocabulaire déjà appris sur les horaires
de stand plutôt que d'en inventer un second : portée `TOUS` / `JOURS_SEMAINE`
(avec `joursSemaine`) / `PLAGE` / `DATES`, et fenêtres compactes
`« 09:00-12:00,14:00-18:00 »`. « De 8h à 12h tous les jours sauf le week-end »
est donc *une* règle. Deux différences avec `ajouter_horaire_stand` :

- l'heure de fin est **obligatoire** — la forme ouverte `« 14:00- »` signifie
  « jusqu'à la fermeture », notion qui n'existe que pour un horaire évalué au
  regard d'un créneau ; un créneau *est* l'amplitude, il n'a rien d'extérieur
  dont hériter une fin ;
- `exclusions` retire des dates précises quel que soit le sélecteur, pour le
  cas « tous les jours sauf le 14 » qui obligerait sinon à tout énumérer.

Le numéro de jour ne se saisit jamais : il n'est pas stocké, il est redérivé
des dates à chaque lecture par `Creneau.assignerJours`, ce qui garde la
numérotation juste même quand un lot ajoute une date antérieure à toutes les
autres.

`previsualiser_creneaux_recurrents` prend exactement les mêmes arguments et
n'écrit rien : une règle qui se trompe d'une heure crée des dizaines de lignes
d'un coup, l'aperçu est là pour que ça se voie avant.

#### Ce que `valider_creneaux` contrôle

L'outil **agrège** trois analyses plutôt que d'en réimplémenter une quatrième.
`OuvertureStandsAnalyzer` juge déjà la relation stand↔créneau (stand jamais
ouvert, fenêtre sans effet, segment trop court) et `FeasibilityAnalyzer` juge
l'effectif ; ce que ni l'un ni l'autre ne regarde, c'est la cohérence interne
de la grille, et c'est ce que `GrilleCreneauxService` ajoute :

| Anomalie | Sévérité | Déclenchement |
| --- | --- | --- |
| `CRENEAU_INCOMPLET`, `DUREE_NULLE` | erreur | date ou heures manquantes, début égal à la fin |
| `DOUBLON` | erreur | mêmes date, heures et famille |
| `REPOS_QUOTIDIEN_IMPOSSIBLE` | erreur | vacation plus longue que `24 h − reposQuotidienMinimal` : toute affectation violera une contrainte dure |
| `CHEVAUCHEMENT` | avertissement | deux **amplitudes** du même jour se recouvrent |
| `TROU_DANS_LA_JOURNEE` | avertissement | plage intérieure qu'aucun créneau ne couvre |
| `AMPLITUDE_PLUS_COURTE_QUE_LA_VACATION_MINIMALE` | avertissement | le découpage ne produira rien d'exploitable ce jour-là |
| `VACATION_TROP_LONGUE` | avertissement | au-delà de `dureeVacationMaxMinutes` — amplitude déguisée ? |
| `DATE_ISOLEE` | avertissement | date à plus de 7 jours de toute autre : erreur de mois ou d'année |

Seules les erreurs rendent la grille invalide (`valide: false`) ; les
avertissements décrivent ce qui est suspect **sans** rien refuser, parce que
chacun d'eux a un cas légitime (une fermeture voulue entre midi et deux, un
festival qui saute une semaine).

### Paramètres et contraintes

| Outil | Description |
| --- | --- |
| `lister_contraintes` | Catalogue métier + résultat de la dernière analyse |
| `activer_contrainte` / `desactiver_contrainte` | Active/désactive une contrainte pour le prochain solve |
| `consulter_parametres_legaux` / `modifier_parametres_legaux` | Durées maximales, pauses, repos |
| `consulter_parametres_decoupage` / `modifier_parametres_decoupage` | Paramètres de génération des vacations |
| `consulter_parametres_solveur` / `modifier_parametres_solveur` | Durée de résolution par défaut |
| `lister_contraintes_ad_hoc` / `creer_contrainte_ad_hoc` / `supprimer_contrainte_ad_hoc` | Contraintes au cas par cas (animateurs désignés par id) — voir la note sur `AFFINITE` ci-dessous |

Trois des quatre types de contrainte ad hoc sont **durs**
(`INDISPONIBILITE_FORCEE`, `INCOMPATIBILITE`, `AFFECTATION_FORCEE`) ;
`AFFINITE` est l'exception voulue (issue #80) : une **récompense soft** pour
les créneaux où la paire tient le même stand. Un assistant qui la croirait
dure se tromperait dans les deux sens — il annoncerait la paire garantie
ensemble, et il chercherait une violation dure là où il n'y a qu'un score
soft moins bon. Déclarer une même paire à la fois incompatible et en affinité
est refusé à la saisie, avec un message explicite renvoyé tel quel par
l'outil.

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
| `POST /api/planning/export/pdf/*`, `GET /api/planning/export/pdf/global`, `/ics/*`, `/api/planning/hours/export` | Exports binaires ou CSV nominatifs, destinés au téléchargement depuis l'interface. |
| `GET /api/config` | Clés publiques destinées au navigateur (Sentry, Cloudflare) : aucun intérêt pour un assistant. |
| `POST /api/debug/test-exception` | Endpoint de test de la remontée d'erreurs. |
| `GET /api/planning/sample`, `GET /api/planning/persisted` | Renvoient un `PlanningFestival` entier (plusieurs dizaines de Mo, avec les données personnelles) ; `lister_affectations` et `lister_scenarios` couvrent le besoin en restant filtrés. |
