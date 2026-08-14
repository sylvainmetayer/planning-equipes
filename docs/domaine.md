# Modèle de domaine

Pattern standard Timefold de « shift rostering », à respecter tel quel pour rester
compatible avec `HardMediumSoftScore`. Les noms de classes et de champs restent en
**vocabulaire métier français** (`Animateur`, `Creneau`, `typologie`,
`joursIndisponibles`) pour rester alignés avec la documentation métier ; les commentaires et identifiants
non métier sont en anglais.

## Données de référence (non modifiées par le solveur)

Les typologies de jeu (« catégories ») ne sont **pas** un enum Java figé : ce sont
des lignes CRUD de la table `typologie` (`id`, `label`), gérées via
`/api/typologies` et la page `/typologies`. `Stand.typologiesProposees` et
`Animateur.competences` référencent ces `id` par simple `String` (validé contre la
table par `ReferenceDataService`, et par une contrainte `FOREIGN KEY` en base —
migration `V27__typologie_foreign_keys.sql`), ce qui permet d'ajouter, renommer ou
supprimer une typologie sans toucher au code. Sept catégories « genre de jeu »
(`STRATEGIE`, `AMBIANCE`, `ENFANT`, `COOPERATIF`, `ADRESSE`, `ROLE`, `ENIGME`) plus
`HOMME_JEU` sont seedées par les migrations `V3`/`V24` pour qu'une base neuve les
propose d'office, mais rien n'empêche d'en ajouter d'autres depuis l'UI.

```java
public enum NiveauCompetence {
    DEBUTANT, AUTONOME, REFERENT
}

public enum NiveauEffort {
    NORMAL, EPUISANT  // deux niveaux pour le moment — voir note ci-dessous
}

public class Creneau {
    private Long id;              // entier auto-généré par la base, jamais saisi ni affiché
    private int jour;              // calculé, jamais persisté — voir plus bas
    private LocalDate date;
    private LocalTime heureDebut;
    private LocalTime heureFin;
    private GroupeCreneau groupe; // planning ("groupe de créneaux") auquel ce créneau appartient
}

public class Animateur {
    private String id;
    private String prenom;
    private String nom;
    private LocalDate dateNaissance;            // → régime applicable calculé à la date du créneau :
                                                //   moins de 16 ans / 16-18 ans / majeur — jamais stocké
    private boolean manager;                    // gère d'autres animateurs ; tous les animateurs sont payés
    private Map<String, NiveauCompetence> competences;  // clé = id de typologie (table typologie) ; appréciation de
                                                          //   l'administrateur (nom Java inchangé, « Appréciation »
                                                          //   côté frontend) — voir contraintes.md
    private Set<String> souhaits;               // ids de typologie souhaités par l'animateur, sans niveau ni priorité
    private Set<LocalDate> joursIndisponibles;  // opt-out : dispo par défaut, on ne liste que les jours OFF
}

public class Stand {
    private String id;
    private String nom;
    private Set<String> typologiesProposees;    // ids de typologie (table typologie)
    private int effectifMin;
    private int effectifMax;
    private boolean reserveMajeurs;             // stand interdit aux mineurs
    private boolean premium;                    // stand éditeur : continuité + expérience privilégiées
    private NiveauEffort niveauEffort;          // défaut NORMAL ; EPUISANT déclenche repos post-créneau + équilibrage
    private Emplacement emplacement;            // lieu physique (kiosque, mairie, ...) ; nullable
    private List<IndisponibiliteStand> indisponibilites;  // vide = toujours ouvert (défaut) — voir plus bas
    private List<OuvertureStand> ouvertures;    // inverse des fermetures — voir plus bas
}

public class IndisponibiliteStand {
    private Long id;              // entier auto-généré par la base
    private LocalDate date;
    private LocalTime heureDebut;
    private LocalTime heureFin;   // doit être strictement après heureDebut : ne peut pas traverser minuit
    private String motif;         // libre, informatif — jamais lu par le solveur
}

public class OuvertureStand {
    // Même forme qu'IndisponibiliteStand — id/date/heureDebut/heureFin/motif,
    // mêmes règles (heureFin strictement après heureDebut) — mais sens inverse.
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
    private String groupeSourceId; // groupe d'amplitudes source d'un découpage auto ; null si saisi/importé directement
}
```

