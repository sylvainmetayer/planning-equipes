package dev.sylvain.planning.solver;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.impl.score.director.ScoreDirector;
import ai.timefold.solver.core.preview.api.move.Move;
import ai.timefold.solver.core.preview.api.move.MutableSolutionView;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The day swap on plans small enough to enumerate: who may trade a day with
 * whom, and what each seat ends up holding.
 */
class DaySwapMoveIteratorFactoryTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 7, 6);
    private static final LocalDate TUESDAY = LocalDate.of(2026, 7, 7);

    private final Animateur x = new Animateur("X", "Xavier", "Un", LocalDate.of(1990, 1, 1), false);
    private final Animateur y = new Animateur("Y", "Yann", "Deux", LocalDate.of(1990, 1, 1), false);
    /** Fifteen on the event's dates: a minor. */
    private final Animateur minor = new Animateur("M", "Maël", "Trois", LocalDate.of(2011, 1, 1), false);

    private final Stand montage = new Stand("S1", "Montage", Set.of(), 1, 2, false);
    private final Stand other = new Stand("S2", "Autre", Set.of(), 1, 2, false);
    private final Stand adultsOnly = new Stand("S3", "Majeurs", Set.of(), 1, 2, true);

    private final Creneau mondayMorning = new Creneau(1L, 1, MONDAY, LocalTime.of(9, 0), LocalTime.of(12, 0));
    private final Creneau mondayAfternoon = new Creneau(2L, 1, MONDAY, LocalTime.of(13, 0), LocalTime.of(16, 0));
    private final Creneau tuesdayMorning = new Creneau(3L, 2, TUESDAY, LocalTime.of(9, 0), LocalTime.of(12, 0));

    @Test
    void swapsTheWholeDayOfBothAnimateursInOneMove() {
        PosteAffectation xMorning = seat("xm", montage, mondayMorning, x);
        PosteAffectation xAfternoon = seat("xa", montage, mondayAfternoon, x);
        PosteAffectation yMorning = seat("ym", other, mondayMorning, y);
        PlanningEvenement plan = new PlanningEvenement(MONDAY, List.of(x, y), List.of(xMorning, xAfternoon, yMorning));

        List<Move<PlanningEvenement>> moves = draws(plan, 10);

        // X's two seats go to Y and Y's seat to X, whichever of the two blocks was drawn.
        assertThat(moves)
                .isNotEmpty()
                .allSatisfy(move -> assertThat(assignments(move))
                        .containsExactlyInAnyOrderEntriesOf(Map.of(xMorning, y, xAfternoon, y, yMorning, x)));
    }

    @Test
    void handsEachDayToTheOtherWhicheverBlockIsDrawn() {
        PosteAffectation xMorning = seat("xm", montage, mondayMorning, x);
        PosteAffectation yMorning = seat("ym", other, mondayMorning, y);
        PlanningEvenement plan = new PlanningEvenement(MONDAY, List.of(x, y), List.of(xMorning, yMorning));

        // Forty seeds draw X's block and Y's alike; both directions of the
        // exchange are always in the same move, never one without the other.
        assertThat(draws(plan, 40))
                .hasSize(40)
                .allSatisfy(move -> assertThat(assignments(move))
                        .containsExactlyInAnyOrderEntriesOf(Map.of(xMorning, y, yMorning, x)));
    }

    @Test
    void neverPairsAnimateursWorkingDifferentDates() {
        PosteAffectation xMonday = seat("xm", montage, mondayMorning, x);
        PosteAffectation yTuesday = seat("yt", other, tuesdayMorning, y);
        PlanningEvenement plan = new PlanningEvenement(MONDAY, List.of(x, y), List.of(xMonday, yTuesday));

        assertThat(iterator(plan, 3).hasNext()).isFalse();
    }

    @Test
    void neverHandsASeatToAnIneligibleAnimateur() {
        PosteAffectation xAdultsOnly = seat("xr", adultsOnly, mondayMorning, x);
        PosteAffectation minorSeat = seat("mm", other, mondayAfternoon, minor);
        PosteAffectation yMorning = seat("ym", other, mondayMorning, y);
        PlanningEvenement plan =
                new PlanningEvenement(MONDAY, List.of(x, y, minor), List.of(xAdultsOnly, minorSeat, yMorning));

        List<Move<PlanningEvenement>> moves = draws(plan, 60);

        // The minor may take Y's seat but never X's, on a stand reserved for adults:
        // X and the minor never trade, X and Y do, Y and the minor do.
        assertThat(moves)
                .isNotEmpty()
                .noneSatisfy(move -> assertThat(move.getPlanningEntities()).contains(xAdultsOnly, minorSeat));
        assertThat(moves)
                .anySatisfy(move -> assertThat(assignments(move))
                        .containsExactlyInAnyOrderEntriesOf(Map.of(minorSeat, y, yMorning, minor)))
                .anySatisfy(move -> assertThat(assignments(move))
                        .containsExactlyInAnyOrderEntriesOf(Map.of(xAdultsOnly, y, yMorning, x)));
    }

    @Test
    void leavesPinnedSeatsWhereTheyAre() {
        PosteAffectation xMorning = seat("xm", montage, mondayMorning, x);
        PosteAffectation xAfternoon = seat("xa", montage, mondayAfternoon, x);
        xAfternoon.setVerrouille(true);
        PosteAffectation yMorning = seat("ym", other, mondayMorning, y);
        PlanningEvenement plan = new PlanningEvenement(MONDAY, List.of(x, y), List.of(xMorning, xAfternoon, yMorning));

        assertThat(draws(plan, 10))
                .isNotEmpty()
                .allSatisfy(move -> assertThat(move.getPlanningEntities()).doesNotContain(xAfternoon));
    }

    /** Each seat the move touches, with the animateur it hands it to — read by playing the move on a recorder. */
    @SuppressWarnings("unchecked")
    private static Map<Object, Object> assignments(Move<PlanningEvenement> move) {
        Map<Object, Object> assignments = new HashMap<>();
        MutableSolutionView<PlanningEvenement> recorder =
                (MutableSolutionView<PlanningEvenement>) Proxy.newProxyInstance(
                        MutableSolutionView.class.getClassLoader(),
                        new Class<?>[] {MutableSolutionView.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("changeVariable")) {
                                assertThat(assignments.put(args[1], args[2]))
                                        .as("each seat changes once")
                                        .isNull();
                                return null;
                            }
                            throw new UnsupportedOperationException(method.getName());
                        });
        move.execute(recorder);
        return assignments;
    }

    private static List<Move<PlanningEvenement>> draws(PlanningEvenement plan, int count) {
        List<Move<PlanningEvenement>> moves = new ArrayList<>();
        for (int seed = 0; seed < count; seed++) {
            Iterator<Move<PlanningEvenement>> iterator = iterator(plan, seed);
            if (iterator.hasNext()) {
                moves.add(iterator.next());
            }
        }
        return moves;
    }

    private static Iterator<Move<PlanningEvenement>> iterator(PlanningEvenement plan, long seed) {
        return new DaySwapMoveIteratorFactory().createRandomMoveIterator(director(plan), new Random(seed));
    }

    private static PosteAffectation seat(String id, Stand stand, Creneau creneau, Animateur animateur) {
        PosteAffectation poste = new PosteAffectation(id, stand, creneau);
        poste.setAnimateur(animateur);
        return poste;
    }

    /** The only call the factory makes on the director: the working solution. */
    @SuppressWarnings("unchecked")
    private static ScoreDirector<PlanningEvenement> director(PlanningEvenement solution) {
        return (ScoreDirector<PlanningEvenement>) Proxy.newProxyInstance(
                ScoreDirector.class.getClassLoader(), new Class<?>[] {ScoreDirector.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getWorkingSolution")) {
                        return solution;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
