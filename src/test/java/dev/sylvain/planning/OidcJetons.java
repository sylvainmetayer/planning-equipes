package dev.sylvain.planning;

import io.smallrye.jwt.build.Jwt;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Signs the access tokens the OIDC tests present, in the shape Keycloak emits
 * them.
 *
 * <p>{@code OidcWiremockTestResource} can already mint a token, but only with
 * the roles in a flat {@code groups} claim — and Keycloak does not put them
 * there. It puts them in {@code realm_access.roles}, which is why
 * {@code application.properties} spells out
 * {@code quarkus.oidc.roles.role-claim-path=realm_access/roles}. A test using
 * the flat claim would exercise a mapping this application does not have and
 * pass while the real one was broken, so the tokens are built here instead.</p>
 *
 * <p>The signing key is {@code privateKey.jwk}, shipped on the test classpath
 * by {@code quarkus-test-oidc-server} — the same key pair whose public half the
 * mock server publishes on its JWKS endpoint.</p>
 */
public final class OidcJetons {

    private OidcJetons() {}

    /** A token for one person, their realm roles, and the audience it is meant for. */
    public static String jeton(String utilisateur, List<String> roles, String audience) {
        return jeton(utilisateur, roles, audience, utilisateur + "@example.org", true);
    }

    /**
     * The full shape, for the espace animateur: the address and whether the
     * identity provider says it was verified.
     *
     * @param emailVerifie Keycloak will happily hold an account whose address
     *                     nobody ever confirmed (a bulk import, an admin typo).
     *                     The espace refuses those, so the tests need to be
     *                     able to mint one.
     */
    public static String jeton(
            String utilisateur, List<String> roles, String audience, String email, boolean emailVerifie) {
        return Jwt.claims()
                .issuer("https://server.example.com")
                .subject(utilisateur)
                .upn(utilisateur)
                .preferredUserName(utilisateur)
                .claim("email", email)
                .claim("email_verified", emailVerifie)
                .claim("realm_access", Map.of("roles", roles))
                .audience(audience)
                .expiresIn(300)
                .sign();
    }

    /**
     * A verified admin-style token carrying {@code auth_time}: the moment the
     * person last proved who they are to the realm, which is what the MCP key
     * reveal reads in place of a re-typed password.
     */
    public static String jeton(String email, List<String> roles, Instant authTime) {
        return Jwt.claims()
                .issuer("https://server.example.com")
                .subject(email)
                .upn(email)
                .preferredUserName(email)
                .claim("email", email)
                .claim("email_verified", true)
                .claim("auth_time", authTime.getEpochSecond())
                .claim("realm_access", Map.of("roles", roles))
                .audience("planning-app")
                .expiresIn(300)
                .sign();
    }
}
