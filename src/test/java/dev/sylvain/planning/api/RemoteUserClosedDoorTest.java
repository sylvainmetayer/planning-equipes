package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The remote-user admin builds the principal {@code admin} like the form
 * login does: closing the local door ({@code ADMIN_SECOURS_ENABLED=false},
 * as behind an OIDC provider) must strip the form sessions only, not the
 * admin an access proxy asserts on this very request.
 */
@QuarkusTest
@TestProfile(RemoteUserClosedDoorTest.Profil.class)
class RemoteUserClosedDoorTest {

    static final String SECRET = "secret-de-test";
    static final String EMAIL_ADMIN = "patronne@exemple.fr";

    public static class Profil implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.http.auth.permission.admin-api.policy",
                    "role-admin",
                    "planning.auth.secours.enabled",
                    "false",
                    "planning.auth.remote-user.enabled",
                    "true",
                    "planning.auth.remote-user.secret",
                    SECRET,
                    "planning.auth.remote-user.admin-email",
                    EMAIL_ADMIN);
        }
    }

    @Test
    void theProxyAssertedAdminKeepsItsRoleWhileTheLocalDoorIsClosed() {
        given().header("X-Edition-Id", "E1")
                .header("Remote-Auth-Secret", SECRET)
                .header("Remote-Email", EMAIL_ADMIN)
                .when()
                .get("/api/constraints")
                .then()
                .statusCode(200);
        given().header("X-Edition-Id", "E1")
                .header("Remote-Auth-Secret", SECRET)
                .header("Remote-Email", EMAIL_ADMIN)
                .when()
                .get("/api/auth/me")
                .then()
                .statusCode(200)
                .body("nom", equalTo("admin"));
    }
}
