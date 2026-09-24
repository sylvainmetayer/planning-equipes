package dev.sylvain.planning.solver;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.impl.score.director.ScoreDirector;
import ai.timefold.solver.core.preview.api.move.Move;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ConstraintToggle;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The shape of the chain, on the smallest plan that needs one: X holds both
 * Monday blocks and is free on Tuesday, whose two seats are empty; Y and Z
 * each hold one Monday block on another stand. The move gives X Tuesday and
 * hands X's Monday seats to Y and Z.
 */
class WeekRelocationMoveIteratorFactoryTest {

    private static final LocalDate LUNDI = LocalDate.of(2026, 7, 6);
    private static final LocalDate MARDI = LocalDate.of(2026, 7, 7);

    private final Animateur x = new Animateur("X", "Xavier", "Un", LocalDate.of(1990, 1, 1), false);
    private final Animateur y = new Animateur("Y", "Yann", "Deux", LocalDate.of(1990, 1, 1), false);
    private final Animateur z = new Animateur("Z", "Zoé", "Trois", LocalDate.of(1990, 1, 1), false);
    private final Stand montage = new Stand("M", "Montage", Set.of(), 1, 2, false);
    private final Stand autre = new Stand("N", "Autre", Set.of(), 1, 2, false);
    private final Creneau lundiMatin = new Creneau(1L, 1, LUNDI, LocalTime.of(9, 0), LocalTime.of(12, 0));
    private final Creneau lundiAprem = new Creneau(2L, 1, LUNDI, LocalTime.of(13, 0), LocalTime.of(16, 0));
    private final Creneau mardiMatin = new Creneau(3L, 2, MARDI, LocalTime.of(9, 0), LocalTime.of(12, 0));
    private final Creneau mardiAprem = new Creneau(4L, 2, MARDI, LocalTime.of(13, 0), LocalTime.of(16, 0));

