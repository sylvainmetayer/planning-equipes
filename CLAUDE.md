# Planning Festival de Jeux — Contexte projet

## Objectif du projet
Application de gestion de planning pour un festival de jeux de société sur 15 jours,
à l'échelle réelle (~150 animateurs). Affecte des animateurs à des stands de jeux,
sous contraintes dures / medium / soft (temps de travail, repos, âge/mineurs,
compétences, équité, rotation).

Référence complète : voir `Cahier_des_charges_Planning_Festival.docx` (à placer à la
racine du repo ou dans `/docs`) pour le détail exhaustif des contraintes, du modèle
de données et de l'architecture cible. **On part directement sur cette version
complète, pas sur un jeu de données réduit.**

## Modèle de données

Pattern standard Timefold pour le "shift rostering" — à respecter tel quel pour
rester compatible avec `HardMediumSoftScore`.

**Données de référence (non modifiées par le solveur)**

```java
public enum TypologieJeu {
    STRATEGIE, AMBIANCE, ENFANT, COOPERATIF, ADRESSE, ROLE
    // à ajuster/compléter selon les vraies catégories du festival
}

public enum NiveauCompetence {
    DEBUTANT, AUTONOME, REFERENT
}

public class Creneau {
    private String id;
    private int jour;            // 1 à 15
    private LocalTime heureDebut;
    private LocalTime heureFin;
}

public class Animateur {
    private String id;
    private String prenom;
    private String nom;
    private LocalDate dateNaissance;          // permet de calculer mineur/majeur et tranche d'âge
    private StatutAnimateur statut;           // BENEVOLE, SALARIE, ... (cf. cahier des charges 2.)
    private Map<TypologieJeu, NiveauCompetence> competences;
    private Set<LocalDate> joursIndisponibles;  // opt-out: disponible par défaut, on liste seulement les jours OFF
}

public class Stand {
    private String id;
    private String nom;
    private Set<TypologieJeu> typologiesProposees;
    private int effectifMin;
    private int effectifMax;
    private boolean reserveMajeurs;           // certains stands interdits aux mineurs
}
```

**Entité que le solveur remplit**

Un `PosteAffectation` par place à pourvoir (pas une seule `Affectation` par couple
stand×créneau) : si un stand a besoin de 2 personnes sur un créneau, générer 2
instances à l'initialisation. Permet de gérer l'effectif min/max naturellement
(poste non pourvu = `animateur` reste `null`).

```java
@PlanningEntity
public class PosteAffectation {
    @PlanningId
    private String id;

    private Stand stand;      // fixe, connu à l'avance
    private Creneau creneau;  // fixe, connu à l'avance

    @PlanningVariable(valueRangeProviderRefs = "animateurRange", nullable = true)
    private Animateur animateur;  // variable optimisée par Timefold
}
```

**Solution globale**

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

**Mapping contraintes → modèle**
- Compétence : `poste.animateur.competences` doit contenir une typologie présente
  dans `poste.stand.typologiesProposees`
- Disponibilité (opt-out) : `poste.animateur` ne doit pas avoir `poste.creneau.date`
  dans `poste.animateur.joursIndisponibles` (disponible par défaut, seuls les jours
  OFF sont déclarés) — cf. `Animateur.estIndisponibleLe(LocalDate)`
- Effectif min/max : comptage des `poste.animateur != null` groupés par `stand` +
  `creneau`
- Mineur/majeur : dérivé de `animateur.dateNaissance` à la date du festival — pas de
  champ booléen stocké, calculer à la volée pour éviter toute désynchronisation
- Repos quotidien, hebdomadaire, encadrement mineur : regrouper les
  `PosteAffectation` d'un même `animateur`, triés par `creneau.jour`/`heureDebut` —
  accessible directement via la référence portée par chaque poste
- Stand réservé aux majeurs : `poste.stand.reserveMajeurs` vs âge de
  `poste.animateur`

