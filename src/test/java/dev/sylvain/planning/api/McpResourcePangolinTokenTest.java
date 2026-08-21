package dev.sylvain.planning.api;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * When {@code planning.mcp.pangolin.access-token-id}/{@code -access-token} are
 * set server-side, revealing the API key (see {@link McpResourceTest})
 * reveals the Pangolin access-proxy token alongside it, under the same
 * admin-password gate — see {@code mcp-page.ts}.
 */
@QuarkusTest
@TestProfile(McpResourcePangolinTokenTest.Profil.class)
class McpResourcePangolinTokenTest {

    public static class Profil implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "planning.mcp.pangolin.access-token-id", "id-42",
                    "planning.mcp.pangolin.access-token", "jeton-secret");
        }
    }

    @Test
    void reveleLeJetonPangolinAvecLaCle() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"motDePasse\":\"admin\"}")
                .when().post("/api/mcp/cle")
                .then()
                .statusCode(200)
                .body("cle", equalTo("test-mcp-key"))
                .body("pangolinAccessTokenId", equalTo("id-42"))
                .body("pangolinAccessToken", equalTo("jeton-secret"));
    }
}
