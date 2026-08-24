# Référentiel de contraintes

Trois niveaux, alignés sur le `HardMediumSoftScore` de Timefold :

- **DUR (hard)** — bloquant : un planning qui en viole une est invalide ;
- **MEDIUM** — fortement pénalisé mais non bloquant, signalé à l'organisateur ;
- **SOFT** — préférence, optimisée en dernier pour départager deux plannings valides.

**Ne jamais reclasser une contrainte dure en medium/soft sans validation explicite**, en
particulier tout ce qui touche au cadre légal des mineurs.

## Contraintes implémentées

Source : `solver/ConstraintCatalog.java` (description métier) et
`solver/constraints/*.java` (implémentation). Le catalogue est exposé par
`GET /api/constraints` et affiché sur la page « Constraints » de l'IHM.

### Dures — affectation (`AffectationConstraints`)

| Contrainte | Description |
| --- | --- |
| `posteDoitEtrePourvu` | Chaque place ouverte sur un stand doit être pourvue par un animateur |
| `animateurDisponible` | Pas d'affectation un jour déclaré indisponible |
| `pasDeChevauchementHoraire` | Un animateur ne tient jamais deux postes dont les créneaux se **chevauchent dans le temps** (le double poste sur un même créneau n'en est que le cas dégénéré) |

La compétence (rebaptisée **appréciation** côté frontend) n'est plus une
contrainte dure : voir `appreciationIncompatible` dans la table medium
ci-dessous.

#### Indisponibilité partielle d'un stand

Un stand fermé pour **une partie seulement** d'un créneau (ex. fermé de 14 h à
16 h dans un créneau 9 h-19 h) — ou, à l'inverse, normalement fermé et n'ouvrant
que sur des fenêtres précises (`Stand.ouvertures`, voir [`domaine.md`](domaine.md)
pour la règle des trois états par jour) — génère un poste par segment encore
ouvert, chacun portant une fenêtre horaire *effective*
(`PosteAffectation.heureDebutEffective`/`heureFinEffective`) plus étroite que
le créneau — `posteDoitEtrePourvu` s'applique alors à ces postes réduits, donc
personne n'est jamais exigé sur la plage fermée sans qu'une pénalité ne soit
créée pour autant (comme pour une fermeture totale).

Deux familles de contraintes lisent explicitement cette fenêtre effective
plutôt que celle, plus large, du créneau — les autres restent volontairement
calées sur le créneau entier :

- **`pasDeChevauchementHoraire`** compare les fenêtres effectives : deux postes
  du même animateur, sur deux segments d'un même créneau qui ne se recouvrent
  pas réellement dans le temps, ne sont pas signalés à tort comme un double
  emploi.
- **Les cumuls d'heures** (`dureeQuotidienneMaxMineur`, `dureeQuotidienneMaxMajeur`,
  `dureeHebdomadaireMax`, `dureeHebdomadaireMaxMineur`) et **le repos
  quotidien/entre-vacations** (`reposQuotidienMinimal`, `pauseMinimaleEntreVacations`)
  utilisent la durée effective du poste, pas celle du créneau : un poste
  réduit par une fermeture partielle ne compte que le temps réellement couvert
  dans les plafonds légaux, et le repos qui suit démarre à la fin de ce temps
  réel — jamais plus tard qu'avant #60 pour un poste sans fermeture (la fenêtre
  effective retombe alors sur celle du créneau).
- **`travailDeNuitInterditPourMineur`** reste volontairement calé sur la
  fenêtre **entière** du créneau, pas la fenêtre effective : une règle de
  sécurité pour mineurs ne doit jamais devenir *plus permissive* comme effet de
  bord d'une fonctionnalité de disponibilité de stand.

Ce mécanisme ne crée jamais de créneau supplémentaire : `poste_affectation.creneau_id`
reste une clé étrangère vers le créneau réel, la fenêtre effective vit sur le
poste. Voir `PlanningService.buildPostes` et `Creneau.segmentsOuvertsMinutes`.

### Dures — cadre légal mineurs (`LegalConstraints`)

Toutes ces règles sont dérivées de `dateNaissance` **à la date du créneau**,
jamais d'un booléen stocké.

| Contrainte | Article | Description |
| --- | --- | --- |
| `standReserveAuxMajeurs` | — (drapeau métier) | Aucun mineur sur un stand réservé aux majeurs |
| `travailDeNuitInterditPourMineur` | L3163-1 | Pas de créneau empiétant sur la nuit légale du mineur : **20 h-6 h avant 16 ans**, **22 h-6 h de 16 à 18 ans** |
| `dureeQuotidienneMaxMineur` | L3162-1, D4153-3 | Maximum **8 h** de travail effectif sur une même journée, **7 h avant 16 ans** |
| `dureeHebdomadaireMaxMineur` | L3162-1, D4153-3 | Maximum 35 h de travail effectif par semaine pour un mineur |
| `travailContinuMaxMineur` | L3162-3 | Aucune période de travail ininterrompue de plus de **4 h 30** ; au-delà, pause d'au moins **30 minutes consécutives** |
| `reposHebdomadaireMineur` | L3164-2 | **Deux jours de repos consécutifs** par semaine ISO |
| `travailInterditJourFerieMineur` | L3164-6 (liste : L3133-1) | Aucun mineur ne travaille un **jour férié légal** |