**Note sur `effectifMin`/`effectifMax`** : ces champs de `Stand` servent à générer
le bon nombre de `PosteAffectation` au démarrage (étape de setup/seed à partir des
données réelles importées), ce n'est pas une contrainte calculée par une classe
séparée.

**Contraintes manuelles ponctuelles (ad hoc)**

En complément du référentiel général (section 4 du cahier des charges), l'admin
doit pouvoir poser des exceptions au cas par cas (ex. « cet animateur pas affecté
le jour 7 », « ces deux animateurs jamais sur le même créneau »). Modélisées comme
une entité à part, stockée en base et transformée en contrainte dure dynamique
côté solveur — pas de contrainte codée en dur pour un cas particulier.

```java
public enum TypeContrainteAdHoc {
    INDISPONIBILITE_FORCEE,   // un animateur ne doit jamais être affecté sur X
    INCOMPATIBILITE,          // deux animateurs ne doivent jamais être ensemble
    AFFECTATION_FORCEE        // un animateur DOIT être sur ce créneau/stand
}

public class ContrainteAdHoc {
    private String id;
    private TypeContrainteAdHoc type;
    private List<Animateur> animateursConcernes;  // 1 pour indispo/forcée, 2 pour incompatibilité
    private Creneau creneau;                       // nullable si la règle porte sur tout le festival
    private Stand stand;                            // nullable
    private String raison;                          // libre, pour traçabilité
    private String creeParUtilisateurId;
    private Instant creeLe;
}
```

Ces contraintes sont chargées au même titre que le référentiel général dans
`PlanningFestival` et évaluées comme `HardScore` par le moteur — au même niveau de
priorité que les contraintes dures légales, elles ne doivent jamais être
contournées silencieusement par l'optimiseur.

## Référentiel de contraintes

Implémenter le référentiel complet dur / medium / soft détaillé dans le cahier des
charges (sections 4.1 à 4.4) : temps de travail et repos (avec distinction stricte
mineur/majeur), âge et encadrement, compétences, disponibilité et équité.

Toutes les contraintes dures sont bloquantes (`HardScore`), les contraintes medium
fortement pénalisées mais non bloquantes, les contraintes soft optimisées en
dernier. Se référer au tableau du cahier des charges pour le classement exact de
chaque règle — ne pas reclasser une contrainte dure en medium/soft sans validation
explicite (en particulier tout ce qui touche au cadre légal des mineurs).

## Fonctionnalités attendues

- **Pages CRUD** (ajout / modification / suppression) pour les trois référentiels
  de base : espaces (stands), personnes (animateurs), typologies de jeux. Écrans
  simples formulaire + liste, consommant des endpoints REST classiques
  (`GET/POST/PUT/DELETE /api/stands`, `/api/animateurs`, `/api/typologies`) — pas
  de logique métier complexe à ce niveau.
- **Page d'administration des contraintes ad hoc** : lister / créer / supprimer les
  `ContrainteAdHoc` (voir modèle de données ci-dessus), séparée des écrans CRUD de
  base.
- **Export PDF** du planning individuel et du planning global — génération côté
  serveur (Quarkus, ex. lib type OpenPDF), pas de génération PDF côté navigateur.
- **Export ICS** du planning individuel : chaque animateur doit pouvoir importer
  directement ses créneaux dans son calendrier personnel (Google Calendar, Apple
  Calendar, Outlook). Format texte simple, pas de dépendance lourde nécessaire.

## Architecture / Stack

**Application unique, conteneurisée avec Docker Compose.**

- **Backend + moteur** : un seul service **Quarkus** (Java/Kotlin) qui encapsule
  directement Timefold Solver — pas de micro-service séparé pour le solveur à ce
  stade, tout est dans la même JVM. Quarkus est le choix retenu (quickstarts
  officiels Timefold disponibles dessus, démarrage rapide, bien adapté à un
  déploiement conteneurisé léger).
