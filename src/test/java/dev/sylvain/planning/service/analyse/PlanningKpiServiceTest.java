package dev.sylvain.planning.service.analyse;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.service.analyse.PlanningKpiService.AffectationKpi;
import dev.sylvain.planning.service.analyse.PlanningKpiService.PlanningKpi;
import java.time.LocalDate;
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

    /** The replay's day × coverage band: every seat counted once, on its day. */
    @Test
    void coverageByDayAddsUpToTheWholePlan() {
        LocalDate lundi = LocalDate.of(2026, 7, 6);
        LocalDate mardi = lundi.plusDays(1);
        List<AffectationKpi> affectations = List.of(
                new AffectationKpi("S1", "1", "A1", 120, lundi),
                new AffectationKpi("S2", "1", null, 120, lundi),
                new AffectationKpi("S1", "2", "A2", 120, mardi),
                new AffectationKpi("S2", "2", "A1", 120, mardi),
                new AffectationKpi("S3", "2", null, 120, mardi));

        PlanningKpi kpi = PlanningKpiService.compute(affectations, null, Map.of(), null, null, null);

        assertThat(kpi.couvertureParJour())
                .containsExactly(
                        Map.entry("2026-07-06", new PlanningKpiService.DayCoverage(2, 1)),
                        Map.entry("2026-07-07", new PlanningKpiService.DayCoverage(3, 2)));
        assertThat(kpi.couvertureParJour().values().stream()
                        .mapToInt(PlanningKpiService.DayCoverage::postes)
                        .sum())
                .isEqualTo(kpi.postesTotal());
        assertThat(kpi.couvertureParJour().values().stream()
                        .mapToInt(PlanningKpiService.DayCoverage::pourvus)
                        .sum())
                .isEqualTo(kpi.postesPourvus());
    }

    /** Seats that did not say their day — a degraded snapshot — leave the figure unmeasured, not empty. */
    @Test
    void coverageByDayIsUnmeasuredWhenNoSeatKnowsItsDay() {
        PlanningKpi kpi =
                PlanningKpiService.compute(List.of(seat("S1", "1", "A1", 60)), null, Map.of(), null, null, null);

        assertThat(kpi.couvertureParJour()).isNull();
    }

    /** A line of the Autopsie written before the figure existed still reads, with the figure absent. */
    @Test
    void aRowWrittenBeforeTheFigureStillReads() throws Exception {
        String ancienne = """
                {"score":"0hard/-3medium/0soft","scoreHard":0,"scoreMedium":-3,"scoreSoft":0,
                 "postesTotal":4,"postesPourvus":3,"animateursAffectes":2,"standsDistincts":2,
                 "creneauxDistincts":2,"heuresIncompletes":false,"violationsParContrainte":{}}""";

        PlanningKpi kpi = new com.fasterxml.jackson.databind.ObjectMapper().readValue(ancienne, PlanningKpi.class);

        assertThat(kpi.postesTotal()).isEqualTo(4);
        assertThat(kpi.couvertureParJour()).isNull();
        assertThat(kpi.dosage()).isNull();
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
