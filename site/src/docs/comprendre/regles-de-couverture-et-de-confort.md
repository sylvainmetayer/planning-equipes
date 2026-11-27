# Règles de couverture et de confort

Vous savez ce que lisent et ce que coûtent les règles qui ne viennent pas du Code du travail : polyvalents, quotas par typologie, temps de trajet, covoiturage, seuils de qualité, stabilité d'un plan déjà publié, et la manière dont toutes traitent un passé déjà vécu.

## Typologie polyvalente et réserve de polyvalents

Au plus une typologie porte le drapeau `polyvalent` (index unique partiel). Un animateur qui la possède dans ses compétences est **polyvalent** :

- `hasCompetenceFor(stand)` renvoie vrai pour n'importe quel stand : il n'est jamais pénalisé par `appreciationIncompatible` ;
- il est exclu de `limiterTypologiesDistinctesParAnimateur`, car le disperser est sa raison d'être ;
- `preserverBufferPolyvalents` pénalise chaque créneau où il ne reste aucun polyvalent libre.

Cette dernière règle est **volontairement en tension** avec `equilibrerCharge` : un polyvalent laissé en réserve déséquilibre mécaniquement la charge. L'arbitrage est porté par les niveaux : soft contre medium, l'équilibrage l'emporte sauf à égalité. Tant qu'aucune typologie n'est polyvalente, personne n'est polyvalent et la règle ne coûte rien. Le polyvalent est un renfort, pas un spécialiste ([ADR 0017](repo:docs/decisions/0017-fragilite-le-ninja-est-un-renfort-pas-un-specialiste.md)).

Aucune règle n'exprime aujourd'hui de préférence pour la variété des stands : celle qui pénalisait deux postes du même animateur sur le même stand entrait en conflit avec `eviterRoulementStandsPremium` et `limiterTypologiesDistinctesParAnimateur`, qui poussent à la stabilité. Rétablir ce besoin suppose d'abord de l'arbitrer contre ces deux règles.

## Un quota par typologie

Une typologie peut porter un plafond, `maxCreneauxParAnimateur`, saisi sur sa fiche. Vide, il signifie « pas de plafond » et une édition qui n'y touche pas ne change pas de comportement. La règle dure `plafondCreneauxParTypologie` le fait respecter ([ADR 0042](repo:docs/decisions/0042-quota-par-typologie-sur-la-typologie.md)). Deux choix à connaître avant de s'en servir :

- **L'unité est le créneau**, pas l'heure : quatre créneaux d'une heure et quatre de six heures pèsent pareil.
- **Un poste compte pour chaque typologie que son stand propose**, pas pour celles que son animateur maîtrise. On tient le jeu auquel on est assis, que la fiche le mentionne ou non. C'est le choix inverse de `limiterTypologiesDistinctesParAnimateur`, qui lit l'intersection : celle-là parle de ce qu'une personne doit apprendre, celle-ci de ce qu'un jeu consomme.

La portée est l'**édition entière**, jamais la journée ni la semaine. La règle étant dure, un plafond trop bas rend l'édition infaisable plutôt que dépassé : le diagnostic nomme alors qui, quelle typologie, combien de créneaux tenus pour quel plafond. La page Typologies porte la lecture correspondante (animateurs réellement affectés, postes, heures, écart avec ceux que le référentiel apprécie) : un plafond qu'on ne voit nulle part est un plafond que personne ne peut régler.

## Le temps de trajet entre deux postes

`eviterChangementEmplacementEloigne` ne regarde que deux postes qui se touchent (fin de l'un égale début de l'autre) et juge 300 m à vol d'oiseau sans notion de temps. Quelqu'un qui finit à 14 h 00 au stand A et reprend à 14 h 10 au stand B, à un kilomètre, n'était jamais examiné ; `limiterEmplacementsParJour` compte les zones d'une journée sans regarder ni l'ordre ni la distance.

`trajetInsuffisantEntrePostes` comble ce trou. Le temps de marche entre deux emplacements géolocalisés vaut **distance à vol d'oiseau × facteur de détour ÷ vitesse de marche**, arrondi à la minute supérieure : 1 000 m à 4 km/h avec un facteur 1,3 font 20 min. Pour deux postes consécutifs d'un même animateur le même jour, sur deux emplacements différents, séparés par un battement **strictement positif**, chaque minute qui manque au battement au-delà de la tolérance coûte un point medium. 10 min de battement pour 20 min de trajet et 5 min de tolérance coûtent 5 : le solveur a une pente à descendre, élargir le battement ou garder la personne au même endroit.

