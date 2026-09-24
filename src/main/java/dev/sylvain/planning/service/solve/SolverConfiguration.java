package dev.sylvain.planning.service.solve;

import ai.timefold.solver.core.api.domain.solution.ConstraintWeightOverrides;
import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.solver.SolutionManager;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.score.director.ScoreDirectorFactoryConfig;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationCompositionStyle;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.service.diagnostic.ConstraintDiagnosticMode;
import dev.sylvain.planning.service.diagnostic.ConstraintDiagnosticService;
import dev.sylvain.planning.service.referentiel.ParametresQualiteDefaults;
import dev.sylvain.planning.service.referentiel.ReferenceData;
import dev.sylvain.planning.solver.ConstraintCatalog;
import dev.sylvain.planning.solver.PlanningConstraintProvider;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.Config;

/**
 * How this deployment configures a solve: the {@link SolverFactory} built from
 * {@code solver/solverConfig.xml}, its termination rules, and the weight every
 * constraint carries — the deployment default from
 * {@code application.properties}, overridden by whatever the current edition or
 * an imported scenario pinned.
 *
 * <p>Split out of {@link PlanningService}, which builds one in its constructor
 * and keeps {@link #effectiveConstraintWeights()} as a façade. Reading the
 * configuration here rather than injecting {@code @ConfigProperty} values is
 * deliberate and unchanged: the plain (non-CDI) tests build the service with
 * {@code new}, and pass a {@link Config} through.</p>
 */
final class SolverConfiguration {

    private final SolverFactory<PlanningEvenement> solverFactory;

    /**
     * Kept for {@link SolutionManager#update} alone — refreshing a reloaded
     * plan's score. Breaking a score down per constraint goes through
     * {@link ConstraintDiagnosticService}.
     */
    private final SolutionManager<PlanningEvenement, ?> solutionManager;

    /** The factory of an edition held to a hard run of days, at the default budget; built on first use. */
    private volatile SolverFactory<PlanningEvenement> hardRunCapSolverFactory;

    private final ConstraintDiagnosticService constraintDiagnosticService;
    private final ReferenceData referenceDataService;
    private final long defaultSecondsLimit;

    /** The shared factory's unimproved-time bailout, reapplied to a factory built for one large problem. */
    private final Long defaultUnimprovedSecondsLimit;

    /**
     * The deployment-wide weight of every constraint, read once from
     * {@code application.properties}. An edition may override any of them
     * (table {@code ponderation_contrainte}); see
     * {@link #constraintWeightOverrides(Map)}.
     */
    private final Map<String, Integer> configuredWeights;

    /**
     * The quality thresholds every solve carries as a problem fact: the cap of
     * {@code limiterEmplacementsParJour}, passed in because
     * {@code PlanningService} has always injected it, and the three anti-clopening
     * thresholds of {@code eviterFermeturePuisOuverture}, read from
     * {@code config} here rather than injected — the plain (non-CDI) tests build
     * the service with {@code new}, and adding three parameters to that
     * constructor would have rewritten twenty call sites to say nothing.
     */
    private final ParametresQualite parametresQualite;

    SolverConfiguration(
            Long secondsLimit,
            Long unimprovedSecondsLimit,
            Integer maxEmplacementsParJour,
            ReferenceData referenceDataService,
            Config config) {
        SolverConfig solverConfig = SolverConfig.createFromXmlResource("solver/solverConfig.xml");
        solverConfig.setScoreDirectorFactoryConfig(
                new ScoreDirectorFactoryConfig().withConstraintProviderClass(PlanningConstraintProvider.class));
        applyTermination(solverConfig, secondsLimit, unimprovedSecondsLimit);
        this.solverFactory = SolverFactory.create(solverConfig);
        this.solutionManager = SolutionManager.create(this.solverFactory);
        this.constraintDiagnosticService =
                ConstraintDiagnosticService.of(readDiagnosticMode(config), this.solverFactory);
        this.referenceDataService = referenceDataService;
        this.defaultSecondsLimit = secondsLimit;
        this.defaultUnimprovedSecondsLimit = unimprovedSecondsLimit;
        this.parametresQualite = readParametresQualite(maxEmplacementsParJour, config);
        this.configuredWeights = readConfiguredWeights(config);
    }

    SolutionManager<PlanningEvenement, ?> solutionManager() {
        return solutionManager;
    }

    ConstraintDiagnosticService diagnosticService() {
        return constraintDiagnosticService;
    }

    ParametresQualite parametresQualite() {
        return parametresQualite;
    }

    /**
     * The {@code planning.contraintes.*} block, read once at startup like the
     * weights: {@code application.properties} does not change at runtime. Since
     * issue #591 these are stored per edition too, and what this reads is the
     * <b>default</b> an edition starts from — what it solves with until someone
     * opens the Paramètres screen.
     *
     * <p>An hour left blank in the configuration reads as absent, not as
     * midnight: that is how a deployment neutralises the rule without editing
     * the catalogue, and {@code LocalTime.parse("")} would otherwise fail the
     * boot.</p>
     */
    /**
     * The {@code planning.contraintes.*} block, read once at startup like the
     * weights: {@code application.properties} does not change at runtime. Since
     * issue #591 these are stored per edition too, and what this reads is the
     * <b>default</b> an edition starts from — what it solves with until someone
     * opens the Paramètres screen.
     *
     * <p>{@code maxEmplacementsParJour} arrives already resolved, from the
     * constructor's own {@code @ConfigProperty}; everything else comes straight
     * from {@link ParametresQualiteDefaults}, which the repository's fallback
     * reads too.</p>
     */
    private static ParametresQualite readParametresQualite(Integer maxEmplacementsParJour, Config config) {
        ParametresQualite defauts = ParametresQualiteDefaults.of(config);
        return maxEmplacementsParJour == null
                ? defauts
                : new ParametresQualite(
                        maxEmplacementsParJour,
                        defauts.heureServiceTardif(),
                        defauts.heureServiceMatinal(),
                        defauts.reposSouhaiteApresServiceTardifMinutes(),
                        defauts.typologiesDistinctesMax(),
                        defauts.joursConsecutifsMax());
    }

