package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The MCP page's rotation: a key shown once, the old one cut or kept, the generated one not revealable. */
@QuarkusTest
class McpResourceRotationTest {

    @Inject
    DataSource dataSource;

    @Inject
    dev.sylvain.planning.service.mcp.McpKeyService keys;

    @AfterEach
    void restoreConfiguredKey() throws Exception {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement("DELETE FROM mcp_cle")) {
            ps.executeUpdate();
        }
        keys.invalidate();
    }

    private String rotate(int minutes) {
        return given().header("X-Edition-Id", "E1")
                .contentType(ContentType.JSON)
                .body("{\"motDePasse\":\"admin\",\"recouvrementMinutes\":" + minutes + "}")
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
                .contentType(ContentType.JSON)
                .body("{\"motDePasse\":\"admin\"}")
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
    void aWrongPasswordIsRefused() {
        given().header("X-Edition-Id", "E1")
                .contentType(ContentType.JSON)
                .body("{\"motDePasse\":\"mauvais\",\"recouvrementMinutes\":0}")
                .post("/api/mcp/cle/rotation")
                .then()
                .statusCode(401);
    }
}
