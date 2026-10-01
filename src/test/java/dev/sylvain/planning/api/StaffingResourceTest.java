package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

import io.quarkus.test.junit.QuarkusTest;
import java.time.Duration;
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

    @Test
    void aCheckStaffsTheSeatsWithAMadeUpTeamAndSaysWhetherItHolds() {
        seedSeats();

        // No animateur at all: the team is made up, of the floor's size.
        given().contentType("application/json")
                .body("{}")
                .when()
                .post("/api/staffing/verification")
                .then()
                .statusCode(202)
                .body("effectif", equalTo(2))
                .body("sieges", equalTo(2));

        awaitTheEnd();
        given().when()
                .get("/api/staffing/verification")
                .then()
                .statusCode(200)
                .body("etat", equalTo("TERMINEE"))
                .body("majeurs", equalTo(2))
                .body("mineurs", equalTo(0))
                .body("realisable", equalTo(true))
                .body("siegesNonPourvus", equalTo(0));

        // The trail: launched by the admin, ended by the application, both
        // naming the check, whose figures the history joins.
        given().when()
                .get("/api/historique")
                .then()
                .statusCode(200)
                .body("find { it.action == 'VERIFICATION_BESOIN_TERMINEE' }.acteur", equalTo("SYSTEME"))
                .body("find { it.action == 'VERIFICATION_BESOIN_TERMINEE' }.verification.realisable", equalTo(true))
                .body("find { it.action == 'VERIFICATION_BESOIN_LANCEE' }.entite", equalTo("VERIFICATION_BESOIN"))
                .body("find { it.action == 'VERIFICATION_BESOIN_LANCEE' }.verification.effectif", equalTo(2));

        seedScenario();
    }

    @Test
    void aCheckTakesAdultsMinorsAndATimeOfItsOwn() {
        seedSeats();

        given().contentType("application/json")
                .body("{\"majeurs\":1,\"mineurs\":1,\"dureeSecondes\":10}")
                .when()
                .post("/api/staffing/verification")
                .then()
                .statusCode(202)
                .body("effectif", equalTo(2))
                .body("majeurs", equalTo(1))
                .body("mineurs", equalTo(1))
                .body("plafondSecondes", equalTo(10));
        awaitTheEnd();
        given().when()
                .get("/api/staffing/verification")
                .then()
                .statusCode(200)
                .body("etat", equalTo("TERMINEE"))
                .body("majeurs", equalTo(1))
                .body("mineurs", equalTo(1))
                .body("plafondSecondes", equalTo(10));

        seedScenario();
    }

    @Test
    void aCheckOutOfRangeIsRefused() {
        seedSeats();

        for (String body : new String[] {
            "{\"majeurs\":0}",
            "{\"majeurs\":0,\"mineurs\":0}",
            "{\"majeurs\":-1,\"mineurs\":3}",
            "{\"majeurs\":2,\"dureeSecondes\":5}",
            "{\"majeurs\":2,\"dureeSecondes\":7200}"
        }) {
            given().contentType("application/json")
                    .body(body)
                    .when()
                    .post("/api/staffing/verification")
                    .then()
                    .statusCode(400);
        }

        seedScenario();
    }

    @Test
    void anEditionWithoutSeatsHasNothingToCheck() {
        given().when().post("/api/planning/reset").then().statusCode(200);

        given().contentType("application/json")
                .body("{}")
                .when()
                .post("/api/staffing/verification")
                .then()
                .statusCode(400);

        seedScenario();
    }

    /** One stand of two seats on one timeslot, and no animateur at all. */
    private static void seedSeats() {
        given().when().post("/api/planning/reset").then().statusCode(200);
        given().contentType("application/json")
                .body("""
                        {
                          "id":"STAND-VERIF",
                          "nom":"Stand vérifié",
                          "typologiesProposees":["STRATEGIE"],
                          "effectifMin":2,
                          "effectifMax":2,
                          "reserveMajeurs":false
                        }
                        """)
                .when()
                .post("/api/stands")
                .then()
                .statusCode(200);
        given().contentType("application/json")
                .body("""
                        {"jour":1,"date":"2026-08-01","heureDebut":"10:00:00","heureFin":"12:00:00"}
                        """)
                .when()
                .post("/api/creneaux")
                .then()
                .statusCode(200);
    }

    private static void awaitTheEnd() {
        await().atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(250))
                .until(() -> !"EN_COURS"
                        .equals(given().when()
                                .get("/api/staffing/verification")
                                .then()
                                .statusCode(200)
                                .extract()
                                .path("etat")));
    }

    private static void seedScenario() {
        given().when().post("/api/planning/reset").then().statusCode(200);
        given().when()
                .post("/api/reference-data/import-scenario?name=scenario.yml")
                .then()
                .statusCode(200);
    }
}