`HOMME_JEU` (issue #93) est une compétence d'*animation de rue* (le stand
mobile « Homme-jeu », un animateur qui déambule en ville plutôt qu'un jeu
tenu à poste fixe), pas un genre de jeu comme les autres typologies seedées.
Réutiliser le même axe « typologie » plutôt que créer un second système de
compétences est une simplification assumée : elle branche gratuitement sur le
mécanisme d'éligibilité existant (`Animateur.estEligiblePour`) au prix d'un
mélange conceptuel mineur entre « genre de jeu » et « compétence transverse ».

`NiveauEffort` (issues #93/#79) qualifie la pénibilité physique d'un `Stand` ;
`EPUISANT` est le cas du stand « Homme-jeu ». Deux niveaux seulement pour
l'instant (pas de `FACILE` explicite) : la contrainte de repos post-créneau
(`eviterEnchainementStandsEpuisants`) ne peut donc que *pénaliser*
l'enchaînement de deux créneaux `EPUISANT`, pas *récompenser* un enchaînement
vers un stand facile. Ce même champ, combiné à `premium`, alimente aussi
`equilibrerCreneauxPenibles` (issue #79) : les deux tickets partagent une
seule notion de pénibilité plutôt que d'en poser deux en parallèle. Voir
[`contraintes.md`](contraintes.md).

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

Un stand est ouvert sur tous les créneaux par défaut. Chaque jour calendaire
est dans l'un de trois états, décidé indépendamment jour par jour
(`Creneau.segmentsOuvertsMinutes(Stand)`) :

- **aucune `IndisponibiliteStand` ni `OuvertureStand` ce jour-là** → ouvert
  par défaut, sans restriction (cas largement majoritaire) ;
- **au moins une `IndisponibiliteStand` ce jour-là** → ouvert par défaut,
  fermé uniquement sur les fenêtres listées. Une fermeture peut ne couvrir
  qu'une partie d'un créneau (ex. fermé de 14 h à 16 h dans un créneau
  9 h-19 h) — le calcul soustrait l'union des fermetures qui le chevauchent :
  aucune fermeture ne chevauche le créneau → un seul segment couvrant le
  créneau entier ; une fermeture le couvre en entier → liste vide, fermé sur
  tout le créneau ; une fermeture ne couvre qu'une partie → un ou deux
  segments ouverts restants (un si elle touche un bord, deux si elle est en
  plein milieu) ;
- **au moins une `OuvertureStand` ce jour-là** → **fermé par défaut**, ouvert
  uniquement sur les fenêtres listées (l'inverse d'une fermeture). Pensé pour
  faciliter la saisie d'un stand normalement fermé et ouvert seulement sur des
  créneaux précis (ex. un stand du soir, ouvert de 20 h à 23 h) : plutôt que de
  saisir une fermeture sur chaque autre créneau du festival, une seule
  ouverture suffit. Le calcul est l'union (fusionnée si deux fenêtres se
  chevauchent ou se touchent) des ouvertures qui chevauchent le créneau,
  clampée à ses bornes — liste vide si aucune ne le chevauche, y compris si le
  stand ouvre ce jour-là mais pas sur ce créneau précis (fermé, donc, pour ce
  créneau-là). Toute `IndisponibiliteStand` de ce même jour est ignorée : un
  jour ne peut structurellement pas avoir les deux à la fois —
  `ReferenceDataService` le refuse à l'écriture (`createStand`/`updateStand`),
  ce qui évite d'avoir à arbitrer un conflit ici.

`PlanningService.construirePostes` génère un poste par place à pourvoir et par
segment ouvert (voir plus bas) — un stand fermé sur tout un créneau n'y génère
simplement aucun poste, sans pénalité associée. En amont, `construirePostes`
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
    private LocalTime heureDebutEffective;  // nullable — fenêtre réellement couverte, si fermeture partielle
    private LocalTime heureFinEffective;    // nullable — idem

    @PlanningVariable(valueRangeProviderRefs = "animateurRange", allowsUnassigned = true)
    private Animateur animateur;
}
```

`heureDebutEffective`/`heureFinEffective` restent `null` dans l'immense
majorité des cas (stand ouvert sur tout le créneau) ; ils ne sont renseignés
que pour un poste issu d'un segment partiel. Ce n'est **pas** un sous-créneau
séparé : `poste_affectation.creneau_id` est une clé étrangère vers un créneau
réel et persisté (voir `V11__creneau_numeric_id.sql`), donc un poste ne peut
jamais référencer un créneau synthétique créé à la volée pour représenter
uniquement le segment ouvert. La fenêtre effective vit donc sur le poste
lui-même, en complément du créneau plutôt qu'à sa place —
`PosteAffectation.heureDebutEffectif()` / `heureFinEffectif()` /
`getDureeEffectiveMinutes()` retombent sur les valeurs du créneau quand
l'override est absent, donc tout code qui les utilise se comporte
identiquement à avant #60 dans le cas non partiel. Voir
[`contraintes.md`](contraintes.md#indisponibilité-partielle-dun-stand) pour
l'impact sur les contraintes.

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

## Découpage automatique en vacations

Un scénario « continu » (voir `scenarios/scenario-continu.yaml`) ne définit qu'une
**amplitude** par jour — la fenêtre d'ouverture, ex. `10:00→20:00` ou `10:00→00:00`
(journée + nocturne fusionnées) — et non des vacations individuelles. Affecter
un animateur à un `PosteAffectation` dont le `Creneau` couvre l'amplitude entière
lui imposerait d'être nominalement en poste 10 à 14 h d'affilée, sans pause.

`VacationGeneratorService.genererVacations(List<Creneau> amplitudes, ParametresDecoupage parametres)`
résout ce problème **à la génération**, pas au solve : il découpe chaque
amplitude en plusieurs `Creneau` « vacation » plus courts et chevauchants (un
relais), plutôt que de faire porter la pause au solveur. Tant que chaque
vacation reste sous `dureeVacationMaxMinutes` (6 h par défaut — le seuil de
l'art. L3121-16 au-delà duquel une pause devient légalement obligatoire) **et**
ne recouvre pas entièrement une fenêtre repas, elle n'a besoin d'aucune pause
interne : la pause (et la pause repas) d'un animateur est simplement le trou
entre deux de ses vacations, comme n'importe quel autre moment hors service.

Mais `dureeVacationMinMinutes` peut, à lui seul, empêcher un relais de tomber
*avant* le début d'une fenêtre repas (le premier relais possible d'une journée
ouvrant à 10h avec un minimum de 3h ne peut pas se produire avant 13h, alors
que la fenêtre déjeuner commence à midi) : la seule coupe qui reste possible
tombe alors *après* la fin de la fenêtre, ce qui fait travailler l'animateur
seul sur cette vacation sans interruption pendant tout le repas. `dureeVacationMaxMinutes`
seul ne détecte rien de tel puisque la vacation reste sous le plafond (ex.
10:00-14:00, 4h). `VacationGeneratorService#appliquerPauseLegaleSiNecessaire`
détecte donc aussi ce cas — une vacation qui engloutit une fenêtre repas
entière de son début à sa fin — et y insère une vraie coupure, quelle que soit
sa durée totale, la coupant en deux avec un trou de `dureePauseRepasMinutes`
au milieu. Exception : quand la fenêtre n'est englobée que parce qu'elle est
tronquée par l'heure de fermeture de l'amplitude (ex. une amplitude qui ferme
à 20h alors que la fenêtre dîner nominale va jusqu'à 21h), aucune coupure
n'est forcée — la journée de l'animateur se termine simplement dans la
fenêtre, comme n'importe quelle fin de service, plutôt que de créer une
vacation résiduelle de quelques minutes juste avant la fermeture.

Le chevauchement entre deux vacations consécutives (`dureeChevauchementMinutes`,
30 min par défaut) est le mécanisme de couverture : pendant cette fenêtre, deux
`PosteAffectation` existent sur le même stand (celui qui part, celui qui
arrive), donc `effectifMin` reste garanti *en excédent* temporaire, jamais en
déficit. Le découpeur tente aussi de faire tomber ce chevauchement dans une
fenêtre repas (`fenetreRepasMidiDebut/Fin`, `fenetreRepasSoirDebut/Fin`) pour
que la relève se fasse juste avant ou après un repas plutôt qu'en plein
service.

**Attention, nuance sur la solvabilité** : la disponibilité/compétence des
animateurs reste la condition nécessaire, mais elle n'est plus suffisante dès
que le découpage crée des pics de demande *simultanée* — typiquement un
`dureeVacationMinMinutes` bas (plus de relais/jour) et/ou
`strategieCouverturePendantPause: RELEVE` (une vacation de relève de plus par
pause, sur *chaque* stand concerné, concentrée sur la même fenêtre horaire).
`FeasibilityAnalyzer` ne voit pas ce pic : c'est une estimation optimiste,
pré-résolution, au niveau du besoin agrégé par jour (voir sa javadoc) — elle
peut répondre « réalisable » alors que le solveur ne parvient pas à ramener le
score dur à zéro dans le budget de résolution, précisément parce que trop de
stands réclament un animateur compétent supplémentaire au même quart d'heure.
Le signal fiable après résolution est le score dur réel
(`PlanningDiagnostic.hardScore`), pas `faisabilite.feasible` seul — voir le
bandeau `app-feasibility-banner`, qui affiche désormais les deux.

`ParametresDecoupage` (une seule ligne en base, même mécanisme que
`ParametresLegaux`) porte ces paramètres, y compris
`strategieCouverturePendantPause` (`FERMETURE` ou `RELEVE`) qui régit toute
pause interne insérée — que ce soit parce qu'un administrateur a configuré un
plafond de vacation au-dessus du seuil légal et qu'une vacation générée le
dépasse malgré tout, ou parce qu'une vacation (de n'importe quelle durée)
engloutit entièrement une fenêtre repas (cas ci-dessus). `FERMETURE` (par
défaut) ferme simplement le stand pendant la pause ; `RELEVE` y ajoute une
vacation courte supplémentaire pour garder le stand ouvert — à utiliser avec
prudence sur un scénario déjà tendu en solvabilité, voir la nuance
ci-dessus : une vacation de relève de plus par pause, sur *chaque* stand
concerné, concentrée sur la même fenêtre horaire, est justement le genre de
pic de demande simultanée qui fait caler le solveur.

