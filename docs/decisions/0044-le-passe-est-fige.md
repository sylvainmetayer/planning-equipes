# 0044 — Le passé est figé

- **Statut** : accepté, implémenté
- **Date** : septembre 2026
- **Portée** : construction du problème (résolution complète, réamorçage,
  replanification incrémentale), préparation des analyses, toutes les
  familles de contraintes, horloge du jour J, résultats de job, IHM, MCP

## Contexte

L'événement est en cours : il a commencé le 12, nous sommes le 15. Un
désistement tombe, l'organisateur relance une résolution — complète depuis
l'écran Solveur ou `lancer_solveur`, ou incrémentale — et le solveur **réécrit
le 13**, une journée déjà travaillée, parce qu'il y trouve un meilleur score.
Le planning enregistré ne dit plus ce qui a été fait, les heures et l'équité
se calculent sur une fiction, et les personnes qui ont travaillé le 13
reçoivent à la publication suivante un « votre emploi du temps a changé » pour
une journée derrière elles.

Rien, dans le modèle, ne connaissait « aujourd'hui ». `ProblemBuilder` ne
lisait aucune horloge ; seuls les verrouillages explicites épinglaient des
places ; la replanification incrémentale épinglait ce qui restait valable
mais **rouvrait** ce qu'un changement avait invalidé — un animateur supprimé,
une journée déclarée indisponible après coup, une indisponibilité forcée —
même sur une journée passée, et rouvrait tout ce que le périmètre nommait.
Seules les consignes appliquaient déjà « le passé reste intact »
([0043](0043-consigne-d-edition-fermer-une-bande-sans-rien-detruire.md)),
à travers `JourJClock`.

Trois faits ont cadré la décision :

- **personne ne peut tenir hier.** Une place d'un créneau commencé n'est plus
  un choix : elle a été tenue par quelqu'un, ou par personne, et aucune
  résolution ne change ce fait ;
- **ce qui a été travaillé conditionne la suite.** Le repos quotidien entre
  hier soir et demain matin, les heures de la semaine, les six jours d'affilée,
  la pause entre deux vacations se mesurent sur le passé autant que sur
  l'avenir. Retirer le passé du problème, c'est autoriser demain ce que hier
  interdit ;
- **le passé ne se corrige pas.** Un trou hier, un mineur la nuit hier, une
  coupure repas manquée hier sont des faits. Les facturer au score empêcherait
  un avenir parfaitement faisable d'atteindre le zéro dur, et l'écran
  Contraintes reprocherait au solveur ce qu'il ne peut pas réparer.

## Options envisagées

**(A) Retirer le passé du problème.** Ne construire que les places des
créneaux à venir. Écarté : les règles qui lient les jours perdent leur
mémoire. Un animateur ayant fermé hier à minuit pourrait ouvrir demain à
8 h ; une semaine entamée à 40 h en accepterait 48 de plus ; six jours
consécutifs déjà travaillés en autoriseraient six autres. Et le plan persisté
perdrait ses journées passées à chaque écriture, ou demanderait une fusion
que rien ne motive.

**(B) Épingler le passé et le scorer comme aujourd'hui.** Reprendre les
places passées du plan enregistré, les épingler, et laisser les contraintes
les juger normalement — le mécanisme des verrouillages
([0003](0003-verrouillage-par-pin-natif.md)), qui « laisse une violation
visible plutôt que de désactiver les règles ». Écarté pour le passé : un
verrou est un choix qu'on peut défaire, le passé non. Une violation passée
resterait au score pour toujours, le zéro dur deviendrait inatteignable dès
le premier incident, la terminaison sur faisabilité ne se déclencherait plus
et le budget serait consommé en entier à chaque relance. Le diagnostic
nommerait chaque jour une erreur d'hier comme une erreur du plan.

**(C) Épingler le passé, le compter, ne jamais le reprocher.** Retenu. Les
places passées sont reprises du plan enregistré et épinglées ; elles restent
dans chaque flux, groupe et jointure ; une correspondance n'est facturée que
si au moins une des places qu'elle implique n'est pas passée.

