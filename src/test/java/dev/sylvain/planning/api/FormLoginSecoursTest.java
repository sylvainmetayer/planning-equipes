package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The break-glass door closed, as production ships it (ADR 0054): the
 * embedded account's password opens nothing, and the interface is told there
 * is no password form to offer.
 */
@QuarkusTest
@TestProfile(FormLoginSecoursTest.Profil.class)
class FormLoginSecoursTest {

    public static class Profil implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "planning.auth.secours.enabled", "false",
                    "quarkus.http.auth.permission.admin-api.policy", "role-admin");
        }
    }

    /** The dev default of {@code ADMIN_PASSWORD} — not a secret. */
    private static final String MOT_DE_PASSE_DEV = "admin";

    /**
     * 409 and not 401: the password may well be right, the door is shut. A 401
     * would send an operator hunting for a credential instead of reading their
     * configuration.
     */
    @Test
    void leFormulaireDeConnexionEstFerme() {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("j_username", "admin")
                .formParam("j_password", MOT_DE_PASSE_DEV)
                .redirects()
                .follow(false)
                .when()
                .post("/j_security_check")
                .then()
                .statusCode(409)
                .header("Set-Cookie", org.hamcrest.Matchers.nullValue())
                .body("message", containsString("ADMIN_SECOURS_ENABLED"));
    }

    /**
     * Quarkus logs in on any path ending with the post location: a prefix must
     * not walk around the closed door.
     */
    @Test
    void unPrefixeNeContournePasLaPorteFermee() {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("j_username", "admin")
                .formParam("j_password", MOT_DE_PASSE_DEV)
                .redirects()
                .follow(false)
                .when()
                .post("/api/j_security_check")
                .then()
                .statusCode(409)
                .header("Set-Cookie", org.hamcrest.Matchers.nullValue());
    }

    @Test
    void laConfigurationAnnonceKeycloakSansFormulaire() {
        given().when()
                .get("/api/config")
                .then()
                .statusCode(200)
                .body("authOidc", equalTo(true))
                .body("authSecours", equalTo(false));
    }

    @Test
    void lApiResteFermeeSansSession() {
        given().when().get("/api/animateurs").then().statusCode(401);
    }
}
