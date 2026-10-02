package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
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

    @Inject
    DataSource dataSource;

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

    /**
     * Every outcome leaves a line in the instance's login journal — the
     * successful login, each failure, and the lockout once, on the failure
     * that reaches the ceiling — with its address, and nothing that was typed:
     * neither the password nor the username, which is often a password typed
     * in the wrong field. A request refused while locked tried nothing and
     * writes nothing.
     */
    @Test
    void everyOutcomeIsJournalledWithItsAddressAndNothingTyped() throws Exception {
        // The database outlives a run: only the lines written from now on count.
        Instant start = Instant.now();
        String address = "203.0.113.40";
        // Drawn at run time: what was typed must be recognisable in a column,
        // and a literal here would read as a committed secret to a scanner.
        String typedUser = "intrus-" + UUID.randomUUID();
        String typedPassword = UUID.randomUUID().toString();
        login(address, typedUser, typedPassword).then().statusCode(anyOf(is(401), is(302)));
        login(address, typedUser, typedPassword).then().statusCode(anyOf(is(401), is(302)));
        login(address, MOT_DE_PASSE_DEV).then().statusCode(429);
        String other = "203.0.113.41";
        login(other, MOT_DE_PASSE_DEV).then().statusCode(anyOf(is(302), is(200)));

        // Written off the event loop: the lines land a moment later.
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(events(other, start)).containsExactly("CONNEXION"));
        assertThat(events(address, start))
                .as("deux échecs, le verrouillage sur le second, rien pour la tentative refusée")
                .containsExactly("VERROUILLAGE", "ECHEC", "ECHEC");
        given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/historique/connexions")
                .then()
                .statusCode(200)
                .body("find { it.adresse == '" + other + "' }.evenement", is("CONNEXION"))
                .body("find { it.adresse == '" + other + "' }.survenuLe", notNullValue());

        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement("SELECT * FROM journal_connexion WHERE adresse = ?")) {
            ps.setString(1, address);
            try (ResultSet rs = ps.executeQuery()) {
                int columns = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    for (int i = 1; i <= columns; i++) {
                        assertThat(String.valueOf(rs.getObject(i)))
                                .as("colonne %s", rs.getMetaData().getColumnName(i))
                                .doesNotContain(typedUser)
                                .doesNotContain(typedPassword);
                    }
                }
            }
        }
    }

    /** The events journalled for {@code address} since {@code start}, newest first, as the screen reads them. */
    private static List<String> events(String address, Instant start) {
        List<Map<String, Object>> lines = given().header("X-Edition-Id", "E1")
                .queryParam("limite", 500)
                .when()
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

    private static Response login(String address, String password) {
        return login(address, "admin", password);
    }

    private static Response login(String address, String user, String password) {
        return given().header("X-Edition-Id", "E1")
                .contentType("application/x-www-form-urlencoded")
                .header("X-Forwarded-For", address)
                .formParam("j_username", user)
                .formParam("j_password", password)
                .redirects()
                .follow(false)
                .when()
                .post("/j_security_check");
    }
}
