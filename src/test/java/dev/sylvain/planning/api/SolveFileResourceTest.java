package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;

import java.util.List;
import java.util.Map;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The "résoudre tous les groupes" queue (issue #167) end to end: every flagged
 * group gets solved in sequence under the single solver lock, a non-active
 * group's result lands as a snapshot without ever touching the persisted plan,
 * and the active group — solved last — goes through the plain persist path.
 */
@QuarkusTest
class SolveFileResourceTest {

    private static final int MAX_POLLS = 240;
    private static final long POLL_INTERVAL_MS = 250;

    @AfterEach
    void supprimerLeGroupeDeTest() {
        given().when().get("/api/creneaux")
                .then().statusCode(200)
                .extract().jsonPath().<Map<String, Object>>getList("$").stream()
                .filter(c -> c.get("groupe") instanceof Map<?, ?> groupe && "FILE-G2".equals(groupe.get("id")))
                .forEach(c -> given().when().delete("/api/creneaux/" + c.get("id")));
        given().when().delete("/api/groupes-creneaux/FILE-G2");
    }

    /** Loads the sample scenario and solves it once, so a plan is persisted for the active group. */
    private void planPersiste() throws InterruptedException {
        given().when().post("/api/planning/reset").then().statusCode(200);
        given().when().post("/api/reference-data/import-scenario?name=scenario.yml").then().statusCode(204);
        attendreSolveurLibre();
        String jobId = given()
                .when().post("/api/solve/async/reference-data?seconds=1")
                .then().statusCode(202)
                .extract().path("id");
        assertThat(pollUntilFinished(jobId).getString("status")).isEqualTo("COMPLETED");
    }

    /** A second, non-active group holding one créneau of its own. */
    private void creerGroupeSecondaire() {
        given().contentType(ContentType.JSON)
                .body("{\"id\":\"FILE-G2\",\"nom\":\"Groupe de file\"}")
                .when().post("/api/groupes-creneaux")
                .then().statusCode(200);
        given().contentType(ContentType.JSON)
                .body("{\"date\":\"2099-06-01\",\"heureDebut\":\"10:00:00\",\"heureFin\":\"12:00:00\","
                        + "\"groupe\":{\"id\":\"FILE-G2\",\"nom\":\"Groupe de file\"}}")
                .when().post("/api/creneaux")
                .then().statusCode(200);
    }

    private String groupeActifId() {
        return given()
                .when().get("/api/planning/persisted/resolution")
                .then().statusCode(200)
                .extract().jsonPath().getString("groupeCreneauId");
    }

    @Test
    void laFileResoutChaqueGroupeEtNEcraseQueLActif() throws InterruptedException {
        planPersiste();
        String actifAvant = groupeActifId();
        assertThat(actifAvant).isNotNull().isNotEqualTo("FILE-G2");
        creerGroupeSecondaire();

        String jobId = given()
                .when().post("/api/solve/file/async?seconds=1")
                .then().statusCode(202)
                .extract().path("id");
        JsonPath job = pollUntilFinished(jobId);
        assertThat(job.getString("status")).isEqualTo("COMPLETED");
        assertThat(job.getString("type")).isEqualTo("SOLVE_FILE");

        // One line per group, non-active first, active last, every one solved.
        List<Map<String, Object>> resultats = job.getList("result");
        assertThat(resultats).hasSize(2);
        assertThat(resultats.get(0)).containsEntry("groupeId", "FILE-G2").containsEntry("actif", false)
                .containsEntry("statut", "RESOLU");
        assertThat(resultats.get(1)).containsEntry("groupeId", actifAvant).containsEntry("actif", true)
                .containsEntry("statut", "RESOLU");
        // Warm start surfaced per group: FILE-G2 never had a snapshot (from
        // scratch), while the active group re-seeds from the plan its own
        // solve just persisted — a zero here is the silent cold-solve
        // regression this field exists to expose.
        assertThat((int) resultats.get(0).get("postesReamorces")).isZero();
        assertThat((int) resultats.get(1).get("postesReamorces")).isPositive();

        // The persisted plan still belongs to the active group: solving FILE-G2
        // never wrote through poste_affectation / planning_resolution.
        assertThat(groupeActifId()).isEqualTo(actifAvant);

        // The non-active group's result is a snapshot, tagged with its group.
        List<Map<String, Object>> snapshots = given()
                .when().get("/api/planning/snapshots")
                .then().statusCode(200)
                .extract().jsonPath().getList("$");
        assertThat(snapshots)
                .filteredOn(snapshot -> "FILE-G2".equals(snapshot.get("groupeCreneauId")))
                .isNotEmpty()
                .allSatisfy(snapshot -> assertThat((int) snapshot.get("nombreAffectations")).isPositive());
    }

    @Test
    void unGroupeExcluDeLaFileNEstPasResolu() throws InterruptedException {
        planPersiste();
        creerGroupeSecondaire();
        given().contentType(ContentType.JSON)
                .body("{\"nom\":\"Groupe de file\",\"resoudreEnFile\":false}")
                .when().put("/api/groupes-creneaux/FILE-G2")
                .then().statusCode(200);

        String jobId = given()
                .when().post("/api/solve/file/async?seconds=1")
                .then().statusCode(202)
                .extract().path("id");
        JsonPath job = pollUntilFinished(jobId);
        assertThat(job.getString("status")).isEqualTo("COMPLETED");

        List<Map<String, Object>> resultats = job.getList("result");
        assertThat(resultats).hasSize(1);
        assertThat(resultats.get(0)).containsEntry("actif", true);
    }

    @Test
    void sansAucunGroupeEligibleLaFileEstRefusee() throws InterruptedException {
        planPersiste();
        String actif = groupeActifId();
        basculerResoudreEnFile(actif, false);
        try {
            given()
                    .when().post("/api/solve/file/async")
                    .then().statusCode(400)
                    .body("message", containsString("Aucun groupe"));
        } finally {
            basculerResoudreEnFile(actif, true);
        }
    }

    /** Reads the group's real nom before toggling, whatever the scenario named it. */
    private void basculerResoudreEnFile(String groupeId, boolean resoudreEnFile) {
        Map<String, Object> groupe = given()
                .when().get("/api/groupes-creneaux")
                .then().statusCode(200)
                .extract().jsonPath().<Map<String, Object>>getList("$").stream()
                .filter(g -> groupeId.equals(g.get("id")))
                .findFirst().orElseThrow();
        given().contentType(ContentType.JSON)
                .body(Map.of(
                        "nom", groupe.get("nom"),
                        "resoudreEnFile", resoudreEnFile))
                .when().put("/api/groupes-creneaux/" + groupeId)
                .then().statusCode(200);
    }

    private void attendreSolveurLibre() throws InterruptedException {
        for (int i = 0; i < MAX_POLLS; i++) {
            if (given().when().get("/api/jobs/active").then().extract().statusCode() == 204) {
                return;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError("Solver still busy");
    }

    private JsonPath pollUntilFinished(String jobId) throws InterruptedException {
        for (int i = 0; i < MAX_POLLS; i++) {
            JsonPath job = given()
                    .when().get("/api/jobs/" + jobId)
                    .then().statusCode(200)
                    .extract().jsonPath();
            if (List.of("COMPLETED", "FAILED", "CANCELLED").contains(job.getString("status"))) {
                return job;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError("Job " + jobId + " did not finish in time");
    }
}
