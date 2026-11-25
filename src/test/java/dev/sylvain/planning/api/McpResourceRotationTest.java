package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

import dev.sylvain.planning.service.compte.SessionOpenings;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** A recent sign-in: the MCP page's rotation, a key shown once, the old one cut or kept, the generated one not revealable. */
@QuarkusTest
class McpResourceRotationTest {

    @Inject
    DataSource dataSource;

    @Inject
    dev.sylvain.planning.service.mcp.McpKeyService keys;

    @Inject
    SessionOpenings openings;

    @AfterEach
    void restoreConfiguredKey() throws Exception {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement("DELETE FROM mcp_cle")) {
            ps.executeUpdate();
        }
        keys.invalidate();
    }

    private String session(long secondsAgo) {
        String session = "s-" + java.util.UUID.randomUUID();
        openings.record(session, Instant.now().minusSeconds(secondsAgo));
        return session;
    }

    private String rotate(int minutes) {
        return given().header("X-Edition-Id", "E1")
                .cookie("planning-session", session(0))
                .contentType(ContentType.JSON)
                .body("{\"recouvrementMinutes\":" + minutes + "}")
                .post("/api/mcp/cle/rotation")
                .then()
                .statusCode(200)
                .extract()
                .path("cle");
    }

    @Test
    void aRotationShowsTheKeyOnceAndTheNewKeyOpensTheTransport() {
        String cle = rotate(0);

        given().header("X-MCP-Api-Key", cle).post("/mcp").then().statusCode(not(equalTo(401)));
        given().header("X-MCP-Api-Key", "test-mcp-key").post("/mcp").then().statusCode(401);
        given().header("X-Edition-Id", "E1")
                .get("/api/mcp/statut")
                .then()
                .statusCode(200)
                .body("origine", equalTo("GENERATED"))
                .body("configuree", equalTo(true));
        given().header("X-Edition-Id", "E1")
                .cookie("planning-session", session(0))
                .contentType(ContentType.JSON)
                .post("/api/mcp/cle")
                .then()
                .statusCode(409);
    }

    @Test
    void anOverlapKeepsTheConfiguredKeyAlive() {
        rotate(30);

        given().header("X-MCP-Api-Key", "test-mcp-key").post("/mcp").then().statusCode(not(equalTo(401)));
    }

    @Test
    void anOldSignInGeneratesNothing() {
        given().header("X-Edition-Id", "E1")
                .cookie("planning-session", session(11 * 60))
                .contentType(ContentType.JSON)
                .body("{\"recouvrementMinutes\":0}")
                .post("/api/mcp/cle/rotation")
                .then()
                .statusCode(409)
                .body("code", org.hamcrest.Matchers.startsWith("RECONNEXION_REQUISE_"));
        given().header("X-MCP-Api-Key", "test-mcp-key").post("/mcp").then().statusCode(not(equalTo(401)));
    }
}
