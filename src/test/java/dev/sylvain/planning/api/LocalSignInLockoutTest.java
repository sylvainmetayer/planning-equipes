package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The password step is counted by the lock of the form login: a run of failures shuts the address out. */
@QuarkusTest
@TestProfile(LocalSignInLockoutTest.Profil.class)
class LocalSignInLockoutTest {

    public static class Profil implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("planning.auth.connexion.max-echecs", "3");
        }
    }

    @Test
    void uneSerieDEchecsAuMotDePasseVerrouilleLAdresse() {
        for (int i = 0; i < 3; i++) {
            given().contentType("application/json")
                    .body(Map.of("email", "personne@example.org", "password", dev.sylvain.planning.TestPasswords.WRONG))
                    .post("/api/auth/connexion")
                    .then()
                    .statusCode(401);
        }
        given().contentType("application/json")
                .body(Map.of("email", "personne@example.org", "password", dev.sylvain.planning.TestPasswords.WRONG))
                .post("/api/auth/connexion")
                .then()
                .statusCode(429)
                .header("Retry-After", org.hamcrest.Matchers.notNullValue());
    }
}
