package dev.sylvain.planning.mcp;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What the MCP rate limit counts against when the deployment sits behind a
 * declared reverse proxy: the right-most {@code X-Forwarded-For} entry that is
 * not itself a declared proxy.
 *
 * <p>The forged-header case is the one worth pinning. A proxy <b>appends</b> its
 * entry, so the leftmost element is whatever the client wrote: reading from the
 * left — which is also what {@code remoteAddress()} does under
 * {@code proxy-address-forwarding} — would hand a fresh counter to anyone
 * prepending a new address per request, and the ceiling would bound nothing at
 * all. That is the bug this very reading fixed in the login lock
 * ({@code ClientAddress}), and the same header is read by the same code here.</p>
 *
 * <p>One test again, for the reason {@link McpRateLimitTest} gives: the counters
 * live in a singleton and nothing resets them between methods.</p>
 */
@QuarkusTest
@TestProfile(McpRateLimitProxyTest.Profil.class)
class McpRateLimitProxyTest {

    private static final int PLAFOND = 2;

    /** The suite calls from 127.0.0.1, so that is the proxy to declare. */
    public static class Profil implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "planning.mcp.rate-limit.max-requests", String.valueOf(PLAFOND),
                    "planning.mcp.rate-limit.window", "PT10M",
                    "planning.mcp.rate-limit.trusted-proxies", "127.0.0.1");
        }
    }

    @Test
    void compteLAdresseTransmiseParLeProxy() {
        for (int i = 0; i < PLAFOND; i++) {
            appel("203.0.113.1").then().statusCode(not(429));
        }
        appel("203.0.113.1").then().statusCode(429);

        // Another caller behind the same proxy has its own counter: one
        // address reaching the ceiling must not shut the endpoint for all.
        appel("203.0.113.2").then().statusCode(not(429));

        // The proxy's own entry, appended to the right, is skipped rather than
        // counted — otherwise every visitor would share one counter.
        appel("203.0.113.1, 127.0.0.1").then().statusCode(429);

        // And an address the client prepends buys nothing: the right-most
        // non-proxy entry is still the one the trusted hop observed.
        appel("198.51.100.9, 203.0.113.1").then().statusCode(429);
    }

    private static io.restassured.response.Response appel(String forwardedFor) {
        return given().header("X-MCP-Api-Key", "test-mcp-key")
                .header("X-Forwarded-For", forwardedFor)
                .when()
                .post("/mcp");
    }
}
