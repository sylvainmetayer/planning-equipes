# Une édition, une partition des données

Aucune requête ne peut lire ou écrire l'édition d'une autre, et ce n'est pas une affaire de discipline : le prédicat d'édition est posé par un assistant unique et vérifié par un test qui lit tout le SQL du backend. Vous saurez comment l'édition d'une requête est résolue, comment une écriture traverse le code jusqu'au commit, comment on compare deux éditions sans jamais les mélanger, et ce que change le fait qu'une seule soit active.

Les choix de départ, et ce qui les a fait évoluer, sont dans [l'ADR 0001](repo:docs/decisions/0001-cloisonnement-par-edition.md).

## Le principe

Toutes les tables métier portent `edition_id`, et leurs identifiants métier une clé primaire composite `(edition_id, id)`. Une édition est un référentiel complet, une grille de créneaux et un planning résolu : « Année 2025 » et « Année 2026 » coexistent sans que rien ne circule de l'une à l'autre.

Le client désigne son édition par l'en-tête `X-Edition-Id`, requête par requête. Ce n'est pas un état global : deux onglets travaillent sur deux éditions en même temps. Les routes qui ne travaillent dans aucune édition (liste des éditions, configuration, authentification) s'en passent ; le détail HTTP est dans [les conventions de l'API](api-conventions.md).

## Comment l'édition d'une requête est résolue

`EditionContext` répond à la seule question que pose toute requête SQL du référentiel : dans quelle édition suis-je ? Il tranche dans cet ordre :

1. une édition forcée sur le fil courant par `EditionContext.executeIn` : un calcul ou une tâche planifiée écrit dans l'édition pour laquelle il a été lancé, même si le navigateur a changé d'édition depuis ;
2. l'édition du jeton d'espace animateur que porte la requête ;
3. l'en-tête `X-Edition-Id`, qui doit désigner une édition connue, sinon `400 EDITION_INCONNUE` ;
4. rien d'autre : sans en-tête, `400 EDITION_REQUISE`.

Il n'y a aucun repli sur une « édition par défaut ». Répondre « la première » est exactement ainsi que les données d'une édition finissent dans une autre : un onglet resté sur une édition supprimée, un assistant qui a oublié son argument. Le frontend choisit son édition avant tout écran (`editionChosenGuard`).

Hors requête, `EditionContext` lève une exception au lieu de deviner. Un travail qui survit à sa requête, comme un calcul ou le planificateur de notifications, doit nommer son édition par `executeIn`. Seul un contexte de requête qu'aucun client n'a ouvert (le transport MCP autour d'un outil non ciblé sur une édition, le harnais de test) se résout à l'édition active ; un outil MCP ciblé refuse d'abord l'appel qui n'a pas son argument `edition`.

![Une écriture du référentiel, de l'en-tête X-Edition-Id au commit](repo:docs/diagrammes/edition-requete.svg)

<sub>Source : [`diagrammes/edition-requete.puml`](repo:docs/diagrammes/edition-requete.puml).</sub>

## Le prédicat est posé par un assistant, vérifié par un test

`JdbcEditionScope` prépare chaque requête avec l'édition courante déjà liée à son premier paramètre (`prepareScoped`) : le prédicat `edition_id = ?` s'écrit en premier et les autres paramètres se comptent à partir de 2. Il ouvre aussi les transactions (voir plus bas).

Ce n'est pas l'assistant qui garantit le prédicat, c'est `IsolationEditionStructurelleTest`. Il lit le SQL de tout le backend et échoue sur toute requête visant une table métier sans `edition_id`. Il suit l'indirection : constante `static final`, variable locale, concaténation, paramètre d'un assistant privé. Écrire la requête dans une variable avant de la préparer ne la lui cache donc pas.

Une forme qu'il ne sait pas suivre (`StringBuilder`, `String.join`, une constante d'une autre classe, une variable réaffectée après sa déclaration) fait échouer le test, sauf si elle figure dans `INDIRECTIONS_ASSUMEES` avec une raison écrite. Toute table nommée par une requête préparée qu'il résout doit être déclarée soit partitionnée (`TABLES_METIER`), soit globale avec son motif (`TABLES_HORS_EDITION`).

Le filet a des bords, qu'il faut connaître : le SQL passé par un `Statement` simple (`DatabaseDumpService`, la table temporaire d'`EditionRepository`) n'est pas ancré, et une requête dont le nom de table est elle-même une variable n'a pas de nom à classer.

