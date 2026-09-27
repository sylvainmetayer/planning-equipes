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
 * Feasibility first, stability after: the solve of an edition that has
 * published a plan.
 *
 * <p>Filling a hole often takes a chain: someone leaves the seat they were
 * told about to take the hole, then that seat is filled in turn. The first
 * link is hard-neutral, and once a plan is published
 * {@code stabiliteDuPlanPublie} gives it a medium price, so late acceptance
 * stops taking it and the plan stays infeasible — a medium rule deciding hard
 * feasibility, which the levels exist to forbid. The regrouping move of the
 * hard run of days ({@code WeekRelocationMoveIteratorFactory}) is the worst
 * case, but not the only one: any published plan the edition has since
 * outgrown can hold the search on such a plateau. So a solve with a published
 * plan runs in two stages within the same job: the first with the stability
 * rule weighed at zero, stopped on feasibility or at
 * {@link #FEASIBILITY_SHARE_NUMERATOR}/{@link #FEASIBILITY_SHARE_DENOMINATOR}
 * of the budget; the second from the plan it reached, the rule back at its
 * weight, on the rest of the budget. Timefold keeps the best solution, so the
 * second stage cannot lose the feasibility the first one found, and its
 * change and swap moves bring people back to their published seats wherever
 * the hard rules allow. The second stage runs even when the first fell short:
 * it starts from the best plan found, which is no worse a starting point than
 * a single solve would have had. See ADR 0067.</p>
 *
 * <p>Every other solve — nothing published, or the stability rule switched
 * off by the edition — keeps the single stage it always had.</p>
 */
final class FeasibilityFirstSolve {

    /** The rule whose price is suspended during the first stage. */
    static final String STABILITY_RULE = "stabiliteDuPlanPublie";

    /** The first stage stops on feasibility, and at the latest after two thirds of the budget. */
    static final long FEASIBILITY_SHARE_NUMERATOR = 2;

    static final long FEASIBILITY_SHARE_DENOMINATOR = 3;

    private FeasibilityFirstSolve() {}

    /**
     * Whether a <em>prepared</em> problem — toggles and published facts in
     * place — is solved in two stages.
     */
    static boolean applies(PlanningEvenement prepared) {
        return prepared != null
                && prepared.getAffectationsPubliees() != null
                && !prepared.getAffectationsPubliees().isEmpty()
                && ConstraintCatalog.isActive(prepared.getConstraintsDesactivees(), STABILITY_RULE);
    }

    /** The first stage's share of a budget, in milliseconds, never under one. */
    static long feasibilityMillis(long budgetMillis) {
        return Math.max(1, budgetMillis * FEASIBILITY_SHARE_NUMERATOR / FEASIBILITY_SHARE_DENOMINATOR);
    }

    /** A stage's duration as the recap says it: whole seconds, a stage that ran at all counting one. */
    static long roundUpToSeconds(long millis) {
        return millis <= 0 ? 0 : (millis + 999) / 1000;
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
