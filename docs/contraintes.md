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
| `competenceCompatible` | L'animateur maîtrise au moins une typologie proposée par le stand |
| `pasDeChevauchementHoraire` | Un animateur ne tient jamais deux postes dont les créneaux se **chevauchent dans le temps** (le double poste sur un même créneau n'en est que le cas dégénéré) |

#### Indisponibilité partielle d'un stand

Un stand fermé pour **une partie seulement** d'un créneau (ex. fermé de 14 h à
16 h dans un créneau 9 h-19 h, voir [`domaine.md`](domaine.md)) génère un poste
par segment encore ouvert, chacun portant une fenêtre horaire *effective*
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
poste. Voir `PlanningService.construirePostes` et `Creneau.segmentsOuvertsMinutes`.

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
(`Animateur.estMoinsDe16AnsLe(LocalDate)` / `estMineurLe(LocalDate)`), **jamais
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

### Medium — qualité d'organisation (`QualiteConstraints`)

| Contrainte | Description |
| --- | --- |
| `standComplexeAvecReferent` | Au moins un référent par stand et par créneau |
| `equilibrerCharge` | Répartition équitable de la charge entre animateurs |
| `repartitionMineursParCreneau` | Pas plus de mineurs que de majeurs sur un stand et un créneau |
| `experienceRequisePourStandsPremium` | Un stand premium ne devrait pas être tenu par un débutant |
| `eviterRoulementStandsPremium` | Sur un stand premium, éviter de faire tourner plusieurs animateurs différents |
| `eviterChangementEmplacementEloigne` | Entre deux créneaux consécutifs, éviter de basculer un animateur vers un stand dont l'emplacement est éloigné (> 300 m) |

### Soft — préférences (`PreferenceConstraints`)

| Contrainte | Description |
| --- | --- |
| `favoriserRotationDesStands` | Éviter de réaffecter le même animateur au même stand |
| `favoriserMixiteDesNiveaux` | Associer un débutant à un référent pour la montée en compétence |

## Activer / désactiver une contrainte

Chaque contrainte peut être activée ou désactivée individuellement depuis la page
« Constraints » de l'IHM (un interrupteur par carte), pour le prochain solve
uniquement — la désactivation ne modifie ni la pondération ni la logique de la
règle, elle empêche simplement le stream de produire des matches. Toutes les
contraintes sont actives par défaut.

- Persistance : table `constraint_toggle` (présence d'une ligne = désactivée,
  absence = active) via `ReferenceDataRepository`/`ReferenceDataService`.
- API : `GET /api/constraints` renvoie `actif` pour chaque contrainte,
  `PUT /api/constraints/{name}` bascule l'état.
- Solveur : l'état désactivé est injecté comme fait de planification
  `ConstraintToggle` sur `PlanningFestival` (voir `PlanningService.prepareProblem`),
  et chaque contrainte le consulte via `ConstraintToggleSupport.actif(stream, "nom")`,
  appelé juste après le `forEach` initial (sur le flux le plus étroit possible :
  une contrainte pilotée par `ContrainteAdHoc` y branche le toggle sur les
  quelques faits ad hoc, pas sur les milliers de postes).
### Désactiver une contrainte légale : avertissement et trace

Désactiver une contrainte de catégorie « Légal » n'est pas un réglage comme un
autre : le solveur peut alors produire un planning **contraire au Code du
travail tout en affichant un score dur à zéro**. La page « Constraints »
oppose donc, pour ces contraintes uniquement, une **boîte de dialogue
d'avertissement** qui explique cette conséquence et exige la saisie d'un
**motif** avant de laisser passer la désactivation.

Le motif, l'auteur déclaré et l'horodatage sont enregistrés dans
`constraint_toggle` (migration V16, colonnes `motif`,
`modifie_par_utilisateur_id`, `modifie_le`), à l'image de `ContrainteAdHoc`.

**Limites connues, à ne pas confondre avec une piste d'audit :**

- l'application **n'a aucune authentification** : `modifie_par_utilisateur_id`
  vaut ce que l'IHM déclare (aujourd'hui la constante `ui`, exactement comme
  `contrainte_ad_hoc.cree_par`). Ce n'est pas une preuve d'imputabilité ;
- `constraint_toggle` est une **table d'état, pas un journal** : réactiver une
  contrainte supprime la ligne, donc la trace d'une désactivation passée ne
  survit pas à son annulation. Un vrai journal (une ligne par changement,
  jamais supprimée) reste à faire ;
- rien n'empêche techniquement la désactivation : l'avertissement informe, il
  ne bloque pas. Un blocage suppose de décider qui a le droit de lever une
  règle légale — donc, à nouveau, une notion d'utilisateur.

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
chaque solve, à partir de `application.properties`).

- Configuration : une propriété par contrainte,
  `planning.constraint-weights.<nomDeLaContrainte>=<entier>`, listées (à 1,
  c'est-à-dire le comportement actuel) dans `application.properties`. Changer
  une valeur et redémarrer suffit à repondérer une contrainte.
- Câblage : `PlanningService` lit ces propriétés au démarrage
  (`buildConstraintWeightOverrides`), construit un `ConstraintWeightOverrides`
  à partir de `ConstraintCatalog` (qui fournit le niveau — dur/medium/soft —
  de chaque nom de contrainte) et l'affecte à chaque `PlanningFestival` résolu.
- Portée de cette itération : configuration fichier uniquement, pas d'IHM ni de
  table dédiée (contrairement à `constraint_toggle` ci-dessus) — à étendre le
  jour où la repondération doit être pilotable par un administrateur sans
  redéploiement.

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
(`HeuresPlanningService`) et les exports.

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
