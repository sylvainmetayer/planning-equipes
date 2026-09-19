# 0048 — Des sièges optionnels jusqu'à l'effectif maximum

- **Statut** : accepté, implémenté
- **Date** : septembre 2026
- **Portée** : domaine, solveur, persistance, analyses, IHM
- **Voisine** : [0044](0044-le-passe-est-fige.md) — l'autre siège que les
  règles comptent sans jamais le reprocher

## Contexte

`effectifMax` était saisi sur chaque stand, validé (`min ≤ max`), persisté — et
lu par personne. La génération des sièges part de l'effectif que la **fenêtre**
déclare, jamais du maximum, et pour une bonne raison, écrite de longue date
dans `ProblemBuilder.buildPostes` : employer `effectifMax` produisait 2 736
sièges **obligatoires** là où le scénario en demande 2 088, soit 31 % de besoin
inventé, et c'était la vraie cause des résolutions qui n'atteignaient jamais la
faisabilité.

Restait une incohérence : la fiche du stand annonçait une capacité que le
solveur n'utiliserait jamais, et l'écran Ouvertures ne la montrait même pas.

## Décision

**Le maximum génère des sièges, mais des sièges optionnels.** Entre l'effectif
de la fenêtre et l'`effectifMax` du stand, la génération ouvre des *renforts* :
des sièges que `posteDoitEtrePourvu` ignore et qu'une règle SOFT,
`pourvoirLesSiegesOptionnels`, récompense quand quelqu'un les prend.

Une seule chose les distingue d'un siège ordinaire : **les laisser vides n'est
jamais une violation, ni un écart**. Pour tout le reste ils sont des sièges —
qui s'y assied travaille vraiment, donc les règles légales, l'équilibre de
charge et le décompte d'heures les comptent.

C'est ce qui sépare une **capacité** d'un **besoin**, et c'est toute la
différence avec la version rejetée plus haut.

### Une récompense, jamais une pénalité

Pénaliser un renfort vide ramènerait exactement le besoin inventé décrit
plus haut : l'organisateur verrait un manque là où il a déclaré une marge. La règle
récompense donc, et en SOFT — sous tout le medium — pour qu'un renfort ne
concurrence jamais un siège dû : le solveur en prend un quand il n'a rien de
mieux à faire de la personne.

## La mesure, et ce qu'elle a coûté

Le ticket demandait la mesure, et elle a servi : la première version **cassait**
le festival hivernal. Chaque correctif a retiré une façon dont les renforts
entraient dans la recherche de faisabilité.

| État | `festival-hivernal` (3 438 sièges dus, 846 renforts) |
| --- | --- |
| renforts partout dans la sélection | 900 s (plafond), **-9 dur**, 4 262 pas |
| renforts en dernier dans l'heuristique de construction | 900 s (plafond), **-9 dur**, 4 262 pas |
| phase 1 restreinte aux sièges dus (sélecteurs) | 900 s (plafond), **-2 dur**, 4 045 pas |
| + chaîne de relogement et ruine-reconstruit aveugles aux renforts | **117 s, 0 dur**, 404 pas |

L'heuristique de construction n'a jamais été en cause : elle finit sur le même
score (-82 dur) dans les quatre cas et ne pourvoit presque aucun renfort — ses
scores medium et soft bougent de moins d'un millième. **C'était la phase 1 de la
recherche locale**, dont le seul travail est d'atteindre la faisabilité : un
siège que personne ne doit n'a rien à y faire, et chaque mouvement passé sur
l'un d'eux est un mouvement qui ne ferme pas un trou. La chaîne de relogement
était la pire : elle jouait une semaine entière de déplacements pour remplir un
renfort, la façon la plus chère qui soit de gagner un point soft.

Résultat final, sur les deux fixtures réelles du dépôt :

| Scénario | sièges | dont renforts | temps | score à la faisabilité |
| --- | --- | --- | --- | --- |
| `festival-realiste-canicule` avant | 1 994 | — | 13,9 s | 0 / -5 174 / -9 939 |
| `festival-realiste-canicule` après | 3 084 | 1 090 | 13,5 s | 0 / -5 174 / -9 939 |
| `festival-hivernal` avant | 3 438 | — | 89,4 s | 0 / -7 904 / -19 140 |
| `festival-hivernal` après | 4 284 | 846 | 116,9 s | 0 / -7 833 / -18 958 |

