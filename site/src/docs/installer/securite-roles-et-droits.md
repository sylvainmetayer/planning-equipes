# Savoir ce que chaque rôle peut appeler

Chaque rôle de l'application (administrateur, organisateur, responsable de stand, droit RH) ouvre un périmètre précis de routes. Cette page dit lequel, jusqu'où va le droit d'un organisateur, et comment le dépôt empêche qu'une nouvelle route s'ouvre à un rôle sans que quelqu'un l'ait décidé. Les droits s'accordent dans Paramètres › Comptes ; les comptes eux-mêmes viennent de [l'annuaire OIDC](oidc.md) ou de [comptes locaux](securite-acces-administration.md#les-cinq-portes-de-ladministration).

## Ce que le rôle organisateur n'ouvre pas

Le rôle `organisateur` ouvre `/api` dans les seules éditions que ses droits en
vigueur nomment (un droit « toutes éditions » les couvre toutes) : une édition
hors de ses droits est refusée comme une édition inexistante
(`EDITION_INCONNUE`, 400) et absente de la liste des éditions. Il n'ouvre pas
les routes d'instance, réservées à `admin` (403) : comptes, webhooks, clé MCP,
sauvegardes, export et import de toute la base, écran Débogage, machine de
calcul, journal des connexions, horloge simulée, création, renommage,
duplication, activation et suppression d'une édition, comparaison de deux
éditions. L'import de scénario reste ouvert à l'organisateur, dans son édition
courante seulement : une section `edition:` qui en désigne une autre, ou une à
créer, est refusée (400). L'inventaire est tenu par `InstanceRoutesStructuralTest` (ressources) et par `RoutesOuvertesStructuralTest` (route par route, décrit plus haut) ; une nouvelle ressource ou route non classée fait échouer le build. Le rôle `responsable_stand` n'ouvre que `/api/responsable/*`
([ADR 0095](repo:docs/decisions/0095-le-responsable-de-stand-lit-son-stand.md), détaillé plus bas). Le `/mcp`
accepte aussi un jeton OAuth du fournisseur, sous les mêmes droits (voir
[`mcp-connexion.md`](../comprendre/mcp-connexion.md)) ; sa clé d'API reste. Les comptes locaux avec mot de passe, ouverts
même derrière un fournisseur, sont décrits dans
[les comptes locaux](comptes-locaux.md) et
[la connexion à l'administration](securite-acces-administration.md#les-cinq-portes-de-ladministration).

## Le classement des routes par rôle

`InstanceRoutesStructuralTest` classe des ressources entières : à l'organisateur
(par édition), au public, aux administrateurs (`@InstanceOnly`) ou au
responsable de stand. Dans une ressource ouverte à l'organisateur, une
**nouvelle méthode lui est ouverte aussi** tant que personne ne la marque
`@InstanceOnly` : c'est l'angle mort que ferme `RoutesOuvertesStructuralTest`.
Il tient deux inventaires versionnés, une route par ligne
(`Classe#méthode VERBE /chemin`, le verbe et le chemin départageant deux
surcharges) ; une sous-ressource ou une route héritée, que l'inventaire ne
verrait pas, fait échouer le test :

| Fichier (`src/test/resources/`) | Ce qu'il liste |
| --- | --- |
| `routes-organisateur.txt` | Chaque route d'une ressource « par édition » qui n'est pas `@InstanceOnly` |
| `routes-responsable.txt` | Les routes de `ResponsableResource` (lecture seule) |

Une route absente des deux échoue le test, **qui imprime la ligne à ajouter** ;
une ligne qui ne désigne plus aucune route échoue aussi. Ajouter la ligne dit
« ouverte à l'organisateur, exprès » ; sinon, annoter la méthode `@InstanceOnly`
(et l'ajouter à `INSTANCE_ROUTES` du premier test).

## Le responsable de stand : un droit de lecture, pas une porte de plus

Un compte (OIDC ou local) peut porter un droit `RESPONSABLE_STAND`, accordé dans
Paramètres › Comptes : **une édition, un ou plusieurs de ses stands**, une
expiration facultative. Il donne le rôle `responsable_stand`, qui n'ouvre que
`/api/responsable/*` ; `/api/*` en dehors de ce préfixe lui répond `403`, comme
à n'importe quel compte sans droit d'organisation (`role-gestion` ne le
contient pas), et `@RolesAllowed` double la règle HTTP sur chaque méthode.

- **Lecture seule** : les deux routes sont des `GET`
  (`ResponsableProjectionStructurelleTest` refuse une méthode qui n'en est pas
  une).
- **Édition active seulement** (ADR 0072) : la requête ne nomme ni édition ni
  stand (pas d'en-tête `X-Edition-Id`). Le service lit l'édition active et les
  stands des droits en vigueur du compte, et rend une vue vide quand rien ne
  s'ouvre (droit expiré, retiré, compte désactivé, édition inactive) : la
  réponse ne distingue pas « pas à vous » de « n'existe pas ».
- **Une projection, jamais le domaine** : horaires, places pourvues et vides
  et, selon le réglage, prénom et nom. Pas d'adresse, de téléphone, d'âge ni
  de statut mineur ; `ResponsableProjectionStructurelleTest` lit les types du
  record. Le plan lu est le plan **publié**, jamais le plan de travail.
- **Rien n'est mis en cache** : les deux routes répondent `Cache-Control: no-store`.
- **Seuls les administrateurs règlent la visibilité des noms** (`@InstanceOnly`).
- **L'affichette suit le même réglage** : elle est construite à partir de la
  même vue, pas d'une seconde lecture.
- `ResponsableResourceTest` balaie toutes les routes `GET` **sans paramètre
  de chemin** du contrat publié avec le jeton d'un responsable du stand S : aucune ne répond `403` à tort, et
  aucun corps ne contient le nom d'un animateur d'un autre stand, le nom d'un
  autre stand ni, quand le réglage dit « horaires seuls », aucun nom du tout.

## Le droit RH : le rapport de conformité, une édition à la fois

Un compte peut porter un droit `RH`, accordé dans Paramètres, onglet *Comptes*,
**pour une édition** (la base refuse un droit RH sans édition). Il donne le rôle
`rh`, qui n'ouvre que `/api/rh/*` (`role-rh`, aussi ouvert au rôle `admin`) ;
tout le reste de l'API lui répond `403`. Il s'ajoute aux autres droits du
compte : un RH qui est aussi organisateur garde la portée d'organisateur, et un
organisateur sans droit RH ne lit rien de ceci.

- **L'édition est dans le chemin**, jamais dans `X-Edition-Id` : un compte qui
  n'est qu'RH n'est organisateur d'aucune édition, et l'en-tête lui serait
  refusé. `RhService` décide du droit, édition par édition ; une édition sans
  droit et une édition inconnue répondent **le même `404`**.
- **Chaque consultation et chaque export (PDF, CSV) est journalisé** par
  `RhService`, dans l'édition du rapport, pas par le filtre de réponse, qui
  classe sous l'édition de l'en-tête. L'identifiant du compte est écrit, jamais
  son adresse. Les deux routes de fichier sont dans la liste argumentée de
  `CatalogueActions` pour cette raison.
- **Le rapport lit le plan publié**, jamais un brouillon (`409` sans publication)
  et ne contient ni date de naissance ni adresse : un nom, le statut mineur.
- **Pas d'outil MCP** : le rapport est nominatif, il ne sort jamais vers un assistant.
- **Les contacts d'urgence et représentants légaux** (`/api/rh/editions/{id}/contacts`)
  suivent les mêmes règles : droit RH ou administrateur, une édition par droit, `404`
  indistinct, lecture et écriture journalisées par le service. L'animateur lit et écrit
  les siens par son espace (`/api/espace-animateur/{jeton}/contacts`, session par code
  requise), jamais ceux d'un autre. Les données vivent dans `animateur_contact`, pas dans
  `Animateur` : `ContactsEspaceTest` cherche le numéro saisi dans l'espace d'un collègue,
  les fiches, le scénario exporté et ne le trouve nulle part.
- `RhResourceTest` couvre : l'organisateur sans droit RH (`403`), le RH d'une
  autre édition (`404`), l'administrateur (toutes les éditions), l'absence de
  l'en-tête d'édition et la trace dans le journal.
