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

public class Creneau {
    private Long id;              // entier auto-généré par la base, jamais saisi ni affiché
    private int jour;              // calculé, jamais persisté — voir plus bas
    private LocalDate date;
    private LocalTime heureDebut;
    private LocalTime heureFin;
    private Set<String> standsOuvertsIds;  // vide = tous les stands ouverts (défaut) ; sinon liste exclusive
    private GroupeCreneau groupe; // planning ("groupe de créneaux") auquel ce créneau appartient
}

public class Animateur {
    private String id;
    private String prenom;
    private String nom;
    private LocalDate dateNaissance;            // → mineur/majeur et tranche d'âge calculés
    private boolean manager;                    // gère d'autres animateurs ; tous les animateurs sont payés
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
    private boolean premium;                    // stand éditeur : continuité + expérience privilégiées
    private Emplacement emplacement;            // lieu physique (kiosque, mairie, ...) ; nullable
}

public class Emplacement {
    private String id;
    private String nom;
    private Double latitude;
    private Double longitude;
}

public class GroupeCreneau {
    private String id;
    private String nom;
    private boolean actif;       // un seul groupe actif à la fois (index unique partiel en base)
}
```

Un `Emplacement` est un référentiel éditable indépendamment (page « Emplacements »),
géré comme `Stand`/`Creneau`/`Animateur` (CRUD, pas de logique métier propre hormis
`distanceMetresVers(...)`, la distance à vol d'oiseau — formule de haversine — vers
un autre emplacement). Un stand non géolocalisé (`emplacement == null`) est
simplement ignoré par la contrainte de distance.

Un `GroupeCreneau` regroupe des créneaux en un planning nommé (page « Créneaux »,
panneau « Groupes de créneaux »), pour préparer un planning alternatif à
l'avance et l'activer en cas de besoin de dernière minute. Chaque `Creneau`
appartient à exactement un groupe (le groupe « Défaut » créé par la migration
`V10__groupe_creneau.sql` sert de valeur par défaut). Activer un groupe
(`PUT /api/groupes-creneaux/{id}/actif`) désactive automatiquement tous les
autres — au plus un groupe actif à la fois, garanti à la fois en base (index
unique partiel) et côté service. Le solveur ne construit son problème
(`PlanningService.construireDepuisReferenceData`) qu'à partir des créneaux du
groupe actif ; les créneaux des autres groupes existent en base mais ne sont
jamais soumis au solveur tant que leur groupe n'est pas activé. Le chargement
d'un scénario (`ReferenceDataRepository.importFromPlanning`) respecte la même
règle : il ne remplace que les créneaux du groupe actif, ce qui permet de
charger un scénario différent dans chaque groupe sans écraser les autres —
voir [`import-export.md`](import-export.md).

L'id d'un `Creneau` est un entier auto-généré par la base (colonne identity),
jamais saisi par l'utilisateur ni affiché dans l'IHM. Deux groupes ne peuvent
donc structurellement plus entrer en collision d'id (contrairement à l'ancien
schéma à clé texte globale), ce qui rend inutile toute qualification d'id par
groupe.

Le `jour` (« jour du festival ») n'est pas non plus saisi : il est calculé à
chaque lecture (`Creneau.assignerJours`, appelé par
`ReferenceDataRepository.listCreneaux`) comme le nombre de jours calendaires
entre la date la plus ancienne du groupe et la date du créneau, plus un. Ce
calcul garantit que deux créneaux sur des jours calendaires consécutifs ont
toujours des numéros de jour consécutifs, même si un jour du groupe ne
contient aucun créneau — invariant dont dépend la contrainte légale de repos
nuit → lendemain (`LegalConstraints`, `soir.getJour() + 1 == lendemain.getJour()`).

## Entité de planification

Un `PosteAffectation` est créé **par place à pourvoir**, pas un par couple
stand × créneau : si un stand a besoin de 2 personnes sur un créneau, deux
instances sont générées à l'initialisation (seed / scénario), en respectant
`Stand.effectifMin` / `effectifMax`. Un poste non pourvu garde `animateur = null`.

Un stand est ouvert sur tous les créneaux par défaut. Si `Creneau.standsOuvertsIds`
n'est pas vide, seuls les stands listés y génèrent des postes (voir
`PlanningService.construirePostes`) — les autres stands sont simplement fermés
sur ce créneau, sans poste ni pénalité associée. En amont, `construirePostes`
ne reçoit que les créneaux du `GroupeCreneau` actif
(`referenceDataService.listCreneauxGroupeActif()`) : les créneaux d'un groupe
inactif ne génèrent aucun poste tant que ce groupe n'est pas activé.

```java
@PlanningEntity
public class PosteAffectation {
    @PlanningId
    private String id;

    private Stand stand;      // fixe, connu à l'avance
    private Creneau creneau;  // fixe, connu à l'avance