- **Frontend** : **vanilla JS** (pas de framework, pas de React), HTML/CSS/JS
  simples. **Servi directement par Quarkus** en tant que ressources statiques
  (`src/main/resources/META-INF/resources`) — un seul déploiement, aucune
  dépendance Node supplémentaire, pas de build frontend séparé à orchestrer.
  - Le JS est découpé en **modules ES** sous `js/`, chargés via
    `<script type="module" src="/js/app.js">` (les modules ES exigent un service
    HTTP via Quarkus, pas `file://`). Une responsabilité par fichier :
    `js/app.js` (point d'entrée : navigation + init des modules), `js/api.js`
    (helpers `fetch` ; `downloadFile` renvoie un message, ne touche pas au DOM),
    `js/utils.js`, `js/date-utils.js` (calcul de dates calendrier, semaine
    commençant lundi), `js/planning-state.js` (état planning partagé derrière
    get/setLastSolvedPlanning + `ensurePlanning()`, pas de global libre),
    `js/calendar-month.js` (vue mensuelle + filtres animateur/stand, état de vue
    interne), `js/calendar-day.js` (vue par jour), `js/admin.js` (actions
    planning, exports, CRUD référentiels ; état CRUD local isolé).
  - Chaque module de vue exporte `initX()` (câble les events DOM une fois) et
    `renderX()` utilisé par la navigation ; garder les lookups DOM dans le module
    propriétaire. L'état circule dans un seul sens : admin solve/analyze ->
    `setLastSolvedPlanning`, les calendriers le relisent via `ensurePlanning`.
    Ne pas réintroduire de globals mutables partagés ni recréer l'ancien
    `planning.js` monolithique.
  - Le CSS suit le même découpage : `style.css` est un simple agrégateur de
    règles `@import` (police Google d'abord, puis les partials), et les styles
    par composant vivent dans des partials sous `css/` (`base.css` tokens de
    design/reset/typo/contrôles, `layout.css` navigation, `calendar-month.css`,
    `calendar-day.css`), chacun portant ses propres règles responsive `@media`.
    Ajouter les nouveaux styles comme nouveaux partials ; ne pas recréer
    l'ancien `style.css` monolithique.
- Le frontend appelle l'API REST exposée par le même service Java
  (`/api/solve`, `/api/animateurs`, `/api/planning`, …) en `fetch` natif.
- **Base de données** : PostgreSQL, dans son propre conteneur.
- **Conteneurisation** : `docker-compose.yml` à la racine, orchestrant a minima :
  - le service applicatif Quarkus (Dockerfile multi-stage : build Maven/Gradle puis
    image runtime légère type `eclipse-temurin` JRE, ou build natif GraalVM si
    retenu plus tard pour réduire le temps de démarrage)
  - le service PostgreSQL avec volume de persistance
  - variables d'environnement pour la config (connexion DB, etc.) plutôt que des
    valeurs en dur

Structure de dossiers indicative :
```
/src/main/java/...              → domaine, contraintes, API REST (Quarkus)
/src/main/resources/META-INF/resources → index.html, style.css, css/*.css (partials), js/*.js (modules ES vanilla)
/src/main/resources/db          → migrations SQL (ex. Flyway)
Dockerfile
docker-compose.yml
```

## Conventions de travail

- Code et commentaires  en anglais.
- Nommer clairement les classes de domaine en français métier (`Animateur`,
  `Creneau`, `TypologieJeu`) pour rester alignées avec le cahier des charges.
- Écrire un test qui vérifie qu'aucune contrainte dure n'est violée dans le planning
  généré, avant de considérer une étape terminée.
- Pas de dépendance frontend supplémentaire (pas de bundler, pas de framework) sauf
  besoin explicitement validé — le vanilla JS servi statiquement est un choix
  assumé, pas une étape transitoire.
