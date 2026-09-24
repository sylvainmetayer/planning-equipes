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
    // Sunday of one ISO week, the Monday of the next, and a cap of two days.
    private static final LocalDate SAMEDI = LocalDate.of(2026, 7, 11);
    private static final LocalDate DIMANCHE = LocalDate.of(2026, 7, 12);
    private static final LocalDate LUNDI_SUIVANT = LocalDate.of(2026, 7, 13);
    private final Creneau samedi = new Creneau(5L, 6, SAMEDI, LocalTime.of(9, 0), LocalTime.of(12, 0));
    private final Creneau samediAprem = new Creneau(8L, 6, SAMEDI, LocalTime.of(13, 0), LocalTime.of(16, 0));
    private final Creneau dimanche = new Creneau(6L, 7, DIMANCHE, LocalTime.of(9, 0), LocalTime.of(12, 0));
    private final Creneau lundiSuivant = new Creneau(7L, 8, LUNDI_SUIVANT, LocalTime.of(9, 0), LocalTime.of(12, 0));

    @Test
    void releasesADayOfTheRunAcrossTheIsoWeekToAColleagueAlreadyThere() {
        PosteAffectation xSamedi = seat("xs", montage, samedi, x);
        PosteAffectation xDimanche = seat("xd", montage, dimanche, x);
        PosteAffectation ySamedi = seat("ys", autre, samediAprem, y);
        PosteAffectation yLundi = seat("yl", autre, lundiSuivant, y);
        PosteAffectation trou = seat("tl", montage, lundiSuivant, null);
        PlanningEvenement plan =
                new PlanningEvenement(SAMEDI, List.of(x, y), List.of(xSamedi, xDimanche, ySamedi, yLundi, trou));
        holdRunsHard(plan, 2);

        List<Move<PlanningEvenement>> chains = movesTouching(plan, trou);

        // Only X is free for the Monday hole, and Monday would be X's third day in a
        // row: a weekend day has to go — in the other ISO week, which the chain of the
        // week never reached. Saturday, to Y, who works that afternoon; Sunday would
        // have to be a new day for somebody, which only moves the run.
        assertThat(chains).isNotEmpty().allSatisfy(chain -> {
            assertThat(chain.getPlanningEntities()).containsExactlyInAnyOrder(trou, xSamedi);
            assertThat(chain.getPlanningValues()).containsExactlyInAnyOrder(x, y);
        });
    }

    @Test
    void keepsThePlainFillWhenTheHardRunRuleIsOff() {
        PosteAffectation xSamedi = seat("xs", montage, samedi, x);
        PosteAffectation xDimanche = seat("xd", montage, dimanche, x);
        PosteAffectation ySamedi = seat("ys", autre, samediAprem, y);
        PosteAffectation yLundi = seat("yl", autre, lundiSuivant, y);
        PosteAffectation trou = seat("tl", montage, lundiSuivant, null);
        PlanningEvenement plan =
                new PlanningEvenement(SAMEDI, List.of(x, y), List.of(xSamedi, xDimanche, ySamedi, yLundi, trou));

        assertThat(movesTouching(plan, trou)).isNotEmpty().allSatisfy(move -> {
            assertThat(move.getPlanningEntities()).containsExactly(trou);
            assertThat(move.getPlanningValues()).containsExactly(x);
        });
    }

    @Test
    void handsADayOfAnOverlongRunToAColleagueAlreadyThere() {
        PosteAffectation xSamedi = seat("xs", montage, samedi, x);
        PosteAffectation xDimanche = seat("xd", montage, dimanche, x);
        PosteAffectation xLundi = seat("xl", montage, lundiSuivant, x);
        PosteAffectation ySamedi = seat("ys", autre, samediAprem, y);
        PlanningEvenement plan =
                new PlanningEvenement(SAMEDI, List.of(x, y), List.of(xSamedi, xDimanche, xLundi, ySamedi));
        holdRunsHard(plan, 2);

        List<Move<PlanningEvenement>> moves = new ArrayList<>();
        Iterator<Move<PlanningEvenement>> iterator =
                new WeekRelocationMoveIteratorFactory().createRandomMoveIterator(director(plan), new Random(5));
        for (int i = 0; i < 20; i++) {
            moves.add(iterator.next());
        }

        // X's Saturday morning to Y, the one colleague already there that day.
        assertThat(moves).anySatisfy(move -> {
            assertThat(move.getPlanningEntities()).containsExactly(xSamedi);
            assertThat(move.getPlanningValues()).containsExactly(y);
        });
    }

    @Test
    void regroupsAHalfDayOntoTheColleagueHoldingTheOtherHalf() {
        PosteAffectation xMatin = seat("xm", montage, lundiMatin, x);
        PosteAffectation yAprem = seat("ya", montage, lundiAprem, y);
        PlanningEvenement plan = new PlanningEvenement(LUNDI, List.of(x, y), List.of(xMatin, yAprem));
        holdRunsHard(plan, 6);

        Move<PlanningEvenement> move = new WeekRelocationMoveIteratorFactory()
                .createRandomMoveIterator(director(plan), new Random(1))
                .next();

        // No hole, no long run: one of the two half-days goes to the other person,
        // and the Monday costs one person-day instead of two.
        assertThat(move.getPlanningEntities()).hasSize(1);
        assertThat(move.getPlanningValues())
                .containsExactly(move.getPlanningEntities().contains(xMatin) ? y : x);
    }

    /** The moves of twenty draws that touch {@code seat}. */
    private List<Move<PlanningEvenement>> movesTouching(PlanningEvenement plan, PosteAffectation seat) {
        Iterator<Move<PlanningEvenement>> iterator =
                new WeekRelocationMoveIteratorFactory().createRandomMoveIterator(director(plan), new Random(3));
        List<Move<PlanningEvenement>> touching = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Move<PlanningEvenement> move = iterator.next();
            if (move.getPlanningEntities().contains(seat)) {
                touching.add(move);
            }
        }
        return touching;
    }

    private static void holdRunsHard(PlanningEvenement plan, int joursConsecutifsMax) {
        ParametresQualite defaut = new ParametresQualite();
        plan.setParametresQualite(List.of(new ParametresQualite(
                defaut.maxEmplacementsDistinctsParJour(),
                defaut.heureServiceTardif(),
                defaut.heureServiceMatinal(),
                defaut.reposSouhaiteApresServiceTardifMinutes(),
                defaut.typologiesDistinctesMax(),
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
