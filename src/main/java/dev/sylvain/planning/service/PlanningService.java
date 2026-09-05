package dev.sylvain.planning.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import ai.timefold.solver.core.api.domain.solution.ConstraintWeightOverrides;
import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolutionManager;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.score.director.ScoreDirectorFactoryConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationCompositionStyle;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import ai.timefold.solver.core.config.solver.SolverConfig;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ConstraintToggle;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.FenetreHoraire;
import dev.sylvain.planning.domain.HoraireStand;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.ModeHoraire;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.NiveauEffort;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.ParametresDecoupage;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.ParametresSolveur;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.domain.TypeJoursHoraire;
import dev.sylvain.planning.domain.TypeVerrouillage;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.scenario.YamlSections;
import dev.sylvain.planning.service.diagnostic.ConstraintContribution;
import dev.sylvain.planning.service.diagnostic.ConstraintDiagnosticMode;
import dev.sylvain.planning.service.diagnostic.ConstraintDiagnosticService;
import dev.sylvain.planning.service.diagnostic.AffectationHypothesis;
import dev.sylvain.planning.service.diagnostic.MatchFacts;
import dev.sylvain.planning.service.diagnostic.PlanningAnalysis;
import dev.sylvain.planning.solver.ConstraintCatalog;
import dev.sylvain.planning.solver.EligibleAnimateurMoveFilter;
import dev.sylvain.planning.solver.constraints.AdHocConstraints;
import dev.sylvain.planning.solver.PlanningConstraintProvider;

@ApplicationScoped
public class PlanningService {

    private final SolverFactory<PlanningEvenement> solverFactory;
    /**
     * Kept for {@link SolutionManager#update} alone — refreshing a reloaded
     * plan's score. Everything that used to break a score down per constraint
     * now goes through {@link #constraintDiagnosticService}.
     */
    private final SolutionManager<PlanningEvenement, ?> solutionManager;
    private final ConstraintDiagnosticService constraintDiagnosticService;
    private final ReferenceData referenceDataService;
    private final FeasibilityAnalyzer feasibilityAnalyzer;
    private final long defaultSecondsLimit;
    /**
     * The deployment-wide weight of every constraint, read once from
     * {@code application.properties}. An edition may override any of them
     * (table {@code ponderation_contrainte}); see
     * {@link #constraintWeightOverrides(Map)}.
     */
    private final Map<String, Integer> configuredWeights;
    /** Cap fed to {@code limiterEmplacementsParJour} through {@link ParametresQualite}. */
    private final int maxEmplacementsParJour;

    /**
     * Field-injected rather than a constructor parameter: the plain (non-CDI)
     * tests build this service with {@code new} and never exercise the locks,
     * so it stays null there — {@link #applyVerrouillages} guards on it.
     */
    @Inject
    PlanningPersistenceService planningPersistenceService;

    public PlanningService(
            @ConfigProperty(name = "planning.solver.seconds-limit", defaultValue = "120") Long secondsLimit,
            @ConfigProperty(name = "planning.solver.unimproved-seconds-limit", defaultValue = "30") Long unimprovedSecondsLimit,
            @ConfigProperty(name = "planning.contraintes.max-emplacements-par-jour",
                    defaultValue = "" + ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT) Integer maxEmplacementsParJour,
            ReferenceData referenceDataService,
            FeasibilityAnalyzer feasibilityAnalyzer,
            Config config) {
        SolverConfig solverConfig = SolverConfig.createFromXmlResource("solver/solverConfig.xml");
        solverConfig.setScoreDirectorFactoryConfig(new ScoreDirectorFactoryConfig()
                .withConstraintProviderClass(PlanningConstraintProvider.class));
        applyTermination(solverConfig, secondsLimit, unimprovedSecondsLimit);
        this.solverFactory = SolverFactory.create(solverConfig);
        this.solutionManager = SolutionManager.create(this.solverFactory);
        this.constraintDiagnosticService = ConstraintDiagnosticService.of(
                readDiagnosticMode(config), this.solverFactory);
        this.referenceDataService = referenceDataService;
        this.feasibilityAnalyzer = feasibilityAnalyzer;
        this.defaultSecondsLimit = secondsLimit;
        this.maxEmplacementsParJour = maxEmplacementsParJour;
        this.configuredWeights = readConfiguredWeights(config);
    }

    /**
     * Which implementation breaks a score down per constraint. Read here rather
     * than injected as a {@code @ConfigProperty} because the plain (non-CDI)
     * tests build this service with {@code new}, and because the value is only
     * ever consumed once, to pick the implementation.
     */
    private static ConstraintDiagnosticMode readDiagnosticMode(Config config) {
        return config.getOptionalValue(ConstraintDiagnosticMode.CONFIG_PROPERTY, String.class)
                .map(ConstraintDiagnosticMode::fromConfigValue)
                .orElse(ConstraintDiagnosticMode.DEFAULT);
    }

    /**
     * Reads {@code planning.constraint-weights.<constraintName>} for every
     * constraint in {@link ConstraintCatalog}, defaulting to 1 — the
     * {@code ONE_HARD}/{@code ONE_MEDIUM}/{@code ONE_SOFT} literal already
     * baked into each constraint. Computed once at startup since
     * {@code application.properties} does not change at runtime; the edition's
     * own overrides are read at solve time instead (see
     * {@link #effectiveConstraintWeights()}).
     */
    private static Map<String, Integer> readConfiguredWeights(Config config) {
        Map<String, Integer> poids = new HashMap<>();
        for (ConstraintCatalog.ConstraintDefinition definition : ConstraintCatalog.definitions()) {
            poids.put(definition.name(), config
                    .getOptionalValue("planning.constraint-weights." + definition.name(), Integer.class)
                    .orElse(1));
        }
        return Map.copyOf(poids);
    }

    /**
     * The weight actually applied to each constraint on the next solve: the
     * configured default, overridden by whatever the current edition stored.
     *
     * <p>The configuration stays the value shipped with the deployment — the
     * ratios the solver was tuned with — and an organiser retunes their own
     * edition without changing it for the others. Read on every solve rather
     * than cached: the Contraintes screen writes it, and two editions solved
     * by the same process must not share one another's dosage.</p>
     */
    public Map<String, Integer> effectiveConstraintWeights() {
        return effectiveConstraintWeights(null);
    }

    /**
     * Same, for a planning built from a scenario that pinned its own dosage.
     *
     * <p>The scenario layer <b>replaces</b> the edition's rather than merging
     * with it, exactly as importing the file would: a rule the file does not
     * name goes back to the deployment default. Merging would let the ambient
     * edition's leftovers decide, and the same scenario would solve a different
     * problem depending on where it was run — the hole the {@code contraintes:}
     * section exists to close.</p>
     *
     * @param scenario the weights the file pinned, or {@code null} when it
     *                 pinned none — in which case the edition's own tuning
     *                 applies, unchanged
     */
    public Map<String, Integer> effectiveConstraintWeights(Map<String, Integer> scenario) {
        Map<String, Integer> poids = new HashMap<>(configuredWeights);
        Map<String, Integer> surcharges = scenario != null ? scenario : referenceDataService.getConstraintWeights();
        surcharges.forEach((nom, valeur) -> {
            if (valeur != null && configuredWeights.containsKey(nom)) {
                poids.put(nom, valeur);
            }
        });
        return poids;
    }

    /** {@link #effectiveConstraintWeights()} turned into what Timefold applies at solve time. */
    private ConstraintWeightOverrides<HardMediumSoftScore> constraintWeightOverrides(Map<String, Integer> scenario) {
        Map<String, HardMediumSoftScore> overrides = new HashMap<>();
        Map<String, Integer> poids = effectiveConstraintWeights(scenario);
        for (ConstraintCatalog.ConstraintDefinition definition : ConstraintCatalog.definitions()) {
            int weight = poids.getOrDefault(definition.name(), 1);
            if (weight == 1) {
                continue;
            }
            overrides.put(definition.name(), switch (definition.niveau()) {
                case HARD -> HardMediumSoftScore.ofHard(weight);
                case MEDIUM -> HardMediumSoftScore.ofMedium(weight);
                case SOFT -> HardMediumSoftScore.ofSoft(weight);
            });
        }
        return overrides.isEmpty() ? ConstraintWeightOverrides.none() : ConstraintWeightOverrides.of(overrides);
    }

    /**
     * Two ways for a solve to end, whichever comes first: the time budget is
     * exhausted, or the planning is <b>already feasible</b> and has stopped
     * improving for {@code unimprovedSecondsLimit}.
     *
     * <p>The second half is deliberately gated on feasibility
     * ({@link TerminationConfig#withBestScoreFeasible}, AND-ed with the plateau
     * limit). A bare unimproved-time limit — what this used to configure, and
     * why it ended up disabled altogether — bails out of local search on a
     * <em>hard-constraint</em> plateau too: the solver gave up minutes early on
     * a planning that still had unfilled seats, exactly the case where it needs
     * the rest of its budget. Gated this way, the bailout can only ever cut
     * time that was being spent polishing medium/soft score on an already
     * workable planning, never time spent reaching hard-feasibility.</p>
     *
     * <p>{@code unimprovedSecondsLimit <= 0} disables the plateau branch and
     * leaves the plain time budget.</p>
     */
    private static void applyTermination(SolverConfig solverConfig, Long secondsLimit, Long unimprovedSecondsLimit) {
        if (solverConfig.getTerminationConfig() == null) {
            solverConfig.setTerminationConfig(new TerminationConfig());
        }
        TerminationConfig termination = solverConfig.getTerminationConfig();
        termination.setSecondsSpentLimit(secondsLimit);
        if (unimprovedSecondsLimit != null && unimprovedSecondsLimit > 0) {
            termination.setTerminationConfigList(List.of(new TerminationConfig()
                    .withBestScoreFeasible(true)
                    .withUnimprovedSecondsSpentLimit(unimprovedSecondsLimit)
                    .withTerminationCompositionStyle(TerminationCompositionStyle.AND)));
        }
    }

    /** Classpath folder holding every selectable scenario file. */
    static final String SCENARIOS_DIR = "scenarios";

    /** Default scenario loaded when the caller does not pick one. */
    static final String DEFAULT_SCENARIO = "scenario-complet.yaml";

    /**
     * Names of every constraint enforced at {@link ConstraintCatalog.Niveau#HARD}.
     * {@link #diagnose} only builds per-match {@code violations} for these:
     * a soft or medium constraint like {@code souhaitsIncompatibles} can have
     * thousands of matches, which would bloat the diagnostic payload for a
     * detail nobody blocking on a failed solve needs to see.
     */
    private static final Set<String> HARD_CONSTRAINT_NAMES = ConstraintCatalog.definitions().stream()
            .filter(definition -> definition.niveau() == ConstraintCatalog.Niveau.HARD)
            .map(ConstraintCatalog.ConstraintDefinition::name)
            .collect(Collectors.toUnmodifiableSet());

    /** Caps the per-constraint violation list: a UI detail view, not a full dump. */
    private static final int MAX_VIOLATIONS_PAR_CONTRAINTE = 100;

    public PlanningEvenement buildExample() {
        return buildExample(DEFAULT_SCENARIO);
    }

    /**
     * Loads a named scenario from the {@link #SCENARIOS_DIR} folder. The name is
     * a bare file name (e.g. {@code scenario-complet.yaml}); any path component
     * is rejected so callers cannot escape the scenarios folder.
     */
    public PlanningEvenement buildExample(String scenarioName) {
        try {
            return buildPlanningFromData(readScenarioData(cheminScenario(scenarioName)));
        } catch (IOException e) {
            throw new RuntimeException("Erreur lors du chargement du scénario YAML", e);
        }
    }

