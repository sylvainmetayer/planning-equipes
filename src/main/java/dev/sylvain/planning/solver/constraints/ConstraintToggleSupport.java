package dev.sylvain.planning.solver.constraints;

import ai.timefold.solver.core.api.score.stream.Joiners;
import ai.timefold.solver.core.api.score.stream.bi.BiConstraintStream;
import ai.timefold.solver.core.api.score.stream.quad.QuadConstraintStream;
import ai.timefold.solver.core.api.score.stream.tri.TriConstraintStream;
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
 *
 * <p>Laid at the <b>tail</b> of a rule's stream — on the matches, right before
 * {@code penalize} — never at its head: at the head it was one more node on
 * every seat for every rule, and it kept the heads apart, so nothing upstream
 * of it could be shared between rules (see {@link SeatStreams}). Gating the
 * matches instead of the seats costs one probe per match, and a rule's
 * matches are rare where its seats are thousands. Starving the matches or the
 * seats, the rule scores nothing either way.</p>
 */
final class ConstraintToggleSupport {

    private ConstraintToggleSupport() {}

    // An indexed equal() joiner rather than filtering(): the toggle lookup then
    // costs one hash probe per tuple instead of one predicate evaluation per
    // (tuple, toggle) combination. The state is checked by a filtering joiner
    // on top, which only ever sees the toggles of that one name.
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

    static <A, B, C> TriConstraintStream<A, B, C> actif(TriConstraintStream<A, B, C> stream, String constraintName) {
        if (ConstraintCatalog.activeByDefault(constraintName)) {
            return stream.ifNotExists(
                    ConstraintToggle.class,
                    Joiners.equal((a, b, c) -> constraintName, ConstraintToggle::getNom),
                    Joiners.filtering((a, b, c, toggle) -> !toggle.isActif()));
        }
        return stream.ifExists(
                ConstraintToggle.class,
                Joiners.equal((a, b, c) -> constraintName, ConstraintToggle::getNom),
                Joiners.filtering((a, b, c, toggle) -> toggle.isActif()));
    }

    static <A, B, C, D> QuadConstraintStream<A, B, C, D> actif(
            QuadConstraintStream<A, B, C, D> stream, String constraintName) {
        if (ConstraintCatalog.activeByDefault(constraintName)) {
            return stream.ifNotExists(
                    ConstraintToggle.class,
                    Joiners.equal((a, b, c, d) -> constraintName, ConstraintToggle::getNom),
                    Joiners.filtering((a, b, c, d, toggle) -> !toggle.isActif()));
        }
        return stream.ifExists(
                ConstraintToggle.class,
                Joiners.equal((a, b, c, d) -> constraintName, ConstraintToggle::getNom),
                Joiners.filtering((a, b, c, d, toggle) -> toggle.isActif()));
    }
}
