package dev.sylvain.planning.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The boot checks of the authentication setup (ADR 0054), without booting. */
class OidcConfigurationTest {

    private static final String SECRET_32 = "0123456789abcdef0123456789abcdef";

    @Test
    void uneProductionSansKeycloakNiSecoursNeDemarrePas() {
        assertThatThrownBy(() -> OidcConfiguration.check(false, false, false, false, null, provisioning(false), true))
                .hasMessageContaining("aucune porte");
    }

    @Test
    void leCompteDeSecoursSeulSuffitAOuvrirUnePorte() {
        assertThatCode(() -> OidcConfiguration.check(false, false, false, true, null, provisioning(false), true))
                .doesNotThrowAnyException();
    }

    @Test
    void lesTroisInterrupteursDoiventSAccorder() {
        assertThatThrownBy(
                        () -> OidcConfiguration.check(true, true, false, false, SECRET_32, provisioning(false), true))
                .hasMessageContaining("se contredisent");
    }

    @Test
    void unSecretTropCourtEstRefuseEnProduction() {
        assertThatThrownBy(() -> OidcConfiguration.check(true, true, true, false, "court", provisioning(false), true))
                .hasMessageContaining("32 caractères");
        assertThatCode(() -> OidcConfiguration.check(true, true, true, false, SECRET_32, provisioning(false), true))
                .doesNotThrowAnyException();
    }

    /** Dev and test may talk to a realm whose client is public: no secret demanded there. */
    @Test
    void horsProductionLeSecretNEstPasExige() {
        assertThatCode(() -> OidcConfiguration.check(true, true, true, false, null, provisioning(false), false))
                .doesNotThrowAnyException();
    }

    @Test
    void leProvisioningExigeSonPropreCompteDeService() {
        assertThatThrownBy(() -> OidcConfiguration.check(true, true, true, false, SECRET_32, provisioning(true), true))
                .hasMessageContaining("OIDC_PROVISIONING_SERVER_URL");
    }

    private static ConfigOidc.Provisioning provisioning(boolean actif) {
        return new ConfigOidc.Provisioning() {
            @Override
            public boolean enabled() {
                return actif;
            }

            @Override
            public Optional<String> serverUrl() {
                return Optional.empty();
            }

            @Override
            public String realm() {
                return "planning";
            }

            @Override
            public Optional<String> clientId() {
                return Optional.empty();
            }

            @Override
            public Optional<String> clientSecret() {
                return Optional.empty();
            }

            @Override
            public boolean sendInvitation() {
                return true;
            }

            @Override
            public List<String> invitationActions() {
                return List.of("VERIFY_EMAIL", "webauthn-register-passwordless");
            }
        };
    }
}
