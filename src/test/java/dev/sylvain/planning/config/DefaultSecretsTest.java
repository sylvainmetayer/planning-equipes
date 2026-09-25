package dev.sylvain.planning.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * A production deployment must not come up on the passwords shipped with the
 * source. The admin account is unique and has no second factor, and the image
 * can be started without the project's own compose file — the only thing that
 * ever demanded the two variables.
 */
class DefaultSecretsTest {

    @Test
    void theShippedAdminPasswordRefusesTheBoot() {
        assertThatThrownBy(() -> DefaultSecrets.check("admin", "un-vrai-secret"))
                .isInstanceOf(IllegalStateException.class)
                // The message names the variable an operator has to set, not the
                // config expression that failed to expand.
                .hasMessageContaining("ADMIN_PASSWORD");
    }

    @Test
    void theShippedDatabasePasswordRefusesTheBoot() {
        assertThatThrownBy(() -> DefaultSecrets.check("un-vrai-secret", "festival"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_PASSWORD");
    }

    @Test
    void twoChosenSecretsBoot() {
        assertThatCode(() -> DefaultSecrets.check("un-vrai-secret", "un-autre-secret"))
                .doesNotThrowAnyException();
    }

    /** Empty is not "nothing chosen": it is an empty password on the single account. */
    @Test
    void anEmptySecretRefusesTheBoot() {
        assertThatThrownBy(() -> DefaultSecrets.check("", "un-vrai-secret"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_PASSWORD");
        assertThatThrownBy(() -> DefaultSecrets.check("un-vrai-secret", "   "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_PASSWORD");
    }

    /**
     * The message says what is true — the variable holds the shipped value —
     * rather than that it is unset: an operator who did set it to `admin` would
     * otherwise read the description of a problem that is not theirs.
     */
    @Test
    void theMessageSaysTheValueIsTheShippedOne() {
        assertThatThrownBy(() -> DefaultSecrets.check("admin", "un-vrai-secret"))
                .hasMessageContaining("valeur d'exemple")
                .hasMessageNotContaining("n'est pas défini");
    }

    /**
     * Absent is not the same as left at the default: under Dev Services the
     * datasource password is handed out at runtime and the key never appears in
     * the configuration at all.
     */
    @Test
    void anAbsentSecretDoesNotBlock() {
        assertThatCode(() -> DefaultSecrets.check(null, null)).doesNotThrowAnyException();
    }
}
