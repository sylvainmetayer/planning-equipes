package dev.sylvain.planning.mcp;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.not;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * The test profile configures {@code planning.mcp.api-key=test-mcp-key} (see
 * application.properties), so these cases exercise the "configured" branch
 * of {@link McpApiKeyAuthenticationMechanism}: requests under {@code /mcp}
 * must present a matching {@code X-MCP-Api-Key} header (or
 * {@code Authorization: Bearer ...}) or be rejected with 401 before ever
 * reaching the MCP transport.
 */
@QuarkusTest
class McpApiKeyAuthenticationMechanismTest {

    @Test
    void refuseSansEnTeteApiKey() {
        given()
                .when().post("/mcp")
                .then()
                .statusCode(401);
    }

    @Test
    void refuseAvecUneCleIncorrecte() {
        given()
                .header("X-MCP-Api-Key", "mauvaise-cle")
                .when().post("/mcp")
                .then()
                .statusCode(401);
    }

    @Test
    void laisseTraverserAvecLaBonneCle() {
        given()
                .header("X-MCP-Api-Key", "test-mcp-key")
                .when().post("/mcp")
                .then()
                // Does not check the semantics of the MCP protocol (that depends on the
                // streamable-http transport), only that authentication let the request
                // through to the MCP handler: no 401.
                .statusCode(not(401));
    }

    @Test
    void laisseTraverserAvecUnBearerToken() {
        given()
                .header("Authorization", "Bearer test-mcp-key")
                .when().post("/mcp")
                .then()
                .statusCode(not(401));
    }
}