Un fichier scénario (`scenarios/*.yaml`) peut fixer ses propres
`parametresLegaux:`, `parametresDecoupage:` et/ou `parametresSolveur:` en tête
de fichier, avec uniquement les champs à surcharger (les autres gardent leur
valeur par défaut de la classe Java, jamais celle actuellement en base) —
utile pour documenter par un commentaire YAML *pourquoi* un scénario a besoin
d'un réglage non standard, et pour que `POST /api/reference-data/import-scenario`
réapplique ce réglage à chaque import plutôt que de dépendre d'une valeur
laissée en base par une session précédente. `parametresSolveur.dureeResolutionSecondes`
permet en particulier à un gros scénario (ex. `scenario-complet.yaml`, ~8 min
pour atteindre un bon score) d'auto-configurer la durée de résolution
(onglet Données) plutôt que de dépendre d'une valeur laissée par un scénario
précédent, plus rapide. Absentes du fichier (cas de tous les autres
scénarios), ces trois sections sont sans effet : `construireExemple` retombe
sur les `ParametresLegaux` actuellement en base, et l'import laisse
`ParametresDecoupage`/`ParametresSolveur` tels quels.

Un scénario écrit directement en amplitudes peut aussi fixer une section
`decoupageAuto:` (`groupeSourceNom:` + `groupeCibleNom:`) pour que son import
déclenche lui-même ce découpage plutôt que de laisser l'opérateur repasser par
l'écran « Découpage » ensuite (issue #110) : les créneaux importés atterrissent
dans un groupe source portant `groupeSourceNom` (créé si besoin), le découpage
tourne dessus avec les `parametresDecoupage:` déjà appliqués à ce moment-là, et
le groupe cible `groupeCibleNom` (créé si besoin) reçoit les vacations et est
activé — une notification prévient l'opérateur du groupe désormais actif.
Chaque nom de groupe est résolu vers un groupe existant du même nom le
cas échéant, sinon vers un id dérivé du nom (même mécanisme que le `slugify`
de l'écran Découpage). Absente (cas de tous les scénarios `scenario-*.yaml`
fournis, qui listent leurs `postes:` directement sur les créneaux découpés),
l'import se comporte comme avant : les créneaux du scénario remplacent ceux du
groupe actif.

