package dev.sylvain.planning.service.diagnostic;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.analysis.ConstraintAnalysis;
import ai.timefold.solver.core.api.score.analysis.MatchAnalysis;
import ai.timefold.solver.core.api.score.analysis.ScoreAnalysis;
import ai.timefold.solver.core.api.solver.SolutionManager;
import ai.timefold.solver.core.api.solver.SolverFactory;
import dev.sylvain.planning.domain.PlanningEvenement;
import java.util.ArrayList;
import java.util.List;

/**
 * Diagnoses through Timefold's {@code SolutionManager.analyze()} — what every
 * consumer used to call directly, before {@link ConstraintDiagnosticService}
 * turned a dozen call sites into one seam.
 *
 * <p><b>No longer the default</b> (see {@link ConstraintDiagnosticMode}), and
 * kept for one reason: {@code ConstraintDiagnosticServiceContractTest} runs it
 * beside {@link ScoreDirectorConstraintDiagnosticService} and fails when the two
 * disagree. That makes it the oracle guarding a replacement built on Timefold's
 * internals — not a fallback for when the replacement misbehaves.</p>
 *
 * <p>It is expected to stop working on Timefold 2.x without an Enterprise
 * licence, since {@code analyze()} throws there. That is a property of this
 * class, not a regression of the feature.</p>
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
                    constraintAnalysis.constraintRef().id(),
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
                .map(match -> MatchFacts.of(match.justification()))
                .toList();
    }
}
