# Comptes, rôles et accès

Plusieurs personnes administrent une même instance, et l'historique doit dire laquelle a fait quoi. Vous saurez comment un compte, ses droits et son rôle se déduisent, ce que chacun des quatre rôles ouvre, et par quels tests le dépôt interdit qu'une nouvelle route s'ouvre à un rôle sans que quelqu'un l'ait décidé.

Le côté exploitant (brancher un annuaire, créer des comptes locaux) est dans [Brancher votre annuaire](../installer/oidc.md) et [Choisir et protéger la connexion](../installer/securite-acces-administration.md#les-cinq-portes-de-ladministration). Le côté organisateur est dans [Gérer les comptes et les droits](../regles/comptes-et-droits.md). Les en-têtes, les codes de statut et les routes d'authentification sont décrits dans [les conventions de l'API](api-conventions.md).

## Le compte et le droit

Deux tables globales, hors édition : une personne sert toutes les éditions.

| Table | Ce qu'elle dit |
| --- | --- |
| `compte` | La personne : une adresse (unique, sans égard à la casse), un nom facultatif, et `oidc_sujet` quand un fournisseur l'a attestée |
| `habilitation` | Un droit : un rôle, une édition (`NULL` pour toutes) et une expiration facultative |

Cinq règles tiennent le modèle :

- Rien ne se supprime. Un compte se désactive (`desactive_le`), un droit se retire (`retiree_le`) : l'historique peut ainsi dire qui détenait quel droit à quelle date. Supprimer une édition retire, au nom de `systeme`, les droits qui lui étaient propres.
- Le rôle est du code, le périmètre est de la donnée. `RoleHabilitation` est une énumération, sans matrice de permissions configurable : chaque rôle a ses propres tests, et une matrice ouvrirait des combinaisons que personne ne teste. Un nouveau rôle arrive avec une migration qui élargit le `CHECK` de `habilitation.role` et avec les routes qu'il ouvre.
- Il reste toujours un administrateur actif. Retirer le dernier droit `ADMIN` en vigueur ou désactiver le dernier compte qui le porte répond `409`, atomiquement. On ne désactive pas non plus son propre compte.
- Un droit ne s'accorde pas deux fois tant que le premier est en vigueur.
- L'historique nomme l'acteur par l'identifiant du compte, jamais par son adresse ni son nom ; l'écran joint le nom à la lecture, comme pour un animateur. `habilitation.cree_par` suit la même règle.

Aucune colonne ne porte de secret dans ces deux tables : la connexion ajoute les siennes par ses propres migrations.

## Du jeton au rôle : le fournisseur dit qui, l'application dit quoi

Une session s'ouvre par un fournisseur OIDC, par un compte local (adresse et mot de passe, avec un code reçu par courriel par défaut) ou par le compte `admin` de la configuration. Quelle que soit la porte, le rôle ne vient jamais du jeton : `CompteIdentityAugmentor` relit le compte en base à chaque requête authentifiée et lui donne exactement les rôles de ses droits en vigueur, en minuscules (`admin`, `organisateur`, `responsable_stand`, `rh`).

| Session | Ce qui se passe |
| --- | --- |
| Fournisseur OIDC | Rattachée au compte par le couple émetteur et sujet. Un compte inconnu ou désactivé n'a aucun rôle, quoi que dise le jeton ; personne n'est créé en se connectant |
| Compte local | Le compte est relu à chaque requête : désactiver coupe la session au coup suivant, sans attendre l'expiration du cookie |
| Formulaire du compte `admin`, porte de secours fermée | La session perd son rôle : un cookie émis avant la fermeture ne survit pas à l'incident |
| Clé d'API du MCP | Passe telle quelle |

Trois conséquences à connaître :

- Rattachement. Un compte sans sujet est réclamé par la première session dont l'adresse correspond et que le fournisseur déclare vérifiée (`email_verified`). Ensuite il appartient à ce sujet : la même adresse reprise par une autre personne chez le fournisseur n'ouvre rien, et un changement d'adresse n'enferme pas son titulaire. `OIDC_REQUIRE_EMAIL_VERIFIED=false` est l'exception des annuaires dont les adresses sont celles de l'organisation. ([ADR 0093](repo:docs/decisions/0093-connexion-par-un-fournisseur-oidc-et-secours-ferme.md))
- Le compte `admin` de la configuration n'est pas une ligne. C'est un compte implicite que l'historique nomme tant qu'aucun compte n'est lié à la session. Derrière un fournisseur, son formulaire est fermé par défaut (`409` sur `/j_security_check`), et `ADMIN_LOCAL_SECOURS=true` le rouvre. Les comptes locaux, eux, restent ouverts : ce qui est dangereux, c'est un mot de passe partagé et anonyme, pas un mot de passe qui appartient à une personne. Saisir `admin` sur le chemin des comptes locaux ouvre une session sans aucun rôle. ([ADR 0094](repo:docs/decisions/0094-comptes-locaux-par-mot-de-passe.md))

Le prix de cette relecture à chaque requête (deux lectures brèves) est une désactivation qui prend effet tout de suite, sans cache.

## Ce que chaque rôle ouvre

| Rôle | Ce qu'il ouvre |
| --- | --- |
| `admin` | Toute l'instance : comptes (`/api/comptes`), webhooks, clé MCP, sauvegardes, export et import de toute la base, écran Débogage, machine de calcul, journal des connexions, horloge simulée, création, renommage, duplication, activation et suppression d'une édition |
| `organisateur` | Tout ce qu'une édition porte, dans les éditions que ses droits en vigueur nomment (un droit « toutes éditions » les couvre toutes), et rien de ce qui est de l'instance (`403`) |
| `responsable_stand` | `/api/responsable/*` et rien d'autre |
| `rh` | `/api/rh/*` et rien d'autre ; s'ajoute aux autres droits du compte |

Une édition hors des droits d'un organisateur est refusée comme une édition inexistante (`EDITION_INCONNUE`, `400`) et absente de la liste des éditions. L'import de scénario lui reste ouvert, dans son édition courante seulement : une section `edition:` qui en désigne une autre, ou une à créer, est refusée.

Côté configuration, ce sont quatre politiques HTTP (`role-admin`, `role-gestion` pour `admin` et `organisateur`, `role-responsable`, `role-rh` pour `rh` et `admin`) dont celles du responsable de stand et du RH sont doublées par `@RolesAllowed` sur chaque méthode. Le MCP accepte aussi un jeton OAuth du fournisseur, sous les mêmes droits que le compte ([connexion MCP](mcp-connexion.md)).

### Une route ne s'ouvre pas par oubli

Dans une ressource ouverte à l'organisateur, une nouvelle méthode lui est ouverte aussi tant que personne ne la marque `@InstanceOnly`. Deux tests ferment cet angle mort.

`InstanceRoutesStructuralTest` classe des ressources entières : à l'organisateur (par édition), au public, aux administrateurs (`@InstanceOnly`) ou au responsable de stand. `RoutesOuvertesStructuralTest` descend à la route et tient deux inventaires versionnés dans `src/test/resources/`, une route par ligne (`Classe#méthode VERBE /chemin`) :

| Fichier | Ce qu'il liste |
| --- | --- |
| `routes-organisateur.txt` | Chaque route d'une ressource « par édition » qui n'est pas `@InstanceOnly` |
| `routes-responsable.txt` | Les routes de `ResponsableResource` |

Une route absente des deux fait échouer le test, qui imprime la ligne à ajouter ; une ligne qui ne désigne plus aucune route échoue aussi. Ajouter la ligne dit « ouverte à l'organisateur, exprès » ; sinon on annote la méthode `@InstanceOnly` et on l'ajoute à `INSTANCE_ROUTES` du premier test. Une sous-ressource ou une route héritée que l'inventaire ne verrait pas fait aussi échouer le test.

## Le responsable de stand : un droit de lecture

Un droit `RESPONSABLE_STAND` s'accorde pour une édition et un ou plusieurs de ses stands, avec une expiration facultative. Le compte se connecte comme un organisateur ; l'espace animateur reste celui des animateurs, car le jeton d'une fiche n'est pas un droit sur un stand, ne s'expire pas et ne se retire pas par édition ([ADR 0095](repo:docs/decisions/0095-le-responsable-de-stand-lit-son-stand.md)).

Le rôle n'ouvre que `/api/responsable/*`. Le reste de `/api/*` répond `403`, comme à tout compte sans droit d'organisation.

- Lecture seule. Les deux routes sont des `GET` (`ResponsableProjectionStructurelleTest` refuse toute autre méthode) et répondent `Cache-Control: no-store`.
- Édition active, plan publié. La requête ne nomme ni édition ni stand : `ResponsableService`, la seule porte vers les places, lit l'édition active et les stands des droits en vigueur. Un droit expiré ou retiré, un compte désactivé, une édition inactive donnent la même vue vide, qui ne distingue pas « pas à vous » de « n'existe pas ».
- Une projection, jamais le domaine. `ResponsableView` porte les horaires, les places pourvues et vides, et un nom seulement si `ReglagesResponsables` le dit à cet instant : défaut de l'édition, exception par stand dans les deux sens, chacune avec une date d'ouverture facultative. Jamais d'adresse, de téléphone, d'âge ni de statut mineur ; le test lit les types du record. L'affichette PDF est construite à partir de la même vue, jamais d'une seconde lecture.
- Les noms se règlent côté administrateur (`@InstanceOnly`) ; la requête de réglage ne supprime que les exceptions qu'elle nomme. Une duplication d'édition recopie ces réglages fermés (horaires seuls).
- Pas de clé étrangère vers `stand`. Le périmètre d'un droit vit dans `habilitation_stand`, sans clé vers `stand` (comme `habilitation.edition_id` n'en a pas vers `edition`) : l'import d'un dump supprime puis recrée les stands, et une cascade effacerait les droits de l'instance qui reçoit. Le vidage d'une édition les retire donc explicitement.

