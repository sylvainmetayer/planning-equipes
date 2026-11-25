package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import dev.sylvain.planning.service.compte.SessionOpenings;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The break-glass form session, end to end: the login notes the instant the
 * session opened, and the MCP key is revealed on it only while it is recent.
 */
@QuarkusTest
@TestProfile(McpKeyFormSignInTest.Profil.class)
class McpKeyFormSignInTest {

    public static class Profil implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.http.auth.permission.admin-api.policy", "role-gestion",
                    "quarkus.http.auth.permission.api-docs.policy", "role-admin");
        }
    }

    @Inject
    SessionOpenings openings;

    private Response login() {
        return given().header("X-Edition-Id", "E1")
                .contentType("application/x-www-form-urlencoded")
                .formParam("j_username", "admin")
                .formParam("j_password", "admin")
                .redirects()
                .follow(false)
                .post("/j_security_check");
    }

    @Test
    void theLoginNotesTheInstantAndTheKeyIsRevealedOnIt() {
        String session = login().cookie("planning-session");
        assertThat(session).isNotBlank();
        assertThat(openings.openedAt(session))
                .hasValueSatisfying(at -> assertThat(at)
                        .isBetween(Instant.now().minusSeconds(30), Instant.now().plusSeconds(5)));

        given().header("X-Edition-Id", "E1")
                .cookie("planning-session", session)
                .contentType("application/json")
                .post("/api/mcp/cle")
                .then()
                .statusCode(200)
                .body("cle", equalTo("test-mcp-key"));
    }

    @Test
    void anOldSessionIsAskedToSignInAgain() {
        String session = login().cookie("planning-session");
        openings.record(session, Instant.now().minusSeconds(11 * 60));

        given().header("X-Edition-Id", "E1")
                .cookie("planning-session", session)
                .contentType("application/json")
                .post("/api/mcp/cle")
                .then()
                .statusCode(409)
                .body("code", org.hamcrest.Matchers.startsWith("RECONNEXION_REQUISE_"))
                .body("mode", equalTo("BREAK_GLASS"));
    }

    @Test
    void aWrongPasswordNotesNothing() {
        Response refused = given().header("X-Edition-Id", "E1")
                .contentType("application/x-www-form-urlencoded")
                .formParam("j_username", "admin")
                .formParam("j_password", "mauvais")
                .redirects()
                .follow(false)
                .post("/j_security_check");

        assertThat(refused.cookie("planning-session")).isNull();
    }
}
