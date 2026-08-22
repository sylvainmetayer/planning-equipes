package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The push half of following a solver job: {@code GET /api/jobs/stream}.
 *
 * <p>These tests read the raw wire, line by line, rather than going through an
 * SSE client library. The format <em>is</em> the contract here — the browser's
 * {@code EventSource} only ever sees {@code event:}, {@code data:} and
 * {@code :comment} lines — and two of the three properties that matter are
 * invisible to a library that hides them: that the very first event is the
 * current state (and not a wait for the next transition), and that a beat
 * carries both the {@code :keep-alive} comment a proxy needs and the named
 * event the browser's fallback watchdog needs.</p>
 *
 * <p>The heartbeat runs at one second under {@code %test}
 * ({@code planning.jobs.stream.heartbeat}) instead of the twenty of production, so
 * asserting it costs a second rather than half a minute.</p>
 */
@QuarkusTest
class SolverJobStreamResourceTest {

    private static final int MAX_POLLS = 240;
    private static final long POLL_INTERVAL_MS = 250;
    private static final Duration RECEIVE_TIMEOUT = Duration.ofSeconds(15);

    private final List<StreamClient> clients = new ArrayList<>();

    @BeforeEach
    void solverIdleBefore() throws InterruptedException {
        clearQueue();
        waitForIdleSolver();
    }

    /**
     * One teardown, not two: the streams must be closed <b>before</b> anything
     * else, and JUnit does not promise an order between several
     * {@code @AfterEach}. A connection left open holds port 8081 past the end
     * of the class, and the next test class with its own profile — which
     * restarts the whole application — then fails to bind it.
     */
    @AfterEach
    void closeStreamsThenWaitForIdleSolver() throws InterruptedException {
        clients.forEach(StreamClient::close);
        clients.clear();
        clearQueue();
        waitForIdleSolver();
    }

    @Test
    void theStreamDeliversTheCurrentStateOnConnectWithoutWaitingForATransition() {
        StreamClient client = connect();

        // Nothing was submitted, and nothing will be: an idle solver produces
        // no transition, so a stream that only spoke on transitions would leave
        // this client with no state at all — the screen would stay on "unknown"
        // forever, which is the one thing worse than polling.
        SseEvent first = client.next("state");
        JsonPath state = new JsonPath(first.data());
        Object active = state.get("active");
        assertThat(active).isNull();
        assertThat(state.getList("file")).isEmpty();
    }

    @Test
    void everyBeatCarriesBothTheProxyCommentAndAnEventTheBrowserCanSee() {
        StreamClient client = connect();
        client.next("state");

        SseEvent beat = client.next("heartbeat");
        // The comment is what stops a reverse proxy (Pangolin here) from
        // closing a connection that has been silent for minutes.
        assertThat(beat.comments()).contains("keep-alive");
        // The named event is what the browser sees: EventSource never surfaces
        // a comment line, so a bare :keep-alive could not tell the client's
        // watchdog that the stream is still alive.
        assertThat(beat.data()).contains("at");
    }

    @Test
    void aSubmittedJobReachesEveryOpenStreamWithoutBeingAskedFor() {
        planImported();
        StreamClient first = connect();
        StreamClient second = connect();
        assertThat(first.next("state").data()).contains("\"active\":null");
        assertThat(second.next("state").data()).contains("\"active\":null");

        String jobId = given().when().post("/api/solve/async/reference-data?seconds=3")
                .then().statusCode(202)
                .extract().path("id");

        // Both clients, not one: the state is server-wide, and a second tab (or
        // a second person) is entitled to the same push. A single subscription
        // would have served the first client only.
        assertThat(activeIdIn(first, jobId)).isEqualTo(jobId);
        assertThat(activeIdIn(second, jobId)).isEqualTo(jobId);

        // ... and the hand-over back to idle is pushed too, which is what
        // replaces the two-second poll during a solve.
        assertThat(waitForIdleIn(first)).isTrue();
    }

    /* ------------------------------- Helpers ------------------------------- */