    @Test
    void fillsTheHoleAndHandsTheCandidatesOtherDayToFreeColleagues() {
        PosteAffectation xMatin = seat("xm", montage, lundiMatin, x);
        PosteAffectation xAprem = seat("xa", montage, lundiAprem, x);
        PosteAffectation yMatin = seat("ym", autre, lundiMatin, y);
        PosteAffectation zAprem = seat("za", autre, lundiAprem, z);
        PosteAffectation trouMatin = seat("tm", montage, mardiMatin, null);
        PosteAffectation trouAprem = seat("ta", montage, mardiAprem, null);
        PlanningEvenement plan = new PlanningEvenement(
                LUNDI, List.of(x, y, z), List.of(xMatin, xAprem, yMatin, zAprem, trouMatin, trouAprem));

        Iterator<Move<PlanningEvenement>> moves =
                new WeekRelocationMoveIteratorFactory().createRandomMoveIterator(director(plan), new Random(7));

        List<Move<PlanningEvenement>> chains = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Move<PlanningEvenement> move = moves.next();
            if (move.getPlanningEntities().size() == 3) {
                chains.add(move);
            }
        }
        assertThat(chains).as("a chain over the hole and X's two Monday seats").isNotEmpty();
        Move<PlanningEvenement> chain = chains.get(0);
        assertThat(chain.getPlanningEntities()).contains(xMatin, xAprem);
        assertThat(chain.getPlanningEntities()).containsAnyOf(trouMatin, trouAprem);
        // X takes the hole; the Monday seats go to the colleague free at that hour: Z in the morning, Y in the
        // afternoon.
        assertThat(chain.getPlanningValues()).containsExactlyInAnyOrder(x, z, y);
    }

    @Test
    void degradesToAPlainChangeWhenThePlanHasNoHole() {
        PosteAffectation xMatin = seat("xm", montage, lundiMatin, x);
        PlanningEvenement plan = new PlanningEvenement(LUNDI, List.of(x, y), List.of(xMatin));

        Move<PlanningEvenement> move = new WeekRelocationMoveIteratorFactory()
                .createRandomMoveIterator(director(plan), new Random(1))
                .next();

        assertThat(move.getPlanningEntities()).containsExactly(xMatin);
    }

    // The run of days, when the edition holds it hard (ADR 0045): a Saturday and
    // Sunday of one ISO week, the Monday of the next, and a small cap.
    private static final LocalDate SATURDAY = LocalDate.of(2026, 7, 11);
    private static final LocalDate SUNDAY = LocalDate.of(2026, 7, 12);
    private static final LocalDate NEXT_MONDAY = LocalDate.of(2026, 7, 13);
    private final Creneau saturday = new Creneau(5L, 6, SATURDAY, LocalTime.of(9, 0), LocalTime.of(12, 0));
    private final Creneau saturdayAfternoon = new Creneau(8L, 6, SATURDAY, LocalTime.of(13, 0), LocalTime.of(16, 0));
    private final Creneau sunday = new Creneau(6L, 7, SUNDAY, LocalTime.of(9, 0), LocalTime.of(12, 0));
    private final Creneau nextMonday = new Creneau(7L, 8, NEXT_MONDAY, LocalTime.of(9, 0), LocalTime.of(12, 0));
    private final Creneau lundiSoir = new Creneau(9L, 1, LUNDI, LocalTime.of(18, 0), LocalTime.of(21, 0));

    @Test
    void releasesADayOfTheRunAcrossTheIsoWeekToAColleagueAlreadyThere() {
        PosteAffectation xSaturday = seat("xs", montage, saturday, x);
        PosteAffectation xSunday = seat("xd", montage, sunday, x);
        PosteAffectation ySaturday = seat("ys", autre, saturdayAfternoon, y);
        PosteAffectation yMonday = seat("yl", autre, nextMonday, y);
        PosteAffectation hole = seat("tl", montage, nextMonday, null);
        PlanningEvenement plan =
                new PlanningEvenement(SATURDAY, List.of(x, y), List.of(xSaturday, xSunday, ySaturday, yMonday, hole));
        holdRunsHard(plan, 2);

        List<Move<PlanningEvenement>> chains = movesTouching(plan, hole);

        // Only X is free for the Monday hole, and Monday would be X's third day in a
        // row: a weekend day has to go — in the other ISO week, which the chain of the
        // week never reached. Saturday, to Y, who works that afternoon; Sunday would
        // have to be a new day for somebody, which only moves the run.
        assertThat(chains).isNotEmpty().allSatisfy(chain -> {
            assertThat(chain.getPlanningEntities()).containsExactlyInAnyOrder(hole, xSaturday);
            assertThat(chain.getPlanningValues()).containsExactlyInAnyOrder(x, y);
        });
    }

    @Test
    void keepsThePlainFillWhenTheHardRunRuleIsOff() {
        PosteAffectation xSaturday = seat("xs", montage, saturday, x);
        PosteAffectation xSunday = seat("xd", montage, sunday, x);
        PosteAffectation ySaturday = seat("ys", autre, saturdayAfternoon, y);
        PosteAffectation yMonday = seat("yl", autre, nextMonday, y);
        PosteAffectation hole = seat("tl", montage, nextMonday, null);
        PlanningEvenement plan =
                new PlanningEvenement(SATURDAY, List.of(x, y), List.of(xSaturday, xSunday, ySaturday, yMonday, hole));

        assertThat(movesTouching(plan, hole)).isNotEmpty().allSatisfy(move -> {
            assertThat(move.getPlanningEntities()).containsExactly(hole);
            assertThat(move.getPlanningValues()).containsExactly(x);
        });
    }

    /**
     * A re-solve mid-event: X's weekend is past and pinned. It still makes Monday
     * X's third day in a row, as the rule counts it, and it can never be handed
     * over — so the chain goes to Z, whose run is clear, instead of a fill the
     * score refuses.
     */
    @Test
    void countsPinnedDaysInTheRunAndNeverHandsThemOver() {
        PosteAffectation xSaturday = seat("xs", montage, saturday, x);
        PosteAffectation xSunday = seat("xd", montage, sunday, x);
        xSaturday.setVerrouille(true);
        xSunday.setVerrouille(true);
        PosteAffectation hole = seat("tl", montage, nextMonday, null);
        PlanningEvenement plan = new PlanningEvenement(SATURDAY, List.of(x, z), List.of(xSaturday, xSunday, hole));
        holdRunsHard(plan, 2);

        assertThat(movesTouching(plan, hole)).isNotEmpty().allSatisfy(move -> {
            assertThat(move.getPlanningEntities()).containsExactly(hole);
            assertThat(move.getPlanningValues()).containsExactly(z);
        });
    }

    @Test
    void handsADayOfAnOverlongRunToAColleagueAlreadyThere() {
        PosteAffectation xSaturday = seat("xs", montage, saturday, x);
        PosteAffectation xSunday = seat("xd", montage, sunday, x);
        PosteAffectation xMonday = seat("xl", montage, nextMonday, x);
        PosteAffectation ySaturday = seat("ys", autre, saturdayAfternoon, y);
        PlanningEvenement plan =
                new PlanningEvenement(SATURDAY, List.of(x, y), List.of(xSaturday, xSunday, xMonday, ySaturday));
        holdRunsHard(plan, 2);

        // X's Saturday morning to Y, the one colleague already there that day.
        assertThat(draws(plan, 5, 20)).anySatisfy(move -> {
            assertThat(move.getPlanningEntities()).containsExactly(xSaturday);
            assertThat(move.getPlanningValues()).containsExactly(y);
        });
    }

    @Test
    void regroupsAHalfDayOntoTheColleagueHoldingTheOtherHalf() {
        PosteAffectation xMorning = seat("xm", montage, lundiMatin, x);
        PosteAffectation yAfternoon = seat("ya", montage, lundiAprem, y);
        PlanningEvenement plan = new PlanningEvenement(LUNDI, List.of(x, y), List.of(xMorning, yAfternoon));
        holdRunsHard(plan, 6);

        Move<PlanningEvenement> move = new WeekRelocationMoveIteratorFactory()
                .createRandomMoveIterator(director(plan), new Random(1))
                .next();

        // No hole, no long run: one of the two half-days goes to the other person,
        // and the Monday costs one person-day instead of two.
        assertThat(move.getPlanningEntities()).hasSize(1);
        assertThat(move.getPlanningValues())
                .containsExactly(move.getPlanningEntities().contains(xMorning) ? y : x);
    }

    @Test
    void regroupsTwoSeatsOfADayOntoTheOneColleagueAlreadyThere() {
        PosteAffectation xMorning = seat("xm", montage, lundiMatin, x);
        PosteAffectation xAfternoon = seat("xa", montage, lundiAprem, x);
        PosteAffectation yEvening = seat("ye", autre, lundiSoir, y);
        PlanningEvenement plan = new PlanningEvenement(LUNDI, List.of(x, y), List.of(xMorning, xAfternoon, yEvening));
        holdRunsHard(plan, 6);

        assertThat(draws(plan, 2, 20)).anySatisfy(move -> {
            assertThat(move.getPlanningEntities()).containsExactlyInAnyOrder(xMorning, xAfternoon);
            assertThat(move.getPlanningValues()).containsOnly(y);
        });
    }

    /** Only a day of the over-long run can shorten it: an isolated day elsewhere is not one. */
    @Test
    void theDaysOfAnOverlongRunAreTheOnlyOnesThatShortenIt() {
        List<LocalDate> worked = List.of(LUNDI, SATURDAY, SUNDAY, NEXT_MONDAY, NEXT_MONDAY.plusDays(1));

        assertThat(WeekRelocationMoveIteratorFactory.Index.daysOfRunsLongerThan(worked, 2))
                .containsExactly(SATURDAY, SUNDAY, NEXT_MONDAY, NEXT_MONDAY.plusDays(1));
        assertThat(WeekRelocationMoveIteratorFactory.Index.longestRun(worked, LUNDI, null))
                .isEqualTo(4);
        assertThat(WeekRelocationMoveIteratorFactory.Index.longestRun(worked, SUNDAY, null))
                .isEqualTo(2);
        assertThat(WeekRelocationMoveIteratorFactory.Index.longestRun(worked, null, SATURDAY.minusDays(1)))
                .isEqualTo(5);
    }

    /** The moves of twenty draws that touch {@code seat}. */
    private List<Move<PlanningEvenement>> movesTouching(PlanningEvenement plan, PosteAffectation seat) {
        return draws(plan, 3, 20).stream()
                .filter(move -> move.getPlanningEntities().contains(seat))
                .toList();
    }

    private List<Move<PlanningEvenement>> draws(PlanningEvenement plan, long seed, int count) {
        Iterator<Move<PlanningEvenement>> iterator =
                new WeekRelocationMoveIteratorFactory().createRandomMoveIterator(director(plan), new Random(seed));
        List<Move<PlanningEvenement>> moves = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            moves.add(iterator.next());
        }
        return moves;
    }

    private static void holdRunsHard(PlanningEvenement plan, int joursConsecutifsMax) {
        ParametresQualite defaults = new ParametresQualite();
        plan.setParametresQualite(List.of(new ParametresQualite(
                defaults.maxEmplacementsDistinctsParJour(),
                defaults.heureServiceTardif(),
                defaults.heureServiceMatinal(),
                defaults.reposSouhaiteApresServiceTardifMinutes(),
                defaults.typologiesDistinctesMax(),
                joursConsecutifsMax)));
        plan.setConstraintsDesactivees(List.of(new ConstraintToggle("maxJoursConsecutifsTravaillesDur", true)));
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
