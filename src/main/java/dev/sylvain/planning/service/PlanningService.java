package dev.sylvain.planning.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

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
import dev.sylvain.planning.service.diagnostic.ConstraintContribution;
import dev.sylvain.planning.service.diagnostic.ConstraintDiagnosticMode;
import dev.sylvain.planning.service.diagnostic.ConstraintDiagnosticService;
import dev.sylvain.planning.service.diagnostic.MatchFacts;
import dev.sylvain.planning.service.diagnostic.PlanningAnalysis;
import dev.sylvain.planning.solver.ConstraintCatalog;
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

    /** Everything the application answers about a plan without solving it again. */
    private final PlanningWhatIf whatIf;

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
        this.whatIf = new PlanningWhatIf(constraintDiagnosticService, referenceDataService,
                () -> planningPersistenceService, this::prepareProblem);
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
     * The diagnostic only builds per-match {@code violations} for these:
     * a soft or medium constraint like {@code souhaitsIncompatibles} can have
     * thousands of matches, which would bloat the diagnostic payload for a
     * detail nobody blocking on a failed solve needs to see.
     */
    static final Set<String> HARD_CONSTRAINT_NAMES = ConstraintCatalog.definitions().stream()
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
     * Every constraint definition indexed by name, so {@link PlanningWhatIf} can
     * attach the business-facing niveau/catégorie/description to a raw
     * {@code ConstraintAnalysis} without a linear scan. Read from there and from
     * {@link ScenarioYamlReader}; the methods of this class that used to use it
     * are now delegates.
     */
    static final Map<String, ConstraintCatalog.ConstraintDefinition> DEFINITIONS_PAR_NOM =
            ConstraintCatalog.definitions().stream()
                    .collect(Collectors.toUnmodifiableMap(ConstraintCatalog.ConstraintDefinition::name,
                            Function.identity()));


    // --- What-if: façade over PlanningWhatIf --------------------------------

    /** @see PlanningWhatIf#SUGGESTIONS_PLAFOND_DEFAUT */
    public static final int SUGGESTIONS_PLAFOND_DEFAUT = PlanningWhatIf.SUGGESTIONS_PLAFOND_DEFAUT;

    /** @see PlanningWhatIf#SUGGESTIONS_PLAFOND_MAX */
    public static final int SUGGESTIONS_PLAFOND_MAX = PlanningWhatIf.SUGGESTIONS_PLAFOND_MAX;

    /** @see PlanningWhatIf#explainAffectation */
    public PlanningWhatIf.AffectationExplanation explainAffectation(PlanningEvenement solved, String posteId) {
        return whatIf.explainAffectation(solved, posteId);
    }

    /** @see PlanningWhatIf#simulateSwap */
    public PlanningWhatIf.SwapSimulation simulateSwap(PlanningEvenement solved, String posteId,
            String animateurCandidatId) {
        return whatIf.simulateSwap(solved, posteId, animateurCandidatId);
    }

    /** @see PlanningWhatIf#suggererReparations */
    public PlanningWhatIf.SuggestionsReparation suggererReparations(PlanningEvenement solved, String posteId,
            Integer plafondDemande) {
        return whatIf.suggererReparations(solved, posteId, plafondDemande);
    }

    /** @see PlanningWhatIf#creneauAvailability */
    public PlanningWhatIf.CreneauAvailability creneauAvailability(PlanningEvenement solved, Long creneauId,
            String standId, String posteId) {
        return whatIf.creneauAvailability(solved, creneauId, standId, posteId);
    }

    /** @see PlanningWhatIf#persistedCreneauAvailability */
    public PlanningWhatIf.CreneauAvailability persistedCreneauAvailability(Long creneauId, String standId,
            String posteId) {
        return whatIf.persistedCreneauAvailability(creneauId, standId, posteId);
    }

    /** @see PlanningWhatIf#applyReparation */
    public void applyReparation(String posteId, String animateurId) {
        whatIf.applyReparation(posteId, animateurId);
    }

    /** @see PlanningWhatIf#applyReparations */
    public void applyReparations(PlanningEvenement persiste, List<String> posteIds, String animateurId) {
        whatIf.applyReparations(persiste, posteIds, animateurId);
    }

    /** @see PlanningWhatIf#simulateEchange */
    public PlanningWhatIf.EchangeSimulation simulateEchange(PlanningEvenement solved, String demandeurId,
            String cibleId, long creneauId, String standId) {
        return whatIf.simulateEchange(solved, demandeurId, cibleId, creneauId, standId);
    }

    /** @see PlanningWhatIf#simulateDeplacement */
    public PlanningWhatIf.DeplacementSimulation simulateDeplacement(PlanningEvenement solved, String posteSourceId,
            String posteCibleId, String animateurCibleId) {
        return whatIf.simulateDeplacement(solved, posteSourceId, posteCibleId, animateurCibleId);
    }

    /** @see PlanningWhatIf#simulateDirectedEchange */
    public PlanningWhatIf.EchangeSimulation simulateDirectedEchange(PlanningEvenement solved, String demandeurId,
            String cibleId, long creneauId, String standId, long creneauCibleId, String standCibleId) {
        return whatIf.simulateDirectedEchange(solved, demandeurId, cibleId, creneauId, standId, creneauCibleId,
                standCibleId);
    }

    /** @see PlanningWhatIf#suggererEchanges */
    public PlanningWhatIf.SuggestionsEchange suggererEchanges(PlanningEvenement solved, String demandeurId,
            long creneauId, String standId, Integer plafondDemande) {
        return whatIf.suggererEchanges(solved, demandeurId, creneauId, standId, plafondDemande);
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
    static List<String> formatViolations(List<MatchFacts> matches) {
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
