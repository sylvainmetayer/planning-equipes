package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The body size ceiling, checked where it matters: importing an SQL dump is
 * the only endpoint that receives a large body, so it is the one that sizes the
 * setting. The profile lowers the ceiling instead of building a 10 MB body, but
 * it is the same mechanism that cuts.
 */
@QuarkusTest
@TestProfile(RequestSizeCapTest.Profil.class)
class RequestSizeCapTest {

    public static class Profil implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.http.limits.max-body-size", "1K");
        }
    }

    /** Same encoding as {@code DatabaseResourceTest}: the browser sends {@code application/sql}. */
    private static RequestSpecification sqlStatement(String script) {
        return given().config(RestAssured.config()
                        .encoderConfig(
                                EncoderConfig.encoderConfig().encodeContentTypeAs("application/sql", ContentType.TEXT)))
                .contentType("application/sql")
                .body(script);
    }

    @Test
    void unCorpsAuDelaDuPlafondEstCoupeAvantDAtteindreLApplication() {
        sqlStatement("-- ".repeat(2_000))
                .when()
                .post("/api/database/import")
                .then()
                .statusCode(413);
    }

    /**
     * Under the ceiling the request does reach the service: the script carries
     * no statement, so its business refusal (400) is what proves it was read,
     * not a transport refusal.
     */
    @Test
    void unCorpsSousLePlafondAtteintLApplication() {
        sqlStatement("-- rien à rejouer")
                .when()
                .post("/api/database/import")
                .then()
                .statusCode(400)
                .body("message", containsString("does not contain any statement"));
    }
}
