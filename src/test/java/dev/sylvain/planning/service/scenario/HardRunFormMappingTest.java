package dev.sylvain.planning.service.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.scenario.dto.ContraintesDto;
import dev.sylvain.planning.service.scenario.ScenarioYamlReader.ContraintesScenario;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A {@code contraintes} section that reaches the mapper without going through
 * the YAML migrations — a JSON import — reads the 3.x hard run form as the YAML
 * reader does (ADR 0118): switched on, the merged rule obligatoire; anywhere
 * else, nothing.
 */
class HardRunFormMappingTest {

    private static final String RULE = "maxJoursConsecutifsTravailles";
    private static final String HARD = "maxJoursConsecutifsTravaillesDur";

    @Test
    void theHardFormSwitchedOnMakesTheRuleObligatoireAndOn() {
        ContraintesScenario contraintes = ScenarioDomainMapper.contraintes(
                        new ContraintesDto(List.of(RULE), List.of(HARD), Map.of(RULE, 25, HARD, 1), null))
                .orElseThrow();

        assertThat(contraintes.obligatoires()).containsEntry(RULE, true);
        assertThat(contraintes.desactivees()).doesNotContain(RULE, HARD);
        assertThat(contraintes.activees()).doesNotContain(HARD);
        assertThat(contraintes.poids()).isEqualTo(Map.of(RULE, 25));
    }

    @Test
    void theHardFormSwitchedOffSaysNothing() {
        ContraintesScenario contraintes = ScenarioDomainMapper.contraintes(
                        new ContraintesDto(List.of(HARD), List.of(), Map.of(), null))
                .orElseThrow();

        assertThat(contraintes.desactivees()).isEmpty();
        assertThat(contraintes.obligatoires()).isEmpty();
    }
}
