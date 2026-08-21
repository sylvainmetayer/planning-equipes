package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-of-solve notification, wired end to end: the setting is honoured by the
 * solve job itself, not by whichever screen happens to be open — the whole
 * point being to learn the outcome after walking away from the browser.
 */
@QuarkusTest
class MailFinResolutionTest {

    private static final int MAX_POLLS = 120;
    private static final long POLL_INTERVAL_MS = 250;
    /** Configured by `planning.mail.admin` in the test profile. */
    private static final String ADMIN = "admin@example.org";

    @Inject
    MockMailbox boite;

    @BeforeEach
    void videLaBoite() throws InterruptedException {
        boite.clear();
        attendreSolveurLibre();
    }

    /** Leaves the setting off, so no other test starts mailing on every solve. */
    @AfterEach
    void couperLaNotification() {
        reglerNotification(false);
    }

    @Test
    void unSolveTermineEcritALAdminQuandLEditionLeDemande() throws InterruptedException {
        planImporte();
        reglerNotification(true);

        JsonPath job = solve();

        assertThat(boite.getMailsSentTo(ADMIN)).hasSize(1);
        var mail = boite.getMailsSentTo(ADMIN).get(0);
        assertThat(mail.getSubject()).contains("résolution terminée");
        // The three facts asked for, and nothing else: edition, score, feasibility.
        assertThat(mail.getText())
                .contains("Édition : ")
                .contains("Score : " + job.getString("result.score"))
                .contains("Faisabilité : ");
        // Never nominative: this mail leaves the application unattended.
        assertThat(mail.getText()).doesNotContain("prenom");
    }

    @Test
    void sansLeReglageUnSolveNEcritRien() throws InterruptedException {
        planImporte();
        reglerNotification(false);

        solve();

        assertThat(boite.getMailsSentTo(ADMIN)).isEmpty();
    }

    /* ------------------------------- Helpers ------------------------------- */

    private void reglerNotification(boolean actif) {
        int duree = given().when().get("/api/parametres-solveur")
                .then().statusCode(200)
                .extract().jsonPath().getInt("dureeResolutionSecondes");
        given().contentType(ContentType.JSON)
                .body("{\"dureeResolutionSecondes\":" + duree + ",\"mailFinResolution\":" + actif + "}")
                .when().put("/api/parametres-solveur")
                .then().statusCode(200);
    }

    private void planImporte() {
        given().when().post("/api/planning/reset").then().statusCode(200);
        given().when().post("/api/reference-data/import-scenario?name=scenario.yml").then().statusCode(200);
    }

    private JsonPath solve() throws InterruptedException {
        attendreSolveurLibre();
        String jobId = given()
                .when().post("/api/solve/async/reference-data?seconds=1")
                .then().statusCode(202)
                .extract().path("id");
        JsonPath job = pollUntilFinished(jobId);
        assertThat(job.getString("status")).isEqualTo("COMPLETED");
        return job;
    }

    private void attendreSolveurLibre() throws InterruptedException {
        for (int i = 0; i < MAX_POLLS; i++) {
            if (given().when().get("/api/jobs/active").then().extract().statusCode() == 204) {
                return;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError("Solver still busy");
    }

    private JsonPath pollUntilFinished(String jobId) throws InterruptedException {
        for (int i = 0; i < MAX_POLLS; i++) {
            JsonPath job = given()
                    .when().get("/api/jobs/" + jobId)
                    .then().statusCode(200)
                    .extract().jsonPath();
            if (List.of("COMPLETED", "FAILED", "CANCELLED").contains(job.getString("status"))) {
                return job;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError("Job " + jobId + " did not finish in time");
    }
}
