package dev.sylvain.planning.mcp;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

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

    @TestHTTPResource("/")
    URL racine;

    @Test
    void refuseSansEnTeteApiKey() {
        given().header("X-Edition-Id", "E1").when().post("/mcp").then().statusCode(401);
    }

    @Test
    void refuseAvecUneCleIncorrecte() {
        given().header("X-Edition-Id", "E1")
                .header("X-MCP-Api-Key", "mauvaise-cle")
                .when()
                .post("/mcp")
                .then()
                .statusCode(401);
    }

    @Test
    void laisseTraverserAvecLaBonneCle() {
        given().header("X-Edition-Id", "E1")
                .header("X-MCP-Api-Key", "test-mcp-key")
                .when()
                .post("/mcp")
                .then()
                // Does not check the semantics of the MCP protocol (that depends on the
                // streamable-http transport), only that authentication let the request
                // through to the MCP handler: no 401.
                .statusCode(not(401));
    }

    @Test
    void laisseTraverserAvecUnBearerToken() {
        given().header("X-Edition-Id", "E1")
                .header("Authorization", "Bearer test-mcp-key")
                .when()
                .post("/mcp")
                .then()
                .statusCode(not(401));
    }

    /**
     * The transport, and nothing that merely starts like it: the key is
     * matched on the path the permission check reads, normalized, exactly or
     * as a parent.
     */
    @Test
    void laCleNeVautQueSurLeTransport() {
        assertThat(McpApiKeyAuthenticationMechanism.isMcpTransport("/mcp")).isTrue();
        assertThat(McpApiKeyAuthenticationMechanism.isMcpTransport("/mcp/")).isTrue();
        assertThat(McpApiKeyAuthenticationMechanism.isMcpTransport("/mcp/sse")).isTrue();
        assertThat(McpApiKeyAuthenticationMechanism.isMcpTransport("/mcpx")).isFalse();
        assertThat(McpApiKeyAuthenticationMechanism.isMcpTransport("/mcp-console"))
                .isFalse();
        assertThat(McpApiKeyAuthenticationMechanism.isMcpTransport("/mcp..%2f..%2fapi"))
                .isFalse();
        assertThat(McpApiKeyAuthenticationMechanism.isMcpTransport("/api/mcp/cle"))
                .isFalse();
        assertThat(McpApiKeyAuthenticationMechanism.isMcpTransport(null)).isFalse();
    }

    /**
     * {@code /mcp/../api/auth/me} is routed to {@code /api/auth/me}: the key
     * must not authenticate it. Sent over a bare socket, since an HTTP client
     * would resolve the dot segment before the request leaves.
     */
    @Test
    void laCleNOuvrePasUnCheminQuiRemonteDuTransport() throws IOException {
        String reponse = brut("GET /mcp/../api/auth/me HTTP/1.1\r\n"
                + "Host: " + racine.getHost() + "\r\n"
                + "X-Edition-Id: E1\r\n"
                + "X-MCP-Api-Key: test-mcp-key\r\n"
                + "Connection: close\r\n\r\n");

        assertThat(reponse).startsWith("HTTP/1.1 200").contains("\"authentifie\":false");
    }

    private String brut(String requete) throws IOException {
        try (Socket socket = new Socket(racine.getHost(), racine.getPort())) {
            socket.setSoTimeout(10_000);
            OutputStream sortie = socket.getOutputStream();
            sortie.write(requete.getBytes(StandardCharsets.US_ASCII));
            sortie.flush();
            InputStream entree = socket.getInputStream();
            return new String(entree.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
