package dev.sylvain.planning.mcp;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The lockout on a run of wrong API keys ({@code McpRateLimiter}): past the
 * ceiling, the address gets {@code 429} instead of {@code 401}, and the right
 * key no longer opens anything either.
 *
 * <p>Three things matter as much as the ceiling itself, and each has its step
 * below: a request carrying <b>no</b> key counts for nothing, a request that
 * <b>authenticates</b> clears the run, and the ceiling is reached by the
 * failure <em>after</em> it, not by the one that reaches the count.</p>
 *
 * <p>One test again, for the reason {@link McpRateLimitTest} gives: the
 * counters live in a singleton, the suite calls from a single address, and
 * nothing resets them between methods. The rate ceiling is left at the {@code
 * %test} value (100 000) so that only the lockout can answer 429 here.</p>
 */
@QuarkusTest
@TestProfile(McpKeyLockoutTest.Profile.class)
class McpKeyLockoutTest {

    private static final int CEILING = 3;

    /** Low enough to reach by hand, and a duration no test can outlive. */
    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "planning.mcp.lockout.max-failures",
                    String.valueOf(CEILING),
                    "planning.mcp.lockout.duration",
                    "PT10M");
        }
    }

    @Test
    void locksOutARunOfRefusedKeys() {
        // A request carrying no key at all is refused, and counts for nothing:
        // a client not yet configured probes before it is set up, and locking
        // it out for that would punish the one case that is not an attack.
        for (int i = 0; i < CEILING * 2; i++) {
            given().when().post("/mcp").then().statusCode(401);
        }

        given().header("X-MCP-Api-Key", "mauvaise-cle")
                .when()
                .post("/mcp")
                .then()
                .statusCode(401);
        given().header("X-MCP-Api-Key", "mauvaise-cle")
                .when()
                .post("/mcp")
                .then()
                .statusCode(401);

        // Authenticating clears the run: a header corrected on the next try
        // costs nothing.
        given().header("X-MCP-Api-Key", "test-mcp-key")
                .when()
                .post("/mcp")
                .then()
                .statusCode(not(401));

        // So these two are the first and second failure again, not the third
        // and fourth — neither is refused.
        wrongKey().then().statusCode(401);
        wrongKey().then().statusCode(401);

        // The third reaches the ceiling and still gets its own 401: what the
        // lockout refuses is the attempt AFTER the count is reached.
        wrongKey().then().statusCode(401);

        wrongKey().then().statusCode(429).header("Retry-After", matchesPattern("\\d+"));

        // And the right key is refused just the same, which is the whole point:
        // waiting for one's turn must not be enough.
        given().header("X-MCP-Api-Key", "test-mcp-key")
                .when()
                .post("/mcp")
                .then()
                .statusCode(429);
    }

    private static Response wrongKey() {
        return given().header("X-MCP-Api-Key", "mauvaise-cle").when().post("/mcp");
    }
}
