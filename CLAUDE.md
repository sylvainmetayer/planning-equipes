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
    private Set<Creneau> disponibilites;      // créneaux où il peut être affecté
    private ContactLegal contactLegal;        // si mineur — nullable sinon
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
- Disponibilité : `poste.creneau` doit être dans `poste.animateur.disponibilites`
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

## Référentiel de contraintes

Implémenter le référentiel complet dur / medium / soft détaillé dans le cahier des
charges (sections 4.1 à 4.4) : temps de travail et repos (avec distinction stricte
mineur/majeur), âge et encadrement, compétences, disponibilité et équité.

Toutes les contraintes dures sont bloquantes (`HardScore`), les contraintes medium
fortement pénalisées mais non bloquantes, les contraintes soft optimisées en
dernier. Se référer au tableau du cahier des charges pour le classement exact de
chaque règle — ne pas reclasser une contrainte dure en medium/soft sans validation
explicite (en particulier tout ce qui touche au cadre légal des mineurs).

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
/src/main/resources/META-INF/resources → index.html, planning.js, style.css (vanilla JS)
/src/main/resources/db          → migrations SQL (ex. Flyway)
Dockerfile
docker-compose.yml
```

## Conventions de travail

- Code et commentaires en français ou en anglais, à ta convenance, mais rester
  cohérent dans tout le projet.
- Nommer clairement les classes de domaine en français métier (`Animateur`,
  `Creneau`, `TypologieJeu`) pour rester alignées avec le cahier des charges.
- Écrire un test qui vérifie qu'aucune contrainte dure n'est violée dans le planning
  généré, avant de considérer une étape terminée.
- Pas de dépendance frontend supplémentaire (pas de bundler, pas de framework) sauf
  besoin explicitement validé — le vanilla JS servi statiquement est un choix
  assumé, pas une étape transitoire.
