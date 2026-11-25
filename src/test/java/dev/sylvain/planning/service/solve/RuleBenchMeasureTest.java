package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The pure half of {@link RuleBenchTest}, outside the {@code scenario-lent} tag. */
class RuleBenchMeasureTest {

    private static final LocalDate DAY = LocalDate.of(2026, 7, 10);
    private static final Stand STAND = new Stand("S", "Stand", Set.of("JEUX"), 2, 2, false);
    private static final Creneau MORNING = new Creneau(1L, 1, DAY, LocalTime.of(9, 0), LocalTime.of(12, 0));
    private static final Animateur ANNE = new Animateur("A", "Anne", "A", LocalDate.of(1990, 1, 1), false);
    private static final Animateur BRUNO = new Animateur("B", "Bruno", "B", LocalDate.of(1990, 1, 1), false);
    private static final Animateur CLARA = new Animateur("C", "Clara", "C", LocalDate.of(1990, 1, 1), false);

    /** Two seats of one cell, held by {@code first} and {@code second} in that poste order. */
    private static PlanningEvenement plan(Animateur first, Animateur second) {
        PosteAffectation p1 = new PosteAffectation("S-1-0", STAND, MORNING);
        PosteAffectation p2 = new PosteAffectation("S-1-1", STAND, MORNING);
        p1.setAnimateur(first);
        p2.setAnimateur(second);
        return new PlanningEvenement(DAY, List.of(ANNE, BRUNO, CLARA), List.of(p1, p2));
    }

    @Test
    void aSeatGivenToSomebodyElseCounts() {
        assertThat(RuleBenchMeasure.changedSeats(plan(ANNE, BRUNO), plan(ANNE, CLARA)))
                .isEqualTo(1);
        assertThat(RuleBenchMeasure.changedSeats(plan(ANNE, BRUNO), plan(ANNE, null)))
                .isEqualTo(1);
        assertThat(RuleBenchMeasure.changedSeats(plan(ANNE, BRUNO), plan(CLARA, null)))
                .isEqualTo(2);
    }

    @Test
    void twoHoldersSwappedInsideOneCellChangeNothing() {
        // Compared by poste id, this swap would count two seats.
        assertThat(RuleBenchMeasure.changedSeats(plan(ANNE, BRUNO), plan(BRUNO, ANNE)))
                .isZero();
    }

    @Test
    void mediumLostIsTheShareOfTheBaselineGivenUp() {
        assertThat(RuleBenchMeasure.mediumLost(HardMediumSoftScore.of(0, -100, 0), HardMediumSoftScore.of(0, -105, 0)))
                .isEqualTo(0.05);
        assertThat(RuleBenchMeasure.mediumLost(HardMediumSoftScore.of(0, -100, 0), HardMediumSoftScore.of(0, -90, 0)))
                .isZero();
        assertThat(RuleBenchMeasure.mediumLost(HardMediumSoftScore.of(0, 0, 0), HardMediumSoftScore.of(0, -1, 0)))
                .isEqualTo(1);
        assertThat(RuleBenchMeasure.mediumLost(HardMediumSoftScore.of(0, 0, 0), HardMediumSoftScore.of(0, 0, -50)))
                .isZero();
    }

    @Test
    void verdictRefusesAWorseHardThenReadsTheThreshold() {
        HardMediumSoftScore base = HardMediumSoftScore.of(0, -100, 0);
        assertThat(RuleBenchMeasure.verdict(base, HardMediumSoftScore.of(0, -105, -999)))
                .isEqualTo("ACCEPTE");
        assertThat(RuleBenchMeasure.verdict(base, HardMediumSoftScore.of(0, -106, 0)))
                .isEqualTo("REFUSE (medium)");
        assertThat(RuleBenchMeasure.verdict(base, HardMediumSoftScore.of(-1, 0, 0)))
                .isEqualTo("REFUSE (faisabilite perdue)");
        assertThat(RuleBenchMeasure.verdict(HardMediumSoftScore.of(-2, -100, 0), HardMediumSoftScore.of(-3, 0, 0)))
                .isEqualTo("REFUSE (hard)");
    }
}
