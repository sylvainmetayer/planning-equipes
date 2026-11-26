package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.TypeVerrouillage;
import dev.sylvain.planning.scenario.dto.ScenarioDto;
import dev.sylvain.planning.service.scenario.ScenarioYamlReader;
import dev.sylvain.planning.service.scenario.ScenarioYamlWriter;
import dev.sylvain.planning.service.solve.PlanSnapshotService.AffectationSnapshot;
import dev.sylvain.planning.service.solve.WipeBackupService.LockBackup;
import dev.sylvain.planning.service.solve.WipeBackupService.PlanBackup;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The plan section of a copy kept before a wipe: written, read back, and the same plan comes out (ADR 0112). */
class WipeBackupPlanFileTest {

    private static final PlanBackup PLAN = new PlanBackup(
            List.of(
                    new AffectationSnapshot(
                            "P1", "S1", null, "2026-07-04", "09:00", "13:00", "A1", "10:00", "12:30", null),
                    new AffectationSnapshot("P2", "S1", null, "2026-07-04", "13:00", "17:00", null, null, null, "P1")),
            List.of(
                    new LockBackup(
                            TypeVerrouillage.ANIMATEUR_CRENEAU,
                            "A1",
                            null,
                            LocalDate.of(2026, 7, 4),
                            LocalTime.of(9, 0),
                            LocalTime.of(13, 0),
                            null,
                            "à garder"),
                    new LockBackup(
                            TypeVerrouillage.STAND_JOUR,
                            null,
                            "S1",
                            null,
                            null,
                            null,
                            LocalDate.of(2026, 7, 4),
                            null)));

    @Test
    void thePlanSurvivesBeingWrittenAsTheLastSectionOfTheFile() {
        ScenarioDto scenario = ScenarioYamlReader.readBundledScenario("scenario.yml");

        String yaml = ScenarioYamlWriter.dump(scenario.withPlan(WipeBackupService.toDto(PLAN)));
        ScenarioDto read = ScenarioYamlReader.parseScenarioText(yaml);

        assertThat(yaml).contains("\nplan:");
        assertThat(WipeBackupService.fromDto(read.plan())).isEqualTo(PLAN);
    }

    @Test
    void aFileWithoutAPlanSectionIsLeftAlone() {
        ScenarioDto scenario = ScenarioYamlReader.readBundledScenario("scenario.yml");

        assertThat(scenario.plan()).isNull();
        assertThat(WipeBackupService.withLegacyPlan(scenario, PLAN).plan()).isEqualTo(WipeBackupService.toDto(PLAN));
        // A file already carrying its plan keeps it: the column never overrides the section.
        ScenarioDto carrying = scenario.withPlan(WipeBackupService.toDto(new PlanBackup(List.of(), List.of())));
        assertThat(WipeBackupService.withLegacyPlan(carrying, PLAN).plan()).isEqualTo(carrying.plan());
    }
}
