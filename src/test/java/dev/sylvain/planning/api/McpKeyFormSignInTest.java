package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;

import dev.sylvain.planning.service.compte.SessionOpenings;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.time.Duration;
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
                    "quarkus.http.auth.permission.api-docs.policy", "role-admin",
                    // Reissued after a second instead of a minute: the renewal is what is under test.
                    "quarkus.http.auth.form.new-cookie-interval", "PT1S");
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
    void aReissuedCookieKeepsTheInstantItsSessionOpened() throws Exception {
        String session = login().cookie("planning-session");
        Instant opened = openings.openedAt(session).orElseThrow();
        Thread.sleep(1_500);

        String renewed = given().header("X-Edition-Id", "E1")
                .cookie("planning-session", session)
                .get("/api/auth/me")
                .cookie("planning-session");

        assertThat(renewed).isNotBlank().isNotEqualTo(session);
        await().atMost(Duration.ofSeconds(5))
                .until(() -> openings.openedAt(renewed).isPresent());
        assertThat(openings.openedAt(renewed)).contains(opened);
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
    void signingInAgainWithAnOldSessionNotesAFreshOpening() {
        String session = login().cookie("planning-session");
        openings.record(session, Instant.now().minusSeconds(11 * 60));

        String fresh = given().header("X-Edition-Id", "E1")
                .cookie("planning-session", session)
                .contentType("application/x-www-form-urlencoded")
                .formParam("j_username", "admin")
                .formParam("j_password", "admin")
                .redirects()
                .follow(false)
                .post("/j_security_check")
                .cookie("planning-session");

        assertThat(fresh).isNotBlank().isNotEqualTo(session);
        await().atMost(Duration.ofSeconds(5))
                .until(() -> openings.openedAt(fresh).isPresent());
        assertThat(openings.openedAt(fresh))
                .hasValueSatisfying(at -> assertThat(at).isAfter(Instant.now().minusSeconds(30)));
    }

    /**
     * Only a form post logs in: a GET on the login path with an old session is
     * answered a reissued cookie, which keeps the old opening, so the MCP key
     * still asks for a password.
     */
    @Test
    void aGetOnTheLoginPathKeepsTheOpeningOfAnOldSession() throws Exception {
        String session = login().cookie("planning-session");
        Instant old = Instant.now().minusSeconds(11 * 60);
        openings.record(session, old);
        Thread.sleep(1_500);

        String renewed = given().header("X-Edition-Id", "E1")
                .cookie("planning-session", session)
                .redirects()
                .follow(false)
                .get("/j_security_check")
                .cookie("planning-session");

        assertThat(renewed).isNotBlank().isNotEqualTo(session);
        await().atMost(Duration.ofSeconds(5))
                .until(() -> openings.openedAt(renewed).isPresent());
        assertThat(openings.openedAt(renewed)).contains(old);
        given().header("X-Edition-Id", "E1")
                .cookie("planning-session", renewed)
                .contentType("application/json")
                .post("/api/mcp/cle")
                .then()
                .statusCode(409);
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
