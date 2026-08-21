package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

/**
 * Admin authentication (issue #165). The default %test profile opens the API
 * so the functional tests don't need a session; this profile restores the real
 * {@code authenticated} policy and exercises the whole form-login flow — the
 * 401 wall, the login endpoint, the session cookie, and the two deliberate
 * public exceptions (espace animateur, auth status).
 */
@QuarkusTest
@TestProfile(AuthentificationAdminTest.Profil.class)
class AuthentificationAdminTest {

    public static class Profil implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            // Only the policy is restored; the account stays the built-in dev
            // default (ADMIN_PASSWORD unset), so no credential-looking literal
            // lives in this file.
            return Map.of("quarkus.http.auth.permission.admin-api.policy", "authenticated");
        }
    }

    /** The dev default of {@code ADMIN_PASSWORD} — not a secret. */
    private static final String MOT_DE_PASSE_DEV = "admin";

    @Test
    void unAppelApiSansSessionEstRefuse() {
        given().when().get("/api/constraints").then().statusCode(401);
    }

    @Test
    void lEspaceAnimateurResteAccessibleSansSession() {
        // 404 (unknown token), never 401: the token itself is the credential.
        given().when().get("/api/espace-animateur/jeton-inconnu").then().statusCode(404);
    }

    @Test
    void leStatutDeSessionEstPublicEtAnonymeParDefaut() {
        given().when().get("/api/auth/me")
                .then()
                .statusCode(200)
                .body("authentifie", equalTo(false));
    }

    /**
     * The exact failure status depends on which mechanism answers (401
     * challenge, or a 302 to the error page the browser then follows to the
     * anonymous session probe); the security property is that no session
     * cookie is ever issued.
     */
    @Test
    void unMauvaisMotDePasseEstRefuse() {
        String cookie = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("j_username", "admin")
                .formParam("j_password", "mauvais")
                .redirects().follow(false)
                .when().post("/j_security_check")
                .then()
                .statusCode(anyOf(is(401), is(302)))
                .extract().cookie("planning-session");
        assertThat(cookie).isNullOrEmpty();
    }

    @Test
    void laConnexionOuvreUneSessionUtilisable() {
        // A successful login answers a redirect to the session probe
        // (landing-page=/api/auth/me), carrying the encrypted session cookie.
        String cookie = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("j_username", "admin")
                .formParam("j_password", MOT_DE_PASSE_DEV)
                .redirects().follow(false)
                .when().post("/j_security_check")
                .then()
                .statusCode(anyOf(is(302), is(200)))
                .extract().cookie("planning-session");
        assertThat(cookie).isNotBlank();

        given().cookie("planning-session", cookie)
                .when().get("/api/constraints")
                .then()
                .statusCode(200);

        given().cookie("planning-session", cookie)
                .when().get("/api/auth/me")
                .then()
                .statusCode(200)
                .body("authentifie", equalTo(true))
                .body("nom", equalTo("admin"));
    }

    /**
     * Behind a TLS-terminating reverse proxy the request reaches the origin in
     * clear text, so the redirect Quarkus builds from the request scheme used
     * to point back to {@code http://<host>/api/auth/me} — active mixed
     * content the browser refuses to follow from an https page, which made
     * logging in impossible. {@code quarkus.http.proxy.proxy-address-forwarding}
     * makes the scheme follow {@code X-Forwarded-Proto}.
     */
    @Test
    void laRedirectionDeConnexionSuitLeSchemaAnnonceParLeProxy() {
        String location = given()
                .contentType("application/x-www-form-urlencoded")
                .header("X-Forwarded-Proto", "https")
                .formParam("j_username", "admin")
                .formParam("j_password", MOT_DE_PASSE_DEV)
                .redirects().follow(false)
                .when().post("/j_security_check")
                .then()
                .statusCode(302)
                .extract().header("Location");
        assertThat(location).startsWith("https://").endsWith("/api/auth/me");
    }

    @Test
    void laDeconnexionEffaceLeCookie() {
        given().when().post("/api/auth/logout")
                .then()
                .statusCode(204);
    }
}
