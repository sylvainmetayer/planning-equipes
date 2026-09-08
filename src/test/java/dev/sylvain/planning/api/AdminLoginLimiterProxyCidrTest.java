package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;

/**
 * A proxy declared as a <b>block</b> rather than a literal address.
 *
 * <p>{@link AdminLoginLimiterTest} declares {@code 127.0.0.1}; this one declares
 * {@code 127.0.0.0/8} and expects the same behaviour. It exists because the
 * block form is the one a containerised deployment actually uses — behind a
 * tunnel client that terminates locally, the peer is a container on a bridge
 * network whose address is handed out at attach time, while the network's
 * subnet is fixed at creation.</p>
 *
 * <p>What this adds over {@code TrustedProxiesTest} is the wiring: the parser
 * could be flawless and the block never reach the request path.</p>
 */
@QuarkusTest
@TestProfile(AdminLoginLimiterProxyCidrTest.Profil.class)
class AdminLoginLimiterProxyCidrTest {

    public static class Profil implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("planning.auth.connexion.max-echecs", "2",
                    "planning.auth.connexion.duree-blocage", "PT15M",
                    // The test client connects from the loopback, declared here
                    // as a block. Narrow it to one that excludes 127.0.0.1 and
                    // both assertions below fail: the header stops being read,
                    // and the two announced clients share the peer's counter.
                    "planning.auth.connexion.proxys-fiables", "127.0.0.0/8");
        }
    }

    /** The development default of {@code ADMIN_PASSWORD} — this is not a secret. */
    private static final String MOT_DE_PASSE_DEV = "admin";

    /**
     * The announced address is counted, and only it: the lock lands on the
     * client the trusted peer reported, and its neighbour still gets in.
     */
    @Test
    void unProxyDeclareParBlocFaitLireLEnTete() {
        String address = "203.0.113.40";
        login(address, "mauvais").then().statusCode(anyOf(is(401), is(302)));
        login(address, "mauvais").then().statusCode(anyOf(is(401), is(302)));

        login(address, MOT_DE_PASSE_DEV).then()
                .statusCode(429)
                .body("message", containsString("Trop de tentatives"));

        // The decisive half: with the block not matching, this second client
        // would be counted on the peer's address — the same counter, already
        // locked — and would answer 429 instead of a session.
        String cookie = login("203.0.113.41", MOT_DE_PASSE_DEV).then()
                .statusCode(anyOf(is(302), is(200)))
                .extract().cookie("planning-session");
        assertThat(cookie).isNotBlank();
    }

    private static Response login(String address, String password) {
        return given()
                .contentType("application/x-www-form-urlencoded")
                .header("X-Forwarded-For", address)
                .formParam("j_username", "admin")
                .formParam("j_password", password)
                .redirects().follow(false)
                .when().post("/j_security_check");
    }
}
