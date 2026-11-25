package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.service.compte.Compte;
import dev.sylvain.planning.service.compte.CompteAccesService;
import dev.sylvain.planning.service.compte.CompteService;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * An attacker holding one account of their own must not reset the lock of an
 * address by signing in with it between two guesses at somebody else's.
 */
@QuarkusTest
@TestProfile(LocalSignInSprayTest.Profil.class)
class LocalSignInSprayTest {

    public static class Profil implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "planning.auth.connexion.max-echecs", "3",
                    "planning.auth.local.seconde-etape", "false");
        }
    }

    @Inject
    CompteService comptes;

    @Inject
    CompteAccesService acces;

    @Inject
    MockMailbox mailbox;

    private void fail(String email) {
        given().contentType("application/json")
                .body(Map.of("email", email, "password", dev.sylvain.planning.TestPasswords.WRONG))
                .post("/api/auth/connexion");
    }

    @Test
    void unSuccesLocalNeRemetPasLesEchecsAZero() {
        Compte own = comptes.create("own-" + UUID.randomUUID() + "@example.org", null);
        acces.sendLink(own.id(), true);
        Matcher m = Pattern.compile("/mot-de-passe#(\\S+)")
                .matcher(mailbox.getMailsSentTo(own.email()).get(0).getText());
        assertThat(m.find()).isTrue();
        acces.choose(m.group(1), dev.sylvain.planning.TestPasswords.LONG);

        fail("victime@example.org");
        fail("victime@example.org");
        String challenge = given().contentType("application/json")
                .body(Map.of("email", own.email(), "password", dev.sylvain.planning.TestPasswords.LONG))
                .post("/api/auth/connexion")
                .then()
                .statusCode(200)
                .extract()
                .path("challenge");
        given().contentType("application/x-www-form-urlencoded")
                .formParam("j_username", own.email())
                .formParam("j_password", challenge)
                .redirects()
                .follow(false)
                .post("/api/auth/connexion/j_security_check");
        fail("victime@example.org");

        given().contentType("application/json")
                .body(Map.of("email", "victime@example.org", "password", dev.sylvain.planning.TestPasswords.WRONG))
                .post("/api/auth/connexion")
                .then()
                .statusCode(429);
    }
}
