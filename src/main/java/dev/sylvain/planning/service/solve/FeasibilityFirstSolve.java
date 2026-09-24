package dev.sylvain.planning.service.solve;

import dev.sylvain.planning.domain.AffectationPubliee;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.solver.ConstraintCatalog;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Feasibility first, stability after: the solve of an edition that holds its
 * run of days hard <b>and</b> has published a plan.
 *
 * <p>Under {@code maxJoursConsecutifsTravaillesDur} the search fills a hole by
 * freeing a person-day elsewhere, and the move that frees them
 * ({@code WeekRelocationMoveIteratorFactory}'s regrouping) is hard-neutral.
 * Once a plan is published, {@code stabiliteDuPlanPublie} gives each of those
 * moves a medium price, late acceptance stops taking them, and the plan stays
 * infeasible — a medium rule deciding hard feasibility, which the levels exist
 * to forbid. So such a solve runs in two stages within the same job: the first
 * with the stability rule weighed at zero, stopped on feasibility or at
 * {@link #FEASIBILITY_SHARE_NUMERATOR}/{@link #FEASIBILITY_SHARE_DENOMINATOR}
 * of the budget; the second from the plan it reached, the rule back at its
 * weight, on the rest of the budget. Timefold keeps the best solution, so the
 * second stage cannot lose the feasibility the first one found, and its
 * change and swap moves bring people back to their published seats wherever
 * the hard rules allow. The second stage runs even when the first fell short:
 * it starts from the best plan found, which is no worse a starting point than
 * a single solve would have had. See ADR 0050.</p>
 *
 * <p>Every other solve — the rule off, nothing published, or the stability
 * rule switched off by the edition — keeps the single stage it always had.</p>
 */
final class FeasibilityFirstSolve {

    /** The rule whose price is suspended during the first stage. */
    static final String STABILITY_RULE = "stabiliteDuPlanPublie";

    /** The first stage stops on feasibility, and at the latest after two thirds of the budget. */
    static final long FEASIBILITY_SHARE_NUMERATOR = 2;

    static final long FEASIBILITY_SHARE_DENOMINATOR = 3;

    private FeasibilityFirstSolve() {}

    /**
     * Whether a <em>prepared</em> problem — toggles, quality parameters and
     * published facts in place — is solved in two stages.
     */
    static boolean applies(PlanningEvenement prepared) {
        return prepared != null
                && HardRunCapSearch.applies(prepared)
                && prepared.getAffectationsPubliees() != null
                && !prepared.getAffectationsPubliees().isEmpty()
                && ConstraintCatalog.isActive(prepared.getConstraintsDesactivees(), STABILITY_RULE);
    }

    /** The first stage's share of a budget, never under one second. */
    static long feasibilitySeconds(long budgetSeconds) {
        return Math.max(1, budgetSeconds * FEASIBILITY_SHARE_NUMERATOR / FEASIBILITY_SHARE_DENOMINATOR);
    }

    /**
     * Published seats a plan no longer gives to the person who was told:
     * what {@code stabiliteDuPlanPublie} counts, one per seat of a published
     * line whose holder is not among the people the publication named there,
     * an empty seat included. Past seats are not counted, as the rule does not
     * charge them (ADR 0044).
     */
    static int publishedSeatsChanged(PlanningEvenement planning) {
        List<AffectationPubliee> published = planning.getAffectationsPubliees();
        if (published == null || published.isEmpty()) {
            return 0;
        }
        Map<String, Set<String>> holdersByLine = new HashMap<>();
        for (AffectationPubliee fact : published) {
            holdersByLine.computeIfAbsent(fact.key(), key -> new HashSet<>()).add(fact.animateurId());
        }
        int changed = 0;
        for (PosteAffectation poste : planning.getPostes()) {
            if (poste.isPasse()
                    || poste.getStand() == null
                    || poste.getCreneau() == null
                    || poste.getCreneau().getDate() == null) {
                continue;
            }
            Set<String> holders = holdersByLine.get(AffectationPubliee.key(
                    poste.getStand().getId(),
                    poste.getCreneau().getDate(),
                    poste.getCreneau().getHeureDebut(),
                    poste.getCreneau().getHeureFin()));
            if (holders != null
                    && (poste.getAnimateur() == null
                            || !holders.contains(poste.getAnimateur().getId()))) {
                changed++;
            }
        }
        return changed;
    }
}
