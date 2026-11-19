package dev.sylvain.planning.service.solve;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.Score;
import ai.timefold.solver.core.api.solver.event.BestSolutionChangedEvent;
import ai.timefold.solver.core.api.solver.event.EventProducerId;
import dev.sylvain.planning.domain.PlanningEvenement;

/**
 * A best score announced by a search that is not Timefold's own — a worker's
 * ({@link RemoteSolver}), the direct engine's ({@link HandSearchSolver}) — in
 * the form a job follows any solver through. It carries no solution: the plan
 * only exists on the application's problem once the search is over.
 */
record BestScore(long timeMillisSpent, HardMediumSoftScore score)
        implements BestSolutionChangedEvent<PlanningEvenement> {

    @Override
    public long getTimeMillisSpent() {
        return timeMillisSpent;
    }

    @Override
    public EventProducerId getProducerId() {
        return null;
    }

    @Override
    public PlanningEvenement getNewBestSolution() {
        return null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override
    public Score getNewBestScore() {
        return score;
    }

    @Override
    public boolean isNewBestSolutionInitialized() {
        return true;
    }

    @Override
    public boolean isEveryProblemChangeProcessed() {
        return true;
    }
}
