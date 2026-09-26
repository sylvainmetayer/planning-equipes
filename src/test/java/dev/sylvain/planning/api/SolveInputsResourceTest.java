package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

/**
 * « Ce calcul tiendra compte de »: the counts follow what is stored. Read as
 * differences, since the edition is shared with the other classes of the run.
 */
@QuarkusTest
class SolveInputsResourceTest {

    private static final String RULE = "equilibrerCharge";

    private static int count(String field) {
        return given().when()
                .get("/api/solve/entrees")
                .then()
                .statusCode(200)
                .body("consignes", notNullValue())
                .body("pendingDeclarations", greaterThanOrEqualTo(0))
                .body("changesSinceSolve", greaterThanOrEqualTo(0))
                .extract()
                .path(field);
    }

    @Test
    void aLockLaidDownIsCountedAndItsRemovalToo() {
        int before = count("locks");
        String id = given().contentType(ContentType.JSON)
                .body("{\"type\":\"JOUR\",\"jour\":\"2026-07-14\",\"raison\":\"Récapitulatif\"}")
                .when()
                .post("/api/verrouillages")
                .then()
                .statusCode(200)
                .extract()
                .path("verrouillage.id");
        try {
            assertEquals(before + 1, count("locks"));
        } finally {
            given().when().delete("/api/verrouillages/" + id).then().statusCode(204);
        }
        assertEquals(before, count("locks"));
    }

    @Test
    void aRuleSwitchedOffIsCounted() {
        int before = count("disabledRules");
        given().contentType(ContentType.JSON)
                .body("{\"actif\":false}")
                .when()
                .put("/api/constraints/" + RULE)
                .then()
                .statusCode(200);
        try {
            assertEquals(before + 1, count("disabledRules"));
        } finally {
            given().contentType(ContentType.JSON)
                    .body("{\"actif\":true}")
                    .when()
                    .put("/api/constraints/" + RULE)
                    .then()
                    .statusCode(200);
        }
    }
}
