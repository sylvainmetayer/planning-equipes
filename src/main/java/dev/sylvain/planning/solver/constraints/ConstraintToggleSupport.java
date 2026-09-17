package dev.sylvain.planning.solver.constraints;

import ai.timefold.solver.core.api.score.stream.Joiners;
import ai.timefold.solver.core.api.score.stream.bi.BiConstraintStream;
import ai.timefold.solver.core.api.score.stream.uni.UniConstraintStream;
import dev.sylvain.planning.domain.ConstraintToggle;
import dev.sylvain.planning.solver.ConstraintCatalog;

/**
 * Gates a constraint stream on the {@link ConstraintToggle} problem fact.
 *
 * <p>A toggle carries the <b>explicit</b> state of one constraint; its absence
 * means the catalogue's default ({@code ConstraintCatalog.activeByDefault}).
 * For the rules active by default — all but the ones the catalogue ships off —
 * a toggle saying {@code actif = false} starves the stream, disabling the
 * constraint for this solve without touching its scoring logic. For a rule the
 * catalogue ships <b>off</b>, the test is reversed: the stream is starved
 * unless a toggle says {@code actif = true}.</p>
 *
 * <p>Reading the default here rather than only in the service layer is what
 * makes it hold everywhere: a plain-Java harness that hands the solver no
 * toggle at all gets exactly what an untouched edition gets.</p>
 */
final class ConstraintToggleSupport {

    private ConstraintToggleSupport() {}

    // An indexed equal() joiner rather than filtering(): the toggle lookup then
    // costs one hash probe per tuple instead of one predicate evaluation per
    // (tuple, toggle) combination, on streams that carry thousands of tuples.
    // The state is checked by a filtering joiner on top, which only ever sees
    // the toggles of that one name.
    static <A> UniConstraintStream<A> actif(UniConstraintStream<A> stream, String constraintName) {
        if (ConstraintCatalog.activeByDefault(constraintName)) {
            return stream.ifNotExists(
                    ConstraintToggle.class,
                    Joiners.equal(a -> constraintName, ConstraintToggle::getNom),
                    Joiners.filtering((a, toggle) -> !toggle.isActif()));
        }
        return stream.ifExists(
                ConstraintToggle.class,
                Joiners.equal(a -> constraintName, ConstraintToggle::getNom),
                Joiners.filtering((a, toggle) -> toggle.isActif()));
    }

    static <A, B> BiConstraintStream<A, B> actif(BiConstraintStream<A, B> stream, String constraintName) {
        if (ConstraintCatalog.activeByDefault(constraintName)) {
            return stream.ifNotExists(
                    ConstraintToggle.class,
                    Joiners.equal((a, b) -> constraintName, ConstraintToggle::getNom),
                    Joiners.filtering((a, b, toggle) -> !toggle.isActif()));
        }
        return stream.ifExists(
                ConstraintToggle.class,
                Joiners.equal((a, b) -> constraintName, ConstraintToggle::getNom),
                Joiners.filtering((a, b, toggle) -> toggle.isActif()));
    }
}