**(D) Un verrouillage `JOUR` posé automatiquement sur les journées passées.**
Écarté : un verrou est ce que l'utilisateur pose délibérément, jamais un
artefact technique ([`domaine.md`](../domaine.md#replanification-incrémentale)),
il n'épingle jamais une place vide, et il ne dit rien du reproche.

## Décision

### Ce qui est passé

Une place est passée si la date de son créneau est **strictement avant
aujourd'hui**, quelle que soit l'heure ; ou si c'est **aujourd'hui** et que
le **début effectif de la place** (celui du segment, pas du créneau, sur la
date du créneau) est atteint ou dépassé. Un créneau à cheval sur minuit
appartient à sa date de début : la nuit d'hier est passée à 1 h du matin, la
nuit de ce soir est à venir jusqu'à 20 h. Le reste d'aujourd'hui reste
mobile.

« Aujourd'hui » et « maintenant » sont ceux de `JourJClock` : l'horloge de la
machine en production, où la valeur simulée est ignorée à la lecture comme à
l'écriture (`SimulatedClockPermission`), et la date figée par
`PUT /api/debug/date-du-jour` sous `quarkus:dev` ou avec
`HORLOGE_SIMULEE_AUTORISEE=true`. Le solveur était jusqu'ici exclu de cette
horloge par principe ; il y entre parce que la garde est sur la lecture, si
bien qu'une instance de production ne peut pas figer autre chose que la
vraie date, et parce que c'est cette horloge qui rend la règle **répétable
avant l'événement** — un serveur de recette, une date posée à l'intérieur de
l'édition, trois résolutions, et l'on constate que les journées passées n'ont
pas bougé.

### Ce que devient une place passée

Dans `ProblemBuilder`, pour la résolution complète (réamorcée ou à froid)
comme pour l'incrémentale, **avant** toute autre règle :

- elle reçoit le titulaire que le plan enregistré lui donnait, par le même
  rapprochement positionnel stand × créneau que les verrous et la
  replanification ;
- ce titulaire est **gardé** même s'il a depuis déclaré la journée
  indisponible, même si une indisponibilité forcée le couvre maintenant, même
  s'il est supprimé du référentiel — dans ce dernier cas la place reste vide ;
- elle est **épinglée**, vide ou non. Timefold accepte une entité épinglée
  dont la variable est `null` (`allowsUnassigned = true`), et un test le
  prouve ;
- elle porte le drapeau `passe`, un fait, distinct de `verrouille` qui reste
  ce que les verrous et le gel incrémental posent ;
- elle n'est ni « libérée parce qu'invalidée » ni rouverte par le périmètre :
  `ReplanificationScope.release` refuse une place passée ;
- elle est comptée à part, `postesPasses`, sur `ProblemeReamorce`,
  `StatistiquesIncremental`, le résultat du job et l'écran Solveur.

Un problème envoyé dans le corps d'une requête est **épinglé** sur son passé
mais pas réamorcé : le serveur ne connaît pas le point de départ de
l'appelant. La préparation commune à toute analyse (`prepareProblem`) marque
le passé sans l'épingler, ce qui suffit aux diagnostics du plan enregistré :
ils lisent le même fournisseur de contraintes et disent donc la même chose
qu'un solve.

**Un seul horizon par problème.** L'horloge est lue une fois, en un seul
instant (`JourJClock.dateTime()`, jamais `today()` puis `now()` — les deux
lectures pourraient enjamber minuit), au moment où le problème est
construit, et le problème la garde (`PlanningEvenement.pastHorizon`). La
préparation qui précède la résolution marque contre cet horizon-là, pas
contre l'horloge du moment : une place dont le début tombe entre la
construction et la résolution — rouverte par le périmètre, ou vide sur un
départ à froid — resterait sinon épinglée vide, sans avoir été réamorcée et
sans que son trou soit reproché. Un problème arrivé sans horizon (le corps
d'une requête, le plan enregistré d'une analyse) reçoit celui de la
première préparation, et le garde de même. Les filtres de mouvements qui
cherchent des places vides (`HoleNeighbourPosteFilter`,
`UnassignedPosteFilter`) ignorent une place épinglée : un trou du passé
n'est pas un trou à combler, et le mémo du premier resterait sinon
définitivement non vide.

### Compté, non reproché

Une règle uniforme, appliquée contrainte par contrainte : **une
correspondance est facturée seulement si au moins une des places qu'elle
implique n'est pas passée.** Sa forme suit celle de la règle.

| Contrainte | Forme | Lecture du passé |
| --- | --- | --- |
| `posteDoitEtrePourvu` | par place | un trou passé n'est pas facturé |
| `animateurDisponible`, `standReserveAuxMajeurs`, `travailDeNuitInterditPourMineur`, `travailInterditJourFerieMineur`, `mineurNecessiteEncadrementMajeur`, `experienceRequisePourStandsPremium`, `appreciationIncompatible`, `souhaitsIncompatibles`, `stabiliteDuPlanPublie`, `indisponibiliteForcee` | par place | une place passée est ignorée |
| `pasDeChevauchementHoraire`, `reposQuotidienMinimal`, `pauseMinimaleEntreVacations`, `eviterChangementEmplacementEloigne`, `eviterEnchainementStandsEpuisants`, `incompatibiliteAdHoc`, `affiniteAdHoc` (récompense) | par paire | facturée sauf si les deux places sont passées ; hier soir compte contre demain matin |
| `dureeQuotidienneMaxMajeur`, `dureeQuotidienneMaxMineur`, `travailContinuMaxMajeur`, `travailContinuMaxMineur`, `coupureRepasObligatoire`, `coupureRepasPlacementPrefere`, `pauseSurPosteSansRelais` | par animateur et jour, liste | mesurée sur toutes les places, facturée si le jour tient une place à venir |
| `dureeHebdomadaireMax`, `dureeHebdomadaireMaxMineur`, `maxJoursTravaillesParSemaine`, `reposHebdomadaireMineur` | par animateur et semaine, agrégat | agrégat sur toutes les places, facturé si la semaine tient une place à venir (compte des places à venir replié dans le groupe, `PastSeats.withAhead`) |
| `reposHebdomadaireMinimal` | par animateur, toutes semaines | les occupations sont celles de toutes les places, seules les semaines tenant une place à venir portent leur déficit |
| `dureeHebdomadaireMaxDeuxSemaines` | par animateur, paires de semaines | une paire de semaines pleines est facturée si l'une des deux tient une place à venir |
| `maxJoursConsecutifsTravailles` | par animateur, séries de jours | une série entièrement passée n'est pas facturée ; une série qui atteint demain l'est, ses jours passés comptés |
| `eviterFermeturePuisOuverture` | par nuit entre deux journées | facturée si l'une des deux journées tient une place à venir |
| `plafondCreneauxParTypologie` | par animateur et typologie sur l'édition | les places passées comptent, facturé tant que l'animateur tient une place à venir **de cette typologie** |
| `limiterTypologiesDistinctesParAnimateur` | par animateur sur l'édition | les places passées comptent, facturé tant que l'animateur tient une place à venir |
| `limiterEmplacementsParJour` | par animateur et jour, ensemble | facturé si le jour tient une place à venir |
| `standComplexeAvecReferent` | par stand × créneau, places vides comprises | facturé si la ligne tient une place à venir, vide comprise |
| `repartitionMineursParCreneau`, `favoriserMixiteDesNiveaux` | par stand × créneau, places tenues | facturé si la ligne tient une place tenue à venir |
| `eviterRoulementStandsPremium` | par stand | les têtes déjà vues comptent, facturé si le stand tient une place à venir |
| `preserverBufferPolyvalents` | par créneau | facturé si le créneau tient une place à venir |
| `equilibrerCharge`, `equilibrerCreneauxPenibles` | global | les places passées pèsent dans l'équilibre, facturé tant qu'une place est à venir |
| `affectationForcee` | par fait | facturée tant qu'une place de sa portée est à venir |
| `animateurVerrouilleFige`, `animateurVerrouilleCreneauFige` | par verrou et place | une place passée est ignorée, épinglée ou non |

**Ce que ça coûte au solveur, et pourquoi presque rien.** Les règles par
place, par paire et par liste ajoutent un test sur un drapeau. Les règles
agrégées — équilibres de charge, quota par typologie, roulement premium,
plafonds hebdomadaires, jours par semaine, repos hebdomadaire des mineurs,
emplacements par jour, typologies par animateur, lignes stand × créneau,
buffer de polyvalents, « fermer tard puis ouvrir tôt » — **replient** la
question dans le groupe lui-même (`PastSeats.withAhead` : le collecteur de
la règle composé avec un compte des places à venir) : incrémental, O(1) par
mouvement, et le tuple s'imprime comme avant dans le diagnostic. Un
`ifExists` accroché à un tuple de groupe relit les places du groupe à
chaque changement du groupe, à l'aller et au retour, freeze coupé compris :
avec un tel nœud sur les deux équilibres globaux et neuf autres sur des
groupes à clé, la vitesse d'évaluation des mouvements mesurée sur
`festival-realiste-canicule.yaml` (`PlanningServiceScenarioFestivalRealisteTest`)
était tombée de 19 000-19 700 à 14 500-14 800 mouvements par seconde ;
repliée, elle est celle de la branche de base (19 400, une exécution de
chaque côté sur la même machine).
Le seul `ifExists` qui reste, sur `affectationForcee`, vaut par fait ad
hoc, et il n'y en a presque jamais.

Les analyses qui comptent hors du solveur (Pauses, Besoin, contrôle de
grille, Heures, Équité) continuent de **décrire** le passé, y compris ce qui
s'y est mal passé : elles disent ce qui a été fait, elles ne le reprochent
pas non plus.

### Les gestes manuels ne réécrivent plus le passé

Le premier état de cette décision laissait les gestes à la main — le
glisser-déposer des vues journalières, l'acceptation d'un échange, une
réparation appliquée ou suggérée — libres de réécrire une place passée, leur
simulation lisant un passé non reproché. Retenu depuis : **ils refusent**.
Une place passée est un fait, et un fait ne se corrige pas plus à la main
qu'au solveur ; un déplacement accepté sur hier aurait aussi produit, à la
publication suivante, le « votre emploi du temps a changé » sur une journée
derrière soi que la règle existe pour empêcher. Chaque écriture qui déplace
un animateur sur une place — `deplacer_affectation` et son écran,
`accepter_demande_echange`, `suggerer_reparations` et l'application d'une
réparation, ainsi que la simulation qui précède chacune — lit le même
`PastHorizon` et refuse d'une seule phrase : « Ce créneau est déjà commencé :
le passé ne se modifie plus » (`400` en REST, refus métier en MCP). Le
commutateur coupé, rien n'est refusé. Le mode jour J garde son périmètre —
les créneaux restants de la journée — et n'écrit plus le siège d'un créneau
en cours : l'absence y est enregistrée, le siège reste tel quel.

### Une résolution sans avenir est refusée

Une résolution dont toutes les places sont passées — l'édition est terminée,
ou la date simulée est après l'événement — produisait un plan vide à zéro
dur, que l'écran Contraintes lisait comme un succès. Retenu : elle est
**refusée**, complète comme incrémentale, avec « Rien à planifier : tous les
créneaux sont déjà commencés — l'édition est terminée, ou la date simulée est
après l'événement » ; le job porte la phrase en erreur, sans être compté
comme une panne. Quand des places passées sont restées vides — aucun
titulaire enregistré, parce que c'est le premier calcul de l'événement ou
que le titulaire a été supprimé — la résolution a lieu et son résultat porte
`postesPassesVides`, montré comme un avertissement sur la ligne du compte
rendu, sur l'écran Solveur et dans la vue MCP du job.

### Le commutateur

`planning.solver.passe-fige=${PASSE_FIGE:true}`, coupé sous `%test` : les
jeux de données de la suite sont datés dans le passé, et sans le commutateur
chaque test de résolution recevrait un problème entièrement épinglé. Les
tests de la règle l'allument par un `@TestProfile` et posent la date par
`PUT /api/debug/date-du-jour`. La CI de bout en bout le coupe aussi, pour
la même raison, tant que ses amorces ne sont pas datées dans l'avenir. En
exploitation, `PASSE_FIGE=false` se réserve à une recette qui rejoue une
édition ancienne.

## Conséquences

- Une résolution pendant l'événement ne touche plus une journée travaillée,
  et ne peut plus annoncer à quelqu'un un changement sur une journée derrière
  lui. Les journées passées sont identiques d'un solve à l'autre, octet pour
  octet.
- Le zéro dur se lit sur ce qui reste à jouer. L'écran Contraintes et
  `expliquer_echec_contraintes_dures` ne nomment plus une erreur d'hier ;
  elle reste visible dans les analyses descriptives.
- Un solve lancé pendant l'événement sur une édition **sans plan enregistré**
  laisse ses journées passées vides et épinglées : personne ne peut tenir
  hier, et ce n'est pas reproché.
- Sous `quarkus:dev` avec l'horloge réelle, un jeu d'exemple daté dans le
  passé est entièrement figé : poser la date du jour depuis la page Débogage
  lui rend son avenir. La page d'aide le dit.
- Les gestes manuels sur une place passée — glisser-déposer, échange,
  réparation, et leur simulation — sont refusés, dans les mêmes termes
  partout, tant que le commutateur est allumé.
- Une résolution dont toutes les places sont passées est refusée ; des
  places passées vides sont annoncées, jamais reprochées.
- **Un plancher medium pendant l'événement, assumé.** Les deux équilibres
  globaux mesurent l'édition entière et ne peuvent plus bouger que l'avenir :
  un écart déjà creusé ne se rattrape pas, et le score medium garde un
  plancher qu'aucune relance ne ramène à zéro. Il se lit d'un solve à
  l'autre, pas au zéro ([`contraintes.md`](../contraintes.md#compté-non-reproché--le-passé)).
- **Pas de couverture de bout en bout, assumé.** La CI de bout en bout coupe
  le commutateur : ses amorces sont datées derrière l'horloge réelle et
  l'application empaquetée refuse l'horloge simulée. Les redater dans
  l'avenir en gardant les âges des mineurs est un chantier à part ; la règle
  est couverte par `FrozenPastAcceptanceTest` sur l'horloge figée.
- `PosteAffectation` porte un drapeau de plus, jamais sérialisé : le contrat
  JSON ne bouge pas. `StatistiquesIncremental` et `ReamorcageEffectue`
  portent `postesPasses` et `postesPassesVides`.
