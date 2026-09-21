package dev.sylvain.planning.mcp;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The rate limit on the MCP transport ({@code McpRateLimiter}): past the
 * ceiling, one address gets {@code 429} and a {@code Retry-After}, whether or
 * not it presents the right key.
 *
 * <p>The whole class shares one counter — the limiter is a singleton, the suite
 * calls from a single address, and nothing resets it between methods — so it is
 * deliberately <b>one</b> test walking the ceiling from below to above it. Split
 * in two, the second method would inherit the first one's count and pass or fail
 * depending on the order JUnit picked.</p>
 */
@QuarkusTest
@TestProfile(McpRateLimitTest.Profil.class)
class McpRateLimitTest {

    private static final int PLAFOND = 3;

    /**
     * A window long enough that no test can outlive it, and a ceiling low enough
     * to reach by hand. The production defaults (120 requests a minute) would
     * need two minutes of requests to prove the same thing.
     */
    public static class Profil implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "planning.mcp.rate-limit.max-requests",
                    String.valueOf(PLAFOND),
                    "planning.mcp.rate-limit.window",
                    "PT10M");
        }
    }

    @Test
    void plafonneLesRequetesDUneMemeAdresse() {
        for (int i = 0; i < PLAFOND; i++) {
            given().header("X-MCP-Api-Key", "test-mcp-key")
                    .when()
                    .post("/mcp")
                    .then()
                    .statusCode(not(429));
        }

        // Past the ceiling the right key buys nothing: the filter refuses
        // before authentication is ever consulted.
        given().header("X-MCP-Api-Key", "test-mcp-key")
                .when()
                .post("/mcp")
                .then()
                .statusCode(429)
                .header("Retry-After", matchesPattern("\\d+"));

        // And a wrong key meets that same 429 rather than a 401, which is
        // what bounds trying keys as much as using the right one.
        given().header("X-MCP-Api-Key", "mauvaise-cle")
                .when()
                .post("/mcp")
                .then()
                .statusCode(429);

        // The rest of the application is untouched: the MCP page of the
        // interface lives under /api/mcp and keeps the admin policy.
        given().when().get("/api/mcp/statut").then().statusCode(not(429));
    }
}
