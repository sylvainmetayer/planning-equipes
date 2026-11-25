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

    @Test
    void theValueIsNeverPrinted() {
        // A retired variable may hold a secret: the log names it, never shows it.
        String secret = java.util.UUID.randomUUID().toString();

        assertThat(RetiredVariables.warnings(Map.of("REMOTE_USER_SECRET", secret)::get))
                .singleElement()
                .asString()
                .contains("REMOTE_USER_SECRET")
                .doesNotContain(secret);
    }
}
