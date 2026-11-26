# Les conventions de l'API : édition, authentification, erreurs

Vous saurez comment un client désigne l'édition sur laquelle il travaille, comment il s'authentifie (session, jetons publics), et comment lire les refus du serveur : le statut HTTP, puis le `code` qui distingue deux `409` de même statut. Ce qui vaut pour tous les appels est ici ; la liste des routes et des schémas est dans la spécification générée.

Tout est sous `/api`, en JSON. La spécification fait foi pour la forme : [`openapi.json`](repo:docs/schema/openapi.json) dans le dépôt, `/q/openapi` sur une instance qui tourne, `/q/swagger-ui` pour l'explorer. Les pages d'API de cette partie ne portent que ce qu'un schéma ne sait pas dire : les invariants, les arbitrages métier, les pièges. Le serveur MCP a sa propre porte, présentée dans [la connexion MCP](mcp-connexion.md).

## L'édition, désignée à chaque requête

Le référentiel est cloisonné par édition (« Année 2025 », « Année 2026 »), et rien ne circule de l'une à l'autre ([ADR 0001](repo:docs/decisions/0001-cloisonnement-par-edition.md), [le cloisonnement](cloisonnement-par-edition.md)). Le client nomme l'édition par l'en-tête `X-Edition-Id`, sur tous les endpoints sauf ceux qui ne travaillent dans aucune édition (`/api/editions`, `/api/config`, `/api/auth/*`) et le dump SQL (`/api/database/*`, outillage de test), qui ignore l'en-tête et emporte toutes les éditions. Deux onglets peuvent donc travailler sur deux éditions à la fois : la désignation vit dans la requête, jamais dans un état global.

| Situation | Réponse |
| --- | --- |
| En-tête absent | `400`, code `EDITION_REQUISE` |
| En-tête nommant une édition supprimée ou inconnue | `400`, code `EDITION_INCONNUE` |
| Geste qui sort de l'instance (publier, envoyer un courriel, ouvrir l'espace animateur, le flux ICS ou l'affichage mural) sur une édition inactive | `409`, code `EDITION_INACTIVE` |

Il n'existe aucun repli sur une « édition par défaut » : le frontend répond au refus en faisant choisir l'édition avant d'afficher quoi que ce soit. Une seule édition est **active** (champ `active` de `GET /api/editions`, [ADR 0072](repo:docs/decisions/0072-une-seule-edition-active.md)). `PUT /api/editions/{id}/active` et `DELETE /api/editions/{id}/active` l'activent ou la désactivent, et `GET /api/editions/{id}/activation` dit d'abord ce que la bascule ferme.

Le verrou « un solveur à la fois » reste **global à l'instance**, quelle que soit l'édition ([le calcul](api-calcul.md)).

Le dump SQL n'est pas une sauvegarde de la base. Il restaure les tables qui décrivent le jeu de données, et neuf tables restent volontairement dehors : l'horloge, les paramètres de sauvegarde, les accès et sessions d'espace, la file de jobs, le journal d'actions, entre autres. Elles décrivent la machine, qui y a droit en ce moment ou ce qu'on lui a demandé de faire ensuite. Le journal est exclu pour une raison propre : un dump supprime ce qu'il emporte, et l'inclure ferait effacer le journal local à chaque import. La copie qui couvre tout est le `pg_dump` de nuit ([ADR 0015](repo:docs/decisions/0015-sauvegarde-par-pg-dump-restauration-hors-application.md)).

## Lire un refus

Un refus voulu est un `BusinessError` (une hiérarchie scellée) que [`BusinessErrorMapper`](repo:src/main/java/dev/sylvain/planning/api/BusinessErrorMapper.java) transforme en réponse, une fois pour toutes les routes. Le corps est `{ "message": "…" }`, une phrase écrite pour la personne, que le frontend affiche telle quelle. Une exception ordinaire (`IllegalArgumentException` lancée par une bibliothèque, ou par accident) ne passe pas par là : elle reste un `500` et déclenche l'alerte. C'est ce qui distingue « refusé exprès » de « cassé ».

| Statut | Cas | Corps |
| --- | --- | --- |
| `400` | requête mal formée, règle métier enfreinte, champ du corps qui ne nomme rien | `message` |
| `404` | segment de chemin qui ne nomme rien | `message` |
| `409` | état qui refuse la requête (ex. un solveur occupé, une publication sans destinataire) | `message` |
| `409` | écriture fondée sur une lecture périmée : `code: "MODIFICATION_CONCURRENTE"` | `message`, `code`, `modifieLe` (la date réelle de la dernière écriture, à renvoyer pour écraser en connaissance de cause) |
| `409` | famille du référentiel figée par la phase de l'édition : `code: "REFERENTIEL_FIGE"` | `message`, `code`, `familles` |
| `400` / `409` | refus d'édition, voir plus haut | `message`, `code` |
| `409` | session trop ancienne pour une action sensible : `RECONNEXION_REQUISE_<mode>` | `code`, `mode`, `message` |
| `429` | trop d'essais (connexion, codes d'accès) | en-tête `Retry-After` |

Trois conséquences pour un client :