    /**
     * Resolves a scenario name to its classpath path. Shared by every scenario
     * accessor so they all apply the same two rules: an absent or blank name
     * falls back to {@link #DEFAULT_SCENARIO} — concatenating it raw would ask
     * for {@code scenarios/null}, or worse for {@code scenarios/} itself, whose
     * directory listing parses as a plain YAML string and blows up later as a
     * {@code ClassCastException} — and any path component is rejected so
     * callers cannot escape the scenarios folder.
     */
    private static String cheminScenario(String scenarioName) {
        String name = (scenarioName == null || scenarioName.isBlank()) ? DEFAULT_SCENARIO : scenarioName;
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new BusinessError.Invalid("Nom de scénario invalide: " + name);
        }
        return SCENARIOS_DIR + "/" + name;
    }

    /**
     * Small, self-contained scenario used as a fast nominal case (a handful of
     * postes) so the hard-constraint invariant can be checked in seconds. The
     * large {@code scenario-complet.yaml} is the complex performance target
     * solved by {@link #buildExample()}.
     */
    public PlanningEvenement buildSimpleExample() {
        try {
            return buildPlanningFromData(
                    readScenarioData(SCENARIOS_DIR + "/scenario.yml"));
        } catch (IOException e) {
            throw new RuntimeException("Erreur lors du chargement du scénario YAML", e);
        }
    }

    /**
     * Builds a fresh problem from the persisted reference data: one
     * {@link PosteAffectation} per required seat ({@code stand.effectifMax}) on
     * every stand × timeslot, all seats unassigned. This mirrors the client-side
     * builder so a solve can be launched by sending only a request to the
     * server — the (potentially huge) planning is built here and never travels
     * to the browser and back, which is what makes very large scenarios
     * solvable at all (the JSON of such a planning exceeds the HTTP body limit).
     */
    public PlanningEvenement buildFromReferenceData() {
        // Resolved stands: buildPostes asks each créneau which parts of it
        // a stand is open for, so the recurring horaires have to be expanded
        // first.
        return buildFromReferenceData(
                referenceDataService.listAnimateurs(),
                referenceDataService.listSolvedStands(),
                referenceDataService.listCreneaux());
    }

    /**
     * The build itself, on lists already read from the referential. Locks, ad
     * hoc constraints and legal parameters are still read from here.
     */
    private PlanningEvenement buildFromReferenceData(List<Animateur> animateurs, List<Stand> stands,
            List<Creneau> creneaux) {
        if (animateurs.isEmpty() || stands.isEmpty() || creneaux.isEmpty()) {
            throw new IllegalStateException(
                    "Aucune donnée de référence. Chargez un scénario ou créez des stands, "
                            + "des animateurs et des créneaux d'abord.");
        }
        List<PosteAffectation> postes = buildPostes(stands, creneaux);
        List<VerrouillagePlanning> verrouillages = referenceDataService.listVerrouillages();
        applyVerrouillages(postes, animateurs, verrouillages);
        LocalDate dateDebut = creneaux.stream()
                .map(Creneau::getDate)
                .filter(Objects::nonNull)
                .min(LocalDate::compareTo)
                .orElse(null);
        PlanningEvenement evenement = new PlanningEvenement(dateDebut, animateurs, postes,
                referenceDataService.snapshotContraintes());
        evenement.setParametresLegaux(List.of(referenceDataService.getParametresLegaux()));
        evenement.setVerrouillages(verrouillages);
        return evenement;
    }

    /** How much of an incremental problem is frozen versus re-opened (issue #86). */
    public record StatistiquesIncremental(
            int postesTotal,
            int postesFiges,
            int postesLiberes,
            int postesLiberesManuellement,
            int postesNouveaux) {
    }

    /**
     * An incremental re-solve problem: the planning to hand to the solver, how
     * much of it is frozen, and the persisted assignments it was seeded from —
     * kept so the caller can diff the result against them.
     */
    public record ProblemeIncremental(PlanningEvenement planning, StatistiquesIncremental statistiques,
            Map<String, List<String>> affectationsPrecedentes) {
    }

    /**
     * Builds an incremental re-solve problem (issue #86): the same seats as
     * {@link #buildFromReferenceData()}, but seeded from the persisted
     * plan and <b>pinned wherever that plan is still valid</b>, so a short
     * solve only has to fill what a late change actually opened — a fresh
     * unavailability, a new stand, seats the previous solve left empty, plus
     * whatever {@code scope} re-opens on purpose.
     *
     * <p>Seats are matched positionally on stand × créneau, the same convention
     * as the locks of issue #87 (see
     * {@link PlanningPersistenceService#loadAnimateursByStandCreneau()}):
     * the seats of one stand and créneau are interchangeable, so no seat id has
     * to survive a reference-data change for the reconciliation to hold.</p>
     *
     * <p>Everything still valid and outside the perimeter is pinned, including
     * seats covered by no explicit lock: an incremental re-solve exists to keep
     * the standing plan stable, not to re-optimise it. Re-opening a validated
     * area is therefore an explicit act — name it in {@code scope}, or run
     * a full solve with locks protecting what must survive it.</p>
     */
    public ProblemeIncremental buildIncrementalFromReferenceData(ReplanificationScope scope) {
        List<Animateur> animateurs = referenceDataService.listAnimateurs();
        List<Stand> stands = referenceDataService.listSolvedStands();
        List<Creneau> creneaux = referenceDataService.listCreneaux();
        if (animateurs.isEmpty() || stands.isEmpty() || creneaux.isEmpty()) {
            throw new IllegalStateException(
                    "Aucune donnée de référence. Chargez un scénario ou créez des stands, "
                            + "des animateurs et des créneaux d'abord.");
        }
        Map<String, List<String>> affectationsPrecedentes =
                planningPersistenceService.loadAnimateursByStandCreneau();
        if (affectationsPrecedentes.isEmpty()) {
            throw new IllegalStateException(
                    "Aucun plan persisté : lancez d'abord une résolution complète, "
                            + "la replanification incrémentale repart de son résultat.");
        }
        List<PosteAffectation> postes = buildPostes(stands, creneaux);
        List<ContrainteAdHoc> contraintesAdHoc = referenceDataService.snapshotContraintes();
        StatistiquesIncremental statistiques = figerPostesIncremental(postes, animateurs, affectationsPrecedentes,
                scope == null ? ReplanificationScope.automatic() : scope, contraintesAdHoc);
        LocalDate dateDebut = creneaux.stream()
                .map(Creneau::getDate)
                .filter(Objects::nonNull)
                .min(LocalDate::compareTo)
                .orElse(null);
        PlanningEvenement evenement = new PlanningEvenement(dateDebut, animateurs, postes, contraintesAdHoc);
        evenement.setParametresLegaux(List.of(referenceDataService.getParametresLegaux()));
        evenement.setVerrouillages(referenceDataService.listVerrouillages());
        return new ProblemeIncremental(evenement, statistiques, affectationsPrecedentes);
    }

    /**
     * The incremental reconciliation itself (issue #86), positional like
     * {@link #applyVerrouillages}: every seat is re-seeded with the
     * animateur the persisted plan gave it, then
     * <ul>
     * <li>named by {@code scope} → cleared and left free, whatever its
     * state: this is the operator saying "redo that";</li>
     * <li>still valid (the animateur exists and is not unavailable on the
     * seat's day) → pinned, the solver may not touch it;</li>
     * <li>invalidated by a late change (animateur deleted, freshly declared
     * unavailable that day, or covered by a fresh forced-unavailability ad hoc
     * constraint) → cleared and left free: exactly what the incremental solve
     * has to re-fill. Pinning such a seat would freeze a hard violation nobody
     * could then fix;</li>
     * <li>never staffed, or newly created (added stand or créneau) → left
     * free, as in a full solve.</li>
     * </ul>
     * Package-private and static so it can be unit-tested without a database.
     */
    static StatistiquesIncremental figerPostesIncremental(List<PosteAffectation> postes, List<Animateur> animateurs,
            Map<String, List<String>> animateursPersistes, ReplanificationScope scope,
            List<ContrainteAdHoc> contraintesAdHoc) {
        Map<String, Animateur> animateursById = new HashMap<>();
        for (Animateur animateur : animateurs) {
            animateursById.put(animateur.getId(), animateur);
        }
        // Filtered once: the loop below runs on thousands of seats, and this
        // list is normally empty.
        List<ContrainteAdHoc> indisponibilitesForcees = contraintesAdHoc == null
                ? List.of()
                : contraintesAdHoc.stream()
                        .filter(contrainte -> contrainte.getType() == TypeContrainteAdHoc.INDISPONIBILITE_FORCEE)
                        .toList();
        Map<String, Integer> prochainePlace = new HashMap<>();
        int figes = 0;
        int liberes = 0;
        int liberesManuellement = 0;
        int nouveaux = 0;
        for (PosteAffectation poste : postes) {
            if (poste.getStand() == null || poste.getCreneau() == null) {
                continue;
            }
            String key = PlanningPersistenceService.standCreneauKey(
                    poste.getStand().getId(), poste.getCreneau().getId());
            List<String> tenants = animateursPersistes.getOrDefault(key, List.of());
            int place = prochainePlace.merge(key, 1, Integer::sum) - 1;
            String tenantId = place < tenants.size() ? tenants.get(place) : null;
            if (tenantId == null) {
                nouveaux++;
                continue;
            }
            if (scope.release(poste, tenantId)) {
                liberesManuellement++;
                continue;
            }
            Animateur tenant = animateursById.get(tenantId);
            // Seeded first: the ad hoc check below reads the seat as staffed,
            // exactly like the constraint it shares its implementation with.
            poste.setAnimateur(tenant);
            if (tenant == null || indisponible(tenant, poste)
                    || forbiddenByContrainteAdHoc(indisponibilitesForcees, poste)) {
                poste.setAnimateur(null);
                liberes++;
                continue;
            }
            poste.setVerrouille(true);
            figes++;
        }
        return new StatistiquesIncremental(postes.size(), figes, liberes, liberesManuellement, nouveaux);
    }

    private static boolean indisponible(Animateur animateur, PosteAffectation poste) {
        return animateur.getJoursIndisponibles() != null
                && animateur.getJoursIndisponibles().contains(poste.getCreneau().getDate());
    }

    /**
     * Whether a forced-unavailability ad hoc constraint forbids this seat as
     * staffed — the other way a late change lands, alongside a day off. The
     * predicate is the solver's own
     * ({@link AdHocConstraints#violatesForcedIndisponibilite}), so the two can
     * never disagree about what is allowed.
     */
    private static boolean forbiddenByContrainteAdHoc(List<ContrainteAdHoc> indisponibilitesForcees,
            PosteAffectation poste) {
        for (ContrainteAdHoc contrainte : indisponibilitesForcees) {
            if (AdHocConstraints.violatesForcedIndisponibilite(contrainte, poste)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Freezes the seats covered by the active group's locks (issue #87): each
     * one is re-seeded with the animateur the last persisted solve gave it and
     * pinned ({@link PosteAffectation#setVerrouille}), so no move can change it
     * while the rest of the plan is re-optimised from scratch.
     *
     * <p>Only a seat that <em>was</em> staffed can be frozen: an empty seat is
     * left unassigned and movable, because pinning a hole would make it
     * permanently unfillable. For the same reason, a lock recorded before any
     * solve has been persisted simply freezes nothing.</p>
     *
     * <p>Seats are re-seeded before the locks are evaluated because a
     * {@link TypeVerrouillage#ANIMATEUR} lock is expressed in terms of who
     * holds the seat; anything seeded but not covered by a lock is cleared
     * again, leaving the unlocked part of the problem exactly as it was
     * before.</p>
     */
    private void applyVerrouillages(List<PosteAffectation> postes, List<Animateur> animateurs,
            List<VerrouillagePlanning> verrouillages) {
        if (verrouillages.isEmpty() || planningPersistenceService == null) {
            return;
        }
        applyVerrouillages(postes, animateurs, verrouillages,
                planningPersistenceService.loadAnimateursByStandCreneau());
    }

    /**
     * The pinning itself, taking the persisted assignments as a parameter:
     * package-private and static so it can be unit-tested without a database,
     * like {@link #buildPostes}.
     */
    static void applyVerrouillages(List<PosteAffectation> postes, List<Animateur> animateurs,
            List<VerrouillagePlanning> verrouillages, Map<String, List<String>> animateursPersistes) {
        if (verrouillages.isEmpty()) {
            return;
        }
        seedFromAffectations(postes, animateurs, verrouillages, animateursPersistes);
    }

    /**
     * Re-seeds the seats positionally from {@code seed} (animateur ids per
     * stand × créneau key, seat order — ids are interchangeable within one
     * key, see {@link PlanningPersistenceService#loadAnimateursByStandCreneau()}),
     * pins the seats covered by a lock, and clears the others again so the
     * solver restarts from scratch everywhere it is free to. An animateur id
     * the referential no longer knows simply leaves its seat empty — a stale
     * seed is a worse starting point, never an error.
     *
     * <p>The variant that <em>keeps</em> the unlocked seeds as a warm start
     * belongs to the incremental re-solve of issue #86, and lives in
     * {@link #figerPostesIncremental}: it has its own notion of what stays
     * valid, and pins rather than merely seeds.</p>
     */
    static void seedFromAffectations(List<PosteAffectation> postes, List<Animateur> animateurs,
            List<VerrouillagePlanning> verrouillages, Map<String, List<String>> seed) {
        if (seed.isEmpty()) {
            return;
        }
        Map<String, Animateur> animateursById = new HashMap<>();
        for (Animateur animateur : animateurs) {
            animateursById.put(animateur.getId(), animateur);
        }
        Map<String, Integer> prochaineePlace = new HashMap<>();
        for (PosteAffectation poste : postes) {
            if (poste.getStand() == null || poste.getCreneau() == null) {
                continue;
            }
            String key = PlanningPersistenceService.standCreneauKey(
                    poste.getStand().getId(), poste.getCreneau().getId());
            List<String> tenants = seed.getOrDefault(key, List.of());
            int place = prochaineePlace.merge(key, 1, Integer::sum) - 1;
            if (place < tenants.size()) {
                poste.setAnimateur(animateursById.get(tenants.get(place)));
            }
            if (poste.getAnimateur() == null) {
                continue;
            }
            boolean gele = verrouillages.stream().anyMatch(verrouillage -> verrouillage.couvre(poste));
            poste.setVerrouille(gele);
            if (!gele) {
                poste.setAnimateur(null);
            }
        }
    }

    /**
     * One {@link PosteAffectation} per required seat on every stand × timeslot
     * × open segment (see {@link Creneau#segmentsOuverts(Stand)}),
     * all seats unassigned. Package-private and static so it can be unit-tested
     * without a database.
     *
     * <p>The seat count comes from the <b>open segment</b>, not from the stand:
     * a stand whose staffing varies during the day states it per opening window
     * ({@link dev.sylvain.planning.domain.FenetreHoraire#getEffectif()}), and a
     * window that names none falls back to {@code stand.effectifMin}. A slot
     * spanning two windows of different effectifs therefore yields two groups of
     * seats, each carrying the effective time window of its own segment.</p>
     *
     * <p>The fallback is {@code effectifMin}, not {@code effectifMax}:
     * {@code effectifMax} is the upper capacity a stand could accept, not the
     * number of seats that must be staffed (that's exactly what
     * {@code posteDoitEtrePourvu} makes a hard requirement for every generated
     * poste). Confirmed against scenario-complet.yaml, whose hand-authored poste
     * list — since dropped as redundant with this very method — held 2088
     * entries, precisely {@code sum(effectifMin) * creneaux} (58 * 36); the
     * effectifMax sum instead gives 2736, 31% more mandatory seats than the
     * scenario intends. Building a problem from reference data with effectifMax
     * silently inflated every solve started from "Lancer le solveur" into a
     * substantially bigger, harder problem than the one actually staffed
     * for — the real reason it kept stalling short of hard-feasibility.</p>
     *
     * <p>Falling back to {@code effectifMin} on <i>every</i> slot was in turn
     * what made a real event's planning cover 7 155 h where its source workbook
     * needed 10 986: the minimum is what a stand needs at its quietest hour, and
     * applying it at the peak under-staffs by a third. Hence the per-window
     * effectif.</p>
     *
     * <p>A stand closed for only part of a créneau (see
     * {@link dev.sylvain.planning.domain.IndisponibiliteStand})
     * still generates a poste for the créneau's open remainder(s), each one
     * carrying an effective time-window override
     * ({@link PosteAffectation#getHeureDebutEffective()}) narrower than the
     * créneau itself — the poste still references the real, persisted créneau
     * (a hard requirement of {@code poste_affectation.creneau_id}'s foreign
     * key), so it cannot be split into a synthetic sub-créneau instead.</p>
     *
     * <p>When {@code creneaux} contains more than one relay-grid "famille"
     * (see {@link VacationGeneratorService#generateVacations}), each stand is
     * deterministically assigned to exactly one (see
     * {@link #spreadStandsByFamily}) and only ever paired against that
     * famille's créneaux, instead of the full cross product. Whichever family a
     * stand lands on is stable across regenerations (it depends only on the set
     * of stand ids), so re-running découpage doesn't reshuffle which stands
     * share a grid.
     * With a single famille (the default, {@code famille} always 0) this is
     * exactly the historical unfiltered cross product.</p>
     */
    static List<PosteAffectation> buildPostes(List<Stand> stands, List<Creneau> creneaux) {
        int nombreFamilles = creneaux.stream().mapToInt(Creneau::getFamille).max().orElse(0) + 1;
        Map<String, Integer> familleParStand = spreadStandsByFamily(stands, nombreFamilles);
        List<PosteAffectation> postes = new ArrayList<>();
        int counter = 0;
        for (Stand stand : stands) {
            int familleStand = familleParStand.get(stand.getId());
            for (Creneau creneau : creneaux) {
                if (creneau.getFamille() != familleStand) {
                    continue;
                }
                List<Creneau.SegmentOuvert> segments = creneau.segmentsOuverts(stand);
                boolean creneauEntierOuvert = segments.size() == 1 && segments.get(0).debutMinutes() == 0
                        && segments.get(0).finMinutes() == creneau.getDureeMinutes();
                for (Creneau.SegmentOuvert segment : segments) {
                    // At least one seat on an open stand: a stand nobody
                    // declared a headcount for still needs somebody, so closing
                    // it stays an explicit decision rather than a side effect of
                    // an unset effectifMin.
                    int effectif = Math.max(1, segment.effectif());
                    // On a break-covering shift (EFFECTIF_REDUIT strategy) the
                    // stand runs at half staffing, rounded up: a stand held by a
                    // single person keeps that person rather than closing.
                    int seats = creneau.isCouverturePause() ? (effectif + 1) / 2 : effectif;
                    for (int seat = 0; seat < seats; seat++) {
                        PosteAffectation poste = new PosteAffectation("poste-" + (counter++), stand, creneau);
                        if (!creneauEntierOuvert) {
                            poste.setHeureDebutEffective(decaler(creneau.getHeureDebut(), segment.debutMinutes()));
                            poste.setHeureFinEffective(decaler(creneau.getHeureDebut(), segment.finMinutes()));
                        }
                        postes.add(poste);
                    }
                }
            }
        }
        return postes;
    }

    /**
     * Assigns every stand to exactly one relay-grid famille, round-robin over
     * the stands sorted by id. Deterministic and stable across regenerations
     * (it depends on nothing but the set of stand ids), just like the hash it
     * replaces — but <b>balanced</b>, which the hash was not.
     *
     * <p>{@code floorMod(id.hashCode(), n)} spreads ids pseudo-randomly, and on
     * a roster this small that is visibly lumpy: on the reference scenario it
     * put 36 of the 91 seats on a single famille out of four (19/21/36/15).
     * That famille alone then changed crew at one instant with 40% of the whole
     * event's demand behind it, which is exactly the simultaneity peak the
     * staggering exists to break — the mechanism was working against itself.
     * Round-robin over sorted ids gives buckets that differ by at most one
     * stand.</p>
     */
    private static Map<String, Integer> spreadStandsByFamily(List<Stand> stands, int nombreFamilles) {
        List<String> ids = stands.stream().map(Stand::getId).sorted().toList();
        Map<String, Integer> families = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            families.put(ids.get(i), i % nombreFamilles);
        }
        return families;
    }

    /** {@code heureDebut} shifted forward by {@code minutes}, wrapping past midnight. */
    private static LocalTime decaler(LocalTime heureDebut, int minutes) {
        return LocalTime.ofSecondOfDay(Math.floorMod(heureDebut.toSecondOfDay() + minutes * 60L, 24 * 3600L));
    }

    /**
     * Serializes the current reference data into the same YAML shape read by
     * {@link #buildPlanningFromData}, so the result can be dropped into the
     * {@link #SCENARIOS_DIR} folder and reloaded as-is.
     *
     * <p>Everything that shapes a solve is written, not only the entities:
     * {@code typologies}, {@code emplacements}, {@code parametresLegaux},
     * {@code parametresDecoupage}, {@code parametresSolveur} and, when the
     * active planning was generated by an auto-découpage, {@code decoupageAuto}
     * — re-importing the file therefore reproduces the very same problem, which
     * is the whole point of exporting it. A file missing those sections silently
     * fell back to the importing instance's own settings (its solve duration,
     * its vacation lengths, its relay families), so the "same" scenario replayed
     * elsewhere solved a different problem.</p>
     *
     * <p>Two mutually exclusive shapes come out of that, depending on the active
     * group:</p>
     * <ul>
     * <li>a hand-built planning exports its own créneaux plus the seat list they
     * imply ({@code postes});</li>
     * <li>a planning generated by découpage exports its <b>source</b>
     * amplitudes plus {@code decoupageAuto}, and no {@code postes} — the import
     * re-runs the découpage and regenerates the seats from the vacations it
     * creates, which is the only way the ids stay consistent (the generated
     * créneaux get fresh database ids on the way in).</li>
     * </ul>
     */
    public String exportScenarioYaml() {
        List<Animateur> animateurs = referenceDataService.listAnimateurs();
        // Raw stands, so the file gets the recurring horaires as rules rather
        // than the few hundred dated windows they expand to — the resolution
        // still runs, because the seat list does depend on it.
        List<Stand> stands = referenceDataService.listStands();
        List<Creneau> creneaux = referenceDataService.listCreneaux();
        if (animateurs.isEmpty() || stands.isEmpty() || creneaux.isEmpty()) {
            throw new IllegalStateException(
                    "Aucune donnée de référence à exporter. Créez des stands, des animateurs et des créneaux d'abord.");
        }
        HoraireStandResolver.apply(stands, creneaux);

        // The edition's créneaux are exported as-is (issue #172): once the
        // découpage ran, the amplitudes it consumed are gone, so a découpé
        // edition exports its vacations plainly — the hand-maintained
        // "amplitudes + decoupageAuto:" scenario file stays the source of
        // truth for re-slicing, never this export.
        List<PosteAffectation> postes = buildPostes(stands, creneaux);
        return buildScenarioYaml(new ScenarioExport(
                animateurs,
                stands,
                creneaux,
                postes,
                referenceDataService.listTypologies(),
                referenceDataService.listEmplacements(),
                referenceDataService.getParametresLegaux(),
                referenceDataService.getParametresDecoupage(),
                referenceDataService.getParametresSolveur(),
                referenceDataService.getContraintesDesactivees(),
                referenceDataService.getConstraintWeights(),
                referenceDataService.snapshotContraintes()));
    }

    /**
     * Everything one exported scenario file holds. A record rather than ten
     * positional parameters, since {@link #buildScenarioYaml} is called
     * both from the export above and from its unit tests.
     *
     * @param postes the seat list, or {@code null} to leave the section out
     *               (see {@link #exportScenarioYaml()})
     */
    record ScenarioExport(
            List<Animateur> animateurs,
            List<Stand> stands,
            List<Creneau> creneaux,
            List<PosteAffectation> postes,
            List<TypologieItem> typologies,
            List<Emplacement> emplacements,
            ParametresLegaux parametresLegaux,
            ParametresDecoupage parametresDecoupage,
            ParametresSolveur parametresSolveur,
            Set<String> contraintesDesactivees,
            Map<String, Integer> poidsContraintes,
            List<ContrainteAdHoc> contraintesAdHoc) {
    }

    /**
     * Builds the YAML text from already-fetched data. Package-private and
     * static, like {@link #buildPostes}, so it can be unit-tested without
     * a database.
     */
    static String buildScenarioYaml(List<Animateur> animateurs, List<Stand> stands, List<Creneau> creneaux,
            List<PosteAffectation> postes) {
        return buildScenarioYaml(new ScenarioExport(animateurs, stands, creneaux, postes, List.of(), List.of(),
                null, null, null, Set.of(), Map.of(), List.of()));
    }

    /** Full-fidelity variant: writes every optional section {@link ScenarioExport} carries. */
    static String buildScenarioYaml(ScenarioExport export) {
        List<Animateur> animateurs = export.animateurs();
        List<Stand> stands = export.stands();
        List<Creneau> creneaux = export.creneaux();
        List<PosteAffectation> postes = export.postes();
        LocalDate dateDebut = creneaux.stream()
                .map(Creneau::getDate)
                .filter(Objects::nonNull)
                .min(LocalDate::compareTo)
                .orElse(null);

        Map<String, Object> evenement = new LinkedHashMap<>();
        evenement.put("dateDebut", asString(dateDebut));

        List<Map<String, Object>> creneauxYaml = new ArrayList<>();
        for (Creneau creneau : creneaux) {
            Map<String, Object> item = new LinkedHashMap<>();
            // asString, as everywhere else: the id is a Long in the database,
            // but the published schema declares it `string` and the loader
            // reads it back as one. Writing it raw produced a YAML number that
            // no import could read — see the round trip covered by
            // PlanningServiceScenarioAllerRetourTest.
            item.put("id", asString(creneau.getId()));
            item.put("jour", creneau.getJour());
            item.put("date", asString(creneau.getDate()));
            item.put("heureDebut", asString(creneau.getHeureDebut()));
            item.put("heureFin", asString(creneau.getHeureFin()));
            creneauxYaml.add(item);
        }

        List<Map<String, Object>> standsYaml = new ArrayList<>();
        for (Stand stand : stands) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", stand.getId());
            item.put("nom", stand.getNom());
            if (stand.getEmplacement() != null) {
                item.put("emplacementId", stand.getEmplacement().getId());
            }
            item.put("typologiesProposees", new ArrayList<>(stand.getTypologiesProposees()));
            item.put("effectifMin", stand.getEffectifMin());
            item.put("effectifMax", stand.getEffectifMax());
            item.put("reserveMajeurs", stand.isReserveMajeurs());
            item.put("premium", stand.isPremium());
            item.put("niveauEffort", stand.getNiveauEffort().name());
            item.put("indisponibilites", indisponibilitesYaml(stand.getIndisponibilites()));
            item.put("ouvertures", ouverturesYaml(stand.getOuvertures()));
            item.put("horaires", horairesYaml(stand.getHoraires()));
            standsYaml.add(item);
        }

        List<Map<String, Object>> animateursYaml = new ArrayList<>();
        for (Animateur animateur : animateurs) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", animateur.getId());
            item.put("prenom", animateur.getPrenom());
            item.put("nom", animateur.getNom());
            item.put("dateNaissance", asString(animateur.getDateNaissance()));
            item.put("manager", animateur.isManager());
            // Contact only — the espace-animateur access token never travels
            // through a scenario file (regenerated from the database instead).
            if (animateur.getEmail() != null && !animateur.getEmail().isBlank()) {
                item.put("email", animateur.getEmail());
            }
            Map<String, String> competences = new LinkedHashMap<>();
            if (animateur.getCompetences() != null) {
                animateur.getCompetences()
                        .forEach((typologie, niveau) -> competences.put(typologie, niveau.name()));
            }
            item.put("competences", competences);
            List<String> joursIndisponibles = animateur.getJoursIndisponibles() == null
                    ? List.of()
                    : animateur.getJoursIndisponibles().stream()
                            .sorted()
                            .map(PlanningService::asString)
                            .toList();
            item.put("joursIndisponibles", joursIndisponibles);
            List<String> souhaits = animateur.getSouhaits() == null
                    ? List.of()
                    : new ArrayList<>(animateur.getSouhaits());
            item.put("souhaits", souhaits);
            animateursYaml.add(item);
        }

        // null (not empty): a scenario carrying decoupageAuto must not pin a seat
        // list, since the créneaux it would reference only exist after the
        // découpage has run on import.
        List<Map<String, Object>> postesYaml = postes == null ? null : new ArrayList<>();
        for (PosteAffectation poste : postes == null ? List.<PosteAffectation>of() : postes) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", poste.getId());
            item.put("standId", poste.getStand().getId());
            item.put("creneauId", asString(poste.getCreneau().getId()));
            item.put("animateurId", null);
            postesYaml.add(item);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("festival", evenement);
        if (export.parametresSolveur() != null) {
            root.put("parametresSolveur",
                    Map.of("dureeResolutionSecondes", export.parametresSolveur().dureeResolutionSecondes()));
        }
        if (export.parametresLegaux() != null) {
            root.put("parametresLegaux", parametresLegauxYaml(export.parametresLegaux()));
        }
        if (export.parametresDecoupage() != null) {
            root.put("parametresDecoupage", parametresDecoupageYaml(export.parametresDecoupage()));
        }
        Map<String, Object> contraintes = contraintesYaml(export);
        if (contraintes != null) {
            root.put("contraintes", contraintes);
        }
        if (!export.typologies().isEmpty()) {
            root.put("typologies", typologiesYaml(export.typologies()));
        }
        root.put("creneaux", creneauxYaml);
        if (!export.emplacements().isEmpty()) {
            root.put("emplacements", emplacementsYaml(export.emplacements()));
        }
        root.put("stands", standsYaml);
        root.put("animateurs", animateursYaml);
        if (postesYaml != null) {
            root.put("postes", postesYaml);
        }
        if (export.contraintesAdHoc() != null && !export.contraintesAdHoc().isEmpty()) {
            root.put("contraintesAdHoc", contraintesAdHocYaml(export.contraintesAdHoc(), creneaux));
        }

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        return new Yaml(options).dump(root);
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    /**
     * The {@code contraintes:} section, or {@code null} when the edition tunes
     * nothing — every rule active at its default weight is what a file without
     * the section already means, and writing it out would be noise.
     */
    private static Map<String, Object> contraintesYaml(ScenarioExport export) {
        Set<String> desactivees = export.contraintesDesactivees() == null ? Set.of() : export.contraintesDesactivees();
        Map<String, Integer> poids = export.poidsContraintes() == null ? Map.of() : export.poidsContraintes();
        if (desactivees.isEmpty() && poids.isEmpty()) {
            return null;
        }
        Map<String, Object> item = new LinkedHashMap<>();
        if (!desactivees.isEmpty()) {
            item.put("desactivees", new TreeSet<>(desactivees).stream().toList());
        }
        if (!poids.isEmpty()) {
            item.put("poids", new TreeMap<>(poids));
        }
        return item;
    }

    /**
     * Serializes the hand-entered constraints, translating the créneau's
     * database id back into the text id the {@code creneaux:} section of the
     * very same file uses — the export writes {@code creneau.getId()} there,
     * so the two halves stay tied together whatever the ids become on import.
     */
    private static List<Map<String, Object>> contraintesAdHocYaml(List<ContrainteAdHoc> contraintes,
            List<Creneau> creneaux) {
        Set<Long> creneauxConnus = creneaux.stream().map(Creneau::getId).collect(Collectors.toSet());
        List<Map<String, Object>> result = new ArrayList<>();
        for (ContrainteAdHoc contrainte : contraintes) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", contrainte.getId());
            item.put("type", contrainte.getType().name());
            item.put("animateurs", contrainte.getAnimateursConcernes().stream()
                    .filter(Objects::nonNull)
                    .map(Animateur::getId)
                    .toList());
            // A constraint aiming at a créneau the export does not carry would
            // be refused on import: drop the scope rather than the constraint,
            // it then covers the whole event, which is the safe side for
            // every prescriptive type.
            if (contrainte.getCreneau() != null && creneauxConnus.contains(contrainte.getCreneau().getId())) {
                item.put("creneauId", asString(contrainte.getCreneau().getId()));
            }
            if (contrainte.getStand() != null) {
                item.put("standId", contrainte.getStand().getId());
            }
            if (contrainte.getRaison() != null) {
                item.put("raison", contrainte.getRaison());
            }
            result.add(item);
        }
        return result;
    }

    /** Only the four fields a scenario file is read back with (see {@link ScenarioSections}). */
    private static Map<String, Object> parametresLegauxYaml(ParametresLegaux parametres) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("dureeHebdomadaireMaxMinutes", parametres.getDureeHebdomadaireMaxMinutes());
        item.put("pauseMinimaleEntreVacationsMinutes", parametres.getPauseMinimaleEntreVacationsMinutes());
        item.put("reposQuotidienMinimalMinutes", parametres.getReposQuotidienMinimalMinutes());
        item.put("pauseSurPoste", parametres.isPauseSurPoste());
        return item;
    }

    private static Map<String, Object> parametresDecoupageYaml(ParametresDecoupage parametres) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("dureeVacationCibleMinutes", parametres.getDureeVacationCibleMinutes());
        item.put("dureeVacationMinMinutes", parametres.getDureeVacationMinMinutes());
        item.put("dureeVacationMaxMinutes", parametres.getDureeVacationMaxMinutes());
        item.put("dureeChevauchementMinutes", parametres.getDureeChevauchementMinutes());
        item.put("dureePauseRepasMinutes", parametres.getDureePauseRepasMinutes());
        item.put("fenetreRepasMidiDebut", asString(parametres.getFenetreRepasMidiDebut()));
        item.put("fenetreRepasMidiFin", asString(parametres.getFenetreRepasMidiFin()));
        item.put("fenetreRepasSoirDebut", asString(parametres.getFenetreRepasSoirDebut()));
        item.put("fenetreRepasSoirFin", asString(parametres.getFenetreRepasSoirFin()));
        item.put("strategieCouverturePendantPause", parametres.getStrategieCouverturePendantPause().name());
        item.put("nombreFamillesDecalage", parametres.getNombreFamillesDecalage());
        item.put("dureeDecalageMaxMinutes", parametres.getDureeDecalageMaxMinutes());
        return item;
    }

    private static List<Map<String, Object>> typologiesYaml(List<TypologieItem> typologies) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (TypologieItem typologie : typologies) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", typologie.id());
            item.put("label", typologie.label());
            item.put("ninja", typologie.ninja());
            result.add(item);
        }
        return result;
    }

    private static List<Map<String, Object>> emplacementsYaml(List<Emplacement> emplacements) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Emplacement emplacement : emplacements) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", emplacement.getId());
            item.put("nom", emplacement.getNom());
            item.put("latitude", emplacement.getLatitude());
            item.put("longitude", emplacement.getLongitude());
            result.add(item);
        }
        return result;
    }

    /** Serializes a stand's {@link IndisponibiliteStand} closures to the shape {@link #loadReferenceScenario} reads back. */
    private static List<Map<String, Object>> indisponibilitesYaml(List<IndisponibiliteStand> indisponibilites) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (IndisponibiliteStand indispo : indisponibilites) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", asString(indispo.getDate()));
            item.put("heureDebut", asString(indispo.getHeureDebut()));
            item.put("heureFin", asString(indispo.getHeureFin()));
            item.put("motif", indispo.getMotif());
            result.add(item);
        }
        return result;
    }

    /** Serializes a stand's {@link OuvertureStand} openings to the shape {@link #loadReferenceScenario} reads back. */
    private static List<Map<String, Object>> ouverturesYaml(List<OuvertureStand> ouvertures) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (OuvertureStand ouverture : ouvertures) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", asString(ouverture.getDate()));
            item.put("heureDebut", asString(ouverture.getHeureDebut()));
            item.put("heureFin", asString(ouverture.getHeureFin()));
            item.put("motif", ouverture.getMotif());
            item.put("effectif", ouverture.getEffectif());
            result.add(item);
        }
        return result;
    }

    /**
     * Serializes a stand's recurring {@link HoraireStand} rules to the shape
     * {@link #loadReferenceScenario} reads back — the day selector flattened
     * onto the rule itself, so the common "every day" case stays a two-line
     * entry and the reader needs no polymorphism.
     *
     * <p>Only the fields the selector actually uses are written: a {@code TOUS}
     * rule carries no dates, so emitting empty {@code dates}/{@code dateDebut}
     * keys would be noise in a file meant to be read and diffed by hand.</p>
     */
    private static List<Map<String, Object>> horairesYaml(List<HoraireStand> horaires) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (HoraireStand horaire : horaires) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("mode", horaire.getMode().name());
            item.put("jours", horaire.getJours().name());
            switch (horaire.getJours()) {
                case JOURS_SEMAINE -> item.put("joursSemaine",
                        horaire.getJoursSemaine().stream().map(Enum::name).toList());
                case PLAGE -> {
                    item.put("dateDebut", asString(horaire.getDateDebut()));
                    item.put("dateFin", asString(horaire.getDateFin()));
                }
                case DATES -> item.put("dates", horaire.getDates().stream().map(PlanningService::asString).toList());
                case TOUS -> {
                    // No selector data to write.
                }
            }
            List<Map<String, Object>> fenetres = new ArrayList<>();
            for (FenetreHoraire fenetre : horaire.getFenetres()) {
                Map<String, Object> fenetreYaml = new LinkedHashMap<>();
                fenetreYaml.put("heureDebut", asString(fenetre.getHeureDebut()));
                // Absent rather than null: "jusqu'à la fermeture" reads better as
                // a missing end than as an explicit empty one.
                if (fenetre.getHeureFin() != null) {
                    fenetreYaml.put("heureFin", asString(fenetre.getHeureFin()));
                }
                // Absent for the same reason: no effectif means "inherit
                // effectifMin", and writing it out would freeze today's value
                // into the file as if it had been chosen.
                if (fenetre.getEffectif() != null) {
                    fenetreYaml.put("effectif", fenetre.getEffectif());
                }
                fenetres.add(fenetreYaml);
            }
            item.put("fenetres", fenetres);
            if (horaire.getMotif() != null) {
                item.put("motif", horaire.getMotif());
            }
            result.add(item);
        }
        return result;
    }

    /**
     * Lists every {@code .yaml}/{@code .yml} scenario available in the
     * {@link #SCENARIOS_DIR} classpath folder, sorted alphabetically. Drop a new
     * file in that folder and it shows up here (and in the UI dropdown) with no
     * code change. Works both in dev (folder on disk) and from a packaged jar.
     */
    public List<String> listScenarios() {
        try {
            java.net.URL dirUrl = getClass().getClassLoader().getResource(SCENARIOS_DIR);
            if (dirUrl == null) {
                return List.of();
            }
            Set<String> names = new TreeSet<>();
            if ("file".equals(dirUrl.getProtocol())) {
                Path dir = Paths.get(dirUrl.toURI());
                try (Stream<Path> files = Files.list(dir)) {
                    files.filter(Files::isRegularFile)
                            .map(p -> p.getFileName().toString())
                            .filter(PlanningService::isScenarioFile)
                            .forEach(names::add);
                }
            } else if ("jar".equals(dirUrl.getProtocol())) {
                java.net.JarURLConnection conn = (java.net.JarURLConnection) dirUrl.openConnection();
                String prefix = SCENARIOS_DIR + "/";
                try (JarFile jar = conn.getJarFile()) {
                    Enumeration<JarEntry> entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        String entry = entries.nextElement().getName();
                        if (entry.startsWith(prefix) && !entry.endsWith("/")) {
                            String fileName = entry.substring(prefix.length());
                            if (!fileName.contains("/") && isScenarioFile(fileName)) {
                                names.add(fileName);
                            }
                        }
                    }
                }
            }
            return new ArrayList<>(names);
        } catch (Exception e) {
            throw new RuntimeException("Erreur lors de la lecture du dossier des scénarios", e);
        }
    }

    private static boolean isScenarioFile(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".yaml") || lower.endsWith(".yml");
    }

    private PlanningEvenement buildPlanningFromData(Map<String, Object> scenarioData) {
        ReferenceScenario reference = loadReferenceScenario(scenarioData);

        // Expand the recurring opening hours before deciding anything about
        // openings: a file may describe the opening hours of a stand as rules
        // rather than as dated windows, and they must be resolved on the days of
        // its own timeslots. With no rule, the call changes nothing.
        HoraireStandResolver.apply(reference.standsById().values(), reference.creneauxParId().values());

        // Load the seats: taken as they are from the file when the section is
        // there, otherwise generated from the stands/timeslots (same rules as
        // buildFromReferenceData) — a file no longer has to enumerate its
        // seats by hand to be imported.
        List<Map<String, Object>> postesList = YamlSections.objets(scenarioData, "postes");
        List<PosteAffectation> postes;
        if (postesList == null) {
            postes = buildPostes(new ArrayList<>(reference.standsById().values()),
                    new ArrayList<>(reference.creneauxParId().values()));
        } else {
            postes = new ArrayList<>();
            for (Map<String, Object> posteData : postesList) {
                String id = parseTextId(posteData.get("id"));
                String standId = parseTextId(posteData.get("standId"));
                String creneauId = parseTextId(posteData.get("creneauId"));

                Stand stand = reference.standsById().get(standId);
                Creneau creneau = reference.creneauxParId().get(creneauId);

                PosteAffectation poste = new PosteAffectation(id, stand, creneau);
                // Mirrors buildPostes(): a hand-authored poste can still name a
                // créneau the stand is only partially open for (IndisponibiliteStand /
                // OuvertureStand), so narrow its effective window the same way instead
                // of silently using the créneau's full amplitude.
                List<int[]> segments = creneau.segmentsOuvertsMinutes(stand);
                if (segments.size() == 1) {
                    int[] segment = segments.get(0);
                    boolean creneauEntierOuvert = segment[0] == 0 && segment[1] == creneau.getDureeMinutes();
                    if (!creneauEntierOuvert) {
                        poste.setHeureDebutEffective(decaler(creneau.getHeureDebut(), segment[0]));
                        poste.setHeureFinEffective(decaler(creneau.getHeureDebut(), segment[1]));
                    }
                }
                postes.add(poste);
            }
        }

        // Exactly the file's own ad hoc constraints, and nothing else: a
        // scenario describes the whole problem, and re-importing it must not
        // merge somebody else's. A file carrying no section carries none.
        //
        // It used to fall back on the database's — the *current* edition's,
        // resolved before the target edition is even known. That leaked one
        // edition's exceptions into another (a foreign key violation as soon
        // as the target held neither the stand nor the créneau they name), and
        // was unsound even into the same edition: an import replaces every
        // créneau, so the ids those exceptions point at are deleted on the way
        // through. What preserves them across a round-trip is the export,
        // which writes the section whenever the edition holds any.
        List<ContrainteAdHoc> contraintesAdHoc = parseContraintesAdHoc(scenarioData, reference);
        PlanningEvenement evenement = new PlanningEvenement(reference.dateDebut(), reference.animateurs(), postes,
                contraintesAdHoc != null ? contraintesAdHoc : new ArrayList<>());
        evenement.setParametresLegaux(List.of(
                parseParametresLegaux(scenarioData).orElseGet(referenceDataService::getParametresLegaux)));
        // Same reasoning as the ad hoc constraints above, for the dosage: a file
        // that pins its weights describes the problem it was verified against,
        // and solving it must apply them whether or not it was ever imported.
        evenement.setPonderationsScenario(parseContraintes(scenarioData)
                .map(ContraintesScenario::poids)
                .orElse(null));
        return evenement;
    }

    /**
     * Parses a scenario YAML file uploaded by a user (same shape as the files
     * under {@link #SCENARIOS_DIR}, typically produced by "Exporter les
     * données actuelles en scénario") into the same result the
     * {@code import-scenario} endpoint applies for a built-in scenario name —
     * without ever touching the classpath. Used by the "Importer un file"
     * button on the Scénarios page.
     *
     * <p>Every failure (malformed YAML, a missing/mistyped section) is
     * reported as an {@link IllegalArgumentException} carrying a message
     * meant to be shown to the user as-is, rather than surfacing the raw
     * {@link org.yaml.snakeyaml.error.YAMLException}/{@link ClassCastException}/
     * {@link NullPointerException} a malformed file triggers deep inside
     * {@link #buildPlanningFromData}.</p>
     */
    public ScenarioImporte buildFromScenarioText(String yamlContent) {
        if (yamlContent == null || yamlContent.isBlank()) {
            throw new BusinessError.Invalid("Le fichier est vide.");
        }
        Map<String, Object> scenarioData;
        try {
            scenarioData = parserYaml(
                    new java.io.ByteArrayInputStream(yamlContent.getBytes(StandardCharsets.UTF_8)));
        } catch (RuntimeException | IOException e) {
            throw new BusinessError.Invalid("YAML invalide : " + messageOr(e), e);
        }
        PlanningEvenement planning;
        try {
            planning = buildPlanningFromData(scenarioData);
        } catch (RuntimeException e) {
            throw new BusinessError.Invalid("Scénario invalide : " + messageOr(e), e);
        }
        return new ScenarioImporte(planning, sectionsOf(scenarioData));
    }

    private static String messageOr(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    /**
     * Result of {@link #buildFromScenarioText}: the built planning plus
     * whichever optional parameter sections the file pinned, mirroring what
     * {@code POST /reference-data/import-scenario} applies for a named
     * built-in scenario.
     */
    public record ScenarioImporte(PlanningEvenement planning, ScenarioSections sections) {
    }

    /**
     * Loads a scenario's raw stands/animateurs/creneaux, ignoring any
     * hand-authored {@code postes:} list — unlike {@link #buildExample},
     * which uses that list as-is. For tests that need to run découpage (see
     * {@link VacationGeneratorService}) on the raw créneaux themselves before
     * building postes via {@link #buildPostes}, the way
     * {@link #buildFromReferenceData} does against the database.
     */
    ReferenceScenario loadReferenceScenario(String scenarioName) throws IOException {
        return loadReferenceScenario(readScenarioData(cheminScenario(scenarioName)));
    }

    /** {@code creneaux}/{@code stands}/{@code animateurs} sections of a scenario file, parsed and cross-linked. */
    record ReferenceScenario(LocalDate dateDebut, Map<String, Creneau> creneauxParId, Map<String, Stand> standsById,
            List<Animateur> animateurs) {
    }

    private ReferenceScenario loadReferenceScenario(Map<String, Object> scenarioData) {
        // Load the creneaux: the YAML file carries a historical text id (used
        // only to tie postes and creneaux together), replaced here by a synthetic
        // numeric id; jour is recomputed (see Creneau.assignerJours), the value
        // from the file is ignored.
        Map<String, Creneau> creneauxMap = new HashMap<>();
        long compteurCreneauId = 1;
        List<Map<String, Object>> creneauxList = YamlSections.objets(scenarioData, "creneaux");
        for (Map<String, Object> creneauData : creneauxList) {
            String id = parseTextId(creneauData.get("id"));

            LocalDate date = parseLocalDate(creneauData.get("date"), "creneaux.date");
            LocalTime heureDebut = parseLocalTime(creneauData.get("heureDebut"));
            LocalTime heureFin = parseLocalTime(creneauData.get("heureFin"));

            Creneau creneau = new Creneau(compteurCreneauId++, 0, date, heureDebut, heureFin);
            creneauxMap.put(id, creneau);
        }
        Creneau.assignerJours(creneauxMap.values());

        // Load the emplacements
        Map<String, Emplacement> emplacementsMap = new HashMap<>();
        List<Map<String, Object>> emplacementsList = YamlSections.objets(scenarioData, "emplacements");
        if (emplacementsList != null) {
            for (Map<String, Object> emplacementData : emplacementsList) {
                String id = (String) emplacementData.get("id");
                String nom = (String) emplacementData.get("nom");
                Double latitude = emplacementData.get("latitude") == null ? null
                        : ((Number) emplacementData.get("latitude")).doubleValue();
                Double longitude = emplacementData.get("longitude") == null ? null
                        : ((Number) emplacementData.get("longitude")).doubleValue();

                Emplacement emplacement = new Emplacement(id, nom, latitude, longitude);
                emplacementsMap.put(id, emplacement);
            }
        }

        // Load the stands
        Map<String, Stand> standsMap = new HashMap<>();
        List<Map<String, Object>> standsList = YamlSections.objets(scenarioData, "stands");
        for (Map<String, Object> standData : standsList) {
            String id = (String) standData.get("id");
            String nom = (String) standData.get("nom");
            List<String> typologiesStr = YamlSections.chaines(standData, "typologiesProposees");
            Set<String> typologies = new HashSet<>(typologiesStr);
            int effectifMin = ((Number) standData.get("effectifMin")).intValue();
            int effectifMax = ((Number) standData.get("effectifMax")).intValue();
            boolean reserveMajeurs = (Boolean) standData.getOrDefault("reserveMajeurs", false);
            boolean premium = (Boolean) standData.getOrDefault("premium", false);

            Stand stand = new Stand(id, nom, typologies, effectifMin, effectifMax, reserveMajeurs, premium);
            String niveauEffortStr = (String) standData.getOrDefault("niveauEffort", NiveauEffort.NORMAL.name());
            stand.setNiveauEffort(NiveauEffort.valueOf(niveauEffortStr));
            String emplacementId = (String) standData.get("emplacementId");
            if (emplacementId != null) {
                stand.setEmplacement(emplacementsMap.get(emplacementId));
            }
            List<Map<String, Object>> indisponibilitesData = YamlSections.objets(standData, "indisponibilites");
            if (indisponibilitesData != null) {
                List<IndisponibiliteStand> indisponibilites = new ArrayList<>();
                for (Map<String, Object> indispoData : indisponibilitesData) {
                    LocalDate date = parseLocalDate(indispoData.get("date"), "stands.indisponibilites.date");
                    LocalTime heureDebut = LocalTime.parse((String) indispoData.get("heureDebut"));
                    LocalTime heureFin = parseTimeOrEndOfDay(indispoData.get("heureFin"));
                    String motif = (String) indispoData.get("motif");
                    indisponibilites.add(new IndisponibiliteStand(null, date, heureDebut, heureFin, motif));
                }
                stand.setIndisponibilites(indisponibilites);
            }
            List<Map<String, Object>> ouverturesData = YamlSections.objets(standData, "ouvertures");
            if (ouverturesData != null) {
                List<OuvertureStand> ouvertures = new ArrayList<>();
                for (Map<String, Object> ouvertureData : ouverturesData) {
                    LocalDate date = parseLocalDate(ouvertureData.get("date"), "stands.ouvertures.date");
                    LocalTime heureDebut = LocalTime.parse((String) ouvertureData.get("heureDebut"));
                    LocalTime heureFin = parseTimeOrEndOfDay(ouvertureData.get("heureFin"));
                    String motif = (String) ouvertureData.get("motif");
                    Object effectif = ouvertureData.get("effectif");
                    if (effectif != null && !(effectif instanceof Number)) {
                        throw new BusinessError.Invalid(
                                "Champ invalide: stands.ouvertures.effectif doit être un entier");
                    }
                    ouvertures.add(new OuvertureStand(null, date, heureDebut, heureFin, motif,
                            effectif == null ? null : ((Number) effectif).intValue()));
                }
                stand.setOuvertures(ouvertures);
            }
            List<Map<String, Object>> horairesData = YamlSections.objets(standData, "horaires");
            if (horairesData != null) {
                stand.setHoraires(readHoraires(horairesData));
            }
            standsMap.put(id, stand);
        }

        // Load the animateurs
        List<Animateur> animateurs = new ArrayList<>();
        List<Map<String, Object>> animateursList = YamlSections.objets(scenarioData, "animateurs");
        for (Map<String, Object> animateurData : animateursList) {
            String id = (String) animateurData.get("id");
            String prenom = (String) animateurData.get("prenom");
            String nom = (String) animateurData.get("nom");
            LocalDate dateNaissance = parseLocalDate(animateurData.get("dateNaissance"), "animateurs.dateNaissance");
            boolean manager = Boolean.TRUE.equals(animateurData.get("manager"));

            Animateur animateur = new Animateur(id, prenom, nom, dateNaissance, manager);
            animateur.setEmail((String) animateurData.get("email"));

            // Load the competences
            Map<String, String> competencesData = (Map<String, String>) animateurData.get("competences");
            Map<String, NiveauCompetence> competences = new HashMap<>();
            for (Map.Entry<String, String> entry : competencesData.entrySet()) {
                competences.put(entry.getKey(), NiveauCompetence.valueOf(entry.getValue()));
            }
            animateur.setCompetences(competences);

            // Load the days off (opt-out: available unless listed)
            List<Object> joursOffData = YamlSections.valeurs(animateurData, "joursIndisponibles");
            Set<LocalDate> joursIndisponibles = joursOffData == null
                    ? new HashSet<>()
                    : joursOffData.stream()
                            .map(value -> parseLocalDate(value, "animateurs.joursIndisponibles"))
                            .collect(Collectors.toCollection(HashSet::new));
            animateur.setJoursIndisponibles(joursIndisponibles);

            // Load the souhaits (wished-for stand typologies, with no level and no priority)
            List<Object> souhaitsData = YamlSections.valeurs(animateurData, "souhaits");
            Set<String> souhaits = souhaitsData == null
                    ? new HashSet<>()
                    : souhaitsData.stream()
                            .map(value -> (String) value)
                            .collect(Collectors.toCollection(HashSet::new));
            animateur.setSouhaits(souhaits);

            animateurs.add(animateur);
        }

        // A scenario carries its own typologie referential, so the ninja typologie
        // comes from the file itself — the database one may not be loaded yet (or
        // may describe a different event entirely).
        String typologieNinja = parseTypologies(scenarioData).stream()
                .filter(TypologieItem::ninja)
                .map(TypologieItem::id)
                .findFirst()
                .orElse(null);
        animateurs.forEach(animateur -> animateur.applyNinjaTypologie(typologieNinja));

        LocalDate dateDebut = parseLocalDate(
            YamlSections.objet(scenarioData, "festival").get("dateDebut"),
            "festival.dateDebut");

        return new ReferenceScenario(dateDebut, creneauxMap, standsMap, animateurs);
    }

    /** Shared YAML loading for {@link #buildPlanningFromData} and the optional-section accessors below. */
    private Map<String, Object> readScenarioData(String scenarioPath) throws IOException {
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream(scenarioPath);
        if (inputStream == null) {
            throw new IOException("Fichier de scénario non trouvé: " + scenarioPath);
        }
        return parserYaml(inputStream);
    }

    /**
     * Parses a scenario's raw YAML bytes, from the classpath ({@link #readScenarioData})
     * or from a user-uploaded file ({@link #buildFromScenarioText}).
     */
    // The one unchecked cast left in this class, and the only one that has no
    // alternative: this IS the entry point that turns SnakeYAML's untyped
    // Object into the scenario document every parseXxx below reads. The check
    // just above it is what makes it safe; every nested section goes through
    // YamlSections instead.
    @SuppressWarnings("unchecked")
    private Map<String, Object> parserYaml(InputStream inputStream) throws IOException {
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setCodePointLimit(Integer.MAX_VALUE);
        Yaml yaml = new Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(loaderOptions));
        Object contenu = yaml.load(inputStream);
        // An empty file loads as null, and anything that is not a mapping (a bare
        // scalar, a list) would only surface much later as a ClassCastException
        // deep in a parseXxx: say what is actually wrong with the file instead.
        if (!(contenu instanceof Map)) {
            throw new IOException("Le fichier de scénario n'est pas un document YAML valide (mapping attendu)");
        }
        return (Map<String, Object>) contenu;
    }

    /**
     * Every optional top-level section a scenario file may pin, read in
     * <b>one</b> pass over the file.
     *
     * <p>There used to be one public accessor per section, each three lines
     * long and each re-reading and re-parsing the whole file. Importing a
     * scenario called five of them plus the planning build, so a single click
     * parsed {@code festival-realiste.yaml} seven times — and adding a section
     * meant adding a seventh near-identical method. One record, one read.</p>
     *
     * @param parametresLegaux    lets a scenario pin the legal parameters it was
     *                            authored and verified against instead of silently
     *                            depending on whatever the database currently holds.
     *                            Absent fields fall back to {@link ParametresLegaux}'s
     *                            own defaults, never to the live value, so the
     *                            scenario stays reproducible on its own
     * @param parametresDecoupage generation-time only (never a solver problem fact,
     *                            see its javadoc), hence read separately and applied
     *                            by the scenario-import endpoint alone — it has no
     *                            place on {@link PlanningEvenement}
     * @param parametresSolveur   lets a large scenario pin the termination duration
     *                            it actually needs ({@code scenario-complet.yaml}
     *                            takes ~8 min to reach a good score) rather than
     *                            relying on the Données tab. Absent, the current
     *                            database value is left untouched
     * @param decoupageAuto       a scenario written in "amplitudes" (one long opening
     *                            window per day, e.g. {@code scenario-continu.yaml})
     *                            asks its import to slice itself into vacations,
     *                            instead of leaving the operator to run the
     *                            "Découpage" screen by hand afterwards
     * @param typologies          {@code {id, label}} pairs defining the scenario's own
     *                            typologie referential entries up front, instead of
     *                            leaving every referenced id to the id-as-its-own-label
     *                            default {@code ReferenceDataImportRepository#importFromPlanning}
     *                            derives on the fly. Empty, not absent, when the section
     *                            is missing: a list has no "absent" distinct from "empty"
     * @param edition             the edition the import must write into; absent means
     *                            the caller's current one
     * @param contraintes         which constraints the scenario switches off and how
     *                            it weights the others. Absent leaves the target
     *                            edition's own tuning alone; present, it replaces it
     *                            wholesale — a file that pins nothing but the section
     *                            itself re-enables everything, which is what "this is
     *                            the tuning this scenario was verified with" means
     */
    public record ScenarioSections(
            Optional<ParametresLegaux> parametresLegaux,
            Optional<ParametresDecoupage> parametresDecoupage,
            Optional<ParametresSolveur> parametresSolveur,
            boolean decoupageAuto,
            List<TypologieItem> typologies,
            Optional<dev.sylvain.planning.scenario.dto.EditionCibleDto> edition,
            Optional<ContraintesScenario> contraintes) {
    }

    /**
     * The {@code contraintes:} section of a scenario, parsed.
     *
     * @param desactivees names of the constraints to switch off
     * @param poids       weight per constraint name; what is absent keeps the
     *                    deployment default
     */
    public record ContraintesScenario(Set<String> desactivees, Map<String, Integer> poids) {
    }

    /** Reads {@link ScenarioSections} out of an already-parsed scenario document. */
    private static ScenarioSections sectionsOf(Map<String, Object> scenarioData) {
        return new ScenarioSections(
                parseParametresLegaux(scenarioData),
                parseParametresDecoupage(scenarioData),
                parseParametresSolveur(scenarioData),
                parseDecoupageAuto(scenarioData),
                parseTypologies(scenarioData),
                parseTargetEdition(scenarioData),
                parseContraintes(scenarioData));
    }

    /**
     * The optional sections of a bundled scenario, <b>without</b> building its
     * planning: the pre-import step that names the target edition, and the
     * cheap read the tests use to assert what a file pins.
     */
    public ScenarioSections loadScenarioSections(String scenarioName) {
        try {
            return sectionsOf(readScenarioData(cheminScenario(scenarioName)));
        } catch (IOException e) {
            throw new RuntimeException("Erreur lors du chargement du scénario YAML", e);
        }
    }

    /**
     * A bundled scenario, whole: its planning and its optional sections, from
     * a single parse. The named-file counterpart of
     * {@link #buildFromScenarioText}, so the two import paths differ
     * only in where the bytes come from.
     */
    public ScenarioImporte loadScenario(String scenarioName) {
        try {
            Map<String, Object> scenarioData = readScenarioData(cheminScenario(scenarioName));
            return new ScenarioImporte(buildPlanningFromData(scenarioData), sectionsOf(scenarioData));
        } catch (IOException e) {
            throw new RuntimeException("Erreur lors du chargement du scénario YAML", e);
        }
    }

    /**
     * Optional {@code edition:} section of an uploaded scenario text, parsed
     * alone — the pre-import step the UI uses to NAME the target edition in
     * its confirmation dialog, before anything is written.
     */
    public Optional<dev.sylvain.planning.scenario.dto.EditionCibleDto> loadEditionScenarioText(
            String yamlContent) {
        if (yamlContent == null || yamlContent.isBlank()) {
            throw new BusinessError.Invalid("Le fichier est vide.");
        }
        try {
            return parseTargetEdition(parserYaml(new java.io.ByteArrayInputStream(
                    yamlContent.getBytes(StandardCharsets.UTF_8))));
        } catch (RuntimeException | IOException e) {
            throw new BusinessError.Invalid("YAML invalide : " + messageOr(e), e);
        }
    }

    private static Optional<ParametresLegaux> parseParametresLegaux(Map<String, Object> scenarioData) {
        Map<String, Object> data = YamlSections.objet(scenarioData, "parametresLegaux");
        if (data == null) {
            return Optional.empty();
        }
        ParametresLegaux parametres = new ParametresLegaux();
        readInt(data, "dureeHebdomadaireMaxMinutes", parametres::setDureeHebdomadaireMaxMinutes);
        readInt(data, "pauseMinimaleEntreVacationsMinutes", parametres::setPauseMinimaleEntreVacationsMinutes);
        readInt(data, "reposQuotidienMinimalMinutes", parametres::setReposQuotidienMinimalMinutes);
        Object pauseSurPoste = data.get("pauseSurPoste");
        if (pauseSurPoste instanceof Boolean valeur) {
            parametres.setPauseSurPoste(valeur);
        } else if (pauseSurPoste != null) {
            throw new BusinessError.Invalid("parametresLegaux.pauseSurPoste must be true or false");
        }
        return Optional.of(parametres);
    }

    /** Applies the section's integer field to the setter, leaving the target's own default when absent. */
    private static void readInt(Map<String, Object> data, String key, IntConsumer setter) {
        Object valeur = data.get(key);
        if (valeur != null) {
            setter.accept(((Number) valeur).intValue());
        }
    }

    /** Same as {@link #readInt} for an {@code HH:MM:SS} field — see {@link #parseLocalTime(Object)}. */
    private static void readTime(Map<String, Object> data, String key, Consumer<LocalTime> setter) {
        Object valeur = data.get(key);
        if (valeur != null) {
            setter.accept(parseLocalTime(valeur));
        }
    }

    private static Optional<ParametresDecoupage> parseParametresDecoupage(Map<String, Object> scenarioData) {
        Map<String, Object> data = YamlSections.objet(scenarioData, "parametresDecoupage");
        if (data == null) {
            return Optional.empty();
        }
        ParametresDecoupage parametres = new ParametresDecoupage();
        readInt(data, "dureeVacationCibleMinutes", parametres::setDureeVacationCibleMinutes);
        readInt(data, "dureeVacationMinMinutes", parametres::setDureeVacationMinMinutes);
        readInt(data, "dureeVacationMaxMinutes", parametres::setDureeVacationMaxMinutes);
        readInt(data, "dureeChevauchementMinutes", parametres::setDureeChevauchementMinutes);
        readInt(data, "dureePauseRepasMinutes", parametres::setDureePauseRepasMinutes);
        readTime(data, "fenetreRepasMidiDebut", parametres::setFenetreRepasMidiDebut);
        readTime(data, "fenetreRepasMidiFin", parametres::setFenetreRepasMidiFin);
        readTime(data, "fenetreRepasSoirDebut", parametres::setFenetreRepasSoirDebut);
        readTime(data, "fenetreRepasSoirFin", parametres::setFenetreRepasSoirFin);
        if (data.get("strategieCouverturePendantPause") != null) {
            parametres.setStrategieCouverturePendantPause(ParametresDecoupage.PauseCoverageStrategy
                    .valueOf((String) data.get("strategieCouverturePendantPause")));
        }
        readInt(data, "nombreFamillesDecalage", parametres::setNombreFamillesDecalage);
        readInt(data, "dureeDecalageMaxMinutes", parametres::setDureeDecalageMaxMinutes);
        return Optional.of(parametres);
    }

    private static Optional<ParametresSolveur> parseParametresSolveur(Map<String, Object> scenarioData) {
        Map<String, Object> data = YamlSections.objet(scenarioData, "parametresSolveur");
        if (data == null || data.get("dureeResolutionSecondes") == null) {
            return Optional.empty();
        }
        return Optional.of(new ParametresSolveur(((Number) data.get("dureeResolutionSecondes")).intValue()));
    }

    /**
     * True when the scenario carries a top-level {@code decoupageAuto:}
     * section. Its historical {@code groupeSourceNom}/{@code groupeCibleNom}
     * fields are accepted and ignored (issue #172: the découpage replaces the
     * edition's créneaux in place, there are no groups to name anymore).
     */
    private static boolean parseDecoupageAuto(Map<String, Object> scenarioData) {
        // Key presence, not value truthiness: a bare `decoupageAuto:` (YAML
        // null) and the canonical `decoupageAuto: {}` both mean "slice on
        // import"; only an explicit `decoupageAuto: false` opts out.
        return scenarioData.containsKey("decoupageAuto")
                && !Boolean.FALSE.equals(scenarioData.get("decoupageAuto"));
    }

    private static Optional<dev.sylvain.planning.scenario.dto.EditionCibleDto> parseTargetEdition(
            Map<String, Object> scenarioData) {
        Object data = scenarioData.get("edition");
        if (data == null) {
            return Optional.empty();
        }
        // Pattern matching rather than a cast: a wildcard Map reads its own
        // values as Object, which is all this section needs, so there is
        // nothing left to suppress.
        if (!(data instanceof Map<?, ?> editionData)) {
            throw new BusinessError.Invalid(
                    "La section edition doit être un objet { id, nom? }, pas une valeur simple.");
        }
        String id = (String) editionData.get("id");
        if (id == null || id.isBlank()) {
            throw new BusinessError.Invalid("La section edition exige un champ id non vide.");
        }
        return Optional.of(new dev.sylvain.planning.scenario.dto.EditionCibleDto(
                id, (String) editionData.get("nom")));
    }

    /**
     * Reads {@code contraintes:} — {@code desactivees:} (a list of constraint
     * names) and {@code poids:} (name → weight).
     *
     * <p>An unknown name is refused rather than ignored: it means either a
     * typo or a file written against another version of the catalogue, and
     * silently dropping it would leave the operator convinced a rule was
     * switched off — or dosed — when it never was.</p>
     */
    private static Optional<ContraintesScenario> parseContraintes(Map<String, Object> scenarioData) {
        Map<String, Object> data = YamlSections.objet(scenarioData, "contraintes");
        if (data == null) {
            return Optional.empty();
        }
        Set<String> desactivees = new LinkedHashSet<>();
        List<Object> nomsData = YamlSections.valeurs(data, "desactivees");
        if (nomsData != null) {
            for (Object nom : nomsData) {
                desactivees.add(requireKnownConstraint(String.valueOf(nom)));
            }
        }
        Map<String, Integer> poids = new LinkedHashMap<>();
        Map<String, Object> poidsData = YamlSections.objet(data, "poids");
        if (poidsData != null) {
            for (Map.Entry<String, Object> entry : poidsData.entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                poids.put(requireKnownConstraint(entry.getKey()), ((Number) entry.getValue()).intValue());
            }
        }
        return Optional.of(new ContraintesScenario(desactivees, poids));
    }

    private static String requireKnownConstraint(String nom) {
        if (!DEFINITIONS_PAR_NOM.containsKey(nom)) {
            throw new BusinessError.Invalid(
                    "La section contraintes cite « " + nom + " », qui n'est pas une contrainte du catalogue.");
        }
        return nom;
    }

    /**
     * Reads {@code contraintesAdHoc:}, resolving the file's own animateur,
     * stand and créneau ids against the sections already parsed.
     *
     * <p>The créneau reference goes through {@code creneauxParId}: a
     * scenario's créneaux carry a text id in the file and get a synthetic
     * numeric one here (then a fresh database one on import, remapped by
     * {@code ReferenceDataImportRepository}). Resolving it any later would
     * leave the constraint pointing at nothing.</p>
     */
    private static List<ContrainteAdHoc> parseContraintesAdHoc(Map<String, Object> scenarioData,
            ReferenceScenario reference) {
        List<Map<String, Object>> data = YamlSections.objets(scenarioData, "contraintesAdHoc");
        if (data == null) {
            return null;
        }
        List<ContrainteAdHoc> contraintes = new ArrayList<>();
        for (Map<String, Object> item : data) {
            String id = (String) item.get("id");
            if (id == null || id.isBlank()) {
                throw new BusinessError.Invalid("Chaque contrainte ad hoc doit porter un id non vide.");
            }
            ContrainteAdHoc contrainte = new ContrainteAdHoc(id,
                    TypeContrainteAdHoc.valueOf((String) item.get("type")));
            List<Object> animateurs = YamlSections.valeurs(item, "animateurs");
            if (animateurs != null) {
                for (Object animateurId : animateurs) {
                    contrainte.getAnimateursConcernes().add(reference.animateurs().stream()
                            .filter(animateur -> animateur.getId().equals(animateurId))
                            .findFirst()
                            .orElseThrow(() -> new BusinessError.Invalid("La contrainte ad hoc " + id
                                    + " vise l'animateur " + animateurId + ", absent du scénario.")));
                }
            }
            // String.valueOf, not a cast: the créneau ids of a hand-authored
            // file are text ("J1-MATIN"), those of an exported one are the
            // numbers SnakeYAML hands back as Integer.
            Object creneauRef = item.get("creneauId");
            if (creneauRef != null) {
                String creneauId = String.valueOf(creneauRef);
                Creneau creneau = reference.creneauxParId().get(creneauId);
                if (creneau == null) {
                    throw new BusinessError.Invalid("La contrainte ad hoc " + id + " vise le créneau "
                            + creneauId + ", absent du scénario.");
                }
                contrainte.setCreneau(creneau);
            }
            String standId = (String) item.get("standId");
            if (standId != null) {
                Stand stand = reference.standsById().get(standId);
                if (stand == null) {
                    throw new BusinessError.Invalid("La contrainte ad hoc " + id + " vise le stand "
                            + standId + ", absent du scénario.");
                }
                contrainte.setStand(stand);
            }
            contrainte.setRaison((String) item.get("raison"));
            contraintes.add(contrainte);
        }
        return contraintes;
    }

    private static List<TypologieItem> parseTypologies(Map<String, Object> scenarioData) {
        List<Map<String, Object>> data = YamlSections.objets(scenarioData, "typologies");
        if (data == null) {
            return List.of();
        }
        List<TypologieItem> typologies = new ArrayList<>();
        for (Map<String, Object> typologieData : data) {
            typologies.add(new TypologieItem(
                    (String) typologieData.get("id"), (String) typologieData.get("label"),
                    Boolean.TRUE.equals(typologieData.get("ninja"))));
        }
        return typologies;
    }

    /**
     * SnakeYAML's default (YAML 1.1) resolver reads an unquoted {@code HH:MM:SS}
     * scalar as sexagesimal ({@code H*3600 + M*60 + S}), not as a string — so a
     * scenario author who doesn't think to quote {@code fenetreRepasMidiDebut:
     * 12:00:00} hands this parser an {@link Integer} (43200), not
     * {@code "12:00:00"}. Both forms are accepted here since the sexagesimal
     * value happens to equal the second-of-day, same as {@link LocalTime}'s own
     * representation.
     */
    /**
     * A scenario's textual id, read whatever type the YAML resolver gave it.
     *
     * <p>These ids only tie the sections of one file together, and the
     * published schema declares them {@code string} — but nothing forces an
     * author to write {@code id: "1"} rather than {@code id: 1}, and the export
     * itself produced the latter for a long time. A direct cast to
     * {@code String} then throws a {@link ClassCastException} on an otherwise
     * valid file.</p>
     *
     * <p>Same stance as {@link #parseLocalTime}: accept what YAML resolved,
     * rather than require the author to know the quoting rules of YAML 1.1.</p>
     */
    private static String parseTextId(Object value) {
        return value == null ? null : value.toString();
    }

    private static LocalTime parseLocalTime(Object value) {
        if (value instanceof Number number) {
            return LocalTime.ofSecondOfDay(number.longValue());
        }
        return LocalTime.parse(value.toString());
    }

    public PlanningEvenement solve(PlanningEvenement problem) {
        return solve(problem, null);
    }

    public PlanningEvenement solve(PlanningEvenement problem, Long secondsLimitOverride) {
        return solve(problem, secondsLimitOverride, null);
    }

    /**
     * Same as {@link #solve(PlanningEvenement, Long)}, but hands the freshly
     * built {@link Solver} to {@code onSolverReady} before blocking on
     * {@code solve()} — the only way a caller running this on a background
     * thread (see {@code SolverJobService}) can later call
     * {@link Solver#terminateEarly()} to stop a solve started by mistake.
     */
    public PlanningEvenement solve(PlanningEvenement problem, Long secondsLimitOverride,
            Consumer<Solver<PlanningEvenement>> onSolverReady) {
        prepareProblem(problem);
        Solver<PlanningEvenement> solver = resolveSolverFactory(secondsLimitOverride).buildSolver();
        if (onSolverReady != null) {
            onSolverReady.accept(solver);
        }
        return solver.solve(problem);
    }

    /**
     * Solves until the plan becomes hard-feasible (or {@code secondsLimitSecurite}
     * elapses, whichever comes first), instead of spending a full time budget on
     * medium/soft polishing. Large scenarios (e.g. {@code scenario-complet.yaml},
     * ~2000 postes) reach hard-feasibility in well under a minute but keep
     * improving medium/soft for the rest of a production-sized budget; a caller
     * that only cares about the hard score (e.g. a regression test) would
     * otherwise wait out that whole budget for nothing.
     */
    public PlanningEvenement solveUntilFeasible(PlanningEvenement problem, long secondsLimitSecurite) {
        prepareProblem(problem);
        SolverConfig solverConfig = SolverConfig.createFromXmlResource("solver/solverConfig.xml");
        solverConfig.setScoreDirectorFactoryConfig(new ScoreDirectorFactoryConfig()
                .withConstraintProviderClass(PlanningConstraintProvider.class));
        TerminationConfig termination = new TerminationConfig();
        termination.setSecondsSpentLimit(secondsLimitSecurite);
        termination.setBestScoreFeasible(true);
        solverConfig.setTerminationConfig(termination);
        Solver<PlanningEvenement> solver = SolverFactory.<PlanningEvenement>create(solverConfig).buildSolver();
        return solver.solve(problem);
    }

    private void prepareProblem(PlanningEvenement problem) {
        if (problem.getContraintesAdHoc() == null || problem.getContraintesAdHoc().isEmpty()) {
            problem.setContraintesAdHoc(referenceDataService.snapshotContraintes());
        }
        if (problem.getParametresLegaux() == null || problem.getParametresLegaux().isEmpty()) {
            problem.setParametresLegaux(List.of(referenceDataService.getParametresLegaux()));
        }
        if (problem.getConstraintsDesactivees() == null || problem.getConstraintsDesactivees().isEmpty()) {
            problem.setConstraintsDesactivees(referenceDataService.getContraintesDesactivees().stream()
                    .map(ConstraintToggle::new)
                    .toList());
        }
        // Server-side configuration, like the weights below: always overwritten
        // so a caller cannot loosen a quality threshold by sending its own.
        problem.setParametresQualite(List.of(new ParametresQualite(maxEmplacementsParJour)));
        // Never sent by a caller (the field is @JsonIgnore-d on PlanningEvenement),
        // so this always overwrites the ConstraintWeightOverrides.none() default.
        problem.setPonderationsContraintes(constraintWeightOverrides(problem.getPonderationsScenario()));
    }

    /**
     * Every constraint definition indexed by name, for {@link #explainAffectation}
     * and {@link #simulateSwap} to attach the business-facing niveau/catégorie/
     * description to a raw {@code ConstraintAnalysis} without a linear scan.
     */
    private static final Map<String, ConstraintCatalog.ConstraintDefinition> DEFINITIONS_PAR_NOM =
            ConstraintCatalog.definitions().stream()
                    .collect(Collectors.toUnmodifiableMap(ConstraintCatalog.ConstraintDefinition::name,
                            Function.identity()));

    /**
     * Per-assignment explainability ("Pourquoi lui ?"): every constraint match
     * of the already-solved {@code solved} planning whose justification facts
     * involve {@code posteId}, split into violated / not violated for that one
     * poste. "Respected" only means no violation was found for this poste, not
     * that the constraint is even applicable to it — the UI must present it as
     * such rather than as a positive endorsement.
     */
    public AffectationExplanation explainAffectation(PlanningEvenement solved, String posteId) {
        PosteAffectation poste = findPoste(solved, posteId);
        PlanningAnalysis analysis = constraintDiagnosticService.analyze(solved);
        String animateurId = poste.getAnimateur() == null ? null : poste.getAnimateur().getId();
        return new AffectationExplanation(posteId, animateurId, analysis.score(),
                impactsFor(analysis, poste, true), impactsFor(analysis, poste, false));
    }

    /**
     * Simulates giving {@code posteId} to {@code animateurCandidatId} instead
     * of its current occupant, and reports the resulting score delta plus how
     * that poste's own violated constraints change. The candidate substitution
     * is applied to {@code solved} only for the duration of the second
     * {@code analyze} call and reverted immediately after (the caller's object
     * graph is a throwaway per-request payload, never shared/cached, so a
     * temporary in-place mutation is safe and avoids a full deep copy of a
     * planning that can hold thousands of postes).
     */
    public SwapSimulation simulateSwap(PlanningEvenement solved, String posteId, String animateurCandidatId) {
        PosteAffectation poste = findPoste(solved, posteId);
        Animateur candidat = findAnimateur(solved, animateurCandidatId);
        Animateur actuel = poste.getAnimateur();

        PlanningAnalysis avant = constraintDiagnosticService.analyze(solved);
        List<ContrainteImpact> violeesAvant = impactsFor(avant, poste, true);

        PlanningAnalysis apres;
        poste.setAnimateur(candidat);
        try {
            apres = constraintDiagnosticService.analyze(solved);
        } finally {
            poste.setAnimateur(actuel);
        }
        List<ContrainteImpact> violeesApres = impactsFor(apres, poste, true);

        HardMediumSoftScore scoreAvant = avant.score();
        HardMediumSoftScore scoreApres = apres.score();
        return new SwapSimulation(posteId, actuel == null ? null : actuel.getId(), animateurCandidatId,
                scoreAvant, scoreApres, scoreApres.subtract(scoreAvant), violeesAvant, violeesApres);
    }


    /**
     * How many candidates {@link #suggererReparations} simulates when the
     * caller names no plafond. Every candidate costs one full
     * {@link ConstraintDiagnosticService#analyze} over the whole planning, so
     * the endpoint's cost is linear in this number and in nothing else — the
     * eligible pool may well be the entire referential (~150 animateurs on the
     * reference scenario).
     */
    public static final int SUGGESTIONS_PLAFOND_DEFAUT = 20;

    /** Ceiling a caller may raise the plafond to, so no single request can pay 150 analyses. */
    public static final int SUGGESTIONS_PLAFOND_MAX = 100;

    /** {@link ConstraintCatalog.Niveau#HARD} as {@link ContrainteImpact} spells it. */
    private static final String DUR = ConstraintCatalog.Niveau.HARD.name();

    /**
     * Repair suggestions for one poste (issue #71): the loop that <b>looks for</b>
     * candidates, where {@link #simulateSwap} only scores the one it is handed.
     * Every eligible animateur is substituted in turn on {@code posteId},
     * candidates that would worsen the plan's hard score <b>or introduce a hard
     * violation on that very seat</b> are dropped, and what survives is returned
     * best impact first. Nothing is persisted — applying a suggestion is the
     * separate, explicit {@link #applyReparation}.
     *
     * <p><b>Bounded on purpose.</b> Only the first {@code plafond} eligible
     * candidates are simulated (see {@link #SUGGESTIONS_PLAFOND_DEFAUT}); the
     * result carries both counts so the caller can say "the 20 most promising of
     * 137" rather than pass a truncated list off as exhaustive.</p>
     *
     * @param plafondDemande {@code null} or non-positive falls back to the
     *                       default, anything above {@link #SUGGESTIONS_PLAFOND_MAX} is clamped
     */
    public SuggestionsReparation suggererReparations(PlanningEvenement solved, String posteId,
            Integer plafondDemande) {
        PosteAffectation poste = findPoste(solved, posteId);
        Animateur actuel = poste.getAnimateur();
        int plafond = effectiveCandidateCap(plafondDemande);

        PlanningAnalysis avant = constraintDiagnosticService.analyze(solved);
        HardMediumSoftScore scoreAvant = avant.score();
        List<ContrainteImpact> violeesAvant = impactsFor(avant, poste, true);
        Set<String> nomsAvant = violeesAvant.stream().map(ContrainteImpact::name).collect(Collectors.toSet());

        List<Animateur> eligibles = candidatsEligibles(solved, poste);
        List<Animateur> evalues = eligibles.size() > plafond ? eligibles.subList(0, plafond) : eligibles;

        List<SuggestionReparation> suggestions = new ArrayList<>();
        for (Animateur candidat : evalues) {
            // Same throwaway in-place substitution as simulateSwap, reverted in
            // the finally: the planning is a per-request payload, never shared.
            PlanningAnalysis apres;
            poste.setAnimateur(candidat);
            try {
                apres = constraintDiagnosticService.analyze(solved);
            } finally {
                poste.setAnimateur(actuel);
            }
            HardMediumSoftScore scoreApres = apres.score();
            // The verdict is planning-wide, like simulateEchange's: moving this
            // seat can break a hard constraint on a poste it does not touch
            // (weekly hours, rest periods), which the poste's own matches would
            // never show.
            if (scoreApres.hardScore() < scoreAvant.hardScore()) {
                continue;
            }
            List<ContrainteImpact> violeesApres = impactsFor(apres, poste, true);
            Set<String> nomsApres = violeesApres.stream().map(ContrainteImpact::name).collect(Collectors.toSet());
            List<ContrainteImpact> introduites =
                    violeesApres.stream().filter(impact -> !nomsAvant.contains(impact.name())).toList();
            // The planning-wide test above is not enough on an empty seat: filling
            // it settles one hard point (posteDoitEtrePourvu) and can spend it on
            // another, leaving the global hard score flat while the candidate
            // plainly breaks a rule on this very poste — an animateur forced
            // unavailable on that timeslot being the case issue #297 walks into.
            // The invariant SuggestionReparation states is therefore enforced
            // here, not merely hoped for: a suggestion never introduces a hard
            // violation on the seat it repairs.
            if (introduites.stream().anyMatch(impact -> DUR.equals(impact.niveau()))) {
                continue;
            }
            suggestions.add(new SuggestionReparation(candidat.getId(), scoreApres,
                    scoreApres.subtract(scoreAvant),
                    violeesAvant.stream().filter(impact -> !nomsApres.contains(impact.name())).toList(),
                    introduites));
        }
        suggestions.sort(Comparator
                .comparing(SuggestionReparation::delta, Comparator.<HardMediumSoftScore>naturalOrder().reversed())
                .thenComparing(SuggestionReparation::animateurId, NaturalOrder.DES_IDS));
        return new SuggestionsReparation(posteId, actuel == null ? null : actuel.getId(), scoreAvant,
                violeesAvant, eligibles.size(), evalues.size(), plafond, List.copyOf(suggestions));
    }

    private static int effectiveCandidateCap(Integer demande) {
        if (demande == null || demande <= 0) {
            return SUGGESTIONS_PLAFOND_DEFAUT;
        }
        return Math.min(demande, SUGGESTIONS_PLAFOND_MAX);
    }

    /**
     * Who may be simulated on {@code poste}, most promising first: every
     * animateur but its current occupant, keeping only those
     * {@link EligibleAnimateurMoveFilter#isEligible} accepts — reusing the
     * solver's own notion of a viable candidate rather than restating it, so
     * the two can never drift apart.
     *
     * <p>The order matters because the caller truncates: animateurs free at
     * that moment come first, since handing them the seat cannot create the
     * overlap that anyone already busy then would. Natural id order breaks ties
     * so the same call twice returns the same list.</p>
     */
    private static List<Animateur> candidatsEligibles(PlanningEvenement solved, PosteAffectation poste) {
        String actuelId = poste.getAnimateur() == null ? null : poste.getAnimateur().getId();
        Set<String> occupes = animateursOccupesPendant(solved, poste);
        return solved.getAnimateurs().stream()
                .filter(animateur -> !animateur.getId().equals(actuelId))
                .filter(animateur -> EligibleAnimateurMoveFilter.isEligible(poste, animateur,
                        solved.pauseSurPosteActive()))
                .sorted(Comparator.comparing((Animateur animateur) -> occupes.contains(animateur.getId()))
                        .thenComparing(Animateur::getId, NaturalOrder.DES_IDS))
                .toList();
    }

    /**
     * Ids of the animateurs already holding a seat whose effective window
     * overlaps {@code poste}'s — the very overlap {@code pasDeChevauchementHoraire}
     * penalises, compared the same way (effective start plus effective
     * duration, so a window crossing midnight ends the next day).
     *
     * <p>Only used to <em>rank</em> candidates: being busy is not an exclusion,
     * since a busy candidate may still be the least bad repair and the
     * simulation is what decides.</p>
     */
    private static Set<String> animateursOccupesPendant(PlanningEvenement solved, PosteAffectation poste) {
        if (!horaireConnu(poste)) {
            return Set.of();
        }
        LocalDateTime debut = debutEffectif(poste);
        LocalDateTime fin = debut.plusMinutes(poste.getDureeEffectiveMinutes());
        Set<String> occupes = new HashSet<>();
        for (PosteAffectation autre : solved.getPostes()) {
            if (autre == poste || autre.getAnimateur() == null || !horaireConnu(autre)) {
                continue;
            }
            LocalDateTime autreDebut = debutEffectif(autre);
            if (autreDebut.isBefore(fin) && autreDebut.plusMinutes(autre.getDureeEffectiveMinutes()).isAfter(debut)) {
                occupes.add(autre.getAnimateur().getId());
            }
        }
        return occupes;
    }

    private static boolean horaireConnu(PosteAffectation poste) {
        return poste.getCreneau() != null && poste.getCreneau().getDate() != null
                && poste.getCreneau().getHeureDebut() != null;
    }

    private static LocalDateTime debutEffectif(PosteAffectation poste) {
        return LocalDateTime.of(poste.getCreneau().getDate(), poste.heureDebutEffectif());
    }

    /**
     * The « banc de touche » of one créneau (issue #303): everyone <b>not</b>
     * on duty then, and — seat by seat — why they could not be.
     *
     * <p><b>Read-only, and derived, not restated.</b> Every reason returned is
     * the name of a constraint {@code PlanningConstraintProvider} actually
     * enforces, obtained one of the two ways this application already has of
     * asking the rules rather than repeating them:</p>
     * <ol>
     * <li>{@link EligibleAnimateurMoveFilter#motifs} for what the (poste,
     *     animateur) pair alone decides — the very predicate the solver's move
     *     filters and {@code candidatsEligibles} use, so an animateur this
     *     screen refuses is one the repair assistant never proposes;</li>
     * <li>{@link ConstraintDiagnosticService#hypotheses} for everything that
     *     depends on the rest of the plan (daily and weekly caps, rest,
     *     breaks, overlaps, adult supervision, appreciation): the seat is
     *     handed to each candidate in turn and the constraints are asked what
     *     changed. Nothing here knows what a cap is worth.</li>
     * </ol>
     *
     * <p><b>Both readings are measured against the seat being empty</b>, which
     * is what keeps them honest: against the current occupant, a candidate
     * breaking the very rule that occupant already breaks leaves the
     * per-constraint totals flat and comes back with nothing against them. See
     * {@link ConstraintDiagnosticService#hypotheses}.</p>
     *
     * <p>{@code AnimateurAvailability.disponible} is <b>stricter</b> than
     * {@link #suggererReparations}, and {@code CreneauAvailabilityCoherenceTest}
     * proves the implication that follows: anyone this screen shows as
     * available is a candidate the repair assistant proposes for the same seat.
     * See {@link AnimateurAvailability} for why the converse is deliberately
     * not claimed.</p>
     *
     * <p>All applicable reasons are listed, not the first one found: three
     * reasons and one reason are different situations for whoever has to fill
     * the seat, and only the full list says whether lifting one obstacle would
     * be enough.</p>
     *
     * @param standId optional — narrows which seat of the créneau is probed
     * @param posteId optional — names that seat outright; wins over {@code standId}
     */
    public CreneauAvailability creneauAvailability(PlanningEvenement solved, Long creneauId, String standId,
            String posteId) {
        List<CreneauSiege> creneauxAvecSieges = staffedCreneaux(solved);
        // No créneau asked for: answer on the first one that has something to
        // show, rather than making the screen guess an id it cannot know before
        // its first call. A screen that only ever offers staffed créneaux has
        // no way to pick a valid default on its own.
        Long cibleId = creneauId != null ? creneauId
                : creneauxAvecSieges.stream().map(CreneauSiege::id).findFirst().orElse(null);
        if (cibleId == null) {
            return new CreneauAvailability(null, SeatStatus.NO_PLAN, null, null, null, 0, 0,
                    creneauxAvecSieges, List.of());
        }
        List<PosteAffectation> postesDuCreneau = solved.getPostes().stream()
                .filter(poste -> poste.getCreneau() != null
                        && Objects.equals(poste.getCreneau().getId(), cibleId))
                .toList();
        PosteAffectation cible = targetSeat(solved, postesDuCreneau, cibleId, standId, posteId);
        if (cible == null) {
            return new CreneauAvailability(cibleId,
                    solved.getPostes().isEmpty() ? SeatStatus.NO_PLAN : SeatStatus.NO_SEAT,
                    null, null, null, 0, 0, creneauxAvecSieges, List.of());
        }

        Set<String> deja = postesDuCreneau.stream()
                .map(PosteAffectation::getAnimateur)
                .filter(Objects::nonNull)
                .map(Animateur::getId)
                .collect(Collectors.toSet());
        List<Animateur> banc = solved.getAnimateurs().stream()
                .filter(animateur -> !deja.contains(animateur.getId()))
                .sorted(Comparator.comparing(Animateur::getId, NaturalOrder.DES_IDS))
                .toList();

        // The occupant is probed alongside the bench, and for one reason: the
        // hypotheses are measured against the seat being EMPTY (the only
        // baseline a candidate cannot hide behind — see
        // ConstraintDiagnosticService#hypotheses), while « what would this cost
        // compared to today » is measured against the plan as it stands. Asking
        // what the occupant himself costs on his own seat is exactly that
        // reference, at the price of one more candidate.
        Animateur titulaire = cible.getAnimateur();
        List<Animateur> sondes = titulaire == null
                ? banc
                : Stream.concat(Stream.of(titulaire), banc.stream()).toList();
        Map<String, AffectationHypothesis> hypotheses = constraintDiagnosticService
                .hypotheses(solved, cible, sondes).stream()
                .collect(Collectors.toMap(AffectationHypothesis::animateurId, Function.identity()));
        HardMediumSoftScore reference = titulaire == null || hypotheses.get(titulaire.getId()) == null
                ? HardMediumSoftScore.ZERO
                : hypotheses.get(titulaire.getId()).delta();

        List<AnimateurAvailability> lignes = new ArrayList<>(banc.size());
        for (Animateur animateur : banc) {
            AffectationHypothesis hypothese = hypotheses.get(animateur.getId());
            Set<String> contraintes = new LinkedHashSet<>();
            for (EligibleAnimateurMoveFilter.Motif motif : EligibleAnimateurMoveFilter.motifs(cible, animateur,
                    solved.pauseSurPosteActive())) {
                contraintes.add(motif.contrainte());
            }
            if (hypothese != null) {
                contraintes.addAll(hypothese.contraintesAggravees());
            }
            List<MotifExclusion> motifs = contraintes.stream().map(PlanningService::motifExclusion).toList();
            boolean disponible = motifs.stream().noneMatch(PlanningService::isHardRule);
            HardMediumSoftScore delta = hypothese == null ? null : hypothese.delta().subtract(reference);
            boolean degradeLePlan = delta != null && delta.hardScore() < 0;
            lignes.add(new AnimateurAvailability(animateur.getId(), disponible, degradeLePlan, delta, motifs));
        }
        // Available first: this screen is opened to find someone, and the
        // people who can take the seat without breaking anything are the
        // answer — the refusals are the explanation of why the list is short.
        lignes.sort(Comparator.comparing(AnimateurAvailability::disponible, Comparator.reverseOrder())
                .thenComparing(AnimateurAvailability::degradeLePlan)
                .thenComparing(AnimateurAvailability::animateurId, NaturalOrder.DES_IDS));
        int disponibles = (int) lignes.stream().filter(AnimateurAvailability::disponible).count();
        return new CreneauAvailability(cibleId, SeatStatus.EVALUATED, cible.getId(),
                cible.getStand() == null ? null : cible.getStand().getId(),
                cible.getAnimateur() == null ? null : cible.getAnimateur().getId(),
                lignes.size(), disponibles, creneauxAvecSieges, List.copyOf(lignes));
    }

    /**
     * Same, on the last persisted plan — what the screen calls, with no payload
     * of its own. Prepared like a solve would prepare it (ad hoc constraints,
     * legal parameters, toggles, weights) so the hypotheses are scored against
     * the rules currently in force, not against defaults.
     *
     * <p>This is the <b>only</b> place that can tell an unknown créneau from a
     * créneau carrying no seat, because only here is the referential in reach:
     * the persisted plan holds seats, so a créneau nobody was scheduled on
     * simply does not appear in it. Getting that distinction wrong is what made
     * the screen open on « Créneau inconnu » for a perfectly real créneau on
     * which no stand happened to be open.</p>
     *
     * @throws BusinessError.NotFound when the créneau exists in no edition data
     *         at all — the one case that really is a bad request
     */
    public CreneauAvailability persistedCreneauAvailability(Long creneauId, String standId, String posteId) {
        if (creneauId != null && referenceDataService.listCreneaux().stream()
                .noneMatch(creneau -> Objects.equals(creneau.getId(), creneauId))) {
            throw new BusinessError.NotFound("Créneau inconnu: " + creneauId);
        }
        PlanningEvenement persiste = planningPersistenceService.loadPersistedPlanning();
        prepareProblem(persiste);
        return creneauAvailability(persiste, creneauId, standId, posteId);
    }

    /**
     * The seat the hypotheses are evaluated on: the one named, else the first
     * unfilled seat of the créneau (of {@code standId} when given), else its
     * first seat — probing an occupied seat is the « qui pourrait le
     * remplacer ? » question, which is exactly what
     * {@link #suggererReparations} answers on the same poste.
     *
     * <p>{@code null} when the plan holds no seat to probe: on this créneau at
     * all, or on the stand asked for. That is <b>an answer, not a refusal</b> —
     * a créneau on which no stand is open, or one added after the last solve,
     * legitimately carries none, and this screen exists precisely to say so.
     * Only a caller-supplied {@code posteId} can still be wrong enough to be
     * refused, and it is not something a user types.</p>
     *
     * <p>Ordered by id so the same request twice probes the same seat.</p>
     */
    private static PosteAffectation targetSeat(PlanningEvenement solved, List<PosteAffectation> postesDuCreneau,
            long creneauId, String standId, String posteId) {
        if (posteId != null && !posteId.isBlank()) {
            PosteAffectation poste = findPoste(solved, posteId);
            if (poste.getCreneau() == null || !Objects.equals(poste.getCreneau().getId(), creneauId)) {
                throw new BusinessError.Invalid(
                        "Le poste " + posteId + " n'appartient pas au créneau " + creneauId + ".");
            }
            return poste;
        }
        List<PosteAffectation> candidats = standId == null || standId.isBlank()
                ? postesDuCreneau
                : postesDuCreneau.stream()
                        .filter(poste -> poste.getStand() != null && standId.equals(poste.getStand().getId()))
                        .toList();
        Comparator<PosteAffectation> byId = Comparator.comparing(PosteAffectation::getId, NaturalOrder.DES_IDS);
        return candidats.stream()
                .filter(poste -> poste.getAnimateur() == null)
                .min(byId)
                .or(() -> candidats.stream().min(byId))
                .orElse(null);
    }

    /**
     * The créneaux the saved plan holds at least one seat on, described rather
     * than merely named — they are what the screen's selector is built from.
     *
     * <p>Sending descriptors, and only these, is the point: the référentiel
     * holds every créneau of the edition (354 vacations on the reference
     * scenario), the plan covers a fraction of them, and a créneau nothing is
     * scheduled on has nothing to show. The screen used to pull the whole
     * référentiel and offer all of it, which is how a user landed on a créneau
     * the answer could only be empty for.</p>
     *
     * <p>Ordered as a day is read — date, then start time, then id — so the
     * selector needs no ordering rule of its own.</p>
     */
    private static List<CreneauSiege> staffedCreneaux(PlanningEvenement solved) {
        Map<Long, Creneau> parId = new LinkedHashMap<>();
        for (PosteAffectation poste : solved.getPostes()) {
            Creneau creneau = poste.getCreneau();
            if (creneau != null && creneau.getId() != null) {
                parId.putIfAbsent(creneau.getId(), creneau);
            }
        }
        return parId.values().stream()
                .sorted(Comparator.comparing(Creneau::getDate, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Creneau::getHeureDebut, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Creneau::getId))
                .map(creneau -> new CreneauSiege(creneau.getId(), creneau.getJour(), creneau.getDate(),
                        creneau.getHeureDebut(), creneau.getHeureFin(), creneau.getFamille()))
                .toList();
    }

    /** True for a reason the catalogue rates as a hard rule — the ones « disponible » may not hide. */
    private static boolean isHardRule(MotifExclusion motif) {
        return ConstraintCatalog.Niveau.HARD.name().equals(motif.niveau());
    }

    /** A constraint name dressed with the business wording {@link ConstraintCatalog} already holds for it. */
    private static MotifExclusion motifExclusion(String contrainte) {
        ConstraintCatalog.ConstraintDefinition definition = DEFINITIONS_PAR_NOM.get(contrainte);
        return new MotifExclusion(contrainte,
                definition == null ? null : definition.niveau().name(),
                definition == null ? null : definition.categorie(),
                definition == null ? null : definition.description());
    }

    /**
     * Applies one repair suggestion to the <b>persisted</b> plan (issue #71):
     * the seat changes hands and nothing else does, which is exactly the plan
     * {@link #suggererReparations} scored. A single surgical {@code UPDATE},
     * no solve, no rewrite of the rest of the plan.
     *
     * <p>Refused on a locked seat (issue #87): a verrouillage is the operator
     * saying "this one does not move", and a one-click assistant that quietly
     * overrode it would undo a decision the next solve is then told to
     * restore.</p>
     *
     * @param animateurId {@code null} empties the seat
     */
    public void applyReparation(String posteId, String animateurId) {
        applyReparations(planningPersistenceService.loadPersistedPlanning(), List.of(posteId), animateurId);
    }

    /**
     * The same write as {@link #applyReparation}, over several seats and over a
     * plan the caller already holds.
     *
     * <p>Reading the plan is what costs here — 1 800 seats resolved against the
     * whole referential — and {@code applyReparation} pays it per call. Freeing
     * the five seats of somebody absent for the rest of the day therefore paid
     * it five times, on the one screen (issue #297) whose reason to exist is
     * answering fast on a phone. Same checks, same single {@code UPDATE} per
     * seat, one read.</p>
     *
     * <p>Every seat is validated <b>before</b> the first write, so a lock on the
     * third one does not leave the first two reassigned.</p>
     *
     * @param animateurId {@code null} empties the seats
     */
    public void applyReparations(PlanningEvenement persiste, List<String> posteIds, String animateurId) {
        List<PosteAffectation> postes = posteIds.stream().map(id -> findPoste(persiste, id)).toList();
        if (animateurId != null) {
            findAnimateur(persiste, animateurId);
        }
        List<VerrouillagePlanning> verrouillages = referenceDataService.listVerrouillages();
        for (PosteAffectation poste : postes) {
            if (verrouillages.stream().anyMatch(verrouillage -> verrouillage.couvre(poste))) {
                throw new BusinessError.Invalid(
                        "Ce poste est verrouillé : déverrouillez-le avant d'y appliquer une réparation.");
            }
        }
        for (PosteAffectation poste : postes) {
            planningPersistenceService.reaffecterPoste(poste.getId(), animateurId);
        }
    }
    /**
     * Simulates a demande d'échange (issue #165) on an already-solved planning:
     * the demandeur's seat on ({@code creneauId}, {@code standId}) goes to
     * {@code cibleId}, and — when the target also works that créneau — their own
     * seat goes to the demandeur (échange croisé). Nothing is persisted; the
     * substitution lives only for the second {@code analyze} call, exactly like
     * {@link #simulateSwap}.
     *
     * <p>Unlike {@code simulateSwap}'s per-poste view, the verdict here is
     * planning-wide: a swap can break a hard constraint on a poste it does not
     * touch (weekly hours, rest periods…), so feasibility is judged on the
     * global hard score and the extra hard matches, not on the two seats
     * alone.</p>
     */
    public EchangeSimulation simulateEchange(PlanningEvenement solved, String demandeurId, String cibleId,
            long creneauId, String standId) {
        PosteAffectation posteDemandeur = solved.getPostes().stream()
                .filter(poste -> poste.getStand() != null && standId.equals(poste.getStand().getId())
                        && poste.getCreneau() != null && poste.getCreneau().getId() != null
                        && poste.getCreneau().getId() == creneauId
                        && poste.getAnimateur() != null && demandeurId.equals(poste.getAnimateur().getId()))
                .findFirst()
                .orElseThrow(() -> new BusinessError.Invalid(
                        "Aucun poste de l'animateur " + demandeurId + " sur ce créneau et ce stand"));
        Animateur demandeur = posteDemandeur.getAnimateur();
        // Invalid and not NotFound, unlike the lookups of
        // explainAffectation/simulateSwap: there the id is the path of the
        // resource being asked for, so an unknown one means "no such thing
        // here" (404). Here it is a field of a submitted demande, so an
        // unknown one means "your form is wrong" (400) — the same answer as
        // the sibling check just above.
        Animateur target = solved.getAnimateurs().stream()
                .filter(animateur -> animateur.getId().equals(cibleId))
                .findFirst()
                .orElseThrow(() -> new BusinessError.Invalid("Animateur inconnu: " + cibleId));
        PosteAffectation posteCible = solved.getPostes().stream()
                .filter(poste -> poste != posteDemandeur
                        && poste.getCreneau() != null && poste.getCreneau().getId() != null
                        && poste.getCreneau().getId() == creneauId
                        && poste.getAnimateur() != null && cibleId.equals(poste.getAnimateur().getId()))
                .findFirst()
                .orElse(null);

        PlanningAnalysis avant = constraintDiagnosticService.analyze(solved);
        PlanningAnalysis apres;
        posteDemandeur.setAnimateur(target);
        if (posteCible != null) {
            posteCible.setAnimateur(demandeur);
        }
        try {
            apres = constraintDiagnosticService.analyze(solved);
        } finally {
            posteDemandeur.setAnimateur(demandeur);
            if (posteCible != null) {
                posteCible.setAnimateur(target);
            }
        }

        HardMediumSoftScore scoreAvant = avant.score();
        HardMediumSoftScore scoreApres = apres.score();
        return new EchangeSimulation(
                posteDemandeur.getId(),
                posteCible == null ? null : posteCible.getId(),
                posteCible != null,
                posteCible == null ? null : posteCible.getStand().getId(),
                scoreAvant, scoreApres, scoreApres.subtract(scoreAvant),
                scoreApres.hardScore() < scoreAvant.hardScore(),
                extraHardViolations(avant, apres));
    }

    /**
     * Directed variant of {@link #simulateEchange}: the demandeur's seat on
     * (créneau, stand) goes to the target, and the CIBLE'S seat on
     * (créneau target, stand target) goes to the demandeur — two different
     * créneaux, "I give you my Monday, I take your Tuesday". Both seats must
     * exist; feasibility is judged planning-wide like the plain variant.
     */
    public EchangeSimulation simulateDirectedEchange(PlanningEvenement solved, String demandeurId, String cibleId,
            long creneauId, String standId, long creneauCibleId, String standCibleId) {
        PosteAffectation posteDemandeur = posteOf(solved, demandeurId, creneauId, standId);
        PosteAffectation posteCible = posteOf(solved, cibleId, creneauCibleId, standCibleId);
        Animateur demandeur = posteDemandeur.getAnimateur();
        Animateur target = posteCible.getAnimateur();

        PlanningAnalysis avant = constraintDiagnosticService.analyze(solved);
        PlanningAnalysis apres;
        posteDemandeur.setAnimateur(target);
        posteCible.setAnimateur(demandeur);
        try {
            apres = constraintDiagnosticService.analyze(solved);
        } finally {
            posteDemandeur.setAnimateur(demandeur);
            posteCible.setAnimateur(target);
        }

        HardMediumSoftScore scoreAvant = avant.score();
        HardMediumSoftScore scoreApres = apres.score();
        return new EchangeSimulation(
                posteDemandeur.getId(),
                posteCible.getId(),
                true,
                posteCible.getStand().getId(),
                scoreAvant, scoreApres, scoreApres.subtract(scoreAvant),
                scoreApres.hardScore() < scoreAvant.hardScore(),
                extraHardViolations(avant, apres));
    }

    /**
     * What an animateur could really do with a créneau they do not want (the
     * espace's « qui peut me remplacer ? »): the same search as
     * {@link #suggererReparations}, but for an <b>échange</b> — the demandeur
     * names only their own seat, and each way out is tried in turn, scored
     * exactly as {@link #simulateEchange} would score the one they had named
     * themselves.
     *
     * <p>Three ways out, because an échange is not only « quelqu'un prend ma
     * place » (see {@link NatureEchange}): being freed outright, trading seats
     * on that same créneau, or trading it against a colleague's seat on
     * <b>another day</b>. All three are enumerated and all three are scored
     * the same way, so the answer never silently omits a family.</p>
     *
     * <p>Options that would worsen the plan's hard score are dropped, so what
     * comes back is viable in the current planning, not merely plausible. The
     * verdict is planning-wide for the same reason as everywhere else here:
     * moving these seats can break a rule on a poste they do not touch (weekly
     * hours, rest), which the seats' own matches would never show.</p>
     *
     * <p>Nothing is persisted and no demande is created: the animateur still
     * picks one and submits, and the colleague still has to agree.</p>
     *
     * <p><b>Bounded like the repair assistant</b>, and for the same reason —
     * one full analyse per option. The three families are evaluated
     * <b>round-robin</b> rather than one after the other: a roster of 150 free
     * colleagues would otherwise spend the whole plafond on « on vous libère »
     * and never once ask whether a Tuesday could be traded for a Monday. The
     * result carries both counts so the caller can say « les 20 pistes les plus
     * prometteuses sur 400 » instead of passing a truncated list off as the
     * whole truth.</p>
     */
    public SuggestionsEchange suggererEchanges(PlanningEvenement solved, String demandeurId,
            long creneauId, String standId, Integer plafondDemande) {
        PosteAffectation posteDemandeur = posteOf(solved, demandeurId, creneauId, standId);
        Animateur demandeur = posteDemandeur.getAnimateur();
        int plafond = effectiveCandidateCap(plafondDemande);

        PlanningAnalysis avant = constraintDiagnosticService.analyze(solved);
        HardMediumSoftScore scoreAvant = avant.score();

        List<OptionEchange> eligibles = optionsEchange(solved, posteDemandeur);
        List<OptionEchange> evaluees = eligibles.size() > plafond ? eligibles.subList(0, plafond) : eligibles;

        List<SuggestionEchange> suggestions = new ArrayList<>();
        for (OptionEchange option : evaluees) {
            PosteAffectation siege = option.siege();
            // Same throwaway in-place substitution as simulateEchange, reverted
            // in the finally: the planning is a per-request payload.
            PlanningAnalysis apres;
            posteDemandeur.setAnimateur(option.animateur());
            if (siege != null) {
                siege.setAnimateur(demandeur);
            }
            try {
                apres = constraintDiagnosticService.analyze(solved);
            } finally {
                posteDemandeur.setAnimateur(demandeur);
                if (siege != null) {
                    siege.setAnimateur(option.animateur());
                }
            }
            HardMediumSoftScore scoreApres = apres.score();
            if (scoreApres.hardScore() < scoreAvant.hardScore()) {
                continue;
            }
            suggestions.add(new SuggestionEchange(option.animateur().getId(), option.nature(),
                    option.nature() == NatureEchange.DIRIGE ? siege.getCreneau().getId() : null,
                    siege == null ? null : siege.getStand().getId(),
                    scoreApres, scoreApres.subtract(scoreAvant)));
        }
        // Grouped by family, which is how the espace lists them, then best
        // impact on the plan first and a stable id order to break ties.
        suggestions.sort(Comparator.comparing(SuggestionEchange::nature)
                .thenComparing(SuggestionEchange::delta, Comparator.<HardMediumSoftScore>naturalOrder().reversed())
                .thenComparing(SuggestionEchange::animateurId, NaturalOrder.DES_IDS));
        return new SuggestionsEchange(creneauId, standId, scoreAvant,
                eligibles.size(), evaluees.size(), plafond, List.copyOf(suggestions));
    }

    /**
     * One way out of a créneau, before it is scored: who takes it, and which
     * seat — if any — comes back in return.
     *
     * @param siege {@code null} for {@link NatureEchange#LIBERE}; the
     *              colleague's seat on the same créneau for
     *              {@link NatureEchange#CROISE}; one of their seats elsewhere
     *              for {@link NatureEchange#DIRIGE}
     */
    private record OptionEchange(Animateur animateur, PosteAffectation siege, NatureEchange nature) {
    }

    /**
     * How many seats of a single colleague are offered as a trade in return.
     * A colleague holding fifteen seats would otherwise eat the whole DIRIGE
     * share of the plafond on their own, and the animateur would be shown one
     * name where they wanted a choice of days.
     */
    private static final int SIEGES_DIRIGES_PAR_COLLEGUE = 2;

    /**
     * Every way out of {@code posteDemandeur}, the most promising of each
     * family first, then the three families interleaved.
     *
     * <p>Both halves of every trade go through the solver's own
     * {@link EligibleAnimateurMoveFilter}: the colleague must be eligible on
     * the demandeur's seat, and the demandeur on whatever seat comes back.
     * Filtering here rather than by the score keeps the plafond for options
     * that stand a chance.</p>
     *
     * <p>Within LIBERE, colleagues free at that hour come first — the only
     * ones who cannot create an overlap by taking the seat.</p>
     */
    private static List<OptionEchange> optionsEchange(PlanningEvenement solved,
            PosteAffectation posteDemandeur) {
        Animateur demandeur = posteDemandeur.getAnimateur();
        long creneauId = posteDemandeur.getCreneau().getId();
        Set<String> occupes = animateursOccupesPendant(solved, posteDemandeur);
        // Indexed once: the alternative rescans the whole poste list per
        // colleague, twice, on a planning that holds a couple of thousand.
        Map<String, List<PosteAffectation>> siegesParAnimateur = solved.getPostes().stream()
                .filter(poste -> poste.getAnimateur() != null && poste.getStand() != null
                        && poste.getCreneau() != null && poste.getCreneau().getId() != null)
                .collect(Collectors.groupingBy(poste -> poste.getAnimateur().getId()));

        List<OptionEchange> liberent = new ArrayList<>();
        List<OptionEchange> croises = new ArrayList<>();
        List<OptionEchange> diriges = new ArrayList<>();
        List<Animateur> collegues = solved.getAnimateurs().stream()
                .filter(collegue -> !collegue.getId().equals(demandeur.getId()))
                .filter(collegue -> EligibleAnimateurMoveFilter.isEligible(posteDemandeur, collegue,
                        solved.pauseSurPosteActive()))
                .sorted(Comparator.comparing(Animateur::getId, NaturalOrder.DES_IDS))
                .toList();
        for (Animateur collegue : collegues) {
            List<PosteAffectation> sieges = siegesParAnimateur.getOrDefault(collegue.getId(), List.of());
            PosteAffectation memeCreneau = sieges.stream()
                    .filter(poste -> poste != posteDemandeur && poste.getCreneau().getId() == creneauId)
                    .findFirst()
                    .orElse(null);
            if (memeCreneau == null) {
                liberent.add(new OptionEchange(collegue, null, NatureEchange.LIBERE));
            } else if (EligibleAnimateurMoveFilter.isEligible(memeCreneau, demandeur, solved.pauseSurPosteActive())) {
                croises.add(new OptionEchange(collegue, memeCreneau, NatureEchange.CROISE));
            }
            for (PosteAffectation ailleurs : siegesAilleurs(sieges, creneauId, demandeur, solved.pauseSurPosteActive())) {
                diriges.add(new OptionEchange(collegue, ailleurs, NatureEchange.DIRIGE));
            }
        }
        liberent.sort(Comparator
                .comparing((OptionEchange option) -> occupes.contains(option.animateur().getId()))
                .thenComparing(option -> option.animateur().getId(), NaturalOrder.DES_IDS));
        return entrelacer(liberent, croises, diriges);
    }

    /**
     * The colleague's seats on <b>other</b> créneaux that the demandeur could
     * take in return, earliest first and capped per colleague — the « je te
     * laisse mon lundi, je prends ton mardi » family.
     */
    private static List<PosteAffectation> siegesAilleurs(List<PosteAffectation> sieges, long creneauId,
            Animateur demandeur, boolean pauseSurPoste) {
        return sieges.stream()
                .filter(poste -> poste.getCreneau().getId() != creneauId)
                .filter(poste -> EligibleAnimateurMoveFilter.isEligible(poste, demandeur, pauseSurPoste))
                .sorted(Comparator
                        .comparing((PosteAffectation poste) -> poste.getCreneau().getDate(),
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(PosteAffectation::heureDebutEffectif,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(PosteAffectation::getId, NaturalOrder.DES_IDS))
                .limit(SIEGES_DIRIGES_PAR_COLLEGUE)
                .toList();
    }

    /** Round-robin over the three families, so a truncation trims all of them evenly rather than erasing two. */
    @SafeVarargs
    private static List<OptionEchange> entrelacer(List<OptionEchange>... familles) {
        List<OptionEchange> entrelacees = new ArrayList<>();
        int plusLongue = Stream.of(familles).mapToInt(List::size).max().orElse(0);
        for (int rang = 0; rang < plusLongue; rang++) {
            for (List<OptionEchange> famille : familles) {
                if (rang < famille.size()) {
                    entrelacees.add(famille.get(rang));
                }
            }
        }
        return entrelacees;
    }

    /** The seat {@code animateurId} holds on (créneau, stand), or throws in business words. */
    private static PosteAffectation posteOf(PlanningEvenement solved, String animateurId, long creneauId,
            String standId) {
        return solved.getPostes().stream()
                .filter(poste -> poste.getStand() != null && standId.equals(poste.getStand().getId())
                        && poste.getCreneau() != null && poste.getCreneau().getId() != null
                        && poste.getCreneau().getId() == creneauId
                        && poste.getAnimateur() != null && animateurId.equals(poste.getAnimateur().getId()))
                .findFirst()
                .orElseThrow(() -> new BusinessError.Invalid(
                        "Aucun poste de l'animateur " + animateurId + " sur ce créneau et ce stand"));
    }

    /**
     * The hard constraints with strictly more matches after the simulated swap
     * than before, each carried with its business description from the
     * {@link ConstraintCatalog} — what the animateur (and the admin) reads,
     * rather than a technical constraint dump.
     */
    private static List<HardViolation> extraHardViolations(PlanningAnalysis avant,
            PlanningAnalysis apres) {
        Map<String, Integer> matchesAvant = new HashMap<>();
        for (ConstraintContribution ca : avant.contributions()) {
            matchesAvant.put(ca.constraintName(), ca.matchCount());
        }
        List<HardViolation> violations = new ArrayList<>();
        for (ConstraintContribution ca : apres.contributions()) {
            String name = ca.constraintName();
            if (!HARD_CONSTRAINT_NAMES.contains(name)) {
                continue;
            }
            int supplement = ca.matchCount() - matchesAvant.getOrDefault(name, 0);
            if (supplement <= 0) {
                continue;
            }
            ConstraintCatalog.ConstraintDefinition definition = DEFINITIONS_PAR_NOM.get(name);
            violations.add(new HardViolation(name,
                    definition == null ? name : definition.description(), supplement));
        }
        return violations;
    }

    /** @return one {@link ContrainteImpact} per constraint that matches (violées) or does not (respectées) for {@code poste}. */
    private static List<ContrainteImpact> impactsFor(PlanningAnalysis analysis, PosteAffectation poste, boolean violees) {
        List<ContrainteImpact> impacts = new ArrayList<>();
        for (ConstraintContribution ca : analysis.contributions()) {
            List<MatchFacts> matches = ca.matches().stream()
                    .filter(match -> concerns(match, poste))
                    .toList();
            if (matches.isEmpty() == violees) {
                continue;
            }
            ConstraintCatalog.ConstraintDefinition definition = DEFINITIONS_PAR_NOM.get(ca.constraintName());
            impacts.add(new ContrainteImpact(
                    ca.constraintName(),
                    definition == null ? null : definition.niveau().name(),
                    definition == null ? null : definition.categorie(),
                    definition == null ? null : definition.description(),
                    matches.size(),
                    formatViolations(matches)));
        }
        return impacts;
    }

    /** True when {@code poste} itself appears among a match's justification facts, flattening any collection fact. */
    private static boolean concerns(MatchFacts match, PosteAffectation poste) {
        return match.facts().stream().anyMatch(fact -> concernsFact(fact, poste));
    }

    private static boolean concernsFact(Object fact, PosteAffectation poste) {
        if (fact instanceof Collection<?> collection) {
            return collection.stream().anyMatch(element -> concernsFact(element, poste));
        }
        return fact == poste;
    }

    private static PosteAffectation findPoste(PlanningEvenement solved, String posteId) {
        return solved.getPostes().stream()
                .filter(poste -> poste.getId().equals(posteId))
                .findFirst()
                .orElseThrow(() -> new BusinessError.NotFound("Poste inconnu: " + posteId));
    }

    private static Animateur findAnimateur(PlanningEvenement solved, String animateurId) {
        return solved.getAnimateurs().stream()
                .filter(animateur -> animateur.getId().equals(animateurId))
                .findFirst()
                .orElseThrow(() -> new BusinessError.NotFound("Animateur inconnu: " + animateurId));
    }

    /**
     * One constraint's impact on a single poste: either one of the violations
     * it is party to (see {@link #explainAffectation}), or an entry meaning
     * this constraint had no match involving that poste.
     *
     * @param details one human-readable line per match (see {@link ViolationFormatter}), empty when not violated
     */
    public record ContrainteImpact(String name, String niveau, String categorie, String description,
            int matchCount, List<String> details) {
    }

    /** @param animateurId the poste's current occupant, {@code null} when unassigned */
    public record AffectationExplanation(String posteId, String animateurId, HardMediumSoftScore score,
            List<ContrainteImpact> contraintesViolees, List<ContrainteImpact> contraintesRespectees) {
    }

    /**
     * @param animateurActuelId    the poste's occupant before the simulation, {@code null} when unassigned
     * @param animateurCandidatId  the animateur substituted in for the simulation
     * @param delta                {@code scoreApres - scoreAvant}: positive/less-negative means the swap improves the score
     */
    public record SwapSimulation(String posteId, String animateurActuelId, String animateurCandidatId,
            HardMediumSoftScore scoreAvant, HardMediumSoftScore scoreApres, HardMediumSoftScore delta,
            List<ContrainteImpact> contraintesVioleesAvant, List<ContrainteImpact> contraintesVioleesApres) {
    }

    /**
     * One viable replacement for a poste (issue #71): who, what the whole plan
     * would then score, and which of that poste's violations the move settles
     * or raises. Only ever produced for a candidate that leaves the plan's hard
     * score no worse, so {@code violationsIntroduites} never holds a hard one.
     *
     * @param delta                 {@code scoreApres - scoreAvant}: the greater, the better the repair
     * @param violationsResolues    violated for the current occupant, not for this candidate
     * @param violationsIntroduites the mirror image
     */
    public record SuggestionReparation(String animateurId, HardMediumSoftScore scoreApres,
            HardMediumSoftScore delta, List<ContrainteImpact> violationsResolues,
            List<ContrainteImpact> violationsIntroduites) {
    }

    /**
     * Result of {@link #suggererReparations}, carrying the cost it actually
     * paid: {@code candidatsEvalues} of the {@code candidatsEligibles} were
     * simulated, one full analyse each. When the two differ the search stopped
     * at the plafond and the list is the best of what it saw, not an exhaustive
     * answer — the UI has to say so rather than imply completeness.
     *
     * @param animateurActuelId the poste's occupant before any repair, {@code null} when the seat is empty
     */
    public record SuggestionsReparation(String posteId, String animateurActuelId,
            HardMediumSoftScore scoreAvant, List<ContrainteImpact> contraintesVioleesAvant,
            int candidatsEligibles, int candidatsEvalues, int plafond,
            List<SuggestionReparation> suggestions) {
    }

    /**
     * One reason an animateur is not on this seat, named by the constraint
     * that says so and worded by {@link ConstraintCatalog}.
     *
     * <p>The name is the payload; {@code niveau}, {@code categorie} and
     * {@code description} are the catalogue's, copied here so a caller needs
     * one round trip instead of two. A reason with no catalogue entry keeps
     * its name and nulls the rest rather than inventing wording.</p>
     */
    public record MotifExclusion(String contrainte, String niveau, String categorie, String description) {
    }

    /**
     * One line of the banc de touche, with <b>two</b> verdicts, because there
     * are genuinely two questions and they do not have the same answer.
     *
     * <p>{@code disponible} is the one the screen is named after: not a single
     * hard rule stands between this animateur and the seat, measured against
     * that seat being <b>empty</b>. {@code degradeLePlan} is the wider
     * question — would the plan's hard score actually get worse than it is
     * today. The two come apart on an occupied seat and on a full one: taking
     * over from someone who already breaks a rule can break another and leave
     * the plan no worse overall. « Il peut le prendre, mais il sera sur deux
     * stands à la fois » is a real answer; « il est disponible » would be a
     * false one.</p>
     *
     * <p><b>{@code disponible} is the strict one, and deliberately stricter
     * than {@link #suggererReparations}.</b> The repair assistant keeps a
     * candidate whose hard score comes out flat, reporting what they would
     * break as {@code violationsIntroduites}; this screen refuses to call that
     * person available. The guarantee that holds, and that
     * {@code CreneauAvailabilityCoherenceTest} proves, is the implication:
     * anyone shown as {@code disponible} <em>is</em> a candidate the assistant
     * proposes. The converse is false on purpose — see that test for why
     * pretending otherwise would mean re-implementing the assistant's per-seat
     * match analysis here, which is the duplication issue #303 exists to
     * avoid.</p>
     *
     * @param delta  what the plan's score would become minus what it is today,
     *               so a viable candidate can still be ranked by what they cost
     * @param motifs every applicable reason, not the most blocking one: the
     *               point of the screen is to tell « lever l'indisponibilité
     *               suffirait » apart from « il en resterait trois »
     */
    public record AnimateurAvailability(String animateurId, boolean disponible, boolean degradeLePlan,
            HardMediumSoftScore delta, List<MotifExclusion> motifs) {
    }

    /**
     * Why the banc de touche has, or has not, anything to say about a créneau.
     *
     * <p>The two empty cases are answers, not failures, and the screen has to
     * word them differently — « lancez une résolution » and « choisissez un
     * autre créneau » are not the same advice. Returning a {@code 404} for
     * either of them, which this endpoint used to do for {@link #NO_SEAT}, made
     * the screen open on an error the user could not act on.</p>
     */
    public enum SeatStatus {
        /** Nothing is saved yet: there is no plan for anybody to be absent from. */
        NO_PLAN,
        /**
         * The saved plan holds seats, but none on this créneau — or none on the
         * stand asked for. No stand is open then, or the plan predates the
         * créneau (a découpage regenerated the grid after the last solve).
         */
        NO_SEAT,
        /** A seat was probed: {@code animateurs} is the answer. */
        EVALUATED
    }

    /**
     * Result of {@link #creneauAvailability}: who is off duty on a créneau, and why
     * the seat probed is or is not within their reach.
     *
     * @param statut             which of the three answers this is; the three
     *                           fields below are {@code null} and the lists
     *                           empty unless it is {@link SeatStatus#EVALUATED}
     * @param posteCibleId       the seat every reason is relative to
     * @param animateurCibleId   its current occupant, {@code null} when the seat
     *                           is free — when it is not, the question answered is
     *                           « qui pourrait le remplacer ? »
     * @param disponibles        how many of {@code animateurs} could take the
     *                           seat without breaking a hard rule
     * @param creneauId          the créneau actually answered on: the one asked
     *                           for, or the first staffed one when none was —
     *                           {@code null} only when the plan staffs none
     * @param creneauxAvecSieges every créneau the saved plan holds a seat on,
     *                           and <b>the whole of what the selector offers</b>.
     *                           The référentiel holds more créneaux than the
     *                           plan does; offering those was how a user landed
     *                           on one the answer could only be empty for. Empty
     *                           here means nothing is staffed at all, which is
     *                           exactly {@link SeatStatus#NO_PLAN}
     */
    public record CreneauAvailability(Long creneauId, SeatStatus statut, String posteCibleId, String standCibleId,
            String animateurCibleId, int total, int disponibles, List<CreneauSiege> creneauxAvecSieges,
            List<AnimateurAvailability> animateurs) {
    }

    /**
     * One créneau of the saved plan, with what it takes to label it in a
     * selector and nothing more.
     *
     * @param famille the découpage stagger family, meaningful only once a
     *                découpage has produced several variants of the same hours
     */
    public record CreneauSiege(Long id, int jour, LocalDate date, LocalTime heureDebut, LocalTime heureFin,
            int famille) {
    }

    /**
     * What an échange actually does for the animateur who asked — the three
     * distinct answers to « je ne veux pas de ce créneau », which the espace
     * lists apart because they are not interchangeable for the person reading.
     *
     * <p>Declaration order is display order: freed first, then the two trades
     * that keep them on duty.</p>
     */
    public enum NatureEchange {
        /** The colleague is free then and simply takes the seat: the demandeur is off. */
        LIBERE,
        /** The colleague works that same créneau: the two seats swap, the demandeur only changes stand. */
        CROISE,
        /** A seat on ANOTHER créneau comes back — « je te laisse mon lundi, je prends ton mardi ». */
        DIRIGE
    }

    /**
     * One viable way out of a créneau (the espace's « qui peut me remplacer ? »).
     * Only ever produced for an option that leaves the plan's hard score no
     * worse.
     *
     * @param creneauCibleId the créneau of the seat coming back, set for
     *                       {@link NatureEchange#DIRIGE} only — the two other
     *                       families play out on the demandeur's own créneau
     * @param standCibleId   the stand the demandeur would end up on, {@code null}
     *                       when the échange frees them
     * @param delta          {@code scoreApres - scoreAvant}: the greater, the better for the plan
     */
    public record SuggestionEchange(String animateurId, NatureEchange nature, Long creneauCibleId,
            String standCibleId, HardMediumSoftScore scoreApres, HardMediumSoftScore delta) {
    }

    /**
     * Result of {@link #suggererEchanges}, carrying the cost it actually paid,
     * like {@link SuggestionsReparation}: {@code optionsEvaluees} of the
     * {@code optionsEligibles} were simulated. When the two differ the search
     * stopped at the plafond and the list is the best of what it saw, not an
     * exhaustive answer.
     *
     * <p>Options, not colleagues: one colleague can hold several — take my
     * seat, or trade me your Tuesday, or your Thursday.</p>
     */
    public record SuggestionsEchange(long creneauId, String standId, HardMediumSoftScore scoreAvant,
            int optionsEligibles, int optionsEvaluees, int plafond,
            List<SuggestionEchange> suggestions) {
    }

    /**
     * Result of {@link #simulateEchange}: what a demande d'échange would do to
     * the persisted planning, without persisting anything.
     *
     * @param posteCibleId  the target's own seat on the same créneau, {@code null}
     *                      when the target is free there (simple takeover)
     * @param echangeCroise true when both seats swap occupants
     * @param standCibleId  stand of {@code posteCibleId}, {@code null} on takeover
     * @param casseContrainteDure true when the swap makes the global hard score
     *                      worse — the prevalidation verdict shown to the animateur
     */
    public record EchangeSimulation(String posteDemandeurId, String posteCibleId, boolean echangeCroise,
            String standCibleId, HardMediumSoftScore scoreAvant, HardMediumSoftScore scoreApres,
            HardMediumSoftScore delta, boolean casseContrainteDure,
            List<HardViolation> nouvellesViolationsDures) {
    }

    /** One hard constraint the simulated échange would newly violate, in business words. */
    public record HardViolation(String name, String description, int matchesSupplementaires) {
    }

    /**
     * Builds the diagnostic of an already-solved planning, without solving it
     * again. Used right after {@link #solve} so a solve is never run twice
     * just to produce its own analysis.
     */
    /**
     * Re-derives the constraint analysis of the plan currently persisted —
     * called after a snapshot restore rewrote {@code poste_affectation}
     * outside of any solve. Without it, the Contraintes screen kept
     * describing the <b>last solve</b>: after a group switch plus a one-click
     * restore (the issue #167 flow), it still showed the previous group's
     * hard violations against the freshly restored plan. Runs the same
     * preparation as a solve (ad hoc constraints, legal parameters, toggles,
     * weights) so the diagnostic is comparable to a post-solve one. Returns
     * {@code null} when nothing is persisted.
     */
    public PlanningDiagnostic diagnosePersistedPlan() {
        PlanningEvenement persisted = planningPersistenceService.loadPersistedPlanning();
        if (persisted.getPostes().isEmpty()) {
            return null;
        }
        prepareProblem(persisted);
        // diagnose() reads the solution's own score (hardScore, medium
        // breakdown): a freshly reloaded plan has none until update() sets it.
        solutionManager.update(persisted);
        return diagnose(persisted);
    }

    public PlanningDiagnostic diagnose(PlanningEvenement solved) {
        PlanningAnalysis analysis = constraintDiagnosticService.analyze(solved);
        List<ConstraintDiagnostic> constraintDiagnostics = new ArrayList<>();
        Map<String, ContributionAdHoc> contributionsAdHoc = new LinkedHashMap<>();
        for (ConstraintContribution ca : analysis.contributions()) {
            String name = ca.constraintName();
            boolean hard = HARD_CONSTRAINT_NAMES.contains(name);
            List<String> violations = hard ? formatViolations(ca.matches()) : List.of();
            if (hard) {
                collectContributionsAdHoc(name, ca.matches(), contributionsAdHoc);
            }
            constraintDiagnostics.add(new ConstraintDiagnostic(
                    name,
                    String.valueOf(ca.score()),
                    ca.matchCount(),
                    violations));
        }
        constraintDiagnostics.sort((a, b) -> Integer.compare(b.matchCount, a.matchCount));
        int unassigned = (int) solved.getPostes().stream()
                .filter(p -> p.getAnimateur() == null)
                .count();
        FeasibilityAnalyzer.FeasibilityReport faisabilite = feasibilityAnalyzer.analyze(
                solved.getAnimateurs(), distinctStands(solved), distinctCreneaux(solved),
                solved.getContraintesAdHoc());
        int hardScore = solved.getScore() == null ? 0 : Math.toIntExact(solved.getScore().hardScore());
        List<ContributionAdHoc> contraintesAdHocEnCause = contributionsAdHoc.values().stream()
                .sorted(Comparator.comparingInt(ContributionAdHoc::violations).reversed()
                        .thenComparing(ContributionAdHoc::contrainteId))
                .toList();
        return new PlanningDiagnostic(String.valueOf(solved.getScore()), unassigned, constraintDiagnostics,
                faisabilite, hardScore, contraintesAdHocEnCause);
    }

    /**
     * Which hand-entered exceptions the still-violated hard constraints are
     * about (issue #84).
     *
     * <p>A solve that ends hard-negative names the rules that failed, and
     * "affectationForcee: 12" is where the user stops reading: nothing says
     * <em>which</em> of their exceptions the solver could not honour, so the
     * usual conclusion is that the solver is at fault. The three prescriptive
     * ad hoc rules carry the {@code ContrainteAdHoc} itself in their
     * justification, so the attribution is a matter of reading it back.</p>
     *
     * <p>Counted over the matches actually analysed, so a constraint capped by
     * {@link #MAX_VIOLATIONS_PAR_CONTRAINTE} is not capped here — this reads
     * the raw matches, not the formatted lines.</p>
     */
    private static void collectContributionsAdHoc(String constraintName,
            List<MatchFacts> matches, Map<String, ContributionAdHoc> contributions) {
        for (MatchFacts match : matches) {
            for (Object fact : match.facts()) {
                if (fact instanceof ContrainteAdHoc contrainte && contrainte.getId() != null) {
                    contributions.merge(contrainte.getId(),
                            new ContributionAdHoc(contrainte.getId(),
                                    contrainte.getType() == null ? null : contrainte.getType().name(),
                                    contrainte.getRaison(), 1, List.of(constraintName)),
                            PlanningService::mergeContributions);
                }
            }
        }
    }

    private static ContributionAdHoc mergeContributions(ContributionAdHoc existing, ContributionAdHoc addition) {
        List<String> contraintes = new ArrayList<>(existing.contraintes());
        for (String name : addition.contraintes()) {
            if (!contraintes.contains(name)) {
                contraintes.add(name);
            }
        }
        return new ContributionAdHoc(existing.contrainteId(), existing.type(), existing.raison(),
                existing.violations() + addition.violations(), List.copyOf(contraintes));
    }

    /**
     * One line per match, human-readable (see {@link ViolationFormatter}) —
     * e.g. "Sarah Rousseau (A45)" for a {@code reposHebdomadaireMineur} hit, or
     * "Stand tir à l'arc — 2026-07-16 12:30-15:30" for an unfilled
     * {@code posteDoitEtrePourvu} seat. Capped at {@link #MAX_VIOLATIONS_PAR_CONTRAINTE}:
     * this feeds a UI detail popup, not an export.
     */
    private static List<String> formatViolations(List<MatchFacts> matches) {
        return matches.stream()
                .limit(MAX_VIOLATIONS_PAR_CONTRAINTE)
                .map(match -> ViolationFormatter.describe(match.facts()))
                .toList();
    }

    private static List<Stand> distinctStands(PlanningEvenement solved) {
        Map<String, Stand> byId = new LinkedHashMap<>();
        for (PosteAffectation poste : solved.getPostes()) {
            byId.putIfAbsent(poste.getStand().getId(), poste.getStand());
        }
        return new ArrayList<>(byId.values());
    }

    private static List<Creneau> distinctCreneaux(PlanningEvenement solved) {
        Map<Long, Creneau> byId = new LinkedHashMap<>();
        for (PosteAffectation poste : solved.getPostes()) {
            byId.putIfAbsent(poste.getCreneau().getId(), poste.getCreneau());
        }
        return new ArrayList<>(byId.values());
    }

    private SolverFactory<PlanningEvenement> resolveSolverFactory(Long secondsLimitOverride) {
        if (secondsLimitOverride == null || secondsLimitOverride.equals(defaultSecondsLimit)) {
            return solverFactory;
        }
        SolverConfig solverConfig = SolverConfig.createFromXmlResource("solver/solverConfig.xml");
        solverConfig.setScoreDirectorFactoryConfig(new ScoreDirectorFactoryConfig()
                .withConstraintProviderClass(PlanningConstraintProvider.class));
        // An explicit override means the caller wants exactly that many seconds;
        // the ambient unimproved-time bailout (e.g. the test profile's 2s, far
        // too tight for a large scenario solved with a bigger override) must not
        // silently cut it short, so it is disabled rather than reused here.
        applyTermination(solverConfig, secondsLimitOverride, 0L);
        return SolverFactory.create(solverConfig);
    }

    /**
     * @param violations one human-readable line per match (see
     *                    {@link ViolationFormatter}), populated only for
     *                    constraints enforced at
     *                    {@link ConstraintCatalog.Niveau#HARD} — empty for
     *                    medium/soft ones, which can run into the thousands
     *                    of matches (see {@link #HARD_CONSTRAINT_NAMES}).
     */
    public record ConstraintDiagnostic(String name, String score, int matchCount, List<String> violations) {
    }

    /**
     * Business-facing result of a solve: score, unfilled seats and
     * per-constraint breakdown. Deliberately excludes the {@link PlanningEvenement}
     * itself (animateurs/stands/créneaux/postes) — that payload can reach several
     * dozens of MB and is consulted through the dedicated screens instead, which
     * load it from {@code /api/planning/persisted}.
     *
     * <p>{@code hardScore} is the actually-reached hard score, distinct from
     * {@code faisabilite}: the latter is a cheap, optimistic pre-solve capacity
     * estimate (see {@link FeasibilityAnalyzer}'s javadoc — it can under-report a
     * shortfall it didn't account for, e.g. one only created by the vacation
     * découpage or by a legal constraint on minors) and can say "réalisable"
     * for a plan the solver still could not bring to zero hard within its time
     * budget. Callers that need to know whether the plan actually in hand is
     * fully legal/staffed must check {@code hardScore == 0}, not just
     * {@code faisabilite.feasible()}.</p>
     */
    public record PlanningDiagnostic(
            String score,
            int postesNonPourvus,
            List<ConstraintDiagnostic> contraintes,
            FeasibilityAnalyzer.FeasibilityReport faisabilite,
            int hardScore,
            List<ContributionAdHoc> contraintesAdHocEnCause) {
    }

    /**
     * One hand-entered exception the last analysis found still violated, most
     * violated first.
     *
     * @param contrainteId id of the {@code ContrainteAdHoc}, the one shown on
     *                     the ad hoc screen
     * @param type         its {@code TypeContrainteAdHoc}, as a name
     * @param raison       the free text its author typed, kept as-is
     * @param violations   number of matches it accounts for
     * @param contraintes  names of the solver rules it broke, usually one
     */
    public record ContributionAdHoc(String contrainteId, String type, String raison, int violations,
            List<String> contraintes) {
    }

    /**
     * Reads a window's end hour, {@code null} (absent or explicitly empty)
     * meaning "until closing time" — see {@link FenetreHoraire}. A missing end
     * used to be a hard error; it is now the way to say "to whatever hour this
     * day closes at", which is what lets one rule cover days closing at 20:00
     * and days closing at midnight alike.
     */
    private static LocalTime parseTimeOrEndOfDay(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : LocalTime.parse(text);
    }

    /**
     * Reads the {@code horaires:} section of a stand — recurring rules, with the
     * day selector flattened onto the rule (see {@link #horairesYaml}). An absent
     * {@code jours} reads as {@link TypeJoursHoraire#TOUS}, which is what makes
     * the common case a two-line entry.
     */
    private List<HoraireStand> readHoraires(List<Map<String, Object>> horairesData) {
        List<HoraireStand> horaires = new ArrayList<>();
        for (Map<String, Object> horaireData : horairesData) {
            HoraireStand horaire = new HoraireStand();
            String modeStr = (String) horaireData.get("mode");
            if (modeStr == null) {
                throw new BusinessError.Invalid("Champ manquant: stands.horaires.mode (OUVERTURE ou FERMETURE)");
            }
            horaire.setMode(ModeHoraire.valueOf(modeStr));
            String joursStr = (String) horaireData.getOrDefault("jours", TypeJoursHoraire.TOUS.name());
            horaire.setJours(TypeJoursHoraire.valueOf(joursStr));
            List<String> joursSemaine = YamlSections.chaines(horaireData, "joursSemaine");
            if (joursSemaine != null) {
                horaire.setJoursSemaine(joursSemaine.stream().map(DayOfWeek::valueOf)
                        .collect(Collectors.toCollection(TreeSet::new)));
            }
            if (horaireData.get("dateDebut") != null) {
                horaire.setDateDebut(parseLocalDate(horaireData.get("dateDebut"), "stands.horaires.dateDebut"));
            }
            if (horaireData.get("dateFin") != null) {
                horaire.setDateFin(parseLocalDate(horaireData.get("dateFin"), "stands.horaires.dateFin"));
            }
            List<Object> dates = YamlSections.valeurs(horaireData, "dates");
            if (dates != null) {
                horaire.setDates(dates.stream().map(date -> parseLocalDate(date, "stands.horaires.dates"))
                        .collect(Collectors.toCollection(TreeSet::new)));
            }
            List<Map<String, Object>> fenetresData = YamlSections.objets(horaireData, "fenetres");
            if (fenetresData == null || fenetresData.isEmpty()) {
                throw new BusinessError.Invalid("Champ manquant: stands.horaires.fenetres (au moins une fenêtre)");
            }
            List<FenetreHoraire> fenetres = new ArrayList<>();
            for (Map<String, Object> fenetreData : fenetresData) {
                Object heureDebut = fenetreData.get("heureDebut");
                if (heureDebut == null) {
                    throw new BusinessError.Invalid("Champ manquant: stands.horaires.fenetres.heureDebut");
                }
                Object effectif = fenetreData.get("effectif");
                if (effectif != null && !(effectif instanceof Number)) {
                    throw new BusinessError.Invalid(
                            "Champ invalide: stands.horaires.fenetres.effectif doit être un entier");
                }
                fenetres.add(new FenetreHoraire(LocalTime.parse(heureDebut.toString()),
                        parseTimeOrEndOfDay(fenetreData.get("heureFin")),
                        effectif == null ? null : ((Number) effectif).intValue()));
            }
            horaire.setFenetres(fenetres);
            horaire.setMotif((String) horaireData.get("motif"));
            horaires.add(horaire);
        }
        return horaires;
    }

    private LocalDate parseLocalDate(Object value, String fieldName) {
        if (value == null) {
            throw new BusinessError.Invalid("Champ date manquant: " + fieldName);
        }
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof Date date) {
            return date.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
        }
        if (value instanceof CharSequence charSequence) {
            return LocalDate.parse(charSequence.toString());
        }
        throw new BusinessError.Invalid(
                "Type de date non supporte pour " + fieldName + ": " + value.getClass().getName());
    }
}