Trois choix la cadrent :

- **Pas de double compte.** Une paire à battement nul reste l'affaire de `eviterChangementEmplacementEloigne`, sur la même clé (les heures des créneaux) : une paire est jugée par l'une ou l'autre. Seules deux vacations consécutives forment une paire, un poste intercalé porte le trajet qui compte.
- **Pas de service d'itinéraire.** Ni OSRM ni Google : aucune dépendance réseau, aucune donnée envoyée à un tiers, un calcul déterministe. Le facteur de détour est le levier d'un site où l'on contourne.
- **Medium et dosable, pas dure.** Un trajet serré est un inconfort, la vitesse une estimation, et il n'y a aucune base légale.

Un stand sans emplacement, ou un emplacement sans coordonnées, rend la règle inerte : un trajet inconnu n'est ni court ni long.

La vérification des enchaînements (`GET /api/planning/enchainements`, outil MCP `consulter_repas_pauses`, section `enchainements`) lit le plan persisté avec le même calcul mais un filet plus large : toutes les paires consécutives, battement nul compris, passé compris. Elle signale en plus un battement qui est aussi la pause légale quand le trajet en mange une part (« trajet pris sur la pause »), sans que la règle de pause change.

## L'arrivée groupée (covoiturage)

Des bénévoles viennent à plusieurs dans une même voiture et repartent ensemble. `affiniteAdHoc` ne sait pas le dire : elle récompense deux personnes sur le même stand au même créneau, trop strict pour une voiture (qui n'a pas besoin du même stand) et trop faible (rien n'y aligne les heures).

`arriveeGroupee` lit, pour chaque ajustement `ARRIVEE_GROUPEE` (2 à 4 membres), chaque paire du groupe et chaque jour :

- si un membre travaille et pas l'autre : **forfait de 120 minutes**, car « les mêmes jours » pèse autant que « les mêmes heures » ;
- si tous deux travaillent : les minutes d'écart entre leurs **premiers débuts** au-delà de la tolérance (30 min par défaut, réglable sur la ligne de la règle), plus celles entre leurs **dernières fins**. Ce sont les fenêtres effectives, lues sur le même pliage (animateur, jour) que `eviterFermeturePuisOuverture` : une pause ou une coupure repas au milieu de la journée ne change rien.