Un scénario peut aussi fixer une section `typologies:` (liste de `{ id,
label }`) pour donner un libellé humain aux ids de typologie qu'il référence
(`stands[].typologiesProposees`, `animateurs[].competences`/`souhaits`) —
voir [`import-export.md`](import-export.md#chargement-de-scénario) pour le
détail de l'ordre d'application (après l'import du référentiel lui-même, pour
ne pas être écrasée par le libellé-egal-à-l'id que dérive automatiquement
`ReferenceDataRepository#importFromPlanning` pour tout id non déclaré ici).
Absente, chaque id non déjà présent dans le référentiel `typologie` se voit
créé avec ce libellé-egal-à-l'id par défaut.

Une contrainte dure dans `LegalConstraints` complète le dispositif :
`pauseMinimaleEntreVacations` (l'écart entre deux vacations d'un même
animateur le même jour doit être suffisant, 30 min par défaut). Le repos
quotidien entre la fin de la dernière vacation d'un jour et le début de la
première le lendemain est déjà couvert, pour tout animateur et gradué par
tranche d'âge, par `reposQuotidienMinimal` (11 h majeur, 12 h mineur, 14 h
avant 16 ans — art. L3131-1 / L3164-1). Voir [`contraintes.md`](contraintes.md).

Une amplitude est un `Creneau` ordinaire vivant dans un `GroupeCreneau` non
activé (le groupe source) ; les vacations générées remplacent le contenu d'un
autre `GroupeCreneau` (le groupe cible, dont `groupeSourceId` trace sa
provenance pour permettre de le régénérer). Le solveur ne voit jamais les
amplitudes elles-mêmes — seul le groupe actif (les vacations) alimente
`PlanningService.construireDepuisReferenceData`.

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

