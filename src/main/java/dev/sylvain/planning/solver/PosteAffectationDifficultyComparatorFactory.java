package dev.sylvain.planning.solver;

import java.util.Comparator;

import ai.timefold.solver.core.api.domain.common.ComparatorFactory;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.PosteAffectation;

/**
 * Orders postes by how many animateurs are eligible for them (competence +
 * availability), ascending. The FIRST_FIT_DECREASING construction heuristic
 * reverses this to place the scarcest postes first, while the pool of
 * eligible animateurs is still intact, instead of leaving them for last with
 * nobody left to assign — the main cause of leftover hard-constraint
 * violations (unfilled postes, double-bookings) FIRST_FIT was producing.
 */
public final class PosteAffectationDifficultyComparatorFactory
        implements ComparatorFactory<PlanningFestival, PosteAffectation> {

    @Override
    public Comparator<PosteAffectation> createComparator(PlanningFestival solution) {
        return Comparator.comparingLong((PosteAffectation poste) -> eligibleAnimateurCount(solution, poste))
                .reversed();
    }

    private static long eligibleAnimateurCount(PlanningFestival solution, PosteAffectation poste) {
        return solution.getAnimateurs().stream()
                .filter(animateur -> animateur.possedeCompetencePour(poste.getStand())
                        && !animateur.estIndisponibleLe(poste.getCreneau().getDate()))
                .count();
    }
}
