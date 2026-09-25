package dev.sylvain.planning.service.solve;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.sylvain.planning.domain.PlanningEvenement;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The solver's operating metrics, read from the registry the Prometheus
 * endpoint serves: a finished run is timed under its type and outcome, a run
 * that dies on a bug is counted, and the queue gauge follows the queue.
 */
@QuarkusTest
class SolverMetricsTest {

    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);

    @Inject
    MeterRegistry registry;

    @BeforeEach
    @AfterEach
    void idleSolver() {
        for (String id : queuedIds()) {
            given().when().delete("/api/jobs/" + id);
        }
        awaitIdleSolver();
    }

    @Test
    void twoQueuedJobsShowOnTheQueueGaugeAndAFinishedRunIsTimed() {
        importScenario();
        long completedBefore = completedFullRuns();
        String running = given().when()
                .post("/api/solve/async/reference-data?seconds=4")
                .then()
                .statusCode(202)
                .extract()
                .path("id");
        awaitActive(running);

        String queuedFull = given().when()
                .post("/api/solve/async/reference-data?enFile=true&seconds=1")
                .then()
                .statusCode(202)
                .extract()
                .path("id");
        String queuedIncremental = given().contentType(ContentType.JSON)
                .when()
                .post("/api/solve/incremental/async?enFile=true&seconds=1")
                .then()
                .statusCode(202)
                .extract()
                .path("id");

        assertThat(gauge(SolverMetrics.QUEUE_SIZE)).isEqualTo(2.0);
        assertThat(gauge(SolverMetrics.ACTIVE)).isEqualTo(1.0);

        given().when().delete("/api/jobs/" + queuedFull).then().statusCode(204);
        given().when().delete("/api/jobs/" + queuedIncremental).then().statusCode(204);
        assertThat(gauge(SolverMetrics.QUEUE_SIZE)).isZero();

        assertThat(pollUntilFinished(running).getString("status")).isEqualTo("COMPLETED");
        awaitIdleSolver();
        assertThat(gauge(SolverMetrics.ACTIVE)).isZero();
        assertThat(completedFullRuns()).isEqualTo(completedBefore + 1);
    }

    @Test
    void aRunThatDiesOnABugIsCounted() {
        QuarkusMock.installMockForType(new FailingTasks(), SolverJobTasks.class);
        double before = registry.get(SolverMetrics.FAILURES)
                .tag("type", "full")
                .counter()
                .count();
        String planning = given().when()
                .get("/api/planning/sample")
                .then()
                .statusCode(200)
                .extract()
                .asString();

        String jobId = given().contentType(ContentType.JSON)
                .body(planning)
                .when()
                .post("/api/solve/async?seconds=1")
                .then()
                .statusCode(202)
                .extract()
                .path("id");

        assertThat(pollUntilFinished(jobId).getString("status")).isEqualTo("FAILED");
        awaitIdleSolver();
        assertThat(registry.get(SolverMetrics.FAILURES)
                        .tag("type", "full")
                        .counter()
                        .count())
                .isEqualTo(before + 1);
        assertThat(registry.get(SolverMetrics.DURATION)
                        .tag("type", "full")
                        .tag("outcome", "failed")
                        .timer()
                        .count())
                .isPositive();
    }

    /** A solve that throws what no business rule throws — a bug, the case the counter is for. */
    static class FailingTasks extends SolverJobTasks {
        FailingTasks() {
            super(null, null, null);
        }

        @Override
        JobTask solve(PlanningEvenement problem, Long secondsLimit, BooleanSupplier shutdownRequested) {
            return job -> {
                throw new IllegalStateException("simulated solver bug");
            };
        }
    }

    private long completedFullRuns() {
        Timer timer = registry.find(SolverMetrics.DURATION)
                .tag("type", "full")
                .tag("outcome", "completed")
                .timer();
        return timer == null ? 0 : timer.count();
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    private void importScenario() {
        given().when().post("/api/planning/reset").then().statusCode(200);
        given().when()
                .post("/api/reference-data/import-scenario?name=scenario.yml")
                .then()
                .statusCode(200);
    }

    private List<String> queuedIds() {
        return given().when()
                .get("/api/jobs/file")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("id");
    }

    private void awaitActive(String jobId) {
        await().alias("Job " + jobId + " never became active")
                .atMost(POLL_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(() -> {
                    var response = given().when().get("/api/jobs/active").then().extract();
                    return response.statusCode() == 200
                            && jobId.equals(response.jsonPath().getString("id"));
                });
    }

    private void awaitIdleSolver() {
        await().alias("Solver still busy")
                .atMost(POLL_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(() ->
                        given().when().get("/api/jobs/active").then().extract().statusCode() == 204);
    }

    private JsonPath pollUntilFinished(String jobId) {
        return await().alias("Job " + jobId + " did not finish in time")
                .atMost(POLL_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(
                        () -> given().when()
                                .get("/api/jobs/" + jobId)
                                .then()
                                .statusCode(200)
                                .extract()
                                .jsonPath(),
                        job -> List.of("COMPLETED", "FAILED", "CANCELLED").contains(job.getString("status")));
    }
}
