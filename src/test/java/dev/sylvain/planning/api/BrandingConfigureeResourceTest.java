package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A deployment that <b>did</b> configure its identity, which is the case the
 * neutral-default test cannot cover.
 *
 * <p>{@link BrandingResourceTest} proves a bare instance stays neutral. What is
 * proven here is the other half of the promise: that setting the variables
 * actually changes what the application answers, and that it changes it
 * everywhere the name travels — the endpoint the frontend reads before its own
 * bootstrap, and the SQL dump an operator downloads.</p>
 *
 * <p>A white-label product whose variables are read but never applied would
 * pass every neutral-default test ever written.</p>
 */
@QuarkusTest
@TestProfile(BrandingConfigureeResourceTest.Profil.class)
class BrandingConfigureeResourceTest {

    private static final String NOM = "Festival du Jeu";

    public static class Profil implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "planning.branding.product-name", NOM,
                    "planning.branding.organisation", "Association Ludique",
                    "planning.branding.logo-url", "https://exemple.test/logo.png",
                    "planning.branding.accent-color", "#8b1d3f");
        }
    }

    @Test
    void lEndpointPubliqueRendLidentiteConfigureeEtPasLeRepliNeutre() {
        given().when()
                .get("/api/branding")
                .then()
                .statusCode(200)
                .body("productName", equalTo(NOM))
                .body("organisation", equalTo("Association Ludique"))
                .body("logoUrl", equalTo("https://exemple.test/logo.png"))
                .body("accentColor", equalTo("#8b1d3f"));
    }

    @Test
    void lidentiteConfigureeResteLisibleSansAucunIdentifiant() {
        // The login page and the espace animateur read it before anyone is
        // authenticated: a brand reserved for logged-in users would greet every
        // visitor with an empty toolbar.
        given().when().get("/api/branding").then().statusCode(200).body("productName", equalTo(NOM));
    }

    @Test
    void theSqlDumpIsHeadedWithTheDeploymentName() {
        String dump = given().when()
                .get("/api/database/export")
                .then()
                .statusCode(200)
                .extract()
                .asString();

        // The name travels all the way to the files an operator downloads,
        // not only to the UI.
        assertThat(dump).startsWith("-- " + NOM + " database dump").doesNotContain("Planning Équipes database dump");
    }
}