Un membre qui arrive 45 min après l'autre coûte 15. Par paire et par jour, en minutes : une pente, pas un mur. La règle est **soft et non medium** : c'est un confort qui doit céder devant `equilibrerCharge`, les souhaits et toutes les règles légales (un mineur qui doit partir avant 22 h désaligne le groupe, pas l'inverse). Un jour entièrement passé n'est plus facturé.

Des indisponibilités déclarées différentes rendent certains jours inévitablement désalignés : l'onglet Covoiturage de l'écran Disponibilités les compte avant la validation, l'écriture de l'ajustement avertit (sans nommer les personnes autrement que par leur id) et la liste de cohérence du référentiel le rappelle. La page Problèmes liste les jours désalignés par groupe, le Rail marque les membres, et l'espace dit à chacun, jour par jour, si son covoiturage tient, sans jamais détailler les postes des autres.

## Les seuils de qualité

Les règles de « Qualité d'organisation » lisent leurs seuils dans `ParametresQualite`, fait de problème comme `ParametresLegaux`. Ils sont **persistés par édition** et se règlent dans Paramètres, onglet Organisation ; la ligne de la règle qui les lit, dans Règles du planning, y renvoie. Le bloc `planning.contraintes.*` de la configuration donne les valeurs d'une édition qui ne les a jamais réglés, et un scénario les emporte dans sa section `parametres.organisation:`.

| Seuil | Défaut | Règle qui le lit |
|---|---|---|
| Emplacements distincts par jour et par animateur | 3 | `limiterEmplacementsParJour` |
| Typologies distinctes par animateur, sur l'édition entière | 2 | `limiterTypologiesDistinctesParAnimateur` |
| Heure d'un service tardif | 22:00 | `eviterFermeturePuisOuverture` |
| Heure d'un service matinal | 10:00 | `eviterFermeturePuisOuverture` |
| Repos souhaité après un service tardif | 12 h | `eviterFermeturePuisOuverture` |
| Jours travaillés d'affilée | 8 | `maxJoursConsecutifsTravailles`, à tous ses niveaux |
| Vitesse de marche | 4 km/h | `trajetInsuffisantEntrePostes`, vérification des enchaînements |
| Facteur de détour | 1,3 | idem |
| Tolérance de trajet | 5 min | idem |
| Tolérance d'une arrivée groupée | 30 min | `arriveeGroupee`, lecture des arrivées groupées |

Aucun n'a de plancher d'ordre public : ce sont des conforts que l'organisateur arbitre. Laisser les deux heures de service vides est une réponse légitime, c'est ainsi qu'`eviterFermeturePuisOuverture` se tait ([ADR 0031](repo:docs/decisions/0031-signaler-le-plancher-sans-le-decider.md)). La portée du plafond de typologies est l'édition : le `groupBy` de la règle ne porte que l'animateur, sans clé de date, et un test le verrouille pour qu'un refactor ne le fasse pas glisser en silence. Le raisonnement sur 22 h et 10 h est dans [Repos et jours d'affilée](repos-et-jours-d-affilee.md).

## Stabilité du plan publié

`stabiliteDuPlanPublie` (medium, « Qualité d'organisation », dosable) répond à un constat : un calcul relancé après publication déplaçait des dizaines de personnes déjà prévenues pour un gain d'équité marginal, parce que rien dans le score ne disait que déplacer une personne informée a un coût ([ADR 0025](repo:docs/decisions/0025-stabilite-du-plan-publie.md)).

**Ce qu'elle compte.** Un point medium par siège dont le titulaire n'est pas celui que le plan publié avait sur ce stand et ce créneau : un point par personne à qui la publication dirait « votre emploi du temps a changé ». Les faits sont les sièges du dernier instantané publié (`AffectationPubliee`), chargés par `SolveRunner.prepareProblem` avant chaque résolution et chaque diagnostic, jamais envoyés par un client. Un siège publié laissé vide coûte autant qu'un remplacement : son titulaire a été prévenu, le trou est le sien, et c'est ce qui empêche le solveur d'attirer gratuitement un titulaire sur une ligne neuve en laissant derrière lui un trou que personne ne comble sans payer.

**Ce qu'elle ne compte pas, délibérément :**

- rien tant que rien n'a été publié : la règle est muette, le solveur reste libre de tout remanier en amont ;
- un stand ou un créneau créé après la publication : personne n'y a été prévenu de rien ;
- un siège ajouté sur une ligne publiée (l'effectif a grandi) compte son nouvel occupant, c'est bien une personne à prévenir.

**Ce qu'elle ne peut pas faire.** Elle est medium : une règle dure ou une indisponibilité l'emporte toujours (`PlanningHardConstraintsTest.publishedSeatsNeverOutweighAHardRule`). Elle ne fige rien non plus : pour figer, il y a les verrouillages et la replanification incrémentale. Elle arbitre, au poids près, entre le dérangement et le gain.

**Son poids par défaut est l'importance forte (25)** (`planning.constraint-weights.stabiliteDuPlanPublie`) : `equilibrerCharge` produit des milliers de points sur une édition réelle, donc à poids égal la stabilité pèserait peu. Elle se dose par édition comme les autres règles de qualité et s'éteint par l'interrupteur.

### Faire tenir la faisabilité malgré la stabilité

Quand un changement ajoute des sièges (un stand nouveau ouvert toute la journée), pourvoir un siège neuf aux heures pleines demande une chaîne de déplacements que chaque point de stabilité rend infranchissable pas à pas. Trois mécanismes y répondent :

- la phase de faisabilité porte un sélecteur *ruin and recreate* restreint aux sièges de l'heure d'un trou (`HoleNeighbourPosteFilter`, `solverConfig.xml`), qui évalue la chaîne comme un seul mouvement ;
- dès qu'un plan est publié et que la règle est active, la résolution se fait **en deux étapes dans le même job** : la faisabilité d'abord, stabilité pesée à zéro en mémoire, au plus deux tiers du budget ; puis le polissage, stabilité rétablie, qui rend leurs sièges publiés à autant de personnes que les règles dures et le temps restant le permettent. Le récapitulatif de l'onglet Calculer le dit, avec le nombre de places rendues ([ADR 0067](repo:docs/decisions/0067-la-faisabilite-avant-la-stabilite-apres-publication.md)) ;
- un autre trou résiste au *ruin and recreate* parce que sa chaîne passe par un autre jour de la semaine : prendre un siège le mardi, c'est en rendre un autre, le lundi, dont les deux demi-journées doivent aller à deux collègues qui n'en tenaient qu'une. `WeekRelocationMoveIteratorFactory` joue la chaîne entière comme un seul mouvement, avec des collègues éligibles au sens de `EligibleAnimateurMoveFilter`. Quand l'édition tient les jours d'affilée en dur, le jour rendu peut aussi être de l'autre semaine.

**Une grille qui change retire sa prise à la règle.** Elle tient des lignes stand × créneau : une dérivation ou un calendrier de gabarits qui remplace la grille (le plan enregistré part avec) rebat toutes les lignes. Un stand ajouté, lui, ne déplace pas les autres.

**Le compte à côté du score.** Chaque résolution rend aussi `impactPublication.personnes`, le nombre de personnes que la publication préviendrait, calculé comme la publication le calcule, sur les vacations par personne et non par la règle. Les deux ne coïncident pas exactement (une personne qui change deux sièges compte deux points et une personne) : l'écran montre le compte de personnes.

## Compté, non reproché : le passé

Pendant l'événement, une résolution reçoit les places des créneaux déjà commencés **reprises du plan enregistré et épinglées** : le passé est figé ([ADR 0044](repo:docs/decisions/0044-le-passe-est-fige.md), mécanique dans [plan, verrous et publication](plan-verrous-et-publication.md)). Ces places portent un drapeau `passe` que chaque règle lit selon une seule logique :

- une place passée **compte** : elle reste dans chaque flux, groupe et jointure. Ce que quelqu'un a travaillé hier conditionne le repos qui lui est dû cette nuit, les heures qui lui restent dans la semaine, les jours d'affilée qu'il atteint ;
- une place passée n'est **jamais reprochée** : une correspondance dont toutes les places sont passées (un trou d'hier, un mineur la nuit hier, une coupure repas manquée hier) est de l'histoire que le solveur ne peut pas réparer, et la facturer empêcherait un avenir faisable d'atteindre zéro dur. Une correspondance n'est facturée que si au moins une des places qu'elle implique n'est pas passée.

Concrètement : une règle par place ignore la place passée ; une règle par paire ne facture pas une paire dont les deux places sont passées ; une règle groupée par animateur et jour ou semaine compte tout mais ne facture le groupe que s'il tient encore une place à venir ; la récompense d'affinité suit la même lecture. Les diagnostics qui rejouent le score sur le plan enregistré (Règles du planning, `diagnostiquer_plan`, planchers, simulations) passent par le même fournisseur de contraintes et lisent donc la même chose. Les analyses qui comptent hors du solveur (Pauses, Besoin, contrôle de grille, Heures) **décrivent** toujours le passé, y compris ce qui s'y est mal passé : elles disent ce qui a été fait sans le reprocher non plus.

**Un plancher medium pendant l'événement, assumé.** `equilibrerCharge` et `equilibrerCreneauxPenibles` mesurent l'édition entière, places passées comprises (ce que chacun a déjà travaillé rend l'équité lisible), et ne sont facturées que tant qu'une place est à venir. Mais la résolution ne peut plus bouger que l'avenir : un écart déjà creusé avant aujourd'hui ne se rattrape pas, et le score medium garde un plancher qu'aucune relance ne ramène à zéro. Il n'est ni signalé comme les [planchers par règle](doser-et-desactiver-les-regles.md) ni soustrait : le score d'un solve pendant l'événement se compare au précédent, pas au zéro. Le lever demanderait de ne mesurer l'équilibre que sur l'avenir, ce qui inverserait la règle : l'animateur déjà chargé hier redeviendrait « à charger » demain.