#### Trois régimes d'âge, pas deux

Le droit distingue **moins de 16 ans**, **16 à 18 ans** et **majeur**. Les deux
premiers sont dérivés de `dateNaissance` à la date du créneau
(`Animateur.isUnder16On(LocalDate)` / `isMineurOn(LocalDate)`), **jamais
stockés**.

| Sujet | Moins de 16 ans | 16 à 18 ans | Majeur |
| --- | --- | --- | --- |
| Travail de nuit | 20 h-6 h (L3163-1) | 22 h-6 h (L3163-1) | non encadré par l'outil (voir hors périmètre) |
| Durée quotidienne | 7 h (D4153-3) | 8 h (L3162-1) | 10 h (L3121-18) |
| Durée hebdomadaire | 35 h (L3162-1, D4153-3) | 35 h (L3162-1) | 48 h (L3121-20) |

Le plafond de 7 h/jour est appliqué à **tous** les moins de 16 ans, et pas
seulement pendant les vacances scolaires : l'emploi d'un moins de 16 ans est de
toute façon interdit hors vacances scolaires (art. L4153-1), donc le régime
« vacances scolaires » est le seul qui puisse légitimement se présenter ici.
L'application ne connaît aucun calendrier scolaire.

**Deux obligations relatives aux moins de 16 ans restent hors du solveur** et
doivent être vérifiées manuellement avant d'employer un animateur de cette
tranche : l'**autorisation de l'inspection du travail** (art. L4153-3,
D4153-2) et la règle du **repos continu au moins égal à la moitié de la durée
des vacances** (art. D4153-2). Ni l'une ni l'autre n'est modélisable à partir
des données dont dispose l'application.

`reserveMajeurs` est un **drapeau métier** : il ne présume pas d'une
interdiction légale. Si le stand relève des travaux réglementés interdits aux
moins de 18 ans (art. L4153-8 et D4153-15 et suivants
*[non vérifié — à faire valider]*), la restriction est obligatoire et ne doit
pas être levée ; si le stand n'est marqué que par confort d'organisation, la
lever relève de l'organisateur. Le modèle ne distingue pas les deux cas.

### Dures — cadre légal temps de travail (`LegalConstraints`)

| Contrainte | Article | Description |
| --- | --- | --- |
| `dureeHebdomadaireMax` | L3121-20 (ordre public) | Aucun animateur **majeur** (tous payés) ne dépasse la durée hebdomadaire maximale paramétrée (48 h par défaut) — voir `ParametresLegaux` dans [`domaine.md`](domaine.md) |
| `dureeQuotidienneMaxMajeur` | L3121-18 | Maximum **10 h** de travail effectif sur une même journée pour un majeur |
| `reposQuotidienMinimal` | L3131-1, L3164-1 | Entre deux journées travaillées : **11 h** consécutives pour un majeur, **12 h** pour un mineur, **14 h** avant 16 ans |
| `travailContinuMaxMajeur` | L3121-16 | Aucune période de travail ininterrompue de plus de **6 h** ; au-delà, pause d'au moins **20 minutes consécutives** |
| `maxJoursTravaillesParSemaine` | L3132-1 | Jamais plus de **6 jours travaillés** dans la même semaine ISO |
| `reposHebdomadaireMinimal` | L3132-2 + L3131-1 | **35 h consécutives** de repos dans chaque semaine ISO (24 h + les 11 h de repos quotidien) |
| `pauseMinimaleEntreVacations` | — (découpage automatique) | Entre deux vacations d'un même animateur le même jour, l'écart doit être d'au moins la pause minimale paramétrée (30 min par défaut) — empêche le découpage de reconstituer une journée continue en chaînant des vacations bout à bout |

#### Comment les pauses sont modélisées

Le modèle ne représente **aucune pause à l'intérieur d'un créneau** : une pause,
c'est le **trou entre deux créneaux d'un même animateur**. Deux créneaux séparés
par moins que la pause légale (20 min pour un majeur, 30 min pour un mineur) ne
sont donc pas considérés comme interrompus : ils forment une seule séquence de
travail, mesurée du premier début à la dernière fin — les micro-trous comptent
comme du travail, lecture volontairement protectrice.

Le regroupement se fait par animateur **et par date**. Une séquence qui
franchirait minuit est couverte par `reposQuotidienMinimal`, qui verrait un
écart quasi nul entre le jour J et le jour J+1.

**Choix d'interprétation à faire valider** : l'art. L3121-16 dit « dès que le
temps de travail quotidien *atteint* six heures ». La pause est ici traitée
comme due **au plus tard** à la sixième heure : une séquence peut donc
*atteindre* 6 h sans être pénalisée, elle ne peut pas les *dépasser*. Une
lecture plus stricte (toute journée atteignant 6 h exige qu'une pause de 20 min
existe, ce qui rendrait un créneau isolé de 6 h non conforme) est défendable et
**[à faire valider par un juriste]** ; elle se code en remplaçant le `>` par un
`>=` dans `travailContinuMaxMajeur`. L'art. L3162-3, lui, dit explicitement
« ne peut *excéder* quatre heures et demie » : aucune ambiguïté côté mineurs.

#### Jours fériés : périmètre assumé

