package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * « Indisponible ce jour » from the fiche animateur: the off day written, the
 * seats of that day freed in the same call, a locked seat kept in place and
 * named, a running solve refusing the gesture, and the undo making the day
 * available without handing a seat back. The seat already started is
 * {@code FrozenPastAcceptanceTest}'s: it needs the frozen past and a clock.
 */
@QuarkusTest
class AnimateurDayOffResourceTest {

    @AfterEach
    void resetDatabase() {
        given().when().post("/api/planning/reset").then().statusCode(200);
    }

    /** Solves the sample scenario and answers somebody holding a seat, with the date of that seat. */
    private static Held solvedSeat() {
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
        Map<String, Object> poste = held(persisted()).get(0);
        String animateurId = (String) ((Map<?, ?>) poste.get("animateur")).get("id");
        String date = (String) ((Map<?, ?>) poste.get("creneau")).get("date");
        return new Held(animateurId, date);
    }

    private record Held(String animateurId, String date) {}

    private static JsonPath persisted() {
        return given().when()
                .get("/api/planning/persisted")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
    }

    private static List<Map<String, Object>> held(JsonPath plan) {
        List<Map<String, Object>> postes = plan.getList("postes");
        return postes.stream().filter(poste -> poste.get("animateur") != null).toList();
    }

    private static List<String> seatsOf(String animateurId, String date) {
        return held(persisted()).stream()
                .filter(poste -> animateurId.equals(((Map<?, ?>) poste.get("animateur")).get("id")))
                .filter(poste -> date.equals(((Map<?, ?>) poste.get("creneau")).get("date")))
                .map(poste -> (String) poste.get("id"))
                .toList();
    }

    private static List<String> offDays(String animateurId) {
        return given().when()
                .get("/api/animateurs/" + animateurId + "/fiche")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("animateur.joursIndisponibles");
    }

    @Test
    void markingADayOffWritesItAndFreesTheSeatsOfThatDay() {
        Held held = solvedSeat();
        List<String> avant = seatsOf(held.animateurId(), held.date());
        assertThat(avant).isNotEmpty();

        JsonPath reponse = given().when()
                .put("/api/animateurs/" + held.animateurId() + "/jours-indisponibles/" + held.date())
                .then()
                .statusCode(200)
                .body("date", equalTo(held.date()))
                .body("unavailable", equalTo(true))
                .body("lockedSeatsKept.size()", equalTo(0))
                .extract()
                .jsonPath();

        List<String> liberes = reponse.getList("freedSeats.posteId");
        assertThat(liberes).containsExactlyInAnyOrderElementsOf(avant);
        assertThat(seatsOf(held.animateurId(), held.date())).isEmpty();
        assertThat(offDays(held.animateurId())).contains(held.date());
    }

    @Test
    void cancellingMakesTheDayAvailableWithoutHandingTheSeatsBack() {
        Held held = solvedSeat();
        given().when()
                .put("/api/animateurs/" + held.animateurId() + "/jours-indisponibles/" + held.date())
                .then()
                .statusCode(200);

        given().when()
                .delete("/api/animateurs/" + held.animateurId() + "/jours-indisponibles/" + held.date())
                .then()
                .statusCode(200)
                .body("unavailable", equalTo(false))
                .body("freedSeats.size()", equalTo(0));

        assertThat(offDays(held.animateurId())).doesNotContain(held.date());
        assertThat(seatsOf(held.animateurId(), held.date())).isEmpty();
    }

    /**
     * A lock says « this one does not move »: the day is written all the same,
     * the locked seats stay where they are and are named in the answer, and
     * nothing else of that day is left to free.
     */
    @Test
    void aLockedSeatIsKeptAndTheDayWrittenAllTheSame() {
        Held held = solvedSeat();
        List<String> avant = seatsOf(held.animateurId(), held.date());
        given().contentType("application/json")
                .body("{\"id\":\"OFF-VERROU\",\"type\":\"ANIMATEUR\",\"animateurId\":\"" + held.animateurId() + "\"}")
                .when()
                .post("/api/verrouillages")
                .then()
                .statusCode(200);
        try {
            JsonPath reponse = given().when()
                    .put("/api/animateurs/" + held.animateurId() + "/jours-indisponibles/" + held.date())
                    .then()
                    .statusCode(200)
                    .body("unavailable", equalTo(true))
                    .extract()
                    .jsonPath();

            assertThat(reponse.getList("freedSeats")).isEmpty();
            assertThat(reponse.getList("lockedSeatsKept.posteId", String.class))
                    .containsExactlyInAnyOrderElementsOf(avant);
            assertThat(reponse.getString("lockedSeatsKept[0].standId")).isNotBlank();
            assertThat(offDays(held.animateurId())).contains(held.date());
            assertThat(seatsOf(held.animateurId(), held.date())).isEqualTo(avant);
        } finally {
            given().when().delete("/api/verrouillages/OFF-VERROU");
        }
    }

    /** A solve holds the edition: its landing would rewrite both the off days and the seats. */
    @Test
    void aSolveRunningRefusesTheGesture() {
        Held held = solvedSeat();
        String jobId = given().when()
                .post("/api/solve/async/reference-data?seconds=30")
                .then()
                .statusCode(202)
                .extract()
                .path("id");
        try {
            given().when()
                    .put("/api/animateurs/" + held.animateurId() + "/jours-indisponibles/" + held.date())
                    .then()
                    .statusCode(409)
                    .body("message", containsString("résolution est en cours"));
            assertThat(offDays(held.animateurId())).doesNotContain(held.date());
        } finally {
            given().when().post("/api/jobs/" + jobId + "/cancel");
            await().atMost(Duration.ofSeconds(60))
                    .pollInterval(Duration.ofMillis(250))
                    .until(() -> given().when()
                                    .get("/api/jobs/active")
                                    .then()
                                    .extract()
                                    .statusCode()
                            == 204);
        }
    }

    @Test
    void anUnknownAnimateurIs404AndAnUnreadableDateIs400() {
        given().when()
                .put("/api/animateurs/PERSONNE/jours-indisponibles/2026-07-10")
                .then()
                .statusCode(404);
        Held held = solvedSeat();
        given().when()
                .put("/api/animateurs/" + held.animateurId() + "/jours-indisponibles/10-07-2026")
                .then()
                .statusCode(400);
    }
}
