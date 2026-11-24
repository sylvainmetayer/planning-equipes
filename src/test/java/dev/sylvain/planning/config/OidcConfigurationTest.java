package dev.sylvain.planning.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The boot checks of the authentication setup (ADR 0093), without booting. */
class OidcConfigurationTest {

    private static final String SECRET_32 = "0123456789abcdef0123456789abcdef";

    @Test
    void aProductionWithNeitherProviderNorLocalAccountDoesNotStart() {
        assertThatThrownBy(() -> OidcConfiguration.check(false, false, false, null, true))
                .hasMessageContaining("aucune porte");
    }

    @Test
    void theLocalAccountAloneIsADoor() {
        assertThatCode(() -> OidcConfiguration.check(false, false, true, null, true))
                .doesNotThrowAnyException();
    }

    @Test
    void theTwoSwitchesMustAgree() {
        assertThatThrownBy(() -> OidcConfiguration.check(true, false, false, SECRET_32, true))
                .hasMessageContaining("se contredisent");
    }

    @Test
    void aShortSecretIsRefusedInProduction() {
        assertThatThrownBy(() -> OidcConfiguration.check(true, true, false, "court", true))
                .hasMessageContaining("OIDC_CLIENT_SECRET");
        assertThatCode(() -> OidcConfiguration.check(true, true, false, SECRET_32, true))
                .doesNotThrowAnyException();
    }

    @Test
    void theBreakGlassDoorIsClosedByDefaultOnlyBehindAProvider() {
        ConfigSecours silencieux = () -> java.util.Optional.empty();
        ConfigSecours ouvert = () -> java.util.Optional.of(true);
        assertThat(silencieux.open(false)).isTrue();
        assertThat(silencieux.open(true)).isFalse();
        assertThat(ouvert.open(true)).isTrue();
    }
}
