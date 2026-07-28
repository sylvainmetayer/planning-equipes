# Référentiel de contraintes

Trois niveaux, alignés sur le `HardMediumSoftScore` de Timefold :

- **DUR (hard)** — bloquant : un planning qui en viole une est invalide ;
- **MEDIUM** — fortement pénalisé mais non bloquant, signalé à l'organisateur ;
- **SOFT** — préférence, optimisée en dernier pour départager deux plannings valides.

Le classement de référence de chaque règle métier figure dans le
[cahier des charges](CAHIER_DES_CHARGES.md) (sections 4.1 à 4.4). **Ne jamais
reclasser une contrainte dure en medium/soft sans validation explicite**, en
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

### Soft — préférences (`PreferenceConstraints`)

| Contrainte | Description |
| --- | --- |
| `favoriserRotationDesStands` | Éviter de réaffecter le même animateur au même stand |
| `favoriserMixiteDesNiveaux` | Associer un débutant à un référent pour la montée en compétence |

## Ajouter une contrainte

1. Implémenter la règle dans la classe de `solver/constraints/` correspondant à sa
   famille (ou en créer une nouvelle si la famille n'existe pas), une méthode
   privée par contrainte.
2. L'enregistrer dans le tableau retourné par
   `PlanningConstraintProvider.defineConstraints`.
3. Ajouter sa description métier dans `ConstraintCatalog` (niveau + catégorie +
   libellé) — c'est ce qui alimente l'IHM.
4. Écrire ou étendre un test : `PlanningHardConstraintsTest` vérifie que
   `solved.getScore().hardScore()` vaut zéro sur le scénario nominal. **Une
   contrainte n'est pas terminée tant que ce test ne passe pas.**
