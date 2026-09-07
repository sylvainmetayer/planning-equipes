package dev.sylvain.planning.service;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
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

import ai.timefold.solver.core.config.solver.SolverConfig;
import dev.sylvain.planning.domain.AffectationPubliee;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ConstraintToggle;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.service.diagnostic.ConstraintContribution;
import dev.sylvain.planning.service.diagnostic.ConstraintDiagnosticMode;
import dev.sylvain.planning.service.diagnostic.ConstraintDiagnosticService;
import dev.sylvain.planning.service.diagnostic.AffectationHypothesis;
import dev.sylvain.planning.service.diagnostic.MatchFacts;
import dev.sylvain.planning.service.diagnostic.PlanningAnalysis;
import dev.sylvain.planning.solver.ConstraintCatalog;
import dev.sylvain.planning.solver.EligibleAnimateurMoveFilter;
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

    /** Everything that turns the edition's reference data into a problem to solve. */
    private final ProblemBuilder problemBuilder;

    /**
     * Field-injected rather than a constructor parameter: the plain (non-CDI)
     * tests build this service with {@code new} and never exercise the locks,
     * so it stays null there — {@link ProblemBuilder#applyVerrouillages} guards
     * on it, reaching it through the supplier the builder is given.
     */
    @Inject
    PlanningPersistenceService planningPersistenceService;

    /** The published plan, for {@code stabiliteDuPlanPublie}; null in a plain-Java harness like the persistence above. */
    @Inject
    PlanSnapshotService snapshotService;

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
        // Lazily reads the field injected below, which CDI sets after this runs.
        this.problemBuilder = new ProblemBuilder(referenceDataService, () -> planningPersistenceService);
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
        return buildExample(ScenarioYamlReader.DEFAULT_SCENARIO);
    }

    /**
     * Loads a named scenario from the {@link ScenarioYamlReader#SCENARIOS_DIR} folder. The name is
     * a bare file name (e.g. {@code scenario-complet.yaml}); any path component
     * is rejected so callers cannot escape the scenarios folder.
     */
    public PlanningEvenement buildExample(String scenarioName) {
        try {
            return ScenarioYamlReader.buildPlanningFromData(
                    ScenarioYamlReader.readScenarioData(ScenarioYamlReader.cheminScenario(scenarioName)),
                    referenceDataService::getParametresLegaux);
        } catch (IOException e) {
            throw new RuntimeException("Erreur lors du chargement du scénario YAML", e);
        }
    }

    /**
     * Small, self-contained scenario used as a fast nominal case (a handful of
     * postes) so the hard-constraint invariant can be checked in seconds. The
     * large {@code scenario-complet.yaml} is the complex performance target
     * solved by {@link #buildExample()}.
     */
    public PlanningEvenement buildSimpleExample() {
        try {
            return ScenarioYamlReader.buildPlanningFromData(
                    ScenarioYamlReader.readScenarioData(ScenarioYamlReader.SCENARIOS_DIR + "/scenario.yml"),
                    referenceDataService::getParametresLegaux);
        } catch (IOException e) {
            throw new RuntimeException("Erreur lors du chargement du scénario YAML", e);
        }
    }


    // --- Problem building: façade over ProblemBuilder -----------------------

    /** @see ProblemBuilder#buildFromReferenceData() */
    public PlanningEvenement buildFromReferenceData() {
        return problemBuilder.buildFromReferenceData();
    }

    /** @see ProblemBuilder#buildFromReferenceData(Reamorcage) */
    public ProblemBuilder.ProblemeReamorce buildFromReferenceData(Reamorcage reamorcage) {
        return problemBuilder.buildFromReferenceData(reamorcage);
    }

    /** @see ProblemBuilder#buildIncrementalFromReferenceData(ReplanificationScope) */
    public ProblemBuilder.ProblemeIncremental buildIncrementalFromReferenceData(ReplanificationScope scope) {
        return problemBuilder.buildIncrementalFromReferenceData(scope);
    }

    /**
     * Serializes the current reference data — through {@link ScenarioYamlWriter}
     * — into the same YAML shape read by {@link ScenarioYamlReader#buildPlanningFromData}, so the
     * result can be dropped into the
     * {@link ScenarioYamlReader#SCENARIOS_DIR} folder and reloaded as-is.
     *
     * <p>Everything that shapes a solve is written, not only the entities:
     * {@code typologies}, {@code emplacements}, {@code parametresLegaux},
     * {@code parametresDecoupage} and {@code parametresSolveur} — re-importing
     * the file therefore reproduces the very same problem, which is the whole
     * point of exporting it. A file missing those sections silently fell back to
     * the importing instance's own settings (its solve duration, its vacation
     * lengths, its relay families), so the "same" scenario replayed elsewhere
     * solved a different problem.</p>
     *
     * <p><b>The créneaux are always written as they are, with their seat list.</b>
     * There used to be a second shape — a découpé edition exporting its source
     * amplitudes plus {@code decoupageAuto} and no {@code postes}, so the import
     * re-ran the découpage — and this javadoc still described it long after
     * issue #172 removed it. Once the découpage has run, the amplitudes it
     * consumed are gone: a découpé edition has nothing but its vacations left to
     * export. The hand-maintained "amplitudes + {@code decoupageAuto}" scenario
     * file stays the source of truth for re-slicing, never this export.</p>
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
        List<PosteAffectation> postes = ProblemBuilder.buildPostes(stands, creneaux);
        return ScenarioYamlWriter.buildScenarioYaml(new ScenarioYamlWriter.ScenarioExport(
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


    // --- Scenario reading: façade over ScenarioYamlReader -------------------
    //
    // The parsing itself lives in ScenarioYamlReader, which is static and needs
    // no database. What stays here is the one thing it cannot know on its own:
    // the edition's ParametresLegaux, the fallback for a file that pins none.

    /** @see ScenarioYamlReader#listScenarios() */
    public List<String> listScenarios() {
        return ScenarioYamlReader.listScenarios();
    }

    /** @see ScenarioYamlReader#buildFromScenarioText */
    public ScenarioYamlReader.ScenarioImporte buildFromScenarioText(String yamlContent) {
        return ScenarioYamlReader.buildFromScenarioText(yamlContent, referenceDataService::getParametresLegaux);
    }

    /** @see ScenarioYamlReader#loadScenarioSections */
    public ScenarioYamlReader.ScenarioSections loadScenarioSections(String scenarioName) {
        return ScenarioYamlReader.loadScenarioSections(scenarioName);
    }

    /** @see ScenarioYamlReader#loadScenario */
    public ScenarioYamlReader.ScenarioImporte loadScenario(String scenarioName) {
        return ScenarioYamlReader.loadScenario(scenarioName, referenceDataService::getParametresLegaux);
    }

    /** @see ScenarioYamlReader#loadEditionScenarioText */
    public Optional<dev.sylvain.planning.scenario.dto.EditionCibleDto> loadEditionScenarioText(String yamlContent) {
        return ScenarioYamlReader.loadEditionScenarioText(yamlContent);
    }

    /** @see ScenarioYamlReader#loadReferenceScenario(String) */
    ScenarioYamlReader.ReferenceScenario loadReferenceScenario(String scenarioName) throws IOException {
        return ScenarioYamlReader.loadReferenceScenario(scenarioName);
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

    /**
     * Fills a planning read from the database with the server-side facts before
     * anything scores it — the same overwrite a solve does. Package-private for
     * {@link DeplacementService}, which must judge a gesture on the rules the
     * edition really runs under.
     */
    void prepareForAnalysis(PlanningEvenement planning) {
        prepareProblem(planning);
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
        // The published plan is the server's knowledge, never the caller's: a
        // planning posted by a client cannot decide what people were told.
        problem.setAffectationsPubliees(affectationsPubliees());
        // Server-side configuration, like the weights below: always overwritten
        // so a caller cannot loosen a quality threshold by sending its own.
        problem.setParametresQualite(List.of(new ParametresQualite(maxEmplacementsParJour)));
        // Never sent by a caller (the field is @JsonIgnore-d on PlanningEvenement),
        // so this always overwrites the ConstraintWeightOverrides.none() default.
        problem.setPonderationsContraintes(constraintWeightOverrides(problem.getPonderationsScenario()));
    }

    /**
     * The seats of the last published plan, as facts of {@code stabiliteDuPlanPublie};
     * empty when nothing was published, or when the snapshot service is not
     * wired (a plain-Java harness). A seat the publication left empty carries
     * nobody to keep and is skipped.
     */
    List<AffectationPubliee> affectationsPubliees() {
        if (snapshotService == null) {
            return List.of();
        }
        PlanSnapshotService.SnapshotDetail publication = snapshotService.loadLastPublication();
        if (publication == null) {
            return List.of();
        }
        return factsPublies(publication.affectations());
    }

    /**
     * The published seats as facts, <b>without duplicates</b>.
     *
     * <p>A fact says « this line had this person », a stand × créneau × person
     * triple, which several seats can share: a créneau cut into segments gives
     * one seat per segment, and the same person legitimately holds two of them
     * (a stand open 10 h-18 h with a meal-cover shift, say) — and a plan may
     * also have been published with a double booking on one line, which is a
     * hard violation the rule has no business repeating. Timefold indexes
     * problem facts by equality and refuses an equal one twice ("The fact …
     * was already inserted"), so the list must be a set. The constraint only
     * ever asks whether such a line exists, so collapsing the copies changes
     * no score.</p>
     */
    static List<AffectationPubliee> factsPublies(List<PlanSnapshotService.AffectationSnapshot> affectations) {
        return affectations.stream()
                .filter(affectation -> affectation.animateurId() != null && affectation.standId() != null
                        && affectation.creneauId() != null)
                .map(affectation -> new AffectationPubliee(affectation.standId(),
                        Long.parseLong(affectation.creneauId()), affectation.animateurId()))
                .distinct()
                .toList();
    }

    /**
     * Every constraint definition indexed by name, for {@link #explainAffectation}
     * and {@link #simulateSwap} to attach the business-facing niveau/catégorie/
     * description to a raw {@code ConstraintAnalysis} without a linear scan.
     */
    static final Map<String, ConstraintCatalog.ConstraintDefinition> DEFINITIONS_PAR_NOM =
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
     * Simulates a seat changing hands by a gesture on a day view (issue #308),
     * on an already-solved planning, nothing persisted — the substitution lives
     * only for the second {@code analyze} call, like {@link #simulateSwap}.
     *
     * <p>One rule, three gestures. After the move, {@code posteSource} holds
     * {@code animateurCible} and {@code posteCible}, when there is one, holds
     * {@code animateurSource}:</p>
     * <ul>
     * <li>dropped on an <b>empty</b> seat: the animateur moves there and their
     * seat is left empty (the hole moves, the hard score does not);</li>
     * <li>dropped on a <b>held</b> seat: the two animateurs swap seats;</li>
     * <li>dropped on a <b>person</b> (the rail): that person takes the seat —
     * and when they already hold one on the same créneau, the two seats are
     * swapped rather than leaving them in two places at once.</li>
     * </ul>
     *
     * <p>The verdict is planning-wide, like {@link #simulateEchange}: moving a
     * seat can break a hard rule on a seat it does not touch (weekly hours,
     * rest), so feasibility is read on the global hard score and the extra
     * hard matches — never on the two seats alone.</p>
     *
     * @param posteCibleId    the seat dropped on, or {@code null} when a person was
     * @param animateurCibleId the person dropped on, ignored when a seat was given
     */
    private static boolean memePersonne(Animateur premier, Animateur second) {
        return premier != null && second != null && Objects.equals(premier.getId(), second.getId());
    }

    public DeplacementSimulation simulateDeplacement(PlanningEvenement solved, String posteSourceId,
            String posteCibleId, String animateurCibleId) {
        PosteAffectation source = findPoste(solved, posteSourceId);
        Animateur animateurSource = source.getAnimateur();
        if (animateurSource == null) {
            throw new BusinessError.Invalid("Le siège de départ est vide : rien à déplacer.");
        }
        PosteAffectation cible;
        Animateur animateurCible;
        if (posteCibleId != null) {
            cible = findPoste(solved, posteCibleId);
            if (cible == source) {
                throw new BusinessError.Invalid("Le siège d'arrivée est le siège de départ : rien à déplacer.");
            }
            animateurCible = cible.getAnimateur();
        } else {
            if (animateurCibleId == null) {
                throw new BusinessError.Invalid("Indiquez le siège ou la personne qui reçoit l'affectation.");
            }
            animateurCible = findAnimateur(solved, animateurCibleId);
            // By id, never by reference: a planning deserialised from a request
            // body carries one Animateur instance per poste, so == is false
            // even for the same person — the gesture would then be scored as
            // "nobody moves" while the write performs a swap.
            if (memePersonne(animateurCible, animateurSource)) {
                throw new BusinessError.Invalid("Cette personne tient déjà ce siège : rien à déplacer.");
            }
            // The receiver's own seat on that créneau, if any: two people
            // trading créneaux is a swap, not one of them in two places at once.
            cible = solved.getPostes().stream()
                    .filter(poste -> poste != source && memePersonne(poste.getAnimateur(), animateurCible)
                            && poste.getCreneau() != null && source.getCreneau() != null
                            && Objects.equals(poste.getCreneau().getId(), source.getCreneau().getId()))
                    .findFirst()
                    .orElse(null);
        }

        PlanningAnalysis avant = constraintDiagnosticService.analyze(solved);
        PlanningAnalysis apres;
        source.setAnimateur(animateurCible);
        if (cible != null) {
            cible.setAnimateur(animateurSource);
        }
        try {
            apres = constraintDiagnosticService.analyze(solved);
        } finally {
            source.setAnimateur(animateurSource);
            if (cible != null) {
                cible.setAnimateur(animateurCible);
            }
        }
        HardMediumSoftScore scoreAvant = avant.score();
        HardMediumSoftScore scoreApres = apres.score();
        return new DeplacementSimulation(posteSourceId, cible == null ? null : cible.getId(),
                animateurSource.getId(), animateurCible == null ? null : animateurCible.getId(),
                scoreAvant, scoreApres, scoreApres.subtract(scoreAvant),
                scoreApres.hardScore() < scoreAvant.hardScore(), extraHardViolations(avant, apres));
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
     * What a seat movement (issue #308) would do, and whether it may. After it,
     * {@code posteSourceId} holds {@code animateurCibleId} (possibly nobody) and
     * {@code posteCibleId}, when set, holds {@code animateurSourceId}.
     *
     * @param posteCibleId       the seat receiving the moved animateur; {@code null}
     *                           when a person received the seat instead
     * @param animateurCibleId   who ends up on the source seat: the receiver, or
     *                           {@code null} when the seat is left empty
     * @param casseContrainteDure true when the plan's hard score gets worse — the
     *                           gesture is then refused, never applied
     */
    public record DeplacementSimulation(String posteSourceId, String posteCibleId, String animateurSourceId,
            String animateurCibleId, HardMediumSoftScore scoreAvant, HardMediumSoftScore scoreApres,
            HardMediumSoftScore delta, boolean casseContrainteDure, List<HardViolation> nouvellesViolationsDures) {
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

}
