# 0076 — Un journal des connexions administrateur, propre à l'instance

- **Statut** : accepté, implémenté
- **Date** : octobre 2026
- **Portée** : sécurité du form login, historique, données personnelles
- **S'appuie sur** : [0001](0001-cloisonnement-par-edition.md) (cloisonnement
  par édition), [0072](0072-une-seule-edition-active.md) (aucune requête sans
  édition désignée)

## Contexte

Le verrou du form login (`AdminLoginLimiter`) compte les échecs par adresse et
bloque après `CONNEXION_MAX_ECHECS`, mais ne laissait aucune trace consultable :
ni qui s'est connecté et quand, ni la série d'échecs qui précède un
verrouillage. L'historique des actions (`journal_action`) ne pouvait pas la
porter tel quel :

- `/j_security_check` est traité par le mécanisme de form login de Quarkus,
  **avant JAX-RS** : `JournalActionFilter`, qui écrit une ligne par route, ne le
  voit jamais ;
- `journal_action` est **cloisonnée par édition**, et une tentative de
  connexion n'a lieu dans aucune : le formulaire ne porte pas d'`X-Edition-Id`,
  et une session ouverte ouvre toutes les éditions à la fois. La ranger dans
  l'édition active la ferait disparaître de l'écran dès qu'on en choisit une
  autre, et avec l'édition supprimée ; il n'existe d'ailleurs parfois aucune
  édition active (0072).

## Décision

**Une table de l'instance, `journal_connexion`, sans `edition_id`**, écrite par
`AdminLoginLimiter` — qui voit déjà l'issue de chaque tentative — et lue par
l'onglet « Connexions » de la page *Historique*, le même quelle que soit
l'édition choisie.

- **Trois événements** : `CONNEXION` (réussie), `ECHEC`, et `VERROUILLAGE`,
  écrit **une fois**, sur l'échec qui atteint le plafond. Une requête refusée en
  `429` pendant le verrou n'écrit rien : elle n'a essayé aucun identifiant, et
  une ligne par refus rendrait la croissance de la table proportionnelle au
  débit d'un attaquant.
- **Une ligne porte l'horodatage et l'adresse**, celle que le verrou compte
  (`ClientAddress`). **Jamais le mot de passe ni l'identifiant saisis** : un
  identifiant faux est souvent un mot de passe tapé dans le mauvais champ.
- L'écriture quitte la boucle d'événements de Vert.x (où tournent l'événement
  d'échec et le gestionnaire de fin de requête) pour un travailleur, et son échec
  ne coûte que la ligne, comme pour l'historique.
- **Une trace bornée** : le verrou est par adresse, et un attaquant qui en fait
  tourner beaucoup n'est jamais bloqué longtemps. Au-delà de 1 000 tentatives
  échouées par heure pour l'instance, ou de 64 écritures en attente, les lignes
  sont comptées et non écrites, avec un seul avertissement par fenêtre : le
  verrou, lui, voit toujours chaque échec.
- **Rétention** : celle de l'historique (`JOURNAL_RETENTION`), purgée la même
  nuit. Une adresse IP est une donnée personnelle, et la question posée —
  « quelqu'un a-t-il forcé le mot de passe cette saison ? » — a la même portée.
- **Hors du dump SQL** (`DELIBERATELY_NOT_DUMPED`), pour les deux raisons qui y
  tiennent déjà d'autres tables : comme `journal_action`, c'est une piste
  d'audit qu'un import effacerait ; comme les webhooks, c'est l'état de cette
  machine — les connexions d'une autre instance n'ont pas eu lieu ici.

## Alternatives écartées

- **Trois entrées de `CatalogueActions` dans `journal_action`**, comme le
  proposait la demande : il aurait fallu choisir une édition pour une tentative
  qui n'en a pas, et la trace aurait suivi le sort de cette édition. La
  couverture de `JournalCoverageStructurelleTest` n'en souffre pas :
  `/j_security_check` n'est pas une route JAX-RS, et le catalogue renvoie vers
  ce journal.
- **Une ligne par tentative refusée en `429`** : voir plus haut, c'est offrir la
  table au premier qui martèle le formulaire.

## Conséquences

- `IsolationEditionStructurelleTest` range `journal_connexion` parmi les tables
  hors édition, avec son motif.
- Le jour où l'authentification passera par un fournisseur d'identité externe,
  le verrouillage relèvera de lui ; seule la connexion applicative resterait à
  écrire ici.
