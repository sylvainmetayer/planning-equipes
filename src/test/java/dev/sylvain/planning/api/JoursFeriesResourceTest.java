package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/** {@code GET /api/jours-feries}: the named public holidays of a bounded range. */
@QuarkusTest
class JoursFeriesResourceTest {

    @Test
    void listsTheHolidaysOfARangeWithTheirNames() {
        given().queryParam("debut", "2026-07-01")
                .queryParam("fin", "2026-08-31")
                .when()
                .get("/api/jours-feries")
                .then()
                .statusCode(200)
                .body("$", hasSize(2))
                .body("date", contains("2026-07-14", "2026-08-15"))
                .body("label", contains("Fête nationale", "Assomption"));
    }

    @Test
    void refusesAnInvertedRange() {
        given().queryParam("debut", "2026-08-31")
                .queryParam("fin", "2026-07-01")
                .when()
                .get("/api/jours-feries")
                .then()
                .statusCode(400);
    }

    @Test
    void refusesARangeLongerThanTwoYears() {
        given().queryParam("debut", "2026-01-01")
                .queryParam("fin", "2028-01-02")
                .when()
                .get("/api/jours-feries")
                .then()
                .statusCode(400);
        given().queryParam("debut", "2026-01-01")
                .queryParam("fin", "2028-01-01")
                .when()
                .get("/api/jours-feries")
                .then()
                .statusCode(200);
    }

    @Test
    void refusesAMissingOrUnreadableBound() {
        given().queryParam("debut", "2026-01-01")
                .when()
                .get("/api/jours-feries")
                .then()
                .statusCode(400);
        given().queryParam("debut", "2026-13-01")
                .queryParam("fin", "2026-12-31")
                .when()
                .get("/api/jours-feries")
                .then()
                .statusCode(400);
    }
}