La liste appliquée est celle de l'art. **L3133-1** (11 jours), calculée par
`JoursFeries` — dates fixes plus les fêtes mobiles dérivées de Pâques (lundi de
Pâques, Ascension, lundi de Pentecôte) par le comput grégorien.

- **Aucune dérogation sectorielle n'est implémentée.** L'art. **R3164-2** ouvre
  des dérogations dans des secteurs fixés par décret ; savoir si
  l'événementiel / l'animation en fait partie **n'a pas été établi**
  *[non vérifié — à faire valider ; à instruire par un juriste avant tout
  codage]*. Le comportement retenu est donc l'interdiction pure, sans
  dérogation et sans paramétrage possible : coder une dérogation sur une base
  non vérifiée serait pire que de ne pas la coder.
- **Métropole hors Alsace-Moselle.** Le Vendredi saint et le 26 décembre ne
  sont fériés que dans le Haut-Rhin, le Bas-Rhin et la Moselle. Le modèle ne
  porte aucune notion de région : ils sont volontairement exclus plutôt
  qu'appliqués partout. Les ajouter suppose d'abord une région sur le festival
  ou sur le stand.
- L'abolition de l'esclavage (départements d'outre-mer) est hors périmètre pour
  la même raison.

#### Repos hebdomadaire : conventions de calcul

Le repos hebdomadaire est évalué **par semaine ISO** (lundi 00 h → lundi
suivant 00 h), la même fenêtre que `Creneau.semaineIso()` et que les plafonds
hebdomadaires.

- Le temps libre **avant la première** et **après la dernière** affectation de
  la semaine compte, borné à la fenêtre. Une semaine que le festival ne couvre
  que partiellement est donc satisfaite par construction : l'animateur est
  réellement libre ces jours-là.
- **Approximation connue** : un repos à cheval sur la frontière
  dimanche/lundi est compté deux fois, tronqué dans chaque semaine, au lieu
  d'une fois en entier. Le résultat est donc **plus strict** que la loi, jamais
  plus laxiste.
- Pour les mineurs, les deux jours de repos sont comptés en **jours
  calendaires** de la semaine ISO, pas en heures : c'est la lecture littérale
  de l'art. L3164-2.

Les **dérogations conventionnelles** au repos hebdomadaire des mineurs (repos
ramené à 36 heures consécutives pour les jeunes libérés de l'obligation
scolaire) supposent un accord collectif étendu ou une autorisation de
l'inspection du travail. L'application ne connaît ni l'un ni l'autre et ne les
présume pas : le défaut sûr (deux jours consécutifs) s'applique toujours. Toute
dérogation devra être une **donnée saisie** avant d'être codée.

`reposQuotidienMineur` a été **supprimée** au profit de `reposQuotidienMinimal`.
Elle ne se déclenchait qu'après un créneau de nuit tenu par un mineur —
situation que `travailDeNuitInterditPourMineur` interdit déjà — et valait donc
zéro dans tout planning valide : une contrainte dure structurellement morte, qui
donnait l'illusion de couvrir le repos quotidien des mineurs. Elle ne mesurait
d'ailleurs aucune durée de repos, seulement une reprise « avant midi ».

### Dures — sécurité (`LegalConstraints`)

| Contrainte | Article | Description |
| --- | --- | --- |
| `mineurNecessiteEncadrementMajeur` | aucun — politique interne | Au moins un majeur sur le même stand et le même créneau qu'un mineur |

`mineurNecessiteEncadrementMajeur` est une **règle de sécurité posée par
l'organisateur, pas une obligation du Code du travail** : aucun article
n'impose la présence d'un majeur aux côtés d'un jeune travailleur sur son
poste. Elle est **maintenue en contrainte dure par choix**, et classée
« Sécurité (mineurs) » dans `ConstraintCatalog` pour ne pas laisser croire
qu'elle est facultative au même titre qu'une règle métier, ni qu'elle est
opposable au même titre qu'une règle légale.

### Dures — exceptions administrateur (`AdHocConstraints`)

| Contrainte | Description |
| --- | --- |
| `indisponibiliteForcee` | L'animateur ne doit jamais être affecté sur le périmètre visé |
| `incompatibiliteAdHoc` | Deux animateurs incompatibles ne travaillent jamais sur le même créneau |
| `affectationForcee` | L'animateur doit être présent sur le créneau ou le stand visé |

La famille compte aussi une contrainte **soft**, `affiniteAdHoc` — voir
« Soft — exceptions administrateur » plus bas. C'est la seule exception à la
règle « les contraintes ad hoc sont dures », et elle est voulue : une paire
privilégiée imposée en dur serait une affectation forcée déguisée.

### Dures — verrouillage du planning (`VerrouillageConstraints`)

| Contrainte | Description |
| --- | --- |
| `animateurVerrouilleFige` | Le planning d'un animateur verrouillé ne bouge plus : le solveur ne peut pas lui attribuer un poste supplémentaire |
| `animateurVerrouilleCreneauFige` | Un échange validé est figé sur son créneau : ce que chacun des deux animateurs y tient après l'échange ne bouge plus, sans geler le reste de leur planning |

Geler une **journée**, un **stand** ou un **créneau** ne demande aucune
contrainte : les places couvertes sont épinglées (`@PlanningPin` sur
`PosteAffectation.verrouille`) avant la résolution, donc aucun move ne peut les
toucher. Voir [`domaine.md`](domaine.md#verrouillage-partiel-du-planning) pour
le mécanisme complet.

Geler un **animateur** demande en plus cette contrainte : l'épinglage fige les
places qu'il tient déjà, mais laisserait le solveur lui en donner de nouvelles
ailleurs. Seules les places **non épinglées** sont pénalisées ; pénaliser ses
places validées mettrait une violation dure permanente dans tous les plannings.

`animateurVerrouilleCreneauFige` est la même mécanique restreinte à **un seul
créneau** : le verrou `ANIMATEUR_CRENEAU`, posé quand l'admin valide une
demande d'échange (issue #165), épingle la place que l'animateur tient sur ce
créneau et interdit de lui en donner une autre au même moment — y compris à
l'animateur libéré par une reprise simple, qui n'a plus de place à épingler
mais ne doit pas y être réaffecté par la régénération suivante.

Interaction avec les autres contraintes : une place épinglée est **scorée
normalement**. Un verrou peut donc laisser une violation visible (par exemple
un dépassement d'heures figé par l'utilisateur) plutôt que de désactiver
silencieusement les règles autour de lui.

### Medium — qualité d'organisation (`QualiteConstraints`)

| Contrainte | Description |
| --- | --- |
| `standComplexeAvecReferent` | Au moins un référent par stand et par créneau |
| `equilibrerCharge` | Répartition équitable de la charge entre animateurs |
| `repartitionMineursParCreneau` | Pas plus de mineurs que de majeurs sur un stand et un créneau |
| `experienceRequisePourStandsPremium` | Un stand premium ne devrait pas être tenu par un débutant |
| `eviterRoulementStandsPremium` | Sur un stand premium, limiter le nombre d'animateurs **différents** qui s'y relaient au-delà d'un équipage (`max(1, effectifMin)`), et non le nombre de paires de postes — voir la note ci-dessous |
| `eviterChangementEmplacementEloigne` | Entre deux créneaux consécutifs, éviter de basculer un animateur vers un stand dont l'emplacement est éloigné (> 300 m) |
| `limiterEmplacementsParJour` | Sur une même journée, limiter le nombre d'**emplacements distincts** visités par un animateur (plafond `planning.contraintes.max-emplacements-par-jour`, 3 par défaut ; pénalité proportionnelle au dépassement) — voir la note ci-dessous |
| `eviterEnchainementStandsEpuisants` | Entre deux créneaux consécutifs, éviter d'enchaîner un animateur sur deux stands physiquement épuisants (`Stand.niveauEffort = EPUISANT`) sans repos ni stand plus facile entre les deux |
| `appreciationIncompatible` | L'appréciation de l'administrateur (`Animateur.competences`) ne couvre aucune typologie proposée par le stand — ex-contrainte dure `competenceCompatible`, assouplie car il s'agit d'une appréciation métier faite après formation, pas d'une qualification objective |
| `souhaitsIncompatibles` | Aucune des typologies proposées par le stand ne figure dans les souhaits déclarés de l'animateur (`Animateur.souhaits`) |
| `limiterTypologiesDistinctesParAnimateur` | Un animateur devrait idéalement intervenir sur une ou deux typologies de jeu distinctes sur l'ensemble du planning (au-delà de 2, pénalité proportionnelle au dépassement) |
| `maxJoursConsecutifsTravailles` | Un animateur ne devrait pas travailler plus de six jours consécutifs sans au moins un jour de repos — moins est possible, plus ne devrait pas l'être (pénalité proportionnelle au dépassement) |

#### `limiterEmplacementsParJour` : des zones distinctes, pas des transitions

`eviterChangementEmplacementEloigne` ne voit qu'une **paire de créneaux
consécutifs** à la fois, et seulement au-delà de 300 m : dix allers-retours
entre deux zones voisines ne lui coûtent rien, alors que la journée de
l'animateur est bel et bien passée à se déplacer. Cette règle plafonne donc le
nombre d'emplacements distincts couverts dans la **journée**, indépendamment
des distances.

Elle compte des **zones distinctes** et non des transitions : A → B → A vaut
deux zones, pas deux déplacements. C'est plus simple à expliquer, et cela évite
de payer deux fois un aller-retour.

**Zone = `Emplacement`** : le modèle n'a pas d'autre notion de zone, et en
introduire une (un regroupement d'emplacements) serait une évolution du modèle,
hors périmètre ici.

**Pas de double pénalisation** avec `eviterChangementEmplacementEloigne` : les
deux règles ne regardent pas le même fait. Un unique déplacement éloigné entre
deux zones dans la journée est facturé par la première et **pas** par
celle-ci, qui ne se déclenche qu'au-delà du plafond
(`QualiteConstraintsTest#unSeulDeplacementEloigneNEstPasPenaliseDeuxFois`).

**Plafond réglable** : `planning.contraintes.max-emplacements-par-jour` (3 par
défaut), porté au solveur par le fait de planification `ParametresQualite`
comme `ParametresLegaux` l'est pour les règles légales. Contrairement à ces
dernières, il n'est pas stocké par édition : il règle le confort, pas la loi.
Une valeur très grande neutralise la règle sans la désactiver.

**Inerte sans emplacements renseignés** (voir #118) : un poste dont le stand n'a
pas d'emplacement est filtré, donc rien n'est compté — plutôt que de faire
compter tout le monde pour une même zone fictive.

#### `eviterRoulementStandsPremium` : des têtes, pas des paires

La règle compte le nombre d'animateurs **distincts** passés sur un stand
premium, au-delà d'un équipage (`max(1, effectifMin)`, soit exactement le
nombre de places que la génération de postes crée par créneau). Un stand à
deux places tenu par les deux mêmes personnes du début à la fin ne coûte rien,
quel que soit le nombre de créneaux ; chaque tête supplémentaire coûte un
point.

Elle comptait auparavant les **paires** de postes du même stand tenues par des
animateurs différents sur des créneaux différents, ce qui est quadratique en
nombre de postes par stand et devient ingérable dès qu'un festival marque plus
d'une poignée de stands premium. Mesuré sur les données réelles 2026 (45 stands
premium sur 65, 3 502 postes) : 48 567 paires possibles, une pénalité de 38 591
sur un score medium total de 46 284 — 84 % de celui-ci — dont environ 25 000
**structurellement inatteignables**, un stand ouvert douze jours ne pouvant pas
légalement être tenu par une seule personne (48 h/semaine, 11 h de repos
quotidien, 6 jours par semaine). Le solveur dépensait donc son budget à
descendre une pente qui s'arrête très au-dessus de zéro, pendant que 36 postes
restaient non pourvus. Compter les têtes conserve l'intention (la continuité
reste récompensée de façon monotone), ramène la contrainte à l'ordre de
grandeur des autres règles medium, et supprime au passage une jointure
quadratique maintenue à chaque mouvement du solveur.

`maxJoursConsecutifsTravailles` n'est adossée à aucun article identifié du
Code du travail : `maxJoursTravaillesParSemaine` (dur, art. L3132-1) borne
déjà le nombre de jours travaillés à l'intérieur de chaque semaine ISO, mais
une série peut chevaucher deux semaines (ex. jeu-ven-sam-dim-lun-mar-mer : six
jours dans chacune des deux semaines ISO concernées, mais sept d'affilée)
sans déclencher la contrainte dure. `maxJoursConsecutifsTravailles` capture
directement cette série glissante, indépendamment du découpage en semaines,
mais reste classée qualité d'organisation (medium) tant que sa base légale
n'est pas établie. Son poids est surchargé à 5 dans `application.properties`
(contre 1 pour la plupart des autres contraintes medium), pour que le solveur
l'élimine en priorité sur le reste de la famille « Qualité d'organisation ».

`appreciationIncompatible` et `souhaitsIncompatibles` sont volontairement deux
contraintes medium séparées, pas une seule agrégée : ça permet de les
activer/pondérer indépendamment. Le poids par défaut de
`appreciationIncompatible` est surchargé à 3 dans `application.properties`
(contre 1 pour `souhaitsIncompatibles`), pour que le solveur élimine toujours
en priorité un écart d'appréciation avant d'optimiser la satisfaction des
souhaits — voir « Pondérer une contrainte » plus bas.

### Soft — préférences (`PreferenceConstraints`)

| Contrainte | Description |
| --- | --- |
| `favoriserMixiteDesNiveaux` | Associer un débutant à un référent pour la montée en compétence |
| `equilibrerCreneauxPenibles` | Répartir équitablement entre animateurs les créneaux « pénibles » (stands épuisants ou premium) |
| `preserverBufferPolyvalents` | Garder au moins un animateur polyvalent (porteur de la typologie « ninja ») libre sur chaque créneau, pour pouvoir réparer le planning en cas d'absence de dernière minute |

### Soft — exceptions administrateur (`AdHocConstraints`)

| Contrainte | Description |
| --- | --- |
| `affiniteAdHoc` | Paire d'animateurs à privilégier : chaque créneau où les deux tiennent le même stand est récompensé |

`affiniteAdHoc` (issue #80) est le pendant positif d'`incompatibiliteAdHoc` :
une contrainte ad hoc de type `AFFINITE` déclare « ces deux-là fonctionnent
bien ensemble, mettez-les sur le même stand quand c'est possible ». Deux choix
de conception, tous deux délibérés :

- **Soft obligatoirement.** En dur, une paire privilégiée deviendrait une
  affectation forcée déguisée et entrerait en collision frontale avec
  `equilibrerCharge`, `repartitionMineursParCreneau` et la disponibilité
  individuelle.
- **Récompense, pas pénalité.** Pénaliser l'absence de la paire reviendrait à
  punir tous les créneaux où l'un des deux ne travaille pas — un bruit
  permanent dans le score. Seule la co-affectation effective (même créneau,
  même stand, dans le périmètre créneau/stand optionnel de la contrainte)
  rapporte un point soft.

Le schéma de saisie est celui d'`incompatibiliteAdHoc`
(`ContrainteAdHoc.animateursConcernes`, portée optionnelle par créneau et par
stand) : ni migration de schéma ni nouveau modèle. Une même paire déclarée à la
fois `INCOMPATIBILITE` et `AFFINITE` est refusée à la saisie avec un message
explicite (`ReferenceDataService.createContrainteAdHoc`), plutôt que
silencieusement arbitrée par le score.

#### Typologie « ninja » et buffer de polyvalents

Une seule typologie du référentiel peut être marquée **ninja** (case à cocher
« Typologie ninja » sur la page Typologies, colonne `typologie.ninja` en base,
unicité garantie par un index unique partiel — migration V30). Un animateur qui
possède cette typologie dans ses compétences est dit **polyvalent** : il sait
s'adapter, donc

- `Animateur.hasCompetenceFor(stand)` renvoie vrai pour **n'importe quel**
  stand — il n'est jamais pénalisé par `appreciationIncompatible` ;
- il est exclu de `limiterTypologiesDistinctesParAnimateur` : le disperser sur
  plusieurs typologies est précisément sa raison d'être ;
- `preserverBufferPolyvalents` pénalise chaque créneau où il ne reste aucun
  polyvalent libre (pénalité = déficit par rapport au minimum, aujourd'hui 1).

Cette dernière contrainte est **volontairement en tension** avec
`equilibrerCharge` (medium) : un polyvalent laissé libre pour rester en réserve
déséquilibre mécaniquement la charge. L'arbitrage est assumé par les niveaux —
soft contre medium, donc l'équilibrage l'emporte sauf à égalité par ailleurs.
Tant qu'aucune typologie n'est marquée ninja, personne n'est polyvalent et la
contrainte ne coûte rien (elle ne pénalise jamais un planning « sans ninja »).

> **Retirée : `favoriserRotationDesStands`.** Elle pénalisait chaque paire de
> postes tenus par le même animateur sur le même stand, pour favoriser la
> rotation. Supprimée parce qu'elle entrait en conflit avec deux règles de
> niveau supérieur qui, elles, poussent à la stabilité :
> `eviterRoulementStandsPremium` (privilégier la continuité sur un stand
> premium) et `limiterTypologiesDistinctesParAnimateur` (rester sur une ou deux
> typologies). Le niveau MEDIUM l'emportant sur le SOFT, elle ne faisait de
> toute façon que du bruit dans le score. Plus rien n'exprime aujourd'hui de
> préférence pour la variété des stands : si le besoin revient, le rétablir
> suppose d'abord d'arbitrer contre ces deux règles.

## Activer / désactiver une contrainte

Chaque contrainte peut être activée ou désactivée individuellement depuis la page
« Constraints » de l'IHM (un interrupteur par carte), pour le prochain solve
uniquement — la désactivation ne modifie ni la pondération ni la logique de la
règle, elle empêche simplement le stream de produire des matches. Toutes les
contraintes sont actives par défaut.

- Persistance : table `constraint_toggle` (présence d'une ligne = désactivée,
  absence = active) via `ParametresRepository`/`ParametresService`.
- API : `GET /api/constraints` renvoie `actif` pour chaque contrainte,
  `PUT /api/constraints/{name}` bascule l'état. Pour les règles légales et de
  sécurité, l'IHM confirme d'abord (voir ci-dessous) ; l'API, elle, ne demande
  rien — un client MCP ou un `curl` désactive sans passer par la modale.
- Solveur : l'état désactivé est injecté comme fait de planification
  `ConstraintToggle` sur `PlanningFestival` (voir `PlanningService.prepareProblem`),
  et chaque contrainte le consulte via `ConstraintToggleSupport.actif(stream, "nom")`,
  appelé juste après le `forEach` initial (sur le flux le plus étroit possible :
  une contrainte pilotée par `ContrainteAdHoc` y branche le toggle sur les
  quelques faits ad hoc, pas sur les milliers de postes).
### Désactiver une règle légale : confirmation, et rien d'autre

Désactiver une règle qui fonde le planning en droit n'est pas un réglage comme
un autre : le solveur peut alors produire un planning **contraire au Code du
travail tout en affichant un score dur à zéro**, et rien dans le score ne le
signale.

Trois catégories sont concernées, listées dans
`ConstraintCatalog.CATEGORIES_PROTEGEES` et exposées par le champ `protegee` de
`GET /api/constraints` : **« Légal (mineurs) »**, **« Légal (temps de
travail) »** et **« Sécurité (mineurs) »** — cette dernière n'est pas une
obligation du Code du travail mais une règle de sécurité posée par
l'organisateur, et la lever engage tout autant. Les 24 autres règles se
décochent sans friction : elles arbitrent du confort, et un planning qui en
ignore une est seulement moins bon.

Pour ces trois catégories, l'IHM demande une **confirmation** (modale de
danger, `LegalDisableDialog`) qui nomme la règle, rappelle l'article qui la
fonde — les descriptions du catalogue le citent — et reprend la formule de la
page `/conditions-utilisation` : **l'organisateur reste l'employeur et le
responsable** du planning produit et diffusé ; l'application est une aide à la
décision, jamais une validation juridique. Réactiver une règle ne demande
rien : remettre une règle légale ne mérite aucune cérémonie.

**Confirmation seule, décision assumée** : pas de motif saisi, pas de
journalisation, pas de colonne pour tracer l'auteur. La traçabilité de V16
(`motif`, `modifie_par_utilisateur_id`, `modifie_le`) a été retirée par V39
précisément parce qu'elle n'était pas imputable — sans notion d'utilisateur,
l'auteur ne pouvait valoir que la constante `ui` ou `mcp` — et elle n'est pas
réintroduite ici. Ce qui reste visible, c'est **l'état** : l'écran Contraintes
montre en permanence ce qui est désactivé, et le bandeau partagé
(`ProblemesStore.reglesLegalesDesactivees`, lu par les pages Contraintes et
Solveur) le répète partout où un solve se lance ou se juge.

Une vraie piste d'audit reste donc à faire, et suppose d'abord une notion
d'utilisateur : un journal (une ligne par changement, jamais supprimée) plutôt
qu'une table d'état, et le cas échéant un blocage — qui suppose de décider qui
a le droit de lever une règle légale.

- `ConstraintToggleTest` couvre le mécanisme lui-même, une contrainte
  représentative par famille : le même jeu de données doit être pénalisé sans
  `ConstraintToggle` et valoir exactement zéro avec. **Une contrainte oubliée
  par `ConstraintToggleSupport.actif` affiche un interrupteur sans effet dans
  l'IHM** — c'est exactement ce qui était arrivé à
  `eviterChangementEmplacementEloigne`.

## Pondérer une contrainte

Chaque contrainte pénalise avec un poids littéral (`HardMediumSoftScore.ONE_HARD` /
`ONE_MEDIUM` / `ONE_SOFT`) dans son code — c'est la valeur par défaut, celle qui
s'applique tant qu'aucune configuration ne la modifie.

Ce défaut est surchageable sans toucher au code Java, via le mécanisme natif
Timefold `ConstraintWeightOverrides` : `PlanningFestival` porte un champ
`ponderationsContraintes` (jamais exposé par l'API — `@JsonIgnore` — car
`PlanningService.prepareProblem` le renseigne systématiquement juste avant
chaque solve).

Deux niveaux, dans cet ordre :

1. **Le déploiement** — une propriété par contrainte,
   `planning.constraint-weights.<nomDeLaContrainte>=<entier>`, listées (à 1,
   c'est-à-dire le poids littéral du code) dans `application.properties`.
   C'est la valeur de référence, celle avec laquelle le solveur a été réglé,
   et elle s'applique à **toutes** les éditions.
2. **L'édition** — table `ponderation_contrainte` (migration V53), une ligne
   par `(edition_id, nom)`. Même convention que `constraint_toggle` : pas de
   ligne, pas de surcharge. Ce que l'édition enregistre l'emporte, sans rien
   changer pour les autres éditions ni pour la valeur par défaut du
   déploiement.

- Câblage : `PlanningService` lit les propriétés au démarrage
  (`readConfiguredWeights`) et les surcharges de l'édition **à chaque solve**
  (`effectiveConstraintWeights`, via `ReferenceData.getConstraintWeights`) —
  jamais en cache, puisque deux éditions résolues par le même processus ne
  doivent pas hériter du dosage l'une de l'autre. Le
  `ConstraintWeightOverrides` est construit à partir de `ConstraintCatalog`,
  qui fournit le niveau (dur/medium/soft) de chaque nom de contrainte.
- IHM : la page « Contraintes » affiche le poids de chaque règle et permet de
  le modifier (`PUT /api/constraints/{name}/poids`, voir
  [`api.md`](api.md#contraintes)). Les valeurs admises vont de 1 à 100 ; `0`
  est refusé, parce qu'une règle pesée zéro serait éteinte en fait tout en
  s'affichant active — éteindre une règle passe par son interrupteur.
- Transport : un scénario YAML peut épingler ses poids comme il épingle ses
  toggles (section `contraintes:`, voir
  [`import-export.md`](import-export.md)).

### Ce qui se dose et ce qui ne se dose pas

Sur les 39 contraintes, celles qui varient réellement d'un organisateur à
l'autre sont les **MEDIUM de « Qualité d'organisation »**
(`ConstraintDefinition.dosable()`) : elles arbitrent du confort contre du
confort — la continuité sur un stand premium contre l'équilibre des charges,
les souhaits contre l'expérience — et chaque organisateur les classe
différemment. Ce sont celles-là qui se **dosent** : la règle dont on se moque
descend à 1 et se fait battre, celle qui compte monte.

Les autres familles ne se dosent pas dans le même sens : une contrainte dure
est respectée ou le planning est invalide, son poids ne change que la vitesse
de convergence. Repondérer une règle légale ne la rend ni plus ni moins
obligatoire — l'IHM ne la présente donc pas comme un curseur.

`equilibrerCharge` (voir tableau ci-dessus) est un cas particulier : son
`matchWeigher` met `loadBalance().unfairness()` (un `BigDecimal` typiquement
inférieur à 1) à l'échelle ×10 avant de tronquer en `int`, sans quoi tout
déséquilibre modéré tronquait exactement à 0 et le solveur n'avait aucun
gradient à suivre pour améliorer l'équité. Ce facteur ×10 est lui-même un choix
de pondération (il rapproche l'ordre de grandeur d'`equilibrerCharge` de celui
des autres contraintes medium, qui valent 1 point par violation) — à ajuster
via `planning.constraint-weights.equilibrerCharge` plutôt qu'en changeant le
facteur d'échelle si le besoin est « plus/moins d'importance », et en changeant
le facteur d'échelle (`UNFAIRNESS_SCALE` dans `QualiteConstraints`) seulement
si le besoin est « plus/moins de granularité ».

`equilibrerCreneauxPenibles` (issue #79) applique la même technique de mise à
l'échelle (sa propre constante `UNFAIRNESS_SCALE`, dans `PreferenceConstraints`),
mais sur le sous-ensemble des postes dont le stand est épuisant
(`niveauEffort = EPUISANT`, voir `NiveauEffort` dans
[`domaine.md`](domaine.md)) ou premium — pas sur tous les postes comme
`equilibrerCharge`.

## Hors périmètre assumé

Ces obligations sont réelles mais **volontairement non implémentées** dans le
solveur. Elles sont listées ici pour que leur absence soit un choix écrit, pas
un oubli — c'est le sens du constat D3 de l'audit
([`audit-conformite-rh.md`](audit-conformite-rh.md)).

### Durée hebdomadaire moyenne de 44 h sur 12 semaines (art. L3121-22)

*« La durée hebdomadaire de travail calculée sur une période quelconque de
douze semaines consécutives ne peut dépasser quarante-quatre heures […] »*
(art. L3121-22, vérifié ; repris par la CCN ÉCLAT art. 5.2 *[non vérifié]*).

Le festival dure 15 jours, soit 2 à 3 semaines ISO. **Le solveur ne peut pas
calculer cette moyenne** : il ne connaît ni les 9 semaines précédentes ni les
suivantes. C'est une limite structurelle du périmètre, pas un manque
d'implémentation. Le contrôle relève du service RH, à partir du cumul par
animateur et par semaine déjà exposé par la page « Heures »
(`PlanningHoursService`) et les exports.

### Travail de nuit des majeurs (art. L3122-1 et suivants)

Aucune règle ne s'applique aux majeurs travaillant la nuit : `chevaucheNuit()`
n'est consulté que par les contraintes mineurs. Or les scénarios livrés
comportent un créneau nocturne quotidien.

La qualification de « travailleur de nuit » dépend d'un seuil d'heures et
d'une régularité, mais aussi du contrat, d'un accord collectif ou d'une
autorisation de l'inspection du travail — des faits que l'application ne
détient pas. La numérotation exacte des articles applicables reste par ailleurs
**[non vérifiée — à confirmer article par article]**. Le sujet doit donc être
**instruit avec un juriste avant toute implémentation** ; d'ici là, le travail
de nuit des majeurs n'est **pas** encadré par l'outil et relève d'un contrôle
manuel.

### Autres points signalés par l'audit et non couverts

| Sujet | Pourquoi c'est hors périmètre |
| --- | --- |
| Heures supplémentaires, contingent annuel (L3121-30) | Hors d'un solveur d'affectation ; le planning produit est l'assiette du décompte, pas le décompte |
| Nature des indisponibilités | `joursIndisponibles` est un `Set<LocalDate>` non typé : impossible de distinguer un repos légal accordé, un congé payé et une indisponibilité de convenance |
| Autorisation d'inspection du travail pour les moins de 16 ans (L4153-3, D4153-2) | Donnée administrative absente du modèle ; signalée en documentation, à vérifier manuellement |
| Dérogation sectorielle aux jours fériés (R3164-2) | Non instruite ; le défaut le plus protecteur s'applique (voir plus haut) |

## Ajouter une contrainte

1. Implémenter la règle dans la classe de `solver/constraints/` correspondant à sa
   famille (ou en créer une nouvelle si la famille n'existe pas), une méthode
   privée par contrainte. Enrober le stream initial avec
   `ConstraintToggleSupport.actif(constraintFactory.forEach(...), "nomDeLaContrainte")`
   pour qu'elle soit désactivable depuis l'IHM comme les autres.
2. L'enregistrer dans le tableau retourné par
   `PlanningConstraintProvider.defineConstraints`.
3. Ajouter sa description métier dans `ConstraintCatalog` (niveau + catégorie +
   libellé) — c'est ce qui alimente l'IHM. `ConstraintCatalogTest` échoue si le
   catalogue et le provider divergent, dans un sens comme dans l'autre.
4. Écrire les tests :
   - un test unitaire isolé dans la classe `*ConstraintsTest` de la famille
     (via le `ConstraintVerifier`), avec au moins un cas pénalisé et un cas
     valide — c'est ce qui garantit que la contrainte pénalise exactement ce
     qu'elle doit ;
   - pour une contrainte **dure**, s'assurer en plus que
     `PlanningHardConstraintsTest` (score `hardScore()` à zéro sur le scénario
     nominal) passe toujours.

   **Une contrainte n'est pas terminée tant que ces tests ne passent pas.**

### Écrire une contrainte qui ne coûte pas cher

Le coût d'une contrainte se mesure au nombre de tuples que son stream construit
et maintient à chaque mouvement du solveur, pas à sa longueur. Deux réflexes :

- **Restreindre avant de joindre.** Un `filter` placé après un
  `forEachUniquePair` a déjà payé la construction de toutes les paires. Filtrer
  d'abord (`forEach(...).filter(...).join(...)`) réduit la combinatoire à la
  source.
- **Préférer `Joiners.equal` / `lessThan` / `overlapping` à
  `Joiners.filtering`.** Un joiner indexé est un accès par hachage ; un
  `filtering` est un prédicat évalué sur chaque combinaison. `Joiners.lessThan`
  sur le `@PlanningId` reproduit exactement la sémantique « chaque paire une
  seule fois » de `forEachUniquePair` tout en autorisant un flux d'entrée déjà
  filtré.

Chiffres mesurés et méthode de vérification dans
[`developpement.md`](developpement.md) (section « Réglage du solveur »).
