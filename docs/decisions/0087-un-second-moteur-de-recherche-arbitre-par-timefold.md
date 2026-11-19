# 0087 — Un second moteur de recherche, écrit à la main, que Timefold arbitre

- **Statut** : accepté · le moteur est livré **désactivé** ; l'activer est un réglage d'instance · complétée par la note en fin de page
- **Date** : novembre 2026
- **Portée** : recherche d'un plan (`solver/handsearch`, `service/solve`), rien du modèle ni des règles
- **Voisine de** : [0013](0013-diagnostic-par-le-score-director.md) (le score sans l'API réservée), [0079](0079-la-phase-de-faisabilite-tire-sur-une-liste-par-pas-et-reconstruit-rarement.md) et [0082](0082-la-fenetre-de-plateau-part-de-la-faisabilite.md) (la recherche de Timefold réglée au plus près), [0080](0080-un-solveur-exact-pour-la-seule-verification-du-besoin-etude.md) (le solveur exact, étudié pour une autre question), [0086](0086-le-calcul-deporte-un-worker-sans-conteneur-et-un-crochet.md) (dont elle reprend l'interface et la politique d'échec)

## Contexte

Une résolution évalue des mouvements : « ce siège à cette personne, que
devient le score ? ». Sur les contraintes écrites en flux (`solver/constraints`),
Timefold en évalue un à trois milliers par seconde sur une édition de taille
courante — chaque mouvement traverse le réseau des 47 règles, quelle que soit
celle qu'il concerne. Les réglages de la recherche ont été poussés aussi loin
qu'ils allaient (0079, 0082) et le calcul déporté (0086) achète un cœur plus
rapide ; ni l'un ni l'autre ne change cet ordre de grandeur.

Or presque toutes nos règles ne lisent que **les sièges d'une personne** :
ses durées, ses repos, ses pauses, ses repas, ses trajets. Déplacer un siège
ne change le score que de la personne qui le quitte et de celle qui le prend.
Relire ces deux personnes coûte quelques microsecondes.

## Décision

### 1. Un second moteur, qui ne remplace rien

`solver/handsearch` contient une recherche locale écrite à la main :
remplissage glouton, acceptation tardive sur des déplacements et des
échanges, mouvements dirigés vers ce qui est en écart, chaîne d'éjection pour
les derniers sièges vides. Son score est tenu à jour mouvement par mouvement
(`IncrementalScore`) sur le problème mis à plat une fois (`FlatProblem`).

Les 47 règles y sont écrites **une seconde fois**, sous quatre formes :

| Forme | Exemples | Coût d'un mouvement |
| --- | --- | --- |
| un siège et son titulaire, rien d'autre | disponibilité, souhaits, verrous, plan publié | une lecture de table |
| les sièges triés d'une personne | durées, repos, pauses, repas, trajets | une ou deux relectures |
| un compte partagé (ligne, stand, créneau, plan) | référent, répartition des mineurs, équilibrages | quelques additions |
| le coût d'une personne lit les sièges d'une autre | relais de pause, encadrement d'un mineur, paires ad hoc | relire ceux qui en dépendent |

Le moteur n'importe rien de `ai.timefold.solver.core.impl`, ne lit aucune
configuration et n'ouvre rien : il tourne tel quel dans un worker (0086).

### 2. Timefold reste l'arbitre, et la seule source du score

Le score du moteur n'est jamais cru. `HandSearchSolver` pose le meilleur plan
sur le problème, puis le fait **re-scorer par les contraintes en flux** — par
le `SolutionManager`, la voie sans licence de 0013. Ce que l'application
enregistre et explique vient de ce second calcul.

Pendant la recherche, la courbe de score est celle du moteur : arbitrer
chaque meilleur plan coûterait ce que le moteur fait gagner. Deux instants
sont arbitrés, ceux sur lesquels l'application s'engage : **le premier plan
sans écart dur**, avant que la tâche annonce « faisable à N s », et **le plan
final**, avant qu'il soit rendu.

**Deux scores différents font échouer la résolution** : tâche en erreur, plan
enregistré intact, message qui donne les deux scores. Jamais de repli sur
l'autre moteur — c'est la politique du calcul déporté (0086), pour la même
raison : un moteur qui a dérivé des règles est une panne à voir, et un plan
calculé autrement sans le dire la cacherait. Contrairement à une machine
injoignable, cette panne-là est un défaut du code : elle est remontée comme
telle.

Le moteur refuse de lui-même, en clair, ce qu'il ne sait pas scorer : plus de
64 typologies de jeu, une pause de plus d'une heure, un siège sans horaire
complet, un siège tenu par quelqu'un que le problème ne connaît pas, un fait
porté en double (deux états pour une contrainte, deux plafonds pour une
typologie, deux jeux de paramètres), et un score medium ou soft qui sort de ce
que leur nombre commun sait ordonner (500 millions de points soft). Le niveau
dur est compté à part et n'a pas de plafond : un plan de départ dont des
centaines de sièges sont tenus par quelqu'un qui n'y a plus droit, à 10 000
points chacun, se résout comme un autre.

Une troisième issue n'est ni un refus ni un désaccord : le moteur se contredit
lui-même — le score qu'il a tenu au fil des mouvements n'est pas celui du plan
relu par une seconde instance construite du plan seul. C'est un défaut du
moteur, dit comme tel sur la tâche et journalisé avec le détail par règle.

### 3. Le filet est dans la suite de tests, pas dans la discipline

Deux engagements tiennent le second exemplaire des règles :

- **`HandSearchCoverageStructuralTest`** : toute contrainte du catalogue a sa
  règle dans le moteur, et toute règle y est comptée quelque part. Une
  contrainte ajoutée d'un seul côté échoue par son nom.
- **`HandSearchDifferentialTest`** : sur chaque plan essayé — vide, aléatoire
  avec doubles affectations, aléatoire sans, tout sur trois personnes, plan
  trouvé, plan trouvé puis perturbé —, les deux scores sont égaux **contrainte
  par contrainte**, pas au total où deux erreurs se compensent. Les scénarios
  y passent tels quels puis avec les branches qu'aucun scénario versionné ne
  porte, fabriquées en mémoire : verrous, sièges épinglés, passé figé, plan
  publié, sièges scindés, plafonds par typologie, les cinq types d'exception,
  anniversaires pendant l'événement. Et chaque contrainte doit y avoir été
  comparée au moins une fois **sur un score non nul** : une égalité à zéro ne
  prouve rien.

### 4. Derrière l'interface du solveur, choisi par l'instance

`HandSearchSolver` est un `Solver` comme `RemoteSolver` : la file, l'arrêt et
la courbe de score ne savent pas quel moteur cherche. `planning.solver.engine`
(`SOLVER_ENGINE`) vaut `timefold` par défaut et `hand` pour le moteur direct ;
le réglage voyage avec le problème jusqu'au worker, qui suit l'instance et non
sa propre variable.

Sous `hand`, la résolution en deux temps d'un plan publié
([0067](0067-la-faisabilite-avant-la-stabilite-apres-publication.md)) n'a pas
lieu : elle répond à une lenteur de l'autre recherche. La vérification du
besoin et les résolutions « jusqu'à la faisabilité » restent sur Timefold.

## Mesure

`festival-hivernal` (3 438 sièges, 153 animateurs), 300 s, sans arrêt sur
plateau, graine 0, chaque moteur seul sur la machine de développement
(`SolverBenchTest`, avec `-Dbench.engine=hand` pour le second) :

| Moteur | Zéro écart dur | Score à 60 s | à 120 s | à 300 s |
| --- | --- | --- | --- | --- |
| Timefold | 36 s | −34 140 medium / −91 025 soft | −30 495 / −90 005 | −27 380 / −92 190 |
| direct | 10 s (dont 8 s de remplissage glouton) | −24 910 / −93 965 | −23 575 / −93 830 | −22 915 / −94 845 |

Le moteur direct est faisable trois à quatre fois plus tôt et passe en une
minute sous le medium que Timefold atteint en cinq. Son soft reste un peu
moins bon (le niveau qui cède aux deux autres) : l'acceptation tardive à
historique fixe ne le travaille pas une fois le medium à plat.

## Ce que ça coûte

- **Chaque règle existe deux fois.** Une contrainte nouvelle s'écrit en flux,
  puis dans le moteur ; les deux tests ci-dessus échouent tant que la seconde
  écriture manque ou diffère. C'est le prix, et il est permanent.
- Trois règles ont demandé plus qu'une transcription, et le différentiel est
  ce qui l'a montré : la pause relayée dépend de l'ordre de deux sièges
  simultanés (le moteur prend celui du problème, celui d'un calcul à neuf) ;
  l'encadrement d'un mineur ne se compte pas par ligne dès qu'une ligne a des
  horaires resserrés ; l'équilibrage reprend l'arrondi à six chiffres de
  Timefold près du demi-point.
- La fin de partie — les deux ou trois derniers sièges vides — est moins
  régulière que le reste : la chaîne d'éjection les trouve, en un temps qui
  varie du simple au quadruple selon la graine.
- Résolution incrémentale et réamorçage passent par le même moteur (il part
  du plan que le problème porte et ne touche pas un siège épinglé), sans
  avoir encore été mesurés sous lui.

## Alternatives écartées

- **Un solveur exact (CP-SAT).** Étudié en 0080 pour une question oui / non.
  Deux règles n'y entrent qu'approchées (pause relayée, repos hebdomadaire
  crédité), il apporte une dépendance native d'une centaine de mégaoctets
  hors de l'image, et rien ne dit qu'il optimise 47 règles pondérées sur
  4 000 sièges dans le temps d'une résolution.
- **Changer de langage.** Le gain vient de l'évaluation incrémentale, pas du
  langage : en Java, le moteur lit les objets du domaine pour se construire,
  partage les constantes légales et tourne dans la même image, le même worker,
  les mêmes tests.
- **Garder Timefold et lui donner un calculateur de score incrémental.** On
  gardait ses mouvements et ses phases, mais on perdait la décomposition par
  contrainte que lit tout le diagnostic (0013), et on réécrivait les règles
  quand même — sans arbitre indépendant pour dire si la réécriture est juste.
- **Remplacer Timefold.** Les contraintes en flux restent la définition des
  règles : ce sont elles que le catalogue décrit, que le diagnostic explique
  et que les tests unitaires de contraintes vérifient une par une. Le moteur
  direct est un accélérateur qui doit leur rendre des comptes, pas une
  seconde vérité.

## Note de révision — ce qui a bougé depuis

La décision tient ; trois points de son texte ne décrivent plus le code.

- **Refus.** Une pause de plus d'une heure et une édition de plus de 64
  typologies ne sont plus refusées : le relais d'une pause se cherche parmi
  les sièges qui chevauchent le siège tenu, et les typologies se numérotent
  par animateur (64 est ce qu'une fiche peut porter d'appréciations). S'y
  ajoute un refus de taille : au-delà de 15 millions de couples siège ×
  animateur — le seuil de la construction échantillonnée de
  [0036](0036-construction-echantillonnee-des-tres-gros-problemes.md) — la
  table du moteur prendrait l'essentiel d'un gigaoctet.
- **Résolution incrémentale et réamorçage** sont vérifiés sous les deux
  moteurs (`SolveEnginesPerimetersTest`) et de bout en bout par la file
  (`SolverJobHandEngineTest`).
- **Arrêt sur plateau.** Celui du moteur direct suit désormais les sources
  des deux terminaisons de Timefold (`Plateau`) : l'horloge part avec la
  recherche, le plan de départ n'est pas une référence, chaque meilleur plan
  — faisable ou non — en est une, une fois, le plus ancien d'abord. Il
  repartait du score de sa dernière relance, ce qui finissait une résolution
  jusqu'à une fenêtre plus tôt. `PlateauTest` compare à des instants déduits
  de ces sources à la main : il ne fait pas tourner les classes de Timefold,
  internes et construites autour de portées de solveur. Un écart reste : le
  moteur date un meilleur plan au pointage suivant, quelques milliers de
  mouvements plus tard.
- **Fin de partie.** Pour décider d'accepter un mouvement, un siège vide pèse
  le double de ce que le score compte : en combler un en enfreignant une
  autre règle reste un pas en avant, et les derniers sièges vides ne sont
  plus un palier dont on sort par chance. Ce poids ne sert qu'à
  l'acceptation : le meilleur plan est choisi, annoncé et rendu sur le vrai
  score, et la construction décide elle aussi sur les vrais points durs.
