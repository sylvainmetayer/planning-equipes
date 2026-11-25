package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;

import dev.sylvain.planning.OidcTests;
import dev.sylvain.planning.service.journal.LoginJournalEntry.Evenement;
import dev.sylvain.planning.service.journal.LoginJournalService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Behind a provider the break-glass door is closed by default (ADR 0093). */
@QuarkusTest
@TestProfile(OidcTests.Profil.class)
class FormLoginSecoursFermeTest {

    @Inject
    LoginJournalService journal;

    @Test
    void leFormulaireEstFermeParDefautEtUnPrefixeNeLeContournePas() {
        for (String chemin : new String[] {"/j_security_check", "/api/j_security_check"}) {
            given().contentType("application/x-www-form-urlencoded")
                    .formParam("j_username", "admin")
                    .formParam("j_password", "admin")
                    .redirects()
                    .follow(false)
                    .post(chemin)
                    .then()
                    .statusCode(409)
                    .header("Set-Cookie", nullValue())
                    .body("message", containsString("ADMIN_LOCAL_SECOURS"));
        }
    }

    @Test
    void sansSessionLApiRepond401() {
        given().get("/api/animateurs").then().statusCode(401);
    }

    @Test
    void uneTentativeSurLaPorteFermeeEstJournalisee() {
        int avant = journal.page(null, 500).size();
        given().contentType("application/x-www-form-urlencoded")
                .formParam("j_username", "admin")
                .formParam("j_password", java.util.UUID.randomUUID().toString())
                .post("/j_security_check")
                .then()
                .statusCode(409);
        await().atMost(Duration.ofSeconds(5))
                .until(() -> journal.page(null, 500).size() > avant);
        assertThat(journal.page(null, 1).get(0).evenement()).isEqualTo(Evenement.ECHEC);
    }
}
