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

### Dures — cadre légal mineurs (`LegalConstraints`)

Toutes ces règles sont dérivées de `dateNaissance` **à la date du créneau**,
jamais d'un booléen stocké.

| Contrainte | Article | Description |
| --- | --- | --- |
| `standReserveAuxMajeurs` | — (drapeau métier) | Aucun mineur sur un stand réservé aux majeurs |
| `travailDeNuitInterditPourMineur` | L3163-1 | Pas de créneau empiétant sur la nuit légale du mineur : **20 h-6 h avant 16 ans**, **22 h-6 h de 16 à 18 ans** |
| `dureeQuotidienneMaxMineur` | L3162-1, D4153-3 | Maximum **8 h** de travail effectif sur une même journée, **7 h avant 16 ans** |
| `dureeHebdomadaireMaxMineur` | L3162-1, D4153-3 | Maximum 35 h de travail effectif par semaine pour un mineur |
| `reposQuotidienMineur` | — | Après un créneau de nuit, pas de reprise avant midi le lendemain (~12 h de repos) |

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
