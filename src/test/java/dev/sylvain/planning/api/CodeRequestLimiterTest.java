package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import dev.sylvain.planning.service.ReferenceDataService;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;

/**
 * Rate limit on the access-code requests: the link alone must not be enough to
 * trigger a loop of mails towards the animateur's inbox.
 *
 * <p>The profile lowers the ceiling to two requests so the test holds in a few
 * calls; it is the same counter as in production. Since that counter lives in
 * memory for the whole life of the application, every test has its own
 * animateur rather than a counter reset in between.</p>
 */
@QuarkusTest
@TestProfile(CodeRequestLimiterTest.Profil.class)
class CodeRequestLimiterTest {

    public static class Profil implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("planning.espace.code.max-demandes", "2",
                    "planning.espace.code.fenetre", "PT10M");
        }
    }

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 14);
    private static final String PLAFOND = "DEBIT-PLAFOND";
    private static final String CREDIT = "DEBIT-CREDIT";
    private static final String EMAIL_PLAFOND = "debit-plafond@example.org";
    private static final String EMAIL_CREDIT = "debit-credit@example.org";

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    ReferenceDataService referenceData;

    @Inject
    MockMailbox mailbox;

    @BeforeEach
    void seed() {
        mailbox.clear();
        Animateur plafond = new Animateur(PLAFOND, "Carla", "Roux", LocalDate.of(1991, 3, 3), false);
        Animateur credit = new Animateur(CREDIT, "Diego", "Lima", LocalDate.of(1989, 4, 4), false);
        Stand stand = new Stand("DEBIT-S1", "Stand débit", Set.of(), 1, 2, false);
        Creneau creneau = new Creneau(9601L, 1, JOUR, LocalTime.of(10, 0), LocalTime.of(12, 0));
        PosteAffectation premier = new PosteAffectation("DEBIT-P1", stand, creneau);
        premier.setAnimateur(plafond);
        PosteAffectation second = new PosteAffectation("DEBIT-P2", stand, creneau);
        second.setAnimateur(credit);
        persistence.persist(new PlanningEvenement(JOUR, List.of(plafond, credit), List.of(premier, second)));
        donnerEmail(PLAFOND, EMAIL_PLAFOND);
        donnerEmail(CREDIT, EMAIL_CREDIT);
    }

    @Test
    void auDelaDuPlafondLesDemandesDeCodeSontRefusees() {
        String token = tokenOf(PLAFOND);
        requestCode(token).then().statusCode(200);
        requestCode(token).then().statusCode(200);

        requestCode(token).then()
                .statusCode(429)
                .header("Retry-After", notNullValue())
                .body("message", containsString("Trop de codes"));

        // Not one more mail left: that is the whole point of the ceiling.
        assertThat(mailbox.getMailsSentTo(EMAIL_PLAFOND)).hasSize(2);
    }

    /**
     * What is counted are the codes never used: opening the session clears the
     * counter, otherwise an animateur logging in regularly would end up being
     * refused access to their own planning.
     */
    @Test
    void ouvrirLaSessionRendSonCreditAuCompteur() {
        String token = tokenOf(CREDIT);
        requestCode(token).then().statusCode(200);
        requestCode(token).then().statusCode(200);

        given().contentType(ContentType.JSON)
                .body("{\"code\":\"" + dernierCode(EMAIL_CREDIT) + "\"}")
                .when().post("/api/espace-animateur/" + token + "/session")
                .then().statusCode(204);

        requestCode(token).then().statusCode(200);
    }

    private static Response requestCode(String token) {
        return given().contentType(ContentType.JSON)
                .when().post("/api/espace-animateur/" + token + "/code");
    }

    private String dernierCode(String email) {
        List<Mail> mails = mailbox.getMailsSentTo(email);
        assertThat(mails).isNotEmpty();
        Matcher matcher = Pattern.compile("\\b(\\d{6})\\b").matcher(mails.get(mails.size() - 1).getText());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private void donnerEmail(String animateurId, String email) {
        Animateur fiche = recordOf(animateurId);
        fiche.setEmail(email);
        referenceData.updateAnimateur(animateurId, fiche);
    }

    private String tokenOf(String animateurId) {
        return recordOf(animateurId).getAccessToken();
    }

    private Animateur recordOf(String animateurId) {
        return referenceData.listAnimateurs().stream()
                .filter(candidat -> candidat.getId().equals(animateurId))
                .findFirst()
                .orElseThrow();
    }
}