    /** A {@code HH:mm} property, {@code null} when unset or left blank. */
    private static LocalTime readHeure(Config config, String propriete) {
        return config.getOptionalValue(propriete, String.class)
                .map(String::strip)
                .filter(valeur -> !valeur.isEmpty())
                .map(LocalTime::parse)
                .orElse(null);
    }

    /**
     * Which implementation breaks a score down per constraint. Read here rather
     * than injected as a {@code @ConfigProperty} because the plain (non-CDI)
     * tests build this service with {@code new}, and because the value is only
     * ever consumed once, to pick the implementation.
     */
    static ConstraintDiagnosticMode readDiagnosticMode(Config config) {
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
            poids.put(
                    definition.name(),
                    config.getOptionalValue("planning.constraint-weights." + definition.name(), Integer.class)
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
    ConstraintWeightOverrides<HardMediumSoftScore> constraintWeightOverrides(Map<String, Integer> scenario) {
        Map<String, HardMediumSoftScore> overrides = new HashMap<>();
        Map<String, Integer> poids = effectiveConstraintWeights(scenario);
        for (ConstraintCatalog.ConstraintDefinition definition : ConstraintCatalog.definitions()) {
            int weight = poids.getOrDefault(definition.name(), 1);
            if (weight == 1) {
                continue;
            }
            overrides.put(
                    definition.name(),
                    switch (definition.niveau()) {
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
     * {@link #resolveSolverFactory(Long)}, for one problem: a problem large
     * enough for {@link LargeProblemConstruction}, or held to a hard run of days
     * ({@link HardRunCapSearch}), gets a factory of its own, built from the same
     * XML adapted by {@link #adaptToProblem} and with the same termination as the
     * shared one would have had. The hard-run factory at the default budget
     * depends on nothing else about the problem, so it is built once and kept,
     * like the shared one: an edition that holds the rule solves again and again.
     */
    SolverFactory<PlanningEvenement> resolveSolverFactory(Long secondsLimitOverride, PlanningEvenement problem) {
        boolean large = LargeProblemConstruction.applies(problem);
        boolean hardRunCap = HardRunCapSearch.applies(problem);
        if (!large && !hardRunCap) {
            return resolveSolverFactory(secondsLimitOverride);
        }
        boolean defaultBudget = secondsLimitOverride == null || secondsLimitOverride.equals(defaultSecondsLimit);
        if (!large && defaultBudget) {
            SolverFactory<PlanningEvenement> cached = hardRunCapSolverFactory;
            if (cached == null) {
                cached = adaptedSolverFactory(secondsLimitOverride, problem);
                hardRunCapSolverFactory = cached;
            }
            return cached;
        }
        return adaptedSolverFactory(secondsLimitOverride, problem);
    }

    private SolverFactory<PlanningEvenement> adaptedSolverFactory(
            Long secondsLimitOverride, PlanningEvenement problem) {
        SolverConfig solverConfig = SolverConfig.createFromXmlResource("solver/solverConfig.xml");
        solverConfig.setScoreDirectorFactoryConfig(
                new ScoreDirectorFactoryConfig().withConstraintProviderClass(PlanningConstraintProvider.class));
        if (secondsLimitOverride == null || secondsLimitOverride.equals(defaultSecondsLimit)) {
            applyTermination(solverConfig, defaultSecondsLimit, defaultUnimprovedSecondsLimit);
        } else {
            applyTermination(solverConfig, secondsLimitOverride, 0L);
        }
        adaptToProblem(solverConfig, problem);
        return SolverFactory.create(solverConfig);
    }

    /**
     * Every adaptation of {@code solverConfig.xml} to one problem, in one place:
     * the production solve and {@code SolveRunner.solveUntilFeasible} — what the
     * scenario tests run — must search the same way, and a tuning added to one
     * of the two alone would have them validate a search production does not run.
     */
    static void adaptToProblem(SolverConfig solverConfig, PlanningEvenement problem) {
        if (LargeProblemConstruction.applies(problem)) {
            LargeProblemConstruction.adapt(solverConfig);
        }
        if (HardRunCapSearch.applies(problem)) {
            HardRunCapSearch.adapt(solverConfig);
        }
    }

    SolverFactory<PlanningEvenement> resolveSolverFactory(Long secondsLimitOverride) {
        if (secondsLimitOverride == null || secondsLimitOverride.equals(defaultSecondsLimit)) {
            return solverFactory;
        }
        SolverConfig solverConfig = SolverConfig.createFromXmlResource("solver/solverConfig.xml");
        solverConfig.setScoreDirectorFactoryConfig(
                new ScoreDirectorFactoryConfig().withConstraintProviderClass(PlanningConstraintProvider.class));
        // An explicit override means the caller wants exactly that many seconds;
        // the ambient unimproved-time bailout (e.g. the test profile's 2s, far
        // too tight for a large scenario solved with a bigger override) must not
        // silently cut it short, so it is disabled rather than reused here.
        applyTermination(solverConfig, secondsLimitOverride, 0L);
        return SolverFactory.create(solverConfig);
    }
}
