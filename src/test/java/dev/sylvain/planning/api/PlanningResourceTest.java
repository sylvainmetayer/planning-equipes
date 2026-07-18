package dev.sylvain.planning.api;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
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

    @Test
    void standCrudWorks() {
        given()
                .contentType("application/json")
                .body("""
                        {
                          "id":"STAND-TEST",
                          "nom":"Test Stand",
                          "typologiesProposees":["STRATEGIE"],
                          "effectifMin":1,
                          "effectifMax":1,
                          "reserveMajeurs":false
                        }
                        """)
                .when().post("/api/stands")
                .then()
                .statusCode(200)
                .body("id", equalTo("STAND-TEST"));

        given()
                .when().get("/api/stands")
                .then()
                .statusCode(200)
                .body("find { it.id == 'STAND-TEST' }.nom", equalTo("Test Stand"));

        given()
                .when().delete("/api/stands/STAND-TEST")
                .then()
                .statusCode(204);
    }

    @Test
    void creneauCrudWorks() {
        given()
                .contentType("application/json")
                .body("""
                        {
                          "id":"J2-SOIR",
                          "jour":2,
                          "date":"2030-01-02",
                          "heureDebut":"18:00:00",
                          "heureFin":"22:00:00"
                        }
                        """)
                .when().post("/api/creneaux")
                .then()
                .statusCode(200)
                .body("id", equalTo("J2-SOIR"));

        given()
                .when().get("/api/creneaux")
                .then()
                .statusCode(200)
                .body("find { it.id == 'J2-SOIR' }.jour", equalTo(2));

        given()
                .when().delete("/api/creneaux/J2-SOIR")
                .then()
                .statusCode(204);
    }

    @Test
    void exportEndpointsReturnFiles() {
        String planningJson = given()
                .when().get("/api/planning/sample")
                .then()
                .statusCode(200)
                .extract().asString();

        given()
                .contentType("application/json")
                .body(planningJson)
                .when().post("/api/planning/export/pdf/global")
                .then()
                .statusCode(200)
                .contentType("application/pdf");

        given()
                .contentType("application/json")
                .body(planningJson)
                .when().post("/api/planning/export/ics/animateur/A1")
                .then()
                .statusCode(200)
                .contentType("text/calendar;charset=UTF-8");
    }
}
