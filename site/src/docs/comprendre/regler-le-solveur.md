# Régler le solveur

Vous saurez quelles propriétés bornent un calcul, ce que chaque réglage de `solverConfig.xml` achète et ce qu'il coûte, et comment écrire une règle sans ralentir la recherche. Ce qui suit vaut pour le moteur Timefold, celui de `solverConfig.xml` et des *constraint streams*, qui définit les règles et re-score tout plan rendu par le moteur direct (voir [comment le planning est calculé](comment-le-planning-est-calcule.md)). Les mesures qui ont tranché chaque réglage, et les pistes écartées, sont rassemblées dans [la mémoire du projet](repo:docs/memoire-du-projet.md).

## Les propriétés qui bornent un calcul

| Propriété | Défaut | Rôle |
| --- | --- | --- |
| `planning.solver.seconds-limit` | `900` (`SOLVER_SECONDS_LIMIT` ; `3` en `%test`) | Budget de résolution de l'instance, le même pour toutes les éditions ([ADR 0110](repo:docs/decisions/0110-budget-de-calcul-d-instance-file-en-memoire-quota-public.md)) |
| `planning.solver.unimproved-seconds-limit` | `180` (`SOLVER_UNIMPROVED_SECONDS_LIMIT` ; `2` en `%test`), `0` désactive | Fenêtre de plateau de l'instance |
| `planning.solver.plateau-gain-medium-per-seat` | `0.25` (`0` en `%test`) | Gain de medium, par siège du problème, que la fenêtre doit apporter pour que le calcul continue |
| `planning.solver.engine` | `hand` (`SOLVER_ENGINE`) | Le moteur qui cherche : `hand` (moteur direct, [ADR 0087](repo:docs/decisions/0087-un-second-moteur-de-recherche-arbitre-par-timefold.md), défaut depuis l'[ADR 0088](repo:docs/decisions/0088-le-moteur-direct-devient-le-moteur-par-defaut.md)) ou `timefold` |
| `planning.staffing.verification-default` | `calcul` | Ce que répond la vérification du besoin quand la requête ne choisit pas : `calcul` (une équipe fictive est résolue) ou `borne` (le minimum exact, sans calcul ; à adopter quand l'équivalence est totale, [ADR 0098](repo:docs/decisions/0098-la-borne-exacte-s-affiche-d-abord-le-calcul-reste-le-defaut.md)) |
| `planning.solver.seconds-limit-max` | `3600` (`SOLVER_SECONDS_LIMIT_MAX`) | Plafond de ce qu'un lancement demande (`?seconds=`, MCP) ; le démarrage est refusé s'il est sous `seconds-limit` |
| `planning.public-instance` | `false` (`PUBLIC_INSTANCE`) | Instance publique : seule elle applique le quota ci-dessous et refuse de démarrer sans mentions légales |
| `planning.solver.max-solves-per-hour` | `0`, aucune limite (`SOLVER_MAX_SOLVES_PER_HOUR`) | Instance publique seulement : quota sur 60 minutes glissantes, compté au lancement par [`SolverQuota`](repo:src/main/java/dev/sylvain/planning/service/solve/SolverQuota.java) ([ADR 0075](repo:docs/decisions/0075-quota-de-calcul-de-l-instance-dans-l-application.md)) |
| `planning.solver.max-queued-jobs` | `0`, aucune limite (`SOLVER_MAX_QUEUED_JOBS`) | Instance publique seulement : tâches en file derrière celle qui tourne |
| `planning.constraint-weights.<contrainte>` | `1` pour une règle dure, `5` pour la plupart des autres, `25` pour `stabiliteDuPlanPublie` | Poids du catalogue, voir [doser et désactiver les règles](doser-et-desactiver-les-regles.md) |
| `planning.jobs.reprise-au-demarrage` | `true` (`false` en `%test`) | Règle au démarrage ce qu'une exécution précédente a laissé inachevé : la file n'est plus conservée, ses tâches deviennent `INTERROMPU` ; un calcul parti sur une machine distante est suivi de nouveau |
| `planning.diagnostic.mode` | `score-director` | Implémentation de la ventilation du score par contrainte ; `solution-manager` est l'oracle du test de contrat, pas un mode de secours ([ADR 0013](repo:docs/decisions/0013-diagnostic-par-le-score-director.md)) |

Le plafond `-max` et le quota ne se contournent pas : un service qui lance un calcul consomme le quota (sans effet hors instance publique). Pour exécuter le calcul sur une autre machine, voir [le calcul sur une autre machine](../installer/calcul-sur-une-autre-machine.md).

## L'arrêt anticipé dépend de la faisabilité

Un calcul s'arrête quand le budget est épuisé, ou quand le planning est déjà faisable et n'a pas gagné, sur la fenêtre de plateau, `0,25 × sièges` points de medium. Les deux conditions sont liées par un `AND` entre `bestScoreFeasible` et le seuil `unimprovedScoreDifferenceThreshold` de Timefold (le soft reste libre). Trois choix en découlent.

- **La faisabilité conditionne l'arrêt.** Sans elle, le solveur abandonnerait sur un plateau de score dur, c'est-à-dire quand il a le plus besoin du reste de son budget. Le revers : à budget trop court, il rend un plan avec des places non tenues sans lever d'erreur, puisque l'arrêt sur plateau ne se déclenche pas non plus. Le défaut de 900 s laisse environ 2,7 fois la durée mesurée jusqu'à la faisabilité sur un profil réel de 3 500 sièges.
- **Le plateau se juge sur un gain minimal de medium.** La seconde phase trouve toujours un point de medium de temps en temps : un plateau strict ne se déclenchait jamais sur une édition réelle. `0` en test redonne le plateau strict. Les rendements décroissants de Timefold ne lisent que le soft et ne conviennent pas non plus ([ADR 0081](repo:docs/decisions/0081-le-plateau-se-juge-sur-son-gain-de-medium-a-l-echelle-du-plan.md)).
- **La fenêtre part de la faisabilité.** Une heuristique de construction vide sépare les deux recherches locales : elle ne fait aucun pas, mais Timefold remet la fenêtre de plateau à zéro à sa fin. Sans elle, la fenêtre remontait dans la phase de faisabilité et un hôte lent s'arrêtait dès 0 hard, sans optimiser ([ADR 0082](repo:docs/decisions/0082-la-fenetre-de-plateau-part-de-la-faisabilite.md)).

## La construction échantillonnée des très gros problèmes

La première phase évalue chaque siège contre chaque animateur. Au-delà de 15 millions de couples sièges × animateurs, [`LargeProblemConstruction`](repo:src/main/java/dev/sylvain/planning/service/solve/LargeProblemConstruction.java) la remplace à la résolution par la même phase (sièges les plus difficiles d'abord, filtre d'éligibilité compris) qui n'évalue que 50 animateurs éligibles tirés au hasard par siège. La limite compte ce que le filtre garde, pas les tirages : comptée sur les tirages, un siège dont tout le tirage était écarté n'avait aucun mouvement, et Timefold termine alors la phase entière.

Mesuré sur les scénarios `extreme-` versionnés, à graine égale :

| Problème | Construction | 0 dur en |
| --- | --- | --- |
| `extreme-02` (6 480 sièges × 1 000 animateurs) | exacte | 182 s |
| `extreme-02` | échantillonnée | 12 s |
| `extreme-09` (41 370 sièges × 1 000 animateurs) | exacte | 50 min 48 s |
| `extreme-09` | échantillonnée | 3 min 30 s |

Sous le seuil, la construction exacte se rembourse : elle coûte quelques minutes de plus pour un premier plan meilleur que la recherche locale ne rattrape pas. Au-dessus, elle ne tient plus dans un budget de production. Le seuil se place là où la construction exacte dépasserait dix minutes, et toute édition réelle est loin dessous ([ADR 0036](repo:docs/decisions/0036-construction-echantillonnee-des-tres-gros-problemes.md)).

Le critère d'arrêt `pickEarlyType` `FIRST_FEASIBLE_SCORE_OR_NON_DETERIORATING_HARD` est inutilisable ici : un siège peut rester vide, « le laisser vide » ne dégrade pas le score, et la construction s'arrêterait sur un plan entièrement vide.

## `acceptedCountLimit` : deux phases, un réglage par objectif

Le nombre de mouvements candidats échantillonnés à chaque pas de recherche locale (`<forager>`) est le réglage le plus sensible du fichier, et il dépend de la taille du problème. Un compteur bas atteint la faisabilité vite mais polit mal ; un compteur haut polit bien, mais sur un gros problème un pas coûte assez cher pour qu'échantillonner beaucoup de candidats affame la recherche et qu'elle n'atteigne jamais la faisabilité. La recherche locale est donc découpée en deux phases :

| Phase | `acceptedCountLimit` | Fin | Rôle |
| --- | --- | --- | --- |
| 1 | `1` | `bestScoreFeasible` | atteindre le 0 hard le plus tôt possible |
| 2 | `40` | le reste du budget | polir le medium et le soft |

Ce qu'il faut retenir de la mesure :

- Un compteur bas seul atteint la faisabilité, mais en déplaçant le medium au mauvais endroit : sur le profil réel, le nombre de postes tenus par un animateur sans appréciation de la typologie du stand doublait. Un compteur haut seul n'atteint jamais le 0 hard. Les deux phases donnent la faisabilité et la qualité de l'échantillonnage large.
- Timefold garde toujours la meilleure solution rencontrée : la phase 2 ne peut pas reperdre la faisabilité acquise. Les deux sélecteurs ciblés sur les postes non pourvus ne sont pas repris en phase 2, il n'y en a plus.
- `lateAcceptanceSize` à 400 : en dessous, la qualité se dégrade ; au-dessus, elle ne change rien.
- Le réglage de phase 1 a été remesuré à la montée en Timefold 2.5 (à réglage identique, un pas évalue environ dix fois plus de mouvements) et un fixture trop petit sous-estime le problème réel d'un tiers : `festival-realiste` sort de la construction à -110 hard quand le profil de production sort à -218. Arbitrez un réglage sur le problème réel, jamais sur le seul scénario versionné.

À refaire si la taille du problème change nettement : rejouer le vrai problème (`GET /api/planning/persisted`, champ `score` retiré, animateurs remis à `null`) hors du serveur de dev, une configuration à la fois. Deux solveurs concurrents se partagent cache et bande passante mémoire, et la comparaison n'a plus de sens. Le banc `SolverBenchTest` est décrit avec [les tests](tests.md).

## La seconde phase échange des journées

Plusieurs règles medium comptent par personne et par jour (stands premium, typologies, emplacements). Pour qu'une journée change de mains, un mouvement à un siège doit d'abord dégrader le score, ce que la recherche accepte rarement. La seconde phase tire donc aussi, à poids 2 face au *change* et au *swap*, un **échange de journées** ([`DaySwapMoveIteratorFactory`](repo:src/main/java/dev/sylvain/planning/solver/DaySwapMoveIteratorFactory.java)) : les postes d'un animateur à une date passent à un collègue qui travaille ce jour-là, et les siens à lui, en un seul mouvement. Chacun garde les dates qu'il travaille, donc son nombre de jours, sa semaine et sa série de jours ; les heures de la journée, le total de la semaine et le repos autour changent, et le score en juge.

Le couple est choisi, pas tiré puis filtré : un bloc (animateur, date) uniforme parmi ceux qui existent, puis un collègue du même jour uniforme parmi ceux qui peuvent échanger. Un échange n'est construit que si le score ne le refuserait pas d'emblée :

| Vérification | Raison |
| --- | --- |
| Chaque poste va à un animateur éligible (`EligibleAnimateurMoveFilter.isEligible`) | indisponibilité, règles des mineurs propres au couple poste-animateur |
| Aucun poste reçu ne chevauche un poste figé que son receveur garde ce jour-là | le passé et les verrouillages ne changent pas de mains, mais ils occupent leur heure |
| La journée d'un mineur, postes reçus compris, reste sous `dureeQuotidienneMaxMineur` | mesurée comme la règle la mesure, et seulement quand elle est active |

Deux journées faites des mêmes postes s'échangent quand même : les refuser coûte du medium (-30 852 contre -30 293 sur `festival-hivernal`). C'est ce qui distingue ce mouvement des *pillar moves* de Timefold, écartés après mesure : le `pillarSwapMoveSelector` tirait deux blocs au hasard et son filtre en rejetait 99,97 %, ce qui divisait la vitesse par neuf, et le `pillarChangeMoveSelector` perdait du medium sur sept scénarios sur neuf ([ADR 0078](repo:docs/decisions/0078-echange-de-journees-plutot-que-mouvements-pillar.md)).

Gain mesuré sur scénarios versionnés (90 s de seconde phase depuis le même plan faisable, moyenne des graines 0, 1 et 2) :

| Scénario | Medium sans échange | Medium avec échange |
| --- | --- | --- |
| `scenario-complet` | -30 737 | -23 822 |
| `festival-realiste-canicule` | -21 872 | -19 200 |
| `festival-hivernal` | -33 702 | -30 282 |
| `gamme-20` | -38 163 | -33 817 |
| `gamme-23` | -38 260 | -32 587 |

Le gain a le même sens à chaque graine sur les neuf scénarios essayés, au prix de 14 à 32 % d'évaluations par seconde en moins. Le soft recule sur plusieurs d'entre eux et reste classé sous le medium. Au-delà du poids 2, le gain tient dans l'écart entre graines alors que *change* et *swap* perdent des tirages. Les chiffres par graine sont dans le commentaire de la seconde phase de [`solverConfig.xml`](repo:src/main/resources/solver/solverConfig.xml).

## Écrire une règle sans ralentir la recherche

La vitesse est dominée par le nombre de tuples que les *constraint streams* construisent et maintiennent à chaque mouvement, pas par le nombre de règles. Un `forEachUniquePair` suivi d'un `filter` construit toutes les paires avant d'écarter la quasi-totalité ; un `Joiners.equal` en fait un accès indexé.

### Des joiners indexés plutôt que `filtering`

Sur `scenario-complet.yaml` (2 088 postes, 150 animateurs), reformuler quatre contraintes en joiners indexés a supprimé environ 202 000 tuples de paires inutiles, à trajectoire de recherche identique (`randomSeed=0`, mêmes 1 220 pas, même score final) :

| Contrainte | Paires construites avant | Après |
| --- | --- | --- |
| `incompatibiliteAdHoc` | 59 508 (toutes les paires de postes d'un même créneau) | 0 tant qu'aucune incompatibilité n'est saisie |
| `eviterRoulementStandsPremium` | 115 596 (toutes les paires d'un même stand) | 0 sans stand premium |
| `reposQuotidienMinimal` | environ 13 500 | les seuls créneaux de nuit tenus par un mineur |
| `eviterChangementEmplacementEloigne` | environ 13 500 | les seuls créneaux réellement enchaînés |

Le temps jusqu'à la faisabilité est passé de 22,0 s à 7,3 s. La règle qui en découle pour toute nouvelle contrainte :

1. exprimer d'abord ce qui peut l'être en `Joiners.equal`, `lessThan` ou `overlapping` ;
2. restreindre le flux d'entrée par un `filter` avant de joindre ;
3. ne garder `Joiners.filtering` que pour ce qui n'est pas indexable (la distance haversine et la comparaison de périmètre des contraintes ad hoc).

La métrique à regarder est la *move evaluation speed* du log du solveur, pas le temps écoulé.

### Un nœud par agrégation, partagé entre les règles

Bavet, le moteur des *constraint streams*, construit un nœud par objet de flux et le partage entre les contraintes qui lisent ce même objet. Il ne fusionne pas deux flux qui se ressemblent : deux `groupBy` aux mêmes clés sont deux nœuds, chacun entretenant sa liste à chaque mouvement. Écrites règle par règle, les familles entretenaient onze fois « les sièges de l'animateur ce jour-là ».

[`SeatStreams`](repo:src/main/java/dev/sylvain/planning/solver/constraints/SeatStreams.java) construit ces flux une fois par fournisseur et les prête à chaque famille : sièges tenus, sièges datés, sièges par animateur et par jour (avec les paramètres légaux ou les fenêtres repas en face), par animateur, par ligne stand × créneau. Deux règles de forme voisine se branchent sur le même objet. Le gardien d'activation (`ConstraintToggleSupport.actif`) se pose en queue de flux, sur les écarts, jamais en tête : en tête il serait un nœud de plus sur chaque siège et rendrait la tête impartageable.

Le partage ne change que le coût : les deux versions suivent la même trajectoire pas à pas et finissent sur le même plan. Les tests unitaires d'une famille (`ConstraintVerifier`) continuent d'appeler `define(factory)`, chaque famille se construit alors ses propres flux, et le partage entre familles n'a lieu que dans `PlanningConstraintProvider`. Une nouvelle règle qui lit les sièges part d'un flux de `SeatStreams` ; une agrégation qu'aucun flux n'offre encore s'y ajoute plutôt que dans la règle.

### Le filtre d'éligibilité n'est pas un mur

`EligibleAnimateurMoveFilter` écarte les couples (poste, animateur) que la règle légale refuserait de toute façon et décidables sur le seul couple : mineur la nuit, mineur sur un stand réservé aux majeurs, créneau plus long que le plafond quotidien ou continu d'un mineur. Il ne peut, par construction, écarter aucune solution faisable, et épargne un calcul de score pour découvrir un écart certain. Il n'est pourtant pas un mur : la reconstruction du *ruin and recreate* ne le lit pas. C'est pourquoi ces mêmes règles pèsent un forfait de 10 000 points durs par écart ([ADR 0034](repo:docs/decisions/0034-exclusions-eligibilite-plus-lourdes-que-tout.md)).

Un jeu de règles légales plus strict peut aussi rendre un découpage de journée structurellement infaisable (une soirée qui finit à minuit repousse le matin suivant, le vivier d'une typologie rare ne suffit plus). Si le calcul n'atteint plus la faisabilité après un durcissement légal, regardez d'abord le scénario avant la vitesse du solveur.

## Mode d'environnement et parallélisme

`solverConfig.xml` fixe `<environmentMode>NO_ASSERT</environmentMode>`. Les modes *assert* recalculent intégralement le score à chaque frontière de phase pour détecter une corruption ; les retirer a gagné environ 7 % de vitesse d'évaluation. Le déterminisme tient à `<randomSeed>`. En cas de doute sur le score, repassez à `PHASE_ASSERT`, ou à `FULL_ASSERT` pour identifier le mouvement fautif, puis revenez à `NO_ASSERT`. `REPRODUCIBLE`, nom du même compromis en Timefold 1.x, n'existe plus en 2.x et fait échouer la construction de la *solver factory*.

Il n'y a pas de `<moveThreadCount>` : la résolution incrémentale multi-thread est une fonction de Timefold Solver Enterprise Edition. Avec `timefold-solver-core` seul, le solveur refuse de démarrer si le paramètre est présent (« Enterprise Edition could not be loaded »). Les cœurs disponibles restent inexploités, ce qui est le plafond de performance principal du déploiement, et aucune réécriture de contrainte ne le contourne.

## Geler un bloc acquis

`PosteAffectation.verrouille` porte `@PlanningPin`, et un verrou de type `STAND` couvre toutes les places d'un stand. Poser un tel verrou sur un bloc volumineux mais facile (le montage et le démontage, 20 % des postes sur deux stands mono-typologie sans contrainte de compétence) retire ces entités de la génération de mouvements sans les retirer du planning : elles restent affectées, nominatives, et leurs heures continuent d'être évaluées.

Le gain porte sur l'espace de recherche ; le coût d'un calcul de score reste le même. Le verrou fige ces personnes et consomme leurs heures : il n'est sain que si l'affectation gelée est déjà bonne, d'où l'ordre résoudre, vérifier, puis verrouiller. Aucun code à écrire, deux appels d'API (voir [verrous et publication](plan-verrous-et-publication.md)).
