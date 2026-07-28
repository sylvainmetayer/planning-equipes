# Modèle de domaine

Pattern standard Timefold de « shift rostering », à respecter tel quel pour rester
compatible avec `HardMediumSoftScore`. Les noms de classes et de champs restent en
**vocabulaire métier français** (`Animateur`, `Creneau`, `TypologieJeu`,
`joursIndisponibles`) pour rester alignés avec la documentation métier ; les commentaires et identifiants
non métier sont en anglais.

## Données de référence (non modifiées par le solveur)

```java
public enum TypologieJeu {
    STRATEGIE, AMBIANCE, ENFANT, COOPERATIF, ADRESSE, ROLE, ENIGME
}

public enum NiveauCompetence {
    DEBUTANT, AUTONOME, REFERENT
}

public enum StatutAnimateur {
    BENEVOLE, SALARIE
}

public class Creneau {
    private String id;
    private int jour;            // 1 à 15
    private LocalDate date;
    private LocalTime heureDebut;
    private LocalTime heureFin;
}

public class Animateur {
    private String id;
    private String prenom;
    private String nom;
    private LocalDate dateNaissance;            // → mineur/majeur et tranche d'âge calculés
    private StatutAnimateur statut;
    private Map<TypologieJeu, NiveauCompetence> competences;
    private Set<LocalDate> joursIndisponibles;  // opt-out : dispo par défaut, on ne liste que les jours OFF
}

public class Stand {
    private String id;
    private String nom;
    private Set<TypologieJeu> typologiesProposees;
    private int effectifMin;
    private int effectifMax;
    private boolean reserveMajeurs;             // stand interdit aux mineurs
}
```

## Entité de planification

Un `PosteAffectation` est créé **par place à pourvoir**, pas un par couple
stand × créneau : si un stand a besoin de 2 personnes sur un créneau, deux
instances sont générées à l'initialisation (seed / scénario), en respectant
`Stand.effectifMin` / `effectifMax`. Un poste non pourvu garde `animateur = null`.

```java
@PlanningEntity
public class PosteAffectation {
    @PlanningId
    private String id;

    private Stand stand;      // fixe, connu à l'avance
    private Creneau creneau;  // fixe, connu à l'avance

    @PlanningVariable(valueRangeProviderRefs = "animateurRange", nullable = true)
    private Animateur animateur;
}
```

## Solution globale

```java
@PlanningSolution
public class PlanningFestival {
    @ValueRangeProvider(id = "animateurRange")
    @ProblemFactCollectionProperty
    private List<Animateur> animateurs;

    @PlanningEntityCollectionProperty
    private List<PosteAffectation> postes;

    @PlanningScore
    private HardMediumSoftScore score;
}
```

## Contraintes ad hoc

Exceptions ponctuelles posées par l'administrateur, stockées en base et évaluées
dynamiquement par le solveur — **jamais de contrainte codée en dur pour un cas
particulier**.

```java
public enum TypeContrainteAdHoc {
    INDISPONIBILITE_FORCEE,   // un animateur ne doit jamais être affecté sur X
    INCOMPATIBILITE,          // deux animateurs ne doivent jamais être ensemble
    AFFECTATION_FORCEE        // un animateur DOIT être sur ce créneau/stand
}

public class ContrainteAdHoc {
    private String id;
    private TypeContrainteAdHoc type;
    private List<Animateur> animateursConcernes;  // 1, ou 2 pour l'incompatibilité
    private Creneau creneau;                      // nullable si la règle porte sur tout le festival
    private Stand stand;                          // nullable
    private String raison;                        // traçabilité
    private String creeParUtilisateurId;
    private Instant creeLe;
}
```

Elles sont chargées dans `PlanningFestival` au même titre que le référentiel
général et évaluées en `HardScore`, **au même niveau de priorité que les
contraintes dures légales** — jamais reclassées en medium/soft.
`ReferenceDataService` injecte les contraintes ad hoc dans un `PlanningFestival`
résolu si aucune n'a été fournie.

## Mapping contraintes → modèle

| Règle | Mise en œuvre |
| --- | --- |
| Compétence | `poste.animateur.competences` doit contenir une typologie présente dans `poste.stand.typologiesProposees` |
| Disponibilité (opt-out) | `poste.creneau.date` ne doit pas figurer dans `animateur.joursIndisponibles` — cf. `Animateur.estIndisponibleLe(LocalDate)` |
| Effectif min/max | Comptage des `poste.animateur != null` groupés par `stand` + `creneau` (pas de classe de contrainte dédiée) |
| Mineur / majeur | Toujours dérivé de `dateNaissance` à la date du créneau via `estMineurLe(LocalDate)` / `estMajeurLe(LocalDate)` — **jamais un booléen stocké**, pour éviter toute désynchronisation |
| Repos quotidien, hebdo, encadrement | Regroupement des `PosteAffectation` d'un même animateur, triés par `creneau.jour` / `heureDebut` |
| Stand réservé aux majeurs | `poste.stand.reserveMajeurs` vs âge de `poste.animateur` à la date du créneau |

## Invariants à ne pas casser

- Un `PosteAffectation` = une place, jamais un couple stand × créneau.
- Le statut mineur/majeur est calculé, jamais stocké.
- Les contraintes ad hoc restent des contraintes dures.
- Les noms de domaine restent en français métier.