    @PlanningVariable(valueRangeProviderRefs = "animateurRange", allowsUnassigned = true)
    private Animateur animateur;
}
```

`allowsUnassigned = true` (et non l'attribut `nullable`, déprécié et voué à
disparaître côté Timefold) : une place peut rester vide pendant la recherche et
dans un planning infaisable ; c'est `posteDoitEtrePourvu` qui en fait une
exigence dure. Conséquence pratique côté contraintes : `forEach(...)` **exclut**
les postes non pourvus, seul `forEachIncludingUnassigned(...)` les voit — d'où
son usage dans `posteDoitEtrePourvu`, et l'inutilité d'un test
`animateur != null` après un `forEach`.

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

## Paramètres légaux

`ParametresLegaux` est un fait de problème (`@ProblemFactCollectionProperty` sur
`PlanningFestival`, même mécanisme que `ContrainteAdHoc`) qui porte les **deux
durées hebdomadaires de travail maximales**, paramétrables depuis la page
« Constraints » et persistées en base (`ReferenceDataService.getParametresLegaux()` /
`updateParametresLegaux(...)`).

| Champ | Défaut | Base légale | Contrainte qui le consomme |
| --- | --- | --- | --- |
| `dureeHebdomadaireMaxMinutes` | 48 h (2880 min) | Code du travail art. L3121-20 (ordre public) ; CCN Animation ÉCLAT IDCC 1518 art. 5.2 *[non vérifié]* | `dureeHebdomadaireMax` (**majeurs uniquement**) |
| `dureeHebdomadaireMaxMineurMinutes` | 35 h (2100 min) | Code du travail art. L3162-1 ; art. D4153-3 pour les 14 à moins de 16 ans | `dureeHebdomadaireMaxMineur` |

Les deux contraintes regroupent les `PosteAffectation` par animateur et semaine
ISO (`Creneau.semaineIso()`) et pénalisent le dépassement au prorata des minutes
excédentaires (gradient, pas simple booléen).

Les autres seuils légaux (repos quotidien, durée quotidienne, pauses, repos
hebdomadaire, jours fériés) sont **des constantes du code**, pas des paramètres :
ce sont des minima/maxima d'ordre public qu'un administrateur n'a aucune raison
légitime d'assouplir. Ils sont déclarés dans `LegalConstraints`, chacun avec son
article.

### Convention de rattachement à la semaine

`Creneau.semaineIso()` rattache un créneau **entièrement** à la semaine ISO de sa
date de début. Un créneau du dimanche 20 h → 00 h est donc compté dans la semaine
qui s'achève, pas dans celle qui commence. Convention assumée : elle simplifie le
décompte et reste conservatrice tant que les créneaux de nuit sont courts.

### Amplitude vs travail effectif

`Creneau.getDureeMinutes()` mesure une **amplitude** (fin − début), alors que les
articles cités portent sur le **travail effectif**. Le modèle ne représente
aucune pause à l'intérieur d'un créneau : les deux grandeurs coïncident donc, ce
qui revient à supposer qu'aucune pause n'est prise pendant un créneau. C'est
précisément pourquoi les pauses sont modélisées comme des **trous entre deux
créneaux d'un même animateur**, et non comme un attribut de créneau.

## Activation des contraintes

`ConstraintToggle` (même mécanisme de fait de problème que `ParametresLegaux` /
`ContrainteAdHoc`) porte le nom d'une contrainte désactivée pour le solve en
cours. Absence de fait pour un nom donné = contrainte active (comportement par
défaut). Persisté en base (table `constraint_toggle`, présence d'une ligne =
désactivée) et pilotable depuis la page « Constraints » via
`PUT /api/constraints/{name}`. Chaque contrainte consulte ce fait via
`ConstraintToggleSupport.actif(stream, "nom")`, voir
[`contraintes.md`](contraintes.md).

## Pondération des contraintes

`ponderationsContraintes` (`ConstraintWeightOverrides<HardMediumSoftScore>`,
type natif Timefold) porte, pour les contraintes dont le poids a été surchargé
via `application.properties`, le score à appliquer à la place du littéral
`ONE_HARD`/`ONE_MEDIUM`/`ONE_SOFT` écrit dans le code de la contrainte. Jamais
sérialisé côté API (`@JsonIgnore`) : `PlanningService.prepareProblem` le
renseigne systématiquement avant chaque solve à partir de la configuration lue
au démarrage. Détails et exemple dans [`contraintes.md`](contraintes.md#pondérer-une-contrainte).

## Mapping contraintes → modèle

| Règle | Mise en œuvre |
| --- | --- |
| Compétence | `poste.animateur.competences` doit contenir une typologie présente dans `poste.stand.typologiesProposees` |
| Disponibilité (opt-out) | `poste.creneau.date` ne doit pas figurer dans `animateur.joursIndisponibles` — cf. `Animateur.estIndisponibleLe(LocalDate)` |
| Effectif min/max | Comptage des `poste.animateur != null` groupés par `stand` + `creneau` (pas de classe de contrainte dédiée) |
| Mineur / majeur | Toujours dérivé de `dateNaissance` à la date du créneau via `estMineurLe(LocalDate)` / `estMajeurLe(LocalDate)` — **jamais un booléen stocké**, pour éviter toute désynchronisation |
| Repos quotidien, hebdo, encadrement | Regroupement des `PosteAffectation` d'un même animateur, triés par `creneau.jour` / `heureDebut` |
| Stand réservé aux majeurs | `poste.stand.reserveMajeurs` vs âge de `poste.animateur` à la date du créneau |
| Éloignement entre créneaux consécutifs | `Emplacement.distanceMetresVers(...)` (haversine) entre les emplacements des deux stands d'un même animateur sur deux créneaux consécutifs (même jour, l'un se terminant quand l'autre commence) |

## Invariants à ne pas casser

- Un `PosteAffectation` = une place, jamais un couple stand × créneau.
- Le statut mineur/majeur est calculé, jamais stocké.
- Les contraintes ad hoc restent des contraintes dures.
- Les noms de domaine restent en français métier.
