package dev.sylvain.planning.service.solve;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.solve.SolverJobRepository.LigneJob;
import dev.sylvain.planning.service.solve.SolverJobService.JobStatus;
import dev.sylvain.planning.service.solve.SolverJobService.JobType;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A solve run on a machine started on demand (ADR 0086), end to end inside the
 * application: the job asks the launcher's hook for the machine, the worker
 * solves, the plan lands, the machine is kept for the next solve and stopped
 * when asked — and a job left on the worker by a restart is followed again.
 *
 * <p>The launcher is a few lines of HTTP in this class, and its "machine" a
 * {@link SolveWorkerServer} on the loopback address: the contract is the
 * thing under test, not a provider.</p>
 */
@QuarkusTest
@TestProfile(RemoteSolvesTest.OnDemandWorker.class)
class RemoteSolvesTest {

    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);
    private static final String HOOK_SECRET = "hook-secret-of-the-test";

    /** The launcher: what an operator would run beside the application, holding the provider's token. */
    static final class Launcher {
        static final AtomicInteger STARTS = new AtomicInteger();
        static final AtomicInteger STOPS = new AtomicInteger();
        static volatile boolean neverReady;
        static volatile SolveWorkerServer machine;
        static final HttpServer SERVER;

        static {
            try {
                SERVER = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            SERVER.createContext("/", Launcher::handle);
            SERVER.start();
        }

        private static void handle(HttpExchange exchange) throws IOException {
            // The JDK server's threads carry the launcher's class loader, not
            // the test's: Jackson's module lookup needs the one that sees both.
            Thread.currentThread().setContextClassLoader(Launcher.class.getClassLoader());
            try (exchange) {
                ObjectMapper json = new ObjectMapper();
                String path = exchange.getRequestURI().getPath();
                boolean authorised = ("Bearer " + HOOK_SECRET)
                        .equals(exchange.getRequestHeaders().getFirst("Authorization"));
                Map<String, Object> answer;
                // The test's own levers, over HTTP on purpose: the profile and
                // the test load this class twice, and only the copy the
                // application calls holds the machine.
                if (path.endsWith("/control/reset")) {
                    neverReady = false;
                    stopMachine();
                    STARTS.set(0);
                    STOPS.set(0);
                    answer = Map.of();
                } else if (path.endsWith("/control/never-ready")) {
                    neverReady = true;
                    answer = Map.of();
                } else if (path.endsWith("/control/counters")) {
                    answer = Map.of("starts", STARTS.get(), "stops", STOPS.get());
                } else if (!authorised) {
                    exchange.sendResponseHeaders(401, -1);
                    return;
                } else if (path.endsWith("/start")) {
                    JsonNode body = json.readTree(exchange.getRequestBody());
                    STARTS.incrementAndGet();
                    if (machine == null) {
                        machine = new SolveWorkerServer(
                                new SolveWorker(),
                                body.path("secretWorker").asText(),
                                body.path("version").asText(),
                                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                                null);
                    }
                    answer = Map.of("state", "creating");
                } else if (path.endsWith("/stop")) {
                    STOPS.incrementAndGet();
                    stopMachine();
                    answer = Map.of("state", "absent");
                } else if (machine == null) {
                    answer = Map.of("state", "absent");
                } else if (neverReady) {
                    answer = Map.of("state", "creating");
                } else {
                    answer = Map.of("state", "ready", "url", "http://127.0.0.1:" + machine.port());
                }
                byte[] bytes = json.writeValueAsBytes(answer);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (RuntimeException | Error e) {
                // Said aloud: the JDK server would only close the connection.
                e.printStackTrace();
                throw e;
            }
        }

        static void stopMachine() {
            SolveWorkerServer running = machine;
            machine = null;
            if (running != null) {
                running.close();
            }
        }
    }

    public static class OnDemandWorker implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "planning.solver.worker.hook-url",
                    "http://127.0.0.1:" + Launcher.SERVER.getAddress().getPort() + "/hook",
                    "planning.solver.worker.hook-secret",
                    HOOK_SECRET,
                    "planning.solver.worker.start-timeout",
                    "PT3S");
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
    void idleSolverNoMachineEmptyTables() {
        await().atMost(POLL_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(() -> jobService.findActive().isEmpty());
        launcher("reset");
        remoteSolves.pollEvery(Duration.ofMillis(40));
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM machine_calcul");
            statement.executeUpdate("DELETE FROM resolution_distante");
            statement.executeUpdate("DELETE FROM solver_job");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aSolveAskedOfTheMachineStartsItSolvesThereAndKeepsItForTheNext() {
        importedPlan();
        assertThat(machine().getString("state")).isEqualTo("ABSENT");

        JsonPath first = pollUntilFinished(launch("seconds=2&distant=true"));

        assertThat(first.getString("status")).isEqualTo("COMPLETED");
        assertThat(first.getBoolean("remote")).isTrue();
        assertThat(first.getString("remoteStage")).isNull();
        assertThat(first.getInt("result.diagnostic.hardScore")).isZero();
        assertThat(launcher("counters").getInt("starts")).isEqualTo(1);
        JsonPath ready = machine();
        assertThat(ready.getString("state")).isEqualTo("READY");
        assertThat(ready.getString("stopsAt")).isNotNull();
        assertThat(ready.getLong("billedHoursThisMonth")).isEqualTo(1L);
        assertThat(remoteSolves.hasPending(first.getString("id"))).isFalse();

        assertThat(pollUntilFinished(launch("seconds=1&distant=true")).getString("status"))
                .isEqualTo("COMPLETED");
        assertThat(launcher("counters").getInt("starts"))
                .as("the machine already up serves the next solve")
                .isEqualTo(1);

        given().header("X-Edition-Id", "E1")
                .when()
                .post("/api/machine-calcul/arreter")
                .then()
                .statusCode(200);
        assertThat(launcher("counters").getInt("stops")).isEqualTo(1);
        assertThat(machine().getString("state")).isEqualTo("ABSENT");
        assertThat(machine().getLong("billedHoursThisMonth"))
                .as("a stopped machine still counts for the hour it started")
                .isEqualTo(1L);
    }

    @Test
    void aMachinePreparedAheadIsReadyBeforeAnySolveAndStopsOnRequest() {
        given().header("X-Edition-Id", "E1")
                .when()
                .post("/api/machine-calcul/preparer")
                .then()
                .statusCode(200);

        await().atMost(POLL_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(() -> "READY".equals(machine().getString("state")));
        assertThat(launcher("counters").getInt("starts")).isEqualTo(1);

        given().header("X-Edition-Id", "E1")
                .when()
                .post("/api/machine-calcul/arreter")
                .then()
                .statusCode(200);
        assertThat(machine().getString("state")).isEqualTo("ABSENT");
    }

    @Test
    void aSolveAskedHereNeverStartsAMachine() {
        importedPlan();

        JsonPath local = pollUntilFinished(launch("seconds=1&distant=false"));

        assertThat(local.getString("status")).isEqualTo("COMPLETED");
        assertThat(local.getBoolean("remote")).isFalse();
        assertThat(launcher("counters").getInt("starts")).isEqualTo(0);
    }

    @Test
    void aMachineThatNeverComesUpFailsTheJobAndIsStopped() {
        importedPlan();
        launcher("never-ready");

        JsonPath failed = pollUntilFinished(launch("seconds=1&distant=true"));

        assertThat(failed.getString("status")).isEqualTo("FAILED");
        assertThat(failed.getString("error")).contains("Machine de calcul indisponible");
        assertThat(launcher("counters").getInt("stops"))
                .as("a machine nobody will use is not left running")
                .isEqualTo(1);
        assertThat(machine().getString("state")).isEqualTo("ABSENT");
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
        assertThat(launcher("counters").getInt("starts"))
                .as("followed where it ran, not on a fresh machine")
                .isEqualTo(1);
    }

    @ConfigProperty(name = "planning.solver.worker.hook-url")
    String hookUrl;

    private JsonPath launcher(String lever) {
        return given().when()
                .get(hookUrl + "/control/" + lever)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
    }

    private JsonPath machine() {
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
