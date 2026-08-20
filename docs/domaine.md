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

Une (et une seule) typologie peut porter le drapeau **ninja**
(`typologie.ninja`, migration `V30`, unicité garantie par un index unique
partiel ; sélecteur « Typologie ninja » sur la page `/typologies`). Un animateur
qui la possède dans ses `competences` est **polyvalent** : le solveur le
considère compétent pour n'importe quel stand et garde ce vivier partiellement
libre en réserve — voir [`contraintes.md`](contraintes.md#typologie-ninja-et-buffer-de-polyvalents).
Le drapeau n'est pas stocké sur l'animateur : il est dérivé au chargement du
problème (`Animateur.appliquerTypologieNinja`).

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
    private List<IndisponibiliteStand> indisponibilites;  // exceptions datées ; vide = toujours ouvert (défaut)
    private List<OuvertureStand> ouvertures;    // inverse des fermetures — voir plus bas
    private List<HoraireStand> horaires;        // règles récurrentes, au-dessus des exceptions — voir plus bas
}

// Forme commune des deux fenêtres datées (classe de base abstraite) :
// deux sous-classes de sens opposés, jamais interchangeables (l'égalité
// est limitée à la classe concrète).
public abstract class FenetreDateeStand {
    private Long id;              // entier auto-généré par la base
    private LocalDate date;
    private LocalTime heureDebut;
    private LocalTime heureFin;   // nullable = jusqu'à la fermeture ; sinon strictement après heureDebut
    private String motif;         // libre, informatif — jamais lu par le solveur
}

public class IndisponibiliteStand extends FenetreDateeStand { }  // fermeture datée

public class OuvertureStand extends FenetreDateeStand { }        // sens inverse, mêmes règles

public class HoraireStand {
    private Long id;
    private ModeHoraire mode;             // OUVERTURE | FERMETURE
    private TypeJoursHoraire jours;       // TOUS | JOURS_SEMAINE | PLAGE | DATES — défaut TOUS
    private Set<DayOfWeek> joursSemaine;  // si jours == JOURS_SEMAINE
    private LocalDate dateDebut;          // si jours == PLAGE (bornes incluses)
    private LocalDate dateFin;
    private Set<LocalDate> dates;         // si jours == DATES
    private List<FenetreHoraire> fenetres;  // au moins une ; plusieurs = coupure méridienne
    private String motif;
}

public class FenetreHoraire {
    private LocalTime heureDebut;
    private LocalTime heureFin;   // nullable = jusqu'à la fermeture du créneau évalué
}