`ResponsableResourceTest` balaie toutes les routes `GET` sans paramètre de chemin du contrat publié avec le jeton d'un responsable : aucune ne répond `403` à tort, et aucun corps ne contient le nom d'un animateur d'un autre stand, le nom d'un autre stand ni, en mode « horaires seuls », aucun nom du tout.

## Le droit RH : le rapport de conformité

Un droit `RH` s'accorde pour une seule édition (la base refuse un droit RH sans édition) et ouvre le rapport de conformité et les contacts d'urgence ([ADR 0096](repo:docs/decisions/0096-le-droit-rh-lit-le-rapport-de-conformite.md)). Il s'ajoute aux autres droits : un organisateur sans ce droit ne lit rien du rapport, et un RH qui est aussi organisateur garde la portée d'organisateur.

- L'édition est dans le chemin, jamais dans `X-Edition-Id` : un compte qui n'est que RH n'est organisateur d'aucune édition, et l'en-tête lui serait refusé. `RhService` décide du droit édition par édition ; une édition sans droit et une édition inconnue répondent le même `404`.
- Le rapport lit le plan publié (`409` sans publication) et applique les plafonds légaux tels que les règles les lisent, que l'organisateur ait éteint la règle ou non. Ni date de naissance ni adresse : un nom et le statut de mineur.
- Consultations et exports sont journalisés par `RhService`, dans l'édition du rapport, et non par le filtre de réponse, qui classe sous l'édition de l'en-tête que ces routes n'envoient pas ; les deux routes de fichier sont pour cela dans la liste argumentée de `CatalogueActions`.
- Jamais par MCP : le rapport est nominatif.
- Les contacts vivent dans `animateur_contact`, pas dans `Animateur` : cette classe est un fait du problème du solveur, sérialisé par le scénario, les exports, le MCP et l'espace des collègues. Une donnée d'un tiers qui ne doit sortir par aucun d'eux ne se range pas dans le type qu'ils lisent tous. L'animateur lit et écrit les siens dans son espace, jamais ceux d'un autre ; une écriture concurrente répond `409 MODIFICATION_CONCURRENTE` ; une duplication d'édition ne les recopie pas. `ContactsEspaceTest` cherche le numéro saisi dans l'espace d'un collègue, les fiches et le scénario exporté, et ne le trouve nulle part.