**Une affectation forcée ne passe pas au-dessus du cadre légal.** Puisqu'elle
est évaluée au même rang dur qu'une règle du Code du travail, une
`AFFECTATION_FORCEE` contraire à une règle légale ne produit pas un planning
illégal : elle produit un planning **infaisable** (`hardScore < 0`), que le
solveur signale au lieu de le livrer. C'est le comportement voulu — mais il
n'était écrit nulle part, et un lecteur pressé pouvait croire l'inverse.

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
| Appréciation (réelle, medium) | `poste.animateur.competences` (= « Appréciation » côté frontend) devrait contenir une typologie présente dans `poste.stand.typologiesProposees` — `appreciationIncompatible`, poids fort |
| Souhaits (medium) | `poste.animateur.souhaits` devrait contenir une typologie présente dans `poste.stand.typologiesProposees` — `souhaitsIncompatibles`, poids faible (privilégie le réel sur le souhaité) |
| Disponibilité (opt-out) | `poste.creneau.date` ne doit pas figurer dans `animateur.joursIndisponibles` — cf. `Animateur.estIndisponibleLe(LocalDate)` |
| Effectif min/max | Comptage des `poste.animateur != null` groupés par `stand` + `creneau` (pas de classe de contrainte dédiée) |
| Mineur / majeur | Toujours dérivé de `dateNaissance` à la date du créneau via `estMineurLe(LocalDate)` / `estMajeurLe(LocalDate)` — **jamais un booléen stocké**, pour éviter toute désynchronisation |
| Moins de 16 ans / 16-18 ans | Même principe, via `estMoinsDe16AnsLe(LocalDate)` : trois régimes légaux distincts (nuit, durée quotidienne, repos quotidien) — voir [`contraintes.md`](contraintes.md) |
| Repos quotidien | Jointure des `PosteAffectation` d'un même animateur sur `creneau.jour` adjacents (`reposQuotidienMinimal`, 11 h / 12 h / 14 h selon l'âge) |
| Travail continu et pauses | Regroupement des `PosteAffectation` d'un même animateur **par date**, fusion des créneaux séparés par moins que la pause légale (`travailContinuMaxMajeur` / `travailContinuMaxMineur`) |
| Encadrement d'un mineur | `ifNotExists` d'un majeur sur le même `stand` + `creneau` |
| Stand réservé aux majeurs | `poste.stand.reserveMajeurs` vs âge de `poste.animateur` à la date du créneau |
| Éloignement entre créneaux consécutifs | `Emplacement.distanceMetresVers(...)` (haversine) entre les emplacements des deux stands d'un même animateur sur deux créneaux consécutifs (même jour, l'un se terminant quand l'autre commence) |

## Invariants à ne pas casser

- Un `PosteAffectation` = une place, jamais un couple stand × créneau.
- `Animateur.souhaits` est un `Set` sans ordre ni priorité — ne pas le transformer en liste ordonnée.
- Le statut mineur/majeur est calculé, jamais stocké.
- Les contraintes ad hoc restent des contraintes dures.
- Les noms de domaine restent en français métier.
- Un `Creneau` reste toujours l'unité de travail réellement assignable à un
  `PosteAffectation` (une vacation) — jamais une amplitude d'ouverture brute.
  Une amplitude est un `Creneau` ordinaire vivant dans un groupe non activé,
  source d'un découpage automatique (voir plus haut).