+55 % et +25 % de sièges pour +31 % de temps sur l'hivernal et rien sur la
canicule, qui atteint la faisabilité dans l'heuristique seule. Le plafond de
sécurité des tests reste 900 s, soit près de huit fois la mesure.

## Conséquences

- **La phase 1 ne voit pas les renforts**, et c'est une propriété du
  `solverConfig.xml`, pas un détail d'implémentation : `SiegeObligatoireFilter`
  sur ses sélecteurs, `HoleNeighbourPosteFilter` et
  `WeekRelocationMoveIteratorFactory` qui les écartent. La phase 2 ne porte
  aucun de ces filtres — c'est là qu'un renfort se pourvoit, une fois le plan
  faisable.
- **Un renfort vide n'est un manque pour personne** : le diagnostic, la
  fragilité, le besoin en animateurs, le prérequis de validation d'une journée,
  la liste du jour J et le PDF global l'excluent tous. Le PDF écrit « 2/3 +1 » :
  ce qui manque et ce qui est un bonus, sur la même ligne, sans que le second
  fasse lire le premier de travers.
- **Les renforts sont générés après les sièges dus** de la même clé
  (stand, créneau). Le réamorçage d'un plan enregistré rend les places dans
  l'ordre, et c'est cet ordre qui fait retomber les gens sur ce qui est dû
  avant ce qui est un bonus.
- **La volumétrie les compte à côté, jamais dedans.** La carte annonce des
  « postes à pourvoir » et son taux de remplissage alerte dès qu'il approche
  de 1 : y verser les renforts gonflerait le besoin d'un quart à une moitié et
  crierait au loup sur une marge déclarée exprès. `posteCount` et les heures
  sont donc les sièges dus, `posteOptionnelCount` est ce qui les surplombe, et
  l'espace de recherche — le seul chiffre qui soit vraiment le nombre
  d'entités de Timefold — rajoute les deux.
- **Un verrouillage fige aussi le renfort vide qu'il couvre.** Un siège vide
  n'est jamais épinglé — épingler un trou le rendrait impossible à combler —
  mais la raison tombe pour un siège que personne ne doit : sans cela, une
  journée verrouillée gagnerait le renfort que la résolution suivante décide
  d'y poser, c'est-à-dire exactement le changement que le verrou interdit. Un
  verrou par animateur ne couvre aucun de ces sièges, puisqu'il désigne celui
  qui l'occupe.
- **Un écran dit où sont les heures bonus** (`/renforts`). Les autres vues
  lisent le renfort comme un bonus et s'arrêtent là — une icône au calendrier,
  « + 1 optionnel » aux ouvertures, « 2/3 +1 » au PDF ; aucune ne les
  additionne, et c'est l'addition qui répond à « le budget se réduit, où
  couper ». Une ligne par stand, une colonne par jour, deux chiffres par
  case : les heures **ouvertes**, lues sur les sièges qu'une résolution
  construirait maintenant, et les heures **pourvues**, lues sur le plan
  enregistré. Deux sources, assumées : l'écart entre elles dit si une coupe
  est gratuite ou si quelqu'un la sentira. L'écran est en lecture seule, le
  levier restant l'`effectifMax` de la fiche du stand, avec sa garde
  « min ≤ max » et son horodatage de concurrence.
- **Le drapeau est persisté et sérialisé**, contrairement à `passe` : les écrans
  lisent le plan enregistré, et un renfort vide qui s'y relirait comme un trou
  serait précisément la fausse alerte que cette décision supprime.
- **Le format de scénario le porte** (`postes[].optionnel`, écrit seulement
  quand il est vrai). Un fichier qui perdrait le drapeau transformerait chaque
  renfort en siège dû à la relecture — le besoin inventé, encore.
- Un instantané capturé avant cette décision ne porte pas le drapeau et se relit
  comme entièrement dû : ce qui a été capturé alors l'était.
