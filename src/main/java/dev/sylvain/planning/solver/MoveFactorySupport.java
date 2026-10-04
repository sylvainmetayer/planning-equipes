package dev.sylvain.planning.solver;

import ai.timefold.solver.core.preview.api.domain.metamodel.PlanningSolutionMetaModel;
import ai.timefold.solver.core.preview.api.domain.metamodel.PlanningVariableMetaModel;
import ai.timefold.solver.core.preview.api.move.Move;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import java.time.LocalTime;
import java.util.Iterator;
import java.util.List;

/**
 * The plumbing {@link WeekRelocationMoveIteratorFactory} and
 * {@link DaySwapMoveIteratorFactory} share: the variable their moves change,
 * the size they report, the bounded original-order iterator, and the overlap
 * test of two seats of one day. Public preview API only — nothing here
 * imports {@code core.impl}.
 */
final class MoveFactorySupport {

    /** How many moves an original-order iterator yields: bounded, since both neighbourhoods are combinatorial. */
    static final int ORIGINAL_MOVES = 1_000;

    static final PlanningVariableMetaModel<PlanningEvenement, PosteAffectation, Animateur> ANIMATEUR =
            PlanningSolutionMetaModel.of(PlanningEvenement.class, PosteAffectation.class)
                    .genuineEntity(PosteAffectation.class)
                    .basicVariable("animateur", Animateur.class);

    private MoveFactorySupport() {}

    /**
     * An order of magnitude, not the neighbourhood's exact size — every seat
     * times every animateur — and never 0, so an edition without animateurs
     * does not read as a selector with nothing to offer.
     */
    static long sizeBound(PlanningEvenement solution) {
        return (long) solution.getPostes().size()
                * Math.max(1, solution.getAnimateurs().size());
    }

    /** The first {@link #ORIGINAL_MOVES} moves of {@code random}, fewer if it runs dry. */
    static Iterator<Move<PlanningEvenement>> bounded(Iterator<Move<PlanningEvenement>> random) {
        return new Iterator<>() {
            private int yielded;

            @Override
            public boolean hasNext() {
                return yielded < ORIGINAL_MOVES && random.hasNext();
            }

            @Override
            public Move<PlanningEvenement> next() {
                yielded++;
                return random.next();
            }
        };
    }

    /** Whether {@code seat} overlaps in time any of {@code others}, all seats of one day. */
    static boolean overlapsAny(PosteAffectation seat, List<PosteAffectation> others) {
        int debut = minutes(seat.heureDebutEffectif());
        int fin = debut + seat.getDureeEffectiveMinutes();
        for (PosteAffectation other : others) {
            int otherDebut = minutes(other.heureDebutEffectif());
            int otherFin = otherDebut + other.getDureeEffectiveMinutes();
            if (debut < otherFin && otherDebut < fin) {
                return true;
            }
        }
        return false;
    }

    private static int minutes(LocalTime time) {
        return time == null ? 0 : time.toSecondOfDay() / 60;
    }
}
