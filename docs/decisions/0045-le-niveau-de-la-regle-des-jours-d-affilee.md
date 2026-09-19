# 0045 — Les six jours d'affilée restent une règle dosée, avec une forme dure éteinte par défaut

- **Statut** : accepté, implémenté
- **Date** : septembre 2026
- **Portée** : solveur (catalogue, contraintes de qualité), configuration des poids
- **Prolonge** : [0006](0006-obligation-legale-ou-politique-organisateur.md), [0041](0041-encadrement-des-mineurs-eteint-par-defaut.md)

## Contexte

L'organisation a arrêté, pour ses éditions, un cadre de temps de travail en
cinq règles. La cinquième — **pas plus de six jours travaillés consécutifs, et
au moins un jour libre par semaine civile** — était la seule dont le niveau
restait à trancher.

Ce que `main` tenait déjà : `maxJoursTravaillesParSemaine` (dure, L3132-1) et
`reposHebdomadaireMinimal` (dure, L3132-2, 35 h) garantissent le jour libre par
semaine civile. Le plafond de six jours **glissants**, lui, n'était que
`maxJoursConsecutifsTravailles`, medium, catégorie « Qualité d'organisation »,
dosable, seuil constant à six.

Le trou est réel : les deux règles dures laissent passer mardi→dimanche puis
lundi→samedi, soit **douze jours d'affilée à zéro écart dur**, chacune des deux
semaines civiles ayant bien son jour libre.

Trois faits ont cadré la décision :

1. **Aucune base légale pour un décompte glissant.** L3132-1 se lit sur la
   semaine civile (L3121-35), et la Cour de cassation juge que le repos
   hebdomadaire n'a pas à tomber au plus tard après six jours consécutifs
   (Cass. soc. 13 nov. 2025, n° 24-10.733 ; même lecture CJUE C-306/16). Au
   seul titre du Code, la règle des six jours est une **politique de
   l'organisateur**, au sens de [0006](0006-obligation-legale-ou-politique-organisateur.md).
2. **La convention collective aurait changé la réponse** : l'art. 5.2 de la CCN
   de l'Animation (ÉCLAT, IDCC 1518) donne deux jours de repos consécutifs à
   tout salarié, ce qui interdirait mécaniquement les douze jours et donnerait
   une base textuelle à une règle dure — sous la forme « deux jours de repos
   consécutifs par semaine civile pour tous », pas sous celle des six jours.
   L'organisation a confirmé qu'elle **n'en relève pas**.
3. **Un poids ne change jamais le niveau d'une règle** :
   `SolverConfiguration.constraintWeightOverrides` mappe sur le niveau déclaré
   au catalogue, et `ConstraintToggle` ne porte que `actif`. « Les six jours,
   mais bloquants » ne pouvait donc pas être un réglage ; il fallait une
   seconde contrainte.

## Options envisagées

**A. Laisser la règle medium seule, dosée.** Le niveau juridiquement correct.
Mais rien ne permet alors à une organisation d'exiger le plafond, et les douze
jours restent possibles à zéro dur si le score trouve mieux ailleurs.

**B. Passer la règle en dur.** Cohérent avec « pas plus de six jours »
entendu au pied de la lettre, mais faux : la règle serait présentée comme
légale à des organisations qu'aucun texte n'oblige, et la cérémonie de
désactivation des règles légales mentirait sur ce qu'elle protège.

**C. Deux contraintes : la medium par défaut, une forme dure éteinte.** Le
mécanisme existe depuis [0041](0041-encadrement-des-mineurs-eteint-par-defaut.md)
(`DESACTIVEES_PAR_DEFAUT`), il ne coûte aucune migration, et il laisse le choix
à l'édition plutôt qu'au dépôt.

## Décision

**(C).** `maxJoursConsecutifsTravailles` reste **MEDIUM**, active par défaut,
dosable. `maxJoursConsecutifsTravaillesDur` ajoute la même mesure en **HARD**,
**éteinte par défaut**, activable par l'écran Contraintes, `activer_contrainte`
ou `contraintes.activees` d'un scénario.

Cinq règles :

1. **Les deux partagent leur corps** (`sequencesTropLongues`) et leur seuil :
   elles ne peuvent pas diverger sur ce que « jours d'affilée » compte, ni sur
   combien elles en laissent passer. Ce seuil a d'abord été la constante
   `JOURS_CONSECUTIFS_MAX = 6` ; il est depuis réglable par édition
   (`ParametresQualite.joursConsecutifsMax`, six par défaut), les
   deux formes le lisant au même endroit. Le niveau par défaut tranché ici ne
   change pas : ce qui devient réglable, c'est le nombre de jours, pas le fait
   que la forme dure soit éteinte.
2. **Les deux restent en « Qualité d'organisation »**, jamais en « Légal » :
   aucun article du Code ne les fonde, et la catégorie décide de ce que dit la
   confirmation de désactivation.
3. **La dure ne se dose pas** — un écart dur n'a pas de prix — mais porte
   quand même sa ligne `planning.constraint-weights.…=1`, comme les autres.
4. **Les deux peuvent être actives ensemble** : la dure bloque au-delà de six,
   la medium continue de coûter en deçà. C'est une manière légitime de dire
   « jamais plus de six, et de préférence moins ».
5. **La remédiation de la dure renvoie vers la souple** : « désactivez
   `maxJoursConsecutifsTravaillesDur`, `maxJoursConsecutifsTravailles` continue
   de la pénaliser sans bloquer ». Celle de la souple ne cite plus un « plafond
   dans les paramètres légaux » qui n'a jamais existé.

**Le défaut retenu reste la forme medium, au poids 1**, sur la foi du banc
ci-dessous.

## Conséquences

