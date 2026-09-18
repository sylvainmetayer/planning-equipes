package dev.sylvain.planning.solver.constraints;

import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.bi.BiConstraintCollector;
import ai.timefold.solver.core.api.score.stream.common.LoadBalance;
import ai.timefold.solver.core.api.score.stream.uni.UniConstraintCollector;
import dev.sylvain.planning.domain.PosteAffectation;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * <p>The three predicates below cover the constraints that hold their seats
 * in hand — one seat, a pair, a grouped list. The constraints that only hold
 * an aggregate fold the question into the group with {@link #withAhead}: a
 * count of the seats still ahead composed next to their own collector —
 * incremental and O(1) per move, whatever the size of the group. Not an
 * {@code ifExists} on a seat of the group: hung on a group tuple, such a node
 * re-scans the seats of the group at every change of the group, do and undo,
 * whether the freeze is on or not — measured at a quarter of the move
 * evaluation speed on the real fixture when eleven groups carried one. The
 * one {@code ifExists} left, on {@code affectationForcee}, runs per ad hoc
 * fact, and there are almost never any.</p>
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

    /**
     * How many seats of a group are still ahead of now: composed next to the
     * group's own collector. A {@code long}, the only width Timefold's
     * {@code sum} collector offers.
     */
    static UniConstraintCollector<PosteAffectation, ?, Long> ahead() {
        return ConstraintCollectors.sum(PastSeats::aheadOne);
    }

    static long aheadOne(PosteAffectation poste) {
        return poste.isPasse() ? 0L : 1L;
    }

    /**
     * A group's own aggregate, and how many of its seats are still ahead of
     * now. Prints as the aggregate alone, so the sentence the diagnostic
     * builds from the justification reads exactly as it did before the fold.
     */
    record Ahead<T>(T value, long ahead) {

        @Override
        public String toString() {
            return String.valueOf(value);
        }
    }

    /** The group's collector, with the count of its seats still ahead folded next to it. */
    static <C, T> UniConstraintCollector<PosteAffectation, ?, Ahead<T>> withAhead(
            UniConstraintCollector<PosteAffectation, C, T> collector) {
        return ConstraintCollectors.compose(collector, ahead(), Ahead::new);
    }

    /** Same, on a stream of seats paired with a second fact. */
    static <B, C, T> BiConstraintCollector<PosteAffectation, B, ?, Ahead<T>> withAhead(
            BiConstraintCollector<PosteAffectation, B, C, T> collector) {
        return ConstraintCollectors.compose(
                collector, ConstraintCollectors.sum((poste, other) -> aheadOne(poste)), Ahead::new);
    }

    /** {@code unfairness()} scaled to an integer, see the callers' {@code UNFAIRNESS_SCALE}. */
    static int scaledUnfairness(LoadBalance<?> balance, BigDecimal scale) {
        return balance.unfairness()
                .multiply(scale)
                .setScale(0, RoundingMode.HALF_UP)
                .intValue();
    }
}
