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
| `pasDeDoubleAffectationSurMemeCreneau` | Un seul poste par animateur et par créneau |

### Dures — cadre légal mineurs (`LegalConstraints`)

| Contrainte | Description |
| --- | --- |
| `standReserveAuxMajeurs` | Aucun mineur sur un stand réservé aux majeurs |
| `mineurNecessiteEncadrementMajeur` | Au moins un majeur sur le même stand et le même créneau qu'un mineur |
| `travailDeNuitInterditPourMineur` | Pas de créneau empiétant sur la nuit pour un mineur |
| `dureeQuotidienneMaxMineur` | Maximum 8 h de présence sur une même journée pour un mineur |
| `reposQuotidienMineur` | Après un créneau de nuit, pas de reprise avant midi le lendemain (~12 h de repos) |
| `dureeHebdomadaireMax` | Aucun animateur (tous payés) ne dépasse la durée hebdomadaire maximale paramétrée (48 h par défaut) — voir `ParametresLegaux` dans [`domaine.md`](domaine.md) |

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
  appelé juste après le `forEach`/`forEachUniquePair` initial.

## Ajouter une contrainte

1. Implémenter la règle dans la classe de `solver/constraints/` correspondant à sa
   famille (ou en créer une nouvelle si la famille n'existe pas), une méthode
   privée par contrainte. Enrober le stream initial avec
   `ConstraintToggleSupport.actif(constraintFactory.forEach(...), "nomDeLaContrainte")`
   pour qu'elle soit désactivable depuis l'IHM comme les autres.
2. L'enregistrer dans le tableau retourné par
   `PlanningConstraintProvider.defineConstraints`.
3. Ajouter sa description métier dans `ConstraintCatalog` (niveau + catégorie +
   libellé) — c'est ce qui alimente l'IHM.
4. Écrire les tests :
   - un test unitaire isolé dans la classe `*ConstraintsTest` de la famille
     (via le `ConstraintVerifier`), avec au moins un cas pénalisé et un cas
     valide — c'est ce qui garantit que la contrainte pénalise exactement ce
     qu'elle doit ;
   - pour une contrainte **dure**, s'assurer en plus que
     `PlanningHardConstraintsTest` (score `hardScore()` à zéro sur le scénario
     nominal) passe toujours.

   **Une contrainte n'est pas terminée tant que ces tests ne passent pas.**
