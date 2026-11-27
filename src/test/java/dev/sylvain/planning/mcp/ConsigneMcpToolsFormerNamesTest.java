package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.PrereglageConsigne;
import dev.sylvain.planning.mcp.ConsigneMcpTools.LeveeResult;
import dev.sylvain.planning.service.consigne.ConsigneService;
import dev.sylvain.planning.service.consigne.ConsigneService.ApercuLevee;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@code lever_consigne} and {@code definir_prereglage_consigne} do what their new names do (ADR 0127 § 4). */
class ConsigneMcpToolsFormerNamesTest {

    /** Records what each tool asked of the service. */
    private static final class ConsignesFictives extends ConsigneService {

        private final List<List<LocalDate>> levees = new ArrayList<>();
        private final List<PrereglageConsigne> prereglages = new ArrayList<>();

        ConsignesFictives() {
            super(null, null, null, null, null, null, null, null, null);
        }

        @Override
        public List<ApercuLevee> apercuLevee(List<LocalDate> dates) {
            return List.of();
        }

        @Override
        public void lever(List<LocalDate> dates) {
            levees.add(dates);
        }

        @Override
        public PrereglageConsigne savePrereglage(PrereglageConsigne prereglage) {
            prereglages.add(prereglage);
            return prereglage;
        }
    }

    @Test
    void theFormerLiftNameLiftsLikeTheNewOne() {
        ConsignesFictives consignes = new ConsignesFictives();
        ConsigneMcpTools tools = new ConsigneMcpTools(consignes);

        LeveeResult nouveau = tools.liftConsigne("2027-02-03", "E1");
        LeveeResult ancien = tools.liftConsigneFormerName("2027-02-03", "E1");

        assertThat(ancien).isEqualTo(nouveau);
        assertThat(ancien.levee()).isTrue();
        assertThat(consignes.levees)
                .containsExactly(List.of(LocalDate.of(2027, 2, 3)), List.of(LocalDate.of(2027, 2, 3)));
    }

    @Test
    void theFormerPresetNameSavesLikeTheNewOne() {
        ConsignesFictives consignes = new ConsignesFictives();
        ConsigneMcpTools tools = new ConsigneMcpTools(consignes);

        PrereglageConsigne nouveau = tools.defineConsignePreset(
                "Plan canicule", "12:00", "18:00", "Arrêté", "18:00-22:00", null, null, null, null, null, null, "E1");
        PrereglageConsigne ancien = tools.defineConsignePresetFormerName(
                "Plan canicule", "12:00", "18:00", "Arrêté", "18:00-22:00", null, null, null, null, null, null, "E1");

        assertThat(ancien).isEqualTo(nouveau);
        assertThat(ancien.fermetureDebut()).isEqualTo(LocalTime.of(12, 0));
        assertThat(ancien.fenetres()).hasSize(1);
        assertThat(consignes.prereglages).hasSize(2);
    }
}
