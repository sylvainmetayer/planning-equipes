package dev.sylvain.planning.service.espace;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.config.ConfigOidc;
import dev.sylvain.planning.service.compte.Compte;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.jwt.Claims;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

/** What an OIDC session lets the espace take as proven. */
class OidcAuthenticationTest {

    private static JsonWebToken token() {
        return (JsonWebToken) Proxy.newProxyInstance(
                JsonWebToken.class.getClassLoader(), new Class<?>[] {JsonWebToken.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getClaim")) {
                        return Claims.email.name().equals(String.valueOf(args[0]))
                                ? " Agent@Exemple.fr "
                                : Claims.email_verified.name().equals(String.valueOf(args[0])) ? Boolean.TRUE : null;
                    }
                    return method.getName().equals("getName") ? "agent" : null;
                });
    }

    private static OidcAuthentication session(Compte compte) {
        OidcAuthentication auth = new OidcAuthentication();
        auth.config = new ConfigOidc() {
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
                return AdminMfa.PROVIDER;
            }

            @Override
            public List<String> adminMfaAmr() {
                return List.of();
            }
        };
        QuarkusSecurityIdentity.Builder builder =
                QuarkusSecurityIdentity.builder().setPrincipal(token());
        if (compte != null) {
            builder.addAttribute(Compte.IDENTITY_ATTRIBUTE, compte);
        }
        auth.identity = builder.build();
        return auth;
    }

    private static Compte compte(Instant desactiveLe) {
        return new Compte("C1", "agent@exemple.fr", null, null, Instant.now(), null, desactiveLe, List.of());
    }

    @Test
    void anActiveAccountOpensTheEspaceWithItsAddress() {
        assertThat(session(compte(null)).trustedEmail()).contains("agent@exemple.fr");
    }

    @Test
    void aDeactivatedAccountOpensNothing() {
        assertThat(session(compte(Instant.now())).trustedEmail()).isEmpty();
    }
}
