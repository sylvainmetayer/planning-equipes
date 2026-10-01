package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
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
        given().header("X-Edition-Id", "E1")
                .when()
                .post("/api/planning/reset")
                .then()
                .statusCode(200);
        given().header("X-Edition-Id", "E1")
                .contentType("application/json")
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
        given().header("X-Edition-Id", "E1")
                .contentType("application/json")
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

        given().header("X-Edition-Id", "E1")
                .when()
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
        given().header("X-Edition-Id", "E1")
                .when()
                .post("/api/planning/reset")
                .then()
                .statusCode(200);

        given().header("X-Edition-Id", "E1")
                .when()
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
        given().header("X-Edition-Id", "E1")
                .contentType("application/json")
                .body("{}")
                .when()
                .post("/api/staffing/verification")
                .then()
                .statusCode(202)
                .body("effectif", equalTo(2))
                .body("sieges", equalTo(2));

        awaitTheEnd();
        given().header("X-Edition-Id", "E1")
                .when()
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
        given().header("X-Edition-Id", "E1")
                .when()
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

        given().header("X-Edition-Id", "E1")
                .contentType("application/json")
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
        given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/staffing/verification")
                .then()
                .statusCode(200)
                .body("etat", equalTo("TERMINEE"))
                .body("majeurs", equalTo(1))
                .body("mineurs", equalTo(1))
                .body("plafondSecondes", equalTo(10));

        seedScenario();
    }

    /** A solve that starts while a check runs takes the cores: the check gives way, and says why. */
    @Test
    void aSolveStartingStopsTheRunningCheck() {
        seedSeats();
        // One person for two seats at the same time: no plan exists, so the
        // check would run its whole minute if nothing stopped it.
        given().header("X-Edition-Id", "E1")
                .contentType("application/json")
                .body("{\"majeurs\":1,\"dureeSecondes\":60}")
                .when()
                .post("/api/staffing/verification")
                .then()
                .statusCode(202);

        String jobId = given().header("X-Edition-Id", "E1")
                .when()
                .post("/api/solve/async/reference-data?seconds=1")
                .then()
                .statusCode(202)
                .extract()
                .path("id");
        try {
            await().atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(250))
                    .until(() -> !"EN_COURS"
                            .equals(given().header("X-Edition-Id", "E1")
                                    .when()
                                    .get("/api/staffing/verification")
                                    .then()
                                    .extract()
                                    .path("etat")));
            given().header("X-Edition-Id", "E1")
                    .when()
                    .get("/api/staffing/verification")
                    .then()
                    .statusCode(200)
                    .body("etat", equalTo("ECHEC"))
                    .body("erreur", containsString("une résolution a démarré"));
        } finally {
            given().header("X-Edition-Id", "E1").when().post("/api/jobs/" + jobId + "/cancel");
            await().atMost(Duration.ofSeconds(60))
                    .pollInterval(Duration.ofMillis(250))
                    .until(() -> given().header("X-Edition-Id", "E1")
                                    .when()
                                    .get("/api/jobs/active")
                                    .then()
                                    .extract()
                                    .statusCode()
                            == 204);
        }

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
            given().header("X-Edition-Id", "E1")
                    .contentType("application/json")
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
        given().header("X-Edition-Id", "E1")
                .when()
                .post("/api/planning/reset")
                .then()
                .statusCode(200);

        given().header("X-Edition-Id", "E1")
                .contentType("application/json")
                .body("{}")
                .when()
                .post("/api/staffing/verification")
                .then()
                .statusCode(400);

        seedScenario();
    }

    /** One stand of two seats on one timeslot, and no animateur at all. */
    private static void seedSeats() {
        given().header("X-Edition-Id", "E1")
                .when()
                .post("/api/planning/reset")
                .then()
                .statusCode(200);
        given().header("X-Edition-Id", "E1")
                .contentType("application/json")
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
        given().header("X-Edition-Id", "E1")
                .contentType("application/json")
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
                        .equals(given().header("X-Edition-Id", "E1")
                                .when()
                                .get("/api/staffing/verification")
                                .then()
                                .statusCode(200)
                                .extract()
                                .path("etat")));
    }

    private static void seedScenario() {
        given().header("X-Edition-Id", "E1")
                .when()
                .post("/api/planning/reset")
                .then()
                .statusCode(200);
        given().header("X-Edition-Id", "E1")
                .when()
                .post("/api/reference-data/import-scenario?name=scenario.yml")
                .then()
                .statusCode(200);
    }
}
