package dev.sylvain.planning.api;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

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

        String jobId = given()
                .contentType("application/json")
                .body(planningJson)
                .when().post("/api/solve/async")
                .then()
                .statusCode(202)
                .body("id", notNullValue())
                .body("type", equalTo("SOLVE"))
                .extract().path("id");

        JsonPath job = pollUntilFinished(jobId);
        assertThat(job.getString("status")).isEqualTo("COMPLETED");
        // The job result is the diagnostic only (score, unfilled seats,
        // constraint breakdown, feasibility) — the solved planning itself is
        // not part of the polling payload, it is fetched from
        // /api/planning/persisted by the dedicated screens instead.
        assertThat(job.getString("result.score")).isNotBlank();
    }

    /**
     * A large scenario's planning JSON is too big to upload; instead the solve
     * problem is built server-side from the persisted reference data, so the
     * browser sends no planning at all.
     */
    @Test
    void solveFromReferenceDataBuildsProblemServerSide() throws InterruptedException {
        given()
                .when().post("/api/reference-data/import-scenario?name=scenario.yml")
                .then().statusCode(204);

        String jobId = given()
                .when().post("/api/solve/async/reference-data")
                .then()
                .statusCode(202)
                .body("type", equalTo("SOLVE"))
                .extract().path("id");

        JsonPath job = pollUntilFinished(jobId);
        assertThat(job.getString("status")).isEqualTo("COMPLETED");
        assertThat(job.getString("result.score")).isNotBlank();
        // The solved planning is persisted server-side even though it never
        // travels back as part of the job result.
        assertThat(given().when().get("/api/planning/persisted").then().extract().jsonPath().getList("postes"))
                .isNotEmpty();
    }

    @Test
    void analyzeAsyncReturnsConstraintBreakdown() throws InterruptedException {
        String planningJson = sampleplanning();

        String jobId = given()
                .contentType("application/json")
                .body(planningJson)
                .when().post("/api/solve/analyze/async")
                .then()
                .statusCode(202)
                .body("type", equalTo("ANALYZE"))
                .extract().path("id");

        JsonPath job = pollUntilFinished(jobId);
        assertThat(job.getString("status")).isEqualTo("COMPLETED");
        assertThat(job.getString("result.score")).isNotBlank();

        given().when().get("/api/jobs")
                .then()
                .statusCode(200);

        given().when().delete("/api/jobs/" + jobId)
                .then()
                .statusCode(204);

        given().when().get("/api/jobs/" + jobId)
                .then()
                .statusCode(404);
    }

    @Test
    void unknownJobReturnsNotFound() {
        given().when().get("/api/jobs/does-not-exist")
                .then()
                .statusCode(404);
    }

    /**
     * The solver lock lives on the server: a second run is refused whoever asks
     * for it, and any client can read the running job (and its elapsed time)
     * from {@code /api/jobs/active} without any browser-side state.
     */
    @Test
    void secondSolverJobIsRefusedWhileOneIsRunning() throws InterruptedException {
        String planningJson = sampleplanning();

        String jobId = given()
                .contentType("application/json")
                .body(planningJson)
                .when().post("/api/solve/async")
                .then()
                .statusCode(202)
                .extract().path("id");

        given()
                .contentType("application/json")
                .body(planningJson)
                .when().post("/api/solve/analyze/async")
                .then()
                .statusCode(409)
                .body("id", equalTo(jobId))
                .body("type", equalTo("SOLVE"));

        JsonPath active = given().when().get("/api/jobs/active")
                .then()
                .statusCode(200)
                .extract().jsonPath();
        assertThat(active.getString("id")).isEqualTo(jobId);
        assertThat(active.getLong("elapsedSeconds")).isGreaterThanOrEqualTo(0);

        // A running job cannot be dropped: that would release the lock while
        // the solver keeps working.
        given().when().delete("/api/jobs/" + jobId)
                .then()
                .statusCode(409);

        assertThat(pollUntilFinished(jobId).getString("status")).isEqualTo("COMPLETED");

        given().when().get("/api/jobs/active")
                .then()
                .statusCode(204);
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
        return given()
                .when().get("/api/planning/sample")
                .then()
                .statusCode(200)
                .extract().asString();
    }

    private JsonPath pollUntilFinished(String jobId) throws InterruptedException {
        for (int i = 0; i < MAX_POLLS; i++) {
            JsonPath job = given()
                    .when().get("/api/jobs/" + jobId)
                    .then()
                    .statusCode(200)
                    .extract().jsonPath();
            if (List.of("COMPLETED", "FAILED", "CANCELLED").contains(job.getString("status"))) {
                return job;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError("Job " + jobId + " did not finish in time");
    }
}
