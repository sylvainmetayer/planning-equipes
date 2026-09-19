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
 * <p><b>A renfort is placed last, whatever its rarity</b> (issue #505,
 * ADR 0046). The heuristic walks the entities in decreasing difficulty and
 * seats somebody on each in turn: an optional seat taken early is a person
 * spent before the seats that are owed have been served, and the plan ends
 * short of feasibility with its capacity comfortably filled. Measured on
 * {@code festival-hivernal.yaml}, which has 846 renforts: interleaved by
 * rarity alone, the solve ran the whole 900 s safety ceiling and stopped at
 * -6 hard where it reaches zero in 89 s. The optional band therefore sorts
 * below every mandatory seat, and the rarity order applies within each of the
 * two groups.</p>
 *
 * <p>Eligibility depends only on the poste's stand and its créneau's date, not
 * on the poste itself, and neither changes during a solve — so the count is
 * computed once per (stand, date) pair and reused. Sorting n postes costs
 * O(n log n) comparisons, each of which previously rescanned all ~150
 * animateurs: on {@code scenario-complet.yaml} that is ~2088 postes reduced to
 * at most 20 stands × 15 dates of distinct work.</p>
 */
public final class PosteAffectationDifficultyComparatorFactory
        implements ComparatorFactory<PlanningEvenement, PosteAffectation> {

    @Override
    public Comparator<PosteAffectation> createComparator(PlanningEvenement solution) {
        // Confined to the comparator returned here, which Timefold uses from a
        // single thread while sorting the entities of that one solution.
        Map<EligibilityKey, Long> cache = new HashMap<>();
        Comparator<PosteAffectation> parRarete = Comparator.comparingLong((PosteAffectation poste) ->
                        cache.computeIfAbsent(EligibilityKey.of(poste), key -> eligibleAnimateurCount(solution, poste)))
                .reversed();
        // Mandatory first: the heuristic takes the entities from the greatest
        // down, so a seat that is owed must compare above every renfort.
        return Comparator.comparingInt((PosteAffectation poste) -> poste.isOptionnel() ? 0 : 1)
                .thenComparing(parRarete);
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
