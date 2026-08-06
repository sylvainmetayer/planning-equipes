package dev.sylvain.planning.solver.constraints;

import ai.timefold.solver.core.api.score.stream.Joiners;
import ai.timefold.solver.core.api.score.stream.bi.BiConstraintStream;
import ai.timefold.solver.core.api.score.stream.uni.UniConstraintStream;
import dev.sylvain.planning.domain.ConstraintToggle;

/**
 * Gates a constraint stream on the {@link ConstraintToggle} problem fact: if
 * a toggle exists for {@code constraintName}, the stream is starved (no
 * matches), disabling the constraint for the current solve without touching
 * its scoring logic. Absence of a toggle — the default — leaves the stream
 * untouched.
 */
final class ConstraintToggleSupport {

    private ConstraintToggleSupport() {
    }

    // An indexed equal() joiner rather than filtering(): the toggle lookup then
    // costs one hash probe per tuple instead of one predicate evaluation per
    // (tuple, toggle) combination, on streams that carry thousands of tuples.
    static <A> UniConstraintStream<A> actif(UniConstraintStream<A> stream, String constraintName) {
        return stream.ifNotExists(ConstraintToggle.class,
                Joiners.equal(a -> constraintName, ConstraintToggle::getNom));
    }

    static <A, B> BiConstraintStream<A, B> actif(BiConstraintStream<A, B> stream, String constraintName) {
        return stream.ifNotExists(ConstraintToggle.class,
                Joiners.equal((a, b) -> constraintName, ConstraintToggle::getNom));
    }
}
