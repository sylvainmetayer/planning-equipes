# 0047 — Différer le message d'une personne sans la perdre de vue

- **Statut** : accepté, implémenté
- **Date** : septembre 2026
- **Portée** : persistance, service, API, frontend
- **Voisines** : [0011](0011-publier-plutot-qu-envoyer-a-tous.md) (la publication
  par personne), [0007](0007-instantanes-contenu-denormalise.md) (le support du
  plan publié)

## Contexte

La 0011 a fait de la publication un décompte par personne : on écrit à ceux dont
l'emploi du temps a bougé, et à personne d'autre. Elle laissait cependant
l'organisateur sans prise sur une situation banale : « on ne prévient pas Untel
ce soir, je l'appelle d'abord ». Le bouton était tout ou rien.

Le réflexe — ne pas publier tant qu'on n'a pas appelé — coûte cher : il fige
aussi les cent quarante-neuf autres, dont l'espace continue d'afficher un
planning périmé.

La difficulté n'est pas l'interface. Elle est que « ce qu'on a annoncé » était
**une seule référence pour tout le monde** : le dernier instantané publié. Sauter
une personne à l'envoi, sans rien changer d'autre, aurait fait disparaître son
écart à la publication suivante — comparé à un instantané qu'elle n'a jamais
reçu, son planning n'a plus bougé. La personne qu'on voulait justement rappeler
était celle que l'application cessait de nommer.

## Décision

**La référence de comparaison devient une propriété de la personne.** Chaque
animateur porte l'instantané dont le contenu lui a réellement été annoncé
(`animateur.plan_notifie_id`, V95). L'aperçu compare le planning de travail à ce
repère-là, personne par personne.

Il en découle quatre choses.

**La capture, elle, reste commune.** Publier en excluant quelqu'un capture le
plan pour tout le monde : l'espace suit le plan publié (0011), et laisser un
espace en arrière demanderait un second plan publié — exactement ce que la 0011
a supprimé. Ce qui reste en arrière est le *repère* de la personne, pas le plan.

**Le repère avance pour les destinataires, et pour eux seuls.** Quelqu'un à qui
la publication n'avait rien à dire n'a rien été dit : lui attribuer ce plan
transformerait son prochain message de « voici votre planning » en « votre
planning a changé ». Un envoi en échec compte en revanche comme annoncé, comme
le veut la 0011 — le décompte mesure ce qui a changé, pas ce qui a été délivré.

**L'écart se cumule.** Une personne différée deux fois lit, quand son message
part enfin, la différence avec ce qu'on lui a envoyé la dernière fois — pas avec
un plan qui a bougé deux fois dans son dos.

**Ne prévenir personne est refusé.** Exclure tous les destinataires
publierait un plan dont l'application saurait que personne ne l'a appris. C'est
le seul cas où l'exclusion est un refus (`409`) plutôt qu'une décision.

## Ce que la trace et l'historique en gardent

Une publication qui diffère quelqu'un l'écrit deux fois, et les deux ont leur
raison d'être :

| Où | Quoi | Pourquoi là |
| --- | --- | --- |
| `publication_destinataire`, statut `EXCLU` | la liste des exclus **de cette publication** | c'est la trace d'une publication, elle est lue par publication |
| `journal_action`, `PUBLICATION_DIFFEREE` | un acte par personne, désignée par id | c'est un acte d'administration, il se lit dans le fil de l'historique |

La ligne `EXCLU` ne repart jamais dans l'espace de l'intéressé : elle documente
un message qui n'a pas été envoyé, et le rejouer montrerait comme « dernier
message reçu » un message qui n'existe pas.

## Le changement mineur, et pourquoi c'est un filtre et non une règle

Le ticket demandait aussi de distinguer « ces trois-là ne bougent que de dix
minutes ». Un changement est dit **mineur** quand chacune de ses lignes est la
même vacation, sur le même stand, glissée d'un quart d'heure au plus — et
qu'aucune décision d'échange n'attend d'être annoncée.

Le seuil est écrit dans le code (`PublicationDiffService.DECALAGE_MINEUR`), pas
configurable : ce n'est pas un réglage mais la définition d'un quart d'heure,
la granularité sur laquelle un bénévole cale son arrivée.

Et c'est un **filtre de vue**, jamais une exclusion automatique. Décider de ne
pas prévenir quelqu'un est un acte ; le faire à sa place sur un critère
numérique reproduirait exactement le mode de défaillance que la 0011 a écarté —
une application qui se tait toute seule.

## Conséquences

- La reprise de la V95 lit `publication_destinataire` : sans elle, la première
  publication après la migration écrirait aux cent cinquante personnes comme si
  aucune ne savait rien.
- Un repère qui désigne un instantané disparu — supprimé, purgé — se lit comme
  « jamais prévenu » : c'est la seule lecture honnête quand il ne reste rien à
  comparer.
- L'aperçu charge un instantané par repère distinct. Une publication ordinaire
  laisse tout le monde sur le même : c'est une lecture, et cela ne grossit que
  du nombre de personnes réellement reportées.
- `POST /api/planning/publication` demande désormais un corps JSON, là où il
  acceptait n'importe quoi : la liste des exclus est de longueur libre et un id
  d'animateur est fourni par le client, donc ni un séparateur ni une URL n'est
  un endroit sûr pour la porter.
