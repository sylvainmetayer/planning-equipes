package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class SolverJobResourceTest {

    private static final int MAX_POLLS = 120;
    private static final long POLL_INTERVAL_MS = 500;

    // The solver is a single shared resource: a job left running by the
    // previous test would make the next submit return 409.
    @BeforeEach
    void solverIsIdle() throws InterruptedException {
        awaitIdleSolver();
    }

    @Test
    void solveAsyncReturnsImmediatelyThenCompletes() throws InterruptedException {
        String planningJson = sampleplanning();

        String jobId = given().contentType("application/json")
                .body(planningJson)
                .when()
                .post("/api/solve/async")
                .then()
                .statusCode(202)
                .body("id", notNullValue())
                .body("type", equalTo("SOLVE"))
                .extract()
                .path("id");

        JsonPath job = pollUntilFinished(jobId);
        assertThat(job.getString("status")).isEqualTo("COMPLETED");
        // The job result wraps the diagnostic (score, unfilled seats,
        // constraint breakdown, feasibility) next to the plan this solve
        // replaced (issue #274) — the solved planning itself is not part of
        // the polling payload, it is fetched from /api/planning/persisted by
        // the dedicated screens instead.
        assertThat(job.getString("result.diagnostic.score")).isNotBlank();
    }

    /**
     * A large scenario's planning JSON is too big to upload; instead the solve
     * problem is built server-side from the persisted reference data, so the
     * browser sends no planning at all.
     */
    @Test
    void solveFromReferenceDataBuildsProblemServerSide() throws InterruptedException {
        given().when()
                .post("/api/reference-data/import-scenario?name=scenario.yml")
                .then()
                .statusCode(200);

        String jobId = given().when()
                .post("/api/solve/async/reference-data")
                .then()
                .statusCode(202)
                .body("type", equalTo("SOLVE"))
                .extract()
                .path("id");

        JsonPath job = pollUntilFinished(jobId);
        assertThat(job.getString("status")).isEqualTo("COMPLETED");
        assertThat(job.getString("result.diagnostic.score")).isNotBlank();
        // The solved planning is persisted server-side even though it never
        // travels back as part of the job result.
        assertThat(given().when()
                        .get("/api/planning/persisted")
                        .then()
                        .extract()
                        .jsonPath()
                        .getList("postes"))
                .isNotEmpty();
    }

    /**
     * Issue #274: a solve announced its own score and never the one it
     * overwrote, so re-solving a good plan read as a success while quietly
     * costing medium points. The second solve must name what it replaced, and
     * the snapshot holding it — the first one has nothing to name.
     */
    @Test
    void aSolveNamesThePlanItReplaced() throws InterruptedException {
        given().when()
                .post("/api/reference-data/import-scenario?name=scenario.yml")
                .then()
                .statusCode(200);

        JsonPath premier = pollUntilFinished(solveFromReferenceData());
        assertThat(premier.getString("status")).isEqualTo("COMPLETED");
        assertThat(premier.getMap("result.previousPlan")).isNull();

        awaitIdleSolver();
        JsonPath second = pollUntilFinished(solveFromReferenceData());

        assertThat(second.getString("status")).isEqualTo("COMPLETED");
        assertThat(second.getLong("result.previousPlan.snapshotId")).isPositive();
        assertThat(second.getString("result.previousPlan.score")).isNotBlank();
        assertThat(second.getBoolean("result.previousPlan.degraded")).isNotNull();
    }

    private String solveFromReferenceData() {
        return given().when()
                .post("/api/solve/async/reference-data")
                .then()
                .statusCode(202)
                .extract()
                .path("id");
    }

    /** A finished job stays in the journal until it is explicitly dropped. */
    @Test
    void finishedJobIsReadableThenDroppedFromTheJournal() throws InterruptedException {
        String planningJson = sampleplanning();

        String jobId = given().contentType("application/json")
                .body(planningJson)
                .when()
                .post("/api/solve/async?seconds=1")
                .then()
                .statusCode(202)
                .body("type", equalTo("SOLVE"))
                .extract()
                .path("id");

        JsonPath job = pollUntilFinished(jobId);
        assertThat(job.getString("status")).isEqualTo("COMPLETED");
        assertThat(job.getString("result.diagnostic.score")).isNotBlank();

        given().when().get("/api/jobs").then().statusCode(200);

        given().when().delete("/api/jobs/" + jobId).then().statusCode(204);

        given().when().get("/api/jobs/" + jobId).then().statusCode(404);
    }

    /**
     * Stops a solve started by mistake: cancel terminates the underlying
     * Timefold solver early, and the job still transitions to CANCELLED
     * (rather than hanging or being killed) once the current run unwinds.
     */
    @Test
    void cancelStopsARunningSolveJob() throws InterruptedException {
        String planningJson = sampleplanning();

        String jobId = given().contentType("application/json")
                .body(planningJson)
                .when()
                .post("/api/solve/async")
                .then()
                .statusCode(202)
                .extract()
                .path("id");

        given().when()
                .post("/api/jobs/" + jobId + "/cancel")
                .then()
                .statusCode(200)
                .body("id", equalTo(jobId));

        JsonPath job = pollUntilFinished(jobId);
        assertThat(job.getString("status")).isEqualTo("CANCELLED");

        given().when().get("/api/jobs/active").then().statusCode(204);
    }

    /**
     * The score curve of the running solve (issue #304), read back through the
     * one route that carries an edition. Asserted on a finished run rather than
     * mid-solve: what matters is that a curve exists, that it is closed by the
     * end of the job, and that it carries the three levels separately.
     */
    @Test
    void aSolveLeavesAReadableScoreCurveBehindIt() throws InterruptedException {
        String jobId = given().contentType("application/json")
                .body(sampleplanning())
                .when()
                .post("/api/solve/async")
                .then()
                .statusCode(202)
                .extract()
                .path("id");

        assertThat(pollUntilFinished(jobId).getString("status")).isEqualTo("COMPLETED");

        JsonPath courbe = given().when()
                .get("/api/jobs/score")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(courbe.getString("jobId")).isEqualTo(jobId);
        // Closed by the end of the job, whichever way it ended: this is what
        // stops the curve on screen instead of leaving it looking live.
        assertThat(courbe.getBoolean("termine")).isTrue();
        assertThat(courbe.getList("points")).isNotEmpty();
        assertThat(courbe.getLong("points[0].tempsMs")).isGreaterThanOrEqualTo(0);
        // Three levels, never one merged number: a hard score at -36 and a soft
        // one at -400 000 have nothing to say to each other on a single axis.
        assertThat(courbe.getMap("points[0]")).containsKeys("hard", "medium", "soft");
    }

    @Test
    void cancelUnknownJobReturnsNotFound() {
        given().when().post("/api/jobs/does-not-exist/cancel").then().statusCode(404);
    }

    @Test
    void unknownJobReturnsNotFound() {
        given().when().get("/api/jobs/does-not-exist").then().statusCode(404);
    }

    /**
     * The solver lock lives on the server: a second run is refused whoever asks
     * for it, and any client can read the running job (and its elapsed time)
     * from {@code /api/jobs/active} without any browser-side state.
     */
    @Test
    void secondSolverJobIsRefusedWhileOneIsRunning() throws InterruptedException {
        String planningJson = sampleplanning();

        String jobId = given().contentType("application/json")
                .body(planningJson)
                .when()
                .post("/api/solve/async")
                .then()
                .statusCode(202)
                .extract()
                .path("id");

        given().contentType("application/json")
                .body(planningJson)
                .when()
                .post("/api/solve/async")
                .then()
                .statusCode(409)
                .body("id", equalTo(jobId))
                .body("type", equalTo("SOLVE"))
                // The frontend's toError reads body.message and nothing else:
                // without it every conflict reaches the screen as the useless
                // "Échec de la requête (code 409)" (issue #328).
                .body("message", containsString("résolution est en cours"));

        // …and the job payloads themselves stay free of it.
        given().when().get("/api/jobs/" + jobId).then().statusCode(200).body("$", not(hasKey("message")));

        JsonPath active = given().when()
                .get("/api/jobs/active")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(active.getString("id")).isEqualTo(jobId);
        assertThat(active.getLong("elapsedSeconds")).isGreaterThanOrEqualTo(0);

        // A running job cannot be dropped: that would release the lock while
        // the solver keeps working.
        given().when().delete("/api/jobs/" + jobId).then().statusCode(409);

        assertThat(pollUntilFinished(jobId).getString("status")).isEqualTo("COMPLETED");

        given().when().get("/api/jobs/active").then().statusCode(204);
    }

    /** Tests share one solver: wait for any job left running by another test. */
    private void awaitIdleSolver() throws InterruptedException {
        for (int i = 0; i < MAX_POLLS; i++) {
            if (given().when().get("/api/jobs/active").then().extract().statusCode() == 204) {
                return;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError("Solver still busy");
    }

    private String sampleplanning() {
        return given().when()
                .get("/api/planning/sample")
                .then()
                .statusCode(200)
                .extract()
                .asString();
    }

    private JsonPath pollUntilFinished(String jobId) throws InterruptedException {
        for (int i = 0; i < MAX_POLLS; i++) {
            JsonPath job = given().when()
                    .get("/api/jobs/" + jobId)
                    .then()
                    .statusCode(200)
                    .extract()
                    .jsonPath();
            if (List.of("COMPLETED", "FAILED", "CANCELLED").contains(job.getString("status"))) {
                return job;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError("Job " + jobId + " did not finish in time");
    }
}
