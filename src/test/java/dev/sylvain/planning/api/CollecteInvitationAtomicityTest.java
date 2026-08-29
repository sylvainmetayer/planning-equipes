package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;

/**
 * Opening the collection window on a deployment with no {@code PUBLIC_URL} — a
 * supported one, the property is {@code Optional} and nothing else requires it.
 *
 * <p>The window used to be saved <b>before</b> the invitation was attempted, so
 * ticking « Prévenir les animateurs » there answered 400 on a collection that
 * was, by then, open and already accepting declarations. Two half-truths at
 * once: an error message about an operation that partly succeeded, and a public
 * write route opened by an admin who believes it is closed.</p>
 *
 * <p>What this test pins is not the message but the <b>atomicity</b>: refused
 * means nothing moved.</p>
 */
@QuarkusTest
@TestProfile(CollecteInvitationAtomicityTest.Profil.class)
class CollecteInvitationAtomicityTest {

    public static class Profil implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("planning.public-url", "");
        }
    }

    @AfterEach
    void cleanUp() {
        given().contentType(ContentType.JSON)
                .body("{\"collecteOuverte\":false}")
                .when().put("/api/disponibilites/configuration")
                .then().statusCode(200);
    }

    @Test
    void uneInvitationImpossibleNOuvrePasLaCollecte() {
        given().contentType(ContentType.JSON)
                .body("{\"collecteOuverte\":true,\"prevenirAnimateurs\":true}")
                .when().put("/api/disponibilites/configuration")
                .then()
                .statusCode(400);

        // The refusal has to mean nothing happened: an admin reading « Erreur »
        // must not be leaving a public write route open behind them.
        given().when().get("/api/disponibilites/configuration")
                .then()
                .statusCode(200)
                .body("collecteOuverte", is(false));
    }

    @Test
    void sansInvitationLOuvertureMarcheQuandMeme() {
        // The missing URL only bites what actually needs a link. A deployment
        // without one still collects declarations; it just cannot mail the way in.
        given().contentType(ContentType.JSON)
                .body("{\"collecteOuverte\":true,\"prevenirAnimateurs\":false}")
                .when().put("/api/disponibilites/configuration")
                .then()
                .statusCode(200)
                .body("collecteOuverte", is(true))
                .body("invitation", is((Object) null));
    }
}
