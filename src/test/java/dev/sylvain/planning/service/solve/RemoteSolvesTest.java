package dev.sylvain.planning.service.solve;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.solve.SolverJobRepository.LigneJob;
import dev.sylvain.planning.service.solve.SolverJobService.JobStatus;
import dev.sylvain.planning.service.solve.SolverJobService.JobType;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A solve run on the permanent worker (ADR 0086), end to end inside the
 * application: the job is sent to the worker, the plan lands, and a job left on
 * the worker by a restart is followed again.
 *
 * <p>The "machine" is a {@link SolveWorkerServer} on the loopback address: the
 * contract is the thing under test, not a deployment.</p>
 */
@QuarkusTest
@TestProfile(RemoteSolvesTest.PermanentWorker.class)
class RemoteSolvesTest {

    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);
    private static final String SECRET = "worker-secret-of-the-test";

    /** Both sides must say the same: a worker refuses a problem of another version than its own. */
    private static final String VERSION = "test-version";

    /** What an operator would run beside the application. */
    static final class Worker {
        static final SolveWorkerServer SERVER = new SolveWorkerServer(
                new SolveWorker(), SECRET, VERSION, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null);
    }

    public static class PermanentWorker implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "planning.solver.worker.url",
                    "http://127.0.0.1:" + Worker.SERVER.port(),
                    "planning.solver.worker.secret",
                    SECRET,
                    "quarkus.application.version",
                    VERSION);
        }
    }

    @Inject
    RemoteSolves remoteSolves;

    @Inject
    RemoteSolveRepository remoteRepository;

    @Inject
    SolverJobService jobService;

    @Inject
    SolverJobRepository jobRepository;

    @Inject
    EditionContext editionContext;

    @Inject
    DataSource dataSource;

    @BeforeEach
    @AfterEach
    void idleSolverEmptyTables() {
        await().atMost(POLL_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(() -> jobService.findActive().isEmpty());
        remoteSolves.pollEvery(Duration.ofMillis(40));
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM resolution_distante");
            statement.executeUpdate("DELETE FROM solver_job");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aSolveAskedOfTheWorkerSolvesThereAndTheViewSaysSo() {
        importedPlan();
        assertThat(view().getBoolean("configured")).isTrue();

        JsonPath first = pollUntilFinished(launch("seconds=2&distant=true"));

        assertThat(first.getString("status")).isEqualTo("COMPLETED");
        assertThat(first.getBoolean("remote")).isTrue();
        assertThat(first.getString("remoteStage")).isNull();
        assertThat(first.getInt("result.diagnostic.hardScore")).isZero();
        assertThat(remoteSolves.hasPending(first.getString("id"))).isFalse();
    }

    @Test
    void aSolveAskedHereStaysHere() {
        importedPlan();

        JsonPath local = pollUntilFinished(launch("seconds=1&distant=false"));

        assertThat(local.getString("status")).isEqualTo("COMPLETED");
        assertThat(local.getBoolean("remote")).isFalse();
    }

    @Test
    void aJobLeftOnTheWorkerByARestartIsFollowedAgainAndItsPlanLands() {
        importedPlan();
        String first = launch("seconds=3&distant=true");
        await().atMost(POLL_TIMEOUT).pollInterval(Duration.ofMillis(20)).until(() -> remoteSolves.hasPending(first));
        // What a stop of the application leaves: a job RUNNING, and the solve
        // it had sent. Written under another id while the first one runs, so
        // the same worker solve is found again once it is over.
        RemoteSolveRepository.PendingSolve pending =
                remoteRepository.findPending(first).orElseThrow();
        String left = UUID.randomUUID().toString();
        remoteRepository.savePending(left, pending.solveId(), pending.realIds(), Instant.now());
        Instant now = Instant.now();
        jobRepository.save(new LigneJob(
                left,
                editionContext.editionIdCourant(),
                "Édition de test",
                JobType.SOLVE,
                JobStatus.RUNNING,
                3L,
                0L,
                null,
                null,
                null,
                true,
                null,
                now,
                now,
                null));
        assertThat(pollUntilFinished(first).getString("status")).isEqualTo("COMPLETED");

        assertThat(jobService.restaurer()).isEqualTo(1);

        JsonPath resumed = pollUntilFinished(left);
        assertThat(resumed.getString("status")).isEqualTo("COMPLETED");
        assertThat(resumed.getBoolean("remote")).isTrue();
        assertThat(remoteSolves.hasPending(left)).isFalse();
    }

    private JsonPath view() {
        return given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/machine-calcul")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
    }

    private String launch(String query) {
        return given().header("X-Edition-Id", "E1")
                .when()
                .post("/api/solve/async/reference-data?" + query)
                .then()
                .statusCode(202)
                .extract()
                .path("id");
    }

    private void importedPlan() {
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

    private JsonPath pollUntilFinished(String jobId) {
        return await().alias("Job " + jobId + " did not finish in time")
                .atMost(POLL_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(
                        () -> given().header("X-Edition-Id", "E1")
                                .when()
                                .get("/api/jobs/" + jobId)
                                .then()
                                .statusCode(200)
                                .extract()
                                .jsonPath(),
                        job -> !"PENDING".equals(job.getString("status"))
                                && !"RUNNING".equals(job.getString("status"))
                                && !"QUEUED".equals(job.getString("status")));
    }
}
