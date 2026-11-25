package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;

import dev.sylvain.planning.OidcTests;
import dev.sylvain.planning.service.journal.LoginJournalEntry.Evenement;
import dev.sylvain.planning.service.journal.LoginJournalService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The operator reopened the break-glass door: it works, and both the opening and the login leave a trace. */
@QuarkusTest
@TestProfile(FormLoginSecoursOuvertTest.Profil.class)
class FormLoginSecoursOuvertTest {

    public static class Profil extends OidcTests.Profil {
        public Profil() {
            super(Map.of("planning.auth.secours.enabled", "true"));
        }
    }

    @Inject
    LoginJournalService journal;

    @Test
    void rouvertLeSecoursOuvreUneSessionEtLeJournalLeDit() {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("j_username", "admin")
                .formParam("j_password", "admin")
                .redirects()
                .follow(false)
                .post("/j_security_check")
                .then()
                .statusCode(302);
        given().get("/api/config").then().body("authSecours", equalTo(true));
        // The journal write leaves the event loop for a worker.
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(journal.page(null, 50))
                        .extracting(e -> e.evenement())
                        .contains(Evenement.SECOURS_OUVERT, Evenement.CONNEXION_SECOURS));
    }
}
