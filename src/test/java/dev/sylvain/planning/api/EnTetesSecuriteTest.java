package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * En-têtes de durcissement navigateur ({@link EnTetesSecuriteFilter}), posés
 * sur toute réponse avant l'ouverture de l'application sur Internet.
 */
@QuarkusTest
class EnTetesSecuriteTest {

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
     * Le jeton de l'espace animateur voyage dans l'URL : sans
     * {@code Referrer-Policy} il partirait dans le {@code Referer} de chaque
     * navigation sortante de la page.
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
     * Swagger UI sert des scripts inline qui ne sont pas les nôtres : la CSP
     * s'arrête au seuil de {@code /q/*} plutôt que de casser la page — c'est
     * la raison d'être du filtre, la configuration {@code quarkus.http.header.*}
     * ne sachant pas donner deux valeurs au même en-tête selon le chemin.
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
