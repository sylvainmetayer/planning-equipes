package dev.sylvain.planning.solver;

import java.time.LocalTime;

import ai.timefold.solver.core.impl.heuristic.selector.common.decorator.SelectionFilter;
import ai.timefold.solver.core.impl.score.director.ScoreDirector;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;

/**
 * Seats worth ruining together with an unfilled one: the unfilled seats
 * themselves, and every seat whose créneau overlaps one of theirs in time.
 *
 * <p>Filling a hole at a saturated hour takes a chain — A leaves a seat for
 * the hole, B free at that hour takes A's seat — whose every link is
 * hard-neutral and, once the published plan has a price (ADR 0025), costs
 * medium: the local search sees the intermediate state and refuses it. The
 * ruin-and-recreate move selector of the feasibility phase evaluates the
 * chain as one move, provided the seats it ruins are the ones the chain
 * runs through: those live at the hole's hour, which is what this filter
 * narrows the ruined set to. Nothing to ruin while there is no hole, which
 * is also when the phase ends.</p>
 */
public final class HoleNeighbourPosteFilter implements SelectionFilter<PlanningEvenement, PosteAffectation> {

    @Override
    public boolean accept(ScoreDirector<PlanningEvenement> scoreDirector, PosteAffectation poste) {
        Creneau creneau = poste.getCreneau();
        if (creneau == null) {
            return false;
        }
        if (poste.getAnimateur() == null) {
            return true;
        }
        for (PosteAffectation autre : scoreDirector.getWorkingSolution().getPostes()) {
            if (autre.getAnimateur() == null && autre.getCreneau() != null && overlap(creneau, autre.getCreneau())) {
                return true;
            }
        }
        return false;
    }

    static boolean overlap(Creneau a, Creneau b) {
        if (a.getDate() == null || !a.getDate().equals(b.getDate())) {
            return false;
        }
        return start(a) < end(b) && start(b) < end(a);
    }

    private static int start(Creneau creneau) {
        LocalTime debut = creneau.getHeureDebut();
        return debut == null ? 0 : debut.toSecondOfDay() / 60;
    }

    private static int end(Creneau creneau) {
        return start(creneau) + creneau.getDureeMinutes();
    }
}
