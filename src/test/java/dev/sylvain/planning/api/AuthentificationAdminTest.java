package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Admin authentication (issue #165). The default %test profile opens the API
 * so the functional tests don't need a session; this profile restores the real
 * {@code authenticated} policy and exercises the whole form-login flow — the
 * 401 wall, the login endpoint, the session cookie, and the deliberate
 * public exceptions (espace animateur, calendar subscription, auth status,
 * legal notice).
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
            return Map.of(
                    "quarkus.http.auth.permission.admin-api.policy", "authenticated",
                    "quarkus.http.auth.permission.api-docs.policy", "authenticated");
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

    /**
     * The calendar subscription lives under a prefix of its own so an access
     * proxy can except exactly it, and that exception is worth nothing unless
     * the origin agrees: a calendar client cannot answer a 401 challenge, so
     * a wall here would break every subscription silently. 404 (unknown
     * token), never 401 — the token itself is the credential.
     */
    @Test
    void lAbonnementIcsResteAccessibleSansSession() {
        given().when().get("/api/abonnements/jeton-inconnu/planning.ics").then().statusCode(404);
    }

    /**
     * And the exemption stops there. The prefix names one route; nothing else
     * under {@code /api} follows it out of the admin wall.
     */
    @Test
    void lExemptionNeDeborddePasDuPrefixeDAbonnement() {
        given().when().get("/api/animateurs").then().statusCode(401);
        given().when().get("/api/planning/publication").then().statusCode(401);
    }

    /**
     * The tabular import takes a file of names and birth dates, minors
     * included, and the preview alone would already read the whole roster
     * back. Both halves are asserted: an endpoint that only writes behind the
     * wall while it reads in front of it would be worse than neither.
     */
    @Test
    void lImportTabulaireDesAnimateursExigeUneSession() {
        given().contentType("application/json")
                .body("{}")
                .when()
                .post("/api/animateurs/import-csv/analyse")
                .then()
                .statusCode(401);
        given().contentType("application/json")
                .body("{}")
                .when()
                .post("/api/animateurs/import-csv")
                .then()
                .statusCode(401);
        // The example roster carries no real person, but it lives under the
        // admin prefix and stays there: the exemptions are enumerated, never
        // widened by accident.
        given().when().get("/api/animateurs/import-csv/exemple").then().statusCode(401);
    }

    /**
     * A legal notice readable only once logged in would miss the reader it
     * exists for: someone deciding whether to trust the site, or an animateur
     * whose access link has expired and who needs to know whom to contact.
     */
    /**
     * The OpenAPI description and Swagger UI ship in production so the deployed
     * instance documents itself. Public, they would hand a map of every
     * endpoint and payload to anyone — and Swagger UI would offer to call them.
     */
    @Test
    void laDocumentationDeLApiExigeUneSession() {
        given().when().get("/q/openapi").then().statusCode(401);
        given().when().get("/q/swagger-ui").then().statusCode(401);
    }

    @Test
    void lesMentionsLegalesSontLisiblesSansSession() {
        given().when().get("/api/mentions-legales").then().statusCode(200);
    }

    /**
     * The frontend reads its own name, logo and accent colour before it
     * bootstraps — on the login page above all. Behind the session wall, the
     * very page that asks for credentials would render nameless.
     */
    @Test
    void theDeploymentBrandIsReadableWithoutASession() {
        given().when().get("/api/branding").then().statusCode(200);
    }

    @Test
    void leStatutDeSessionEstPublicEtAnonymeParDefaut() {
        given().when().get("/api/auth/me").then().statusCode(200).body("authentifie", equalTo(false));
    }

    /**
     * The exact failure status depends on which mechanism answers (401
     * challenge, or a 302 to the error page the browser then follows to the
     * anonymous session probe); the security property is that no session
     * cookie is ever issued.
     */
    @Test
    void unMauvaisMotDePasseEstRefuse() {
        String cookie = given().contentType("application/x-www-form-urlencoded")
                .formParam("j_username", "admin")
                .formParam("j_password", "mauvais")
                .redirects()
                .follow(false)
                .when()
                .post("/j_security_check")
                .then()
                .statusCode(anyOf(is(401), is(302)))
                .extract()
                .cookie("planning-session");
        assertThat(cookie).isNullOrEmpty();
    }

    @Test
    void laConnexionOuvreUneSessionUtilisable() {
        // A successful login answers a redirect to the session probe
        // (landing-page=/api/auth/me), carrying the encrypted session cookie.
        String cookie = given().contentType("application/x-www-form-urlencoded")
                .formParam("j_username", "admin")
                .formParam("j_password", MOT_DE_PASSE_DEV)
                .redirects()
                .follow(false)
                .when()
                .post("/j_security_check")
                .then()
                .statusCode(anyOf(is(302), is(200)))
                .extract()
                .cookie("planning-session");
        assertThat(cookie).isNotBlank();

        given().cookie("planning-session", cookie)
                .when()
                .get("/api/constraints")
                .then()
                .statusCode(200);

        given().cookie("planning-session", cookie)
                .when()
                .get("/api/auth/me")
                .then()
                .statusCode(200)
                .body("authentifie", equalTo(true))
                .body("nom", equalTo("admin"));
    }

    /**
     * The MCP endpoint takes the API key and nothing else. Form auth reads the
     * session cookie on every path, so under an {@code authenticated} policy a
     * logged-in browser reached {@code /mcp} without the key. 403, not 401:
     * the session is valid, it simply is not the credential this path asks for.
     */
    @Test
    void adminSessionDoesNotOpenTheMcpEndpoint() {
        String cookie = given().contentType("application/x-www-form-urlencoded")
                .formParam("j_username", "admin")
                .formParam("j_password", MOT_DE_PASSE_DEV)
                .redirects()
                .follow(false)
                .when()
                .post("/j_security_check")
                .then()
                .statusCode(anyOf(is(302), is(200)))
                .extract()
                .cookie("planning-session");

        given().cookie("planning-session", cookie).when().post("/mcp").then().statusCode(403);
        given().cookie("planning-session", cookie)
                .header("X-MCP-Api-Key", "test-mcp-key")
                .when()
                .post("/mcp")
                .then()
                .statusCode(not(anyOf(is(401), is(403))));
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
        String location = given().contentType("application/x-www-form-urlencoded")
                .header("X-Forwarded-Proto", "https")
                .formParam("j_username", "admin")
                .formParam("j_password", MOT_DE_PASSE_DEV)
                .redirects()
                .follow(false)
                .when()
                .post("/j_security_check")
                .then()
                .statusCode(302)
                .extract()
                .header("Location");
        assertThat(location).startsWith("https://").endsWith("/api/auth/me");
    }

    @Test
    void laDeconnexionEffaceLeCookie() {
        given().when().post("/api/auth/logout").then().statusCode(204);
    }
}
