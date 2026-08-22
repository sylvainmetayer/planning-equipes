package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Browser hardening headers ({@link SecurityHeadersFilter}), set on every
 * response before the application is opened onto the Internet.
 */
@QuarkusTest
class SecurityHeadersTest {

    @Test
    void toutesLesReponsesPortentLesEnTetesDeDurcissement() {
        given().when().get("/api/auth/me")
                .then()
                .statusCode(200)
                .header("X-Content-Type-Options", equalTo("nosniff"))
                .header("X-Frame-Options", equalTo("DENY"))
                .header("Referrer-Policy", equalTo("no-referrer"))
                .header("Cross-Origin-Opener-Policy", equalTo("same-origin"))
                .header("Content-Security-Policy", containsString("frame-ancestors 'none'"));
    }

    /**
     * The espace animateur token travels in the URL: without
     * {@code Referrer-Policy} it would leave in the {@code Referer} of every
     * navigation out of the page.
     */
    @Test
    void lEspaceAnimateurAussiEstCouvert() {
        given().when().get("/api/espace-animateur/jeton-invente")
                .then()
                .statusCode(404)
                .header("Referrer-Policy", equalTo("no-referrer"));
    }

    @Test
    void hstsNEstEnvoyeQueSurUneVisiteHttps() {
        given().header("X-Forwarded-Proto", "https")
                .when().get("/api/auth/me")
                .then()
                .statusCode(200)
                .header("Strict-Transport-Security", startsWith("max-age="));

        given().when().get("/api/auth/me")
                .then()
                .statusCode(200)
                .header("Strict-Transport-Security", blankOrNullString());
    }

    /**
     * Swagger UI serves inline scripts that are not ours: the CSP stops at the
     * doorstep of {@code /q/*} rather than breaking the page — which is what the
     * filter exists for, {@code quarkus.http.header.*} being unable to give the
     * same header two values depending on the path.
     */
    @Test
    void laCspEpargneLesPagesQuarkus() {
        given().when().get("/q/swagger-ui")
                .then()
                .statusCode(200)
                .header("Content-Security-Policy", blankOrNullString())
                .header("X-Content-Type-Options", equalTo("nosniff"));
    }
}
