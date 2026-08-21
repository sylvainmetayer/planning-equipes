package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

/**
 * Le plafond de taille de corps, vérifié là où il compte : l'import de dump
 * SQL est le seul endpoint qui reçoit un gros corps, et c'est donc lui qui
 * dimensionne le réglage. Le profil abaisse le plafond au lieu de fabriquer un
 * corps de 10 Mo, mais c'est le même mécanisme qui coupe.
 */
@QuarkusTest
@TestProfile(PlafondTailleRequeteTest.Profil.class)
class PlafondTailleRequeteTest {

    public static class Profil implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.http.limits.max-body-size", "1K");
        }
    }

    /** Même encodage que {@code DatabaseResourceTest} : le navigateur envoie du {@code application/sql}. */
    private static RequestSpecification requeteSql(String script) {
        return given()
                .config(RestAssured.config().encoderConfig(
                        EncoderConfig.encoderConfig().encodeContentTypeAs("application/sql", ContentType.TEXT)))
                .contentType("application/sql")
                .body(script);
    }

    @Test
    void unCorpsAuDelaDuPlafondEstCoupeAvantDAtteindreLApplication() {
        requeteSql("-- ".repeat(2_000))
                .when().post("/api/database/import")
                .then()
                .statusCode(413);
    }

    /**
     * Sous le plafond, la requête arrive bien jusqu'au service : le script ne
     * porte aucune instruction, donc c'est son refus métier (400) qui prouve
     * qu'il a été lu, pas un refus de transport.
     */
    @Test
    void unCorpsSousLePlafondAtteintLApplication() {
        requeteSql("-- rien à rejouer")
                .when().post("/api/database/import")
                .then()
                .statusCode(400)
                .body("message", containsString("does not contain any statement"));
    }
}
