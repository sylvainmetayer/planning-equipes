package dev.sylvain.planning.solver;

import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import dev.sylvain.planning.solver.constraints.AdHocConstraints;
import dev.sylvain.planning.solver.constraints.AffectationConstraints;
import dev.sylvain.planning.solver.constraints.LegalConstraints;
import dev.sylvain.planning.solver.constraints.PreferenceConstraints;
import dev.sylvain.planning.solver.constraints.QualiteConstraints;
import dev.sylvain.planning.solver.constraints.RepasConstraints;
import dev.sylvain.planning.solver.constraints.SeatStreams;
import dev.sylvain.planning.solver.constraints.VerrouillageConstraints;
import java.util.stream.Stream;

/**
 * Aggregates every constraint definition. The actual rules live in dedicated
 * classes under {@code solver.constraints}, grouped by relevance:
 * <ul>
 *   <li>{@link AffectationConstraints} — core assignment (hard)</li>
 *   <li>{@link LegalConstraints} — legal protection of minors (hard)</li>
 *   <li>{@link AdHocConstraints} — administrative exceptions (hard)</li>
 *   <li>{@link VerrouillageConstraints} — partial planning locks (hard)</li>
 *   <li>{@link RepasConstraints} — the meal break (hard + soft)</li>
 *   <li>{@link QualiteConstraints} — organisational quality (medium)</li>
 *   <li>{@link PreferenceConstraints} — soft preferences (soft)</li>
 * </ul>
 */
public class PlanningConstraintProvider implements ConstraintProvider {

    @Override
    public Constraint[] defineConstraints(ConstraintFactory constraintFactory) {
        // One set of seat streams for every family: the engine then keeps one
        // node per grouping of the seats, whatever the number of rules reading
        // it (see SeatStreams).
        SeatStreams seats = new SeatStreams(constraintFactory);
        return Stream.of(
                        new AffectationConstraints().define(constraintFactory, seats),
                        new LegalConstraints().define(constraintFactory, seats),
                        new AdHocConstraints().define(constraintFactory, seats),
                        new VerrouillageConstraints().define(constraintFactory),
                        new RepasConstraints().define(constraintFactory, seats),
                        new QualiteConstraints().define(constraintFactory, seats),
                        new PreferenceConstraints().define(constraintFactory, seats))
                .flatMap(Stream::of)
                .toArray(Constraint[]::new);
    }
}