public class Emplacement {
    private String id;
    private String nom;
    private Double latitude;
    private Double longitude;
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

Les créneaux appartiennent directement à leur **édition** — la notion de
« groupe de créneaux » a été supprimée (issue #172) : elle ne cloisonnait que
les créneaux alors que tout le reste du référentiel (stands et horaires,
animateurs, paramètres) restait partagé par l'édition, si bien qu'une
« variante » de plan y mélangeait silencieusement les référentiels. L'édition
est désormais l'**unique porteur de variantes** : un plan canicule est une
édition dupliquée la veille (le rituel de bascule est décrit dans
[`editions.md`](editions.md)), et le découpage automatique remplace les
créneaux de l'édition **en place** — les amplitudes qu'il lit sont consommées,
l'édition ne porte jamais qu'une seule grille. Pour re-découper avec d'autres
paramètres : ré-importer le scénario source (le YAML reste la source de vérité
des amplitudes), ou entretenir une édition « amplitudes » maîtresse dupliquée
à chaque essai.

L'id d'un `Creneau` est un entier auto-généré par la base (colonne identity),
jamais saisi par l'utilisateur ni affiché dans l'IHM.

Le `jour` (« jour du festival ») n'est pas non plus saisi : il est calculé à
chaque lecture (`Creneau.assignerJours`, appelé par
`ReferenceDataRepository.listCreneaux`) comme le nombre de jours calendaires
entre la date la plus ancienne de l'édition et la date du créneau, plus un. Ce
calcul garantit que deux créneaux sur des jours calendaires consécutifs ont
toujours des numéros de jour consécutifs, même si un jour de l'édition ne
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

L'`heureFin` d'une fenêtre est **nullable**, et vaut alors « jusqu'à la
fermeture » : la fenêtre court jusqu'à la fin du créneau évalué, quelle que soit
l'heure à laquelle ce jour-là ferme. C'est ce que veut dire « ouvert de 14 h à la
fermeture », et c'est ce qui évite le contournement `23:59` qu'imposait une heure
de fin concrète les jours fermant à minuit (une fenêtre ne peut pas chevaucher
minuit, contrairement à un `Creneau`).

Une fenêtre datée d'un jour J+1 n'est lue que par un créneau qui **traverse
réellement minuit** — le seul cas où elle peut le chevaucher. Un créneau
10 h-20 h n'est donc pas concerné par ce qui est daté du lendemain, ni pour ses
segments ni pour le choix de son mode.

### Horaires récurrents

Les fenêtres datées ci-dessus sont des **exceptions** ; le motif qui se répète
se saisit au-dessus d'elles, en `HoraireStand` — une règle qui porte un
`ModeHoraire` (`OUVERTURE` ou `FERMETURE`), une ou plusieurs `FenetreHoraire`
sans date, et le sélecteur de jours auquel elle s'applique :

| `jours` | Données propres | Spécificité |
|---|---|---|
| `TOUS` | aucune | 0 |
| `JOURS_SEMAINE` | `joursSemaine` | 1 |
| `PLAGE` | `dateDebut`, `dateFin` (bornes incluses) | 2 |
| `DATES` | `dates` | 3 |

Le stand « Autres - Bourse », ouvert 10 h-12 h puis 14 h-fermeture sur les douze
jours du festival, est **une** règle à deux fenêtres au lieu de vingt-quatre
lignes datées. Sur la fixture de 63 stands, le rapport est du même ordre
partout : 714 fenêtres datées deviennent 120 règles et 19 exceptions
résiduelles.

Quand **tous** les jours du festival sont énoncés, le motif majoritaire d'un
stand s'écrit en `TOUS` et les autres, plus spécifiques, le surchargent : « ouvert
14 h→fermeture tous les jours, sauf les 9 et 12 juillet où ça ferme à 20 h » au
lieu de deux listes de dates. Si un seul jour reste non énoncé, en revanche,
aucune règle ne peut prendre `TOUS` — elle gouvernerait un jour laissé
volontairement ouvert par défaut.

`HoraireStandResolver` résout tout cela **jour calendaire par jour calendaire**,
en trois couches :

1. **une fenêtre datée ce jour-là** (`OuvertureStand`/`IndisponibiliteStand`) →
   elle gagne, seule, et remplace *entièrement* ce que les règles disaient de ce
   jour ;
2. **sinon les règles qui couvrent ce jour** → on ne garde que celles de
   spécificité maximale et on prend l'union de leurs fenêtres. Deux règles de
   même spécificité et de modes opposés sur des jours qui se croisent sont
   refusées à l'écriture (`ReferenceDataService.validateHoraires`) ; le résolveur
   garde malgré tout un arbitrage déterministe pour une donnée arrivée
   autrement (un fichier de scénario écrit à la main) : `OUVERTURE` l'emporte,
   parce que c'est la lecture la plus restrictive des deux ;
3. **sinon** → rien, donc ouvert toute la journée, le défaut historique.

Le résultat est toujours **un seul mode par jour**, ce qui préserve par
construction l'invariant des trois états ci-dessus : ni
`Creneau.segmentsOuvertsMinutes`, ni les contraintes, ni le solveur, ni les
exports n'ont eu à changer quand les règles sont apparues.

L'expansion n'est jamais écrite sur les listes datées : elle vit à côté, dans
`Stand.setFenetresEffectives(...)`, et c'est
`getIndisponibilitesEffectives()`/`getOuverturesEffectives()` que lit
`Creneau` — avec repli sur les listes datées tant qu'aucune résolution n'a
tourné. Un stand résolu peut donc repasser par une sauvegarde (ce que fait tout
stand atteint via un `PlanningFestival`) sans figer son expansion en quelques
centaines de lignes datées. Côté service, la distinction est explicite :
`listStands()` rend la vue CRUD (règles + exceptions, brutes), et
`listStandsResolus()` la vue effective, résolue sur les jours des créneaux de
l'édition — c'est elle que prennent le solveur, la génération de postes et
l'analyse de faisabilité.

**Limite assumée** : une exception *remplace* la journée au lieu de se
soustraire aux règles. « Ouvert 10 h-12 h / 14 h-fermeture tous les jours, sauf
le 14 juillet après-midi » demande donc de ressaisir la journée du 14 en
exception. C'est le prix de l'invariant à un mode par jour ; l'alternative
serait de mélanger les deux modes sur une même journée, précisément l'ambiguïté
que cet invariant existe pour écarter.

Une base saisie avant les règles n'a rien à migrer : ses lignes datées gardent
exactement leur sens (ce sont les exceptions). L'action **« Compacter les
horaires »** (`CompactageHoraires`, `POST /api/stands/compactage-horaires`) en
dérive à la demande les règles équivalentes, et ne réécrit un stand que si les
règles proposées reproduisent ses propres segments ouverts — vérifié en les
rejouant contre les vrais créneaux (`ecartMaximalMinutes`).

L'écart est mesuré en **minutes d'ouverture** en désaccord, pas en appariant les
segments un à un, et une minute est tolérée : celle que récupère un ancien
`23:59` devenu la fermeture réelle. Cette nuance compte, parce qu'un stand absent
toute la journée s'écrivait « fermé 10 h-23 h 59 » un jour fermant à minuit — ce
qui laissait une minute ouverte, donc un poste d'une minute à 23 h 59. Réécrit en
« fermé de 10 h à la fermeture », le stand ne génère plus aucun poste : le nombre
de segments passe de 1 à 0 alors que le désaccord réel est cette seule minute, qui
n'aurait jamais dû être à pourvoir. Compter les segments ferait refuser
exactement les stands que la réécriture aide le plus. L'écart constaté est
rapporté stand par stand plutôt que corrigé en silence.

`PlanningService.construirePostes` génère un poste par place à pourvoir et par
segment ouvert (voir plus bas) — un stand fermé sur tout un créneau n'y génère
simplement aucun poste, sans pénalité associée. En amont, `construirePostes`
reçoit les créneaux de l'édition (`referenceDataService.listCreneaux()`).

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

    @PlanningPin
    private boolean verrouille;  // place validée par l'utilisateur, figée pour le solveur
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

### Verrouillage partiel du planning

`verrouille` (`@PlanningPin`) marque une place **validée par l'utilisateur et
figée** : aucun move, ni en construction heuristique ni en recherche locale, ne
peut changer son animateur. Le champ n'est jamais positionné par le solveur ni
persisté sur `poste_affectation` : il est recalculé à chaque construction du
problème (`PlanningService.construireDepuisReferenceData`) à partir des
verrous enregistrés dans la table `verrouillage_planning`
(`VerrouillagePlanning`, voir [`api.md`](api.md#verrouillages-du-planning)).

Un verrou porte sur un **animateur**, un **stand**, une **journée**, un
**créneau** ou un couple **animateur × créneau** (`ANIMATEUR_CRENEAU`, posé
automatiquement quand l'admin valide une demande d'échange — voir
[Demandes d'échange](#demandes-déchange)), et appartient à son édition comme
le reste du référentiel.

Deux règles encadrent le mécanisme :

- **une place non pourvue n'est jamais figée.** Les places couvertes par un
  verrou sont d'abord réamorcées avec l'animateur que la dernière résolution
  persistée leur avait donné ; celles qui étaient vides restent vides et
  mobiles, sinon geler un trou le rendrait définitivement non pourvu ;
- **une place figée est scorée normalement.** Un verrou peut donc laisser une
  violation visible dans le planning — c'est volontaire, il ne désactive
  silencieusement aucune règle.

Épingler ne suffit pas pour le verrouillage d'un **animateur** : cela fige les
places qu'il tient, mais laisserait le solveur lui en attribuer de nouvelles
ailleurs. C'est la contrainte dure `animateurVerrouilleFige` (voir
[`contraintes.md`](contraintes.md)) qui l'interdit, à partir des
`VerrouillagePlanning` transmis comme faits du problème.
`animateurVerrouilleCreneauFige` applique la même mécanique au verrou
`ANIMATEUR_CRENEAU`, restreinte à son seul créneau.

`allowsUnassigned = true` (et non l'attribut `nullable`, déprécié et voué à
disparaître côté Timefold) : une place peut rester vide pendant la recherche et
dans un planning infaisable ; c'est `posteDoitEtrePourvu` qui en fait une
exigence dure. Conséquence pratique côté contraintes : `forEach(...)` **exclut**
les postes non pourvus, seul `forEachIncludingUnassigned(...)` les voit — d'où
son usage dans `posteDoitEtrePourvu`, et l'inutilité d'un test
`animateur != null` après un `forEach`.

### Demandes d'échange

La « foire au planning » (issue #165) permet à un animateur de proposer un
échange de créneau depuis son espace en libre-service ; rien n'est appliqué
sans validation admin explicite.

`DemandeEchange` (table `demande_echange`, partitionnée par édition) porte le
cycle de vie : demandeur, animateur ciblé, le poste cédé référencé par son
couple **(créneau, stand)** — les ids de `poste_affectation` sont renumérotés
à chaque résolution, le couple est ce qui survit — un motif libre, le statut
(`PROPOSEE` → `ACCEPTEE` / `REFUSEE`, ou `ANNULEE` par le demandeur) et le
verdict de prévalidation des contraintes dures au moment de la soumission
(`prevalidationOk` + descriptions métier du catalogue).

Trois briques l'entourent :

- **l'identité** : chaque animateur porte un `jeton_acces` opaque, généré par
  la base (`DEFAULT gen_random_uuid()`), unique **globalement** pour résoudre
  à lui seul le couple (édition, animateur). C'est le lien imprimé sur le
  planning PDF individuel ; seule l'action « régénérer » le change. Depuis que
  l'espace sert le planning en téléchargement, le lien seul ne suffit plus :
  un **code à 6 chiffres envoyé à l'adresse de la fiche** (tables
  `espace_acces` / `espace_session`, code et session stockés hachés) ouvre une
  session de 30 jours portée par un cookie HttpOnly — pas de compte ni de mot
  de passe (issue #63 reste ouverte), la boîte mail est le second facteur, et
  un animateur sans adresse doit la faire ajouter par l'organisation ;
- **l'accord du collègue** : une demande naît `EN_ATTENTE_CIBLE` ; le
  collègue ciblé l'accepte (elle devient `PROPOSEE` et l'admin est notifié —
  les deux animateurs sont alors d'accord, l'admin n'a plus à le leur
  demander) ou la décline (`REFUSEE_CIBLE`, terminal, le demandeur est
  prévenu). L'admin peut refuser une demande encore `EN_ATTENTE_CIBLE`, mais
  ne peut l'accepter qu'après l'accord du collègue ;
- **la simulation** : `PlanningService.simulerEchange` généralise
  `simulerSwap` au cas à deux places — échange croisé si la cible tient aussi
  un poste sur le créneau, reprise simple sinon — et juge la faisabilité sur
  le **score dur global** (un échange peut casser une contrainte sur un poste
  qu'il ne touche pas : heures hebdomadaires, repos…). L'**échange dirigé**
  (`simulerEchangeDirige`) troque deux créneaux distincts : le demandeur
  désigne, en plus de son propre créneau, le créneau du collègue qu'il veut
  récupérer (« je te laisse mon lundi, je prends ton mardi ») — champs
  `creneauCibleId`/`standCibleId` de la demande, NULL = sémantique
  historique ;
- **l'application** : accepter met à jour chirurgicalement les places
  concernées de `poste_affectation` (jamais de re-résolution implicite), puis
  pose deux verrous `ANIMATEUR_CRENEAU` — sur le créneau échangé pour un
  échange simple, sur le créneau que chacun **reçoit** pour un échange
  dirigé — la régénération suivante ne défera pas l'échange, sans geler le
  reste du planning des deux animateurs.

La foire s'**ouvre et se ferme** par édition (table `parametres_echange`, une
ligne par édition, absente = ouverte) : fermée, soumissions et annulations
sont refusées côté serveur, et l'espace animateur passe en consultation seule
— planning visible et téléchargeable (PDF, ICS), historique des demandes
conservé.

Le lien de l'espace (le jeton) identifie l'animateur **dans son édition** :
une personne = un lien par édition, et une édition dupliquée frappe des jetons
neufs — un lien envoyé désigne donc toujours exactement le plan d'une édition.

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
Le chevauchement de relais est lui-même une source de pic, et la principale :
pendant sa durée, *chaque* stand qui relève compte double. Si tous les stands
relèvent au même instant — ce qui arrive dès que la grille de vacations est
synchronisée, voir `docs/optimisation-solveur.md` — le nombre de sièges à pourvoir
double à cet instant précis. Sur le scénario de référence, 86 sièges réellement
ouverts devenaient 172 à pourvoir à 18:30, pour 153 animateurs : infaisable par
construction, sans la moindre pénurie d'animateurs. Deux leviers désamorcent ce
pic : `nombreFamillesDecalage` (grilles décalées, chaque stand n'en suivant
qu'une, donc les relèves s'étalent) et `dureeChevauchementMinutes` (durée
pendant laquelle le stand compte double).

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
`strategieCouverturePendantPause` (`FERMETURE`, `RELEVE` ou `EFFECTIF_REDUIT`)
qui régit toute pause interne insérée — que ce soit parce qu'un administrateur
a configuré un plafond de vacation au-dessus du seuil légal et qu'une vacation
générée le dépasse malgré tout, ou parce qu'une vacation (de n'importe quelle
durée) engloutit entièrement une fenêtre repas (cas ci-dessus).

| Valeur | Effectif pendant la pause | Vacation de couverture générée |
| --- | --- | --- |
| `FERMETURE` (défaut) | aucun, le stand ferme | non |
| `RELEVE` | effectif plein, en plus des deux vacations encadrantes | oui |
| `EFFECTIF_REDUIT` | moitié de `effectifMin`, **arrondie au supérieur** | oui, marquée `Creneau.couverturePause` |

`RELEVE` est à utiliser avec prudence sur un scénario déjà tendu en
solvabilité, voir la nuance ci-dessus : une vacation de relève de plus par
pause, sur *chaque* stand concerné, concentrée sur la même fenêtre horaire,
est justement le genre de pic de demande simultanée qui fait caler le solveur.

`EFFECTIF_REDUIT` reproduit ce que fait réellement le classeur source du
festival, qui divise l'effectif par deux sur les créneaux de repas plutôt que
d'y ajouter une équipe ou de fermer. C'est la seule des trois valeurs dont
l'effectif dépend du stand : `PlanningService#construirePostes` lit
`Creneau.couverturePause` et ne crée que `ceil(effectifMin / 2)` sièges sur ces
vacations. L'arrondi est volontairement au supérieur, pour qu'un stand tenu par
une seule personne la garde au lieu de fermer — fermer reste une décision
explicite (`FERMETURE`, ou une indisponibilité datée) et non l'effet de bord
d'une division entière. Voir
[`solver-pause-effectif-reduit.md`](solver-pause-effectif-reduit.md) pour le
chiffrage et les limites de ce réglage.

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
(page Solveur) plutôt que de dépendre d'une valeur laissée par un scénario
précédent, plus rapide. Absentes du fichier (cas de tous les autres
scénarios), ces trois sections sont sans effet : `construireExemple` retombe
sur les `ParametresLegaux` actuellement en base, et l'import laisse
`ParametresDecoupage`/`ParametresSolveur` tels quels.

Un scénario écrit directement en amplitudes peut aussi fixer une section
`decoupageAuto:` pour que son import déclenche lui-même ce découpage plutôt
que de laisser l'opérateur repasser par la page Créneaux ensuite (issue
#110) : les créneaux importés sont découpés **en place** avec les
`parametresDecoupage:` déjà appliqués à ce moment-là — l'édition reçoit
directement les vacations, et une notification prévient l'opérateur. Les
anciens champs `groupeSourceNom:`/`groupeCibleNom:` de la section sont
acceptés et ignorés (issue #172 : plus de groupes à nommer). Absente (cas de
tous les scénarios `scenario-*.yaml` fournis, qui listent leurs `postes:`
directement sur les créneaux découpés), l'import se comporte comme avant :
les créneaux du scénario remplacent ceux de l'édition. La forme canonique de
la section est `decoupageAuto: {}` (validée par le schéma JSON) ; une clé nue
`decoupageAuto:` vaut présence, et `decoupageAuto: false` la désactive
explicitement.

Un scénario peut aussi fixer une section `typologies:` (liste de `{ id,
label, ninja? }`) pour donner un libellé humain aux ids de typologie qu'il référence
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

Une amplitude est un `Creneau` ordinaire, en base le temps de l'import ; le
découpage les remplace **en place** par les vacations générées (issue #172).
Le solveur ne voit donc jamais d'amplitudes : au moment où il construit son
problème, l'édition ne porte que les vacations.

## Contraintes ad hoc

Exceptions ponctuelles posées par l'administrateur, stockées en base et évaluées
dynamiquement par le solveur — **jamais de contrainte codée en dur pour un cas
particulier**.

```java
public enum TypeContrainteAdHoc {
    INDISPONIBILITE_FORCEE,   // un animateur ne doit jamais être affecté sur X
    INCOMPATIBILITE,          // deux animateurs ne doivent jamais être ensemble
    AFFECTATION_FORCEE,       // un animateur DOIT être sur ce créneau/stand
    AFFINITE                  // paire à privilégier sur le même stand (soft)
}

public class ContrainteAdHoc {
    private String id;
    private TypeContrainteAdHoc type;
    private List<Animateur> animateursConcernes;  // 1, ou 2 pour l'incompatibilité et l'affinité
    private Creneau creneau;                      // nullable si la règle porte sur tout le festival
    private Stand stand;                          // nullable
    private String raison;                        // traçabilité
    private String creeParUtilisateurId;
    private Instant creeLe;
}
```

Elles sont chargées dans `PlanningFestival` au même titre que le référentiel
général. Les trois types prescriptifs (`INDISPONIBILITE_FORCEE`,
`INCOMPATIBILITE`, `AFFECTATION_FORCEE`) sont évalués en `HardScore`, **au même
niveau de priorité que les contraintes dures légales** — jamais reclassés en
medium/soft. `AFFINITE` (issue #80) est l'unique exception, voulue : une
**récompense soft** pour chaque créneau où les deux animateurs de la paire
tiennent le même stand — en dur, elle serait une affectation forcée déguisée.
Une même paire déclarée à la fois `INCOMPATIBILITE` et `AFFINITE` est refusée à
la saisie. `ReferenceDataService` injecte les contraintes ad hoc dans un
`PlanningFestival` résolu si aucune n'a été fournie.

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

## Paramètres de qualité

`ParametresQualite` est un fait de problème du même type que `ParametresLegaux`,
pour les seuils qui règlent le **confort** d'un planning et non la loi. Il ne
porte aujourd'hui qu'un champ :

| Champ | Défaut | Réglage | Contrainte qui le consomme |
| --- | --- | --- | --- |
| `maxEmplacementsDistinctsParJour` | 3 | `planning.contraintes.max-emplacements-par-jour` | `limiterEmplacementsParJour` |

Différence assumée avec `ParametresLegaux` : ces seuils ne sont **pas stockés
par édition** en base, ils viennent de la configuration de l'application. Ils
n'engagent aucune obligation, seulement un arbitrage d'organisation, et
`PlanningService.prepareProblem` les écrase systématiquement — un appelant ne
peut donc pas desserrer un seuil de qualité en l'envoyant dans son payload.

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
| Polyvalence (« ninja ») | `animateur.competences` contient la typologie marquée `ninja` → `Animateur.isNinja()` : compétent partout, exclu de `limiterTypologiesDistinctesParAnimateur`, et gardé partiellement libre par `preserverBufferPolyvalents` (soft) |
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
- Les contraintes ad hoc prescriptives (`INDISPONIBILITE_FORCEE`,
  `INCOMPATIBILITE`, `AFFECTATION_FORCEE`) restent des contraintes dures ;
  seule `AFFINITE` est une récompense soft, par conception (issue #80).
- Les noms de domaine restent en français métier.
- Un `Creneau` reste toujours l'unité de travail réellement assignable à un
  `PosteAffectation` (une vacation) — jamais une amplitude d'ouverture brute.
  Une amplitude est un `Creneau` ordinaire que le découpage automatique
  consomme et remplace en place (voir plus haut).
