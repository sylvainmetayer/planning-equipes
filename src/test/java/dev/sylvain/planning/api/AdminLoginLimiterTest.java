package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Lockout of the admin form login ({@link AdminLoginLimiter}). The
 * account is unique and has no second factor: without that lock, a single pair
 * of credentials can be attacked at the speed of the network.
 *
 * <p>The profile lowers the ceiling to two failures. The counter being kept per
 * address, every test announces its own through {@code X-Forwarded-For} rather
 * than sharing the {@code 127.0.0.1} of all the others — which is also what a
 * real deployment behind a reverse proxy does.</p>
 */
@QuarkusTest
@TestProfile(AdminLoginLimiterTest.Profil.class)
class AdminLoginLimiterTest {

    public static class Profil implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "planning.auth.connexion.max-echecs",
                    "2",
                    "planning.auth.connexion.duree-blocage",
                    "PT15M",
                    // The test client connects from the loopback: declaring it
                    // as the proxy is what makes the announced address worth
                    // trusting, exactly as a deployment declares its own. Without
                    // this line the header is ignored — and that is the right
                    // default, see AdminLoginLimiterProxyNonFiableTest.
                    "planning.auth.connexion.proxys-fiables",
                    "127.0.0.1");
        }
    }

    /** The development default of {@code ADMIN_PASSWORD} — this is not a secret. */
    private static final String MOT_DE_PASSE_DEV = "admin";

    @Test
    void auDelaDeDeuxEchecsLAdresseEstVerrouillee() {
        String address = "203.0.113.10";
        login(address, "mauvais").then().statusCode(anyOf(is(401), is(302)));
        login(address, "mauvais").then().statusCode(anyOf(is(401), is(302)));

        // The lock holds even against the right password: that is what stops an
        // online attack from simply waiting for its turn.
        login(address, MOT_DE_PASSE_DEV)
                .then()
                .statusCode(429)
                .header("Retry-After", notNullValue())
                .body("message", containsString("Trop de tentatives"));

        // And it does not spill over onto the other visitors.
        String cookie = login("203.0.113.11", MOT_DE_PASSE_DEV)
                .then()
                .statusCode(anyOf(is(302), is(200)))
                .extract()
                .cookie("planning-session");
        assertThat(cookie).isNotBlank();
    }

    /**
     * A successful login clears the counter: otherwise two typos a week apart
     * would end up locking the administrator out.
     */
    @Test
    void uneConnexionReussieEffaceLesEchecsPrecedents() {
        String address = "203.0.113.20";
        login(address, "mauvais").then().statusCode(anyOf(is(401), is(302)));
        login(address, MOT_DE_PASSE_DEV).then().statusCode(anyOf(is(302), is(200)));

        login(address, "mauvais").then().statusCode(anyOf(is(401), is(302)));
        // Without that clearing, this second failure would be the second of a
        // run and the next attempt would answer 429.
        String cookie = login(address, MOT_DE_PASSE_DEV)
                .then()
                .statusCode(anyOf(is(302), is(200)))
                .extract()
                .cookie("planning-session");
        assertThat(cookie).isNotBlank();
    }

    private static Response login(String address, String password) {
        return given().contentType("application/x-www-form-urlencoded")
                .header("X-Forwarded-For", address)
                .formParam("j_username", "admin")
                .formParam("j_password", password)
                .redirects()
                .follow(false)
                .when()
                .post("/j_security_check");
    }
}