Une nouvelle table du référentiel suit la même convention et passe par cet assistant. Il y a un dépôt par famille (`StandRepository`, `AnimateurRepository`…) plutôt qu'un dépôt unique, pour que le prédicat reste auditable d'un `grep` sur le paquet. La seule écriture qui traverse toutes les familles, remplacer le référentiel par celui d'un scénario, passe par `ReferenceDataImportRepository`, qui emprunte une connexion et la passe à chaque dépôt.

`ReferenceData` est l'interface étroite que la construction d'un problème lit du référentiel, et rien d'autre. C'est ce qui permet aux tests qui n'ont ni conteneur ni base de construire des problèmes.

## Une transaction est un `scope.write`

Il n'y a pas de gestionnaire de transactions. Chaque `JdbcEditionScope.write` ou `writeAndReturn` ouvre une transaction, la valide ou l'annule, et restaure l'autocommit de la connexion avant de la rendre au pool.

Une unité de travail qui doit tout écrire ou rien (un stand avec les typologies et l'emplacement qu'il nomme, un import) fait ses écritures sous un seul `write` et passe sa `Connection` aux dépôts et services qui en prennent une. Une méthode qui prend une `Connection` rejoint la transaction de l'appelant ; une qui n'en prend pas valide seule. La règle est dans la signature, et c'est la raison pour laquelle `@Transactional` n'a pas été retenu : un intercepteur donnerait au même appel l'un ou l'autre sens selon une annotation posée plusieurs cadres plus haut, et le `commit()` manuel du scope lèverait sous JTA.

Narayana est pourtant déjà sur le classpath, et un prototype a montré que six lignes dans le scope en feraient un participant JTA. L'étude est close sans adoption, avec ses garde-fous chiffrés, dans [l'ADR 0028](repo:docs/decisions/0028-transactions-declaratives-narayana.md).

## Comparer deux éditions sans les mélanger

Comparer deux instantanés, ou le référentiel de deux éditions (`EditionDeltaService`), ne demande aucune requête inter-éditions. Chaque côté est lu dans son édition, par `EditionContext.executeIn` et les services ordinaires, puis la comparaison se fait en mémoire.

Elle ne rapproche jamais deux lignes par leur identifiant : deux éditions numérotent leurs fiches à partir du même compteur, si bien que `A151` peut désigner deux personnes ([ADR 0050](repo:docs/decisions/0050-identifiants-generes-par-edition.md)). Pour la même raison, aucune date n'est comparée telle quelle : la grille, et les fenêtres datées ou règles à dates d'un stand, s'alignent sur le rang du jour d'ouverture. Les réglages des contraintes se comparent par leur valeur effective : un poids saisi égal au défaut n'est pas une différence.

## Une seule édition parle à l'extérieur

Au plus une édition est active (`edition.active`, index unique partiel) ; aucune est un état valide, entre deux événements ([ADR 0072](repo:docs/decisions/0072-une-seule-edition-active.md)). Toute édition naît inactive (création, duplication, import) et reste pleinement utilisable en interne. La seule édition active publie, envoie des courriels aux animateurs et ouvre l'espace animateur, le flux ICS et l'affichage mural.

Quatre mécanismes portent cette règle, pour qu'un nouveau chemin ne puisse pas l'oublier :

- une méthode de service qui envoie un courrier ou publie porte `@RequiresActiveEdition` (`service/edition/`), et l'intercepteur répond `409 EDITION_INACTIVE` ;
- `NotificationDispatcher` écarte toute `Notification` d'une édition inactive ; `editionScoped` y est un `switch` exhaustif, si bien qu'un nouveau cas ne compile pas tant que quelqu'un n'a pas décidé ;
- les filtres de jeton répondent pour une édition inactive exactement comme pour un jeton inconnu ;
- les webhooks et l'alerte météo interrogent `OutboundEditionPolicy`, et nulle part ailleurs ([tâches de fond](taches-de-fond-et-notifications.md)).

`ActiveEditionEmissionStructuralTest` échoue sur tout envoi de `MailService` ou tout outil MCP sortant qui n'est ni gardé ni justifié.

Le verrou « un seul calcul à la fois » est, lui, global à l'instance, pas par édition.
