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

## Divergences volontaires avec le REST

Chacune répond à la même question : l'appelant est un assistant, pas un écran
qui vient d'afficher la donnée.

| Outil | Ce qu'il fait autrement | Pourquoi |
| --- | --- | --- |
| `modifier_animateur` | fusionne au lieu de remplacer | voir le corollaire ci-dessus |
| `deverrouiller` | échoue sur un id inconnu, là où `DELETE /api/verrouillages/{id}` répond 204 | un écran vient de lister les verrouillages et sait que la ligne existait ; un assistant travaille sur des ids qu'il a pu inventer, et « supprimé » sur un verrouillage inexistant lui ferait croire le planning libre de bouger |
| `capturer_instantane`, `restaurer_instantane` | lèvent une erreur là où le REST renvoie un 409 avec un corps | une réponse d'outil que l'assistant lit comme un succès ne doit pas être celle qui dit que rien n'a été écrit |
| `lister_affectations`, `consulter_instantane` | plafonnent la liste (200 par défaut) et annoncent le total | un planning réel porte plusieurs milliers de postes ; le total à côté de la liste est ce qui rend la troncature lisible, plutôt qu'un plafond caché |
| `lister_animateurs`, `lister_stands` | acceptent une `limite` mais ne plafonnent rien par défaut | leur taille est celle du référentiel, pas celle du planning : l'appelant qui les demande les veut en général en entier |

**La question « où ça coince ? » ne passe pas par la liste.**
`synthese_affectations` répond en quelques dizaines de lignes — postes pourvus
au total, par stand et par jour — là où `lister_affectations` dépenserait le
contexte entier de l'assistant avant qu'il ne voie qu'un stand est en
sous-effectif le samedi.

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
édition sans laisser la désigner. Sept exemptions : les deux outils de scénario
(fichiers livrés, validation pure), les quatre de pilotage des jobs — le
registre est global, chaque job portant l'édition pour laquelle il a été lancé —
et `lister_kpi_historique`, délibérément non cloisonné pour qu'une édition se
compare à la précédente.

Deux précisions sur l'écriture : les lancements de solveur capturent l'édition
**à la soumission**, donc le job y reste attaché même si le défaut change
ensuite ; et une section `edition:` dans un YAML importé **prime** sur
l'argument, puisque le fichier désigne explicitement sa cible.

## Les résolutions construisent leur problème au démarrage

`lancer_solveur` et `resoudre_incremental` passent par les soumissions
**rejouables** du `SolverJobService`, exactement comme les écrans. Le problème
n'est donc pas construit à l'appel mais au démarrage du job, ce qui apporte
trois propriétés d'un coup :

- **la mise en file** (`enFile`) : sans elle, un solveur occupé refuse la
  demande. Un job qui porte son problème tout construit ne peut pas attendre —
  il résoudrait l'édition telle qu'elle était avant l'attente ;
- **le rejeu au redémarrage** : un job en file survit à un arrêt du serveur ;
- **la fraîcheur** : un assistant qui continue de préparer l'édition pendant
  qu'une résolution tourne verra ses modifications prises en compte par la
  suivante.

`lancer_analyse` reste hors de ce mécanisme : une analyse porte son propre
problème, elle n'est donc ni mise en file ni rejouée.

Le périmètre de `resoudre_incremental` (animateurs, jours, stands) **ne touche
pas aux verrouillages** : il ne vaut que pour ce job. Ce qui est figé
durablement se pose avec `verrouiller`.

## Chaque outil annonce ce qu'il fait aux données

Les quatre *hints* de la spécification MCP sont déclarés sur **tous** les
outils. Ce n'est pas cosmétique : leurs valeurs par défaut sont
`destructiveHint = true` et `openWorldHint = true`, si bien qu'un outil qui ne
dit rien — `lister_stands` par exemple — s'annonce comme un appel destructif
vers l'extérieur. Un client qui demande confirmation avant les outils
destructifs la demandait alors pour chaque lecture, et le signal ne voulait
plus rien dire.

| Hint | Ce qu'il vaut ici |
| --- | --- |
| `readOnlyHint` | vrai pour les outils qui ne font que lire |
| `destructiveHint` | vrai pour les suppressions, les imports, une restauration d'instantané — et pour les résolutions, qui remplacent le planning persisté |
| `idempotentHint` | vrai quand rappeler l'outil avec les mêmes arguments ne change plus rien |
| `openWorldHint` | **toujours faux** : aucun outil ne sort de la base de l'application |

`McpAnnotationsStructurelleTest` tient les deux bouts. Le `openWorldHint = false`
sert de marqueur — un bloc oublié garde la valeur par défaut et échoue — et les
hints attendus sont dérivés du **nom** de l'outil, si bien qu'un
`supprimer_stand` qui se déclarerait en lecture seule échoue aussi. Un nom que
le test ne sait pas classer échoue également : un nouvel outil ne passe pas
sans que quelqu'un ait dit ce qu'il fait.

## Retoucher un poste sans relancer le solveur

`suggerer_reparations` cherche qui pourrait tenir un poste et chiffre chaque
candidat ; `affecter_poste` applique le choix. Ce sont deux outils et non un
seul avec un drapeau : un assistant qui évalue un mouvement et l'applique dans
le même appel ne laisse plus aucune étape à laquelle un humain puisse dire non.

`affecter_poste` est le seul outil qui écrit dans le planning résolu lui-même,
et il **refuse un poste verrouillé** — le verrou est ce qu'on lui demande de
respecter, il ne doit pas pouvoir passer dessus en silence.

## Le serveur ne sert pas que des outils

Deux autres familles MCP sont servies, parce qu'elles portent ce qu'un outil ne
peut pas dire.

**Trois prompts** — diagnostiquer les contraintes dures, vérifier une édition
avant de résoudre, résoudre sans perdre le planning en place. Chacun prend un
argument `edition` facultatif et enchaîne les outils dans le bon ordre. Ils sont
**distincts** du texte prêt-à-copier de la page MCP : celui-là est affiché,
traduit et collé par un humain, et reste en place pour les clients sans support
des prompts. Le texte des prompts est lu par `McpToolNamesTest`, qui échoue s'il
nomme un outil inexistant — la panne qu'avait déjà connue la page.

**Deux ressources**, à attacher une fois pour informer tous les appels suivants :

| URI | Contenu |
| --- | --- |
| `planning://contraintes` | le catalogue des contraintes, **généré depuis `ConstraintCatalog`** : c'est la liste que le solveur applique, elle ne peut donc ni décrire une règle absente ni en oublier une ajoutée la veille |
| `planning://vocabulaire` | stand, créneau, poste, vacation, amplitude, typologie, découpage, édition — et l'ordre dans lequel on prépare un événement |

Le vocabulaire est le seul texte écrit à la main ici : il résume
`docs/domaine.md` pour un lecteur qui ne verra jamais le modèle Java.

## Hors périmètre, volontairement

| Ce qui n'a pas d'outil | Pourquoi |
| --- | --- |
| Export de scénario YAML | Contient prénom, nom et date de naissance — il doit être réimportable |
| Dump SQL (export et import) | Toutes les données personnelles, et un fichier volumineux destiné à une sauvegarde, pas à une conversation |
| Exports PDF / ICS / CSV | Binaires ou nominatifs, destinés au téléchargement depuis l'interface |
| `/api/config` | Clés publiques destinées au navigateur : aucun intérêt pour un assistant |
| `/api/planning/sample`, `/persisted` | Un `PlanningEvenement` entier, plusieurs dizaines de Mo avec les données personnelles. `lister_affectations` couvre le besoin en restant filtré |