    /** Reads state events until one reports this job as holding the solver. */
    private String activeIdIn(StreamClient client, String jobId) {
        for (int i = 0; i < 20; i++) {
            JsonPath state = new JsonPath(client.next("state").data());
            String active = state.getString("active.id");
            if (jobId.equals(active)) {
                return active;
            }
        }
        throw new AssertionError("Job " + jobId + " never appeared on the stream");
    }

    private boolean waitForIdleIn(StreamClient client) {
        for (int i = 0; i < 40; i++) {
            SseEvent event = client.next(null);
            if ("state".equals(event.name()) && event.data().contains("\"active\":null")) {
                return true;
            }
        }
        throw new AssertionError("The stream never went back to an idle solver");
    }

    private StreamClient connect() {
        StreamClient client = new StreamClient(
                URI.create("http://localhost:" + RestAssured.port + "/api/jobs/stream"));
        clients.add(client);
        return client;
    }

    private void planImported() {
        given().when().post("/api/planning/reset").then().statusCode(200);
        given().when().post("/api/reference-data/import-scenario?name=scenario.yml").then().statusCode(200);
    }

    private void clearQueue() {
        List<String> ids = given().when().get("/api/jobs/file")
                .then().statusCode(200).extract().jsonPath().getList("id");
        for (String id : ids) {
            given().when().delete("/api/jobs/" + id);
        }
    }

    private void waitForIdleSolver() throws InterruptedException {
        for (int i = 0; i < MAX_POLLS; i++) {
            if (given().when().get("/api/jobs/active").then().extract().statusCode() == 204) {
                return;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError("Solver still busy");
    }

    /** One parsed server-sent event: its name, its data, and its comment lines. */
    private record SseEvent(String name, String data, List<String> comments) {
    }

    /**
     * Minimal SSE reader: consumes the response line by line on its own thread
     * and hands complete events over through a queue, so a test can block on
     * "the next event" with a timeout instead of sleeping.
     */
    private static final class StreamClient {

        private final HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        private final BlockingQueue<SseEvent> events = new LinkedBlockingQueue<>();
        private final Thread reader;
        private final Stream<String> lines;

        StreamClient(URI uri) {
            HttpResponse<Stream<String>> response;
            try {
                response = http.send(HttpRequest.newBuilder(uri)
                        .header("Accept", "text/event-stream")
                        .GET().build(), HttpResponse.BodyHandlers.ofLines());
            } catch (IOException e) {
                throw new IllegalStateException("Could not open " + uri, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while opening " + uri, e);
            }
            if (response.statusCode() != 200) {
                throw new AssertionError("Stream refused with " + response.statusCode());
            }
            assertThat(response.headers().firstValue("content-type").orElse(""))
                    .startsWith("text/event-stream");
            this.lines = response.body();
            this.reader = Thread.ofVirtual().start(this::read);
        }

        private void read() {
            try {
                consume();
            } catch (RuntimeException e) {
                // Closing the stream from close() cancels the subscription
                // under this thread's feet. Expected: it is how the reader is
                // stopped, and letting it escape only prints a stack trace.
            }
        }

        private void consume() {
            String name = null;
            StringBuilder data = new StringBuilder();
            List<String> comments = new ArrayList<>();
            for (String line : (Iterable<String>) lines::iterator) {
                if (line.isEmpty()) {
                    if (name != null || !data.isEmpty() || !comments.isEmpty()) {
                        events.add(new SseEvent(name, data.toString(), List.copyOf(comments)));
                    }
                    name = null;
                    data.setLength(0);
                    comments.clear();
                } else if (line.startsWith(":")) {
                    comments.add(line.substring(1).trim());
                } else if (line.startsWith("event:")) {
                    name = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring("data:".length()).trim());
                }
            }
        }

        /** The next event, optionally of that name; fails rather than hanging. */
        SseEvent next(String wanted) {
            long deadline = System.nanoTime() + RECEIVE_TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                SseEvent event;
                try {
                    event = events.poll(500, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Interrupted while reading the stream", e);
                }
                if (event != null && (wanted == null || wanted.equals(event.name()))) {
                    return event;
                }
            }
            throw new AssertionError("No " + (wanted == null ? "event" : wanted + " event")
                    + " arrived within " + RECEIVE_TIMEOUT);
        }

        void close() {
            lines.close();
            reader.interrupt();
            http.close();
        }
    }
}
