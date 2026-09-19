package dev.sylvain.planning.solver;

import ai.timefold.solver.core.impl.heuristic.selector.common.decorator.SelectionFilter;
import ai.timefold.solver.core.impl.score.director.ScoreDirector;
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
        // A pinned hole — a past seat nobody held (ADR 0044) — is not one the
        // selectors can fill: attempts spent on it are attempts lost. An empty
        // renfort (issue #505) is not a hole at all: nobody is missing on it,
        // and the whole point of this selector is to clear the last
        // posteDoitEtrePourvu violations, which a renfort never produces. The
        // ordinary Change and Swap selectors still reach it — that is how it
        // gets taken once there is nothing better to do with somebody.
        return poste.getAnimateur() == null && !poste.isVerrouille() && !poste.isOptionnel();
    }
}
