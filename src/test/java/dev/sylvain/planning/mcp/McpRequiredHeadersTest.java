package dev.sylvain.planning.mcp;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Deployment behind an access proxy (Pangolin): on top of the shared API key,
 * {@code planning.mcp.required-headers} can demand the proxy's own token headers,
 * so the origin stays protected even when it is reachable without going
 * through the proxy. The profile below mimics Pangolin's default
 * {@code P-Access-Token-Id}/{@code P-Access-Token} pair.
 */
@QuarkusTest
@TestProfile(McpRequiredHeadersTest.Profil.class)
class McpRequiredHeadersTest {

    public static class Profil implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("planning.mcp.required-headers", "P-Access-Token-Id=id-42,P-Access-Token=jeton-secret");
        }
    }

    @Test
    void refuseLaBonneCleSansLesEnTetesDuProxy() {
        given().header("X-MCP-Api-Key", "test-mcp-key")
                .when()
                .post("/mcp")
                .then()
                .statusCode(401);
    }

    @Test
    void refuseUnEnTeteDuProxyIncorrect() {
        given().header("X-MCP-Api-Key", "test-mcp-key")
                .header("P-Access-Token-Id", "id-42")
                .header("P-Access-Token", "mauvais-jeton")
                .when()
                .post("/mcp")
                .then()
                .statusCode(401);
    }

    @Test
    void refuseLesEnTetesDuProxySansLaCleApi() {
        given().header("P-Access-Token-Id", "id-42")
                .header("P-Access-Token", "jeton-secret")
                .when()
                .post("/mcp")
                .then()
                .statusCode(401);
    }

    @Test
    void laisseTraverserAvecLaCleEtTousLesEnTetes() {
        given().header("X-MCP-Api-Key", "test-mcp-key")
                .header("P-Access-Token-Id", "id-42")
                .header("P-Access-Token", "jeton-secret")
                .when()
                .post("/mcp")
                .then()
                .statusCode(not(401));
    }
}
