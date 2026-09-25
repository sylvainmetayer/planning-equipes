package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.edition.EditionRepository;
import dev.sylvain.planning.service.mural.AffichageMuralLinkRequest;
import dev.sylvain.planning.service.mural.AffichageMuralService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The perimeter of the wall display token, under the real admin policy (the
 * {@code %test} profile opens the whole API, which would prove nothing here).
 *
 * <p>Two halves, and the second is the one ADR 0053 is about: the screen opens
 * without a session, <b>and</b> its token opens nothing else — no admin route,
 * no espace, no calendar feed, no other path under its own prefix.</p>
 *
 * <p>Both ceilings are lowered so the last tests can reach them; the others stay
 * well under them, and the order makes sure they run first.</p>
 */
@QuarkusTest
@TestProfile(AffichageMuralSecurityTest.Profil.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AffichageMuralSecurityTest {

    /** Refused reads per address and per hour: the tests before the last ones stay under it. */
    private static final int MAX_REFUSED = 25;

    /** Reads per valid link and per hour: every other test reads a token of its own, once or twice. */
    private static final int MAX_READS_PER_LINK = 10;

    public static class Profil implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.http.auth.permission.admin-api.policy",
                    "authenticated",
                    "planning.affichage-mural.max-refused",
                    String.valueOf(MAX_REFUSED),
                    "planning.affichage-mural.max-reads-per-link",
                    String.valueOf(MAX_READS_PER_LINK),
                    "planning.affichage-mural.window",
                    "PT1H");
        }
    }

    @Inject
    AffichageMuralService service;

    @Inject
    EditionContext editionContext;

    @Test
    @Order(1)
    void theScreenOpensWithoutASession() {
        String token = newToken();

        given().when().get("/api/mural/" + token).then().statusCode(200);
        given().when().get("/api/mural/jeton-inconnu").then().statusCode(404);
    }

    @Test
    @Order(2)
    void managingTheLinksNeedsTheAdminSession() {
        given().when().get("/api/affichage-mural").then().statusCode(401);
        given().contentType("application/json")
                .body("{\"libelle\":\"TV\"}")
                .when()
                .post("/api/affichage-mural")
                .then()
                .statusCode(401);
        given().when().delete("/api/affichage-mural/1").then().statusCode(401);
    }

    /**
     * The token opens this one read and nothing else: wherever else it is put
     * — in the path of the other public routes, in a sub-path of its own
     * prefix, as a header or a query parameter on an admin route — it is
     * refused, and never by a 200.
     */
    @Test
    @Order(3)
    void theTokenOpensNoOtherEndpoint() {
        String token = newToken();

        given().when().get("/api/espace-animateur/" + token).then().statusCode(404);
        given().when().get("/api/abonnements/" + token + "/planning.ics").then().statusCode(404);
        given().when().get("/api/mural/" + token + "/planning").then().statusCode(404);
        for (String admin : List.of("/api/animateurs", "/api/planning/persiste", "/api/affichage-mural")) {
            given().header("Authorization", "Bearer " + token)
                    .queryParam("token", token)
                    .when()
                    .get(admin)
                    .then()
                    .statusCode(401);
        }
    }

    /** The exception is the prefix, not a neighbour that happens to start the same way. */
    @Test
    @Order(4)
    void theExceptionStopsAtItsPrefix() {
        given().when().get("/api/muraux").then().statusCode(401);
        given().when().get("/api/affichage-mural/qr-code").then().statusCode(401);
    }

    @Test
    @Order(5)
    void aRevokedTokenIsRefused() {
        var created = editionContext.executeIn(
                EditionRepository.EDITION_DEFAUT_ID,
                () -> service.create(new AffichageMuralLinkRequest("Révoqué", false, List.of())));
        editionContext.executeIn(
                EditionRepository.EDITION_DEFAUT_ID,
                () -> service.revoke(created.link().id()));

        given().when().get("/api/mural/" + created.token()).then().statusCode(404);
    }

    /**
     * Last: it spends the per-address ceiling. Only refused reads count, and a
     * token already served goes through a locked address — behind an undeclared
     * proxy every request carries the same address, and junk from anywhere must
     * not blank a screen that was reading fine.
     */
    @Test
    @Order(99)
    void aJunkFloodLocksUnknownTokensOutButNeverAValidOne() {
        String screen = newToken();
        given().when().get("/api/mural/" + screen).then().statusCode(200);

        int status = 0;
        for (int i = 0; i < MAX_REFUSED + 1 && status != 429; i++) {
            status =
                    given().when().get("/api/mural/essai-" + i).then().extract().statusCode();
        }

        assertThat(status).isEqualTo(429);
        given().when()
                .get("/api/mural/essai-encore")
                .then()
                .statusCode(429)
                .header("Retry-After", org.hamcrest.Matchers.notNullValue());
        for (int i = 0; i < 3; i++) {
            given().when().get("/api/mural/" + screen).then().statusCode(200);
        }
    }

    /** A valid link is bounded too, generously: a leaked address cannot draw at the speed of the network. */
    @Test
    @Order(100)
    void oneLinkIsBoundedByItsOwnCeiling() {
        String token = newToken();

        int status = 0;
        for (int i = 0; i < MAX_READS_PER_LINK + 2 && status != 429; i++) {
            status = given().when().get("/api/mural/" + token).then().extract().statusCode();
        }

        assertThat(status).isEqualTo(429);
    }

    private String newToken() {
        return editionContext
                .executeIn(
                        EditionRepository.EDITION_DEFAUT_ID,
                        () -> service.create(new AffichageMuralLinkRequest("TV", false, List.of())))
                .token();
    }
}
