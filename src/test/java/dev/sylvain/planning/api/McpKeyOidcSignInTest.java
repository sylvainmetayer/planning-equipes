package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import dev.sylvain.planning.OidcTests;
import dev.sylvain.planning.service.compte.Compte;
import dev.sylvain.planning.service.compte.CompteService;
import dev.sylvain.planning.service.compte.RoleHabilitation;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** An administrator signed in through the provider: the key follows the {@code auth_time} of the ID token. */
@QuarkusTest
@TestProfile(OidcTests.Profil.class)
class McpKeyOidcSignInTest {

    @Inject
    CompteService comptes;

    private String admin(Instant authTime) {
        String email = "mcp-" + UUID.randomUUID() + "@example.org";
        Compte compte = comptes.create(email, null);
        comptes.grant(compte.id(), RoleHabilitation.ADMIN, null, null);
        return OidcTests.tokenAuthenticatedAt("s-" + UUID.randomUUID(), email, authTime);
    }

    private Response reveal(String bearer) {
        return given().header("X-Edition-Id", "E1")
                .header("Authorization", bearer)
                .contentType("application/json")
                .post("/api/mcp/cle");
    }

    @Test
    void aRecentAuthTimeRevealsTheKey() {
        reveal(admin(Instant.now().minusSeconds(60))).then().statusCode(200).body("cle", equalTo("test-mcp-key"));
    }

    @Test
    void anOldAuthTimeAsksToSignInAgainAtTheProvider() {
        reveal(admin(Instant.now().minusSeconds(11 * 60)))
                .then()
                .statusCode(409)
                .body("code", org.hamcrest.Matchers.startsWith("RECONNEXION_REQUISE_"))
                .body("mode", equalTo("OIDC"));
    }

    @Test
    void aTokenWithoutAuthTimeIsNotRecent() {
        reveal(admin(null)).then().statusCode(409).body("mode", equalTo("OIDC"));
    }

    @Test
    void theReauthenticationRouteDropsTheSessionAndComesBackThroughTheLoginRoute() {
        Response response = given().redirects()
                .follow(false)
                .cookie("q_session", "x")
                .cookie("q_session_at", "y")
                .queryParam("retour", "/parametres?onglet=mcp")
                .get("/api/auth/oidc/reconnexion");

        assertThat(response.statusCode()).isIn(302, 303);
        assertThat(response.header("Location"))
                .isEqualTo("/api/auth/oidc/login?reauth=1&retour=%2Fparametres%3Fonglet%3Dmcp");
        assertThat(response.headers().getValues("Set-Cookie"))
                .anyMatch(c -> c.startsWith("q_session=;") && c.contains("Max-Age=0"))
                .anyMatch(c -> c.startsWith("q_session_at=;") && c.contains("Max-Age=0"));
    }

    @Test
    void theReturnPathStaysInsideTheApplication() {
        assertThat(AuthResource.safeReturn("https://autre.example/x")).isEqualTo("/");
        assertThat(AuthResource.safeReturn("//autre.example")).isEqualTo("/");
        assertThat(AuthResource.safeReturn(null)).isEqualTo("/");
        assertThat(AuthResource.safeReturn("/parametres")).isEqualTo("/parametres");
    }

    @Test
    void theProviderIsAskedToAuthenticateAgainOnlyOnAReauthentication() {
        String reauth = given().redirects()
                .follow(false)
                .queryParam("reauth", "1")
                .get("/api/auth/oidc/login")
                .then()
                .statusCode(302)
                .extract()
                .header("Location");
        String plain = given().redirects()
                .follow(false)
                .get("/api/auth/oidc/login")
                .then()
                .statusCode(302)
                .extract()
                .header("Location");

        assertThat(reauth).contains("prompt=login").contains("max_age=0");
        assertThat(plain).doesNotContain("prompt=login").doesNotContain("max_age");
    }
}
