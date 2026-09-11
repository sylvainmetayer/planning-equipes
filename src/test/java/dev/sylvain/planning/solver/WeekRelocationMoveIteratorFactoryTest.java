package dev.sylvain.planning.solver;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.impl.score.director.ScoreDirector;
import ai.timefold.solver.core.preview.api.move.Move;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
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
