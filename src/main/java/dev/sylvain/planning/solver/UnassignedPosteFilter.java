package dev.sylvain.planning.solver;

import ai.timefold.solver.core.api.score.director.ScoreDirector;
import ai.timefold.solver.core.impl.heuristic.selector.common.decorator.SelectionFilter;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;

/**
 * Restricts an entity selector to postes still unassigned. Used to bias a
 * dedicated slice of the local search's move selection towards the (usually
 * tiny) set of empty seats, which plain uniform Change/Swap selection over
 * ~2000+ mostly-already-filled postes samples far too rarely to reliably
 * clear the last few {@code posteDoitEtrePourvu} violations within the
 * solving time budget.
 */
public final class UnassignedPosteFilter implements SelectionFilter<PlanningEvenement, PosteAffectation> {

    @Override
    public boolean accept(ScoreDirector<PlanningEvenement> scoreDirector, PosteAffectation poste) {
        return poste.getAnimateur() == null;
    }
}
