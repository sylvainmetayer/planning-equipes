# Serveur MCP

`POST /mcp` (streamable HTTP) et `GET /mcp/sse` (rétrocompatibilité), fournis
par `quarkus-mcp-server-http`. **La liste des outils est celle que le serveur
annonce lui-même** — le code du paquet `mcp` en est la source. Ce document porte
les règles qui ne s'y lisent pas.

Périmètre retenu : tous les endpoints peuvent être exposés, **la seule
restriction est la confidentialité des données animateur**.

## Authentification : clé API partagée

L'application n'a pas de modèle utilisateur, donc pas d'OAuth2 — il supposerait
un consentement qui n'existe pas ici. La clé se présente dans un en-tête
configurable (`X-MCP-Api-Key` par défaut) ou en `Authorization: Bearer`.

**Sécurisé par défaut** : tant que `PLANNING_MCP_API_KEY` n'est pas positionnée,
`/mcp` répond 401 systématiquement. Le serveur MCP peut vider la base, modifier
les référentiels et lancer une résolution : une exposition non authentifiée
serait pire qu'un refus de servir.

> **Un filtre Vert.x ne suffit pas.** Les routes de `quarkus-mcp-server-http`
> sont enregistrées **en amont** de la chaîne de filtres standard : un bean
> `Filter` ne voit jamais ces requêtes. D'où le passage par le moteur de
> politiques `quarkus.http.auth.permission.*`, qui s'applique à tout chemin
> quelle que soit l'extension qui l'a monté.

`PLANNING_MCP_*` remplace `PLANNING_MCP_*`, encore accepté en repli.

## Derrière un proxy d'accès

`PLANNING_MCP_REQUIRED_HEADERS` exige des paires `Nom=valeur` **en plus** de la
clé — comparaison exacte, à temps constant. C'est de la défense en profondeur :
elle protège l'origine si celle-ci reste joignable sans passer par le proxy.
Facultative, vide par défaut.

Deux pièges :

- ne lister que des en-têtes que le proxy **retransmet** ; un proxy qui les
  consomme et les retire ferait échouer toutes les requêtes ;
- une entrée sans `=`, ou à valeur vide, n'est **jamais** satisfaite — échec
  fermé, cohérent avec la clé. Ce n'est pas un moyen d'exiger la simple présence
  d'un en-tête.

Côté client, préférer l'en-tête dédié à `Authorization: Bearer` : **un proxy
d'accès consomme fréquemment `Authorization` pour son propre compte**, et la clé
n'arriverait jamais à l'application.

La page MCP de l'interface détecte le proxy via l'en-tête de réponse
`X-Pangolin`. Pangolin ne l'ajoute pas lui-même — son réglage « en-têtes
personnalisés » ne modifie que la requête vers le backend, pas la réponse au
navigateur. `PangolinHeaderFilter` renvoie donc sur la réponse tout `X-Pangolin`
reçu sur la requête : il suffit de le déclarer côté proxy.

## Confidentialité des données animateur

**Nom, prénom et date de naissance ne sortent jamais par MCP.** Sortent l'id, le
statut majeur/mineur et moins-de-16-ans — dérivés de la date, jamais la date —
et les attributs de planification non identifiants.

Trois mécanismes :

1. **Des vues, jamais les objets de domaine.** Chaque outil renvoie un `record`
   dédié qui ne possède structurellement aucun accesseur vers un champ
   personnel.
2. **Anonymisation des messages de violation.** `ViolationFormatter` désigne un
   animateur par « Prénom Nom (id) » pour l'interface web ;
   `AnonymisationViolations` réécrit en « animateur id » avant toute sortie MCP.
3. **Un test structurel.** `McpConfidentialiteStructurelleTest` parcourt par
   réflexion tous les `@Tool` et échoue si l'un expose `Animateur` ou un champ
   `prenom` / `dateNaissance` / `email` / `accessToken`, **y compris à travers
   les génériques**. Un nouvel outil mal filtré casse le build sans qu'on ait à
   y penser.

**Corollaire côté écriture** : `modifier_animateur` fusionne au lieu de
remplacer, contrairement au `PUT` REST. Un assistant qui ne peut pas lire
nom/prénom/date de naissance ne peut pas les renvoyer : un remplacement complet
les effacerait à chaque modification.

## L'édition se désigne argument par argument

Une requête MCP n'est pas une requête JAX-RS : `EditionHeaderFilter` ne la voit
jamais, et **`X-Edition-Id` n'a aucun effet sur `/mcp`**. Chaque outil porte un
argument `edition` facultatif, qui accepte l'id ou le nom. Omis, l'outil
travaille dans l'édition par défaut.

**Une édition inconnue échoue**, au lieu de retomber sur la courante — seule
divergence volontaire avec l'en-tête HTTP. Les deux appelants ne sont pas dans
la même situation : un onglet resté ouvert sur une édition supprimée doit
continuer d'afficher ses écrans, alors qu'un assistant qui nomme une édition
s'apprête à y écrire, et un repli silencieux enverrait l'écriture dans la
mauvaise édition sans que rien ne le signale. Le message énumère les éditions
existantes.

Trois pièces : `@EditionArg` marque l'argument porteur — c'est l'annotation, pas
le nom, qui fait le lien ; `@EditionCiblee` se pose sur la **classe** d'outils,
si bien qu'un outil ajouté plus tard en hérite au lieu d'écrire silencieusement
dans l'édition par défaut ; `EditionCibleeInterceptor` lie l'édition autour de
l'appel.

> **Piège d'auto-invocation.** Un outil qui en appelle un autre sur `this`
> court-circuite l'intercepteur. Il doit passer par un helper privé, jamais par
> l'outil voisin.

`McpEditionStructurelleTest` échoue sur tout `@Tool` qui travaille dans une
édition sans laisser la désigner. Six exemptions : les deux outils de scénario
(fichiers livrés, validation pure) et les quatre de pilotage des jobs — le
registre est global, chaque job portant l'édition pour laquelle il a été lancé.

Deux précisions sur l'écriture : les lancements de solveur capturent l'édition
**à la soumission**, donc le job y reste attaché même si le défaut change
ensuite ; et une section `edition:` dans un YAML importé **prime** sur
l'argument, puisque le fichier désigne explicitement sa cible.

## Hors périmètre, volontairement

| Ce qui n'a pas d'outil | Pourquoi |
| --- | --- |
| Export de scénario YAML | Contient prénom, nom et date de naissance — il doit être réimportable |
| Dump SQL (export et import) | Toutes les données personnelles, et un fichier volumineux destiné à une sauvegarde, pas à une conversation |
| Exports PDF / ICS / CSV | Binaires ou nominatifs, destinés au téléchargement depuis l'interface |
| `/api/config` | Clés publiques destinées au navigateur : aucun intérêt pour un assistant |
| `/api/planning/sample`, `/persisted` | Un `PlanningFestival` entier, plusieurs dizaines de Mo avec les données personnelles. `lister_affectations` couvre le besoin en restant filtré |
