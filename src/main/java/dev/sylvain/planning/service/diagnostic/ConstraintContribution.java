package dev.sylvain.planning.service.diagnostic;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import java.util.List;

/**
 * What one constraint contributed to a planning's score: its total, and the
 * facts of every match that produced it.
 *
 * <p>Not to be confused with {@code PlanningDiagnosticService.ConstraintDiagnostic},
 * which is the API payload derived from this one: there the matches are already
 * formatted into human-readable lines and capped for a popup, here they are
 * still the raw domain objects the constraint justified itself with.</p>
 *
 * <p>A constraint that matched nothing is still listed, with a zero score and
 * an empty match list. The "contraintes respectées" half of the per-assignment
 * explanation is built by looking for exactly those, so dropping them would
 * silently shrink that screen.</p>
 */
public record ConstraintContribution(String constraintName, HardMediumSoftScore score, List<MatchFacts> matches) {

    public ConstraintContribution {
        matches = List.copyOf(matches);
    }

    /** Derived rather than stored, so the count can never drift from the list. */
    public int matchCount() {
        return matches.size();
    }
}