- Une organisation qui veut le plafond bloquant l'allume par édition, sans
  livraison ni migration — et choisit le nombre de jours qui va avec, ce qui
  change tout sur une grille tendue : sur `festival-hivernal`, la forme dure
  laisse 22 sièges vides à six jours, dix à sept, et **atteint zéro écart dur
  en 294 s à huit**. Le seuil décide de la faisabilité là où le poids ne
  pouvait rien.
- Le jour où la convention collective s'appliquerait, la bonne forme dure ne
  serait pas celle-ci mais « deux jours de repos consécutifs par semaine
  civile pour tous » — l'extension de `reposHebdomadaireMineur`. Cette ADR ne
  la préempte pas.
- `ConstraintCatalogTest` vérifie que la dure n'est ni dosable ni protégée, et
  `QualiteConstraintsTest` qu'elle est muette sans son interrupteur.

## Annexe — banc de comparaison

Protocole : même fixture, même graine (celle de `solverConfig.xml`), même
budget, problème construit comme en production (horaires de stands résolus,
fenêtres repas et paramètres légaux du fichier). Trois configurations :

- **A** — medium au poids 100, forme dure éteinte ;
- **B** — forme dure activée, medium au poids 1 ;
- **C** — témoin : medium au poids 1, forme dure éteinte (le défaut livré).

Le harnais n'est pas versionné : il est reproductible depuis ce protocole, et
un test de 15 à 30 minutes que personne ne joue n'est pas un test. Les
mesures : score final, sièges non pourvus, plus longue série de jours
consécutifs, nombre d'animateurs au-delà de six, et la distribution complète
des séries.

### `festival-hivernal` — la grille de l'organisateur, 153 animateurs, 600 s

| Config | Score | Sièges vides | Plus longue série | Animateurs > 6 j | Distribution des séries |
|---|---|---|---|---|---|
| **C** (défaut) | `0hard/-6389medium/-20141soft` | 0 | 11 | 48 | 4 j : 1, 5 j : 16, 6 j : 88, 7 j : 25, 8 j : 13, 9 j : 6, 10 j : 3, 11 j : 1 |
| **A** (medium 100) | `0hard/-11878medium/-21057soft` | 0 | 10 | 37 | 5 j : 10, 6 j : 106, 7 j : 24, 8 j : 9, 9 j : 2, 10 j : 2 |
| **B** (dure) | **`-24hard`**`/-7241medium/-20784soft` | **23** | 6 | 0 | 4 j : 2, 5 j : 19, 6 j : 132 |

**Lecture.** C'est la grille qui réclame les longues séries, pas le score :
88 animateurs sur 153 tiennent exactement six jours d'affilée au dosage par
défaut, 132 sous la règle dure. L'événement demande presque tout le monde
presque tous les jours.

- **La forme dure fait exactement ce qu'on lui demande, et l'événement n'est
  plus couvert.** Zéro animateur au-delà de six — mais 23 sièges restent
  vides au bout de 600 s, là où les deux autres configurations pourvoient
  tout. La règle est respectée, le festival ne l'est pas.
- **Le poids ne rattrape pas grand-chose.** Le score medium de A n'est pas
  comparable brut : la règle y coûte 100 points par jour excédentaire. En
  retirant sa part — 56 jours excédentaires × 100 chez A, 86 × 1 chez C — les
  **autres** règles de qualité valent 6 278 chez A contre 6 303 chez C :
  inchangées. Le poids 100 achète donc 86 → 56 jours excédentaires et 48 → 37
  personnes concernées, pour rien d'autre. C'est réel, ce n'est pas une
  solution.

### `festival-realiste-canicule` — 153 animateurs, 16 jours, 300 s

| Config | Score | Sièges vides | Plus longue série | Animateurs > 6 j |
|---|---|---|---|---|
| **C** (défaut) | `0hard/-3833medium/-10248soft` | 0 | 7 | 1 |
| **A** (medium 100) | `0hard/-3818medium/-10020soft` | 0 | 6 | 0 |
| **B** (dure) | `0hard/-3874medium/-9876soft` | 0 | 6 | 0 |

**Lecture.** Sur une grille qui a du mou, les trois configurations atteignent
zéro dur et pourvoient tout ; le témoin laisse passer **une** série de sept
jours, que le poids 100 comme la forme dure suppriment. La forme dure n'y coûte
rien — 1,1 % de medium concédé, meilleur score soft. C'est l'édition sur
laquelle elle s'allumerait sans y penser, et c'est précisément pourquoi elle ne
peut pas être le défaut : la même règle, sur la grille d'à côté, coûte 23
sièges.

### Ce que le banc décide

1. **Le défaut reste la forme medium au poids 1.** Aucune base légale, et la
   forme dure rend infaisable, dans son budget, la grille même de
   l'organisation qui a demandé la règle.
2. **La forme dure existe, éteinte.** Une édition qui a de la marge — la
   canicule en est une — l'allume et n'y perd rien. Une organisation qui
   préfère des trous à des séries de onze jours peut aussi l'allumer en
   connaissance de cause : le banc dit ce qu'elle coûte.
3. **Le poids reste dosable, et ce qu'il achète est mesuré.** Monter à 100 sur
   `festival-hivernal` retire un tiers des dépassements sans rien coûter aux
   autres règles de qualité. Ce n'est pas le défaut parce que ce n'est pas la
   réponse : le vrai levier, sur cette grille, est le vivier, pas le score.
4. **Le chiffre à retenir pour l'organisation** : au dosage livré, sa propre
   grille place 48 personnes au-delà de six jours d'affilée, jusqu'à onze. Ce
   n'est pas un défaut du solveur, c'est ce que l'événement demande à effectif
   constant — et c'est l'écran Équité, colonne « plus longue série », qui le
   montre avant la publication.
