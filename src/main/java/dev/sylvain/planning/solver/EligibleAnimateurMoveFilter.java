package dev.sylvain.planning.solver;

import ai.timefold.solver.core.api.score.director.ScoreDirector;
import ai.timefold.solver.core.impl.heuristic.selector.common.decorator.SelectionFilter;
import ai.timefold.solver.core.impl.heuristic.selector.move.generic.ChangeMove;
import ai.timefold.solver.core.impl.heuristic.selector.move.generic.SwapMove;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.PosteAffectation;

/**
 * The {@code animateurRange} value range spans every animateur (~150), because
 * eligibility depends on the target poste (stand competence, date
 * availability), not on a static property of the animateur. Without this
 * filter, the construction heuristic and local search spend most of their
 * moves on obviously-hard-invalid assignments (wrong competence, declared
 * unavailable) and pay for a full incremental score calculation to find that
 * out. Rejecting them here, before scoring, is what keeps solving fast enough
 * on constrained hardware (e.g. a Raspberry Pi) for the full ~150
 * animateurs / 2000+ postes scenario.
 */
public final class EligibleAnimateurMoveFilter {

    private EligibleAnimateurMoveFilter() {
    }

    private static boolean estEligible(PosteAffectation poste, Animateur animateur) {
        return animateur == null
                || (animateur.possedeCompetencePour(poste.getStand())
                        && !animateur.estIndisponibleLe(poste.getCreneau().getDate()));
    }

    public static final class ChangeMoveFilter implements SelectionFilter<PlanningFestival, ChangeMove<PlanningFestival>> {
        @Override
        public boolean accept(ScoreDirector<PlanningFestival> scoreDirector, ChangeMove<PlanningFestival> move) {
            PosteAffectation poste = (PosteAffectation) move.getEntity();
            Animateur animateur = (Animateur) move.getToPlanningValue();
            return estEligible(poste, animateur);
        }
    }

    public static final class SwapMoveFilter implements SelectionFilter<PlanningFestival, SwapMove<PlanningFestival>> {
        @Override
        public boolean accept(ScoreDirector<PlanningFestival> scoreDirector, SwapMove<PlanningFestival> move) {
            PosteAffectation left = (PosteAffectation) move.getLeftEntity();
            PosteAffectation right = (PosteAffectation) move.getRightEntity();
            return estEligible(left, right.getAnimateur()) && estEligible(right, left.getAnimateur());
        }
    }
}
