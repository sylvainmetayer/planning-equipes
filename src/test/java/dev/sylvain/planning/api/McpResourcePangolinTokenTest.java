package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import dev.sylvain.planning.service.compte.SessionOpenings;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * When {@code planning.mcp.pangolin.access-token-id}/{@code -access-token} are
 * set server-side, revealing the API key (see {@link McpResourceTest})
 * reveals the Pangolin access-proxy token alongside it, under the same
 * recent sign-in — see {@code mcp-page.ts}.
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

    @Inject
    SessionOpenings openings;

    private String session() {
        String session = "s-" + java.util.UUID.randomUUID();
        openings.record(session, Instant.now());
        return session;
    }

    @Test
    void reveleLeJetonPangolinAvecLaCle() {
        given().header("X-Edition-Id", "E1")
                .cookie("planning-session", session())
                .contentType(ContentType.JSON)
                .when()
                .post("/api/mcp/cle")
                .then()
                .statusCode(200)
                .body("cle", equalTo("test-mcp-key"))
                .body("pangolinAccessTokenId", equalTo("id-42"))
                .body("pangolinAccessToken", equalTo("jeton-secret"));
    }
}
