package dev.sylvain.planning.observability;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The Prometheus endpoint: where it is served, where it is not, and what it
 * may never say.
 *
 * <p>The protection is the port. The metrics live on the management
 * interface, which the production stack does not publish; the application
 * port — the one the reverse proxy forwards — must not serve them at all.
 * And whatever the scraper reads must have a bounded set of series: no label
 * may carry a token, an id or an address, which is also what keeps an
 * animateur's espace link out of a monitoring system.</p>
 */
@QuarkusTest
class MetricsEndpointTest {

    /** Label names that would mean one series per person, per edition or per caller. */
    private static final Set<String> FORBIDDEN_LABELS =
            Set.of("edition", "animateur", "jeton", "token", "email", "address", "client", "ip", "user", "solver_id");

    /** A {@code uri} value is a fixed word or a path of literal segments and {@code {templates}}. */
    private static final Pattern SAFE_URI =
            Pattern.compile("NOT_FOUND|REDIRECTION|UNKNOWN|root|(/(\\{[A-Za-z]+\\}|[A-Za-z._-]+))+");

    private static final Pattern LABEL = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)=\"([^\"]*)\"");

    /** Resolved under the management root path, /q: this is /q/metrics on the management port. */
    @TestHTTPResource(value = "metrics", management = true)
    URL managementMetrics;

    @Test
    void theManagementInterfaceServesThePrometheusFormat() {
        String body = scrape();

        assertThat(body)
                .contains("# TYPE planning_solver_queue_size gauge")
                .contains("planning_solver_active ")
                .contains("planning_solver_failures_total{type=\"full\"}")
                .contains("planning_solver_failures_total{type=\"incremental\"}")
                .contains("jvm_memory_used_bytes");
    }

    /** What the reverse proxy forwards never reaches the metrics. */
    @Test
    void theApplicationPortServesNoMetrics() {
        String body = given().when()
                .get("/q/metrics")
                .then()
                .statusCode(404)
                .extract()
                .asString();

        assertThat(body).doesNotContain("planning_solver").doesNotContain("jvm_memory");
    }

    /** The production stack publishes the application port alone, on the loopback. */
    @Test
    void theProductionStackDoesNotPublishTheManagementPort() throws IOException {
        String compose = Files.readString(Path.of("docker-compose.prod.yml"));

        assertThat(compose).contains("\"127.0.0.1:8080:8080\"");
        // A port mapping is a list item "host:container"; the one comment
        // naming the scrape URL is not.
        assertThat(compose).doesNotContainPattern("(?m)^\\s*-\\s*\"?[0-9.:]*9000");
    }

    /**
     * The two public routes carry a credential in their path. Called with one,
     * they show up under their template; the credential shows up nowhere —
     * nor does the espace link the frontend serves, nor a path nobody routes.
     * The template is the resource's, without the {@code /api} the REST layer
     * is mounted under: that is how the extension writes it.
     */
    @Test
    void noTokenEverReachesALabel() {
        List<String> tokens = List.of(
                "espacetoken0a1b2c3d4e5f",
                "espacetoken9f8e7d6c5b4a",
                "icstoken1234567890abcdef",
                "spatoken0f1e2d3c4b5a",
                "unroutedtoken5a4b3c2d1e");

        given().when().get("/api/espace-animateur/" + tokens.get(0));
        given().when().get("/api/espace-animateur/" + tokens.get(1) + "/demandes");
        given().when().get("/api/abonnements/" + tokens.get(2) + "/planning.ics");
        given().when().get("/animateur/" + tokens.get(3));
        given().when().get("/api/nothing-here/" + tokens.get(4));

        String body = awaitScrapeContaining("uri=\"/abonnements/{token}/planning.ics\"");

        assertThat(body)
                .contains("uri=\"/espace-animateur/{jeton}\"")
                .contains("uri=\"/espace-animateur/{jeton}/demandes\"")
                .contains("uri=\"/{frontend}\"");
        for (String token : tokens) {
            assertThat(body).doesNotContain(token);
        }
        Set<String> labelNames = new TreeSet<>();
        Matcher label = LABEL.matcher(body);
        while (label.find()) {
            labelNames.add(label.group(1));
            if (label.group(1).equals("uri")) {
                assertThat(label.group(2)).matches(SAFE_URI);
            }
        }
        assertThat(labelNames).doesNotContainAnyElementsOf(FORBIDDEN_LABELS);
    }

    private String scrape() {
        return given().when()
                .get(managementMetrics)
                .then()
                .statusCode(200)
                .extract()
                .asString();
    }

    /** A request is recorded once its response has ended, a moment after the client read it. */
    private String awaitScrapeContaining(String expected) {
        return await().atMost(Duration.ofSeconds(2))
                .pollInterval(Duration.ofMillis(50))
                .until(this::scrape, body -> body.contains(expected));
    }
}
