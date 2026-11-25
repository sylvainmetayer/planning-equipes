package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The grouped variables reach the running application: the config interceptor
 * is registered in a real boot, and the two public endpoints answer from them.
 * The unit test of {@code GroupedVariables} cannot prove the registration.
 */
@QuarkusTest
@TestProfile(GroupedVariablesResourceTest.Profile.class)
class GroupedVariablesResourceTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "BRANDING_IMAGES", "logo=https://exemple.test/logo.png",
                    "BRANDING_COLORS", "accent=#8b1d3f",
                    "LEGAL_MENTIONS", "editeur=Association Ludique;contact=contact@exemple.invalid",
                    "LEGAL_HEBERGEUR", "Hébergeur Fictif SAS");
        }
    }

    @Test
    void groupedBrandingVariablesFeedTheBrandingEndpoint() {
        given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/branding")
                .then()
                .statusCode(200)
                .body("logoUrl", equalTo("https://exemple.test/logo.png"))
                .body("accentColor", equalTo("#8b1d3f"));
    }

    @Test
    void groupedAndOldLegalVariablesFeedTheNoticeEndpoint() {
        given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/mentions-legales")
                .then()
                .statusCode(200)
                .body("editeur", equalTo("Association Ludique"))
                .body("contact", equalTo("contact@exemple.invalid"))
                .body("hebergeur", equalTo("Hébergeur Fictif SAS"));
    }
}
