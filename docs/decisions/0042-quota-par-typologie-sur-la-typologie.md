# 0042 — Le quota par typologie se pose sur la typologie, pas sur une contrainte ad hoc

- **Statut** : accepté, implémenté
- **Date** : septembre 2026
- **Portée** : référentiel (typologie), solveur (contrainte dure)

## Contexte

L'organisateur veut pouvoir dire, en contrainte **dure** : sur cette typologie,
un animateur ne tient pas plus de N créneaux sur l'ensemble de l'édition. Le cas
qui motive la demande est celui des **hommes jeu**, 4 créneaux au maximum
(issue #594).

Rien ne permettait de l'exprimer :

- `limiterTypologiesDistinctesParAnimateur` borne le **nombre de typologies
  différentes** d'une personne, pas son volume sur l'une d'elles, et c'est un
  medium ;
- `TypeContrainteAdHoc` ne connaît que `INDISPONIBILITE_FORCEE`,
  `INCOMPATIBILITE`, `AFFECTATION_FORCEE` et `AFFINITE` — aucune notion de
  quota ;
- `equilibrerCharge` répartit la charge globale sans jamais regarder la
  typologie.

Le contournement — poser des indisponibilités forcées à la main — ne tient pas :
le plafond porte sur l'édition entière, donc sur une combinaison que
l'organisateur ne peut pas énumérer à l'avance.

## Options envisagées

**A. Un champ sur la typologie** (`maxCreneauxParAnimateur`, nullable). Simple,
visible sur la fiche typologie, mais impose la même valeur à tout le monde.

**B. Un cinquième type de contrainte ad hoc** (`QUOTA_TYPOLOGIE`, portée
animateur + typologie + édition). Plus général, réutilise l'écran des
contraintes ad hoc et sa traçabilité, mais demande une portée nouvelle dans un
modèle qui n'en a pas : les quatre types existants portent sur un couple
(animateurs, stand/créneau), jamais sur une typologie, et aucun ne porte de
nombre.

## Décision

**A maintenant, B quand un second cas arrivera.**

`TypologieItem` gagne `maxCreneauxParAnimateur`, nullable — l'absence de valeur
signifie « pas de plafond », donc une édition qui n'y touche pas ne change pas
de comportement. La contrainte dure `plafondCreneauxParTypologie` la lit comme
fait de problème (`QuotaTypologie`), et seules les typologies qui portent un
plafond deviennent des faits : une édition qui ne plafonne rien ne paie rien.

Deux points tranchés avec elle :

1. **L'unité est le créneau**, pas l'heure : c'est la formulation de
   l'organisateur, et c'est aussi ce qu'il compte quand il regarde un planning.
2. **Un poste compte pour chaque typologie que son stand propose**, pas pour
   celles que son animateur maîtrise. On tient le jeu auquel on est assis, que
   sa fiche le mentionne ou non. C'est le choix inverse de
   `limiterTypologiesDistinctesParAnimateur`, qui lit l'intersection : cette
   règle-là parle de ce qu'une personne doit apprendre, celle-ci de ce qu'un jeu
   consomme.

## Ce qui déclenchera B

Une demande de quota **par personne** — « untel ne fait pas plus de deux
nocturnes » — que A ne sait pas exprimer. Le jour où elle arrive, le champ de la
typologie reste le défaut de l'édition et la contrainte ad hoc le dérogé, comme
partout ailleurs dans ce modèle. Un ADR remplacera celui-ci.

## Conséquences

- Le plafond porte sur l'**édition entière**, jamais sur la journée ni sur la
  semaine — même portée que `limiterTypologiesDistinctesParAnimateur`, et un
  test le verrouille des deux côtés.
- La contrainte est dure : un plan qui ne peut pas la respecter laisse un siège
  vide plutôt que de dépasser. Un plafond saisi trop bas rend donc l'édition
  infaisable, ce que le diagnostic dit en nommant qui, quelle typologie,
  combien de créneaux pour quel plafond.
- Le champ voyage dans les scénarios (`typologies:`), comme le reste du
  référentiel.
