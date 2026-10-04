package dev.sylvain.planning.solver;

import static dev.sylvain.planning.solver.MoveFactoryTestSupport.director;
import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.preview.api.move.Move;
import ai.timefold.solver.core.preview.api.move.MutableSolutionView;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ConstraintToggle;
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
    private final Creneau mondayLongAfternoon = new Creneau(4L, 1, MONDAY, LocalTime.of(13, 0), LocalTime.of(18, 0));
    private final Creneau mondayMidday = new Creneau(5L, 1, MONDAY, LocalTime.of(11, 0), LocalTime.of(14, 0));

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

    @Test
    void neverHandsASeatOverlappingAPinnedSeatItsReceiverKeeps() {
        // X keeps a locked 09:00-12:00 seat and can give away 13:00-16:00; Y
        // holds 11:00-14:00. Y's seat would land on top of X's lock.
        PosteAffectation xLocked = seat("xl", montage, mondayMorning, x);
        xLocked.setVerrouille(true);
        PosteAffectation xAfternoon = seat("xa", montage, mondayAfternoon, x);
        PosteAffectation yMidday = seat("ym", other, mondayMidday, y);
        PlanningEvenement plan = new PlanningEvenement(MONDAY, List.of(x, y), List.of(xLocked, xAfternoon, yMidday));

        assertThat(iterator(plan, 1).hasNext()).isFalse();
    }

    @Test
    void neverPutsAMinorsDayOverTheDailyCap() {
        // X's 09:00-12:00 and 13:00-18:00 are each fine for a fifteen-year-old,
        // but together they make 7 h 30 of work, over the 7 h cap.
        PosteAffectation xMorning = seat("xm", montage, mondayMorning, x);
        PosteAffectation xLong = seat("xl", montage, mondayLongAfternoon, x);
        PosteAffectation minorMorning = seat("mm", other, mondayMorning, minor);
        PlanningEvenement plan =
                new PlanningEvenement(MONDAY, List.of(x, minor), List.of(xMorning, xLong, minorMorning));

        assertThat(iterator(plan, 1).hasNext()).isFalse();

        // Measured only while the rule bites: switched off, the swap is offered.
        plan.setConstraintsDesactivees(List.of(new ConstraintToggle("dureeQuotidienneMaxMineur")));
        assertThat(draws(plan, 10))
                .isNotEmpty()
                .allSatisfy(move -> assertThat(assignments(move))
                        .containsExactlyInAnyOrderEntriesOf(Map.of(xMorning, minor, xLong, minor, minorMorning, x)));
    }

    @Test
    void stillSwapsTwoDaysOfTheSameSeats() {
        // Neutral for the score, and kept on purpose: turning them down was
        // measured, and cost medium (see the class javadoc).
        PosteAffectation xMorning = seat("xm", montage, mondayMorning, x);
        PosteAffectation yMorning = seat("ym", montage, mondayMorning, y);
        PlanningEvenement plan = new PlanningEvenement(MONDAY, List.of(x, y), List.of(xMorning, yMorning));

        assertThat(draws(plan, 5))
                .hasSize(5)
                .allSatisfy(move -> assertThat(assignments(move))
                        .containsExactlyInAnyOrderEntriesOf(Map.of(xMorning, y, yMorning, x)));
    }

    @Test
    void picksThePartnerUniformlyAmongThoseWhoCanTrade() {
        // X's seat is reserved for adults. Ten minors come first in the plan and
        // cannot take it; the two adults after them can, and must be picked
        // about equally often rather than the first one after the minors.
        List<Animateur> animateurs = new ArrayList<>(List.of(x));
        List<PosteAffectation> seats = new ArrayList<>(List.of(seat("xr", adultsOnly, mondayMorning, x)));
        for (int i = 0; i < 10; i++) {
            Animateur young = new Animateur("M" + i, "Mineur", "N" + i, LocalDate.of(2011, 1, 1), false);
            animateurs.add(young);
            seats.add(seat("m" + i, other, mondayMorning, young));
        }
        Animateur first = new Animateur("A1", "Adulte", "Un", LocalDate.of(1990, 1, 1), false);
        Animateur second = new Animateur("A2", "Adulte", "Deux", LocalDate.of(1990, 1, 1), false);
        animateurs.addAll(List.of(first, second));
        seats.add(seat("a1", other, mondayMorning, first));
        seats.add(seat("a2", other, mondayMorning, second));
        PlanningEvenement plan = new PlanningEvenement(MONDAY, animateurs, seats);
        PosteAffectation xSeat = seats.getFirst();

        Map<Object, Integer> partners = new HashMap<>();
        for (Move<PlanningEvenement> move : draws(plan, 2_000)) {
            Object partner = assignments(move).get(xSeat);
            if (partner != null) {
                partners.merge(partner, 1, Integer::sum);
            }
        }

        assertThat(partners).containsOnlyKeys(first, second);
        int a = partners.get(first);
        int b = partners.get(second);
        assertThat(Math.abs(a - b)).as("%s vs %s", a, b).isLessThan((a + b) / 5);
    }

    @Test
    void drawsEveryBlockEquallyOftenWhateverItsSize() {
        // X's block has three seats, Y's and Z's one each, and anybody can
        // trade with anybody. Blocks drawn equally often, each first draw
        // pairing with either of the other two, Z's seat goes to X as often as
        // to Y; drawn in proportion to their seats, it would go to X twice as
        // often.
        Creneau evening = new Creneau(6L, 1, MONDAY, LocalTime.of(17, 0), LocalTime.of(19, 0));
        Animateur z = new Animateur("Z", "Zoé", "Quatre", LocalDate.of(1990, 1, 1), false);
        PosteAffectation zSeat = seat("z", montage, mondayMorning, z);
        PlanningEvenement plan = new PlanningEvenement(
                MONDAY,
                List.of(x, y, z),
                List.of(
                        seat("x1", other, mondayMorning, x),
                        seat("x2", other, mondayAfternoon, x),
                        seat("x3", other, evening, x),
                        seat("y1", adultsOnly, mondayAfternoon, y),
                        zSeat));

        Map<Object, Integer> takers = new HashMap<>();
        for (Move<PlanningEvenement> move : draws(plan, 3_000)) {
            Object taker = assignments(move).get(zSeat);
            if (taker != null) {
                takers.merge(taker, 1, Integer::sum);
            }
        }

        int toX = takers.getOrDefault(x, 0);
        int toY = takers.getOrDefault(y, 0);
        assertThat(toX).isPositive();
        assertThat(Math.abs(toX - toY)).as("%s vs %s", toX, toY).isLessThan((toX + toY) / 5);
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
}
