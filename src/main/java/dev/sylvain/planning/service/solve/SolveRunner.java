package dev.sylvain.planning.service.solve;

import java.util.List;
import java.util.function.Consumer;

import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.score.director.ScoreDirectorFactoryConfig;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;

import dev.sylvain.planning.domain.AffectationPubliee;
import dev.sylvain.planning.domain.ConstraintToggle;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.solver.PlanningConstraintProvider;
import dev.sylvain.planning.service.referentiel.ReferenceData;

/**
 * Runs a solve, and fills the problem with the server-side facts first.
 *
 * <p>That preparation is the point: ad hoc constraints, legal parameters,
 * constraint toggles, quality caps, published plan and weights are always
 * overwritten from the edition, never taken from what a caller sent — a
 * planning posted by a client cannot loosen a threshold or decide what people
 * were told. The same preparation runs before a what-if or a diagnostic, which
 * is why it is handed to them as a {@link Consumer}.</p>
 *
 * <p>Split out of {@link PlanningService}, which keeps the four solve entry
 * points as a façade.</p>
 */
final class SolveRunner {

    private final SolverConfiguration configuration;
    private final ReferenceData referenceDataService;

    /** The published plan; {@code null} in the plain-Java harnesses that build {@link PlanningService} with {@code new}. */
    private final PlanSnapshotService snapshots;

    SolveRunner(SolverConfiguration configuration, ReferenceData referenceDataService,
            PlanSnapshotService snapshots) {
        this.configuration = configuration;
        this.referenceDataService = referenceDataService;
        this.snapshots = snapshots;
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
        Solver<PlanningEvenement> solver = configuration.resolveSolverFactory(secondsLimitOverride).buildSolver();
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

    void prepareProblem(PlanningEvenement problem) {
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
        problem.setParametresQualite(List.of(new ParametresQualite(configuration.maxEmplacementsParJour())));
        // Never sent by a caller (the field is @JsonIgnore-d on PlanningEvenement),
        // so this always overwrites the ConstraintWeightOverrides.none() default.
        problem.setPonderationsContraintes(
                configuration.constraintWeightOverrides(problem.getPonderationsScenario()));
    }

    /**
     * The seats of the last published plan, as facts of {@code stabiliteDuPlanPublie};
     * empty when nothing was published, or when the snapshot service is not
     * wired (a plain-Java harness). A seat the publication left empty carries
     * nobody to keep and is skipped.
     */
    List<AffectationPubliee> affectationsPubliees() {
        PlanSnapshotService snapshotService = snapshots;
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
}
