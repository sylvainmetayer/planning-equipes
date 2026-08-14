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
                // Ne vérifie pas la sémantique du protocole MCP (dépend du transport
                // streamable-http), seulement que l'authentification a laissé passer
                // la requête jusqu'au handler MCP : pas de 401.
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
