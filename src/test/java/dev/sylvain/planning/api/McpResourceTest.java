package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

import dev.sylvain.planning.service.compte.SessionOpenings;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The MCP page's two server-side needs: knowing whether a key exists, and
 * exchanging the admin password for it.
 *
 * <p>Ordered, and the lock-out case runs last on purpose: the counter is
 * application-scoped state, so five deliberate failures leave the endpoint
 * blocked for five minutes. Nothing else in the suite calls {@code /api/mcp},
 * so the leak is contained — but the order is what keeps the two useful cases
 * from being collateral damage.</p>
 *
 * <p>Values come from the {@code %test} profile: {@code planning.mcp.api-key} is
 * {@code test-mcp-key} and the admin password falls back to {@code admin}.</p>
 */
@QuarkusTest
@TestMethodOrder(OrderAnnotation.class)
class McpResourceTest {

    @Test
    @Order(1)
    void leStatutDitQuUneCleEstConfigureeSansJamaisLaDonner() {
        given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/mcp/statut")
                .then()
                .statusCode(200)
                .body("configuree", is(true))
                .body("header", equalTo("X-MCP-Api-Key"))
                .body("$", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasKey("cle")));
    }

    /**
     * The page reads its prompts here rather than carrying its own copies —
     * one of those copies had drifted to a tool the application never exposed.
     * What matters on the wire is that every entry is complete: a description
     * to choose by, and a text to paste.
     */
    @Test
    @Order(1)
    void theServerPromptsAreServedInFull() {
        given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/mcp/prompts")
                .then()
                .statusCode(200)
                .body("size()", org.hamcrest.Matchers.greaterThan(0))
                .body(
                        "nom",
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.not(org.hamcrest.Matchers.emptyString())))
                .body(
                        "description",
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.not(org.hamcrest.Matchers.emptyString())))
                .body(
                        "texte",
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.not(org.hamcrest.Matchers.emptyString())))
                // First of the catalogue, so first of the event: starting the
                // edition, before its stands' hours and its grid.
                .body("[0].nom", equalTo("demarrer_une_nouvelle_edition"));
    }

    @Inject
    SessionOpenings openings;

    /** A session cookie whose login the application saw {@code ago} ago. */
    private String sessionOpened(Duration ago) {
        String session = "s-" + UUID.randomUUID();
        openings.record(session, Instant.now().minus(ago));
        return session;
    }

    @Test
    @Order(2)
    void aRecentSignInRevealsTheKey() {
        given().header("X-Edition-Id", "E1")
                .cookie("planning-session", sessionOpened(Duration.ofSeconds(30)))
                .contentType(ContentType.JSON)
                .when()
                .post("/api/mcp/cle")
                .then()
                .statusCode(200)
                .body("cle", equalTo("test-mcp-key"))
                .body("pangolinAccessTokenId", org.hamcrest.Matchers.nullValue())
                .body("pangolinAccessToken", org.hamcrest.Matchers.nullValue());
    }

    @Test
    @Order(3)
    void anOldSignInRevealsNothingAndSaysHowToSignInAgain() {
        given().header("X-Edition-Id", "E1")
                .cookie("planning-session", sessionOpened(Duration.ofMinutes(11)))
                .contentType(ContentType.JSON)
                .when()
                .post("/api/mcp/cle")
                .then()
                .statusCode(409)
                .body("code", org.hamcrest.Matchers.startsWith("RECONNEXION_REQUISE_"))
                .body("mode", equalTo("BREAK_GLASS"))
                .body("$", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasKey("cle")));
    }

    @Test
    @Order(4)
    void noCookieAndAForgedCookieAreRefused() {
        given().header("X-Edition-Id", "E1")
                .contentType(ContentType.JSON)
                .when()
                .post("/api/mcp/cle")
                .then()
                .statusCode(409)
                .body("code", org.hamcrest.Matchers.startsWith("RECONNEXION_REQUISE_"));
        // A session the application never saw log in (renewed, forgotten, invented) is not recent.
        given().header("X-Edition-Id", "E1")
                .cookie("planning-session", "s-" + UUID.randomUUID())
                .contentType(ContentType.JSON)
                .when()
                .post("/api/mcp/cle")
                .then()
                .statusCode(409);
    }
}
