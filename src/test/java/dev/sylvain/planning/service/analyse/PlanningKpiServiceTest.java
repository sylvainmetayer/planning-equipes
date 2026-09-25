package dev.sylvain.planning.service.analyse;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.service.analyse.PlanningKpiService.AffectationKpi;
import dev.sylvain.planning.service.analyse.PlanningKpiService.PlanningKpi;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * KPI aggregation (issues #89/#70), exercised on the static core: no database,
 * no Quarkus context. The fairness metrics must stay aggregate — the record
 * has no per-animateur field to leak, which these tests pin down by checking
 * only dispersions come out.
 */
class PlanningKpiServiceTest {

    private static AffectationKpi seat(String stand, String creneau, String animateur, Integer minutes) {
        return new AffectationKpi(stand, creneau, animateur, minutes);
    }

    @Test
    void aggregatesCoverageVolumetryAndHoursDispersion() {
        List<AffectationKpi> affectations = List.of(
                seat("S1", "1", "A1", 120),
                seat("S1", "2", "A1", 120),
                seat("S2", "1", "A2", 240),
                seat("S2", "2", null, null));

        PlanningKpi kpi = PlanningKpiService.compute(
                affectations, "0hard/-3medium/-120soft", Map.of("posteDoitEtrePourvu", 1), 4, 60L, -2);

        assertThat(kpi.postesTotal()).isEqualTo(4);
        assertThat(kpi.postesPourvus()).isEqualTo(3);
        assertThat(kpi.animateursAffectes()).isEqualTo(2);
        assertThat(kpi.standsDistincts()).isEqualTo(2);
        assertThat(kpi.creneauxDistincts()).isEqualTo(2);
        // A1: 4h, A2: 4h → mean 4, spread 0.
        assertThat(kpi.heuresTotal()).isEqualTo(8.0);
        assertThat(kpi.heuresMoyenne()).isEqualTo(4.0);
        assertThat(kpi.heuresEcartType()).isEqualTo(0.0);
        assertThat(kpi.heuresMin()).isEqualTo(4.0);
        assertThat(kpi.heuresMax()).isEqualTo(4.0);
        assertThat(kpi.heuresIncompletes()).isFalse();
        assertThat(kpi.scoreHard()).isZero();
        assertThat(kpi.scoreMedium()).isEqualTo(-3);
        assertThat(kpi.scoreSoft()).isEqualTo(-120);
        assertThat(kpi.modificationsManuelles()).isEqualTo(4);
        assertThat(kpi.tauxModificationsManuelles()).isEqualTo(1.0);
        assertThat(kpi.dureeSolveSecondes()).isEqualTo(60L);
        assertThat(kpi.violationsParContrainte()).containsEntry("posteDoitEtrePourvu", 1);
        // -3 medium of which -2 is a floor: -1 is what a solve can still move.
        assertThat(kpi.plancherMedium()).isEqualTo(-2);
        assertThat(kpi.scoreMediumHorsPlancher()).isEqualTo(-1);
    }

    /**
     * A floor nobody measured is not a floor of zero: the net score stays
     * unknown rather than pretending the whole medium score is in play.
     */
    @Test
    void anUnmeasuredFloorLeavesTheNetScoreUnknown() {
        PlanningKpi kpi = PlanningKpiService.compute(List.of(), "0hard/-3medium/0soft", Map.of(), null, null, null);

        assertThat(kpi.scoreMedium()).isEqualTo(-3);
        assertThat(kpi.plancherMedium()).isNull();
        assertThat(kpi.scoreMediumHorsPlancher()).isNull();
    }

    @Test
    void anUnknownDurationRaisesTheIncompleteHoursFlagWithoutBreakingTheRest() {
        // A staffed seat whose créneau no longer exists: its hours cannot be
        // counted, which the flag must say instead of silently under-counting.
        List<AffectationKpi> affectations = List.of(seat("S1", "1", "A1", 120), seat("S1", "99", "A2", null));

        PlanningKpi kpi = PlanningKpiService.compute(affectations, null, Map.of(), null, null, null);

        assertThat(kpi.heuresIncompletes()).isTrue();
        assertThat(kpi.postesPourvus()).isEqualTo(2);
        assertThat(kpi.heuresTotal()).isEqualTo(2.0);
        assertThat(kpi.scoreHard()).isNull();
        assertThat(kpi.modificationsManuelles()).isNull();
        assertThat(kpi.tauxModificationsManuelles()).isNull();
    }

    @Test
    void anEmptyPlanStaysComputableWithoutDivisionByZero() {
        PlanningKpi kpi = PlanningKpiService.compute(List.of(), null, Map.of(), 2, null, null);

        assertThat(kpi.postesTotal()).isZero();
        assertThat(kpi.heuresMoyenne()).isNull();
        assertThat(kpi.tauxModificationsManuelles()).isNull();
    }

    @Test
    void parseScoreReadsTheThreeLevelsAndToleratesTheInitPrefix() {
        assertThat(PlanningKpiService.parseScore("0hard/-3medium/-120soft")).containsExactly(0, -3, -120);
        assertThat(PlanningKpiService.parseScore("-2init/-1hard/0medium/5soft")).containsExactly(-1, 0, 5);
        assertThat(PlanningKpiService.parseScore(null)).isEmpty();
        assertThat(PlanningKpiService.parseScore("pas un score")).isEmpty();
    }
}
