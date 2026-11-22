# 0088 — Le moteur direct devient le moteur par défaut

- **Statut** : accepté · version 3.0.0 · révise le point « livré désactivé » de [0087](0087-un-second-moteur-de-recherche-arbitre-par-timefold.md)
- **Date** : novembre 2026
- **Portée** : la valeur par défaut de `planning.solver.engine` (`SOLVER_ENGINE`) ; ni le moteur, ni les règles, ni l'arbitrage ne changent
- **Voisine de** : [0086](0086-le-calcul-deporte-un-worker-sans-conteneur-et-un-crochet.md) (le worker suit le réglage de l'instance)

## Contexte

[0087](0087-un-second-moteur-de-recherche-arbitre-par-timefold.md) a livré le
moteur direct **désactivé** : `SOLVER_ENGINE=timefold` par défaut, `hand` pour
qui le choisit. Le temps de le connaître en exploitation était le but de ce
choix. Il a tourné sur une instance réelle sans incident, et les deux garde-fous
qui justifiaient la prudence existent toujours : Timefold re-score chaque plan
avant qu'il soit cru, et la suite de tests compare les deux scores règle par
règle (`HandSearchDifferentialTest`).

Rester sur `timefold` par défaut, c'est donner à toute nouvelle instance le
moteur le plus lent, et à toute mise à jour la même recherche qu'avant, alors
que l'autre atteint un plan sans écart dur trois à quatre fois plus tôt.

## Décision

### 1. Le défaut passe à `hand`

`planning.solver.engine=${SOLVER_ENGINE:hand}`. Une instance qui ne dit rien
cherche ses plans avec le moteur direct ; `SOLVER_ENGINE=timefold` et un
redémarrage lui rendent la recherche d'origine, sans migration ni donnée à
convertir. Le réglage reste un réglage d'**instance** : un worker suit celui
de l'application qui lui envoie le problème.

C'est une **rupture majeure (3.0.0)**, au sens de `docs/versioning.md` : un
défaut qui change le comportement de toutes les instances à la mise à jour. Les
plans rendus ne sont plus ceux d'avant à budget égal (autre recherche, autres
égalités départagées), et un plan est maintenant refusé, avec un message sur la
tâche, quand le moteur ne sait pas le scorer (liste dans `docs/exploitation.md`).

### 2. Ce qui reste sur Timefold

- **L'arbitre** : chaque plan du moteur direct est re-scoré par les contraintes
  en flux, et un écart fait échouer la résolution, sans repli. Le moteur direct
  n'est jamais cru seul.
- **La vérification du besoin** (équipe fictive résolue jusqu'à la
  faisabilité) et les résolutions « jusqu'à la faisabilité ».
- **Le diagnostic** : l'analyse du score par règle passe par
  `service/diagnostic/`, quel que soit le moteur qui a cherché.
- **La résolution en deux temps d'un plan publié**
  ([0067](0067-la-faisabilite-avant-la-stabilite-apres-publication.md)) n'a pas
  lieu sous `hand` (elle répond à une lenteur de l'autre recherche) ; elle
  reste celle de `timefold`.
- Les définitions des règles : elles restent écrites en flux, qui les
  définissent ; le moteur direct les écrit une seconde fois et le test
  différentiel dit si la seconde écriture est juste.

### 3. Retour arrière

`SOLVER_ENGINE=timefold` dans le `.env.prod`, puis redémarrage : rien d'autre.
Le schéma de base de données n'est pas touché, et un plan enregistré sous l'un
des moteurs est lisible, re-scorable et réamorçable sous l'autre. Le retour est
aussi la conduite à tenir devant « Le moteur direct s'est contredit dans son
propre score » : signaler le défaut, rendre la main à Timefold en attendant.

## Mesure

Celle de 0087, reproductible : `festival-hivernal` (3 438 sièges, 153
animateurs), 300 s, sans arrêt sur plateau, graine 0, chaque moteur seul
(`SolverBenchTest`, avec `-Dbench.engine=hand` pour le moteur direct).

| Moteur | Zéro écart dur | Score à 60 s | à 120 s | à 300 s |
| --- | --- | --- | --- | --- |
| Timefold | 36 s | −34 140 medium / −91 025 soft | −30 495 / −90 005 | −27 380 / −92 190 |
| direct | 10 s | −24 910 / −93 965 | −23 575 / −93 830 | −22 915 / −94 845 |

Le moteur direct est faisable trois à quatre fois plus tôt et passe en une
minute sous le medium que Timefold atteint en cinq. Il cède environ 3 % de soft
à 300 s ; ce prix est connu et accepté (le niveau qui cède aux deux autres).

## Ce que ça coûte

- Une suite de tests qui joue le défaut réel doit aussi garder des cas qui
  forcent `timefold` pour ce qui lui est propre (deux temps, filtres et
  fabriques de mouvements) : deux moteurs à tenir, comme dans 0087.
- Le plan d'une résolution partie de zéro n'est pas comparable siège par siège
  à celui d'avant la 3.0.0, à budget égal.
- Une édition hors du périmètre du moteur direct (plus de 15 millions de
  couples siège × animateur, plus de 64 appréciations de typologies par fiche)
  est refusée par un message : l'exploitant qui en dépend règle
  `SOLVER_ENGINE=timefold`.

## Alternatives écartées

- **Garder `timefold` par défaut jusqu'à la 3.0.0 de calendrier.** Retarder le
  gain sans nouvelle information : le moteur a l'arbitre et le différentiel.
- **Un repli automatique sur Timefold en cas d'écart.** Écarté par 0087 : il
  cacherait exactement le défaut que l'arbitre est là pour montrer.
- **Changer le défaut dans une version mineure avec « ⚠️ Attention ».**
  `docs/versioning.md` réserve ce marqueur aux ruptures d'exploitation non
  majeures (un réglage rééchelonné, une valeur à convertir) ; un défaut qui
  change le comportement de toutes les instances est MAJOR.
