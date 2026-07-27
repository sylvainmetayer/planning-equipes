package dev.sylvain.planning.api;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
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
        assertThat(job.getList("result.postes")).isNotEmpty();
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
