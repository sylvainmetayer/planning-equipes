package dev.sylvain.planning;

import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import io.smallrye.jwt.build.Jwt;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the OIDC tests share: a profile that plugs a provider in (the in-memory
 * WireMock server of {@code quarkus-test-oidc-server}, no container) in
 * {@code hybrid} mode — a bearer token stands in for the session cookie the
 * code flow would set, so the augmentor under test is the production one — and
 * a minter of the tokens such a provider would sign. The code flow itself is
 * the provider's.
 */
public final class OidcTests {

    private OidcTests() {}

    /** OIDC on, the real admin policy restored, extra overrides on top. */
    public static class Profil implements QuarkusTestProfile {
        private final Map<String, String> extras;

        public Profil() {
            this(Map.of());
        }

        protected Profil(Map<String, String> extras) {
            this.extras = extras;
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> m = new HashMap<>(Map.of(
                    "planning.auth.oidc.enabled", "true",
                    "quarkus.oidc.tenant-enabled", "true",
                    "quarkus.oidc.application-type", "hybrid",
                    "quarkus.oidc.credentials.secret", "secret",
                    "quarkus.oidc.auth-server-url", "${keycloak.url}/realms/quarkus",
                    "quarkus.http.auth.permission.admin-api.policy", "role-gestion"));
            m.putAll(extras);
            return m;
        }

        @Override
        public List<TestResourceEntry> testResources() {
            return List.of(new TestResourceEntry(OidcWiremockTestResource.class));
        }
    }

    /** A verified-address token of one person, with the roles a provider might (wrongly) map. */
    public static String token(String sujet, String email, boolean emailVerifie, String... roles) {
        return "Bearer "
                + Jwt.claims()
                        .issuer("https://server.example.com")
                        .subject(sujet)
                        .upn(email)
                        .claim("email", email)
                        .claim("email_verified", emailVerifie)
                        .claim("realm_access", Map.of("roles", List.of(roles)))
                        .audience("planning-app")
                        .expiresIn(300)
                        .sign();
    }
}
