package dev.sylvain.planning.mcp;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;

import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.solve.QueueReplay;
import dev.sylvain.planning.service.solve.SolverJobRepository;
import dev.sylvain.planning.service.solve.SolverJobRepository.LigneJob;
import dev.sylvain.planning.service.solve.SolverJobService;
import dev.sylvain.planning.service.solve.SolverJobService.JobStatus;
import dev.sylvain.planning.service.solve.SolverJobService.JobType;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The operator's guard on solving, through every entry point: the queue cap,
 * then the hourly quota once spent — over REST (the three asynchronous solves,
 * the synchronous one, the staffing check) and over MCP — and the persisted
 * queue replayed at startup, which neither is refused nor counts.
 *
 * <p>One test, in order, because the quota is the instance's: once spent, it
 * stays spent for the hour, and a second test could not start from a fresh
 * one. The window arithmetic itself is {@code SolverQuotaTest}'s.</p>
 */
@QuarkusTest
@TestProfile(SolverQuotaGuardTest.Profile.class)
class SolverQuotaGuardTest {

    /** Three runs an hour, one job waiting at most. */
    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "planning.solver.max-solves-per-hour", "3",
                    "planning.solver.max-queued-jobs", "1");
        }
    }

    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);

    private static final String QUOTA_SPENT =
            "Cette instance limite le calcul à 3 résolutions par heure\\. Prochaine résolution possible à \\d\\d:\\d\\d\\.";

    @Inject
    SolveurMcpTools solveurTools;

    @Inject
    SolverJobService solverJobService;

    @Inject
    SolverJobRepository jobRepository;

    @Inject
    EditionContext editionContext;

    @Test
    void everyEntryPointMeetsTheQueueCapAndTheHourlyQuotaButAReplayDoesNot() {
        importScenario();

        // 1st run, long enough to hold the solver while the queue fills.
        String running = submit("/api/solve/async/reference-data?seconds=30", 202);
        // 2nd, queued behind it: the queue holds one job.
        String queued = given().header("X-Edition-Id", "E1")
                .contentType("application/json")
                .body("{}")
                .when()
                .post("/api/solve/incremental/async?enFile=true")
                .then()
                .statusCode(202)
                .extract()
                .path("id");

        // The queue is full: refused, a sentence and not a job — the screen
        // tells the two 409 apart by that.
        given().header("X-Edition-Id", "E1")
                .when()
                .post("/api/solve/async/reference-data?enFile=true")
                .then()
                .statusCode(409)
                .body(
                        "message",
                        matchesPattern("Cette instance limite la file d'attente du solveur à 1 résolution "
                                + "planifiée\\. .*"))
                .body("id", nullValue());

        given().header("X-Edition-Id", "E1").when().delete("/api/jobs/" + queued);
        given().header("X-Edition-Id", "E1").when().post("/api/jobs/" + running + "/cancel");
        awaitSolverIdle();

        // 3rd: the last of the hour. The refusal above consumed nothing.
        awaitFinished(submit("/api/solve/async/reference-data?seconds=1", 202));

        // Spent, whatever the route.
        assertQuotaSpent(given().header("X-Edition-Id", "E1")
                .when()
                .post("/api/solve/async/reference-data")
                .then());
        assertQuotaSpent(given().header("X-Edition-Id", "E1")
                .contentType("application/json")
                .body("{}")
                .when()
                .post("/api/solve/incremental/async")
                .then());
        assertQuotaSpent(given().header("X-Edition-Id", "E1")
                .contentType("application/json")
                .body("{}")
                .when()
                .post("/api/solve")
                .then());
        assertQuotaSpent(given().header("X-Edition-Id", "E1")
                .contentType("application/json")
                .body("{}")
                .when()
                .post("/api/staffing/verification")
                .then());
        // A refused check leaves nothing running behind it.
        assertThat(given().header("X-Edition-Id", "E1")
                        .when()
                        .get("/api/jobs/active")
                        .then()
                        .extract()
                        .statusCode())
                .isEqualTo(204);

        // Over MCP: a tool result in error carrying the same sentence.
        assertThatThrownBy(() -> solveurTools.startSolver(1L, null, null, "E1"))
                .isInstanceOf(ToolCallException.class)
                .hasCauseInstanceOf(BusinessError.Conflict.class)
                .hasMessageMatching(QUOTA_SPENT);
        assertThatThrownBy(() -> solveurTools.solveIncremental(null, null, null, 1L, true, "E1"))
                .isInstanceOf(ToolCallException.class)
                .hasMessageMatching(QUOTA_SPENT);

        // A job the persisted queue replays at startup was counted when it was
        // submitted: it runs, quota spent or not.
        String replayed = UUID.randomUUID().toString();
        jobRepository.save(new LigneJob(
                replayed,
                editionContext.editionIdCourant(),
                "Édition de test",
                JobType.SOLVE,
                JobStatus.QUEUED,
                1L,
                null,
                null,
                null,
                null,
                true,
                null,
                Instant.now(),
                null,
                null));
        assertThat(QueueReplay.replay(solverJobService)).isEqualTo(1);
        assertThat(awaitFinished(replayed)).isEqualTo(JobStatus.COMPLETED);

        jobRepository.list().forEach(ligne -> jobRepository.delete(ligne.id()));
    }

    private static void assertQuotaSpent(ValidatableResponse response) {
        response.statusCode(409).body("message", matchesPattern(QUOTA_SPENT));
    }

    private static String submit(String path, int status) {
        return given().header("X-Edition-Id", "E1")
                .when()
                .post(path)
                .then()
                .statusCode(status)
                .extract()
                .path("id");
    }

    private static void importScenario() {
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

    private JobStatus awaitFinished(String jobId) {
        return await().alias("Job " + jobId + " did not finish in time")
                .atMost(POLL_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(
                        () -> solverJobService.find(jobId).orElseThrow().getStatus(),
                        status -> status == JobStatus.COMPLETED
                                || status == JobStatus.FAILED
                                || status == JobStatus.CANCELLED
                                || status == JobStatus.INTERROMPU);
    }

    private void awaitSolverIdle() {
        await().alias("Solver still busy")
                .atMost(POLL_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(() -> solverJobService.findActive().isEmpty());
    }
}
