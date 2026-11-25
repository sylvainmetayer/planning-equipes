package dev.sylvain.planning.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RetiredVariablesTest {

    @Test
    void nothingSetSaysNothing() {
        assertThat(RetiredVariables.warnings(Map.<String, String>of()::get)).isEmpty();
    }

    @Test
    void blankValueIsNotSet() {
        assertThat(RetiredVariables.warnings(Map.of("REMOTE_USER_ENABLED", "  ")::get))
                .isEmpty();
    }

    @Test
    void aSetVariableIsNamedAndOthersAreNot() {
        assertThat(RetiredVariables.warnings(Map.of("REMOTE_USER_ENABLED", "true")::get))
                .singleElement()
                .asString()
                .contains("REMOTE_USER_ENABLED", "n'a plus aucun effet");
    }
}
