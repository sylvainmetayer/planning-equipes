# 0072 — Une seule édition active : elle seule parle à l'extérieur

- **Statut** : accepté, implémenté — révise [0001](0001-cloisonnement-par-edition.md) §4.1 et §5, et [0009](0009-edition-unique-porteur-de-variantes.md)
- **Date** : octobre 2026
- **Portée** : persistance, service, API, MCP, frontend

## Contexte

Deux notions coexistaient sur l'édition, et aucune ne disait ce qu'il fallait :

- l'**édition par défaut** (`edition.defaut`, unique par index partiel,
  0001 §4.1) répondait à toute requête qui ne désignait pas d'édition. Un
  onglet resté ouvert sur une édition supprimée, un outil MCP appelé sans son
  argument `edition`, retombaient dessus sans un mot ;
- l'**armement** des envois de nuit (`parametres_notifications.actives`) se
  réglait édition par édition. Rien n'empêchait d'en armer deux. Deux éditions
  armées portant des créneaux à la même date envoyaient alors deux rappels de
  la veille, avec deux plannings différents, aux mêmes personnes, la même
  nuit. Aucune ne voyait l'autre : un envoi est revendiqué par
  `(edition_id, type, cle)`.

Le second défaut tenait au modèle de 0009 : un plan de repli était une édition
dupliquée, vivante à côté de l'édition nominale. Depuis 0043, un plan de repli
est une consigne datée dans l'édition vivante. Deux éditions n'ont donc plus de
raison de parler en même temps aux mêmes gens.

## Décision

### Une seule notion : l'édition active

« Par défaut » et « active » fusionnent. La colonne `defaut` devient `active`
(`V118`), et l'index unique partiel garantit **au plus une** édition active.
**Aucune** est un état valide : entre deux événements, plus rien ne part.

Toute édition **naît inactive** : création, duplication, import. Une seule
exception, l'édition `E1` qu'une base neuve sème : elle est active, faute de
quoi une installation neuve aurait l'espace animateur fermé avant le premier
geste.

### Ce que l'activation ouvre

Seule l'édition active émet vers l'extérieur :

| Surface | Hors édition active |
|---|---|
| Envois de nuit (rappel de la veille, relance des non-confirmés, alerte sur les échanges) | le planificateur ne sert que l'édition active |
| Publication, envoi d'un planning, relance manuelle, invitation à déclarer, code d'accès | `409 EDITION_INACTIVE` (`@RequiresActiveEdition`) |
| Notifications au fil de l'eau (échanges, covoiturage, absences, fin de résolution) | abandonnées avant rédaction (`NotificationDispatcher`) |
| Espace animateur, flux ICS, affichage mural | même réponse qu'un jeton inconnu |

Une édition inactive reste **entièrement utilisable en interne** : référentiel,
résolution, verrous, simulations, instantanés. On prépare N+1 pendant que N vit.

Les messages de l'**instance** ne dépendent d'aucune édition : l'alerte de
sauvegarde de nuit et le courriel de test de Débogage partent toujours. Le mail
de fin de résolution, lui, appartient à son édition : une résolution terminée
sur une édition inactive ne prévient personne.

### Un jeton d'édition inactive ne se distingue pas d'un jeton inconnu

Même statut (`404`), même corps (« lien inconnu, expiré, ou édition
terminée »). Répondre « édition terminée » à un jeton valide et `404` à un jeton
inventé aurait fait de la réponse un oracle sur la validité d'un jeton.

### Plus de repli : une requête désigne son édition

Une requête d'un client qui ne nomme pas d'édition est refusée
(`400 EDITION_REQUISE`), tout comme une requête qui en nomme une qui n'existe pas
(`400 EDITION_INCONNUE`). Côté MCP, un outil `@EditionCiblee` appelé sans son
argument `edition` est refusé de la même façon. Le choix se fait côté client :
le navigateur qui n'en a jamais fait prend l'édition active, ou la plus récente
entre deux événements, avant tout écran (`editionChosenGuard`). Sur
`EDITION_INCONNUE`, il oublie son choix et recommence.

Un contexte de requête qu'aucun client n'a ouvert — celui que le transport MCP
active autour d'un outil non ciblé, celui que le harnais de test active autour
d'une méthode — ne désigne rien et ne peut rien désigner. Il se résout sur
l'édition active, et il est refusé quand il n'y en a pas. Hors requête,
`EditionContext` lève toujours, comme avant.

### La bascule

« Activer cette édition » est un geste explicite et atomique : une seule
transaction désactive l'ancienne et active la nouvelle. Il est refusé (`409`)
tant qu'une résolution tourne sur l'**une des deux** éditions concernées. Il est
précédé d'un aperçu de ce qu'il ferme : liens d'espace et de calendrier,
affichages muraux, demandes d'échange ouvertes, résolutions en file. Le geste
est journalisé (`EDITION_ACTIVEE`, `EDITION_DESACTIVEE`).

Un bandeau de l'administration signale ce qui demande une décision : le dernier
jour de l'édition active est passé, une édition inactive commence dans les
`planning.editions.rappel-jours` prochains jours (7 par défaut), ou une édition
inactive a déjà commencé — le cas le plus urgent, puisque son espace est fermé. Les dates se
lisent dans les créneaux, comme partout : une édition sans créneau ne déclenche
rien. Le bandeau n'envoie aucun courriel.

### La migration

`V118` choisit l'édition active pour ne réveiller personne qu'on n'avait pas
armé, et ne faire taire personne qui l'était :

1. si exactement une édition est armée, c'est elle ;
2. sinon, l'édition par défaut ;
3. sinon, la plus ancienne, comme le repli d'avant.

Son choix est annoncé par un `NOTICE`. Les lignes d'historique
`EDITION_PAR_DEFAUT` deviennent `EDITION_ACTIVEE`.

## Options écartées

- **Garder le repli sur l'édition active** quand la requête ne désigne rien.
  Le rayon d'impact aurait été bien plus petit (une centaine de classes de
  test envoient désormais l'en-tête explicitement). Mais le repli, c'est
  exactement le défaut qu'on retire : une écriture qui atterrit dans une
  édition que personne n'a nommée.
- **Garder l'armement à côté de l'activation.** Deux interrupteurs pour une
  même question laissaient revenir le cas des deux éditions qui écrivent aux
  mêmes gens.
- **Prévenir par courriel** que l'édition active est terminée. Un rappel qui
  part d'une édition dont on demande justement si elle doit encore parler, ce
  serait le défaut qu'on corrige.

## Conséquences

- Les liens déjà imprimés d'une édition désactivée cessent de répondre. Les
  jetons ne sont de toute façon pas recopiés à la duplication : un lien n'a
  jamais désigné qu'une édition.
- Le prompt MCP de variante par édition dupliquée est retiré : un plan de
  repli se prépare avec `simuler_consigne` et `appliquer_consigne`.
- `definir_edition_par_defaut` devient `activer_edition`, et
  `desactiver_edition` s'y ajoute. `edition_courante` nomme l'édition active.
- `ActiveEditionEmissionStructuralTest` refuse tout courriel de
  `MailService` et tout outil MCP sortant qui ne serait ni gardé ni argumenté.
