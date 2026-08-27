package dev.sylvain.planning.service.diagnostic;

import java.util.ArrayList;
import java.util.List;

import ai.timefold.solver.core.api.score.analysis.ConstraintAnalysis;
import ai.timefold.solver.core.api.score.analysis.MatchAnalysis;
import ai.timefold.solver.core.api.score.analysis.ScoreAnalysis;
import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.stream.ConstraintJustification;
import ai.timefold.solver.core.api.score.stream.DefaultConstraintJustification;
import ai.timefold.solver.core.api.solver.SolutionManager;
import ai.timefold.solver.core.api.solver.SolverFactory;
import dev.sylvain.planning.domain.PlanningEvenement;

/**
 * Diagnoses through Timefold's {@code SolutionManager.analyze()} — what every
 * consumer used to call directly, now behind {@link ConstraintDiagnosticService}
 * and reachable through one seam instead of a dozen call sites.
 *
 * <p>Nothing here is new behaviour: the conversion below is the same walk over
 * {@code constraintAnalyses()} the callers were doing themselves, done once.</p>
 */
public final class SolutionManagerConstraintDiagnosticService implements ConstraintDiagnosticService {

    private final SolutionManager<PlanningEvenement, HardMediumSoftScore> solutionManager;

    public SolutionManagerConstraintDiagnosticService(SolverFactory<PlanningEvenement> solverFactory) {
        this.solutionManager = SolutionManager.create(solverFactory);
    }

    @Override
    public PlanningAnalysis analyze(PlanningEvenement solution) {
        ScoreAnalysis<HardMediumSoftScore> analysis = solutionManager.analyze(solution);
        List<ConstraintContribution> contributions = new ArrayList<>();
        for (ConstraintAnalysis<HardMediumSoftScore> constraintAnalysis : analysis.constraintAnalyses()) {
            contributions.add(new ConstraintContribution(
                    constraintAnalysis.constraintRef().constraintName(),
                    constraintAnalysis.score(),
                    matchFacts(constraintAnalysis)));
        }
        return new PlanningAnalysis(analysis.score(), contributions);
    }

    private static List<MatchFacts> matchFacts(ConstraintAnalysis<HardMediumSoftScore> constraintAnalysis) {
        List<MatchAnalysis<HardMediumSoftScore>> matches = constraintAnalysis.matches();
        // Null under a shallow fetch policy, which this class never asks for —
        // guarded anyway so a future caller changing the policy gets an empty
        // list rather than a NullPointerException three screens away.
        if (matches == null) {
            return List.of();
        }
        return matches.stream()
                .map(match -> new MatchFacts(factsOf(match.justification())))
                .toList();
    }

    /**
     * A constraint that does not name its own justification type gets Timefold's
     * default one, whose facts are the tuple the constraint stream matched on.
     * Anything else is a justification object the constraint built itself, and
     * is its own single fact.
     */
    static List<Object> factsOf(ConstraintJustification justification) {
        return justification instanceof DefaultConstraintJustification defaultJustification
                ? defaultJustification.getFacts()
                : List.of(justification);
    }
}
