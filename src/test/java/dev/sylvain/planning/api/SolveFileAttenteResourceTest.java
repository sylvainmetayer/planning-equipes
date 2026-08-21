package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import java.util.List;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Queued solves end to end: planning a run while another one holds the solver,
 * so preparing the next edition no longer means waiting in front of the screen.
 *
 * <p>Two properties matter more than the queue itself. A queued job must
 * <b>not</b> hold the solver — {@code /jobs/active} keeps reporting the running
 * one, which is what leaves data entry open on the edition being prepared. And
 * its problem must be built <b>when it starts</b>, not when it was planned:
 * the chaining test below could not pass otherwise, since the queued
 * incremental re-solve needs the plan the job before it persisted.</p>
 */
@QuarkusTest
class SolveFileAttenteResourceTest {

    private static final int MAX_POLLS = 240;
    private static final long POLL_INTERVAL_MS = 250;

    /** The solver is a single shared resource: never hand it over still busy. */
    @BeforeEach
    @AfterEach
    void solveurLibre() throws InterruptedException {
        viderLaFile();
        attendreSolveurLibre();
    }

    @Test
    void uneResolutionPlanifieeDemarreSeuleEtVoitCeQueLaPrecedenteAEcrit() throws InterruptedException {
        planImporte();
        String enCours = lancerSolve(6);

        // Planned while the first one runs: same edition, different kind, so the
        // incremental re-solve is legitimately chained after the full one.
        JsonPath planifie = given().contentType(ContentType.JSON)
                .when().post("/api/solve/incremental/async?enFile=true&seconds=3")
                .then().statusCode(202)
                .body("status", equalTo("QUEUED"))
                .extract().jsonPath();
        String planifieId = planifie.getString("id");

        // It is in the queue, and it holds nothing: the running job is still the
        // active one, so the edition it targets stays editable meanwhile.
        assertThat(idsEnFile()).containsExactly(planifieId);
        assertThat(jobActif().getString("id")).isEqualTo(enCours);

        assertThat(pollUntilFinished(enCours).getString("status")).isEqualTo("COMPLETED");
        // Nobody clicked anything: the queued job takes the solver by itself.
        JsonPath resultat = pollUntilFinished(planifieId);
        assertThat(resultat.getString("status")).isEqualTo("COMPLETED");
        assertThat(resultat.getString("type")).isEqualTo("SOLVE_INCREMENTAL");
        // Only possible if the problem was built at start time: an incremental
        // re-solve fails outright when no plan is persisted yet.
        assertThat(resultat.getInt("result.statistiques.postesFiges")).isPositive();
        assertThat(idsEnFile()).isEmpty();
    }

    @Test
    void planifierDeuxFoisLaMemeChoseSurLaMemeEditionEstRefuse() throws InterruptedException {
        planImporte();
        lancerSolve(6);

        String planifieId = given().contentType(ContentType.JSON)
                .when().post("/api/solve/incremental/async?enFile=true&seconds=1")
                .then().statusCode(202)
                .extract().path("id");

        // The double-click guard: the very same run is already planned.
        given().contentType(ContentType.JSON)
                .when().post("/api/solve/incremental/async?enFile=true&seconds=1")
                .then().statusCode(409)
                .body("id", equalTo(planifieId))
                .body("status", equalTo("QUEUED"));
        assertThat(idsEnFile()).containsExactly(planifieId);
    }

    @Test
    void replanifierLEditionEnCoursDeResolutionResteAutorise() throws InterruptedException {
        planImporte();
        String enCours = lancerSolve(6);

        // Same edition, same kind as the running job: legitimate, and the whole
        // point of the queue. The run under way started before the last
        // corrections and cannot account for them — planning another is how one
        // says "redo it with what I just fixed".
        String planifieId = given()
                .when().post("/api/solve/async/reference-data?enFile=true&seconds=1")
                .then().statusCode(202)
                .body("status", equalTo("QUEUED"))
                .extract().path("id");
        assertThat(idsEnFile()).containsExactly(planifieId);

        assertThat(pollUntilFinished(enCours).getString("status")).isEqualTo("COMPLETED");
        assertThat(pollUntilFinished(planifieId).getString("status")).isEqualTo("COMPLETED");
    }

    @Test
    void uneResolutionRetireeDeLaFileNeDemarreJamais() throws InterruptedException {
        planImporte();
        String enCours = lancerSolve(4);
        String planifieId = given().contentType(ContentType.JSON)
                .when().post("/api/solve/incremental/async?enFile=true&seconds=1")
                .then().statusCode(202)
                .extract().path("id");

        given().when().delete("/api/jobs/" + planifieId).then().statusCode(204);
        assertThat(idsEnFile()).isEmpty();

        assertThat(pollUntilFinished(enCours).getString("status")).isEqualTo("COMPLETED");
        attendreSolveurLibre();
        // Forgotten, not run: the server no longer knows this job at all.
        given().when().get("/api/jobs/" + planifieId).then().statusCode(404);
    }

    @Test
    void sansEnFileUneSecondeResolutionResteRefusee() throws InterruptedException {
        planImporte();
        String enCours = lancerSolve(4);

        // Unchanged default: queueing is something the caller asks for.
        given().when().post("/api/solve/async/reference-data?seconds=1")
                .then().statusCode(409)
                .body("id", equalTo(enCours));
        assertThat(idsEnFile()).isEmpty();
    }

    /* ------------------------------- Helpers ------------------------------- */

    private void planImporte() {
        given().when().post("/api/planning/reset").then().statusCode(200);
        given().when().post("/api/reference-data/import-scenario?name=scenario.yml").then().statusCode(200);
    }

    private String lancerSolve(int secondes) throws InterruptedException {
        attendreSolveurLibre();
        String jobId = given()
                .when().post("/api/solve/async/reference-data?seconds=" + secondes)
                .then().statusCode(202)
                .extract().path("id");
        attendreJobActif(jobId);
        return jobId;
    }

    /** Waits until the server reports this job as the one holding the solver. */
    private void attendreJobActif(String jobId) throws InterruptedException {
        for (int i = 0; i < MAX_POLLS; i++) {
            JsonPath actif = jobActif();
            if (actif != null && jobId.equals(actif.getString("id"))) {
                return;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError("Job " + jobId + " never became active");
    }

    /** {@code null} when the solver is idle (204). */
    private JsonPath jobActif() {
        var response = given().when().get("/api/jobs/active").then().extract();
        return response.statusCode() == 204 ? null : response.jsonPath();
    }

    private List<String> idsEnFile() {
        return given().when().get("/api/jobs/file")
                .then().statusCode(200)
                .extract().jsonPath().getList("id");
    }

    private void viderLaFile() {
        for (String id : idsEnFile()) {
            given().when().delete("/api/jobs/" + id);
        }
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
