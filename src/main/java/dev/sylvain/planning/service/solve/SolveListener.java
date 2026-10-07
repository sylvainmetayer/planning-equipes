package dev.sylvain.planning.service.solve;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.solver.Solver;
import dev.sylvain.planning.domain.PlanningEvenement;
import java.util.function.Consumer;

/**
 * What a job hands a solve beyond the solver it is given: the score of a
 * starting plan that is already complete.
 *
 * <p>Timefold announces a new best solution only when the best <em>changes</em>
 * — its construction heuristic fires nothing when every seat is already
 * held, and the local search nothing until it strictly improves. A re-solve
 * that starts from a feasible plan is usable from its first second, and would
 * otherwise say so only at its first improvement, or never on a run that
 * finds none. {@link SolveRunner} scores such a starting plan once, before the
 * solver starts, and tells the listener.</p>
 *
 * <p>A plain {@code Consumer<Solver>} is still accepted wherever this is: the
 * harnesses and the synchronous entry points have no job to tell.</p>
 */
interface SolveListener extends Consumer<Solver<PlanningEvenement>> {

    /** The score of the starting plan, every seat already held; called before the first solver is handed over. */
    void startingScore(HardMediumSoftScore score);
}
