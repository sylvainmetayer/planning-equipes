package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;

/**
 * Lockout of the admin form login ({@link LimiteurConnexionsAdmin}). The
 * account is unique and has no second factor: without that lock, a single pair
 * of credentials can be attacked at the speed of the network.
 *
 * <p>The profile lowers the ceiling to two failures. The counter being kept per
 * address, every test announces its own through {@code X-Forwarded-For} rather
 * than sharing the {@code 127.0.0.1} of all the others — which is also what a
 * real deployment behind a reverse proxy does.</p>
 */
@QuarkusTest
@TestProfile(LimiteConnexionsAdminTest.Profil.class)
class LimiteConnexionsAdminTest {

    public static class Profil implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("planning.auth.connexion.max-echecs", "2",
                    "planning.auth.connexion.duree-blocage", "PT15M");
        }
    }

    /** The development default of {@code ADMIN_PASSWORD} — this is not a secret. */
    private static final String MOT_DE_PASSE_DEV = "admin";

    @Test
    void auDelaDeDeuxEchecsLAdresseEstVerrouillee() {
        String adresse = "203.0.113.10";
        connexion(adresse, "mauvais").then().statusCode(anyOf(is(401), is(302)));
        connexion(adresse, "mauvais").then().statusCode(anyOf(is(401), is(302)));

        // The lock holds even against the right password: that is what stops an
        // online attack from simply waiting for its turn.
        connexion(adresse, MOT_DE_PASSE_DEV).then()
                .statusCode(429)
                .header("Retry-After", notNullValue())
                .body("message", containsString("Trop de tentatives"));

        // And it does not spill over onto the other visitors.
        String cookie = connexion("203.0.113.11", MOT_DE_PASSE_DEV).then()
                .statusCode(anyOf(is(302), is(200)))
                .extract().cookie("planning-session");
        assertThat(cookie).isNotBlank();
    }

    /**
     * A successful login clears the counter: otherwise two typos a week apart
     * would end up locking the administrator out.
     */
    @Test
    void uneConnexionReussieEffaceLesEchecsPrecedents() {
        String adresse = "203.0.113.20";
        connexion(adresse, "mauvais").then().statusCode(anyOf(is(401), is(302)));
        connexion(adresse, MOT_DE_PASSE_DEV).then().statusCode(anyOf(is(302), is(200)));

        connexion(adresse, "mauvais").then().statusCode(anyOf(is(401), is(302)));
        // Without that clearing, this second failure would be the second of a
        // run and the next attempt would answer 429.
        String cookie = connexion(adresse, MOT_DE_PASSE_DEV).then()
                .statusCode(anyOf(is(302), is(200)))
                .extract().cookie("planning-session");
        assertThat(cookie).isNotBlank();
    }

    private static Response connexion(String adresse, String motDePasse) {
        return given()
                .contentType("application/x-www-form-urlencoded")
                .header("X-Forwarded-For", adresse)
                .formParam("j_username", "admin")
                .formParam("j_password", motDePasse)
                .redirects().follow(false)
                .when().post("/j_security_check");
    }
}
