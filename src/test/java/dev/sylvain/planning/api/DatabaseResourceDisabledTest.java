package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The SQL dump routes are test tooling: an instance that does not switch them on answers 404. */
@QuarkusTest
@TestProfile(DatabaseResourceDisabledTest.Off.class)
class DatabaseResourceDisabledTest {

    public static class Off implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("planning.outillage-test.dump-sql", "false");
        }
    }

    @Test
    void exportIsNotFoundWhenTheToolingIsOff() {
        given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/database/export")
                .then()
                .statusCode(404);
    }

    @Test
    void importIsNotFoundWhenTheToolingIsOff() {
        given().header("X-Edition-Id", "E1")
                .contentType(io.restassured.http.ContentType.TEXT)
                .body("SELECT 1;")
                .when()
                .post("/api/database/import")
                .then()
                .statusCode(404);
    }
}
