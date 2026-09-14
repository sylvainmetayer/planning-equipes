package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * « Relu et accepté » over the API: what a reading may name, what it offers to
 * freeze, and what a solve does to it afterwards.
 *
 * <p>The sample scenario is one day (2026-07-08) on one stand, which is enough
 * for every rule here: the progression counts days, and the withdrawal is
 * decided per day.</p>
 */
@QuarkusTest
class ValidationJourneeResourceTest {

    private static final String JOUR = "2026-07-08";

    @AfterEach
    void resetDatabase() {
        given().when().post("/api/planning/reset").then().statusCode(200);
    }

    /** Solves the sample scenario, which persists a plan for the single day above. */
    private void aPersistedPlan() {
        String sample = given().when()
                .get("/api/planning/sample?name=scenario.yml")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        given().contentType("application/json")
                .body(sample)
                .when()
                .post("/api/solve?seconds=3")
                .then()
                .statusCode(200);
    }

    private static String accept(Map<String, Object> corps) {
        return given().contentType("application/json")
                .body(corps)
                .when()
                .post("/api/validations")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("validation.id");
    }

    @Test
    void acceptingADayRecordsItAndMovesTheProgression() {
        aPersistedPlan();

        String id = accept(Map.of("jour", JOUR, "commentaire", "Relu avec le responsable"));

        assertThat(id).isNotBlank();
        given().when()
                .get("/api/validations")
                .then()
                .statusCode(200)
                .body("size()", is(1))
                .body("[0].jour", equalTo(JOUR))
                .body("[0].commentaire", equalTo("Relu avec le responsable"));
        given().when()
                .get("/api/validations/progression")
                .then()
                .statusCode(200)
                .body("journees", is(1))
                .body("journeesValidees", is(1));
    }

    /** Accepting says somebody read the day; it must not freeze it behind their back. */
    @Test
    void acceptingADayLaysNoLockUnlessItIsAskedFor() {
        aPersistedPlan();

        given().contentType("application/json")
                .body(Map.of("jour", JOUR))
                .when()
                .post("/api/validations")
                .then()
                .statusCode(200)
                .body("verrouPose", is(false));

        given().when().get("/api/verrouillages").then().statusCode(200).body("size()", is(0));
    }

    @Test
    void theLockIsLaidDownWhenTheRequestAsksForIt() {
        aPersistedPlan();

        given().contentType("application/json")
                .body(Map.of("jour", JOUR, "poserVerrou", true))
                .when()
                .post("/api/validations")
                .then()
                .statusCode(200)
                .body("verrouPose", is(true));

        given().when()
                .get("/api/verrouillages")
                .then()
                .statusCode(200)
                .body("size()", is(1))
                .body("[0].type", equalTo("JOUR"))
                .body("[0].jour", equalTo(JOUR));
    }

    /** Re-reading a day is a new reading, not a second row. */
    @Test
    void readingADayAgainReplacesItsValidation() {
        aPersistedPlan();

        accept(Map.of("jour", JOUR, "commentaire", "Première lecture"));
        accept(Map.of("jour", JOUR, "commentaire", "Deuxième lecture"));

        given().when()
                .get("/api/validations")
                .then()
                .statusCode(200)
                .body("size()", is(1))
                .body("[0].commentaire", equalTo("Deuxième lecture"));
    }

    @Test
    void withdrawingAValidationLeavesTheDayToReadAgain() {
        aPersistedPlan();
        String id = accept(Map.of("jour", JOUR));

        given().when().delete("/api/validations/" + id).then().statusCode(204);

        given().when().get("/api/validations").then().statusCode(200).body("size()", is(0));
        given().when().delete("/api/validations/" + id).then().statusCode(404);
    }

    @Test
    void aDayTheGridDoesNotHoldIsRefused() {
        aPersistedPlan();

        given().contentType("application/json")
                .body(Map.of("jour", "2026-12-25"))
                .when()
                .post("/api/validations")
                .then()
                .statusCode(400)
                .body(containsString("Aucun créneau"));
    }

    @Test
    void anUnreadableDayIsRefusedRatherThanIgnored() {
        given().when()
                .get("/api/validations/prerequis?jour=hier")
                .then()
                .statusCode(400)
                .body(containsString("illisible"));
        given().when().get("/api/validations/prerequis").then().statusCode(400);
    }

    @Test
    void prerequisitesReadTheDayOfThePersistedPlan() {
        aPersistedPlan();

        JsonPath prerequis = given().when()
                .get("/api/validations/prerequis?jour=" + JOUR)
                .then()
                .statusCode(200)
                .body("jour", equalTo(JOUR))
                .body("validee", is(false))
                .extract()
                .jsonPath();

        List<String> codes = prerequis.getList("prerequis.code");
        assertThat(codes)
                .containsExactlyInAnyOrder(
                        "ECARTS_DURS", "SIEGES_VIDES", "PAUSES_NON_RELAYEES", "POSTES_IRREMPLACABLES");
    }

    /**
     * The one place the two mechanisms meet: a solve that moves a seat of an
     * accepted day withdraws the reading, because nobody has read what the
     * solver has just written.
     *
     * <p>The day is made to move by declaring its holder unavailable — a real
     * late change, and the one thing that makes the move certain rather than
     * hoped for.</p>
     */
    @Test
    void aSolveThatMovesAnAcceptedDayWithdrawsItsValidation() {
        aPersistedPlan();
        accept(Map.of("jour", JOUR));
        markUnavailable(aSeatedAnimateur());

        aPersistedPlan();

        given().when().get("/api/validations").then().statusCode(200).body("size()", is(0));
    }

    /** A frozen day could not move, so its reading still describes what is there. */
    @Test
    void aLockedDayKeepsItsValidationAcrossASolve() {
        aPersistedPlan();
        accept(Map.of("jour", JOUR, "poserVerrou", true));
        markUnavailable(aSeatedAnimateur());

        aPersistedPlan();

        given().when().get("/api/validations").then().statusCode(200).body("size()", is(1));
    }

    /** An animateur the persisted plan seats somewhere on the day. */
    private static String aSeatedAnimateur() {
        String id = given().when()
                .get("/api/planning/persisted")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("postes.find { it.animateur != null }.animateur.id");
        assertThat(id).as("the sample plan should seat somebody").isNotBlank();
        return id;
    }

    /** A late change the next solve has to work around: this person is out that day. */
    private static void markUnavailable(String animateurId) {
        given().contentType("application/json")
                .body("""
                        {"id":"VALIDATION-TEST-INDISPO","type":"INDISPONIBILITE_FORCEE",
                         "animateursConcernes":[{"id":"%s"}],"jour":"%s"}""".formatted(animateurId, JOUR))
                .when()
                .post("/api/contraintes-ad-hoc")
                .then()
                .statusCode(200);
    }

    /** The publication panel says how many days would go out unread. */
    @Test
    void thePublicationPreviewCountsTheDaysNobodyHasRead() {
        aPersistedPlan();

        given().when().get("/api/planning/publication").then().statusCode(200).body("journeesNonValidees", is(1));

        accept(Map.of("jour", JOUR));

        given().when().get("/api/planning/publication").then().statusCode(200).body("journeesNonValidees", is(0));
    }
}
