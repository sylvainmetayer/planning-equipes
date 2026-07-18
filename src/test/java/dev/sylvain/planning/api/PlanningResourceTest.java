package dev.sylvain.planning.api;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class PlanningResourceTest {

    @Test
    void sampleEndpointRetourneUnPlanning() {
        given()
                .when().get("/api/planning/sample")
                .then()
                .statusCode(200)
                .body("animateurs.size()", greaterThan(0))
                .body("postes.size()", greaterThan(0))
                .body("dateDebutFestival", notNullValue());
    }
}
