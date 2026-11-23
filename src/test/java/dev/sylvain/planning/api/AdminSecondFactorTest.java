package dev.sylvain.planning.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.config.ConfigOidc;
import jakarta.json.Json;
import java.util.List;
import org.junit.jupiter.api.Test;

/** When the application itself checks an administrator's second factor (ADR 0077). */
class AdminSecondFactorTest {

    private static ConfigOidc config(ConfigOidc.AdminMfa mode) {
        return new ConfigOidc() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public boolean requireEmailVerified() {
                return true;
            }

            @Override
            public AdminMfa adminMfa() {
                return mode;
            }

            @Override
            public List<String> adminMfaAmr() {
                return List.of("mfa", "otp", "http://schemas.microsoft.com/claims/multipleauthn");
            }
        };
    }

    @Test
    void theProviderModeTrustsTheProvider() {
        assertThat(CompteIdentityAugmentor.secondFactorProven(config(ConfigOidc.AdminMfa.PROVIDER), null))
                .isTrue();
    }

    @Test
    void theTokenModeWantsASecondFactorInAmr() {
        ConfigOidc jeton = config(ConfigOidc.AdminMfa.TOKEN);
        assertThat(CompteIdentityAugmentor.secondFactorProven(jeton, null)).isFalse();
        assertThat(CompteIdentityAugmentor.secondFactorProven(
                        jeton, Json.createArrayBuilder().add("pwd").build()))
                .isFalse();
        assertThat(CompteIdentityAugmentor.secondFactorProven(
                        jeton, Json.createArrayBuilder().add("pwd").add("otp").build()))
                .isTrue();
        // ADFS names its multi-factor method by URI, sometimes as a single value.
        assertThat(CompteIdentityAugmentor.secondFactorProven(
                        jeton, "http://schemas.microsoft.com/claims/multipleauthn"))
                .isTrue();
    }
}