- **le statut ne suffit pas pour un `409`**. Il faut lire `code` (`MODIFICATION_CONCURRENTE` appelle « recharger ou écraser », `REFERENTIEL_FIGE` appelle « changer de phase ou débloquer la structure »), et, pour un refus de lancement de calcul, la forme du corps ([le calcul](api-calcul.md#un-seul-calcul-à-la-fois)) ;
- **la variante se choisit d'après l'origine de l'identifiant** : un segment de chemin qui ne désigne rien est un `404`, un champ du corps qui ne désigne rien est un `400` ;
- **une écriture qu'un calcul en cours défairait est refusée en `409`** avec le job en cause dans le corps, par le service lui-même : une route REST et un outil MCP rencontrent le même refus.

Les routes de concurrence et d'avertissement d'écriture sont détaillées dans [les référentiels](api-referentiels.md).

## Authentification

Presque toute l'API demande une session ; un appel non authentifié répond **`401`, jamais une redirection HTML**. Le périmètre dépend du rôle de la session :

| Préfixe | Qui l'ouvre |
| --- | --- |
| `/api/*` | les rôles `admin` et `organisateur` (l'organisateur ne voit que ses éditions) ; les routes qui règlent quelque chose pour toute l'instance sont réservées à `admin` (annotation `@InstanceOnly`) |
| `/api/responsable/*` | le rôle `responsable_stand`, et ce préfixe seulement |
| `/api/rh/*` | les rôles `rh` et `admin`, l'édition étant nommée dans le chemin |
| `/mcp` | la clé MCP ou une connexion OAuth, voir [la connexion MCP](mcp-connexion.md) |

Les comptes, les rôles et les droits sont présentés dans [comptes, rôles et accès](acces-et-comptes.md). Les exceptions publiques sont volontaires et listées une à une dans `application.properties` :

- l'espace animateur, `/api/espace-animateur/*` : le jeton d'URL est la clé, et il résout l'édition lui-même ;
- l'abonnement ICS, `/api/abonnements/*`, et l'affichage mural, `/api/mural/*` : chacun a son jeton dédié et n'ouvre qu'une lecture ([la page jour J et animateurs](api-jour-j-et-animateurs.md)) ;
- les routes de session (`/api/auth/me`, `/api/auth/connexion`, `/api/auth/mot-de-passe`, `/api/auth/logout`), `/api/config`, `/api/branding` et `/api/mentions-legales`, que le frontend lit avant son démarrage, page de connexion comprise.

![Connexion du coordinateur par le formulaire intégré](repo:docs/diagrammes/authentification.svg)

<sub>Source : [`diagrammes/authentification.puml`](repo:docs/diagrammes/authentification.puml).</sub>

Le formulaire `/j_security_check` répond `429` après cinq échecs depuis la même adresse, y compris avec le bon mot de passe ([la sécurité](../installer/securite.md)). La connexion par un fournisseur OIDC et les comptes locaux sont décrits dans [la connexion OIDC](../installer/oidc.md) et [les comptes locaux](../installer/comptes-locaux.md).

### La clé MCP

`POST /api/mcp/cle` révèle la clé configurée et `POST /api/mcp/cle/rotation` en génère une nouvelle, montrée une seule fois, l'ancienne continuant de valoir pendant `recouvrementMinutes` (zéro : elle s'arrête aussitôt). Les deux exigent une connexion récente (`AUTH_RECENT_WINDOW`, dix minutes par défaut) : une clé donne un accès complet en écriture et survit à la session d'où elle a été copiée, la révélation doit donc être liée à quelqu'un présent au clavier. Une session trop ancienne reçoit le `409 RECONNEXION_REQUISE_<mode>` du tableau, où `mode` dit par quelle voie se reconnecter. Une clé générée n'est jamais relue : seule son empreinte est gardée, et `POST /api/mcp/cle` répond alors `409`. Le contexte est dans [l'ADR 0097](repo:docs/decisions/0097-mcp-par-oauth-et-rotation-de-la-cle.md).

`GET /api/mcp/prompts` sert les prompts que le serveur MCP annonce (nom, description, texte), pour que l'écran MCP les propose aux clients qui ne savent pas les récupérer. Il les lit au lieu de les recopier.

## Derrière un reverse proxy qui termine le TLS

Quarkus fabrique la redirection de connexion en URL absolue, depuis le schéma de la requête reçue. Derrière un proxy qui termine le TLS, cette requête arrive en clair : sans précaution, la redirection part en `http://`, que le navigateur refuse depuis une page `https`, et la connexion est impossible.

`PROXY_ADDRESS_FORWARDING` (activé par défaut) fait suivre les `X-Forwarded-*` : schéma, hôte et chiffrement correspondent à ce que le visiteur a demandé, et le cookie de session est marqué `Secure`. Sans proxy en amont, rien ne change. Deux compléments :

- `QUARKUS_HTTP_PROXY_TRUSTED_PROXIES`, à renseigner dès que l'origine est joignable sans passer par le proxy : sinon n'importe quel client annonce le schéma et l'adresse de son choix ;
- `QUARKUS_HTTP_PROXY_ENABLE_FORWARDED_HOST` si le proxy réécrit `Host`.
