package dev.sylvain.planning.solver;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ai.timefold.solver.core.impl.score.director.ScoreDirector;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;

/** The neighbourhood a hole is filled from: the créneaux that share its hour, midnight included. */
class HoleNeighbourPosteFilterTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 9, 8);

    @Test
    void twoSlotsOfTheSameDaySharingMinutesOverlap() {
        assertThat(HoleNeighbourPosteFilter.overlap(slot(JOUR, 13, 45, 16, 45), slot(JOUR, 14, 15, 20, 0))).isTrue();
        assertThat(HoleNeighbourPosteFilter.overlap(slot(JOUR, 10, 0, 12, 0), slot(JOUR, 10, 0, 12, 15))).isTrue();
    }

    @Test
    void touchingOrDistinctSlotsDoNot() {
        // 12:00 ends where the other starts: nobody is on both.
        assertThat(HoleNeighbourPosteFilter.overlap(slot(JOUR, 10, 0, 12, 0), slot(JOUR, 12, 0, 13, 0))).isFalse();
        assertThat(HoleNeighbourPosteFilter.overlap(slot(JOUR, 9, 0, 12, 0), slot(JOUR.plusDays(1), 9, 0, 12, 0))).isFalse();
    }

    @Test
    void aSlotCrossingMidnightStillOverlapsTheEvening() {
        assertThat(HoleNeighbourPosteFilter.overlap(slot(JOUR, 20, 0, 0, 0), slot(JOUR, 22, 0, 23, 0))).isTrue();
    }

    /** The one a same-day comparison could not see: 22:00→02:00 runs into the next day. */
    @Test
    void aSlotCrossingMidnightOverlapsTheNextMorning() {
        assertThat(HoleNeighbourPosteFilter.overlap(slot(JOUR, 22, 0, 2, 0), slot(JOUR.plusDays(1), 0, 0, 6, 0)))
                .isTrue();
        assertThat(HoleNeighbourPosteFilter.overlap(slot(JOUR, 22, 0, 2, 0), slot(JOUR.plusDays(1), 3, 0, 6, 0)))
                .isFalse();
    }

    /**
     * The neighbourhood itself, through the filter's own entry point: a filled
     * seat is worth ruining only next to a hole, and a plan with no hole at all
     * — feasible or not — offers nothing, which is the case that used to cost
     * a full scan per call.
     */
    @Test
    void aFilledSeatIsWorthRuiningOnlyNextToAHole() {
        Creneau matin = slot(JOUR, 9, 0, 12, 0);
        Creneau apresMidi = slot(JOUR, 14, 0, 18, 0);
        PosteAffectation tenuMatin = poste("p1", matin, animateur());
        PosteAffectation tenuApresMidi = poste("p2", apresMidi, animateur());
        PosteAffectation trouDuMatin = poste("p3", matin, null);

        HoleNeighbourPosteFilter filtre = new HoleNeighbourPosteFilter();
        PlanningEvenement sansTrou = plan(List.of(tenuMatin, tenuApresMidi));
        assertThat(filtre.accept(directeur(sansTrou), tenuMatin)).isFalse();
        assertThat(filtre.accept(directeur(sansTrou), tenuApresMidi)).isFalse();

        PlanningEvenement avecTrou = plan(List.of(tenuMatin, tenuApresMidi, trouDuMatin));
        assertThat(filtre.accept(directeur(avecTrou), trouDuMatin)).as("the hole itself").isTrue();
        assertThat(filtre.accept(directeur(avecTrou), tenuMatin)).as("same hour as the hole").isTrue();
        assertThat(filtre.accept(directeur(avecTrou), tenuApresMidi)).as("another hour").isFalse();
    }

    private static PlanningEvenement plan(List<PosteAffectation> postes) {
        PlanningEvenement planning = new PlanningEvenement();
        planning.setPostes(postes);
        return planning;
    }

    private static PosteAffectation poste(String id, Creneau creneau, Animateur animateur) {
        PosteAffectation poste = new PosteAffectation(id, new Stand("S", "S", Set.of("JEU"), 1, 1, false), creneau);
        poste.setAnimateur(animateur);
        return poste;
    }

    private static Animateur animateur() {
        return new Animateur("A1", "Ana", "Un", LocalDate.of(2000, 1, 1), false);
    }

    /** The filter reads nothing else of the score director. */
    private static ScoreDirector<PlanningEvenement> directeur(PlanningEvenement solution) {
        return (ScoreDirector<PlanningEvenement>) java.lang.reflect.Proxy.newProxyInstance(
                HoleNeighbourPosteFilterTest.class.getClassLoader(),
                new Class<?>[] {ScoreDirector.class},
                (proxy, method, args) -> "getWorkingSolution".equals(method.getName()) ? solution : null);
    }

    private static Creneau slot(LocalDate date, int h1, int m1, int h2, int m2) {
        return new Creneau(null, 1, date, LocalTime.of(h1, m1), LocalTime.of(h2, m2));
    }
}
