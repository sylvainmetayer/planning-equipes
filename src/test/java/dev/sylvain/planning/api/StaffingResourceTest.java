package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * The staffing need is promised « avant la saisie d'aucun animateur » (issue
 * #416): the seats depend on the stands and the timeslots only, so an edition
 * without a single animateur still gets its bounds — and the payload names
 * what is missing instead of showing a zero.
 */
@QuarkusTest
class StaffingResourceTest {

    @Test
    void withStandsAndTimeslotsButNoAnimateurTheBoundsAreProvenAndTheRosterIsNamedMissing() {
        given().when().post("/api/planning/reset").then().statusCode(200);
        given().contentType("application/json")
                .body("""
                        {
                          "id":"STAND-STAFFING",
                          "nom":"Stand sans animateur",
                          "typologiesProposees":["STRATEGIE"],
                          "effectifMin":2,
                          "effectifMax":3,
                          "reserveMajeurs":false
                        }
                        """)
                .when()
                .post("/api/stands")
                .then()
                .statusCode(200);
        given().contentType("application/json")
                .body("""
                        {
                          "jour":1,
                          "date":"2026-08-01",
                          "heureDebut":"10:00:00",
                          "heureFin":"12:00:00"
                        }
                        """)
                .when()
                .post("/api/creneaux")
                .then()
                .statusCode(200);

        given().when()
                .get("/api/staffing")
                .then()
                .statusCode(200)
                // Two seats at once: the bound is at least two, whoever is known.
                .body("minimumTotal", greaterThanOrEqualTo(2))
                .body("picSimultane", equalTo(2))
                .body("parJour.size()", equalTo(1))
                .body("parJour[0].sieges", equalTo(2))
                .body("parJour[0].disponibles", equalTo(0))
                .body("referentielsManquants", contains("ANIMATEURS"))
                // What the « Goulot par compétence » card keys its message on.
                .body("parCompetence.animateursTotal", equalTo(0));

        // Leave a coherent dataset behind for the other test classes.
        seedScenario();
    }

    @Test
    void anEmptyEditionNamesEveryMissingReferential() {
        given().when().post("/api/planning/reset").then().statusCode(200);

        given().when()
                .get("/api/staffing")
                .then()
                .statusCode(200)
                .body("minimumTotal", equalTo(0))
                .body("parJour.size()", equalTo(0))
                .body("referentielsManquants", contains("STANDS", "CRENEAUX", "ANIMATEURS"));

        seedScenario();
    }

    private static void seedScenario() {
        given().when().post("/api/planning/reset").then().statusCode(200);
        given().when()
                .post("/api/reference-data/import-scenario?name=scenario.yml")
                .then()
                .statusCode(200);
    }
}
