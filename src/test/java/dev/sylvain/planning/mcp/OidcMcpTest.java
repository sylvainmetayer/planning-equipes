package dev.sylvain.planning.mcp;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import dev.sylvain.planning.OidcJetons;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import java.net.URI;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * {@code /mcp} as an OAuth 2.1 resource server, which is what the MCP
 * specification asks for — and what the API key alone could never be, being a
 * secret with no expiry, no revocation and no audience.
 *
 * <p>Run against the in-process OIDC server of {@code quarkus-test-oidc-server}
 * ({@code OidcTestServer}) rather than a Keycloak container: the whole backend suite must stay runnable
 * without Docker. What that costs is the realm itself — its roles, its flows,
 * its second factor — which the Playwright suite exercises against the real
 * thing ({@code e2e/authentification-keycloak.spec.ts}). What it buys is the
 * part that actually lives in this repository: the tenant routing, the claim
 * path, the audience check and the challenge.</p>
 */
@QuarkusTest
class OidcMcpTest {

    /** {@code quarkus.oidc.mcptransport.token.audience}'s default. */
    static final String AUDIENCE = "planning-mcp";

    /**
     * The hard point of the whole OAuth2 switch: the API-key mechanism has
     * priority 2000 and therefore wins every challenge, so left alone it
     * answers a bare 401 to {@code /mcp} — and a bare 401 tells an MCP client
     * nothing. The client discovers its authorization server from
     * {@code WWW-Authenticate: Bearer resource_metadata="…"} (RFC 9728); without
     * that header, an OAuth2 setup can be entirely correct and still be
     * unusable by every client in existence.
     */
    @Test
    void leChallengeDeMcpDesigneLeServeurDAutorisation() {
        given().when()
                .post("/mcp")
                .then()
                .statusCode(401)
                .header("WWW-Authenticate", containsString("resource_metadata"));
    }

    /**
     * The discovery chain, followed the way a client follows it: read the
     * metadata URL out of the challenge rather than assuming where Quarkus
     * publishes it, then fetch it. A test that hardcoded the path would keep
     * passing if the challenge ever pointed somewhere else — which is the only
     * failure that matters here, since the challenge is all a client has.
     */
    @Test
    void lesMetadonneesDeRessourceSontPubliquesEtDesignentLAuthorizationServer() {
        String challenge =
                given().when().post("/mcp").then().statusCode(401).extract().header("WWW-Authenticate");

        Matcher url = Pattern.compile("resource_metadata=\"([^\"]+)\"").matcher(challenge);
        assertThat(url.find()).as("le challenge porte une URL de métadonnées").isTrue();

        // Public, and naming the authorization server: those two are what a
        // client needs from this document. Asserting neither made the whole
        // chain a no-op that only proved the fetch did not throw.
        //
        // Read as a string and parsed by hand because Quarkus serves this
        // document without a `Content-Type`, which leaves RestAssured with no
        // parser to pick — the body is JSON, the header does not say so. Not
        // this application's endpoint to fix, and not what this test is about.
        String corps = given().when()
                .get(URI.create(url.group(1)).getPath())
                .then()
                .statusCode(200)
                .extract()
                .asString();
        JsonPath metadonnees = JsonPath.from(corps);

        assertThat(metadonnees.getString("resource"))
                .as("la ressource protégée est nommée")
                .isNotBlank();
        assertThat(metadonnees.getList("authorization_servers"))
                .as("au moins un authorization server, sans quoi le client n'a nulle part où aller")
                .isNotEmpty();
    }

    @Test
    void unJetonPorteurDuRoleMcpEtDeLaBonneAudienceEstAccepte() {
        given().header("Authorization", "Bearer " + OidcJetons.jeton("assistant", List.of("mcp"), AUDIENCE))
                .when()
                .post("/mcp")
                .then()
                .statusCode(not(401));
    }

    /**
     * Audience validation is point 3 of what the MCP specification requires,
     * and the only one that stops a token obtained for a different client of
     * the same realm from opening the tools here.
     */
    @Test
    void unJetonEmisPourUneAutreAudienceEstRefuse() {
        given().header(
                        "Authorization",
                        "Bearer " + OidcJetons.jeton("assistant", List.of("mcp"), "une-autre-application"))
                .when()
                .post("/mcp")
                .then()
                .statusCode(401);
    }

    /**
     * An animateur signed into their espace holds a perfectly valid realm
     * token. Before the policy named roles, {@code authenticated} would have
     * handed them every MCP tool — reading the whole referential, starting the
     * solver, wiping the database.
     */
    @Test
    void unAnimateurAuthentifieNObtientPasLesOutilsMcp() {
        given().header("Authorization", "Bearer " + OidcJetons.jeton("marie", List.of("user"), AUDIENCE))
                .when()
                .post("/mcp")
                .then()
                .statusCode(403);
    }

    /**
     * Coexistence, not replacement: the key stays the simple road for a local
     * deployment, or for one behind an access proxy that eats the
     * {@code Authorization} header for its own account — a bearer token has no
     * fallback header, so such a deployment cannot use OAuth2 at all.
     */
    @Test
    void laCleApiContinueDeFonctionnerQuandOAuth2EstActif() {
        given().header("X-MCP-Api-Key", "test-mcp-key")
                .when()
                .post("/mcp")
                .then()
                .statusCode(not(401));
    }

    /** And a wrong key is still a wrong key, not a fallthrough to "anonymous". */
    @Test
    void uneMauvaiseCleResteRefusee() {
        given().header("X-MCP-Api-Key", "mauvaise-cle")
                .when()
                .post("/mcp")
                .then()
                .statusCode(401);
    }
}
