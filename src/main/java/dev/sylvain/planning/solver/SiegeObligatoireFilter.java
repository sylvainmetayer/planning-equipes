package dev.sylvain.planning.solver;

import ai.timefold.solver.core.impl.heuristic.selector.common.decorator.SelectionFilter;
import ai.timefold.solver.core.impl.score.director.ScoreDirector;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;

/**
 * Restricts an entity selector to the seats somebody is owed — renforts left
 * out (issue #505, ADR 0046).
 *
 * <p>Phase 1 of the local search has one job: reach feasibility. Renforts
 * cannot take part in it — leaving one empty is never a violation — so every
 * move spent on one is a move not spent closing a hole. Measured on
 * {@code festival-hivernal.yaml}, whose 3 438 owed seats gain 846 renforts:
 * with them in the selection the phase ran its whole 900 s ceiling over 4 262
 * steps and stopped at -9 hard, where the same 82 holes close in 59 s over 360
 * steps without. The construction heuristic is not the culprit — it ends on
 * the very same score either way, and fills almost no renfort, the medium and
 * soft scores at its boundary moving by less than a thousandth.</p>
 *
 * <p>Phase 2 carries no such filter, and that is where a renfort is taken: by
 * then the plan is feasible, and employing the volant available is exactly the
 * kind of improvement that phase exists for.</p>
 *
 * <p><b>Its counterpart is that nothing may hand phase 1 an occupied
 * renfort.</b> A seat this filter refuses is a seat the phase cannot empty
 * either, so somebody seated on one before it starts is stuck there: a hard
 * violation they cause would never be repaired, and a plan short of hands
 * could not take them back. That is why {@code ProblemBuilder} never
 * warm-starts a renfort. A locked one is safe by another route — it is pinned,
 * so no selector sees it at all.</p>
 */
public final class SiegeObligatoireFilter implements SelectionFilter<PlanningEvenement, PosteAffectation> {

    @Override
    public boolean accept(ScoreDirector<PlanningEvenement> scoreDirector, PosteAffectation poste) {
        return !poste.isOptionnel();
    }
}
