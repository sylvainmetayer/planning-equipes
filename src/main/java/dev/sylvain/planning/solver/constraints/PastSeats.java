package dev.sylvain.planning.solver.constraints;

import dev.sylvain.planning.domain.PosteAffectation;
import java.util.Collection;

/**
 * « Compté, non reproché » (ADR 0044): the one rule every constraint applies
 * to the seats of timeslots already started ({@link PosteAffectation#isPasse()}).
 *
 * <p>A past seat <b>counts</b>: what somebody worked yesterday conditions the
 * rest they are owed tonight, the hours left in their week, the days in a
 * row they have reached. So the past stays in every stream, group and join.
 * But a past seat is <b>never reproached</b>: a match whose every seat is past
 * — a hole yesterday, a minor at night yesterday, a meal break missed
 * yesterday — is history the solver cannot mend, and charging it would keep a
 * feasible future from ever scoring zero hard. A match is therefore charged
 * only if at least one of the seats it involves is not past.</p>
 *
 * <p>The three shapes below cover the constraints that hold their seats in
 * hand — one seat, a pair, a grouped list. The constraints that only hold an
 * aggregate (a sum, a count, a set) ask the same question with an
 * {@code ifExists} on a seat of the same group that is not past; each says so
 * where it does.</p>
 */
final class PastSeats {

    private PastSeats() {}

    /** A match on one seat is charged unless that seat is past. */
    static boolean reproachable(PosteAffectation poste) {
        return !poste.isPasse();
    }

    /** A match on a pair is charged unless both seats are past. */
    static boolean reproachable(PosteAffectation a, PosteAffectation b) {
        return !(a.isPasse() && b.isPasse());
    }

    /** A match on a group is charged unless every seat of the group is past. */
    static boolean reproachable(Collection<PosteAffectation> postes) {
        for (PosteAffectation poste : postes) {
            if (!poste.isPasse()) {
                return true;
            }
        }
        return false;
    }
}
