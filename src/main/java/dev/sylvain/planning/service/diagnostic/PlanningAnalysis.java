package dev.sylvain.planning.service.diagnostic;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import java.util.List;

/**
 * One planning's score broken down per constraint — everything the diagnostic
 * screens, the per-assignment explanation and the swap simulations read from
 * the solver.
 *
 * <p>Deliberately <b>not</b> Timefold's {@code ScoreAnalysis}. That type is only
 * obtainable through {@code SolutionManager.analyze()}, which the Community
 * edition of Timefold 2.x refuses to run; everything downstream of this record
 * therefore speaks a shape the project owns, and which implementation fills it
 * in is invisible to callers. See {@link ConstraintDiagnosticService}.</p>
 */
public record PlanningAnalysis(HardMediumSoftScore score, List<ConstraintContribution> contributions) {

    public PlanningAnalysis {
        contributions = List.copyOf(contributions);
    }
}
