package dev.sylvain.planning.solver;

import ai.timefold.solver.core.api.domain.common.ComparatorFactory;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/**
 * Orders postes by how many animateurs are eligible for them (competence +
 * availability), ascending. The FIRST_FIT_DECREASING construction heuristic
 * reverses this to place the scarcest postes first, while the pool of
 * eligible animateurs is still intact, instead of leaving them for last with
 * nobody left to assign — the main cause of leftover hard-constraint
 * violations (unfilled postes, double-bookings) FIRST_FIT was producing.
 *
 * <p>Eligibility depends only on the poste's stand and its créneau's date, not
 * on the poste itself, and neither changes during a solve — so the count is
 * computed once per (stand, date) pair and reused. Sorting n postes costs
 * O(n log n) comparisons, each of which previously rescanned all ~150
 * animateurs: on {@code scenario-complet.yaml} that is ~2088 postes reduced to
 * at most 20 stands × 15 dates of distinct work.</p>
 *
 * <p><b>Under the hard run of days, the calendar comes first</b> (ADR 0068).
 * With {@code maxJoursConsecutifsTravaillesDur} on, what the plan runs short
 * of is person-days in a row, and scarcity alone places the seats nearly
 * everyone can hold — set-up, dismantling — last, after most people already
 * work the days that follow: a set-up day added in front of their run would
 * break the cap, so the construction leaves it empty, and the local search
 * then spends its time building the chains of days that fill it. Placed day
 * by day, earliest first, the runs are built in the order they are counted,
 * and the holes the construction leaves are spread over the days, where a
 * change or a swap fills them. Scarcity still orders the seats of a same
 * day. Every other edition keeps the scarcity order alone.</p>
 */
public final class PosteAffectationDifficultyComparatorFactory
        implements ComparatorFactory<PlanningEvenement, PosteAffectation> {

    @Override
    public Comparator<PosteAffectation> createComparator(PlanningEvenement solution) {
        // Confined to the comparator returned here, which Timefold uses from a
        // single thread while sorting the entities of that one solution.
        Map<EligibilityKey, Long> cache = new HashMap<>();
        Comparator<PosteAffectation> scarcity = Comparator.comparingLong((PosteAffectation poste) ->
                        cache.computeIfAbsent(EligibilityKey.of(poste), key -> eligibleAnimateurCount(solution, poste)))
                .reversed();
        if (WeekRelocationMoveIteratorFactory.hardRunCap(solution) <= 0) {
            return scarcity;
        }
        // The heuristic places the greatest first: an earlier date is the
        // greater, and a seat with no date comes last.
        return Comparator.comparing(
                        PosteAffectationDifficultyComparatorFactory::dateOf,
                        Comparator.nullsFirst(Comparator.<LocalDate>reverseOrder()))
                .thenComparing(scarcity);
    }

    private static LocalDate dateOf(PosteAffectation poste) {
        return poste.getCreneau() == null ? null : poste.getCreneau().getDate();
    }

    private static long eligibleAnimateurCount(PlanningEvenement solution, PosteAffectation poste) {
        LocalDate date = poste.getCreneau() == null ? null : poste.getCreneau().getDate();
        return solution.getAnimateurs().stream()
                .filter(animateur -> animateur.hasCompetenceFor(poste.getStand()) && !animateur.isIndisponibleOn(date))
                .count();
    }

    /** Everything the eligible-animateur count actually depends on. */
    private record EligibilityKey(String standId, LocalDate date) {

        static EligibilityKey of(PosteAffectation poste) {
            return new EligibilityKey(
                    poste.getStand() == null ? null : poste.getStand().getId(),
                    poste.getCreneau() == null ? null : poste.getCreneau().getDate());
        }
    }
}
