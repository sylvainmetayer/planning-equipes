# Brancher un assistant IA sur le serveur MCP

Vous savez comment un client MCP (Claude, Le Chat, un outil maison) s'authentifie auprès de l'instance, comment renouveler la clé sans toucher au serveur, ce qui borne le rythme et les essais, comment passer un proxy d'accès, et ce qui ne sort jamais vers l'assistant. Ce que l'assistant peut faire une fois connecté est décrit dans [les outils du serveur MCP](mcp-outils.md) ; l'organisateur qui veut seulement savoir ce qu'on peut lui demander trouvera l'essentiel dans [les outils de l'organisateur](../regles/outils-de-l-organisateur.md).

Le serveur répond sur `POST /mcp` (streamable HTTP) et `GET /mcp/sse`, fournis par `quarkus-mcp-server-http`. Presque tout ce que fait l'administration est exposé ; la seule restriction de fond est la confidentialité des données animateur.

![Un appel d'outil MCP, de la clé au service](repo:docs/diagrammes/mcp.svg)

<sub>Source : [`diagrammes/mcp.puml`](repo:docs/diagrammes/mcp.puml).</sub>

## Choisir la clé ou OAuth

Deux portes existent, au choix du client ([ADR 0097](repo:docs/decisions/0097-mcp-par-oauth-et-rotation-de-la-cle.md)). La **clé** se présente dans un en-tête configurable (`X-MCP-Api-Key` par défaut, `PLANNING_MCP_API_KEY_HEADER`) ou en `Authorization: Bearer`. Elle reste le choix des clients qui ne savent pas faire OAuth et celui des instances derrière un proxy d'accès qui consomme `Authorization`. **OAuth** s'ajoute dès que le fournisseur d'identité est configuré (`OIDC_ENABLED`, voir [la connexion OIDC](../installer/oidc.md)).

Tant que `PLANNING_MCP_API_KEY` est vide et qu'aucune clé n'a été générée, `/mcp` répond `401` à tout le monde : le serveur peut vider la base et lancer un calcul, un refus de servir vaut mieux qu'une exposition sans authentification.

La politique de `/mcp` exige le rôle `mcp`, que porte seule l'identité construite à partir de la clé (`McpApiKeyAuthenticationMechanism`, `McpApiKeyIdentityProvider`). Une session admin de navigateur n'a pas ce rôle et reçoit `403` : avec une politique `authenticated`, l'authentification par formulaire, qui lit son cookie sur tous les chemins, aurait laissé un navigateur connecté atteindre `/mcp` sans clé.

Quand un client envoie sa clé dans `Authorization: Bearer`, une valeur à trois segments séparés par des points est tenue pour un jeton OAuth : elle ne compte pas comme un essai de clé dans le verrou décrit plus bas, sans quoi un jeton expiré bloquerait l'adresse. Une clé est une chaîne aléatoire, jamais un JWT.

## Se connecter par OAuth

Avec le fournisseur configuré, `/mcp` est une ressource protégée au sens de la spécification d'autorisation MCP : le `401` porte `WWW-Authenticate: Bearer resource_metadata=…` et `/.well-known/oauth-protected-resource` nomme le fournisseur, de quoi laisser un client découvrir seul où ouvrir la connexion.

| Variable | Rôle | Défaut |
| --- | --- | --- |
| `OIDC_MCP_AUDIENCE` | Audience que le jeton doit porter. À déclarer chez le fournisseur (mappeur d'audience Keycloak, identifiant de ressource ADFS) | `planning-mcp` |

Le jeton ne vaut que pour **un compte actif** de l'application qui a un droit d'administrateur ou d'organisateur en vigueur ; ses rôles chez le fournisseur ne comptent pas. Un compte inconnu, désactivé ou seulement responsable de stand reçoit `403`.

- Un **administrateur** dispose des mêmes outils qu'avec la clé.
- Un **organisateur** ne désigne que les éditions de ses droits : une autre est refusée comme inconnue et `lister_editions` ne la montre pas. Il n'a pas les outils d'instance (création, renommage, duplication, activation, désactivation, suppression et comparaison d'éditions, sauvegardes). Les outils de jobs (`consulter_calcul`, `arreter_calcul`, `supprimer_calcul`) se limitent à ses éditions, et un outil sans argument `edition` ne retombe jamais sur une édition active hors de ses droits. `McpEditionStructurelleTest` impose que tout outil sans édition soit `@InstanceOnlyTool` ou justifié.
- Un **lecteur** (droit en lecture seule d'une édition) n'a que les outils qui déclarent `readOnlyHint = true` sans calculer (`@Computes` : simulations, prévisualisations, réparations…), sur ses éditions ; les autres répondent par un refus métier ([ADR 0099](repo:docs/decisions/0099-le-lecteur-voit-sans-rien-ecrire.md)).

Le journal des actions garde l'assistant comme auteur, comme avec la clé.

## Renouveler la clé sans toucher au serveur

L'écran MCP propose **Générer une nouvelle clé**. L'action exige un administrateur **connecté depuis moins de 10 minutes** (`AUTH_RECENT_WINDOW`, `PT10M` par défaut), exigence que partage la révélation de la clé de configuration. Elle remplace une re-saisie de mot de passe, que ni un compte OIDC ni un compte local n'ont à fournir. Sinon le serveur répond `409` avec le code `RECONNEXION_REQUISE_<mode>` et l'écran propose de se reconnecter :

| Mode | Reconnexion |
| --- | --- |
| `OIDC` | chez le fournisseur, rappelé avec `prompt=login` et `max_age=0`, puis retour sur l'écran |
| `LOCAL` | page de connexion : mot de passe et code |
| `BREAK_GLASS` | formulaire du secours |

L'instant de connexion est le `auth_time` du jeton d'identité : un fournisseur qui ne l'envoie pas, ou qui ignore `max_age` et `prompt`, ne permet pas la reconnexion. Pour un formulaire, c'est l'instant que la connexion a noté en mémoire, par empreinte du cookie de session, et un redémarrage demande à chacun de se reconnecter.

La clé n'est montrée **qu'une fois** : seule son empreinte (SHA-256) est conservée. On choisit combien de temps l'ancienne reste valable : aucun (elle est coupée tout de suite), 15 minutes, 1 heure ou 24 heures sur l'écran, 7 jours au plus par l'API (`POST /api/mcp/cle/rotation`). L'écran affiche la date de fin tant qu'elle court, et le journal garde l'action (`CLE_MCP_ROTEE`), jamais la clé.

La variable `PLANNING_MCP_API_KEY` marche tant qu'aucune clé n'a été générée. À la première génération elle devient « l'ancienne », donc valable pendant le recouvrement, puis ne rouvre plus rien, même conservée ou modifiée : vous pouvez la retirer du déploiement. Une clé générée n'est plus révélable (`409` sur `POST /api/mcp/cle`) : en cas de perte, on en génère une autre.

## Borner le rythme

`/mcp` est la seule surface authentifiée dont l'identifiant est une clé partagée à longue durée de vie, sans session ni second facteur. `McpRateLimiter` compte **toutes** les requêtes sous `/mcp`, authentifiées ou non, par adresse source, dans une fenêtre fixe ouverte à la première requête. Passé le plafond, la réponse est `429` avec un `Retry-After`, **y compris avec la bonne clé** : un plafond qui ne compterait que les échecs laisserait une clé volée appeler à la vitesse du réseau. La fenêtre se referme à l'heure, une requête refusée ne la repousse pas.

| Variable | Défaut | Usage |
| --- | --- | --- |
| `PLANNING_MCP_MAX_REQUESTS` | `120` | Requêtes tolérées par adresse et par fenêtre ; `0` ou moins désactive le plafond |
| `PLANNING_MCP_RATE_WINDOW` | `PT1M` | Durée de la fenêtre, au format ISO-8601 |
| `PLANNING_MCP_TRUSTED_PROXIES` | valeur de `CONNEXION_PROXYS_FIABLES` | Proxys inverses dont `X-Forwarded-For` est cru : adresses ou blocs CIDR séparés par des virgules |

Cent vingt requêtes par minute suffisent à un assistant piloté par une personne ; un script qui parcourt le référentiel les dépasse. Relevez alors le plafond, et ne le désactivez que si `/mcp` n'est joignable que depuis la machine elle-même. `PLANNING_MCP_TRUSTED_PROXIES` reprend par défaut la liste du verrou de connexion, les proxys étant un fait du déploiement ; une valeur vide compte comme absente, ce que transmettent les fichiers compose. Pourquoi l'en-tête se lit par la droite est expliqué dans [les limites de débit](../installer/securite-limites-de-debit.md#débit-du-serveur-mcp).

> **Derrière un proxy, renseignez `CONNEXION_PROXYS_FIABLES` en même temps que la clé.** Sans elle, tous les appels qui arrivent par le proxy tombent sur un seul compteur, et un appelant anonyme (le filtre passe avant l'authentification) y tient votre client MCP en `429` à deux requêtes par seconde.

Ce plafond ne remplace pas la limitation par IP du reverse proxy, première ligne pour le reste de l'application.

## Verrouiller après des clés refusées

Le plafond ne limite pas le devinage : 120 requêtes par minute font plus de sept mille essais par heure. L'entropie de la clé reste sa protection, et ce second garde fait seulement coûter du temps à une série d'essais. Au-delà de `PLANNING_MCP_MAX_FAILURES` clés refusées consécutives, l'adresse reçoit `429` pendant `PLANNING_MCP_LOCKOUT_DURATION`, y compris avec la bonne clé, sans quoi il suffirait d'attendre son tour. La durée court depuis le **dernier** échec : réessayer pendant le blocage ne rapporte rien.

| Variable | Défaut | Usage |
| --- | --- | --- |
| `PLANNING_MCP_MAX_FAILURES` | `5` | Clés refusées consécutives tolérées par adresse ; `0` ou moins désactive le verrou |
| `PLANNING_MCP_LOCKOUT_DURATION` | `PT10M` | Durée du blocage |

- **Configurer un client ne verrouille pas.** Une requête sans aucune clé reçoit son `401` sans rien compter, et une requête qui s'authentifie efface la série. Seule une clé présentée et refusée compte, ce qui inclut la bonne clé privée d'un en-tête exigé par `PLANNING_MCP_REQUIRED_HEADERS`.
- **Une tentative se compte avant d'être faite.** Une requête qui présente une clé réserve sa place d'échec avant l'authentification et la rend dès que le statut est écrit. Une rafale de clés en parallèle ne dépasse donc jamais `PLANNING_MCP_MAX_FAILURES` tentatives, alors que compter le `401` après coup en laissait passer autant que la rafale en contenait. Tant que ces places sont prises, la requête suivante reçoit `429` avec un `Retry-After` d'une seconde.

## Passer un proxy d'accès

`PLANNING_MCP_REQUIRED_HEADERS` exige des paires `Nom=valeur` **en plus** de la clé, par comparaison exacte à temps constant. C'est de la défense en profondeur qui protège l'origine si elle reste joignable sans passer par le proxy ; elle est facultative et vide par défaut. Deux pièges :

- ne listez que des en-têtes que le proxy **retransmet** : un proxy qui les consomme et les retire ferait échouer toutes les requêtes ;
- une entrée sans `=`, ou à valeur vide, n'est **jamais** satisfaite (échec fermé, comme pour la clé) : elle ne permet pas d'exiger la simple présence d'un en-tête.

Côté client, préférez l'en-tête dédié à `Authorization: Bearer` : un proxy d'accès consomme souvent `Authorization` pour son propre compte, et la clé n'arriverait jamais à l'application.

La page MCP de l'interface détecte le proxy par l'en-tête de réponse `X-Pangolin`. Pangolin ne l'ajoute pas lui-même, son réglage d'en-têtes personnalisés ne touchant que la requête vers le backend. `PangolinHeaderFilter` renvoie donc sur la réponse tout `X-Pangolin` reçu sur la requête : il suffit de le déclarer côté proxy.

## Se connecter depuis une offre gratuite

Le connecteur MCP est payant chez la plupart des assistants grand public. Ce qui départage les offres gratuites est **ce que le client sait présenter comme authentification** : un client qui n'envoie ni un en-tête libre ni `Authorization: Bearer` ne peut pas porter la clé. Ces offres bougent vite, relisez ce tableau avant de vous y fier (état de septembre 2026).

| Client | Connecteur MCP en gratuit | Ce qu'il présente | Utilisable avec la clé |
| --- | --- | --- | --- |
| Le Chat (Mistral) | oui | en-tête libre, Bearer, Basic, OAuth 2.1 | oui, dans l'en-tête dédié |
| Claude sur le web | oui, un seul connecteur | OAuth ou rien, pas d'en-tête libre | non, sans OAuth configuré |
| Claude Desktop | oui | en-tête libre, via le pont `mcp-remote` | oui, avec la configuration que la page MCP affiche |
| VS Code avec Copilot Free | oui | en-tête libre | oui |
| ChatGPT | non | | non |

Un **client hébergé** (Le Chat, Claude sur le web) ouvre la connexion depuis ses serveurs : l'instance doit être joignable depuis l'Internet public, la situation que `PLANNING_MCP_REQUIRED_HEADERS` durcit. Il n'a pas d'adresse stable, donc un proxy qui filtre par pays, par plage ou par SSO le bloque : ouvrez `/mcp` par une règle sur le chemin, pas sur l'appelant, et laissez la clé faire la garde. Un **client local** (Claude Desktop avec `mcp-remote`, VS Code) appelle depuis le poste : une instance ouverte sur le seul réseau de l'organisation reste joignable, au prix d'une installation par personne et d'un Node pour le pont. Une clé d'API facturée à l'usage, posée dans un client libre, est une troisième voie qu'on écarte souvent en la confondant avec un abonnement : une clé d'organisation sert tout le monde.

## Ce qui ne sort jamais

**Nom, prénom et date de naissance d'un animateur ne sortent jamais par MCP.** Sortent l'id, le statut majeur ou mineur et moins de 16 ans, dérivés de la date mais jamais la date, et les attributs de planification non identifiants.

L'id ne dit rien de la personne, structurellement : l'application l'attribue (`A1`, `A2`… dans chaque édition, [ADR 0050](repo:docs/decisions/0050-identifiants-generes-par-edition.md)), jamais un appelant ni un import à partir du nom, faute de quoi `marie.dupont` ferait ressortir la personne dans chaque réponse. Les outils de création (`creer_animateur`, `creer_stand`, `creer_stand_complet`, `creer_emplacement`, `creer_typologie`, `creer_contrainte_ad_hoc`, `creer_edition`, avec ou sans `source`) ne prennent donc aucun id et rendent celui qu'ils ont attribué. Un stand, une typologie ou un emplacement se crée avec un `code` lisible facultatif, et les outils qui citent une typologie ou un emplacement acceptent l'id comme le code.

Quatre mécanismes tiennent la règle :

1. **Des vues, jamais les objets du domaine.** Chaque outil renvoie un `record` dédié sans accesseur vers un champ personnel.
2. **Des messages d'écart anonymisés.** `ViolationFormatter` écrit « Prénom Nom (id) » pour l'interface web, `AnonymisationViolations` le réécrit en « animateur id » et retire la raison d'une contrainte ad hoc avant toute sortie MCP. Elle part des fiches de l'édition : le libellé exact de chaque animateur connu est remplacé, quelle que soit la forme du nom (un mot, une initiale, un chiffre). Une reconnaissance par la forme ne vient qu'ensuite, pour ce que le référentiel ne connaît plus, car une ligne enregistrée peut survivre à la fiche qu'elle nomme. Son pire cas est un libellé abîmé, jamais un nom qui sort ; un stand connu dont le nom a cette forme est épargné.
3. **Les textes libres sortent en « renseigné ou non ».** Le motif d'une demande d'échange, le commentaire d'une déclaration, la réponse de l'organisation, la raison d'un verrouillage ou d'une contrainte ad hoc : c'est là qu'un nom ou un rendez-vous médical revient (« je remplace Marie D. »), et aucun filtre ne le distingue d'une phrase anodine. Les vues portent `motifRenseigne`, `commentaireRenseigne`, `raisonRenseignee`. L'écriture reste ouverte : refuser une demande ou une déclaration prend toujours le commentaire que l'animateur lira. Le motif de fermeture d'un stand sort tel quel, il décrit un stand.
4. **Un test structurel.** `McpConfidentialiteStructurelleTest` parcourt par réflexion tous les `@Tool` et échoue si l'un expose `Animateur` ou un champ `prenom`, `dateNaissance`, `email`, `accessToken`, `commentaire`, `urgenceNom`, `urgenceTelephone`, `representantNom`, `representantTelephone`, `commentaireAdmin` ou `raison`, y compris à travers les génériques.

Deux conséquences côté écriture. `modifier_animateur` **fusionne** au lieu de remplacer, contrairement au `PUT` REST : un assistant qui ne peut pas lire nom, prénom et date de naissance ne peut pas les renvoyer, et un remplacement complet les effacerait. Et tous les outils qui créent ou modifient un animateur, un stand ou un créneau passent par le même `write*` de `ReferenceDataService` que les ressources REST : il relit la fiche avant d'écrire, note les champs qui ont bougé pour le journal et rend les avertissements de cohérence. Ces avertissements traversent en **codes** (`INDISPONIBILITE_HORS_EVENEMENT`, `MINEUR_PENDANT_EVENEMENT`…), jamais en phrases, parce que le message d'un avertissement sur un mineur date sa majorité, donc sa date de naissance décalée de dix-huit ans. `LayeringStructuralTest` interdit un `create*` ou `update*` nu hors de la façade.
