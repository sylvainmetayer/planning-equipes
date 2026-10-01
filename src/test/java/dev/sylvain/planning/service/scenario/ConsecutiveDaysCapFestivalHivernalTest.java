package dev.sylvain.planning.service.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer.CauseInfaisabilite;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer.ConsecutiveDaysRule;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer.FeasibilityReport;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer.PlanContext;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer.SeveriteInfaisabilite;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer.TypeCauseInfaisabilite;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * {@code festival-hivernal} held at six days in a row: the grid holds on
 * paper — its tightest window, the fourth to the tenth day, needs 908
 * person-days at its floor where its 153 animateurs offer 918 — so the check
 * warns rather than proves, and names that window. Whether a solve then finds
 * the plan is the solver's question, not this one's.
 */
class ConsecutiveDaysCapFestivalHivernalTest {

    @Test
    void atSixDaysTheFestivalHoldsOnPaperWithTenPersonDaysToSpare() {
        ScenarioLadder.Loaded loaded = ScenarioLadder.load("festival-hivernal");

        FeasibilityReport report = new FeasibilityAnalyzer()
                .analyze(
                        loaded.problem().getAnimateurs(),
                        loaded.stands(),
                        loaded.creneaux(),
                        loaded.problem().getContraintesAdHoc(),
                        false,
                        new ConsecutiveDaysRule(6, true),
                        PlanContext.NONE);

        CauseInfaisabilite cause = report.causes().stream()
                .filter(candidate -> candidate.type() == TypeCauseInfaisabilite.PLAFOND_JOURS_CONSECUTIFS)
                .findFirst()
                .orElseThrow();
        assertThat(cause.severite()).isEqualTo(SeveriteInfaisabilite.ELEVE);
        assertThat(cause.capacite() - cause.demande()).isEqualTo(10);
        assertThat(cause.demande()).isEqualTo(908);
        assertThat(cause.capacite()).isEqualTo(918);
        LocalDate j1 = loaded.creneaux().stream()
                .map(Creneau::getDate)
                .min(LocalDate::compareTo)
                .orElseThrow();
        assertThat(cause.date()).isEqualTo(j1.plusDays(3));
        assertThat(cause.message()).contains("du " + j1.plusDays(3) + " au " + j1.plusDays(9));
    }
}
