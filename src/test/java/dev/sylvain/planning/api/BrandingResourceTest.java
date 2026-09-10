package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * Branding endpoint. Two things are being pinned down, and neither is the
 * colour of anything.
 *
 * <p>It must answer <b>without any credential</b>: the login page and the
 * espace animateur read it before anyone is authenticated, and an application
 * that could only tell its own name to logged-in users would greet every
 * visitor with a blank toolbar.</p>
 *
 * <p>And a deployment that configured nothing must get a working neutral
 * identity — a product name, and explicitly <em>no</em> logo — rather than the
 * mark of whichever customer the code was written for.</p>
 */
@QuarkusTest
class BrandingResourceTest {

    @Test
    void answersWithoutAuthenticationAndFallsBackToANeutralIdentity() {
        given().when()
                .get("/api/branding")
                .then()
                .statusCode(200)
                .body("productName", equalTo("Planning Équipes"))
                // Empty means "show no logo", not "show the default one": a
                // deployment must never inherit somebody else's mark.
                .body("logoUrl", equalTo(""))
                .body("organisation", equalTo(""))
                .body("accentColor", equalTo(""));
    }
}
