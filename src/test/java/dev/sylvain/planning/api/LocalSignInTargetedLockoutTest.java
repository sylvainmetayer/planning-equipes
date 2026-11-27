package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.sylvain.planning.TestPasswords;
import dev.sylvain.planning.service.compte.Compte;
import dev.sylvain.planning.service.compte.CompteAccesService;
import dev.sylvain.planning.service.compte.CompteService;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * A stranger's wrong passwords lock out their own address, not the account:
 * the owner still signs in from elsewhere. Many addresses at once still lock
 * the account, even to its right password.
 */
@QuarkusTest
@TestProfile(LocalSignInTargetedLockoutTest.Profil.class)
class LocalSignInTargetedLockoutTest {

    private static final int CEILING = 3;

    public static class Profil implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "planning.auth.connexion.max-echecs", String.valueOf(CEILING),
                    "planning.auth.local.seconde-etape", "false",
                    // The test client connects from the loopback: X-Forwarded-For names each caller.
                    "planning.auth.connexion.proxys-fiables", "127.0.0.1");
        }
    }

    @Inject
    CompteService comptes;

    @Inject
    CompteAccesService acces;

    @Inject
    MockMailbox mailbox;

    @Test
    void wrongPasswordsFromOneAddressDoNotLockTheOwnerOut() {
        Instant start = Instant.now();
        String email = account();

        for (int i = 0; i < CEILING; i++) {
            attempt("198.51.100.10", email, TestPasswords.WRONG).then().statusCode(401);
        }
        attempt("198.51.100.10", email, TestPasswords.WRONG).then().statusCode(429);
        attempt("198.51.100.10", email, TestPasswords.LONG).then().statusCode(429);

        attempt("198.51.100.20", email, TestPasswords.LONG).then().statusCode(200);

        // Each failure and the lockout are journalled, the refused attempts are not.
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(events("198.51.100.10", start))
                        .containsExactly("VERROUILLAGE", "ECHEC", "ECHEC", "ECHEC"));
    }

    @Test
    void wrongPasswordsFromManyAddressesStillLockTheAccount() {
        String email = account();

        for (int a = 0; a < CompteAccesService.ACCOUNT_FACTOR; a++) {
            for (int i = 0; i < CEILING; i++) {
                attempt("203.0.113." + a, email, TestPasswords.WRONG).then().statusCode(401);
            }
        }
        attempt("192.0.2.99", email, TestPasswords.LONG).then().statusCode(429);
    }

    private String account() {
        Compte compte = comptes.create("cible-" + UUID.randomUUID() + "@example.org", null);
        acces.sendLink(compte.id(), true);
        Matcher m = Pattern.compile("/mot-de-passe#(\\S+)")
                .matcher(mailbox.getMailsSentTo(compte.email()).get(0).getText());
        assertThat(m.find()).isTrue();
        acces.choose(m.group(1), TestPasswords.LONG);
        return compte.email();
    }

    /** The events journalled for {@code address} since {@code start}, newest first. */
    private static List<String> events(String address, Instant start) {
        List<Map<String, Object>> lines = given().header("X-Edition-Id", "E1")
                .queryParam("limite", 500)
                .get("/api/historique/connexions")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("$");
        return lines.stream()
                .filter(line -> address.equals(line.get("adresse")))
                .filter(line -> Instant.parse((String) line.get("survenuLe")).isAfter(start))
                .map(line -> (String) line.get("evenement"))
                .toList();
    }

    private static Response attempt(String address, String email, String password) {
        return given().contentType("application/json")
                .header("X-Forwarded-For", address)
                .body(Map.of("email", email, "password", password))
                .post("/api/auth/connexion");
    }
}
