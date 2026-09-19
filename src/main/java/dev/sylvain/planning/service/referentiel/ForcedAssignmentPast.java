package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.PastHorizon;
import java.time.LocalDate;

/**
 * The one exemption {@code AdHocConstraints.affectationForcee} grants the past
 * (ADR 0044), read by the three checks that report an unsatisfiable forced
 * assignment before a solve.
 *
 * <p>That constraint charges an exception <b>unless every seat of its scope is
 * past</b> — {@code forcedAssignmentsInThePast}. A rule written for a day
 * already worked is history, not a hole a solve can fill, so reporting it as a
 * blocking cause would make the Solveur screen ask for a confirmation on every
 * run for the rest of the event, over something nobody can act on.</p>
 *
 * <p><b>Read on the date alone</b>, where the solver reads the seat's effective
 * start ({@code FrozenPast.isPast}). These checks work on the referential, not
 * on a built problem, and a seat's effective start belongs to the problem. The
 * difference is one day wide and errs on the side of <em>reporting</em>: a
 * timeslot of today counts as ahead here even once it has started, which is
 * what this check did before it knew about the past at all. The case it exists
 * for — an edition behind us, or a rule left on a day already gone — is
 * answered exactly.</p>
 */
final class ForcedAssignmentPast {

    private ForcedAssignmentPast() {}

    /**
     * Whether that date is still to be played. Always {@code true} without a
     * horizon: the freeze is off ({@code PASSE_FIGE=false}), or the caller has
     * no clock, and nothing is then behind us.
     */
    static boolean aVenir(LocalDate date, PastHorizon horizon) {
        return horizon == null || date == null || !date.isBefore(horizon.today());
    }
}
